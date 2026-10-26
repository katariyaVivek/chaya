// The Chaya window: the address bar, what it recognises, and the appearance settings.
import { setTheme, setMotion } from './design/chaya-motion.js';

const tauri = window.__TAURI__;

// ── Appearance ───────────────────────────────────────────────────────────────────────────────

const SETTINGS_KEY = 'chaya.appearance';

function loadAppearance() {
  try {
    return { theme: 'system', motion: 'system', ...JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}') };
  } catch {
    return { theme: 'system', motion: 'system' };
  }
}

function saveAppearance(appearance) {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(appearance));
  } catch {
    // Not saved; the choice still applies until the window closes.
  }
}

function applyAppearance({ theme, motion }) {
  setTheme(theme);
  setMotion(motion);
  // The title bar is Windows' own; tell it which theme to draw. null follows Windows.
  tauri?.window
    .getCurrentWindow()
    .setTheme({ day: 'light', night: 'dark' }[theme] ?? null)
    .catch(() => {});
  document.querySelectorAll('[data-theme-choice]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.themeChoice === theme)));
  document.querySelectorAll('[data-motion-choice]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.motionChoice === motion)));
}

const appearance = loadAppearance();
applyAppearance(appearance);

for (const [attr, key] of [['themeChoice', 'theme'], ['motionChoice', 'motion']]) {
  document.querySelectorAll(`[data-${key}-choice]`).forEach((button) =>
    button.addEventListener('click', () => {
      appearance[key] = button.dataset[attr];
      applyAppearance(appearance);
      saveAppearance(appearance);
    }),
  );
}

const settingsButton = document.getElementById('settings-button');
const settingsPanel = document.getElementById('settings-panel');
function showSettings(open) {
  settingsPanel.hidden = !open;
  settingsButton.setAttribute('aria-expanded', String(open));
}
settingsButton.addEventListener('click', () => showSettings(settingsPanel.hidden));
document.addEventListener('click', (e) => {
  if (!settingsPanel.hidden && !e.target.closest('.settings')) showSettings(false);
});

// ── The address bar ─────────────────────────────────────────────────────────────────────────

const address = document.getElementById('address');
const result = document.getElementById('result');
const template = document.getElementById('result-template');
const lookupTemplate = document.getElementById('lookup-template');

/** What the window says about a link that is not looked up. */
function describe(link) {
  switch (link.kind) {
    case 'account':
      return {
        icon: 'i-account',
        title: `@${link.username} on ${link.site}`,
        url: link.url,
        note: 'Saving everything an account has posted, as one ZIP, comes in a later build.',
      };
    case 'web':
      return {
        icon: 'i-page',
        title: `A page on ${hostOf(link.url)}`,
        url: link.url,
        note: 'Chaya for Windows does not open pages yet. Paste a link to a video or post on YouTube, Instagram, TikTok or X.',
      };
    default:
      return {
        icon: 'i-search',
        title: 'Search is not here yet',
        url: link.text,
        note: 'Paste a link to a video or post on YouTube, Instagram, TikTok or X.',
      };
  }
}

function hostOf(url) {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

function show(link) {
  if (link.kind === 'media') {
    lookUp(link);
    return;
  }
  forget();
  const { icon, title, url, note } = describe(link);
  const card = template.content.firstElementChild.cloneNode(true);
  card.querySelector('use').setAttribute('href', `#${icon}`);
  card.querySelector('.card__title').textContent = title;
  card.querySelector('.card__url').textContent = url;
  card.querySelector('.card__note').textContent = note;
  result.replaceChildren(card);
}

// ── Looking a link up ──────────────────────────────────────────────────────────────────────

/** Bumped whenever the link on show changes, so a late answer for a link already left is dropped. */
let looking = 0;

function forget() {
  looking++;
  result.replaceChildren();
}

/** Failures worth another try as they are; the others need something about the link to change. */
const RETRYABLE = new Set(['network', 'engine', 'unknown', 'bot_check']);

async function lookUp(link) {
  const ask = ++looking;
  const card = lookupTemplate.content.firstElementChild.cloneNode(true);
  card.querySelector('.card__title').textContent = `${link.site} ${link.lookingFor}`;
  card.querySelector('.card__meta').textContent = link.url;
  card.querySelector('.card__state').replaceChildren(finding(`Finding the ${link.lookingFor}…`));
  result.replaceChildren(card);

  let answer;
  try {
    answer = await tauri.core.invoke('look_up', { url: link.url });
  } catch (error) {
    console.error('look_up failed', error);
    answer = { kind: 'failed', reason: 'engine', message: "The video finder couldn't start. Try again in a moment." };
  }
  if (ask !== looking) return;
  if (answer.kind === 'found') showFound(card, answer);
  else showFailed(card, link, answer);
}

function showFound(card, found) {
  card.querySelector('.card__title').textContent = found.title;
  card.querySelector('.card__meta').textContent = [found.author, found.duration].filter(Boolean).join(' · ');
  const poster = card.querySelector('.poster img');
  if (found.thumbnail?.startsWith('https://')) {
    poster.addEventListener('error', () => poster.removeAttribute('src'), { once: true });
    poster.src = found.thumbnail;
  }

  const state = card.querySelector('.card__state');
  const parts = [];
  if (found.post) {
    const { pictures, videos } = found.post;
    const counted = [count(pictures, 'picture'), count(videos, 'video')].filter(Boolean).join(' and ');
    parts.push(paragraph(`${counted}, each saved as it is.`));
  } else {
    const list = document.createElement('ul');
    list.className = 'choices stagger';
    for (const choice of found.choices) {
      const row = document.createElement('li');
      row.className = 'choice';
      row.append(span('choice__label', choice.label), span('choice__detail num', choice.detail));
      list.append(row);
    }
    parts.push(list);
  }
  parts.push(paragraph('Downloading comes in the next build.', 'card__note'));
  state.replaceChildren(...parts);
}

function showFailed(card, link, failed) {
  card.querySelector('.card__meta').textContent = failed.message;
  const state = card.querySelector('.card__state');
  state.replaceChildren();
  if (RETRYABLE.has(failed.reason)) {
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'pill glass pressable';
    retry.textContent = 'Try again';
    retry.addEventListener('click', () => lookUp(link));
    state.append(retry);
  }
}

function finding(text) {
  const pill = span('finding glass', '');
  pill.setAttribute('role', 'status');
  pill.append(span('finding__text', text));
  return pill;
}

function span(className, text) {
  const el = document.createElement('span');
  el.className = className;
  el.textContent = text;
  return el;
}

function paragraph(text, className = '') {
  const el = document.createElement('p');
  el.className = className;
  el.textContent = text;
  return el;
}

function count(n, word) {
  return n === 0 ? '' : `${n} ${word}${n === 1 ? '' : 's'}`;
}

// ── Reading the address bar ─────────────────────────────────────────────────────────────────

let asked = 0;
async function recognise() {
  const text = address.value.trim();
  if (!text) {
    forget();
    return;
  }
  const ask = ++asked;
  try {
    const link = await tauri.core.invoke('recognise_link', { text });
    if (ask === asked) show(link);
  } catch (error) {
    console.error('recognise_link failed', error);
  }
}

document.getElementById('address-form').addEventListener('submit', (e) => {
  e.preventDefault();
  recognise();
});
// A pasted link is recognised straight away, without waiting for Enter
address.addEventListener('paste', () => setTimeout(recognise));
address.addEventListener('input', () => {
  if (!address.value.trim()) forget();
});

document.addEventListener('keydown', (e) => {
  if (e.ctrlKey && e.key.toLowerCase() === 'l') {
    e.preventDefault();
    address.focus();
    address.select();
  } else if (e.key === 'Escape') {
    if (!settingsPanel.hidden) showSettings(false);
    else if (document.activeElement === address) {
      address.value = '';
      forget();
    }
  }
});

address.focus();
