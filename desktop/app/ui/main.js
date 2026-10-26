// The Chaya window: the address bar, what it recognises, the built-in browser, downloads, and the appearance
// settings.
import { DownloadButton, setTheme, setMotion, stagger } from './design/chaya-motion.js';

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
  // The page's webview is drawn over the window's content, so it makes way for the panel.
  showPageView();
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

function hostOf(url) {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

/** A video or post is looked up; anything else (a page, an account, words to search for) opens in the page. */
function show(link, text) {
  if (link.kind === 'media') lookUp(link);
  else openPage(text);
}

/** A card saying why `text` could not be opened. */
function showProblem(text, note) {
  forget();
  const card = template.content.firstElementChild.cloneNode(true);
  card.querySelector('use').setAttribute('href', '#i-page');
  card.querySelector('.card__title').textContent = "This can't be opened";
  card.querySelector('.card__url').textContent = text;
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
  if (answer.kind === 'found') showFound(card, link, answer);
  else showFailed(card, link, answer);
}

function showFound(card, link, found) {
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
    const row = document.createElement('div');
    row.className = 'choice';
    row.append(span('choice__label', counted), span('choice__detail', 'each saved as it is'));
    row.append(downloadButton(link.url, 'post', 'Save all', state));
    parts.push(row);
  } else {
    const list = document.createElement('ul');
    list.className = 'choices stagger';
    for (const choice of found.choices) {
      const row = document.createElement('li');
      row.className = 'choice';
      row.append(span('choice__label', choice.label), span('choice__detail num', choice.detail));
      row.append(downloadButton(link.url, choice.label, 'Download', state));
      list.append(row);
    }
    parts.push(list);
    if (found.moreLater) {
      parts.push(paragraph("Some of this video's qualities come in a form Chaya for Windows can't save yet.", 'card__note'));
    }
  }
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

// ── Downloading ─────────────────────────────────────────────────────────────────────────────

const dlTemplate = document.getElementById('dl-template');
const rowTemplate = document.getElementById('row-template');
const downloadList = document.getElementById('download-list');
const downloadsEmpty = document.getElementById('downloads-empty');

/** Every download the window knows, by id, as the app last described it. */
const tasks = new Map();
/** The Download buttons on show, each with the downloads it started (a post's "Save all" starts several). */
const buttons = new Set();
/** Set while a button follows the app's events, so it does not send them back as clicks. */
let following = false;

/**
 * A Download button for `choice` of the link at `url`. It starts the download, pauses and resumes it, and
 * follows it as it runs: the pill becomes a ring with the percentage, then a check.
 */
function downloadButton(url, choice, label, state) {
  const el = dlTemplate.content.firstElementChild.cloneNode(true);
  const entry = { ids: [], button: null };
  entry.button = new DownloadButton(el, {
    label,
    onEvent: async (event) => {
      if (following) return;
      if (event === 'start') {
        state.querySelector('.card__error')?.remove();
        try {
          const started = await tauri.core.invoke('download', { url, choice });
          entry.ids = started.map((t) => t.id);
          // Events about these downloads may have come before this answer, and are newer than it.
          started.filter((t) => !tasks.has(t.id)).forEach(taskChanged);
          follow(entry);
        } catch (message) {
          entry.button.send('fail');
          state.append(paragraph(String(message), 'card__error'));
        }
      } else if (event === 'pause') {
        entry.ids.forEach((id) => tauri.core.invoke('pause_download', { id }));
      } else if (event === 'resume') {
        entry.ids.forEach((id) => tauri.core.invoke('resume_download', { id }));
      } else if (event === 'reset') {
        entry.ids = [];
      }
    },
  });
  buttons.add(entry);
  return el;
}

/** Brings a button in line with its downloads: their total progress, paused, failed or all saved. */
function follow(entry) {
  const mine = entry.ids.map((id) => tasks.get(id)).filter(Boolean);
  if (!mine.length) return;
  following = true;
  const { button } = entry;
  const states = new Set(mine.map((t) => t.state));
  if (states.has('failed')) button.send('fail');
  else if (mine.every((t) => t.state === 'completed')) button.send('complete');
  else if (mine.every((t) => t.state === 'paused')) button.send('pause');
  else {
    // Started again from the list (Try again) while the button had gone back to its pill.
    if (button.state === 'idle') button.send('start');
    button.send('resume');
    const total = mine.reduce((sum, t) => sum + (t.total ?? 0), 0);
    const done = mine.reduce((sum, t) => sum + t.done, 0);
    if (total > 0) button.setProgress(done / total);
  }
  following = false;
}

function taskChanged(task) {
  const known = tasks.get(task.id);
  tasks.set(task.id, { ...task, speed: known?.speed ?? 0 });
  renderRow(task.id);
  countRunning();
  for (const entry of buttons) {
    if (!entry.button.el.isConnected) buttons.delete(entry);
    else if (entry.ids.includes(task.id)) follow(entry);
  }
}

function taskProgress({ id, done, total, bytesPerSecond }) {
  const task = tasks.get(id);
  if (!task) return;
  Object.assign(task, { done, total, speed: bytesPerSecond });
  renderRow(id);
  for (const entry of buttons) if (entry.ids.includes(id)) follow(entry);
}

function taskRemoved(id) {
  tasks.delete(id);
  downloadList.querySelector(`[data-id="${id}"]`)?.remove();
  showEmpty();
  countRunning();
}

/** While a page is open, the Downloads button says how many downloads are under way. */
function countRunning() {
  const running = [...tasks.values()].filter((t) => t.state === 'downloading' || t.state === 'queued').length;
  const badge = document.querySelector('.page-downloads__count');
  badge.textContent = String(running);
  badge.hidden = running === 0;
}

const ROW_ICON = { video: 'i-video', audio: 'i-audio', picture: 'i-post' };

function renderRow(id) {
  const task = tasks.get(id);
  let row = downloadList.querySelector(`[data-id="${id}"]`);
  if (!row) {
    row = rowTemplate.content.firstElementChild.cloneNode(true);
    row.dataset.id = id;
    row.querySelector('.row__thumb use').setAttribute('href', `#${ROW_ICON[task.kind] ?? 'i-video'}`);
    const img = row.querySelector('.row__thumb img');
    if (task.thumbnail?.startsWith('https://')) {
      img.addEventListener('error', () => img.removeAttribute('src'), { once: true });
      img.src = task.thumbnail;
    }
    row.querySelector('.row__title').addEventListener('click', () => {
      if (tasks.get(id)?.state === 'completed') tauri.core.invoke('open_download', { id });
    });
    row.querySelector('.row__main').addEventListener('click', () => mainAction(id));
    row.querySelector('.row__close').addEventListener('click', () => {
      const state = tasks.get(id)?.state;
      tauri.core.invoke(state === 'completed' ? 'remove_download' : 'cancel_download', { id });
    });
    // Newest first, as the app lists them.
    const newer = [...downloadList.children].find((r) => Number(r.dataset.id) < id);
    downloadList.insertBefore(row, newer ?? null);
  }

  row.dataset.state = task.state;
  row.querySelector('.row__title').textContent = task.fileName;
  row.querySelector('.row__meta').textContent = metaOf(task);
  const percent = task.total ? Math.min(99, Math.floor((task.done / task.total) * 100)) : null;
  row.querySelector('.row__pct').textContent =
    (task.state === 'downloading' || task.state === 'paused') && percent !== null ? `${percent}%` : '';
  row.querySelector('.row__bar > span').style.setProperty('--p', String(percent ?? 0));

  const main = row.querySelector('.row__main');
  const action = {
    downloading: ['i-pause', 'Pause'],
    queued: ['i-pause', 'Pause'],
    paused: ['i-play', 'Resume'],
    completed: ['i-folder', 'Show in folder'],
    failed: task.retryable ? ['i-retry', 'Try again'] : null,
  }[task.state];
  main.hidden = !action;
  if (action) {
    main.querySelector('use').setAttribute('href', `#${action[0]}`);
    main.setAttribute('aria-label', action[1]);
    main.title = action[1];
  }
  const close = row.querySelector('.row__close');
  const closeLabel = task.state === 'completed' ? 'Remove from the list (the file stays)' : 'Cancel and delete what arrived';
  close.setAttribute('aria-label', closeLabel);
  close.title = closeLabel;
  showEmpty();
}

function mainAction(id) {
  const command = {
    downloading: 'pause_download',
    queued: 'pause_download',
    paused: 'resume_download',
    completed: 'show_download',
    failed: 'retry_download',
  }[tasks.get(id)?.state];
  if (command) tauri.core.invoke(command, { id });
}

function metaOf(task) {
  const sizes = task.total ? `${fileSize(task.done)} of ${fileSize(task.total)}` : fileSize(task.done);
  switch (task.state) {
    case 'queued':
      return 'Waiting for another download to finish';
    case 'downloading': {
      if (task.step === 'joining') return 'Joining picture and sound';
      // A stream arrives as pieces, then becomes one file.
      if (task.step === 'saving') return task.kind === 'audio' ? 'Saving as M4A' : 'Saving as MP4';
      const parts = [sizes];
      // A quality whose picture and sound come apart: which of the two is on its way.
      if (task.step === 'picture') parts.unshift('Picture');
      if (task.step === 'sound') parts.unshift('Sound');
      if (task.speed > 0) {
        parts.push(`${fileSize(task.speed)}/s`);
        if (task.total) parts.push(`${timeLeft((task.total - task.done) / task.speed)} left`);
      }
      return parts.join(' · ');
    }
    case 'paused':
      return `Paused · ${sizes}`;
    case 'completed':
      return `Saved · ${fileSize(task.total ?? task.done)} · ${task.folder.split(/[\\/]/).pop()}`;
    case 'failed':
      return task.error ?? 'The download failed.';
    default:
      return '';
  }
}

/** "48.0 MB", as the app writes sizes everywhere. */
function fileSize(bytes) {
  const units = [['GB', 1024 ** 3], ['MB', 1024 ** 2], ['KB', 1024]];
  for (const [unit, size] of units) if (bytes >= size) return `${(bytes / size).toFixed(1)} ${unit}`;
  return `${Math.round(bytes)} B`;
}

function timeLeft(seconds) {
  const s = Math.max(1, Math.round(seconds));
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.floor(s / 60)} min ${s % 60} s`;
  return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
}

function showEmpty() {
  downloadsEmpty.hidden = tasks.size > 0;
}

tauri?.event.listen('download', ({ payload }) => {
  if (payload.type === 'changed') taskChanged(payload.task);
  else if (payload.type === 'progress') taskProgress(payload);
  else if (payload.type === 'removed') taskRemoved(payload.id);
});

tauri?.core.invoke('list_downloads').then((list) => {
  list.forEach(taskChanged);
  stagger(downloadList);
  downloadList.classList.add('stagger');
});

// ── The built-in browser ────────────────────────────────────────────────────────────────────
//
// The page is a webview of its own, drawn over the window where .page is: the window tells the app where that
// is whenever it moves, and hides the page while it shows something in its place (the downloads, the
// settings).

const pageEl = document.getElementById('page');
const pageLoad = document.getElementById('page-load');
const pageReload = document.getElementById('page-reload');
const pageDownloads = document.getElementById('page-downloads');
const notice = document.getElementById('page-notice');

let browsing = false;
/** The address the page is at, for the address bar to show again after it was edited. */
let pageUrl = '';

function boundsOf(el) {
  const r = el.getBoundingClientRect();
  return { x: r.left, y: r.top, width: r.width, height: r.height };
}

function setBrowsing(on) {
  browsing = on;
  document.body.classList.toggle('browsing', on);
  document.querySelectorAll('[data-browsing]').forEach((el) => (el.hidden = !on));
  if (!on) {
    showDownloads(false);
    hideNotice();
    setLoading(false);
    pageUrl = '';
  }
}

/** Opens what `text` names in the page: an address, a site, an account, or a search. */
async function openPage(text) {
  forget();
  const first = !browsing;
  if (first) setBrowsing(true);
  showDownloads(false);
  // Laid out first, so the page opens where .page is.
  await new Promise(requestAnimationFrame);
  try {
    const url = await tauri.core.invoke('open_page', { text, bounds: boundsOf(pageEl) });
    pageUrl = url;
    address.value = url;
    address.blur();
  } catch (message) {
    if (first) setBrowsing(false);
    showProblem(text, String(message));
  }
}

function closePage() {
  tauri.core.invoke('close_page');
  setBrowsing(false);
  address.value = '';
  address.focus();
}

/** Shows the page unless something is shown in its place. */
function showPageView() {
  if (!browsing) return;
  const covered = !settingsPanel.hidden || document.body.classList.contains('showing-downloads');
  tauri.core.invoke('show_page', { visible: !covered });
}

function showDownloads(on) {
  document.body.classList.toggle('showing-downloads', on);
  pageDownloads.setAttribute('aria-pressed', String(on));
  showPageView();
}

function setLoading(loading) {
  pageLoad.toggleAttribute('data-loading', loading);
  pageReload.querySelector('use').setAttribute('href', loading ? '#i-close' : '#i-retry');
  const label = loading ? 'Stop' : 'Reload';
  pageReload.setAttribute('aria-label', label);
  pageReload.title = label;
}

function showNotice(text, action, onAction) {
  notice.querySelector('.notice__text').textContent = text;
  const button = notice.querySelector('.notice__action');
  button.hidden = !action;
  button.textContent = action ?? '';
  button.onclick = () => {
    hideNotice();
    onAction?.();
  };
  notice.hidden = false;
}

function hideNotice() {
  notice.hidden = true;
}

/** The page's address, in the address bar unless the person is typing there. */
function pageAt(url) {
  pageUrl = url;
  if (document.activeElement !== address) address.value = url;
}

// The page follows .page as the window lays out: resized, or moved by a card or a notice above it.
new ResizeObserver(() => {
  if (browsing) tauri.core.invoke('place_page', { bounds: boundsOf(pageEl) });
}).observe(pageEl);
new ResizeObserver(() => {
  if (browsing) tauri.core.invoke('place_page', { bounds: boundsOf(pageEl) });
}).observe(document.querySelector('.main'));

document.getElementById('page-back').addEventListener('click', () => tauri.core.invoke('page_action', { action: 'back' }));
document.getElementById('page-forward').addEventListener('click', () => tauri.core.invoke('page_action', { action: 'forward' }));
pageReload.addEventListener('click', () =>
  tauri.core.invoke('page_action', { action: pageLoad.hasAttribute('data-loading') ? 'stop' : 'reload' }),
);
pageDownloads.addEventListener('click', () =>
  showDownloads(!document.body.classList.contains('showing-downloads')),
);
document.getElementById('page-close').addEventListener('click', closePage);

tauri?.event.listen('page', ({ payload }) => {
  if (!browsing) return;
  switch (payload.type) {
    case 'loading':
      setLoading(true);
      pageAt(payload.url);
      break;
    case 'loaded':
      setLoading(false);
      pageAt(payload.url);
      break;
    case 'popup':
      showNotice(`This page tried to open ${hostOf(payload.url)} in a new window.`, 'Open it here', () => openPage(payload.url));
      break;
    case 'saving':
      showNotice(`Saving ${payload.name} in Downloads.`, 'Show', () => showDownloads(true));
      break;
  }
});

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
    if (ask === asked) show(link, text);
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
    else if (document.activeElement === address && browsing) {
      // Back to the page's own address.
      address.value = pageUrl;
      address.blur();
    } else if (document.activeElement === address) {
      address.value = '';
      forget();
    }
  }
});

address.focus();
