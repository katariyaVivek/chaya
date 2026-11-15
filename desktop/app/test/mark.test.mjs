// Chaya's mark in the window: the title bar's markup is the resting mark the constants draw, and the downloads give
// it one state (ui/mark-state.js). Run with: node --test test/mark.test.mjs, in desktop/app

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { markMarkup } from '../../design/chaya-mark.js';
import { markState, nextRun, shareOf } from '../ui/mark-state.js';

const html = readFileSync(fileURLToPath(new URL('../ui/index.html', import.meta.url)), 'utf8');

test("the sidebar's mark is the resting mark, as chaya-mark.js draws it", () => {
  assert.ok(html.includes(markMarkup('side-mark', { size: 26 })), 'index.html no longer matches markMarkup("side-mark", { size: 26 })');
});

let ids = 0;
const file = (state, done, total) => ({ id: ++ids, state, done, total });

test('nothing under way: at rest', () => {
  assert.deepEqual(markState([]), { mode: 'idle', p: 1, saved: 0 });
  assert.deepEqual(markState([file('completed', 10, 10), file('failed', 2, 10)]), { mode: 'idle', p: 1, saved: 1 });
});

test('one download: its progress', () => {
  assert.deepEqual(markState([file('downloading', 25, 100)]), { mode: 'saving', p: 0.25, saved: 0 });
  assert.deepEqual(markState([file('queued', 0, 100)]), { mode: 'saving', p: 0, saved: 0 });
});

test('several at once: their progress weighted by size, so a big one moves the line more', () => {
  // 80 of 100 MB and 0 of 900 MB: 80 of 1000 MB has arrived, not the 40% an average would say
  assert.equal(markState([file('downloading', 80, 100), file('downloading', 0, 900)]).p, 0.08);
  assert.equal(markState([file('downloading', 50, 100), file('downloading', 50, 100)]).p, 0.5);
});

test("one finishing does not step the line back: the run's finished ones count as done", () => {
  const big = file('downloading', 600, 1000);
  const small = file('downloading', 10, 100);
  let run = nextRun(new Set(), [big, small]);
  const before = markState([big, small], true, run).p;
  // The big one finishes; the small one is still at 10%
  const done = { ...big, state: 'completed', done: 1000 };
  run = nextRun(run, [done, small]);
  const after = markState([done, small], true, run).p;
  assert.ok(after >= before, `stepped back from ${before} to ${after}`);
  assert.equal(after, (1000 + 10) / 1100);
  // Without the run it would fall to the small one's 10%
  assert.equal(markState([done, small]).p, 0.1);
});

test('a run lasts while anything is under way and starts afresh after', () => {
  const a = file('downloading', 1, 10);
  let run = nextRun(new Set(), [a]);
  assert.deepEqual([...run], [a.id]);
  const finished = { ...a, state: 'completed' };
  run = nextRun(run, [finished]);
  assert.equal(run.size, 0, 'nothing under way: the run ends');
  const b = file('downloading', 0, 10);
  run = nextRun(run, [finished, b]);
  assert.deepEqual([...run], [b.id], 'the next download starts a new run, without the old one');
  assert.equal(markState([finished, b], true, run).p, 0);
});

test("an account's ZIP, with no byte size yet, weighs as much as the run's other downloads on average", () => {
  const zip = { id: ++ids, state: 'downloading', done: 0, total: null, archive: { found: 10, saved: 5 } };
  assert.equal(markState([zip]).p, 0.5);
  assert.equal(markState([zip, file('downloading', 0, 200)]).p, 0.25);
});

test('a stream, or anything whose size is not known yet: unknown, unless another knows its share', () => {
  assert.equal(markState([file('downloading', 5_000_000, null)]).mode, 'unknown');
  assert.equal(markState([{ state: 'downloading', archive: { found: 0, saved: 0 } }]).mode, 'unknown');
  assert.deepEqual(markState([file('downloading', 5_000_000, null), file('downloading', 30, 100)]), { mode: 'saving', p: 0.3, saved: 0 });
});

test('everything paused: paused, holding its progress; one still running keeps it saving', () => {
  assert.deepEqual(markState([file('paused', 50, 100)]), { mode: 'paused', p: 0.5, saved: 0 });
  assert.equal(markState([file('paused', 50, 100), file('downloading', 10, 100)]).mode, 'saving');
  assert.deepEqual(markState([file('paused', 5, null)]), { mode: 'paused', p: 0, saved: 0 });
});

test('a failed or cancelled download returns to rest, and only finished ones count as saved', () => {
  assert.deepEqual(markState([file('failed', 40, 100)]), { mode: 'idle', p: 1, saved: 0 });
  assert.equal(markState([file('completed', 1, 1), file('completed', 1, 1)]).saved, 2);
});

test('offline: the calm pose, whatever is under way', () => {
  assert.equal(markState([file('downloading', 50, 100)], false).mode, 'offline');
  assert.equal(markState([], false).mode, 'offline');
});

test('shares out of range are clamped, and broken ones ignored', () => {
  assert.equal(markState([file('downloading', 150, 100)]).p, 1);
  assert.equal(markState([file('downloading', -5, 100)]).p, 0);
  assert.equal(markState([file('downloading', NaN, 100), file('downloading', 50, 100)]).p, 0.5);
});

test("a share is bytes for a file and files for an account's ZIP", () => {
  assert.equal(shareOf({ done: 30, total: 120 }), 0.25);
  assert.equal(shareOf({ done: 30, total: null }), null);
  assert.equal(shareOf({ archive: { found: 8, saved: 2 } }), 0.25);
  assert.equal(shareOf({ archive: { found: 0, saved: 0 } }), null);
});
