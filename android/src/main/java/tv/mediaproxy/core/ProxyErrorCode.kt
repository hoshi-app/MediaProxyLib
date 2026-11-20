package tv.mediaproxy.core

/**
 * Error codes shared by both platforms; see docs/PROTOCOL.md.
 * `httpStatus` is what the player receives. Origin 4xx is never passed through: Media3 kills the item.
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
            // A 416 for our Range means the prefix does not match the resource. The engine restarts
            // the segment instead of serving a splice.
            416 -> RANGE_UNSUPPORTED
            429 -> ORIGIN_429
            in 400..499 -> ORIGIN_400
            else -> ORIGIN_5XX
        }
    }
}
