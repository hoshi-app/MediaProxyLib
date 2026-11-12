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
        /** Когда последний раз перечитывали media-плейлист ради ретрая сегмента (анти-шторм). */
        @Volatile var playlistRefreshedAt: Long = 0L
        /** Когда клиент (плеер) последний раз просил ресурс через прокси — гейт вотчдога. */
        @Volatile var lastClientRequestAt: Long = nowMs()
        /** Сегменты, по которым кики уже не помогли: больше не тратим трафик до прогресса. */
        val recoveriesGaveUp = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

        /**
         * Поколения карт «URL сегмента → индекс». После перечитывания манифеста URL меняются, а плеер
         * продолжает просить СТАРЫЕ (свой плейлист он не перезапрашивает). Без этого кольца такие
         * запросы уходили бы в pass-through, и кэш тихо переставал работать.
         */
        private val indexGenerations = java.util.Collections.synchronizedList(ArrayList<Map<String, Int>>())

        fun pushIndexMap(map: Map<String, Int>) {
            synchronized(indexGenerations) {
                indexGenerations.add(0, map)
                while (indexGenerations.size > 3) indexGenerations.removeAt(indexGenerations.size - 1)
            }
        }

        /** Сначала текущий плейлист, затем старые поколения (R1: не наоборот). */
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
         * Текущий URL сегмента. Меняется после «перезапуска манифеста»: повтор обязан уйти на свежий
         * URL (другой edge/подпись), а не в тот, который уже отвалился.
         */
        @Volatile var url: String,
    ) {
        val cancelled = AtomicBoolean(false)
        /** Перечитывали ли плейлист ради этого сегмента: одна такая попытка на загрузку. */
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
     * Наблюдение за прогрессом КЛИЕНТСКИХ загрузок для вотчдога: когда байты перестали расти.
     * Живёт отдельно от `Download`, чтобы не трогать горячие пути; чистится вместе с загрузкой.
     */
    private class Progress(val written: Long, val at: Long) {
        var last: Long = written
        var lastAt: Long = at
        var kicks: Int = 0
        var baseline: Long = written
    }

    private val downloadProgress = HashMap<String, Progress>()
    private var recoverWatchdog: java.util.concurrent.ScheduledExecutorService? = null

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
     * Порядок присвоений важен: `ThreadPoolExecutor` требует core ≤ max, поэтому при повышении
     * сначала поднимается `maximumPoolSize`, при понижении — сначала `corePoolSize`.
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
    }

    fun failOpen(streamIdOrUrl: String): Map<String, Any?>? {
        val session = sessions[streamIdOrUrl] ?: sessionFromProxyUrl(streamIdOrUrl) ?: return null
        session.stale = true
        val originUrl = session.originUrl
        release(session.id)
        return mapOf("streamId" to session.id, "originUrl" to originUrl)
    }

    /**
     * «Догрузи вперёд» для сторожа плеера. Отсчёт — от ПОСЛЕДНЕГО ЗАПРОШЕННОГО сегмента включительно:
     * залип обычно именно он, а не следующий за ним. Кулдаун неудачных сегментов снимаем.
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
     * «Перезапуск манифеста» по просьбе JS (сторож плеера): перечитываем media-плейлист и заново
     * ставим загрузку залипшего окна. Плеер при этом НЕ перемонтируется — это и есть цель.
     * `segmentsChanged` — изменился ли хоть один URL сегментов (иначе рестарт был формальностью).
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

    // MARK: - вотчдог зависшей загрузки

    private fun startRecoverWatchdog() {
        recoverWatchdog?.shutdownNow()
        val pool = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mediaproxy-recover").apply { isDaemon = true }
        }
        pool.scheduleWithFixedDelay({ runCatching { recoverTick() } }, 2, 2, TimeUnit.SECONDS)
        recoverWatchdog = pool
    }

    /**
     * Прокси сам замечает «клиент просит, а байты не идут» и перечитывает манифест — плееру для
     * этого перемонтироваться не нужно. Гейты: только клиентские загрузки, только сессии с живым
     * клиентом, только после cfg.stallKickSec тишины, не больше cfg.recoveryMax киков на сегмент.
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
                // Клиент ушёл (пауза/фон/перемонт) — лечить нечего и незачем.
                if (now - session.lastClientRequestAt > 15_000L) continue
                if (now - progress.lastAt < (cfg.stallKickSec * 1000).toLong()) continue
                if (progress.kicks >= cfg.recoveryMax) {
                    // Все кики без прогресса — сегмент мёртв, дальше решает плеер.
                    if (progress.last <= progress.baseline) session.recoveriesGaveUp.add(download.segment.index)
                    continue
                }
                stalled.add(download)
            }
        }
        // Сетевой рефреш — ВНЕ замка загрузок.
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
        // Следующий кик — не раньше чем через stallKickSec: рефреш уже мог занять время.
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
        // Метка «клиент ещё жив»: вотчдог не лечит сессии, к которым никто не обращается.
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
            // Кэшируемый VOD-плейлист неизменен, а live/event обязан перечитываться: иначе
            // воспроизведение «замерзает» на первом же списке сегментов (§5.5).
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
            // Плеер запомнил URL из ЭТОГО плейлиста: после будущих перечитываний они должны
            // по-прежнему адресовать тот же индекс (иначе кэш тихо превращается в pass-through).
            session.pushIndexMap(resource.indexByUrl)
            if (!rewritten.cacheable) {
                session.cacheable = false
                Log.line("playlist", "без кэша: ${rewritten.reason} $url")
            }
            ensureStream(session, rewritten.fingerprint)
            // Прогрев имеет смысл только для кэшируемого VOD: у live/BYTERANGE/DRM первые
            // «сегменты» — это либо пустая трата трафика, либо весь медиафайл целиком.
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
     * Срок жизни не-cacheable плейлиста. Плеер перезапрашивает live-плейлист раз в таргет-дирекшн,
     * и прокси обязан к этому моменту сходить в origin — иначе список сегментов «замерзает».
     */
    private fun playlistTtlMs(resource: PlaylistResource): Long =
        maxOf((resource.result.media.targetDuration * 1000).toLong(), 2_000L)

    /** Итог перечитывания манифеста: сделан ли рефреш и сменились ли хоть какие-то URL сегментов. */
    private data class RefreshOutcome(val performed: Boolean, val segmentsChanged: Boolean)

    /**
     * «Перезапуск манифеста» на уровне прокси: перечитываем media-плейлист и, если структура
     * сегментов та же (одинаковый fingerprint — кэш остаётся валидным), подменяем его свежей копией.
     * Зачем: подпись/хост сегментов у CDN устаревают, а свежий запрос плейлиста отдаёт актуальные
     * URL. Так повтор сегмента уходит на живой edge, и плееру не нужно перемонтироваться.
     */
    private fun refreshMedia(session: Session): RefreshOutcome {
        val current = session.activeMedia ?: return RefreshOutcome(false, false)
        // Шторм ретраев не должен превращаться в шторм запросов плейлиста.
        synchronized(session.lock) {
            if (nowMs() - session.playlistRefreshedAt < 5_000L) return RefreshOutcome(false, false)
            session.playlistRefreshedAt = nowMs()
        }
        val cfg = config
        val url = current.originUrl
        val profile = TimeoutProfile.refresh(cfg)
        // Жёсткий потолок запроса: рефреш идёт внутри полосы голода, слот загрузки держать нельзя.
        val fetched = origin.fetchPlaylist(
            url, headersFor(session, url), profile, cfg.maxPlaylistBytes,
            callTimeoutMs = profile.firstByteMs + 5_000,
        )
        mergeCookies(session, url, fetched.setCookies)
        val data = fetched.data
        if (fetched.error != null || data == null || !PlaylistRewriter.looksLikePlaylist(data)) {
            Log.line("refresh", "плейлист не перечитан: ${fetched.error?.wire ?: "не плейлист"} $url")
            // 401/403/410 — умерла подпись, а не сегмент: JS обязан узнать (fail-open/перерезолв).
            fetched.error?.takeIf { it.auth }?.let { notifyError(session, url, it, 1) }
            return RefreshOutcome(false, false)
        }
        val text = String(data, Charsets.UTF_8)
        val finalUrl = fetched.finalUrl ?: url
        val rewritten = PlaylistRewriter.rewrite(text, finalUrl) { absolute, kind ->
            proxyUrl(session, absolute, kind, port)
        }
        // Другая структура — это уже другой контент: подменять плейлист нельзя, иначе кэш сегментов
        // станет мусором, а ensureStream снесёт каталог прямо во время игры.
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
        // Наш же prefetch считает неудачные сегменты «мёртвыми» на cooldownSec: после смены
        // манифеста кулдаун надо снять, иначе окно впереди так и не перезакачается.
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

    /** Сравниваем URL по индексу: подпись или хост в query могли обновиться. */
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
        // Индекс ищем по карте поколений: после перечитывания манифеста URL другие, а плеер
        // продолжает просить старые — без этого он получал бы pass-through мимо кэша.
        val index = session.indexOf(url) ?: return passThrough(session, url, range, cacheable = false)
        synchronized(session.lock) {
            if (index > session.lastSegmentIndex) session.lastSegmentIndex = index
        }
        val segment = media.result.media.segments.firstOrNull { it.index == index }
            ?: return passThrough(session, url, range, false)
        var entry = cache.ensureEntry(stream, index, nowMs())

        if (entry.complete) {
            hits.incrementAndGet()
            // Сегмент докачался — кики по нему больше не нужны (новый сбой начнёт счёт заново).
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
            // Кулдаун — ограничение ТОЛЬКО для prefetch. Запросу плеера отдаём повтор: 502 на 30 с
            // «мёртвого» сегмента плеер воспринимал как отказ источника и перемонтировал поток.
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
            // Событие отдаёт загрузчик (runDownload): иначе на один сбой приходило бы два onError.
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
                // Поток нельзя сдвинуть: сервер не должен резать его своим Range.
                cacheState = debugState("passthrough"), rangeHandled = true,
            )
        }
        if (start > 0) {
            // `total` — полный размер ресурса, поэтому Content-Range идёт от `start` до `total-1`,
            // а длина тела — хвост. Раньше здесь считалось `start + total`, и разбор уезжал.
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
            // Живая загрузка отдаётся как есть. Уже УПАВШАЯ (feed с ошибкой), но ещё не снятая с
            // учёта, не отдаётся: иначе повторный запрос плеера мгновенно получает тот же отказ,
            // ни разу не сходив в origin («запрос плеера сходил в origin» в SelfTest).
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
     * Feed сегмента. Если на диске уже лежит префикс (сорвавшийся прошлый запрос), он не затирается,
     * а докачивается: `written` стартует с размера файла, и загрузчик шлёт `Range: bytes=<prefix>-`.
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
            // Файл уже содержит весь сегмент, но запись не была закоммичена (сбой между записью и
            // журналом): докачивать нечего, иначе Range за пределом ресурса даст 416.
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
                // Свежий URL (после перечитывания манифеста — своим ретраем или вотчдогом) обязан
                // подхватываться этой же загрузкой: иначе клиентский ответ придётся рвать, а серия
                // уйдёт в перемонт. Склейку префикса страхует проверка Content-Range в OriginClient
                // (206 с чужим start → RANGE_UNSUPPORTED) и If-Range, когда есть ETag.
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
                    // Префикс не подходит к текущему ресурсу (сменился контент или подпись):
                    // начинаем сегмент заново, иначе отдадим склейку из двух разных файлов.
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

                // Правило «ретраит плеер, а не мы» в силе для первого облома: один запрос плеера =
                // один запрос к origin. Но если мы уже повторили и снова облом — виноват не поток,
                // а конкретный сегментный edge. Перечитываем манифест ЗДЕСЬ, в прокси (свежие URL
                // сегментов), и повторяем: плееру перемонтироваться не нужно. Условие перехода на
                // новый URL — смена URL, а не факт «кто-то перечитал плейлист» (иначе анти-шторм
                // вотчдога глушил бы собственный ретрай загрузки).
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
                // Только свою запись: пока мы падали (feed.fail уже виден читателям), под этим же
                // ключом могла стартовать новая загрузка — её снимать нельзя.
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
        // `fromIndex` — последний запрошенный плеером сегмент; он включён в окно (`+1`), потому что
        // залипает обычно именно он. `force` — не считаться с кулдауном неудачной загрузки.
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
            // Дедуп — по живой загрузке и по состоянию записи. Постоянной метки «уже ставили» больше
            // нет: она глушила повторную догрузку навсегда (nudge → `queued: 0` при залипшем сегменте
            // и пустом окне впереди). Упавшая загрузка (feed с ошибкой) дедупом не считается.
            val inFlight = downloads[key]
            if (inFlight != null && inFlight.feed.error == null) return false
            val existing = cache.entry(stream, segment.index)
            // Уже скачано или недавно провалилось — новый запрос только тратит трафик (кроме force).
            if (existing?.complete == true) return false
            if (!force && (existing?.failedUntil ?: 0L) > nowMs()) return false
            if (!cache.evictIfNeeded(0, setOf(session.cacheKey), nowMs())) return false
            val feed = openFeed(stream, segment.index)
            val download = Download(key, session, stream, segment, feed, isPrefetch = true, url = segment.url)
            downloads[key] = download
            downloadProgress[key] = Progress(feed.written, nowMs())
            cache.retain(stream)
            // У prefetch нет «запроса плеера», который создал бы запись: без ensureEntry update ниже
            // молча ничего не делает, и сегмент скачан, но кэш о нём не знает.
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
        // Без streamId — чистим всё. С streamId неизвестной сессии — ничего: иначе clearCache
        // по уже освобождённому id молча стирал бы весь кэш приложения.
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
     * Актуальный URL сегмента по индексу: после «перезапуска манифеста» в плейлисте уже новые URL,
     * и повтор должен идти на них, а не на тот, что отвалился.
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
