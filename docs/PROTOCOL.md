# MediaProxy: протокол и общие алгоритмы (Swift ↔ Kotlin ↔ TS)

Документ фиксирует контракт, который обязаны реализовать обе нативные платформы
одинаково. Любое расхождение здесь — баг паритета.

## 1. Loopback-URL

```
http://127.0.0.1:<port>/s/<sid>/<b64url(origin-absolute-url)>/<name>.<ext>
```

- `<port>` — фактический порт listener'а (эфемерный, `bind(127.0.0.1:0)`), на всё время процесса.
- `<sid>` — 128-бит hex (принадлежит сессии `resolve`).
- `<b64url(...)>` — URL-safe base64 без padding (алфавит `A-Za-z0-9-_`).
- `<name>.<ext>` — расширение обязательно: плейлист всегда `.m3u8`, сегмент — исходное или
  `.ts`/`.m4s`, ключ — `.key`, init-сегмент `EXT-X-MAP` — исходное/`.m4s`/`.mp4`.
- Роутинг: `parts = path.split("/")` → `["s", sid, b64, name.ext]`. Всё остальное — 404.

## 2. HTTP-контракт

| Запрос | Ответ |
|---|---|
| `GET` полный | 200, `Content-Length`, `Content-Type`, `Accept-Ranges: bytes` |
| `GET` + `Range: bytes=a-b`, `a-`, `-n` | 206, `Content-Range: bytes a-b/total` |
| `GET` + невалидный Range | 416, `Content-Range: bytes */total` |
| `GET` + несколько диапазонов | 200 целиком (игнор) |
| `HEAD` | те же заголовки, без тела |
| `Range` на плейлист | 206 по тексту из памяти |
| неизвестная длина | 200, без `Content-Length`, `Connection: close` (close-delimited) |
| ошибка origin | 502/504/503 по таблице плана §4.2 (никогда не 4xx) |

Обязательные заголовки ответа: `Content-Type` (по расширению/оригиналу), `Content-Length` (если
известна), `Accept-Ranges`, `Cache-Control: no-store` для плейлистов/ключей, `Connection`
(`keep-alive` по умолчанию, `close` для close-delimited).

`X-MediaProxy-Cache: hit|miss|partial|passthrough` — только в debug.

## 3. Идентичность и кэш

- `cacheKey` приходит из JS и стабилен для (тайтл, серия, озвучка, качество).
- Каталог: `<Caches>/mediaproxy/v1/<fnv1a64(cacheKey)>/`.
- Отпечаток плейлиста: `fnv1a64("n=<segments>|td=<target>|f=<path первого>|l=<path последнего>|d=<сумма
  длительностей>|v=<вариантов>|map=<путь init>")`.
- Журнал `journal.log`, append-only:
  - `H|1|<cacheKey>|<fingerprint>`
  - `S|<index>|<total>|<prefix>|<complete 0|1>|<contentType>|<accessedAt>|<index>`
- При открытии стрима: шапка несовпала по отпечатку → каталог удаляется; файл `s%05d.bin`:
  - нет файла → запись удаляется;
  - `size < prefix` → `prefix = size`;
  - `size > prefix` → `truncate(prefix)`;
  - `complete && prefix != total` → `complete = false`;
  - `.bin` без записи → удалить; журнал переписывается (компакция).
- Вытеснение: только целыми стримами, LRU по `lastAccess`; защищённые (`refs > 0`, активный
  `cacheKey`, карантин) не трогаем; soft limit 90 % — стоп prefetch, hard 100 % — стоп новым записям.

## 4. Загрузка сегмента

1. Ключ `(cacheKey, segmentIndex)`; single-flight на ключ.
2. `feed.written == 0` → `Range` не шлём; `written > 0` → `Range: bytes=<written>-` + `If-Range`.
3. `Accept-Encoding: identity`; прочие заголовки — только из сессии (hop-by-hop и `Range` — свои).
4. Валидация ответа:
   - 200/206; `200` при запрошенном `Range>0` → ошибка `RANGE_UNSUPPORTED` (движок решает: сброс/повтор);
   - `text/html` → `HTML_RESPONSE`, не кэшировать;
   - `Content-Range` для 206 обязателен, `total` = значение после `/`;
   - `Content-Length` и фактический объём в конце: не совпало → `TRUNCATED`.
5. Тело пишется в файл; `feed.written` растёт; читатели ждут на condition (не EOF).
6. Терминальные состояния: `complete` (written == total), `failed` (код + `failedUntil`), `truncated`
   (ретрай с `Range`), `rangeUnsupported` (сброс записи).
7. Ретраи: только prefetch и продолжение с ненулевым префиксом; 3 попытки, паузы 0.5/1.5/4 с,
   cooldown 30 с на сегмент.

## 5. Заголовки сессии

- Источник — `resolve().headers`, больше ниоткуда.
- Снимаем: `Host`, `Connection`, `Content-Length`, `Range`, `Accept-Encoding`; ставим
  `Accept-Encoding: identity`.
- `Cookie`: явный заголовок сессии + per-session jar из `Set-Cookie` (iOS
  `httpShouldSetCookies=false`, `httpCookieStorage=nil`).
- `Origin` не добавляем, `User-Agent` не подменяем.

## 6. Коды ошибок

См. план, приложение B. Обязательные к реализации в v1: `INVALID_URL`, `SESSION_UNKNOWN`,
`CONNECT_FAILED`, `TIMEOUT_FIRST_BYTE`, `TIMEOUT_SILENCE`, `SLOW_STREAM`, `RANGE_UNSUPPORTED`,
`ORIGIN_400/401/403/404/410/429/5XX`, `HTML_RESPONSE`, `EMPTY_RESPONSE`, `TRUNCATED`, `BAD_CONTENT`,
`BUDGET_EXHAUSTED`, `DISK_FULL`, `DISK_IO`, `NETWORK_CHANGED`, `CLEARTEXT_BLOCKED`, `ATS_BLOCKED`.

## 7. Сессии

- `resolve` синхронный, никогда не бросает; при недоступном сервере возвращает
  `{url: origin, cached: false, reason: "NOT_STARTED"}`.
- Активных сессий ≤ `maxSessions` (LRU по `lastAccess`).
- `release` отменяет prefetch этой сессии, снимает refcount, но не удаляет кэш.
- `failOpen` помечает сессию `stale`: новые запросы её URL → 410, JS монтирует origin напрямую.
