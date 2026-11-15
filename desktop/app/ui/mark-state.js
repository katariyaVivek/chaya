// What Chaya's mark shows, from the downloads (design/chaya-mark.js draws it). One value for the whole list:
// idle, saving with progress p, unknown (nothing in it knows its size yet, as with a stream), paused, or offline.
//
// Progress is that of the run: every download under way since the mark was last at rest, the ones already finished
// in it counting as done, each weighted by its size. So a big download moves the line more than a small one, and
// the line does not step back when one of them finishes; it steps back only when a new one joins the run.

const ACTIVE = new Set(['queued', 'downloading', 'paused']);

/** How much of a download has arrived, 0..1, or null when its size is not known (a stream, an account still
 * being listed). An account's ZIP counts its files; anything else counts bytes. */
export function shareOf(task) {
  if (task.archive) return task.archive.found ? task.archive.saved / task.archive.found : null;
  return task.total ? task.done / task.total : null;
}

/**
 * The run after this change: the downloads under way now, added to those already in it; empty once nothing is
 * under way, so the next download starts a new run.
 * @param {Set<number>} run the ids in the run so far
 * @param {Iterable<{id: number, state: string}>} tasks
 * @returns {Set<number>}
 */
export function nextRun(run, tasks) {
  const active = [...tasks].filter((t) => ACTIVE.has(t.state)).map((t) => t.id);
  return active.length ? new Set([...run, ...active]) : new Set();
}

/** The share of a run that has arrived, each download weighted by its size; null when none knows its share. An
 * account's ZIP has no byte size until it is made, so it weighs as much as the run's other downloads on average. */
function runShare(members) {
  const known = members
    .map((t) => ({ t, share: t.state === 'completed' ? 1 : shareOf(t) }))
    .filter(({ share }) => share !== null && Number.isFinite(share));
  if (!known.length) return null;
  const sized = known.filter(({ t }) => !t.archive && t.total > 0).map(({ t }) => t.total);
  const typical = sized.length ? sized.reduce((a, b) => a + b, 0) / sized.length : 1;
  let weight = 0;
  let arrived = 0;
  for (const { t, share } of known) {
    const w = !t.archive && t.total > 0 ? t.total : typical;
    weight += w;
    arrived += w * Math.min(1, Math.max(0, share));
  }
  return arrived / weight;
}

/**
 * The mark's state for these downloads. saved counts the finished ones, so the mark can tell a download that
 * finished (a short settle) from one that was cancelled or failed (a plain return to rest).
 * @param {Iterable<{id: number, state: string}>} tasks
 * @param {boolean} online whether Windows says the PC is online
 * @param {Set<number>} [run] the ids in the current run (nextRun); without it, only the downloads under way count
 * @returns {{mode: 'idle'|'saving'|'unknown'|'paused'|'offline', p: number, saved: number}}
 */
export function markState(tasks, online = true, run = new Set()) {
  const list = [...tasks];
  const saved = list.filter((t) => t.state === 'completed').length;
  if (!online) return { mode: 'offline', p: 1, saved };
  const active = list.filter((t) => ACTIVE.has(t.state));
  if (!active.length) return { mode: 'idle', p: 1, saved };

  const members = list.filter((t) => ACTIVE.has(t.state) || (t.state === 'completed' && run.has(t.id)));
  const share = runShare(members);
  const running = active.filter((t) => t.state !== 'paused');
  if (!running.length) return { mode: 'paused', p: share ?? 0, saved };
  // A stream (or anything without a size) on its own: nothing to draw progress from
  if (runShare(running) === null) return { mode: 'unknown', p: 0, saved };
  return { mode: 'saving', p: share, saved };
}
