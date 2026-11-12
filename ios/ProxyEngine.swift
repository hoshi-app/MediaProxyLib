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
        /// Когда последний раз перечитывали media-плейлист ради ретрая сегмента (анти-шторм).
        var playlistRefreshedAt: TimeInterval = 0
        /// Когда клиент (плеер) последний раз просил ресурс через прокси — гейт вотчдога.
        var lastClientRequestAt: TimeInterval = Date().timeIntervalSince1970
        /// Сегменты, по которым кики уже не помогли: больше не тратим трафик до прогресса.
        var recoveriesGaveUp: Set<Int> = []
        /// Поколения карт «URL сегмента → индекс». После перечитывания манифеста URL меняются, а
        /// плеер продолжает просить СТАРЫЕ (свой плейлист он не перезапрашивает). Без кольца такие
        /// запросы уходили бы в pass-through, и кэш тихо переставал работать.
        var indexGenerations: [[String: Int]] = []
        /// Per-session cookie jar: host → name → value (§6.1). Никакого HTTPCookieStorage.
        var cookies: [String: [String: String]] = [:]
        /// Один лок на изменяемое состояние сессии: запросы плейлиста/сегментов идут с разных потоков.
        let lock = NSRecursiveLock()

        func pushIndexMap(_ map: [String: Int]) {
            lock.withLock {
                indexGenerations.insert(map, at: 0)
                if indexGenerations.count > 3 { indexGenerations.removeLast() }
            }
        }

        /// Сначала текущий плейлист, затем старые поколения (R1: не наоборот).
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
        /// Текущий URL сегмента. Меняется после «перезапуска манифеста»: повтор обязан уйти на свежий
        /// URL (другой edge/подпись), а не в тот, который уже отвалился.
        var url: String
        /// Перечитывали ли плейлист ради этого сегмента: одна такая попытка на загрузку.
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

    /// Наблюдение за прогрессом КЛИЕНТСКИХ загрузок для вотчдога: когда байты перестали расти.
    /// Живёт отдельно от `Download`, чтобы не трогать горячие пути; чистится вместе с загрузкой.
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

    /// События в JS. Вызывается из фоновых потоков — модуль сам решает, как доставлять.
    public var onEvent: ((_ name: String, _ payload: [String: Any]) -> Void)?

    public init(root: URL) {
        self.root = root
        // Кэш и клиент создаются в конструкторе, а не в start(): getStats()/getStreamState()
        // вызываются из JS и до start(), и не должны падать на nil.
        self.cache = CacheStore(root: root, config: { ProxyConfig() },
                                diskFree: { Fs.freeDiskBytes(at: root) })
        self.origin = OriginClient()
    }

    // MARK: - lifecycle

    @discardableResult
    public func start() -> (running: Bool, port: Int, error: String?) {
        if running, let server { return (true, server.boundPort, nil) }
        try? Fs.ensureDir(root)
        // rootUrl фиксируем локально: в escaping-замыкании обращение к свойству требует self,
        // а захватывать self в замыкании кэша (self → cache → замыкание) не нужно.
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

    /// Освобождает всё, что сессия держала: ref кэша, свои загрузки, маркеры prefetch.
    /// Без этого вытесненная сессия навсегда оставляла бы стрим с `refs > 0` (то есть неудаляемым).
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

    /// «Догрузи вперёд» для сторожа плеера. Отсчёт — от ПОСЛЕДНЕГО ЗАПРОШЕННОГО сегмента включительно:
    /// залип обычно именно он, а не следующий за ним. `force` — снять кулдаун неудачных сегментов.
    public func nudge(_ streamId: String) -> [String: Any] {
        guard let session = sessionById(streamId), session.cacheable else { return ["queued": 0] }
        return session.lock.withLock { () -> [String: Any] in
            guard let media = session.activeMedia, let stream = session.stream else { return ["queued": 0] }
            let from = max(0, session.lastSegmentIndex) - 1
            let queued = schedulePrefetch(session: session, stream: stream, resource: media, from: from, force: true)
            return ["queued": queued]
        }
    }

    /// «Перезапуск манифеста» по просьбе JS (сторож плеера): перечитываем media-плейлист и ставим
    /// заново загрузку залипшего окна. Плеер при этом НЕ перемонтируется — это и есть цель.
    /// `segmentsChanged` — изменился ли хоть один URL сегментов (иначе рестарт был формальностью).
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

    // MARK: - вотчдог зависшей загрузки

    private func startRecoverWatchdog() {
        recoverTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: watchdogQueue)
        timer.schedule(deadline: .now() + 2, repeating: 2)
        timer.setEventHandler { [weak self] in self?.recoverTick() }
        timer.resume()
        recoverTimer = timer
    }

    /// Прокси сам замечает «клиент просит, а байты не идут» и перечитывает манифест — плееру для
    /// этого перемонтироваться не нужно. Гейты: только клиентские загрузки, только сессии с живым
    /// клиентом, только после cfg.stallKickSec тишины, не больше cfg.recoveryMax киков на сегмент.
    ///
    /// Порядок локов строго `session.lock → downloadsLock` (так в nudge/schedulePrefetch): под
    /// `downloadsLock` НЕ берём `session.lock`, иначе встречный порядок даёт дедлок.
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
            // Клиент ушёл (пауза/фон/перемонт) — лечить нечего и незачем.
            if now - lastClient > 15 { continue }
            if progress.kicks >= cfg.recoveryMax {
                // Все кики без прогресса — сегмент мёртв, дальше решает плеер.
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
        // Следующий кик — не раньше чем через stallKickSec: рефреш уже мог занять время.
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
            // Метка «клиент ещё жив»: вотчдог не лечит сессии, к которым никто не обращается.
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
            // Кэшируемый VOD-плейлист неизменен, а live/event обязан перечитываться: иначе
            // воспроизведение «замерзает» на первом же списке сегментов (§5.5).
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

        session.lock.withLock {
            session.playlists[url] = resource
            session.playlists[finalUrl] = resource
            if rewritten.kind == .media {
                session.activeMedia = resource
                // Плеер запомнил URL из ЭТОГО плейлиста: после будущих перечитываний они должны
                // по-прежнему адресовать тот же индекс (иначе кэш тихо превращается в pass-through).
                session.pushIndexMap(resource.indexByUrl)
                if !rewritten.cacheable {
                    session.cacheable = false
                    Log.line("playlist", "без кэша: \(rewritten.reason) \(url)")
                }
                ensureStreamLocked(session: session, fingerprint: rewritten.fingerprint)
            }
        }
        if rewritten.kind == .media {
            // Прогрев только для кэшируемого VOD: у live/BYTERANGE/DRM первые «сегменты» —
            // это либо пустая трата трафика, либо весь медиафайл целиком.
            if cfg.prewarmSegments > 0, session.cacheable {
                prewarm(session: session, resource: resource)
            }
        }
        return playlistReply(resource, range: nil)
    }

    /// Срок жизни не-cacheable плейлиста: один таргет-дирекшн, но не меньше 2 с.
    private func playlistTtl(resource: PlaylistResource) -> TimeInterval {
        max(resource.result.media.targetDuration, 2)
    }

    /// Итог перечитывания манифеста: сделан ли рефреш и сменились ли хоть какие-то URL сегментов.
    private struct RefreshOutcome {
        var performed = false
        var segmentsChanged = false
    }

    /// «Перезапуск манифеста» на уровне прокси: перечитываем media-плейлист и, если структура
    /// сегментов та же (одинаковый fingerprint — кэш остаётся валидным), подменяем его свежей копией.
    /// Зачем: подпись/хост сегментов у CDN устаревают, а свежий запрос плейлиста отдаёт актуальные
    /// URL. Так повтор сегмента уходит на живой edge, и плееру не нужно перемонтироваться.
    private func refreshMedia(session: Session) -> RefreshOutcome {
        guard let current = session.lock.withLock({ session.activeMedia }) else { return RefreshOutcome() }
        let now = Date().timeIntervalSince1970
        // Шторм ретраев не должен превращаться в шторм запросов плейлиста.
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
            // 401/403/410 — умерла подпись, а не сегмент: JS обязан узнать (fail-open/перерезолв).
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
        // Другая структура — это уже другой контент: подменять плейлист нельзя, иначе кэш сегментов
        // станет мусором, а `ensureStreamLocked` снесёт каталог прямо во время игры.
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
        // Наш же prefetch считает неудачные сегменты «мёртвыми» на `cooldownSec`: после смены
        // манифеста кулдаун надо снять, иначе окно впереди так и не перезакачается.
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

    /// Сравниваем URL по индексу: подпись или хост в query могли обновиться.
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
        // Хит проверяется ДО эвикции: иначе на заполненном диске готовый сегмент уходил бы в сеть.
        if entry.complete {
            bumpHits()
            // Сегмент докачался — кики по нему больше не нужны (новый сбой начнёт счёт заново).
            session.lock.withLock { _ = session.recoveriesGaveUp.remove(index) }
            let type = entry.contentType ?? Http.contentType(forExtension: urlPathExtension(url))
            return HttpReply(status: 200, headers: cacheHeaders(type: type, state: "hit"),
                             body: FileBody(url: entry.file), length: entry.prefix)
        }
        if entry.failedUntil > Date().timeIntervalSince1970 {
            // Кулдаун — ограничение ТОЛЬКО для prefetch. Запросу плеера отдаём повтор: 502 на 30 с
            // «мёртвого» сегмента плеер воспринимал как отказ источника и перемонтировал поток
            // (лог 12.11: `[mp] error TRUNCATED` → 502 → `stream reload` при живом кэше).
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
            // Событие отдаёт загрузчик (runDownload): иначе на один сбой приходило бы два onError.
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

    /// Заголовки ответа. `X-MediaProxy-Cache` — только в debug (§4.2).
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
            // `total` — полный размер ресурса, поэтому Content-Range от `start` до `total-1`,
            // а длина тела — хвост. Раньше считалось `start + total`, и заголовок уезжал.
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
        // Живая загрузка отдаётся как есть. Уже УПАВШАЯ (feed с ошибкой), но ещё не снятая с учёта,
        // не отдаётся: иначе повторный запрос плеера мгновенно получает тот же отказ, ни разу не
        // сходив в origin («запрос плеера сходил в origin» в SelfTest).
        if let existing = downloads[key], existing.feed.error == nil {
            downloadsLock.unlock()
            return existing.feed
        }
        // cfg здесь не нужен: эвикция читает конфиг сама.
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
        // Загрузки идут параллельно (OperationQueue), а не на одной последовательной очереди:
        // иначе один висящий origin блокировал бы все остальные сегменты.
        interactiveQueue.addOperation { [weak self] in
            self?.runDownload(download, profile: profile)
        }
        return feed
    }

    /// Feed с докачкой: если на диске уже лежит префикс, он не затирается, а продолжается.
    private func openFeed(_ stream: CacheStore.Stream, _ index: Int) -> ByteFeed {
        let file = cache.fileURL(stream, index)
        return ByteFeed(file: file, resumeFrom: Fs.size(file))
    }

    private func runDownload(_ download: Download, profile: TimeoutProfile) {
        let cfg = config
        let index = download.segment?.index ?? 0
        var attempt = 0
        do {
            // Файл уже содержит весь сегмент, но запись не закоммичена: докачивать нечего,
            // иначе Range за пределом ресурса даст 416.
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
                // Свежий URL (после перечитывания манифеста — своим ретраем или вотчдогом) обязан
                // подхватываться этой же загрузкой: иначе клиентский ответ придётся рвать, а серия
                // уйдёт в перемонт. Склейку префикса страхует проверка Content-Range в OriginClient
                // (206 с чужим start → RANGE_UNSUPPORTED) и If-Range, когда есть ETag.
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
                // Читаем URL под замком: вотчдог мог поменять его на свежий с другого потока.
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
                    // Префикс не подходит к текущему ресурсу: начинаем сегмент заново, иначе
                    // отдадим склейку из двух разных файлов.
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

                // Правило «ретраит плеер, а не мы» в силе для первого облома: один запрос плеера =
                // один запрос к origin. Но если мы уже повторили и снова облом — виноват не поток,
                // а конкретный сегментный edge. Перечитываем манифест ЗДЕСЬ, в прокси (свежие URL
                // сегментов), и повторяем: плееру перемонтироваться не нужно. Условие перехода на
                // новый URL — смена URL, а не факт «кто-то перечитал плейлист» (иначе анти-шторм
                // вотчдога глушил бы собственный ретрай загрузки).
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
        // Только своя запись: пока мы падали (feed.fail уже виден читателям), под этим же ключом
        // могла стартовать новая загрузка — её снимать нельзя.
        if downloads[download.key] === download {
            downloads.removeValue(forKey: download.key)
            downloadProgress.removeValue(forKey: download.key)
        }
        downloadsLock.unlock()
        cache.release(download.stream)
        // Метка о следующем сегменте здесь НЕ ставится: иначе prefetch «считает» сегмент готовым,
        // ни разу его не скачав. Учёт — только по завершённой собственной загрузке prefetch.
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
        // `index` — последний запрошенный плеером сегмент; он включён в окно (`+1`), потому что
        // залипает обычно именно он. `force` — не считаться с кулдауном неудачной загрузки.
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
        // Дедуп — по живой загрузке и по состоянию записи. Постоянной метки «уже ставили» больше нет:
        // она глушила повторную догрузку навсегда (лог 12.11: nudge → `queued: 0` при залипшем
        // сегменте и пустом окне впереди). Упавшая загрузка (feed с ошибкой) дедупом не считается.
        if let inFlight = downloads[key], inFlight.feed.error == nil {
            downloadsLock.unlock()
            return false
        }
        let existing = cache.entry(stream, index: segment.index)
        // Уже скачано или недавно провалилось — новый запрос только тратит трафик (кроме force).
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
            // У prefetch нет «запроса плеера», который создал бы запись: без ensureEntry update молчит.
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

    /// Вызывать, держа `session.lock`.
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
        // Без streamId — чистим всё. С streamId неизвестной сессии — ничего: иначе clearCache
        // по уже освобождённому id молча стирал бы весь кэш приложения.
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
            // Вытесняем только ДРУГИЕ сессии: при равных timestamp могли выбросить новую.
            guard let victim = sessions.values.filter({ $0.id != keeping })
                .min(by: { $0.lastAccess < $1.lastAccess }) else { break }
            sessions.removeValue(forKey: victim.id)
            victims.append(victim)
        }
        // Освобождать ресурсы — ВНЕ замка сессий: releaseSessionResources берёт другие локи.
        for victim in victims { releaseSessionResources(victim) }
    }

    /// Актуальный URL сегмента по индексу: после «перезапуска манифеста» в плейлисте уже новые URL,
    /// и повтор должен идти на них, а не на тот, что отвалился.
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

    /// Код origin, а не наш HTTP-ответ плееру: `onAuthRequired.originStatus` должен быть 403/401/400/410.
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

    /// Заголовки origin-запроса: заголовки сессии + cookie из per-session jar по хосту (§6.1).
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
