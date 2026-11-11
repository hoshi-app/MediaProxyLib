/**
 * Config plugin: ATS-исключение для loopback (AVPlayer ходит на http://127.0.0.1) и напоминание
 * про Android cleartext. Android-часть намеренно не трогает глобальный usesCleartextTraffic:
 * правильный путь — network-security-config приложения с разрешением только для 127.0.0.1.
 */
let withInfoPlist;
try {
  ({ withInfoPlist } = require('expo/config-plugins'));
} catch {
  withInfoPlist = null;
}

function mediaProxyPlugin(config) {
  if (!withInfoPlist) return config;
  return withInfoPlist(config, (cfg) => {
    const ats = cfg.modResults.NSAppTransportSecurity || {};
    cfg.modResults.NSAppTransportSecurity = {
      ...ats,
      NSAllowsLocalNetworking: true,
    };
    return cfg;
  });
}

module.exports = mediaProxyPlugin;
