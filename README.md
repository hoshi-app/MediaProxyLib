# MediaProxyLib

Лёгкий HLS-прокси для React Native (iOS + Android): локальный HTTP-сервер, перезапись плейлистов,
дисковый кэш сегментов с Range, prefetch, ретраи и события в JS.

План и все решения — в [docs/PLAN.md](docs/PLAN.md).

## Статус

| Часть | Состояние |
|---|---|
| План | утверждён, `docs/PLAN.md` |
| Swift-ядро (`ios/`) | пишется, на этой машине не собирается (нужен macOS) |
| Kotlin-ядро (`android/`) | пишется и собирается локально (JDK 17 + Android SDK, офлайн) |
| TS-оболочка (`src/`, `app.plugin.js`) | пишется, typecheck локально |

## Сборка Android без сети

Maven в этой среде недоступен, поэтому проверка ядра идёт кэшированным Kotlin-компилятором:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build-core.ps1
```

Скрипт компилирует `android/src/main/java/tv/mediaproxy/core` и тесты, затем запускает
`tv.mediaproxy.SelfTestKt` (assert-раннер, exit code 1 при провале). Для сборки внутри Expo-приложения
используется обычный Gradle-модуль `android/`.

## Быстрый контракт

```ts
import * as MediaProxy from 'react-native-media-proxy';

await MediaProxy.start();                       // один раз на процесс
MediaProxy.setConfig({ maxCacheMB: 512 });      // политика — из JS

const source = MediaProxy.resolve(origin, {
  cacheKey: 't28825|p-cvh|e2|q720',             // стабильная идентичность кэша
  headers: { 'User-Agent': '...' },             // уйдут на master/вариант/сегменты
});
// source.url = http://127.0.0.1:<port>/s/<sid>/<b64>/<name>.m3u8
// source.cached === false → играем origin (fail-open), ничего не сломано

const off = MediaProxy.onAuthRequired((e) => {
  // 400/401/403/410: перерезолвить поток и перемонтировать плеер
});
```

Методы: `start`, `resolve`, `release`, `failOpen`, `nudge`, `setConfig`, `getStats`,
`getStreamState`, `clearCache`; события: `onError`, `onAuthRequired`, `onServerState`, `onCache`.
Плейлисты, Range, prefetch, лимит кэша и вытеснение — нативно; JS только задаёт политику и
разбирает ошибки.

