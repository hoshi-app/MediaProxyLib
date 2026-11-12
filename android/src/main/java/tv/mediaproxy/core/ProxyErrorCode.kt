package tv.mediaproxy.core

/**
 * Коды ошибок: один словарь на обе платформы (см. docs/PROTOCOL.md, план приложение B).
 * `httpStatus` — что отдаём плееру; 4xx origin наружу не отдаём никогда (Media3 убивает item).
 */
enum class ProxyErrorCode(
    val wire: String,
    val httpStatus: Int,
    val retryable: Boolean,
    val auth: Boolean = false,
) {
    NOT_STARTED("NOT_STARTED", 503, false),
    SERVER_START_FAILED("SERVER_START_FAILED", 503, false),
    INVALID_URL("INVALID_URL", 400, false),
    SESSION_UNKNOWN("SESSION_UNKNOWN", 410, false),
    CONNECT_FAILED("CONNECT_FAILED", 502, true),
    TIMEOUT_FIRST_BYTE("TIMEOUT_FIRST_BYTE", 504, true),
    TIMEOUT_SILENCE("TIMEOUT_SILENCE", 504, true),
    SLOW_STREAM("SLOW_STREAM", 504, true),
    RANGE_UNSUPPORTED("RANGE_UNSUPPORTED", 502, false),
    ORIGIN_400("ORIGIN_400", 502, false, auth = true),
    ORIGIN_401("ORIGIN_401", 502, false, auth = true),
    ORIGIN_403("ORIGIN_403", 502, false, auth = true),
    ORIGIN_404("ORIGIN_404", 502, false),
    ORIGIN_410("ORIGIN_410", 502, false, auth = true),
    ORIGIN_429("ORIGIN_429", 503, true),
    ORIGIN_5XX("ORIGIN_5XX", 502, true),
    HTML_RESPONSE("HTML_RESPONSE", 502, false),
    EMPTY_RESPONSE("EMPTY_RESPONSE", 502, true),
    TRUNCATED("TRUNCATED", 502, true),
    BAD_CONTENT("BAD_CONTENT", 502, false),
    BUDGET_EXHAUSTED("BUDGET_EXHAUSTED", 502, false),
    DISK_FULL("DISK_FULL", 500, false),
    DISK_IO("DISK_IO", 500, false),
    CACHE_EVICTED("CACHE_EVICTED", 502, true),
    UNSUPPORTED_PLAYLIST("UNSUPPORTED_PLAYLIST", 502, false),
    NETWORK_CHANGED("NETWORK_CHANGED", 502, true),
    CLEARTEXT_BLOCKED("CLEARTEXT_BLOCKED", 500, false),
    ATS_BLOCKED("ATS_BLOCKED", 500, false);

    companion object {
        fun byWire(wire: String): ProxyErrorCode? = entries.firstOrNull { it.wire == wire }

        fun forStatus(status: Int): ProxyErrorCode = when (status) {
            400 -> ORIGIN_400
            401 -> ORIGIN_401
            403 -> ORIGIN_403
            404 -> ORIGIN_404
            410 -> ORIGIN_410
            // 416 на наш Range означает, что префикс не подходит к ресурсу: движок сбрасывает
            // сегмент и начинает его заново, а не отдаёт склейку.
            416 -> RANGE_UNSUPPORTED
            429 -> ORIGIN_429
            in 400..499 -> ORIGIN_400
            else -> ORIGIN_5XX
        }
    }
}
