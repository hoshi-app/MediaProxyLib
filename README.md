# MediaProxyLib

Лёгкий HLS-прокси для React Native (iOS + Android): локальный HTTP-сервер, перезапись плейлистов,
дисковый кэш сегментов с Range, prefetch, ретраи и события в JS.

План и все решения — в [docs/PLAN.md](docs/PLAN.md), общий контракт Swift/Kotlin/TS —
[docs/PROTOCOL.md](docs/PROTOCOL.md).

## Статус

| Часть | Состояние |
|---|---|
| План | утверждён, `docs/PLAN.md` |
| Протокол | `docs/PROTOCOL.md` — общий контракт Swift/Kotlin/TS (обновлён после ревью) |
| Kotlin-ядро (`android/`) | собирается и проверено локально: **281 проверка зелёная** |
| Swift-ядро (`ios/`) | написано; **сборка на macOS не прогонялась** — считается непроверенным |
| TS-оболочка (`src/`, `app.plugin.js`) | typecheck зелёный |
| Android-библиотека (AGP/Expo) | не собиралась: нужен host-проект Expo и Maven |

> **Что было исправлено после адверсариального ревью (P0).** Докачка сегмента между запросами
> плеера (префикс на диске больше не затирается), корректный `Content-Range` в pass-through,
> сверка отпечатка плейлиста в журнале при старте, `clearCache(неизвестный id)` больше не чистит
> весь кэш, преварм только для кэшируемого VOD, TTL для live-плейлистов, нативные ретраи только для
> prefetch/продолжений, один `onError` на сбой, `evictions` считается, пул загрузок iOS больше не
> последовательный, iOS: cookie-jar, `onAuthRequired.originStatus`, отсутствие крашей на nil,
> config plugin ставит Android network-security-config для 127.0.0.1. Остались пункты P1/P2 из
> плана ревью (метрики `onCache`, §6.5 смена сети, `serving`/буфер, CI и Gradle-тесты).

## Сборка и проверка без сети

Maven в этой среде недоступен, поэтому ядро и тесты идут кэшированным Kotlin-компилятором
(нужны JDK 17 и прогретый `~/.gradle/caches`; версии jar зафиксированы в `tools/build-core.ps1`):

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
// source.cached — «кэш на диске включён», а НЕ «сегменты уже загружены»;
// прогретое смотрите в MediaProxy.getStreamState(streamId).cachedAheadSec.
// fail-open: source.url === origin или пустой streamId → играйте origin с теми же headers.

const off = MediaProxy.onAuthRequired((e) => {
  // 400/401/403/410: перерезолвить поток и перемонтировать плеер
});
```

Методы: `start`, `resolve`, `release`, `failOpen`, `nudge`, `setConfig`, `getStats`,
`getStreamState`, `clearCache`; события: `onError`, `onAuthRequired`, `onServerState`, `onCache`.
Плейлисты, Range, prefetch, лимит кэша и вытеснение — нативно; JS только задаёт политику и
разбирает ошибки.

