# Vimpo — Reference Spec for Chaya

Source: vimpo.app (home, /creators, /changelog, blog posts "Smart Line Breaks" and "Why Vimpo has no database"), read 2026-10-29. Vimpo is at v1.7.5 beta, macOS 15.6+, 14.2 MB download, free during beta (one-time purchase later).

Vimpo is a native macOS app (it uses NaturalLanguage, Keychain, and Core Data-style naming, so most likely Swift). It works as a pipeline: **paste link → download → transcribe locally → translate with the user's own LLM → watch (Cinema) → cut clips (Studio) → export.**

---

## 1. Core pipeline

| Stage | How it works |
|---|---|
| **Download** | Uses **yt-dlp** plus **ffmpeg**, so it works with thousands of sites (YouTube, Vimeo, Twitch, TED, SoundCloud, X, TikTok, Instagram, Reddit, Facebook, Dailymotion, Niconico, Bandcamp, Bilibili). Interrupted downloads resume, and the parts already downloaded are kept. Shows a queue with live speed (MB/s) and %, plus Stop/Resume/Remove. Has a sign-in flow for when a YouTube download needs a login. |
| **Local import** | Drag-drop, ⌘I, or a "Local Files" button. Two modes: **Copy to Library** (uses disk space, and the original can be deleted) or **Link Original File** (uses no space, but breaks if the original is deleted). Can transcribe and translate right after import. |
| **Transcribe** | **Whisper (whisper.cpp), on-device, offline.** A **MOSS** ASR model was also added in 1.6.0. Can be started from the download options, the player, or Studio. |
| **Translate** | The user's own LLM (an API key or a local model). Translates subtitles line by line, with an optional "Smart Translation" whole-video pass (§4). |
| **Online playback** (1.7.0) | Plays only the audio of YouTube or Bilibili without downloading the video. |

Each job has these per-item options at paste time: quality (e.g. 1080p), subtitle target language, and status chips (`Reading link… → Download → Transcribe → Translate`).

---

## 2. Models

### Speech recognition (local)
| Whisper model | Size | Note |
|---|---|---|
| Tiny | 78 MB | fastest, least accurate |
| Small | 488 MB | |
| **Large v3 Turbo (quantized)** | **574 MB** | **Recommended default** |
| Medium | 1.5 GB | |
| Large v3 | 3.1 GB | most accurate |

Models are downloaded on demand into the app-support directory. MOSS is offered as an alternative engine.

### Translation / AI (bring your own key)
Providers: OpenAI, Anthropic, Gemini, DeepSeek, OpenRouter, Qwen, Moonshot, Z.ai, MiniMax, xAI, Mistral, Groq, **Ollama**, **LM Studio**, plus **any OpenAI-compatible endpoint**.
- Keys are stored in the **OS keychain**.
- A **translation cache** means the same line is never paid for twice.
- Target languages: EN, ES, FR, DE, PT, JA, KO, zh-Hans, zh-Hant, IT, RU.
- Re-translating with a different model **saves a new version**, and the user can switch between versions.
- Redoing original or translated subtitles keeps the old ones playing until the new ones are ready.
- Only subtitle text and the title are sent to the LLM. Video never leaves the device.

---

## 3. Storage — "file over app", no database

The library is a plain folder named `*.vimpo`, suggested inside ~/Movies. It is not a bundle.

```
Vimpo Library.vimpo/
├── library.json
└── How Film Cameras Work [Xk2pR8vL0aQ]/     # folder = title + source id
    ├── item.json        # the ONLY record for this film
    ├── video.mp4
    ├── cover.jpg
    ├── video.en.srt     # one SRT per language: video.<lang>.srt
    └── video.zh-Hans.srt
```

`item.json` is pretty-printed, uses alphabetical keys and ISO-8601 dates, and stores relative paths. Only `id` and `title` are required:
```json
{
  "cover": "cover.jpg",
  "createdAt": "2026-10-12T09:41:07Z",
  "duration": 1264,
  "id": "6F1C2A9E-3B7D-4C51-9A0E-2D8B5F47C310",
  "lastPlayedAt": "2026-10-14T21:03:55Z",
  "playbackPosition": 812.4,
  "sourceURL": "https://www.youtube.com/watch?v=Xk2pR8vL0aQ",
  "subtitles": [
    { "fileName": "video.en.srt", "isOriginal": true,  "languageCode": "en",      "lineCount": 418, "origin": "recognized" },
    { "fileName": "video.zh-Hans.srt", "isOriginal": false, "languageCode": "zh-Hans", "lineCount": 418, "origin": "translated" }
  ],
  "title": "How Film Cameras Work",
  "uploader": "Film Notes",
  "video": "video.mp4"
}
```

Rules:
- The app **rescans the library folder every time the window regains focus**. Changes made in Finder count:
  - renaming a folder keeps the same film (matched by `id`, so the playback position survives);
  - editing the title in `item.json` updates the title shown in the app;
  - duplicating a folder creates a separate film;
  - deleting a folder removes the film.
- Deleting a film in the app **moves it to the Trash**. Permanent delete is a separate option.
- There is no export or backup feature, because Time Machine, iCloud, Dropbox, or Syncthing can handle the folder. "Open Library" (⌘O) lets the user switch libraries.
- **Outside the library** (everything here can be regenerated): yt-dlp and ffmpeg binaries, Whisper models, and the translation cache live in `~/Library/Application Support/Vimpo/`. API keys live in the Keychain.
- SQLite is allowed later **only as a cache index** that can be deleted and rebuilt from the JSON files.
- Subtitles are written as SRT files next to the video, so other players (IINA, VLC) pick them up automatically.

---

## 4. Subtitle intelligence

### Smart Line Breaks (local, no LLM)
Problem: Whisper segments can run 13 s and 200+ characters on average, with some up to 28 s and 450 characters.
1. **Word-level timestamps** using whisper.cpp DTW, which reads the attention heads. **Flash Attention must be OFF**, otherwise every timestamp comes back as -1. This makes transcription about 15% slower (12 s → 14 s for 6 minutes of audio).
2. **Sentence and part-of-speech analysis** with Apple NaturalLanguage, which also segments Chinese words. First split at sentence ends and clear pauses. Then recursively split any line that is still too long at the best-scoring gap between two words:
   - comma or semicolon before the gap: **+**
   - pause between the words: **+** (more for a longer pause)
   - next word is a conjunction (and / but / because): **+**
   - previous word is a function word (the / of / to): **−**
   - gap falls between an adjective and its noun: **−**
   - the two halves are balanced: **+**; either half too short: **−**
3. Post-processing: merge tiny fragments into a neighbour. Show each cue **0.2 s early** and keep it on screen **0.3 s longer**.
4. Chinese (Whisper output has almost no punctuation): rely mainly on pauses and clause openers (但是 / 所以 / 其实 / 比如说). Never break right after 的, never split 一 from 个, and never end a line with 这个 or 那个.
- Result: 27 subtitles became 127 (average 2.7 s and 44 characters, longest 5 s). In Chinese, about 5% of breaks are still off. A planned improvement is an optional LLM pass to refine breaks.

### Smart Translation (LLM, whole-video context)
The model reads the **whole transcript first**, then translates. This gives three benefits:
- **Misheard names get fixed** (e.g. "Irimandris" → "Sr. Irimajiri").
- **The same term is translated the same way every time** (e.g. "agent" became *agente* 31/31 times, instead of *agente* 17 and *representante* 14).
- **Lines stay in step**: each translated line stays aligned 1:1 with its source line.
It can be turned on in the download/import options, in Settings → Translate, or in the subtitle menu.

---

## 5. Player — "Cinema" mode (⌘1)

- **Bilingual subtitles**, original and translation stacked. The user can choose which one goes on top.
- **Separate style for each language**: color, font, and size (font size controls added in 1.7.1).
- **Transcript sidebar** that follows playback (shows timestamp, original line, and translated line). Clicking a line seeks to it. The header shows e.g. "Spanish · English · 2 speakers".
- Sidebar tabs: **Transcript / Highlights / Subtitle Style**.
- **AI Highlights on the progress bar**: markers with a title and a reason; chapter-like titles appear in the scrubber.
- Playback speed, theater mode (F key or double-click), and the ability to open films in separate windows to watch several at once.
- Resume position is saved to `item.json`.
- Subtitles can be dragged to the top of the frame.
- The trackpad swipe is guarded so it doesn't accidentally seek.

---

## 6. Studio — clip editor (⌘2)

- **Select by text**: click a transcript line, then Shift-click another line. This shows "2 lines selected · 1:46–2:13 · 0:27". **⌘B** (or the Make Clip button) creates the clip.
- **Timeline** with tracks: *AI Picks*, *Picture*, *<subtitle language>*, *Sound*. Clip edges **snap to subtitle line boundaries**. I/O keys set in and out points, and the display shows a frame-accurate timecode (`00:01:46:12`).
- **Clips panel**: named clips with ranges and a running total.
- **AI Highlights**: a "Find Highlights" button sends subtitles and title to the chosen LLM, which returns N picks. Each pick has a **title, a reason ("A clear claim, with numbers"), and a range**. Picks can be added with **Add to Clips** or **Add All**, or dismissed. The user can choose how many clips the AI should find.
- **Subtitle presets**: Outline, Box, Large, Two Languages, Translation on Top, Original on Top.
- **Export dialog**:
  - Content: N clips or the whole film.
  - Export as: Video or Subtitle files.
  - Subtitles: Burn In, SRT File (re-timed from 0:00 for each clip), or None.
  - Quality: 720p, 1080p, or Original.
  - Optionally also export **FCPXML** for further editing in Final Cut or Premiere.
  - Notifies when the export finishes or fails, and has a retry option.

---

## 7. App shell / UX

- **Sidebar**:
  - Videos: All
  - Source: YouTube, Bilibili, Other Sites, Local Import
  - Settings: General, Speech Recognition, AI Providers, Translate
- **Footer**: global download speed and the number of items in progress.
- **Main area**: a top bar that takes a pasted link or dropped files. Below it is a video "wall" (grid), where each card shows the cover, title, language badges (EN/ES), and duration.
- Components (yt-dlp, ffmpeg, models) are **installed lazily** with a visible state ("Waiting to install components"). If an install fails, the app shows the reason.
- Error reporting happens in place ("report a problem right where it happens").
- No accounts and no servers.

---

## 8. Porting notes for Chaya

| Vimpo piece | macOS-only? | Cross-platform equivalent |
|---|---|---|
| whisper.cpp + DTW word timestamps | No | whisper.cpp everywhere (`--dtw` / `token_timestamps`, flash-attn off); or faster-whisper with `word_timestamps=True` |
| Apple NaturalLanguage (sentence/POS/zh segmentation) | **Yes** | spaCy / compromise (EN), jieba or pkuseg (ZH), or a simpler punctuation-and-pause heuristic |
| Keychain | **Yes** | OS keyring (`keytar`, Tauri `keyring`, Python `keyring`) |
| yt-dlp + ffmpeg | No | same; download binaries to the app-data dir on first run |
| FCPXML export | No | write the XML yourself; it's a documented format |
| Library folder + item.json | No | copy the design as-is |
