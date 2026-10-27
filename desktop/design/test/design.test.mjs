// Checks that keep the desktop design system honest: the CSS spring matches the physics, both
// themes define the same tokens and stay readable, reduced motion really is opacity-only, and
// the download button's state machine does what the README says.
// Run with: npm test, in desktop/design

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  springEasing,
  PRESS_SPRING,
  nextDownloadState,
  clickEvent,
  ringPercent,
  DownloadButton,
} from '../chaya-motion.js';

const read = (name) => readFileSync(fileURLToPath(new URL(`../${name}`, import.meta.url)), 'utf8');
const tokensCss = read('tokens.css');
const componentsCss = read('components.css');

/** The declarations inside the first block whose selector is exactly `selector`. */
function block(css, selector) {
  const start = css.indexOf(`${selector} {`);
  assert.notEqual(start, -1, `no block for ${selector}`);
  let depth = 0;
  let open = -1;
  for (let i = start; i < css.length; i++) {
    if (css[i] === '{') {
      if (depth++ === 0) open = i;
    } else if (css[i] === '}' && --depth === 0) {
      return declarations(css.slice(open + 1, i));
    }
  }
  throw new Error(`unclosed block for ${selector}`);
}

function declarations(body) {
  const out = new Map();
  const clean = body.replace(/\/\*[\s\S]*?\*\//g, '');
  for (const m of clean.matchAll(/(--[\w-]+|color-scheme)\s*:\s*([^;]+);/g)) out.set(m[1], m[2].trim());
  return out;
}

/** The block nested inside an at-rule, e.g. the :root inside @media (prefers-color-scheme: dark). */
function nested(css, atRule, selector) {
  const at = css.indexOf(atRule);
  assert.notEqual(at, -1, `no ${atRule}`);
  return block(css.slice(at), selector);
}

const day = block(tokensCss, ':root');
const nightSystem = nested(tokensCss, '@media (prefers-color-scheme: dark)', ':root:not([data-theme="day"])');
const nightPicked = block(tokensCss, ':root[data-theme="night"]');
const reducedSystem = nested(tokensCss, '@media (prefers-reduced-motion: reduce)', ':root');
const reducedPicked = block(tokensCss, ':root[data-motion="reduce"]');

// ── The spring ────────────────────────────────────────────────────────────────────────────

test('the press spring rises from 0 to 1 and settles with almost no overshoot', () => {
  const { easing, duration, overshoot } = springEasing(PRESS_SPRING);
  const stops = easing.slice('linear('.length, -1).split(',').map(Number);
  assert.equal(stops[0], 0);
  assert.equal(stops.at(-1), 1);
  assert.ok(overshoot > 0 && overshoot < 0.03, `overshoot ${overshoot}`);
  assert.ok(duration > 350 && duration < 550, `duration ${duration}`);
});

test('tokens.css carries the spring springEasing() computes', () => {
  const { easing, duration } = springEasing(PRESS_SPRING);
  assert.equal(day.get('--spring-press'), easing);
  assert.equal(day.get('--dur-spring'), `${duration}ms`);
});

test('a stiffer spring settles sooner', () => {
  const soft = springEasing({ stiffness: 200, damping: 30 });
  const stiff = springEasing({ stiffness: 600, damping: 40 });
  assert.ok(stiff.duration < soft.duration);
});

// ── Themes ────────────────────────────────────────────────────────────────────────────────

test('Night picked and Night from Windows are the same values', () => {
  assert.deepEqual([...nightPicked], [...nightSystem]);
});

test('every Night token is a Day token, so nothing exists in one theme only', () => {
  for (const name of nightPicked.keys()) assert.ok(day.has(name), `${name} has no Day value`);
});

test('the brief\'s colors are what the tokens say', () => {
  assert.equal(day.get('--bg'), '#F6F4F0');
  assert.equal(day.get('--surface'), 'rgb(255 255 255 / 0.8)');
  assert.equal(day.get('--surface-blur'), '20px');
  assert.equal(day.get('--text'), '#1A1917');
  assert.equal(day.get('--muted'), '#6B6862');
  assert.equal(nightPicked.get('--bg'), '#0B0B0D');
  assert.equal(nightPicked.get('--surface'), 'rgb(255 255 255 / 0.06)');
  assert.equal(nightPicked.get('--surface-blur'), '24px');
  assert.equal(nightPicked.get('--border'), 'rgb(255 255 255 / 0.08)');
  assert.equal(nightPicked.get('--text'), '#F2F1EE');
  assert.equal(nightPicked.get('--muted'), '#8A8883');
  assert.equal(day.get('--accent'), '#FF9A3C');
  assert.ok(!nightPicked.has('--accent'), 'the accent is shared, not redefined for Night');
});

const hex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const rgbAlpha = (v) => {
  const m = v.match(/rgb\((\d+) (\d+) (\d+) \/ ([\d.]+)\)/);
  return { rgb: [m[1], m[2], m[3]].map(Number), alpha: Number(m[4]) };
};
const over = ({ rgb, alpha }, ground) => rgb.map((c, i) => c * alpha + ground[i] * (1 - alpha));
const luminance = (rgb) => {
  const [r, g, b] = rgb.map((c) => {
    const s = c / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const contrast = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

for (const [name, theme] of [['Day', day], ['Night', nightPicked]]) {
  test(`${name}: text and muted text read at 4.5:1 on the ground and on glass`, () => {
    const bg = hex(theme.get('--bg'));
    const glass = over(rgbAlpha(theme.get('--surface')), bg);
    for (const token of ['--text', '--muted']) {
      const fg = hex(theme.get(token));
      for (const [ground, rgb] of [['background', bg], ['glass', glass]]) {
        const ratio = contrast(fg, rgb);
        assert.ok(ratio >= 4.5, `${token} on ${ground}: ${ratio.toFixed(2)}`);
      }
    }
  });
}

test('the accent is too pale for text on Day, so Day focuses with the text color', () => {
  assert.ok(contrast(hex(day.get('--accent')), hex(day.get('--bg'))) < 3);
  assert.equal(day.get('--focus'), 'var(--text)');
  assert.ok(contrast(hex(day.get('--accent')), hex(nightPicked.get('--bg'))) >= 3);
  assert.equal(nightPicked.get('--focus'), 'var(--accent)');
});

test('components use tokens, not literal colors', () => {
  const code = componentsCss.replace(/\/\*[\s\S]*?\*\//g, '').replace(/linear-gradient\(#000 0 0\)/g, '');
  assert.deepEqual(code.match(/#[0-9a-fA-F]{3,8}\b/g) ?? [], []);
});

// ── Motion ────────────────────────────────────────────────────────────────────────────────

test('reduced motion is the same whether Windows or Chaya asks for it', () => {
  assert.deepEqual([...reducedPicked], [...reducedSystem]);
});

test('reduced motion leaves nothing that moves, scales or rotates', () => {
  assert.equal(reducedPicked.get('--press-scale'), '1');
  assert.equal(reducedPicked.get('--rise'), '0px');
  assert.equal(reducedPicked.get('--stagger'), '0ms');
  assert.equal(reducedPicked.get('--dur-morph'), '0ms');
  assert.equal(reducedPicked.get('--anim-orbit'), 'none');
  assert.equal(reducedPicked.get('--anim-shimmer'), 'chaya-breathe');
  // Every animation still running under reduced motion changes opacity and nothing else
  for (const [name, value] of reducedPicked) {
    if (!name.startsWith('--anim-') || value === 'none') continue;
    const frames = componentsCss.match(new RegExp(`@keyframes ${value} \\{(.*)\\}`))?.[1];
    assert.ok(frames, `${value} has no keyframes`);
    const properties = [...frames.matchAll(/([\w-]+)\s*:/g)].map((m) => m[1]);
    assert.deepEqual([...new Set(properties)], ['opacity'], `${value} animates ${properties}`);
  }
});

test('every animation a token names has keyframes', () => {
  const names = [...day, ...reducedPicked].filter(([k, v]) => k.startsWith('--anim-') && v !== 'none').map(([, v]) => v);
  for (const name of new Set(names)) assert.match(componentsCss, new RegExp(`@keyframes ${name} \\{`), name);
});

test('the brief\'s timings are what the tokens say', () => {
  assert.equal(day.get('--ease-out'), 'cubic-bezier(0.22, 1, 0.36, 1)');
  assert.equal(day.get('--dur-enter'), '220ms');
  assert.equal(day.get('--dur-exit'), '160ms');
  assert.equal(day.get('--dur-hover'), '120ms');
  assert.equal(day.get('--press-scale'), '0.97');
  assert.equal(day.get('--stagger'), '30ms');
  assert.equal(day.get('--rise'), '6px');
  assert.equal(day.get('--shimmer-period'), '1.6s');
  assert.equal(day.get('--orbit-period'), '8s');
});

// ── Download button ───────────────────────────────────────────────────────────────────────

test('a download goes pill, ring, check, pill', () => {
  let state = 'idle';
  for (const [event, expected] of [
    ['start', 'progress'],
    ['pause', 'paused'],
    ['resume', 'progress'],
    ['complete', 'done'],
    ['settle', 'saved'],
    ['reset', 'idle'],
  ]) {
    state = nextDownloadState(state, event);
    assert.equal(state, expected, `after ${event}`);
  }
});

test('a download that fails goes back to the pill, from running or paused', () => {
  assert.equal(nextDownloadState('progress', 'fail'), 'idle');
  assert.equal(nextDownloadState('paused', 'fail'), 'idle');
  // Cancelled, from the button's ring or from the list: back to a pill to start again.
  assert.equal(nextDownloadState('progress', 'cancel'), 'idle');
  assert.equal(nextDownloadState('paused', 'cancel'), 'idle');
  assert.equal(nextDownloadState('saved', 'cancel'), 'saved', 'a saved file stays saved');
});

test('events that do not apply leave the button as it is', () => {
  assert.equal(nextDownloadState('idle', 'pause'), 'idle');
  assert.equal(nextDownloadState('done', 'pause'), 'done');
  assert.equal(nextDownloadState('saved', 'complete'), 'saved');
  assert.equal(nextDownloadState('idle', 'complete'), 'idle');
});

test('a click starts, pauses and resumes, and waits while the check shows', () => {
  assert.equal(clickEvent('idle'), 'start');
  assert.equal(clickEvent('progress'), 'pause');
  assert.equal(clickEvent('paused'), 'resume');
  assert.equal(clickEvent('done'), null);
  assert.equal(clickEvent('saved'), 'reset');
});

test('the ring never shows 100% before the download completes', () => {
  assert.equal(ringPercent(0), 0);
  assert.equal(ringPercent(0.426), 42);
  assert.equal(ringPercent(0.999), 99);
  assert.equal(ringPercent(1), 99);
  assert.equal(ringPercent(1.4), 99);
  assert.equal(ringPercent(-0.2), 0);
  assert.equal(ringPercent(Number.NaN), 0);
});

/**
 * Just enough of a `.dl` button for DownloadButton: its label measures by its text (8px a letter) when the
 * button is on screen, and as nothing at all when it is hidden, as a browser's does.
 */
function fakeButton() {
  const listeners = {};
  const button = {
    shown: true,
    isConnected: true,
    dataset: {},
    style: { width: '', setProperty() {} },
    get offsetWidth() { return this.shown ? 120 : 0; },
    getClientRects() { return this.shown ? [{}] : []; },
    setAttribute() {},
    addEventListener(type, fn) { listeners[type] = fn; },
    append() {},
    querySelector(selector) { return selector === '.dl__label' ? label : null; },
  };
  const label = {
    textContent: 'Download',
    style: {},
    cloneNode() {
      return {
        textContent: '',
        style: {},
        getBoundingClientRect() { return { width: button.shown ? this.textContent.length * 8 : 0 }; },
        remove() {},
      };
    },
  };
  return button;
}

test('a download cancelled while its button is hidden gets its whole pill back, not a sliver', () => {
  globalThis.getComputedStyle = () => ({ getPropertyValue: () => '16px' });
  const el = fakeButton();
  const button = new DownloadButton(el);
  assert.equal(el.style.width, '96px', '8 letters of label and 16px each side');
  button.send('start');
  assert.equal(el.style.width, '40px', 'the ring');
  // The owner cancelled from the Downloads list, with the card's button out of sight.
  el.shown = false;
  button.send('cancel');
  assert.equal(button.state, 'idle');
  assert.notEqual(el.style.width, '32px', 'measured hidden, the label was nothing wide');
  assert.equal(el.style.width, '', 'the pill takes the width of its label');
  // Shown again, it measures as usual from then on.
  el.shown = true;
  button.send('start');
  assert.equal(el.style.width, '40px');
});
