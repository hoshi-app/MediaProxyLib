import Foundation

/// Общий буфер между загрузчиком и отдачей. Пишет либо в файл (кэш), либо в память (pass-through).
/// Читатель ждёт `written >= N` на condition, а не EOF файла: иначе получит обрезанное тело (P-11).
public final class ByteFeed {
    public let file: URL?
    private let condition = NSCondition()
    private var handle: FileHandle?
    private var writtenBytes: Int64 = 0
    private var totalBytes: Int64 = -1
    private var contentType: String?
    private var failure: ProxyErrorCode?
    private var finished = false
    private var finalUrl: String?
    private var etag: String?
    private var memory = Data()
    private var firstBytes = Data()
    private var metaReady = false

    /// - Parameter resumeFrom: сколько байт уже лежит на диске (докачка с префикса). `0` — с нуля.
    public init(file: URL?, resumeFrom: Int64 = 0) {
        self.file = file
        if let file {
            try? Fs.ensureDir(file.deletingLastPathComponent())
            if !FileManager.default.fileExists(atPath: file.path) {
                FileManager.default.createFile(atPath: file.path, contents: nil)
            }
            let size = Fs.size(file)
            let resume = min(max(0, resumeFrom), size)
            handle = try? FileHandle(forWritingTo: file)
            if resume > 0 {
                writtenBytes = resume
                _ = try? handle?.seekToEnd()
            } else {
                _ = try? handle?.truncate(atOffset: 0)
            }
        }
    }

    deinit {
        try? handle?.close()
    }

    public var written: Int64 {
        condition.lock(); defer { condition.unlock() }
        return writtenBytes
    }

    public var total: Int64 {
        condition.lock(); defer { condition.unlock() }
        return totalBytes
    }

    public var responseType: String? {
        condition.lock(); defer { condition.unlock() }
        return contentType
    }

    public var etagValue: String? {
        condition.lock(); defer { condition.unlock() }
        return etag
    }

    public var responseUrl: String? {
        condition.lock(); defer { condition.unlock() }
        return finalUrl
    }

    public var error: ProxyErrorCode? {
        condition.lock(); defer { condition.unlock() }
        return failure
    }

    public var isFinished: Bool {
        condition.lock(); defer { condition.unlock() }
        return finished
    }

    /// Первые байты — для детекта `#EXTM3U`/HTML/`0x47`.
    public var prefix: Data {
        condition.lock(); defer { condition.unlock() }
        return firstBytes
    }

    public func setMeta(total: Int64, contentType: String?, finalUrl: String?, etag: String?) {
        condition.lock()
        totalBytes = total
        self.contentType = contentType
        self.finalUrl = finalUrl
        self.etag = etag
        metaReady = true
        condition.broadcast()
        condition.unlock()
    }

    public func append(_ data: Data) throws {
        condition.lock()
        defer { condition.unlock() }
        guard failure == nil else { return }
        if firstBytes.count < 64 { firstBytes.append(data.prefix(64 - firstBytes.count)) }
        if let handle {
            try handle.write(contentsOf: data)
        } else {
            memory.append(data)
        }
        writtenBytes += Int64(data.count)
        condition.broadcast()
    }

    public func finish() {
        condition.lock()
        finished = true
        condition.broadcast()
        condition.unlock()
    }

    public func fail(_ code: ProxyErrorCode) {
        condition.lock()
        if failure == nil { failure = code }
        condition.broadcast()
        condition.unlock()
    }

    /// Ждёт метаданные ответа. Учитывает докачку: `written` может быть > 0 сразу (префикс на диске),
    /// и ждать «первый байт» в этом случае бессмысленно — нужен именно `total`.
    public func waitForMeta(timeout: TimeInterval) -> ProxyErrorCode? {
        let limit = Date().addingTimeInterval(max(0, timeout))
        condition.lock()
        defer { condition.unlock() }
        while !metaReady && failure == nil && !finished {
            if !condition.wait(until: limit) { return failure ?? .timeoutFirstByte }
        }
        if let failure { return failure }
        if !metaReady && finished && writtenBytes == 0 { return .emptyResponse }
        return nil
    }

    /// Ждёт первый байт/ошибку. `nil` = дождались, иначе код ошибки (включая таймаут).
    public func waitForFirstBytes(timeout: TimeInterval) -> ProxyErrorCode? {
        let limit = Date().addingTimeInterval(max(0, timeout))
        condition.lock()
        defer { condition.unlock() }
        while writtenBytes == 0 && failure == nil && !finished {
            if !condition.wait(until: limit) { return failure ?? .timeoutFirstByte }
        }
        if let failure { return failure }
        if finished && writtenBytes == 0 { return .emptyResponse }
        return nil
    }

    /// Ждёт, пока появится байт с офсетом `offset` (или терминальное состояние). Возвращает доступный префикс.
    public func waitFor(offset: Int64, timeout: TimeInterval) -> Int64 {
        let limit = Date().addingTimeInterval(max(0.001, timeout))
        condition.lock()
        defer { condition.unlock() }
        while writtenBytes <= offset && failure == nil && !finished {
            if !condition.wait(until: limit) { return writtenBytes }
        }
        return writtenBytes
    }

    public func waitForFinish(timeout: TimeInterval) -> Bool {
        let limit = Date().addingTimeInterval(max(0.001, timeout))
        condition.lock()
        defer { condition.unlock() }
        while !finished && failure == nil {
            if !condition.wait(until: limit) { return false }
        }
        return failure == nil
    }

    /// Pass-through: следующая порция из памяти. `nil` = поток завершён (без ошибки).
    public func readChunk(maxBytes: Int, timeout: TimeInterval) -> Data? {
        let limit = Date().addingTimeInterval(max(0.001, timeout))
        condition.lock()
        defer { condition.unlock() }
        while memory.isEmpty && failure == nil && !finished {
            if !condition.wait(until: limit) { return Data() }
        }
        if failure != nil { return nil }
        if memory.isEmpty { return nil }
        let count = min(maxBytes, memory.count)
        let chunk = memory.prefix(count)
        memory.removeFirst(count)
        return Data(chunk)
    }
}
