package tv.mediaproxy.core

import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/**
 * Движок прокси: сессии, маршрутизация loopback-запросов, загрузки, кэш, prefetch, события.
 * JS управляет только конфигом и получает события; в медиапуть JS не вмешивается.
 */
class ProxyEngine(private val root: File) {
    data class ResolveOptions(
        val cacheKey: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val cache: Boolean = true,
        val prewarm: Boolean = true,
    )

    data class ResolveResult(
        val streamId: String,
        val url: String,
        val cached: Boolean,
        val reason: String,
        val generation: Int,
    )

    data class StartResult(
        val running: Boolean,
        val port: Int,
        val error: String?,
        val issues: List<String>,
    )

    class Session(
        val id: String,
        val originUrl: String,
        val cacheKey: String,
        val headers: Map<String, String>,
        @Volatile var cacheable: Boolean,
        val generation: Int,
        @Volatile var lastAccess: Long,
    ) {
        val lock = Any()
        val playlists = ConcurrentHashMap<String, PlaylistResource>()
        val cookies = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
        @Volatile var activeMedia: PlaylistResource? = null
        @Volatile var lastSegmentIndex: Int = -1
        @Volatile var stream: CacheStore.Stream? = null
        @Volatile var streamRefHeld: Boolean = false
        @Volatile var stale: Boolean = false
        @Volatile var authNotified: Boolean = false
    }

    class PlaylistResource(
        val originUrl: String,
        val finalUrl: String,
        val text: String,
        val result: RewriteResult,
    ) {
        val data: ByteArray = text.toByteArray(Charsets.UTF_8)
        val indexByUrl: Map<String, Int> = result.media.segments.associate { it.url to it.index }
    }

    private class Download(
        val key: String,
        val session: Session,
        val stream: CacheStore.Stream,
        val segment: PlaylistSegment,
        val feed: ByteFeed,
        val isPrefetch: Boolean,
    ) {
        val cancelled = AtomicBoolean(false)
    }

    @Volatile
    var config: ProxyConfig = ProxyConfig()
        private set

    private var cache = CacheStore(root) { config }
    private var origin = OriginClient { config }
    private var server: HttpServer? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var generation: Int = 0
        private set

    private val sessions = ConcurrentHashMap<String, Session>()
    private val downloads = HashMap<String, Download>()
    private val downloadsLock = Any()
    private val prefetched = HashSet<String>()

    private var interactive = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
    private var prefetchPool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
    private var loader = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
    private var passPool: java.util.concurrent.ExecutorService = createPassPool()

    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val prefetchedCount = AtomicLong(0)
    private val evictions = AtomicLong(0)

    /** События в JS: onError, onAuthRequired, onServerState, onCache. */
    var onEvent: ((name: String, payload: Map<String, Any?>) -> Unit)? = null

    init {
        Fs.ensureDir(root)
        resizePools()
    }

    // MARK: - lifecycle

    fun start(issues: List<String> = emptyList()): StartResult {
        if (running && server != null) {
            val s = server!!
            return StartResult(true, s.boundPort, null, issues)
        }
        Fs.ensureDir(root)
        cache = CacheStore(root) { config }
        origin = OriginClient { config }
        val http = HttpServer(
            host = "127.0.0.1",
            maxConnections = config.maxLoopbackConnections,
            idleTimeoutMs = (config.socketIdleSec * 1000).toLong(),
            handler = { method, path, range -> handle(method, path, range) },
        )
        return try {
            http.start()
            server = http
            running = true
            generation++
            ensurePools()
            resizePools()
            emit(
                "onServerState",
                mapOf("running" to true, "port" to http.boundPort, "baseUrl" to baseUrl,
                      "generation" to generation, "reason" to "started"),
            )
            StartResult(true, http.boundPort, null, issues)
        } catch (e: Exception) {
            emit(
                "onServerState",
                mapOf("running" to false, "port" to 0, "baseUrl" to "", "generation" to generation,
                      "reason" to "SERVER_START_FAILED"),
            )
            StartResult(false, 0, ProxyErrorCode.SERVER_START_FAILED.wire,
                        issues + ProxyErrorCode.SERVER_START_FAILED.wire)
        }
    }

    fun stop() {
        running = false
        server?.stop()
        server = null
        synchronized(downloadsLock) {
            downloads.values.forEach { it.cancelled.set(true) }
            downloads.clear()
        }
        runCatching { origin.shutdown() }
        interactive.shutdownNow()
        prefetchPool.shutdownNow()
        loader.shutdownNow()
        passPool.shutdownNow()
    }

    val baseUrl: String
        get() = server?.let { "http://127.0.0.1:${it.boundPort}" } ?: ""

    val port: Int
        get() = server?.boundPort ?: 0

    fun setConfig(patch: Map<String, Any?>) {
        config = config.withPatch(patch)
        Log.enabled = config.debug
        resizePools()
    }

    private fun resizePools() {
        interactive.corePoolSize = config.interactiveConcurrency
        interactive.maximumPoolSize = config.interactiveConcurrency
        prefetchPool.corePoolSize = config.prefetchConcurrency
        prefetchPool.maximumPoolSize = config.prefetchConcurrency
    }

    /**
     * stop() гасит пулы; start() после stop() обязан их пересоздать, иначе первый же сегмент
     * получит RejectedExecutionException и плеер — обрыв (рестарт сервера после фона/ошибки).
     */
    private fun ensurePools() {
        if (interactive.isShutdown) interactive = createPool(config.interactiveConcurrency)
        if (prefetchPool.isShutdown) prefetchPool = createPool(config.prefetchConcurrency)
        if (loader.isShutdown) loader = createPool(1)
        if (passPool.isShutdown) passPool = createPassPool()
    }

    private fun createPool(size: Int): ThreadPoolExecutor =
        ThreadPoolExecutor(size, size, 30, TimeUnit.SECONDS, LinkedBlockingQueue())

    private fun createPassPool(): java.util.concurrent.ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mediaproxy-pass").apply { isDaemon = true }
        }

    // MARK: - resolve / release

    fun resolve(originUrl: String, options: ResolveOptions): ResolveResult {
        val srv = server
        if (!running || srv == null) {
            return ResolveResult("", originUrl, false, ProxyErrorCode.NOT_STARTED.wire, generation)
        }
        if (!Http.isAllowedOrigin(originUrl) && !config.allowLoopbackOrigins) {
            return ResolveResult("", originUrl, false, ProxyErrorCode.INVALID_URL.wire, generation)
        }
        val sid = Ids.sessionId()
        val cacheKey = options.cacheKey ?: originUrl
        val session = Session(
            id = sid,
            originUrl = originUrl,
            cacheKey = cacheKey,
            headers = options.headers,
            cacheable = options.cache && config.enabled,
            generation = generation,
            lastAccess = nowMs(),
        )
        synchronized(sessions) {
            sessions[sid] = session
            // Вытесняем только ДРУГИЕ сессии: при равных timestamp minByOrNull мог вернуть новую.
            while (sessions.size > config.maxSessions) {
                val victim = sessions.values.filter { it.id != sid }.minByOrNull { it.lastAccess } ?: break
                sessions.remove(victim.id)
                releaseSessionResources(victim)
            }
        }
        val url = proxyUrl(session, originUrl, ResourceKind.PLAYLIST, srv.boundPort)
        Log.line("resolve", "sid=$sid cached=${session.cacheable} $originUrl")
        if (options.prewarm) {
            loader.execute { runCatching { loadPlaylist(session, originUrl) } }
        }
        return ResolveResult(
            streamId = sid,
            url = url,
            cached = session.cacheable,
            reason = if (session.cacheable) "ok" else "cache off",
            generation = generation,
        )
    }

    fun release(streamId: String) {
        val session = sessions.remove(streamId) ?: return
        releaseSessionResources(session)
    }

    private fun releaseSessionResources(session: Session) {
        synchronized(session.lock) {
            if (session.streamRefHeld) {
                session.stream?.let { cache.release(it) }
                session.streamRefHeld = false
            }
        }
        synchronized(downloadsLock) {
            val iterator = downloads.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.session.id == session.id) {
                    entry.value.cancelled.set(true)
                    iterator.remove()
                }
            }
        }
        val prefix = session.cacheKey + "|"
        synchronized(downloadsLock) { prefetched.removeIf { it.startsWith(prefix) } }
    }

    fun failOpen(streamIdOrUrl: String): Map<String, Any?>? {
        val session = sessions[streamIdOrUrl] ?: sessionFromProxyUrl(streamIdOrUrl) ?: return null
        session.stale = true
        val originUrl = session.originUrl
        release(session.id)
        return mapOf("streamId" to session.id, "originUrl" to originUrl)
    }

    fun nudge(streamId: String): Map<String, Any?> {
        val session = sessions[streamId] ?: return mapOf("queued" to 0)
        val media = session.activeMedia ?: return mapOf("queued" to 0)
        val stream = session.stream ?: return mapOf("queued" to 0)
        if (!session.cacheable) return mapOf("queued" to 0)
        return mapOf("queued" to schedulePrefetch(session, stream, media, session.lastSegmentIndex.coerceAtLeast(0)))
    }

    // MARK: - HTTP routing

    private fun handle(method: String, path: String, range: String?): HttpReply {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size < 3 || parts[0] != "s") return HttpReply.text(404, "not found")
        val session = sessions[parts[1]]
            ?: return HttpReply.text(410, ProxyErrorCode.SESSION_UNKNOWN.wire)
        session.lastAccess = nowMs()
        val absolute = B64Url.decode(parts[2])
            ?: return HttpReply.text(400, ProxyErrorCode.INVALID_URL.wire)
        if (session.stale) return HttpReply.text(410, "stale")
        val name = parts.getOrNull(3) ?: ""
        val extension = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
        val kind = Http.resourceKind(extension, null)
        return when {
            kind == ResourceKind.PLAYLIST || session.playlists.containsKey(absolute) ->
                loadPlaylist(session, absolute)
            kind == ResourceKind.KEY -> passThrough(session, absolute, range, cacheable = false)
            else -> serveSegment(session, absolute, range)
        }
    }

    private fun loadPlaylist(session: Session, url: String): HttpReply {
        session.playlists[url]?.let { return playlistReply(it) }
        val cfg = config
        val profile = TimeoutProfile.playlist(cfg)
        val fetched = origin.fetchPlaylist(url, headersFor(session, url), profile, cfg.maxPlaylistBytes)
        mergeCookies(session, url, fetched.setCookies)
        fetched.error?.let {
            notifyError(session, url, it, 1)
            return errorReply(it)
        }
        val data = fetched.data ?: return errorReply(ProxyErrorCode.EMPTY_RESPONSE)
        if (!PlaylistRewriter.looksLikePlaylist(data)) {
            val contentType = fetched.contentType ?: "application/octet-stream"
            return HttpReply(
                status = 200,
                headers = mapOf("Content-Type" to contentType, "Cache-Control" to "no-store"),
                body = BytesBody(data),
                length = data.size.toLong(),
                cacheState = debugState("passthrough"),
            )
        }
        val text = String(data, Charsets.UTF_8)
        val finalUrl = fetched.finalUrl ?: url
        val rewritten = PlaylistRewriter.rewrite(text, finalUrl) { absolute, kind ->
            proxyUrl(session, absolute, kind, port)
        }
        val resource = PlaylistResource(url, finalUrl, rewritten.text, rewritten)
        session.playlists[url] = resource
        session.playlists[finalUrl] = resource

        if (rewritten.kind == PlaylistKind.MEDIA) {
            session.activeMedia = resource
            if (!rewritten.cacheable) {
                session.cacheable = false
                Log.line("playlist", "без кэша: ${rewritten.reason} $url")
            }
            ensureStream(session, rewritten.fingerprint)
            if (cfg.prewarmSegments > 0) {
                session.stream?.let { stream ->
                    rewritten.media.segments.take(cfg.prewarmSegments).forEach { segment ->
                        enqueuePrefetch(session, stream, segment)
                    }
                }
            }
        }
        return playlistReply(resource)
    }

    private fun playlistReply(resource: PlaylistResource): HttpReply = HttpReply(
        status = 200,
        headers = mapOf("Content-Type" to "application/vnd.apple.mpegurl", "Cache-Control" to "no-store"),
        body = BytesBody(resource.data),
        length = resource.data.size.toLong(),
        cacheState = debugState("hit"),
    )

    private fun serveSegment(session: Session, url: String, range: String?): HttpReply {
        val cfg = config
        val media = session.activeMedia
        val stream = session.stream
        if (!session.cacheable || media == null || stream == null) {
            return passThrough(session, url, range, cacheable = false)
        }
        val index = media.indexByUrl[url] ?: return passThrough(session, url, range, cacheable = false)
        synchronized(session.lock) {
            if (index > session.lastSegmentIndex) session.lastSegmentIndex = index
        }
        val segment = media.result.media.segments.getOrNull(index) ?: return passThrough(session, url, range, false)
        var entry = cache.ensureEntry(stream, index, nowMs())

        if (entry.complete) {
            hits.incrementAndGet()
            val type = entry.contentType ?: Http.contentType(extensionOf(url))
            return HttpReply(
                status = 200,
                headers = mapOf("Content-Type" to type, "Cache-Control" to "no-store"),
                body = FileBody(entry.file),
                length = entry.prefix,
                cacheState = debugState("hit"),
            )
        }
        if (entry.failedUntil > nowMs()) {
            return errorReply(ProxyErrorCode.BUDGET_EXHAUSTED)
        }
        if (!cache.evictIfNeeded(0, setOf(session.cacheKey), nowMs())) {
            return passThrough(session, url, range, cacheable = false)
        }
        misses.incrementAndGet()
        val feed = ensureDownload(session, stream, segment, TimeoutProfile.interactive(cfg))
            ?: return passThrough(session, url, range, cacheable = false)
        val firstError = feed.waitForFirstBytes((cfg.reqFirstByteSec * 1000).toLong())
        if (firstError != null) {
            notifyError(session, url, firstError, 1)
            return errorReply(firstError)
        }
        val total = feed.total
        if (total <= 0) {
            // Неизвестная длина: отдаём как pass-through, кэш не ведём (план §4.4).
            return passThrough(session, url, range, cacheable = false)
        }
        val type = feed.contentType ?: entry.contentType ?: Http.contentType(extensionOf(url))
        cache.update(stream, index) { it.contentType = type }
        schedulePrefetch(session, stream, media, index)
        entry = cache.ensureEntry(stream, index, nowMs())
        return HttpReply(
            status = 200,
            headers = mapOf("Content-Type" to type, "Cache-Control" to "no-store"),
            body = GrowingFileBody(entry.file, feed),
            length = total,
            cacheState = debugState("miss"),
        )
    }

    private fun passThrough(session: Session, url: String, range: String?, cacheable: Boolean): HttpReply {
        val cfg = config
        val start = Http.rangeStart(range)
        val feed = ByteFeed(null)
        val request = OriginClient.Request(url, headersFor(session, url), start, TimeoutProfile.interactive(cfg), false)
        passPool.execute {
            val result = origin.download(request, feed)
            mergeCookies(session, url, result.setCookies)
            if (result.code != null) feed.fail(result.code!!) else feed.finish()
        }
        val firstError = feed.waitForFirstBytes((cfg.reqFirstByteSec * 1000).toLong())
        if (firstError != null) {
            notifyError(session, url, firstError, 1)
            return errorReply(firstError)
        }
        val total = feed.total
        val type = feed.contentType ?: Http.contentType(extensionOf(url))
        val headers = linkedMapOf("Content-Type" to type, "Cache-Control" to "no-store")
        if (total < 0) {
            return HttpReply(
                status = 200, headers = headers, body = StreamBody(feed), length = -1,
                cacheState = debugState("passthrough"),
            )
        }
        if (start > 0) {
            headers["Content-Range"] = "bytes $start-${start + total - 1}/${start + total}"
            return HttpReply(
                status = 206, headers = headers, body = StreamBody(feed), length = total - start,
                cacheState = debugState("passthrough"), rangeHandled = true,
            )
        }
        return HttpReply(
            status = 200, headers = headers, body = StreamBody(feed), length = total,
            cacheState = debugState("passthrough"), rangeHandled = range != null,
        )
    }

    private fun errorReply(code: ProxyErrorCode): HttpReply = HttpReply.text(code.httpStatus, code.wire)

    // MARK: - downloads

    private fun ensureDownload(
        session: Session,
        stream: CacheStore.Stream,
        segment: PlaylistSegment,
        profile: TimeoutProfile,
    ): ByteFeed? {
        val key = "${stream.key}#${segment.index}"
        synchronized(downloadsLock) {
            downloads[key]?.let { return it.feed }
            if (!cache.evictIfNeeded(0, setOf(session.cacheKey), nowMs())) return null
            val feed = ByteFeed(cache.fileURL(stream, segment.index))
            val download = Download(key, session, stream, segment, feed, isPrefetch = false)
            downloads[key] = download
            cache.retain(stream)
            cache.update(stream, segment.index) {
                it.downloading = true
                it.total = -1
                it.prefix = 0
                it.complete = false
                it.failedUntil = 0
            }
            interactive.execute { runDownload(download, profile) }
            return feed
        }
    }

    private fun runDownload(download: Download, profile: TimeoutProfile) {
        val cfg = config
        var attempt = 0
        try {
            while (!download.cancelled.get()) {
                val start = download.feed.written
                val headers = headersFor(download.session, download.segment.url)
                val request = OriginClient.Request(
                    url = download.segment.url,
                    headers = headers,
                    rangeStart = start,
                    profile = profile,
                    isPrefetch = download.isPrefetch,
                )
                val result = origin.download(request, download.feed)
                mergeCookies(download.session, download.segment.url, result.setCookies)
                val code = result.code

                if (code == null) {
                    val total = download.feed.total
                    val written = download.feed.written
                    download.feed.finish()
                    cache.update(download.stream, download.segment.index) {
                        it.total = total
                        it.prefix = written
                        it.complete = total >= 0 && written == total
                        it.downloading = false
                        it.feed = download.feed
                        it.contentType = download.feed.contentType
                        it.lastAccess = nowMs()
                    }
                    prefetchedCount.incrementAndGet()
                    return
                }

                if (code == ProxyErrorCode.RANGE_UNSUPPORTED && start > 0) {
                    cache.update(download.stream, download.segment.index) {
                        it.total = -1
                        it.prefix = 0
                        it.complete = false
                        it.downloading = false
                    }
                    return
                }

                attempt++
                if (code.retryable && attempt <= cfg.prefetchRetries && !download.cancelled.get()) {
                    val backoff = cfg.retryBackoffMs[minOf(attempt - 1, cfg.retryBackoffMs.lastIndex)]
                    runCatching { Thread.sleep(backoff) }
                    continue
                }

                val written = download.feed.written
                val total = download.feed.total
                download.feed.fail(code)
                cache.update(download.stream, download.segment.index) {
                    it.total = total
                    it.prefix = written
                    it.complete = total >= 0 && written == total
                    it.downloading = false
                    it.failedUntil = nowMs() + (cfg.cooldownSec * 1000).toLong()
                    it.feed = download.feed
                }
                notifyError(download.session, download.segment.url, code, attempt)
                return
            }
        } finally {
            synchronized(downloadsLock) {
                downloads.remove(download.key)
                if (download.isPrefetch) prefetchedCount.incrementAndGet()
            }
            cache.release(download.stream)
        }
    }

    private fun schedulePrefetch(
        session: Session,
        stream: CacheStore.Stream,
        resource: PlaylistResource,
        fromIndex: Int,
    ): Int {
        val cfg = config
        if (!session.cacheable || cfg.prefetchMaxSegments <= 0) return 0
        val media = resource.result.media
        if (media.segments.isEmpty()) return 0
        val avg = (media.totalDuration / media.segments.size).coerceAtLeast(0.5)
        val wanted = ceil(cfg.prefetchSeconds / avg).toInt()
        val count = minOf(cfg.prefetchMaxSegments, maxOf(1, wanted))
        var queued = 0
        val last = minOf(media.segments.lastIndex, fromIndex + count)
        for (i in (fromIndex + 1)..last) {
            if (enqueuePrefetch(session, stream, media.segments[i])) queued++
        }
        return queued
    }

    private fun enqueuePrefetch(session: Session, stream: CacheStore.Stream, segment: PlaylistSegment): Boolean {
        val key = "${stream.key}#${segment.index}"
        val marker = "${session.cacheKey}|${segment.index}"
        synchronized(downloadsLock) {
            if (downloads.containsKey(key) || prefetched.contains(marker)) return false
            if (!cache.evictIfNeeded(0, setOf(session.cacheKey), nowMs())) return false
            val feed = ByteFeed(cache.fileURL(stream, segment.index))
            val download = Download(key, session, stream, segment, feed, isPrefetch = true)
            downloads[key] = download
            prefetched.add(marker)
            cache.retain(stream)
            cache.update(stream, segment.index) {
                it.downloading = true
                it.total = -1
                it.prefix = 0
                it.complete = false
                it.failedUntil = 0
            }
            prefetchPool.execute { runDownload(download, TimeoutProfile.prefetch(config)) }
            return true
        }
    }

    private fun ensureStream(session: Session, fingerprint: String) {
        synchronized(session.lock) {
            val stream = cache.openStream(session.cacheKey, fingerprint, nowMs())
            if (session.stream !== stream) {
                if (session.streamRefHeld) session.stream?.let { cache.release(it) }
                session.stream = stream
                cache.retain(stream)
                session.streamRefHeld = true
            }
        }
    }

    // MARK: - state / stats

    fun stats(): Map<String, Any?> = mapOf(
        "port" to port,
        "running" to running,
        "generation" to generation,
        "bytes" to cache.totalBytes(),
        "maxBytes" to config.maxCacheBytes,
        "freeDiskBytes" to Fs.freeDiskBytes(root),
        "streams" to sessions.size,
        "hits" to hits.get(),
        "misses" to misses.get(),
        "evictions" to evictions.get(),
        "prefetched" to prefetchedCount.get(),
        "maxSessions" to config.maxSessions,
    )

    fun streamState(streamId: String): Map<String, Any?> {
        val session = sessions[streamId]
            ?: return mapOf("cacheKey" to "", "cacheable" to false, "segments" to emptyList<Map<String, Any?>>(), "cachedAheadSec" to 0.0)
        val media = session.activeMedia
        val stream = session.stream
        if (media == null || stream == null) {
            return mapOf("cacheKey" to session.cacheKey, "cacheable" to session.cacheable,
                         "segments" to emptyList<Map<String, Any?>>(), "cachedAheadSec" to 0.0)
        }
        val last = session.lastSegmentIndex.coerceAtLeast(0)
        val segments = ArrayList<Map<String, Any?>>(media.result.media.segments.size)
        var cachedAhead = 0.0
        for (segment in media.result.media.segments) {
            val entry = cache.entry(stream, segment.index)
            val complete = entry?.complete ?: false
            segments.add(
                mapOf(
                    "index" to segment.index,
                    "startSec" to segment.startSec,
                    "durationSec" to segment.duration,
                    "cachedBytes" to (entry?.prefix ?: 0L),
                    "complete" to complete,
                ),
            )
            if (segment.index > last) {
                if (complete) cachedAhead += segment.duration else break
            }
        }
        return mapOf(
            "cacheKey" to session.cacheKey,
            "cacheable" to session.cacheable,
            "segments" to segments,
            "cachedAheadSec" to cachedAhead,
        )
    }

    fun clearCache(streamId: String? = null): Map<String, Any?> {
        val cacheKey = streamId?.let { sessions[it]?.cacheKey }
        val freed = cache.clear(cacheKey)
        emit("onCache", mapOf("type" to "clear", "bytes" to cache.totalBytes(), "maxBytes" to config.maxCacheBytes))
        return mapOf("freedBytes" to freed)
    }

    // MARK: - helpers

    private fun proxyUrl(session: Session, absolute: String, kind: ResourceKind, port: Int): String {
        val name = Http.safeName(absolute, kind)
        return "http://127.0.0.1:$port/s/${session.id}/${B64Url.encode(absolute)}/$name"
    }

    private fun sessionFromProxyUrl(url: String): Session? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host != "127.0.0.1") return null
        val parts = uri.path.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2 || parts[0] != "s") return null
        return sessions[parts[1]]
    }

    private fun headersFor(session: Session, url: String): Map<String, String> {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return session.headers
        val jar = session.cookies[host] ?: return session.headers
        if (jar.isEmpty()) return session.headers
        val cookie = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val result = LinkedHashMap(session.headers)
        val existing = result.entries.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value
        result["Cookie"] = if (existing.isNullOrBlank()) cookie else "$existing; $cookie"
        return result
    }

    private fun mergeCookies(session: Session, url: String, setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return
        val jar = session.cookies.getOrPut(host) { ConcurrentHashMap() }
        for (raw in setCookies) {
            val pair = raw.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            jar[pair.substring(0, eq).trim()] = pair.substring(eq + 1)
        }
    }

    private fun extensionOf(url: String): String =
        runCatching { URI(url).path }.getOrNull()?.substringAfterLast('.', "")?.lowercase() ?: ""

    private fun debugState(state: String): String? = if (config.debug) state else null

    private fun notifyError(session: Session, url: String, code: ProxyErrorCode, attempt: Int) {
        if (code.auth) {
            if (!session.authNotified) {
                session.authNotified = true
                emit(
                    "onAuthRequired",
                    mapOf("streamId" to session.id, "url" to url, "originStatus" to originStatus(code)),
                )
            }
            return
        }
        emit(
            "onError",
            mapOf(
                "code" to code.wire,
                "streamId" to session.id,
                "url" to url,
                "originStatus" to originStatus(code),
                "attempt" to attempt,
                "retryable" to code.retryable,
                "fatal" to !code.retryable,
                "message" to code.wire,
            ),
        )
    }

    private fun originStatus(code: ProxyErrorCode): Int = when (code) {
        ProxyErrorCode.ORIGIN_400 -> 400
        ProxyErrorCode.ORIGIN_401 -> 401
        ProxyErrorCode.ORIGIN_403 -> 403
        ProxyErrorCode.ORIGIN_404 -> 404
        ProxyErrorCode.ORIGIN_410 -> 410
        ProxyErrorCode.ORIGIN_429 -> 429
        ProxyErrorCode.ORIGIN_5XX -> 500
        else -> 0
    }

    private fun emit(name: String, payload: Map<String, Any?>) {
        runCatching { onEvent?.invoke(name, payload) }
    }
}
