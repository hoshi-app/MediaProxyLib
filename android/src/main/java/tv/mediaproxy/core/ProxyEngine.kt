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
 * Proxy engine: sessions, loopback request routing, downloads, cache, prefetch, events.
 * JS only controls config and receives events. It never sits in the media path.
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
        /** When the media playlist was last re-read for a segment retry, to rate-limit re-reads. */
        @Volatile var playlistRefreshedAt: Long = 0L
        /** When the player last requested a resource through the proxy. Gates the stall detector. */
        @Volatile var lastClientRequestAt: Long = nowMs()
        /** Segment indexes whose retries were exhausted without progress. */
        val recoveriesGaveUp = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

        /**
         * Previous "segment URL → index" maps, newest first. A playlist refresh changes segment URLs,
         * but the player keeps requesting the old ones; without these maps those requests bypass the cache.
         */
        private val indexGenerations = java.util.Collections.synchronizedList(ArrayList<Map<String, Int>>())

        fun pushIndexMap(map: Map<String, Int>) {
            synchronized(indexGenerations) {
                indexGenerations.add(0, map)
                while (indexGenerations.size > 3) indexGenerations.removeAt(indexGenerations.size - 1)
            }
        }

        /** Looks up the current playlist first, then earlier maps. */
        fun indexOf(url: String): Int? {
            activeMedia?.indexByUrl?.get(url)?.let { return it }
            synchronized(indexGenerations) {
                for (map in indexGenerations) map[url]?.let { return it }
            }
            return null
        }
    }

    class PlaylistResource(
        val originUrl: String,
        val finalUrl: String,
        val text: String,
        val result: RewriteResult,
        val fetchedAt: Long = nowMs(),
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
        /**
         * Current segment URL. It changes after a media playlist refresh: a retry must go to the
         * fresh URL (a different edge or signature), not to the one that already failed.
         */
        @Volatile var url: String,
    ) {
        val cancelled = AtomicBoolean(false)
        /** Whether the playlist was re-read for this segment. Only one such attempt per download. */
        @Volatile var refreshed: Boolean = false
        val index: Int get() = segment.index
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

    private var interactive = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
    private var prefetchPool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
    private var loader = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue())
    private var passPool: java.util.concurrent.ExecutorService = createPassPool()

    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val prefetchedCount = AtomicLong(0)
    private val stallKicks = AtomicLong(0)
    private val originTruncated = AtomicLong(0)

    /**
     * Tracks progress of client downloads for the stall detector: when bytes stopped growing.
     * Kept separate from `Download` so the hot path is not touched, and cleaned up with it.
     */
    private class Progress(val written: Long, val at: Long) {
        var last: Long = written
        var lastAt: Long = at
        var kicks: Int = 0
        var baseline: Long = written
    }

    private val downloadProgress = HashMap<String, Progress>()
    private var recoverWatchdog: java.util.concurrent.ScheduledExecutorService? = null

    /** Events forwarded to JS. Their names must match the Expo module's event list. */
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
            startRecoverWatchdog()
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
        recoverWatchdog?.shutdownNow()
        recoverWatchdog = null
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
        resize(interactive, config.interactiveConcurrency)
        resize(prefetchPool, config.prefetchConcurrency)
    }

    /**
     * Assignment order matters: `ThreadPoolExecutor` requires core ≤ max, so raise
     * `maximumPoolSize` before `corePoolSize` and lower them in the opposite order.
     */
    private fun resize(pool: ThreadPoolExecutor, requested: Int) {
        val size = requested.coerceAtLeast(1)
        if (pool.maximumPoolSize == size && pool.corePoolSize == size) return
        if (size > pool.maximumPoolSize) {
            pool.maximumPoolSize = size
            pool.corePoolSize = size
        } else {
            pool.corePoolSize = size
            pool.maximumPoolSize = size
        }
    }

    /**
     * `stop()` shuts the pools down, so a later `start()` must recreate them. Otherwise the first
     * segment gets RejectedExecutionException and the player loses the stream.
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
            // Evict only other sessions: with equal timestamps minByOrNull could pick the new one.
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
    }

    fun failOpen(streamIdOrUrl: String): Map<String, Any?>? {
        val session = sessions[streamIdOrUrl] ?: sessionFromProxyUrl(streamIdOrUrl) ?: return null
        session.stale = true
        val originUrl = session.originUrl
        release(session.id)
        return mapOf("streamId" to session.id, "originUrl" to originUrl)
    }

    /**
     * Prefetch-ahead requested by the player's stall detector. The window starts at the last
     * requested segment, since that is usually the stuck one. Failure cooldowns are bypassed.
     */
    fun nudge(streamId: String): Map<String, Any?> {
        val session = sessions[streamId] ?: return mapOf("queued" to 0)
        val media = session.activeMedia ?: return mapOf("queued" to 0)
        val stream = session.stream ?: return mapOf("queued" to 0)
        if (!session.cacheable) return mapOf("queued" to 0)
        val from = (session.lastSegmentIndex - 1).coerceAtLeast(0)
        return mapOf("queued" to schedulePrefetch(session, stream, media, from, force = true))
    }

    /**
     * Media playlist refresh requested by JS (the player's stall detector): re-reads the playlist
     * and re-queues the stuck window without remounting the player.
     * `segmentsChanged` reports whether any segment URL actually changed.
     */
    fun restart(streamId: String): Map<String, Any?> {
        val session = sessions[streamId]
            ?: return mapOf("queued" to 0, "refreshed" to false, "segmentsChanged" to false,
                            "reason" to ProxyErrorCode.SESSION_UNKNOWN.wire)
        val outcome = refreshMedia(session)
        val queued = nudge(streamId)["queued"] ?: 0
        Log.line("restart", "sid=$streamId refreshed=${outcome.performed} " +
            "changed=${outcome.segmentsChanged} queued=$queued")
        val reason = when {
            !outcome.performed -> "unchanged"
            outcome.segmentsChanged -> "ok"
            else -> "same-urls"
        }
        return mapOf("queued" to queued, "refreshed" to outcome.performed,
                     "segmentsChanged" to outcome.segmentsChanged,
                     "cacheable" to session.cacheable, "reason" to reason)
    }

    // MARK: - stalled download detection

    private fun startRecoverWatchdog() {
        recoverWatchdog?.shutdownNow()
        val pool = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mediaproxy-recover").apply { isDaemon = true }
        }
        pool.scheduleWithFixedDelay({ runCatching { recoverTick() } }, 2, 2, TimeUnit.SECONDS)
        recoverWatchdog = pool
    }

    /**
     * Detects "the client is asking but bytes stopped" and re-reads the playlist, so the player does
     * not need to remount. It considers only client downloads whose session had a recent request,
     * after `cfg.stallKickSec` of silence, with at most `cfg.recoveryMax` retries per segment.
     */
    private fun recoverTick() {
        val cfg = config
        if (!running || cfg.stallKickSec <= 0 || cfg.recoveryMax <= 0) return
        val now = nowMs()
        val stalled = mutableListOf<Download>()
        synchronized(downloadsLock) {
            val iterator = downloads.entries.iterator()
            while (iterator.hasNext()) {
                val (key, download) = iterator.next()
                if (download.isPrefetch) continue
                val progress = downloadProgress.getOrPut(key) { Progress(download.feed.written, now) }
                val written = download.feed.written
                if (written > progress.last) {
                    progress.last = written
                    progress.lastAt = now
                }
                val session = download.session
                if (session.stale) continue
                // The client is gone (pause, background, remount), so there is nothing to recover.
                if (now - session.lastClientRequestAt > 15_000L) continue
                if (now - progress.lastAt < (cfg.stallKickSec * 1000).toLong()) continue
                if (progress.kicks >= cfg.recoveryMax) {
                    // All retries without progress: the segment is dead, the player decides next.
                    if (progress.last <= progress.baseline) session.recoveriesGaveUp.add(download.segment.index)
                    continue
                }
                stalled.add(download)
            }
        }
        // The network refresh runs outside the downloads lock.
        for (download in stalled) kickStalled(download, now)
    }

    private fun kickStalled(download: Download, now: Long) {
        val session = download.session
        val index = download.segment.index
        val outcome = refreshMedia(session)
        val fresh = currentSegmentUrl(session, index)
        val urlChanged = fresh != null && fresh != download.url
        if (urlChanged) download.url = fresh!!
        val progress = synchronized(downloadsLock) { downloadProgress[download.key] } ?: return
        val waited = (now - progress.lastAt) / 1000.0
        progress.kicks += 1
        // Reset the silence clock so the next retry waits stallKickSec again.
        progress.lastAt = now
        if (progress.kicks == 1) progress.baseline = progress.last
        stallKicks.incrementAndGet()
        Log.line("recover", "sid=${session.id} seg=$index waited=${waited}s " +
            "changed=${outcome.segmentsChanged} urlChanged=$urlChanged kick=${progress.kicks}")
        emit(
            "onRecover",
            mapOf(
                "streamId" to session.id,
                "segmentIndex" to index,
                "waitedSec" to waited,
                "urlChanged" to urlChanged,
            ),
        )
    }

    // MARK: - HTTP routing

    private fun handle(method: String, path: String, range: String?): HttpReply {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size < 3 || parts[0] != "s") return HttpReply.text(404, "not found")
        val session = sessions[parts[1]]
            ?: return HttpReply.text(410, ProxyErrorCode.SESSION_UNKNOWN.wire)
        session.lastAccess = nowMs()
        // Marks the client as alive: the stall detector skips sessions nobody requests.
        session.lastClientRequestAt = nowMs()
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
        session.playlists[url]?.let { cached ->
            // A cacheable VOD playlist never changes, while a live or event playlist must be
            // re-read, or playback freezes on the first segment list.
            if (cached.result.cacheable || nowMs() - cached.fetchedAt < playlistTtlMs(cached)) {
                return playlistReply(cached)
            }
        }
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
            // The player memorized URLs from this playlist, so later re-reads must keep mapping
            // them to the same index.
            session.pushIndexMap(resource.indexByUrl)
            if (!rewritten.cacheable) {
                session.cacheable = false
                Log.line("playlist", "без кэша: ${rewritten.reason} $url")
            }
            ensureStream(session, rewritten.fingerprint)
            // Prewarm only makes sense for cacheable VOD. For live, BYTERANGE or DRM the first
            // "segments" either waste traffic or are the whole media file.
            if (cfg.prewarmSegments > 0 && session.cacheable) {
                session.stream?.let { stream ->
                    rewritten.media.segments.take(cfg.prewarmSegments).forEach { segment ->
                        enqueuePrefetch(session, stream, segment)
                    }
                }
            }
        }
        return playlistReply(resource)
    }

    /**
     * Lifetime of a non-cacheable playlist. The player re-reads a live playlist once per target
     * duration, so the proxy must re-fetch from origin by then or the segment list freezes.
     */
    private fun playlistTtlMs(resource: PlaylistResource): Long =
        maxOf((resource.result.media.targetDuration * 1000).toLong(), 2_000L)

    /** Result of a media playlist re-read: whether it happened and whether any segment URL changed. */
    private data class RefreshOutcome(val performed: Boolean, val segmentsChanged: Boolean)

    /**
     * Re-reads the media playlist and swaps it in when the segment structure is unchanged (same
     * fingerprint, so the cache stays valid). CDN signatures and hosts expire, while a fresh
     * playlist returns current URLs. A segment retry then reaches a live edge, and the player
     * need not remount.
     */
    private fun refreshMedia(session: Session): RefreshOutcome {
        val current = session.activeMedia ?: return RefreshOutcome(false, false)
        // A retry storm must not become a playlist request storm.
        synchronized(session.lock) {
            if (nowMs() - session.playlistRefreshedAt < 5_000L) return RefreshOutcome(false, false)
            session.playlistRefreshedAt = nowMs()
        }
        val cfg = config
        val url = current.originUrl
        val profile = TimeoutProfile.refresh(cfg)
        // Hard request cap: a refresh can run while a download is stalled, so it must not wait indefinitely.
        val fetched = origin.fetchPlaylist(
            url, headersFor(session, url), profile, cfg.maxPlaylistBytes,
            callTimeoutMs = profile.firstByteMs + 5_000,
        )
        mergeCookies(session, url, fetched.setCookies)
        val data = fetched.data
        if (fetched.error != null || data == null || !PlaylistRewriter.looksLikePlaylist(data)) {
            Log.line("refresh", "плейлист не перечитан: ${fetched.error?.wire ?: "не плейлист"} $url")
            // 401, 403 and 410 mean the signature died, not the segment. JS must learn and re-resolve.
            fetched.error?.takeIf { it.auth }?.let { notifyError(session, url, it, 1) }
            return RefreshOutcome(false, false)
        }
        val text = String(data, Charsets.UTF_8)
        val finalUrl = fetched.finalUrl ?: url
        val rewritten = PlaylistRewriter.rewrite(text, finalUrl) { absolute, kind ->
            proxyUrl(session, absolute, kind, port)
        }
        // A different structure means different content. Swapping the playlist would turn the
        // segment cache into garbage, and ensureStream would delete the directory during playback.
        if (rewritten.kind != PlaylistKind.MEDIA || rewritten.fingerprint != current.result.fingerprint) {
            Log.line("refresh", "структура сегментов изменилась (${rewritten.reason}) — остаёмся на старом")
            return RefreshOutcome(true, false)
        }
        val changed = segmentsChanged(current, rewritten)
        val resource = PlaylistResource(url, finalUrl, rewritten.text, rewritten)
        session.playlists[url] = resource
        session.playlists[finalUrl] = resource
        session.activeMedia = resource
        session.pushIndexMap(resource.indexByUrl)
        // Prefetch marks failed segments dead for cooldownSec. Clear that cooldown after the
        // playlist changes, or the window ahead never retries.
        val stream = session.stream
        if (stream != null) {
            val last = session.lastSegmentIndex.coerceAtLeast(0)
            val end = minOf(rewritten.media.segments.lastIndex, last + maxOf(1, cfg.prefetchMaxSegments))
            if (end >= last) {
                for (index in last..end) cache.update(stream, index) { it.failedUntil = 0 }
            }
        }
        Log.line("refresh", "манифест перезапущен: сегментов ${rewritten.media.segments.size} " +
            "changed=$changed $url")
        return RefreshOutcome(true, changed)
    }

    /** Compares URLs by index: the signature or host in the query may have been refreshed. */
    private fun segmentsChanged(current: PlaylistResource, fresh: RewriteResult): Boolean {
        val before = current.result.media.segments
        val after = fresh.media.segments
        if (before.size != after.size) return true
        for (i in after.indices) {
            if (before[i].url != after[i].url) return true
        }
        return false
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
        // Look the index up across previous maps: after a playlist re-read the URLs differ, while
        // the player keeps requesting the old ones.
        val index = session.indexOf(url) ?: return passThrough(session, url, range, cacheable = false)
        synchronized(session.lock) {
            if (index > session.lastSegmentIndex) session.lastSegmentIndex = index
        }
        val segment = media.result.media.segments.firstOrNull { it.index == index }
            ?: return passThrough(session, url, range, false)
        var entry = cache.ensureEntry(stream, index, nowMs())

        if (entry.complete) {
            hits.incrementAndGet()
            // The segment is complete, so clear its give-up mark: a new failure starts the count over.
            session.recoveriesGaveUp.remove(index)
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
            // The cooldown applies only to prefetch. A player request gets a retry: a 502 for 30 s
            // made the player treat the segment as a dead source and remount the stream.
            cache.update(stream, index) { it.failedUntil = 0 }
        }
        if (!cache.evictIfNeeded(0, setOf(session.cacheKey), nowMs())) {
            return passThrough(session, url, range, cacheable = false)
        }
        misses.incrementAndGet()
        val feed = ensureDownload(session, stream, segment, TimeoutProfile.interactive(cfg))
            ?: return passThrough(session, url, range, cacheable = false)
        val firstError = feed.waitForMeta((cfg.reqFirstByteSec * 1000).toLong())
        if (firstError != null) {
            // The loader emits the event in runDownload, otherwise one failure produces two onError calls.
            return errorReply(firstError)
        }
        val total = feed.total
        if (total <= 0) {
            // Unknown length: serve it as pass-through and do not cache it.
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
        val firstError = feed.waitForMeta((cfg.reqFirstByteSec * 1000).toLong())
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
                // A stream cannot be resumed, so the server must not slice it with its own Range.
                cacheState = debugState("passthrough"), rangeHandled = true,
            )
        }
        if (start > 0) {
            // `total` is the full resource size, so Content-Range spans `start` to `total-1` and the
            // body length is the tail.
            headers["Content-Range"] = "bytes $start-${total - 1}/$total"
            return HttpReply(
                status = 206, headers = headers, body = StreamBody(feed), length = total - start,
                cacheState = debugState("passthrough"), rangeHandled = true,
            )
        }
        return HttpReply(
            status = 200, headers = headers, body = StreamBody(feed), length = total,
            cacheState = debugState("passthrough"), rangeHandled = true,
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
            // A live download is returned as is. A failed download still registered is not:
            // otherwise the player's next request would get the same failure without reaching origin.
            downloads[key]?.let { if (it.feed.error == null) return it.feed }
            if (!cache.evictIfNeeded(0, setOf(session.cacheKey), nowMs())) return null
            val feed = openFeed(stream, segment.index)
            val download = Download(key, session, stream, segment, feed, isPrefetch = false, url = segment.url)
            downloads[key] = download
            downloadProgress[key] = Progress(feed.written, nowMs())
            cache.retain(stream)
            cache.update(stream, segment.index) {
                it.downloading = true
                it.prefix = feed.written
                it.complete = false
                it.failedUntil = 0
            }
            interactive.execute { runDownload(download, profile) }
            return feed
        }
    }

    /**
     * File feed for a segment. An existing prefix from an interrupted request is resumed, not
     * overwritten. `written` starts at the file size and the loader sends `Range: bytes=<prefix>-`.
     */
    private fun openFeed(stream: CacheStore.Stream, index: Int): ByteFeed {
        val file = cache.fileURL(stream, index)
        return ByteFeed(file, Fs.size(file))
    }

    private fun runDownload(download: Download, profile: TimeoutProfile) {
        val cfg = config
        val index = download.segment.index
        var attempt = 0
        try {
            // The file already holds the whole segment, but the entry was never committed (crash
            // between write and journal update). There is nothing to resume, and a Range past the
            // resource would give 416.
            val knownTotal = cache.entry(download.stream, index)?.total ?: -1L
            if (download.feed.written > 0 && knownTotal > 0 && download.feed.written >= knownTotal) {
                download.feed.finish()
                cache.update(download.stream, index) {
                    it.total = knownTotal
                    it.prefix = knownTotal
                    it.complete = true
                    it.downloading = false
                }
                if (download.isPrefetch) prefetchedCount.incrementAndGet()
                return
            }
            while (!download.cancelled.get()) {
                // A fresh URL after a playlist re-read (this retry or the stall detector) must be
                // picked up by the same download. Otherwise the client response has to be cut and
                // the stream remounts.
                // Content-Range checks in OriginClient and If-Range guard the prefix splice.
                currentSegmentUrl(download.session, index)?.let { fresh ->
                    if (fresh != download.url) {
                        Log.line("retry", "сегмент $index → свежий URL")
                        download.url = fresh
                    }
                }
                val start = download.feed.written
                val ifRange = if (start > 0) {
                    download.feed.etag ?: cache.entry(download.stream, index)?.etag
                } else {
                    null
                }
                val headers = headersFor(download.session, download.url)
                val request = OriginClient.Request(
                    url = download.url,
                    headers = headers,
                    rangeStart = start,
                    profile = profile,
                    isPrefetch = download.isPrefetch,
                    ifRange = ifRange,
                )
                val result = origin.download(request, download.feed)
                mergeCookies(download.session, download.url, result.setCookies)
                val code = result.code
                if (code == ProxyErrorCode.TRUNCATED) originTruncated.incrementAndGet()

                if (code == null) {
                    val total = download.feed.total
                    val written = download.feed.written
                    download.feed.finish()
                    cache.ensureEntry(download.stream, index, nowMs())
                    cache.update(download.stream, index) {
                        it.total = total
                        it.prefix = written
                        it.complete = total >= 0 && written == total
                        it.downloading = false
                        it.feed = download.feed
                        it.contentType = download.feed.contentType
                        it.etag = download.feed.etag
                        it.lastAccess = nowMs()
                    }
                    if (download.isPrefetch) prefetchedCount.incrementAndGet()
                    return
                }

                if (code == ProxyErrorCode.RANGE_UNSUPPORTED && start > 0) {
                    // The prefix does not match the current resource (content or signature changed).
                    // Restart the segment, or we would serve a splice of two different files.
                    download.feed.file?.let { Fs.truncate(it, 0) }
                    download.feed.fail(code)
                    cache.ensureEntry(download.stream, index, nowMs())
                    cache.update(download.stream, index) {
                        it.total = -1
                        it.prefix = 0
                        it.complete = false
                        it.downloading = false
                        it.etag = null
                    }
                    notifyError(download.session, download.url, code, attempt + 1)
                    return
                }

                attempt++
                val mayRetry = download.isPrefetch || start > 0 || download.feed.written > start
                if (code.retryable && mayRetry && attempt <= cfg.prefetchRetries && !download.cancelled.get()) {
                    val backoff = cfg.retryBackoffMs[minOf(attempt - 1, cfg.retryBackoffMs.lastIndex)]
                    runCatching { Thread.sleep(backoff) }
                    continue
                }

                // The player retries the first failure, not us: one player request means one origin
                // request. After our own retry fails again, the segment edge is at fault, not the
                // stream, so re-read the playlist here and retry with fresh URLs.
                // Switch only when the URL actually changed: otherwise the stall detector's rate
                // limit would suppress this retry too.
                if (code.retryable && attempt >= 2 && !download.refreshed && !download.cancelled.get()) {
                    refreshMedia(download.session)
                    val fresh = currentSegmentUrl(download.session, index)
                    if (fresh != null && fresh != download.url) {
                        download.refreshed = true
                        download.url = fresh
                        attempt = 0
                        Log.line("retry", "сегмент $index → $fresh")
                        continue
                    }
                }

                val written = download.feed.written
                val total = download.feed.total
                download.feed.fail(code)
                cache.ensureEntry(download.stream, index, nowMs())
                cache.update(download.stream, index) {
                    it.total = total
                    it.prefix = written
                    it.complete = total >= 0 && written == total
                    it.downloading = false
                    it.failedUntil = nowMs() + (cfg.cooldownSec * 1000).toLong()
                    it.feed = download.feed
                    it.etag = download.feed.etag
                }
                notifyError(download.session, download.url, code, attempt)
                return
            }
        } finally {
            synchronized(downloadsLock) {
                // Remove only our own entry: while this download was failing, a new one could start
                // under the same key, and that one must stay.
                if (downloads[download.key] === download) {
                    downloads.remove(download.key)
                    downloadProgress.remove(download.key)
                }
            }
            cache.release(download.stream)
        }
    }

    private fun schedulePrefetch(
        session: Session,
        stream: CacheStore.Stream,
        resource: PlaylistResource,
        fromIndex: Int,
        force: Boolean = false,
    ): Int {
        val cfg = config
        if (!session.cacheable || cfg.prefetchMaxSegments <= 0) return 0
        val media = resource.result.media
        if (media.segments.isEmpty()) return 0
        val avg = (media.totalDuration / media.segments.size).coerceAtLeast(0.5)
        val wanted = ceil(cfg.prefetchSeconds / avg).toInt()
        val count = minOf(cfg.prefetchMaxSegments, maxOf(1, wanted))
        // The window starts at `fromIndex + 1`. `force` ignores the failure cooldown.
        val first = (fromIndex + 1).coerceAtLeast(0)
        if (first > media.segments.lastIndex) return 0
        val last = minOf(media.segments.lastIndex, first + count - 1)
        var queued = 0
        for (i in first..last) {
            if (enqueuePrefetch(session, stream, media.segments[i], force)) queued++
        }
        return queued
    }

    private fun enqueuePrefetch(
        session: Session,
        stream: CacheStore.Stream,
        segment: PlaylistSegment,
        force: Boolean = false,
    ): Boolean {
        if (!session.cacheable) return false
        val key = "${stream.key}#${segment.index}"
        synchronized(downloadsLock) {
            // Deduplicate against a live download and the cache entry state. A failed download does
            // not count as in flight, so it can be retried.
            val inFlight = downloads[key]
            if (inFlight != null && inFlight.feed.error == null) return false
            val existing = cache.entry(stream, segment.index)
            // Already cached or recently failed: a new request would only waste traffic (unless forced).
            if (existing?.complete == true) return false
            if (!force && (existing?.failedUntil ?: 0L) > nowMs()) return false
            if (!cache.evictIfNeeded(0, setOf(session.cacheKey), nowMs())) return false
            val feed = openFeed(stream, segment.index)
            val download = Download(key, session, stream, segment, feed, isPrefetch = true, url = segment.url)
            downloads[key] = download
            downloadProgress[key] = Progress(feed.written, nowMs())
            cache.retain(stream)
            // Prefetch has no player request that would create the entry, and update alone does
            // nothing. Without ensureEntry the segment is downloaded but the cache never learns of it.
            cache.ensureEntry(stream, segment.index, nowMs())
            cache.update(stream, segment.index) {
                it.downloading = true
                it.prefix = feed.written
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
        "evictions" to cache.evictedCount(),
        "prefetched" to prefetchedCount.get(),
        "stallKicks" to stallKicks.get(),
        "originTruncated" to originTruncated.get(),
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
        // No streamId clears everything. An unknown streamId clears nothing, otherwise a stale id
        // would silently wipe the whole app cache.
        val freed: Long = if (streamId == null) {
            cache.clear(null)
        } else {
            val session = sessions[streamId] ?: return mapOf("freedBytes" to 0L)
            cache.clear(session.cacheKey)
        }
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

    /**
     * Current URL of a segment by index. After a playlist refresh the playlist holds new URLs, and a
     * retry must use them instead of the URL that failed.
     */
    private fun currentSegmentUrl(session: Session, index: Int): String? =
        session.activeMedia?.result?.media?.segments?.firstOrNull { it.index == index }?.url

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
