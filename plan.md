# Chaya — Kotlin-First Media Downloader App Plan

## 1. Core Direction

**Chaya** should be built as a native Android app first.

The app idea:

> **Chaya = Android WebView browser + smart media detection + robust download manager**

Do **not** start by forking Chromium or building on Aloha Core directly. Aloha is useful for research and inspiration, but its architecture is too heavy for a solo MVP.

The practical path is to use Android's built-in WebView and build your own media detection and download layer around it.

---

## 2. Final Tech Stack

| Layer | Choice |
|---|---|
| Platform | Native Android |
| Language | Kotlin |
| UI | Jetpack Compose |
| Browser | Android WebView |
| Network detection | `WebViewClient.shouldInterceptRequest` |
| DOM detection | Injected JavaScript + `@JavascriptInterface` |
| File downloads | OkHttp |
| HLS/DASH downloads | Media3 / ExoPlayer DownloadManager, later |
| Database | Room |
| Async | Kotlin Coroutines + Flow |
| Background downloads | Foreground Service |
| Retry/background scheduling | WorkManager, later |
| Dependency injection | Hilt, optional for MVP |

Recommended MVP stack:

```text
Kotlin
Jetpack Compose
Android WebView
OkHttp
Room
Coroutines + Flow
Foreground Service
```

Use Hilt later if the app grows. Do not over-engineer the first version.

---

## 3. Why Kotlin Instead of Flutter

Flutter is good for UI-heavy apps, dashboards, forms, chat apps, and general cross-platform products.

But Chaya needs deep Android control:

- WebView request interception
- JavaScript bridge
- Cookie syncing
- Custom headers
- Background downloads
- Foreground service notifications
- Media playback and fullscreen handling
- HLS/DASH support through Android Media3

These are easier, cleaner, and more reliable in native Android.

Flutter can technically do many of these through plugins or native bridges, but then you are still writing Android-native code while also managing Flutter complexity.

For Chaya, Kotlin is the better choice.

---

## 4. Recommended Project Structure

Start with a single Android app module.

Do **not** split into too many Gradle modules at the beginning.

Suggested package structure:

```text
com.chaya
├── browser/
│   ├── ChayaWebView.kt
│   ├── ChayaWebViewClient.kt
│   ├── ChayaWebChromeClient.kt
│   └── CookieSync.kt
│
├── detection/
│   ├── MediaInterceptor.kt
│   ├── MediaInspector.kt
│   ├── MediaBridge.kt
│   ├── MediaDetectionCoordinator.kt
│   └── js/
│       └── chaya_media_detector.js
│
├── download/
│   ├── DownloadManager.kt
│   ├── HttpDownloader.kt
│   ├── DownloadService.kt
│   ├── DownloadNotification.kt
│   └── DownloadState.kt
│
├── database/
│   ├── ChayaDatabase.kt
│   ├── DownloadDao.kt
│   └── DownloadEntity.kt
│
├── model/
│   ├── DetectedMedia.kt
│   └── DownloadTask.kt
│
├── ui/
│   ├── browser/
│   ├── downloads/
│   ├── components/
│   └── theme/
│
└── utils/
    ├── FileNameUtils.kt
    ├── MimeTypeUtils.kt
    └── UrlUtils.kt
```

Later, if the project grows, you can split it into modules like:

```text
app/
core/
feature-browser/
feature-detection/
feature-download/
```

But for MVP, one module is faster.

---

## 5. MVP Goal

The first version should do only this:

> Open a website → detect media URL → show download button → download file → show progress.

That is enough for a strong MVP.

Do not start with YouTube support, HLS merging, iframe traversal, or advanced media extraction.

---

## 6. MVP Feature Scope

### Chaya MVP v0.1

Features:

1. WebView browser
2. URL bar
3. Back, forward, refresh
4. Network-level media detection
5. DOM-level media detection
6. Floating media detected button
7. Detected media bottom sheet
8. Basic file downloader using OkHttp
9. Download progress UI
10. Room-based download history
11. Foreground Service for active downloads
12. Basic pause, resume, cancel

Supported first:

```text
.mp4
.webm
.mp3
.m4a
.aac
```

Detect but do not fully support yet:

```text
.m3u8
.mpd
.ts
```

These should be added in v0.2.

---

## 7. Development Phases

## Phase 1 — Android Project Setup

Goal:

> Create the basic Android project foundation.

Tasks:

- Create Kotlin Android project
- Add Jetpack Compose
- Add Material 3
- Add Coroutines
- Add Room
- Add OkHttp
- Set up basic navigation
- Create app theme
- Create main browser screen
- Enable required permissions

Initial dependencies:

```kotlin
implementation("androidx.core:core-ktx:<latest>")
implementation("androidx.activity:activity-compose:<latest>")
implementation("androidx.compose.ui:ui:<latest>")
implementation("androidx.compose.material3:material3:<latest>")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:<latest>")
implementation("androidx.room:room-runtime:<latest>")
implementation("androidx.room:room-ktx:<latest>")
implementation("com.squareup.okhttp3:okhttp:<latest>")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<latest>")
```

---

## Phase 2 — Browser Shell

Goal:

> Build a working mini-browser.

Tasks:

- Add WebView inside Compose using `AndroidView`
- Add URL input bar
- Add back button
- Add forward button
- Add refresh button
- Show page loading progress
- Enable JavaScript
- Enable DOM storage
- Enable media playback

Important WebView settings:

```kotlin
webView.settings.javaScriptEnabled = true
webView.settings.domStorageEnabled = true
webView.settings.mediaPlaybackRequiresUserGesture = false
webView.settings.loadsImagesAutomatically = true
```

---

## Phase 3 — Network-Level Media Detection

Goal:

> Detect direct media URLs flowing through the WebView.

Use:

```kotlin
WebViewClient.shouldInterceptRequest()
```

Detect URLs containing:

```text
.mp4
.webm
.mp3
.m4a
.aac
.m3u8
.mpd
.ts
```

Also detect MIME types where available:

```text
video/*
audio/*
application/vnd.apple.mpegurl
application/x-mpegURL
application/dash+xml
```

Create:

```kotlin
data class DetectedMedia(
    val url: String,
    val pageUrl: String?,
    val mimeType: String?,
    val source: DetectionSource,
    val detectedAt: Long
)
```

Detection source enum:

```kotlin
enum class DetectionSource {
    NETWORK,
    DOM,
    XHR_FETCH,
    MANIFEST
}
```

Deduplicate using normalized URL.

---

## Phase 4 — DOM-Level Media Detection

Goal:

> Detect media already present in the page DOM.

Inject JavaScript after page load.

First version script:

```javascript
(function() {
  function sendMedia(url, type) {
    if (!url) return;
    if (window.ChayaBridge && window.ChayaBridge.onMediaDetected) {
      window.ChayaBridge.onMediaDetected(url, type || "");
    }
  }

  function scan() {
    document.querySelectorAll("video, audio, source").forEach(function(el) {
      sendMedia(el.currentSrc || el.src, el.tagName);
      if (el.querySelectorAll) {
        el.querySelectorAll("source").forEach(function(source) {
          sendMedia(source.src, "source");
        });
      }
    });
  }

  scan();

  const observer = new MutationObserver(scan);
  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["src"]
  });
})();
```

Android bridge:

```kotlin
class MediaBridge(
    private val onDetected: (String, String?) -> Unit
) {
    @JavascriptInterface
    fun onMediaDetected(url: String, type: String?) {
        onDetected(url, type)
    }
}
```

Later, add:

- Shadow DOM traversal
- iframe traversal
- fetch/XHR sniffing
- blob URL handling

---

## Phase 5 — Media Detection UI

Goal:

> Make detected media visible to the user.

UI elements:

- Floating button: “1 media found”
- Bottom sheet with detected media list
- Each item shows:
  - File type
  - URL/domain
  - Estimated filename
  - Source: Network or DOM
  - Download button

Keep the first UI simple.

Avoid fancy animations until the app works reliably.

---

## Phase 6 — Basic OkHttp Downloader

Goal:

> Download normal media files.

Create `HttpDownloader`.

Features:

- Download file by URL
- Show progress
- Save to app-specific storage
- Support cancellation
- Support resume with `Range` header
- Use cookies from WebView
- Use User-Agent from WebView

Basic request idea:

```kotlin
val request = Request.Builder()
    .url(url)
    .header("User-Agent", userAgent)
    .header("Cookie", cookies)
    .build()
```

For resume:

```kotlin
.header("Range", "bytes=$downloadedBytes-")
```

Progress model:

```kotlin
data class DownloadProgress(
    val taskId: Long,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long,
    val state: DownloadState
)
```

---

## Phase 7 — Room Database

Goal:

> Persist downloads and restore them after app restart.

Entity:

```kotlin
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val pageUrl: String?,
    val fileName: String,
    val mimeType: String?,
    val filePath: String?,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val state: DownloadState,
    val createdAt: Long,
    val updatedAt: Long
)
```

Download states:

```kotlin
enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
```

---

## Phase 8 — Foreground Download Service

Goal:

> Keep downloads running when the app is in background.

Use a Foreground Service for active downloads.

Notification should show:

- File name
- Download progress
- Speed
- Pause button
- Cancel button

This is important because Android can kill normal background work.

Use WorkManager later for retries, but not for active long downloads.

---

## Phase 9 — HLS/DASH Support

This is v0.2, not v0.1.

Add support for:

```text
.m3u8
.mpd
.ts segments
```

Use:

```text
Media3 / ExoPlayer DownloadManager
```

Features:

- Detect manifest
- Parse available qualities
- Let user select quality
- Download segments
- Save offline media

Do this after normal MP4/MP3/WebM downloads work.

---

## Phase 10 — Advanced Detection

This is v0.3.

Add:

- Shadow DOM traversal
- iframe traversal
- MutationObserver improvements
- XHR/fetch interception
- blob URL detection
- MediaRecorder-based capture mode

Inspired by Aloha, but implemented in injected JavaScript instead of Chromium C++.

---

## 8. What Not to Build First

Avoid these in the MVP:

| Feature | Reason |
|---|---|
| Chromium fork | Too heavy |
| Aloha Core integration | Complex build system |
| YouTube download support | Policy/legal risk |
| HLS merging | More complex than normal files |
| iOS version | Build Android first |
| Complex Clean Architecture | Slows down MVP |
| Too many Gradle modules | Adds friction early |
| Fancy UI animations | Not useful until core works |

---

## 9. Version Roadmap

## v0.1 — Basic Downloader

- WebView browser
- Network detection
- DOM detection
- Detected media list
- OkHttp file downloads
- Room history
- Foreground Service

## v0.2 — Streaming Support

- HLS detection
- DASH detection
- Media3 DownloadManager
- Quality selector
- Better retry/resume

## v0.3 — Better Detection

- Shadow DOM support
- iframe support
- fetch/XHR sniffer
- better duplicate filtering
- authenticated download improvements

## v0.4 — Polish

- Better UI
- Thumbnails
- In-app media preview
- Download categories
- File manager
- Search and filters

## v0.5 — Distribution Prep

- App icon
- Privacy policy
- Domain blacklist
- Legal safety rules
- Crash reporting
- Play Store or direct APK decision

---

## 10. Important Legal / Policy Note

If you plan to publish on Google Play, avoid promoting YouTube downloading or downloading copyrighted content from restricted platforms.

Possible safer positioning:

> “A browser-based media manager for downloading files you own or have permission to download.”

Add a domain blacklist or restrictions if needed.

---

## 11. Final Recommendation

Build Chaya like this:

```text
Native Android first
Kotlin
Jetpack Compose
WebView
OkHttp downloader
Room database
Foreground Service
Media3 later
```

Do not build a Chromium fork.

Do not start with Flutter.

Do not start with HLS.

First make normal direct-media downloads work perfectly.

Your first success milestone should be:

> Open a page with an MP4 video, detect it, tap download, save it, and show progress.

Once that works, everything else becomes an upgrade.
