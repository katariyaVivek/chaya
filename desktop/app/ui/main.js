// The Chaya window: the address bar, what it recognises, the built-in browser, downloads, and the appearance
// settings.
import { DownloadButton, setTheme, setMotion, stagger, leave } from './design/chaya-motion.js';
import { selection, snap, moveEdge, stepEdge, rows, clipOf, nameFrom, view as clipView, exact } from './clips.js';
import { chapterAt, stepChapter, snapEdges, dividers } from './chapters.js';
import {
  LOOKS,
  LOOK_NAMES,
  firstChoice,
  changed,
  toggledSrt,
  clipIds,
  optionsOf,
  keptOf,
  exportLabel,
  whereNote,
  sampleWords,
  isRunning,
  progressWords,
  noticeOf,
  keyOf,
  gifTooLong,
} from './export.js';
import { lineSearch } from './search.js';
import { ownVideos } from './own.js';

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
  // The window's frame and its menu are Windows' own; tell them which theme to draw. null follows Windows.
  tauri?.window
    .getCurrentWindow()
    .setTheme({ day: 'light', night: 'dark' }[theme] ?? null)
    .catch(() => {});
  document.querySelectorAll('[data-theme-choice]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.themeChoice === theme)));
  document.querySelectorAll('[data-motion-choice]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.motionChoice === motion)));
}

const appearance = loadAppearance();
applyAppearance(appearance);

// ── Updates ─────────────────────────────────────────────────────────────────────────────────
//
// The app checks at most once a day (`update.rs`); one found is offered here, and installed only when clicked.

const updateBox = document.getElementById('settings-update');
const updateText = document.getElementById('update-text');
const updateInstall = document.getElementById('update-install');

function showUpdate(update) {
  if (!update) return;
  updateBox.hidden = false;
  updateText.textContent = `Chaya ${update.version} is ready. It installs in a few seconds, and Chaya opens again.`;
  document.getElementById('settings-button').classList.add('has-update');
}

tauri?.event.listen('update', ({ payload }) => showUpdate(payload));
tauri?.core.invoke('update_waiting').then(showUpdate).catch(() => {});
tauri?.app.getVersion().then((v) => {
  document.getElementById('app-version').textContent = `Chaya for Windows ${v}.`;
});
updateInstall.addEventListener('click', () => {
  updateInstall.disabled = true;
  updateInstall.textContent = 'Updating…';
  tauri.core.invoke('install_update').catch((message) => {
    updateText.textContent = String(message);
    updateInstall.hidden = true;
  });
});

// ── The title bar's buttons ────────────────────────────────────────────────────────────────────────────────────
//
// Chaya draws the window's title bar (`decorations: false`); these are Windows' three, doing what Windows' do.

const appWindow = tauri?.window.getCurrentWindow();
const captionMax = document.getElementById('caption-max');

async function showMaximized() {
  const maximized = await appWindow.isMaximized().catch(() => false);
  document.body.classList.toggle('maximized', maximized);
  captionMax.setAttribute('aria-label', maximized ? 'Restore' : 'Maximize');
}

if (appWindow) {
  document.getElementById('caption-min').addEventListener('click', () => appWindow.minimize());
  captionMax.addEventListener('click', () => appWindow.toggleMaximize());
  document.getElementById('caption-close').addEventListener('click', () => appWindow.close());
  appWindow.onResized(showMaximized);
  showMaximized();

  // Windows 11 offers its snap layouts when the pointer rests on a window's maximize button. It can't tell that this
  // button is one, so the app asks Windows for them (Win+Z) after the same short rest, and only while Chaya is the
  // window in front: Win+Z offers them for whichever window that is.
  let resting = 0;
  captionMax.addEventListener('mouseenter', () => {
    clearTimeout(resting);
    resting = setTimeout(() => {
      if (document.hasFocus()) tauri.core.invoke('snap_layouts').catch(() => {});
    }, 500);
  });
  captionMax.addEventListener('mouseleave', () => clearTimeout(resting));
  captionMax.addEventListener('click', () => clearTimeout(resting));
}

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
  fitSettings();
  // Whether Ollama or LM Studio has started since.
  if (open) loadAi();
  // The page's webview is drawn over the window's content, so it makes way for the panel.
  showPageView();
}
/** The panel ends inside the window wherever its button is (lower while browsing, beside the address bar), and
 * scrolls the rest. */
function fitSettings() {
  if (settingsPanel.hidden) return;
  settingsPanel.style.maxHeight = `${Math.max(160, window.innerHeight - settingsPanel.getBoundingClientRect().top - 12)}px`;
}
window.addEventListener('resize', fitSettings);
settingsButton.addEventListener('click', () => showSettings(settingsPanel.hidden));
document.addEventListener('click', (e) => {
  // A choice in the panel can draw the panel again, taking the button clicked out of the page: still inside.
  if (!settingsPanel.hidden && e.target.isConnected && !e.target.closest('.settings')) showSettings(false);
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
  if (link.kind === 'media' || link.kind === 'account') {
    if (link.kind === 'media') lookUp(link);
    else showAccount(link);
    // While a page is open the card is in the bar's panel, opened since the person asked for it.
    if (browsing) openMedia(true);
  } else openPage(text);
}

/**
 * An Instagram or X account: everything it has posted can be saved as one ZIP, as on the phone. With the person's
 * sign-in only when they choose it; without one, the sites show a visitor little or nothing.
 */
async function showAccount(link) {
  const ask = ++looking;
  resultNoun = 'account';
  const card = template.content.firstElementChild.cloneNode(true);
  card.classList.add('card--account');
  card.querySelector('use').setAttribute('href', '#i-zip');
  card.querySelector('.card__title').textContent = `@${link.username}`;
  card.querySelector('.card__url').textContent = `${link.site} account`;
  card.querySelector('.card__note').textContent =
    `Save every photo and video @${link.username} has posted, as one ZIP in your Downloads.`;
  const state = document.createElement('div');
  state.className = 'card__state';
  card.querySelector('.card__body').append(state);
  result.replaceChildren(card);

  const signedIn = await tauri.core.invoke('account_signed_in', { url: link.url }).catch(() => false);
  if (ask !== looking) return;
  const save = (useSignIn) => tauri.core.invoke('save_account', { url: link.url, useSignIn });
  const actions = document.createElement('div');
  actions.className = 'card__actions';
  let note;
  if (signedIn) {
    actions.append(downloadButton(() => save(true), 'Save all posts with my sign-in', state));
    actions.append(pill('Try without signing in', () => save(false)));
    note = `Your sign-in lets ${link.site} show everything you can see there. Sites can limit accounts that download ` +
      "a lot, so Chaya goes at the site's own pace; a big account takes a while.";
  } else {
    actions.append(downloadButton(() => save(false), 'Try without signing in', state));
    actions.append(pill(`Sign in to ${link.site} first`, () => openPage(link.url, true)));
    note = link.site === 'X'
      ? "X shows an account's posts only to signed-in people, so without a sign-in this usually finds nothing. Sign in, then come back to this account."
      : `Without a sign-in, ${link.site} shows a visitor a few recent posts at most. Sign in, then come back to this account to save all of them.`;
  }
  if (!browsing) actions.append(pill('Open the page', () => openPage(link.url)));
  state.replaceChildren(actions, paragraph(note, 'card__note'));
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

/** Looks a link to a video or post up, and shows what it can be saved as. */
function lookUp(link, useSignIn = false) {
  return showLookup({
    title: `${link.site} ${link.lookingFor}`,
    meta: link.url,
    finding: useSignIn ? `Finding the ${link.lookingFor} with your sign-in…` : `Finding the ${link.lookingFor}…`,
    noun: link.lookingFor,
    ask: () => tauri.core.invoke('look_up', { url: link.url, useSignIn }),
    save: (choice) => tauri.core.invoke('download', { url: link.url, choice }),
    again: () => lookUp(link),
    site: link.site,
    // Only when the person taps it: some sites limit accounts that download a lot.
    withSignIn: () => lookUp(link, true),
    // The site asks for its sign-in on its own page; once signed in there, that page is looked up again.
    signInFirst: () => openPage(link.url, true),
  });
}

/**
 * A card for something being looked up: `ask` answers with what was found, `save(choice)` starts saving one of
 * its choices, and `again` looks it up again.
 */
async function showLookup(lookup) {
  const ask = ++looking;
  resultNoun = lookup.noun ?? '';
  const card = lookupTemplate.content.firstElementChild.cloneNode(true);
  card.querySelector('.card__title').textContent = lookup.title;
  card.querySelector('.card__meta').textContent = lookup.meta;
  card.querySelector('.card__state').replaceChildren(finding(lookup.finding));
  result.replaceChildren(card);

  let answer;
  try {
    answer = await lookup.ask();
  } catch (error) {
    console.error('lookup failed', error);
    answer = { kind: 'failed', reason: 'engine', message: "The video finder couldn't start. Try again in a moment." };
  }
  if (ask !== looking) return;
  if (answer.kind === 'found') showFound(card, lookup, answer);
  else showFailed(card, lookup, answer);
}

function showFound(card, lookup, found) {
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
    row.append(downloadButton(() => lookup.save('post'), 'Save all', state));
    parts.push(row);
  } else {
    const list = document.createElement('ul');
    list.className = 'choices stagger';
    for (const choice of found.choices) {
      const row = document.createElement('li');
      row.className = 'choice';
      row.append(span('choice__label', choice.label), span('choice__detail num', choice.detail));
      row.append(downloadButton(() => lookup.save(choice.label), 'Download', state));
      list.append(row);
    }
    parts.push(list);
    if (found.moreLater) {
      parts.push(paragraph("Some of this video's qualities come in a form Chaya for Windows can't save yet.", 'card__note'));
    }
  }
  state.replaceChildren(...parts);
}

function showFailed(card, lookup, failed) {
  card.classList.add('card--failed');
  card.querySelector('.card__meta').textContent = failed.message;
  const state = card.querySelector('.card__state');
  state.replaceChildren();
  const actions = document.createElement('div');
  actions.className = 'card__actions';
  if (RETRYABLE.has(failed.reason)) actions.append(pill('Try again', () => lookup.again()));
  if (failed.signIn === 'use' && lookup.withSignIn) {
    actions.append(pill('Use my sign-in', () => lookup.withSignIn(), true));
    state.append(actions, paragraph(
      `This uses the ${lookup.site} account you're signed in to in Chaya's browser. Some sites limit accounts ` +
        'that download a lot, so it is only done when you ask.',
      'card__note',
    ));
    return;
  }
  if (failed.signIn === 'signInFirst' && lookup.signInFirst) {
    actions.append(pill(`Sign in to ${lookup.site}`, () => lookup.signInFirst(), true));
    state.append(actions, paragraph(
      `Sign in to ${lookup.site} in Chaya's browser. Once you're signed in, this link is looked up there again.`,
      'card__note',
    ));
    return;
  }
  if (actions.childElementCount) state.append(actions);
}

function pill(text, onClick, primary = false) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = primary ? 'pill pill--primary glass pressable' : 'pill glass pressable';
  button.textContent = text;
  button.addEventListener('click', onClick);
  return button;
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
/** How making each video's subtitles is getting on (`speech.rs`'s JobView), by download. */
const speechJobs = new Map();
/** The Download buttons on show, each with the downloads it started (a post's "Save all" starts several). */
const buttons = new Set();
/** Set while a button follows the app's events, so it does not send them back as clicks. */
let following = false;

/**
 * A Download button: `start` starts what it saves (resolving to the downloads it started), and the button pauses
 * and resumes them and follows them as they run: the pill becomes a ring with the percentage, then a check. A
 * failure to start is written into `state`.
 */
function downloadButton(start, label, state) {
  const el = dlTemplate.content.firstElementChild.cloneNode(true);
  const entry = { ids: [], button: null };
  entry.button = new DownloadButton(el, {
    label,
    onEvent: async (event) => {
      if (following) return;
      if (event === 'start') {
        state.querySelector('.card__error')?.remove();
        try {
          const started = await start();
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
    const archive = mine.length === 1 ? mine[0].archive : null;
    const total = mine.reduce((sum, t) => sum + (t.total ?? 0), 0);
    const done = mine.reduce((sum, t) => sum + t.done, 0);
    // An account's ZIP counts in files: its size is not known until the end.
    if (archive) button.setProgress(archive.found ? archive.saved / archive.found : 0);
    else if (total > 0) button.setProgress(done / total);
  }
  following = false;
}

function taskChanged(task) {
  const known = tasks.get(task.id);
  tasks.set(task.id, { ...task, speed: known?.speed ?? 0 });
  renderRow(task.id);
  if (known?.state !== task.state) arrange();
  showNarrowing();
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
  arrange();
  showNarrowing();
  showEmpty();
  countRunning();
  // A button whose downloads were all cancelled goes back to its pill; a saved one stays saved, as the file does.
  for (const entry of buttons) {
    if (!entry.ids.includes(id)) continue;
    entry.ids = entry.ids.filter((other) => other !== id);
    if (entry.ids.length) {
      follow(entry);
    } else {
      following = true;
      entry.button.send('cancel');
      following = false;
    }
  }
}

/** While a page is open, the Downloads button says how many downloads are under way. */
function countRunning() {
  const running = [...tasks.values()].filter((t) => t.state === 'downloading' || t.state === 'queued').length;
  const badge = document.querySelector('.page-downloads .page-count');
  badge.textContent = String(running);
  badge.hidden = running === 0;
}

const ROW_ICON = { video: 'i-video', audio: 'i-audio', picture: 'i-post', archive: 'i-zip' };

function renderRow(id) {
  const task = tasks.get(id);
  let row = downloadList.querySelector(`[data-id="${id}"]`);
  if (!row) {
    row = rowTemplate.content.firstElementChild.cloneNode(true);
    row.dataset.id = id;
    row.querySelector('.row__thumb use').setAttribute('href', `#${ROW_ICON[task.kind] ?? 'i-video'}`);
    row.querySelector('.row__title').addEventListener('click', () => {
      if (tasks.get(id)?.state === 'completed') openSaved(id);
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

  // Its picture once, and again once it is saved: a saved video's poster is made from the file.
  const covered = task.state === 'completed' ? 'saved' : 'site';
  if (row.dataset.covered !== covered) {
    row.dataset.covered = covered;
    const img = row.querySelector('.row__thumb img');
    showCover(img, task, () => img.removeAttribute('src'));
  }
  row.dataset.state = task.state;
  row.querySelector('.row__title').textContent = task.fileName;
  row.querySelector('.row__meta').textContent = metaOf(task);
  const share = task.archive ? (task.archive.found ? task.archive.saved / task.archive.found : null) : task.total ? task.done / task.total : null;
  const percent = share === null ? null : Math.min(99, Math.floor(share * 100));
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
  row.hidden = !keeps(task);
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
      // An account's ZIP: first its posts are found, then saved one by one, then packed.
      if (task.archive?.listing) {
        return task.archive.found ? `Finding posts · ${count(task.archive.found, 'file')} so far` : 'Finding posts';
      }
      if (task.archive) {
        const { found, saved } = task.archive;
        if (saved >= found) return 'Making the ZIP';
        const parts = [`${saved} of ${count(found, 'file')}`, fileSize(task.done)];
        if (task.speed > 0) parts.push(`${fileSize(task.speed)}/s`);
        return parts.join(' · ');
      }
      if (task.step === 'joining') return 'Joining picture and sound';
      // A stream arrives as pieces, then becomes one file.
      if (task.step === 'saving') {
        if (task.kind === 'audio') return 'Saving as M4A';
        return task.fileName.toLowerCase().endsWith('.webm') ? 'Saving as WebM' : 'Saving as MP4';
      }
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
    case 'completed': {
      const job = speechJobs.get(task.id);
      if (job && !['done', 'failed'].includes(job.state)) return speechText(job);
      // The person's own file is listed where it is, not saved by Chaya.
      return `${task.own ? 'On this PC' : 'Saved'} · ${fileSize(task.total ?? task.done)} · ${task.folder.split(/[\\/]/).pop()}`;
    }
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

// ── Narrowing the list ──────────────────────────────────────────────────────────────────────
//
// As the phone's library: a kind, a site (`core::library`) and words in the name, each optional, all together.

const KINDS = [['video', 'Videos'], ['picture', 'Pictures'], ['audio', 'Audio'], ['archive', 'ZIPs']];
const SITES = [['youtube', 'YouTube'], ['instagram', 'Instagram'], ['tiktok', 'TikTok'], ['x', 'X'], ['pinterest', 'Pinterest'], ['other', 'Other'], ['pc', 'This PC']];
const narrowing = { kind: null, site: null, words: '' };
const narrowEl = document.getElementById('downloads-narrow');
const findField = document.getElementById('downloads-find-field');
const findInput = document.getElementById('downloads-find');
const downloadsNone = document.getElementById('downloads-none');

// ── Searching what is said, and your own videos ──
//
// The find box also searches every saved video's lines (search.js); a line found opens the player there. Videos and
// sounds dropped on the window, or picked with *Add from this PC*, are listed where they are (own.js).

const said = lineSearch({
  invoke: (command, args) => tauri.core.invoke(command, args),
  box: document.getElementById('downloads-lines'),
  // The chips' kind and site narrow the lines too; the words are what is searched.
  among: () =>
    narrowing.kind || narrowing.site
      ? [...tasks.values()]
          .filter((t) => (!narrowing.kind || t.kind === narrowing.kind) && (!narrowing.site || t.site === narrowing.site))
          .map((t) => t.id)
      : null,
  open: (id, from) => openPlayer(id, from),
  clock,
  languageName,
  changed: () => showNarrowing(),
});

ownVideos({
  tauri,
  drop: document.getElementById('drop'),
  button: document.getElementById('downloads-add'),
  note: document.getElementById('own-note'),
  added: (list) => list.filter((t) => !tasks.has(t.id)).forEach(taskChanged),
  open: (id) => openSaved(id),
  shown: () => browsing && showDownloads(true),
});

function keeps(task) {
  if (narrowing.kind && task.kind !== narrowing.kind) return false;
  if (narrowing.site && task.site !== narrowing.site) return false;
  const words = narrowing.words.trim().toLowerCase();
  return !words || task.title.toLowerCase().includes(words) || task.fileName.toLowerCase().includes(words);
}

/** One group of chips: only the values the downloads have (and the one chosen), and only when that is two or more. */
function chips(group, field, options) {
  const present = new Set([...tasks.values()].map((t) => t[field]));
  const shown = options.filter(([value]) => present.has(value) || narrowing[field] === value);
  group.hidden = shown.length < 2;
  const key = shown.map(([value]) => value + (narrowing[field] === value ? '*' : '')).join(' ');
  if (group.dataset.key === key) return;
  group.dataset.key = key;
  group.replaceChildren(
    ...shown.map(([value, label]) => {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'chip pressable';
      chip.textContent = label;
      chip.setAttribute('aria-pressed', String(narrowing[field] === value));
      // Pressed again, it lets go.
      chip.addEventListener('click', () => narrow({ [field]: narrowing[field] === value ? null : value }));
      return chip;
    }),
  );
}

function showNarrowing() {
  const kinds = document.getElementById('narrow-kind');
  const sites = document.getElementById('narrow-site');
  chips(kinds, 'kind', KINDS);
  chips(sites, 'site', SITES);
  narrowEl.querySelector('.narrow__rule').hidden = kinds.hidden || sites.hidden;
  narrowEl.hidden = kinds.hidden && sites.hidden;
  // With one saved video, the box still finds what is said in it.
  const saidIn = [...tasks.values()].some((t) => t.state === 'completed' && (t.kind === 'video' || t.kind === 'audio'));
  findField.hidden = tasks.size < 2 && !narrowing.words && !saidIn;
  const kept = [...tasks.values()].filter(keeps).length;
  downloadsNone.hidden = tasks.size === 0 || kept > 0 || said.shown > 0;
  // The grid holds what was saved: offered once something has been.
  viewSwitch.hidden = ![...tasks.values()].some((t) => t.state === 'completed');
  showView(viewSwitch.hidden ? 'list' : view, false);
}

function narrow(change) {
  Object.assign(narrowing, change);
  said.run(narrowing.words);
  for (const row of downloadList.querySelectorAll('.row')) {
    const task = tasks.get(Number(row.dataset.id));
    row.hidden = !task || !keeps(task);
  }
  hideEmptySections();
  showNarrowing();
  renderGrid();
}

// ── Sections ────────────────────────────────────────────────────────────────────────────────
//
// As the phone's list: what runs, what needs attention, then the days things were saved on (`core::library`), in
// this PC's time zone. Asked for again when a download changes state, and as the day turns.

let arranging = 0;
let arrangedDay = new Date().toDateString();

function arrange() {
  cancelAnimationFrame(arranging);
  arranging = requestAnimationFrame(async () => {
    arrangedDay = new Date().toDateString();
    const sections = await tauri.core.invoke('download_sections', { offset: -new Date().getTimezoneOffset() }).catch(() => null);
    if (!sections) return;
    const heads = new Map([...downloadList.querySelectorAll('.list__head')].map((h) => [h.textContent, h]));
    const order = [];
    for (const section of sections) {
      let head = heads.get(section.title);
      if (!head) {
        head = document.createElement('li');
        head.className = 'list__head';
        head.textContent = section.title;
      }
      order.push(head, ...section.ids.map((id) => downloadList.querySelector(`.row[data-id="${id}"]`)).filter(Boolean));
    }
    // Moved only when the order changed, so a row being pointed at stays where it is.
    const now = [...downloadList.children];
    if (order.length !== now.length || order.some((node, i) => node !== now[i])) downloadList.replaceChildren(...order);
    hideEmptySections();
    renderGrid();
  });
}

// ── The grid ────────────────────────────────────────────────────────────────────────────────
//
// As the phone's library: what was saved, as square tiles. A picture shows itself (served to this window only, by
// the download's number: `saved_picture` in lib.rs), a video the poster its site gave, a post's files one tile with
// their count (`core::library::tiles`). A picture or a post opens in the viewer; anything else in its own program.

const viewSwitch = document.getElementById('downloads-view');
const gridWrap = document.getElementById('download-grid-wrap');
const grid = document.getElementById('download-grid');
const gridRunning = document.getElementById('grid-running');
const SVG = 'http://www.w3.org/2000/svg';
let view = 'list';
try {
  if (localStorage.getItem('chaya.downloads.view') === 'grid') view = 'grid';
} catch {}

/** The address of saved picture `id`, which only this window can open. */
function savedPicture(id) {
  return tauri.core.convertFileSrc(String(id), 'chaya-file');
}

function icon(id) {
  const svg = document.createElementNS(SVG, 'svg');
  const use = document.createElementNS(SVG, 'use');
  use.setAttribute('href', `#${id}`);
  svg.append(use);
  return svg;
}

/** A tile or a page without a picture: its kind's icon over its name. */
function named(task) {
  const el = document.createElement('span');
  el.className = 'tile__name';
  el.append(icon(ROW_ICON[task.kind] ?? 'i-video'), span('', task.title || task.fileName));
  return el;
}

/** What may show a download, best first: the picture itself; for a saved video, the poster made from it; the
 * site's thumbnail (which may have stopped working). */
function coversOf(task) {
  if (task.kind === 'picture') return task.state === 'completed' ? [savedPicture(task.id)] : [];
  const covers = task.kind === 'video' && task.state === 'completed' ? [posterOf(task.id)] : [];
  if (task.thumbnail?.startsWith('https://')) covers.push(task.thumbnail);
  return covers;
}

/** Shows in `img` the first of `task`'s covers that loads, or calls `onNone`. A saved video whose poster isn't made
 * yet has it made, and every picture of it shows the poster once it is. */
function showCover(img, task, onNone) {
  const covers = coversOf(task);
  let at = 0;
  img.dataset.cover = String(task.id);
  img.alt = '';
  img.referrerPolicy = 'no-referrer';
  img.onerror = () => {
    if (covers[at] === posterOf(task.id)) makePoster(task.id);
    at += 1;
    if (at < covers.length) img.src = covers[at];
    else {
      img.onerror = null;
      onNone();
    }
  };
  if (covers.length) img.src = covers[0];
  else onNone();
}

function image(task, onNone) {
  const img = new Image();
  img.loading = 'lazy';
  img.decoding = 'async';
  showCover(img, task, () => onNone(img));
  return img;
}

// ── Posters ──
//
// A saved video's poster is a frame of it, taken here (WebView2 plays what Chaya saves) and kept by the app beside the
// video's other data (`keep_poster`): a site's thumbnail address may stop working, and a video from a page has none.

/** Where saved video `id`'s poster is served (`chaya-file`, which answers 404 until it is made). */
function posterOf(id) {
  return tauri.core.convertFileSrc(`poster-${id}`, 'chaya-file');
}

/** The videos whose poster was tried this run, made or not (a file WebView2 can't play isn't tried again). */
const postersTried = new Set();
let posterLine = Promise.resolve();

function makePoster(id) {
  if (postersTried.has(id)) return;
  postersTried.add(id);
  // One at a time: each is a video opened to its middle.
  posterLine = posterLine.then(() => takePoster(id)).catch(() => {});
}

async function takePoster(id) {
  const video = document.createElement('video');
  video.muted = true;
  video.preload = 'auto';
  video.crossOrigin = 'anonymous';
  const ready = (event) =>
    new Promise((resolve, reject) => {
      video.addEventListener(event, resolve, { once: true });
      video.addEventListener('error', reject, { once: true });
      setTimeout(reject, 20000);
    });
  try {
    video.src = tauri.core.convertFileSrc(String(id), 'chaya-file');
    await ready('loadedmetadata');
    if (!video.videoWidth) return;
    // A tenth of the way in, past a title card, and no further than half a minute.
    video.currentTime = Math.min(30, (video.duration || 10) / 10);
    await ready('seeked');
    const scale = Math.min(1, 480 / video.videoWidth);
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(video.videoWidth * scale);
    canvas.height = Math.round(video.videoHeight * scale);
    canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
    const jpeg = canvas.toDataURL('image/jpeg', 0.82).split(',')[1];
    await tauri.core.invoke('keep_poster', { id, jpeg });
    // Every picture of it, again: with the poster first, that is what loads now.
    const task = tasks.get(id);
    if (!task) return;
    for (const img of document.querySelectorAll(`img[data-cover="${id}"]`)) {
      showCover(img, task, () => {});
    }
  } finally {
    video.removeAttribute('src');
    video.load();
  }
}

function tileFor({ key, ids }) {
  const li = document.createElement('li');
  li.dataset.key = key;
  li.dataset.ids = ids.join(',');
  const cover = tasks.get(ids[0]);
  const name = cover.title || cover.fileName;
  const tile = document.createElement('button');
  tile.type = 'button';
  tile.className = 'tile pressable';
  tile.title = name;
  tile.setAttribute('aria-label', ids.length > 1 ? `${name}, ${ids.length} files` : name);
  tile.append(coversOf(cover).length ? image(cover, (img) => img.replaceWith(named(cover))) : named(cover));
  if (cover.kind === 'video') {
    const play = span('tile__badge tile__badge--play', '');
    play.append(icon('i-play'));
    tile.append(play);
  }
  if (ids.length > 1) {
    const count = span('tile__badge tile__badge--count num', '');
    count.append(icon('i-post'), String(ids.length));
    tile.append(count);
  }
  tile.addEventListener('click', () => {
    const only = tasks.get(ids[0]);
    if (ids.length === 1 && only?.kind !== 'picture') openSaved(ids[0]);
    else openViewer(ids);
  });
  li.append(tile);
  return li;
}

let gridding = 0;
function renderGrid() {
  if (view !== 'grid') return;
  cancelAnimationFrame(gridding);
  gridding = requestAnimationFrame(async () => {
    const kept = [...tasks.values()].filter(keeps).map((t) => t.id);
    const tiles = await tauri.core.invoke('library_tiles', { kept }).catch(() => null);
    if (!tiles) return;
    // A tile that holds the same files stays as it is, so its picture is not loaded again.
    const shown = new Map([...grid.children].map((li) => [li.dataset.key, li]));
    const next = tiles.map((tile) => {
      const old = shown.get(tile.key);
      return old && old.dataset.ids === tile.ids.join(',') ? old : tileFor(tile);
    });
    const now = [...grid.children];
    if (next.length !== now.length || next.some((li, i) => li !== now[i])) grid.replaceChildren(...next);
    // The grid shows what was saved; what is still on its way is followed in the list.
    const running = [...tasks.values()].filter((t) => ['queued', 'downloading', 'paused'].includes(t.state)).length;
    gridRunning.hidden = running === 0;
    gridRunning.textContent = `${count(running, 'download')} still on the way · Show the list`;
  });
}

function showView(next, remember = true) {
  if (remember) {
    view = next;
    try {
      localStorage.setItem('chaya.downloads.view', next);
    } catch {}
  }
  for (const b of viewSwitch.querySelectorAll('button')) b.setAttribute('aria-pressed', String(b.dataset.view === next));
  downloadList.hidden = next === 'grid';
  gridWrap.hidden = next !== 'grid';
  if (next === 'grid') renderGrid();
}

viewSwitch.addEventListener('click', (e) => {
  const choice = e.target.closest('[data-view]')?.dataset.view;
  if (choice) showView(choice);
});
gridRunning.addEventListener('click', () => showView('list'));

// ── The viewer ──────────────────────────────────────────────────────────────────────────────

const viewer = document.getElementById('viewer');
const viewerFrame = document.getElementById('viewer-frame');
const viewerPrev = document.getElementById('viewer-prev');
const viewerNext = document.getElementById('viewer-next');
let viewing = { ids: [], at: 0 };

function openViewer(ids, at = 0) {
  viewing = { ids, at };
  viewer.hidden = false;
  // Over a page, the page would be drawn over the viewer: it hides while the viewer is open.
  showPageView();
  showViewerPage();
  document.getElementById('viewer-close').focus();
}

function closeViewer() {
  viewer.hidden = true;
  viewerFrame.replaceChildren();
  showPageView();
}

function showViewerPage() {
  const { ids, at } = viewing;
  const task = tasks.get(ids[at]);
  if (!task) return closeViewer();
  document.getElementById('viewer-title').textContent = task.title || task.fileName;
  document.getElementById('viewer-count').textContent = ids.length > 1 ? `${at + 1} of ${ids.length}` : '';
  viewerPrev.disabled = at === 0;
  viewerNext.disabled = at === ids.length - 1;
  if (task.kind === 'picture') {
    const img = image(task, (missing) => missing.replaceWith(named(task)));
    img.loading = 'eager';
    viewerFrame.replaceChildren(img);
    return;
  }
  // A video in a post plays in Chaya's player; anything else in its own program.
  const play = document.createElement('div');
  play.className = 'viewer__play';
  play.append(coversOf(task).length ? image(task, (img) => img.remove()) : named(task));
  play.append(pill(task.kind === 'video' ? 'Play' : 'Open', () => openSaved(task.id), true));
  viewerFrame.replaceChildren(play);
}

function stepViewer(by) {
  const at = viewing.at + by;
  if (at < 0 || at >= viewing.ids.length) return;
  viewing.at = at;
  showViewerPage();
}

viewerPrev.addEventListener('click', () => stepViewer(-1));
viewerNext.addEventListener('click', () => stepViewer(1));
document.getElementById('viewer-close').addEventListener('click', closeViewer);
document.getElementById('viewer-folder').addEventListener('click', () => {
  tauri.core.invoke('show_download', { id: viewing.ids[viewing.at] });
});
viewer.addEventListener('keydown', (e) => {
  // The viewer's own keys; the window's (Ctrl+L, the tab keys) wait until it is closed.
  e.stopPropagation();
  if (e.key === 'Escape') closeViewer();
  else if (e.key === 'ArrowLeft') stepViewer(-1);
  else if (e.key === 'ArrowRight') stepViewer(1);
});

// ── The player ──────────────────────────────────────────────────────────────────────────────
//
// A saved video or sound plays here rather than in another program: from the list, the grid or a post's viewer. The
// file comes through `chaya-file`, a part at a time so it seeks (`saved_file` in lib.rs). The player draws its own
// controls over the picture, and they fade while it plays. Beside it, two tabs: the transcript, the lines of the
// subtitles kept next to the video ("name.en.srt"), to read and jump through; and the subtitles, to choose a language
// or make them on this PC. Chaya draws the line on show itself rather than with `<track>`, so a later step can stack
// two languages. Where playback stopped is kept (`core::video`), and the next time it starts there.

const player = document.getElementById('player');
const playerVideo = document.getElementById('player-video');
const playerCaption = document.getElementById('player-caption');
const playerCaptionTop = document.getElementById('player-caption-top');
const playerCaptionUnder = document.getElementById('player-caption-under');
const playerLines = document.getElementById('player-lines');
const playerLinesHead = document.getElementById('player-lines-head');
const playerSubtitles = document.getElementById('player-subtitles');
const playerSpeed = document.getElementById('player-speed');
const playerSpeedButton = document.getElementById('player-speed-button');
const playerSpeedPanel = document.getElementById('player-speed-panel');
const playerResumed = document.getElementById('player-resumed');
const playerProblem = document.getElementById('player-problem');
const playerPlay = document.getElementById('player-play');
const playerMute = document.getElementById('player-mute');
const playerCc = document.getElementById('player-cc');
const playerTime = document.getElementById('player-time');
const playerSeek = document.getElementById('player-seek');
const playerHover = document.getElementById('player-hover');
const playerTabs = document.getElementById('player-tabs');
const playerNone = document.getElementById('player-none');
const playerMakeBox = document.getElementById('player-make-box');
const playerMaking = document.getElementById('player-making');
const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];
const TABS = {
  transcript: ['tab-transcript', 'panel-transcript'],
  highlights: ['tab-highlights', 'panel-highlights'],
  clips: ['tab-clips', 'panel-clips'],
  subtitles: ['tab-subtitles', 'panel-subtitles'],
};
const languageNames = (() => {
  try {
    return new Intl.DisplayNames(['en'], { type: 'language' });
  } catch {
    return null;
  }
})();

/** What plays: the download, its subtitles by language, the one chosen (or null), and its line on show. */
let playing = null;

function remembered(key, fallback) {
  try {
    return localStorage.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
}

function remember(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch {}
}

/** Whether the line on show is drawn over the picture (C, or the CC button). */
let captionsOn = remembered('chaya.player.captions', 'on') === 'on';

/** A finished download: a video or sound plays in the player, anything else opens in its own program. */
function openSaved(id) {
  const task = tasks.get(id);
  if (task?.kind === 'video' || task?.kind === 'audio') openPlayer(id);
  else tauri.core.invoke('open_download', { id });
}

/** "1:05", or "1:02:05" past an hour. */
function clock(seconds) {
  const s = Math.max(0, Math.floor(seconds || 0));
  const mm = String(Math.floor(s / 60) % 60);
  const ss = String(s % 60).padStart(2, '0');
  return s >= 3600 ? `${Math.floor(s / 3600)}:${mm.padStart(2, '0')}:${ss}` : `${mm}:${ss}`;
}

function languageName(code) {
  if (!code) return 'Subtitles';
  try {
    return languageNames?.of(code) ?? code;
  } catch {
    return code;
  }
}

/** Plays saved video `id` from where it stopped, or, with `from` (a line found by the search), from `from.at`
 * seconds with the subtitles in `from.language` shown. */
async function openPlayer(id, from = null) {
  const task = tasks.get(id);
  if (!task) return;
  if (!viewer.hidden) closeViewer();
  playing = { id, tracks: [], shown: null, line: null, savedAt: 0, clips: [], anchor: null, pick: null, cut: null, until: null, view: null, peaks: null, synced: null, chapters: [] };
  document.getElementById('player-title').textContent = task.title || task.fileName;
  playerProblem.hidden = true;
  playerResumed.hidden = true;
  playerCaption.hidden = true;
  // Nothing of the video before shows while this one's subtitles are read.
  playerLines.replaceChildren();
  playerLinesHead.hidden = true;
  playerNone.hidden = true;
  playerMakeBox.hidden = true;
  playerMaking.hidden = true;
  playerTranslating.hidden = true;
  document.getElementById('player-translate-box').hidden = true;
  document.getElementById('player-pick').hidden = true;
  document.getElementById('player-fixed').hidden = true;
  cut.hidden = true;
  playerCc.hidden = true;
  player.hidden = false;
  showPageView();
  showTab(remembered('chaya.player.tab', 'transcript'));
  showSpeeds();
  loadExports();
  playerVideo.poster = posterOf(id);
  playerVideo.src = tauri.core.convertFileSrc(String(id), 'chaya-file');
  playerVideo.playbackRate = Number(remembered('chaya.player.speed', '1')) || 1;
  playerVideo.muted = remembered('chaya.player.muted', 'no') === 'yes';
  showControls();
  showPosition();
  playerVideo.focus();

  markChapters();
  const [tracks, start, made, highlights, clips, chapters] = await Promise.all([
    tauri.core.invoke('video_subtitles', { id }).catch(() => []),
    tauri.core.invoke('playback_start', { id }).catch(() => 0),
    tauri.core.invoke('made_subtitles', { id }).catch(() => null),
    tauri.core.invoke('video_highlights', { id }).catch(() => null),
    tauri.core.invoke('video_clips', { id }).catch(() => []),
    tauri.core.invoke('video_chapters', { id }).catch(() => []),
  ]);
  if (playing?.id !== id) return;
  playing.chapters = chapters;
  markChapters();
  playing.tracks = tracks;
  playing.made = made;
  playing.highlights = highlights;
  playing.clips = clips;
  const wanted = from ? from.language : remembered('chaya.player.subtitles', '');
  showSubtitles(tracks.find((t) => t.language === wanted) ?? tracks[0] ?? null);
  whenReady(() => {
    if (from) {
      playerVideo.currentTime = from.at;
    } else if (start > 0) {
      playerVideo.currentTime = start;
      showResumed(start);
    }
    playerVideo.play().catch(() => {});
  });
}

/** Runs `then` once the video knows its length (now, if it already does). */
function whenReady(then) {
  if (playerVideo.readyState >= HTMLMediaElement.HAVE_METADATA) then();
  else playerVideo.addEventListener('loadedmetadata', then, { once: true });
}

function showResumed(at) {
  playerResumed.querySelector('span').textContent = `Playing from ${clock(at)}`;
  playerResumed.hidden = false;
  clearTimeout(showResumed.timer);
  showResumed.timer = setTimeout(() => (playerResumed.hidden = true), 6000);
}

function closePlayer() {
  if (!playing) return;
  closeExport();
  keepPosition(true);
  playing = null;
  dragging = null;
  sweeping = null;
  dropFrames();
  player.classList.remove('player--full', 'player--idle');
  openSpeeds(false);
  player.hidden = true;
  playerVideo.pause();
  playerVideo.removeAttribute('src');
  playerVideo.load();
  playerLines.replaceChildren();
  showPageView();
  showExports();
}

/** Tells the app where playback is, at most every few seconds unless `now`. */
function keepPosition(now = false) {
  if (!playing || !Number.isFinite(playerVideo.currentTime)) return;
  const moment = performance.now();
  if (!now && moment - playing.savedAt < 5000) return;
  playing.savedAt = moment;
  const duration = Number.isFinite(playerVideo.duration) ? playerVideo.duration : null;
  tauri.core.invoke('playback_stopped', { id: playing.id, at: playerVideo.currentTime, duration }).catch(() => {});
}

// ── The player's tabs ──

function showTab(name, focus = false) {
  if (!TABS[name]) name = 'transcript';
  for (const [key, [tabId, panelId]] of Object.entries(TABS)) {
    const tab = document.getElementById(tabId);
    const on = key === name;
    tab.setAttribute('aria-selected', String(on));
    tab.tabIndex = on ? 0 : -1;
    document.getElementById(panelId).hidden = !on;
    if (on && focus) tab.focus();
  }
  remember('chaya.player.tab', name);
  placeTabLight();
  if (playing) showCut();
  showExports();
}

/** The chosen tab's ground, under it; it slides there (`--dur-morph`, nothing under reduced motion). */
function placeTabLight() {
  const tab = playerTabs.querySelector('[aria-selected="true"]');
  if (!tab || !tab.offsetWidth) return;
  playerTabs.style.setProperty('--tab-x', `${tab.offsetLeft}px`);
  playerTabs.style.setProperty('--tab-width', `${tab.offsetWidth}px`);
}

new ResizeObserver(placeTabLight).observe(playerTabs);
for (const [name, [tabId]] of Object.entries(TABS)) {
  document.getElementById(tabId).addEventListener('click', () => showTab(name));
}
/** The tab before or after the one shown (← and → in the tabs). */
function stepTab(by) {
  const names = Object.keys(TABS);
  const at = names.findIndex((n) => document.getElementById(TABS[n][0]).getAttribute('aria-selected') === 'true');
  showTab(names[(at + by + names.length) % names.length], true);
}

// ── The player's controls ──

/** Shows the controls, and hides them again after a while if it is playing and nothing is being done with them. */
function showControls() {
  player.classList.remove('player--idle');
  clearTimeout(showControls.timer);
  showControls.timer = setTimeout(() => {
    const busy = playerVideo.paused || !playerSpeedPanel.hidden || playerSeek.classList.contains('is-dragging');
    if (playing && !busy) player.classList.add('player--idle');
  }, 2500);
}

/** The time, the seek bar and how much has loaded, as they are now. */
function showPosition() {
  const duration = Number.isFinite(playerVideo.duration) ? playerVideo.duration : 0;
  const at = playerVideo.currentTime || 0;
  playerTime.textContent = `${clock(at)} / ${clock(duration)}`;
  playerSeek.style.setProperty('--played', String(duration ? at / duration : 0));
  playerSeek.setAttribute('aria-valuemax', String(Math.round(duration)));
  playerSeek.setAttribute('aria-valuenow', String(Math.round(at)));
  playerSeek.setAttribute('aria-valuetext', `${clock(at)} of ${clock(duration)}`);
  showChapter(at * 1000);
  const { buffered } = playerVideo;
  let loaded = 0;
  for (let i = 0; i < buffered.length; i++) if (buffered.start(i) <= at + 0.5) loaded = buffered.end(i);
  playerSeek.style.setProperty('--buffered', String(duration ? Math.min(1, loaded / duration) : 0));
  followCut();
}

// ── Chapters ──
//
// A video's chapters, as its maker named them on the site (`chapters.js`): the seek bar broken where each begins, the
// one playing named after the time and under the pointer on the seek bar, Ctrl+← and Ctrl+→ to go between them, and a
// lane of them on the timeline, where a clip's edges snap to them.

const playerChapter = document.getElementById('player-chapter');

/** Where each chapter begins on the seek bar, and the one playing. */
function markChapters() {
  const chapters = playing?.chapters ?? [];
  document.getElementById('player-chapters').replaceChildren(
    ...dividers(chapters, lengthMs()).map((at) => {
      const divider = span('controls__divider', '');
      divider.style.left = `${at * 100}%`;
      return divider;
    }),
  );
  delete playerChapter.dataset.index;
  showChapter((playerVideo.currentTime || 0) * 1000);
}
playerVideo.addEventListener('loadedmetadata', markChapters);

/** The chapter playing at `ms`, named after the time; nothing for a video without chapters. */
function showChapter(ms) {
  const chapters = playing?.chapters ?? [];
  const index = chapterAt(chapters, ms);
  if (playerChapter.dataset.index === String(index)) return;
  playerChapter.dataset.index = String(index);
  playerChapter.hidden = index < 0;
  playerChapter.textContent = index < 0 ? '' : chapters[index].title;
  for (const el of document.querySelectorAll('#cut-chapters .cut__chapter')) {
    el.setAttribute('aria-current', String(el.dataset.chapter === String(index)));
  }
}

/** Ctrl+→ to the next chapter, Ctrl+← back to the start of this one or the one before. */
function goChapter(by) {
  const to = stepChapter(playing?.chapters ?? [], (playerVideo.currentTime || 0) * 1000, by);
  if (to === null) return;
  playing.until = null;
  playerVideo.currentTime = to / 1000;
  showPosition();
}

/** The chapters on their lane of the timeline, each a block with its title; a click plays from its start. */
function drawChapters() {
  const chapters = playing?.chapters ?? [];
  for (const el of cut.querySelectorAll('[data-lane="chapters"]')) el.hidden = chapters.length === 0;
  const lane = document.getElementById('cut-chapters');
  if (!chapters.length) {
    lane.replaceChildren();
    return;
  }
  const { from, to, at } = scale();
  const playingNow = chapterAt(chapters, (playerVideo.currentTime || 0) * 1000);
  lane.replaceChildren(
    ...chapters.flatMap((c, i) => {
      if (c.end <= from || c.start >= to) return [];
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'cut__chapter';
      el.tabIndex = -1;
      el.dataset.chapter = String(i);
      el.textContent = c.title;
      el.title = `${c.title} · ${clock(c.start / 1000)} – ${clock(c.end / 1000)}`;
      el.setAttribute('aria-current', String(i === playingNow));
      el.style.left = `${at(Math.max(c.start, from)) * 100}%`;
      el.style.width = `${(at(Math.min(c.end, to)) - at(Math.max(c.start, from))) * 100}%`;
      el.addEventListener('pointerdown', (e) => e.stopPropagation());
      el.addEventListener('click', () => {
        playing.until = null;
        playerVideo.currentTime = c.start / 1000;
      });
      return [el];
    }),
  );
}

/** The Clips tab's Add chapters to clips, while some chapter is not a clip yet. */
function showChapterClips() {
  const chapters = playing?.chapters ?? [];
  const button = document.getElementById('player-chapter-clips');
  button.hidden = !chapters.length || chapters.every((c) => clipOf(playing.clips, c.start, c.end));
}
document.getElementById('player-chapter-clips').addEventListener('click', () => {
  const wanted = (playing?.chapters ?? []).filter((c) => !clipOf(playing.clips, c.start, c.end));
  addClips(wanted.map((c) => ({ name: c.title, start: c.start, end: c.end })));
});

function showPlaying() {
  const paused = playerVideo.paused;
  playerPlay.querySelector('use').setAttribute('href', paused ? '#i-play' : '#i-pause');
  const label = paused ? 'Play (Space)' : 'Pause (Space)';
  playerPlay.setAttribute('aria-label', label);
  playerPlay.title = label;
  showControls();
}

function togglePlay() {
  if (playerVideo.paused) playerVideo.play().catch(() => {});
  else playerVideo.pause();
}

function showMuted() {
  const muted = playerVideo.muted;
  playerMute.querySelector('use').setAttribute('href', muted ? '#i-muted' : '#i-sound');
  const label = muted ? 'Sound on (M)' : 'Mute (M)';
  playerMute.setAttribute('aria-label', label);
  playerMute.title = label;
}

/** The fraction of the video at the pointer's place on the seek bar. */
function seekFraction(e) {
  const r = playerSeek.getBoundingClientRect();
  return Math.min(1, Math.max(0, (e.clientX - r.left) / r.width));
}

function seekTo(fraction) {
  if (playing) playing.until = null;
  if (Number.isFinite(playerVideo.duration)) playerVideo.currentTime = fraction * playerVideo.duration;
  showPosition();
}

playerSeek.addEventListener('pointerdown', (e) => {
  playerSeek.setPointerCapture(e.pointerId);
  playerSeek.classList.add('is-dragging');
  seekTo(seekFraction(e));
});
playerSeek.addEventListener('pointermove', (e) => {
  const fraction = seekFraction(e);
  if (playerSeek.classList.contains('is-dragging')) seekTo(fraction);
  if (!Number.isFinite(playerVideo.duration)) return;
  playerHover.hidden = false;
  const at = fraction * playerVideo.duration;
  const highlight = playing?.highlights?.items.find((h) => h.start <= at * 1000 && at * 1000 < h.end);
  const chapter = playing?.chapters?.[chapterAt(playing.chapters, at * 1000)];
  const named = highlight?.title ?? chapter?.title;
  playerHover.textContent = named ? `${clock(at)} · ${named}` : clock(at);
  playerHover.style.left = `${fraction * 100}%`;
});
playerSeek.addEventListener('pointerleave', () => (playerHover.hidden = true));
for (const end of ['pointerup', 'pointercancel']) {
  playerSeek.addEventListener(end, () => {
    playerSeek.classList.remove('is-dragging');
    showControls();
  });
}

playerPlay.addEventListener('click', togglePlay);
playerVideo.addEventListener('click', togglePlay);
playerMute.addEventListener('click', () => {
  playerVideo.muted = !playerVideo.muted;
  remember('chaya.player.muted', playerVideo.muted ? 'yes' : 'no');
});
playerCc.addEventListener('click', () => setCaptions(!captionsOn));
document.getElementById('player-stage').addEventListener('pointermove', showControls);
playerVideo.addEventListener('volumechange', showMuted);
playerVideo.addEventListener('play', showPlaying);
playerVideo.addEventListener('pause', showPlaying);
playerVideo.addEventListener('loadedmetadata', showPosition);
playerVideo.addEventListener('progress', showPosition);
playerVideo.addEventListener('durationchange', showPosition);

function setCaptions(on) {
  captionsOn = on;
  remember('chaya.player.captions', on ? 'on' : 'off');
  playerCc.setAttribute('aria-pressed', String(on));
  if (playing) playing.line = null;
  followLine();
}

function showSpeeds() {
  const rate = Number(remembered('chaya.player.speed', '1')) || 1;
  const buttons = SPEEDS.map((speed) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = `${speed}×`;
    button.setAttribute('aria-pressed', String(speed === rate));
    button.addEventListener('click', () => {
      playerVideo.playbackRate = speed;
      remember('chaya.player.speed', String(speed));
      showSpeeds();
      openSpeeds(false);
    });
    return button;
  });
  refill(playerSpeed, buttons);
  playerSpeedButton.textContent = `${rate}×`;
  playerSpeedButton.setAttribute('aria-label', `Speed, ${rate}×`);
}

/** Fills a group of choices again, the focus staying on the chosen one if it was in the group. */
function refill(group, buttons) {
  const focused = group.contains(document.activeElement);
  group.replaceChildren(...buttons);
  if (focused) group.querySelector('[aria-pressed="true"]')?.focus();
}

/** The speeds, in a small panel over the speed button. */
function openSpeeds(open) {
  playerSpeedPanel.hidden = !open;
  playerSpeedButton.setAttribute('aria-expanded', String(open));
  if (open) playerSpeed.querySelector('[aria-pressed="true"]')?.focus();
  else showControls();
}

playerSpeedButton.addEventListener('click', () => openSpeeds(playerSpeedPanel.hidden));
document.addEventListener('click', (e) => {
  if (!playerSpeedPanel.hidden && e.target.isConnected && !e.target.closest('.player__speed')) openSpeeds(false);
});

// ── The transcript and the subtitles ──

/** Shows `track`'s subtitles: its lines in the transcript, the line on show over the video; null shows none. */
function showSubtitles(track) {
  playing.shown = track;
  // Lines chosen are those of the track shown before.
  playing.anchor = null;
  playing.pick = null;
  // Drawn again from nothing, whatever was on show.
  playing.line = null;
  const { tracks } = playing;
  // The second language, shown with the first: the one chosen before, when this video has it.
  const wantedSecond = remembered('chaya.player.second', '');
  playing.second = (wantedSecond && tracks.find((t) => t !== track && (t.language ?? '') === wantedSecond)) || null;
  playerCc.hidden = tracks.length === 0;
  playerCc.setAttribute('aria-pressed', String(captionsOn));
  refill(
    playerSubtitles,
    tracks.map((t) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = languageName(t.language);
      button.setAttribute('aria-pressed', String(t === track));
      button.addEventListener('click', () => {
        remember('chaya.player.subtitles', t.language ?? '');
        showSubtitles(t);
        if (!captionsOn) setCaptions(true);
      });
      return button;
    }),
  );
  playerSubtitles.hidden = tracks.length === 0;
  document.getElementById('player-subtitles-label').textContent = tracks.length ? 'Show' : 'No subtitles next to this video yet.';
  const lines = track?.lines ?? [];
  playerLinesHead.hidden = !track;
  // Subtitles Chaya made name the model that heard them, and where; a site's say nothing more.
  const made = playing?.made?.language === track?.language ? playing.made : null;
  const by = made ? ` · Made by ${made.model}${made.on === 'graphicsCard' ? ', on the graphics card' : ''}` : '';
  playerLinesHead.textContent = track ? `${languageName(track.language)} · ${count(lines.length, 'line')}${by}` : '';
  playerLines.replaceChildren(
    ...lines.map((line, i) => {
      const li = document.createElement('li');
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'player__line';
      button.dataset.line = String(i);
      const text = span('', line.text);
      // The second language's line at the same moment, under it.
      const second = playing.second ? lineAt(playing.second.spans, line.start + 1) : -1;
      if (second >= 0) text.append(span('player__second', playing.second.lines[second].text));
      button.append(span('num', clock(line.start / 1000)), text);
      // Shift-click: the lines from the one clicked before to this one, chosen for a clip.
      button.addEventListener('mousedown', (e) => e.shiftKey && e.preventDefault());
      button.addEventListener('click', (e) => {
        if (e.shiftKey && playing.anchor !== null) {
          pickLines(playing.anchor, i);
          return;
        }
        playing.anchor = i;
        if (playing.pick) pickLines(null);
        playing.until = null;
        playerVideo.currentTime = line.start / 1000;
        playerVideo.play().catch(() => {});
      });
      li.append(button, lineActions(i));
      return li;
    }),
  );
  showTiming();
  showPick();
  showSecond();
  showMaking();
  showTranslate();
  showHighlights();
  showClips();
  followLine();
}

// ── Two languages at once ──
//
// A second language drawn with the first (the original and its translation), either on top; and each language's own
// colour, font and size, kept by language for every video.

const SWATCHES = { white: '--subtitle-text', yellow: '--subtitle-yellow', cyan: '--subtitle-cyan', green: '--subtitle-green', pink: '--subtitle-pink' };
const SIZES = { S: 0.8, M: 1, L: 1.2, XL: 1.45 };
const FONTS = { Sans: '--font-ui', Serif: '--font-serif', Display: '--font-display', Mono: '--font-mono' };

/** `language`'s subtitle style: its colour, size and font, as chosen in the Subtitles tab. */
function styleOf(language) {
  let kept = {};
  try {
    kept = JSON.parse(remembered(`chaya.style.${language ?? 'und'}`, '{}'));
  } catch {
    kept = {};
  }
  return { color: 'white', size: 'M', font: 'Sans', ...kept };
}

function setStyle(language, change) {
  remember(`chaya.style.${language ?? 'und'}`, JSON.stringify({ ...styleOf(language), ...change }));
  showSecond();
  if (playing) playing.line = null;
  followLine();
}

/** Gives a caption line `language`'s style. */
function styleLine(el, language) {
  const style = styleOf(language);
  el.style.setProperty('--line-color', `var(${SWATCHES[style.color] ?? SWATCHES.white})`);
  el.style.setProperty('--line-font', `var(${FONTS[style.font] ?? FONTS.Sans})`);
  el.style.setProperty('--line-size', String(SIZES[style.size] ?? 1));
}

/** The Subtitles tab's second language, which goes on top, versions, and each shown language's style. */
function showSecond() {
  const { tracks, shown, second } = playing;
  const others = tracks.filter((t) => t !== shown);
  document.getElementById('player-second-box').hidden = !shown || others.length === 0;
  const choose = (t) => {
    remember('chaya.player.second', t ? t.language ?? '' : '');
    showSubtitles(shown);
  };
  const none = document.createElement('button');
  none.type = 'button';
  none.textContent = 'None';
  none.setAttribute('aria-pressed', String(!second));
  none.addEventListener('click', () => choose(null));
  refill(document.getElementById('player-second'), [
    none,
    ...others.map((t) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = languageName(t.language);
      button.setAttribute('aria-pressed', String(t === second));
      button.addEventListener('click', () => choose(t));
      return button;
    }),
  ]);

  document.getElementById('player-top-box').hidden = !second;
  const top = remembered('chaya.player.top', 'second');
  if (second) {
    refill(
      document.getElementById('player-top'),
      [
        ['first', shown],
        ['second', second],
      ].map(([which, t]) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = languageName(t.language);
        button.setAttribute('aria-pressed', String(which === top));
        button.addEventListener('click', () => {
          remember('chaya.player.top', which);
          showSecond();
          playing.line = null;
          followLine();
        });
        return button;
      }),
    );
  }

  const styles = document.getElementById('player-styles');
  const languages = [shown, second].filter(Boolean);
  styles.hidden = languages.length === 0;
  styles.replaceChildren(
    ...(languages.length ? [span('settings__label', 'Style')] : []),
    ...languages.map((t) => {
      const style = styleOf(t.language);
      const box = document.createElement('div');
      box.className = 'styles__lang';
      const swatches = document.createElement('div');
      swatches.className = 'styles__row';
      swatches.append(
        ...Object.entries(SWATCHES).map(([name, token]) => {
          const swatch = document.createElement('button');
          swatch.type = 'button';
          swatch.className = 'swatch';
          swatch.style.setProperty('--swatch', `var(${token})`);
          swatch.setAttribute('aria-label', `${languageName(t.language)} in ${name}`);
          swatch.title = name;
          swatch.setAttribute('aria-pressed', String(style.color === name));
          swatch.addEventListener('click', () => setStyle(t.language, { color: name }));
          return swatch;
        }),
      );
      const choice = (label, options, key) => {
        const seg = document.createElement('div');
        seg.className = 'seg';
        seg.setAttribute('role', 'group');
        seg.setAttribute('aria-label', `${languageName(t.language)}: ${label}`);
        seg.append(
          ...options.map((option) => {
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = option;
            button.setAttribute('aria-pressed', String(style[key] === option));
            button.addEventListener('click', () => setStyle(t.language, { [key]: option }));
            return button;
          }),
        );
        return seg;
      };
      box.append(
        span('styles__name', languageName(t.language)),
        swatches,
        choice('size', Object.keys(SIZES), 'size'),
        choice('font', Object.keys(FONTS), 'font'),
      );
      return box;
    }),
  );
  showVersions();
}

/** A language's translations by more than one model: which is the one next to the video, and the others to use. */
async function showVersions() {
  const box = document.getElementById('player-versions');
  const id = playing?.id;
  const versions = id == null ? [] : await tauri.core.invoke('video_translations', { id }).catch(() => []);
  if (playing?.id !== id) return;
  const shownLanguages = [playing.shown, playing.second].filter(Boolean).map((t) => t.language);
  const groups = shownLanguages
    .map((language) => [language, versions.filter((v) => v.to === language && v.whole)])
    .filter(([, list]) => list.length > 1);
  box.hidden = groups.length === 0;
  box.replaceChildren(
    ...groups.flatMap(([language, list]) => {
      const seg = document.createElement('div');
      seg.className = 'seg seg--wrap';
      seg.setAttribute('role', 'group');
      seg.setAttribute('aria-label', `Version of ${languageName(language)}`);
      seg.append(
        ...list.map((v) => {
          const button = document.createElement('button');
          button.type = 'button';
          button.textContent = v.model;
          button.title = `Translated ${new Date(v.made * 1000).toLocaleDateString()}`;
          button.setAttribute('aria-pressed', String(v.inFile));
          button.addEventListener('click', async () => {
            if (v.inFile) return;
            await tauri.core.invoke('use_translation', { id, to: v.to, model: v.model }).catch(() => {});
            reloadSubtitles(id, playing.shown?.language);
          });
          return button;
        }),
      );
      return [span('settings__label', `${languageName(language)} translated by`), seg];
    }),
  );
}

/** The line shown at `ms`, from the track's timeline (`core::subtitles::timeline`), or -1. */
function lineAt(spans, ms) {
  let lo = 0;
  let hi = spans.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (spans[mid].start <= ms) lo = mid + 1;
    else hi = mid;
  }
  const span = spans[lo - 1];
  return span && ms < span.end ? span.line : -1;
}

/** When the person last scrolled the transcript: it follows the video again a little after. */
let linesScrolled = 0;
['wheel', 'pointerdown', 'keydown'].forEach((kind) =>
  document.getElementById('panel-transcript').addEventListener(kind, () => (linesScrolled = performance.now())),
);

function followLine() {
  if (!playing) return;
  const track = playing.shown;
  const ms = playerVideo.currentTime * 1000;
  const line = track ? lineAt(track.spans, ms) : -1;
  const second = playing.second ? lineAt(playing.second.spans, ms) : -1;
  if (playing.line === `${line}:${second}`) return;
  playing.line = `${line}:${second}`;
  playerCaption.hidden = (line < 0 && second < 0) || !captionsOn;
  // The second language on top, or under the first, each in its own style.
  const first = [line < 0 ? '' : track.lines[line].text, track?.language];
  const other = [second < 0 ? '' : playing.second.lines[second].text, playing.second?.language];
  const [top, under] = remembered('chaya.player.top', 'second') === 'second' && playing.second ? [other, first] : [first, other];
  for (const [el, [text, language]] of [[playerCaptionTop, top], [playerCaptionUnder, under]]) {
    el.textContent = text;
    styleLine(el, language);
  }
  playerLines.querySelector('[aria-current="true"]')?.removeAttribute('aria-current');
  const current = line < 0 ? null : playerLines.querySelector(`[data-line="${line}"]`);
  if (!current) return;
  current.setAttribute('aria-current', 'true');
  if (performance.now() - linesScrolled > 4000) current.scrollIntoView({ block: 'center', behavior: 'smooth' });
}

/** The player's subtitles again, as they are next to the video now, showing those in `language`. */
async function reloadSubtitles(id, language) {
  const [tracks, made] = await Promise.all([
    tauri.core.invoke('video_subtitles', { id }).catch(() => null),
    tauri.core.invoke('made_subtitles', { id }).catch(() => null),
  ]);
  if (!tracks || playing?.id !== id) return;
  playing.tracks = tracks;
  playing.made = made;
  showSubtitles(tracks.find((t) => t.language === language) ?? tracks[0] ?? null);
}

// While it plays, the line and the seek bar follow every frame; the video's own events cover seeking while paused.
function followFrames() {
  stopAtClipEnd();
  followLine();
  showPosition();
  if (playing && !playerVideo.paused) requestAnimationFrame(followFrames);
}
playerVideo.addEventListener('play', () => requestAnimationFrame(followFrames));
playerVideo.addEventListener('seeked', () => {
  followLine();
  showPosition();
});
playerVideo.addEventListener('timeupdate', () => {
  stopAtClipEnd();
  if (playerVideo.paused) {
    followLine();
    showPosition();
  }
  keepPosition();
});
playerVideo.addEventListener('pause', () => {
  keepPosition(true);
  if (playing) playing.until = null;
  showCutPlaying();
});
playerVideo.addEventListener('play', showCutPlaying);
playerVideo.addEventListener('ended', () => keepPosition(true));
playerVideo.addEventListener('error', () => {
  if (playing && playerVideo.getAttribute('src')) playerProblem.hidden = false;
});
playerVideo.addEventListener('dblclick', (e) => {
  e.preventDefault();
  fullWindow();
});

function fullWindow(on = !player.classList.contains('player--full')) {
  player.classList.toggle('player--full', on);
  showControls();
  showExports();
}

// ── Making subtitles, from the player ──

/** How making the playing video's subtitles is going, over the tabs; and Make subtitles where it applies. */
function showMaking() {
  if (!playing) return;
  const job = speechJobs.get(playing.id);
  const active = job && !['done', 'failed'].includes(job.state);
  const model = speech.models.find((m) => m.id === speech.settings?.model);
  const offered = speech.available && !active;
  const empty = playing.tracks.length === 0;
  playerNone.hidden = !empty;
  document.getElementById('player-make-first').hidden = !offered;
  document.getElementById('player-none-note').textContent = speech.available
    ? 'Chaya can listen to this video on this PC and write them. Nothing is uploaded.'
    : 'This video came without subtitles.';
  playerMakeBox.hidden = !speech.available || empty;
  document.getElementById('player-make').hidden = !offered;
  document.getElementById('player-make-note').textContent =
    `Listens with ${model?.name ?? 'the model picked in the settings'}, in the language it hears, and replaces that language's file.`;
  clearTimeout(showMaking.timer);
  playerMaking.hidden = !job;
  if (!job) return;
  document.getElementById('player-making-text').textContent = speechText(job);
  const share = { model: job.total ? (100 * job.done) / job.total : 0, listening: job.percent, done: 100 }[job.state] ?? 0;
  playerMaking.style.setProperty('--p', String(share));
  const action = document.getElementById('player-making-action');
  action.hidden = job.state === 'done';
  action.textContent = job.state === 'failed' ? 'Try again' : 'Stop';
  if (job.state === 'done') showMaking.timer = setTimeout(() => (playerMaking.hidden = true), 5000);
}

function makeSubtitles() {
  if (playing) tauri.core.invoke('make_subtitles', { id: playing.id });
}

document.getElementById('player-make').addEventListener('click', makeSubtitles);
document.getElementById('player-make-first').addEventListener('click', makeSubtitles);
document.getElementById('player-making-action').addEventListener('click', () => {
  if (!playing) return;
  const failed = speechJobs.get(playing.id)?.state === 'failed';
  tauri.core.invoke(failed ? 'make_subtitles' : 'stop_subtitles', { id: playing.id });
});

/** A `speech` event about the video playing. */
function playerHeard(job) {
  if (playing?.id !== job.id) return;
  showMaking();
  if (job.state === 'done') reloadSubtitles(job.id, job.language);
}

// ── The player's keys ──

document.getElementById('player-close').addEventListener('click', closePlayer);
document.getElementById('player-full').addEventListener('click', () => fullWindow());
document.getElementById('player-restart').addEventListener('click', () => {
  playerVideo.currentTime = 0;
  playerResumed.hidden = true;
});
for (const open of ['player-open', 'player-problem-open']) {
  document.getElementById(open).addEventListener('click', () => {
    const id = playing?.id;
    playerVideo.pause();
    if (id !== undefined) tauri.core.invoke('open_download', { id });
  });
}
document.getElementById('player-folder').addEventListener('click', () => {
  if (playing) tauri.core.invoke('show_download', { id: playing.id });
});
// The player's own keys, wherever the focus is while it is open; the window's (Ctrl+L, the tab keys) wait until it is
// closed. Taken before anything else hears them.
document.addEventListener(
  'keydown',
  (e) => {
    if (player.hidden) return;
    e.stopPropagation();
    // The Export dialog, while it is open, has the keys to itself.
    if (!exportBox.hidden) {
      exportKey(e);
      return;
    }
    showControls();
    const onButton = e.target instanceof Element && e.target.closest('button, [role="tab"]');
    const typing = e.target instanceof Element && e.target.closest('input, textarea, select');
    const edge = e.target instanceof Element && e.target.closest('.cut__edge');
    const key = e.key.toLowerCase();
    if (e.key === 'Escape') {
      if (typing && e.target.closest('.line__edit')) stopEditing(false);
      else if (typing) e.target.blur();
      else if (!playerSpeedPanel.hidden) openSpeeds(false);
      else if (player.classList.contains('player--full')) fullWindow(false);
      else if (playing?.pick) pickLines(null);
      else if (playing?.cut !== null && playing?.cut !== undefined) closeCut();
      else closePlayer();
    } else if (e.ctrlKey && key === 'b' && !e.shiftKey && !e.altKey) {
      e.preventDefault();
      makeClip();
    } else if (e.ctrlKey && key === 'e' && !e.shiftKey && !e.altKey) {
      e.preventDefault();
      openExport();
    } else if (typing) {
      // The field's own: a name typed, Enter to keep it.
      if (e.key === 'Enter' && e.target === cutName) cutName.blur();
      // A line being corrected: Enter keeps it, Shift+Enter breaks it in two.
      if (e.key === 'Enter' && !e.shiftKey && e.target.closest('.line__edit')) {
        e.preventDefault();
        stopEditing(true);
      }
    } else if (edge && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
      e.preventDefault();
      nudgeEdge(edge === cutStart ? 'start' : 'end', e.key === 'ArrowRight' ? 1 : -1, e.altKey);
    } else if (e.ctrlKey && !e.altKey && !e.shiftKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight') && playing?.chapters.length) {
      e.preventDefault();
      goChapter(e.key === 'ArrowRight' ? 1 : -1);
    } else if (e.ctrlKey || e.altKey || e.metaKey) {
      // Not the player's.
    } else if (key === 'f') {
      fullWindow();
    } else if (key === 'm') {
      playerMute.click();
    } else if (key === 'c' && playing?.tracks.length) {
      setCaptions(!captionsOn);
    } else if ((key === 'i' || key === 'o') && cutClip()) {
      edgeAtPlayhead(key === 'i' ? 'start' : 'end', e.shiftKey);
    } else if (e.key === ' ' && !onButton) {
      e.preventDefault();
      togglePlay();
    } else if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && e.target.closest?.('[role="tab"]')) {
      e.preventDefault();
      stepTab(e.key === 'ArrowRight' ? 1 : -1);
    } else if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && !onButton) {
      e.preventDefault();
      playerVideo.currentTime = Math.max(0, playerVideo.currentTime + (e.key === 'ArrowLeft' ? -5 : 5));
    }
  },
  true,
);
// A removed download takes its player with it.
tauri?.event.listen('download', ({ payload }) => {
  if (payload.type === 'removed' && playing?.id === payload.id) closePlayer();
});

// ── Subtitles made on the PC ────────────────────────────────────────────────────────────────
//
// whisper.cpp listens to a saved video on this PC and its subtitles are saved next to it (`speech.rs`). Make subtitles
// is in the player's bar; Settings › Speech recognition picks the model, the language spoken, and whether every
// download gets subtitles. How each is getting on is in its row and over the player.

const speechBox = document.getElementById('settings-speech');
const speechModels = document.getElementById('speech-models');
const speechLanguage = document.getElementById('speech-language');
const speechEvery = document.getElementById('speech-every');
const speechCard = document.getElementById('speech-card');
const speechUsed = document.getElementById('speech-used');
// Whisper's languages most people here will want, named in English by the window; the rest are detected.
const LANGUAGES = ['ar', 'bn', 'cs', 'da', 'de', 'el', 'en', 'es', 'fa', 'fi', 'fr', 'gu', 'he', 'hi', 'hu', 'id', 'it',
  'ja', 'ko', 'mr', 'ms', 'nl', 'no', 'pa', 'pl', 'pt', 'ro', 'ru', 'sv', 'ta', 'te', 'th', 'tr', 'uk', 'ur', 'vi', 'zh'];

/** What the app says of speech recognition: whether it is here, and on a graphics card, the person's choices, the
 * models. */
let speech = { available: false, card: false, settings: null, models: [] };

async function loadSpeech() {
  const status = await tauri.core.invoke('speech_status').catch(() => null);
  if (!status) return;
  speech = status;
  for (const job of status.jobs) speechJobs.set(job.id, job);
  showSpeechSettings();
  showMaking();
}

/** Where whisper.cpp is listening, as the making card says it. */
const LISTENING_ON = {
  graphicsCard: ' on the graphics card',
  processorAfterCard: ' on the processor (the graphics card couldn’t run this model)',
};

function speechText(job) {
  switch (job.state) {
    case 'waiting':
      return 'Waiting to make subtitles';
    case 'model':
      return `Fetching the speech model · ${fileSize(job.done)} of ${fileSize(job.total)}`;
    case 'sound':
      return 'Making subtitles · Reading the sound';
    case 'listening':
      return `Making subtitles${LISTENING_ON[job.on] ?? ''} · ${job.percent}%`;
    case 'done': {
      // Where it listened and how long it took, so the graphics card and the processor can be compared.
      const where = job.on === 'graphicsCard' ? 'on the graphics card' : 'on the processor';
      const by = job.model ? ` with ${job.model}` : '';
      const made = job.seconds == null ? `Subtitles made${by}` : `Subtitles made${by} ${where} in ${timeLeft(job.seconds)}`;
      return job.on === 'processorAfterCard' ? `${made}. The graphics card couldn’t run this model.` : made;
    }
    default:
      return job.message ?? 'Subtitles couldn’t be made.';
  }
}

function showSpeechSettings() {
  speechBox.hidden = !speech.available;
  if (!speech.available) return;
  const { settings } = speech;
  speechUsed.textContent = speech.used ? `Models on this PC: ${fileSize(speech.used)}` : 'No models on this PC yet';
  speechModels.replaceChildren(
    ...speech.models.map((model) => {
      const li = document.createElement('li');
      li.className = 'model';
      const pick = document.createElement('button');
      pick.type = 'button';
      pick.className = 'model__pick';
      const inUse = model.id === settings.model;
      pick.setAttribute('aria-pressed', String(inUse));
      const name = span('model__name', '');
      name.append(span('', model.name));
      if (inUse) name.append(span('model__tag model__tag--use', 'In use'));
      if (model.fetched) name.append(span('model__tag', 'Downloaded'));
      else if (model.arrived) name.append(span('model__tag', 'Partly downloaded'));
      const meta = span('model__meta', '');
      const size = model.fetched || !model.arrived ? fileSize(model.size) : `${fileSize(model.arrived)} of ${fileSize(model.size)}`;
      const where = model.fetched ? '' : model.arrived ? 'the rest when next used' : 'downloaded when first used';
      meta.append(span('num', size), ...[where, model.note].filter(Boolean).map((part) => ` · ${part}`));
      pick.append(name, meta);
      pick.addEventListener('click', () => saveSpeech({ model: model.id }));
      li.append(pick);
      if (model.fetched || model.arrived) {
        const room = fileSize(model.fetched ? model.size : model.arrived);
        const remove = pill('Delete', async () => {
          const refused = await tauri.core.invoke('delete_speech_model', { id: model.id }).then(() => null, String);
          await loadSpeech();
          if (refused) speechUsed.textContent = refused;
        });
        remove.classList.add('model__delete');
        remove.setAttribute('aria-label', `Delete ${model.name} from this PC, freeing ${room}`);
        remove.title = `Free ${room}; it is downloaded again when next used`;
        li.append(remove);
      }
      return li;
    }),
  );
  if (!speechLanguage.options.length) {
    const named = LANGUAGES.map((code) => [code, languageName(code)]).sort((a, b) => a[1].localeCompare(b[1]));
    speechLanguage.replaceChildren(
      new Option('Detected', ''),
      ...named.map(([code, name]) => new Option(name, code)),
    );
  }
  speechLanguage.value = settings.language ?? '';
  for (const b of speechEvery.querySelectorAll('button')) {
    b.setAttribute('aria-pressed', String(b.dataset.every === String(settings.everyDownload)));
  }
  document.getElementById('speech-card-box').hidden = !speech.card;
  for (const b of speechCard.querySelectorAll('button')) {
    b.setAttribute('aria-pressed', String(b.dataset.card === String(settings.graphicsCard)));
  }
}

function saveSpeech(change) {
  speech.settings = { ...speech.settings, ...change };
  showSpeechSettings();
  tauri.core.invoke('set_speech', { settings: speech.settings }).catch(() => loadSpeech());
}

// ── Copied links ──
//
// Ctrl+Alt+D from anywhere in Windows saves the link just copied, when the person turns it on (`copied.rs`): off or
// on, and the quality it saves. The app says how each press went as `copied-link` events: nothing to save, looking
// it up, saving it, or a link to show as if pasted (a sign-in, or a choice the setting can't make).

const copiedOn = document.getElementById('copied-on');
const copiedQuality = document.getElementById('copied-quality');
/** The setting as the app has it: on, the quality, the key, and why the key couldn't be had. */
let copied = null;

function showCopied() {
  if (!copied) return;
  document.getElementById('copied-key').textContent = copied.key;
  for (const b of copiedOn.querySelectorAll('[data-copied]')) b.setAttribute('aria-pressed', String(b.dataset.copied === String(copied.on)));
  for (const b of copiedQuality.querySelectorAll('[data-wanted]')) b.setAttribute('aria-pressed', String(b.dataset.wanted === copied.quality));
  document.getElementById('copied-quality-box').hidden = !copied.on;
  const note = document.getElementById('copied-note');
  note.hidden = !copied.problem;
  note.textContent = copied.problem ?? '';
}

async function setCopied(change) {
  if (!copied) return;
  const wanted = { on: copied.on, quality: copied.quality, ...change };
  copied = { ...copied, ...wanted };
  showCopied();
  try {
    copied = await tauri.core.invoke('set_copied_link', wanted);
  } catch (message) {
    copied = { ...copied, problem: String(message) };
  }
  showCopied();
}

copiedOn.addEventListener('click', (e) => {
  const on = e.target.closest('[data-copied]')?.dataset.copied;
  if (on) setCopied({ on: on === 'true' });
});
copiedQuality.addEventListener('click', (e) => {
  const quality = e.target.closest('[data-wanted]')?.dataset.wanted;
  if (quality) setCopied({ quality });
});
tauri?.core.invoke('copied_link').then((view) => {
  copied = view;
  showCopied();
}).catch(() => (document.getElementById('settings-copied').hidden = true));

/** The notice for the press being looked up, replaced by what comes of it. */
let copiedNotice = null;
tauri?.event.listen('copied-link', ({ payload }) => {
  copiedNotice?.remove();
  copiedNotice = null;
  switch (payload.kind) {
    case 'nothing':
      noticeFor(null, { text: 'Nothing to save', detail: 'Copy a link to a video or post, then press ' + (copied?.key ?? 'the key') + '.' });
      break;
    case 'looking':
      copiedNotice = noticeFor(null, { text: 'Looking up the copied link…', detail: payload.url });
      break;
    case 'saving':
      noticeFor(null, {
        text: `Saving “${payload.title}”`,
        detail: payload.choice === 'post' ? 'Every picture and video in it, to Downloads' : `${payload.choice}, to Downloads`,
      });
      break;
    case 'show':
      // As if pasted: the card says what the site wants, or offers the choices.
      address.value = payload.url;
      recognise();
      break;
  }
});

speechLanguage.addEventListener('change', () => saveSpeech({ language: speechLanguage.value || null }));
speechEvery.addEventListener('click', (e) => {
  const every = e.target.closest('[data-every]')?.dataset.every;
  if (every) saveSpeech({ everyDownload: every === 'true' });
});
speechCard.addEventListener('click', (e) => {
  const card = e.target.closest('[data-card]')?.dataset.card;
  if (card) saveSpeech({ graphicsCard: card === 'true' });
});

tauri?.event.listen('speech', ({ payload: job }) => {
  if (job.state === 'stopped') speechJobs.delete(job.id);
  else speechJobs.set(job.id, job);
  if (tasks.has(job.id)) renderRow(job.id);
  playerHeard(job);
  // A model fetched, or failed to be: the settings say which are on this PC.
  if (['done', 'failed', 'stopped'].includes(job.state)) loadSpeech();
});
loadSpeech();

/** A section whose downloads are all narrowed away keeps no heading. */
function hideEmptySections() {
  let head = null;
  let shown = false;
  for (const node of [...downloadList.children, null]) {
    if (!node || node.classList.contains('list__head')) {
      if (head) head.hidden = !shown;
      head = node;
      shown = false;
    } else if (!node.hidden) {
      shown = true;
    }
  }
}

// "Today" becomes "Yesterday" at midnight, with the window left open.
setInterval(() => {
  if (new Date().toDateString() !== arrangedDay) arrange();
}, 60_000);

findInput.addEventListener('input', () => narrow({ words: findInput.value }));
findInput.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && findInput.value) {
    e.stopPropagation();
    findInput.value = '';
    narrow({ words: '' });
  }
});
document.getElementById('narrow-clear').addEventListener('click', () => {
  findInput.value = '';
  narrow({ kind: null, site: null, words: '' });
});

tauri?.event.listen('download', ({ payload }) => {
  if (payload.type === 'changed') taskChanged(payload.task);
  else if (payload.type === 'progress') taskProgress(payload);
  else if (payload.type === 'removed') taskRemoved(payload.id);
});

tauri?.core.invoke('list_downloads').then((list) => {
  list.forEach(taskChanged);
  arrange();
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
  // Over a page, what is looked up and what the page holds open from the bar, so the page never moves for them.
  if (on) {
    mediaPanel.append(result, foundBox);
    foundBox.classList.add('found--open');
  } else {
    openMedia(false);
    addressForm.after(result);
    notice.after(foundBox);
    foundBox.classList.remove('found--open');
  }
  if (!on) {
    openShield(false);
    showDownloads(false);
    hideNotice();
    forgetFound();
    pageLookedAt = '';
    setLoading(false);
    pageUrl = '';
    hidePageStill();
  }
}

/**
 * Opens what `text` names in the page: an address, a site, an account, or a search. In the tab shown; from the home,
 * in a new tab.
 */
async function openPage(text, signIn = false) {
  forget();
  const first = !browsing;
  if (first) setBrowsing(true);
  showDownloads(false);
  // Laid out first, so the page opens where .page is.
  await new Promise(requestAnimationFrame);
  try {
    const url = await tauri.core.invoke('open_page', { text, bounds: boundsOf(pageEl), signIn, newTab: first });
    pageUrl = url;
    address.value = url;
    address.blur();
  } catch (message) {
    if (first) setBrowsing(false);
    // Over a page, a line above it rather than a card: the page is still the one shown.
    if (browsing) showNotice(String(message));
    else showProblem(text, String(message));
  }
}

/** The window's own home, which is also where a new tab starts: every tab stays open behind it. */
function goHome() {
  if (playing) closePlayer();
  tauri.core.invoke('go_home');
  setBrowsing(false);
  address.value = '';
  address.focus();
}

// ── Tabs ────────────────────────────────────────────────────────────────────────────────────
//
// The app keeps the tabs (`browser.rs`) and says how they are after every change; the window draws them, in the title
// bar's row, whenever any is open.

const tabstrip = document.getElementById('tabstrip');
const tabList = tabstrip.querySelector('.tabstrip__tabs');
const tabTemplate = document.getElementById('tab-template');
/** The tabs as last heard, and the one the window shows. */
let tabsNow = { tabs: [], active: null };
let activeTab = null;

function tabName(tab) {
  if (tab.title) return tab.title;
  return tab.url ? hostOf(tab.url) : 'New tab';
}

function showTabs(view) {
  tabsNow = view;
  tabstrip.hidden = view.tabs.length === 0;
  tabList.replaceChildren(
    ...view.tabs.map((tab) => {
      const el = tabTemplate.content.firstElementChild.cloneNode(true);
      el.dataset.id = tab.id;
      el.setAttribute('aria-selected', String(tab.id === view.active));
      el.classList.toggle('tab--loading', tab.loading);
      const title = el.querySelector('.tab__title');
      title.textContent = tabName(tab);
      title.title = tab.url ? `${tabName(tab)}\n${tab.url}` : tabName(tab);
      title.addEventListener('click', () => switchTab(tab.id));
      // A middle click closes a tab, as in any browser.
      el.addEventListener('auxclick', (e) => {
        if (e.button === 1) closeTab(tab.id);
      });
      el.querySelector('.tab__close').addEventListener('click', () => closeTab(tab.id));
      return el;
    }),
  );
  if (view.active === null && browsing) {
    // The last tab shown was closed: the home again.
    setBrowsing(false);
    address.value = '';
    activeTab = null;
  } else if (view.active !== null && view.active !== activeTab) {
    tabShown(view.tabs.find((t) => t.id === view.active));
  }
}

/** Another tab is shown: the bar, the shield and what the page holds are that tab's now. */
function tabShown(tab) {
  activeTab = tab?.id ?? null;
  if (!tab) return;
  if (!browsing) setBrowsing(true);
  forget();
  forgetFound();
  hideNotice();
  pageLookedAt = '';
  pageAt(tab.url);
  setLoading(tab.loading);
  tauri.core.invoke('ad_blocking').then((view) => showShield(view.shield)).catch(() => {});
}

async function switchTab(id) {
  // A tab is a place of its own: the player gives way to it.
  if (playing) closePlayer();
  if (id === activeTab && browsing) return;
  if (!browsing) setBrowsing(true);
  showDownloads(false);
  await new Promise(requestAnimationFrame);
  tauri.core.invoke('switch_tab', { id, bounds: boundsOf(pageEl) }).catch((message) => showNotice(String(message)));
}

function closeTab(id) {
  tauri.core.invoke('close_tab', { id });
}

/** The tab `step` places along from the one shown (or the first or last from the home), as Ctrl+Tab goes. */
function cycleTabs(step) {
  const { tabs } = tabsNow;
  if (!tabs.length) return;
  const at = tabs.findIndex((t) => t.id === activeTab);
  const next = at < 0 ? (step > 0 ? 0 : tabs.length - 1) : (at + step + tabs.length) % tabs.length;
  switchTab(tabs[next].id);
}

/** The tab key `e` is (with Ctrl held), named as `tab_key` in `browser.rs` names one pressed in a page. */
function tabKeyOf(e) {
  const key = e.key.toLowerCase();
  if (key === 't' && !e.shiftKey) return 'new-tab';
  if (key === 'w' && !e.shiftKey) return 'close-tab';
  if (key === 'tab') return e.shiftKey ? 'previous-tab' : 'next-tab';
  if (/^[1-9]$/.test(key) && !e.shiftKey) return `tab-${key}`;
  return null;
}

function tabKey(name) {
  if (name === 'new-tab') {
    goHome();
  } else if (name === 'close-tab') {
    // The tab shown, never Chaya's own window.
    if (browsing && activeTab !== null) closeTab(activeTab);
  } else if (name === 'next-tab' || name === 'previous-tab') {
    cycleTabs(name === 'next-tab' ? 1 : -1);
  } else if (name === 'address') {
    address.focus();
    address.select();
  } else if (name.startsWith('tab-')) {
    // Ctrl+9 is the last tab, as in any browser.
    const { tabs } = tabsNow;
    const n = Number(name.slice(4));
    const tab = n === 9 ? tabs[tabs.length - 1] : tabs[n - 1];
    if (tab) switchTab(tab.id);
  }
}

tauri?.event.listen('tabs', ({ payload }) => showTabs(payload));
// Pressed with the focus in a page (Windows): WebView2 hands them here.
tauri?.event.listen('tab-key', ({ payload }) => tabKey(payload));
tauri?.core.invoke('list_tabs').then(showTabs).catch(() => {});
document.getElementById('tab-new').addEventListener('click', goHome);

const pageStill = document.getElementById('page-still');
/** Counts the calls to showPageView, so a slow picture of the page is not shown after the panel has closed. */
let pageViews = 0;

/** The narrowest a page is made to stand beside an open panel; narrower, a still picture of it stands in. */
const NARROWEST_PAGE = 360;

/** The panel open over the page, if one is. */
function openPanel() {
  return [settingsPanel, shieldPanel, mediaPanel].find((panel) => !panel.hidden) ?? null;
}

/** Where the page goes: its place in the window, narrowed to what an open panel leaves beside it; null when that is
 * too narrow to be of use. */
function pageBounds() {
  const bounds = aboveToasts(boundsOf(pageEl));
  const panel = openPanel();
  if (!panel) return bounds;
  const width = Math.min(bounds.width, panel.getBoundingClientRect().left - 8 - bounds.x);
  return width >= NARROWEST_PAGE ? { ...bounds, width } : null;
}

/** `bounds` made shorter, where notices are shown, to end above them: the page is drawn over the window, so a
 *  notice can't lie over it. */
function aboveToasts(bounds) {
  if (!toasts.childElementCount) return bounds;
  const top = toasts.getBoundingClientRect().top - 8;
  if (top >= bounds.y + bounds.height) return bounds;
  return { ...bounds, height: Math.max(NARROWEST_PAGE / 2, top - bounds.y) };
}

/**
 * Shows the page unless something is shown in its place. The page is drawn over the window, so a panel can't lie
 * over it: while one is open the page steps aside for it and keeps playing; where the window is too narrow for both,
 * the page is hidden and a still picture of it stands in its place.
 */
async function showPageView() {
  if (!browsing) return;
  const view = ++pageViews;
  const panel = openPanel();
  // The window's own views over the page's place: the Downloads list, the player and the viewer.
  const covered = document.body.classList.contains('showing-downloads') || !player.hidden || !viewer.hidden;
  const bounds = pageBounds();
  if (panel && !bounds && !covered && pageStill.hidden) {
    const picture = await tauri.core.invoke('page_picture').catch(() => null);
    if (view !== pageViews) return;
    if (picture) {
      pageStill.src = picture;
      pageStill.hidden = false;
    }
  }
  if (bounds) {
    hidePageStill();
    tauri.core.invoke('place_page', { bounds });
  }
  tauri.core.invoke('show_page', { visible: !covered && !!bounds });
}

function hidePageStill() {
  pageStill.hidden = true;
  pageStill.removeAttribute('src');
}

function showDownloads(on) {
  if (on) openMedia(false);
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
const placePage = () => {
  if (browsing) showPageView();
};
new ResizeObserver(placePage).observe(pageEl);
new ResizeObserver(placePage).observe(document.querySelector('.main'));

/** Back from the Downloads list is back to the page it covers; from the page, the page's own history. */
function goBack() {
  if (document.body.classList.contains('showing-downloads')) showDownloads(false);
  else tauri.core.invoke('page_action', { action: 'back' });
}
document.getElementById('page-back').addEventListener('click', goBack);
document.getElementById('page-forward').addEventListener('click', () => tauri.core.invoke('page_action', { action: 'forward' }));
pageReload.addEventListener('click', () =>
  tauri.core.invoke('page_action', { action: pageLoad.hasAttribute('data-loading') ? 'stop' : 'reload' }),
);
pageDownloads.addEventListener('click', () =>
  showDownloads(!document.body.classList.contains('showing-downloads')),
);
document.getElementById('page-home').addEventListener('click', goHome);

tauri?.event.listen('page', ({ payload }) => {
  // Only the tab shown speaks to the bar; the others' titles and loading show in the strip.
  if (!browsing || payload.tab !== activeTab) return;
  switch (payload.type) {
    case 'loading':
      setLoading(true);
      pageAt(payload.url);
      forgetFound();
      break;
    case 'loaded':
      setLoading(false);
      pageAt(payload.url);
      break;
    case 'popup':
      // Only once the tabs are full; otherwise it opens in a new tab.
      showNotice(`This page tried to open ${hostOf(payload.url)} in a new tab, but ten are open.`, 'Open it here', () => openPage(payload.url));
      break;
    case 'saving':
      showNotice(`Saving ${payload.name} in Downloads.`, 'Show', () => showDownloads(true));
      break;
  }
});

// ── What the page holds ─────────────────────────────────────────────────────────────────────
//
// While a page is shown, the window asks every so often what its players have loaded. The main video gets a line
// of its own, with its Download button; the rest open under it.

const foundBox = document.getElementById('found');
const foundTemplate = document.getElementById('found-template');
/** The list on show, by address, so a new answer only adds what is new and keeps each button as it is. */
let shownFound = new Map();
let asking = false;

const FOUND_ICON = { video: 'i-video', audio: 'i-audio', stream: 'i-video', dash: 'i-video' };
const FOUND_NOUN = { video: 'Video', audio: 'Audio', stream: 'Stream', dash: 'Stream' };

function forgetFound() {
  shownFound = new Map();
  foundBox.querySelector('.found__list').replaceChildren();
  foundBox.hidden = true;
}

/** Looks a stream found on the page up for its qualities. */
function lookUpFound(item) {
  return showLookup({
    title: item.name,
    meta: item.host,
    finding: 'Finding its qualities…',
    noun: FOUND_NOUN[item.kind],
    ask: () => tauri.core.invoke('look_up_found', { url: item.url }),
    save: (choice) => tauri.core.invoke('download_found', { url: item.url, choice }),
    again: () => lookUpFound(item),
  });
}

/** The page's address last looked at, so a video site's page is looked up once, when it is reached. */
let pageLookedAt = '';

/**
 * A page that is a video or post on YouTube, Instagram, TikTok or X, or a Pinterest pin, is looked up as its link is. Such sites
 * change their address without loading a new page, so the address is followed as the window asks the page.
 */
async function followPage(url) {
  if (url === pageLookedAt) return;
  pageLookedAt = url;
  try {
    const link = await tauri.core.invoke('recognise_link', { text: url });
    if (url !== pageLookedAt || !browsing) return;
    if (link.kind === 'media') lookUp(link);
    else if (link.kind === 'account') showAccount(link);
    else forget();
  } catch {
    // Not recognised this time; the next address is.
  }
}

async function askPage() {
  if (!browsing || asking || document.body.classList.contains('showing-downloads')) return;
  asking = true;
  try {
    const page = await tauri.core.invoke('page_media');
    followPage(page.url);
    showPageMedia(page.items);
    showShield(page.ads);
  } catch {
    // No page, or it did not answer this time.
  } finally {
    asking = false;
  }
}

function showPageMedia(list) {
  if (!browsing) return;
  const listEl = foundBox.querySelector('.found__list');
  for (const item of list) {
    if (shownFound.has(item.url)) continue;
    const el = foundTemplate.content.firstElementChild.cloneNode(true);
    el.querySelector('use').setAttribute('href', `#${FOUND_ICON[item.kind]}`);
    el.querySelector('.found__name').textContent = item.name;
    const facts = [item.ad ? 'Ad' : null, FOUND_NOUN[item.kind], item.host].filter(Boolean);
    el.querySelector('.found__meta').textContent = facts.join(' · ');
    const state = el.querySelector('.found__state');
    if (item.qualities) {
      // A stream's qualities are looked up, and chosen from as a link's are.
      const choose = document.createElement('button');
      choose.type = 'button';
      choose.className = 'pill glass pressable';
      choose.textContent = 'Choose quality';
      choose.addEventListener('click', () => lookUpFound(item));
      el.querySelector('.found__action').append(choose);
    } else if (item.savable) {
      el.querySelector('.found__action').append(
        downloadButton(() => tauri.core.invoke('save_found', { url: item.url }), 'Download', state),
      );
    } else {
      state.append(paragraph(item.kind === 'dash' ? "Chaya can't save this kind of stream from a page yet." : "Saving streams needs Chaya's ffmpeg.", 'found__why'));
    }
    shownFound.set(item.url, el);
  }
  // The page's order: the main video first, ads last.
  const order = list.map((item) => shownFound.get(item.url));
  order.forEach((el, i) => el.classList.toggle('found__item--main', i === 0 && list[0].main));
  // Only when the list changed: putting an item back in starts its entrance again, so an unchanged list,
  // asked for every 1.5 s, would fade in over and over.
  const unchanged = order.length === listEl.children.length && order.every((el, i) => listEl.children[i] === el);
  if (!unchanged) listEl.replaceChildren(...order);
  const others = order.length - 1;
  const more = foundBox.querySelector('.found__more');
  more.hidden = others < 1;
  more.textContent = foundBox.classList.contains('found--open') ? 'Show less' : `${others} more on this page`;
  foundBox.hidden = order.length === 0;
}

foundBox.querySelector('.found__more').addEventListener('click', () => {
  foundBox.classList.toggle('found--open');
  const others = shownFound.size - 1;
  foundBox.querySelector('.found__more').textContent = foundBox.classList.contains('found--open') ? 'Show less' : `${others} more on this page`;
});

setInterval(askPage, 1500);

// ── What the page holds, from the bar ───────────────────────────────────────────────────────
//
// A pill in the bar names what was found on the page (or the video the page is, on YouTube and the other sites);
// its panel lists each with its Download, over a picture of the page.

const addressForm = document.getElementById('address-form');
const mediaWrap = document.querySelector('.media');
const mediaButton = document.getElementById('page-media');
const mediaPanel = document.getElementById('media-panel');
/** What the pill counted last, so it lights up only when something new is found. */
let mediaCount = 0;
/** What the card on show is a lookup of ("video", "post"), for the pill to name it. */
let resultNoun = '';

function openMedia(open) {
  if (open === !mediaPanel.hidden) return;
  mediaPanel.hidden = !open;
  mediaButton.setAttribute('aria-expanded', String(open));
  if (open) mediaButton.classList.remove('page-media--new');
  showPageView();
}

function capitalised(word) {
  return word ? word[0].toUpperCase() + word.slice(1) : '';
}

/** The pill: hidden while the page holds nothing; otherwise what it holds, lit when there is something new. */
function showMediaButton() {
  if (!browsing) return;
  const card = result.childElementCount > 0;
  const items = foundBox.querySelectorAll('.found__item');
  const count = (card ? 1 : 0) + items.length;
  let label = `${count} found`;
  if (count === 1) label = card ? capitalised(resultNoun) || 'Video' : items[0].querySelector('.found__meta').textContent.split(' · ').find((f) => f !== 'Ad');
  mediaButton.querySelector('.page-media__label').textContent = label;
  mediaButton.setAttribute('aria-label', `On this page: ${label}`);
  mediaButton.hidden = count === 0;
  if (count > mediaCount && mediaPanel.hidden) mediaButton.classList.add('page-media--new');
  if (count === 0) {
    mediaButton.classList.remove('page-media--new');
    openMedia(false);
  }
  mediaCount = count;
}

new MutationObserver(showMediaButton).observe(result, { childList: true });
new MutationObserver(showMediaButton).observe(foundBox.querySelector('.found__list'), { childList: true });

mediaButton.addEventListener('click', () => openMedia(mediaPanel.hidden));
document.addEventListener('click', (e) => {
  // The path as the click found it: a card's button may have replaced the card by the time this runs.
  if (!mediaPanel.hidden && !e.composedPath().includes(mediaWrap)) openMedia(false);
});

// ── The ad blocker ──────────────────────────────────────────────────────────────────────────
//
// The shield says how many ads and trackers were blocked on the page; its panel lets the person show a site's ads,
// or every site's, as the phone's ad blocker sheet does.

const shieldButton = document.getElementById('page-shield');
const shieldPanel = document.getElementById('shield-panel');
const shieldSite = document.getElementById('shield-site');
/** The ad blocker as last heard, for the panel's buttons. */
let ads = null;

function openShield(open) {
  shieldPanel.hidden = !open;
  shieldButton.setAttribute('aria-expanded', String(open));
  if (open) {
    tauri.core
      .invoke('ad_blocking')
      .then((view) => {
        showAds(view);
        showShield(view.shield);
      })
      .catch(() => {});
  }
  showPageView();
}

function adStatus({ site, enabled, blocking, blocked }) {
  if (!enabled) return 'Off: pages show their ads.';
  if (!site) return 'Nothing to block on this page.';
  if (!blocking) return `Ads are shown on ${site}.`;
  return blocked === 1 ? '1 ad or tracker blocked on this page.' : `${blocked} ads and trackers blocked on this page.`;
}

function listsNote(rules, updated) {
  const lists = 'Uses EasyList and EasyPrivacy, the lists uBlock Origin uses';
  if (rules == null) return `${lists}. Reading them…`;
  let when = 'the copies that came with Chaya';
  if (updated != null) {
    const days = Math.floor((Date.now() / 1000 - updated) / 86400);
    when = days <= 0 ? 'updated today' : days === 1 ? 'updated yesterday' : `updated ${days} days ago`;
  }
  return `${lists} (${rules.toLocaleString()} rules, ${when}). Chaya fetches fresh copies about once a week.`;
}

/** The shield's count, and the panel when it is open. */
function showShield(shield) {
  if (!shield) return;
  const badge = shieldButton.querySelector('.page-count');
  const counting = shield.enabled && shield.blocking && shield.blocked > 0;
  badge.textContent = shield.blocked > 99 ? '99+' : String(shield.blocked);
  badge.hidden = !counting;
  shieldButton.classList.toggle('page-shield--off', !(shield.enabled && shield.blocking));
  const label = `Ad blocker: ${adStatus(shield)}`;
  shieldButton.setAttribute('aria-label', label);
  shieldButton.title = label;
  if (!shieldPanel.hidden) showAds({ ...(ads ?? {}), shield });
}

function showAds(view) {
  ads = view;
  const { shield, updated } = view;
  document.getElementById('shield-status').textContent = adStatus(shield);
  document.getElementById('shield-lists').textContent = listsNote(shield.rules, updated);
  const siteShown = shield.enabled && Boolean(shield.site);
  shieldSite.hidden = !siteShown;
  document.getElementById('shield-site-label').hidden = !siteShown;
  document.getElementById('shield-site-label').textContent = `On ${shield.site}`;
  shieldSite.querySelectorAll('[data-site-ads]').forEach((b) =>
    b.setAttribute('aria-pressed', String((b.dataset.siteAds === 'block') === shield.blocking)),
  );
  shieldPanel.querySelectorAll('[data-all-ads]').forEach((b) =>
    b.setAttribute('aria-pressed', String((b.dataset.allAds === 'block') === shield.enabled)),
  );
}

/** A choice changes what the page loads, so the page loads again under it. */
async function chooseAds(command, args) {
  try {
    const view = await tauri.core.invoke(command, args);
    showAds(view);
    showShield(view.shield);
    tauri.core.invoke('page_action', { action: 'reload' });
  } catch (message) {
    showNotice(String(message));
  }
}

shieldButton.addEventListener('click', () => openShield(shieldPanel.hidden));
shieldSite.querySelectorAll('[data-site-ads]').forEach((button) =>
  button.addEventListener('click', () => {
    if (ads?.shield.site) chooseAds('allow_ads_on', { site: ads.shield.site, allowed: button.dataset.siteAds === 'show' });
  }),
);
shieldPanel.querySelectorAll('[data-all-ads]').forEach((button) =>
  button.addEventListener('click', () => chooseAds('block_ads', { on: button.dataset.allAds === 'block' })),
);
document.addEventListener('click', (e) => {
  if (!shieldPanel.hidden && !e.target.closest('.shield')) openShield(false);
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
  } else if (e.ctrlKey && !e.altKey && e.key.toLowerCase() === 'f' && !findField.hidden && viewer.hidden &&
    (!browsing || document.body.classList.contains('showing-downloads'))) {
    // The Downloads list's find box, which also finds what is said in the videos.
    e.preventDefault();
    findInput.focus();
    findInput.select();
  } else if (e.ctrlKey && !e.altKey && tabKeyOf(e)) {
    e.preventDefault();
    tabKey(tabKeyOf(e));
  } else if (browsing && e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
    // A browser's history keys, which WebView2 would otherwise spend on Chaya's own window.
    e.preventDefault();
    if (e.key === 'ArrowLeft') goBack();
    else tauri.core.invoke('page_action', { action: 'forward' });
  } else if (e.key === 'F5' || (e.ctrlKey && e.key.toLowerCase() === 'r')) {
    // A browser's reload keys reload the page. Left to WebView2 they would reload Chaya's own window instead.
    e.preventDefault();
    if (browsing) tauri.core.invoke('page_action', { action: 'reload' });
  } else if (e.key === 'Escape') {
    if (!settingsPanel.hidden) showSettings(false);
    else if (!shieldPanel.hidden) openShield(false);
    else if (!mediaPanel.hidden) openMedia(false);
    else if (document.body.classList.contains('showing-downloads')) showDownloads(false);
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

// ── AI model ──
//
// Settings › AI model (`ai.rs`): a service, its key (kept in Windows Credential Manager by the app, never here once
// saved), its address for one the person names or a program on the PC, and a model from its list. *Test* asks it
// something small. Nothing else uses it yet.

const aiService = document.getElementById('ai-service');
const aiAddress = document.getElementById('ai-address');
const aiKey = document.getElementById('ai-key');
const aiModel = document.getElementById('ai-model');
const aiModels = document.getElementById('ai-models');
const aiNote = document.getElementById('ai-note');
const aiKeyNote = document.getElementById('ai-key-note');
/** What the app says of it: the services, the person's choices, which services have a key saved, which run here. */
let ai = { services: [], settings: {}, keys: [], running: [] };
/** Counts the person's changes, so an answer asked for before one does not undo it. */
let aiChanges = 0;

async function loadAi() {
  const asked = aiChanges;
  const status = await tauri.core.invoke('ai_status').catch(() => null);
  if (!status) return;
  // Chosen while the app was looking (Ollama or LM Studio can take a moment): the window's choice stands.
  if (asked !== aiChanges) status.settings = ai.settings;
  const changed = status.settings.service !== ai.settings.service || status.keys.join() !== ai.keys.join();
  ai = status;
  showAi();
  if (changed) listAiModels();
}

function aiChosen() {
  return ai.services.find((s) => s.id === ai.settings.service) ?? null;
}

function showAi() {
  const running = ai.running.join();
  if (!aiService.options.length || aiService.dataset.running !== running) {
    aiService.dataset.running = running;
    aiService.replaceChildren(
      new Option('None', ''),
      ...ai.services.map((s) => new Option(ai.running.includes(s.id) ? `${s.name} · running on this PC` : s.name, s.id)),
    );
  }
  const service = aiChosen();
  aiService.value = service?.id ?? '';
  document.getElementById('ai-address-box').hidden = !service || !(service.local || service.id === 'custom');
  aiAddress.placeholder = service?.address || 'https://…/v1';
  if (document.activeElement !== aiAddress) aiAddress.value = ai.settings.address ?? '';

  document.getElementById('ai-key-box').hidden = !service?.key;
  const saved = !!service && ai.keys.includes(service.id);
  aiKey.value = '';
  aiKey.placeholder = saved ? 'Saved · paste a new one to replace it' : 'Paste your key';
  aiKeyNote.replaceChildren();
  if (saved) {
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'ai__link';
    remove.textContent = 'Remove it';
    remove.addEventListener('click', forgetAiKey);
    aiKeyNote.append('Kept in Windows Credential Manager. ', remove);
  } else if (service?.keysAt) {
    const link = document.createElement('button');
    link.type = 'button';
    link.className = 'ai__link';
    link.textContent = new URL(service.keysAt).host;
    link.addEventListener('click', () => {
      showSettings(false);
      openPage(service.keysAt);
    });
    aiKeyNote.append(`Make one at `, link, '.');
  }

  document.getElementById('ai-model-box').hidden = !service;
  if (document.activeElement !== aiModel) aiModel.value = ai.settings.model ?? '';
}

function sayAi(text) {
  aiNote.textContent = text;
}

function saveAi(change) {
  aiChanges += 1;
  ai.settings = { ...ai.settings, ...change };
  return tauri.core
    .invoke('set_ai', { settings: ai.settings })
    .then(() => loadTranslator())
    .catch((message) => sayAi(String(message)));
}

/** The chosen service's models, offered as the model field is typed in; a service that lists none is typed in full. */
async function listAiModels() {
  aiModels.replaceChildren();
  const service = aiChosen();
  if (!service || (service.key && !ai.keys.includes(service.id))) return;
  try {
    const names = await tauri.core.invoke('ai_models', { settings: ai.settings });
    if (ai.settings.service !== service.id) return;
    aiModels.replaceChildren(...names.map((name) => new Option(name)));
    aiModel.placeholder = names.length ? `Pick one of ${count(names.length, 'model')}, or type one` : 'Type the model’s name';
  } catch (message) {
    sayAi(String(message));
  }
}

async function forgetAiKey() {
  const service = aiChosen();
  if (!service) return;
  await tauri.core.invoke('forget_ai_key', { service: service.id }).catch((message) => sayAi(String(message)));
  sayAi('');
  await loadAi();
}

aiService.addEventListener('change', async () => {
  sayAi('');
  await saveAi({ service: aiService.value || null, address: null, model: null });
  showAi();
  listAiModels();
});
aiAddress.addEventListener('change', async () => {
  sayAi('');
  await saveAi({ address: aiAddress.value.trim() || null });
  listAiModels();
});
aiModel.addEventListener('change', () => {
  sayAi('');
  saveAi({ model: aiModel.value.trim() || null });
});
document.getElementById('ai-key-save').addEventListener('click', async () => {
  const service = aiChosen();
  if (!service) return;
  try {
    await tauri.core.invoke('set_ai_key', { service: service.id, key: aiKey.value });
    sayAi('');
    await loadAi();
  } catch (message) {
    sayAi(String(message));
  }
});
aiKey.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') document.getElementById('ai-key-save').click();
});
document.getElementById('ai-test').addEventListener('click', async () => {
  if (aiModel.value.trim() !== (ai.settings.model ?? '')) await saveAi({ model: aiModel.value.trim() || null });
  sayAi('Asking…');
  try {
    const seconds = await tauri.core.invoke('test_ai', { settings: ai.settings });
    sayAi(`It answered in ${seconds < 10 ? seconds.toFixed(1) : Math.round(seconds)} s. It’s ready.`);
  } catch (message) {
    sayAi(String(message));
  }
});
loadAi();

// ── Translating, from the player ──
//
// The subtitles shown, translated by the person's AI model (`translate.rs`): into a language picked in the Subtitles
// tab, the whole transcript going with every batch so names and terms come out right. A whole translation is saved
// next to the video ("name.es.srt") and listed with its other subtitles.

const translateJobs = new Map();
const translateTo = document.getElementById('player-translate-to');
const playerTranslating = document.getElementById('player-translating');
/** The person's model as *Translate* names it ("Gemini · gemini-3.5-flash-lite"), or null when not set up. */
let translator = null;

async function loadTranslator() {
  translator = await tauri.core.invoke('translator').catch(() => null);
  showTranslate();
  showHighlights();
}

/** The Subtitles tab's *Translate*: the subtitles shown, into the language picked, with the person's model. */
function showTranslate() {
  if (!playing) return;
  const shown = playing.shown;
  document.getElementById('player-translate-box').hidden = !shown;
  if (!shown) return;
  if (!translateTo.options.length) {
    const named = LANGUAGES.map((code) => [code, languageName(code)]).sort((a, b) => a[1].localeCompare(b[1]));
    const choose = new Option('Choose a language', '');
    choose.disabled = true;
    translateTo.replaceChildren(choose, ...named.map(([code, name]) => new Option(name, code)));
    const own = (navigator.language || 'en').split('-')[0];
    translateTo.value = remembered('chaya.translate.to', LANGUAGES.includes(own) ? own : 'en');
  }
  for (const option of [...translateTo.options].slice(1)) option.disabled = option.value === shown.language;
  // Never into the language the subtitles are already in.
  if (translateTo.value === shown.language) translateTo.value = '';
  const job = translateJobs.get(playing.id);
  const busy = job && ['waiting', 'translating'].includes(job.state);
  document.getElementById('player-translate-row').hidden = !translator;
  document.getElementById('player-translate-setup').hidden = !!translator;
  document.getElementById('player-translate').disabled = busy || !translateTo.value;
  document.getElementById('player-translate-note').textContent = translator
    ? `${languageName(shown.language)} subtitles, with ${translator}. Only their text and the video’s title are sent.`
    : 'Translating uses your own AI model: a service you have a key for, or one on this PC.';
  showTranslating();
}

function translatingText(job) {
  const into = languageName(job.language);
  switch (job.state) {
    case 'waiting':
      return `Waiting to translate into ${into}`;
    case 'translating':
      return `Translating into ${into} · ${job.done} of ${count(job.total, 'line')}`;
    case 'done':
      return `Translated into ${into} with ${job.model}`;
    default:
      return job.message ?? 'The translation couldn’t be finished.';
  }
}

/** How translating the playing video is going, over the tabs. */
function showTranslating() {
  if (!playing) return;
  const job = translateJobs.get(playing.id);
  clearTimeout(showTranslating.timer);
  playerTranslating.hidden = !job;
  if (!job) return;
  document.getElementById('player-translating-text').textContent = translatingText(job);
  const share = job.state === 'done' ? 100 : job.total ? (100 * job.done) / job.total : 0;
  playerTranslating.style.setProperty('--p', String(share));
  const action = document.getElementById('player-translating-action');
  action.hidden = job.state === 'done';
  action.textContent = job.state === 'failed' ? 'Try again' : 'Stop';
  if (job.state === 'done') showTranslating.timer = setTimeout(() => (playerTranslating.hidden = true), 6000);
}

function translate(to = translateTo.value) {
  if (!playing?.shown || !to) return;
  remember('chaya.translate.to', to);
  const from = playing.shown.language ?? null;
  translateJobs.set(playing.id, { id: playing.id, state: 'waiting', done: 0, total: 0, language: to, from });
  showTranslate();
  tauri.core.invoke('translate_video', { id: playing.id, from, to, into: languageName(to) }).catch((message) => {
    translateJobs.set(playing.id, { id: playing.id, state: 'failed', language: to, message: String(message) });
    showTranslate();
  });
}

document.getElementById('player-translate').addEventListener('click', () => translate());
translateTo.addEventListener('change', showTranslate);
document.getElementById('player-translate-setup').addEventListener('click', () => {
  showSettings(true);
  document.getElementById('settings-ai').scrollIntoView({ block: 'start' });
});
document.getElementById('player-translating-action').addEventListener('click', () => {
  const job = playing && translateJobs.get(playing.id);
  if (!job) return;
  if (job.state === 'failed') translate(job.language);
  else tauri.core.invoke('stop_translation', { id: playing.id });
});

tauri?.event.listen('translate', ({ payload: job }) => {
  const from = translateJobs.get(job.id)?.from;
  if (job.state === 'stopped') translateJobs.delete(job.id);
  else translateJobs.set(job.id, { ...job, from });
  if (playing?.id !== job.id) return;
  showTranslate();
  // Done: listed with the video's other subtitles, and shown.
  if (job.state === 'done') {
    remember('chaya.player.subtitles', job.language);
    reloadSubtitles(job.id, job.language);
  }
});
tauri?.core.invoke('translations_status').then((jobs) => jobs.forEach((job) => translateJobs.set(job.id, job))).catch(() => {});
loadTranslator();

// ── Highlights ──
//
// The moments worth watching on their own, found by the person's AI model in the subtitles shown (`highlights.rs`):
// listed in the Highlights tab with their time, title and reason, marked on the seek bar, the one playing marked in
// the list. Clicking one plays it from its start.

const playerHighlights = document.getElementById('player-highlights');
const findCount = document.getElementById('player-find-count');
const findButton = document.getElementById('player-find');
/** The video whose highlights are being found now, if any. */
let findingFor = null;

function showHighlights() {
  if (!playing) return;
  const found = playing.highlights?.items ?? [];
  const head = document.getElementById('player-highlights-head');
  document.getElementById('player-highlights-top').hidden = found.length === 0;
  head.textContent = found.length ? `${count(found.length, 'highlight')} · found by ${playing.highlights.model}` : '';
  playerHighlights.replaceChildren(
    ...found.map((h, i) => {
      const li = document.createElement('li');
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'highlight';
      button.dataset.highlight = String(i);
      button.append(
        span('highlight__time num', `${clock(h.start / 1000)} – ${clock(h.end / 1000)}`),
        span('highlight__title', h.title),
      );
      if (h.reason) button.append(span('highlight__reason', h.reason));
      button.addEventListener('click', () => {
        playing.until = null;
        playerVideo.currentTime = h.start / 1000;
        playerVideo.play().catch(() => {});
      });
      const add = document.createElement('button');
      add.type = 'button';
      add.className = 'card__act';
      add.dataset.add = String(i);
      add.addEventListener('click', () => addClips([{ name: h.title, start: h.start, end: h.end }]));
      li.append(button, add);
      return li;
    }),
  );
  markHighlightClips();
  markHighlights();

  // Find: in the subtitles shown, with the person's model; again, in place of those found before.
  const shown = playing.shown;
  findCount.value = remembered('chaya.highlights.count', '5');
  const busy = findingFor === playing.id;
  document.getElementById('player-find-label').textContent = found.length ? 'Find them again' : 'Find highlights';
  document.getElementById('player-find-row').hidden = !translator || !shown;
  document.getElementById('player-find-setup').hidden = !!translator || !shown;
  findButton.textContent = busy ? 'Stop' : 'Find';
  findCount.disabled = busy;
  const note = document.getElementById('player-find-note');
  if (busy) note.textContent = `Finding highlights with ${translator}…`;
  else if (!shown) note.textContent = 'Highlights are found in a video’s subtitles: make or translate them first.';
  else if (!translator) note.textContent = 'Highlights are found by your own AI model: a service you have a key for, or one on this PC.';
  else if (!note.dataset.kept) note.textContent = `In the ${languageName(shown.language)} subtitles, with ${translator}. Only their text and the video’s title are sent.`;
  delete note.dataset.kept;
}

/** The highlights on the seek bar, each where it is in the video. */
function markHighlights() {
  const marks = document.getElementById('player-marks');
  const duration = playerVideo.duration;
  const found = Number.isFinite(duration) && duration > 0 ? playing?.highlights?.items ?? [] : [];
  marks.replaceChildren(
    ...found.map((h) => {
      const mark = span('controls__mark', '');
      mark.style.left = `${Math.min(100, (h.start / 1000 / duration) * 100)}%`;
      mark.style.width = `${Math.max(0.4, ((h.end - h.start) / 1000 / duration) * 100)}%`;
      return mark;
    }),
  );
}
playerVideo.addEventListener('loadedmetadata', markHighlights);

/** The highlight playing now, marked in the list. */
function followHighlight() {
  const at = playerVideo.currentTime * 1000;
  const index = (playing?.highlights?.items ?? []).findIndex((h) => h.start <= at && at < h.end);
  const current = playerHighlights.querySelector('[aria-current="true"]');
  if (current && current.dataset.highlight === String(index)) return;
  current?.removeAttribute('aria-current');
  if (index >= 0) playerHighlights.querySelector(`[data-highlight="${index}"]`)?.setAttribute('aria-current', 'true');
}
playerVideo.addEventListener('timeupdate', followHighlight);

findCount.addEventListener('change', () => remember('chaya.highlights.count', findCount.value));
findButton.addEventListener('click', async () => {
  if (!playing?.shown) return;
  const id = playing.id;
  if (findingFor === id) {
    tauri.core.invoke('stop_highlights', { id });
    return;
  }
  findingFor = id;
  showHighlights();
  const note = document.getElementById('player-find-note');
  try {
    const found = await tauri.core.invoke('find_highlights', {
      id,
      from: playing.shown.language ?? null,
      language: languageName(playing.shown.language),
      count: Number(findCount.value),
    });
    if (playing?.id === id) playing.highlights = found;
  } catch (message) {
    if (playing?.id === id) {
      note.textContent = String(message);
      note.dataset.kept = 'yes';
    }
  } finally {
    findingFor = null;
    if (playing?.id === id) showHighlights();
  }
});
document.getElementById('player-find-setup').addEventListener('click', () => {
  showSettings(true);
  document.getElementById('settings-ai').scrollIntoView({ block: 'start' });
});

document.getElementById('player-highlights-all').addEventListener('click', () => {
  const found = playing?.highlights?.items ?? [];
  addClips(found.map((h) => ({ name: h.title, start: h.start, end: h.end })));
});

/** Each highlight's Add to clips, or that it is in them already. */
function markHighlightClips() {
  const found = playing?.highlights?.items ?? [];
  let all = true;
  for (const add of playerHighlights.querySelectorAll('[data-add]')) {
    const h = found[Number(add.dataset.add)];
    const there = h && clipOf(playing.clips, h.start, h.end);
    all &&= !!there;
    add.textContent = there ? 'In clips' : 'Add to clips';
    add.disabled = !!there;
  }
  document.getElementById('player-highlights-all').hidden = all;
}

// ── Clips ───────────────────────────────────────────────────────────────────────────────────
//
// Parts of the video cut out: lines chosen in the transcript (a click, then a Shift-click) made into a clip with
// Ctrl+B, or a highlight added. The Clips tab lists them; the one chosen is trimmed on the timeline under the picture,
// where its edges snap to the lines (`clips.js`). The app keeps them, put right, in the video's file.

const cut = document.getElementById('cut');
const cutName = document.getElementById('cut-name');
const cutStart = document.getElementById('cut-start');
const cutEnd = document.getElementById('cut-end');
const cutPlay = document.getElementById('cut-play');
const playerClips = document.getElementById('player-clips');

/** The video's length in milliseconds, or NaN before it is known. */
const lengthMs = () => (Number.isFinite(playerVideo.duration) ? playerVideo.duration * 1000 : NaN);
/** The lines shown. */
const shownLines = () => playing?.shown?.lines ?? [];
/** What a clip's edges snap to: the lines shown, and the chapters' edges. */
const edges = () => snapEdges(shownLines(), playing?.chapters ?? []);

/** Chooses lines `a` to `b` of those shown for a clip; `a` null chooses none. */
function pickLines(a, b = a) {
  if (!playing) return;
  playing.pick = a === null ? null : selection(shownLines(), a, b);
  showPick();
}

function showPick() {
  const pick = playing?.pick;
  for (const el of playerLines.querySelectorAll('.is-picked')) el.classList.remove('is-picked');
  document.getElementById('player-pick').hidden = !pick;
  if (!pick) return;
  for (let i = pick.first; i <= pick.last; i++) playerLines.querySelector(`[data-line="${i}"]`)?.classList.add('is-picked');
  const length = clock(Math.round((pick.end - pick.start) / 1000));
  document.getElementById('player-pick-text').textContent =
    `${count(pick.count, 'line')} · ${clock(pick.start / 1000)}–${clock(pick.end / 1000)} · ${length}`;
}
document.getElementById('player-pick-make').addEventListener('click', () => makeClip());
document.getElementById('player-pick-clear').addEventListener('click', () => pickLines(null));

/** Ctrl+B: a clip of the lines chosen; with none, of the line on show; in a video without subtitles, ten seconds
 *  from the playhead. It opens on the timeline. */
function makeClip() {
  if (!playing) return;
  const lines = shownLines();
  const at = playerVideo.currentTime * 1000;
  let range = playing.pick;
  if (!range && playing.shown) {
    const line = lineAt(playing.shown.spans, at);
    if (line >= 0) range = selection(lines, line, line);
  }
  const wanted = range
    ? { name: nameFrom(lines[range.first]?.text), start: range.start, end: range.end }
    : { name: '', start: Math.round(at), end: Math.round(at) + 10_000 };
  pickLines(null);
  addClips([wanted], true);
}

/** Adds `wanted` to the video's clips; with `open`, the first opens on the timeline (shown whole there, unless
 *  `reframe` is false and it is in sight already). */
async function addClips(wanted, open = false, reframe = true) {
  if (!playing || wanted.length === 0) return;
  const id = playing.id;
  try {
    const duration = Number.isFinite(playerVideo.duration) ? playerVideo.duration : null;
    const added = await tauri.core.invoke('add_clips', { id, clips: wanted, duration });
    if (playing?.id !== id) return;
    playing.clips = added.clips;
    const first = added.ids.find((clip) => clip !== null);
    if (open && first !== undefined) openCut(first, reframe);
    else showClips();
  } catch (message) {
    if (playing?.id === id) sayClips(String(message));
  }
}

/** Keeps `clip`'s name and times. */
async function saveClip(clip) {
  if (!playing) return;
  const id = playing.id;
  try {
    const clips = await tauri.core.invoke('change_clip', { id, clip });
    if (playing?.id === id) playing.clips = clips;
  } catch (message) {
    if (playing?.id === id) sayClips(String(message));
  }
  if (playing?.id === id) showClips();
}

async function removeClip(clipId) {
  if (!playing) return;
  const id = playing.id;
  try {
    const clips = await tauri.core.invoke('remove_clip', { id, clip: clipId });
    if (playing?.id !== id) return;
    playing.clips = clips;
    if (playing.cut === clipId) playing.cut = null;
  } catch (message) {
    if (playing?.id === id) sayClips(String(message));
  }
  if (playing?.id === id) showClips();
}

function sayClips(text) {
  const head = document.getElementById('player-clips-head');
  head.hidden = false;
  head.textContent = text;
  head.dataset.kept = 'yes';
}

/** The Clips tab's list, each highlight's Add to clips, and the timeline. */
function showClips() {
  if (!playing) return;
  const clips = playing.clips;
  const head = document.getElementById('player-clips-head');
  if (!head.dataset.kept) {
    const total = clips.reduce((sum, c) => sum + (c.end - c.start), 0);
    head.hidden = clips.length === 0;
    head.textContent = `${count(clips.length, 'clip')} · ${clock(Math.round(total / 1000))} in all`;
  }
  delete head.dataset.kept;
  document.getElementById('player-clips-none').hidden = clips.length > 0;
  playerExport.hidden = clips.length === 0;
  if (exporting) {
    if (clips.length) showExport();
    else closeExport();
  }
  playerClips.replaceChildren(
    ...clips.map((clip) => {
      const li = document.createElement('li');
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'highlight';
      button.dataset.clip = String(clip.id);
      button.setAttribute('aria-pressed', String(playing.cut === clip.id));
      const length = exact(clip.end - clip.start);
      button.append(
        span('highlight__time num', `${clock(clip.start / 1000)} – ${clock(clip.end / 1000)} · ${length}`),
        span('highlight__title', clip.name),
      );
      button.addEventListener('click', () => openCut(clip.id));
      const play = document.createElement('button');
      play.type = 'button';
      play.className = 'card__act';
      play.textContent = 'Play';
      play.addEventListener('click', () => playClip(clip));
      li.append(button, play);
      return li;
    }),
  );
  markHighlightClips();
  showChapterClips();
  showCut();
}

/** The clip on the timeline, if one is. */
function cutClip() {
  return playing?.clips.find((c) => c.id === playing.cut) ?? null;
}

/** Opens clip `clipId` on the timeline, at its start; shown whole there unless `reframe` is false and it is in sight. */
function openCut(clipId, reframe = true) {
  if (!playing) return;
  playing.cut = clipId;
  playing.until = null;
  const clip = cutClip();
  if (clip) {
    playerVideo.currentTime = clip.start / 1000;
    const inSight = playing.view && clip.start >= playing.view.from && clip.end <= playing.view.to;
    if (reframe || !inSight) playing.view = null;
  }
  showClips();
}

function closeCut() {
  if (!playing) return;
  playing.cut = null;
  playing.until = null;
  showClips();
}

/** Plays `clip` from its start, and stops at its end. */
function playClip(clip) {
  playerVideo.currentTime = clip.start / 1000;
  playerVideo.play().catch(() => {});
  playing.until = clip.end;
  showCutPlaying();
}

function stopAtClipEnd() {
  if (playing?.until != null && playerVideo.currentTime * 1000 >= playing.until) {
    playerVideo.pause();
    playerVideo.currentTime = playing.until / 1000;
    playing.until = null;
  }
}

function showCutPlaying() {
  const on = !!playing && playing.until != null && !playerVideo.paused;
  cutPlay.querySelector('use').setAttribute('href', on ? '#i-pause' : '#i-play');
  cutPlay.lastChild.textContent = on ? 'Pause' : 'Play clip';
  cutPlay.title = on ? 'Pause' : 'Play clip';
}

// ── The timeline ──
//
// Under the picture, as an editor has it: a ruler, the picture (frames taken from the video), the lines of the
// subtitles shown, the sound, and the clips. It shows while the Clips tab is open or a clip is chosen. The wheel moves
// along it and Ctrl+wheel (or − and +) zooms; it follows the playhead while the video plays. A clip chosen is lit
// across every track between two handles; dragging across the timeline makes a new one.

const cutEditor = document.getElementById('cut-editor');
const cutRuler = document.getElementById('cut-ruler');
const cutPictures = document.getElementById('cut-pictures');
const cutSound = document.getElementById('cut-sound');
const cutSweep = document.getElementById('cut-sweep');
const cutHover = document.getElementById('cut-hover');
const cutNote = document.getElementById('cut-sound-note');

/** The shortest and longest parts of the video the timeline shows, in milliseconds. */
const NARROWEST_VIEW = 2_000;
/** An edge being dragged (which, and the clip as it is), or a sweep across the timeline (where it began). */
let dragging = null;
let sweeping = null;
/** When the person last moved along the timeline themselves: it doesn't follow the playhead for a while after. */
let viewMoved = 0;

/** Whether the timeline shows: the Clips tab open, or a clip chosen. */
function cutWanted() {
  if (!playing) return false;
  const clipsTab = document.getElementById('tab-clips').getAttribute('aria-selected') === 'true';
  return clipsTab || cutClip() !== null;
}

/** The part of the video shown: kept in `playing.view`, inside the video. */
function setView(from, to) {
  const length = lengthMs();
  const longest = Number.isFinite(length) ? Math.max(NARROWEST_VIEW, length) : Math.max(to, NARROWEST_VIEW);
  let span = Math.min(longest, Math.max(NARROWEST_VIEW, to - from));
  from = Math.max(0, Math.min(from, longest - span));
  playing.view = { from, to: from + span };
  drawView();
}

/** The part shown when there is none yet: around the clip chosen, or half a minute around the playhead. */
function viewFor(clip) {
  if (clip) return clipView(clip, lengthMs());
  const at = playerVideo.currentTime * 1000;
  return { from: at - 10_000, to: at + 20_000 };
}

function showCut() {
  const shown = cutWanted();
  cut.hidden = !shown;
  if (!shown) return;
  const clip = dragging?.clip ?? cutClip();
  document.getElementById('cut-clip').hidden = !clip;
  document.getElementById('cut-title').hidden = !!clip;
  for (const id of ['cut-play', 'cut-remove', 'cut-close']) document.getElementById(id).hidden = !clip;
  if (clip && document.activeElement !== cutName) cutName.value = clip.name;
  const audioOnly = tasks.get(playing.id)?.kind === 'audio';
  for (const el of cut.querySelectorAll('[data-lane="picture"]')) el.hidden = audioOnly;
  document.getElementById('cut-label-lines').textContent = playing.shown ? languageName(playing.shown.language) : 'Lines';
  if (!playing.view) {
    const { from, to } = viewFor(clip);
    setView(from, to);
  } else {
    drawView();
  }
  loadPeaks();
  showCutPlaying();
}

/** Milliseconds a CSS pixel, and where on the timeline a time is (0 to 1). */
function scale() {
  const { from, to } = playing.view;
  const width = Math.max(1, cutRuler.clientWidth);
  return { from, to, width, perPx: (to - from) / width, at: (ms) => (ms - from) / (to - from) };
}

/** The time at the pointer's place on the timeline. */
function timeAt(e) {
  const r = cutRuler.getBoundingClientRect();
  const { from, to } = playing.view;
  return from + Math.min(1, Math.max(0, (e.clientX - r.left) / Math.max(1, r.width))) * (to - from);
}

/** Every track for the part shown, then the clip and the playhead over them. */
function drawView() {
  if (!playing?.view || cut.hidden) return;
  drawRuler();
  drawChapters();
  drawLines();
  drawClipsLane();
  drawPictures();
  drawRegion();
}

/** The ruler: a time every so often, at least 80 pixels apart, with small ticks between. */
function drawRuler() {
  const { from, to, perPx, at } = scale();
  const steps = [100, 200, 500, 1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000];
  const step = steps.find((s) => s / perPx >= 80) ?? steps[steps.length - 1];
  const marks = [];
  for (let t = Math.ceil(from / step) * step; t <= to; t += step) {
    const mark = span('cut__tick num', step < 1_000 ? exact(t) : clock(t / 1000));
    mark.style.left = `${at(t) * 100}%`;
    marks.push(mark);
  }
  cutRuler.replaceChildren(...marks);
  // Five small ticks to a step, drawn as a pattern lined up with the times.
  const px = step / perPx / 5;
  cutRuler.style.setProperty('--minor', `${px}px`);
  cutRuler.style.setProperty('--minor-at', `${(-(from % (step / 5)) / perPx).toFixed(2)}px`);
}

/** The subtitles' lines, each a block with its text. */
function drawLines() {
  const { from, to, at } = scale();
  const place = (el, start, end) => {
    el.style.left = `${at(Math.max(start, from)) * 100}%`;
    el.style.width = `${(at(Math.min(end, to)) - at(Math.max(start, from))) * 100}%`;
  };
  const lines = shownLines().filter((l) => l.end > from && l.start < to);
  document.getElementById('cut-lines').replaceChildren(
    ...lines.map((line) => {
      const el = span('cut__line', line.text.replace(/\n/g, ' '));
      el.title = line.text;
      place(el, line.start, line.end);
      return el;
    }),
  );
}

/** Every clip, on rows of their own where they overlap; the one chosen lit. Clicking one chooses it. */
function drawClipsLane() {
  const { from, to, at } = scale();
  const chosen = playing.cut;
  const placed = rows(playing.clips);
  const shown = playing.clips.filter((c) => c.end > from && c.start < to);
  const lane = document.getElementById('cut-clips');
  lane.style.setProperty('--rows', String(Math.max(1, ...[...placed.values()].map((r) => r + 1))));
  lane.replaceChildren(
    ...shown.map((c) => {
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'cut__other';
      el.tabIndex = -1;
      el.title = `${c.name} · ${exact(c.start)} – ${exact(c.end)}`;
      el.textContent = c.name;
      el.setAttribute('aria-pressed', String(c.id === chosen));
      el.style.setProperty('--row', String(placed.get(c.id)));
      el.style.left = `${at(Math.max(c.start, from)) * 100}%`;
      el.style.width = `${(at(Math.min(c.end, to)) - at(Math.max(c.start, from))) * 100}%`;
      el.addEventListener('pointerdown', (e) => e.stopPropagation());
      el.addEventListener('click', () => openCut(c.id, false));
      return el;
    }),
  );
}

/** The clip chosen, or the one being swept, between its handles; its times; and the sound, lit inside it. */
function drawRegion() {
  const clip = dragging?.clip ?? cutClip();
  const { from, to, at } = scale();
  const region = document.getElementById('cut-region');
  region.hidden = !clip || clip.end <= from || clip.start >= to;
  if (clip) {
    region.style.left = `${at(clip.start) * 100}%`;
    region.style.width = `${(at(clip.end) - at(clip.start)) * 100}%`;
    document.getElementById('cut-times').textContent =
      `${exact(clip.start)} – ${exact(clip.end)} · ${exact(clip.end - clip.start)}`;
    for (const [el, ms] of [[cutStart, clip.start], [cutEnd, clip.end]]) {
      el.setAttribute('aria-valuemin', '0');
      el.setAttribute('aria-valuemax', String(Math.round(lengthMs() || clip.end)));
      el.setAttribute('aria-valuenow', String(Math.round(ms)));
      el.setAttribute('aria-valuetext', exact(ms));
    }
  }
  drawSound();
  followCut();
}

/** A canvas drawn sharp at the screen's pixel density; its 2D context in CSS pixels, and its size. */
function sharp(canvas) {
  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
  }
  const g = canvas.getContext('2d');
  g.setTransform(ratio, 0, 0, ratio, 0, 0);
  g.clearRect(0, 0, width, height);
  return { g, width, height, ratio };
}

const cssColor = (name) => getComputedStyle(cut).getPropertyValue(name).trim();

// ── The sound, as bars ──

/** Reads the video's sound once (`sound_peaks`), and draws it. */
async function loadPeaks() {
  if (!playing || playing.peaks) return;
  const id = playing.id;
  playing.peaks = { loading: true };
  cutNote.textContent = 'Reading the sound…';
  cutNote.hidden = false;
  try {
    const { perSecond, peaks } = await tauri.core.invoke('sound_peaks', { id });
    if (playing?.id !== id) return;
    const bytes = Uint8Array.from(atob(peaks), (c) => c.charCodeAt(0));
    let loudest = 1;
    for (const b of bytes) loudest = Math.max(loudest, b);
    playing.peaks = { perSecond, bytes, loudest };
    cutNote.hidden = true;
  } catch (message) {
    if (playing?.id !== id) return;
    playing.peaks = { failed: true };
    cutNote.textContent = String(message);
  }
  drawSound();
}

function drawSound() {
  if (!playing?.view || cut.hidden) return;
  const { g, width, height } = sharp(cutSound);
  const peaks = playing.peaks;
  const middle = height / 2;
  if (!peaks?.bytes) {
    // Before it is read: a dashed line where it will be.
    g.strokeStyle = cssColor('--editor-rule');
    g.setLineDash([3, 3]);
    g.beginPath();
    g.moveTo(0, middle);
    g.lineTo(width, middle);
    g.stroke();
    return;
  }
  const { from, perPx } = scale();
  const clip = dragging?.clip ?? cutClip();
  const wave = cssColor('--editor-wave');
  const lit = cssColor('--accent');
  const BAR = 3;
  const perPeak = 1000 / peaks.perSecond;
  for (let x = 0; x < width; x += BAR) {
    const start = from + x * perPx;
    const end = start + BAR * perPx;
    let loudest = 0;
    const last = Math.min(peaks.bytes.length, Math.ceil(end / perPeak));
    for (let i = Math.max(0, Math.floor(start / perPeak)); i < last; i++) loudest = Math.max(loudest, peaks.bytes[i]);
    // Quiet sound still shows: the square root of how loud, against the loudest in the video.
    const size = Math.max(1, Math.sqrt(loudest / peaks.loudest) * (middle - 1));
    g.fillStyle = clip && start >= clip.start && start < clip.end ? lit : wave;
    g.fillRect(x, middle - size, BAR - 1, size * 2);
  }
}

// ── The picture, as frames ──
//
// Frames are taken by a second, silent copy of the video that seeks to each in turn, drawn small and kept for the
// video (the newest 300). Each frame stands for its own stretch of time, lined up so that moving along reuses them.

const frames = { id: null, video: null, ready: false, aspect: 16 / 9, kept: new Map(), wanted: [], busy: false };

/** Lets go of the frames' video, and the frames: the player is closed. */
function dropFrames() {
  if (frames.video) {
    frames.video.removeAttribute('src');
    frames.video.load();
  }
  Object.assign(frames, { id: null, video: null, ready: false, kept: new Map(), wanted: [], busy: false });
}

function framesFor(id) {
  if (frames.id === id) return;
  dropFrames();
  const video = document.createElement('video');
  video.muted = true;
  video.preload = 'auto';
  video.crossOrigin = 'anonymous';
  Object.assign(frames, { id, video, ready: false, kept: new Map(), wanted: [], busy: false });
  video.addEventListener('loadedmetadata', () => {
    if (frames.video !== video) return;
    frames.ready = video.videoWidth > 0;
    if (frames.ready) frames.aspect = video.videoWidth / video.videoHeight;
    drawPictures();
  });
  video.src = tauri.core.convertFileSrc(String(id), 'chaya-file');
}

/** The frame kept nearest `ms`, within `within` milliseconds. */
function frameNear(ms, within) {
  let best = null;
  let gap = within;
  for (const [t, canvas] of frames.kept) {
    if (Math.abs(t - ms) <= gap) {
      gap = Math.abs(t - ms);
      best = canvas;
    }
  }
  return best;
}

function drawPictures() {
  if (!playing?.view || cut.hidden || tasks.get(playing.id)?.kind === 'audio') return;
  framesFor(playing.id);
  const { g, width, height } = sharp(cutPictures);
  if (!frames.ready) return;
  const { from, to, perPx } = scale();
  const slotPx = Math.max(24, Math.round(height * frames.aspect));
  const slotMs = slotPx * perPx;
  const wanted = [];
  g.fillStyle = cssColor('--editor-lane');
  for (let k = Math.floor(from / slotMs); k * slotMs < to; k++) {
    const t = Math.round((k + 0.5) * slotMs);
    const x = (k * slotMs - from) / perPx;
    const frame = frameNear(t, slotMs / 2);
    if (frame) g.drawImage(frame, x, 0, slotPx, height);
    else {
      g.fillRect(x, 0, slotPx - 1, height);
      wanted.push(Math.min(t, (lengthMs() || t) - 50));
    }
  }
  // Those nearest the playhead first.
  const now = playerVideo.currentTime * 1000;
  frames.wanted = wanted.sort((a, b) => Math.abs(a - now) - Math.abs(b - now));
  takeFrames();
}

async function takeFrames() {
  if (frames.busy) return;
  frames.busy = true;
  const { video } = frames;
  try {
    while (frames.video === video && frames.ready && frames.wanted.length) {
      const t = frames.wanted.shift();
      video.currentTime = t / 1000;
      const seeked = await new Promise((resolve) => {
        const done = (ok) => {
          clearTimeout(timer);
          video.removeEventListener('seeked', onSeeked);
          resolve(ok);
        };
        const onSeeked = () => done(true);
        const timer = setTimeout(() => done(false), 4000);
        video.addEventListener('seeked', onSeeked);
      });
      if (!seeked || frames.video !== video) continue;
      const height = Math.max(1, Math.round(cutPictures.clientHeight * (window.devicePixelRatio || 1)));
      const canvas = document.createElement('canvas');
      canvas.height = height;
      canvas.width = Math.round(height * frames.aspect);
      try {
        canvas.getContext('2d').drawImage(video, 0, 0, canvas.width, canvas.height);
      } catch {
        continue;
      }
      frames.kept.set(t, canvas);
      if (frames.kept.size > 300) frames.kept.delete(frames.kept.keys().next().value);
      drawPicturesSoon();
    }
  } finally {
    if (frames.video === video) frames.busy = false;
  }
}

/** Draws the picture track at the next frame, once however many arrive before it. */
function drawPicturesSoon() {
  if (drawPicturesSoon.asked) return;
  drawPicturesSoon.asked = true;
  requestAnimationFrame(() => {
    drawPicturesSoon.asked = false;
    drawPictures();
  });
}

/** The playhead across the tracks, and the timeline following it while the video plays. */
function followCut() {
  if (!playing?.view || cut.hidden) return;
  const at = playerVideo.currentTime * 1000;
  const { from, to } = playing.view;
  const span = to - from;
  const free = !dragging && !sweeping && performance.now() - viewMoved > 3000;
  if (free && !playerVideo.paused && (at > to || at < from)) {
    // A page on, as an editor turns the page, with a little of the last one kept.
    setView(at - span * 0.1, at + span * 0.9);
    return;
  }
  const head = document.getElementById('cut-head');
  head.hidden = at < from || at > to;
  head.style.left = `${((at - from) / span) * 100}%`;
}

// Handles: dragged, they snap to the lines (Alt: anywhere) and show the frame there.
for (const [el, kind] of [[cutStart, 'start'], [cutEnd, 'end']]) {
  el.addEventListener('pointerdown', (e) => {
    const clip = cutClip();
    if (!clip) return;
    e.preventDefault();
    e.stopPropagation();
    try {
      el.setPointerCapture(e.pointerId);
    } catch {}
    playerVideo.pause();
    dragging = { kind, el, clip };
    el.focus();
  });
  el.addEventListener('pointermove', (e) => {
    if (dragging?.el !== el) return;
    dragging.clip = moveEdge(dragging.clip, kind, timeAt(e), edges(), lengthMs(), e.altKey);
    playerVideo.currentTime = dragging.clip[kind] / 1000;
    drawRegion();
  });
  for (const end of ['pointerup', 'pointercancel']) {
    el.addEventListener(end, () => {
      if (dragging?.el !== el) return;
      const { clip } = dragging;
      dragging = null;
      saveClip(clip);
    });
  }
}

// Across the tracks: a click plays from there; a drag sweeps out a new clip, its edges snapped to the lines.
cutEditor.addEventListener('pointerdown', (e) => {
  if (!playing?.view || e.button !== 0 || e.target.closest('.cut__edge, .cut__other')) return;
  try {
    cutEditor.setPointerCapture(e.pointerId);
  } catch {}
  sweeping = { x: e.clientX, at: timeAt(e), moved: false };
});
cutEditor.addEventListener('pointermove', (e) => {
  if (!playing?.view) return;
  const at = timeAt(e);
  const r = cutRuler.getBoundingClientRect();
  cutHover.hidden = e.clientX < r.left || e.clientX > r.right;
  cutHover.style.left = `${((at - playing.view.from) / (playing.view.to - playing.view.from)) * 100}%`;
  cutHover.dataset.time = exact(at);
  if (!sweeping) return;
  if (Math.abs(e.clientX - sweeping.x) > 4) sweeping.moved = true;
  if (!sweeping.moved) return;
  const [a, b] = [Math.min(sweeping.at, at), Math.max(sweeping.at, at)];
  const lines = edges();
  sweeping.range = { start: snap(lines, a, 'start', e.altKey), end: snap(lines, b, 'end', e.altKey) };
  const { from, to } = playing.view;
  cutSweep.hidden = false;
  cutSweep.style.left = `${((sweeping.range.start - from) / (to - from)) * 100}%`;
  cutSweep.style.width = `${(Math.max(0, sweeping.range.end - sweeping.range.start) / (to - from)) * 100}%`;
});
cutEditor.addEventListener('pointerleave', () => (cutHover.hidden = true));
for (const end of ['pointerup', 'pointercancel']) {
  cutEditor.addEventListener(end, (e) => {
    if (!sweeping) return;
    const done = sweeping;
    sweeping = null;
    cutSweep.hidden = true;
    if (end === 'pointercancel') return;
    if (!done.moved) {
      playing.until = null;
      playerVideo.currentTime = timeAt(e) / 1000;
      return;
    }
    const range = done.range;
    if (!range || range.end - range.start < 500) return;
    const first = shownLines().find((l) => l.end > range.start);
    addClips([{ name: first && first.start < range.end ? nameFrom(first.text) : '', ...range }], true, false);
  });
}

// The wheel moves along the timeline; Ctrl+wheel zooms around the pointer.
cutEditor.addEventListener(
  'wheel',
  (e) => {
    if (!playing?.view) return;
    e.preventDefault();
    viewMoved = performance.now();
    const { from, to, perPx } = scale();
    if (e.ctrlKey) {
      const anchor = timeAt(e);
      const factor = Math.exp(e.deltaY * 0.002);
      setView(anchor - (anchor - from) * factor, anchor + (to - anchor) * factor);
    } else {
      const by = (Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY) * perPx;
      setView(from + by, to + by);
    }
  },
  { passive: false },
);

/** − and +: zooms out or in around the playhead, or the middle when it is out of sight. */
function zoom(factor) {
  if (!playing?.view) return;
  viewMoved = performance.now();
  const { from, to } = playing.view;
  const at = playerVideo.currentTime * 1000;
  const anchor = at >= from && at <= to ? at : (from + to) / 2;
  setView(anchor - (anchor - from) * factor, anchor + (to - anchor) * factor);
}
document.getElementById('cut-zoom-out').addEventListener('click', () => zoom(2));
document.getElementById('cut-zoom-in').addEventListener('click', () => zoom(0.5));

/** The arrow keys on a handle: a line at a time, or a tenth of a second with Alt. */
function nudgeEdge(kind, by, free) {
  const clip = cutClip();
  if (!clip) return;
  const moved = stepEdge(clip, kind, by, edges(), lengthMs(), free);
  playerVideo.currentTime = moved[kind] / 1000;
  playing.clips = playing.clips.map((c) => (c.id === moved.id ? moved : c));
  showCut();
  saveClip(moved);
}

/** I and O: the clip's start or end at the playhead, snapped to a line unless Shift is held. */
function edgeAtPlayhead(kind, free) {
  const clip = cutClip();
  if (!clip) return;
  const at = playerVideo.currentTime * 1000;
  const length = clip.end - clip.start;
  // An edge put past the other takes the clip with it, as long as it was, so I and O work in either order.
  let from = clip;
  if (kind === 'start' && at >= clip.end) from = { ...clip, end: at + length };
  if (kind === 'end' && at <= clip.start) from = { ...clip, start: Math.max(0, at - length) };
  saveClip(moveEdge(from, kind, at, edges(), lengthMs(), free));
}

cutName.addEventListener('change', () => {
  const clip = cutClip();
  if (clip && cutName.value.trim() && cutName.value.trim() !== clip.name) saveClip({ ...clip, name: cutName.value });
  else if (clip) cutName.value = clip.name;
});
cutPlay.addEventListener('click', () => {
  const clip = cutClip();
  if (!clip) return;
  if (playing.until != null && !playerVideo.paused) playerVideo.pause();
  else playClip(clip);
});
document.getElementById('cut-remove').addEventListener('click', () => {
  if (playing?.cut != null) removeClip(playing.cut);
});
document.getElementById('cut-close').addEventListener('click', closeCut);
new ResizeObserver(() => drawView()).observe(cutEditor);
// The timeline may show before the video's length is known: once it is, it frames the video again (a clip chosen
// keeps its own framing).
playerVideo.addEventListener('loadedmetadata', () => {
  if (!playing || cut.hidden || cutClip()) return;
  playing.view = null;
  showCut();
});

// ── Export ──────────────────────────────────────────────────────────────────────────────────
//
// The Clips tab's Export (Ctrl+E): the clip chosen, or all of them, saved next to the video as videos with their
// subtitles burned in (one language or two, in the look chosen, at the quality chosen), or as SRT files timed from
// 0:00 for each clip. The app does the work, a job at a time (`export_clips`), and tells of each change with an
// `export` event. The jobs still going are listed under the clips; when one ends, a notice says so, whatever is shown.
// The rules are in `export.js`.

const exportBox = document.getElementById('export');
const exportSheet = exportBox.querySelector('.export__sheet');
const exportGo = document.getElementById('export-go');
const exportError = document.getElementById('export-error');
const playerExport = document.getElementById('player-export');
const playerExports = document.getElementById('player-exports');
const toasts = document.getElementById('toasts');
/** Every export this run, by job number, as the app last told of it. */
const exportJobs = new Map();
/** While the dialog is open: what is chosen in it, and what had the focus before it opened. */
let exporting = null;

/** The playing video's subtitles, by key (export.js). */
const exportTracks = () => playing.tracks.map(keyOf);
/** A key's track, its player style, and its name. */
const trackOf = (key) => playing.tracks.find((t) => keyOf(t) === key) ?? null;
const keyStyle = (key) => styleOf(key === '' ? null : key);
const keyName = (key) => languageName(key === '' ? null : key);

function openExport() {
  if (!playing || !exportBox.hidden) return;
  if (playing.clips.length === 0) {
    showTab('clips');
    sayClips('Make a clip first, then export it.');
    return;
  }
  let kept = {};
  try {
    kept = JSON.parse(remembered('chaya.export', '{}')) ?? {};
  } catch {
    kept = {};
  }
  exporting = {
    back: document.activeElement,
    choice: firstChoice({
      clips: playing.clips,
      cut: playing.cut,
      tracks: exportTracks(),
      shown: playing.shown ? keyOf(playing.shown) : null,
      second: playing.second ? keyOf(playing.second) : null,
      top: remembered('chaya.player.top', 'second'),
      captions: captionsOn,
      kept,
    }),
  };
  openSpeeds(false);
  fullWindow(false);
  exportError.hidden = true;
  exportGo.disabled = false;
  exportBox.hidden = false;
  showExport();
  exportGo.focus();
}

function closeExport() {
  if (exportBox.hidden) return;
  exportBox.hidden = true;
  const back = exporting?.back;
  exporting = null;
  if (back instanceof HTMLElement && back.isConnected && !player.hidden) back.focus();
}

/** Makes a change to what is chosen, and shows it. */
function chooseExport(change) {
  if (!exporting) return;
  exporting.choice = changed(exporting.choice, change, exportTracks());
  showExport();
}

/** A group of buttons, one pressed: `options` are [value, words]; `pick` is called with the value clicked. */
function exportSeg(group, labelledBy, options, current, pick) {
  const seg = document.createElement('div');
  seg.className = 'seg seg--wrap';
  seg.setAttribute('role', 'group');
  seg.setAttribute('aria-labelledby', labelledBy);
  seg.append(...options.map(([value, words]) => exportButton(group, value, words, value === current, () => pick(value))));
  return seg;
}

/** One of the dialog's buttons, named `group:value` so the focus finds it again once the dialog is drawn anew. */
function exportButton(group, value, words, pressed, onClick) {
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = words;
  button.dataset.pick = `${group}:${value}`;
  button.setAttribute('aria-pressed', String(pressed));
  button.addEventListener('click', onClick);
  return button;
}

/** Shows or hides one of the dialog's rows: its label and what it holds. */
function exportRow(id, on) {
  document.getElementById(id).hidden = !on;
  document.getElementById(`${id}-label`).hidden = !on;
}

/** Draws the dialog for what is chosen. */
function showExport() {
  if (!exporting || !playing) return;
  const focused = exportSheet.contains(document.activeElement) ? document.activeElement.dataset.pick : undefined;
  const { choice } = exporting;
  const tracks = exportTracks();
  const ids = clipIds(choice, playing.clips, playing.cut);
  const chosen = cutClip();
  const video = choice.saveAs === 'video';
  // A GIF has a picture, and so subtitles drawn on it and a look, but no sound, size or quality to choose.
  const picture = choice.saveAs !== 'srt';
  const length = (list) => clock(Math.round(list.reduce((sum, c) => sum + (c.end - c.start), 0) / 1000));

  // Clips: the one chosen on the timeline, or all of them.
  const clipsBox = document.getElementById('export-clips');
  const all = `All ${playing.clips.length} clips`;
  if (chosen && playing.clips.length > 1) {
    const seg = exportSeg('clips', 'export-clips-label', [['chosen', `“${chosen.name}”`], ['all', all]], choice.which, (which) =>
      chooseExport({ which }),
    );
    clipsBox.replaceChildren(seg, span('export__meta num', length(playing.clips.filter((c) => ids.includes(c.id)))));
  } else {
    const only = playing.clips.length === 1 ? `“${playing.clips[0].name}”` : all;
    clipsBox.replaceChildren(span('export__value', only), span('export__meta num', length(playing.clips)));
  }

  // Save as: a video, or subtitle files, which need subtitles.
  for (const button of document.querySelectorAll('#export-as [data-save-as]')) {
    button.setAttribute('aria-pressed', String(button.dataset.saveAs === choice.saveAs));
    const offered = button.dataset.saveAs !== 'srt' || tracks.length > 0;
    button.disabled = !offered;
    button.title = offered ? '' : 'This video has no subtitles yet';
  }

  // A video's subtitles: none, one language or two; which, and which goes on top.
  const subtitlesBox = document.getElementById('export-subtitles');
  if (tracks.length === 0) {
    subtitlesBox.replaceChildren(span('export__value muted', 'None: this video has no subtitles yet'));
  } else {
    const options = [
      ['none', 'None'],
      ['one', tracks.length === 1 ? keyName(tracks[0]) : 'One language'],
      ...(tracks.length > 1 ? [['two', 'Two languages']] : []),
    ];
    subtitlesBox.replaceChildren(
      exportSeg('subtitles', 'export-subtitles-label', options, choice.subtitles, (subtitles) => chooseExport({ subtitles })),
    );
  }
  const burned = picture && choice.subtitles !== 'none';
  exportRow('export-subtitles', picture);
  exportRow('export-first', burned && tracks.length > 1);
  exportRow('export-second', picture && choice.subtitles === 'two' && tracks.length > 2);
  exportRow('export-top', picture && choice.subtitles === 'two');
  exportRow('export-look', burned);
  exportRow('export-quality', video);
  exportRow('export-fit', video);
  exportRow('export-sound', video);
  exportRow('export-srt', choice.saveAs === 'srt' && tracks.length > 1);
  exportRow('export-together', choice.saveAs === 'srt' && choice.srt.length === 2);
  for (const button of document.querySelectorAll('#export-fit [data-fit]')) {
    button.setAttribute('aria-pressed', String(button.dataset.fit === String(choice.fit ?? '')));
  }
  for (const button of document.querySelectorAll('#export-sound [data-even]')) {
    button.setAttribute('aria-pressed', String((button.dataset.even === 'yes') === choice.even));
  }
  for (const button of document.querySelectorAll('#export-together [data-together]')) {
    button.setAttribute('aria-pressed', String((button.dataset.together === 'yes') === choice.together));
  }
  document.getElementById('export-first-label').textContent = choice.subtitles === 'two' ? 'Main language' : 'Language';
  refill(
    document.getElementById('export-first'),
    tracks.map((key) => exportButton('first', key, keyName(key), key === choice.first, () => chooseExport({ first: key }))),
  );
  refill(
    document.getElementById('export-second'),
    tracks
      .filter((key) => key !== choice.first)
      .map((key) => exportButton('second', key, keyName(key), key === choice.second, () => chooseExport({ second: key }))),
  );
  refill(
    document.getElementById('export-top'),
    choice.second === null
      ? []
      : [
          exportButton('top', 'first', keyName(choice.first), !choice.secondOnTop, () => chooseExport({ secondOnTop: false })),
          exportButton('top', 'second', keyName(choice.second), choice.secondOnTop, () => chooseExport({ secondOnTop: true })),
        ],
  );
  document.getElementById('export-look').replaceChildren(...LOOKS.map((look) => lookTile(look, choice)));
  for (const button of document.querySelectorAll('#export-quality [data-quality]')) {
    button.setAttribute('aria-pressed', String(button.dataset.quality === choice.quality));
  }

  // Subtitle files: one for each language turned on.
  refill(
    document.getElementById('export-srt'),
    tracks.map((key) =>
      exportButton('srt', key, keyName(key), choice.srt.includes(key), () => {
        exporting.choice = toggledSrt(exporting.choice, key, exportTracks());
        showExport();
      }),
    ),
  );

  const task = tasks.get(playing.id);
  const title = task?.title || (task?.fileName ?? '').replace(/\.[^.]+$/, '');
  const first = playing.clips.find((c) => c.id === ids[0]);
  document.getElementById('export-where').textContent = whereNote(choice, title, first?.name ?? '');
  exportGo.textContent = exportLabel(choice, ids.length);
  // A GIF of a clip longer than a minute is refused here, before the app would.
  const tooLong = choice.saveAs === 'gif' ? gifTooLong(playing.clips.filter((c) => ids.includes(c.id))) : null;
  if (tooLong) {
    exportError.textContent = tooLong;
    exportError.hidden = false;
    exportError.dataset.gif = 'yes';
  } else if (exportError.dataset.gif) {
    exportError.hidden = true;
    delete exportError.dataset.gif;
  }
  exportGo.disabled = !!tooLong;

  // The focus back on the button it was on, drawn anew; on the sheet, if that button has gone.
  if (focused !== undefined) {
    const again = focused && exportSheet.querySelector(`[data-pick="${CSS.escape(focused)}"]`);
    if (again && again.offsetParent !== null) again.focus();
    else if (!exportSheet.contains(document.activeElement) || document.activeElement.offsetParent === null) exportSheet.focus();
  }
}

/** A look to choose, with a small picture of the main language's first words drawn in it (both languages, for two). */
function lookTile(look, choice) {
  const button = exportButton('look', look, '', choice.look === look, () => chooseExport({ look }));
  button.className = 'look';
  button.setAttribute('aria-label', LOOK_NAMES[look]);
  const picture = span('look__picture', '');
  picture.setAttribute('aria-hidden', 'true');
  const keys = choice.subtitles === 'two' && choice.second !== null ? [choice.first, choice.second] : [choice.first];
  if (keys.length === 2 && choice.secondOnTop) keys.reverse();
  const words = span(`look__words look__words--${look}`, '');
  for (const key of keys) {
    const line = span('look__line', sampleWords(trackOf(key)?.lines[0]?.text));
    styleLine(line, key === '' ? null : key);
    words.append(line);
  }
  picture.append(words);
  button.append(picture, span('look__name', LOOK_NAMES[look]));
  return button;
}

async function startExport() {
  if (!exporting || !playing || exportGo.disabled) return;
  const { choice } = exporting;
  const id = playing.id;
  exportGo.disabled = true;
  exportError.hidden = true;
  try {
    await tauri.core.invoke('export_clips', {
      id,
      clips: clipIds(choice, playing.clips, playing.cut),
      options: optionsOf(choice, keyStyle),
    });
    remember('chaya.export', JSON.stringify(keptOf(choice)));
    closeExport();
  } catch (message) {
    exportError.textContent = String(message);
    exportError.hidden = false;
    exportGo.disabled = false;
  }
}

/** The dialog's keys: Esc closes it, and Tab goes round inside it. */
function exportKey(e) {
  if (e.key === 'Escape') {
    e.preventDefault();
    closeExport();
  } else if (e.key === 'Tab') {
    const stops = [...exportSheet.querySelectorAll('button')].filter((el) => !el.disabled && el.offsetParent !== null);
    if (!stops.length) return;
    const at = stops.indexOf(document.activeElement);
    if (e.shiftKey && at <= 0) {
      e.preventDefault();
      stops.at(-1).focus();
    } else if (!e.shiftKey && (at === -1 || at === stops.length - 1)) {
      e.preventDefault();
      stops[0].focus();
    }
  } else if (e.ctrlKey && e.key.toLowerCase() === 'e') {
    e.preventDefault();
  }
}

playerExport.addEventListener('click', openExport);
exportGo.addEventListener('click', startExport);
document.getElementById('export-close').addEventListener('click', closeExport);
document.getElementById('export-cancel').addEventListener('click', closeExport);
// A click beside the sheet closes it, as Esc does.
exportBox.addEventListener('click', (e) => {
  if (e.target === exportBox) closeExport();
});
document.getElementById('export-as').addEventListener('click', (e) => {
  const button = e.target.closest('[data-save-as]');
  if (button && !button.disabled) chooseExport({ saveAs: button.dataset.saveAs });
});
document.getElementById('export-quality').addEventListener('click', (e) => {
  const button = e.target.closest('[data-quality]');
  if (button) chooseExport({ quality: button.dataset.quality });
});
document.getElementById('export-fit').addEventListener('click', (e) => {
  const button = e.target.closest('[data-fit]');
  if (button) chooseExport({ fit: button.dataset.fit ? Number(button.dataset.fit) : null });
});
document.getElementById('export-sound').addEventListener('click', (e) => {
  const button = e.target.closest('[data-even]');
  if (button) chooseExport({ even: button.dataset.even === 'yes' });
});
document.getElementById('export-together').addEventListener('click', (e) => {
  const button = e.target.closest('[data-together]');
  if (button) chooseExport({ together: button.dataset.together === 'yes' });
});

// ── Exports going, and the notices when they end ──

/** The jobs the app has from this run (the window may have been opened again since they began). */
function loadExports() {
  tauri?.core
    .invoke('exports_status')
    .then((list) => {
      for (const job of list ?? []) exportJobs.set(job.job, job);
      showExports();
    })
    .catch(() => {});
}

/** An `export` event: how one job is now. */
function exportChanged(job) {
  const before = exportJobs.get(job.job);
  exportJobs.set(job.job, job);
  if (before?.state !== job.state) {
    const notice = noticeOf(job);
    if (notice) noticeFor(job, notice);
  }
  showExports();
}

/** The jobs still going: those of the video playing under its clips; the rest, and all of them while that list is
 *  out of sight, in a small card with the notices. */
function showExports() {
  const running = [...exportJobs.values()].filter(isRunning).sort((a, b) => a.job - b.job);
  const here = (job) => !!playing && !player.hidden && job.id === playing.id;
  const listed = running.filter(here);
  document.getElementById('player-exports-box').hidden = listed.length === 0;
  fillJobs(playerExports, listed);
  const inSight = !player.hidden && !document.getElementById('panel-clips').hidden && !player.classList.contains('player--full');
  showExportsCard(running.filter((job) => !(here(job) && inSight)));
}

/** Fills `list` with a row for each of `jobs`, keeping the rows already there (and the focus on their Stop). */
function fillJobs(list, jobs) {
  const wanted = new Set(jobs.map((job) => String(job.job)));
  for (const row of [...list.children]) if (!wanted.has(row.dataset.job)) row.remove();
  for (const job of jobs) {
    let row = list.querySelector(`[data-job="${job.job}"]`);
    if (!row) {
      row = jobRow(job.job);
      list.append(row);
    }
    row.dataset.state = job.state;
    row.style.setProperty('--p', String(job.state === 'waiting' ? 0 : job.percent ?? 0));
    row.querySelector('.export-job__name').textContent = job.name;
    row.querySelector('.export-job__pct').textContent = progressWords(job);
    row.querySelector('.export-job__stop').setAttribute('aria-label', `Stop exporting “${job.name}”`);
  }
}

function jobRow(job) {
  const row = document.createElement('li');
  row.className = 'making export-job';
  row.dataset.job = String(job);
  const text = document.createElement('div');
  text.className = 'making__text';
  const stop = document.createElement('button');
  stop.type = 'button';
  stop.className = 'downloads__all export-job__stop';
  stop.textContent = 'Stop';
  stop.addEventListener('click', () =>
    tauri.core.invoke('stop_export', { job }).catch((message) => noticeFor(null, { text: String(message), detail: '', failed: true })),
  );
  text.append(span('export-job__name', ''), span('export-job__pct num', ''), stop);
  const bar = span('making__bar', '');
  bar.append(document.createElement('span'));
  row.append(text, bar);
  return row;
}

/** At most this many jobs in the card; it counts the rest. */
const CARD_JOBS = 3;

function showExportsCard(jobs) {
  let card = document.getElementById('toast-exports');
  if (jobs.length === 0) {
    card?.remove();
    return;
  }
  if (!card) {
    card = document.createElement('div');
    card.id = 'toast-exports';
    card.className = 'toast toast--exports';
    // Each percent is not read out; the notice when each ends is.
    card.setAttribute('aria-live', 'off');
    const list = document.createElement('ul');
    list.className = 'exports';
    list.setAttribute('aria-label', 'Exporting');
    card.append(paragraph('Exporting', 'toast__text'), list, paragraph('', 'toast__detail'));
    toasts.append(card);
  }
  fillJobs(card.querySelector('.exports'), jobs.slice(0, CARD_JOBS));
  const more = jobs.length - CARD_JOBS;
  const rest = card.querySelector('.toast__detail');
  rest.hidden = more <= 0;
  rest.textContent = more > 0 ? `and ${more} more` : '';
}

/** How long a notice stays, unless it says something failed, or the pointer or the focus is on it. */
const NOTICE_MS = 8000;
/** At most this many notices at once; the oldest goes. */
const NOTICES = 4;

/** A notice, for `job` (or for no job, with no action): its words, the small words under them, and its action. */
function noticeFor(job, notice) {
  if (job) toasts.querySelector(`.toast[data-job="${job.job}"]`)?.remove();
  const toast = document.createElement('div');
  toast.className = 'toast';
  toast.setAttribute('role', notice.failed ? 'alert' : 'status');
  if (job) {
    toast.dataset.job = String(job.job);
    toast.dataset.state = job.state;
  }
  const body = document.createElement('div');
  body.className = 'toast__body';
  const detail = paragraph(notice.detail, 'toast__detail');
  detail.hidden = !notice.detail;
  body.append(paragraph(notice.text, 'toast__text'), detail);
  toast.append(body);
  if (job && notice.action) {
    const action = document.createElement('button');
    action.type = 'button';
    action.className = 'toast__action';
    action.textContent = notice.action;
    action.addEventListener('click', () => (notice.failed ? retryExport(job.job, toast) : showExported(job.job, toast)));
    toast.append(action);
  }
  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'icon-btn pressable toast__close';
  close.setAttribute('aria-label', 'Dismiss');
  close.title = 'Dismiss';
  close.innerHTML = '<svg><use href="#i-close"/></svg>';
  close.addEventListener('click', () => dismiss(toast));
  toast.append(close);
  toasts.insertBefore(toast, document.getElementById('toast-exports'));
  const shown = toasts.querySelectorAll('.toast:not(.toast--exports):not(.is-leaving)');
  if (shown.length > NOTICES) dismiss(shown[0]);
  if (!notice.failed) dismissLater(toast, NOTICE_MS);
  return toast;
}

function dismissLater(toast, ms) {
  setTimeout(() => {
    if (!toast.isConnected) return;
    if (toast.matches(':hover, :focus-within')) dismissLater(toast, 2000);
    else dismiss(toast);
  }, ms);
}

/** Takes a notice away; the focus, if it was in it, goes to the next one, or back to the window. */
function dismiss(toast) {
  if (!toast.isConnected || toast.classList.contains('is-leaving')) return;
  if (toast.contains(document.activeElement)) {
    const next = [...toasts.querySelectorAll('.toast:not(.is-leaving)')].find((t) => t !== toast);
    const button = next?.querySelector('button');
    if (button) button.focus();
    else if (!player.hidden) playerVideo.focus();
    else document.activeElement.blur();
  }
  leave(toast);
}

/** Says, in place of a notice's small words, why its action did not work. */
function sayInNotice(toast, message) {
  const detail = toast.querySelector('.toast__detail');
  detail.textContent = String(message);
  detail.hidden = false;
}

function showExported(job, toast) {
  tauri.core.invoke('show_export', { job }).catch((message) => sayInNotice(toast, message));
}

async function retryExport(job, toast) {
  try {
    await tauri.core.invoke('retry_export', { job });
    dismiss(toast);
  } catch (message) {
    sayInNotice(toast, message);
  }
}

// The page is made shorter for the notices while they show (`aboveToasts`), and longer again after.
new ResizeObserver(placePage).observe(toasts);
tauri?.event.listen('export', ({ payload }) => exportChanged(payload));
loadExports();

// ── Subtitles put right ─────────────────────────────────────────────────────────────────────
//
// A line's words corrected in the transcript (its pencil, or a double-click), or taken out by leaving it empty; and the
// timing moved, in one language or every one: "said now" on a line moves the lines so it starts at the playhead, and
// on a second line further on stretches them between the two, for subtitles that drift; or a little at a time from
// the Subtitles tab. The app writes the file next to the video and keeps what was there for Undo (`fix.rs`).

const playerFixed = document.getElementById('player-fixed');

/** A line's own buttons: correct it, and "said now". */
function lineActions(index) {
  const acts = span('line__acts', '');
  const make = (icon, label, action) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'line__act';
    button.setAttribute('aria-label', label);
    button.title = label;
    button.innerHTML = `<svg><use href="#${icon}"/></svg>`;
    button.addEventListener('click', (e) => {
      e.stopPropagation();
      action();
    });
    return button;
  };
  acts.append(
    make('i-pencil', 'Correct this line', () => startEditing(index)),
    make('i-sync', 'This line is said now: move the lines to the playhead', () => sayNow(index)),
  );
  return acts;
}

playerLines.addEventListener('dblclick', (e) => {
  const line = e.target.closest('.player__line');
  if (line) startEditing(Number(line.dataset.line));
});

/** The line being corrected: its index and its field. */
let editing = null;

function startEditing(index) {
  if (!playing?.shown) return;
  if (editing) stopEditing(false);
  const li = playerLines.querySelector(`[data-line="${index}"]`)?.closest('li');
  if (!li) return;
  playerVideo.pause();
  const field = document.createElement('textarea');
  field.className = 'field line__edit';
  field.value = playing.shown.lines[index].text;
  field.rows = Math.min(4, field.value.split('\n').length + 1);
  field.setAttribute('aria-label', `Line at ${clock(playing.shown.lines[index].start / 1000)}`);
  field.addEventListener('blur', () => editing?.field === field && stopEditing(true));
  const hint = span('line__hint', 'Enter keeps it · Shift+Enter for a new line · Esc leaves it · empty takes it out');
  li.classList.add('is-editing');
  li.append(field, hint);
  editing = { index, field, li };
  field.focus();
  field.setSelectionRange(field.value.length, field.value.length);
}

/** Ends correcting a line: kept (`keep`) when its words changed, or left as it was. */
async function stopEditing(keep) {
  if (!editing) return;
  const { index, field, li } = editing;
  editing = null;
  const before = playing?.shown?.lines[index]?.text ?? '';
  const text = field.value;
  li.classList.remove('is-editing');
  field.remove();
  li.querySelector('.line__hint')?.remove();
  if (!keep || !playing || text.trim() === before.trim()) return;
  const taken = text.trim() === '';
  await changeSubtitles('edit_line', { index, text }, taken ? 'Line taken out.' : 'Line corrected.');
}

/** "Said now": the line starts at the playhead; after another line was put right further away, the lines stretch. */
function sayNow(index) {
  const line = playing?.shown?.lines[index];
  if (!line) return;
  const at = Math.round(playerVideo.currentTime * 1000);
  const fixed = playing.synced;
  const ask = fixed != null && Math.abs(line.start - fixed) >= 5000
    ? { kind: 'through', fixed, from: line.start, to: at }
    : { kind: 'shift', by: at - line.start };
  const said =
    ask.kind === 'through'
      ? `Lines stretched to fit between ${clock(fixed / 1000)} and ${clock(at / 1000)}.`
      : moved(ask.by);
  changeSubtitles('retime_subtitles', { ask, every: timingEvery() }, said).then((ok) => {
    if (ok) playing.synced = at;
  });
}

function moved(by) {
  const seconds = (Math.abs(by) / 1000).toFixed(by % 1000 === 0 ? 0 : 1);
  return by === 0 ? 'Lines left where they were.' : `Lines moved ${seconds} s ${by > 0 ? 'later' : 'earlier'}.`;
}

/** Whether timing moves every language next to the video, or only the one shown. */
function timingEvery() {
  return remembered('chaya.timing.every', 'yes') === 'yes';
}

/** The Subtitles tab's Timing, while subtitles are shown. */
function showTiming() {
  document.getElementById('player-timing').hidden = !playing?.shown;
  const every = timingEvery();
  // With one language there is nothing to choose.
  document.getElementById('player-timing-which').hidden = (playing?.tracks.length ?? 0) < 2;
  for (const button of document.querySelectorAll('#player-timing-which button')) {
    button.setAttribute('aria-pressed', String((button.dataset.every === 'yes') === every));
  }
}
for (const button of document.querySelectorAll('#player-timing-which button')) {
  button.addEventListener('click', () => {
    remember('chaya.timing.every', button.dataset.every);
    showTiming();
  });
}
for (const button of document.querySelectorAll('#player-timing-nudge button')) {
  button.addEventListener('click', () => {
    const by = Number(button.dataset.by);
    changeSubtitles('retime_subtitles', { ask: { kind: 'shift', by }, every: timingEvery() }, moved(by));
  });
}

/** Asks the app to change the subtitles shown with `command`; shows them as they are now, and what changed. */
async function changeSubtitles(command, args, said) {
  if (!playing?.shown) return false;
  const id = playing.id;
  const language = playing.shown.language ?? null;
  try {
    const tracks = await tauri.core.invoke(command, { id, language, ...args });
    if (playing?.id !== id) return false;
    showChangedTracks(tracks, language);
    showFixed(said);
    return true;
  } catch (message) {
    if (playing?.id === id) showFixed(String(message), false);
    return false;
  }
}

function showChangedTracks(tracks, language) {
  playing.tracks = tracks;
  playing.line = null;
  showSubtitles(tracks.find((t) => (t.language ?? null) === language) ?? tracks[0] ?? null);
}

function showFixed(text, undo = true) {
  playerFixed.hidden = false;
  document.getElementById('player-fixed-text').textContent = text;
  document.getElementById('player-fixed-undo').hidden = !undo;
}

document.getElementById('player-fixed-undo').addEventListener('click', async () => {
  if (!playing) return;
  const id = playing.id;
  const language = playing.shown?.language ?? null;
  try {
    const tracks = await tauri.core.invoke('undo_subtitles', { id });
    if (playing?.id !== id) return;
    playing.synced = null;
    showChangedTracks(tracks, language);
    const left = await tauri.core.invoke('subtitle_undos', { id }).catch(() => 0);
    showFixed('Put back as it was.', left > 0);
  } catch (message) {
    showFixed(String(message), false);
  }
});
document.getElementById('player-fixed-close').addEventListener('click', () => (playerFixed.hidden = true));
