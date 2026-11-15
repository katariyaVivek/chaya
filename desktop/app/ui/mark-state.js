// What Chaya's mark shows, from the downloads (design/chaya-mark.js draws it). One value for the whole list:
// idle, saving with progress p, unknown (nothing in it knows its size yet, as with a stream), paused, or offline.

const ACTIVE = new Set(['queued', 'downloading', 'paused']);

/** How much of a download has arrived, 0..1, or null when its size is not known (a stream, an account still
 * being listed). An account's ZIP counts its files; anything else counts bytes. */
export function shareOf(task) {
  if (task.archive) return task.archive.found ? task.archive.saved / task.archive.found : null;
  return task.total ? task.done / task.total : null;
}

/**
 * The mark's state for these downloads. saved counts the finished ones, so the mark can tell a download that
 * finished (a short settle) from one that was cancelled or failed (a plain return to rest).
 * @param {Iterable<{state: string}>} tasks
 * @param {boolean} online whether Windows says the PC is online
 * @returns {{mode: 'idle'|'saving'|'unknown'|'paused'|'offline', p: number, saved: number}}
 */
export function markState(tasks, online = true) {
  const list = [...tasks];
  const saved = list.filter((t) => t.state === 'completed').length;
  if (!online) return { mode: 'offline', p: 1, saved };
  const active = list.filter((t) => ACTIVE.has(t.state));
  if (!active.length) return { mode: 'idle', p: 1, saved };

  const known = (group) =>
    group
      .map(shareOf)
      .filter((s) => s !== null && Number.isFinite(s))
      .map((s) => Math.min(1, Math.max(0, s)));
  const mean = (shares) => shares.reduce((a, b) => a + b, 0) / shares.length;

  const running = active.filter((t) => t.state !== 'paused');
  if (!running.length) {
    const shares = known(active);
    return { mode: 'paused', p: shares.length ? mean(shares) : 0, saved };
  }
  const shares = known(running);
  if (!shares.length) return { mode: 'unknown', p: 0, saved };
  return { mode: 'saving', p: mean(shares), saved };
}
