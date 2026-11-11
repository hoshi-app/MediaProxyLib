package tv.mediaproxy.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Тестовый origin: отдаёт media/master-плейлисты и сегменты, умеет 206, 403, HTML, обрыв, тишину.
 * Счётчики запросов нужны, чтобы проверять «отдали из кэша, в origin не ходили».
 */
class FixtureOrigin {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newFixedThreadPool(8)
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    var token: String = "t1"

    val segmentSize = 1000
    val segmentBytes: ByteArray = ByteArray(segmentSize) { (it * 7 % 251).toByte() }

    val port: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    val mediaUrl: String get() = "$baseUrl/playlist.m3u8?tok=$token"
    val masterUrl: String get() = "$baseUrl/master.m3u8?tok=$token"
    val forbiddenUrl: String get() = "$baseUrl/forbidden.m3u8"
    val liveUrl: String get() = "$baseUrl/live.m3u8"

    fun start(): FixtureOrigin {
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        return this
    }

    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    fun hits(key: String): Int = counters[key]?.get() ?: 0

    private fun bump(key: String) {
        counters.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun mediaPlaylist(): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-TARGETDURATION:4")
        appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        for (i in 0 until 3) {
            appendLine("#EXTINF:4.000,")
            appendLine("$baseUrl/seg/$i.ts?tok=$token")
        }
        appendLine("#EXT-X-ENDLIST")
    }

    private fun masterPlaylist(): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,CODECS=\"avc1.64001f,mp4a.40.2\"")
        appendLine("$baseUrl/720.m3u8?tok=$token")
        appendLine("#EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1920x1080")
        appendLine("$baseUrl/1080.m3u8?tok=$token")
    }

    private fun livePlaylist(): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-TARGETDURATION:4")
        appendLine("#EXTINF:4.000,")
        appendLine("$baseUrl/seg/0.ts?tok=$token")
    }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val range = exchange.requestHeaders.getFirst("Range")
        when {
            path == "/playlist.m3u8" -> {
                bump("playlist")
                send(exchange, 200, "application/vnd.apple.mpegurl", mediaPlaylist().toByteArray(), range)
            }
            path == "/master.m3u8" -> {
                bump("master")
                send(exchange, 200, "application/vnd.apple.mpegurl", masterPlaylist().toByteArray(), range)
            }
            path == "/live.m3u8" -> {
                bump("live")
                send(exchange, 200, "application/vnd.apple.mpegurl", livePlaylist().toByteArray(), range)
            }
            path == "/bad.m3u8" -> {
                bump("bad-playlist")
                val text = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.000,\n$baseUrl/bad.ts\n#EXT-X-ENDLIST\n"
                send(exchange, 200, "application/vnd.apple.mpegurl", text.toByteArray(), range)
            }
            path == "/bad.ts" -> {
                bump("bad")
                send(exchange, 500, "text/plain", "boom".toByteArray(), null)
            }
            path == "/slowplaylist.m3u8" -> {
                bump("slow-playlist")
                val text = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.000,\n$baseUrl/silent\n#EXT-X-ENDLIST\n"
                send(exchange, 200, "application/vnd.apple.mpegurl", text.toByteArray(), range)
            }
            path == "/slow" -> {
                bump("slow")
                exchange.sendResponseHeaders(200, 100)
                repeat(10) {
                    Thread.sleep(200)
                    exchange.responseBody.write(ByteArray(10) { (it + 1).toByte() })
                    exchange.responseBody.flush()
                }
                exchange.close()
            }
            path == "/forbidden.m3u8" -> {
                bump("forbidden")
                send(exchange, 403, "text/html", "forbidden".toByteArray(), null)
            }
            path == "/html" -> {
                bump("html")
                send(exchange, 200, "text/html", "<html>error</html>".toByteArray(), null)
            }
            path.startsWith("/seg/") -> {
                val index = path.removePrefix("/seg/").substringBefore('.')
                bump("seg-$index")
                send(exchange, 200, "video/mp2t", segmentBytes, range)
            }
            path == "/silent" -> {
                bump("silent")
                Thread.sleep(10_000)
                exchange.close()
            }
            path == "/truncated" -> {
                bump("truncated")
                exchange.sendResponseHeaders(200, segmentSize.toLong())
                exchange.responseBody.write(segmentBytes, 0, segmentSize / 2)
                exchange.responseBody.flush()
                exchange.close()
            }
            path == "/flaky" -> {
                val n = counters.computeIfAbsent("flaky") { AtomicInteger(0) }.incrementAndGet()
                if (n == 1) {
                    send(exchange, 503, "text/plain", "busy".toByteArray(), null)
                } else {
                    send(exchange, 200, "video/mp2t", segmentBytes, range)
                }
            }
            path == "/ignore-range" -> {
                bump("ignore-range")
                send(exchange, 200, "video/mp2t", segmentBytes, null)
            }
            else -> send(exchange, 404, "text/plain", "not found".toByteArray(), null)
        }
    }

    private fun send(exchange: HttpExchange, status: Int, type: String, body: ByteArray, range: String?) {
        var payload = body
        var code = status
        var contentRange: String? = null
        if (range != null && body.isNotEmpty()) {
            when (val parsed = Http.parseRange(range, body.size.toLong())) {
                is RangeResult.Valid -> {
                    code = 206
                    payload = body.copyOfRange(parsed.start.toInt(), (parsed.end + 1).toInt())
                    contentRange = "bytes ${parsed.start}-${parsed.end}/${body.size}"
                }
                else -> Unit
            }
        }
        exchange.responseHeaders.add("Content-Type", type)
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        if (contentRange != null) exchange.responseHeaders.add("Content-Range", contentRange)
        exchange.sendResponseHeaders(code, payload.size.toLong())
        exchange.responseBody.write(payload)
        exchange.close()
    }
}
