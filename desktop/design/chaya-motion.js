// Chaya desktop: the motion and state helpers behind components.css.
// Plain ES module, no dependencies, so it works in any web view the desktop app ends up using.

// ---------------------------------------------------------------------------------------------
// Springs as CSS easing
//
// CSS has no spring timing function, but linear() can trace one. springEasing() integrates a
// damped spring from 0 to 1 and samples it, so a press can scale with a real spring while still
// being an ordinary CSS transition. tokens.css carries the press spring this produces; a test
// checks the two agree.

/**
 * @param {{stiffness: number, damping: number, mass?: number}} spring
 * @param {{points?: number, settle?: number}} [options] settle: how close to rest counts as done
 * @returns {{easing: string, duration: number, overshoot: number}} duration in milliseconds
 */
export function springEasing({ stiffness, damping, mass = 1 }, { points = 32, settle = 0.001 } = {}) {
  const dt = 1 / 2000;
  const samples = [];
  let x = 0;
  let v = 0;
  let t = 0;
  let peak = 0;
  // Semi-implicit Euler is plenty at this step for springs this stiff.
  while (t < 3) {
    const a = (-stiffness * (x - 1) - damping * v) / mass;
    v += a * dt;
    x += v * dt;
    t += dt;
    peak = Math.max(peak, x);
    samples.push(x);
    if (Math.abs(x - 1) < settle && Math.abs(v) < settle * 10) break;
  }
  const stops = [];
  for (let i = 0; i <= points; i++) {
    const value = i === 0 ? 0 : i === points ? 1 : samples[Math.round((i / points) * (samples.length - 1))];
    stops.push(round(value));
  }
  return {
    easing: `linear(${stops.join(', ')})`,
    duration: Math.round(t * 1000),
    overshoot: round(peak - 1),
  };
}

const round = (n) => Math.round(n * 10000) / 10000;

/** The press spring from the brief: stiffness 400, damping 30. */
export const PRESS_SPRING = { stiffness: 400, damping: 30 };

// ---------------------------------------------------------------------------------------------
// Download button
//
// The button is a pill that becomes a progress ring, then a check, then a pill again:
//
//   idle ──start──► progress ⇄ paused        progress ──complete──► done ──(settle)──► saved
//                       └──fail, cancel──► idle
//
// nextDownloadState() is the whole state machine, kept free of the DOM so it can be tested on
// its own; DownloadButton applies it to an element.

/** @typedef {'idle' | 'progress' | 'paused' | 'done' | 'saved'} DownloadButtonState */

const TRANSITIONS = {
  idle: { start: 'progress' },
  progress: { pause: 'paused', complete: 'done', fail: 'idle', cancel: 'idle' },
  paused: { resume: 'progress', complete: 'done', fail: 'idle', cancel: 'idle' },
  done: { settle: 'saved' },
  saved: { reset: 'idle' },
};

/**
 * @param {DownloadButtonState} state
 * @param {'start' | 'pause' | 'resume' | 'complete' | 'fail' | 'cancel' | 'settle' | 'reset'} event
 * @returns {DownloadButtonState} the same state when the event does not apply
 */
export function nextDownloadState(state, event) {
  return TRANSITIONS[state]?.[event] ?? state;
}

/** What a click means in each state. Done is mid-celebration, so a click there waits. */
export function clickEvent(state) {
  return { idle: 'start', progress: 'pause', paused: 'resume', saved: 'reset' }[state] ?? null;
}

/** Whole percent for the ring, never 100 before the download is really complete. */
export function ringPercent(fraction) {
  if (!Number.isFinite(fraction)) return 0;
  return Math.min(99, Math.max(0, Math.floor(fraction * 100)));
}

const RING_SIZE = 40; // px, the pill's height, so the morph changes width only
const SETTLE_AFTER = 900; // ms the check stays before the button settles back to a pill

export class DownloadButton {
  /**
   * @param {HTMLButtonElement} el a `.dl` button (see components.css for its markup)
   * @param {{label?: string, savedLabel?: string, onEvent?: (event: string, state: DownloadButtonState) => void}} [options]
   */
  constructor(el, { label = 'Download', savedLabel = 'Saved', onEvent } = {}) {
    this.el = el;
    this.labels = { idle: label, saved: savedLabel };
    this.onEvent = onEvent;
    this.state = /** @type {DownloadButtonState} */ (el.dataset.state || 'idle');
    this.fraction = 0;
    this.labelEl = el.querySelector('.dl__label');
    this.pctEl = el.querySelector('.dl__pct');
    el.addEventListener('click', () => {
      const event = clickEvent(this.state);
      if (event) this.send(event);
    });
    this.render();
  }

  /** @param {number} fraction 0..1; ignored while paused, so the ring stays frozen */
  setProgress(fraction) {
    if (this.state !== 'progress') return;
    this.fraction = fraction;
    this.renderProgress();
  }

  send(event) {
    const next = nextDownloadState(this.state, event);
    if (next === this.state) return;
    this.state = next;
    if (event === 'start' || event === 'reset' || event === 'fail' || event === 'cancel') this.fraction = 0;
    if (event === 'complete') {
      this.fraction = 1;
      clearTimeout(this.settleTimer);
      this.settleTimer = setTimeout(() => this.send('settle'), SETTLE_AFTER);
    }
    this.render();
    this.onEvent?.(event, next);
  }

  render() {
    const { el, state } = this;
    el.dataset.state = state;
    if (this.labelEl && this.labels[state]) this.labelEl.textContent = this.labels[state];
    if (el.isConnected) {
      // From its natural width the first time, so even the first change of shape animates.
      if (!el.style.width) {
        el.style.width = `${el.offsetWidth}px`;
        void el.offsetWidth;
      }
      el.style.width = `${this.widthFor(state)}px`;
    } else {
      // Not on screen yet: nothing to measure, so the pill keeps the width of its label.
      el.style.width = '';
    }
    this.renderProgress();
  }

  renderProgress() {
    const { el, state } = this;
    const pct = state === 'done' || state === 'saved' ? 100 : ringPercent(this.fraction);
    el.style.setProperty('--p', String(pct));
    if (this.pctEl) this.pctEl.textContent = `${pct}%`;
    el.setAttribute(
      'aria-label',
      {
        idle: this.labels.idle,
        progress: `Downloading, ${pct}%. Press to pause.`,
        paused: `Paused at ${pct}%. Press to resume.`,
        done: 'Downloaded',
        saved: this.labels.saved,
      }[state],
    );
  }

  /** The pill's width comes from its label, measured, so width can animate between real values. */
  widthFor(state) {
    if (state === 'progress' || state === 'paused' || state === 'done') return RING_SIZE;
    const probe = this.labelEl.cloneNode(true);
    probe.textContent = this.labels[state];
    Object.assign(probe.style, { position: 'absolute', visibility: 'hidden', width: 'auto' });
    this.el.append(probe);
    const width = Math.ceil(probe.getBoundingClientRect().width);
    probe.remove();
    const pad = parseFloat(getComputedStyle(this.el).getPropertyValue('--dl-pad')) || 16;
    return width + pad * 2;
  }
}

// ---------------------------------------------------------------------------------------------
// Lists, theme and motion settings

/** Sets --i on each child so it enters 30 ms after the one before (CSS covers the first 12). */
export function stagger(list) {
  [...list.children].forEach((child, i) => child.style.setProperty('--i', String(i)));
}

/** Replays a list's entrance, for content that arrives after the list first showed. */
export function replayEntrance(list) {
  list.classList.remove('stagger');
  void list.offsetWidth;
  stagger(list);
  list.classList.add('stagger');
}

/** Plays an element's exit (160 ms), then removes it. */
export function leave(el) {
  el.classList.add('is-leaving');
  el.addEventListener('animationend', () => el.remove(), { once: true });
}

const THEME_ATTR = { day: 'day', night: 'night' };

/** @param {'day' | 'night' | 'system'} theme system follows Windows' light or dark setting */
export function setTheme(theme, root = document.documentElement) {
  if (theme === 'system') root.removeAttribute('data-theme');
  else root.dataset.theme = THEME_ATTR[theme];
}

/** @param {'reduce' | 'system'} motion reduce gives opacity-only motion whatever Windows says */
export function setMotion(motion, root = document.documentElement) {
  if (motion === 'reduce') root.dataset.motion = 'reduce';
  else root.removeAttribute('data-motion');
}
