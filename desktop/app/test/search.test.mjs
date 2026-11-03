// The window's rules for the search across every video's lines (ui/search.js): the words marked, the lines by video,
// and what the heading says. Run with: node --test test/search.test.mjs, in desktop/app

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pieces, groups, summary, worthSearching } from '../ui/search.js';

const hit = (id, start, language = 'en', text = 'a walk in the park', marks = [[2, 6]]) =>
  ({ id, title: `Video ${id}`, language, start, end: start + 2000, text, marks });

test('a line is cut into the words marked and the rest, as the app counts them', () => {
  assert.deepEqual(pieces('a walk in the park', [[2, 6], [14, 18]]), [
    { text: 'a ', marked: false },
    { text: 'walk', marked: true },
    { text: ' in the ', marked: false },
    { text: 'park', marked: true },
  ]);
  assert.deepEqual(pieces('walk', [[0, 4]]), [{ text: 'walk', marked: true }]);
  assert.deepEqual(pieces('no marks', []), [{ text: 'no marks', marked: false }]);
});

test('marks out of order, overlapping or past the end are put right', () => {
  assert.deepEqual(pieces('abcdef', [[4, 9], [0, 2], [1, 3]]), [
    { text: 'ab', marked: true },
    { text: 'c', marked: true },
    { text: 'd', marked: false },
    { text: 'ef', marked: true },
  ]);
  assert.deepEqual(pieces('abc', [[5, 7]]), [{ text: 'abc', marked: false }]);
});

test('marks count UTF-16 units, so a face before a word does not move them', () => {
  // "😀 café": the face is two units, as the app counts it.
  assert.deepEqual(pieces('😀 café', [[3, 7]]), [{ text: '😀 ', marked: false }, { text: 'café', marked: true }]);
});

test('lines are grouped by video in the order they came, each saying how many languages it has', () => {
  const found = groups([hit(2, 1000), hit(1, 1000), hit(1, 5000, 'fr'), hit(1, 9000)]);
  assert.deepEqual(found.map((g) => [g.id, g.title, g.hits.map((h) => h.start), g.languages]), [
    [2, 'Video 2', [1000], 1],
    [1, 'Video 1', [1000, 5000, 9000], 2],
  ]);
  assert.deepEqual(groups([]), []);
});

test('the heading counts the lines and videos, or says only the first came back', () => {
  assert.equal(summary({ hits: [hit(1, 0)], total: 1 }), '1 line in 1 video');
  assert.equal(summary({ hits: [hit(1, 0), hit(2, 0), hit(2, 9)], total: 3 }), '3 lines in 2 videos');
  const many = Array.from({ length: 200 }, (_, i) => hit(1, i));
  assert.equal(summary({ hits: many, total: 312 }), 'The first 200 of 312 lines');
});

test('a single letter is not searched for', () => {
  assert.equal(worthSearching('w'), false);
  assert.equal(worthSearching('  w  '), false);
  assert.equal(worthSearching('wa'), true);
  assert.equal(worthSearching('你好'), true);
});
