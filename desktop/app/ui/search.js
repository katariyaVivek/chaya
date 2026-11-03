// The search across what is said in every saved video (`search_lines`, `core::search` in the app): typed into the
// Downloads list's find box, which also narrows the list by name. Below the downloads it matches, the lines that hold
// every word, by video, each with its time and the words marked; a click opens the video in the player at that line.
//
// The rules (`pieces`, `groups`, `summary`) are plain functions, tested with Node (`test/search.test.mjs`); `lineSearch`
// puts them in the window.

/** How long typing has to pause before the lines are searched. */
export const PAUSE = 140;

/** Whether `query` is worth searching the lines for: two letters or more, once the spaces are taken off. */
export function worthSearching(query) {
  return [...query.trim()].length >= 2;
}

/** `text` cut into the pieces `marks` mark ([start, end) in UTF-16 units, as JavaScript counts), in order:
 * `{ text, marked }`. Marks out of order, overlapping or past the end are put right. */
export function pieces(text, marks) {
  const out = [];
  let at = 0;
  const sorted = [...marks].sort((a, b) => a[0] - b[0]);
  for (const [start, end] of sorted) {
    const from = Math.max(at, Math.min(start, text.length));
    const to = Math.min(end, text.length);
    if (to <= from) continue;
    if (from > at) out.push({ text: text.slice(at, from), marked: false });
    out.push({ text: text.slice(from, to), marked: true });
    at = to;
  }
  if (at < text.length) out.push({ text: text.slice(at), marked: false });
  return out;
}

/** The lines found, by video in the order they came (`search_lines` gives them a video at a time): `{ id, title,
 * languages, hits }`, `languages` being how many languages its lines are in. */
export function groups(hits) {
  const byVideo = new Map();
  for (const hit of hits) {
    let group = byVideo.get(hit.id);
    if (!group) {
      group = { id: hit.id, title: hit.title, hits: [], languages: 0 };
      byVideo.set(hit.id, group);
    }
    group.hits.push(hit);
  }
  for (const group of byVideo.values()) group.languages = new Set(group.hits.map((h) => h.language ?? '')).size;
  return [...byVideo.values()];
}

/** What the lines' heading says: "3 lines in 2 videos", or, past what came back, "The first 200 of 312 lines". */
export function summary({ hits, total }) {
  const lines = (n) => `${n} ${n === 1 ? 'line' : 'lines'}`;
  if (total > hits.length) return `The first ${hits.length} of ${lines(total)}`;
  const videos = new Set(hits.map((h) => h.id)).size;
  return `${lines(total)} in ${videos} ${videos === 1 ? 'video' : 'videos'}`;
}

/**
 * Searches the lines as the find box is typed into, and shows them in `box` (its `ul` holds the videos, its heading
 * the summary). `invoke` is Tauri's; `among()` the downloads the list's chips keep (null: all); `open(id, from)` plays
 * a video from `{ at, language }`; `clock(seconds)` and `languageName(code)` write times and languages as the player
 * does; `changed()` is called when lines appear or go, so the list can say "Nothing here matches" or not.
 */
export function lineSearch({ invoke, box, among, open, clock, languageName, changed }) {
  const list = box.querySelector('ul');
  const head = box.querySelector('.lines__summary');
  let timer = 0;
  let asked = 0;
  let shown = 0;
  let query = '';

  function show(results) {
    const found = groups(results.hits);
    shown = results.hits.length;
    box.hidden = shown === 0;
    head.textContent = shown ? summary(results) : '';
    list.replaceChildren(...found.map(videoOf));
    changed();
  }

  function videoOf(group) {
    const li = document.createElement('li');
    li.className = 'lines__video';
    li.dataset.id = String(group.id);
    const title = document.createElement('p');
    title.className = 'lines__title';
    title.textContent = group.title;
    const lines = document.createElement('ul');
    lines.className = 'player__lines';
    lines.append(...group.hits.map((hit) => lineOf(hit, group.languages > 1)));
    li.append(title, lines);
    return li;
  }

  function lineOf(hit, sayLanguage) {
    const li = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'player__line lines__line';
    button.dataset.start = String(hit.start);
    const time = document.createElement('span');
    time.className = 'num';
    time.textContent = clock(hit.start / 1000);
    const text = document.createElement('span');
    if (sayLanguage) {
      const language = document.createElement('span');
      language.className = 'lines__language';
      language.textContent = languageName(hit.language);
      text.append(language);
    }
    for (const piece of pieces(hit.text, hit.marks)) {
      if (piece.marked) {
        const mark = document.createElement('mark');
        mark.textContent = piece.text;
        text.append(mark);
      } else {
        text.append(piece.text);
      }
    }
    button.setAttribute('aria-label', `${hit.title}, at ${clock(hit.start / 1000)}: ${hit.text}`);
    button.append(time, text);
    button.addEventListener('click', () => open(hit.id, { at: hit.start / 1000, language: hit.language }));
    li.append(button);
    return li;
  }

  async function now() {
    const mine = ++asked;
    if (!worthSearching(query)) {
      show({ hits: [], total: 0 });
      return;
    }
    const results = await invoke('search_lines', { query, among: among() }).catch(() => null);
    // A newer search was asked for while this one ran: its answer is the one to show.
    if (mine === asked && results) show(results);
  }

  return {
    /** Searches for `text` once typing pauses; straight away when it is cleared. */
    run(text) {
      query = text;
      clearTimeout(timer);
      if (!worthSearching(text)) now();
      else timer = setTimeout(now, PAUSE);
    },
    /** How many lines are on show. */
    get shown() {
      return shown;
    },
  };
}
