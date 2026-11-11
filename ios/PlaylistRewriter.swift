import Foundation

public struct PlaylistSegment {
    public let index: Int
    public let url: String
    public let duration: Double
    public let startSec: Double
}

public struct PlaylistVariant {
    public let url: String
    public let bandwidth: Int
    public let width: Int
    public let height: Int
    public let codecs: String?
}

public struct MediaInfo {
    public var segments: [PlaylistSegment] = []
    public var initUrl: String? = nil
    public var keyUrls: [String] = []
    public var targetDuration: Double = 0
    public var endList: Bool = false
    public var byteRange: Bool = false
    public var llhls: Bool = false
    public var drm: Bool = false
    public var totalDuration: Double = 0
}

public struct RewriteResult {
    public enum Kind: String { case master, media, other }
    public let text: String
    public let kind: Kind
    public let media: MediaInfo
    public let variants: [PlaylistVariant]
    public let fingerprint: String
    public let cacheable: Bool
    public let reason: String
}

/// Переписывает HLS-плейлист на loopback-URL. Чистая функция: никакого I/O, всё тестируется на фикстурах.
public enum PlaylistRewriter {
    public typealias ProxyBuilder = (_ absoluteOriginUrl: String, _ kind: ResourceKind) -> String

    private static let uriAttribute = try! NSRegularExpression(pattern: "URI=\"([^\"]*)\"", options: [])
    private static let resolution = try! NSRegularExpression(pattern: "RESOLUTION=(\\d+)x(\\d+)", options: [])
    private static let bandwidth = try! NSRegularExpression(pattern: "BANDWIDTH=(\\d+)", options: [])
    private static let codecs = try! NSRegularExpression(pattern: "CODECS=\"([^\"]*)\"", options: [])

    public static func looksLikePlaylist(_ prefix: Data) -> Bool {
        var bytes = [UInt8](prefix.prefix(64))
        if bytes.count >= 3, bytes[0] == 0xEF, bytes[1] == 0xBB, bytes[2] == 0xBF { bytes.removeFirst(3) }
        while let first = bytes.first, first == 0x20 || first == 0x0A || first == 0x0D || first == 0x09 {
            bytes.removeFirst()
        }
        return String(bytes: bytes, encoding: .utf8)?.hasPrefix("#EXTM3U") ?? false
    }

    public static func isPlaylistCandidate(url: String, contentType: String?) -> Bool {
        if let ct = contentType?.lowercased(), ct.contains("mpegurl") { return true }
        let path = URL(string: url)?.path.lowercased() ?? url.lowercased()
        return path.contains(".m3u8") || path.contains(".m3u")
    }

    public static func rewrite(text: String, finalUrl: String, proxy: ProxyBuilder) -> RewriteResult {
        var media = MediaInfo()
        var variants: [PlaylistVariant] = []
        var out: [String] = []
        var isMaster = false
        var pendingDuration: Double? = nil
        var pendingBandwidth = 0
        var pendingWidth = 0
        var pendingHeight = 0
        var pendingCodecs: String? = nil
        var segmentIndex = 0
        var elapsed = 0.0
        var lastVariantUrl: String? = nil

        for rawLine in text.components(separatedBy: "\n") {
            let hasCR = rawLine.hasSuffix("\r")
            let line = hasCR ? String(rawLine.dropLast()) : rawLine

            if line.isEmpty {
                out.append(line)
                continue
            }

            if line.hasPrefix("#") {
                let upper = line.uppercased()

                if upper.hasPrefix("#EXT-X-STREAM-INF") {
                    isMaster = true
                    pendingBandwidth = intAttribute(bandwidth, line) ?? 0
                    if let r = resolution.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) {
                        pendingWidth = Int((line as NSString).substring(with: r.range(at: 1))) ?? 0
                        pendingHeight = Int((line as NSString).substring(with: r.range(at: 2))) ?? 0
                    }
                    pendingCodecs = codecs.firstMatch(in: line, range: NSRange(line.startIndex..., in: line))
                        .map { (line as NSString).substring(with: $0.range(at: 1)) }
                } else if upper.hasPrefix("#EXT-X-TARGETDURATION") {
                    if let v = doubleValue(line) { media.targetDuration = v }
                } else if upper.hasPrefix("#EXT-X-ENDLIST") {
                    media.endList = true
                } else if upper.hasPrefix("#EXT-X-BYTERANGE") {
                    media.byteRange = true
                } else if upper.hasPrefix("#EXT-X-PART") || upper.hasPrefix("#EXT-X-PRELOAD-HINT") {
                    media.llhls = true
                } else if upper.hasPrefix("#EXTINF") {
                    pendingDuration = doubleValue(line)
                } else if upper.hasPrefix("#EXT-X-KEY") || upper.hasPrefix("#EXT-X-SESSION-KEY") {
                    let method = attribute("METHOD", line)?.uppercased() ?? ""
                    let keyFormat = attribute("KEYFORMAT", line)
                    if method.contains("SAMPLE-AES") || (keyFormat != nil && keyFormat != "identity") {
                        media.drm = true
                    }
                }

                // URI-несущие теги: переписываем сам атрибут.
                if let kind = uriTagKind(upper) {
                    let lineOut = rewriteUriAttribute(line, kind: kind) { absolute, k in
                        if k == .playlist { return proxy(absolute, .playlist) }
                        if k == .key { media.keyUrls.append(absolute); return proxy(absolute, .key) }
                        if upper.hasPrefix("#EXT-X-MAP") { media.initUrl = absolute }
                        return proxy(absolute, .segment)
                    }
                    out.append(lineOut)
                } else {
                    out.append(line)
                }
                continue
            }

            // Строка-URI: либо вариант master'а, либо сегмент media-плейлиста.
            let absolute = UrlResolver.resolve(base: finalUrl, reference: line)
            if isMaster {
                let proxyUrl = proxy(absolute, .playlist)
                variants.append(PlaylistVariant(url: absolute, bandwidth: pendingBandwidth,
                                                width: pendingWidth, height: pendingHeight, codecs: pendingCodecs))
                pendingBandwidth = 0; pendingWidth = 0; pendingHeight = 0; pendingCodecs = nil
                lastVariantUrl = absolute
                out.append(proxyUrl)
            } else {
                let duration = pendingDuration ?? 0
                media.segments.append(PlaylistSegment(index: segmentIndex, url: absolute,
                                                      duration: duration, startSec: elapsed))
                elapsed += duration
                segmentIndex += 1
                pendingDuration = nil
                out.append(proxy(absolute, .segment))
            }
        }

        media.totalDuration = elapsed
        let kind: RewriteResult.Kind = isMaster ? .master : (media.segments.isEmpty ? .other : .media)
        let cacheable = kind == .media && media.endList && !media.byteRange && !media.llhls && !media.drm
        var reason = "ok"
        if kind == .other { reason = "empty playlist" }
        else if !media.endList { reason = "live playlist" }
        else if media.byteRange { reason = "byte-range playlist" }
        else if media.llhls { reason = "ll-hls playlist" }
        else if media.drm { reason = "drm playlist" }

        return RewriteResult(
            text: out.joined(separator: "\n"),
            kind: kind,
            media: media,
            variants: variants,
            fingerprint: fingerprint(media: media, variants: variants),
            cacheable: cacheable,
            reason: reason)
    }

    // MARK: - private

    private static func uriTagKind(_ upper: String) -> ResourceKind? {
        if upper.hasPrefix("#EXT-X-MEDIA:") || upper.hasPrefix("#EXT-X-MEDIA:") { return .playlist }
        if upper.hasPrefix("#EXT-X-I-FRAME-STREAM-INF") { return .playlist }
        if upper.hasPrefix("#EXT-X-KEY") || upper.hasPrefix("#EXT-X-SESSION-KEY") { return .key }
        if upper.hasPrefix("#EXT-X-MAP") { return .segment }
        if upper.hasPrefix("#EXT-X-PART") || upper.hasPrefix("#EXT-X-PRELOAD-HINT") { return .segment }
        return nil
    }

    private static func rewriteUriAttribute(_ line: String, kind: ResourceKind,
                                            resolve: (String, ResourceKind) -> String) -> String {
        guard let match = uriAttribute.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) else {
            return line
        }
        let ns = line as NSString
        let raw = ns.substring(with: match.range(at: 1))
        // METHOD=NONE / пустой URI — не трогаем
        if raw.isEmpty { return line }
        let replaced = resolve(raw, kind)
        return ns.replacingCharacters(in: match.range(at: 1), with: replaced)
    }

    private static func attribute(_ name: String, _ line: String) -> String? {
        let pattern = name + "=([^,\"]+)"
        guard let re = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return nil }
        guard let m = re.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) else { return nil }
        return (line as NSString).substring(with: m.range(at: 1)).trimmingCharacters(in: CharacterSet(charactersIn: "\""))
    }

    private static func intAttribute(_ re: NSRegularExpression, _ line: String) -> Int? {
        guard let m = re.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) else { return nil }
        return Int((line as NSString).substring(with: m.range(at: 1)))
    }

    private static func doubleValue(_ line: String) -> Double? {
        guard let colon = line.firstIndex(of: ":") else { return nil }
        var s = String(line[line.index(after: colon)...])
        if let comma = s.firstIndex(of: ",") { s = String(s[..<comma]) }
        return Double(s.trimmingCharacters(in: .whitespaces))
    }

    private static func fingerprint(media: MediaInfo, variants: [PlaylistVariant]) -> String {
        var parts: [String] = ["n=\(media.segments.count)", "td=\(media.targetDuration)"]
        if let first = media.segments.first { parts.append("f=\(pathOnly(first.url))") }
        if let last = media.segments.last { parts.append("l=\(pathOnly(last.url))") }
        parts.append("d=\(Int(media.totalDuration))")
        parts.append("v=\(variants.count)")
        if let i = media.initUrl { parts.append("map=\(pathOnly(i))") }
        return String(format: "%016llx", Ids.fnv1a(parts.joined(separator: "|")))
    }

    private static func pathOnly(_ url: String) -> String {
        URL(string: url)?.path ?? url
    }
}

/// Резолв относительных URI. Отличие от RFC 3986 сознательное: ссылка без query наследует query базы —
/// иначе kodik/CVH теряют `sig` (совпадает с `src/lib/urlResolve.ts` в AnimeApp).
public enum UrlResolver {
    public static func resolve(base: String, reference: String) -> String {
        let ref = reference.trimmingCharacters(in: .whitespaces)
        if ref.isEmpty { return base }
        guard let baseURL = URL(string: base) else { return ref }
        if let scheme = URLComponents(string: ref)?.scheme, !scheme.isEmpty { return ref }
        guard let resolved = URL(string: ref, relativeTo: baseURL)?.absoluteString else { return ref }

        let refHasQuery = ref.contains("?")
        let baseQuery = baseURL.query
        if !refHasQuery, let q = baseQuery, !q.isEmpty, !resolved.contains("?") {
            return resolved + "?" + q
        }
        return resolved
    }
}
