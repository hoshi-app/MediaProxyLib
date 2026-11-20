import { DEFAULT_CONFIG } from './defaults';
import type {
  AuthRequiredEvent,
  CacheEvent,
  FailOpenResult,
  MediaProxyConfig,
  NativeMediaProxy,
  ProxyErrorEvent,
  ProxyStats,
  RecoverEvent,
  ResolveOptions,
  ResolveResult,
  RestartResult,
  ServerStateEvent,
  StartResult,
  StreamState,
  Subscription,
} from './types';

const MODULE_NAME = 'MediaProxy';

let nativeCache: NativeMediaProxy | null | undefined;

/**
 * Loaded lazily so the library still imports in tests, on web and in Expo Go without crashing.
 * Without the native module, `resolve()` returns the origin with `cached: false` and playback works
 * as before.
 */
function loadNative(): NativeMediaProxy | null {
  if (nativeCache !== undefined) return nativeCache;
  try {
    const core = require('expo-modules-core') as { requireNativeModule?: (name: string) => unknown };
    if (core && typeof core.requireNativeModule === 'function') {
      nativeCache = core.requireNativeModule(MODULE_NAME) as NativeMediaProxy;
    } else {
      nativeCache = null;
    }
  } catch {
    nativeCache = null;
  }
  return nativeCache;
}

export function isAvailable(): boolean {
  return loadNative() !== null;
}

export function getDefaultConfig(): MediaProxyConfig {
  return { ...DEFAULT_CONFIG };
}

// ── native response normalization (plain objects, no guarantees) ─────────────────────────────────

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : {};
}

function asNumber(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function asString(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function asBoolean(value: unknown, fallback = false): boolean {
  return typeof value === 'boolean' ? value : fallback;
}

function normalizeStart(value: unknown): StartResult {
  const raw = asRecord(value);
  const issues = Array.isArray(raw.issues) ? raw.issues.filter((item): item is string => typeof item === 'string') : [];
  return {
    port: asNumber(raw.port),
    baseUrl: asString(raw.baseUrl),
    generation: asNumber(raw.generation),
    issues,
  };
}

function normalizeResolve(value: unknown, origin: string): ResolveResult {
  const raw = asRecord(value);
  const url = asString(raw.url, origin);
  const streamId = asString(raw.streamId);
  return {
    streamId,
    url,
    cached: asBoolean(raw.cached) && streamId.length > 0 && url !== origin,
    reason: asString(raw.reason, url === origin ? 'FALLBACK' : 'ok'),
    generation: asNumber(raw.generation),
  };
}

function normalizeStats(value: unknown): ProxyStats {
  const raw = asRecord(value);
  return {
    port: asNumber(raw.port),
    running: asBoolean(raw.running),
    generation: asNumber(raw.generation),
    bytes: asNumber(raw.bytes),
    maxBytes: asNumber(raw.maxBytes),
    freeDiskBytes: asNumber(raw.freeDiskBytes),
    streams: asNumber(raw.streams),
    hits: asNumber(raw.hits),
    misses: asNumber(raw.misses),
    evictions: asNumber(raw.evictions),
    prefetched: asNumber(raw.prefetched),
    stallKicks: asNumber(raw.stallKicks),
    originTruncated: asNumber(raw.originTruncated),
    maxSessions: asNumber(raw.maxSessions),
  };
}

function normalizeStreamState(value: unknown): StreamState {
  const raw = asRecord(value);
  const segments = Array.isArray(raw.segments)
    ? raw.segments.map((item) => {
        const segment = asRecord(item);
        return {
          index: asNumber(segment.index),
          startSec: asNumber(segment.startSec),
          durationSec: asNumber(segment.durationSec),
          cachedBytes: asNumber(segment.cachedBytes),
          complete: asBoolean(segment.complete),
        };
      })
    : [];
  return {
    cacheKey: asString(raw.cacheKey),
    cacheable: asBoolean(raw.cacheable),
    segments,
    cachedAheadSec: asNumber(raw.cachedAheadSec),
  };
}

function fallbackResolve(origin: string, reason: string): ResolveResult {
  return { streamId: '', url: origin, cached: false, reason, generation: 0 };
}

// ── public API ───────────────────────────────────────────────────────────────────────────────────

/**
 * Starts the loopback server once per process; call it when the app loads. `resolve` is synchronous
 * and can be called right after. If the server fails to start, the result carries `issues` and
 * `resolve` keeps returning the origin.
 */
export async function start(): Promise<StartResult> {
  const native = loadNative();
  if (!native) return { port: 0, baseUrl: '', generation: 0, issues: ['NO_NATIVE'] };
  try {
    return normalizeStart(native.start());
  } catch {
    return { port: 0, baseUrl: '', generation: 0, issues: ['SERVER_START_FAILED'] };
  }
}

/**
 * Creates a session and returns the loopback URL for the player. Synchronous and never throws: any
 * failure yields the origin with `cached: false`, per the app's fail-open contract.
 */
export function resolve(origin: string, options: ResolveOptions = {}): ResolveResult {
  const native = loadNative();
  try {
    if (typeof origin !== 'string') return fallbackResolve('', 'INVALID_URL');
    if (!native) return fallbackResolve(origin, 'NO_NATIVE');
    if (!origin.startsWith('http://') && !origin.startsWith('https://')) {
      return fallbackResolve(origin, 'INVALID_URL');
    }
    return normalizeResolve(native.resolve(origin, options), origin);
  } catch {
    return fallbackResolve(typeof origin === 'string' ? origin : '', 'RESOLVE_FAILED');
  }
}

/** Releases the session: cancels prefetch and drops the refcount; cached bytes stay until eviction. */
export function release(streamId: string): void {
  const native = loadNative();
  if (!native || !streamId) return;
  try {
    native.release(streamId);
  } catch {
    // fail-open: the session expires on its own
  }
}

/** Switches the stream to the origin after the player failed on the loopback URL. */
export function failOpen(streamIdOrUrl: string): FailOpenResult | null {
  const native = loadNative();
  if (!native || !streamIdOrUrl) return null;
  try {
    const raw = asRecord(native.failOpen(streamIdOrUrl));
    const streamId = asString(raw.streamId);
    const originUrl = asString(raw.originUrl);
    if (!streamId && !originUrl) return null;
    return { streamId: streamId || undefined, originUrl: originUrl || undefined };
  } catch {
    return null;
  }
}

/** Queues a prefetch window ahead of the last requested segment; the app's stall detector calls it. */
export function nudge(streamId: string): { queued: number } {
  const native = loadNative();
  if (!native || !streamId) return { queued: 0 };
  try {
    return { queued: asNumber(asRecord(native.nudge(streamId)).queued) };
  } catch {
    return { queued: 0 };
  }
}

/**
 * Restarts the media playlist at the proxy level: re-reads it for fresh segment URLs from the CDN
 * and re-queues the stuck window, so the player needs no remount. Asynchronous because the native
 * side is an AsyncFunction that issues a network request.
 */
export async function restart(streamId: string): Promise<RestartResult> {
  const native = loadNative();
  const empty: RestartResult = { queued: 0, refreshed: false, segmentsChanged: false, reason: 'NO_NATIVE' };
  if (!native || !streamId) return empty;
  try {
    const raw = asRecord(await native.restartStream(streamId));
    return {
      queued: asNumber(raw.queued),
      refreshed: asBoolean(raw.refreshed),
      segmentsChanged: asBoolean(raw.segmentsChanged),
      cacheable: asBoolean(raw.cacheable, true),
      reason: asString(raw.reason, 'ok'),
    };
  } catch {
    return empty;
  }
}

/** Changes policy at runtime; it applies to subsequent requests. */
export function setConfig(patch: Partial<MediaProxyConfig>): void {
  const native = loadNative();
  if (!native) return;
  try {
    native.setConfig(patch);
  } catch {
    // policy not applied — keep running on the native defaults
  }
}

export function getStats(): ProxyStats {
  const native = loadNative();
  if (!native) return normalizeStats(null);
  try {
    return normalizeStats(native.getStats());
  } catch {
    return normalizeStats(null);
  }
}

/** Segment state for the "on disk" bar in the UI. */
export function getStreamState(streamId: string): StreamState {
  const native = loadNative();
  if (!native || !streamId) return normalizeStreamState(null);
  try {
    return normalizeStreamState(native.getStreamState(streamId));
  } catch {
    return normalizeStreamState(null);
  }
}

export function clearCache(streamId?: string): { freedBytes: number } {
  const native = loadNative();
  if (!native) return { freedBytes: 0 };
  try {
    return { freedBytes: asNumber(asRecord(native.clearCache(streamId)).freedBytes) };
  } catch {
    return { freedBytes: 0 };
  }
}

// ── events ──────────────────────────────────────────────────────────────────────────────────────

function subscribe<T>(event: string, listener: (payload: T) => void): Subscription {
  const native = loadNative();
  if (!native) return { remove: () => {} };
  try {
    const subscription = native.addListener(event, (payload) => listener(payload as T));
    if (subscription && typeof subscription.remove === 'function') return subscription;
  } catch {
    // subscription failed — no events will arrive, which is no reason to crash
  }
  return { remove: () => {} };
}

export const onError = (listener: (event: ProxyErrorEvent) => void): Subscription =>
  subscribe<ProxyErrorEvent>('onError', listener);

export const onAuthRequired = (listener: (event: AuthRequiredEvent) => void): Subscription =>
  subscribe<AuthRequiredEvent>('onAuthRequired', listener);

export const onServerState = (listener: (event: ServerStateEvent) => void): Subscription =>
  subscribe<ServerStateEvent>('onServerState', listener);

export const onCache = (listener: (event: CacheEvent) => void): Subscription =>
  subscribe<CacheEvent>('onCache', listener);

/** The engine re-reads the media playlist on a stalled download, so the player needs no remount. */
export const onRecover = (listener: (event: RecoverEvent) => void): Subscription =>
  subscribe<RecoverEvent>('onRecover', listener);

/** Whether the URL is HLS; needed for the player's `source.type` hint (see src/hls.ts). */
export { isHlsUrl } from './hls';

export type {
  AuthRequiredEvent,
  CacheEvent,
  FailOpenResult,
  MediaProxyConfig,
  MediaProxyErrorCode,
  ProxyErrorEvent,
  ProxyStats,
  RecoverEvent,
  ResolveOptions,
  ResolveResult,
  RestartResult,
  SegmentState,
  ServerStateEvent,
  StartResult,
  StreamState,
  Subscription,
} from './types';
