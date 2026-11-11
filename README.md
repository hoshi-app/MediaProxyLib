# MediaProxyLib

Лёгкий HLS-прокси для React Native (iOS + Android): локальный HTTP-сервер, перезапись плейлистов,
дисковый кэш сегментов с Range, prefetch, ретраи и события в JS.

План и все решения — в [docs/PLAN.md](docs/PLAN.md).

## Статус

| Часть | Состояние |
|---|---|
| План | утверждён, `docs/PLAN.md` |
| Протокол | `docs/PROTOCOL.md` — общий контракт Swift/Kotlin/TS |
| Swift-ядро (`ios/`) | написано, на этой машине не собиралось (нужен macOS) |
| Kotlin-ядро (`android/`) | собирается и проверено локально: **253 проверки зелёные** |
| TS-оболочка (`src/`, `app.plugin.js`) | typecheck зелёный |
| Android-библиотека (AGP/Expo) | не собиралась: нужен host-проект Expo и Maven |

## Сборка и проверка без сети

Maven в этой среде недоступен, поэтому ядро и тесты идут кэшированным Kotlin-компилятором:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build-core.ps1
```

Скрипт компилирует `android/src/main/java/tv/mediaproxy/core`, затем тесты и запускает
`tv.mediaproxy.core.SelfTestKt` (assert-раннер, exit code 1 при провале). Покрыто: Range/206/416,
перезапись HLS (master/media, MAP/KEY/MEDIA, AES-128, BYTERANGE, live, LL-HLS), журнал и реконсиляция
кэша, эвикция, HTTP-сервер (keep-alive, HEAD, потоки, 8 параллельных), origin-клиент (206-докачка,
HTML, 403/5xx, обрыв, тишина, пол скорости), сквозной прокси (заголовки, кэш-хит, перерезолв с новым
токеном, префетч, clearCache, restart, fail-open, авторизация, cooldown).

TypeScript:

```powershell
node C:\dev\AnimeApp\app\node_modules\typescript\bin\tsc -p tsconfig.json
```

Внутри Expo-приложения используется обычный Gradle-модуль `android/` (AGP + expo-module-gradle-plugin);
эта сборка требует host-проект и доступ к Maven, поэтому здесь не запускалась.

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

