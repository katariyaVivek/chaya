// The rules the window follows while the person cuts clips: lines chosen in the transcript as one range, an edge
// dragged on the timeline snapped to the lines, clips that overlap drawn on rows of their own, and the part of the
// video the timeline shows. What is kept is put right again by the app (`chaya-core`'s `clips`). Times are in
// milliseconds; `lines` are a track's, in order, each with `start`, `end` and `text`.

/** An edge this close to a line's edge goes to it, in milliseconds. */
export const SNAP = 1500;
/** The shortest clip, as `chaya-core` keeps it. */
export const SHORTEST = 500;

/** Lines `a` and `b` and those between, either way round: the first and last, how many, and the time from the
 *  first's start to the last's end; null when there are no lines. */
export function selection(lines, a, b) {
  if (!lines.length) return null;
  const first = Math.max(0, Math.min(a, b));
  const last = Math.min(lines.length - 1, Math.max(a, b));
  return { first, last, count: last - first + 1, start: lines[first].start, end: lines[last].end };
}

/** Where an edge (`kind` 'start' or 'end') let go at `ms` goes: to the nearest line's start (or end) within `SNAP`;
 *  anywhere else, and anywhere when `free` (Alt held), to the nearest tenth of a second. */
export function snap(lines, ms, kind, free = false) {
  const rounded = Math.max(0, Math.round(ms / 100) * 100);
  if (free) return rounded;
  let best = null;
  for (const line of lines) {
    const edge = line[kind];
    if (Math.abs(edge - ms) <= SNAP && (best === null || Math.abs(edge - ms) < Math.abs(best - ms))) best = edge;
  }
  return best ?? rounded;
}

/** `clip` with its `kind` edge moved to `ms` (`snap`), inside a video `duration` long, and at least `SHORTEST` from
 *  its other edge. */
export function moveEdge(clip, kind, ms, lines, duration, free = false) {
  const at = snap(lines, ms, kind, free);
  const longest = Number.isFinite(duration) && duration > 0 ? duration : Infinity;
  if (kind === 'start') return { ...clip, start: Math.max(0, Math.min(at, clip.end - SHORTEST)) };
  return { ...clip, end: Math.min(longest, Math.max(at, clip.start + SHORTEST)) };
}

/** `clip` with its `kind` edge moved one step `by` (-1 or 1, the arrow keys): to the line edge of its kind before or
 *  after it, or a tenth of a second when `free` or there is none. */
export function stepEdge(clip, kind, by, lines, duration, free = false) {
  const now = clip[kind];
  let to = null;
  if (!free) {
    const edges = lines.map((l) => l[kind]).filter((e) => (by > 0 ? e > now : e < now));
    if (edges.length) to = by > 0 ? Math.min(...edges) : Math.max(...edges);
  }
  return moveEdge(clip, kind, to ?? now + by * 100, lines, duration, true);
}

/** Each clip's row on the timeline, by id: the first row where it overlaps nothing already there. */
export function rows(clips) {
  const ends = [];
  const placed = new Map();
  for (const clip of [...clips].sort((a, b) => a.start - b.start || a.end - b.end)) {
    let row = ends.findIndex((end) => end <= clip.start);
    if (row < 0) row = ends.push(0) - 1;
    ends[row] = clip.end;
    placed.set(clip.id, row);
  }
  return placed;
}

/** The clip in `clips` from `start` to `end`, if there is one. */
export function clipOf(clips, start, end) {
  return clips.find((c) => c.start === start && c.end === end) ?? null;
}

/** A clip's name from the first line it starts with: its first words, about 40 characters at most, without the
 *  stop at the end. */
export function nameFrom(text) {
  const words = String(text ?? '').replace(/\s+/g, ' ').trim().replace(/[.,;:!?…]+$/u, '').split(' ').filter(Boolean);
  let name = '';
  for (const word of words) {
    const longer = name ? `${name} ${word}` : word;
    if (longer.length > 40) break;
    name = longer;
  }
  return name || (words[0] ?? '').slice(0, 40);
}

/** The part of the video the timeline shows for `clip`: a little before and after it, inside the video. */
export function view(clip, duration) {
  const pad = Math.max(4000, (clip.end - clip.start) * 0.3);
  const longest = Number.isFinite(duration) && duration > 0 ? duration : clip.end + pad;
  return { from: Math.max(0, clip.start - pad), to: Math.min(longest, clip.end + pad) };
}

/** "1:46.4", or "1:02:05.3" past an hour: minutes, seconds and tenths. */
export function exact(ms) {
  const tenths = Math.max(0, Math.round(ms / 100));
  const s = Math.floor(tenths / 10);
  const mm = String(Math.floor(s / 60) % 60);
  const ss = `${String(s % 60).padStart(2, '0')}.${tenths % 10}`;
  return s >= 3600 ? `${Math.floor(s / 3600)}:${mm.padStart(2, '0')}:${ss}` : `${mm}:${ss}`;
}
