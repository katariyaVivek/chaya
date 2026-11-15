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

import { heroOf, clockOf } from '../ui/shell.js';

test("Home's hero carries on with the video stopped last, else shows the newest saved video", () => {
  const tasks = [
    { id: 1, state: 'completed', kind: 'video', ended: 100 },
    { id: 2, state: 'completed', kind: 'video', ended: 300 },
    { id: 3, state: 'completed', kind: 'picture', ended: 900 },
    { id: 4, state: 'downloading', kind: 'video', added: 950 },
  ];
  const watching = { id: 1, at: 252, duration: 760 };
  assert.deepEqual(heroOf(tasks, watching), { id: 1, watching });
  assert.deepEqual(heroOf(tasks, null), { id: 2, watching: null }, 'the newest saved video, not a picture or a running one');
  assert.deepEqual(heroOf(tasks, { id: 9, at: 5 }), { id: 2, watching: null }, 'one no longer in the list is not offered');
  assert.equal(heroOf([{ id: 3, state: 'completed', kind: 'picture' }], null), null);
  assert.equal(heroOf([], null), null);
});

test('times read as a clock', () => {
  assert.equal(clockOf(252), '4:12');
  assert.equal(clockOf(5), '0:05');
  assert.equal(clockOf(3725), '1:02:05');
  assert.equal(clockOf(NaN), '0:00');
  assert.equal(clockOf(-3), '0:00');
});

import { shelfColumns } from '../ui/shell.js';

test("Home's shelf shows one row: as many 180 px cards as its width holds, as the grid would lay them out", () => {
  // 1000 px with 12 px gaps holds five (5 × 180 + 4 × 12 = 948), not six (1128)
  assert.equal(shelfColumns(1000, 180, 12, 5), 5);
  assert.equal(shelfColumns(800, 180, 12, 5), 4); // 4 × 180 + 3 × 12 = 756
  assert.equal(shelfColumns(756, 180, 12, 5), 4); // exactly four
  assert.equal(shelfColumns(755, 180, 12, 5), 3);
  assert.equal(shelfColumns(2000, 180, 12, 5), 5, 'never past the most it asks for');
  assert.equal(shelfColumns(100, 180, 12, 5), 1, 'one card even when narrower than one');
  assert.equal(shelfColumns(0, 180, 12, 5), 5, 'not laid out yet: the most, to be counted again once it is');
  assert.equal(shelfColumns(NaN, 180, 12, 5), 5);
});
