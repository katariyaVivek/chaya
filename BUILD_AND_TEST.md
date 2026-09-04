# Chaya — Build & Test Guide

## What's Here

This is the **Chaya** Android app — a WebView-based browser with smart media detection and downloads. Built in Kotlin with Jetpack Compose.

### Features (v0.3)

| Area | What it does |
|------|-------------|
| **WebView browser** | Full browser with URL bar, back/forward/refresh, system back handling, `target=_blank` links, fullscreen video; session survives navigation |
| **Network detection** | Extension-based detection in `shouldInterceptRequest()` (mp4/webm/mp3/m4a/aac/m4v/mov/mkv/ts/m3u8/mpd…) |
| **DOM detection** | Injected JS scans video/audio/source elements, Shadow DOM, and same-origin iframes. Direct URLs are reported automatically; response-confirmed unknown same-origin fetch/XHR endpoints are checked only after an explicit, bounded scan. |
| **Media sheet** | Floating FAB with badge count → bottom sheet listing all detected media |
| **OkHttp downloader** | Progress, resume via `Range`, silent cancel, Referer/Cookie/UA forwarding, Content-Disposition filenames |
| **Media3 streaming** | HLS/DASH downloads via ExoPlayer `DownloadManager`; pause = stopDownload, resume = startDownload; headers forwarded |
| **Quality picker** | Parses manifests → video/audio track selection; error state keeps URL for "download anyway" |
| **Pause/resume/retry/cancel** | Full state machine; partial files kept and reused (HTTP Range resume, Media3 cache reuse) |
| **Public storage export** | Completed files copied to MediaStore (Movies/Music/Downloads) on API 29+; FileProvider open below |
| **In-app player** | Completed streams play via ExoPlayer reading the same segment cache |
| **Foreground service** | Always promotes safely, progress + Pause/Cancel actions, per-download completion notifications, permission-safe notify |
| **Room database** | Explicit IDs (no ID mismatch), error messages stored, interrupted downloads restored as PAUSED |
| **Downloads screen** | Per-state actions: pause/resume/retry/delete/open/play + error text + sizes |

---

## How to Build

### Prerequisites

- Android Studio (Hedgehog 2023.1+ or later)
- Android SDK 35
- JDK 17

### Steps

1. **Open the project** in Android Studio:
   - `File → Open` → select the `chaya/` directory
   - Android Studio will detect `settings.gradle.kts` and sync Gradle automatically

2. **Let Gradle sync** — it will download all dependencies:
   - Compose BOM, Room, OkHttp, Media3 (ExoPlayer), Coroutines
   - This takes 1-3 minutes on first open

3. **Build the APK**:
   - `Build → Build Bundle(s) / APK(s) → Build APK(s)`
   - Or via command line (after first Android Studio sync generates the wrapper):
     ```
     ./gradlew assembleDebug
     ```

> **Note:** the Gradle wrapper JAR is not checked in. Open the project once in Android Studio (which generates it), or run `gradle wrapper --gradle-version 8.9` if you have a local Gradle install.

4. **Install** on a connected device/emulator:
   ```
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
   Or just press **Run** (▶) in Android Studio.

### Release builds (signed APK via GitHub Releases)

1. **Create a keystore once** (keep it backed up — losing it means a new app identity):
   ```
   keytool -genkeypair -keystore chaya-release.keystore -alias chaya \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. **Local builds:** copy `keystore.properties.example` to `keystore.properties`
   (gitignored) and fill in your path/passwords. `./gradlew assembleRelease`
   then signs with your key; without it, release falls back to debug signing
   for verification only — never distribute that APK.
3. **CI releases:** store `KEYSTORE_BASE64` (base64 of the keystore file),
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` as repo secrets, then
   push a tag (`git tag v0.3.0 && git push origin v0.3.0`). The `release` job
   runs tests, builds the signed APK, and publishes it on the GitHub Release.
4. **Distribution decision (5.2):** direct APK via GitHub Releases. Play Store
   is deferred — a media downloader is policy-adjacent there (copyrighted-
   content clause) and the self-hosted diagnostics assume no Play Services.
   See `LIMITATIONS.md` and `PRIVACY_POLICY.md` for the positioning.

---

## How to Test

### 1. Basic browsing
- Open the app
- Type `https://www.sample-videos.com/` in the URL bar
- Press Go → page should load with back/forward working

### 2. Media detection (regular files)
- Navigate to a page with direct `.mp4` links
- A **floating download button** (FAB) appears — badge shows media count
- Tap it → bottom sheet lists detected media
- Tap a media item → download starts → snackbar confirms + notification shows progress

### 3. Thorough scan for an extensionless endpoint
- On a trusted page with no automatically detected media, wait briefly for **Scan more thoroughly** to appear.
- Tap it to opt in. Chaya only considers successful, same-origin `GET` fetch/XHR responses that already advertised an audio, video, HLS, DASH, or Ogg `Content-Type` to the browser.
- At most ten unique candidate URLs are rechecked with redirect-free `HEAD` requests. The request carries the target's browser cookies, user agent, and page referrer only to that same origin.
- A recognized `Content-Type` (for example `video/mp4` from `/media/abc123`) appears in the media sheet. JSON, failed `HEAD` responses, cross-origin endpoints, and redirects are ignored.
- No extra `HEAD` traffic runs before the user taps the affordance; this is a deliberate privacy and network-use boundary rather than automatic background scanning.

### 4. Streaming detection (HLS/DASH)
- Visit a site with `.m3u8` or `.mpd` streams
- Tap the stream in the media sheet
- A **quality picker** appears showing available video resolutions and audio tracks
- Select desired tracks and tap "Download Selected"
- Download proceeds via Media3 with progress in notification

### 5. Foreground download
- Start a download and press Home
- Notification with progress bar stays in the shade
- Tap "Cancel" on the notification to stop the download

### 6. Downloads screen
- Tap the downloads icon (bottom nav bar, rightmost)
- Active → **pause** ⏸ / **cancel** ✕ · Paused/cancelled → **resume** ▶ / **delete** 🗑
- Failed → **retry** ↻ (error reason shown) · Completed file → **open** ↗ · Completed stream → **play** ▶ (in-app player)

### 7. Persistence
- Complete a download, force-stop the app, reopen
- Navigate to the downloads screen — the completed download is still there

---

## Project Structure

```
com.chaya.app
├── browser/            WebView shell + ViewModel
│   ├── BrowserScreen.kt       UI (URL bar, WebView, nav, FAB, sheets)
│   ├── BrowserViewModel.kt    State management + quality picker logic
│   └── RetainedWebViewCallbacks.kt  Atomic callback rebinding for the retained WebView
├── detection/          Media detection layers
│   ├── ContentTypeSniffer.kt  Cancellable, redirect-free opt-in MIME verification
│   ├── MediaInterceptor.kt    Network-level (shouldInterceptRequest)
│   └── MediaBridge.kt         Capability-gated @JavascriptInterface DOM bridge
├── download/           Download infrastructure
│   ├── DownloadManager.kt     Coordinates HTTP + stream downloads
│   ├── HttpDownloader.kt      OkHttp single-file downloader
│   ├── DownloadService.kt     Foreground service for background downloads
│   ├── DownloadNotification.kt Notification channel + builder
│   ├── DownloadTask.kt        Domain model
│   └── DownloadState.kt       State enum
├── streaming/          HLS/DASH via Media3
│   ├── StreamDownloader.kt    Media3 DownloadManager wrapper
│   ├── ManifestHelper.kt      Manifest parser → available tracks
│   └── StreamTrack.kt         Track model for quality picker
├── database/           Room persistence
│   ├── ChayaDatabase.kt       Database class
│   ├── DownloadDao.kt         DAO with CRUD + progress queries
│   ├── DownloadEntity.kt      Room entity
│   └── Converters.kt          DownloadState ↔ String converter
├── ui/                 Compose UI components
│   ├── components/
│   │   ├── DetectedMediaSheet.kt   Media list bottom sheet
│   │   └── QualitySelectorSheet.kt Track quality picker
│   ├── navigation/    NavHost + Screen routes
│   └── theme/         Material 3 theme (dynamic color)
└── model/
    └── DetectedMedia.kt        Media detection data class
```

---

## Dependencies

| Library | Purpose |
|---------|---------|
| Jetpack Compose + M3 | UI framework |
| Android WebView | Browser engine |
| OkHttp 4.12 | Regular file downloads |
| Media3 1.5.1 (ExoPlayer) | HLS/DASH streaming downloads |
| Room 2.6.1 | Download history persistence |
| Kotlin Coroutines 1.9 | Async operations |
| Navigation Compose 2.8 | Screen navigation |

---

## Architecture Flow

```
WebView → shouldInterceptRequest → MediaInterceptor → DetectedMedia
WebView → evaluateJavascript → MediaBridge → DetectedMedia
                                      │
                         explicit “Scan more thoroughly” opt-in
                                      │
                              ContentTypeSniffer (≤10 same-origin HEADs)
                                      │
                                 response-confirmed DetectedMedia
                                                     ↓
                                              DetectedMediaSheet
                                                     ↓
                                      ┌────────────────┴────────────────┐
                                      │                                │
                                  .mp4/.mp3                     .m3u8/.mpd
                                      │                                │
                              HttpDownloader                  ManifestHelper
                                      │                                │
                                      │                          QualitySelectorSheet
                                      │                                │
                                      └──────────┬─────────────────────┘
                                                 │
                                         DownloadManager
                                         (Room persistence)
                                                 │
                                          DownloadService
                                     (Foreground + Notification)
```

---

## Key Design Decisions

- **Kotlin over Flutter** — needed for deep WebView control, JS bridge, foreground services
- **Single module** — no premature Gradle module splitting for MVP speed
- **Media3 for streams** — handles segment downloading, caching, and offline storage
- **In-memory progress** — progress updates are not written to Room (too frequent); only state transitions persist
- **Observer-only interceptor** — `shouldInterceptRequest` returns `null` so the WebView loads normally; we just watch
- **Explicit unknown-endpoint verification** — no extensionless fetch/XHR URL is checked in the background. A user must choose **Scan more thoroughly**; native code independently enforces same-origin authorization, per-navigation capabilities, deduplication, and a ten-candidate limit.
