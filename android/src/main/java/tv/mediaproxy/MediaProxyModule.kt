package tv.mediaproxy

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import tv.mediaproxy.core.ProxyEngine

/**
 * Expo module: a thin shell over ProxyEngine. All work happens in the core; this is only the JS
 * contract. The preflight check verifies cleartext for 127.0.0.1 before the player's first request.
 */
class MediaProxyModule : Module() {
    private var engine: ProxyEngine? = null

    private val rootDir: File
        get() {
            val context = appContext.reactContext?.applicationContext
            val cacheDir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            return File(cacheDir, "mediaproxy/v1")
        }

    override fun definition() = ModuleDefinition {
        Name("MediaProxy")
        Events("onError", "onAuthRequired", "onServerState", "onCache", "onRecover")

        Function("start") {
            val engine = ensureEngine()
            val issues = mutableListOf<String>()
            if (!isCleartextAllowed()) issues.add("CLEARTEXT_BLOCKED")
            val result = engine.start(issues)
            mapOf(
                "port" to result.port,
                "baseUrl" to engine.baseUrl,
                "generation" to engine.generation,
                "issues" to result.issues,
            )
        }

        Function("resolve") { origin: String, options: Map<String, Any?> ->
            val engine = ensureEngine()
            val headers = mutableMapOf<String, String>()
            (options["headers"] as? Map<*, *>)?.forEach { (key, value) ->
                if (key is String && value != null) headers[key] = value.toString()
            }
            val resolved = engine.resolve(
                origin,
                ProxyEngine.ResolveOptions(
                    cacheKey = options["cacheKey"] as? String,
                    headers = headers,
                    cache = (options["cache"] as? Boolean) ?: true,
                    prewarm = (options["prewarm"] as? Boolean) ?: true,
                ),
            )
            mapOf(
                "streamId" to resolved.streamId,
                "url" to resolved.url,
                "cached" to resolved.cached,
                "reason" to resolved.reason,
                "generation" to resolved.generation,
            )
        }

        Function("release") { streamId: String ->
            engine?.release(streamId)
        }

        Function("failOpen") { streamIdOrUrl: String ->
            engine?.failOpen(streamIdOrUrl)
        }

        Function("nudge") { streamId: String ->
            engine?.nudge(streamId) ?: mapOf("queued" to 0)
        }

        /**
         * Media playlist refresh at the proxy level, requested by the player's stall detector instead
         * of a remount. AsyncFunction because it re-fetches the playlist and must not block JS.
         */
        AsyncFunction("restartStream") { streamId: String ->
            engine?.restart(streamId) ?: mapOf("queued" to 0, "refreshed" to false, "reason" to "NO_ENGINE")
        }

        Function("setConfig") { patch: Map<String, Any?> ->
            engine?.setConfig(patch)
        }

        Function("getStats") { engine?.stats() ?: emptyMap<String, Any?>() }

        Function("getStreamState") { streamId: String ->
            engine?.streamState(streamId)
                ?: mapOf("cacheKey" to "", "cacheable" to false, "segments" to emptyList<Any?>(), "cachedAheadSec" to 0.0)
        }

        Function("clearCache") { streamId: String? ->
            engine?.clearCache(streamId) ?: mapOf("freedBytes" to 0L)
        }
    }

    private fun ensureEngine(): ProxyEngine {
        val existing = engine
        if (existing != null) return existing
        val created = ProxyEngine(rootDir)
        created.onEvent = { name, payload -> sendEvent(name, payload) }
        engine = created
        return created
    }

    private fun isCleartextAllowed(): Boolean = runCatching {
        android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1")
    }.getOrDefault(false)
}
