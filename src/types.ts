/** Политика прокси. Всё, что меняется из JS, живёт здесь (натив ничего не решает по таймаутам «сам»). */
export type ServingMode = 'stream' | 'complete';

export type MediaProxyConfig = {
  enabled: boolean;
  /** Единый бюджет диска; приложение передаёт свой videoCacheMB. */
  maxCacheMB: number;
  /** Меньше этого свободного места — не кэшируем (pass-through). */
  minFreeDiskMB: number;
  maxSessions: number;
  prefetchSeconds: number;
  prefetchMaxSegments: number;
  prefetchConcurrency: number;
  interactiveConcurrency: number;
  /** Ожидание соединения/заголовков для запросов плеера. Должно быть меньше, чем у плеера (Media3 8 с). */
  reqConnectSec: number;
  reqFirstByteSec: number;
  reqSilenceSec: number;
  prefetchFirstByteSec: number;
  prefetchSilenceSec: number;
  prefetchRetries: number;
  /** Пол скорости — только для prefetch/продолжений (запрос плеера не рвём по скорости). */
  minSpeedKBps: number;
  cooldownSec: number;
  prewarmSegments: number;
  /**
   * Вотчдог зависшей загрузки: через сколько секунд без прироста байт движок сам перечитывает
   * манифест (0 — выключить).
   */
  stallKickSec: number;
  /** Сколько таких киков допускается на один сегмент, прежде чем движок сдаётся плееру. */
  recoveryMax: number;
  /** stream = tee (по умолчанию), complete = сначала сегмент целиком (fallback после S3). */
  serving: ServingMode;
  debug: boolean;
};

export type ResolveOptions = {
  /** Стабильная идентичность кэша: t<id>|p-<player>|v-<voice>|e<ep>|q<quality>. */
  cacheKey?: string;
  headers?: Record<string, string>;
  /** false — прокси без диска (live/BYTERANGE/отладка). */
  cache?: boolean;
  /** Предзагрузка плейлиста и первых сегментов (по умолчанию true). */
  prewarm?: boolean;
};

export type ResolveResult = {
  streamId: string;
  url: string;
  cached: boolean;
  reason: string;
  generation: number;
};

export type StartResult = {
  port: number;
  baseUrl: string;
  generation: number;
  issues: string[];
};

export type ProxyStats = {
  port: number;
  running: boolean;
  generation: number;
  bytes: number;
  maxBytes: number;
  freeDiskBytes: number;
  streams: number;
  hits: number;
  misses: number;
  evictions: number;
  prefetched: number;
  /** Сколько раз движок сам перечитывал манифест из-за зависшей загрузки. */
  stallKicks: number;
  /** Сколько раз origin обрывал тело сегмента (TRUNCATED) — диагностика «залипшего edge». */
  originTruncated: number;
  maxSessions: number;
};

export type SegmentState = {
  index: number;
  startSec: number;
  durationSec: number;
  cachedBytes: number;
  complete: boolean;
};

export type StreamState = {
  cacheKey: string;
  cacheable: boolean;
  segments: SegmentState[];
  cachedAheadSec: number;
};

export type FailOpenResult = { streamId?: string; originUrl?: string };

/** Итог «перезапуска манифеста» на уровне прокси (сторож плеера зовёт его вместо перемонтажа). */
export type RestartResult = {
  /** Сколько сегментов поставлено в догрузку. */
  queued: number;
  /** Плейлист реально перечитан и подменён (структура сегментов не изменилась). */
  refreshed: boolean;
  /** Изменился ли хоть один URL сегментов — только тогда рестарт реально что-то поменял. */
  segmentsChanged: boolean;
  cacheable?: boolean;
  reason: string;
};

/** Движок сам заметил зависшую загрузку и перезапустил манифест (без перемонтажа плеера). */
export type RecoverEvent = {
  streamId: string;
  segmentIndex: number;
  /** Сколько секунд без прироста байт уже прошло. */
  waitedSec: number;
  /** Удалось ли перейти на свежий URL сегмента. */
  urlChanged: boolean;
};

export type Subscription = { remove(): void };

export type MediaProxyErrorCode =
  | 'NOT_STARTED'
  | 'SERVER_START_FAILED'
  | 'SERVER_RESTARTED'
  | 'INVALID_URL'
  | 'SESSION_UNKNOWN'
  | 'CONNECT_FAILED'
  | 'TLS_FAILED'
  | 'TIMEOUT_FIRST_BYTE'
  | 'TIMEOUT_SILENCE'
  | 'SLOW_STREAM'
  | 'RANGE_UNSUPPORTED'
  | 'ORIGIN_400'
  | 'ORIGIN_401'
  | 'ORIGIN_403'
  | 'ORIGIN_404'
  | 'ORIGIN_410'
  | 'ORIGIN_429'
  | 'ORIGIN_5XX'
  | 'HTML_RESPONSE'
  | 'EMPTY_RESPONSE'
  | 'TRUNCATED'
  | 'BAD_CONTENT'
  | 'BUDGET_EXHAUSTED'
  | 'DISK_FULL'
  | 'DISK_IO'
  | 'CACHE_EVICTED'
  | 'UNSUPPORTED_PLAYLIST'
  | 'NETWORK_CHANGED'
  | 'CLEARTEXT_BLOCKED'
  | 'ATS_BLOCKED';

export type ProxyErrorEvent = {
  code: MediaProxyErrorCode | string;
  streamId?: string;
  url: string;
  originStatus: number;
  attempt: number;
  retryable: boolean;
  fatal: boolean;
  message: string;
};

export type AuthRequiredEvent = {
  streamId: string;
  url: string;
  originStatus: number;
};

export type ServerStateEvent = {
  running: boolean;
  port: number;
  baseUrl: string;
  generation: number;
  reason: string;
};

export type CacheEvent = {
  type: 'evict' | 'clear' | 'full' | 'reconcile';
  bytes: number;
  maxBytes: number;
};

/** Контракт нативного модуля (то, что реализуют Kotlin и Swift). */
export type NativeMediaProxy = {
  start(): StartResult;
  resolve(origin: string, options: ResolveOptions): ResolveResult;
  release(streamId: string): void;
  failOpen(streamIdOrUrl: string): FailOpenResult | null;
  nudge(streamId: string): { queued: number };
  /** AsyncFunction в нативе: внутри сетевой перезапрос плейлиста, поэтому Promise. */
  restartStream(streamId: string): Promise<RestartResult>;
  setConfig(patch: Partial<MediaProxyConfig>): void;
  getStats(): ProxyStats;
  getStreamState(streamId: string): StreamState;
  clearCache(streamId?: string): { freedBytes: number };
  addListener(event: string, listener: (payload: unknown) => void): Subscription;
};

