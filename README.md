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
const { url, cached } = MediaProxy.resolve(origin, {
  cacheKey: 't28825|p-cvh|e2|q720',
  headers: { 'User-Agent': '...' },
});
// url = http://127.0.0.1:<port>/s/<sid>/<b64>/<name>.m3u8
```
