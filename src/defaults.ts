import type { MediaProxyConfig } from './types';

/**
 * Дефолты согласованы с планом (§6.2): таймауты короче, чем у плеера (Media3 8 с), лимит — один
 * владелец бюджета (512 МБ), prefetch — 30 с / 6 сегментов / 2 параллельно.
 */
export const DEFAULT_CONFIG: MediaProxyConfig = {
  enabled: true,
  maxCacheMB: 512,
  minFreeDiskMB: 2048,
  maxSessions: 4,
  prefetchSeconds: 30,
  prefetchMaxSegments: 6,
  prefetchConcurrency: 2,
  interactiveConcurrency: 4,
  reqConnectSec: 5,
  reqFirstByteSec: 5,
  reqSilenceSec: 4,
  prefetchFirstByteSec: 10,
  prefetchSilenceSec: 15,
  prefetchRetries: 3,
  minSpeedKBps: 1,
  cooldownSec: 30,
  prewarmSegments: 2,
  serving: 'stream',
  debug: false,
};
