import Foundation

/// Конфигурация движка. Значения по умолчанию — из плана (§6.2, §7.2): таймауты короче, чем у плеера
/// (Media3 живёт 8 с connect/read), иначе плеер отваливается первым и наши ретраи невидимы.
public struct ProxyConfig {
    public var enabled: Bool = true
    public var maxCacheBytes: Int64 = 512 * 1024 * 1024
    public var minFreeDiskBytes: Int64 = 2048 * 1024 * 1024
    public var maxSessions: Int = 4
    public var prefetchSeconds: Double = 30
    public var prefetchMaxSegments: Int = 6
    public var prefetchConcurrency: Int = 2
    public var interactiveConcurrency: Int = 4
    public var reqConnectSec: Double = 5
    public var reqFirstByteSec: Double = 5
    public var reqSilenceSec: Double = 4
    public var prefetchFirstByteSec: Double = 10
    public var prefetchSilenceSec: Double = 15
    public var prefetchRetries: Int = 3
    public var prefetchMinSpeedBps: Int64 = 1024
    public var retryBackoffMs: [Int64] = [500, 1500, 4000]
    public var cooldownSec: Double = 30
    public var prewarmSegments: Int = 2
    public var maxLoopbackConnections: Int = 32
    public var socketIdleSec: Double = 60
    public var maxPlaylistBytes: Int = 2 * 1024 * 1024
    public var maxBufferThenServeBytes: Int = 8 * 1024 * 1024
    public var serving: ServingMode = .stream
    public var debug: Bool = false

    public init() {}

    /// Применяет частичный словарь из JS (ключи — как в TS `Config`). Неизвестные ключи игнорируются.
    public mutating func apply(_ patch: [String: Any]) {
        func b(_ k: String) -> Bool? { patch[k] as? Bool }
        func i(_ k: String) -> Int? {
            if let v = patch[k] as? Int { return v }
            if let v = patch[k] as? Double { return Int(v) }
            return nil
        }
        func d(_ k: String) -> Double? {
            if let v = patch[k] as? Double { return v }
            if let v = patch[k] as? Int { return Double(v) }
            return nil
        }
        if let v = b("enabled") { enabled = v }
        if let v = i("maxCacheMB") { maxCacheBytes = Int64(max(0, v)) * 1024 * 1024 }
        if let v = i("minFreeDiskMB") { minFreeDiskBytes = Int64(max(0, v)) * 1024 * 1024 }
        if let v = i("maxSessions") { maxSessions = max(1, v) }
        if let v = d("prefetchSeconds") { prefetchSeconds = max(0, v) }
        if let v = i("prefetchMaxSegments") { prefetchMaxSegments = max(0, v) }
        if let v = i("prefetchConcurrency") { prefetchConcurrency = max(1, v) }
        if let v = i("interactiveConcurrency") { interactiveConcurrency = max(1, v) }
        if let v = d("reqConnectSec") { reqConnectSec = v }
        if let v = d("reqFirstByteSec") { reqFirstByteSec = v }
        if let v = d("reqSilenceSec") { reqSilenceSec = v }
        if let v = d("prefetchFirstByteSec") { prefetchFirstByteSec = v }
        if let v = d("prefetchSilenceSec") { prefetchSilenceSec = v }
        if let v = i("prefetchRetries") { prefetchRetries = max(0, v) }
        if let v = i("minSpeedKBps") { prefetchMinSpeedBps = Int64(max(0, v)) * 1024 }
        if let v = i("cooldownSec") { cooldownSec = max(0, Double(v)) }
        if let v = i("prewarmSegments") { prewarmSegments = max(0, v) }
        if let v = i("maxLoopbackConnections") { maxLoopbackConnections = max(8, v) }
        if let v = d("socketIdleSec") { socketIdleSec = max(1, v) }
        if let v = b("debug") { debug = v }
    }
}

public enum ServingMode: String {
    case stream   // tee: отдаём плееру по мере прихода байтов (по умолчанию)
    case complete // сначала сегмент целиком, потом ответ (безопасный fallback, S3)
}

/// Таймауты одного origin-запроса. `nil` = не применять.
public struct TimeoutProfile {
    public var connectMs: Int64
    public var firstByteMs: Int64
    public var silenceMs: Int64
    public var minSpeedBps: Int64      // 0 = выключен (для запросов плеера не применяется)
    public var speedWindowMs: Int64

    public static func interactive(_ c: ProxyConfig) -> TimeoutProfile {
        TimeoutProfile(connectMs: Int64(c.reqConnectSec * 1000),
                       firstByteMs: Int64(c.reqFirstByteSec * 1000),
                       silenceMs: Int64(c.reqSilenceSec * 1000),
                       minSpeedBps: 0,
                       speedWindowMs: 15000)
    }

    public static func prefetch(_ c: ProxyConfig) -> TimeoutProfile {
        TimeoutProfile(connectMs: Int64(c.reqConnectSec * 1000),
                       firstByteMs: Int64(c.prefetchFirstByteSec * 1000),
                       silenceMs: Int64(c.prefetchSilenceSec * 1000),
                       minSpeedBps: c.prefetchMinSpeedBps,
                       speedWindowMs: 15000)
    }

    public static func playlist(_ c: ProxyConfig) -> TimeoutProfile {
        TimeoutProfile(connectMs: Int64(c.reqConnectSec * 1000),
                       firstByteMs: 20000,
                       silenceMs: 60000,
                       minSpeedBps: 0,
                       speedWindowMs: 0)
    }
}

public enum Log {
    public static var enabled = false
    private static let lock = NSLock()
    private static let redact = try! NSRegularExpression(
        pattern: "([?&](?:sig|ms|expires|token|signature|srcIp|hash)=)[^&]+",
        options: [.caseInsensitive])

    public static func line(_ tag: String, _ message: String) {
        guard enabled else { return }
        lock.lock()
        let redacted = redact.stringByReplacingMatches(
            in: message, options: [],
            range: NSRange(message.startIndex..., in: message),
            withTemplate: "$1…")
        NSLog("[mediaproxy] \(tag): \(redacted)")
        lock.unlock()
    }
}

public enum Ids {
    /// Стабильный 64-битный хеш (FNV-1a) для имён каталогов: одинаковый в Swift и Kotlin.
    public static func fnv1a(_ text: String) -> UInt64 {
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in text.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x100000001b3
        }
        return hash
    }

    public static func cartesianKey(_ cacheKey: String) -> String {
        String(format: "%016llx", fnv1a(cacheKey))
    }

    /// 128-битный идентификатор сессии: hex, 32 символа.
    public static func sessionId() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        for i in 0..<16 { bytes[i] = UInt8.random(in: 0...255) }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}

/// `b64url` без паддинга — компактный и однозначный способ положить origin-URL в путь.
public enum B64Url {
    public static func encode(_ text: String) -> String {
        Data(text.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func decode(_ text: String) -> String? {
        var s = text
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        guard let data = Data(base64Encoded: s) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
