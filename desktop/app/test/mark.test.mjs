// Chaya's mark in the window: the title bar's markup is the resting mark the constants draw, and the downloads give
// it one state (ui/mark-state.js). Run with: node --test test/mark.test.mjs, in desktop/app

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { markMarkup } from '../../design/chaya-mark.js';
import { markState, shareOf } from '../ui/mark-state.js';

const html = readFileSync(fileURLToPath(new URL('../ui/index.html', import.meta.url)), 'utf8');

test("the title bar's mark is the resting mark, as chaya-mark.js draws it", () => {
  assert.ok(html.includes(markMarkup('top-mark', { size: 24 })), 'index.html no longer matches markMarkup("top-mark", { size: 24 })');
});

const file = (state, done, total) => ({ state, done, total });

test('nothing under way: at rest', () => {
  assert.deepEqual(markState([]), { mode: 'idle', p: 1, saved: 0 });
  assert.deepEqual(markState([file('completed', 10, 10), file('failed', 2, 10)]), { mode: 'idle', p: 1, saved: 1 });
});

test('one download: its progress', () => {
  assert.deepEqual(markState([file('downloading', 25, 100)]), { mode: 'saving', p: 0.25, saved: 0 });
  assert.deepEqual(markState([file('queued', 0, 100)]), { mode: 'saving', p: 0, saved: 0 });
});

test('several at once: their progress together, so a new one starting lowers it (the mark eases down)', () => {
  const one = markState([file('downloading', 80, 100)]);
  const two = markState([file('downloading', 80, 100), file('downloading', 0, 50)]);
  assert.equal(one.p, 0.8);
  assert.equal(two.p, 0.4);
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
