// The window's sidebar (Stage): which of its places is the one shown, and what its foot says about the library.
// Pure functions of what main.js already knows, so they can be tested without the window.

/**
 * The sidebar's place for the window's views. The Library is shown over whatever else is (`showing-downloads`), so
 * it comes first; then a page open is Browse; otherwise Home. The player and the viewer are layers over a place, not
 * places of their own.
 * @param {{ browsing: boolean, library: boolean }} views
 * @returns {'home' | 'browse' | 'library'}
 */
export function placeOf({ browsing, library = false }) {
  if (library) return 'library';
  return browsing ? 'browse' : 'home';
}

/** Whether the sidebar is a rail: only over a page, where the page needs the room. */
export function railed({ browsing, library = false }) {
  return placeOf({ browsing, library }) === 'browse';
}

/**
 * The sources the Library offers, in `order`, each with how many downloads it holds; only those it has, and the one
 * chosen even when it holds none (so it can be let go of).
 * @param {Iterable<{site: string}>} tasks
 * @param {[string, string][]} order [site, name] pairs
 * @param {string | null} chosen
 */
export function sourcesOf(tasks, order, chosen) {
  const counts = new Map();
  for (const t of tasks) counts.set(t.site, (counts.get(t.site) ?? 0) + 1);
  return order
    .filter(([site]) => counts.has(site) || site === chosen)
    .map(([site, name]) => ({ site, name, count: counts.get(site) ?? 0, chosen: site === chosen }));
}

/** Whether a download belongs on Home while it is only a list of what is under way: running, paused or failed. */
export function underWay(task) {
  return ['queued', 'downloading', 'paused', 'failed'].includes(task.state);
}

/**
 * The tab Browse goes back to: the one last shown if it is still open, else the first. null with no tabs, when
 * Browse is not offered at all.
 * @param {{ tabs: {id: number}[] }} view the tabs as last heard
 * @param {number | null} lastShown
 */
export function tabToBrowse(view, lastShown) {
  if (!view.tabs.length) return null;
  return view.tabs.some((t) => t.id === lastShown) ? lastShown : view.tabs[0].id;
}

/**
 * What the library holds: how many downloads are saved and how many bytes they take.
 * @param {Iterable<{state: string, total?: number | null, done?: number}>} tasks
 */
export function libraryTotals(tasks) {
  let saved = 0;
  let bytes = 0;
  for (const t of tasks) {
    if (t.state !== 'completed') continue;
    saved++;
    const size = Number(t.total ?? t.done);
    if (Number.isFinite(size) && size > 0) bytes += size;
  }
  return { saved, bytes };
}

/**
 * The video Home's hero shows: the one to carry on with (`continue_watching`), while it is still a saved video in
 * the list; else the newest saved video; else none.
 * @param {Iterable<{id: number, state: string, kind: string, ended?: number | null, added?: number}>} tasks
 * @param {{id: number, at: number, duration?: number | null} | null} watching
 * @returns {{ id: number, watching: object | null } | null}
 */
export function heroOf(tasks, watching) {
  const videos = [...tasks].filter((t) => t.state === 'completed' && t.kind === 'video');
  if (watching && videos.some((t) => t.id === watching.id)) return { id: watching.id, watching };
  const when = (t) => t.ended ?? t.added ?? 0;
  const newest = videos.reduce((a, b) => (a === null || when(b) > when(a) || (when(b) === when(a) && b.id > a.id) ? b : a), null);
  return newest ? { id: newest.id, watching: null } : null;
}

/** "4:12", or "1:02:05" past an hour. */
export function clockOf(seconds) {
  const s = Number.isFinite(seconds) ? Math.max(0, Math.floor(seconds)) : 0;
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}

/**
 * How many cards Home's shelf has room for in one row, as `repeat(auto-fill, minmax(card, 1fr))` lays them out:
 * at least one, and none past `most`. With no width yet (hidden, not laid out), `most`.
 */
export function shelfColumns(width, card, gap, most) {
  if (!(width > 0) || !(card > 0)) return most;
  return Math.max(1, Math.min(most, Math.floor((width + Math.max(0, gap)) / (card + Math.max(0, gap)))));
}

/** What the player's back button names: the place it closes back to (the player is a layer over a place). */
export function backLabel(place) {
  return { home: 'Home', library: 'Library', browse: 'Page' }[place] ?? 'Home';
}

/**
 * The line under the player's title (Stage): where the video came from, the quality it was saved in, the subtitles
 * shown, and the chapter playing, as `YouTube · 1080p · English subtitles · Chapter 2 of 3, “Second song”`.
 * @param {{ site?: string | null, choice?: string | null, subtitles?: string | null, chapter?: number, chapters?: {title: string}[] }} now
 */
export function playerFacts({ site, choice, subtitles, chapter = -1, chapters = [] }) {
  const parts = [site, choice, subtitles ? `${subtitles} subtitles` : null];
  if (chapter >= 0 && chapter < chapters.length) {
    const title = chapters[chapter].title?.trim();
    parts.push(`Chapter ${chapter + 1} of ${chapters.length}${title ? `, “${title}”` : ''}`);
  }
  return parts.filter(Boolean).join(' · ');
}

/**
 * The lines said in a clip (Stage's clip editor): every line that overlaps it, in order. Times in milliseconds.
 * @param {{start: number, end: number, text: string}[]} lines
 * @param {{start: number, end: number}} clip
 */
export function linesIn(lines, clip) {
  return (lines ?? []).filter((l) => l.end > clip.start && l.start < clip.end);
}

/** "0:38 clip · 4:02 to 4:40": a clip's length, then where it is in the video. Times in milliseconds. */
export function clipSpan(clip) {
  const s = (ms) => clockOf(Math.round(ms / 1000));
  return `${s(clip.end - clip.start)} clip · ${s(clip.start)} to ${s(clip.end)}`;
}

/**
 * Where a page gliding from `from` to `to` stands `k` of the way there (0 to 1, already eased), in whole pixels. The
 * page is drawn over the window, so it can't be animated as the window's own content is: it is placed again at each
 * step, and steps aside for a panel or follows the sidebar's edge as they move, instead of jumping to its new place.
 */
export function glideAt(from, to, k) {
  const at = Math.min(1, Math.max(0, k));
  const step = (a, b) => Math.round(a + (b - a) * at);
  return { x: step(from.x, to.x), y: step(from.y, to.y), width: step(from.width, to.width), height: step(from.height, to.height) };
}

/** Whether two places for the page are the same, to the pixel. */
export function samePlace(a, b) {
  return !!a && !!b && ['x', 'y', 'width', 'height'].every((k) => Math.round(a[k]) === Math.round(b[k]));
}

/**
 * The Library's tab in the title bar, as a browser's downloads page is a tab of its own: `closed`; `open`, in the
 * strip while a page or Home is shown; or `shown`. `show` opens it or comes back to it; `leave` goes to another place
 * and leaves it in the strip; `close` closes it.
 * @param {'closed' | 'open' | 'shown'} now
 * @param {'show' | 'leave' | 'close'} event
 */
export function libraryTabAfter(now, event) {
  if (event === 'show') return 'shown';
  if (event === 'close') return 'closed';
  if (event === 'leave') return now === 'shown' ? 'open' : now;
  return now;
}
