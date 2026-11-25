// The rules the window follows to export clips: what the Export dialog offers first (from what the player shows and
// what was chosen last time), the choices kept consistent as they change, the `options` the app is asked with, and
// the words for how an export went. The app does the work (`export_clips`, and an `export` event for each change).
//
// Clips are saved as videos, GIFs or SRT files, one a clip, or as an editor project ('fcpxml'): one file for Final
// Cut Pro or DaVinci Resolve holding all the clips chosen, cut from the video as it is, with the subtitles chosen as
// titles.
//
// A language is named here by its key: the track's language code, or '' for a track without one, as the app takes
// it. `styleOf(key)` gives that language's player style: { color, size, font }, the font capitalised ('Sans').

export const LOOKS = ['outline', 'boxed', 'large'];
/** The looks that follow each word as it is said, offered for subtitles Chaya made (it has each word's time). */
export const WORD_LOOKS = ['highlight', 'karaoke', 'pop', 'oneAtATime'];
export const QUALITIES = ['p720', 'p1080', 'original'];
export const LOOK_NAMES = {
  outline: 'Outline',
  boxed: 'Box',
  large: 'Large',
  highlight: 'Highlight',
  karaoke: 'Karaoke',
  pop: 'Pop',
  oneAtATime: 'One at a time',
};
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
 * 'second' on top, `captions` whether the line is drawn at all, `words` the key of the subtitles Chaya made (it has
 * their words' times; null when it made none), and `kept` what was chosen last time.
 */
export function firstChoice({ clips = [], cut = null, tracks = [], shown = null, second = null, top = 'second', captions = true, words = null, kept = {} }) {
  const has = (key) => key !== null && key !== undefined && tracks.includes(key);
  const first = has(shown) ? shown : tracks[0] ?? null;
  const other = has(second) && second !== first ? second : null;
  let subtitles = 'none';
  if (tracks.length && captions && has(shown) && kept.subtitles !== 'none') subtitles = other !== null ? 'two' : 'one';
  const srt = [first, other].filter((key) => key !== null);
  return fixed(
    {
      which: cut !== null && clips.some((c) => c.id === cut) ? 'chosen' : 'all',
      saveAs: kept.saveAs === 'srt' && tracks.length ? 'srt' : ['gif', 'fcpxml'].includes(kept.saveAs) ? kept.saveAs : 'video',
      subtitles,
      first,
      second: other,
      secondOnTop: top === 'second',
      look: [...LOOKS, ...WORD_LOOKS].includes(kept.look) ? kept.look : 'outline',
      words,
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
  // A look that follows the words, for subtitles without them, is drawn as Outline.
  if (WORD_LOOKS.includes(next.look) && !hasWords(next)) next.look = 'outline';
  return next;
}

/** Whether the main language's subtitles are the ones Chaya made, whose words it has with their times. */
const hasWords = (choice) => choice.words !== null && choice.words !== undefined && choice.first === choice.words;

/** The looks offered: the word looks too when the main language's words have their times. */
export function looksFor(choice) {
  return hasWords(choice) ? [...LOOKS, ...WORD_LOOKS] : LOOKS;
}

/** Why the word looks aren't offered, for a video with subtitles drawn on; null when they are, or don't apply. */
export function wordLooksNote(choice) {
  if (hasWords(choice) || choice.subtitles === 'none' || choice.first === null) return null;
  return choice.words === null || choice.words === undefined
    ? 'Words light up only for subtitles Chaya made: Make subtitles first.'
    : 'Words light up only for the subtitles Chaya made: choose them as the main language.';
}

/** The colour the word being said takes in the word looks, as the app draws it: white lights up yellow, any other
 *  colour white. */
export function accentOf(color) {
  return color === 'white' || !color ? 'yellow' : 'white';
}

/** A word look's small picture: the sample's words, and which are lit (the word being said; for Karaoke, those
 *  filled so far). One at a time shows only the first two. */
export function lookWords(look, text) {
  const words = String(text ?? '').split(' ').filter(Boolean);
  if (look === 'oneAtATime') return words.slice(0, 2).map((word, i) => ({ word, lit: i === 0 }));
  const lit = { highlight: 1, pop: 1, karaoke: 0 }[look];
  return words.map((word, i) => ({ word, lit: lit !== undefined && i === lit }));
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

/** How many files an export makes: one a clip, or one a clip and language for SRT; one project for them all. */
export function fileCount(choice, clips) {
  if (choice.saveAs === 'fcpxml') return 1;
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
  if (choice.saveAs === 'fcpxml') return clips === 1 ? 'Export project' : `Export project of ${clips} clips`;
  return clips === 1 ? 'Export clip' : `Export ${clips} clips`;
}

/** Where the files go, with the first one's name as an example (`clips` of them chosen). */
export function whereNote(choice, title, clipName, clips = 1) {
  if (choice.saveAs === 'fcpxml') {
    const name = `${title} - ${clips === 1 ? clipName : 'Clips'}.fcpxml`;
    return `Saved next to the video for Final Cut Pro or DaVinci Resolve (not Premiere): “${name}”. It uses the video where it is, so keep the two together.`;
  }
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
    const detail = job.saveAs === 'srt' || job.saveAs === 'fcpxml' ? baseName(job.path) : encoderWords(job.encoder);
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
