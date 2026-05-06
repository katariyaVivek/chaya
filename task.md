# Chaya — Milestone 1 Tasks

## Phase 1: Project Setup
- [ ] Check Android SDK availability on system
- [ ] Scaffold Android project with Gradle, Kotlin DSL
- [ ] Set up multi-module structure (app, core, feature-detection, feature-download, feature-browser)
- [ ] Configure dependencies (Compose, Hilt, OkHttp, Room, Media3)
- [ ] Material 3 dark theme
- [ ] Basic app shell with bottom navigation

## Phase 2: Browser Shell
- [ ] WebView wrapped in Compose (AndroidView)
- [ ] URL bar with navigation controls
- [ ] ChayaWebViewClient with shouldInterceptRequest
- [ ] ChayaWebChromeClient for fullscreen + progress
- [ ] JavaScript + DOM storage enabled

## Phase 3: Media Detection (Network Layer)
- [ ] MediaInterceptor pattern matching (.mp4, .m3u8, etc.)
- [ ] Content-Type header detection
- [ ] Deduplication logic
- [ ] Floating "media detected" badge
- [ ] Detected media list sheet

## Phase 4: Media Detection (DOM Layer)
- [ ] chaya_media_detector.js injection
- [ ] @JavascriptInterface bridge
- [ ] MutationObserver for dynamic media
- [ ] Merge network + DOM results

## Phase 5: Basic Download Engine
- [ ] HttpDownloader with OkHttp (resume, progress)
- [ ] Room database for downloads
- [ ] Downloads list screen

## Phase 6: Download Service
- [ ] Foreground Service with notification
- [ ] Download queue (3 concurrent)
- [ ] Pause/Resume/Cancel
- [ ] Notification progress bar
