package tv.mediaproxy.core

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.URI

enum class ResourceKind(val fallbackExtension: String) {
    PLAYLIST("m3u8"),
    SEGMENT("ts"),
    KEY("key"),
}

sealed class RangeResult {
    data object None : RangeResult()
    data class Valid(val start: Long, val end: Long) : RangeResult()
    data object Unsatisfiable : RangeResult()
}

object Http {
    val statusText: Map<Int, String> = mapOf(
        200 to "OK", 206 to "Partial Content", 400 to "Bad Request", 403 to "Forbidden",
        404 to "Not Found", 410 to "Gone", 416 to "Range Not Satisfiable", 429 to "Too Many Requests",
        500 to "Internal Server Error", 502 to "Bad Gateway", 503 to "Service Unavailable",
        504 to "Gateway Timeout",
    )

    /**
     * Разбор одного `Range`. Несколько диапазонов/неизвестный формат -> None (отдаём 200),
     * заведомо неверный диапазон -> Unsatisfiable (416).
     */
    fun parseRange(header: String?, total: Long): RangeResult {
        if (header == null || total <= 0) return RangeResult.None
        val value = header.trim()
        if (!value.startsWith("bytes=", ignoreCase = true)) return RangeResult.None
        val spec = value.substring("bytes=".length)
        if (spec.contains(',')) return RangeResult.None
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.Unsatisfiable
        val left = spec.substring(0, dash).trim()
        val right = spec.substring(dash + 1).trim()

        if (left.isEmpty()) {
            val n = right.toLongOrNull()?.takeIf { it > 0 } ?: return RangeResult.Unsatisfiable
            val start = (total - n).coerceAtLeast(0)
            return RangeResult.Valid(start, total - 1)
        }
        val start = left.toLongOrNull() ?: return RangeResult.Unsatisfiable
        if (start < 0 || start >= total) return RangeResult.Unsatisfiable
        if (right.isEmpty()) return RangeResult.Valid(start, total - 1)
        val rawEnd = right.toLongOrNull() ?: return RangeResult.Unsatisfiable
        val end = minOf(rawEnd, total - 1)
        if (end < start) return RangeResult.Unsatisfiable
        return RangeResult.Valid(start, end)
    }

    /** Начало диапазона для pass-through; 0 — если диапазона нет/он не распознан. */
    fun rangeStart(header: String?): Long {
        if (header == null) return 0
        val spec = header.trim().removePrefix("bytes=")
        val dash = spec.indexOf('-')
        if (dash <= 0) return 0
        return spec.substring(0, dash).trim().toLongOrNull() ?: 0
    }

    fun isAllowedOrigin(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
        if (host.isBlank()) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return false
        return true
    }

    fun contentType(extension: String): String = when (extension.lowercase()) {
        "m3u8", "m3u" -> "application/vnd.apple.mpegurl"
        "ts" -> "video/mp2t"
        "m4s" -> "video/iso.segment"
        "mp4", "m4v" -> "video/mp4"
        "aac" -> "audio/aac"
        "vtt" -> "text/vtt"
        "key" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    fun safeName(rawUrl: String, kind: ResourceKind): String {
        val path = runCatching { URI(rawUrl).path }.getOrNull() ?: rawUrl
        val name = path.substringAfterLast('/', "")
        var ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
        if (kind == ResourceKind.PLAYLIST) ext = "m3u8"
        if (ext.isEmpty()) ext = kind.fallbackExtension
        ext = ext.replace(Regex("[^a-z0-9]"), "").ifEmpty { kind.fallbackExtension }
        var base = if (name.contains('.')) name.substringBeforeLast('.') else name
        base = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (base.isEmpty() || base.length > 48) base = "r"
        return "$base.$ext"
    }

    fun resourceKind(extension: String, contentType: String?): ResourceKind {
        val e = extension.lowercase()
        if (e == "m3u8" || e == "m3u") return ResourceKind.PLAYLIST
        if (e == "key") return ResourceKind.KEY
        val ct = contentType?.lowercase() ?: ""
        if (ct.contains("mpegurl")) return ResourceKind.PLAYLIST
        return ResourceKind.SEGMENT
    }
}

interface HttpResponseBody {
    fun write(out: OutputStream, offset: Long, count: Long, deadlineMs: Long)
}

class BytesBody(private val data: ByteArray) : HttpResponseBody {
    override fun write(out: OutputStream, offset: Long, count: Long, deadlineMs: Long) {
        val start = offset.coerceAtLeast(0).toInt()
        if (start >= data.size) return
        val end = if (count < 0) data.size else minOf(data.size.toLong(), start + count).toInt()
        if (end <= start) return
        out.write(data, start, end - start)
        out.flush()
    }
}

class FileBody(private val file: File) : HttpResponseBody {
    override fun write(out: OutputStream, offset: Long, count: Long, deadlineMs: Long) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset.coerceAtLeast(0))
            var remaining = count
            val buf = ByteArray(CHUNK)
            while (remaining != 0L) {
                val want = if (remaining < 0) CHUNK else minOf(remaining, CHUNK.toLong()).toInt()
                val n = raf.read(buf, 0, want)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (remaining > 0) remaining -= n
            }
            out.flush()
            if (remaining > 0) throw IOException("body shorter than promised")
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}

/** Отдача сегмента, который ещё качается: читает файл по мере роста, ждёт на feed. */
class GrowingFileBody(private val file: File, private val feed: ByteFeed) : HttpResponseBody {
    override fun write(out: OutputStream, offset: Long, count: Long, deadlineMs: Long) {
        RandomAccessFile(file, "r").use { raf ->
            var position = offset.coerceAtLeast(0)
            var remaining = count
            val buf = ByteArray(CHUNK)
            while (remaining != 0L) {
                val left = deadlineMs - nowMs()
                if (left <= 0) throw IOException("deadline")
                val available = feed.waitFor(position, left)
                if (available <= position) {
                    val err = feed.error
                    if (err != null) throw IOException(err.wire)
                    if (feed.isFinished) break
                    continue
                }
                val want = if (remaining < 0) (available - position) else minOf(available - position, remaining)
                raf.seek(position)
                val n = raf.read(buf, 0, minOf(want, CHUNK.toLong()).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                position += n
                if (remaining > 0) remaining -= n
            }
            out.flush()
            if (remaining > 0) throw IOException("stream interrupted")
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}

/** Pass-through: отдаём по мере прихода из памяти (без диска). */
class StreamBody(private val feed: ByteFeed) : HttpResponseBody {
    override fun write(out: OutputStream, offset: Long, count: Long, deadlineMs: Long) {
        while (true) {
            val left = deadlineMs - nowMs()
            if (left <= 0) throw IOException("deadline")
            val chunk = feed.readChunk(64 * 1024, left) ?: return
            if (chunk.isEmpty()) continue
            out.write(chunk)
            out.flush()
        }
    }
}

data class HttpReply(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: HttpResponseBody? = null,
    val length: Long = 0,
    val cacheState: String? = null,
    val rangeHandled: Boolean = false,
) {
    companion object {
        fun text(status: Int, text: String): HttpReply {
            val data = text.toByteArray(Charsets.UTF_8)
            return HttpReply(
                status = status,
                headers = mapOf("Content-Type" to "text/plain; charset=utf-8"),
                body = BytesBody(data),
                length = data.size.toLong(),
            )
        }
    }
}

object B64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val reverse = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encode(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8) or
                (bytes[i + 2].toInt() and 0xff)
            sb.append(ALPHABET[(n ushr 18) and 0x3f])
            sb.append(ALPHABET[(n ushr 12) and 0x3f])
            sb.append(ALPHABET[(n ushr 6) and 0x3f])
            sb.append(ALPHABET[n and 0x3f])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xff) shl 16
                sb.append(ALPHABET[(n ushr 18) and 0x3f]).append(ALPHABET[(n ushr 12) and 0x3f])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
                sb.append(ALPHABET[(n ushr 18) and 0x3f]).append(ALPHABET[(n ushr 12) and 0x3f])
                    .append(ALPHABET[(n ushr 6) and 0x3f])
            }
        }
        return sb.toString()
    }

    fun decode(text: String): String? {
        val cleaned = text.filter { it != '=' }
        if (cleaned.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in cleaned) {
            val v = if (c.code < 128) reverse[c.code] else -1
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xff)
            }
        }
        return runCatching { String(out.toByteArray(), Charsets.UTF_8) }.getOrNull()
    }
}

object Fnv {
    fun hash(text: String): ULong {
        var h = 0xcbf29ce484222325uL
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = (h xor (b.toInt() and 0xff).toULong()) * 0x100000001b3uL
        }
        return h
    }

    fun hex(text: String): String = hash(text).toString(16).padStart(16, '0')
}

object Fs {
    fun ensureDir(dir: File) {
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create $dir")
    }

    fun size(file: File): Long = if (file.exists()) file.length() else 0L

    fun appendLine(file: File, line: String) {
        file.appendText(line + "\n", Charsets.UTF_8)
    }

    fun readLines(file: File): List<String> =
        if (file.exists()) file.readLines(Charsets.UTF_8) else emptyList()

    fun truncate(file: File, to: Long) {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use { it.setLength(to.coerceAtLeast(0)) }
    }

    fun freeDiskBytes(dir: File): Long = runCatching { dir.usableSpace }.getOrDefault(0L)
}
