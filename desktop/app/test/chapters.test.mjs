// The window's chapter rules (ui/chapters.js): which chapter plays, where Ctrl+← and Ctrl+→ go, what edges snap to.
// Run with: node --test test/chapters.test.mjs, in desktop/app

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { chapterAt, stepChapter, snapEdges, dividers, BACK_TO_START } from '../ui/chapters.js';
import { snap } from '../ui/clips.js';

// Three chapters, as the tour's video has them: 0–10 s, 10–20 s, 20–30 s.
const chapters = [
  { start: 0, end: 10_000, title: 'Into the park' },
  { start: 10_000, end: 20_000, title: 'Counting' },
  { start: 20_000, end: 30_000, title: 'The end' },
];

test('the chapter playing is the one whose time holds the playhead', () => {
  assert.equal(chapterAt(chapters, 0), 0);
  assert.equal(chapterAt(chapters, 9_999), 0);
  assert.equal(chapterAt(chapters, 10_000), 1, 'a chapter starts where the one before ends');
  assert.equal(chapterAt(chapters, 30_000), -1, 'none past the end');
  assert.equal(chapterAt([], 5_000), -1);
});

test('Ctrl+→ goes to the next chapter, and nowhere from the last', () => {
  assert.equal(stepChapter(chapters, 2_000, 1), 10_000);
  assert.equal(stepChapter(chapters, 10_000, 1), 20_000);
  assert.equal(stepChapter(chapters, 25_000, 1), null);
});

test('Ctrl+← goes to the start of the chapter playing, or the one before when that start was only just passed', () => {
  assert.equal(stepChapter(chapters, 15_000, -1), 10_000, 'back to its start');
  assert.equal(stepChapter(chapters, 10_000 + BACK_TO_START - 1, -1), 0, 'just passed: the one before');
  assert.equal(stepChapter(chapters, 1_000, -1), 0, 'in the first, its start');
  assert.equal(stepChapter([], 1_000, -1), null);
});

test('a clip edge snaps to a chapter edge as to a line', () => {
  const lines = [{ start: 1_000, end: 4_000, text: 'One.' }, { start: 12_500, end: 15_000, text: 'Two.' }];
  const edges = snapEdges(lines, chapters);
  assert.equal(snap(edges, 10_800, 'start'), 10_000, 'the chapter start, nearer than the line');
  assert.equal(snap(edges, 9_200, 'end'), 10_000, 'a chapter ends where the next starts');
  assert.equal(snap(edges, 12_300, 'start'), 12_500, 'the line, nearer than the chapter');
  assert.equal(snapEdges(lines, []), lines, 'without chapters, the lines alone');
});

test('the seek bar divides where each chapter after the first begins', () => {
  assert.deepEqual(dividers(chapters, 30_000), [1 / 3, 2 / 3]);
  assert.deepEqual(dividers(chapters, NaN), [], 'nothing before the length is known');
  assert.deepEqual(dividers([{ start: 0, end: 40_000 }, { start: 40_000, end: 50_000 }], 30_000), [], 'none past the end');
});
