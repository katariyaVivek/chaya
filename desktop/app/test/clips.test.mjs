// The window's clip rules (ui/clips.js): lines chosen as a range, edges snapped to lines, overlapping clips on rows.
// Run with: node --test test/clips.test.mjs, in desktop/app

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { selection, snap, moveEdge, stepEdge, rows, clipOf, nameFrom, view, exact, SHORTEST } from '../ui/clips.js';

// Seven lines, as the tour's clip has them: 1–4 s, 5–8 s, … each 3 s with a second between (the last 4 s).
const lines = [1, 5, 9, 13, 17, 21, 25].map((s, i) => ({ start: s * 1000, end: (s + (i === 6 ? 4 : 3)) * 1000, text: `Line ${i + 1}.` }));

test('a line clicked and another Shift-clicked are one range, whichever comes first', () => {
  assert.deepEqual(selection(lines, 1, 4), { first: 1, last: 4, count: 4, start: 5000, end: 20000 });
  assert.deepEqual(selection(lines, 4, 1), selection(lines, 1, 4));
  assert.deepEqual(selection(lines, 3, 3), { first: 3, last: 3, count: 1, start: 13000, end: 16000 });
  assert.equal(selection([], 0, 2), null);
});

test('an edge let go near a line goes to its start or end, else to a tenth of a second', () => {
  assert.equal(snap(lines, 9800, 'start'), 9000, 'a start to the nearest start');
  assert.equal(snap(lines, 11400, 'end'), 12000, 'an end to the nearest end');
  assert.equal(snap(lines, 10400, 'start'), 9000, 'within 1.5 s');
  assert.equal(snap(lines, 10800, 'start'), 10800, '1.8 s from the nearest start: as it is');
  assert.equal(snap(lines, 11040, 'start'), 11000, 'too far from any start: a tenth of a second');
  assert.equal(snap(lines, 9800, 'start', true), 9800, 'Alt held: anywhere');
  assert.equal(snap([], 1234, 'end'), 1200, 'no lines');
});

test('an edge moved stays inside the video and short of the other edge', () => {
  const clip = { id: 1, start: 9000, end: 16000 };
  assert.deepEqual(moveEdge(clip, 'start', 4600, lines, 30000), { ...clip, start: 5000 });
  assert.deepEqual(moveEdge(clip, 'start', 17000, lines, 30000), { ...clip, start: 16000 - SHORTEST }, 'not past the end');
  assert.deepEqual(moveEdge(clip, 'end', 29900, lines, 30000), { ...clip, end: 29000 }, 'the last line ends at 29 s');
  assert.deepEqual(moveEdge(clip, 'end', 40000, lines, 30000), { ...clip, end: 30000 }, 'not past the video');
    assert.deepEqual(moveEdge(clip, 'start', -300, lines, 30000), { ...clip, start: 1000 });
  assert.deepEqual(moveEdge(clip, 'start', -3000, [], 30000), { ...clip, start: 0 });
});

test('the arrow keys move an edge a line at a time, or a tenth with Alt', () => {
  const clip = { id: 1, start: 9000, end: 16000 };
  assert.equal(stepEdge(clip, 'start', -1, lines, 30000).start, 5000);
  assert.equal(stepEdge(clip, 'end', 1, lines, 30000).end, 20000);
  assert.equal(stepEdge(clip, 'end', -1, lines, 30000).end, 12000);
  assert.equal(stepEdge(clip, 'start', 1, lines, 30000, true).start, 9100);
  assert.equal(stepEdge({ id: 1, start: 1000, end: 4000 }, 'start', -1, lines, 30000).start, 900, 'no line before: a tenth');
});

test('clips that overlap go on rows of their own; those that only touch share one', () => {
  const clips = [
    { id: 1, start: 0, end: 10000 },
    { id: 2, start: 5000, end: 8000 },
    { id: 3, start: 10000, end: 12000 },
    { id: 4, start: 6000, end: 11000 },
  ];
  assert.deepEqual([...rows(clips)].sort(), [[1, 0], [2, 1], [3, 0], [4, 2]]);
  assert.equal(clipOf(clips, 5000, 8000)?.id, 2);
  assert.equal(clipOf(clips, 5000, 8001), null);
});

test('a clip is named by the first words of its first line', () => {
  assert.equal(nameFrom('  This is a walk in the park.'), 'This is a walk in the park');
  assert.equal(nameFrom('Clicking a line plays from there, and the list follows the video as it plays…'), 'Clicking a line plays from there, and');
  assert.equal(nameFrom('Antidisestablishmentarianism-and-more-words-joined-up'), 'Antidisestablishmentarianism-and-more-wo');
  assert.equal(nameFrom(''), '');
});

test('the timeline shows a little around the clip, and times to a tenth', () => {
  assert.deepEqual(view({ start: 9000, end: 16000 }, 30000), { from: 5000, to: 20000 }, 'at least 4 s each side');
  assert.deepEqual(view({ start: 1000, end: 29000 }, 30000), { from: 0, to: 30000 });
  assert.equal(exact(106_440), '1:46.4');
  assert.equal(exact(3_725_300), '1:02:05.3');
  assert.equal(exact(59_960), '1:00.0');
});
