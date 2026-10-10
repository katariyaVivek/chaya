# Architecture

How Chaya is put together. One Gradle module (`:app`), package `com.chaya.app`, Kotlin and Jetpack
Compose, with a small Python engine for video sites. Library versions live in
`gradle/libs.versions.toml`; this document avoids repeating them.

## The three ways media gets in

```
 Browsing a page ──► detection/ ──► ranked media sheet ──┐
 A YouTube/Instagram/TikTok/X link ──► platform/ ──► platform sheet ──┼──► download/ ──► Downloads screen
 "Share › Chaya" from another app ──► (same as a link) ──┘
```

1. **A page you browse.** Media flowing through the WebView is detected, ranked and named, and shown
   in one sheet with the page's main video first.
2. **A link to a supported site** (YouTube, Instagram, TikTok, X), from the page you are on, the Paste
   chip, or Share. The on-device engine looks it up and offers one choice per quality.
3. Either way, the choice becomes a download task owned by `DownloadManager`.

## Detection (`detection/`)

Two layers run in parallel and merge by normalized URL:

- **Network:** `MediaInterceptor` watches `shouldInterceptRequest` and returns `null`, so the page
  loads untouched. It recognizes media by extension and manifest MIME type.
- **DOM:** `assets/detection/chaya_media_detector.js` is injected into each page. It scans
  `<video>`/`<audio>`/`<source>`, Shadow DOM and same-origin iframes, hooks fetch/XHR, and watches
  mutations. It also reports page and player hints (title, `og:video`, JSON-LD, player size,
  duration, play state, ad containers) as `PageMeta`.
- **The bridge:** `MediaBridge` is the `@JavascriptInterface`. Every call must carry a 256-bit
  capability issued per navigation and given only to the top-level document, so cross-origin
  iframes cannot call native code. Detection state is scoped to a navigation generation, so a
  late callback from an old page cannot touch the new one.
- **Opt-in check:** `ContentTypeSniffer` verifies extensionless endpoints (`/media/abc123`) only after
  the person taps *Scan more thoroughly*. It sends at most ten same-origin `HEAD` requests and
  never follows a redirect, so session cookies cannot leak to another origin.

What the sheet shows:

- `MediaUrlClassifier` hides stream pieces (`.ts`, `.m4s`, `seg-N`, `init`) and merges byte-range
  requests of one file.
- `MediaRanker` picks the main item, using `AdHosts` and the ad-container hints to fold likely ads
  away.
- `MediaNamer` gives items real names (the page title for the main video), so saved files are
  called `Title (720p).mp4`.

## Video sites (`platform/` and `src/main/python/chaya_engine`)

- `PlatformMatcher` recognizes a link to a single video on a supported site.
- `PlatformEngine` runs **yt-dlp inside the app** through Chaquopy (Python 3.13). It only looks a
  link up and returns the title, poster and formats; Chaya's own downloader fetches the files.
  `chaya_engine.extract` wraps yt-dlp and classifies its errors.
- **Pictures in posts** come from gallery-dl (`chaya_engine.posts`), because yt-dlp only sees
  videos: it fails on a post of photos and keeps only the videos of a carousel. It is asked only in
  those two cases, for Instagram and X, so a single video still gets yt-dlp's quality choices. The
  answer is `LinkState.FoundPost`: every item in the post, each saved as it is.
- **A whole account as one ZIP.** `ProfileMatcher` recognises account pages (instagram.com/name,
  x.com/name). `ProfileLister` asks `chaya_engine.profiles` (gallery-dl) to list the account's
  posts, with the person's sign-in only when they chose it on the sheet; the choice is part of the
  archive's address, `chaya-archive:<site>:<name>[?signin]`. One gallery-dl lookup runs at a time,
  because its settings (including the sign-in) are global.
- `JsSolver` runs yt-dlp's YouTube challenge-solver scripts in QuickJS (quickjs-kt), because a phone
  has no Deno or Node.
- `FormatSelector` turns yt-dlp's format list into one choice per quality. YouTube serves picture
  and sound separately, so a choice is often a picture plus a sound to join. It prefers the
  original-language, plain (non-DRC) audio and SDR video.
- `PlatformLinks` owns lookups: one at a time, late answers for a link already left are dropped,
  answers are kept for 20 minutes (the addresses expire), and failures are explained, not
  retried silently.
- `WebViewSignIn` offers, never applies by itself, the browser's existing sign-in for a site. The
  cookies are written to a temporary file for yt-dlp and deleted right after the lookup.

## Downloads (`download/`)

- `DownloadManager` owns every task and its state machine:
  `QUEUED → DOWNLOADING ⇄ PAUSED → COMPLETED | FAILED | CANCELLED`. Task IDs are shared by memory,
  Room rows and downloader callbacks.
- Files go through the `MediaDownloader` seam; production uses `HttpDownloader` (OkHttp) with
  `Range` resume, forwarded cookie/User-Agent/Referer, `Content-Disposition` names and silent
  cancel. Cancel, pause and delete stop a transfer at any point; `onComplete` never fires after a
  cancel.
- A `DownloadRequest` from the engine carries its own headers and optionally a separate sound. Such
  a task fetches the picture, then the sound (each into `<name>.part`, renamed when whole), then
  `MediaMerger` (`Mp4Merger`, `MediaMuxer`) joins them losslessly. What is left to do is decided
  from which files exist, so pause, resume, a killed app and a retried join all work. Only H.264
  pictures are joined for now.
- Failures are classified by `DownloadError` (network, HTTP status, storage full, unsupported,
  could not combine, …), which decides the message and whether *Retry* is offered. A 50 MB
  free-space check runs before starting.
- **ZIP archives** (`startArchive`) are the fourth kind of task. Like the two-file join, what is
  left is worked out from files in `<name>.items/`: the list (written whole, or not at all), then each
  file not yet fetched, then the ZIP. Pause stops the listing through a stop file and the transfer
  through the downloader; a file the site no longer has (403/404/410) is skipped and named in
  `not-saved.txt`. The card counts files ("40 of 120 files"); those counts are not stored.
- `DownloadService` is the foreground service with progress and Pause/Cancel actions.
- Finished files are copied to public storage (Movies/Music/Pictures/Downloads) through MediaStore.

## Streams (`streaming/`)

- HLS/DASH go through Media3's `DownloadManager`, wrapped by `StreamDownloader`. Content IDs derive
  from task IDs (`chaya_task_<id>`), so pause/resume/delete still work after the process dies.
  Pausing is per download (`setStopReason`); the manager itself is resumed at start-up, because
  without a `DownloadService` it starts paused.
- `ManifestHelper` lists renditions for the quality picker: one entry per rendition, best
  preselected, sizes estimated when the duration is known.
- **Stream downloads live in Media3's cache, not as files.** They play in the in-app player
  (`ui/player/`), which reads the same cache. Saving them as real MP4 files is on the roadmap.

## Storage (`database/`)

Room, currently schema version 5. Every version change has an explicit migration; destructive
migration is never used. Progress is kept in memory and only state changes are written.

## Diagnostics (`diagnostics/`)

All on the device: an event log (500-entry ring plus rotating files, URLs stripped of query and
fragment before they are logged), a crash reporter that writes a file and chains to the default
handler, and opt-in local insights. Nothing is uploaded; a report leaves the phone only through
Share. Reached from the Downloads screen's menu.

## UI (`ui/`, `browser/`, `downloads/`)

- `browser/`: one WebView kept alive across navigation (`RetainedWebViewCallbacks` rebinds its
  callbacks atomically). `BrowserChrome` is the address bar, which collapses to the site name on a
  page. The floating pill and the sheets (`ui/components/`) sit on top.
- `downloads/`: cards per state, All/Active/Done filters, swipe to delete with Undo.
- `ui/theme/`: the design tokens from [`../DESIGN.md`](../DESIGN.md), and `ThemeSettings`, the
  person's choice of light, dark or the same as the phone (kept in SharedPreferences).
- `SharedLinks` holds a link shared from another app until the browser picks it up.

## Tests

| Where | What | Runs |
|---|---|---|
| `app/src/test/java` | JVM and Robolectric tests: state machine, MockWebServer transfers, Room migrations, Media3 stream pipeline, Compose UI | every PR (`build.yml`) |
| `app/src/test/python` | pytest for `chaya_engine`, against the pinned yt-dlp | PRs touching the engine (`engine.yml`) |
| `app/src/androidTest` | On a device: Python/yt-dlp and QuickJS, the real `MediaMuxer` join, the whole two-file save | `ui-check` label or manual (`device-tests.yml`); also *Run workflow* on `build.yml` |
| `.github/scripts/walkthrough.py` | Drives the debug app in an emulator and saves screenshots, screen text and logs | `ui-check` label or manual (`emulator-check.yml`) |
