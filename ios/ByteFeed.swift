import Foundation

/// Shared buffer between the origin loader and the HTTP response writer.
/// Data goes to a file for cache or to memory for pass-through.
/// Readers wait for `written >= N` rather than EOF, because the file is still growing.
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

    /// `resumeFrom` is how many bytes already exist on disk for prefix resume; `0` starts from scratch.
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

    /// First bytes of the body, used to detect `#EXTM3U`, HTML, or a `0x47` TS sync byte.
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

    /// Waits for the response metadata. On resume, `written` is already positive, so wait for `total`
    /// rather than for the first byte.
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

    /// Waits for the first byte or a failure. Returns `nil` on success,
    /// otherwise the error code, including timeout.
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

    /// Waits until byte `offset` arrives or the feed reaches a terminal state. Returns the available prefix.
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

    /// Pass-through: returns the next chunk from memory. `nil` means the stream ended without an error.
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
