import Foundation

public enum ResourceKind {
    case playlist
    case segment
    case key

    /// The loopback URL needs an extension: AVURLAsset picks the demuxer from the extension or UTI.
    public var fallbackExtension: String {
        switch self {
        case .playlist: return "m3u8"
        case .segment: return "ts"
        case .key: return "key"
        }
    }
}

public enum ProxyErrorCode: String {
    case notStarted = "NOT_STARTED"
    case serverStartFailed = "SERVER_START_FAILED"
    case invalidUrl = "INVALID_URL"
    case sessionUnknown = "SESSION_UNKNOWN"
    case connectFailed = "CONNECT_FAILED"
    case timeoutFirstByte = "TIMEOUT_FIRST_BYTE"
    case timeoutSilence = "TIMEOUT_SILENCE"
    case slowStream = "SLOW_STREAM"
    case origin400 = "ORIGIN_400"
    case origin401 = "ORIGIN_401"
    case origin403 = "ORIGIN_403"
    case origin404 = "ORIGIN_404"
    case origin410 = "ORIGIN_410"
    case origin429 = "ORIGIN_429"
    case origin5xx = "ORIGIN_5XX"
    case htmlResponse = "HTML_RESPONSE"
    case emptyResponse = "EMPTY_RESPONSE"
    case truncated = "TRUNCATED"
    case badContent = "BAD_CONTENT"
    case rangeUnsupported = "RANGE_UNSUPPORTED"
    case budgetExhausted = "BUDGET_EXHAUSTED"
    case diskFull = "DISK_FULL"
    case diskIO = "DISK_IO"
    case cacheEvicted = "CACHE_EVICTED"
    case unsupportedPlaylist = "UNSUPPORTED_PLAYLIST"
    case networkChanged = "NETWORK_CHANGED"
    case cleartextBlocked = "CLEARTEXT_BLOCKED"
    case atsBlocked = "ATS_BLOCKED"

    public var isAuth: Bool {
        switch self {
        case .origin400, .origin401, .origin403, .origin410: return true
        default: return false
        }
    }

    public var isRetryable: Bool {
        switch self {
        case .connectFailed, .timeoutFirstByte, .timeoutSilence, .slowStream,
             .origin429, .origin5xx, .truncated, .emptyResponse, .networkChanged:
            return true
        default:
            return false
        }
    }

    public var httpStatus: Int {
        switch self {
        case .timeoutFirstByte, .timeoutSilence, .slowStream: return 504
        case .origin429: return 503
        case .invalidUrl: return 400
        case .sessionUnknown: return 410
        case .diskFull, .diskIO: return 500
        default: return 502
        }
    }
}

public enum Http {
    public static let statusText: [Int: String] = [
        200: "OK", 206: "Partial Content", 400: "Bad Request", 403: "Forbidden",
        404: "Not Found", 410: "Gone", 416: "Range Not Satisfiable", 429: "Too Many Requests",
        500: "Internal Server Error", 502: "Bad Gateway", 503: "Service Unavailable",
        504: "Gateway Timeout",
    ]

    /// Three-way `Range` result. Multiple or unparsable ranges get a full 200,
    /// unlike a range beyond the resource, which gets 416.
    public enum RangeResult {
        case none
        case valid(start: Int64, end: Int64)
        case unsatisfiable
    }

    public static func parseRange(_ header: String?, total: Int64) -> RangeResult {
        guard let header, total > 0 else { return .none }
        let value = header.trimmingCharacters(in: .whitespaces)
        guard value.lowercased().hasPrefix("bytes=") else { return .none }
        let spec = value.dropFirst("bytes=".count)
        if spec.contains(",") { return .none }
        let parts = spec.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        guard parts.count == 2 else { return .unsatisfiable }
        let left = parts[0].trimmingCharacters(in: .whitespaces)
        let right = parts[1].trimmingCharacters(in: .whitespaces)

        if left.isEmpty {
            // Suffix form: bytes=-N
            guard let n = Int64(right), n > 0 else { return .unsatisfiable }
            let start = max(0, total - n)
            return .valid(start: start, end: total - 1)
        }
        guard let start = Int64(left), start >= 0, start < total else { return .unsatisfiable }
        if right.isEmpty { return .valid(start: start, end: total - 1) }
        guard let rawEnd = Int64(right) else { return .unsatisfiable }
        let end = min(rawEnd, total - 1)
        if end < start { return .unsatisfiable }
        return .valid(start: start, end: end)
    }

    /// Range start for pass-through. Returns 0 when there is no usable range, including the suffix form.
    public static func rangeStart(_ header: String?) -> Int64 {
        guard let header else { return 0 }
        let spec = header.trimmingCharacters(in: .whitespaces)
        guard spec.lowercased().hasPrefix("bytes=") else { return 0 }
        let body = spec.dropFirst("bytes=".count)
        guard let dash = body.firstIndex(of: "-") else { return 0 }
        let left = body[body.startIndex..<dash].trimmingCharacters(in: .whitespaces)
        guard !left.isEmpty else { return 0 }
        return Int64(left) ?? 0
    }

    /// Whether an origin URL is allowed. Only http(s), and loopback is rejected so the proxy cannot call itself.
    public static func isAllowedOrigin(_ raw: String) -> Bool {
        guard let url = URL(string: raw), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = url.host, !host.isEmpty else { return false }
        let lowered = host.lowercased()
        if lowered == "localhost" || lowered == "127.0.0.1" || lowered == "::1" { return false }
        return true
    }

    public static func contentType(forExtension ext: String) -> String {
        switch ext.lowercased() {
        case "m3u8", "m3u": return "application/vnd.apple.mpegurl"
        case "ts": return "video/mp2t"
        case "m4s": return "video/iso.segment"
        case "mp4", "m4v": return "video/mp4"
        case "aac": return "audio/aac"
        case "vtt": return "text/vtt"
        case "key": return "application/octet-stream"
        default: return "application/octet-stream"
        }
    }

    public static func safeName(for url: URL, kind: ResourceKind) -> String {
        var ext = url.pathExtension.lowercased()
        if kind == .playlist { ext = "m3u8" }
        if ext.isEmpty { ext = kind.fallbackExtension }
        var base = url.deletingPathExtension().lastPathComponent
        base = base.replacingOccurrences(of: "[^A-Za-z0-9._-]", with: "_", options: .regularExpression)
        if base.isEmpty || base.count > 48 { base = "r" }
        return "\(base).\(ext)"
    }

    public static func resourceKind(extension ext: String, contentType: String?, isInit: Bool = false) -> ResourceKind {
        let e = ext.lowercased()
        if e == "m3u8" || e == "m3u" { return .playlist }
        if e == "key" { return .key }
        let ct = contentType?.lowercased() ?? ""
        if ct.contains("mpegurl") { return .playlist }
        if ct.contains("octet-stream") && isInit { return .segment }
        return .segment
    }
}

/// Thin file wrapper with consistent UTF-8 append and truncate behavior on both platforms.
public enum Fs {
    public static func ensureDir(_ url: URL) throws {
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    public static func size(_ url: URL) -> Int64 {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
    }

    public static func removeIfExists(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
    }

    public static func appendLine(_ url: URL, _ line: String) throws {
        if !FileManager.default.fileExists(atPath: url.path) {
            try Data().write(to: url)
        }
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: Data((line + "\n").utf8))
    }

    public static func readLines(_ url: URL) -> [String] {
        guard let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .utf8) else { return [] }
        return text.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
    }

    public static func truncate(_ url: URL, to size: Int64) {
        guard FileManager.default.fileExists(atPath: url.path), Fs.size(url) > size else { return }
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        defer { try? handle.close() }
        try? handle.truncate(atOffset: UInt64(max(0, size)))
    }

    public static func freeDiskBytes(at url: URL) -> Int64 {
        let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? 0
    }
}
