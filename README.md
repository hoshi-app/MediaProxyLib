# react-native-media-proxy

A local HLS proxy with a disk cache for React Native apps on iOS and Android.

**English** · [Русский](README.ru.md)

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform: iOS / Android](https://img.shields.io/badge/platform-iOS%20%7C%20Android-lightgrey.svg)](#requirements)
[![Status: experimental](https://img.shields.io/badge/status-experimental-orange.svg)](#status)

## Why

Native players (AVPlayer, ExoPlayer) cannot set headers on segment requests and do not expose
control over the disk cache. This library runs an HTTP server on `127.0.0.1` inside your app
process, puts it between the player and the CDN, and solves both problems the same way on iOS
and Android:

- session headers (`User-Agent`, `Referer`, `Cookie`) are sent to playlists, segments and keys;
- segments are cached on disk and survive app restarts;
- playback can resume from the bytes already on disk after a network drop.

## Requirements

- Expo module project (`expo-modules-core`).
- iOS 15+.
- Any player that accepts a regular URL: `react-native-video`, `expo-video`, etc.
- Not published to npm; install from the repository.

## Installation

```bash
npm install github:hoshi-app/MediaProxyLib
```

Add the config plugin to `app.json` or `app.config.js`:

```json
{
  "expo": {
    "plugins": ["react-native-media-proxy"]
  }
}
```

Then rebuild the native projects:

```bash
npx expo prebuild --clean
```

The plugin makes two changes, both required for loopback requests to work:

- **iOS**: adds `NSAllowsLocalNetworking` to `Info.plist`.
- **Android**: adds a `network_security_config` that permits cleartext traffic only for
  `127.0.0.1`. Global `usesCleartextTraffic` is not enabled.

If the project already has its own `android:networkSecurityConfig`, the plugin leaves it alone
and prints a warning: add `127.0.0.1` manually.

Bare React Native projects do not run the plugin; make these two changes by hand.

## Usage

Start the proxy once, then resolve each video URL before passing it to the player:

```ts
import * as MediaProxy from 'react-native-media-proxy';

await MediaProxy.start();

const source = MediaProxy.resolve('https://cdn.example.com/film/index.m3u8', {
  cacheKey: 'film42|voice-ru|ep3|q720',
  headers: {
    'User-Agent': 'Mozilla/5.0 ...',
    'Referer': 'https://example.com/',
  },
});

// Pass source.url to the player. The proxy sends the headers for you.
```

`resolve()` is synchronous and never throws. If the proxy cannot be used, it returns the
original URL with `cached: false`; play that URL with the same headers and nothing breaks.

`cached: true` means "disk caching is on", not "segments are already downloaded". To check
what is actually on disk, use `getStreamState()`.

Use a stable `cacheKey` for the same video. If it changes between runs, the old cache entries
are not reused.

### Player component

```tsx
import { useEffect, useMemo, useState } from 'react';
import Video from 'react-native-video';
import * as MediaProxy from 'react-native-media-proxy';

export function Player({ url, headers, cacheKey }) {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    MediaProxy.start().then(() => setReady(true));
  }, []);

  const source = useMemo(() => {
    const resolved = ready
      ? MediaProxy.resolve(url, { cacheKey, headers })
      : { url, cached: false };
    return { uri: resolved.cached ? resolved.url : url, headers };
  }, [ready, url, cacheKey, headers]);

  return <Video source={source} />;
}
```

### Cache state

```ts
const state = MediaProxy.getStreamState(source.streamId);

state.cachedAheadSec; // seconds available ahead of the current position
state.segments;       // per segment: cachedBytes, complete
```

### Errors and recovery

```ts
const authSub = MediaProxy.onAuthRequired((e) => {
  // 400/401/403/410: the signed URL or session has expired.
  // Fetch a fresh URL from your API and reload the source.
  console.log(e.streamId, e.originStatus);
});

const errorSub = MediaProxy.onError((e) => {
  console.log(e.code, e.message, e.retryable, e.fatal);
});

MediaProxy.onRecover(() => {
  // The proxy re-read the playlist and resumed downloading.
  // The player does not need to be remounted.
});

// Later
authSub.remove();
errorSub.remove();
```

## Android: HLS source type

ExoPlayer guesses the container from the extension of the last URL path segment. Some HLS
variant URLs have no extension (`.../id/.../video/`), and ExoPlayer then creates a progressive
source and fails with `UnrecognizedInputFormatException`.

Loopback URLs from `resolve()` always end in `.m3u8`, so cached playback needs no hint. The
origin URL does, for example when `cached` is `false` or after `failOpen()`:

```tsx
const uri = resolved.cached ? resolved.url : originUrl;
const source = {
  uri,
  headers,
  ...(MediaProxy.isHlsUrl(uri) ? { type: 'm3u8' } : {}),
};
```

`isHlsUrl(url)` returns `true` when the last path segment has no extension or ends in
`.m3u8`. Dots in directories and query strings are ignored. Rule check:
`npm run check:hls`.

## API

### Methods

| Method | Description |
|---|---|
| `start()` | Starts the server once per process. Returns the port, base URL and startup issues. |
| `resolve(url, options?)` | Creates a session and returns the loopback URL. Synchronous, never throws. |
| `release(streamId)` | Releases the session and cancels prefetch. Cached data stays on disk. |
| `failOpen(streamIdOrUrl)` | Switches the session back to the origin URL. |
| `nudge(streamId)` | Queues a prefetch window starting at the last requested segment. |
| `restart(streamId)` | Re-reads the playlist and re-queues downloads without remounting the player. |
| `setConfig(patch)` | Changes policy at runtime. |
| `getStats()` | Port, cache size, hits, misses, recoveries. |
| `getStreamState(streamId)` | Per-segment cache state. |
| `clearCache(streamId?)` | Clears all cache, or the cache of one stream. |
| `isAvailable()` | Whether the native module is loaded. |
| `getDefaultConfig()` | A copy of the default configuration. |

`resolve()` options:

| Option | Description |
|---|---|
| `cacheKey` | Stable cache identity. Must not change between runs. |
| `headers` | Headers for playlists, segments and keys. |
| `cache` | `false` disables disk cache for this stream (live, debugging). |
| `prewarm` | Prefetch the playlist and first segments. Defaults to `true`. |

### Events

| Event | Payload |
|---|---|
| `onError` | Error code, origin status, message, retryable/fatal flags. |
| `onAuthRequired` | Origin returned 400/401/403/410; a new URL is needed. |
| `onServerState` | Server started, restarted or stopped. |
| `onCache` | Eviction, clear, full or reconciliation. |
| `onRecover` | A stalled download was recovered without a player remount. |

## Configuration

`setConfig(patch)` merges a partial update; unknown keys are ignored. The full type is in
[src/types.ts](src/types.ts).

Common settings:

| Option | Default | Description |
|---|---|---|
| `enabled` | `true` | Turns the proxy on or off. |
| `maxCacheMB` | `512` | Total disk budget for all videos. |
| `minFreeDiskMB` | `2048` | Below this much free disk space, caching is disabled. |
| `maxSessions` | `4` | Concurrent sessions. |
| `prefetchSeconds` | `30` | How far ahead to prefetch. |
| `prefetchMaxSegments` | `6` | Maximum segments in the prefetch window. |
| `prewarmSegments` | `2` | Segments downloaded right after `resolve()`. |
| `stallKickSec` | `6` | Byte-progress timeout before the engine re-reads the playlist. |
| `recoveryMax` | `2` | Recovery attempts per segment. |
| `debug` | `false` | Logs and the `X-MediaProxy-Cache` response header. |

Advanced settings:

| Option | Default | Description |
|---|---|---|
| `prefetchConcurrency` | `2` | Parallel prefetch downloads. |
| `interactiveConcurrency` | `4` | Parallel player requests. |
| `prefetchRetries` | `3` | Retries for prefetch downloads. |
| `prefetchFirstByteSec` | `10` | First-byte timeout for prefetch. |
| `prefetchSilenceSec` | `15` | Stall timeout for prefetch. |
| `reqConnectSec` | `5` | Connect timeout for player requests. |
| `reqFirstByteSec` | `5` | First-byte timeout for player requests. |
| `reqSilenceSec` | `4` | Stall timeout for player requests. |
| `minSpeedKBps` | `1` | Minimum speed for prefetch and resumed downloads. |
| `cooldownSec` | `30` | Pause before retrying a failed segment. |
| `serving` | `'stream'` | `'stream'` sends bytes as they arrive; `'complete'` downloads the whole segment first. |

Request timeouts are intentionally shorter than the player's own timeouts. Otherwise the
player gives up before the proxy has a chance to recover.

## How it works

1. `start()` binds an HTTP server to `127.0.0.1` on an ephemeral port.
2. `resolve(url, options)` creates a session and returns a loopback URL for that session.
3. The player requests playlists and segments through the loopback URL.
4. The proxy downloads the playlist, rewrites segment URLs to loopback URLs, and caches
   segments on disk.
5. Range requests are served from the bytes already on disk; the rest is downloaded.
6. On the next play, cached segments are served without touching the CDN.

```text
AVPlayer / ExoPlayer
        │
        ▼
http://127.0.0.1:<port>/s/<sid>/<b64>/<name>.<ext>
        │
        ▼
   native proxy  ──►  CDN
        │
        ▼
   disk cache
```

AES-128 keys are kept in memory only and are never written to disk.

## Cache

- Location: `<app cache dir>/mediaproxy/v1/<hash of cacheKey>/`.
- Limit: `maxCacheMB`, shared by all videos.
- Eviction works on whole cache entries, not individual segments, so an episode is cached as a
  unit.
- At 90% of the limit prefetch stops. At 100% new entries are not created, but serving
  continues.
- On startup the journal is checked against the files on disk; incomplete files are trimmed
  and orphaned files are removed.
- `clearCache()` clears everything; `clearCache(streamId)` clears one stream.

## Debugging

```ts
MediaProxy.setConfig({ debug: true });
```

1. Logs show created URLs, byte sources and errors. Signatures and tokens are redacted.
2. Responses include `X-MediaProxy-Cache: hit | miss | partial | passthrough` when debug is on.
3. `getStats()` shows disk usage, hits, misses and the number of automatic recoveries.

On a second play of the same video, `hits` should grow and `misses` should not.

## Limitations

- DRM (FairPlay, Widevine) is not supported.
- Live and LL-HLS streams are proxied without caching.
- AirPlay does not work with loopback URLs.
- ABR is not implemented: the proxy serves what the player asks for.
- There is no Android foreground service; background playback depends on the player's service.
- The cache is local to the app, not shared between devices.

## Status

Experimental. The Android core (Kotlin) has an automated self-test and the TypeScript layer
type-checks; iOS builds successfully. The full Android library has not been built inside a host
app yet. There is no CI, and performance has not been measured on real titles.

## Documentation

- [docs/PROTOCOL.md](docs/PROTOCOL.md): behavior contract for iOS and Android (in Russian).
- [docs/PLAN.md](docs/PLAN.md): design decisions and tradeoffs (in Russian).

## License

[MIT](LICENSE).
