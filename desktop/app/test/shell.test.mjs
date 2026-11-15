// The sidebar's rules (ui/shell.js): which place is shown, where Browse goes, and what the library holds.
// Run with: node --test test/shell.test.mjs, in desktop/app

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { placeOf, tabToBrowse, libraryTotals } from '../ui/shell.js';

test('a page open is Browse, the home is Home', () => {
  assert.equal(placeOf({ browsing: true }), 'browse');
  assert.equal(placeOf({ browsing: false }), 'home');
});

test('Browse goes back to the tab last shown, or the first if that one closed, and is not offered with no tabs', () => {
  const view = { tabs: [{ id: 3 }, { id: 7 }] };
  assert.equal(tabToBrowse(view, 7), 7);
  assert.equal(tabToBrowse(view, 9), 3);
  assert.equal(tabToBrowse(view, null), 3);
  assert.equal(tabToBrowse({ tabs: [] }, 7), null);
});

test('the library counts saved downloads and the bytes they take, ignoring the rest and broken sizes', () => {
  assert.deepEqual(libraryTotals([]), { saved: 0, bytes: 0 });
  assert.deepEqual(
    libraryTotals([
      { state: 'completed', total: 100 },
      { state: 'completed', total: null, done: 40 },
      { state: 'completed', total: NaN },
      { state: 'downloading', total: 500, done: 10 },
      { state: 'failed', total: 900 },
    ]),
    { saved: 3, bytes: 140 },
  );
});

import { railed, sourcesOf, underWay } from '../ui/shell.js';

test('the Library is its own place, over Home or over a page, and only a page makes the sidebar a rail', () => {
  assert.equal(placeOf({ browsing: false, library: true }), 'library');
  assert.equal(placeOf({ browsing: true, library: true }), 'library');
  assert.equal(placeOf({ browsing: true, library: false }), 'browse');
  assert.equal(placeOf({ browsing: false, library: false }), 'home');
  assert.equal(railed({ browsing: true, library: false }), true);
  assert.equal(railed({ browsing: true, library: true }), false);
  assert.equal(railed({ browsing: false, library: true }), false);
  assert.equal(railed({ browsing: false, library: false }), false);
});

test('the sources are the sites the downloads come from, in order, counted, plus the one chosen', () => {
  const order = [['youtube', 'YouTube'], ['instagram', 'Instagram'], ['x', 'X'], ['pc', 'This PC']];
  const tasks = [{ site: 'x' }, { site: 'youtube' }, { site: 'youtube' }, { site: 'pc' }];
  assert.deepEqual(sourcesOf(tasks, order, null), [
    { site: 'youtube', name: 'YouTube', count: 2, chosen: false },
    { site: 'x', name: 'X', count: 1, chosen: false },
    { site: 'pc', name: 'This PC', count: 1, chosen: false },
  ]);
  assert.deepEqual(sourcesOf([{ site: 'x' }], order, 'instagram').map((s) => [s.site, s.count, s.chosen]), [
    ['instagram', 0, true],
    ['x', 1, false],
  ]);
});

test('Home lists what is under way: running, paused and failed, never what was saved', () => {
  for (const state of ['queued', 'downloading', 'paused', 'failed']) assert.equal(underWay({ state }), true, state);
  assert.equal(underWay({ state: 'completed' }), false);
});
