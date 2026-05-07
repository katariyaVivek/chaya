# Chaya — Build & Test Guide

## What's Here

This is the **Chaya** Android app — a WebView-based browser with smart media detection and downloads. Built in Kotlin with Jetpack Compose.

### v0.2 Features

| Area | What it does |
|------|-------------|
| **WebView browser** | Full browser with URL bar, back/forward/refresh |
| **Network detection** | Catches media URLs flowing through `shouldInterceptRequest()` by extension (`.mp4`, `.webm`, `.mp3`, `.m4a`, `.aac`, `.m3u8`, `.mpd`) and MIME type |
| **DOM detection** | Injected JavaScript scans `<video>`/`<audio>` elements, watches for dynamic additions via MutationObserver |
| **Media sheet** | Floating action button with badge count → bottom sheet listing all detected media |
| **OkHttp downloader** | Downloads regular files with progress, resume via `Range` header, cancellation, cookie/UA forwarding |
| **Media3 streaming** | HLS (`.m3u8`) and DASH (`.mpd`) downloads via ExoPlayer's `DownloadManager` with segment caching |
| **Quality picker** | Parses stream manifests and shows available video/audio tracks — pick what to download |
| **Foreground service** | Keeps downloads alive in background with progress notification and cancel action |
| **Room database** | Persists download history across app restarts |
| **Downloads screen** | Full list with progress bars, status labels, cancel button |

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

4. **Install** on a connected device/emulator:
   ```
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
   Or just press **Run** (▶) in Android Studio.

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

### 3. Streaming detection (HLS/DASH)
- Visit a site with `.m3u8` or `.mpd` streams
- Tap the stream in the media sheet
- A **quality picker** appears showing available video resolutions and audio tracks
- Select desired tracks and tap "Download Selected"
- Download proceeds via Media3 with progress in notification

### 4. Foreground download
- Start a download and press Home
- Notification with progress bar stays in the shade
- Tap "Cancel" on the notification to stop the download

### 5. Downloads screen
- Tap the downloads icon (bottom nav bar, rightmost)
- Shows all downloads: active (progress bar), completed, failed, cancelled
- Cancel active downloads with the ✕ button

### 6. Persistence
- Complete a download, force-stop the app, reopen
- Navigate to the downloads screen — the completed download is still there

---

## Project Structure

```
com.chaya.app
├── browser/            WebView shell + ViewModel
│   ├── BrowserScreen.kt       UI (URL bar, WebView, nav, FAB, sheets)
│   └── BrowserViewModel.kt    State management + quality picker logic
├── detection/          Media detection layers
│   ├── MediaInterceptor.kt    Network-level (shouldInterceptRequest)
│   └── MediaBridge.kt         @JavascriptInterface bridge for DOM scanning
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
