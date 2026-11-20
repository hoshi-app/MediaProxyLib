# react-native-media-proxy

Локальный HLS-прокси с дисковым кэшем для React Native на iOS и Android.

[English](README.md) · **Русский**

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform: iOS / Android](https://img.shields.io/badge/platform-iOS%20%7C%20Android-lightgrey.svg)](#требования)
[![Status: experimental](https://img.shields.io/badge/status-experimental-orange.svg)](#статус)

## Зачем нужен

Нативные плееры (AVPlayer, ExoPlayer) не позволяют задать заголовки для запросов сегментов и
не дают управлять дисковым кэшем. Библиотека поднимает HTTP-сервер на `127.0.0.1` внутри
процесса приложения, ставит его между плеером и CDN и решает обе задачи одинаково на iOS и
Android:

- заголовки сессии (`User-Agent`, `Referer`, `Cookie`) уходят на плейлисты, сегменты и ключи;
- сегменты кэшируются на диске и переживают перезапуск приложения;
- после обрыва связи загрузка продолжается с уже записанных байтов.

## Требования

- Проект с Expo-модулем (`expo-modules-core`).
- iOS 15+.
- Любой плеер, принимающий обычный URL: `react-native-video`, `expo-video` и другие.
- В npm пакет не публиковался; установка из репозитория.

## Установка

```bash
npm install github:hoshi-app/MediaProxyLib
```

Добавьте config-плагин в `app.json` или `app.config.js`:

```json
{
  "expo": {
    "plugins": ["react-native-media-proxy"]
  }
}
```

Затем пересоберите нативные проекты:

```bash
npx expo prebuild --clean
```

Плагин вносит два изменения, без которых loopback-запросы блокируются системой:

- **iOS**: добавляет `NSAllowsLocalNetworking` в `Info.plist`.
- **Android**: добавляет `network_security_config`, который разрешает cleartext-трафик только
  для `127.0.0.1`. Глобальный `usesCleartextTraffic` не включается.

Если в проекте уже есть свой `android:networkSecurityConfig`, плагин не перезаписывает его и
выводит предупреждение: домен `127.0.0.1` нужно добавить вручную.

В проектах без Expo (голый React Native) плагин не применяется, оба изменения нужно внести
руками.

## Использование

Запустите прокси один раз и получайте loopback-URL перед передачей видео в плеер:

```ts
import * as MediaProxy from 'react-native-media-proxy';

await MediaProxy.start();

const source = MediaProxy.resolve('https://cdn.example.com/film/index.m3u8', {
  cacheKey: 'film42|voice-ru|ep3|q720',
  headers: {
    'User-Agent': 'Mozilla/5.0 ...',
    'Referer': 'https://example.com/',
  },
});

// Передайте source.url в плеер. Заголовки прокси отправит сам.
```

`resolve()` синхронный и не бросает исключений. Если прокси использовать нельзя, возвращается
исходный URL с `cached: false`; играйте его с теми же заголовками, и ничего не сломается.

`cached: true` означает «дисковый кэш включён», а не «сегменты уже скачаны». Что реально лежит
на диске, показывает `getStreamState()`.

Для одного и того же видео `cacheKey` должен быть стабильным. Если он меняется между
запусками, старые записи кэша не используются.

### Компонент плеера

```tsx
import { useEffect, useMemo, useState } from 'react';
import Video from 'react-native-video';
import * as MediaProxy from 'react-native-media-proxy';

export function Player({ url, headers, cacheKey }) {
  const [ready, setReady] = useState(false);

  useEffect(() => {
    MediaProxy.start().then(() => setReady(true));
  }, []);

  const source = useMemo(() => {
    const resolved = ready
      ? MediaProxy.resolve(url, { cacheKey, headers })
      : { url, cached: false };
    return { uri: resolved.cached ? resolved.url : url, headers };
  }, [ready, url, cacheKey, headers]);

  return <Video source={source} />;
}
```

### Состояние кэша

```ts
const state = MediaProxy.getStreamState(source.streamId);

state.cachedAheadSec; // сколько секунд уже есть на диске впереди
state.segments;       // по каждому сегменту: cachedBytes, complete
```

### Ошибки и восстановление

```ts
const authSub = MediaProxy.onAuthRequired((e) => {
  // 400/401/403/410: истекла подпись или сессия.
  // Получите новый URL в API приложения и перезагрузите источник.
  console.log(e.streamId, e.originStatus);
});

const errorSub = MediaProxy.onError((e) => {
  console.log(e.code, e.message, e.retryable, e.fatal);
});

MediaProxy.onRecover(() => {
  // Прокси перечитал плейлист и продолжил загрузку.
  // Перемонтировать плеер не нужно.
});

// Позже
authSub.remove();
errorSub.remove();
```

## Android: подсказка типа источника

ExoPlayer определяет контейнер по расширению последнего сегмента пути URL. У некоторых
HLS-вариантов расширения нет (`.../id/.../video/`), и ExoPlayer создаёт progressive-источник,
который падает с `UnrecognizedInputFormatException`.

Loopback-URL из `resolve()` всегда оканчивается на `.m3u8`, поэтому кэшированному
воспроизведению подсказка не нужна. Она нужна исходному URL, например при `cached: false` или
после `failOpen()`:

```tsx
const uri = resolved.cached ? resolved.url : originUrl;
const source = {
  uri,
  headers,
  ...(MediaProxy.isHlsUrl(uri) ? { type: 'm3u8' } : {}),
};
```

`isHlsUrl(url)` возвращает `true`, если у последнего сегмента пути нет расширения или это
`.m3u8`. Точки в каталогах и query-строке расширением не считаются. Проверка правила:
`npm run check:hls`.

## API

### Методы

| Метод | Описание |
|---|---|
| `start()` | Поднимает сервер один раз за процесс. Возвращает порт, базовый URL и проблемы запуска. |
| `resolve(url, options?)` | Создаёт сессию и возвращает loopback-URL. Синхронный, не бросает. |
| `release(streamId)` | Освобождает сессию и отменяет предзагрузку. Данные на диске остаются. |
| `failOpen(streamIdOrUrl)` | Переключает сессию обратно на исходный URL. |
| `nudge(streamId)` | Ставит окно предзагрузки от последнего запрошенного сегмента. |
| `restart(streamId)` | Перечитывает плейлист и заново ставит загрузку без перемонтирования плеера. |
| `setConfig(patch)` | Меняет настройки на ходу. |
| `getStats()` | Порт, объём кэша, попадания, промахи, восстановления. |
| `getStreamState(streamId)` | Состояние кэша по сегментам. |
| `clearCache(streamId?)` | Очищает весь кэш или кэш одного потока. |
| `isAvailable()` | Загружен ли нативный модуль. |
| `getDefaultConfig()` | Копия конфигурации по умолчанию. |

Опции `resolve()`:

| Опция | Описание |
|---|---|
| `cacheKey` | Стабильный ключ кэша. Не должен меняться между запусками. |
| `headers` | Заголовки для плейлистов, сегментов и ключей. |
| `cache` | `false` отключает дисковый кэш для этого потока (live, отладка). |
| `prewarm` | Предзагрузка плейлиста и первых сегментов. По умолчанию `true`. |

### События

| Событие | Что приходит |
|---|---|
| `onError` | Код ошибки, статус источника, сообщение, флаги `retryable`/`fatal`. |
| `onAuthRequired` | Источник вернул 400/401/403/410; нужен новый URL. |
| `onServerState` | Сервер запущен, перезапущен или остановлен. |
| `onCache` | Вытеснение, очистка, заполнение или сверка с диском. |
| `onRecover` | Зависшая загрузка восстановлена без перемонтирования плеера. |

## Настройки

`setConfig(patch)` применяет частичное обновление, неизвестные ключи игнорируются. Полный тип
— в [src/types.ts](src/types.ts).

Основные настройки:

| Параметр | По умолчанию | Описание |
|---|---|---|
| `enabled` | `true` | Включает прокси. |
| `maxCacheMB` | `512` | Общий лимит кэша на диске для всех видео. |
| `minFreeDiskMB` | `2048` | Если свободного места меньше, кэширование отключается. |
| `maxSessions` | `4` | Число одновременных сессий. |
| `prefetchSeconds` | `30` | Глубина предзагрузки в секундах. |
| `prefetchMaxSegments` | `6` | Предел сегментов в окне предзагрузки. |
| `prewarmSegments` | `2` | Сегменты, загружаемые сразу после `resolve()`. |
| `stallKickSec` | `6` | Таймаут без прироста байтов, после которого движок перечитывает плейлист. |
| `recoveryMax` | `2` | Попыток восстановления на сегмент. |
| `debug` | `false` | Логи и заголовок `X-MediaProxy-Cache`. |

Дополнительные настройки:

| Параметр | По умолчанию | Описание |
|---|---|---|
| `prefetchConcurrency` | `2` | Одновременных загрузок в окне предзагрузки. |
| `interactiveConcurrency` | `4` | Параллельных запросов плеера. |
| `prefetchRetries` | `3` | Повторы для предзагрузки. |
| `prefetchFirstByteSec` | `10` | Таймаут первого байта для предзагрузки. |
| `prefetchSilenceSec` | `15` | Таймаут простоя для предзагрузки. |
| `reqConnectSec` | `5` | Таймаут соединения для запросов плеера. |
| `reqFirstByteSec` | `5` | Таймаут первого байта для запросов плеера. |
| `reqSilenceSec` | `4` | Таймаут простоя для запросов плеера. |
| `minSpeedKBps` | `1` | Минимальная скорость для предзагрузки и докачки. |
| `cooldownSec` | `30` | Пауза перед повтором упавшего сегмента. |
| `serving` | `'stream'` | `'stream'` отдаёт байты по мере получения; `'complete'` сначала скачивает сегмент целиком. |

Таймауты специально короче таймаутов плеера. Иначе плеер сдаётся раньше, чем прокси успевает
восстановиться.

## Как это работает

1. `start()` занимает эфемерный порт на `127.0.0.1` и поднимает HTTP-сервер.
2. `resolve(url, options)` создаёт сессию и возвращает loopback-URL для неё.
3. Плеер запрашивает по этому адресу плейлисты и сегменты.
4. Прокси скачивает плейлист, переписывает ссылки на сегменты в loopback-адреса и кэширует
   сегменты на диске.
5. Запросы с `Range` отдаются из уже записанных байтов, остаток догружается.
6. При следующем воспроизведении кэшированные сегменты отдаются без обращения к CDN.

```text
AVPlayer / ExoPlayer
        │
        ▼
http://127.0.0.1:<port>/s/<sid>/<b64>/<name>.<ext>
        │
        ▼
   нативный прокси  ──►  CDN
        │
        ▼
   дисковый кэш
```

Ключи AES-128 хранятся только в памяти и на диск не записываются.

## Кэш

- Каталог: `<кэш приложения>/mediaproxy/v1/<хеш cacheKey>/`.
- Лимит: `maxCacheMB` на все видео суммарно.
- Вытеснение идёт целыми записями, а не отдельными сегментами: эпизод кэшируется как единое
  целое.
- При 90 % лимита предзагрузка останавливается. При 100 % новые записи не создаются, но
  отдача продолжается.
- При запуске журнал сверяется с файлами на диске: недокачанные файлы подрезаются, лишние
  удаляются.
- `clearCache()` очищает всё; `clearCache(streamId)` — только один поток.

## Отладка

```ts
MediaProxy.setConfig({ debug: true });
```

1. В логах видны созданные ссылки, источник байтов и ошибки. Подписи и токены вырезаются.
2. Ответы содержат `X-MediaProxy-Cache: hit | miss | partial | passthrough`, если debug включён.
3. `getStats()` показывает занятое место, попадания, промахи и число автовосстановлений.

При повторном просмотре того же ролика `hits` растут, а `misses` — нет.

## Ограничения

- DRM (FairPlay, Widevine) не поддерживается.
- Live и LL-HLS проксируются без кэширования.
- AirPlay с loopback-URL не работает.
- ABR не реализован: прокси отдаёт то, что запросил плеер.
- Собственного foreground-сервиса на Android нет; фон обеспечивает сервис плеера.
- Кэш локальный для приложения и не общий между устройствами.

## Статус

Экспериментальный проект. Для Android-ядра (Kotlin) есть автотест, TypeScript-слой проходит
проверку типов, iOS собирается. Android-библиотека целиком внутри host-приложения ещё не
собиралась. CI нет, замеров производительности на реальных роликах не проводилось.

## Документация

- [docs/PROTOCOL.md](docs/PROTOCOL.md): контракт поведения iOS и Android.
- [docs/PLAN.md](docs/PLAN.md): обоснование решений и компромиссов.

## Лицензия

[MIT](LICENSE).
