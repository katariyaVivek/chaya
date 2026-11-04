// A video's chapters, as its maker named them on the site (`chaya-core`'s `chapters`), in the player: which one is
// playing, where Ctrl+← and Ctrl+→ go, and the edges a clip's handles snap to. Times are in milliseconds; `chapters`
// are in order, each with `start`, `end` and `title`.

/** Ctrl+← this far into a chapter goes back to its start; any sooner, to the one before, as YouTube does. */
export const BACK_TO_START = 3000;

/** The index of the chapter playing at `ms`, or -1 when there is none there. */
export function chapterAt(chapters, ms) {
  return chapters.findIndex((c) => c.start <= ms && ms < c.end);
}

/** Where Ctrl+→ (`by` 1) or Ctrl+← (`by` -1) at `ms` goes: the next chapter's start; or the start of the one playing,
 *  or of the one before when that start was only just passed. `null` when there is nowhere to go. */
export function stepChapter(chapters, ms, by) {
  if (!chapters.length) return null;
  if (by > 0) return chapters.find((c) => c.start > ms)?.start ?? null;
  const before = chapters.filter((c) => c.start <= ms);
  if (!before.length) return null;
  const now = before[before.length - 1];
  if (ms - now.start >= BACK_TO_START || before.length === 1) return now.start;
  return before[before.length - 2].start;
}

/** What a clip's edges snap to: the lines shown, and the chapters' edges. */
export function snapEdges(lines, chapters) {
  return chapters.length ? [...lines, ...chapters] : lines;
}

/** Where each chapter after the first begins on the seek bar, from 0 to 1, in a video `duration` milliseconds long. */
export function dividers(chapters, duration) {
  if (!(duration > 0)) return [];
  return chapters.slice(1).map((c) => c.start / duration).filter((at) => at > 0 && at < 1);
}
