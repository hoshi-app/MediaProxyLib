/**
 * Подсказка контейнера нативному плееру (`source.type` у react-native-video).
 *
 * ExoPlayer (Android) определяет тип по расширению последнего сегмента URL (`Util.inferContentType`)
 * и без подсказки строит ProgressiveMediaSource — для варианта CVH без расширения
 * (`…/id/…/video/`) это `UnrecognizedInputFormatException`. AVPlayer (iOS) смотрит на тело ответа,
 * подсказка ему не нужна и не мешает.
 *
 * Loopback-URL из `resolve()` всегда оканчивается на `.m3u8` (см. docs/PROTOCOL.md §1), подсказка
 * нужна для исходного URL: `cached: false`, `failOpen()` и прямая игра мимо прокси.
 *
 * Правило: нет расширения или `.m3u8` → HLS. Расширение ищем только в последнем сегменте пути:
 * точки в каталогах и в query расширением не являются.
 */
export function isHlsUrl(url: string): boolean {
  const path = String(url ?? '').replace(/[?#].*$/, '');
  if (/\.m3u8$/i.test(path)) return true;
  const last = path.slice(path.lastIndexOf('/') + 1);
  return !/\.[a-z0-9]{2,5}$/i.test(last);
}
