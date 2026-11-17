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

### 2. Search every line said ☐

**The person gets:** the Library's search finds words said in a video, not only titles: each line that matches, under
its video, and a tap opens the player there.

- Port `core::search`: every video's lines in memory, folded for case and accents, read again only when a file changed.
- `PlayerScreen` takes a time to start at.

### 3. Your own videos ☐

**The person gets:** *Add a video* in the Library opens the phone's picker; the video is listed where it is (not
copied) and plays, with everything the later steps add.

- A persisted read permission on the picked document; a row in the library marked as the person's own, which Delete
  only takes out of Chaya. A Room migration with its test.

### 4. Subtitles put right ☐

**The person gets:** a long press on a transcript line edits its words; *Timing* moves every line earlier or later, or
stretches them between two lines; Undo for each.

- Port `core::fix`. The SRT beside the video is written again; the first original is kept so *Undo* can go back.

### 5. Your own AI model ☐

**The person gets:** Settings › AI model: a service from a list (OpenAI, Anthropic, Gemini, DeepSeek, OpenRouter,
Mistral, Groq, xAI, any OpenAI-compatible address) or a model on a PC in the home, with its key, and *Test*.

- Port `core::ai`: the two protocols, against MockWebServer. The key through the Android Keystore; the setting left
  out of backups. `PRIVACY_POLICY.md` says what is sent, only when the person asks.

### 6. Translation ☐

**The person gets:** *Translate* in the player: the subtitles in their language under the original, on the picture
and in the transcript, a version kept for each model.

- Port `core::translate`: batches, each with the whole transcript for names and terms; carried on where it stopped.

### 7. Highlights ☐

**The person gets:** *Highlights* in the player: the moments worth watching, listed with why, and marked on the seek
bar; a tap plays from one.

- Port `core::highlights`.

### 8. Clips ☐

**The person gets:** lines chosen in the transcript (a long press, then more taps) or a highlight made into a clip;
clips listed under the player and trimmed on a timeline whose edges snap to lines and chapters.

- Port `core::clips`. The timeline is drawn from the video's frames and its sound's loudness.

### 9. Export ☐

**The person gets:** a clip saved as a video with its subtitles drawn on, or as SRT; a size to fit (10, 25 or 50 MB)
for sending; shared or saved to the gallery.

- Media3 Transformer: the clip's span, the subtitles as a text overlay, a bit rate from the size asked for.
- **Tests:** the bit rate for a size (JVM); an `androidTest` that exports a short clip and reads its length back.

### 10. Subtitles made on the phone ☐

**The person gets:** *Make subtitles* on a saved video with none, listened to on the phone; the model fetched the first
time, with its size said first.

- whisper.cpp through the NDK; Tiny and Base models, pinned by SHA-256. Short lines ported from `core::speech::lines`
  (JVM tests with Windows' recorded output). An `androidTest` with the JFK sample, as on Windows.
