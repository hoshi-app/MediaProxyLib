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
 * Натив ищем лениво: библиотека должна импортироваться в тестах/на web/в Expo Go без краша.
 * Если модуля нет — resolve() возвращает origin и cached:false, и плеер играет как раньше.
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

// ── нормализация ответов натива (он возвращает обычные объекты, а не гарантии) ────────────────────

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

// ── публичное API ───────────────────────────────────────────────────────────────────────────────

/**
 * Поднимает loopback-сервер один раз на процесс. Вызывать при загрузке приложения; `resolve` можно
 * звать сразу после (он синхронный). Если сервер не поднялся — вернёт issues, а resolve отдаст origin.
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
 * Создаёт сессию и отдаёт loopback-URL для плеера. Синхронный и не бросает: на любой сбой — origin
 * с `cached:false` (fail-open по контракту приложения).
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

/** Освобождает сессию: отменяет prefetch, снимает refcount, кэш остаётся до вытеснения. */
export function release(streamId: string): void {
  const native = loadNative();
  if (!native || !streamId) return;
  try {
    native.release(streamId);
  } catch {
    // fail-open: сессия истечёт сама
  }
}

/** Аварийный перевод потока на origin (после ошибки плеера по loopback-URL). */
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

/** «Догрузи вперёд» для сторожа: окно prefetch от последнего запрошенного сегмента. */
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
 * Перезапуск манифеста НА УРОВНЕ ПРОКСИ: перечитывает media-плейлист (свежие URL сегментов у CDN)
 * и заново ставит загрузку залипшего окна. Плееру перемонтироваться не нужно — этого и добиваемся.
 * Асинхронный: в нативе это AsyncFunction (внутри сетевой запрос плейлиста).
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

/** Меняет политику на лету; применяется к следующим запросам. */
export function setConfig(patch: Partial<MediaProxyConfig>): void {
  const native = loadNative();
  if (!native) return;
  try {
    native.setConfig(patch);
  } catch {
    // политика не применилась — работаем на дефолтах натива
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

/** Состояние сегментов для полосы «на диске» в UI. */
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

// ── события ─────────────────────────────────────────────────────────────────────────────────────

function subscribe<T>(event: string, listener: (payload: T) => void): Subscription {
  const native = loadNative();
  if (!native) return { remove: () => {} };
  try {
    const subscription = native.addListener(event, (payload) => listener(payload as T));
    if (subscription && typeof subscription.remove === 'function') return subscription;
  } catch {
    // подписка не удалась — событий не будет, это не повод падать
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

/** Движок сам перечитывает манифест при зависшей загрузке — плееру перемонтироваться не нужно. */
export const onRecover = (listener: (event: RecoverEvent) => void): Subscription =>
  subscribe<RecoverEvent>('onRecover', listener);

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
