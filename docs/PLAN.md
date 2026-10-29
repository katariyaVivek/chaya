# Working plan: watch, understand and clip (Chaya for Windows)

Chaya already gets the video. This plan lets the person work with it inside Chaya for Windows, in the
way [Vimpo](https://vimpo.app/) does on a Mac, still with nothing uploaded unless they choose it:
watch it with subtitles, have subtitles made on the PC when the site has none, read them in their own
language beside the original, see the moments worth watching, and cut those into short clips with the
subtitles burned in. What Vimpo does, piece by piece, is in [`vimpo-reference.md`](vimpo-reference.md).

Each step is one PR (a big one may take two), merged before the next starts, and each is useful on its
own. Steps 1 to 3 need no account, no key and no network beyond fetching the speech model once. Tick a
step off here in the PR that finishes it.

## Resuming in a new session

> Read `CLAUDE.md` and `docs/PLAN.md`, then start on the next unticked step. One PR per step, labelled
> for the Windows tour; tell me when it is green and wait for me to say merge.

## Decided

- **Windows first.** The phone may follow once the Windows version has been used for a while; nothing here
  touches the phone app.
- **Files stay where Chaya saves them**, in the person's Downloads folder. Vimpo keeps a library folder of
  its own; Chaya does not move anything. Subtitles are written next to the video as `name.en.srt`,
  `name.es.srt` and so on, so VLC and other players find them too. What only Chaya needs (the words with
  their times, translations kept as versions, highlights, clips, where playback stopped) goes in Chaya's
  own data folder, one JSON file per video, keyed by the download's number like `downloads.json`.
- **Speech to text is whisper.cpp**, run as its own program the installer carries, as ffmpeg is: built in
  CI from a pinned release, by a script beside `build_ffmpeg.sh`, for Linux (the core's tests) and
  Windows. It is MIT, as are Whisper's weights.
- **Speech models are fetched once, when first needed**, from Hugging Face (`ggerganov/whisper.cpp`),
  checked against SHA-256 sums Chaya pins, and kept in Chaya's data folder. The person picks one in the
  settings; Large v3 Turbo (quantized, about 550 MB) is the default, as on Vimpo:

  | Model | Size | |
  |---|---|---|
  | Tiny | 75 MB | fastest, least accurate |
  | Small | 466 MB | |
  | Large v3 Turbo, quantized | 547 MB | the default: close to the largest, much faster |
  | Medium | 1.5 GB | |
  | Large v3 | 3.1 GB | most accurate, slowest |

- **The graphics card does the work when there is one** (whisper.cpp's Vulkan build, which runs on NVIDIA,
  AMD and Intel), and the processor when there is not.
- **Translation and highlights use the person's own model**: their key for OpenAI, Anthropic, Gemini,
  DeepSeek, OpenRouter, Mistral, Groq, xAI or any service that speaks OpenAI's API, or a model on the PC
  through Ollama or LM Studio. Two protocols cover them all: OpenAI-compatible chat completions and
  Anthropic's Messages API. Keys are kept in Windows Credential Manager, never in a file. Nothing is sent
  until the person sets a model up, and then only the subtitle text and the video's title, only to the
  service they picked.
- **Clips are encoded with Windows' own H.264 encoder** (ffmpeg's `h264_mf`, which uses the graphics card
  where it can) and subtitles drawn with libass. That keeps the ffmpeg Chaya carries LGPL, with no x264.
- **Privacy is part of each step.** Each step that contacts something new, or keeps something new, updates
  `PRIVACY_POLICY.md` and `desktop/README.md` in the same PR.

## Steps

### 1. Watch it in Chaya ☑

**The person gets:** a finished video opens in Chaya instead of another program, and plays with its
subtitles when the site had any. A list of the lines beside it follows the video; clicking a line jumps
there. Playback speed, full window (F or a double-click), and it starts again where they stopped.

- The video comes to the window through the `chaya-file` scheme the picture viewer uses, with HTTP range
  requests so seeking works. WebView2 plays H.264, VP9, AV1, AAC and Opus, which covers what Chaya saves.
- At download, yt-dlp also fetches the site's subtitles (`--write-subs`, the video's own language, and
  machine captions only when there are no others) and Chaya saves them as SRT next to the video.
- `chaya-core` reads SRT and WebVTT into lines (`core::subtitles`), and finds the line at a time. Chaya draws
  the subtitles itself rather than with `<track>`, so step 5 can stack two languages and style each.
- **Tests:** SRT and VTT parsing (times, styling tags, overlaps), range requests, the line at a time; the
  Windows tour opens a saved video, sees it play, and clicks a line to seek.

**Done.** The site's subtitles are not fetched by yt-dlp itself (Chaya for Windows downloads the files, not
yt-dlp): the engine lists them (`--subtitles`), the download picks one (`core::subtitles::pick`) and fetches it
after the video, as SRT. Where playback stopped is in `videos/ID.json` (`core::video`), the per-video file the
later steps add to. The tour puts its own 30-second clip and subtitles in place first (`tour.py --seed`).

### 1b. The player, organised ☑

**The person gets:** the player laid out as Vimpo's is, after a look at its demo: the bar holds only the title and
the window's buttons; the player's own controls over the picture fade while it plays; beside it, tabs (Transcript,
Subtitles; Highlights and subtitle styles join in steps 5 and 6), one clear action in each, and motion that follows
Chaya's design system (reduced motion respected).

### 2. Subtitles made on the PC ☑

**The person gets:** *Make subtitles* on any saved video, and a switch to make them for every download.
The first time, Chaya fetches the speech model, showing the download, then shows how far it has got.
The subtitles appear in the player and as an SRT next to the video.

- **Settings › Speech recognition**: the models above with their sizes, which are fetched, and delete.
- **Audio:** ffmpeg turns the sound into 16 kHz mono WAV for whisper.cpp. The cut-down ffmpeg gains the
  audio decoders it needs (AAC, MP3, Opus, Vorbis, FLAC), the resampler, and the WAV muxer.
- **whisper.cpp** runs with full JSON output (each word with its times) and progress, on the processor in
  this step; `build_whisper.sh` builds it, and `prepare.py` puts it in the engine folder.
- The language is detected, or the person picks it.
- **Tests:** in the core, against the Linux build and the Tiny model (cached in CI), the JFK sample
  whisper.cpp ships (public domain) comes back as "ask not what your country can do for you"; reading
  whisper.cpp's JSON; the model download resumes and refuses a file whose sum is wrong (a local server). The
  Windows tour makes subtitles for a short clip.

**Done.** whisper.cpp v1.9.5. The JFK test also runs on Windows, with the cross-compiled program. The model pins
are checked against Hugging Face's own list in CI (`check_models.py`). What was heard, word by word, is kept in
`videos/ID.json` for step 3. Lines are whisper.cpp's segments for now; step 3 makes them short.

### 3. Short lines, and the graphics card ☐

**The person gets:** subtitles in short lines that follow the speech (1 to 5 seconds, about 40
characters), instead of one line held for fifteen seconds; and transcription several times faster on a PC
with a graphics card.

- **Word times** from whisper.cpp's DTW timestamps, with flash attention off (Vimpo found every time comes
  back as -1 with it on).
- **Line breaks** in `chaya-core`, ported from Vimpo's rules (reference §4): split at sentence ends and
  clear pauses, then split what is still too long at the best gap. A gap scores higher after a comma, at a
  longer pause, and before "and", "but" or "because"; lower after "the", "of" or "to", between an adjective
  and its noun, or when it leaves one half too short. Tiny pieces join a neighbour; each line shows 0.2 s
  early and stays 0.3 s longer. Word lists for English first, then the other languages Chaya's people use.
  Chinese and Japanese need their own rules (reference §4) and come after.
- **Vulkan:** a second whisper.cpp build for Windows, used when the PC has a Vulkan device; the processor
  build stays as the fallback. Measured on the owner's PC before and after.
- **Tests:** recorded whisper.cpp output split into lines (the reference's example went from 27 lines to
  127, the longest 5 s); the scoring rules one by one.

**Short lines done** (the first of this step's two PRs). whisper-cli runs with `-dtw` (each model's preset in
`models.json`) and `-nfa`; a word starts at its DTW time and ends at its punctuation's, or twice a usual word's length
later, so the silence after it shows. `core::speech::lines` holds the rules and word lists (English, Spanish,
Portuguese, French, Italian, German, Hindi). Recorded with Large v3 Turbo in CI, George W. Bush's Columbia address
(3 min 18 s) went from whisper.cpp's 39 segments to 73 lines, averaging 2.4 s and 28 characters, the longest 4.3 s.
With flash attention on, every DTW time came back as -1, as Vimpo found. On CI's four processor cores Large v3 Turbo
took ten minutes for those three minutes of speech: the graphics card is next.

### 4. Your own AI model ☐

**The person gets:** **Settings › AI model**: pick a service, paste a key (or point at Ollama or LM
Studio, found on their usual ports), pick a model from the service's list, and *Test*. Nothing else
changes until they use it in step 5 or 6.

- One client in `chaya-core` with the two protocols, streaming off, JSON replies checked against what was
  asked for. The key is read from Credential Manager each time and sent only to that service.
- `PRIVACY_POLICY.md` says exactly what is sent, when, and to whom.
- **Tests:** both protocols against a local stand-in server: a reply, a refused key, a rate limit, a model
  that answers in prose instead of JSON.

### 5. Translation side by side ☐

**The person gets:** the subtitles in their language, with the original under it (or over it, or alone).
Each language has its own colour, font and size. A translation can be made again with another model and
kept as a second version.

- **Whole-video translation** (Vimpo's Smart Translation, reference §4): every batch carries the title and
  the whole transcript, so a misheard name is put right and a term is translated the same way each time.
  Lines go out numbered and must come back with the same numbers, so they stay in step.
- Batches small enough for the model's limit, retried one by one when a reply comes back short. Lines
  already translated are kept, so nothing is paid for twice.
- The translation is written as `name.es.srt` next to the video too.
- **Tests:** batching, numbering and the check that every line came back; a stand-in server that drops a
  line, or merges two.

### 6. Highlights ☐

**The person gets:** *Find highlights*: the moments that stand on their own, each with a title, a reason
and its time, marked on the progress bar and listed beside the video. Clicking one plays it from the
start. The person picks how many.

- The model gets the numbered lines and the title, and answers with line ranges; Chaya turns lines into
  times, so a highlight never starts mid-word.
- **Tests:** the reply checked (ranges inside the video, in order, not overlapping); markers placed.

### 7. Clips ☐

**The person gets:** a clip view: click a line and Shift-click another, *Make clip* (Ctrl+B), trim it on a
timeline whose edges snap to the lines, name it. Highlights become clips in one click.

- Clips are kept in the video's JSON file.
- **Tests:** selection to range, snapping, overlaps.

### 8. Export ☐

**The person gets:** clips saved as videos with the subtitles burned in (Outline, Box or Large, one
language or two), at 720p, 1080p or the original size; or as SRT files timed from 0:00 for each clip.

- ffmpeg gains libass (with FreeType, FriBidi and HarfBuzz), the scaler, `h264_mf` and the AAC encoder.
  Each clip is cut exactly, from the line's time, not the nearest keyframe.
- Notifies when an export ends or fails, with Retry.
- FCPXML (for Final Cut, Premiere and DaVinci Resolve) only if it is asked for.
- **Tests:** an exported clip's length, size and subtitle stream; the ASS file Chaya writes for each style.

### 9. Your own videos, and search ☐

**The person gets:** drop a video from the PC into Chaya to make subtitles for it, translate it or cut it
(a meeting, an interview), without uploading anything; and a search across every line of every video,
which opens the video at that line.

## Later, perhaps

- **Who is speaking:** the MOSS model labels speakers; it runs on Windows through
  [transcribe.cpp](https://github.com/handy-computer/transcribe.cpp), not whisper.cpp.
- **Listening without downloading** (Vimpo's online playback).
- **The same on the phone.**
