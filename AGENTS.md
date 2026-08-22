# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

**Chaya** is a native Android media downloader app (Kotlin, Jetpack Compose, WebView) that provides smart media detection and robust downloads. The project is currently in the research/planning phase, with a full implementation plan documented.

The repository also contains a study of [Aloha Browser's](https://github.com/AlohaBrowser/aloha-core) Chromium fork (`aloha-core-study/aloha-core/`), used as reference for media detection and download patterns.

## Project Structure

```
/
├── plan.md                    # Full implementation plan (MVP → v0.5)
├── aloha_core_analysis.md     # Deep analysis of Aloha's download system
├── AGENTS.md                  # This file
└── aloha-core-study/
    └── aloha-core/            # Sparse checkout of Aloha Browser's Chromium fork
        ├── aloha/             # Aloha-specific code (study target)
        │   ├── build/         # Python build scripts (webview AAR, GN config)
        │   ├── src/native/    # C++ JNI bridge, video URL detection
        │   ├── src/java/      # Android Java API (Bromium engine wrapper)
        │   └── src/js/        # Injected JS (DOM traversal, video recording)
        ├── components/download/  # Chromium download engine (public API, DB, network)
        ├── chrome/browser/download/
        ├── content/browser/download/
        └── ios/web/download/
```

## Architecture (Planned)

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Platform | Native Android |
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Browser | Android WebView |
| Downloads | OkHttp (v0.1), Media3/ExoPlayer (v0.2+) |
| Database | Room |
| Async | Kotlin Coroutines + Flow |
| Background | Foreground Service + WorkManager |

### Package Structure (planned)

```
com.chaya
├── browser/         # WebView shell, WebViewClient, WebChromeClient, cookie sync
├── detection/       # MediaInterceptor, MediaInspector, MediaBridge, injected JS
├── download/        # DownloadManager, HttpDownloader, DownloadService, notifications
├── database/        # Room DB, DAO, entities
├── model/           # DetectedMedia, DownloadTask data classes
├── ui/              # Compose screens (browser, downloads, components, theme)
└── utils/           # FileNameUtils, MimeTypeUtils, UrlUtils
```

### Media Detection Architecture

Two detection layers operate in parallel:

1. **Network-level** — `WebViewClient.shouldInterceptRequest()` catches media URLs flowing through the WebView by extension/MIME type
2. **DOM-level** — Injected JavaScript (`@JavascriptInterface`) scans the page for `<video>`, `<audio>`, `<source>` elements and watches mutations

### Download States

`QUEUED → DOWNLOADING ⇄ PAUSED → COMPLETED | FAILED | CANCELLED`

## Key Research Findings (from Aloha Core)

- **Cache-based downloads**: Aloha serves media from the HTTP cache (150MB max, ~18.75MB per file) to avoid re-requests and CORS issues
- **DOM traversal**: Aloha's `find_video_url.cc` performs BFS DOM traversal with configurable limits (200 elements, 10 parent levels), handling Shadow DOM and iframes
- **`aloha_id` attribute system**: Persistent element IDs across DOM mutations for reliable media element tracking
- **Player-specific handling**: YouTube and JW Player need separate fullscreen/control strategies
- **`InterceptDownload` hook**: Injected into Chromium's `InProgressDownloadManager` to redirect downloads before standard path

## Development Phases (from plan.md)

| Phase | Focus |
|-------|-------|
| 1 | Android project setup (Kotlin, Compose, Room, OkHttp, Coroutines) |
| 2 | Browser shell (WebView, URL bar, navigation) |
| 3 | Network-level media detection (`shouldInterceptRequest`) |
| 4 | DOM-level media detection (injected JS + bridge) |
| 5 | Media detection UI (floating button, bottom sheet) |
| 6 | OkHttp downloader (progress, resume, cookies) |
| 7 | Room database for download history |
| 8 | Foreground download service |
| 9 | HLS/DASH support via Media3 (v0.2) |
| 10 | Advanced detection: Shadow DOM, iframes, XHR sniffing (v0.3) |

## Key Design Decisions

- **Kotlin over Flutter** — needed for deep WebView control, JS bridge, cookie sync, foreground services, Media3 integration
- **Single module MVP** — no premature Gradle module splitting
- **MP4/WebM/MP3 first** — HLS/DASH come after basic downloads work reliably
- **Start with `shouldInterceptRequest`** — simpler than JS injection for initial detection
