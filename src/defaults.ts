import type { MediaProxyConfig } from './types';

/**
 * Timeouts stay below the player's own request timeout (Media3 uses 8 s), so the proxy fails over
 * before playback gives up.
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
  stallKickSec: 6,
  recoveryMax: 2,
  serving: 'stream',
  debug: false,
};
