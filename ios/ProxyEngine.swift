import Foundation

/// Proxy engine: sessions, loopback request routing, downloads, cache, prefetch, and events.
/// All network and disk work lives here; JS only sets config and receives events.
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
        /// When the media playlist was last refetched to retry a segment, to prevent a retry storm.
        var playlistRefreshedAt: TimeInterval = 0
        /// When the player last requested a resource through the proxy. Gates the stall detector.
        var lastClientRequestAt: TimeInterval = Date().timeIntervalSince1970
        /// Segment indexes whose retries were exhausted without progress.
        var recoveriesGaveUp: Set<Int> = []
        /// Previous "segment URL → index" maps, newest first. A playlist refresh changes segment URLs,
        /// but the player keeps requesting the old ones; without these maps those requests bypass the
        /// cache.
        var indexGenerations: [[String: Int]] = []
        /// Per-session cookie jar: host → name → value. The shared HTTPCookieStorage is never used.
        var cookies: [String: [String: String]] = [:]
        /// Single lock for mutable session state; playlist and segment requests arrive on different threads.
        let lock = NSRecursiveLock()

        func pushIndexMap(_ map: [String: Int]) {
            lock.withLock {
                indexGenerations.insert(map, at: 0)
                if indexGenerations.count > 3 { indexGenerations.removeLast() }
            }
        }

        /// Current playlist first, then earlier maps.
        func indexOf(_ url: String) -> Int? {
            if let hit = activeMedia?.indexByUrl[url] { return hit }
            return lock.withLock { () -> Int? in
                for map in indexGenerations {
                    if let hit = map[url] { return hit }
                }
                return nil
            }
        }

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
        let fetchedAt: TimeInterval

        init(originUrl: String, finalUrl: String, text: String, result: RewriteResult,
             fetchedAt: TimeInterval = Date().timeIntervalSince1970) {
            self.originUrl = originUrl
            self.finalUrl = finalUrl
            self.text = text
            self.result = result
            self.fetchedAt = fetchedAt
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
        /// Current segment URL. A media playlist restart updates it so a retry reaches a fresh
        /// CDN edge and signature instead of the one that already failed.
        var url: String
        /// Whether the playlist was already refetched for this segment; only one attempt per download.
        var refreshed = false

        var index: Int { segment?.index ?? 0 }

        init(key: String, session: Session, stream: CacheStore.Stream, segment: PlaylistSegment?,
             feed: ByteFeed, isPrefetch: Bool, url: String) {
            self.key = key
            self.session = session
            self.stream = stream
            self.segment = segment
            self.feed = feed
            self.isPrefetch = isPrefetch
            self.url = url
        }
    }

    private let queue = DispatchQueue(label: "tv.mediaproxy.playlist")
    private let downloadsLock = NSRecursiveLock()
    private let sessionsLock = NSRecursiveLock()
    private let configLock = NSLock()

    private var _config = ProxyConfig()
    public var config: ProxyConfig {
        configLock.lock(); defer { configLock.unlock() }
        return _config
    }

    private let root: URL
    private var cache: CacheStore
    private var origin: OriginClient
    private var server: HttpServer?
    private var running = false
    private var generation = 0
    private var sessions: [String: Session] = [:]
    private var downloads: [String: Download] = [:]
    private var countersLock = NSLock()
    private var hits = 0
    private var misses = 0
    private var prefetchedCount = 0
    private var stallKicks = 0
    private var originTruncated = 0

    /// Byte progress of client downloads, read by the stall detector to see when bytes stop growing.
    /// Kept outside `Download` to stay off the hot path and removed together with the download.
    private final class Progress {
        var last: Int64
        var lastAt: TimeInterval
        var kicks = 0
        var baseline: Int64

        init(written: Int64, at: TimeInterval) {
            last = written
            lastAt = at
            baseline = written
        }
    }

    private var downloadProgress: [String: Progress] = [:]
    private let watchdogQueue = DispatchQueue(label: "tv.mediaproxy.recover")
    private var recoverTimer: DispatchSourceTimer?

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

    /// Events to JS. Called from background threads; the module decides how to deliver them.
    public var onEvent: ((_ name: String, _ payload: [String: Any]) -> Void)?

    public init(root: URL) {
        self.root = root
        // Cache and origin client are created here, not in start(): JS calls stats()/streamState()
        // before start(), so they must not fail on nil.
        self.cache = CacheStore(root: root, config: { ProxyConfig() },
                                diskFree: { Fs.freeDiskBytes(at: root) })
        self.origin = OriginClient()
    }

    // MARK: - lifecycle

    @discardableResult
    public func start() -> (running: Bool, port: Int, error: String?) {
        if running, let server { return (true, server.boundPort, nil) }
        try? Fs.ensureDir(root)
        // Capture root locally: an escaping closure would require self, and the cache must not
        // retain the engine (self → cache → closure).
        let rootUrl = root
        cache = CacheStore(root: rootUrl, config: { [weak self] in self?.config ?? ProxyConfig() },
                           diskFree: { Fs.freeDiskBytes(at: rootUrl) })
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
        startRecoverWatchdog()
        emit("onServerState", ["running": true, "port": http.boundPort,
                               "baseUrl": baseUrl, "generation": generation, "reason": "started"])
        return (true, http.boundPort, nil)
    }

    public func stop() {
        recoverTimer?.cancel()
        recoverTimer = nil
        sessionsLock.lock()
        let all = Array(sessions.values)
        sessions.removeAll()
        sessionsLock.unlock()
        for session in all { releaseSessionResources(session) }
        downloadsLock.lock()
        for (_, d) in downloads { d.cancelled = true }
        downloads.removeAll()
        downloadsLock.unlock()
        server?.stop()
        server = nil
        origin.shutdown()
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

        let kind: ResourceKind = .playlist
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
        releaseSessionResources(session)
    }

    /// Releases everything the session held: the cache ref, its downloads, and prefetch markers.
    /// Without it an evicted session would leave the stream with `refs > 0`, so it could never be deleted.
    private func releaseSessionResources(_ session: Session) {
        session.lock.lock()
        if session.streamRefHeld, let stream = session.stream {
            cache.release(stream)
        }
        session.streamRefHeld = false
        session.stream = nil
        session.lock.unlock()

        downloadsLock.lock()
        for (key, d) in downloads where d.session.id == session.id {
            d.cancelled = true
            downloads.removeValue(forKey: key)
        }
        downloadsLock.unlock()
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

    /// Prefetches ahead for the player's stall detector, forcing past the cooldown on failed segments.
    /// The window starts at the last requested segment, which is the one that usually stalls.
    public func nudge(_ streamId: String) -> [String: Any] {
        guard let session = sessionById(streamId), session.cacheable else { return ["queued": 0] }
        return session.lock.withLock { () -> [String: Any] in
            guard let media = session.activeMedia, let stream = session.stream else { return ["queued": 0] }
            let from = max(0, session.lastSegmentIndex) - 1
            let queued = schedulePrefetch(session: session, stream: stream, resource: media, from: from, force: true)
            return ["queued": queued]
        }
    }

    /// Player-driven restart: refetches the media playlist and requeues the stalled window without
    /// remounting the player. `segmentsChanged` reports whether any segment URL actually changed.
    public func restart(_ streamId: String) -> [String: Any] {
        guard let session = sessionById(streamId) else {
            return ["queued": 0, "refreshed": false, "segmentsChanged": false,
                    "reason": ProxyErrorCode.sessionUnknown.rawValue]
        }
        let outcome = refreshMedia(session: session)
        let nudgeResult = nudge(streamId)
        let queued = nudgeResult["queued"] ?? 0
        Log.line("restart", "sid=\(streamId) refreshed=\(outcome.performed) "
            + "changed=\(outcome.segmentsChanged) queued=\(queued)")
        let reason: String
        if !outcome.performed { reason = "unchanged" }
        else if outcome.segmentsChanged { reason = "ok" }
        else { reason = "same-urls" }
        return ["queued": queued, "refreshed": outcome.performed,
                "segmentsChanged": outcome.segmentsChanged,
                "cacheable": session.cacheable, "reason": reason]
    }

    // MARK: - stall detector

    private func startRecoverWatchdog() {
        recoverTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: watchdogQueue)
        timer.schedule(deadline: .now() + 2, repeating: 2)
        timer.setEventHandler { [weak self] in self?.recoverTick() }
        timer.resume()
        recoverTimer = timer
    }

    /// Notices a client asking for bytes that never arrive and refetches the media playlist, so the
    /// player does not need a remount. Only client downloads in sessions with a live client count,
    /// only after cfg.stallKickSec without progress, and at most cfg.recoveryMax retries per segment.
    ///
    /// Lock order is strictly `session.lock → downloadsLock`. Never take `session.lock` while holding
    /// `downloadsLock`; the reverse order deadlocks.
    private func recoverTick() {
        let cfg = config
        if !running || cfg.stallKickSec <= 0 || cfg.recoveryMax <= 0 { return }
        let now = Date().timeIntervalSince1970
        var candidates: [Download] = []
        var progresses: [String: Progress] = [:]
        downloadsLock.lock()
        for (key, download) in downloads {
            if download.isPrefetch { continue }
            let written = download.feed.written
            let progress = downloadProgress[key] ?? Progress(written: written, at: now)
            downloadProgress[key] = progress
            if written > progress.last {
                progress.last = written
                progress.lastAt = now
            }
            if now - progress.lastAt < cfg.stallKickSec { continue }
            candidates.append(download)
            progresses[key] = progress
        }
        downloadsLock.unlock()
        for download in candidates {
            guard let progress = progresses[download.key] else { continue }
            let session = download.session
            if session.stale { continue }
            let lastClient = session.lock.withLock { session.lastClientRequestAt }
            // Client is gone (pause, background, remount); nothing to recover.
            if now - lastClient > 15 { continue }
            if progress.kicks >= cfg.recoveryMax {
                // All retries made no progress: the segment is dead, the player decides next.
                if progress.last <= progress.baseline {
                    session.lock.withLock { session.recoveriesGaveUp.insert(download.index) }
                }
                continue
            }
            kickStalled(download, now: now)
        }
    }

    private func kickStalled(_ download: Download, now: TimeInterval) {
        let session = download.session
        let index = download.index
        let outcome = refreshMedia(session: session)
        let fresh = currentUrl(session: session, index: index)
        let urlChanged = fresh != nil && fresh != download.url
        if let fresh, urlChanged {
            downloadsLock.lock()
            download.url = fresh
            downloadsLock.unlock()
        }
        downloadsLock.lock()
        let progress = downloadProgress[download.key]
        downloadsLock.unlock()
        guard let progress else { return }
        let waited = now - progress.lastAt
        progress.kicks += 1
        // Next retry waits a full stallKickSec; the refresh may already have taken time.
        progress.lastAt = now
        if progress.kicks == 1 { progress.baseline = progress.last }
        bumpStallKicks()
        Log.line("recover", "sid=\(session.id) seg=\(index) waited=\(Int(waited))s "
            + "changed=\(outcome.segmentsChanged) urlChanged=\(urlChanged) kick=\(progress.kicks)")
        emit("onRecover", ["streamId": session.id, "segmentIndex": index,
                           "waitedSec": waited, "urlChanged": urlChanged])
    }

    // MARK: - HTTP routing

    private func handle(method: String, path: String, range: String?) -> HttpReply {
        let parts = path.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard parts.count >= 3, parts[0] == "s" else { return .text(404, "not found") }
        let sid = parts[1]
        guard let session = sessionById(sid) else { return .text(410, ProxyErrorCode.sessionUnknown.rawValue) }
        let requestAt = Date().timeIntervalSince1970
        session.lock.withLock {
            session.lastAccess = requestAt
            // Marks the client as alive; the stall detector skips sessions nobody requests.
            session.lastClientRequestAt = requestAt
        }
        guard let absolute = B64Url.decode(parts[2]) else { return .text(400, ProxyErrorCode.invalidUrl.rawValue) }
        let name = parts.count >= 4 ? parts[3] : ""
        let ext = (name as NSString).pathExtension.lowercased()
        let kind = Http.resourceKind(extension: ext, contentType: nil)

        if session.stale { return .text(410, "stale") }

        let knownPlaylist = session.lock.withLock { session.playlists[absolute] != nil }
        if kind == .playlist || knownPlaylist {
            return loadPlaylist(session: session, url: absolute)
        }
        if kind == .key {
            return passThrough(session: session, url: absolute, range: range, cacheState: "passthrough")
        }
        return serveSegment(session: session, url: absolute, range: range)
    }

    private func loadPlaylist(session: Session, url: String) -> HttpReply {
        let cached = session.lock.withLock { session.playlists[url] }
        if let cached {
            // A cacheable VOD playlist never changes, but live/event must be refetched or playback
            // freezes on the first segment list.
            let age = Date().timeIntervalSince1970 - cached.fetchedAt
            if cached.result.cacheable || age < playlistTtl(resource: cached) {
                return playlistReply(cached, range: nil)
            }
        }
        let cfg = config
        let profile = TimeoutProfile.playlist(cfg)
        let result = origin.fetchPlaylist(url: url, headers: headersFor(session: session, url: url),
                                          profile: profile, maxBytes: cfg.maxPlaylistBytes)
        mergeCookies(session: session, url: url, setCookies: result.cookies)
        if let error = result.error {
            notifyError(session: session, url: url, code: error, attempt: 1, originStatus: nil)
            return errorReply(error)
        }
        guard let data = result.data else {
            return errorReply(.emptyResponse)
        }
        if !PlaylistRewriter.looksLikePlaylist(data) {
            // Not a playlist: serve it as-is (buffer-then-serve); there is nothing to cache.
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

        session.lock.withLock {
            session.playlists[url] = resource
            session.playlists[finalUrl] = resource
            if rewritten.kind == .media {
                session.activeMedia = resource
                // The player memorized the URLs from this playlist; after later refetches they must
                // still resolve to the same index or the cache silently degrades to pass-through.
                session.pushIndexMap(resource.indexByUrl)
                if !rewritten.cacheable {
                    session.cacheable = false
                    Log.line("playlist", "без кэша: \(rewritten.reason) \(url)")
                }
                ensureStreamLocked(session: session, fingerprint: rewritten.fingerprint)
            }
        }
        if rewritten.kind == .media {
            // Prewarm only for cacheable VOD: in live/BYTERANGE/DRM the first "segments" are either
            // wasted traffic or the whole media file.
            if cfg.prewarmSegments > 0, session.cacheable {
                prewarm(session: session, resource: resource)
            }
        }
        return playlistReply(resource, range: nil)
    }

    /// TTL of a non-cacheable playlist: one target duration, but at least 2 s.
    private func playlistTtl(resource: PlaylistResource) -> TimeInterval {
        max(resource.result.media.targetDuration, 2)
    }

    private struct RefreshOutcome {
        var performed = false
        var segmentsChanged = false
    }

    /// Media playlist restart: refetch the active media playlist and swap in the fresh copy only when
    /// the segment structure is unchanged (same fingerprint, so the cache stays valid).
    /// CDN signatures and hosts expire; a fresh playlist returns current URLs, so a segment retry
    /// reaches a live CDN edge and the player does not need a remount.
    private func refreshMedia(session: Session) -> RefreshOutcome {
        guard let current = session.lock.withLock({ session.activeMedia }) else { return RefreshOutcome() }
        let now = Date().timeIntervalSince1970
        // A retry storm must not become a playlist request storm.
        let busy = session.lock.withLock { () -> Bool in
            if now - session.playlistRefreshedAt < 5 { return true }
            session.playlistRefreshedAt = now
            return false
        }
        if busy { return RefreshOutcome() }

        let cfg = config
        let url = current.originUrl
        let profile = TimeoutProfile.refresh(cfg)
        let result = origin.fetchPlaylist(url: url, headers: headersFor(session: session, url: url),
                                          profile: profile, maxBytes: cfg.maxPlaylistBytes,
                                          waitSec: Double(profile.firstByteMs) / 1000 + 5)
        mergeCookies(session: session, url: url, setCookies: result.cookies)
        guard result.error == nil, let data = result.data, PlaylistRewriter.looksLikePlaylist(data) else {
            Log.line("refresh", "плейлист не перечитан: \(result.error?.rawValue ?? "не плейлист") \(url)")
            // 401/403/410 means the signature died, not the segment, and JS must know so it can
            // fail open or re-resolve.
            if let error = result.error, error.isAuth {
                notifyError(session: session, url: url, code: error, attempt: 1, originStatus: nil)
            }
            return RefreshOutcome()
        }
        let text = String(decoding: data, as: UTF8.self)
        let finalUrl = result.finalUrl ?? url
        let rewritten = PlaylistRewriter.rewrite(text: text, finalUrl: finalUrl) { [weak self] absolute, kind in
            guard let self, let server = self.server else { return absolute }
            return self.proxyUrl(session: session, absolute: absolute, kind: kind, port: server.boundPort)
        }
        // A different structure means different content, so the playlist must not be swapped: the
        // segment cache would become garbage and `ensureStreamLocked` would wipe the directory mid-playback.
        guard rewritten.kind == .media, rewritten.fingerprint == current.result.fingerprint else {
            Log.line("refresh", "структура сегментов изменилась (\(rewritten.reason)) — остаёмся на старом")
            return RefreshOutcome(performed: true, segmentsChanged: false)
        }
        let changed = segmentsChanged(current: current, fresh: rewritten)
        let resource = PlaylistResource(originUrl: url, finalUrl: finalUrl, text: rewritten.text, result: rewritten)
        let stream = session.lock.withLock { () -> CacheStore.Stream? in
            session.playlists[url] = resource
            session.playlists[finalUrl] = resource
            session.activeMedia = resource
            session.pushIndexMap(resource.indexByUrl)
            return session.stream
        }
        // Prefetch treats failed segments as dead for `cooldownSec`; a playlist change must clear that
        // cooldown, or the window ahead never downloads again.
        if let stream {
            let last = max(0, session.lastSegmentIndex)
            let end = min(rewritten.media.segments.count - 1, last + max(1, cfg.prefetchMaxSegments))
            if end >= last {
                for index in last...end {
                    cache.update(stream, index: index) { $0.failedUntil = 0 }
                }
            }
        }
        Log.line("refresh", "манифест перезапущен: сегментов \(rewritten.media.segments.count) "
            + "changed=\(changed) \(url)")
        return RefreshOutcome(performed: true, segmentsChanged: changed)
    }

    /// Compare URLs by index; the signature or host in the query may have been refreshed.
    private func segmentsChanged(current: PlaylistResource, fresh: RewriteResult) -> Bool {
        let before = current.result.media.segments
        let after = fresh.media.segments
        if before.count != after.count { return true }
        for i in after.indices where before[i].url != after[i].url { return true }
        return false
    }

    private func playlistReply(_ resource: PlaylistResource, range: String?) -> HttpReply {
        HttpReply(status: 200,
                  headers: ["Content-Type": "application/vnd.apple.mpegurl", "Cache-Control": "no-store"],
                  body: BytesBody(resource.data), length: Int64(resource.data.count), cacheState: "hit")
    }

    private func serveSegment(session: Session, url: String, range: String?) -> HttpReply {
        let cfg = config
        let snapshot = session.lock.withLock { (session.activeMedia, session.stream) }
        guard session.cacheable, let media = snapshot.0, let stream = snapshot.1 else {
            return passThrough(session: session, url: url, range: range, cacheState: "passthrough")
        }
        guard let index = session.indexOf(url) else {
            return passThrough(session: session, url: url, range: range, cacheState: "passthrough")
        }
        session.lock.withLock { session.lastSegmentIndex = max(session.lastSegmentIndex, index) }
        let segment = media.result.media.segments.first(where: { $0.index == index })

        var entry = cache.ensureEntry(stream, index: index, now: Date().timeIntervalSince1970)
        // Check the hit before eviction, or a complete segment goes to the network on a full disk.
        if entry.complete {
            bumpHits()
            // Segment is complete, so its retries are no longer needed; a new failure starts over.
            session.lock.withLock { _ = session.recoveriesGaveUp.remove(index) }
            let type = entry.contentType ?? Http.contentType(forExtension: urlPathExtension(url))
            return HttpReply(status: 200, headers: cacheHeaders(type: type, state: "hit"),
                             body: FileBody(url: entry.file), length: entry.prefix)
        }
        if entry.failedUntil > Date().timeIntervalSince1970 {
            // The cooldown limits prefetch only. A player request still retries: a 502 on a dead
            // segment made the player treat the origin as failed and remount the stream even though
            // the cache was healthy.
            cache.update(stream, index: index) { $0.failedUntil = 0 }
        }
        if !cache.evictIfNeeded(required: 0, protecting: [session.cacheKey], now: Date().timeIntervalSince1970) {
            return passThrough(session: session, url: url, range: range, cacheState: "passthrough")
        }
        bumpMisses()

        guard let feed = ensureDownload(session: session, stream: stream, index: index, segment: segment,
                                        profile: .interactive(cfg)) else {
            return passThrough(session: session, url: url, range: range, cacheState: "passthrough")
        }
        if let error = feed.waitForMeta(timeout: cfg.reqFirstByteSec) {
            // runDownload emits the event; otherwise one failure would produce two onError callbacks.
            return errorReply(error)
        }
        let total = feed.total
        if total <= 0 {
            return passThrough(session: session, url: url, range: range, cacheState: "passthrough")
        }
        let type = feed.responseType ?? entry.contentType ?? Http.contentType(forExtension: urlPathExtension(url))
        cache.update(stream, index: index) { $0.contentType = type }
        schedulePrefetch(session: session, stream: stream, resource: media, from: index)
        entry = cache.ensureEntry(stream, index: index, now: Date().timeIntervalSince1970)
        return HttpReply(status: 200, headers: cacheHeaders(type: type, state: "miss"),
                         body: GrowingFileBody(url: entry.file, feed: feed), length: total)
    }

    /// Response headers. `X-MediaProxy-Cache` is present only in debug mode.
    private func cacheHeaders(type: String, state: String) -> [String: String] {
        var headers = ["Content-Type": type, "Cache-Control": "no-store"]
        if config.debug { headers["X-MediaProxy-Cache"] = state }
        return headers
    }

    private func passThrough(session: Session, url: String, range: String?, cacheState: String) -> HttpReply {
        let cfg = config
        let feed = ByteFeed(file: nil)
        let start = Http.rangeStart(range)
        let profile = TimeoutProfile.interactive(cfg)
        origin.open(OriginClient.Request(url: url, headers: headersFor(session: session, url: url),
                                         rangeStart: start, profile: profile, isPrefetch: false,
                                         expectPlaylist: false),
                    feed: feed) { [weak self] _, cookies in
            self?.mergeCookies(session: session, url: url, setCookies: cookies)
        }
        if let error = feed.waitForMeta(timeout: cfg.reqFirstByteSec) {
            notifyError(session: session, url: url, code: error, attempt: 1, originStatus: nil)
            return errorReply(error)
        }
        let total = feed.total
        let type = feed.responseType ?? Http.contentType(forExtension: urlPathExtension(url))
        var headers = cacheHeaders(type: type, state: cacheState)
        if total < 0 {
            var reply = HttpReply(status: 200, headers: headers, body: StreamBody(feed: feed), length: -1)
            reply.rangeHandled = true
            return reply
        }
        if start > 0 {
            // `total` is the full resource size, so Content-Range runs from `start` to `total - 1`
            // and the body length is the tail.
            headers["Content-Range"] = "bytes \(start)-\(total - 1)/\(total)"
            var reply = HttpReply(status: 206, headers: headers, body: StreamBody(feed: feed),
                                  length: total - start)
            reply.rangeHandled = true
            return reply
        }
        var reply = HttpReply(status: 200, headers: headers, body: StreamBody(feed: feed), length: total)
        reply.rangeHandled = true
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
        // A live download is returned as-is. A failed feed that is still registered is not: the
        // player's retry would hit the same error without ever contacting the origin.
        if let existing = downloads[key], existing.feed.error == nil {
            downloadsLock.unlock()
            return existing.feed
        }
        // Eviction reads the config itself, so no cfg is passed here.
        guard cache.evictIfNeeded(required: 0, protecting: [session.cacheKey], now: Date().timeIntervalSince1970) else {
            downloadsLock.unlock()
            return nil
        }
        let feed = openFeed(stream, index)
        let download = Download(key: key, session: session, stream: stream, segment: segment,
                                feed: feed, isPrefetch: false,
                                url: segment?.url ?? session.originUrl)
        downloads[key] = download
        downloadsLock.unlock()
        cache.retain(stream)
        cache.ensureEntry(stream, index: index, now: Date().timeIntervalSince1970)
        cache.update(stream, index: index) { entry in
            entry.downloading = true
            entry.prefix = feed.written
            entry.complete = false
            entry.failedUntil = 0
        }
        // Downloads run in parallel on an OperationQueue, not a serial queue: one stalled origin
        // would otherwise block every other segment.
        interactiveQueue.addOperation { [weak self] in
            self?.runDownload(download, profile: profile)
        }
        return feed
    }

    /// Resumable feed: an existing prefix on disk is continued, not overwritten.
    private func openFeed(_ stream: CacheStore.Stream, _ index: Int) -> ByteFeed {
        let file = cache.fileURL(stream, index)
        return ByteFeed(file: file, resumeFrom: Fs.size(file))
    }

    private func runDownload(_ download: Download, profile: TimeoutProfile) {
        let cfg = config
        let index = download.segment?.index ?? 0
        var attempt = 0
        do {
            // The file already holds the whole segment but the entry is not committed. Fetching more
            // would send a Range past the resource and get a 416.
            let knownTotal = cache.entry(download.stream, index: index)?.total ?? -1
            if download.feed.written > 0, knownTotal > 0, download.feed.written >= knownTotal {
                download.feed.finish()
                cache.update(download.stream, index: index) { e in
                    e.total = knownTotal
                    e.prefix = knownTotal
                    e.complete = true
                    e.downloading = false
                }
                if download.isPrefetch { bumpPrefetched() }
                return
            }
            while !download.cancelled {
                // A fresh URL after a playlist refetch must be picked up by this same download, or
                // the client response has to be dropped and the session remounts. OriginClient guards
                // prefix stitching: a 206 with a different start becomes RANGE_UNSUPPORTED, and
                // If-Range is sent when an ETag exists.
                if let fresh = currentUrl(session: download.session, index: index), fresh != download.url {
                    Log.line("retry", "сегмент \(index) → свежий URL")
                    downloadsLock.lock()
                    download.url = fresh
                    downloadsLock.unlock()
                }
                let start = download.feed.written
                let ifRange = start > 0 ? (download.feed.etagValue ?? cache.entry(download.stream, index: index)?.etag) : nil
                let semaphore = DispatchSemaphore(value: 0)
                var terminal: ProxyErrorCode? = nil
                var receivedCookies: [String] = []
                // Read the URL under the lock; the stall detector may have swapped in a fresh one
                // from another thread.
                let target = downloadsLock.withLock { download.url }

                origin.open(OriginClient.Request(url: target,
                                                 headers: headersFor(session: download.session, url: target),
                                                 rangeStart: start,
                                                 ifRange: ifRange,
                                                 profile: profile,
                                                 isPrefetch: download.isPrefetch,
                                                 expectPlaylist: false),
                            feed: download.feed) { error, cookies in
                    terminal = error
                    receivedCookies = cookies
                    semaphore.signal()
                }
                _ = semaphore.wait(timeout: .now() + 300)
                mergeCookies(session: download.session, url: target, setCookies: receivedCookies)

                if terminal == nil {
                    let total = download.feed.total
                    let written = download.feed.written
                    cache.ensureEntry(download.stream, index: index, now: Date().timeIntervalSince1970)
                    cache.update(download.stream, index: index) { e in
                        e.total = total
                        e.prefix = written
                        e.complete = total >= 0 && written == total
                        e.downloading = false
                        e.feed = download.feed
                        e.contentType = download.feed.responseType
                        e.etag = download.feed.etagValue
                        e.lastAccess = Date().timeIntervalSince1970
                    }
                    finishDownload(download, error: nil)
                    return
                }

                let code = terminal ?? .connectFailed
                if code == .truncated { bumpTruncated() }
                if code == .rangeUnsupported, start > 0 {
                    // The prefix does not match the current resource, so restart the segment;
                    // otherwise the client gets a splice of two different files.
                    if let file = download.feed.file { Fs.truncate(file, to: 0) }
                    download.feed.fail(code)
                    cache.ensureEntry(download.stream, index: index, now: Date().timeIntervalSince1970)
                    cache.update(download.stream, index: index) { e in
                        e.total = -1; e.prefix = 0; e.complete = false; e.downloading = false; e.etag = nil
                    }
                    notifyError(session: download.session, url: target, code: code,
                                attempt: attempt + 1, originStatus: nil)
                    finishDownload(download, error: code)
                    return
                }

                attempt += 1
                let mayRetry = download.isPrefetch || start > 0 || download.feed.written > start
                if code.isRetryable && mayRetry && attempt <= cfg.prefetchRetries && !download.cancelled {
                    let backoff = cfg.retryBackoffMs[min(attempt - 1, cfg.retryBackoffMs.count - 1)]
                    Thread.sleep(forTimeInterval: Double(backoff) / 1000)
                    continue
                }

                // For the first failure the player retries, not us: one player request means one
                // origin request. A second failure means the segment's CDN edge is at fault, so
                // refetch the media playlist here and retry on the fresh segment URLs. The trigger
                // is a changed URL, not the fact that someone refetched the playlist; otherwise the
                // stall detector's anti-storm gate would swallow this download's own retry.
                if code.isRetryable, attempt >= 2, !download.refreshed, !download.cancelled {
                    _ = refreshMedia(session: download.session)
                    if let fresh = currentUrl(session: download.session, index: index), fresh != download.url {
                        download.refreshed = true
                        downloadsLock.lock()
                        download.url = fresh
                        downloadsLock.unlock()
                        attempt = 0
                        Log.line("retry", "сегмент \(index) → \(fresh)")
                        continue
                    }
                }

                let written = download.feed.written
                let total = download.feed.total
                download.feed.fail(code)
                cache.ensureEntry(download.stream, index: index, now: Date().timeIntervalSince1970)
                cache.update(download.stream, index: index) { e in
                    e.total = total
                    e.prefix = written
                    e.complete = total >= 0 && written == total
                    e.downloading = false
                    e.failedUntil = Date().timeIntervalSince1970 + cfg.cooldownSec
                    e.feed = download.feed
                    e.etag = download.feed.etagValue
                }
                notifyError(session: download.session, url: target, code: code,
                            attempt: attempt, originStatus: nil)
                finishDownload(download, error: code)
                return
            }
            finishDownload(download, error: .networkChanged)
        }
    }

    private func finishDownload(_ download: Download, error: ProxyErrorCode?) {
        downloadsLock.lock()
        // Remove only our own entry: while we were failing (feed.fail is already visible to readers),
        // a new download may have started under the same key.
        if downloads[download.key] === download {
            downloads.removeValue(forKey: download.key)
            downloadProgress.removeValue(forKey: download.key)
        }
        downloadsLock.unlock()
        cache.release(download.stream)
        // No marker is set for the next segment here; prefetch would count it as done without
        // downloading it. Only a completed prefetch download counts.
        if error == nil && download.isPrefetch {
            bumpPrefetched()
        }
    }

    private func prewarm(session: Session, resource: PlaylistResource) {
        let stream = session.lock.withLock { session.cacheable ? session.stream : nil }
        guard let stream else { return }
        let count = min(max(0, config.prewarmSegments), resource.result.media.segments.count)
        for segment in resource.result.media.segments.prefix(count) {
            enqueuePrefetch(session: session, stream: stream, segment: segment)
        }
    }

    @discardableResult
    private func schedulePrefetch(session: Session, stream: CacheStore.Stream,
                                  resource: PlaylistResource, from index: Int, force: Bool = false) -> Int {
        guard session.cacheable, config.prefetchMaxSegments > 0 else { return 0 }
        let media = resource.result.media
        // The window starts after `index` (`+1`). nudge() passes the previous index so the stalled
        // segment is included. `force` ignores the cooldown on a failed download.
        let first = max(0, index + 1)
        guard first < media.segments.count else { return 0 }
        let avg = media.segments.isEmpty ? 6 : max(0.5, media.totalDuration / Double(media.segments.count))
        let wanted = Int(ceil(max(1, config.prefetchSeconds) / avg))
        let count = min(max(0, config.prefetchMaxSegments), max(1, wanted))
        guard count > 0 else { return 0 }
        let end = min(media.segments.count - 1, first + count - 1)
        var queued = 0
        for i in first...end {
            let segment = media.segments[i]
            if enqueuePrefetch(session: session, stream: stream, segment: segment, force: force) { queued += 1 }
        }
        return queued
    }

    @discardableResult
    private func enqueuePrefetch(session: Session, stream: CacheStore.Stream,
                                 segment: PlaylistSegment, force: Bool = false) -> Bool {
        guard session.cacheable else { return false }
        let key = "\(stream.key)#\(segment.index)"
        downloadsLock.lock()
        // Dedup uses the live download and the cache entry. There is no permanent "already queued"
        // marker: it blocked re-downloads forever. A failed download (feed with an error) does not
        // count as a duplicate.
        if let inFlight = downloads[key], inFlight.feed.error == nil {
            downloadsLock.unlock()
            return false
        }
        let existing = cache.entry(stream, index: segment.index)
        // Already downloaded or recently failed: a new request only wastes traffic unless forced.
        if existing?.complete == true || (!force && (existing?.failedUntil ?? 0) > Date().timeIntervalSince1970) {
            downloadsLock.unlock()
            return false
        }
        let cfg = config
        guard cache.evictIfNeeded(required: 0, protecting: [session.cacheKey], now: Date().timeIntervalSince1970) else {
            downloadsLock.unlock()
            return false
        }
        let feed = openFeed(stream, segment.index)
        let download = Download(key: key, session: session, stream: stream, segment: segment,
                                feed: feed, isPrefetch: true, url: segment.url)
        downloads[key] = download
        downloadsLock.unlock()

        prefetchQueue.addOperation { [weak self] in
            guard let self else { return }
            self.cache.retain(stream)
            // Prefetch has no player request to create the entry; without ensureEntry the update is a no-op.
            self.cache.ensureEntry(stream, index: segment.index, now: Date().timeIntervalSince1970)
            self.cache.update(stream, index: segment.index) { entry in
                entry.downloading = true
                entry.prefix = feed.written
                entry.complete = false
                entry.failedUntil = 0
            }
            self.runDownload(download, profile: .prefetch(cfg))
        }
        return true
    }

    /// Call with `session.lock` held.
    private func ensureStreamLocked(session: Session, fingerprint: String) {
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
        let total = cache.totalBytes()
        let cfg = config
        return [
            "port": port, "running": running, "generation": generation,
            "bytes": total, "maxBytes": cfg.maxCacheBytes,
            "freeDiskBytes": Fs.freeDiskBytes(at: root),
            "streams": sessionsLock.withLock { sessions.count },
            "hits": countersLock.withLock { hits },
            "misses": countersLock.withLock { misses },
            "evictions": cache.evictedCount(),
            "prefetched": countersLock.withLock { prefetchedCount },
            "stallKicks": countersLock.withLock { stallKicks },
            "originTruncated": countersLock.withLock { originTruncated },
            "maxSessions": cfg.maxSessions,
        ]
    }

    public func streamState(_ streamId: String) -> [String: Any] {
        guard let session = sessionById(streamId) else { return ["cacheKey": "", "cacheable": false, "segments": [], "cachedAheadSec": 0] }
        let snapshot = session.lock.withLock { (session.activeMedia, session.stream, session.lastSegmentIndex) }
        guard let media = snapshot.0, let stream = snapshot.1 else {
            return ["cacheKey": session.cacheKey, "cacheable": session.cacheable, "segments": [], "cachedAheadSec": 0]
        }
        var segments: [[String: Any]] = []
        var cachedAhead = 0.0
        let last = max(0, snapshot.2)
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
        // No streamId clears everything. An unknown streamId clears nothing: otherwise clearCache
        // on a released id would silently wipe the whole app cache.
        let freed: Int64
        if let streamId {
            guard let session = sessionById(streamId) else { return ["freedBytes": 0] }
            freed = cache.clear(cacheKey: session.cacheKey)
        } else {
            freed = cache.clear(cacheKey: nil)
        }
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
        var victims: [Session] = []
        while sessions.count > maxSessions {
            // Evict only other sessions; with equal timestamps the new one could be dropped.
            guard let victim = sessions.values.filter({ $0.id != keeping })
                .min(by: { $0.lastAccess < $1.lastAccess }) else { break }
            sessions.removeValue(forKey: victim.id)
            victims.append(victim)
        }
        // Release resources outside the sessions lock; releaseSessionResources takes other locks.
        for victim in victims { releaseSessionResources(victim) }
    }

    /// Current segment URL by index. After a media playlist restart the playlist holds new URLs, and
    /// a retry must use them rather than the one that failed.
    private func currentUrl(session: Session, index: Int) -> String? {
        guard let media = session.lock.withLock({ session.activeMedia }) else { return nil }
        return media.result.media.segments.first(where: { $0.index == index })?.url
    }

    private func urlPathExtension(_ url: String) -> String {
        (URL(string: url)?.pathExtension ?? "").lowercased()
    }

    private func bumpHits() { countersLock.lock(); hits += 1; countersLock.unlock() }
    private func bumpStallKicks() { countersLock.lock(); stallKicks += 1; countersLock.unlock() }
    private func bumpTruncated() { countersLock.lock(); originTruncated += 1; countersLock.unlock() }
    private func bumpMisses() { countersLock.lock(); misses += 1; countersLock.unlock() }
    private func bumpPrefetched() { countersLock.lock(); prefetchedCount += 1; countersLock.unlock() }

    private func notifyError(session: Session, url: String, code: ProxyErrorCode, attempt: Int,
                             originStatus: Int?) {
        if code.isAuth {
            if !session.authNotified {
                session.authNotified = true
                emit("onAuthRequired", ["streamId": session.id, "url": url,
                                        "originStatus": originStatus ?? originStatusCode(code)])
            }
            return
        }
        emit("onError", [
            "code": code.rawValue, "streamId": session.id, "url": url,
            "originStatus": originStatus ?? originStatusCode(code), "attempt": attempt,
            "retryable": code.isRetryable, "fatal": !code.isRetryable,
            "message": code.rawValue,
        ])
    }

    /// Origin status, not our HTTP reply to the player: `onAuthRequired.originStatus` must be 403/401/400/410.
    private func originStatusCode(_ code: ProxyErrorCode) -> Int {
        switch code {
        case .origin400: return 400
        case .origin401: return 401
        case .origin403: return 403
        case .origin404: return 404
        case .origin410: return 410
        case .origin429: return 429
        case .origin5xx: return 500
        default: return 0
        }
    }

    private func emit(_ name: String, _ payload: [String: Any]) {
        onEvent?(name, payload)
    }

    /// Origin request headers: session headers plus cookies for the host from the per-session jar.
    private func headersFor(session: Session, url: String) -> [String: String] {
        guard let host = URL(string: url)?.host?.lowercased() else { return session.headers }
        return session.lock.withLock { () -> [String: String] in
            guard let jar = session.cookies[host], !jar.isEmpty else { return session.headers }
            let cookie = jar.sorted(by: { $0.key < $1.key })
                .map { "\($0.key)=\($0.value)" }
                .joined(separator: "; ")
            var result = session.headers
            if let existing = result.first(where: { $0.key.lowercased() == "cookie" })?.value, !existing.isEmpty {
                result["Cookie"] = existing + "; " + cookie
            } else {
                result["Cookie"] = cookie
            }
            return result
        }
    }

    private func mergeCookies(session: Session, url: String, setCookies: [String]) {
        guard !setCookies.isEmpty, let host = URL(string: url)?.host?.lowercased() else { return }
        session.lock.withLock {
            var jar = session.cookies[host] ?? [:]
            for raw in setCookies {
                let pair = raw.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)
                    .first.map { String($0) } ?? raw
                guard let eq = pair.firstIndex(of: "=") else { continue }
                let name = pair[..<eq].trimmingCharacters(in: .whitespaces)
                let value = pair[pair.index(after: eq)...].trimmingCharacters(in: .whitespaces)
                if !name.isEmpty { jar[name] = value }
            }
            session.cookies[host] = jar
        }
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
