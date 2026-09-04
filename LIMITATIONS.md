# What Chaya does not do (scope boundaries)

Chaya detects and downloads **direct media files** (MP4, WebM, MP3, …) and
**HLS/DASH streams** (`.m3u8` / `.mpd`) found while you browse. The
boundaries below are deliberate product and technical scope decisions, not
open bugs — please don't file them as such.

## MediaSource / blob-only players

Many video sites feed playback exclusively through
`MediaSource.appendBuffer()` driven by JavaScript, with no media file or
stream manifest ever visible on the network. Chaya does not attempt to
intercept that path: it would require either a custom WebView-embedded
media pipeline or Chromium-level hooks, both far outside this project's
scope.

## YouTube, Netflix, and other DRM / ToS-restricted services

Major platforms using the above techniques (YouTube foremost) are
additionally out of scope on policy grounds: downloading from them
typically violates their terms of service, and DRM-protected content
(Encrypted Media Extensions / Widevine) cannot and must not be captured
by a download manager. Chaya is a browser-based media manager for files
you own or have permission to download.

## What Chaya *does* cover

- Direct audio/video files behind ordinary links (`shouldInterceptRequest`
  network sniffing + DOM `<video>`/`<audio>` scanning).
- Extensionless endpoints whose server truthfully reports a media
  `Content-Type` (opt-in “Scan more thoroughly”, redirect-free `HEAD`
  verification, same-origin only).
- HLS/DASH manifests with a per-track quality picker.
- Resume via `Range`, pause/cancel/retry with a classified error taxonomy,
  and a 50 MB free-space pre-flight guard.
