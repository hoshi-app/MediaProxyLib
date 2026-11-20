/** Proxy policy. Every JS-tunable setting lives here; native code never picks timeout values itself. */
export type ServingMode = 'stream' | 'complete';

export type MediaProxyConfig = {
  enabled: boolean;
  /** Single disk budget shared by all sessions; the app passes its own video cache size. */
  maxCacheMB: number;
  /** Below this much free disk space nothing is cached (pass-through). */
  minFreeDiskMB: number;
  maxSessions: number;
  prefetchSeconds: number;
  prefetchMaxSegments: number;
  prefetchConcurrency: number;
  interactiveConcurrency: number;
  /** Connect and header wait for player requests; keep below the player's own timeout (Media3 8 s). */
  reqConnectSec: number;
  reqFirstByteSec: number;
  reqSilenceSec: number;
  prefetchFirstByteSec: number;
  prefetchSilenceSec: number;
  prefetchRetries: number;
  /** Speed floor applies to prefetch and resumed downloads only; player requests are never aborted. */
  minSpeedKBps: number;
  cooldownSec: number;
  prewarmSegments: number;
  /**
   * Stall detector: seconds without byte progress before the engine re-reads the media playlist
   * itself (0 disables it).
   */
  stallKickSec: number;
  /** Recovery attempts allowed per segment before the engine gives up and leaves it to the player. */
  recoveryMax: number;
  /** stream = tee (default); complete = fetch the whole segment first (fallback after S3). */
  serving: ServingMode;
  debug: boolean;
};

export type ResolveOptions = {
  /** Stable cache identity: t<id>|p-<player>|v-<voice>|e<ep>|q<quality>. */
  cacheKey?: string;
  headers?: Record<string, string>;
  /** false serves without disk cache (live, BYTERANGE, debugging). */
  cache?: boolean;
  /** Prefetch the media playlist and first segments (default true). */
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
  /** How often the engine re-read the media playlist after a stalled download. */
  stallKicks: number;
  /** How often the origin cut the segment body short (TRUNCATED); hints at a stuck edge. */
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

/**
 * Outcome of a media playlist restart at the proxy level. The player's stall detector calls it
 * instead of remounting the player.
 */
export type RestartResult = {
  /** Segments queued for download. */
  queued: number;
  /** The playlist was really re-read and swapped in, with the same segment structure. */
  refreshed: boolean;
  /** Whether any segment URL changed; only then did the restart change anything. */
  segmentsChanged: boolean;
  cacheable?: boolean;
  reason: string;
};

/** The engine noticed a stalled download and restarted the media playlist without a player remount. */
export type RecoverEvent = {
  streamId: string;
  segmentIndex: number;
  /** Seconds already spent without byte progress. */
  waitedSec: number;
  /** Whether switching to a fresh segment URL succeeded. */
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

/** Contract of the native module implemented by Kotlin and Swift. */
export type NativeMediaProxy = {
  start(): StartResult;
  resolve(origin: string, options: ResolveOptions): ResolveResult;
  release(streamId: string): void;
  failOpen(streamIdOrUrl: string): FailOpenResult | null;
  nudge(streamId: string): { queued: number };
  /** AsyncFunction in native: it re-fetches the playlist over the network, hence the Promise. */
  restartStream(streamId: string): Promise<RestartResult>;
  setConfig(patch: Partial<MediaProxyConfig>): void;
  getStats(): ProxyStats;
  getStreamState(streamId: string): StreamState;
  clearCache(streamId?: string): { freedBytes: number };
  addListener(event: string, listener: (payload: unknown) => void): Subscription;
};

