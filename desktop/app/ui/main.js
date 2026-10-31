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
      return `Saved · ${fileSize(task.total ?? task.done)} · ${task.folder.split(/[\\/]/).pop()}`;
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
const SITES = [['youtube', 'YouTube'], ['instagram', 'Instagram'], ['tiktok', 'TikTok'], ['x', 'X'], ['pinterest', 'Pinterest'], ['other', 'Other']];
const narrowing = { kind: null, site: null, words: '' };
const narrowEl = document.getElementById('downloads-narrow');
const findField = document.getElementById('downloads-find-field');
const findInput = document.getElementById('downloads-find');
const downloadsNone = document.getElementById('downloads-none');

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
  findField.hidden = tasks.size < 2 && !narrowing.words;
  const kept = [...tasks.values()].filter(keeps).length;
  downloadsNone.hidden = tasks.size === 0 || kept > 0;
  // The grid holds what was saved: offered once something has been.
  viewSwitch.hidden = ![...tasks.values()].some((t) => t.state === 'completed');
  showView(viewSwitch.hidden ? 'list' : view, false);
}

function narrow(change) {
  Object.assign(narrowing, change);
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
const TABS = { transcript: ['tab-transcript', 'panel-transcript'], subtitles: ['tab-subtitles', 'panel-subtitles'] };
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

async function openPlayer(id) {
  const task = tasks.get(id);
  if (!task) return;
  if (!viewer.hidden) closeViewer();
  playing = { id, tracks: [], shown: null, line: null, savedAt: 0 };
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
  playerCc.hidden = true;
  player.hidden = false;
  showPageView();
  showTab(remembered('chaya.player.tab', 'transcript'));
  showSpeeds();
  playerVideo.poster = posterOf(id);
  playerVideo.src = tauri.core.convertFileSrc(String(id), 'chaya-file');
  playerVideo.playbackRate = Number(remembered('chaya.player.speed', '1')) || 1;
  playerVideo.muted = remembered('chaya.player.muted', 'no') === 'yes';
  showControls();
  showPosition();
  playerVideo.focus();

  const [tracks, start, made] = await Promise.all([
    tauri.core.invoke('video_subtitles', { id }).catch(() => []),
    tauri.core.invoke('playback_start', { id }).catch(() => 0),
    tauri.core.invoke('made_subtitles', { id }).catch(() => null),
  ]);
  if (playing?.id !== id) return;
  playing.tracks = tracks;
  playing.made = made;
  const wanted = remembered('chaya.player.subtitles', '');
  showSubtitles(tracks.find((t) => t.language === wanted) ?? tracks[0] ?? null);
  whenReady(() => {
    if (start > 0) {
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
  keepPosition(true);
  playing = null;
  player.classList.remove('player--full', 'player--idle');
  openSpeeds(false);
  player.hidden = true;
  playerVideo.pause();
  playerVideo.removeAttribute('src');
  playerVideo.load();
  playerLines.replaceChildren();
  showPageView();
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
  const { buffered } = playerVideo;
  let loaded = 0;
  for (let i = 0; i < buffered.length; i++) if (buffered.start(i) <= at + 0.5) loaded = buffered.end(i);
  playerSeek.style.setProperty('--buffered', String(duration ? Math.min(1, loaded / duration) : 0));
}

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
  playerHover.textContent = clock(fraction * playerVideo.duration);
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
  // Drawn again from nothing, whatever was on show.
  playing.line = null;
  const { tracks } = playing;
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
      button.append(span('num', clock(line.start / 1000)), span('', line.text));
      button.addEventListener('click', () => {
        playerVideo.currentTime = line.start / 1000;
        playerVideo.play().catch(() => {});
      });
      li.append(button);
      return li;
    }),
  );
  showMaking();
  followLine();
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
  const line = track ? lineAt(track.spans, playerVideo.currentTime * 1000) : -1;
  if (line === playing.line) return;
  playing.line = line;
  playerCaption.hidden = line < 0 || !captionsOn;
  playerCaption.textContent = line < 0 ? '' : track.lines[line].text;
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
  if (playerVideo.paused) {
    followLine();
    showPosition();
  }
  keepPosition();
});
playerVideo.addEventListener('pause', () => keepPosition(true));
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
    showControls();
    const onButton = e.target instanceof Element && e.target.closest('button, [role="tab"]');
    const key = e.key.toLowerCase();
    if (e.key === 'Escape') {
      if (!playerSpeedPanel.hidden) openSpeeds(false);
      else if (player.classList.contains('player--full')) fullWindow(false);
      else closePlayer();
    } else if (e.ctrlKey || e.altKey || e.metaKey) {
      // Not the player's.
    } else if (key === 'f') {
      fullWindow();
    } else if (key === 'm') {
      playerMute.click();
    } else if (key === 'c' && playing?.tracks.length) {
      setCaptions(!captionsOn);
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
  const bounds = boundsOf(pageEl);
  const panel = openPanel();
  if (!panel) return bounds;
  const width = Math.min(bounds.width, panel.getBoundingClientRect().left - 8 - bounds.x);
  return width >= NARROWEST_PAGE ? { ...bounds, width } : null;
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

async function loadAi() {
  const status = await tauri.core.invoke('ai_status').catch(() => null);
  if (!status) return;
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
  ai.settings = { ...ai.settings, ...change };
  return tauri.core.invoke('set_ai', { settings: ai.settings }).catch((message) => sayAi(String(message)));
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
