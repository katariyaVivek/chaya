// The window's export rules (ui/export.js): what the Export dialog offers first, its choices kept consistent, the
// options the app is asked with, and the words for how an export went.
// Run with: node --test test/export.test.mjs, in desktop/app

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  firstChoice,
  changed,
  toggledSrt,
  clipIds,
  optionsOf,
  keptOf,
  contractStyle,
  exportLabel,
  whereNote,
  sampleWords,
  encoderWords,
  progressWords,
  noticeOf,
  baseName,
  keyOf,
  isRunning,
} from '../ui/export.js';

const clips = [
  { id: 1, name: 'Counting the walk', start: 5000, end: 20000 },
  { id: 2, name: 'Into the park', start: 1000, end: 8000 },
  { id: 3, name: 'The end of the walk', start: 25000, end: 29000 },
];
const styles = {
  en: { color: 'yellow', size: 'L', font: 'Serif' },
  fr: { color: 'white', size: 'M', font: 'Sans' },
  '': { color: 'pink', size: 'XL', font: 'Mono' },
};
const styleOf = (key) => styles[key] ?? { color: 'white', size: 'M', font: 'Sans' };

test('with a clip chosen on the timeline, the dialog offers that clip; with none, all of them', () => {
  assert.equal(firstChoice({ clips, cut: 2 }).which, 'chosen');
  assert.equal(firstChoice({ clips, cut: null }).which, 'all');
  assert.equal(firstChoice({ clips, cut: 9 }).which, 'all', 'a clip since removed');
  assert.deepEqual(clipIds({ which: 'chosen' }, clips, 2), [2]);
  assert.deepEqual(clipIds({ which: 'all' }, clips, 2), [1, 2, 3]);
  assert.deepEqual(clipIds({ which: 'chosen' }, clips, 9), [1, 2, 3], 'nothing left chosen: all');
});

test('the subtitles offered first are those the player shows', () => {
  const one = firstChoice({ clips, tracks: ['en', 'fr'], shown: 'en' });
  assert.equal(one.subtitles, 'one');
  assert.equal(one.first, 'en');
  assert.equal(one.second, 'fr', 'ready for Two languages');

  const two = firstChoice({ clips, tracks: ['en', 'fr'], shown: 'fr', second: 'en', top: 'second' });
  assert.deepEqual([two.subtitles, two.first, two.second, two.secondOnTop], ['two', 'fr', 'en', true]);
  assert.equal(firstChoice({ clips, tracks: ['en', 'fr'], shown: 'fr', second: 'en', top: 'first' }).secondOnTop, false);

  assert.equal(firstChoice({ clips, tracks: ['en'], shown: 'en', captions: false }).subtitles, 'none', 'the line hidden');
  assert.equal(firstChoice({ clips, tracks: [], shown: null }).subtitles, 'none', 'a video without subtitles');
  assert.equal(firstChoice({ clips, tracks: ['en'], shown: 'en', kept: { subtitles: 'none' } }).subtitles, 'none', 'None, chosen last time');
  assert.equal(firstChoice({ clips, tracks: ['en'], shown: 'en', kept: { subtitles: 'some' } }).subtitles, 'one');
});

test('the save, look and quality chosen last time come back; anything else is the default', () => {
  const kept = { saveAs: 'srt', look: 'large', quality: 'p720' };
  const choice = firstChoice({ clips, tracks: ['en'], shown: 'en', kept });
  assert.deepEqual([choice.saveAs, choice.look, choice.quality], ['srt', 'large', 'p720']);
  const plain = firstChoice({ clips, tracks: ['en'], shown: 'en', kept: { look: 'neon', quality: '4k' } });
  assert.deepEqual([plain.saveAs, plain.look, plain.quality], ['video', 'outline', 'p1080']);
  assert.equal(firstChoice({ clips, tracks: [], kept }).saveAs, 'video', 'no subtitles to save as SRT');
  assert.deepEqual(keptOf({ ...choice, subtitles: 'two' }), { saveAs: 'srt', subtitles: 'some', look: 'large', quality: 'p720' });
});

test('SRT offers the languages shown, and keeps one at least', () => {
  const tracks = ['en', 'fr', 'de'];
  let choice = firstChoice({ clips, tracks, shown: 'fr', second: 'en' });
  assert.deepEqual(choice.srt, ['fr', 'en']);
  choice = toggledSrt(choice, 'de', tracks);
  assert.deepEqual(choice.srt, ['en', 'fr', 'de'], 'in the video’s order');
  choice = toggledSrt(toggledSrt(choice, 'en', tracks), 'fr', tracks);
  assert.deepEqual(choice.srt, ['de']);
  assert.deepEqual(toggledSrt(choice, 'de', tracks).srt, ['de'], 'the last one stays');
  assert.deepEqual(firstChoice({ clips, tracks, shown: null }).srt, ['en'], 'none shown: the first');
});

test('choosing languages keeps two different ones, and Two languages needs two', () => {
  const tracks = ['en', 'fr', 'de'];
  let choice = firstChoice({ clips, tracks, shown: 'en', second: 'fr' });
  choice = changed(choice, { first: 'fr' }, tracks);
  assert.deepEqual([choice.first, choice.second], ['fr', 'en'], 'the main one taken from the second: they swap');
  choice = changed(choice, { second: 'de' }, tracks);
  assert.deepEqual([choice.first, choice.second], ['fr', 'de']);
  choice = changed(choice, { second: 'fr' }, tracks);
  assert.notEqual(choice.second, choice.first);
  const single = firstChoice({ clips, tracks: ['en'], shown: 'en' });
  assert.equal(changed(single, { subtitles: 'two' }, ['en']).subtitles, 'one');
});

test('the options for a video name its languages and their styles as the app takes them', () => {
  const tracks = ['en', 'fr'];
  let choice = firstChoice({ clips, tracks, shown: 'en', second: 'fr', top: 'second', kept: { look: 'boxed', quality: 'original' } });
  assert.deepEqual(optionsOf(choice, styleOf), {
    saveAs: 'video',
    quality: 'original',
    look: 'boxed',
    subtitles: [
      { language: 'en', style: { color: 'yellow', font: 'serif', size: 'L' } },
      { language: 'fr', style: { color: 'white', font: 'sans', size: 'M' } },
    ],
    secondOnTop: true,
  });
  choice = changed(choice, { subtitles: 'one' }, tracks);
  assert.deepEqual(optionsOf(choice, styleOf).subtitles.map((s) => s.language), ['en']);
  assert.equal(optionsOf(choice, styleOf).secondOnTop, false, 'one language has no top');
  choice = changed(choice, { subtitles: 'none' }, tracks);
  assert.deepEqual(optionsOf(choice, styleOf).subtitles, []);
});

test('a track without a language goes as an empty one, and SRT asks for each language chosen', () => {
  const tracks = ['', 'fr'];
  let choice = firstChoice({ clips, tracks, shown: '' });
  assert.deepEqual(optionsOf(choice, styleOf).subtitles, [{ language: '', style: { color: 'pink', font: 'mono', size: 'XL' } }]);
  choice = toggledSrt(changed(choice, { saveAs: 'srt' }, tracks), 'fr', tracks);
  const options = optionsOf(choice, styleOf);
  assert.equal(options.saveAs, 'srt');
  assert.deepEqual(options.subtitles.map((s) => s.language), ['', 'fr']);
  assert.equal(options.secondOnTop, false);
  assert.equal(keyOf({ language: null }), '');
  assert.equal(keyOf({ language: 'de' }), 'de');
});

test('a style the app does not know becomes the default', () => {
  assert.deepEqual(contractStyle({ color: 'purple', font: 'Comic', size: 'XXL' }), { color: 'white', font: 'sans', size: 'M' });
  assert.deepEqual(contractStyle({ color: 'cyan', font: 'Display', size: 'S' }), { color: 'cyan', font: 'display', size: 'S' });
  assert.deepEqual(contractStyle(), { color: 'white', font: 'sans', size: 'M' });
});

test('the Export button and the note say what will be made and where', () => {
  const video = { saveAs: 'video', srt: ['en'] };
  assert.equal(exportLabel(video, 1), 'Export clip');
  assert.equal(exportLabel(video, 3), 'Export 3 clips');
  assert.equal(exportLabel({ saveAs: 'srt', srt: ['en', 'fr'] }, 3), 'Export 6 subtitle files');
  assert.equal(exportLabel({ saveAs: 'srt', srt: ['en'] }, 1), 'Export 1 subtitle file');
  assert.equal(whereNote(video, 'A walk in the park', 'Counting the walk'),
    'Saved next to the video, named for the video and the clip: “A walk in the park - Counting the walk.mp4”.');
  assert.match(whereNote({ saveAs: 'srt', srt: ['en'] }, 'A walk', 'Counting'), /“A walk - Counting\.en\.srt”/);
  assert.match(whereNote({ saveAs: 'srt', srt: [''] }, 'A walk', 'Counting'), /“A walk - Counting\.srt”/);
});

test('a look’s small picture shows the first few words of a line', () => {
  assert.equal(sampleWords('This is a walk in the park.'), 'This is a');
  assert.equal(sampleWords('Chaya draws these lines itself,'), 'Chaya draws these');
  assert.equal(sampleWords('Extraordinarily long words here'), 'Extraordinarily');
  assert.equal(sampleWords('  '), 'Subtitles here');
  assert.equal(sampleWords('Well, then'), 'Well, then');
  assert.equal(sampleWords('Hello,'), 'Hello');
});

test('how a job is going, and the notice it gives when it ends', () => {
  const job = { job: 4, id: 1, clip: 2, name: 'Into the park', saveAs: 'video', state: 'exporting', percent: 41.7, path: null, encoder: null, message: null };
  assert.equal(progressWords(job), '41%');
  assert.equal(progressWords({ ...job, state: 'waiting' }), 'Waiting');
  assert.ok(isRunning(job) && isRunning({ ...job, state: 'waiting' }));
  assert.ok(!isRunning({ ...job, state: 'done' }) && !isRunning({ ...job, state: 'stopped' }));
  assert.equal(noticeOf(job), null);
  assert.equal(noticeOf({ ...job, state: 'stopped' }), null, 'stopped by the person: nothing to say');

  const done = { ...job, state: 'done', percent: 100, path: 'C:\\Users\\me\\Downloads\\A walk - Into the park.mp4', encoder: 'graphicsCard' };
  assert.deepEqual(noticeOf(done), { text: 'Saved “Into the park”', detail: 'H.264 on the graphics card', action: 'Show in folder', failed: false });
  assert.equal(noticeOf({ ...done, encoder: 'windows' }).detail, 'H.264 (Windows)');
  assert.equal(noticeOf({ ...done, encoder: 'mpeg4' }).detail, 'MPEG-4, as Windows has no H.264 encoder');
  assert.equal(noticeOf({ ...done, saveAs: 'srt', encoder: null, path: '/home/me/Downloads/A walk - Into the park.en.srt' }).detail,
    'A walk - Into the park.en.srt');

  const failed = { ...job, state: 'failed', message: 'The disk is full.' };
  assert.deepEqual(noticeOf(failed), { text: 'Couldn\'t export “Into the park”: The disk is full.', detail: '', action: 'Retry', failed: true });
  assert.match(noticeOf({ ...failed, message: null }).text, /: something went wrong\.$/);
  assert.equal(encoderWords(null), null);
  assert.equal(baseName('C:\\a\\b.mp4'), 'b.mp4');
});
