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
