package tv.mediaproxy.core

/** Engine configuration. Timeouts are shorter than the player's, which uses 8 s in Media3. */
data class ProxyConfig(
    val enabled: Boolean = true,
    val maxCacheBytes: Long = 512L * 1024 * 1024,
    val minFreeDiskBytes: Long = 2048L * 1024 * 1024,
    val maxSessions: Int = 4,
    val prefetchSeconds: Double = 30.0,
    val prefetchMaxSegments: Int = 6,
    val prefetchConcurrency: Int = 2,
    val interactiveConcurrency: Int = 4,
    val reqConnectSec: Double = 5.0,
    val reqFirstByteSec: Double = 5.0,
    val reqSilenceSec: Double = 4.0,
    val prefetchFirstByteSec: Double = 10.0,
    val prefetchSilenceSec: Double = 15.0,
    val prefetchRetries: Int = 3,
    val prefetchMinSpeedBps: Long = 1024,
    val retryBackoffMs: List<Long> = listOf(500, 1500, 4000),
    val cooldownSec: Double = 30.0,
    val prewarmSegments: Int = 2,
    /**
     * Stall detector: seconds without byte progress before the media playlist is restarted.
     * 0 disables the detector.
     */
    val stallKickSec: Double = 6.0,
    /** Retries allowed per segment before the engine gives up and leaves the decision to the player. */
    val recoveryMax: Int = 2,
    val maxLoopbackConnections: Int = 32,
    val socketIdleSec: Double = 60.0,
    val maxPlaylistBytes: Int = 2 * 1024 * 1024,
    val maxBufferThenServeBytes: Int = 8 * 1024 * 1024,
    val serving: ServingMode = ServingMode.STREAM,
    val debug: Boolean = false,
    /** Tests and local origins only: allow loopback addresses as an origin. */
    val allowLoopbackOrigins: Boolean = false,
) {
    fun withPatch(patch: Map<String, Any?>): ProxyConfig {
        fun b(k: String): Boolean? = patch[k] as? Boolean
        fun i(k: String): Int? = (patch[k] as? Number)?.toInt()
        fun d(k: String): Double? = (patch[k] as? Number)?.toDouble()
        var c = this
        b("enabled")?.let { c = c.copy(enabled = it) }
        i("maxCacheMB")?.let { c = c.copy(maxCacheBytes = it.coerceAtLeast(0).toLong() * 1024 * 1024) }
        i("minFreeDiskMB")?.let { c = c.copy(minFreeDiskBytes = it.coerceAtLeast(0).toLong() * 1024 * 1024) }
        i("maxSessions")?.let { c = c.copy(maxSessions = it.coerceAtLeast(1)) }
        d("prefetchSeconds")?.let { c = c.copy(prefetchSeconds = it.coerceAtLeast(0.0)) }
        i("prefetchMaxSegments")?.let { c = c.copy(prefetchMaxSegments = it.coerceAtLeast(0)) }
        i("prefetchConcurrency")?.let { c = c.copy(prefetchConcurrency = it.coerceAtLeast(1)) }
        i("interactiveConcurrency")?.let { c = c.copy(interactiveConcurrency = it.coerceAtLeast(1)) }
        d("reqConnectSec")?.let { c = c.copy(reqConnectSec = it) }
        d("reqFirstByteSec")?.let { c = c.copy(reqFirstByteSec = it) }
        d("reqSilenceSec")?.let { c = c.copy(reqSilenceSec = it) }
        d("prefetchFirstByteSec")?.let { c = c.copy(prefetchFirstByteSec = it) }
        d("prefetchSilenceSec")?.let { c = c.copy(prefetchSilenceSec = it) }
        i("prefetchRetries")?.let { c = c.copy(prefetchRetries = it.coerceAtLeast(0)) }
        i("minSpeedKBps")?.let { c = c.copy(prefetchMinSpeedBps = it.coerceAtLeast(0).toLong() * 1024) }
        i("cooldownSec")?.let { c = c.copy(cooldownSec = it.coerceAtLeast(0).toDouble()) }
        d("stallKickSec")?.let { c = c.copy(stallKickSec = it.coerceAtLeast(0.0)) }
        i("recoveryMax")?.let { c = c.copy(recoveryMax = it.coerceAtLeast(0)) }
        i("prewarmSegments")?.let { c = c.copy(prewarmSegments = it.coerceAtLeast(0)) }
        i("maxLoopbackConnections")?.let { c = c.copy(maxLoopbackConnections = it.coerceAtLeast(8)) }
        d("socketIdleSec")?.let { c = c.copy(socketIdleSec = it.coerceAtLeast(1.0)) }
        b("debug")?.let { c = c.copy(debug = it) }
        b("allowLoopbackOrigins")?.let { c = c.copy(allowLoopbackOrigins = it) }
        (patch["serving"] as? String)?.let { raw ->
            ServingMode.entries.firstOrNull { it.wire == raw }?.let { c = c.copy(serving = it) }
        }
        (patch["retryBackoffMs"] as? List<*>)?.let { list ->
            val values = list.mapNotNull { (it as? Number)?.toLong() }
            if (values.isNotEmpty()) c = c.copy(retryBackoffMs = values)
        }
        return c
    }
}

enum class ServingMode(val wire: String) {
    STREAM("stream"),
    COMPLETE("complete"),
}

/** Timeouts for one origin request. `minSpeedBps = 0` disables the minimum-speed check. */
data class TimeoutProfile(
    val connectMs: Long,
    val firstByteMs: Long,
    val silenceMs: Long,
    val minSpeedBps: Long,
    val speedWindowMs: Long,
) {
    companion object {
        fun interactive(c: ProxyConfig) = TimeoutProfile(
            connectMs = (c.reqConnectSec * 1000).toLong(),
            firstByteMs = (c.reqFirstByteSec * 1000).toLong(),
            silenceMs = (c.reqSilenceSec * 1000).toLong(),
            minSpeedBps = 0,
            speedWindowMs = 15_000,
        )

        fun prefetch(c: ProxyConfig) = TimeoutProfile(
            connectMs = (c.reqConnectSec * 1000).toLong(),
            firstByteMs = (c.prefetchFirstByteSec * 1000).toLong(),
            silenceMs = (c.prefetchSilenceSec * 1000).toLong(),
            minSpeedBps = c.prefetchMinSpeedBps,
            speedWindowMs = 15_000,
        )

        fun playlist(c: ProxyConfig) = TimeoutProfile(
            connectMs = (c.reqConnectSec * 1000).toLong(),
            firstByteMs = 20_000,
            silenceMs = 60_000,
            minSpeedBps = 0,
            speedWindowMs = 0,
        )

        /**
         * Re-reads the media playlist to retry a segment, so it stays short and strict. The normal
         * playlist profile (20 s) would hold an interactive download slot for up to a minute, exactly
         * when a slot is scarce.
         */
        fun refresh(c: ProxyConfig) = TimeoutProfile(
            connectMs = (c.reqConnectSec * 1000).toLong(),
            firstByteMs = 8_000,
            silenceMs = 15_000,
            minSpeedBps = 0,
            speedWindowMs = 0,
        )
    }
}

object Log {
    @Volatile
    var enabled: Boolean = false

    private val redact = Regex("([?&](?:sig|ms|expires|token|signature|srcIp|hash)=)[^&]+", RegexOption.IGNORE_CASE)

    fun line(tag: String, message: String) {
        if (!enabled) return
        val safe = redact.replace(message, "$1…")
        println("[mediaproxy] $tag: $safe")
    }
}

object Ids {
    fun sessionId(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

fun nowMs(): Long = System.currentTimeMillis()
