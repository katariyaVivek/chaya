# Chaya desktop design system

The look and motion of Chaya for Windows. The Android app keeps its own system in
[`../../DESIGN.md`](../../DESIGN.md); the two share a name and a personality (fluid, assured, soft),
not tokens.

Open [`showcase.html`](showcase.html) through any local web server (`python -m http.server` in this
folder) to see every token and component live, in both themes, with full or reduced motion.

## Files

| File | What it is |
|---|---|
| `tokens.css` | Every color, size, radius, space and timing, for Day and Night. The only place values live |
| `fonts.css`, `fonts/` | Geist and Geist Mono (bundled, OFL); Cabinet Grotesk, which each builder downloads from Fontshare (`fonts/README.md`) |
| `components.css` | Base styles and the signature components, built only from tokens |
| `chaya-motion.js` | The spring-to-CSS generator, the download button's state machine and controller, list and theme helpers. An ES module with no dependencies |
| `showcase.html` | The living reference |
| `test/` | `npm test`: the spring, both themes' contrast, reduced motion, the button's states |

Load the CSS in this order: `tokens.css`, `fonts.css`, `components.css`.

## Principles

Taken from what makes the Family wallet feel smooth, and Chaya's own three words:

1. **Show only what the moment needs.** One primary control per screen. A download is a pill, then
   a ring, then a check, never a pill plus a bar plus a spinner.
2. **Change shape, do not jump.** A state change moves between shapes (the pill shrinks into the
   ring) rather than swapping one element for another.
3. **A small reward when a job finishes.** The check draws in and the button glows once. Nothing
   bigger.
4. **Warm light, not purple neon.** Downie, the closest app to Chaya, is dark with purple
   gradients. Chaya is light and shadow: warm amber light under frosted glass.

## Themes

Day is the default. `<html data-theme="night">` picks Night, `data-theme="day"` picks Day, and no
attribute follows Windows (light or dark). `setTheme('day' | 'night' | 'system')` in
`chaya-motion.js` sets it. These are the same three choices the phone offers.

| Token | Day | Night | Use |
|---|---|---|---|
| `--bg` | `#F6F4F0` | `#0B0B0D` | Behind everything |
| `--surface` | white 80% | white 6% | Glass: anything that floats (rows, bars, cards, the window) |
| `--surface-blur` | 20px | 24px | The glass's backdrop blur |
| `--border` | ink 6% | white 8% | The hairline edge of glass |
| `--text` | `#1A1917` | `#F2F1EE` | Titles and body |
| `--muted` | `#6B6862` | `#8A8883` | Secondary lines, placeholders, idle icons |
| `--wash`, `--wash-strong` | ink 4%, 7% | white 5%, 9% | Hover; pressed, selected, tracks and empty thumbnails |
| `--shadow` | `0 1px 2px` 4% + `0 8px 24px` 6% | `0 8px 32px` 50% | Glass elevation |
| `--focus` | `--text` | `--accent` | Focus rings and a focused field's edge |
| `--accent` | `#FF9A3C` | `#FF9A3C` | Light: glow, edges, thin progress |
| `--accent-glow` | `0 0 24px` accent 35% | same | The glow itself |
| `--accent-edge` | accent 45% | same | Border of the one primary control, and of the ring |

Contrast, checked by the tests: text is 16:1 (Day) and 17:1 (Night). Muted is at least 4.9:1 on
both the ground and the glass in both themes.

**The accent is light, never paint.** Use it as a glow, a 1px edge, a 2px progress line or a ring
stroke. Never fill a button, a badge or a background with it. On Day it is only 1.9:1 against the
ground, so it is never text and never the only sign of focus there. Progress it draws is always
also written as a number.

**Glass needs something behind it.** A blur over a flat color looks like a flat color. The app
window's background carries one or two very soft accent lights (see `showcase.html`'s `body`), so
glass has something to soften.

## Type

| Role | Face | Size / line |
|---|---|---|
| UI, large (the address field, lead text) | Geist 400 | 16 / 24 |
| UI, body | Geist 400, 500 for labels, 600 for headings in cards | 14 / 20 |
| UI, small (meta lines, captions) | Geist 400 | 13 / 18 |
| Numbers that count: sizes, speeds, %, times | Geist Mono, `font-variant-numeric: tabular-nums` (class `.num`) | follows its line |
| Page titles (`.title`) | Cabinet Grotesk 600 | 24 / 32 |
| Wordmark (`.wordmark`) | Cabinet Grotesk 600 | 20 |

Cabinet Grotesk is for page titles and the wordmark only. If Geist feels too technical against it,
Satoshi (rounder) or General Sans (calmer) from Fontshare are the alternatives to try. Geist Mono
stays for numbers either way.

Fallbacks are Windows' own: Segoe UI Variable, Cascadia Mono, Consolas.

## Shape and space

- Radii: `--radius-sm` 10 (thumbnails, chips, keys), `--radius-md` 14 (rows, fields),
  `--radius-lg` 20 (cards, the window). Pills are `--radius-pill`, fully round.
- Space: an 8px grid, `--space-1` 8 to `--space-8` 64. `--space-half` (4) is only for an icon
  beside its label, or a title over its meta line.
- Controls are 40px tall (`--control-height`); the address field is 48.

## Motion

| Token | Value | For |
|---|---|---|
| `--ease-out` | `cubic-bezier(0.22, 1, 0.36, 1)` | Every tween |
| `--dur-enter` / `--dur-exit` / `--dur-hover` | 220 / 160 / 120 ms | Appearing / leaving / hover |
| `--spring-press`, `--dur-spring` | `linear(…)`, 453 ms | Press: a spring, stiffness 400, damping 30 |
| `--press-scale` | 0.97 | `.pressable:active` |
| `--stagger`, `--rise` | 30 ms, 6px | `.stagger` lists: each child fades up 6px, 30 ms after the last |
| `--shimmer-period` | 1.6 s | The *Finding media…* sweep |
| `--orbit-period` | 8 s | The active download row's border light |

**Springs in CSS.** CSS has no spring easing, so `springEasing()` in `chaya-motion.js` integrates
the spring and writes it as a `linear()` easing. The press spring settles in 453 ms and overshoots
by 2.8% of a 3% squeeze, which you cannot see as a bounce. If the spring changes, run
`springEasing(PRESS_SPRING)` and paste its `easing` and `duration` into `tokens.css`; a test fails
until they agree.

**Reduced motion is opacity-only.** Under `prefers-reduced-motion: reduce` (Windows' *Animation
effects* off), or `data-motion="reduce"` on `<html>` (Chaya's own setting, `setMotion()`), the tokens
switch: presses do not scale, lists fade without rising, the download button swaps shapes without
resizing, the row's light stops, the shimmer becomes a gentle fade, the check fades in. Progress
still fills, because that is information. Components get this for free by animating through the
`--anim-*` tokens; a new animation must do the same, and the tests check that every reduced
animation touches only `opacity`.

## Components

### Glass, pills, presses

```html
<div class="glass">…</div>                                   <!-- the surface material -->
<button class="pill glass pressable">Show in folder</button>  <!-- a secondary action -->
<button class="pill pill--primary glass pressable">Download</button> <!-- the one primary action -->
<button class="icon-btn pressable" aria-label="Pause"><svg>…</svg></button>
```

Hover on a pill lays a wash over the glass. The primary pill has an accent edge and glows on hover.

### a. Download button

A pill that shrinks into a progress ring counting in Geist Mono, draws a check when done, glows
once, then settles back to a pill reading *Saved*.

```html
<button class="dl pill pill--primary glass" type="button">
  <span class="dl__label">Download</span>
  <span class="dl__ring" aria-hidden="true">
    <svg class="dl__svg" viewBox="0 0 40 40">
      <circle class="dl__track" cx="20" cy="20" r="17"/>
      <circle class="dl__arc" cx="20" cy="20" r="17" pathLength="100"/>
      <path class="dl__check" d="M14 20.5l4 4 8-9" pathLength="1"/>
    </svg>
    <span class="dl__pct num">0%</span>
  </span>
</button>
```

```js
import { DownloadButton } from './chaya-motion.js';
const button = new DownloadButton(el, { onEvent: (event, state) => { /* start, pause, resume… */ } });
button.setProgress(0.42);   // while downloading
button.send('complete');    // or 'fail', or 'cancel'
```

- States (`data-state`): `idle` → `progress` ⇄ `paused` → `done` → `saved` → `idle`. A failure or
  a cancel goes back to `idle`, the pill ready to start again. `nextDownloadState()` is the whole
  machine.
- A click starts, pauses and resumes. While the check shows, clicks wait.
- **Paused:** the ring stops where it is and dims to 50%. Its light stops breathing.
- The ring shows at most 99% until the download really completes.
- Width animates between measured values (the pill's label, the 40px ring), so the morph is a real
  change of shape. Height never changes.
- The button's accessible name says the state and the percentage ("Downloading, 42%. Press to
  pause.").

### b. *Finding media…*

```html
<span class="finding glass" role="status"><span class="finding__text">Finding media…</span></span>
```

A warm light (text mixed with the accent) sweeps across the words every 1.6 s while a page or a
link is looked up. With reduced motion the words fade gently instead. Use it only while something
is really being looked up, and only this once per screen.

### c. Address and search bar

```html
<div class="urlbar">
  <label class="urlbar__field glass" for="address">
    <svg class="urlbar__icon">…</svg>
    <input class="urlbar__input" id="address" placeholder="Paste a link or search">
    <span class="kbd">Ctrl L</span>
  </label>
</div>
```

On focus, amber light fades in under the field (220 ms; out in 160 ms). The light sits behind the
glass, so the field's blur softens it into light under glass. The field's edge also turns to
`--focus`, so focus is visible without the glow. Like Raycast's command bar, this field is the
keyboard's home: Ctrl+L focuses it, paste a link, Enter starts the download.

### d. Download row

```html
<li class="row glass" data-state="downloading">   <!-- or "paused"; no data-state when idle -->
  <div class="row__thumb"><svg>…</svg></div>
  <div class="row__body">
    <div class="row__title">Monsoon over the Western Ghats (1080p).mp4</div>
    <div class="row__meta num">412.6 MB of 1.21 GB · 8.4 MB/s · 1 min 35 s left</div>
    <div class="row__bar"><span style="--p: 34"></span></div>
  </div>
  <span class="row__pct num">34%</span>
  <button class="icon-btn pressable" aria-label="Pause"><svg>…</svg></button>
</li>
```

While downloading, a thin conic-gradient light circles the row's border once every 8 s. Paused, the
light stays where it stopped at half strength. Waiting, finished and failed rows have only the
hairline. Only downloading rows move, so the eye finds them.

### Lists

Add `.stagger` to a list: children fade in and rise 6px, 30 ms apart (the first 12 by CSS; call
`stagger(list)` for longer ones, `replayEntrance(list)` when new content arrives). `leave(el)` plays
the 160 ms exit and then removes the element.

## Decisions beyond the brief

- **Day has a border token** (ink at 6%). The brief gave Night a border and Day none; without it,
  white glass on the pale ground loses its edge.
- **Wash tokens** for hover and pressed states, derived from the text color in each theme.
- **Focus on Day uses the text color**, because the accent is 1.9:1 there.
- **The download button settles to *Saved*.** The brief says it settles back. Saying *Download*
  again would invite a second copy.
- **"Static when idle"** is read as: a paused row's light stops where it is, and rows that are not
  downloading have no light.
- **The browser's page runs edge to edge under the bar**, with a hairline above it and no glass card
  around it. A webview is drawn by Windows as a plain rectangle over the window, so inside a rounded
  card it looked pasted on: its square corners over the card's round ones. Below it, the window's own
  corners (Windows rounds them) finish it, as in any browser.
- **A panel over the page lies over a still picture of it.** The page's webview is drawn over the
  window, so nothing the window draws can cover it. While the settings or the ad blocker's panel is
  open, the page is hidden and a still of it, taken as the panel opens, stands in its place. Before
  this, the page went blank behind a panel, which read as broken. Over a page, a panel is the ground
  colour, not glass: a page can be anything, and Night's glass let a white page wash out its words. (Windows only: Linux, a development
  platform, shows the ground.)
- **A link that couldn't be looked up has no poster box.** The empty box only made sense while
  waiting for a picture.
- **Nothing sits between the bar and the page, and the bar is one 56px row.** What a page holds is
  named by a pill in the bar (accent-edged while new) and listed in its panel. Shown above the page, it
  pushed the page down about 100px after the page had loaded, and the bar and its spacing took 90px.
- **No wordmark in the window.** Windows' title bar already names the app; the top of the window holds
  only the settings, which join the bar's row while a page is open.
- **Glass and the shimmer** are the desktop's own choices. `PRODUCT.md` lists glassmorphism and
  gradient text among the phone app's anti-references. On desktop, glass is the one surface
  material, used consistently, and the shimmer is used on exactly one loading label. The phone's
  rules are unchanged.

## References

- [Family Values](https://benji.org/family-values), Benji Taylor: simplicity, fluidity, delight.
- [rauno.me/craft](https://rauno.me/craft): Combobox, Vercel Agent, DD System for small details.
- [motion.dev/examples](https://motion.dev/examples): "layout", "shared element" and "number" for a row
  opening into its detail, a thumbnail moving into the preview, and a counter rolling up. These fit
  if the desktop app's UI is built on web tech.
- [Raycast](https://www.raycast.com/): the keyboard-first command bar, and the light streaks on black
  that set the mood for Night.
- [Downie](https://software.charliemonroe.net/downie/): the app to look different from.
