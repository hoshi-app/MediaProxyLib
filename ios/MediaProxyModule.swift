import ExpoModulesCore
import Foundation

/// Expo module, a thin shell over `ProxyEngine`. All work lives in the engine;
/// this file only defines the JS contract.
public final class MediaProxyModule: Module {
    private var engine: ProxyEngine?
    private var root: URL = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent("mediaproxy/v1", isDirectory: true)
    }()

    public func definition() -> ModuleDefinition {
        Name("MediaProxy")
        Events("onError", "onAuthRequired", "onServerState", "onCache", "onRecover")

        Function("start") { () -> [String: Any] in
            let engine = self.ensureEngine()
            let result = engine.start()
            if !result.running {
                return ["port": 0, "baseUrl": "", "generation": engine.currentGeneration,
                        "issues": ["SERVER_START_FAILED"]]
            }
            // Preflight is synchronous with a short timeout: an ATS loopback block must appear in
            // `issues` immediately, not as an async event after start.
            let issues = self.preflight(engine: engine)
            return ["port": result.port, "baseUrl": engine.baseUrl,
                    "generation": engine.currentGeneration, "issues": issues]
        }

        Function("resolve") { (origin: String, options: [String: Any]) -> [String: Any] in
            let engine = self.ensureEngine()
            var headers: [String: String] = [:]
            if let raw = options["headers"] as? [String: Any] {
                for (k, v) in raw { headers[k] = String(describing: v) }
            }
            let opts = ProxyEngine.ResolveOptions(
                cacheKey: options["cacheKey"] as? String,
                headers: headers,
                cache: (options["cache"] as? Bool) ?? true,
                prewarm: (options["prewarm"] as? Bool) ?? true)
            let result = engine.resolve(origin, options: opts)
            return ["streamId": result.streamId, "url": result.url, "cached": result.cached,
                    "reason": result.reason, "generation": result.generation]
        }

        Function("release") { (streamId: String) in
            self.engine?.release(streamId)
        }

        Function("failOpen") { (streamIdOrUrl: String) -> [String: Any]? in
            self.engine?.failOpen(streamIdOrUrl)
        }

        Function("nudge") { (streamId: String) -> [String: Any] in
            self.engine?.nudge(streamId) ?? ["queued": 0]
        }

        // Media playlist restart at the proxy level: the player's stall detector calls this instead of remounting.
        // AsyncFunction because it fetches the media playlist with a timeout, and the JS thread must not block.
        AsyncFunction("restartStream") { (streamId: String) -> [String: Any] in
            self.engine?.restart(streamId) ?? ["queued": 0, "refreshed": false, "reason": "NO_ENGINE"]
        }

        Function("setConfig") { (patch: [String: Any]) in
            self.engine?.setConfig(patch)
        }

        Function("getStats") { () -> [String: Any] in
            self.engine?.stats() ?? [:]
        }

        Function("getStreamState") { (streamId: String) -> [String: Any] in
            self.engine?.streamState(streamId) ?? ["cacheKey": "", "cacheable": false,
                                                   "segments": [[String: Any]](), "cachedAheadSec": 0]
        }

        Function("clearCache") { (streamId: String?) -> [String: Any] in
            self.engine?.clearCache(streamId) ?? ["freedBytes": 0]
        }
    }

    private func ensureEngine() -> ProxyEngine {
        if let engine { return engine }
        let engine = ProxyEngine(root: root)
        engine.onEvent = { [weak self] name, payload in
            self?.sendEvent(name, payload)
        }
        self.engine = engine
        return engine
    }

    /// HEAD to our own server to catch -1022 (ATS) before the player does.
    /// Synchronous with a short timeout: the result goes into `start().issues`.
    private func preflight(engine: ProxyEngine) -> [String] {
        guard let url = URL(string: engine.baseUrl + "/") else { return [] }
        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        request.timeoutInterval = 2
        let semaphore = DispatchSemaphore(value: 0)
        var issue: String?
        let task = URLSession.shared.dataTask(with: request) { _, response, error in
            defer { semaphore.signal() }
            if let error {
                let ns = error as NSError
                if ns.domain == NSURLErrorDomain,
                   ns.code == NSURLErrorAppTransportSecurityRequiresSecureConnection {
                    issue = "ATS_BLOCKED"
                } else if response == nil {
                    issue = "CONNECT_FAILED"
                }
            }
        }
        task.resume()
        if semaphore.wait(timeout: .now() + 2) == .timedOut {
            task.cancel()
            return []
        }
        return issue.map { [$0] } ?? []
    }
}
