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
