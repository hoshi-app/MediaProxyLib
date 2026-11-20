/**
 * Container hint for the native player (`source.type` in react-native-video).
 *
 * ExoPlayer infers the type from the extension of the last URL segment and otherwise builds a
 * ProgressiveMediaSource, which throws `UnrecognizedInputFormatException` for an extensionless CVH
 * variant (`…/id/…/video/`). AVPlayer sniffs the response body, so the hint neither helps nor
 * hurts it.
 *
 * Loopback URLs from `resolve()` always end in `.m3u8`, so the hint matters for the origin URL used
 * with `cached: false`, `failOpen()` and direct playback. No extension or `.m3u8` means HLS. Only
 * the last path segment counts: dots in directories and query strings are not extensions.
 */
export function isHlsUrl(url: string): boolean {
  const path = String(url ?? '').replace(/[?#].*$/, '');
  if (/\.m3u8$/i.test(path)) return true;
  const last = path.slice(path.lastIndexOf('/') + 1);
  return !/\.[a-z0-9]{2,5}$/i.test(last);
}
