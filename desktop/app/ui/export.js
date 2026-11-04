// The rules the window follows to export clips: what the Export dialog offers first (from what the player shows and
// what was chosen last time), the choices kept consistent as they change, the `options` the app is asked with, and
// the words for how an export went. The app does the work (`export_clips`, and an `export` event for each change).
//
// A language is named here by its key: the track's language code, or '' for a track without one, as the app takes
// it. `styleOf(key)` gives that language's player style: { color, size, font }, the font capitalised ('Sans').

export const LOOKS = ['outline', 'boxed', 'large'];
export const QUALITIES = ['p720', 'p1080', 'original'];
export const LOOK_NAMES = { outline: 'Outline', boxed: 'Box', large: 'Large' };
export const QUALITY_NAMES = { p720: '720p', p1080: '1080p', original: 'Original' };
/** Sizes a video may be made to fit, in megabytes: Discord's 10, e-mail's 25, and 50. */
export const FITS = [10, 25, 50];
/** The longest clip made into a GIF, in milliseconds, as the app allows. */
export const LONGEST_GIF = 60_000;

const COLORS = ['white', 'yellow', 'cyan', 'green', 'pink'];
const SIZES = ['S', 'M', 'L', 'XL'];
const FONTS = ['sans', 'serif', 'display', 'mono'];

/** A track's key: its language code, or '' without one. */
export const keyOf = (track) => track?.language ?? '';

/** The player's style for a language, as the app takes it: the font in lower case, anything unknown the default. */
export function contractStyle(style = {}) {
  const font = String(style.font ?? '').toLowerCase();
  return {
    color: COLORS.includes(style.color) ? style.color : 'white',
    font: FONTS.includes(font) ? font : 'sans',
    size: SIZES.includes(style.size) ? style.size : 'M',
  };
}

/**
 * What the dialog offers first. `clips` are the video's, `cut` the clip chosen on the timeline (or null), `tracks`
 * the keys of its subtitles, `shown` and `second` the keys the player shows (or null), `top` the player's 'first' or
 * 'second' on top, `captions` whether the line is drawn at all, and `kept` what was chosen last time.
 */
export function firstChoice({ clips = [], cut = null, tracks = [], shown = null, second = null, top = 'second', captions = true, kept = {} }) {
  const has = (key) => key !== null && key !== undefined && tracks.includes(key);
  const first = has(shown) ? shown : tracks[0] ?? null;
  const other = has(second) && second !== first ? second : null;
  let subtitles = 'none';
  if (tracks.length && captions && has(shown) && kept.subtitles !== 'none') subtitles = other !== null ? 'two' : 'one';
  const srt = [first, other].filter((key) => key !== null);
  return fixed(
    {
      which: cut !== null && clips.some((c) => c.id === cut) ? 'chosen' : 'all',
      saveAs: kept.saveAs === 'srt' && tracks.length ? 'srt' : kept.saveAs === 'gif' ? 'gif' : 'video',
      subtitles,
      first,
      second: other,
      secondOnTop: top === 'second',
      look: LOOKS.includes(kept.look) ? kept.look : 'outline',
      quality: QUALITIES.includes(kept.quality) ? kept.quality : 'p1080',
      srt,
      fit: FITS.includes(kept.fit) ? kept.fit : null,
      even: kept.even === true,
      together: kept.together === true,
    },
    tracks,
  );
}

/** `choice` with `change` made, kept consistent with the video's `tracks`. */
export function changed(choice, change, tracks) {
  const next = { ...choice, ...change };
  // A main language chosen that was the second one: they change places.
  if ('first' in change && change.first === choice.second) next.second = choice.first;
  return fixed(next, tracks);
}

/** Puts a choice right: languages the video has, two different ones for two, one SRT language at least. */
function fixed(choice, tracks) {
  const next = { ...choice, srt: (choice.srt ?? []).filter((key) => tracks.includes(key)) };
  if (!tracks.includes(next.first)) next.first = tracks[0] ?? null;
  if (!tracks.includes(next.second) || next.second === next.first) next.second = tracks.find((key) => key !== next.first) ?? null;
  if (tracks.length === 0) {
    next.subtitles = 'none';
    if (next.saveAs === 'srt') next.saveAs = 'video';
  } else if (next.subtitles === 'two' && next.second === null) {
    next.subtitles = 'one';
  }
  if (next.srt.length === 0 && next.first !== null) next.srt = [next.first];
  return next;
}

/** An SRT language turned on or off; the last one stays on. */
export function toggledSrt(choice, key, tracks) {
  const on = choice.srt.includes(key);
  if (on && choice.srt.length === 1) return choice;
  const srt = on ? choice.srt.filter((k) => k !== key) : tracks.filter((k) => k === key || choice.srt.includes(k));
  return { ...choice, srt };
}

/** The clips to export: the one chosen, or all of them. */
export function clipIds(choice, clips, cut) {
  if (choice.which === 'chosen' && clips.some((c) => c.id === cut)) return [cut];
  return clips.map((c) => c.id);
}

/** Whether subtitle files are one file holding both languages (two chosen, and "one file" asked). */
const together = (choice) => choice.saveAs === 'srt' && choice.together && choice.srt.length === 2;

/** The `options` the app's `export_clips` takes. */
export function optionsOf(choice, styleOf) {
  const languages =
    choice.saveAs === 'srt'
      ? choice.srt
      : { none: [], one: [choice.first], two: [choice.first, choice.second] }[choice.subtitles] ?? [];
  const video = choice.saveAs === 'video';
  return {
    saveAs: choice.saveAs,
    quality: choice.quality,
    look: choice.look,
    subtitles: languages.filter((key) => key !== null).map((language) => ({ language, style: contractStyle(styleOf(language)) })),
    secondOnTop: choice.saveAs !== 'srt' && choice.subtitles === 'two' && choice.secondOnTop,
    fit: video ? choice.fit : null,
    evenLoudness: video && choice.even,
    together: together(choice),
  };
}

/** What is remembered for next time. Languages are not: they follow what the player shows. */
export function keptOf(choice) {
  return {
    saveAs: choice.saveAs,
    subtitles: choice.subtitles === 'none' ? 'none' : 'some',
    look: choice.look,
    quality: choice.quality,
    fit: choice.fit,
    even: choice.even,
    together: choice.together,
  };
}

/** How many files an export makes: one a clip, or one a clip and language for SRT. */
export function fileCount(choice, clips) {
  if (together(choice)) return clips;
  return choice.saveAs === 'srt' ? clips * choice.srt.length : clips;
}

/** The Export button's words. */
export function exportLabel(choice, clips) {
  if (choice.saveAs === 'srt') {
    const files = fileCount(choice, clips);
    return `Export ${files} subtitle file${files === 1 ? '' : 's'}`;
  }
  if (choice.saveAs === 'gif') return clips === 1 ? 'Export GIF' : `Export ${clips} GIFs`;
  return clips === 1 ? 'Export clip' : `Export ${clips} clips`;
}

/** Where the files go, with the first one's name as an example. */
export function whereNote(choice, title, clipName) {
  const base = `${title} - ${clipName}`;
  if (together(choice)) {
    const [a, b] = choice.srt.map((key) => key || 'subtitles');
    return `Saved next to the video as one file with both languages: “${base} (${a} + ${b}).srt”.`;
  }
  if (choice.saveAs === 'gif') return `Saved next to the video, without sound: “${base}.gif”.`;
  if (choice.saveAs === 'srt') {
    const language = choice.srt[0] ? `.${choice.srt[0]}` : '';
    return `Saved next to the video, named for the video, the clip and the language: “${base}${language}.srt”.`;
  }
  return `Saved next to the video, named for the video and the clip: “${base}.mp4”.`;
}

/** The first few words of a line, for a look's small picture. */
export function sampleWords(text, fallback = 'Subtitles here') {
  const words = String(text ?? '').replace(/\s+/g, ' ').trim().split(' ').filter(Boolean);
  let out = '';
  for (const word of words.slice(0, 3)) {
    const next = out ? `${out} ${word}` : word;
    if (next.length > 18) break;
    out = next;
  }
  return out.replace(/[,;:]$/, '') || fallback;
}

/** How a video was encoded, in words; null for subtitle files. */
export function encoderWords(encoder) {
  return (
    {
      graphicsCard: 'H.264 on the graphics card',
      windows: 'H.264 (Windows)',
      mpeg4: 'MPEG-4, as Windows has no H.264 encoder',
      gif: 'GIF',
    }[encoder] ?? null
  );
}

/** Whether a job still has work to do. */
export const isRunning = (job) => job.state === 'waiting' || job.state === 'exporting';

/** A running job's progress, in words: Waiting, or how far. */
export function progressWords(job) {
  return job.state === 'waiting' ? 'Waiting' : `${Math.max(0, Math.min(100, Math.floor(job.percent ?? 0)))}%`;
}

/** The last part of a path, with either slash. */
export function baseName(path) {
  return String(path ?? '').split(/[\\/]/).pop();
}

/** The notice an ended job gives: its words, the small words under them, and its one action; null for a job that
 *  has not ended, or was stopped by the person. */
export function noticeOf(job) {
  if (job.state === 'done') {
    const detail = job.saveAs === 'srt' ? baseName(job.path) : encoderWords(job.encoder);
    return { text: `Saved “${job.name}”`, detail: detail ?? '', action: 'Show in folder', failed: false };
  }
  if (job.state === 'failed') {
    const why = String(job.message ?? '').trim() || 'something went wrong.';
    return { text: `Couldn't export “${job.name}”: ${why}`, detail: '', action: 'Retry', failed: true };
  }
  return null;
}

/** Why a GIF can't be made of these clips (one is longer than a minute), or null. */
export function gifTooLong(clips) {
  const long = clips.find((c) => c.end - c.start > LONGEST_GIF);
  return long ? `“${long.name}” is longer than a minute: a GIF is for a short clip.` : null;
}
