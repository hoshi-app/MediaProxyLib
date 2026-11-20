// ponytail: один runnable check на правило «какой контейнер объявить плееру» (без сети и сборки).
// Ошибка здесь = Android снова строит ProgressiveMediaSource на варианте CVH и падает
// `UnrecognizedInputFormatException`.
// Запуск: node tools/check-hls-url.mjs
import { isHlsUrl } from '../src/hls.ts';

let fails = 0;
const check = (name, url, want) => {
  const got = isHlsUrl(url);
  const ok = got === want;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${ok ? '' : `\n      ${url}\n      got ${got}, want ${want}`}`);
  if (!ok) fails++;
};

// вариант CVH: последний сегмент пути — "video", расширения нет
const CVH = 'https://vd537.okcdn.ru/expires/1794824445869/id/12704853134064/video/';
check('CVH-вариант без расширения -> HLS', CVH, true);
check('CVH-вариант без завершающего слэша -> HLS', CVH.slice(0, -1), true);
check('CVH-вариант с query -> HLS', `${CVH}?x=1&y=2`, true);
check('master m3u8 -> HLS', 'https://vd537.okcdn.ru/video.m3u8?cmd=videoPlayerCdn&sig=x', true);
check('локальный офлайн-плейлист -> HLS', 'http://127.0.0.1:38965/t1/e12/local.m3u8', true);
// прогрессив: подсказку не ставим, иначе HLS-парсер получит mp4
check('Kodik mp4 -> без подсказки', 'https://kodik.example.com/serial/1/720.mp4', false);
check('mp4 с query -> без подсказки', 'https://cdn.example.com/v/720.mp4?token=x.y.z', false);
check('Kodik hls-манифест -> HLS', 'https://kodik.example.com/v/720.mp4:hls:manifest.m3u8', true);
check('точка в каталоге не считается расширением', 'https://cdn.example.com/a.b/video/', true);
check('пустая строка -> HLS (плеер всё равно скажет своё)', '', true);

console.log(fails ? `\n${fails} FAIL` : '\nвсе проверки прошли');
process.exit(fails ? 1 : 0);
