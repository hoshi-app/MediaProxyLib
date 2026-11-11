package tv.mediaproxy.core

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

// ── мини-раннер: без JUnit (в офлайн-кеше его нет), но с exit code и списком провалов ─────────────

private var checks = 0
private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    checks++
    if (ok) return
    failures++
    println("FAIL: $name${if (detail.isNotEmpty()) " ($detail)" else ""}")
}

private fun <T> eq(name: String, expected: T, actual: T) =
    check(name, expected == actual, "expected=$expected actual=$actual")

private fun truth(name: String, value: Boolean) = check(name, value)

private fun group(name: String) = println("== $name")

private fun tempDir(): File = Files.createTempDirectory("mediaproxy-test").toFile()

private fun cleanup(dir: File) {
    dir.deleteRecursively()
}

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

fun main() {
    testRange()
    testB64()
    testFnv()
    testResolver()
    testRewriter()
    testFeed()
    testCache()
    testHttpServer()
    testOriginClient()
    testEngine()
    testEnginePlaylistExtras()
    testEngineResumeAndKey()
    testEngineLifecycle()
    println()
    println(if (failures == 0) "OK: $checks проверок" else "ПРОВАЛ: $failures из $checks")
    exitProcess(if (failures > 0) 1 else 0)
}

// ── Range ────────────────────────────────────────────────────────────────────────────────────────

private fun testRange() {
    group("Range")
    check("нет заголовка", Http.parseRange(null, 100) == RangeResult.None)
    check("0-9", Http.parseRange("bytes=0-9", 100) == RangeResult.Valid(0, 9))
    check("50-", Http.parseRange("bytes=50-", 100) == RangeResult.Valid(50, 99))
    check("-10", Http.parseRange("bytes=-10", 100) == RangeResult.Valid(90, 99))
    check("end за пределом", Http.parseRange("bytes=90-500", 100) == RangeResult.Valid(90, 99))
    check("несколько диапазонов -> 200", Http.parseRange("bytes=0-0,2-3", 100) == RangeResult.None)
    check("start за пределом -> 416", Http.parseRange("bytes=200-", 100) == RangeResult.Unsatisfiable)
    check("мусор -> 416", Http.parseRange("bytes=abc", 100) == RangeResult.Unsatisfiable)
    check("-0 -> 416", Http.parseRange("bytes=-0", 100) == RangeResult.Unsatisfiable)
    check("total=0 -> 200", Http.parseRange("bytes=0-1", 0) == RangeResult.None)
    eq("rangeStart", 100L, Http.rangeStart("bytes=100-"))
    eq("rangeStart без диапазона", 0L, Http.rangeStart(null))
    check("origin http", Http.isAllowedOrigin("https://cdn.example/x.m3u8"))
    check("origin loopback запрещён", !Http.isAllowedOrigin("http://127.0.0.1:1/x"))
    check("origin localhost запрещён", !Http.isAllowedOrigin("http://localhost/x"))
    check("origin file запрещён", !Http.isAllowedOrigin("file:///etc/passwd"))
}

// ── b64url / fnv ─────────────────────────────────────────────────────────────────────────────────

private fun testFnv() {
    group("FNV")
    eq("fnv abc", "e71fa2190541574b", Fnv.hex("abc"))
    eq("fnv длина", 16, Fnv.hex("x").length)
    check("fnv различает", Fnv.hex("a") != Fnv.hex("b"))
    check("fnv utf-8", Fnv.hex("привет") == Fnv.hex("привет"))
}

private fun testB64() {
    group("B64Url")
    val samples = listOf(
        "https://p14.solodcdn.com/s/m/hash/720.mp4:hls:manifest.m3u8?ms=1&sig=2",
        "http://127.0.0.1:12345/s/abc/def/1.ts",
        "привет",
        "",
    )
    samples.forEach { sample ->
        eq("b64 roundtrip: ${sample.take(24)}", sample, B64Url.decode(B64Url.encode(sample)))
    }
    check("b64url без паддинга и +/", !B64Url.encode("???>>>").contains('+') && !B64Url.encode("???>>>").contains('/'))
    check("b64 мусор -> null", B64Url.decode("!!!") == null)
}

// ── UrlResolver ─────────────────────────────────────────────────────────────────────────────────

private fun testResolver() {
    group("UrlResolver")
    val base = "https://h.example/s/m/h/a.m3u8?sig=1&ms=2"
    eq("относительный + query базы", "https://h.example/s/m/h/b.ts?sig=1&ms=2", UrlResolver.resolve(base, "b.ts"))
    eq("относительный со своим query", "https://h.example/s/m/h/b.ts?x=2", UrlResolver.resolve(base, "b.ts?x=2"))
    eq("от корня + query базы", "https://h.example/x/b.ts?sig=1&ms=2", UrlResolver.resolve(base, "/x/b.ts"))
    eq("../", "https://h.example/s/m/b.ts?sig=1&ms=2", UrlResolver.resolve(base, "../b.ts"))
    eq("./", "https://h.example/s/m/h/b.ts?sig=1&ms=2", UrlResolver.resolve(base, "./b.ts"))
    eq("protocol-relative", "https://cdn.example/b.ts", UrlResolver.resolve(base, "//cdn.example/b.ts"))
    eq("абсолютный не трогаем", "https://cdn.example/b.ts?k=1", UrlResolver.resolve(base, "https://cdn.example/b.ts?k=1"))
    eq("пустой ref = база", base, UrlResolver.resolve(base, ""))
    eq("без query в базе", "https://h.example/s/m/h/b.ts", UrlResolver.resolve("https://h.example/s/m/h/a.m3u8", "b.ts"))
    eq("loopback без query", "http://127.0.0.1:5000/b.ts", UrlResolver.resolve("http://127.0.0.1:5000/a.m3u8", "b.ts"))
    eq("skd не трогаем", "skd://key", UrlResolver.resolve(base, "skd://key"))
}

// ── HLS rewriter ────────────────────────────────────────────────────────────────────────────────

private fun rewrite(text: String, base: String): RewriteResult =
    PlaylistRewriter.rewrite(text, base) { absolute, kind -> "P|${kind.name}|$absolute" }

private fun uriLines(text: String): List<String> = text.split("\n")
    .map { it.removeSuffix("\r") }
    .filter { it.isNotEmpty() && !it.startsWith("#") }

private fun testRewriter() {
    group("HLS rewriter")

    check("looksLikePlaylist", PlaylistRewriter.looksLikePlaylist("#EXTM3U\n#EXT-X".toByteArray()))
    check("looksLike BOM", PlaylistRewriter.looksLikePlaylist(bytes(0xEF, 0xBB, 0xBF) + "#EXTM3U".toByteArray()))
    check("looksLike html", !PlaylistRewriter.looksLikePlaylist("<html>".toByteArray()))
    check("looksLike пусто", !PlaylistRewriter.looksLikePlaylist(ByteArray(0)))
    check("candidate по content-type", PlaylistRewriter.isPlaylistCandidate("https://x/video/", "application/vnd.apple.mpegurl"))
    check("candidate по .m3u8", PlaylistRewriter.isPlaylistCandidate("https://x/a.m3u8?sig=1", null))
    check("не candidate без расширения", !PlaylistRewriter.isPlaylistCandidate("https://x/video/", null))

    // kodik: относительные сегменты, query в базе
    val kodikBase = "https://p14.solodcdn.com/s/m/hash/2a29:2026/720.mp4:hls:manifest.m3u8?sig=2&ms=1"
    val kodik = """
        #EXTM3U
        #EXT-X-TARGETDURATION:6
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXTINF:6.000,
        ./720.mp4:hls:seg-1-v1-a1.ts
        #EXTINF:6.000,
        ./720.mp4:hls:seg-2-v1-a1.ts
        #EXT-X-ENDLIST
    """.trimIndent() + "\n"
    val kodikResult = rewrite(kodik, kodikBase)
    eq("kodik kind", PlaylistKind.MEDIA, kodikResult.kind)
    truth("kodik cacheable", kodikResult.cacheable)
    eq("kodik segments", 2, kodikResult.media.segments.size)
    eq("kodik start0", 0.0, kodikResult.media.segments[0].startSec)
    eq("kodik start1", 6.0, kodikResult.media.segments[1].startSec)
    eq("kodik target", 6.0, kodikResult.media.targetDuration)
    eq(
        "kodik url0",
        "https://p14.solodcdn.com/s/m/hash/2a29:2026/720.mp4:hls:seg-1-v1-a1.ts?sig=2&ms=1",
        kodikResult.media.segments[0].url,
    )
    eq(
        "kodik proxy0",
        "P|SEGMENT|https://p14.solodcdn.com/s/m/hash/2a29:2026/720.mp4:hls:seg-1-v1-a1.ts?sig=2&ms=1",
        uriLines(kodikResult.text)[0],
    )

    // CVH: вариант без расширения, абсолютные сегменты
    val cvhBase = "https://vd1.okcdn.ru/video/index.m3u8?cmd=x"
    val cvh = """
        #EXTM3U
        #EXT-X-TARGETDURATION:2
        #EXTINF:2.0,
        HIGH00000.ts
        #EXTINF:2.0,
        https://vd1.okcdn.ru/video/HIGH00001.ts?cmd=x
        #EXT-X-ENDLIST
    """.trimIndent() + "\n"
    val cvhResult = rewrite(cvh, cvhBase)
    truth("cvh cacheable", cvhResult.cacheable)
    eq("cvh относительный", "https://vd1.okcdn.ru/video/HIGH00000.ts?cmd=x", cvhResult.media.segments[0].url)
    eq("cvh абсолютный", "https://vd1.okcdn.ru/video/HIGH00001.ts?cmd=x", cvhResult.media.segments[1].url)

    // master + качества
    val masterBase = "https://vd1.okcdn.ru/video/master.m3u8?tok=t"
    val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
        720.m3u8?tok=t
        #EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=1920x1080
        /video/1080.m3u8?tok=t
    """.trimIndent() + "\n"
    val masterResult = rewrite(master, masterBase)
    eq("master kind", PlaylistKind.MASTER, masterResult.kind)
    eq("master variants", 2, masterResult.variants.size)
    eq("master height", 720, masterResult.variants[0].height)
    eq("master width", 1920, masterResult.variants[1].width)
    eq("master bandwidth", 800000, masterResult.variants[0].bandwidth)
    eq("master codecs", "avc1.64001f,mp4a.40.2", masterResult.variants[0].codecs)
    eq("master url0", "https://vd1.okcdn.ru/video/720.m3u8?tok=t", masterResult.variants[0].url)
    eq("master url1", "https://vd1.okcdn.ru/video/1080.m3u8?tok=t", masterResult.variants[1].url)
    truth("master не кэшируется", !masterResult.cacheable)
    check("master lines proxied", uriLines(masterResult.text).all { it.startsWith("P|PLAYLIST|") })

    // fMP4 + AES-128: MAP и KEY переписываются, ключ не кэшируется
    val fmp4 = """
        #EXTM3U
        #EXT-X-TARGETDURATION:4
        #EXT-X-MAP:URI="init.mp4"
        #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x01
        #EXTINF:4.0,
        seg1.m4s
        #EXT-X-ENDLIST
    """.trimIndent() + "\n"
    val fmp4Result = rewrite(fmp4, "https://h.example/v/index.m3u8")
    eq("map uri", "https://h.example/v/init.mp4", fmp4Result.media.initUrl)
    eq("key count", 1, fmp4Result.media.keyUrls.size)
    eq("key uri", "https://h.example/v/key.bin", fmp4Result.media.keyUrls[0])
    truth("key не drm", !fmp4Result.media.drm)
    truth("fmp4 кэшируется (AES не мешает)", fmp4Result.cacheable)
    check("map line proxied", fmp4Result.text.contains("URI=\"P|SEGMENT|https://h.example/v/init.mp4\""))
    check("key line proxied", fmp4Result.text.contains("URI=\"P|KEY|https://h.example/v/key.bin\""))

    // KEY METHOD=NONE — не трогаем
    val noneKey = "#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\ns.ts\n#EXT-X-ENDLIST\n"
    val noneResult = rewrite(noneKey, "https://h.example/v/index.m3u8")
    eq("METHOD=NONE keyUrls", 0, noneResult.media.keyUrls.size)
    truth("METHOD=NONE строка не тронута", noneResult.text.contains("#EXT-X-KEY:METHOD=NONE"))

    // SAMPLE-AES → drm, ключ с skd:// не проксируем
    val drm = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\"\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\ns.ts\n#EXT-X-ENDLIST\n"
    val drmResult = rewrite(drm, "https://h.example/v/index.m3u8")
    truth("SAMPLE-AES drm", drmResult.media.drm)
    truth("drm не кэшируется", !drmResult.cacheable)
    truth("skd:// не проксируется", drmResult.text.contains("URI=\"skd://key\""))

    // BYTERANGE / LIVE / LL-HLS
    val byRange = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\n#EXT-X-BYTERANGE:1000@0\ns.ts\n#EXT-X-ENDLIST\n"
    val byRangeResult = rewrite(byRange, "https://h.example/v/index.m3u8")
    truth("byterange детект", byRangeResult.media.byteRange)
    truth("byterange не кэшируется", !byRangeResult.cacheable)

    val live = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\ns.ts\n"
    val liveResult = rewrite(live, "https://h.example/v/index.m3u8")
    truth("live детект", !liveResult.media.endList)
    truth("live не кэшируется", !liveResult.cacheable)
    eq("live reason", "live playlist", liveResult.reason)

    val llhls = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-PART:DURATION=1,URI=\"p1.ts\"\n#EXTINF:1,\ns.ts\n#EXT-X-ENDLIST\n"
    val llhlsResult = rewrite(llhls, "https://h.example/v/index.m3u8")
    truth("llhls детект", llhlsResult.media.llhls)
    truth("llhls не кэшируется", !llhlsResult.cacheable)

    // EXT-X-MEDIA (audio-rendition)
    val media = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",URI=\"audio/en.m3u8\"\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"a\"\nv720.m3u8\n"
    val mediaResult = rewrite(media, "https://h.example/master.m3u8")
    check("EXT-X-MEDIA переписан", mediaResult.text.contains("URI=\"P|PLAYLIST|https://h.example/audio/en.m3u8\""))

    // CRLF сохраняется
    val crlf = "#EXTM3U\r\n#EXT-X-TARGETDURATION:4\r\n#EXTINF:4,\r\ns.ts\r\n#EXT-X-ENDLIST\r\n"
    val crlfResult = rewrite(crlf, "https://h.example/v/index.m3u8")
    truth("CRLF сохранён", crlfResult.text.contains("\r\n"))

    // отпечаток не зависит от подписи (query) — перерезолв переиспользует кэш
    val a = rewrite(kodik, kodikBase)
    val b = rewrite(kodik, "https://p14.solodcdn.com/s/m/hash/2a29:2026/720.mp4:hls:manifest.m3u8?sig=999&ms=1")
    eq("fingerprint без подписи", a.fingerprint, b.fingerprint)
    val c = rewrite(kodik.replace("seg-2-v1-a1", "seg-3-v1-a1"), kodikBase)
    check("fingerprint различает контент", a.fingerprint != c.fingerprint)
}

// ── ByteFeed ────────────────────────────────────────────────────────────────────────────────────

private fun testFeed() {
    group("ByteFeed")
    val dir = tempDir()
    try {
        val file = File(dir, "a.bin")
        val feed = ByteFeed(file)
        feed.append(bytes(1, 2, 3, 4))
        eq("written", 4L, feed.written)
        eq("ожидание первого байта", null, feed.waitForFirstBytes(1000))
        eq("ожидание смещения", 4L, feed.waitFor(3, 1000))
        feed.finish()
        eq("после finish", true, feed.isFinished)
        eq("файл", 4, file.length().toInt())

        val memory = ByteFeed(null)
        memory.append(bytes(5, 6, 7))
        eq("chunk", 3, memory.readChunk(10, 1000)?.size)
        memory.finish()
        eq("chunk после finish", null, memory.readChunk(10, 1000))

        val waiting = ByteFeed(null)
        eq("таймаут первого байта", ProxyErrorCode.TIMEOUT_FIRST_BYTE, waiting.waitForFirstBytes(50))
        waiting.fail(ProxyErrorCode.ORIGIN_403)
        eq("ошибка после fail", ProxyErrorCode.ORIGIN_403, waiting.waitForFirstBytes(50))
    } finally {
        cleanup(dir)
    }
}

// ── CacheStore ──────────────────────────────────────────────────────────────────────────────────

private fun testCache() {
    group("CacheStore")
    val dir = tempDir()
    try {
        val cfg = ProxyConfig(maxCacheBytes = 2000, minFreeDiskBytes = 0)
        val store = CacheStore(dir) { cfg }
        val stream = store.openStream("key|ep1", "fp1", 1)
        truth("каталог стрима создан", stream.dir.isDirectory)
        truth("журнал создан", File(stream.dir, "journal.log").exists())

        val file = store.fileURL(stream, 0)
        file.writeBytes(ByteArray(50) { it.toByte() })
        store.ensureEntry(stream, 0, 1)
        store.update(stream, 0) {
            it.total = 100
            it.prefix = 50
            it.contentType = "video/mp2t"
        }

        // «рестарт»: новый экземпляр читает журнал и сверяет файлы
        val store2 = CacheStore(dir) { cfg }
        val stream2 = store2.openStream("key|ep1", "fp1", 2)
        val entry = store2.entry(stream2, 0)
        eq("prefix после рестарта", 50L, entry?.prefix)
        eq("total после рестарта", 100L, entry?.total)
        eq("contentType после рестарта", "video/mp2t", entry?.contentType)
        eq("complete после рестарта", false, entry?.complete)

        // файл длиннее журнала → усекаем до коммита
        file.writeBytes(ByteArray(80) { it.toByte() })
        val store3 = CacheStore(dir) { cfg }
        val stream3 = store3.openStream("key|ep1", "fp1", 3)
        eq("усечение до prefix", 50L, store3.entry(stream3, 0)?.prefix)
        eq("файл усечён", 50L, store3.fileURL(stream3, 0).length())

        // complete
        store3.update(stream3, 0) {
            it.prefix = 50
            it.total = 50
            it.complete = true
        }
        val store4 = CacheStore(dir) { cfg }
        val stream4 = store4.openStream("key|ep1", "fp1", 4)
        eq("complete после рестарта", true, store4.entry(stream4, 0)?.complete)

        // смена отпечатка → старый каталог невалиден
        val stream5 = store4.openStream("key|ep1", "fp2", 5)
        truth("новый отпечаток", stream5.fingerprint == "fp2")
        eq("старые записи не унаследованы", null, store4.entry(stream5, 0))

        // эвикция целыми стримами
        val big = CacheStore(dir) { ProxyConfig(maxCacheBytes = 1200, minFreeDiskBytes = 0) }
        val s1 = big.openStream("a", "fp", 1)
        val s2 = big.openStream("b", "fp", 2)
        for (s in listOf(s1, s2)) {
            val f = big.fileURL(s, 0)
            f.writeBytes(ByteArray(500))
            big.ensureEntry(s, 0, 1)
            big.update(s, 0) { it.total = 500; it.prefix = 500; it.complete = true }
        }
        eq("всего до эвикции", 1000L, big.totalBytes())
        truth("место под 500 есть", big.evictIfNeeded(500, emptySet(), 3))
        truth("что-то вытеснено", big.totalBytes() <= 900)

        // защищённый стрим не трогаем
        val protectedDir = s1.dir
        val freed = big.clear("a")
        truth("clear вернул байты", freed >= 0)
        truth("каталог удалён", !protectedDir.exists() || protectedDir.length() == 0L)
    } finally {
        cleanup(dir)
    }
}

// ── HTTP-сервер (конформанс) ────────────────────────────────────────────────────────────────────

private fun testHttpServer() {
    group("HttpServer")
    val dir = tempDir()
    try {
        val payload = ByteArray(100) { it.toByte() }
        val growingFile = File(dir, "grow.bin")
        growingFile.delete()
        val growingFeed = ByteFeed(growingFile)
        val unknownFeed = ByteFeed(null)
        val server = HttpServer(maxConnections = 32, idleTimeoutMs = 5000) { _, path, _ ->
            when (path) {
                "/bytes" -> HttpReply(200, mapOf("Content-Type" to "application/octet-stream"),
                                      BytesBody(payload), payload.size.toLong())
                "/grow" -> HttpReply(200, mapOf("Content-Type" to "video/mp2t"),
                                     GrowingFileBody(growingFile, growingFeed), payload.size.toLong())
                "/unknown" -> HttpReply(200, mapOf("Content-Type" to "text/plain"),
                                        StreamBody(unknownFeed), -1)
                else -> HttpReply.text(404, "not found")
            }
        }
        server.start()
        val port = server.boundPort

        val full = rawGet(port, "/bytes")
        eq("status", 200, full.status)
        eq("content-length", "100", full.headers["content-length"])
        eq("body", 100, full.body.size)
        truth("body совпал", full.body.contentEquals(payload))

        val ranged = rawGet(port, "/bytes", mapOf("Range" to "bytes=10-19"))
        eq("range status", 206, ranged.status)
        eq("content-range", "bytes 10-19/100", ranged.headers["content-range"])
        eq("range body", 10, ranged.body.size)
        truth("range байты", ranged.body.contentEquals(payload.copyOfRange(10, 20)))

        val suffix = rawGet(port, "/bytes", mapOf("Range" to "bytes=-10"))
        eq("suffix status", 206, suffix.status)
        truth("suffix байты", suffix.body.contentEquals(payload.copyOfRange(90, 100)))

        val head = rawGet(port, "/bytes", close = true, method = "HEAD")
        eq("head status", 200, head.status)
        eq("head content-length", "100", head.headers["content-length"])
        eq("head body", 0, head.body.size)

        val invalid = rawGet(port, "/bytes", mapOf("Range" to "bytes=500-"))
        eq("416", 416, invalid.status)
        eq("416 content-range", "bytes */100", invalid.headers["content-range"])

        // keep-alive: два ответа в одном сокете
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val request = "GET /bytes HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: keep-alive\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            val first = readRawResponse(socket.getInputStream())
            socket.getOutputStream().write(request.toByteArray())
            socket.getOutputStream().flush()
            val second = readRawResponse(socket.getInputStream())
            eq("keep-alive 1", 200, first.status)
            eq("keep-alive 2", 200, second.status)
            eq("keep-alive body", 100, second.body.size)
        }

        // потоковая запись в растущий файл
        val growThread = Thread {
            for (i in 0 until 5) {
                growingFeed.append(payload, i * 20, 20)
                Thread.sleep(20)
            }
            growingFeed.finish()
        }.apply { isDaemon = true; start() }
        val grown = rawGet(port, "/grow")
        growThread.join(2000)
        eq("grow status", 200, grown.status)
        eq("grow length", "100", grown.headers["content-length"])
        truth("grow body", grown.body.contentEquals(payload))

        // неизвестная длина — close-delimited
        Thread {
            unknownFeed.append("hello ".toByteArray())
            unknownFeed.append("world".toByteArray())
            unknownFeed.finish()
        }.apply { isDaemon = true; start() }
        val unknown = rawGet(port, "/unknown")
        eq("unknown status", 200, unknown.status)
        eq("unknown нет content-length", null, unknown.headers["content-length"])
        eq("unknown body", "hello world", String(unknown.body))

        // параллельные соединения
        val latch = CountDownLatch(8)
        val okCount = AtomicInteger(0)
        repeat(8) {
            Thread {
                runCatching {
                    val r = rawGet(port, "/bytes")
                    if (r.status == 200 && r.body.size == 100) okCount.incrementAndGet()
                }
                latch.countDown()
            }.start()
        }
        truth("8 параллельных", latch.await(10, java.util.concurrent.TimeUnit.SECONDS) && okCount.get() == 8)

        server.stop()
    } finally {
        cleanup(dir)
    }
}

private fun rawGet(
    port: Int,
    path: String,
    headers: Map<String, String> = emptyMap(),
    close: Boolean = true,
    method: String = "GET",
): RawResponse {
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        val request = StringBuilder("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n")
        headers.forEach { (key, value) -> request.append("$key: $value\r\n") }
        if (close) request.append("Connection: close\r\n")
        request.append("\r\n")
        socket.getOutputStream().write(request.toString().toByteArray())
        socket.getOutputStream().flush()
        return readRawResponse(socket.getInputStream())
    }
}

private data class RawResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

private fun readRawResponse(input: InputStream): RawResponse {
    val head = StringBuilder()
    var state = 0
    while (true) {
        val b = input.read()
        if (b < 0) throw IllegalStateException("eof в заголовках")
        head.append(b.toChar())
        state = when {
            state == 0 && b == '\r'.code -> 1
            state == 1 && b == '\n'.code -> 2
            state == 2 && b == '\r'.code -> 3
            state == 3 && b == '\n'.code -> 4
            else -> 0
        }
        if (state == 4) break
    }
    val lines = head.toString().trimEnd('\r', '\n').split("\r\n")
    val status = lines.first().split(' ')[1].toInt()
    val headers = lines.drop(1).filter { it.contains(':') }.associate {
        it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
    }
    val length = headers["content-length"]?.toIntOrNull()
    val body = if (length != null) readExactly(input, length) else input.readBytes()
    return RawResponse(status, headers, body)
}

private fun readExactly(input: InputStream, count: Int): ByteArray {
    val out = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = input.read(out, read, count - read)
        if (n < 0) break
        read += n
    }
    return if (read == count) out else out.copyOf(read)
}

// ── OriginClient ───────────────────────────────────────────────────────────────────────────────

private fun testOriginClient() {
    group("OriginClient")
    val origin = FixtureOrigin().start()
    val client = OriginClient { ProxyConfig() }
    val dir = tempDir()
    try {
        val profile = TimeoutProfile(
            connectMs = 1000, firstByteMs = 1000, silenceMs = 1000, minSpeedBps = 0, speedWindowMs = 0,
        )

        val feed = ByteFeed(File(dir, "ok.bin"))
        val ok = client.download(OriginClient.Request("${origin.baseUrl}/seg/0.ts", emptyMap(), 0, profile, false), feed)
        eq("скачивание ok", null, ok.code)
        eq("written", origin.segmentSize.toLong(), feed.written)
        truth("байты совпали", File(dir, "ok.bin").readBytes().contentEquals(origin.segmentBytes))
        eq("origin hits", 1, origin.hits("seg-0"))

        // resume: уже есть 100 байт, Range продолжает
        val resumeFile = File(dir, "resume.bin")
        val resumeFeed = ByteFeed(resumeFile)
        resumeFeed.append(origin.segmentBytes, 0, 100)
        val resumed = client.download(
            OriginClient.Request("${origin.baseUrl}/seg/1.ts", emptyMap(), 100, profile, false), resumeFeed,
        )
        eq("resume код", null, resumed.code)
        eq("resume total", origin.segmentSize.toLong(), resumeFeed.total)
        eq("resume written", origin.segmentSize.toLong(), resumeFeed.written)
        truth("resume байты", resumeFile.readBytes().contentEquals(origin.segmentBytes))

        // 200 в ответ на Range — сигнал движку
        val ignore = client.download(
            OriginClient.Request("${origin.baseUrl}/ignore-range", emptyMap(), 100, profile, false),
            ByteFeed(File(dir, "ignore.bin")),
        )
        eq("200 на Range", ProxyErrorCode.RANGE_UNSUPPORTED, ignore.code)

        eq(
            "html",
            ProxyErrorCode.HTML_RESPONSE,
            client.download(OriginClient.Request("${origin.baseUrl}/html", emptyMap(), 0, profile, false),
                            ByteFeed(File(dir, "html.bin"))).code,
        )
        eq(
            "403",
            ProxyErrorCode.ORIGIN_403,
            client.download(OriginClient.Request(origin.forbiddenUrl, emptyMap(), 0, profile, false),
                            ByteFeed(File(dir, "403.bin"))).code,
        )
        eq(
            "truncated",
            ProxyErrorCode.TRUNCATED,
            client.download(OriginClient.Request("${origin.baseUrl}/truncated", emptyMap(), 0, profile, false),
                            ByteFeed(File(dir, "trunc.bin"))).code,
        )

        val flaky1 = client.download(OriginClient.Request("${origin.baseUrl}/flaky", emptyMap(), 0, profile, false),
                                     ByteFeed(File(dir, "flaky1.bin")))
        eq("flaky 1", ProxyErrorCode.ORIGIN_5XX, flaky1.code)
        val flaky2 = client.download(OriginClient.Request("${origin.baseUrl}/flaky", emptyMap(), 0, profile, false),
                                     ByteFeed(File(dir, "flaky2.bin")))
        eq("flaky 2", null, flaky2.code)

        val silent = client.download(
            OriginClient.Request("${origin.baseUrl}/silent", emptyMap(), 0,
                                 profile.copy(firstByteMs = 300, silenceMs = 300), false),
            ByteFeed(File(dir, "silent.bin")),
        )
        eq("тишина", ProxyErrorCode.TIMEOUT_FIRST_BYTE, silent.code)

        val slow = client.download(
            OriginClient.Request("${origin.baseUrl}/slow", emptyMap(), 0,
                                 profile.copy(firstByteMs = 2000, silenceMs = 2000, minSpeedBps = 100_000, speedWindowMs = 200), false),
            ByteFeed(File(dir, "slow.bin")),
        )
        eq("пол скорости", ProxyErrorCode.SLOW_STREAM, slow.code)

        val playlist = client.fetchPlaylist(origin.mediaUrl, emptyMap(), TimeoutProfile.playlist(ProxyConfig()), 1024 * 1024)
        truth("плейлист прочитан", playlist.error == null)
        truth("плейлист содержит #EXTM3U", playlist.data?.let { String(it).contains("#EXTM3U") } == true)
    } finally {
        client.shutdown()
        origin.stop()
        cleanup(dir)
    }
}

// ── Engine: end-to-end ────────────────────────────────────────────────────────────────────────

private fun testEngine() {
    group("ProxyEngine")
    val origin = FixtureOrigin().start()
    val dir = tempDir()
    val engine = ProxyEngine(File(dir, "engine"))
    val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    engine.onEvent = { name, payload -> synchronized(events) { events.add(name to payload) } }
    try {
        engine.setConfig(
            mapOf(
                "maxCacheMB" to 8,
                "minFreeDiskMB" to 0,
                "prewarmSegments" to 0,
                "prefetchMaxSegments" to 0,
                "reqFirstByteSec" to 2,
                "reqSilenceSec" to 2,
                "prefetchRetries" to 0,
                "retryBackoffMs" to listOf(0L),
                "cooldownSec" to 60,
                "maxLoopbackConnections" to 16,
                "allowLoopbackOrigins" to true,
            ),
        )
        val start = engine.start()
        truth("engine start", start.running)
        truth("порт > 0", engine.port > 0)

        val headers = mapOf("User-Agent" to "MediaProxyTest")
        val resolved = engine.resolve(origin.mediaUrl, ProxyEngine.ResolveOptions("k|ep1", headers, cache = true, prewarm = false))
        truth("resolve cached", resolved.cached)
        truth("resolve url loopback", resolved.url.startsWith("http://127.0.0.1:${engine.port}/s/"))
        truth("extension .m3u8", resolved.url.endsWith(".m3u8"))

        val playlist = httpGet(resolved.url)
        eq("плейлист через прокси", 200, playlist.status)
        val playlistText = String(playlist.body)
        truth("плейлист переписан", playlistText.contains("127.0.0.1:${engine.port}/s/"))
        val segUrl = uriLines(playlistText).first()
        truth("сегмент .ts", segUrl.endsWith(".ts"))

        val seg1 = httpGet(segUrl)
        eq("сегмент 1", 200, seg1.status)
        truth("сегмент 1 байты", seg1.body.contentEquals(origin.segmentBytes))
        waitUntil(3000) {
            val state = engine.streamState(resolved.streamId)
            @Suppress("UNCHECKED_CAST")
            val segments = state["segments"] as List<Map<String, Any?>>
            segments.firstOrNull()?.get("complete") == true
        }
        eq("origin seg-0 после первого запроса", 1, origin.hits("seg-0"))

        val seg2 = httpGet(segUrl)
        eq("сегмент 2 hit", 200, seg2.status)
        truth("сегмент 2 байты", seg2.body.contentEquals(origin.segmentBytes))
        eq("origin не дёргали повторно", 1, origin.hits("seg-0"))
        truth("hits растут", (engine.stats()["hits"] as Long) >= 1)

        val ranged = httpGet(segUrl, mapOf("Range" to "bytes=10-19"))
        eq("range на кэше status", 206, ranged.status)
        truth("range на кэше байты", ranged.body.contentEquals(origin.segmentBytes.copyOfRange(10, 20)))
        eq("range content-range", "bytes 10-19/1000", ranged.headers["content-range"])

        // перерезолв с новым токеном и тем же cacheKey: сегмент должен взяться из кэша
        origin.token = "t2"
        val second = engine.resolve(origin.mediaUrl, ProxyEngine.ResolveOptions("k|ep1", headers, cache = true, prewarm = false))
        val playlist2 = httpGet(second.url)
        eq("плейлист 2", 200, playlist2.status)
        val segUrl2 = uriLines(String(playlist2.body)).first()
        val encoded = java.net.URI(segUrl2).path.split('/').filter { it.isNotEmpty() }.getOrNull(2) ?: ""
        truth("новый токен в URL", B64Url.decode(encoded)?.contains("tok=t2") == true)
        val seg3 = httpGet(segUrl2)
        eq("сегмент после перерезолва", 200, seg3.status)
        truth("сегмент после перерезолва байты", seg3.body.contentEquals(origin.segmentBytes))
        eq("origin seg-0 после перерезолва", 1, origin.hits("seg-0"))

        // master: варианты переписаны
        val masterResolved = engine.resolve(origin.masterUrl, ProxyEngine.ResolveOptions("k|master", headers))
        val masterPlaylist = httpGet(masterResolved.url)
        eq("master status", 200, masterPlaylist.status)
        truth("master переписан", String(masterPlaylist.body).contains("127.0.0.1:${engine.port}/s/"))
        truth("master варианты .m3u8", uriLines(String(masterPlaylist.body)).all { it.endsWith(".m3u8") })

        // fail-open: старые URL после release не обслуживаются
        engine.failOpen(second.streamId)
        val stale = httpGet(segUrl2)
        eq("после failOpen 410", 410, stale.status)

        // 403 плейлиста → onAuthRequired
        val forbidden = engine.resolve(origin.forbiddenUrl, ProxyEngine.ResolveOptions("k|auth", headers))
        val forbiddenReply = httpGet(forbidden.url)
        eq("403 -> 502", 502, forbiddenReply.status)
        waitUntil(2000) { synchronized(events) { events.any { it.first == "onAuthRequired" } } }
        val authEvent = synchronized(events) { events.firstOrNull { it.first == "onAuthRequired" } }
        eq("onAuthRequired originStatus", 403, authEvent?.second?.get("originStatus"))

        // 5xx сегмента → один onError, затем cooldown без повторных запросов
        val badPlaylist = engine.resolve("${origin.baseUrl}/bad.m3u8", ProxyEngine.ResolveOptions("k|bad", headers))
        val badReply1 = httpGet(badPlaylist.url)
        eq("bad playlist 200", 200, badReply1.status)
        val badSeg = uriLines(String(badReply1.body)).first()
        val bad1 = httpGet(badSeg)
        eq("bad segment 502", 502, bad1.status)
        eq("bad origin один раз", 1, origin.hits("bad"))
        val bad2 = httpGet(badSeg)
        eq("bad segment cooldown 502", 502, bad2.status)
        eq("bad origin без повторов", 1, origin.hits("bad"))
        waitUntil(2000) { synchronized(events) { events.any { it.first == "onError" && it.second["code"] == "ORIGIN_5XX" } } }

        // таймаут сегмента → 504
        val slowPlaylist = engine.resolve("${origin.baseUrl}/slowplaylist.m3u8", ProxyEngine.ResolveOptions("k|slow", headers))
        val slowReply = httpGet(slowPlaylist.url)
        eq("slow playlist 200", 200, slowReply.status)
        val slowSeg = uriLines(String(slowReply.body)).first()
        val slow = httpGet(slowSeg)
        eq("таймаут сегмента 504", 504, slow.status)

        // clearCache
        val cleared = engine.clearCache()
        truth("clearCache вернул байты", (cleared["freedBytes"] as Long) >= 0)

        val runtime = engine.stats()
        eq("stats running", true, runtime["running"])
        truth("stats maxBytes", (runtime["maxBytes"] as Long) > 0)
        truth("stats freeDiskBytes", (runtime["freeDiskBytes"] as Long) > 0)
    } finally {
        engine.stop()
        origin.stop()
        cleanup(dir)
    }
}

private fun firstSegment(text: String): String =
    uriLines(text).firstOrNull { it.startsWith("http://127.0.0.1:") } ?: ""

private fun testConfig(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
    "maxCacheMB" to 8,
    "minFreeDiskMB" to 0,
    "prewarmSegments" to 0,
    "prefetchMaxSegments" to 0,
    "reqFirstByteSec" to 2,
    "reqSilenceSec" to 2,
    "prefetchRetries" to 1,
    "retryBackoffMs" to listOf(0L),
    "cooldownSec" to 60,
    "allowLoopbackOrigins" to true,
    "maxLoopbackConnections" to 16,
) + extra

private fun newEngine(dir: File, extra: Map<String, Any?> = emptyMap()): ProxyEngine {
    val engine = ProxyEngine(File(dir, "engine"))
    engine.setConfig(testConfig(extra))
    engine.start()
    return engine
}

private fun testEnginePlaylistExtras() {
    group("ProxyEngine: плейлисты/заголовки")
    val origin = FixtureOrigin().start()
    val dir = tempDir()
    val engine = newEngine(dir)
    try {
        val headers = mapOf("User-Agent" to "ProxyTest/1.0")
        val plain = engine.resolve(origin.plainUrl, ProxyEngine.ResolveOptions("plain|1", headers, prewarm = false))
        truth("plain resolve", plain.cached)
        val playlist = httpGet(plain.url)
        eq("plain status", 200, playlist.status)
        eq("plain content-type", "application/vnd.apple.mpegurl", playlist.headers["content-type"])
        val segments = uriLines(String(playlist.body))
        eq("plain один сегмент", 1, segments.size)
        truth("plain сегмент .ts", segments.firstOrNull()?.endsWith(".ts") == true)

        val ranged = httpGet(plain.url, mapOf("Range" to "bytes=0-1"))
        eq("range на плейлисте status", 206, ranged.status)
        eq("range на плейлисте body", 2, ranged.body.size)
        eq("range на плейлисте header", "bytes 0-1/${playlist.body.size}", ranged.headers["content-range"])

        val head = httpGet(plain.url, method = "HEAD")
        eq("HEAD плейлиста status", 200, head.status)
        eq("HEAD плейлиста length", playlist.body.size.toString(), head.headers["content-length"])
        eq("HEAD плейлиста body", 0, head.body.size)

        val media = engine.resolve(origin.mediaUrl, ProxyEngine.ResolveOptions("media|1", headers, prewarm = false))
        val mediaPlaylist = httpGet(media.url)
        eq("media playlist", 200, mediaPlaylist.status)
        val second = uriLines(String(mediaPlaylist.body)).getOrNull(1) ?: ""
        truth("второй сегмент loopback", second.startsWith("http://127.0.0.1:"))
        val segment = httpGet(second)
        eq("segment 1", 200, segment.status)
        eq("UA доехал до сегмента", "ProxyTest/1.0", origin.recordedHeaders["user-agent"])
        eq("Accept-Encoding identity", "identity", origin.recordedHeaders["accept-encoding"])
        eq("Origin не добавлен", "", origin.recordedHeaders["origin"])
    } finally {
        engine.stop()
        origin.stop()
        cleanup(dir)
    }
}

private fun testEngineResumeAndKey() {
    group("ProxyEngine: докачка и AES-ключ")
    val origin = FixtureOrigin().start()
    val dir = tempDir()
    val engine = newEngine(dir)
    try {
        val headers = mapOf("User-Agent" to "ProxyTest/1.0")
        val resume = engine.resolve(origin.resumeUrl, ProxyEngine.ResolveOptions("resume|1", headers, prewarm = false))
        val resumePlaylist = httpGet(resume.url)
        eq("resume playlist", 200, resumePlaylist.status)
        val resumeSegment = firstSegment(String(resumePlaylist.body))
        truth("resume сегмент loopback", resumeSegment.startsWith("http://127.0.0.1:"))
        val resumed = httpGet(resumeSegment)
        eq("resume status", 200, resumed.status)
        truth("resume байты", resumed.body.contentEquals(origin.segmentBytes))
        truth("resume был повтор", origin.hits("resume-flaky") >= 2)

        val bytesBefore = engine.stats()["bytes"] as Long
        val encrypted = engine.resolve(origin.encryptedUrl, ProxyEngine.ResolveOptions("enc|1", headers, prewarm = false))
        val encPlaylist = httpGet(encrypted.url)
        eq("enc playlist", 200, encPlaylist.status)
        val keyUrl = Regex("URI=\"([^\"]+)\"").find(String(encPlaylist.body))?.groupValues?.get(1) ?: ""
        truth("key URL loopback", keyUrl.startsWith("http://127.0.0.1:"))
        truth("key URL .key", keyUrl.endsWith(".key"))
        val key = httpGet(keyUrl)
        eq("key status", 200, key.status)
        truth("key байты", key.body.contentEquals(origin.keyBytes))
        eq("ключ не попал на диск", bytesBefore, engine.stats()["bytes"] as Long)
    } finally {
        engine.stop()
        origin.stop()
        cleanup(dir)
    }
}

private fun testEngineLifecycle() {
    group("ProxyEngine: жизненный цикл")
    val origin = FixtureOrigin().start()
    val dir = tempDir()
    val engine = newEngine(dir, mapOf("maxSessions" to 1))
    val zeroDir = tempDir()
    var zero: ProxyEngine? = null
    try {
        val headers = mapOf("User-Agent" to "ProxyTest/1.0")
        val a = engine.resolve(origin.plainUrl, ProxyEngine.ResolveOptions("a|1", headers, prewarm = false))
        val b = engine.resolve(origin.encryptedUrl, ProxyEngine.ResolveOptions("b|1", headers, prewarm = false))
        eq("старая сессия вытеснена", 410, httpGet(a.url).status)
        eq("новая сессия жива", 200, httpGet(b.url).status)
        engine.release(b.streamId)
        eq("release -> 410", 410, httpGet(b.url).status)

        val c = engine.resolve(origin.plainUrl, ProxyEngine.ResolveOptions("c|1", headers, prewarm = false))
        val failed = engine.failOpen(c.url)
        eq("failOpen по URL вернул origin", origin.plainUrl, failed?.get("originUrl"))
        eq("failOpen -> 410", 410, httpGet(c.url).status)

        zero = newEngine(zeroDir, mapOf("maxCacheMB" to 0))
        val zeroResolved = zero.resolve(origin.mediaUrl, ProxyEngine.ResolveOptions("zero|1", headers, prewarm = false))
        val zeroPlaylist = httpGet(zeroResolved.url)
        eq("pass-through playlist", 200, zeroPlaylist.status)
        val zeroSegment = firstSegment(String(zeroPlaylist.body))
        eq("pass-through segment", 200, httpGet(zeroSegment).status)
        eq("при maxCacheMB=0 ничего не кэшируется", 0L, zero.stats()["bytes"] as Long)

        val cached = engine.resolve(origin.mediaUrl, ProxyEngine.ResolveOptions("clear|1", headers, prewarm = false))
        val cachedPlaylist = httpGet(cached.url)
        val cachedSegment = firstSegment(String(cachedPlaylist.body))
        eq("сегмент до clear", 200, httpGet(cachedSegment).status)
        waitUntil(3000) {
            @Suppress("UNCHECKED_CAST")
            val segments = engine.streamState(cached.streamId)["segments"] as List<Map<String, Any?>>
            segments.firstOrNull()?.get("complete") == true
        }
        val hitsBefore = origin.hits("seg-0")
        val cleared = engine.clearCache(cached.streamId)
        truth("clearCache освободил байты", (cleared["freedBytes"] as Long) > 0)
        val afterClear = httpGet(cachedSegment)
        eq("сегмент после clear", 200, afterClear.status)
        truth("после clear перекачали", origin.hits("seg-0") > hitsBefore)

        engine.stop()
        val restarted = engine.start()
        truth("restart running", restarted.running)
        val restartMedia = engine.resolve(origin.mediaUrl, ProxyEngine.ResolveOptions("restart|1", headers, prewarm = false))
        val restartPlaylist = httpGet(restartMedia.url)
        eq("restart playlist", 200, restartPlaylist.status)
        val restartSegment = firstSegment(String(restartPlaylist.body))
        val restartResult = httpGet(restartSegment)
        eq("restart segment", 200, restartResult.status)
        truth("restart байты", restartResult.body.contentEquals(origin.segmentBytes))

        // мусорный запрос не валит сервер
        Socket("127.0.0.1", engine.port).use { socket ->
            socket.soTimeout = 2000
            socket.getOutputStream().write("GARBAGE\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            runCatching { socket.getInputStream().readBytes() }
        }
        eq("сервер жив после мусора", 200, httpGet(restartMedia.url).status)
    } finally {
        zero?.stop()
        engine.stop()
        origin.stop()
        cleanup(dir)
        cleanup(zeroDir)
    }
}

private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(20)
    }
    return condition()
}

// ── HTTP-клиент для тестов ──────────────────────────────────────────────────────────────────────

private data class HttpResult(val status: Int, val body: ByteArray, val headers: Map<String, String>)

private fun httpGet(url: String, headers: Map<String, String> = emptyMap(), method: String = "GET"): HttpResult {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = 5000
    connection.readTimeout = 15000
    connection.instanceFollowRedirects = false
    headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
    val status = connection.responseCode
    val stream = if (method == "HEAD") null else if (status < 400) connection.inputStream else connection.errorStream
    val body = stream?.use { it.readBytes() } ?: ByteArray(0)
    val responseHeaders = LinkedHashMap<String, String>()
    connection.headerFields.forEach { (key, values) ->
        if (key != null) responseHeaders[key.lowercase()] = values.firstOrNull() ?: ""
    }
    connection.disconnect()
    return HttpResult(status, body, responseHeaders)
}
