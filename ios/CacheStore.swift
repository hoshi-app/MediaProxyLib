import Foundation

/// Дисковый кэш сегментов: append-only журнал, сверка с файлами при старте, LRU целыми стримами,
/// refcount для отложенного удаления. Никаких tmp→rename на каждом сегменте (класс ошибок B-01).
public final class CacheStore {
    public struct Entry {
        public var index: Int
        public var total: Int64        // -1 = неизвестно
        public var prefix: Int64       // непрерывно записанный префикс
        public var complete: Bool
        public var failedUntil: TimeInterval = 0
        public var contentType: String?
        /// ETag ответа origin: нужен для `If-Range` при докачке с префикса.
        public var etag: String? = nil
        public var downloading: Bool = false
        public var feed: ByteFeed? = nil
        public var file: URL
        public var lastAccess: TimeInterval = 0
    }

    public final class Stream {
        public let key: String
        public let cacheKey: String
        public let fingerprint: String
        public let dir: URL
        public var entries: [Int: Entry] = [:]
        public var lastAccess: TimeInterval
        public var refs: Int = 0
        public var quarantineUntil: TimeInterval = 0
        public var pendingDelete = false

        init(key: String, cacheKey: String, fingerprint: String, dir: URL, lastAccess: TimeInterval) {
            self.key = key
            self.cacheKey = cacheKey
            self.fingerprint = fingerprint
            self.dir = dir
            self.lastAccess = lastAccess
        }
    }

    private let root: URL
    private let lock = NSRecursiveLock()
    private var streams: [String: Stream] = [:]
    private var evictedStreams: Int64 = 0
    private let config: () -> ProxyConfig
    private let diskFree: () -> Int64

    public init(root: URL, config: @escaping () -> ProxyConfig, diskFree: @escaping () -> Int64) {
        self.root = root
        self.config = config
        self.diskFree = diskFree
        try? Fs.ensureDir(root)
    }

    // MARK: - streams

    public func openStream(cacheKey: String, fingerprint: String, now: TimeInterval) -> Stream {
        lock.lock(); defer { lock.unlock() }
        let key = Ids.cartesianKey(cacheKey)
        if let existing = streams[key] {
            if existing.fingerprint == fingerprint {
                existing.lastAccess = now
                return existing
            }
            // Плейлист изменился — старый каталог невалиден и вытесняется первым.
            streams.removeValue(forKey: key)
            Fs.removeIfExists(existing.dir)
        }
        let dir = root.appendingPathComponent(key, isDirectory: true)
        try? Fs.ensureDir(dir)
        let stream = Stream(key: key, cacheKey: cacheKey, fingerprint: fingerprint, dir: dir, lastAccess: now)
        load(stream)
        streams[key] = stream
        return stream
    }

    public func stream(cacheKey: String) -> Stream? {
        lock.lock(); defer { lock.unlock() }
        return streams[Ids.cartesianKey(cacheKey)]
    }

    public func touch(_ stream: Stream, now: TimeInterval) {
        lock.lock(); defer { lock.unlock() }
        stream.lastAccess = now
        for i in stream.entries.keys {
            stream.entries[i]?.lastAccess = now
        }
    }

    public func retain(_ stream: Stream) {
        lock.lock(); defer { lock.unlock() }
        stream.refs += 1
    }

    public func release(_ stream: Stream) {
        lock.lock(); defer { lock.unlock() }
        stream.refs = max(0, stream.refs - 1)
        if stream.refs == 0 && stream.pendingDelete {
            Fs.removeIfExists(stream.dir)
            streams.removeValue(forKey: stream.key)
        }
    }

    // MARK: - entries

    public func entry(_ stream: Stream, index: Int) -> Entry? {
        lock.lock(); defer { lock.unlock() }
        return stream.entries[index]
    }

    @discardableResult
    public func ensureEntry(_ stream: Stream, index: Int, now: TimeInterval) -> Entry {
        lock.lock(); defer { lock.unlock() }
        if var e = stream.entries[index] { return e }
        var e = Entry(index: index, total: -1, prefix: 0, complete: false,
                      file: fileURL(stream, index), lastAccess: now)
        stream.entries[index] = e
        e = stream.entries[index]!
        journal(stream, entry: e)
        return e
    }

    public func update(_ stream: Stream, index: Int, mutate: (inout Entry) -> Void) {
        lock.lock(); defer { lock.unlock() }
        guard var e = stream.entries[index] else { return }
        mutate(&e)
        stream.entries[index] = e
        journal(stream, entry: e)
    }

    public func fileURL(_ stream: Stream, _ index: Int) -> URL {
        stream.dir.appendingPathComponent(String(format: "s%05d.bin", index))
    }

    public func totalBytes() -> Int64 {
        lock.lock(); defer { lock.unlock() }
        return streams.values.reduce(0) { acc, s in acc + streamBytesLocked(s) }
    }

    public func streamBytes(_ stream: Stream) -> Int64 {
        lock.lock(); defer { lock.unlock() }
        return streamBytesLocked(stream)
    }

    private func streamBytesLocked(_ stream: Stream) -> Int64 {
        stream.entries.values.reduce(0) { $0 + max(0, $1.prefix) }
    }

    // MARK: - eviction

    /// Освобождает место под `required` байт. Возвращает false, если места нет (тогда — pass-through).
    public func evictIfNeeded(required: Int64, protecting: Set<String>, now: TimeInterval) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let cfg = config()
        guard cfg.maxCacheBytes > 0 else { return false }
        if diskFree() < cfg.minFreeDiskBytes { return false }
        let softLimit = cfg.maxCacheBytes * 9 / 10
        if totalBytesLocked() + required <= softLimit { return true }

        let candidates = streams.values
            .filter { !protecting.contains($0.cacheKey) && $0.refs == 0 && $0.quarantineUntil < now }
            .sorted { $0.lastAccess < $1.lastAccess }
        for candidate in candidates {
            if totalBytesLocked() + required <= softLimit { return true }
            candidate.pendingDelete = true
            streams.removeValue(forKey: candidate.key)
            Fs.removeIfExists(candidate.dir)
            evictedStreams += 1
        }
        return totalBytesLocked() + required <= cfg.maxCacheBytes
    }

    /// Сколько стримов вытеснено с момента старта — для `getStats().evictions`.
    public func evictedCount() -> Int64 {
        lock.lock(); defer { lock.unlock() }
        return evictedStreams
    }

    private func totalBytesLocked() -> Int64 {
        streams.values.reduce(0) { $0 + streamBytesLocked($1) }
    }

    // MARK: - clear

    public func clear(cacheKey: String? = nil) -> Int64 {
        lock.lock(); defer { lock.unlock() }
        var freed: Int64 = 0
        let targets: [Stream]
        if let cacheKey {
            if let s = streams[Ids.cartesianKey(cacheKey)] { targets = [s] } else { targets = [] }
        } else {
            targets = Array(streams.values)
        }
        for s in targets {
            freed += streamBytesLocked(s)
            // Записи чистим всегда: иначе после clear сервер отдавал бы «complete»-сегменты,
            // файлов которых уже нет (FileBody падает, а плеер видит обрыв).
            s.entries.removeAll()
            streams.removeValue(forKey: s.key)
            Fs.removeIfExists(s.dir)
        }
        return freed
    }

    // MARK: - reconciliation

    private func load(_ stream: Stream) {
        let journalURL = stream.dir.appendingPathComponent("journal.log")
        let lines = Fs.readLines(journalURL)
        // Шапка — единственный способ после рестарта процесса отличить «тот же контент» от другого
        // плейлиста с тем же cacheKey (PROTOCOL §3). Формат H|2: отпечаток ПЕРЕД cacheKey, потому
        // что cacheKey приложения содержит `|` (`t1|p-x|e2|q720`) и разбирать его на части нельзя.
        let header = lines.last { $0.hasPrefix("H|") }
        let hasSegments = lines.contains { $0.hasPrefix("S|") }
        if hasSegments && !headerMatches(header, stream: stream) {
            wipe(stream)
            return
        }
        // Append-only журнал: последняя запись по индексу побеждает, сверка с файлом — один раз на запись.
        var latest: [Int: Entry] = [:]
        for line in lines {
            let parts = line.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
            guard parts.first == "S", parts.count >= 8,
                  let index = Int(parts[1]), let total = Int64(parts[2]),
                  let prefix = Int64(parts[3]) else { continue }
            latest[index] = Entry(
                index: index,
                total: total,
                prefix: prefix,
                complete: parts[4] == "1",
                contentType: parts[5].isEmpty ? nil : parts[5],
                file: fileURL(stream, index),
                lastAccess: Double(parts[6]) ?? 0)
        }
        for (index, stored) in latest {
            var e = stored
            let size = Fs.size(e.file)
            if size == 0 && !e.complete { continue }
            if e.prefix > size { e.prefix = size }
            if size > e.prefix { Fs.truncate(e.file, to: e.prefix) }
            if e.total >= 0 && e.prefix < e.total { e.complete = false }
            if e.total < 0 { e.complete = false }
            if e.prefix > 0 || e.complete { stream.entries[index] = e }
        }
        // Файлы, которых нет в журнале, — сироты.
        let known = Set(stream.entries.keys.map { String(format: "s%05d.bin", $0) })
        if let files = try? FileManager.default.contentsOfDirectory(atPath: stream.dir.path) {
            for file in files where file.hasSuffix(".bin") && !known.contains(file) {
                Fs.removeIfExists(stream.dir.appendingPathComponent(file))
            }
        }
        rewriteJournal(stream)
    }

    private func headerMatches(_ line: String?, stream: Stream) -> Bool {
        guard let line else { return false }
        let parts = line.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count >= 4, parts[0] == "H", parts[1] == "2" else { return false }
        let fingerprint = parts[2]
        let cacheKey = parts.dropFirst(3).joined(separator: "|")
        return fingerprint == stream.fingerprint && cacheKey == stream.cacheKey
    }

    /// Снос невалидного каталога: чужие/битые записи не должны пережить смену плейлиста.
    private func wipe(_ stream: Stream) {
        stream.entries.removeAll()
        if let files = try? FileManager.default.contentsOfDirectory(atPath: stream.dir.path) {
            for file in files where file.hasSuffix(".bin") || file == "journal.log" {
                Fs.removeIfExists(stream.dir.appendingPathComponent(file))
            }
        }
        rewriteJournal(stream)
    }

    private func rewriteJournal(_ stream: Stream) {
        let url = stream.dir.appendingPathComponent("journal.log")
        var text = "H|2|\(stream.fingerprint)|\(stream.cacheKey)\n"
        for e in stream.entries.values.sorted(by: { $0.index < $1.index }) {
            text += "S|\(e.index)|\(e.total)|\(e.prefix)|\(e.complete ? 1 : 0)|\(e.contentType ?? "")|\(Int(e.lastAccess))|\(e.index)\n"
        }
        try? Data(text.utf8).write(to: url)
    }

    private func journal(_ stream: Stream, entry: Entry) {
        let url = stream.dir.appendingPathComponent("journal.log")
        let line = "S|\(entry.index)|\(entry.total)|\(entry.prefix)|\(entry.complete ? 1 : 0)|\(entry.contentType ?? "")|\(Int(entry.lastAccess))|\(entry.index)"
        try? Fs.appendLine(url, line)
    }
}
