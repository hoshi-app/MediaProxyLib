/**
 * Config plugin плеера-прокси.
 *
 * iOS: ATS-исключение для loopback (AVPlayer ходит на http://127.0.0.1).
 * Android: network-security-config, разрешающий cleartext **только** для 127.0.0.1
 * (глобальный `usesCleartextTraffic` намеренно не трогаем: он открыл бы весь HTTP приложения).
 *
 * Оба шага идемпотентны и не перетирают настройки приложения: если в манифесте уже объявлен
 * свой `android:networkSecurityConfig` или в res/xml лежит одноимённый файл — плагин отступает
 * и пишет предупреждение, чтобы домен 127.0.0.1 туда добавили руками.
 */
const XML_NAME = 'network_security_config';
const XML_BODY = `<?xml version="1.0" encoding="utf-8"?>
<!-- Сгенерировано react-native-media-proxy: cleartext разрешён только для loopback. -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
`;

let plugins;
try {
  plugins = require('expo/config-plugins');
} catch {
  plugins = null;
}

function withIosAts(config) {
  return plugins.withInfoPlist(config, (cfg) => {
    const ats = cfg.modResults.NSAppTransportSecurity || {};
    cfg.modResults.NSAppTransportSecurity = {
      ...ats,
      NSAllowsLocalNetworking: true,
    };
    return cfg;
  });
}

function withAndroidSecurityConfigFile(config) {
  return plugins.withDangerousMod(config, [
    'android',
    (cfg) => {
      const fs = require('fs');
      const path = require('path');
      const dir = path.join(cfg.modRequest.platformProjectRoot, 'app', 'src', 'main', 'res', 'xml');
      const file = path.join(dir, `${XML_NAME}.xml`);
      fs.mkdirSync(dir, { recursive: true });
      if (fs.existsSync(file)) {
        console.warn(
          `[media-proxy] ${file} уже существует — добавьте в него domain-config для 127.0.0.1 ` +
            'с cleartextTrafficPermitted="true", иначе плеер не сможет ходить в loopback.',
        );
        return cfg;
      }
      fs.writeFileSync(file, XML_BODY, 'utf8');
      return cfg;
    },
  ]);
}

function withAndroidNetworkSecurity(config) {
  return plugins.withAndroidManifest(config, (cfg) => {
    const manifest = cfg.modResults && cfg.modResults.manifest;
    const application = manifest && manifest.application && manifest.application[0];
    if (!application) return cfg;
    application.$ = application.$ || {};
    const existing = application.$['android:networkSecurityConfig'];
    if (existing) {
      if (existing !== '@xml/' + XML_NAME) {
        console.warn(
          `[media-proxy] в манифесте уже есть android:networkSecurityConfig=${existing}; ` +
            'добавьте в него домен 127.0.0.1, иначе loopback-прокси будет заблокирован.',
        );
      }
      return cfg;
    }
    application.$['android:networkSecurityConfig'] = '@xml/' + XML_NAME;
    return cfg;
  });
}

function mediaProxyPlugin(config) {
  if (!plugins) return config;
  let result = withIosAts(config);
  result = withAndroidSecurityConfigFile(result);
  result = withAndroidNetworkSecurity(result);
  return result;
}

module.exports = mediaProxyPlugin;
