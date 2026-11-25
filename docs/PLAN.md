# Working plan: the phone catches up with Windows

Chaya for Windows 0.2.0 learned to watch, understand and clip what it saves (the plan it followed is in
[`history/plan-watch-understand-clip.md`](history/plan-watch-understand-clip.md)). The phone has its first piece: a
saved video plays in Chaya with the site's subtitles over the picture and as a transcript under it, and starts where it
was left (#122 to #127). This plan brings the rest to the phone, so both apps do the same things, each in the way its
screen suits.

Each step is one PR, merged before the next starts and useful on its own, with the `ui-check` label (every step touches
the player). Tick a step off here in the PR that finishes it. The logic is ported from `desktop/core` into plain Kotlin
with no Android imports, tested on the JVM; the screens follow [`DESIGN.md`](../DESIGN.md) and Stage.

## Resuming in a new session

> Read `CLAUDE.md` and `docs/PLAN.md`, then start on the next unticked step. One PR per step, labelled `ui-check`;
> tell me when it is green and wait for me to say merge.

## Decided

- **Same features, the phone's own shape.** What Windows puts in tabs beside the player, the phone puts under the
  picture or in a sheet; what Windows does with a key (Ctrl+B, Ctrl+←), the phone does with a tap.
- **Kept on the phone only**, in the app's own files beside the subtitles, by download, and deleted with the download,
  as subtitles are now (`SubtitleStore`). Nothing is uploaded unless the person sets up a model and asks it something.
- **The person's own AI model** speaks the two protocols Windows does (OpenAI-compatible chat completions and
  Anthropic's Messages API). The key is encrypted with a key in the Android Keystore, never stored in plain text and
  never backed up. A model on a PC in the same home (Ollama, LM Studio) is reached by its local address.
- **Clips are encoded with Media3 Transformer** (already a dependency), on the phone's own hardware encoder, with the
  subtitles drawn on as an overlay. No ffmpeg on the phone.
- **Speech to text comes last**: whisper.cpp built for Android, with the smaller models only (a phone's memory and
  battery), fetched once and checked against pinned SHA-256 sums, as on Windows.
- **Not ported:** Windows' copied-link key (Ctrl+Alt+D). The phone already has its own ways in for a copied link:
  *Share to Chaya*, and Home's paste.
- **Privacy is part of each step.** A step that contacts something new, or keeps something new, updates
  `PRIVACY_POLICY.md` in the same PR.

## Steps

### 1. Chapters ☑

**The person gets:** a video saved with chapters shows them on the seek bar, in pieces, one a chapter; the chapter
playing under the video's name ("3 of 8 · The walk"), which follows the seek bar as it is dragged; and tapping it lists
every chapter, a tap on one playing from there. Full screen, the chapter playing is under the title.

- The engine already listed them (`chaya_engine.extract._chapters`). `Chapters.read` is `core::chapters::read`, ported:
  in order, each ending where the next begins, none past the end, fewer than two are none.
- Kept as `12.chapters` beside `12.en.srt`, for downloads from a link and streams found on a page alike.
- **Tests:** reading and putting right (the desktop's cases), the chapter at a time, kept and read back, kept with a
  download and deleted with it.

### 2. Search every line said ☑

**The person gets:** the Library's search box looks in what is said as well as in titles. Under it, "Said 12 times in
3 videos" when the words are said in any finished video the kind and site chips leave in view; tapping that lists the
lines under their videos' names, the words in bold, and a tap on a line plays the video from there.

- `LineSearch` is `core::search`, ported with its tests: every video's lines in memory, folded for case and accents
  ("ecole" finds "École", "strasse" finds "Straße"), every word anywhere in the line, a file read again only when its
  size or time changed. The index lives in memory only; nothing new is kept.
- The player's address takes a time to start at (`player/12?at=5000`).

### 3. Your own videos ☑

**The person gets:** The Library header's *Add a video* opens the phone's picker for a video or sound; it is listed where it
is (not copied), with a frame of it as its tile, and plays in Chaya ("On this phone" under its name), with everything
the later steps add. Deleting it only takes it out of Chaya.

- An own file is a finished download whose address is `chaya-own:` and the document's (`OwnVideos`, as
  `chaya-archive:` marks an account's ZIP), so no Room migration was needed. Chaya keeps the picker's read permission
  and gives it back on Delete, which never asks the file's provider to delete anything.

### 4. Subtitles put right ☑

**The person gets:** a long press on a transcript line, or the pencil by the transcript's heading (for the line being
said), opens a sheet: the line's words to correct (empty takes it out); *This line is said at 0:15*, which moves every
line so it starts where the video is, or, after another line was put right far enough before, stretches the lines
between the two (subtitles that drift); and every line 0.5 or 0.1 s earlier or later. After each change a bar says what
changed, with *Undo*.

- `SubtitleFixes` and `Retime` are `core::fix`, ported with its tests. `SubtitleEdits` writes the change to the SRT Chaya
  keeps (so the transcript, the picture and the Library's search all follow) and keeps the file as it was, byte for
  byte, for *Undo*: the last 20 changes of each video, in memory while the app runs, as on Windows. The phone keeps one
  language per video, so Windows' "every language" choice is not needed yet.

### 5. Your own AI model ☑

**The person gets:** Home › Settings › *AI model*: a service from a list (OpenAI, Anthropic, Gemini, DeepSeek,
OpenRouter, Mistral, Groq, xAI, any OpenAI-compatible address) or Ollama or LM Studio on a PC in the home (at the PC's
address), with its key, *List models* and *Test* ("llama-3.3-70b answered in 0.8 s").

- `ai/AiModel.kt` and `AiClient` are `core::ai`, ported with its tests against MockWebServer. `AiKeys` seals each
  service's key with AES-GCM under a key in the Android Keystore (`KeystoreLockTest` on a device); the `chaya_ai`
  preferences are left out of backups and device transfers. `PRIVACY_POLICY.md` says what is sent, only when asked.

### 6. Translation ☑

**The person gets:** *Translate* (the translate button by the subtitles button) in the player: the phone's language
first among the languages offered; the subtitles in it under the original, on the picture and in the transcript; how
far it has got, with *Stop*; and a version kept for each language and model, one shown under the original or none.

- `Translator` is `core::translate`, ported with its tests: batches, each with the title and the whole transcript for
  names and terms, a line left out asked for alone, a rate limit waited out. `TranslationJobs` runs one per video in
  the app's scope (leaving the player does not stop it) and keeps every batch in `12.translations.json` beside the
  subtitles, so a stopped or failed translation carries on where it stopped; it goes with the download.

### 7. Highlights ☑

**The person gets:** *Highlights* (the sparkle button) in the player: 5, 10 or 20 moments worth watching, found by
their own AI model, listed with their times, titles and why, and marked in amber under the seek bar; a tap plays from
one. *Find again* replaces them.

- `HighlightFinder` is `core::highlights`, ported with its tests: the title and the lines numbered with their times,
  ranges checked and timed by their lines, a long video in parts with the best kept. `HighlightJobs` runs it in the
  app's scope (`Finding`: `Idle`, `Running`, `Failed`); `12.highlights.json` beside the subtitles keeps them.

### 8. Clips ☑

**The person gets:** a long press on a transcript line starts choosing lines (a tap on another takes the choice to
it); a bar says how many and their span, with *Clip* (and *Put right* for one line, as the long press did before). A
highlight's *Make a clip* does the same. *3 clips* beside the chapters lists them; one opens on a timeline of the part
of the video around it, its frames along the top and its sound's loudness under them, whose two edges are dragged to
trim it and settle on the nearest line or chapter; with its name, *Play* (which stops at its end), *Save* and *Delete*.

- `Clips` is `core::clips` with the snapping of Windows' `clips.js`, ported with their tests; `12.clips.json` keeps
  them. `PeakMeter` is `sound::peaks`' measure; on the phone `ClipMedia` decodes the sound with `MediaCodec` (kept as
  `12.peaks` in the cache) and reads frames with `MediaMetadataRetriever`; `ClipMediaTest` checks both on a device.

### 9. Export ☑

**The person gets:** under a clip's timeline, *SAVE AS* a video with its subtitles drawn on (with the translation
under them when one is shown), a clean video, or its subtitles as SRT; a size to fit (as it is, 10, 25 or 50 MB) for
sending; *Save clip* with how far and *Stop*; then *Share* or *Save to phone* (Movies). A stream kept only in the
cache is saved as an MP4 from the Library first.

- `ClipExport` is `core::export`'s size to fit, bit rate, SRT and naming, ported with its tests. `ClipExporter` cuts
  with Media3 Transformer on the phone's encoder: the subtitles as an `OverlayEffect` drawn as the player draws them,
  the picture made smaller by `Presentation` and the bit rates set when a size is asked for. `ClipExporterTest`
  saves a short clip on a device and reads its length back, and checks a size to fit.

### 10. Subtitles made on the phone ☑

**The person gets:** where the transcript would be, a video saved without subtitles offers *Make subtitles*: Tiny
(fastest) or Base (more accurate), each with its size said until it is on the phone ("Fetch 148 MB and make
subtitles"); then how far it has listened, with *Stop*. The lines become the video's subtitles, so the transcript,
search, translation, highlights and clips all have them.

- whisper.cpp v1.9.5 (Windows' commit) through the NDK (`src/main/cpp`, fetched by CMake at that commit), with DTW
  word times as on Windows; a small bridge writes whisper-cli's `-ojf` JSON, so `Transcript.parse` and `SpeechLines`
  (Windows' `speech::whisper::parse` and `speech::lines`) are ported with Windows' tests and its recorded output. The
  models are pinned by SHA-256 (Base's from Hugging Face's LFS pointer) and fetched resumably. The sound is decoded
  by the phone to 16 kHz (`Sound16k`), five minutes at a time. `WhisperOnDeviceTest` hears the JFK sample on a device.

## Then, on Windows: ideas from OpenFilm

Two steps for Chaya for Windows, from [OpenFilm](https://github.com/openfilm/openfilm), an open-source video editor
(MIT): its export of a timeline to an editor, and its word-by-word captions. They follow the Windows plan's own rules
([`history/plan-watch-understand-clip.md`](history/plan-watch-understand-clip.md)): nothing uploaded, ffmpeg with
libass, one PR each, labelled `ui-check`.

### 11. Clips for an editor (Windows) ☑

**The person gets:** *Editor project* in the Export dialog: the clips they chose, cut and in order, as one FCPXML
file for Final Cut Pro and DaVinci Resolve, to finish there (music, colour, titles) without Chaya re-encoding
anything. The subtitles chosen come as titles over each clip, one block for two languages.

Idea from [OpenFilm](https://github.com/openfilm/openfilm), which exports its timeline the same way (MIT; only the
idea and the shape of the file are taken, Chaya's writer is its own, since OpenFilm's is tied to its own timeline).

- **One file for all the clips**, next to the video as "Video title - Clips.fcpxml" (the clip's name when there is
  one). The video itself is not copied: each clip is a cut of the saved file, named by its `file:///` address, so the
  editor reads the original picture and sound at full quality. Moving the video breaks the link, as it does for any
  editor project; the dialog says so.
- **Frames, not milliseconds.** FCPXML times are fractions of a second on the video's own frame grid, so the
  picture's frame rate is read (`core::export::Probe` gains it, and the sound's rate and channels) and each edge is
  put on the nearest frame (29.97 is 30000/1001, never 29.97 written as a decimal). A clip is at least one frame.
- **Subtitles as titles** (Basic Title, which every Final Cut has), each timed inside its clip. Resolve reads
  titles too. Native captions are left out: Resolve reads them badly. SRT export stays for those who want that.
- **Premiere Pro does not read FCPXML**; it reads Final Cut 7 XML (xmeml). That is a second writer, left for after
  this step if it is asked for. The dialog says which programs the file is for.
- **Privacy:** nothing new is contacted or kept; the file holds the video's path and its clips.
- **Tests:** the file for one clip, several clips and two languages (read back with an XML parser, every `ref`
  resolving, every time on the frame grid, the spine's length the clips' sum); 23.976, 25, 29.97, 30 and 59.94;
  paths with spaces, accents and a drive letter or a network share becoming `file:///` addresses; names and
  subtitles with `&`, `<` and quotes; the app takes `fcpxml` as a way to save and makes one job for all the clips.

**Done.** `core::fcpxml` writes the file: one `format` and one `asset` (the video by its `file:///` address, its sound's
channels and a rate FCPXML names), and a project in an event named for the video whose spine holds an `asset-clip`
for each clip, back to back. `fcpxml::Rate` puts every time on the picture's frames, in lowest terms
(`1001/100s`), from the rate `core::export::Probe` now reads (with the sound's rate and channels). Titles are
connected to their clip on lane 1, offset in the clip's own time, made from the same stretches the burned-in
subtitles use (`export::slices`), so two languages are one title; a stretch shorter than a frame is left out. The app
makes one job for all the clips chosen (`SaveAs::Fcpxml`, the job keeping its clips for Retry), reads the frame rate
with ffmpeg, and saves next to the video. The dialog offers *Editor project*, with *Subtitles as titles* and no look,
quality, size or sound; its note names the file, the programs it is for, not Premiere, and that the video must stay
where it is. Tests: the file read back with an XML parser (every reference defined, every time on the grid, the
spine as long as its clips) at nine rates, clips past the end or shorter than a frame, no sound or no length, paths
of each kind, markup in names and lines, titles inside their clip in two languages, and the tour's own video read by
ffmpeg (`CHAYA_FFMPEG`); the dialog's rules with Node; the Windows tour exports all its clips as a project. Not yet
opened in Final Cut Pro or Resolve themselves: neither runs where this was built.

### 12. Caption styles (Windows) ☐

**The person gets:** more ways for burned-in subtitles to look than Outline, Box and Large, for the short clips
people make from a talk: words that light up as they are said, a few words at a time in big letters, a pop on the
word being said. Each is drawn in the language's own colour, font and size, as the three looks are.

Idea from OpenFilm's caption presets and word-by-word captions (MIT; its library is CC0, but it draws its captions
with a browser, and Chaya draws them with libass, so nothing is copied, only what a good short-form caption does).

- **New looks** (each a small picture in the dialog, as the three are): *Highlight* (the line stays up, the word
  being said is in an accent colour), *Karaoke* (words fill left to right as they are said, libass's `\kf`), *Pop*
  (the word being said grows a little and settles), *One at a time* (a word or two, large, in the middle lower
  third). All are plain ASS, so ffmpeg's libass draws them and `core::export::ass` stays the one place.
- **They need each word's time.** Chaya has them when it made the subtitles (kept in the video's file); a subtitle
  file downloaded with the video has only lines. For those, the word looks are not offered, and the dialog says why
  ("Words light up only for subtitles Chaya made"), rather than guessing a word's time from its length. Two
  languages: the word looks apply to the main language; the second stays a plain line below it.
- **The accent colour** is the language's colour's brighter pair (white lights up yellow, yellow white...), fixed per
  colour so the picture is the same on every PC.
- **Looks are data**, not branches: a look is a small value (how a line is cut, what the current word does) in
  `core::export`, so a new one is a table row and a test.
- **Tests:** the ASS Chaya writes for each look (the words' times, the `\kf` lengths adding up to the line's, lines
  cut at the clip's edges with a word half inside), a look refused for lines without word times, and the dialog's
  rules in `ui/export.js`.
