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

/** What the window says about each kind of link. This build recognises links; it does not fetch them yet. */
function describe(link) {
  switch (link.kind) {
    case 'media':
      return {
        icon: link.lookingFor === 'post' ? 'i-post' : 'i-video',
        title: `${link.site} ${link.lookingFor}`,
        url: link.url,
        note: 'Chaya knows this link. Looking it up and downloading it come in the next build.',
      };
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
  const { icon, title, url, note } = describe(link);
  const card = template.content.firstElementChild.cloneNode(true);
  card.querySelector('use').setAttribute('href', `#${icon}`);
  card.querySelector('.card__title').textContent = title;
  card.querySelector('.card__url').textContent = url;
  card.querySelector('.card__note').textContent = note;
  result.replaceChildren(card);
}

let asked = 0;
async function recognise() {
  const text = address.value.trim();
  if (!text) {
    result.replaceChildren();
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
  if (!address.value.trim()) result.replaceChildren();
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
      result.replaceChildren();
    }
  }
});

address.focus();
