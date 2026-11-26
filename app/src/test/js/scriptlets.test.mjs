// Chaya's scriptlets (app/src/main/assets/adblock/chaya_scriptlets.js), run as a page runs them: the script loads into
// a fresh context, asks ChayaScriptlets for this page's rules, runs them, and then the "page" runs its own code.
// node --test app/src/test/js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../main/assets/adblock/chaya_scriptlets.js', import.meta.url), 'utf8');

/** A page with [rules] ([[name, arg...]...]) handed out by its bridge; [extra] adds globals such as fetch. */
function page(rules, extra = {}) {
  const asked = [];
  const context = vm.createContext({
    JSON, Promise, Response, Headers, EventTarget, Event, setTimeout, clearTimeout, setInterval, clearInterval,
    location: { href: 'https://www.example.com/watch?v=1' },
    ChayaScriptlets: { forPage: (url) => { asked.push(url); return JSON.stringify(rules); } },
    ...extra,
  });
  context.window = context;
  vm.runInContext(source, context);
  return { run: (code) => vm.runInContext(code, context), context, asked };
}

test('it asks the app for the rules of the page it runs in', () => {
  const p = page([]);
  assert.deepEqual(p.asked, ['https://www.example.com/watch?v=1']);
});

test('set-constant holds a property inside an object the page assigns later (YouTube player ads)', () => {
  const p = page([['set-constant', 'ytInitialPlayerResponse.adPlacements', 'undefined'],
    ['set-constant', 'ytInitialPlayerResponse.playerAds', 'undefined']]);
  p.run('var ytInitialPlayerResponse = { adPlacements: [1, 2], playerAds: [3], videoDetails: { videoId: "a" } };');
  assert.equal(p.run('ytInitialPlayerResponse.adPlacements'), undefined);
  assert.equal(p.run('ytInitialPlayerResponse.playerAds'), undefined);
  assert.equal(p.run('ytInitialPlayerResponse.videoDetails.videoId'), 'a');
  // Assigned afresh, it is held again
  p.run('ytInitialPlayerResponse = { adPlacements: [9] };');
  assert.equal(p.run('ytInitialPlayerResponse.adPlacements'), undefined);
});

test('set-constant gives the values the lists name, and ignores ones it does not know', () => {
  const p = page([['set-constant', 'ads.enabled', 'false'], ['set-constant', 'adblock.check', 'noopFunc'],
    ['set-constant', 'weird', '{"a":1}']]);
  p.run('var ads = { enabled: true }; var adblock = {}; var weird = 5;');
  assert.equal(p.run('ads.enabled'), false);
  assert.equal(p.run('typeof adblock.check'), 'function');
  assert.equal(p.run('weird'), 5);
});

test('json-prune takes ad fields out of JSON the page reads, when the needed fields are there', () => {
  const p = page([['json-prune', 'playerResponse.adPlacements adSlots', ''],
    ['json-prune', 'onlyWhen', 'marker']]);
  const out = p.run('JSON.parse(\'{"playerResponse":{"adPlacements":[1],"streamingData":{}},"adSlots":[2],"onlyWhen":1}\')');
  assert.equal(out.playerResponse.adPlacements, undefined);
  assert.deepEqual(Object.keys(out.playerResponse), ['streamingData']);
  assert.equal(out.adSlots, undefined);
  assert.equal(out.onlyWhen, 1, 'kept: "marker" is not in the data');
});

test('json-prune [-] removes the items of a list that hold the path (ads among Shorts)', () => {
  const p = page([['json-prune', 'entries.[-].command.reelWatchEndpoint.adClientParams.isAd']]);
  const out = p.run(`JSON.parse(JSON.stringify({ entries: [
    { command: { reelWatchEndpoint: { videoId: 'a' } } },
    { command: { reelWatchEndpoint: { adClientParams: { isAd: true } } } },
    { command: { reelWatchEndpoint: { videoId: 'b' } } } ] }))`);
  assert.deepEqual(out.entries.map((e) => e.command.reelWatchEndpoint.videoId), ['a', 'b']);
});

test('json-prune-fetch-response prunes answers only for the requests it names', async () => {
  const body = JSON.stringify({ adPlacements: [1], playerResponse: { adSlots: [2], ok: true } });
  const fetch = async () => new Response(body, { status: 200, headers: { 'content-type': 'application/json' } });
  const p = page([['json-prune-fetch-response', 'adPlacements playerResponse.adSlots', '', 'propsToMatch', '/\\/player(\\?|$)/']], { fetch });
  const player = await p.run('fetch("https://www.youtube.com/youtubei/v1/player?key=1").then(r => r.json())');
  assert.equal(player.adPlacements, undefined);
  assert.deepEqual(player.playerResponse, { ok: true });
  const other = await p.run('fetch("https://www.youtube.com/youtubei/v1/next").then(r => r.json())');
  assert.deepEqual(other.adPlacements, [1]);
});

test('no-window-open-if stops pop-ups to matching addresses and lets others open', () => {
  const opened = [];
  // The browser's own window.open, which the scriptlet wraps
  const p = page([['no-window-open-if', '/ads|pop/']], { open: (url) => { opened.push(url); return {}; } });
  assert.equal(p.run('open("https://popads.example/x")'), null);
  assert.deepEqual(p.run('open("https://news.example/story")'), {});
  assert.deepEqual(opened, ['https://news.example/story']);
});

test('abort-on-property-read stops the script that reads the property', () => {
  const p = page([['abort-on-property-read', 'adsbygoogle']]);
  assert.throws(() => p.run('adsbygoogle.push({})'), (e) => e.name === 'ReferenceError');
  assert.doesNotThrow(() => p.run('var fine = 1;'));
});

test('abort-on-property-write stops the script that assigns the property', () => {
  const p = page([['abort-on-property-write', 'popunder']]);
  assert.throws(() => p.run('popunder = function () {}'), (e) => e.name === 'ReferenceError');
});

test('addEventListener-defuser drops matching listeners only', () => {
  const p = page([['addEventListener-defuser', 'click', 'popunder']]);
  const fired = p.run(`(function () {
    var t = new EventTarget(), fired = [];
    t.addEventListener('click', function () { fired.push('popunder'); });
    t.addEventListener('click', function () { fired.push('menu'); });
    t.dispatchEvent(new Event('click'));
    return fired;
  })()`);
  assert.deepEqual([...fired], ['menu']);
});

test('no-setTimeout-if turns matching timers into nothing', async () => {
  const p = page([['no-setTimeout-if', 'adblock', '']]);
  const ran = await p.run(`new Promise(function (done) {
    var ran = [];
    setTimeout(function () { ran.push('adblock check'); }, 1);
    setTimeout(function () { ran.push('page'); }, 1);
    setTimeout(function () { done(ran); }, 20);
  })`);
  assert.deepEqual([...ran], ['page']);
});

test('no-fetch-if answers matching requests with nothing, without the network', async () => {
  let reached = 0;
  const fetch = async () => { reached++; return new Response('real'); };
  const p = page([['no-fetch-if', 'url:/pagead/ method:GET']], { fetch });
  assert.equal(await p.run('fetch("https://ads.example/pagead/x").then(r => r.text())'), '');
  assert.equal(await p.run('fetch("https://example.com/api").then(r => r.text())'), 'real');
  assert.equal(reached, 1);
});

test('a scriptlet that is not known, or that fails, leaves the others to run', () => {
  const p = page([['no-such-scriptlet', 'x'], ['set-constant'], ['set-constant', 'a.b', 'true']]);
  p.run('var a = { b: false };');
  assert.equal(p.run('a.b'), true);
});

test('without the app bridge nothing changes, and a second load does nothing again', () => {
  const context = vm.createContext({ JSON, location: { href: 'https://example.com/' } });
  context.window = context;
  vm.runInContext(source, context);
  assert.equal(vm.runInContext('typeof JSON.parse', context), 'function');
  const p = page([['set-constant', 'x', '1']]);
  vm.runInContext(source, p.context);
  assert.deepEqual(p.asked.length, 1);
});
