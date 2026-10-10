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

## Ad blocking (`adblock/`)

Android's WebView cannot run extensions, so Chaya blocks with uBlock Origin's base lists itself.

- `adblock/engine/` is plain Kotlin with no Android in it. `FilterEngine` reads the Adblock Plus
  format of EasyList and EasyPrivacy. Bare `||host^` rules (nine in ten) go in a set, looked up by
  the request's host and its parents. The other rules are indexed by one whole word of their
  pattern and tried only on addresses containing it. Exceptions, `$important`, `$third-party`
  (by registered name: OkHttp's public suffix list), `$domain`, kinds of request and page-wide
  exceptions (`$document`, `$elemhide`, `$generichide`) are honoured. Regular expressions,
  scriptlets, procedural selectors and rewriting options (`csp`, `removeparam`...) are skipped,
  never half-applied. A request whose kind cannot be told only meets rules that name no kind.
- `CosmeticRules` turns element-hiding rules into CSS. Rules for the site, and the few rules for
  every site that have no class or id to look up, go in the page script. The rest (about 13,000
  `.ad-banner`-style rules) are handed out by the classes and ids the page actually has: the script
  collects them as elements appear and asks `ChayaCosmetic` (a `@JavascriptInterface` that hands
  out only selectors from the public lists, so it needs no capability).
- `AdBlocker` (one per app) holds the engine and `AdBlockSettings` (on/off, allowed sites).
  `AdBlockSession` (one per WebView) knows the page shown, answers `shouldInterceptRequest` with an
  empty 204 for a blocked request (before `MediaInterceptor`, so an ad is never offered for
  download), refuses ad pop-ups and counts what it blocked. Pages themselves are never blocked.
- `FilterLists` reads the lists shipped in `assets/adblock/`, or fresher copies fetched from
  easylist.to once a week. All lists are fetched before any is replaced, and a copy that does not
  look like a list is thrown away. The lists are read in the background when the browser first
  shows (about a second); pages load unblocked until then.

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

### How the engine stays current

The app carries pinned copies of yt-dlp, yt-dlp-ejs and gallery-dl (`enginePackages` in
`app/build.gradle.kts`, also passed to the app as `BuildConfig.ENGINE_PACKAGES`). Sites change
faster than APKs ship, so the app fetches newer releases itself.

- **Checking.** `EngineUpdater` asks PyPI's JSON API (`/pypi/<name>/json`, with `If-None-Match`, so
  an unchanged answer is a 304) about once a day, shortly after the app is opened
  (`ChayaApplication.checkEngineSoon`, from `MainActivity`). A
  newer yt-dlp brings the yt-dlp-ejs its metadata names (`Requires-Dist: yt-dlp-ejs==…`), because
  yt-dlp checks its solver scripts against that release. `PyPackaging.kt` reads versions,
  requirements and markers: a release whose requirements the bundled libraries do not meet (asked
  of Python through `chaya_engine.paths.installed_version`), or that needs another Python, is
  refused and remembered.
- **Fetching.** Only the `py3-none-any` wheel, only from files.pythonhosted.org, checked against
  PyPI's SHA-256, opened to see that it holds the package, the version it should and no native code.
  `chaya_engine.paths.compile_wheel` then compiles it into a zip of `.pyc` files
  (`filesDir/engine/<name>-<version>.zip`), because a wheel's sources would be compiled again at
  every start (about a second on a desktop, several on a phone).
- **Choosing.** `EngineSets` keeps the sets and their state in `filesDir/engine/state.json`.
  When Python starts (`PlatformEngine.python()`), before anything imports yt-dlp, it puts the
  newest trusted set at the front of `sys.path` (`chaya_engine.paths.use`). Imported modules are
  never swapped under a running engine, so a set fetched today is used from the next start.
- **Trust.** A new set is checked on the first start that uses it: `selftest.status` must report its
  versions, the solver registered, and gallery-dl's post links. If it does not, `paths.drop` takes
  it off `sys.path` and forgets its modules, the set is skipped from then on, and the last good set
  or the app's copy is used instead. A check the app died during counts as failed. A set whose
  engine fails twice in a row (an error escaping `chaya_engine`, not a link that cannot be used) is
  skipped from the next start. The two newest good sets are kept, so there is a fallback besides
  the app's copy.
- **Diagnostics** shows the versions in use, a set waiting for the next start, when PyPI was last
  asked and what came of it, and the switch that turns updates off (the app's copy is then used
  from the next start).

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
  `not-saved.txt`. A file that fails on the way is asked for again after 3 s, 15 s and a minute; a
  connection or server still failing after that fails the archive for a later retry, and any other
  file still failing is skipped like a gone one, so one bad file never holds up the rest. The card
  counts files ("40 of 120 files"); those counts are not stored.
- `DownloadService` is the foreground service with progress and Pause/Cancel actions.
- Finished files are copied to public storage (Movies/Music/Pictures/Downloads) through MediaStore.

## Streams (`streaming/`)

- HLS/DASH go through Media3's `DownloadManager`, wrapped by `StreamDownloader`. Content IDs derive
  from task IDs (`chaya_task_<id>`), so pause/resume/delete still work after the process dies.
  Pausing is per download (`setStopReason`); the manager itself is resumed at start-up, because
  without a `DownloadService` it starts paused.
- `ManifestHelper` lists renditions for the quality picker: one entry per rendition, best
  preselected, sizes estimated when the duration is known.
- **Streams are saved as MP4 files.** Media3 downloads a stream's pieces into its cache. When the
  last piece is in, `DownloadManager` keeps the task downloading for one more phase: *Saving as
  MP4*. `Media3StreamExporter` runs Media3 Transformer over the stream's own download request (so
  the downloaded renditions are the ones read), through a data source that reads only the cache:
  a missing piece fails the export instead of being fetched. Transformer copies the samples as
  they are when the MP4 container can take them, and re-encodes only otherwise
  (`ExportResult`'s conversion process says which). The file is written beside its final name
  (`.mp4.saving`), checked (the tracks Transformer reported are there, the duration matches),
  renamed, and only then is the cached copy removed; the task then completes like any file and
  goes to Movies (Music for sound only). Free space for both copies is checked first.
- **Whatever goes wrong, the cached copy stays.** A failed or stopped export (pause, cancel) leaves
  the task completed in the cache, where it plays in the in-app player (`ui/player/`), with the
  reason on its card and *Save as MP4* in its menu; streams downloaded before this have the same.
  The saving phase is kept in memory, like an archive's counts: an app killed mid-export brings the
  task back paused, and resuming finds every piece cached and saves it again.

## Storage (`database/`)

Room, currently schema version 5. Every version change has an explicit migration; destructive
migration is never used. Progress is kept in memory and only state changes are written.

## Diagnostics (`diagnostics/`)

All on the device: an event log (500-entry ring plus rotating files, URLs stripped of query and
fragment before they are logged), a crash reporter that writes a file and chains to the default
handler, and opt-in local insights. Nothing is uploaded; a report leaves the phone only through
Share. Reached from the Downloads screen's menu.

## UI (`ui/`, `browser/`, `downloads/`)

- `browser/`: tabs, each a WebView kept alive across navigation with its own interceptor, bridge
  and ad-block session (`BrowserTabs`, `BrowserTab`). Only the tab shown reports to the browser's
  state (`RetainedWebViewCallbacks` rebinds callbacks atomically); a tab not shown keeps its own page
  state as it loads (`backgroundCallbacks`), and `BrowserViewModel.switchTab` swaps that state in
  and out. A link a page opens in a new window, on a tap, opens in a new tab. `BrowserChrome` is the address bar, which collapses to the site name on a
  page, with the ad blocker's shield and count. The floating pill and the sheets (`ui/components/`)
  sit on top.
- `downloads/`: cards per state, All/Active/Done filters, swipe to delete with Undo.
- `ui/theme/`: the design tokens from [`../DESIGN.md`](../DESIGN.md), and `ThemeSettings`, the
  person's choice of light, dark or the same as the phone (kept in SharedPreferences).
- `SharedLinks` holds a link shared from another app until the browser picks it up.

## Tests

| Where | What | Runs |
|---|---|---|
| `app/src/test/java` | JVM and Robolectric tests: state machine, MockWebServer transfers, Room migrations, Media3 stream pipeline, Compose UI | every PR (`build.yml`) |
| `app/src/test/python` | pytest for `chaya_engine`, against the pinned yt-dlp | PRs touching the engine (`engine.yml`) |
| `app/src/androidTest` | On a device: Python/yt-dlp and QuickJS, an engine update compiled and imported (and a broken one falling back), the real `MediaMuxer` join, the whole two-file save, ad blocking in a real WebView | `ui-check` label or manual (`device-tests.yml`); also *Run workflow* on `build.yml` |
| `.github/scripts/walkthrough.py` | Drives the debug app in an emulator and saves screenshots, screen text and logs | `ui-check` label or manual (`emulator-check.yml`) |
