<div align="center">

# MediaProxyLib

A local HTTP proxy with a disk cache for HLS in React Native (iOS + Android).

[![CI](https://github.com/hoshi-app/MediaProxyLib/actions/workflows/ci.yml/badge.svg)](https://github.com/hoshi-app/MediaProxyLib/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/react-native-media-proxy.svg)](https://www.npmjs.com/package/react-native-media-proxy)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform: iOS / Android](https://img.shields.io/badge/platform-iOS%20%7C%20Android-lightgrey.svg)](#compatibility)
[![Status: experimental](https://img.shields.io/badge/status-experimental-orange.svg)](#status)

**English** · [Русский](README.ru.md)

</div>

---

## Purpose

The library starts an HTTP server on `127.0.0.1` inside the app process and puts it between the
player and the video source. The player gets a loopback URL instead of the CDN link.

Why: native players (AVPlayer, ExoPlayer) cannot set headers for segment requests and cannot manage
a disk cache. The proxy solves both problems the same way on both platforms.

Terms: **playlist** — a list of segments, **segment** — a chunk of the video file (a few seconds),
**loopback URL** — an address like `http://127.0.0.1:<port>/...`.

## Features

- **Session headers** (`User-Agent`, `Referer`, `Cookie`) are sent for playlists, segments and keys.
  Cookies from `Set-Cookie` are stored in the session cookie jar.
- **Disk cache**: limit in megabytes, automatic eviction, manual clearing, statistics. Data survives
  an app restart.
- **Resume from prefix**: after a drop, the download continues from the last written byte instead of
  starting over.
- **Prefetch**: 30 seconds ahead, at most 6 segments, 2 in parallel.
- **Recovery**: when the byte count stops growing, the engine re-reads the playlist and resumes
  loading from the new URLs. No player remount required.
- **Events**: errors, authorization failures, server state, cache state, auto-recovery.
- **AES-128 keys** are never written to disk — session memory only.

## Limitations

- DRM (FairPlay, Widevine) is not supported.
- Live and LL-HLS are proxied without caching.
- AirPlay does not work with a loopback URL.
- ABR (quality switching) is not implemented: the proxy serves what the player asks for.
- There is no dedicated Android foreground service: the player service keeps the process alive.

## How it works

1. `start()` brings up an HTTP server on `127.0.0.1` (ephemeral port, for the lifetime of the
   process).
2. `resolve(url, options)` creates a session with headers and returns a loopback URL with the
   session id.
3. The player requests playlists and segments from that URL.
4. The proxy downloads the playlist, rewrites links to loopback addresses and caches segments on
   disk.
5. Partial requests (seeking) are served from the written prefix, the remainder is fetched.
6. Rewatching and the next app launch read from disk.

```text
AVPlayer / ExoPlayer  →  http://127.0.0.1:<port>/s/<sid>/<b64>/<name>.<ext>  →  proxy  →  CDN
                                                                                  ↓
                                                                             disk (cache)
```

If the proxy is unavailable, `resolve()` returns the origin URL with `cached: false` — the app keeps
playing directly from the CDN. This is part of the contract and needs no exception handling.

## Installation

```bash
npm install react-native-media-proxy
# or
npx expo install react-native-media-proxy
```

The config plugin in `app.json` / `app.config.js`:

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

The plugin makes two changes without which loopback requests are blocked by the system:

- **iOS** — `NSAllowsLocalNetworking` in `Info.plist`.
- **Android** — `network_security_config` with `cleartextTrafficPermitted` for `127.0.0.1` only.
  The global `usesCleartextTraffic` flag stays off.

If the project already has its own `android:networkSecurityConfig`, the plugin does not overwrite
it and prints a warning: add the `127.0.0.1` domain by hand.

For apps without Expo (bare React Native) the plugin does not apply — add both entries to
`Info.plist` and `AndroidManifest.xml` manually.

Installing from the repository is also supported: `npm install github:hoshi-app/MediaProxyLib`.

## Usage

### Minimal example

```ts
import * as MediaProxy from 'react-native-media-proxy';

await MediaProxy.start();

const source = MediaProxy.resolve('https://cdn.example.com/film/index.m3u8', {
  // Stable cache key: survives re-resolve and app restarts.
  cacheKey: 'film42|voice-ru|ep3|q720',
  headers: {
    'User-Agent': 'Mozilla/5.0 ...',
    'Referer': 'https://example.com/',
  },
});
```

`source.url` goes to the player. `cached: true` means "disk cache is enabled", not "segments are
already downloaded"; the warm part is in `getStreamState().cachedAheadSec`. With `cached: false`,
play the origin URL with the same headers.

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

state.cachedAheadSec; // seconds already on disk
state.segments;       // per segment: cachedBytes, complete
```

### Error handling

```ts
MediaProxy.onAuthRequired((e) => {
  // 400/401/403/410: the signature expired, the IP changed, the session is gone.
  // A fresh URL from the app API and a player remount are required.
  console.log(e.streamId, e.originStatus);
});

MediaProxy.onError((e) => {
  console.log(e.code, e.message, e.retryable, e.fatal);
});

MediaProxy.onRecover((e) => {
  // The engine re-read the playlist and resumed loading without remounting the player.
});
```

### Container hint (Android)

ExoPlayer picks HLS from the extension of the last URL segment. CVH variants come without an
extension (`…/id/…/video/`): without an explicit `type`, ExoPlayer builds a progressive source and
fails with `UnrecognizedInputFormatException` — a black screen and repeated remounts.

A loopback URL from `resolve()` always ends with `.m3u8` ([docs/PROTOCOL.md](docs/PROTOCOL.md) §1),
so a cached stream needs no hint. It is needed for an origin URL: `cached: false`, `failOpen()` and
playback that bypasses the proxy.

```tsx
import * as MediaProxy from 'react-native-media-proxy';

const uri = resolved.cached ? resolved.url : origin;
const source = { uri, headers, ...(MediaProxy.isHlsUrl(uri) ? { type: 'm3u8' } : {}) };
```

`isHlsUrl(url)` is a pure function: `true` when the last path segment has no extension or ends with
`.m3u8`; dots in directories and in the query string do not count as extensions. The rule is
verified by `node tools/check-hls-url.mjs`.

## Verifying it works

```ts
MediaProxy.setConfig({ debug: true });
```

1. Logs: created links, byte source, errors. Signatures and tokens are stripped.
2. Response header `X-MediaProxy-Cache: hit | miss | partial | passthrough` (only with
   `debug: true`).
3. `getStats()`: bytes on disk, hits, misses, auto-recovery count.

On a second watch of the same video `hits` grows and `misses` stays flat.

## API

### Methods

| Method | Description |
|---|---|
| `start()` | Starts the server. Once per process. Returns the port and a list of problems. |
| `resolve(url, options?)` | Creates a session, returns the link for the player. Synchronous, never throws. |
| `release(streamId)` | Releases the session, cancels prefetch. The cache stays on disk. |
| `failOpen(streamId)` | Switches the session to the origin URL (after a player error). |
| `nudge(streamId)` | Moves the prefetch window from the last requested segment. |
| `restart(streamId)` | Re-reads the playlist and restarts loading. No player remount needed. |
| `setConfig(patch)` | Changes policy at runtime. |
| `getStats()` | Port, cache size, hits, misses, recoveries. |
| `getStreamState(streamId)` | Segment state: bytes on disk, completion flag. |
| `clearCache(streamId?)` | Clears the whole cache or one video. |

### Events

| Event | Payload |
|---|---|
| `onError` | Once per transition into the error state: code, origin status, message. |
| `onAuthRequired` | 400/401/403/410 — a new URL is required. |
| `onServerState` | Server start, restart, stop. |
| `onCache` | Eviction, clearing, filling. |
| `onRecover` | Automatic recovery after a stall. |

### Settings

`setConfig(patch)` — partial update; unknown keys are ignored. The full list is in
[src/types.ts](src/types.ts).

| Option | Default | Description |
|---|---|---|
| `enabled` | `true` | Enables the proxy. |
| `maxCacheMB` | `512` | Disk cache limit. |
| `minFreeDiskMB` | `2048` | Below this free space, caching is disabled. |
| `maxSessions` | `4` | Concurrent sessions. |
| `prefetchSeconds` | `30` | Prefetch depth in seconds. |
| `prefetchMaxSegments` | `6` | Segment cap in the prefetch window. |
| `prefetchConcurrency` | `2` | Parallel prefetch downloads. |
| `prefetchRetries` | `3` | Prefetch retries. |
| `cooldownSec` | `30` | Pause between attempts for a failed segment. |
| `prewarmSegments` | `2` | Segments fetched right after `resolve`. |
| `stallKickSec` | `6` | Byte-growth silence after which the engine re-reads the playlist. |
| `recoveryMax` | `2` | Recovery attempts per segment. |
| `debug` | `false` | Logs and the `X-MediaProxy-Cache` header. |

Timeouts (`reqConnectSec`, `reqFirstByteSec`, `reqSilenceSec`, `prefetchFirstByteSec`,
`prefetchSilenceSec`) are set shorter than the player timeouts: otherwise the player remounts the
source before the proxy reacts.

## Cache

- Directory: `Caches/mediaproxy/v1/<cacheKey hash>/`.
- Default limit — 512 MB total across all videos.
- Eviction works on whole directory entries. Segments inside an entry are not evicted: an episode
  is watched sequentially.
- An active entry (requested within the last two minutes) is not evicted.
- At 90 % of the limit prefetch stops; at 100 % new entries are not created, serving continues.
- On app start the journal is reconciled with the files: short files are truncated, extra ones are
  removed.
- `clearCache()` clears everything, `clearCache(streamId)` clears one entry.

## Compatibility

- Expo (the module is built as an Expo module and requires `expo-modules-core`).
- iOS 15.0+.
- Players: `react-native-video`, `expo-video` and any other player that accepts a plain URL.

## Status

The project is experimental. Verified:

| Part | State |
|---|---|
| Android core (Kotlin) | builds, 301 automated checks pass |
| TypeScript wrapper | type check passes |
| Android library as a whole (Gradle + Expo layer) | never built: a host project is required |
| iOS (Swift) | builds |
| CI | GitHub Actions: TypeScript, HLS URL rule, Kotlin self-test, Swift type check |
| Performance measurements on real videos | not done |

## Documentation

- [docs/PROTOCOL.md](docs/PROTOCOL.md) — behaviour contract for iOS and Android: responses, codes,
  cache states. (Russian)
- [docs/PLAN.md](docs/PLAN.md) — rationale for decisions and limitations. (Russian)

## License

[MIT](LICENSE): use, modification and distribution without restrictions, including closed-source
and commercial projects. The condition is to keep the license text and the attribution.
