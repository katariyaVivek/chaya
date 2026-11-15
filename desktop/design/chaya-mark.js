/*
 * Chaya's mark: the sun and its shadow-twin, one line of light through both.
 *
 * In the story Chhaya was made from the sun-wife's shadow, and a download is a copy of what is on the page. The
 * line draws across the sun as a download goes; the twin, waiting tucked behind the sun, slides out as the copy
 * arrives. At rest the mark is complete. README.md (Mark) gives its rules; CLAUDE.md repeats the ones that matter.
 *
 * Everything is drawn from the constants below; there are no stored path strings. The resting pose is plain SVG
 * markup (markMarkup), so it is right with no script at all. mountMark() animates that markup by setting
 * attributes on the elements it holds, from frameAt(), a pure function of time. An ES module with no dependencies.
 */

/** The geometry, in a 64-unit box. */
export const MARK = Object.freeze({
  box: 64,
  sun: Object.freeze({ x: 24, y: 27, r: 16 }),
  // The twin's centre, as an offset from the sun's, and its radius
  twin: Object.freeze({ dx: 17, dy: 11, r: 15 }),
  // The hairline of space around the sun where it crosses the twin
  ring: 2.2,
  // The line: where it runs, how wide its gap is, its resting swell and wave number, and how finely it is drawn
  line: Object.freeze({ y: 33, x0: 2, x1: 62, gap: 3.6, swell: 1.2, wave: 0.16, samples: 64 }),
  // How far out the twin sits while it waits (a fraction of its offset): far enough to keep two suns in view
  tuck: 0.32,
});

/** The two colours. tokens.css carries the same values as --mark-light and --mark-shadow (a test keeps them equal). */
export const MARK_COLORS = Object.freeze({ light: '#F2B25A', shadow: '#B9792F' });

/** Motion, in seconds. */
export const MARK_MOTION = Object.freeze({
  speed: 3.2, // how fast the line's wave travels while something is saving (radians a second)
  liveSwell: 1.6, // the line's swell while saving: a little livelier than at rest
  sleepSwell: 0.5, // and while offline: calmer
  streamPass: 1.2, // a stream's light crosses the sun once in this time
  streamDash: 14, // the length of that light
  breathe: 0.08, // how far the twin moves while a stream is saving
  change: 0.45, // moving from one state to the next
  step: 0.3, // following progress as it arrives
  saved: 0.42, // settling when a download is saved
  blinkEvery: 12, // while idle, the twin blinks once this often
  blinkFor: 0.8, // for this long
  blinkDepth: 0.18, // tucking this far in
});

/** The resting pose: the line all the way across, at rest, and the twin all the way out. */
export const REST = Object.freeze({ x0: MARK.line.x0, x1: MARK.line.x1, phase: 0, swell: MARK.line.swell, k: 1 });

const SUN_LEFT = MARK.sun.x - MARK.sun.r;
const SUN_RIGHT = MARK.sun.x + MARK.sun.r;
const clamp01 = (v) => (Number.isFinite(v) ? Math.min(1, Math.max(0, v)) : 0);
const lerp = (a, b, k) => a + (b - a) * k;
/** Ease-out (cubic): arrives without overshooting. */
export const easeOut = (k) => 1 - Math.pow(1 - clamp01(k), 3);
const easeInOut = (k) => {
  const x = clamp01(k);
  return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
};
const fixed = (n) => n.toFixed(2);
const TURN = 2 * Math.PI;
/** The wave's phase for this much travel, kept within one turn: the line is the same a turn later. */
const wrap = (phase) => ((phase % TURN) + TURN) % TURN;

/**
 * How far the line has drawn for progress p. Three quarters of a download cross the sun; the last quarter crosses
 * the twin as it arrives, so the line is never finished before the copy is.
 */
export function lineEnd(p) {
  const q = clamp01(p);
  return q < 0.75 ? lerp(SUN_LEFT - 2, SUN_RIGHT, q / 0.75) : lerp(SUN_RIGHT, MARK.line.x1, (q - 0.75) / 0.25);
}

/** The line's path from x0 to x1, with its wave at this phase and swell. '' when there is nothing to draw. */
export function linePath(x0, x1, phase, swell) {
  const { line } = MARK;
  const a = Math.max(line.x0, x0);
  const b = Math.min(line.x1, x1);
  if (!(b - a > 0.5)) return '';
  const span = line.x1 - line.x0;
  const n = Math.max(2, Math.round(((b - a) * line.samples) / span));
  let d = '';
  for (let i = 0; i <= n; i++) {
    const x = a + ((b - a) * i) / n;
    const taper = Math.sin((Math.PI * (x - line.x0)) / span);
    const y = line.y + swell * taper * Math.sin(line.wave * x - phase);
    d += `${i ? ' L' : 'M'}${fixed(x)},${fixed(y)}`;
  }
  return d;
}

/** Where the twin's centre is, with k = 1 all the way out and k = MARK.tuck waiting behind the sun. */
export function twinCentre(k) {
  return { cx: MARK.sun.x + MARK.twin.dx * k, cy: MARK.sun.y + MARK.twin.dy * k };
}

/**
 * The pose a state asks for at time t (seconds). `held` is the pose when the state began, for states that stand
 * still (paused, offline) and so keep the phase they had.
 *   idle: the complete mark; saving: p known; unknown: a stream or anything without a size; paused; offline.
 */
export function target(mode, p, t, held = REST) {
  const { speed, liveSwell, sleepSwell, streamPass, streamDash, breathe } = MARK_MOTION;
  const time = Number.isFinite(t) ? Math.max(0, t) : 0;
  switch (mode) {
    case 'saving':
    case 'paused': {
      const q = clamp01(p);
      const phase = mode === 'paused' ? held.phase : wrap(speed * time);
      return { x0: MARK.line.x0, x1: lineEnd(q), phase, swell: liveSwell, k: lerp(MARK.tuck, 1, easeInOut(q)) };
    }
    case 'unknown': {
      const pass = (time / streamPass) % 1;
      const c = lerp(SUN_LEFT - streamDash, SUN_RIGHT + 6, pass);
      const k = MARK.tuck + breathe * (0.5 - 0.5 * Math.cos(pass * 2 * Math.PI));
      return { x0: c, x1: c + streamDash, phase: wrap(speed * time), swell: liveSwell, k };
    }
    case 'offline':
      return { x0: MARK.line.x0, x1: MARK.line.x1, phase: held.phase, swell: sleepSwell, k: MARK.tuck };
    default:
      return REST;
  }
}

/** While idle, a rare blink: the twin dips a little way in and comes back out. k for the time since idle began. */
export function blinkK(sinceIdle) {
  const { blinkEvery, blinkFor, blinkDepth } = MARK_MOTION;
  if (!(sinceIdle > 0)) return 1;
  const into = sinceIdle % blinkEvery;
  if (sinceIdle < blinkEvery || into > blinkFor) return 1;
  return 1 - blinkDepth * Math.sin((Math.PI * into) / blinkFor);
}

/** Part way from pose a to pose b. The wave's phase is b's, less a share of `lag`, how far behind it a began. */
function mix(a, b, k, lag) {
  return { x0: lerp(a.x0, b.x0, k), x1: lerp(a.x1, b.x1, k), phase: b.phase - lag * (1 - k), swell: lerp(a.swell, b.swell, k), k: lerp(a.k, b.k, k) };
}

/**
 * The pose at time t for a record of what the mark was asked last: { mode, p, from, t0, d, held }. It moves from
 * `from` to the state's pose over d seconds, eased out, then follows the state. A pure function of t.
 */
export function frameAt(rec, t) {
  const since = Number.isFinite(t) ? t - rec.t0 : 0;
  const goal = target(rec.mode, rec.p, t, rec.held);
  const k = rec.d > 0 ? easeOut(since / rec.d) : 1;
  // How far the wave was behind the new state's when the change began, the short way round: it closes that gap,
  // never sweeping through turns however long the window has been open
  const lag = wrap(target(rec.mode, rec.p, rec.t0, rec.held).phase - rec.from.phase + Math.PI) - Math.PI;
  const pose = k >= 1 ? goal : mix(rec.from, goal, k, lag);
  if (rec.mode === 'idle' && rec.blink !== false && k >= 1) return { ...pose, k: pose.k * blinkK(since - rec.d) };
  return pose;
}

/** The SVG attributes for a pose. */
export function attributesOf(pose) {
  const { cx, cy } = twinCentre(pose.k);
  return { d: linePath(pose.x0, pose.x1, pose.phase, pose.swell), cx: fixed(cx), cy: fixed(cy) };
}

/**
 * The mark's markup, at rest. `id` names its masks, so give each mark on a page its own. paint 'css' colours it
 * from the tokens (in the app); 'inline' writes the colours in (a file that stands alone, such as the icon).
 * gap and ring widen the line's gap and the hairline for the icon's smallest sizes, where the usual ones blur.
 */
export function markMarkup(id, { size = 64, label = 'Chaya', paint = 'css', gap = MARK.line.gap, ring = MARK.ring } = {}) {
  const { sun, twin, line } = MARK;
  const a = attributesOf(REST);
  const fill = (part) => (paint === 'inline' ? ` fill="${MARK_COLORS[part]}"` : '');
  const cut = `<path class="mark__gap" d="${a.d}" fill="none" stroke="#000" stroke-width="${gap}" stroke-linecap="round"/>`;
  const name = label ? ` role="img" aria-label="${label}"` : ' aria-hidden="true"';
  return `<svg class="mark" viewBox="0 0 ${MARK.box} ${MARK.box}" width="${size}" height="${size}"${name}>` +
    `<defs><mask id="${id}-sun"><rect width="${MARK.box}" height="${MARK.box}" fill="#fff"/>${cut}</mask>` +
    `<mask id="${id}-twin"><rect width="${MARK.box}" height="${MARK.box}" fill="#fff"/>` +
    `<circle cx="${sun.x}" cy="${sun.y}" r="${fixed(sun.r + ring)}" fill="#000"/>${cut}</mask></defs>` +
    `<circle class="mark__twin" cx="${a.cx}" cy="${a.cy}" r="${twin.r}"${fill('shadow')} mask="url(#${id}-twin)"/>` +
    `<circle class="mark__sun" cx="${sun.x}" cy="${sun.y}" r="${sun.r}"${fill('light')} mask="url(#${id}-sun)"/>` +
    `</svg>`;
}

/** Whether motion is reduced: Chaya's own setting, or Windows' when Chaya follows it. */
function reducedMotion() {
  if (typeof document === 'undefined') return false;
  if (document.documentElement.dataset.motion === 'reduce') return true;
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * Brings a mark's markup to life. Returns { set(state), stop() }. state is { mode, p, saved }: mode as target()
 * takes it, p the progress (0..1), saved how many downloads have finished so far, so that a finished one settles
 * the mark with a short snap rather than the slower return a cancelled one gets.
 *
 * Frames are drawn by setting attributes on the elements held here, never by rebuilding markup. Nothing runs
 * while the mark rests, while the window is hidden, or with reduced motion (then each state is shown still).
 */
export function mountMark(svg, { blink = true } = {}) {
  const gaps = [...svg.querySelectorAll('.mark__gap')];
  const twin = svg.querySelector('.mark__twin');
  const clock = () => performance.now() / 1000;
  let rec = { mode: 'idle', p: 1, from: REST, t0: clock(), d: 0, held: REST, blink };
  let saved = null;
  let raf = 0;
  let timer = 0;
  let shown = '';

  function draw(pose) {
    const a = attributesOf(pose);
    const key = `${a.d}|${a.cx}|${a.cy}`;
    if (key === shown) return;
    shown = key;
    for (const g of gaps) g.setAttribute('d', a.d);
    twin.setAttribute('cx', a.cx);
    twin.setAttribute('cy', a.cy);
  }

  function moving(t) {
    if (rec.mode === 'saving' || rec.mode === 'unknown') return true;
    if (t - rec.t0 < rec.d) return true;
    return rec.mode === 'idle' && blink && blinkK(t - rec.t0 - rec.d) < 1;
  }

  function wake() {
    clearTimeout(timer);
    timer = 0;
    if (raf || !svg.isConnected) return;
    if (reducedMotion()) return draw(target(rec.mode, rec.p, 0, REST));
    if (typeof document !== 'undefined' && document.hidden) return;
    raf = requestAnimationFrame(frame);
  }

  function frame() {
    raf = 0;
    if (!svg.isConnected) return stop(); // gone from the page: let go of everything
    if (reducedMotion()) return draw(target(rec.mode, rec.p, 0, REST));
    const t = clock();
    draw(frameAt(rec, t));
    if (moving(t)) {
      raf = requestAnimationFrame(frame);
    } else if (rec.mode === 'idle' && blink) {
      // Asleep until the next blink is due
      const { blinkEvery } = MARK_MOTION;
      const since = t - rec.t0 - rec.d;
      const next = (Math.floor(since / blinkEvery) + 1) * blinkEvery;
      timer = setTimeout(wake, Math.max(50, (next - since) * 1000));
    }
  }

  function set({ mode = 'idle', p = 1, saved: count = saved } = {}) {
    const q = clamp01(p);
    if (mode === rec.mode && q === rec.p && count === saved) return;
    const t = clock();
    const now = frameAt(rec, t);
    const finished = mode === 'idle' && saved !== null && count > saved && rec.mode !== 'idle';
    const d = finished ? MARK_MOTION.saved : mode === rec.mode ? MARK_MOTION.step : MARK_MOTION.change;
    rec = { mode, p: q, from: now, t0: t, d, held: now, blink };
    saved = count;
    if (raf) return;
    wake();
  }

  const onVisible = () => (document.hidden ? (cancelAnimationFrame(raf), (raf = 0)) : wake());
  const media = typeof matchMedia === 'function' ? matchMedia('(prefers-reduced-motion: reduce)') : null;
  const watcher = typeof MutationObserver === 'function' ? new MutationObserver(wake) : null;
  document.addEventListener('visibilitychange', onVisible);
  media?.addEventListener('change', wake);
  watcher?.observe(document.documentElement, { attributes: true, attributeFilter: ['data-motion'] });
  wake();

  function stop() {
    cancelAnimationFrame(raf);
    clearTimeout(timer);
    raf = 0;
    document.removeEventListener('visibilitychange', onVisible);
    media?.removeEventListener('change', wake);
    watcher?.disconnect();
  }

  return {
    set,
    stop,
    /** For checks: the record frames are drawn from. */
    get record() {
      return rec;
    },
  };
}
