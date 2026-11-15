// The browser's "Found on this page" panel: its first pick and when it opens on its own (ui/found.js).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { heightOf, firstPick, offerFound } from '../ui/found.js';

const c = (label, audioOnly = false) => ({ label, detail: '', audioOnly });

test('a choice names its picture height in its label', () => {
  assert.equal(heightOf('1080p'), 1080);
  assert.equal(heightOf('720p60'), 720);
  assert.equal(heightOf('2160p'), 2160);
  assert.equal(heightOf('Audio only'), null);
  assert.equal(heightOf('Video'), null);
  assert.equal(heightOf(undefined), null);
});

test('the first pick is the best at most 1080p, as Stage shows it, not the biggest file', () => {
  assert.equal(firstPick([c('2160p'), c('1440p'), c('1080p'), c('720p'), c('Audio only', true)]), 2);
  assert.equal(firstPick([c('720p60'), c('480p'), c('Audio only', true)]), 0);
  assert.equal(firstPick([c('480p'), c('720p')]), 1, 'best by height, wherever it is listed');
});

test('with no height to go by, the first picture; audio only when that is all there is', () => {
  assert.equal(firstPick([c('2160p'), c('Audio only', true)]), 0);
  assert.equal(firstPick([c('Audio only', true), c('Video')]), 1);
  assert.equal(firstPick([c('Audio only', true)]), 0);
  assert.equal(firstPick([]), -1);
});

const at = (over) => ({ address: 'https://v.example/a', offeredFor: '', something: true, open: null, fits: true, ...over });

test('the panel opens itself once for an address with something to save', () => {
  assert.deepEqual(offerFound(at()), { open: true, offeredFor: 'https://v.example/a' });
  // Asked again every 1.5 s: closed by the person, it stays closed there
  assert.deepEqual(offerFound(at({ offeredFor: 'https://v.example/a' })), { open: false, offeredFor: 'https://v.example/a' });
  // A new address offers it again
  assert.deepEqual(offerFound(at({ address: 'https://v.example/b', offeredFor: 'https://v.example/a' })), {
    open: true,
    offeredFor: 'https://v.example/b',
  });
});

test('it never opens over another panel or where the page would have no room, and does not try again there', () => {
  assert.deepEqual(offerFound(at({ open: 'shield' })), { open: false, offeredFor: 'https://v.example/a' });
  assert.deepEqual(offerFound(at({ fits: false })), { open: false, offeredFor: 'https://v.example/a' });
});

test('nothing to save, or no page, offers nothing and remembers nothing', () => {
  assert.deepEqual(offerFound(at({ something: false })), { open: false, offeredFor: '' });
  assert.deepEqual(offerFound(at({ address: '' })), { open: false, offeredFor: '' });
});
