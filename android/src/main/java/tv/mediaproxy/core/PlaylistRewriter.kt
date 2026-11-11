package tv.mediaproxy.core

import java.net.URI

data class PlaylistSegment(
    val index: Int,
    val url: String,
    val duration: Double,
    val startSec: Double,
)

data class PlaylistVariant(
    val url: String,
    val bandwidth: Int,
    val width: Int,
    val height: Int,
    val codecs: String?,
)

data class MediaInfo(
    val segments: MutableList<PlaylistSegment> = mutableListOf(),
    var initUrl: String? = null,
    val keyUrls: MutableList<String> = mutableListOf(),
    var targetDuration: Double = 0.0,
    var endList: Boolean = false,
    var byteRange: Boolean = false,
    var llhls: Boolean = false,
    var drm: Boolean = false,
    var totalDuration: Double = 0.0,
)

enum class PlaylistKind { MASTER, MEDIA, OTHER }

data class RewriteResult(
    val text: String,
    val kind: PlaylistKind,
    val media: MediaInfo,
    val variants: List<PlaylistVariant>,
    val fingerprint: String,
    val cacheable: Boolean,
    val reason: String,
)

/**
 * Переписывает HLS-плейлист на loopback-URL. Чистая функция: никакого I/O — проверяется фикстурами.
 * Покрывает URI-несущие теги, относительные и абсолютные ссылки, наследование query базы (kodik/CVH).
 */
object PlaylistRewriter {
    private val uriAttr = Regex("URI=\"([^\"]*)\"")
    private val resolution = Regex("RESOLUTION=(\\d+)x(\\d+)")
    private val bandwidth = Regex("BANDWIDTH=(\\d+)")
    private val codecs = Regex("CODECS=\"([^\"]*)\"")
    private val method = Regex("METHOD=([^,\"]*)")
    private val keyFormat = Regex("KEYFORMAT=\"([^\"]*)\"")

    fun looksLikePlaylist(prefix: ByteArray): Boolean {
        var start = 0
        if (prefix.size >= 3 && prefix[0] == 0xEF.toByte() && prefix[1] == 0xBB.toByte() && prefix[2] == 0xBF.toByte()) {
            start = 3
        }
        while (start < prefix.size && prefix[start].toInt().toChar().isWhitespace()) start++
        val head = String(prefix, start, minOf(prefix.size - start, 16), Charsets.UTF_8)
        return head.startsWith("#EXTM3U")
    }

    fun isPlaylistCandidate(url: String, contentType: String?): Boolean {
        if (contentType?.lowercase()?.contains("mpegurl") == true) return true
        val path = runCatching { URI(url).path }.getOrNull()?.lowercase() ?: url.lowercase()
        return path.contains(".m3u8") || path.contains(".m3u")
    }

    fun rewrite(text: String, finalUrl: String, proxy: (String, ResourceKind) -> String): RewriteResult {
        val media = MediaInfo()
        val variants = mutableListOf<PlaylistVariant>()
        val out = StringBuilder(text.length + 256)
        var isMaster = false
        var pendingDuration: Double? = null
        var pendingBandwidth = 0
        var pendingWidth = 0
        var pendingHeight = 0
        var pendingCodecs: String? = null
        var segmentIndex = 0
        var elapsed = 0.0
        val lines = text.split("\n")

        lines.forEachIndexed { lineIndex, raw ->
            val hasCR = raw.endsWith("\r")
            val line = if (hasCR) raw.dropLast(1) else raw
            if (lineIndex > 0) out.append('\n')

            if (line.isEmpty()) {
                if (hasCR) out.append('\r')
                return@forEachIndexed
            }
            if (line.startsWith("#")) {
                val upper = line.uppercase()
                when {
                    upper.startsWith("#EXT-X-STREAM-INF") -> {
                        isMaster = true
                        pendingBandwidth = bandwidth.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        resolution.find(line)?.let { m ->
                            pendingWidth = m.groupValues[1].toIntOrNull() ?: 0
                            pendingHeight = m.groupValues[2].toIntOrNull() ?: 0
                        }
                        pendingCodecs = codecs.find(line)?.groupValues?.get(1)
                    }
                    upper.startsWith("#EXT-X-TARGETDURATION") -> {
                        media.targetDuration = line.substringAfter(':', "").trim().toDoubleOrNull() ?: 0.0
                    }
                    upper.startsWith("#EXT-X-ENDLIST") -> media.endList = true
                    upper.startsWith("#EXT-X-BYTERANGE") -> media.byteRange = true
                    upper.startsWith("#EXT-X-PART") || upper.startsWith("#EXT-X-PRELOAD-HINT") -> media.llhls = true
                    upper.startsWith("#EXTINF") -> {
                        pendingDuration = line.substringAfter(':', "").substringBefore(',').trim().toDoubleOrNull()
                    }
                    upper.startsWith("#EXT-X-KEY") || upper.startsWith("#EXT-X-SESSION-KEY") -> {
                        val m = method.find(line)?.groupValues?.get(1)?.uppercase() ?: ""
                        val format = keyFormat.find(line)?.groupValues?.get(1)
                        if (m.contains("SAMPLE-AES") || (format != null && format != "identity")) media.drm = true
                    }
                }

                val kind = uriTagKind(upper)
                if (kind == null) {
                    out.append(line)
                } else {
                    out.append(
                        rewriteUriAttribute(line) { raw ->
                            val absolute = UrlResolver.resolve(finalUrl, raw)
                            // skd://, data: и прочие не-HTTP схемы не проксируем — их обрабатывает плеер.
                            if (!absolute.startsWith("http://") && !absolute.startsWith("https://")) {
                                null
                            } else {
                                when {
                                    kind == ResourceKind.PLAYLIST -> proxy(absolute, ResourceKind.PLAYLIST)
                                    kind == ResourceKind.KEY -> {
                                        media.keyUrls.add(absolute)
                                        proxy(absolute, ResourceKind.KEY)
                                    }
                                    upper.startsWith("#EXT-X-MAP") -> {
                                        media.initUrl = absolute
                                        proxy(absolute, ResourceKind.SEGMENT)
                                    }
                                    else -> proxy(absolute, ResourceKind.SEGMENT)
                                }
                            }
                        },
                    )
                }
                if (hasCR) out.append('\r')
                return@forEachIndexed
            }

            // Строка-URI: вариант master'а или сегмент media-плейлиста.
            val absolute = UrlResolver.resolve(finalUrl, line)
            if (isMaster) {
                variants.add(
                    PlaylistVariant(absolute, pendingBandwidth, pendingWidth, pendingHeight, pendingCodecs)
                )
                pendingBandwidth = 0
                pendingWidth = 0
                pendingHeight = 0
                pendingCodecs = null
                out.append(proxy(absolute, ResourceKind.PLAYLIST))
            } else {
                val duration = pendingDuration ?: 0.0
                media.segments.add(PlaylistSegment(segmentIndex, absolute, duration, elapsed))
                elapsed += duration
                segmentIndex++
                pendingDuration = null
                out.append(proxy(absolute, ResourceKind.SEGMENT))
            }
            if (hasCR) out.append('\r')
        }

        media.totalDuration = elapsed
        val kind = when {
            isMaster -> PlaylistKind.MASTER
            media.segments.isEmpty() -> PlaylistKind.OTHER
            else -> PlaylistKind.MEDIA
        }
        val cacheable = kind == PlaylistKind.MEDIA && media.endList && !media.byteRange && !media.llhls && !media.drm
        val reason = when {
            kind == PlaylistKind.OTHER -> "empty playlist"
            !media.endList -> "live playlist"
            media.byteRange -> "byte-range playlist"
            media.llhls -> "ll-hls playlist"
            media.drm -> "drm playlist"
            else -> "ok"
        }
        return RewriteResult(
            text = out.toString(),
            kind = kind,
            media = media,
            variants = variants,
            fingerprint = fingerprint(media, variants),
            cacheable = cacheable,
            reason = reason,
        )
    }

    private fun uriTagKind(upper: String): ResourceKind? = when {
        upper.startsWith("#EXT-X-MEDIA:") || upper.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> ResourceKind.PLAYLIST
        upper.startsWith("#EXT-X-KEY") || upper.startsWith("#EXT-X-SESSION-KEY") -> ResourceKind.KEY
        upper.startsWith("#EXT-X-MAP") -> ResourceKind.SEGMENT
        upper.startsWith("#EXT-X-PART") || upper.startsWith("#EXT-X-PRELOAD-HINT") -> ResourceKind.SEGMENT
        else -> null
    }

    private fun rewriteUriAttribute(line: String, resolve: (String) -> String?): String {
        val match = uriAttr.find(line) ?: return line
        val raw = match.groupValues[1]
        if (raw.isEmpty()) return line
        val replacement = resolve(raw) ?: return line
        return line.replaceRange(match.range, "URI=\"$replacement\"")
    }

    fun fingerprint(media: MediaInfo, variants: List<PlaylistVariant>): String {
        val parts = mutableListOf("n=${media.segments.size}", "td=${media.targetDuration}")
        media.segments.firstOrNull()?.let { parts.add("f=${pathOnly(it.url)}") }
        media.segments.lastOrNull()?.let { parts.add("l=${pathOnly(it.url)}") }
        parts.add("d=${media.totalDuration.toInt()}")
        parts.add("v=${variants.size}")
        media.initUrl?.let { parts.add("map=${pathOnly(it)}") }
        return Fnv.hex(parts.joinToString("|"))
    }

    private fun pathOnly(url: String): String = runCatching { URI(url).path }.getOrNull() ?: url
}

/**
 * Резолв относительных URI. Отличие от RFC 3986 сознательное: ссылка без query наследует query базы —
 * иначе kodik/CVH теряют `sig` (совпадает с `src/lib/urlResolve.ts` в AnimeApp).
 */
object UrlResolver {
    fun resolve(base: String, reference: String): String {
        val ref = reference.trim()
        if (ref.isEmpty()) return base
        val baseUri = runCatching { URI(base) }.getOrNull() ?: return ref
        if (runCatching { URI(ref).isAbsolute }.getOrDefault(false)) return ref
        val resolved = runCatching { baseUri.resolve(ref).toString() }.getOrNull() ?: return ref
        // Схемо-относительная ссылка — самостоятельный URL: query базы не наследуем.
        if (ref.startsWith("//")) return resolved
        val refHasQuery = ref.contains('?')
        val baseQuery = baseUri.rawQuery
        return if (!refHasQuery && !baseQuery.isNullOrEmpty() && !resolved.contains('?')) {
            "$resolved?$baseQuery"
        } else {
            resolved
        }
    }
}
