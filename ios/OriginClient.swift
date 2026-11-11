import Foundation

/// Сетевой слой origin: URLSession с потоковыми чанками, таймаутами «первый байт / тишина / пол скорости»
/// и валидацией ответа. Ретраи — не здесь: их считает движок (бюджет на сегмент, §6.3).
public final class OriginClient: NSObject, URLSessionDataDelegate {
    public struct Request {
        public let url: String
        public let headers: [String: String]
        public let rangeStart: Int64
        public let ifRange: String?
        public let profile: TimeoutProfile
        public let isPrefetch: Bool
        public let expectPlaylist: Bool
        public init(url: String, headers: [String: String], rangeStart: Int64 = 0, ifRange: String? = nil,
                    profile: TimeoutProfile, isPrefetch: Bool = false, expectPlaylist: Bool = false) {
            self.url = url
            self.headers = headers
            self.rangeStart = rangeStart
            self.ifRange = ifRange
            self.profile = profile
            self.isPrefetch = isPrefetch
            self.expectPlaylist = expectPlaylist
        }
    }

    public final class Call {
        let feed: ByteFeed
        let profile: TimeoutProfile
        let isPrefetch: Bool
        var task: URLSessionDataTask?
        var firstByteTimer: DispatchSourceTimer?
        var silenceTimer: DispatchSourceTimer?
        var speedTimer: DispatchSourceTimer?
        var bytesInWindow: Int64 = 0
        var windowStart = Date()
        var receivedAny = false
        var finished = false
        var completion: ((ProxyErrorCode?) -> Void)?
        init(feed: ByteFeed, profile: TimeoutProfile, isPrefetch: Bool) {
            self.feed = feed
            self.profile = profile
            self.isPrefetch = isPrefetch
        }
    }

    private let queue = DispatchQueue(label: "tv.mediaproxy.origin", attributes: .concurrent)
    private let lock = NSLock()
    private var calls: [Int: Call] = [:]
    private var session: URLSession!

    public override init() {
        super.init()
        let cfg = URLSessionConfiguration.ephemeral
        cfg.httpShouldSetCookies = false
        cfg.httpCookieStorage = nil
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        cfg.urlCache = nil
        cfg.httpMaximumConnectionsPerHost = 4
        cfg.timeoutIntervalForRequest = 30
        cfg.timeoutIntervalForResource = 300
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
    }

    public func shutdown() {
        session.invalidateAndCancel()
    }

    // MARK: - segment / resource streaming

    @discardableResult
    public func open(_ request: Request, feed: ByteFeed, completion: @escaping (ProxyErrorCode?) -> Void) -> Call {
        let call = Call(feed: feed, profile: request.profile, isPrefetch: request.isPrefetch)
        call.completion = completion

        var req = URLRequest(url: URL(string: request.url)!)
        req.httpMethod = "GET"
        req.cachePolicy = .reloadIgnoringLocalCacheData
        for (key, value) in request.headers {
            let lowered = key.lowercased()
            if lowered == "host" || lowered == "range" || lowered == "connection"
                || lowered == "content-length" || lowered == "accept-encoding" { continue }
            req.setValue(value, forHTTPHeaderField: key)
        }
        req.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        if request.rangeStart > 0 {
            req.setValue("bytes=\(request.rangeStart)-", forHTTPHeaderField: "Range")
        }
        if let ifRange = request.ifRange { req.setValue(ifRange, forHTTPHeaderField: "If-Range") }

        let task = session.dataTask(with: req)
        call.task = task
        lock.lock()
        calls[task.taskIdentifier] = call
        lock.unlock()

        startFirstByteTimer(call)
        task.resume()
        return call
    }

    public func cancel(_ call: Call) {
        call.task?.cancel()
        finish(call, error: .connectFailed)
    }

    // MARK: - playlist (bounded, синхронно)

    public func fetchPlaylist(url: String, headers: [String: String], profile: TimeoutProfile,
                              maxBytes: Int) -> (data: Data?, finalUrl: String?, contentType: String?, error: ProxyErrorCode?) {
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = "GET"
        req.cachePolicy = .reloadIgnoringLocalCacheData
        req.timeoutInterval = max(1, profile.firstByteMs / 1000)
        for (key, value) in headers {
            let lowered = key.lowercased()
            if lowered == "host" || lowered == "range" || lowered == "connection"
                || lowered == "content-length" || lowered == "accept-encoding" { continue }
            req.setValue(value, forHTTPHeaderField: key)
        }
        req.setValue("identity", forHTTPHeaderField: "Accept-Encoding")

        let semaphore = DispatchSemaphore(value: 0)
        var result: (Data?, String?, String?, ProxyErrorCode?) = (nil, nil, nil, .connectFailed)
        let task = session.dataTask(with: req) { data, response, error in
            defer { semaphore.signal() }
            if let error {
                result = (nil, nil, nil, OriginClient.map(error))
                return
            }
            guard let http = response as? HTTPURLResponse else {
                result = (nil, nil, nil, .connectFailed); return
            }
            guard http.statusCode == 200 || http.statusCode == 206 else {
                result = (nil, http.url?.absoluteString, http.value(forHTTPHeaderField: "Content-Type"),
                          OriginClient.code(for: http.statusCode)); return
            }
            guard let data else { result = (nil, nil, nil, .emptyResponse); return }
            if data.count > maxBytes { result = (nil, nil, nil, .badContent); return }
            result = (data, http.url?.absoluteString, http.value(forHTTPHeaderField: "Content-Type"), nil)
        }
        task.resume()
        _ = semaphore.wait(timeout: .now() + max(1, profile.firstByteMs / 1000 + 60))
        return result
    }

    // MARK: - URLSessionDataDelegate

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                           didReceive response: URLResponse,
                           completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let call = callFor(dataTask) else { completionHandler(.cancel); return }
        guard let http = response as? HTTPURLResponse else {
            finish(call, error: .connectFailed); completionHandler(.cancel); return
        }
        let contentType = http.value(forHTTPHeaderField: "Content-Type")
        let loweredType = contentType?.lowercased() ?? ""
        if loweredType.contains("text/html") {
            finish(call, error: .htmlResponse); completionHandler(.cancel); return
        }
        guard http.statusCode == 200 || http.statusCode == 206 else {
            finish(call, error: OriginClient.code(for: http.statusCode))
            completionHandler(.cancel)
            return
        }
        // 200 в ответ на Range: origin проигнорировал диапазон — пусть движок решит (сброс/повтор с 0).
        if http.statusCode == 200, let rangeHeader = call.task?.originalRequest?.value(forHTTPHeaderField: "Range"),
           rangeHeader.hasPrefix("bytes="), !rangeHeader.hasPrefix("bytes=0-") {
            finish(call, error: .rangeUnsupported)
            completionHandler(.cancel)
            return
        }

        var total = http.expectedContentLength
        if http.statusCode == 206, let cr = http.value(forHTTPHeaderField: "Content-Range"),
           let slash = cr.lastIndex(of: "/"), let t = Int64(cr[cr.index(after: slash)...].trimmingCharacters(in: .whitespaces)) {
            total = t
        }
        if total < 0 { total = -1 }
        call.feed.setMeta(total: total, contentType: contentType,
                          finalUrl: http.url?.absoluteString,
                          etag: http.value(forHTTPHeaderField: "ETag"))
        completionHandler(.allow)
    }

    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let call = callFor(dataTask) else { return }
        if !call.receivedAny {
            call.receivedAny = true
            call.firstByteTimer?.cancel()
            startSilenceTimer(call)
            if call.profile.minSpeedBps > 0 { startSpeedTimer(call) }
        }
        do {
            try call.feed.append(data)
        } catch {
            finish(call, error: .diskIO)
            dataTask.cancel()
            return
        }
        call.bytesInWindow += Int64(data.count)
        startSilenceTimer(call)
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let call = callFor(task) else { return }
        guard !call.finished else { return }
        let written = call.feed.written
        if let error {
            var code = OriginClient.map(error)
            // Обрыв после первых байт — недокачанный ответ: движок ретраит с префикса.
            if written > 0 && code == .connectFailed { code = .truncated }
            finish(call, error: code)
            return
        }
        let total = call.feed.total
        if total >= 0 && written != total {
            finish(call, error: .truncated)
            return
        }
        call.feed.finish()
        finish(call, error: nil)
    }

    // MARK: - timers

    private func startFirstByteTimer(_ call: Call) {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(Int(call.profile.firstByteMs)))
        timer.setEventHandler { [weak self] in
            guard let self, !call.receivedAny, !call.finished else { return }
            self.finish(call, error: .timeoutFirstByte)
            call.task?.cancel()
        }
        timer.resume()
        call.firstByteTimer = timer
    }

    private func startSilenceTimer(_ call: Call) {
        call.silenceTimer?.cancel()
        guard call.profile.silenceMs > 0 else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(Int(call.profile.silenceMs)))
        timer.setEventHandler { [weak self] in
            guard let self, !call.finished else { return }
            self.finish(call, error: .timeoutSilence)
            call.task?.cancel()
        }
        timer.resume()
        call.silenceTimer = timer
    }

    private func startSpeedTimer(_ call: Call) {
        call.windowStart = Date()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + .milliseconds(Int(call.profile.speedWindowMs)),
                       repeating: .milliseconds(Int(call.profile.speedWindowMs)))
        timer.setEventHandler { [weak self] in
            guard let self, !call.finished else { return }
            let elapsed = Date().timeIntervalSince(call.windowStart)
            let bps = elapsed > 0 ? Double(call.bytesInWindow) / elapsed : 0
            if bps < Double(call.profile.minSpeedBps) {
                self.finish(call, error: .slowStream)
                call.task?.cancel()
            }
            call.bytesInWindow = 0
            call.windowStart = Date()
        }
        timer.resume()
        call.speedTimer = timer
    }

    // MARK: - helpers

    private func callFor(_ task: URLSessionTask) -> Call? {
        lock.lock(); defer { lock.unlock() }
        return calls[task.taskIdentifier]
    }

    private func finish(_ call: Call, error: ProxyErrorCode?) {
        lock.lock()
        guard !call.finished else { lock.unlock(); return }
        call.finished = true
        lock.unlock()
        call.firstByteTimer?.cancel()
        call.silenceTimer?.cancel()
        call.speedTimer?.cancel()
        if let error { call.feed.fail(error) } else { call.feed.finish() }
        call.completion?(error)
        if let task = call.task {
            lock.lock(); calls.removeValue(forKey: task.taskIdentifier); lock.unlock()
        }
    }

    public static func code(for status: Int) -> ProxyErrorCode {
        switch status {
        case 400: return .origin400
        case 401: return .origin401
        case 403: return .origin403
        case 404: return .origin404
        case 410: return .origin410
        case 429: return .origin429
        case 400...499: return .origin400
        default: return .origin5xx
        }
    }

    public static func map(_ error: Error) -> ProxyErrorCode {
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain {
            switch ns.code {
            case NSURLErrorTimedOut: return .timeoutSilence
            case NSURLErrorCancelled: return .connectFailed
            case NSURLErrorNetworkConnectionLost: return .truncated
            case NSURLErrorCannotConnectToHost, NSURLErrorCannotFindHost, NSURLErrorNotConnectedToInternet,
                 NSURLErrorDNSLookupFailed, NSURLErrorSecureConnectionFailed:
                return .connectFailed
            default: return .connectFailed
            }
        }
        return .connectFailed
    }
}
