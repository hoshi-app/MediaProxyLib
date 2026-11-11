import Foundation
import Darwin

/// Тело ответа: умеет писать только нужный диапазон (Range) в сокет.
public protocol HttpResponseBody {
    /// `count < 0` — до конца.
    func write(to fd: Int32, offset: Int64, count: Int64, deadline: TimeInterval) throws
}

public struct HttpReply {
    public var status: Int
    public var headers: [String: String]
    public var body: HttpResponseBody?
    public var length: Int64          // -1 = close-delimited
    public var cacheState: String?    // hit | miss | partial | passthrough (для X-MediaProxy-Cache)
    /// true — Range уже обработан (pass-through с прокинутым Range), сервер не применяет свой.
    public var rangeHandled: Bool

    public init(status: Int, headers: [String: String] = [:], body: HttpResponseBody? = nil, length: Int64 = 0,
                cacheState: String? = nil, rangeHandled: Bool = false) {
        self.status = status
        self.headers = headers
        self.body = body
        self.length = length
        self.cacheState = cacheState
        self.rangeHandled = rangeHandled
    }

    public static func text(_ status: Int, _ text: String, cacheState: String? = nil) -> HttpReply {
        let data = Data(text.utf8)
        return HttpReply(status: status, headers: ["Content-Type": "text/plain; charset=utf-8"],
                         body: BytesBody(data), length: Int64(data.count), cacheState: cacheState)
    }
}

public struct BytesBody: HttpResponseBody {
    let data: Data
    public init(_ data: Data) { self.data = data }
    public func write(to fd: Int32, offset: Int64, count: Int64, deadline: TimeInterval) throws {
        let start = max(0, offset)
        guard start < Int64(data.count) else { return }
        let end = count < 0 ? Int64(data.count) : min(Int64(data.count), start + count)
        var slice = data.subdata(in: Int(start)..<Int(end))
        try slice.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return }
            var written = 0
            while written < slice.count {
                let n = Darwin.send(fd, base.advanced(by: written), slice.count - written, 0)
                if n <= 0 { throw POSIXError(.EPIPE) }
                written += n
            }
        }
    }
}

public struct FileBody: HttpResponseBody {
    let url: URL
    public init(url: URL) { self.url = url }
    public func write(to fd: Int32, offset: Int64, count: Int64, deadline: TimeInterval) throws {
        guard let handle = try? FileHandle(forReadingFrom: url) else { throw POSIXError(.ENOENT) }
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(max(0, offset)))
        var remaining = count
        while remaining != 0 {
            let want = remaining < 0 ? 64 * 1024 : min(64 * 1024, remaining)
            guard let chunk = try handle.read(upToCount: Int(want)), !chunk.isEmpty else { break }
            try sendAll(fd, chunk)
            if remaining > 0 { remaining -= Int64(chunk.count) }
        }
        if remaining > 0 { throw POSIXError(.EIO) }  // недодали обещанное — рвём соединение
    }
}

/// Отдача сегмента, который ещё качается: читает файл по мере роста, ждёт на feed.
public struct GrowingFileBody: HttpResponseBody {
    let url: URL
    let feed: ByteFeed
    public init(url: URL, feed: ByteFeed) { self.url = url; self.feed = feed }
    public func write(to fd: Int32, offset: Int64, count: Int64, deadline: TimeInterval) throws {
        guard let handle = try? FileHandle(forReadingFrom: url) else { throw POSIXError(.ENOENT) }
        defer { try? handle.close() }
        var position = max(0, offset)
        var remaining = count
        while remaining != 0 {
            let available = feed.waitFor(offset: position, timeout: max(0.1, deadline - Date().timeIntervalSince1970))
            if available <= position {
                if let err = feed.error { throw POSIXError(err == .timeoutSilence ? .ETIMEDOUT : .ECONNRESET) }
                if feed.isFinished { break }
                continue
            }
            let want = remaining < 0 ? available - position : min(available - position, remaining)
            try handle.seek(toOffset: UInt64(position))
            guard let chunk = try handle.read(upToCount: Int(want)), !chunk.isEmpty else { break }
            try sendAll(fd, chunk)
            position += Int64(chunk.count)
            if remaining > 0 { remaining -= Int64(chunk.count) }
        }
        if remaining > 0 {
            // Дошли до обрыва: не закрываем ответ «нормально», рвём сокет (плеер переспросит Range).
            throw POSIXError(.ECONNRESET)
        }
    }
}

/// Pass-through: отдаём по мере прихода из памяти (без диска).
public struct StreamBody: HttpResponseBody {
    let feed: ByteFeed
    public init(feed: ByteFeed) { self.feed = feed }
    public func write(to fd: Int32, offset: Int64, count: Int64, deadline: TimeInterval) throws {
        while true {
            guard let chunk = feed.readChunk(maxBytes: 64 * 1024, timeout: max(0.1, deadline - Date().timeIntervalSince1970)) else {
                return  // завершено
            }
            if chunk.isEmpty {
                if feed.isFinished { return }
                continue
            }
            try sendAll(fd, chunk)
        }
    }
}

private func sendAll(_ fd: Int32, _ data: Data) throws {
    try data.withUnsafeBytes { raw in
        guard let base = raw.baseAddress else { return }
        var sent = 0
        while sent < data.count {
            let n = Darwin.send(fd, base.advanced(by: sent), data.count - sent, 0)
            if n <= 0 { throw POSIXError(.EPIPE) }
            sent += n
        }
    }
}

/// Минимальный HTTP/1.1 на loopback: GET/HEAD, keep-alive, всегда Content-Length (или close-delimited),
/// Range/206/416, поток на соединение, лимит соединений. Без chunked — нам он не нужен.
public final class HttpServer {
    public typealias Handler = (_ method: String, _ path: String, _ range: String?, _ host: String?) -> HttpReply

    private let host: String
    private var port: Int
    private let handler: Handler
    private let maxConnections: Int
    private let idleTimeout: TimeInterval
    private let lock = NSLock()
    private var listenFD: Int32 = -1
    private var acceptThread: Thread?
    private var running = false
    private var active = 0
    private let semaphore: DispatchSemaphore

    public private(set) var boundPort: Int = 0

    public init(host: String = "127.0.0.1", port: Int = 0, maxConnections: Int = 32,
                idleTimeout: TimeInterval = 60, handler: @escaping Handler) {
        self.host = host
        self.port = port
        self.handler = handler
        self.maxConnections = maxConnections
        self.idleTimeout = idleTimeout
        self.semaphore = DispatchSemaphore(value: maxConnections)
    }

    public func start() throws {
        let fd = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw POSIXError(.EADDRNOTAVAIL) }
        var one: Int32 = 1
        Darwin.setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, socklen_t(MemoryLayout<Int32>.size))
        Darwin.setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(UInt16(port).bigEndian)
        addr.sin_addr.s_addr = inet_addr(host)

        var bindAddr = addr
        let bindResult = withUnsafePointer(to: &bindAddr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else { Darwin.close(fd); throw POSIXError(.EADDRINUSE) }
        guard Darwin.listen(fd, 64) == 0 else { Darwin.close(fd); throw POSIXError(.EADDRINUSE) }

        var bound = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        withUnsafeMutablePointer(to: &bound) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.getsockname(fd, $0, &len) }
        }
        boundPort = Int(UInt16(bigEndian: bound.sin_port))
        port = boundPort
        listenFD = fd
        running = true

        let thread = Thread { [weak self] in self?.acceptLoop() }
        thread.name = "mediaproxy.http.accept"
        acceptThread = thread
        thread.start()
        Log.line("http", "слушаю 127.0.0.1:\(boundPort)")
    }

    public func stop() {
        lock.lock()
        running = false
        let fd = listenFD
        listenFD = -1
        lock.unlock()
        if fd >= 0 { Darwin.shutdown(fd, Int32(SHUT_RDWR)); Darwin.close(fd) }
    }

    private func acceptLoop() {
        while running {
            var client = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let clientFD = withUnsafeMutablePointer(to: &client) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.accept(listenFD, $0, &len) }
            }
            if clientFD < 0 {
                if !running { return }
                continue
            }
            if semaphore.wait(timeout: .now()) == .timedOut {
                // Слишком много соединений — отвечаем 503 и закрываем, а не рвём молча.
                _ = try? HttpReply.text(503, "Too many connections").body?.write(
                    to: clientFD, offset: 0, count: -1, deadline: Date().timeIntervalSince1970 + 2)
                Darwin.close(clientFD)
                continue
            }
            let thread = Thread { [weak self] in
                defer { Darwin.close(clientFD); self?.semaphore.signal() }
                self?.serve(clientFD)
            }
            thread.name = "mediaproxy.http.conn"
            thread.start()
        }
    }

    private func serve(_ fd: Int32) {
        var one: Int32 = 1
        Darwin.setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: Int(idleTimeout), tv_usec: 0)
        Darwin.setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var buffer = [UInt8](repeating: 0, count: 16 * 1024)
        var keepAlive = true
        while keepAlive {
            guard let request = readRequest(fd, &buffer) else { return }
            keepAlive = request.keepAlive
            let reply = handler(request.method, request.path, request.range, request.host)
            if !writeReply(fd, request: request, reply: reply) { return }
            if reply.length < 0 { return }  // close-delimited
        }
    }

    private struct Request {
        let method: String
        let path: String
        let range: String?
        let host: String?
        let keepAlive: Bool
    }

    private func readRequest(_ fd: Int32, _ buffer: inout [UInt8]) -> Request? {
        var data = Data()
        var headerEnd = -1
        while headerEnd < 0 {
            let n = Darwin.recv(fd, &buffer, buffer.count, 0)
            if n <= 0 { return nil }
            data.append(contentsOf: buffer[0..<n])
            if data.count > 16 * 1024 { return nil }
            if let range = data.range(of: Data("\r\n\r\n".utf8)) {
                headerEnd = data.distance(from: data.startIndex, to: range.lowerBound)
            } else if let range = data.range(of: Data("\n\n".utf8)) {
                headerEnd = data.distance(from: data.startIndex, to: range.lowerBound)
            }
        }
        guard let text = String(data: data.prefix(headerEnd), encoding: .utf8) else { return nil }
        var lines = text.components(separatedBy: "\r\n")
        if lines.count == 1 { lines = text.components(separatedBy: "\n") }
        guard let requestLine = lines.first else { return nil }
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else { return nil }
        let method = String(parts[0]).uppercased()
        if method != "GET" && method != "HEAD" { return nil }
        var path = String(parts[1])
        if let q = path.firstIndex(of: "?") { path = String(path[..<q]) }

        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            headers[key] = value
        }
        let connection = headers["connection"]?.lowercased() ?? ""
        let keepAlive = connection != "close"
        return Request(method: method, path: path, range: headers["range"], host: headers["host"], keepAlive: keepAlive)
    }

    private func writeReply(_ fd: Int32, request: Request, reply: HttpReply) -> Bool {
        var status = reply.status
        var length = reply.length
        var headers = reply.headers
        var offset: Int64 = 0
        var count: Int64 = length

        if !reply.rangeHandled, length >= 0, let parsed = Http.parseRange(request.range, total: length) {
            status = 206
            offset = parsed.start
            count = parsed.end - parsed.start + 1
            headers["Content-Range"] = "bytes \(parsed.start)-\(parsed.end)/\(length)"
            length = count
        } else if !reply.rangeHandled, request.range != nil, length >= 0,
                  Http.parseRange(request.range, total: length) == nil,
                  let raw = request.range, raw.lowercased().hasPrefix("bytes=") {
            // Диапазон запрошен, но невалиден — 416.
            let head = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */\(length)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            _ = try? sendAllData(fd, Data(head.utf8))
            return false
        }

        headers["Accept-Ranges"] = length >= 0 ? "bytes" : "none"
        headers["Connection"] = request.keepAlive ? "keep-alive" : "close"
        headers["Content-Length"] = length >= 0 ? String(length) : ""
        if let state = reply.cacheState, headers["X-MediaProxy-Cache"] == nil {
            headers["X-MediaProxy-Cache"] = state
        }

        var head = "HTTP/1.1 \(status) \(Http.statusText[status] ?? "Unknown")\r\n"
        for (key, value) in headers where !value.isEmpty {
            head += "\(key): \(value)\r\n"
        }
        head += "\r\n"
        guard (try? sendAllData(fd, Data(head.utf8))) != nil else { return false }

        if request.method == "HEAD" || reply.body == nil { return true }
        let deadline = Date().timeIntervalSince1970 + 60
        do {
            try reply.body?.write(to: fd, offset: offset, count: count, deadline: deadline)
            return true
        } catch {
            // Абортивное закрытие: SOCLINGER 0 → RST, чтобы плеер понял «тело оборвано».
            var linger = Darwin.linger(l_onoff: 1, l_linger: 0)
            Darwin.setsockopt(fd, SOL_SOCKET, SO_LINGER, &linger, socklen_t(MemoryLayout<Darwin.linger>.size))
            return false
        }
    }

    private func sendAllData(_ fd: Int32, _ data: Data) throws {
        try data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return }
            var sent = 0
            while sent < data.count {
                let n = Darwin.send(fd, base.advanced(by: sent), data.count - sent, 0)
                if n <= 0 { throw POSIXError(.EPIPE) }
                sent += n
            }
        }
    }
}
