// How far subtitles being made have got (ui/pace.js). Run with: node --test test/pace.test.mjs, in desktop/app

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { secondsLeft, listeningWords } from '../ui/pace.js';

const plain = (s) => `${Math.round(s)} s`;

test('the time left follows the pace since it first moved', () => {
  const from = { at: 10_000, percent: 5 };
  // 20 s for 20 points: one second a point, 75 to go.
  assert.equal(secondsLeft(from, 30_000, 25), 75);
  // Faster later on: the estimate shortens.
  assert.equal(Math.round(secondsLeft(from, 40_000, 65)), 18);
});

test('with too little to go on, it says nothing of time', () => {
  const from = { at: 0, percent: 0 };
  assert.equal(secondsLeft(from, 2_000, 30), null, 'too soon');
  assert.equal(secondsLeft(from, 60_000, 3), null, 'too little heard');
  assert.equal(secondsLeft(null, 60_000, 40), null, 'not seen moving yet');
  assert.equal(secondsLeft(from, 60_000, 100), null, 'done');
});

test('it gets ready, counts, then finishes', () => {
  assert.equal(listeningWords(0, null, plain), 'Getting ready');
  assert.equal(listeningWords(12, null, plain), '12%');
  assert.equal(listeningWords(40, 90, plain), '40% · about 90 s left');
  assert.equal(listeningWords(99, 3, plain), 'Finishing');
});
