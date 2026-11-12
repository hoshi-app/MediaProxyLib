package tv.mediaproxy

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import tv.mediaproxy.core.ProxyEngine

/**
 * Expo-модуль: тонкая оболочка над ProxyEngine. Вся работа — в ядре; здесь только контракт с JS.
 * Preflight проверяет cleartext для 127.0.0.1 до первого запроса плеера.
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
        Events("onError", "onAuthRequired", "onServerState", "onCache")

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
         * «Перезапуск манифеста» на уровне прокси: сторож плеера просит это вместо перемонтажа.
         * AsyncFunction: внутри сетевой перезапрос плейлиста, UI/JS-поток блокировать нельзя.
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
