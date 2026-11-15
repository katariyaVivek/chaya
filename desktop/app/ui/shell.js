// The window's sidebar (Stage): which of its places is the one shown, and what its foot says about the library.
// Pure functions of what main.js already knows, so they can be tested without the window.

/**
 * The sidebar's place for the window's views. A page open is Browse, with the Downloads list, the player or the
 * viewer laid over it (they are layers over a place, not places of their own); otherwise Home.
 * @param {{ browsing: boolean }} views
 * @returns {'home' | 'browse'}
 */
export function placeOf({ browsing }) {
  return browsing ? 'browse' : 'home';
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
