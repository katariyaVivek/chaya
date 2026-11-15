// Chaya's mark (chaya-mark.js): its colours are the tokens', its rest is the complete mark, and every motion stays
// inside its range, with no overshoot and nothing undefined, whatever time or progress it is given.
// Run with: npm test, in desktop/design

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  MARK,
  MARK_COLORS,
  MARK_MOTION,
  REST,
  lineEnd,
  linePath,
  target,
  frameAt,
  blinkK,
  attributesOf,
  markMarkup,
  easeOut,
} from '../chaya-mark.js';

const tokens = readFileSync(fileURLToPath(new URL('../tokens.css', import.meta.url)), 'utf8');
const MODES = ['idle', 'saving', 'unknown', 'paused', 'offline'];
const finite = (pose) => Object.values(pose).every(Number.isFinite);

test('the mark is coloured by the tokens', () => {
  assert.match(tokens, new RegExp(`--mark-light: ${MARK_COLORS.light};`));
  assert.match(tokens, new RegExp(`--mark-shadow: ${MARK_COLORS.shadow};`));
});

test('at rest the line runs all the way across and the twin is all the way out', () => {
  assert.equal(lineEnd(1), MARK.line.x1);
  assert.deepEqual(target('idle', 1, 123), REST);
  const a = attributesOf(REST);
  assert.equal(a.cx, (MARK.sun.x + MARK.twin.dx).toFixed(2));
  assert.equal(a.cy, (MARK.sun.y + MARK.twin.dy).toFixed(2));
  assert.ok(markMarkup('m').includes(`d="${linePath(REST.x0, REST.x1, 0, MARK.line.swell)}"`));
});

test('the line draws further as progress grows, from before the sun to the far side of the twin', () => {
  let last = -Infinity;
  for (let i = 0; i <= 100; i++) {
    const x = lineEnd(i / 100);
    assert.ok(x >= last, `went back at ${i}%`);
    last = x;
  }
  assert.ok(lineEnd(0) < MARK.sun.x - MARK.sun.r, 'nothing drawn at 0');
  assert.equal(lineEnd(0.75), MARK.sun.x + MARK.sun.r, 'the sun is crossed at three quarters');
});

test('progress out of range, NaN and negative time give a pose, never NaN', () => {
  for (const mode of MODES) {
    for (const p of [NaN, -1, 0, 0.5, 1, 2, Infinity, -Infinity, null, undefined]) {
      for (const t of [NaN, -5, 0, 1.3, 1e6]) {
        const pose = target(mode, p, t, REST);
        assert.ok(finite(pose), `${mode} p=${p} t=${t}`);
        const a = attributesOf(pose);
        assert.ok(!/NaN|Infinity/.test(a.d + a.cx + a.cy), `${mode} p=${p} t=${t}`);
      }
    }
  }
});

test('the twin stays between tucked and all the way out, and the line inside the mark', () => {
  for (const mode of MODES) {
    for (let t = 0; t < 30; t += 0.05) {
      for (const p of [0, 0.3, 0.7, 1]) {
        const pose = target(mode, p, t, REST);
        assert.ok(pose.k >= MARK.tuck - 1e-9 && pose.k <= 1 + 1e-9, `${mode} k=${pose.k}`);
        assert.ok(pose.x0 >= MARK.sun.x - MARK.sun.r - MARK_MOTION.streamDash - 1e-9, `${mode} x0=${pose.x0}`);
      }
    }
  }
});

test('moving from one pose to the next never overshoots either end', () => {
  const poses = MODES.map((m) => target(m, 0.4, 2, REST));
  for (const from of poses) {
    for (const mode of MODES) {
      const rec = { mode, p: 0.4, from, t0: 10, d: MARK_MOTION.change, held: from, blink: false };
      for (let t = 9.9; t <= 10 + MARK_MOTION.change + 0.1; t += 0.005) {
        const pose = frameAt(rec, t);
        const goal = target(mode, 0.4, t, from);
        assert.ok(finite(pose));
        // Every number lies between where it started and where it is going
        for (const key of ['x0', 'x1', 'swell', 'k']) {
          const lo = Math.min(from[key], goal[key]) - 1e-9;
          const hi = Math.max(from[key], goal[key]) + 1e-9;
          assert.ok(pose[key] >= lo && pose[key] <= hi, `${key} ${pose[key]} outside ${lo}..${hi} at ${t}`);
        }
      }
    }
  }
});

test('the ease used arrives without overshoot', () => {
  let last = 0;
  for (let i = 0; i <= 1000; i++) {
    const v = easeOut(i / 1000);
    assert.ok(v >= last && v <= 1);
    last = v;
  }
  assert.equal(easeOut(-1), 0);
  assert.equal(easeOut(NaN), 0);
});

test('idle blinks rarely and gently: not in its first seconds, never deeper than its depth', () => {
  const { blinkEvery, blinkFor, blinkDepth } = MARK_MOTION;
  for (let s = -1; s < blinkEvery; s += 0.01) assert.equal(blinkK(s), 1, `blinked at ${s}`);
  let lowest = 1;
  let blinking = 0;
  for (let s = 0; s < 10 * blinkEvery; s += 0.01) {
    const k = blinkK(s);
    lowest = Math.min(lowest, k);
    if (k < 1) blinking += 0.01;
  }
  assert.ok(lowest >= 1 - blinkDepth - 1e-9);
  assert.ok(blinking / (10 * blinkEvery) < 0.08, 'blinks most of the time');
  assert.equal(blinkK(NaN), 1);
});

test('each mark on a page gets its own mask names', () => {
  const a = markMarkup('one');
  const b = markMarkup('two');
  assert.ok(a.includes('id="one-sun"') && a.includes('url(#one-twin)'));
  assert.ok(!b.includes('one-'));
});

test('after the window has been open an hour, a change of state does not strobe the line', () => {
  const { line } = MARK;
  const yAt = (pose, x) => line.y + pose.swell * Math.sin((Math.PI * (x - line.x0)) / (line.x1 - line.x0)) * Math.sin(line.wave * x - pose.phase);
  const hour = 3600;
  const changes = [
    ['idle', 'saving', 0.3],
    ['saving', 'idle', 1],
    ['saving', 'paused', 0.5],
    ['paused', 'saving', 0.5],
    ['unknown', 'idle', 1],
    ['offline', 'saving', 0.5],
  ];
  for (const [fromMode, toMode, p] of changes) {
    const from = target(fromMode, 0.5, hour - 1, REST);
    const rec = { mode: toMode, p, from, t0: hour, d: MARK_MOTION.change, held: from, blink: false };
    let worst = 0;
    for (let t = hour; t < hour + MARK_MOTION.change + 0.2; t += 1 / 60) {
      const a = frameAt(rec, t);
      const b = frameAt(rec, t + 1 / 60);
      for (const x of [16, 24, 32]) worst = Math.max(worst, Math.abs(yAt(a, x) - yAt(b, x)));
    }
    // A frame of travel at full speed and swell moves the line about 0.09 units, and catching up at most half a wave
    // over a change about 0.5 at its fastest; a strobe sweeps the whole swell, 3 units, in a frame
    assert.ok(worst < 0.6, `${fromMode} to ${toMode}: the line jumps ${worst.toFixed(2)} in one frame`);
  }
});
