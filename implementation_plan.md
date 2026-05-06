# Chaya — Media Downloader App

A media downloading app that detects and downloads any media visible on screen, inspired by Aloha Browser's download architecture but built as a standalone downloader (not a full Chromium fork).

## The Core Idea

Chaya = **WebView browser** + **smart media detection** + **robust download engine**

Instead of forking Chromium (like Aloha does — 1.7M commits, insane build system), we use Android's built-in WebView and replicate Aloha's **3 detection strategies** on top of it:

1. **Network Interception** — catch media URLs as they flow through the WebView
2. **DOM Inspection** — inject JavaScript to find `<video>`, `<audio>`, `<source>` elements  
3. **HLS/DASH Detection** — intercept `.m3u8`/`.mpd` manifests for streaming content

## Tech Stack Decision

| Layer | Choice | Why |
|-------|--------|-----|
| **Language** | Kotlin | Modern Android standard, coroutines for async downloads |
| **UI** | Jetpack Compose | Modern, reactive, Material 3 |
| **Architecture** | MVVM + Clean Architecture | Scalable, testable, industry standard |
| **Browser** | Android WebView | Built-in, no Chromium fork needed |
| **HTTP Client** | OkHttp | Custom headers, resume support, interceptors |
| **HLS Downloads** | Media3 (ExoPlayer) DownloadManager | Industry standard for segmented stream downloads |
| **Database** | Room | Download queue persistence, history |
| **DI** | Hilt | Google's recommended DI framework |
| **Background** | Foreground Service + WorkManager | Downloads survive app backgrounding |

> [!IMPORTANT]
> **Why not Flutter/React Native?** Deep WebView integration (shouldInterceptRequest, JavaScript bridge, cookie syncing) is significantly more robust in native Android. We can add iOS later with Swift + WKWebView — the architecture translates cleanly.

## Proposed Changes

### Module Structure

```
chaya/
├── app/                          # Main application module
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/chaya/
│   │       ├── ChayaApp.kt       # Application class + Hilt entry
│   │       ├── MainActivity.kt   # Single activity (Compose)
│   │       └── ui/               # Compose screens & navigation
│   │           ├── theme/        # Material 3 theme
│   │           ├── browser/      # Browser screen (WebView)
│   │           ├── downloads/    # Downloads list screen
│   │           └── player/       # Media preview/player
│
├── core/                         # Shared utilities & models
│   ├── model/                    # Data classes (MediaItem, DownloadTask, etc.)
│   ├── database/                 # Room DB (download history, queue)
│   └── common/                   # Extensions, constants
│
├── feature-detection/            # Media detection engine
│   ├── interceptor/              # Network-level interception
│   │   ├── MediaInterceptor.kt   # shouldInterceptRequest handler
│   │   └── MimeTypeDetector.kt   # URL pattern + Content-Type matching
│   ├── inspector/                # DOM-level JavaScript injection
│   │   ├── MediaInspector.kt     # JS injection orchestrator
│   │   ├── js/                   # JavaScript files (injected into pages)
│   │   │   ├── chaya_media_detector.js   # Find <video>, <audio>, <source>
│   │   │   ├── chaya_shadow_dom.js       # Shadow DOM + iframe traversal
│   │   │   └── chaya_hls_sniffer.js      # Intercept XHR/fetch for .m3u8
│   │   └── MediaBridge.kt       # @JavascriptInterface bridge
│   └── coordinator/              # Combines interceptor + inspector results
│       └── MediaDetectionCoordinator.kt
│
├── feature-download/             # Download engine
│   ├── engine/
│   │   ├── HttpDownloader.kt     # OkHttp-based file download (resume, parallel)
│   │   ├── HlsDownloader.kt     # Media3-based HLS/DASH segment download
│   │   └── DownloadEngine.kt    # Routes to correct downloader based on URL type
│   ├── service/
│   │   ├── DownloadService.kt   # Foreground Service for background downloads
│   │   └── DownloadNotification.kt
│   ├── manager/
│   │   └── DownloadManager.kt   # Queue management, state machine, persistence
│   └── model/
│       └── DownloadState.kt     # NOT_STARTED → QUEUED → DOWNLOADING → PAUSED → COMPLETE/FAILED
│
└── feature-browser/              # WebView wrapper
    ├── ChayaWebView.kt          # Custom WebView with all hooks wired
    ├── ChayaWebViewClient.kt    # shouldInterceptRequest + shouldOverrideUrlLoading
    ├── ChayaWebChromeClient.kt  # Fullscreen video, file chooser, progress
    └── CookieSync.kt           # Sync WebView cookies to OkHttp for authenticated downloads
```

---

### Milestone 1: Walking Skeleton (MVP)

> Goal: Browse a page, detect a video, download it. End to end.

#### Phase 1 — Project Setup
- Scaffold Android project with Kotlin, Compose, Hilt
- Set up module structure (app, core, feature-detection, feature-download, feature-browser)
- Configure build.gradle with all dependencies
- Material 3 theme (dark mode default — media downloaders look better dark)

#### Phase 2 — Browser Shell
- WebView wrapped in Compose (`AndroidView`)
- URL bar with navigation (back, forward, refresh)
- `ChayaWebViewClient` with `shouldInterceptRequest` hooked up
- `ChayaWebChromeClient` for fullscreen video + progress bar
- Basic JavaScript settings (JS enabled, DOM storage, media playback)

#### Phase 3 — Media Detection (Network Layer)
- `MediaInterceptor` inside `shouldInterceptRequest`:
  - Pattern match URLs for: `.mp4`, `.webm`, `.m3u8`, `.mpd`, `.ts`, `.aac`, `.mp3`
  - Check `Content-Type` headers for `video/*`, `audio/*`, `application/vnd.apple.mpegurl`
  - Deduplicate detected URLs
- Floating "media detected" badge (like Aloha's download bubble)
- Tapping the badge shows list of detected media with type, size estimate

#### Phase 4 — Media Detection (DOM Layer)
- Inject `chaya_media_detector.js` on page load:
  ```javascript
  // Scans for <video>, <audio>, <source> elements
  // Reports currentSrc, src attributes back to Android via bridge
  // MutationObserver for dynamically added media
  ```
- `MediaBridge.kt` with `@JavascriptInterface` receiving detected URLs
- Merge network + DOM results in `MediaDetectionCoordinator`

#### Phase 5 — Basic Download Engine
- `HttpDownloader` using OkHttp:
  - Range header support for resume
  - Progress reporting via `Flow<DownloadProgress>`
  - File saved to app-specific external storage
- Room database for download state persistence
- Simple downloads list screen (Compose)

#### Phase 6 — Download Service
- `DownloadService` as Foreground Service with notification
- Download queue (max 3 concurrent)
- Pause/Resume/Cancel controls
- Notification with progress bar

---

### Milestone 2: Power Features

#### Phase 7 — HLS/DASH Stream Downloads
- Integrate Media3 `DownloadManager` for `.m3u8` / `.mpd`
- Parse manifests, offer quality selection
- Download all segments + merge to single file

#### Phase 8 — Shadow DOM + iframe Support
- Port Aloha's `findShadowElementByAlohaId` pattern to `chaya_shadow_dom.js`
- Handle nested iframes (cross-origin safety)
- Element tagging system (like Aloha's `aloha_id`)

#### Phase 9 — Cookie & Auth Sync
- Sync `CookieManager` cookies to OkHttp client
- Support authenticated downloads (logged-in sites)
- Custom headers forwarding

#### Phase 10 — Smart File Naming
- Parse Content-Disposition headers
- Extract filename from URL path
- Fallback: site name + timestamp
- Duplicate file handling

---

### Milestone 3: Polish & iOS Prep

#### Phase 11 — Media Preview
- In-app video player (Media3/ExoPlayer)
- Thumbnail generation for download list
- File type icons (video, audio, image)

#### Phase 12 — UI Polish
- Glassmorphic download cards
- Animated progress rings
- Pull-to-refresh download list
- Swipe-to-delete downloads
- Dark/Light theme toggle

#### Phase 13 — iOS Architecture Planning
- Map Android patterns to Swift equivalents
- WKWebView + `decidePolicyFor` (≈ shouldInterceptRequest)
- WKScriptMessageHandler (≈ @JavascriptInterface)
- URLSession download tasks (≈ OkHttp)

---

## How Aloha's Patterns Map to Chaya

| Aloha Component | Chaya Equivalent | Difference |
|-----------------|------------------|------------|
| `bromium.cc` (C++ JNI) | `MediaBridge.kt` (@JavascriptInterface) | Much simpler — no native code needed |
| `find_video_url.cc` (DOM traversal) | `chaya_media_detector.js` (injected JS) | Same strategy, JS instead of C++ renderer |
| `shouldInterceptRequest` in WebView | Same — `ChayaWebViewClient` | Identical approach |
| `BromiumClient.onHlsDetected()` | `MediaInterceptor` + pattern matching | Simpler — no JNI bridge |
| `BromiumClient.onDownloadToCacheFinished()` | Not needed | We download directly, not from HTTP cache |
| `TemporaryDownloads/` dir | Room DB + file storage | Database-driven instead of filesystem |
| `IsAllowedDownloadFromCache()` blacklist | Domain filter config | Similar pattern, configurable |
| `AlohaVideoPageRecorder` (MediaRecorder) | Future: screen capture mode | Later milestone |
| `InProgressDownloadManager` (Chromium) | `DownloadManager.kt` (custom) | Custom state machine, much simpler |

> [!NOTE]
> **The biggest difference**: Aloha modifies Chromium's internals to download from the HTTP cache. We can't do this with stock WebView. Instead, we re-download the file using OkHttp with the same cookies/headers — this is what 1DM, Soul Browser, and other downloaders do. It works just as well for the user.

## Open Questions

> [!IMPORTANT]
> **Distribution**: Will this be on Google Play? If yes, avoid YouTube download features — instant policy violation. Consider F-Droid or direct APK distribution for full feature set.

> [!IMPORTANT]
> **App Name**: "Chaya" is great. Any tagline in mind? (e.g., "Chaya — Download Anything")

> [!WARNING]
> **Scope Check**: Milestone 1 (Phases 1-6) is roughly **2-3 weeks of focused development**. Want to proceed with just Milestone 1 first, or the full roadmap?

## Verification Plan

### Automated Tests
- Unit tests for `MediaInterceptor` pattern matching
- Unit tests for `DownloadEngine` state machine
- Instrumented tests for WebView JS injection
- `./gradlew test` and `./gradlew connectedAndroidTest`

### Manual Verification
- Test media detection on: YouTube, Twitter/X, Instagram, Reddit, generic MP4 URLs
- Test HLS detection on: Twitch clips, news sites with video
- Test download resume after app kill
- Test concurrent download queue behavior
