// One panel open at a time (ui/panels.js). Run with: node --test test/panels.test.mjs, in desktop/app

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { onePanel } from '../ui/panels.js';

function recorded() {
  const drawn = [];
  const panels = onePanel((name, open) => drawn.push(`${open ? 'open' : 'close'} ${name}`));
  return { panels, drawn };
}

test('opening a panel closes the one open, first', () => {
  const { panels, drawn } = recorded();
  panels.set('settings', true);
  panels.set('media', true);
  assert.equal(panels.open, 'media');
  assert.deepEqual(drawn, ['open settings', 'close settings', 'open media']);
});

test('closing a panel that is not the one open leaves the open one alone', () => {
  const { panels, drawn } = recorded();
  panels.set('shield', true);
  panels.set('settings', false);
  assert.equal(panels.open, 'shield');
  assert.deepEqual(drawn, ['open shield']);
});

test('a panel already open or already closed is not drawn again', () => {
  const { panels, drawn } = recorded();
  panels.set('settings', true);
  panels.set('settings', true);
  panels.set('settings', false);
  panels.set('settings', false);
  assert.equal(panels.open, null);
  assert.deepEqual(drawn, ['open settings', 'close settings']);
});

test("a panel's button opens it, closes it, and takes the place of another", () => {
  const { panels } = recorded();
  panels.toggle('media');
  assert.equal(panels.open, 'media');
  panels.toggle('shield');
  assert.equal(panels.open, 'shield');
  panels.toggle('shield');
  assert.equal(panels.open, null);
});
