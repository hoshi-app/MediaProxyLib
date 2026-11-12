package tv.mediaproxy.core

import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/**
 * Сетевой слой origin: OkHttp с потоковой отдачей, три таймаута (первый байт / тишина / пол скорости)
 * и валидацией ответа. Ретраи — не здесь: их считает движок (единый бюджет на сегмент, §6.3).
 */
class OriginClient(private val config: () -> ProxyConfig) {
    data class Request(
        val url: String,
        val headers: Map<String, String>,
        val rangeStart: Long,
        val profile: TimeoutProfile,
        val isPrefetch: Boolean,
        /** ETag для `If-Range`: докачка с префикса не должна склеивать две версии ресурса. */
        val ifRange: String? = null,
    )

    data class DownloadResult(
        val code: ProxyErrorCode?,
        val finalUrl: String?,
        val setCookies: List<String>,
    )

    data class PlaylistFetch(
        val data: ByteArray?,
        val finalUrl: String?,
        val contentType: String?,
        val error: ProxyErrorCode?,
        val setCookies: List<String>,
    )

    private val watchdog = Executors.newScheduledThreadPool(2) { runnable ->
        Thread(runnable, "mediaproxy-watchdog").apply { isDaemon = true }
    }

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun shutdown() {
        watchdog.shutdownNow()
        runCatching { baseClient.dispatcher.executorService.shutdown() }
    }

    /** Скачивает ресурс в feed. Возвращает код ошибки или null (успех). Терминальные состояния feed ставит движок. */
    fun download(request: Request, feed: ByteFeed): DownloadResult {
        val builder = OkRequest.Builder().url(request.url)
        applyHeaders(builder, request.headers)
        if (request.rangeStart > 0) {
            builder.header("Range", "bytes=${request.rangeStart}-")
            // Если ресурс сменился, origin обязан ответить 200 с целым телом, и мы не склеим
            // префикс старой версии с хвостом новой (тогда сработает RANGE_UNSUPPORTED).
            request.ifRange?.let { builder.header("If-Range", it) }
        }

        val client = baseClient.newBuilder()
            .connectTimeout(request.profile.connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(request.profile.silenceMs.coerceAtLeast(1000), TimeUnit.MILLISECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        val call = client.newCall(builder.build())
        val firstByte = AtomicBoolean(false)
        val terminal = AtomicReference<ProxyErrorCode?>(null)
        val speedCounter = AtomicLong(0)
        val futures = mutableListOf<ScheduledFuture<*>>()

        futures += watchdog.schedule({
            if (!firstByte.get() && !call.isCanceled()) {
                terminal.compareAndSet(null, ProxyErrorCode.TIMEOUT_FIRST_BYTE)
                call.cancel()
            }
        }, request.profile.firstByteMs, TimeUnit.MILLISECONDS)

        if (request.profile.minSpeedBps > 0 && request.profile.speedWindowMs > 0) {
            futures += watchdog.scheduleAtFixedRate({
                if (firstByte.get() && !call.isCanceled()) {
                    val bytes = speedCounter.getAndSet(0)
                    val bps = bytes * 1000 / request.profile.speedWindowMs.coerceAtLeast(1)
                    if (bps < request.profile.minSpeedBps) {
                        terminal.compareAndSet(null, ProxyErrorCode.SLOW_STREAM)
                        call.cancel()
                    }
                }
            }, request.profile.speedWindowMs, request.profile.speedWindowMs, TimeUnit.MILLISECONDS)
        }

        try {
            call.execute().use { response ->
                val cookies = response.headers("Set-Cookie")
                val status = response.code
                if (status != 200 && status != 206) {
                    return DownloadResult(ProxyErrorCode.forStatus(status), null, cookies)
                }
                val contentType = response.header("Content-Type")
                if (contentType?.contains("text/html", ignoreCase = true) == true) {
                    return DownloadResult(ProxyErrorCode.HTML_RESPONSE, null, cookies)
                }
                if (status == 200 && request.rangeStart > 0) {
                    return DownloadResult(ProxyErrorCode.RANGE_UNSUPPORTED, null, cookies)
                }
                var total = response.body?.contentLength() ?: -1L
                if (status == 206) {
                    val range = response.header("Content-Range")
                    val parsed = parseContentRange(range)
                    if (parsed == null) {
                        return DownloadResult(ProxyErrorCode.RANGE_UNSUPPORTED, null, cookies)
                    }
                    if (parsed.first != request.rangeStart) {
                        return DownloadResult(ProxyErrorCode.RANGE_UNSUPPORTED, null, cookies)
                    }
                    total = parsed.third
                    // Ресурс короче нашего префикса — докачивать нечего,Range невалиден.
                    if (total in 0 until request.rangeStart) {
                        return DownloadResult(ProxyErrorCode.RANGE_UNSUPPORTED, null, cookies)
                    }
                }
                val finalUrl = response.request.url.toString()
                // База до чтения тела: у докачки `written` уже содержит префикс, у pass-through — 0.
                val baseline = feed.written
                feed.setMeta(total, contentType, finalUrl, response.header("ETag"))
                val body = response.body ?: return DownloadResult(ProxyErrorCode.EMPTY_RESPONSE, finalUrl, cookies)
                val input = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    firstByte.set(true)
                    speedCounter.addAndGet(n.toLong())
                    feed.append(buffer, 0, n)
                }
                // Ожидаем `baseline + длина тела`. Для 206 `total` — размер ВСЕГО ресурса, поэтому
                // при Range отличном от нуля ожидание = total - rangeStart, иначе любой 206 с
                // докачкой/pass-through ложно считался бы TRUNCATED.
                val expected = if (total >= 0) baseline + (total - request.rangeStart) else -1L
                if (expected >= 0 && feed.written != expected) {
                    return DownloadResult(ProxyErrorCode.TRUNCATED, finalUrl, cookies)
                }
                if (feed.written == 0L) {
                    return DownloadResult(ProxyErrorCode.EMPTY_RESPONSE, finalUrl, cookies)
                }
                return DownloadResult(null, finalUrl, cookies)
            }
        } catch (e: IOException) {
            val known = terminal.get()
            if (known != null) return DownloadResult(known, null, emptyList())
            // Обрыв после первых байт — не «нет связи», а недокачанный ответ: ретраим с префикса.
            if (feed.written > 0 && !call.isCanceled()) {
                return DownloadResult(ProxyErrorCode.TRUNCATED, null, emptyList())
            }
            if (call.isCanceled()) return DownloadResult(ProxyErrorCode.CONNECT_FAILED, null, emptyList())
            return DownloadResult(mapIOException(e), null, emptyList())
        } finally {
            futures.forEach { it.cancel(false) }
        }
    }

    /** Плейлист/ключ: тело небольшое, читаем целиком с лимитом. */
    fun fetchPlaylist(url: String, headers: Map<String, String>, profile: TimeoutProfile, maxBytes: Int): PlaylistFetch {
        val builder = OkRequest.Builder().url(url)
        applyHeaders(builder, headers)
        val client = baseClient.newBuilder()
            .connectTimeout(profile.connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(profile.firstByteMs.coerceAtLeast(1000), TimeUnit.MILLISECONDS)
            .build()
        return try {
            client.newCall(builder.build()).execute().use { response ->
                val cookies = response.headers("Set-Cookie")
                val status = response.code
                if (status != 200 && status != 206) {
                    return PlaylistFetch(null, null, null, ProxyErrorCode.forStatus(status), cookies)
                }
                val contentType = response.header("Content-Type")
                if (contentType?.contains("text/html", ignoreCase = true) == true) {
                    return PlaylistFetch(null, null, contentType, ProxyErrorCode.HTML_RESPONSE, cookies)
                }
                val body = response.body ?: return PlaylistFetch(null, null, contentType, ProxyErrorCode.EMPTY_RESPONSE, cookies)
                val data = body.bytes()
                if (data.isEmpty()) return PlaylistFetch(null, null, contentType, ProxyErrorCode.EMPTY_RESPONSE, cookies)
                if (data.size > maxBytes) return PlaylistFetch(null, null, contentType, ProxyErrorCode.BAD_CONTENT, cookies)
                PlaylistFetch(data, response.request.url.toString(), contentType, null, cookies)
            }
        } catch (e: IOException) {
            PlaylistFetch(null, null, null, mapIOException(e), emptyList())
        }
    }

    private fun applyHeaders(builder: OkRequest.Builder, headers: Map<String, String>) {
        headers.forEach { (key, value) ->
            if (!hopByHop(key)) builder.header(key, value)
        }
        builder.header("Accept-Encoding", "identity")
    }

    private fun hopByHop(key: String): Boolean = when (key.lowercase()) {
        "host", "range", "connection", "content-length", "accept-encoding", "transfer-encoding",
        "keep-alive", "proxy-connection", "te", "trailer", "upgrade",
        -> true
        else -> false
    }

    private fun parseContentRange(header: String?): Triple<Long, Long, Long>? {
        if (header == null) return null
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)").find(header) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: -1L
        return Triple(start, end, total)
    }

    private fun mapIOException(error: IOException): ProxyErrorCode = when (error) {
        is SocketTimeoutException -> ProxyErrorCode.TIMEOUT_SILENCE
        is ConnectException, is UnknownHostException, is NoRouteToHostException, is SSLException ->
            ProxyErrorCode.CONNECT_FAILED
        else -> ProxyErrorCode.CONNECT_FAILED
    }
}
