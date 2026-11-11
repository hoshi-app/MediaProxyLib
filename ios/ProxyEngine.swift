import Foundation

/// Движок прокси: сессии, маршрутизация loopback-запросов, загрузки, кэш, prefetch, события.
/// Всё, что делает сеть и диск, живёт здесь; JS управляет только конфигом и получает события.
public final class ProxyEngine {
    public struct ResolveOptions {
        public var cacheKey: String?
        public var headers: [String: String]
        public var cache: Bool
        public var prewarm: Bool
        public init(cacheKey: String? = nil, headers: [String: String] = [:], cache: Bool = true, prewarm: Bool = true) {
            self.cacheKey = cacheKey
            self.headers = headers
            self.cache = cache
            self.prewarm = prewarm
        }
    }

    public struct ResolveResult {
        public let streamId: String
        public let url: String
        public let cached: Bool
        public let reason: String
        public let generation: Int
    }

    public final class Session {
        let id: String
        let originUrl: String
        let cacheKey: String
        var headers: [String: String]
        var cacheable: Bool
        var generation: Int
        var lastAccess: TimeInterval
        var playlists: [String: PlaylistResource] = [:]
        var activeMedia: PlaylistResource?
        var lastSegmentIndex = -1
        var stream: CacheStore.Stream?
        var streamRefHeld = false
        var stale = false
        var authNotified = false

        init(id: String, originUrl: String, cacheKey: String, headers: [String: String],
             cacheable: Bool, generation: Int, now: TimeInterval) {
            self.id = id
            self.originUrl = originUrl
            self.cacheKey = cacheKey
            self.headers = headers
            self.cacheable = cacheable
            self.generation = generation
            self.lastAccess = now
        }
    }

    public final class PlaylistResource {
        let originUrl: String
        let finalUrl: String
        let text: String
        let result: RewriteResult
        let indexByUrl: [String: Int]

        init(originUrl: String, finalUrl: String, text: String, result: RewriteResult) {
            self.originUrl = originUrl
            self.finalUrl = finalUrl
            self.text = text
            self.result = result
            var map: [String: Int] = [:]
            for segment in result.media.segments { map[segment.url] = segment.index }
            self.indexByUrl = map
        }

        var data: Data { Data(text.utf8) }
    }

    private final class Download {
        let key: String
        let session: Session
        let stream: CacheStore.Stream
        let segment: PlaylistSegment?
        let feed: ByteFeed
        let isPrefetch: Bool
        var attempts = 0
        var cancelled = false
        var lastError: ProxyErrorCode?

        init(key: String, session: Session, stream: CacheStore.Stream, segment: PlaylistSegment?,
             feed: ByteFeed, isPrefetch: Bool) {
            self.key = key
            self.session = session
            self.stream = stream
            self.segment = segment
            self.feed = feed
            self.isPrefetch = isPrefetch
        }
    }

    private let queue = DispatchQueue(label: "tv.mediaproxy.engine")
    private let downloadsLock = NSRecursiveLock()
    private let sessionsLock = NSRecursiveLock()
    private let configLock = NSLock()

    private var _config = ProxyConfig()
    public var config: ProxyConfig {
        configLock.lock(); defer { configLock.unlock() }
        return _config
    }

    private let root: URL
    private var cache: CacheStore!
    private var origin: OriginClient!
    private var server: HttpServer?
    private var running = false
    private var generation = 0
    private var sessions: [String: Session] = [:]
    private var downloads: [String: Download] = [:]
    private var prefetched: Set<String> = []
    private var countersLock = NSLock()
    private var hits = 0
    private var misses = 0
    private var prefetchedCount = 0
    private var evictions = 0

    private let interactiveQueue: OperationQueue = {
        let q = OperationQueue()
        q.name = "tv.mediaproxy.interactive"
        q.maxConcurrentOperationCount = 4
        return q
    }()

    private let prefetchQueue: OperationQueue = {
        let q = OperationQueue()
        q.name = "tv.mediaproxy.prefetch"
        q.maxConcurrentOperationCount = 2
        return q
    }()

    /// События в JS. Вызывается из фоновых потоков — модуль сам решает, как доставлять.
    public var onEvent: ((_ name: String, _ payload: [String: Any]) -> Void)?

    public init(root: URL) {
        self.root = root
    }

    // MARK: - lifecycle

    @discardableResult
    public func start() -> (running: Bool, port: Int, error: String?) {
        if running, let server { return (true, server.boundPort, nil) }
        try? Fs.ensureDir(root)
        cache = CacheStore(root: root, config: { [weak self] in self?.config ?? ProxyConfig() },
                           diskFree: { Fs.freeDiskBytes(at: root) })
        origin = OriginClient()

        let http = HttpServer(maxConnections: config.maxLoopbackConnections, idleTimeout: config.socketIdleSec) {
            [weak self] method, path, range, _ in
            guard let self else { return HttpReply.text(500, "engine gone") }
            return self.handle(method: method, path: path, range: range)
        }
        do {
            try http.start()
        } catch {
            emit("onServerState", ["running": false, "port": 0, "baseUrl": "", "generation": generation,
                                   "reason": "SERVER_START_FAILED"])
            return (false, 0, "SERVER_START_FAILED")
        }
        server = http
        running = true
        generation += 1
        interactiveQueue.maxConcurrentOperationCount = config.interactiveConcurrency
        prefetchQueue.maxConcurrentOperationCount = config.prefetchConcurrency
        emit("onServerState", ["running": true, "port": http.boundPort,
                               "baseUrl": baseUrl, "generation": generation, "reason": "started"])
        return (true, http.boundPort, nil)
    }

    public func stop() {
        sessionsLock.lock()
        sessions.removeAll()
        sessionsLock.unlock()
        downloadsLock.lock()
        for (_, d) in downloads { d.cancelled = true }
        downloads.removeAll()
        downloadsLock.unlock()
        server?.stop()
        server = nil
        origin?.shutdown()
        running = false
    }

    public var baseUrl: String {
        guard let server else { return "" }
        return "http://127.0.0.1:\(server.boundPort)"
    }

    public var port: Int { server?.boundPort ?? 0 }
    public var isRunning: Bool { running }
    public var currentGeneration: Int { generation }

    public func setConfig(_ patch: [String: Any]) {
        configLock.lock()
        _config.apply(patch)
        let cfg = _config
        configLock.unlock()
        interactiveQueue.maxConcurrentOperationCount = cfg.interactiveConcurrency
        prefetchQueue.maxConcurrentOperationCount = cfg.prefetchConcurrency
        Log.enabled = cfg.debug
    }

    // MARK: - resolve / release

    public func resolve(_ originUrl: String, options: ResolveOptions) -> ResolveResult {
        guard running, let server else {
            return ResolveResult(streamId: "", url: originUrl, cached: false, reason: "NOT_STARTED", generation: generation)
        }
        guard Http.isAllowedOrigin(originUrl) else {
            return ResolveResult(streamId: "", url: originUrl, cached: false, reason: "INVALID_URL", generation: generation)
        }
        let now = Date().timeIntervalSince1970
        let sid = Ids.sessionId()
        let cacheKey = options.cacheKey ?? originUrl
        let cacheable = options.cache && config.enabled
        let session = Session(id: sid, originUrl: originUrl, cacheKey: cacheKey,
                              headers: options.headers, cacheable: cacheable,
                              generation: generation, now: now)
        sessionsLock.lock()
        sessions[sid] = session
        evictOldSessionsLocked(now: now, keeping: sid)
        sessionsLock.unlock()

        let kind: ResourceKind = PlaylistRewriter.isPlaylistCandidate(url: originUrl, contentType: nil) ? .playlist : .playlist
        let url = proxyUrl(session: session, absolute: originUrl, kind: kind, port: server.boundPort)
        Log.line("resolve", "sid=\(sid) cached=\(cacheable) \(originUrl)")
        if options.prewarm {
            queue.async { [weak self] in
                guard let self else { return }
                _ = self.loadPlaylist(session: session, url: originUrl)
            }
        }
        return ResolveResult(streamId: sid, url: url, cached: cacheable, reason: cacheable ? "ok" : "cache off",
                             generation: generation)
    }

    public func release(_ streamId: String) {
        sessionsLock.lock()
        let session = sessions.removeValue(forKey: streamId)
        sessionsLock.unlock()
        guard let session else { return }
        if let stream = session.stream, session.streamRefHeld {
            cache.release(stream)
        }
        downloadsLock.lock()
        for (key, d) in downloads where d.session.id == streamId {
            d.cancelled = true
            downloads.removeValue(forKey: key)
        }
        downloadsLock.unlock()
        prefetched = prefetched.filter { !$0.hasPrefix(session.cacheKey + "|") }
    }

    public func failOpen(_ streamIdOrUrl: String) -> [String: Any]? {
        if let session = sessionById(streamIdOrUrl) {
            session.stale = true
            let originUrl = session.originUrl
            release(session.id)
            return ["streamId": session.id, "originUrl": originUrl]
        }
        if let sid = streamIdFromProxyUrl(streamIdOrUrl), let session = sessionById(sid) {
            session.stale = true
            let originUrl = session.originUrl
            release(session.id)
            return ["streamId": session.id, "originUrl": originUrl]
        }
        return nil
    }

    public func nudge(_ streamId: String) -> [String: Any] {
        guard let session = sessionById(streamId), session.cacheable,
              let media = session.activeMedia, let stream = session.stream else {
            return ["queued": 0]
        }
        let from = max(0, session.lastSegmentIndex)
        let queued = schedulePrefetch(session: session, stream: stream, resource: media, from: from)
        return ["queued": queued]
    }

    // MARK: - HTTP routing

    private func handle(method: String, path: String, range: String?) -> HttpReply {
        let parts = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard parts.count >= 3, parts[0] == "s" else { return .text(404, "not found") }
        let sid = parts[1]
        guard let session = sessionById(sid) else { return .text(410, ProxyErrorCode.sessionUnknown.rawValue) }
        session.lastAccess = Date().timeIntervalSince1970
        guard let absolute = B64Url.decode(parts[2]) else { return .text(400, ProxyErrorCode.invalidUrl.rawValue) }
        let name = parts.count >= 4 ? parts[3] : ""
        let ext = (name as NSString).pathExtension.lowercased()
        let kind = Http.resourceKind(extension: ext, contentType: nil)

        if session.stale { return .text(410, "stale") }

        if kind == .playlist || session.playlists[absolute] != nil {
            return loadPlaylist(session: session, url: absolute)
        }
        if kind == .key {
            return passThrough(session: session, url: absolute, range: range, cacheState: "passthrough")
        }
        return serveSegment(session: session, url: absolute, range: range)
    }

    private func loadPlaylist(session: Session, url: String) -> HttpReply {
        if let cached = session.playlists[url] {
            return playlistReply(cached, range: nil)
        }
        let cfg = config
        let profile = TimeoutProfile.playlist(cfg)
        let result = origin.fetchPlaylist(url: url, headers: session.headers, profile: profile,
                                          maxBytes: cfg.maxPlaylistBytes)
        if let error = result.error {
            notifyError(session: session, url: url, code: error, attempt: 1, originStatus: nil)
            return errorReply(error)
        }
        guard let data = result.data else {
            return errorReply(.emptyResponse)
        }
        if !PlaylistRewriter.looksLikePlaylist(data) {
            // Не плейлист: отдаём как есть (buffer-then-serve), кэшировать нечего.
            let contentType = result.contentType ?? "application/octet-stream"
            return HttpReply(status: 200, headers: ["Content-Type": contentType, "Cache-Control": "no-store"],
                             body: BytesBody(data), length: Int64(data.count), cacheState: "passthrough")
        }
        let text = String(decoding: data, as: UTF8.self)
        let finalUrl = result.finalUrl ?? url
        let rewritten = PlaylistRewriter.rewrite(text: text, finalUrl: finalUrl) { [weak self] absolute, kind in
            guard let self, let server = self.server else { return absolute }
            return self.proxyUrl(session: session, absolute: absolute, kind: kind, port: server.boundPort)
        }
        let resource = PlaylistResource(originUrl: url, finalUrl: finalUrl, text: rewritten.text, result: rewritten)
        session.playlists[url] = resource
        session.playlists[finalUrl] = resource

        if rewritten.kind == .media {
            session.activeMedia = resource
            if !rewritten.cacheable {
                session.cacheable = false
                Log.line("playlist", "без кэша: \(rewritten.reason) \(url)")
            }
            ensureStream(session: session, fingerprint: rewritten.fingerprint)
            if cfg.prewarmSegments > 0 {
                prewarm(session: session, resource: resource)
            }
        }
        return playlistReply(resource, range: nil)
    }

    private func playlistReply(_ resource: PlaylistResource, range: String?) -> HttpReply {
        HttpReply(status: 200,
                  headers: ["Content-Type": "application/vnd.apple.mpegurl", "Cache-Control": "no-store"],
                  body: BytesBody(resource.data), length: Int64(resource.data.count), cacheState: "hit")
    }

    private func serveSegment(session: Session, url: String, range: String?) -> HttpReply {
        let cfg = config
        guard session.cacheable, let media = session.activeMedia, let stream = session.stream else {
            return passThrough(session: session, url: url, range: range, cacheState: "passthrough")
        }
        guard let index = media.indexByUrl[url] else {
            return passThrough(session: session, url: url, range: range, cacheState: "passthrough")
        }
        session.lastSegmentIndex = max(session.lastSegmentIndex, index)
        let segment = media.result.media.segments.first(where: { $0.index == index })

        if !cache.evictIfNeeded(required: 0, protecting: [session.cacheKey], now: Date().timeIntervalSince1970) {
            return passThrough(session: session, url: url, range: range, cacheState: "passthrough")
        }

        var entry = cache.ensureEntry(stream, index: index, now: Date().timeIntervalSince1970)
        if entry.complete {
            bumpHits()
            let type = entry.contentType ?? Http.contentType(forExtension: urlPathExtension(url))
            return HttpReply(status: 200, headers: ["Content-Type": type, "Cache-Control": "no-store"],
                             body: FileBody(url: entry.file), length: entry.prefix, cacheState: "hit")
        }
        if entry.failedUntil > Date().timeIntervalSince1970 {
            return errorReply(.budgetExhausted)
        }
        bumpMisses()

        let feed = entry.feed ?? ensureDownload(session: session, stream: stream, index: index, segment: segment,
                                                profile: .interactive(cfg))
        entry = cache.ensureEntry(stream, index: index, now: Date().timeIntervalSince1970)
        if let error = feed?.waitForFirstBytes(timeout: cfg.reqFirstByteSec) {
            notifyError(session: session, url: url, code: error, attempt: 1, originStatus: nil)
            return errorReply(error)
        }
        let total = feed?.total ?? -1
        if total <= 0 {
            return passThrough(session: session, url: url, range: range, cacheState: "passthrough")
        }
        let type = feed?.responseType ?? entry.contentType ?? Http.contentType(forExtension: urlPathExtension(url))
        cache.update(stream, index: index) { $0.contentType = type }
        schedulePrefetch(session: session, stream: stream, resource: media, from: index)
        return HttpReply(status: 200, headers: ["Content-Type": type, "Cache-Control": "no-store"],
                         body: GrowingFileBody(url: entry.file, feed: feed!), length: total, cacheState: "miss")
    }

    private func passThrough(session: Session, url: String, range: String?, cacheState: String) -> HttpReply {
        let cfg = config
        let feed = ByteFeed(file: nil)
        var start: Int64 = 0
        var playerRange: String? = nil
        if let range, range.lowercased().hasPrefix("bytes=") {
            playerRange = range
            if let parsed = Http.parseRange(range, total: Int64.max) { start = parsed.start }
        }
        let profile = TimeoutProfile.interactive(cfg)
        origin.open(OriginClient.Request(url: url, headers: session.headers, rangeStart: start,
                                         profile: profile, isPrefetch: false, expectPlaylist: false),
                    feed: feed) { [weak self] _ in
            self?.applyCookies(session: session, feed: feed, url: url)
        }
        if let error = feed.waitForFirstBytes(timeout: cfg.reqFirstByteSec) {
            notifyError(session: session, url: url, code: error, attempt: 1, originStatus: nil)
            return errorReply(error)
        }
        let total = feed.total
        let type = feed.responseType ?? Http.contentType(forExtension: urlPathExtension(url))
        if total < 0 {
            return HttpReply(status: 200, headers: ["Content-Type": type, "Cache-Control": "no-store"],
                             body: StreamBody(feed: feed), length: -1, cacheState: cacheState)
        }
        var headers: [String: String] = ["Content-Type": type, "Cache-Control": "no-store"]
        if start > 0 {
            headers["Content-Range"] = "bytes \(start)-\(start + total - 1)/\(start + total)"
            var reply = HttpReply(status: 206, headers: headers, body: StreamBody(feed: feed),
                                  length: total - start, cacheState: cacheState)
            reply.rangeHandled = true
            return reply
        }
        var reply = HttpReply(status: 200, headers: headers, body: StreamBody(feed: feed),
                              length: total, cacheState: cacheState)
        reply.rangeHandled = playerRange != nil
        return reply
    }

    private func errorReply(_ code: ProxyErrorCode) -> HttpReply {
        HttpReply.text(code.httpStatus, code.rawValue)
    }

    // MARK: - downloads

    @discardableResult
    private func ensureDownload(session: Session, stream: CacheStore.Stream, index: Int,
                                segment: PlaylistSegment?, profile: TimeoutProfile) -> ByteFeed? {
        let key = "\(stream.key)#\(index)"
        downloadsLock.lock()
        if let existing = downloads[key] {
            downloadsLock.unlock()
            return existing.feed
        }
        let cfg = config
        guard cache.evictIfNeeded(required: 0, protecting: [session.cacheKey], now: Date().timeIntervalSince1970) else {
            downloadsLock.unlock()
            return nil
        }
        let feed = ByteFeed(file: cache.fileURL(stream, index))
        let download = Download(key: key, session: session, stream: stream, segment: segment,
                                feed: feed, isPrefetch: false)
        downloads[key] = download
        downloadsLock.unlock()
        cache.retain(stream)
        cache.ensureEntry(stream, index: index, now: Date().timeIntervalSince1970)
        cache.update(stream, index: index) { entry in
            entry.downloading = true
            entry.total = -1
            entry.prefix = 0
            entry.complete = false
            entry.failedUntil = 0
        }
        queue.async { [weak self] in
            self?.runDownload(download, profile: profile)
        }
        return feed
    }

    private func runDownload(_ download: Download, profile: TimeoutProfile) {
        let cfg = config
        var attempt = 0
        while !download.cancelled {
            let start = download.feed.written
            let index = download.segment?.index ?? 0
            let semaphore = DispatchSemaphore(value: 0)
            var terminal: ProxyErrorCode? = nil

            origin.open(OriginClient.Request(url: download.segment?.url ?? sessionOrigin(download),
                                             headers: download.session.headers,
                                             rangeStart: start,
                                             ifRange: nil,
                                             profile: profile,
                                             isPrefetch: false,
                                             expectPlaylist: false),
                        feed: download.feed) { error in
                terminal = error
                semaphore.signal()
            }
            if download.session.stale {
                download.feed.fail(.networkChanged)
            }
            _ = semaphore.wait(timeout: .now() + 300)

            if terminal == nil {
                let total = download.feed.total
                let written = download.feed.written
                cache.ensureEntry(download.stream, index: download.segment?.index ?? 0, now: Date().timeIntervalSince1970)
                cache.update(download.stream, index: download.segment?.index ?? 0) { e in
                    e.total = total
                    e.prefix = written
                    e.complete = total >= 0 && written == total
                    e.downloading = false
                    e.feed = download.feed
                    e.lastAccess = Date().timeIntervalSince1970
                }
                finishDownload(download, error: nil)
                return
            }

            let code = terminal ?? .connectFailed
            if code == .rangeUnsupported, start > 0 {
                // origin проигнорировал Range: начинаем сегмент заново.
                let fresh = ByteFeed(file: cache.fileURL(download.stream, download.segment?.index ?? 0))
                // проще: пометить failed и позволить плееру перезапросить
                cache.ensureEntry(download.stream, index: download.segment?.index ?? 0, now: Date().timeIntervalSince1970)
                cache.update(download.stream, index: download.segment?.index ?? 0) { e in
                    e.prefix = 0; e.total = -1; e.downloading = false; e.failedUntil = 0
                }
                _ = fresh
                finishDownload(download, error: .rangeUnsupported)
                return
            }

            attempt += 1
            let retryable = code.isRetryable || (download.feed.written > 0 && code == .truncated)
            if retryable && attempt <= cfg.prefetchRetries && !download.cancelled {
                let backoff = cfg.retryBackoffMs[min(attempt - 1, cfg.retryBackoffMs.count - 1)]
                Thread.sleep(forTimeInterval: Double(backoff) / 1000)
                continue
            }

            let written = download.feed.written
            let total = download.feed.total
            download.feed.fail(code)
            cache.ensureEntry(download.stream, index: download.segment?.index ?? 0, now: Date().timeIntervalSince1970)
            cache.update(download.stream, index: download.segment?.index ?? 0) { e in
                e.total = total
                e.prefix = written
                e.complete = total >= 0 && written == total
                e.downloading = false
                e.failedUntil = Date().timeIntervalSince1970 + cfg.cooldownSec
                e.feed = download.feed
            }
            notifyError(session: download.session, url: download.segment?.url ?? "", code: code,
                        attempt: attempt, originStatus: nil)
            finishDownload(download, error: code)
            return
        }
        finishDownload(download, error: .networkChanged)
    }

    private func finishDownload(_ download: Download, error: ProxyErrorCode?) {
        downloadsLock.lock()
        downloads.removeValue(forKey: download.key)
        downloadsLock.unlock()
        cache.release(download.stream)
        // Метка о следующем сегменте здесь НЕ ставится: иначе prefetch «считает» сегмент готовым,
        // ни разу его не скачав. Учёт — только по завершённой собственной загрузке prefetch.
        if error == nil && download.isPrefetch {
            bumpPrefetched()
        }
    }

    private func prewarm(session: Session, resource: PlaylistResource) {
        guard session.cacheable, let stream = session.stream else { return }
        let count = min(max(0, config.prewarmSegments), resource.result.media.segments.count)
        for segment in resource.result.media.segments.prefix(count) {
            enqueuePrefetch(session: session, stream: stream, segment: segment)
        }
    }

    @discardableResult
    private func schedulePrefetch(session: Session, stream: CacheStore.Stream,
                                  resource: PlaylistResource, from index: Int) -> Int {
        guard session.cacheable, config.prefetchMaxSegments > 0 else { return 0 }
        let media = resource.result.media
        guard index + 1 < media.segments.count else { return 0 }
        let avg = media.segments.isEmpty ? 6 : max(0.5, media.totalDuration / Double(media.segments.count))
        let wanted = Int(ceil(max(1, config.prefetchSeconds) / avg))
        let count = min(max(0, config.prefetchMaxSegments), max(1, wanted))
        guard count > 0 else { return 0 }
        let end = min(media.segments.count - 1, index + count)
        var queued = 0
        for i in (index + 1)...max(index + 1, end) {
            guard i < media.segments.count else { continue }
            let segment = media.segments[i]
            if enqueuePrefetch(session: session, stream: stream, segment: segment) { queued += 1 }
        }
        return queued
    }

    @discardableResult
    private func enqueuePrefetch(session: Session, stream: CacheStore.Stream, segment: PlaylistSegment) -> Bool {
        let key = "\(stream.key)#\(segment.index)"
        downloadsLock.lock()
        if downloads[key] != nil || prefetched.contains("\(session.cacheKey)|\(segment.index)") {
            downloadsLock.unlock()
            return false
        }
        let cfg = config
        guard cache.evictIfNeeded(required: 0, protecting: [session.cacheKey], now: Date().timeIntervalSince1970) else {
            downloadsLock.unlock()
            return false
        }
        let feed = ByteFeed(file: cache.fileURL(stream, segment.index))
        let download = Download(key: key, session: session, stream: stream, segment: segment,
                                feed: feed, isPrefetch: true)
        downloads[key] = download
        prefetched.insert("\(session.cacheKey)|\(segment.index)")
        downloadsLock.unlock()

        prefetchQueue.addOperation { [weak self] in
            guard let self else { return }
            self.cache.retain(stream)
            // У prefetch нет «запроса плеера», который создал бы запись: без ensureEntry update молчит.
            self.cache.ensureEntry(stream, index: segment.index, now: Date().timeIntervalSince1970)
            self.cache.update(stream, index: segment.index) { entry in
                entry.downloading = true
                entry.total = -1
                entry.prefix = 0
                entry.complete = false
                entry.failedUntil = 0
            }
            self.runDownload(download, profile: .prefetch(cfg))
        }
        return true
    }

    private func ensureStream(session: Session, fingerprint: String) {
        let stream = cache.openStream(cacheKey: session.cacheKey, fingerprint: fingerprint,
                                      now: Date().timeIntervalSince1970)
        if session.stream !== stream {
            if let old = session.stream, session.streamRefHeld { cache.release(old) }
            session.stream = stream
            cache.retain(stream)
            session.streamRefHeld = true
        }
    }

    // MARK: - state / stats

    public func stats() -> [String: Any] {
        let now = Date().timeIntervalSince1970
        let total = cache.totalBytes()
        let cfg = config
        return [
            "port": port, "running": running, "generation": generation,
            "bytes": total, "maxBytes": cfg.maxCacheBytes,
            "freeDiskBytes": Fs.freeDiskBytes(at: root),
            "streams": sessionsLock.withLock { sessions.count },
            "hits": countersLock.withLock { hits },
            "misses": countersLock.withLock { misses },
            "evictions": countersLock.withLock { evictions },
            "prefetched": countersLock.withLock { prefetchedCount },
            "maxSessions": cfg.maxSessions,
            "now": now,
        ]
    }

    public func streamState(_ streamId: String) -> [String: Any] {
        guard let session = sessionById(streamId) else { return ["cacheKey": "", "cacheable": false, "segments": [], "cachedAheadSec": 0] }
        guard let media = session.activeMedia, let stream = session.stream else {
            return ["cacheKey": session.cacheKey, "cacheable": session.cacheable, "segments": [], "cachedAheadSec": 0]
        }
        var segments: [[String: Any]] = []
        var cachedAhead = 0.0
        let last = max(0, session.lastSegmentIndex)
        for segment in media.result.media.segments {
            let entry = cache.entry(stream, index: segment.index)
            let complete = entry?.complete ?? false
            let bytes = entry?.prefix ?? 0
            segments.append([
                "index": segment.index, "startSec": segment.startSec, "durationSec": segment.duration,
                "cachedBytes": bytes, "complete": complete,
            ])
            if segment.index > last {
                if complete { cachedAhead += segment.duration } else { break }
            }
        }
        return ["cacheKey": session.cacheKey, "cacheable": session.cacheable,
                "segments": segments, "cachedAheadSec": cachedAhead]
    }

    public func clearCache(_ streamId: String? = nil) -> [String: Any] {
        var cacheKey: String? = nil
        if let streamId { cacheKey = sessionById(streamId)?.cacheKey }
        let freed = cache.clear(cacheKey: cacheKey)
        emit("onCache", ["type": "clear", "bytes": cache.totalBytes(),
                         "maxBytes": config.maxCacheBytes])
        return ["freedBytes": freed]
    }

    // MARK: - helpers

    private func proxyUrl(session: Session, absolute: String, kind: ResourceKind, port: Int) -> String {
        let url = URL(string: absolute)
        let name = Http.safeName(for: url ?? URL(string: "https://invalid/")!, kind: kind)
        return "http://127.0.0.1:\(port)/s/\(session.id)/\(B64Url.encode(absolute))/\(name)"
    }

    private func sessionById(_ id: String) -> Session? {
        sessionsLock.lock(); defer { sessionsLock.unlock() }
        return sessions[id]
    }

    private func streamIdFromProxyUrl(_ url: String) -> String? {
        guard let parsed = URL(string: url), parsed.host == "127.0.0.1" else { return nil }
        let parts = parsed.path.split(separator: "/", omittingEmptySubsequences: true)
        return parts.count >= 2 ? String(parts[1]) : nil
    }

    private func evictOldSessionsLocked(now: TimeInterval, keeping: String) {
        let maxSessions = config.maxSessions
        while sessions.count > maxSessions {
            // Вытесняем только ДРУГИЕ сессии: при равных timestamp могли выбросить новую.
            guard let victim = sessions.values.filter({ $0.id != keeping })
                .min(by: { $0.lastAccess < $1.lastAccess }) else { break }
            sessions.removeValue(forKey: victim.id)
        }
    }

    private func sessionOrigin(_ download: Download) -> String {
        download.session.originUrl
    }

    private func urlPathExtension(_ url: String) -> String {
        (URL(string: url)?.pathExtension ?? "").lowercased()
    }

    private func bumpHits() { countersLock.lock(); hits += 1; countersLock.unlock() }
    private func bumpMisses() { countersLock.lock(); misses += 1; countersLock.unlock() }
    private func bumpPrefetched() { countersLock.lock(); prefetchedCount += 1; countersLock.unlock() }

    private func notifyError(session: Session, url: String, code: ProxyErrorCode, attempt: Int,
                             originStatus: Int?) {
        if code.isAuth, !session.authNotified {
            session.authNotified = true
            emit("onAuthRequired", ["streamId": session.id, "url": url,
                                    "originStatus": originStatus ?? code.httpStatus])
            return
        }
        emit("onError", [
            "code": code.rawValue, "streamId": session.id, "url": url,
            "originStatus": originStatus ?? 0, "attempt": attempt,
            "retryable": code.isRetryable, "fatal": !code.isRetryable,
            "message": code.rawValue,
        ])
    }

    private func emit(_ name: String, _ payload: [String: Any]) {
        onEvent?(name, payload)
    }

    private func applyCookies(session: Session, feed: ByteFeed, url: String) {
        // Cookie jar ведёт URLSession-Delegate в OriginClient для плейлистов/сегментов;
        // здесь оставлено место для явного Cookie-заголовка из сессии (§6.1).
    }
}

extension NSRecursiveLock {
    func withLock<T>(_ body: () -> T) -> T {
        lock(); defer { unlock() }
        return body()
    }
}

extension NSLock {
    func withLock<T>(_ body: () -> T) -> T {
        lock(); defer { unlock() }
        return body()
    }
}
