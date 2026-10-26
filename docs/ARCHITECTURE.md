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
2. **A link to a supported site** (YouTube, Instagram, TikTok, X, Pinterest), from the page you are on, the Paste
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
- **Frames from other servers:** `assets/detection/chaya_frame_agent.js` runs at the start of every
  embedded frame's document (`WebViewCompat.addDocumentStartJavaScript`, origin rule `*`), never in
  the page itself. It reports video/audio elements and its player's fetch/XHR answers that are media
  by extension, by `Content-Type`, or by a body starting with `#EXTM3U` or `<MPD`. It reports them to
  the page's detector by `postMessage`, and resends them when the detector asks. The detector relays
  them to `MediaBridge.onFrameMedia` with its capability. The bridge accepts an address only if
  `MediaInterceptor.wasRequested` saw the browser ask for it on this page. The frame's address
  becomes `DetectedMedia.refererUrl`, then the download task's page, so its requests name the player
  as their referrer.
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
- **Pictures in posts.** yt-dlp only sees videos: it fails on a post of photos and keeps only the
  videos of a carousel. On Instagram it still reads the whole post without a sign-in, so the engine
  runs it with `_KeepingInstagramIE`, which keeps that post, and `posts.instagram_post` turns its
  pictures and videos into items. gallery-dl (`chaya_engine.posts`) lists the rest: X posts, and an
  Instagram post yt-dlp could not read (gallery-dl needs a sign-in there). Both are asked only when
  a post holds pictures, so a single video still gets yt-dlp's quality choices. The answer is
  `LinkState.FoundPost`: every item in the post, each saved as it is.
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
- YouTube's files (`googlevideo.com`) are fetched in 10 MB pieces, three at a time
  (`HttpDownloader.PIECES_AT_ONCE`), because one request goes at about the speed of playback. Each
  piece is written at its place in the file, and `PieceLog` keeps `<file>.pieces` listing the whole
  ones, written before the first byte. A resume asks only for the missing pieces; a file from
  before, fetched one piece after another, keeps the whole pieces at its start. A piece answered
  with anything but its range makes the rest go one at a time. A piece that fails then (a second
  403, say) fails the download, and the record stays for the retry. Every piece's call is
  registered, so cancel reaches them all.
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
  the downloaded renditions are the ones read), through a data source that reads only the cache,
  and only once Media3's index reports the download complete: a missing piece fails the export
  instead of being fetched. Transformer copies the samples as
  they are when the MP4 container can take them, and re-encodes only otherwise
  (`ExportResult`'s conversion process says which). It writes with Media3's `InAppMuxer`, not
  Android's `MediaMuxer`, which aborted the whole app (a native `SIGABRT` in `MPEG4Writer`) on
  a real HLS stream; a bad stream now fails its export instead. The file is written beside its final name
  (`.mp4.saving`), checked (the tracks Transformer reported are there, the duration matches),
  renamed, and only then is the cached copy removed; the task then completes like any file and
  goes to Movies (Music for sound only). Free space for both copies is checked first.
- **Whatever goes wrong, the cached copy stays.** A failed or stopped export (pause, cancel) leaves
  the task completed in the cache, where it plays in the in-app player (`ui/player/`), with the
  reason on its card and *Save as MP4* in its menu; streams downloaded before this have the same.
  The saving phase is kept in memory, like an archive's counts: an app killed mid-export brings the
  task back paused, and resuming finds every piece cached and saves it again.

## Storage (`database/`)

Room, currently schema version 7. Every version change has an explicit migration; destructive
migration is never used. Progress is kept in memory and only state changes are written.

- `downloads`: one row per download. Since v7, `group_key` (the post's address and title) ties the
  files saved from one post together, so the library shows them as one tile; older rows stay
  ungrouped.
- `history` and `bookmarks` (v6, `BrowsingEntities.kt`): one row per address. A page that finishes
  loading in any tab updates its history row (title, time, visit count) in one transaction
  (`HistoryDao.recordVisit`). `history/BrowsingRecord` wraps both: it records only http(s) pages
  and only while the *Save history* switch is on (SharedPreferences), stars and unstars, clears a
  span, and builds the address bar's suggestions (bookmarks first, then history ranked by visits
  divided by one plus the days since the last one).

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
  and out. A link a page opens in a new window, on a tap, opens in a new tab.
- **Tabs come back.** `TabStore` keeps them in `filesDir/tabs/`: an index (order, address, title, the
  tab shown) and per tab its WebView history (`WebView.saveState`, marshalled by `WebViewStates`),
  a picture of the page (`TabPictures`: a third of its size, WebP at about 70%) and the site's icon.
  It is written, one file at a time off the main thread, when a tab is left, when its page
  finishes, and when the app goes to the background. At start every saved tab comes back
  *discarded*, address and history only; the tab shown gets a WebView with its history restored,
  the others get one when first shown. At most four WebViews live at once
  (`BrowserTabs.toDiscard`): the one shown least recently is discarded, its history kept on disk,
  so up to 50 tabs can be open.
- **A page's renderer can stop** (it crashes, or Android takes its memory back). Every WebView,
  including a popup's, handles `onRenderProcessGone`, so only that page goes. The tab shown gets a
  new WebView and its page again, unless it stopped within the last ten seconds
  (`RendererLoss`); then it waits for Reload. Other live tabs become discarded and load again when
  shown.
- `ui/components/TabGrid` is the full-screen tab switcher: a new-tab square, the tab count, ⋮ (*Close
  all tabs*), search by title and address, and two columns of cards; swiping or × closes with Undo.
- `BrowserChrome` is the address bar, which collapses to the site name on a
  page, with the ad blocker's shield and count. The floating pill and the sheets (`ui/components/`)
  sit on top.
- `downloads/`: the list (cards per state, All/Active/Done filters, swipe to delete with Undo, Share
  in a finished file's ⋮) or the grid, switched from the top bar and remembered. `DownloadsContent`
  is the screen without its view model, so tests drive it directly.
- `library/`: the grid of finished downloads. `libraryItems` puts a post's files (same `groupKey`)
  on one tile, and `LibraryFilter` narrows by kind, site and title in both the list and the grid.
  `PictureViewer` shows pictures full screen with pinch and double-tap zoom and a pager. A post
  swipes inside itself; a lone picture swipes among the other lone pictures. Videos still open as
  before. A long press chooses several to share or delete. `ZipContents` lists an account's ZIP with
  `java.util.zip` and takes out one file at a time into `cache/library/zip/<id>/`, guarding against
  names that climb out of that folder. `CachedLibraryFiles` keeps video frames (from
  `MediaMetadataRetriever`, a second in, 480 px wide) in `cache/library/frames/`. Both are deleted
  with their download. `LibraryIntents` builds the share sheet and *Save to phone* (MediaStore,
  Android 10+).
- `ui/theme/`: the design tokens from [`../DESIGN.md`](../DESIGN.md), and `ThemeSettings`, the
  person's choice of light, dark or the same as the phone (kept in SharedPreferences).
- `SharedLinks` holds a link shared from another app until the browser picks it up;
  `history/PagesToOpen` does the same for a page picked on the History or Bookmarks screen
  (`history/HistoryScreen`, `history/BookmarksScreen`), which the navigation host answers by going
  back to the browser.

## Tests

| Where | What | Runs |
|---|---|---|
| `app/src/test/java` | JVM and Robolectric tests: state machine, MockWebServer transfers, Room migrations, Media3 stream pipeline, Compose UI | every PR (`build.yml`) |
| `app/src/test/python` | pytest for `chaya_engine`, against the pinned yt-dlp | PRs touching the engine (`engine.yml`) |
| `app/src/androidTest` | On a device: Python/yt-dlp and QuickJS, an engine update compiled and imported (and a broken one falling back), the real `MediaMuxer` join, the whole two-file save, ad blocking in a real WebView | `ui-check` label or manual (`device-tests.yml`); also *Run workflow* on `build.yml` |
| `.github/scripts/walkthrough.py` | Drives the debug app in an emulator and saves screenshots, screen text and logs | `ui-check` label or manual (`emulator-check.yml`) |

## Chaya for Windows (`desktop/`)

A Tauri 2 app, early: the window, its themes, recognising links, looking them up, and downloading:
plain files, qualities whose picture and sound are joined by a cut-down ffmpeg, HLS and DASH streams, and
a built-in browser. [`../desktop/README.md`](../desktop/README.md) has the layout.

- `desktop/core` (`chaya-core`) is plain Rust with no window in it, the desktop's equivalent of the
  phone's Android-free Kotlin. `links` recognises links with the phone's rules and test table.
- **Looking up a link is the phone's engine.** `chaya_engine` runs as a program (`chaya_engine/cli.py`)
  in a Python the installer carries, with the same pinned packages, and QuickJS-ng (`qjs.exe`) for
  YouTube's challenges: off the phone, `jsc_provider` stands aside and yt-dlp's own QuickJS provider
  runs. `desktop/engine/prepare.py` puts the engine together from pinned, checksummed downloads.
  `chaya-core` reads its answer (`media`, ported from `PlatformMedia`) and picks one choice per quality
  (`formats`, ported from `FormatSelector`, with its tests and the same saved YouTube answer).
- **Downloads** (`chaya-core`'s `download`) follow the phone's design in Rust: `http` fetches into a
  `.part` file with `Range` resume and the site's headers (reqwest, with Windows' own TLS); `manager`
  owns every task, `Queued → Downloading ⇄ Paused → Completed | Failed`, three at once, kept in
  `downloads.json`; `error` sorts failures as `DownloadError` does. Retry looks the link up again for
  a fresh address. Both are tested against a local test server (`download/testserver.rs`). What is saved
  from a page carries the browser's sign-in as the phone's `sessionHeaders` does: a `session` source gets
  the cookies the WebView2 store holds for its address (and the browser's user agent) at each start,
  never stored; `chaya-core`'s `session` also writes them as a cookie file for yt-dlp's stream lookups, and
  for a link's lookup when the person taps *Use my sign-in* (the phone's `WebViewSignIn`). The engine runs
  with yt-dlp's plugins off.
- **Joining** (`download/join.rs`) runs the ffmpeg in the engine folder, built by
  `desktop/engine/build_ffmpeg.sh` with no encoders (copying only), where the phone uses `MediaMuxer` for
  H.264 only. **Pieces** (`download/pieces.rs`) port the phone's 10 MB, three-at-a-time fetching of
  YouTube's files and its log of whole pieces.
- **Streams** (`download/stream.rs`) are fetched by the app itself, not by Media3 as on the phone nor by
  ffmpeg. An HLS playlist is read (RFC 8216: byte ranges, initialization sections, AES-128 keys;
  SAMPLE-AES and DRM key systems refused); a DASH stream's pieces are the ones yt-dlp listed from its
  manifest, which the engine passes on when asked (`--fragments`; yt-dlp leaves DRM formats out). The
  pieces come three at a time through the same reqwest
  client (the PC's proxy, the site's headers), and are written in order into one file, with a count of
  the pieces written so a resume asks only for the rest. ffmpeg then copies that file into an MP4, so it
  needs no network of its own. `formats` offers a stream when no file reaches its quality, and pairs a
  picture-only stream with sound as it pairs files.
- **The built-in browser** (`desktop/app/src-tauri/src/browser.rs`, rules in `chaya-core`'s `browse`)
  shows one page in a webview of its own over the window, where the window leaves room for it. Where
  the phone gives the page's detector a capability per navigation, the desktop gives a page nothing:
  the app's commands are granted to the window's own webview only (`webviews: ["main"]`), and a page is
  never local. Pages go only to http(s); windows they open are held back; a media file a page downloads
  goes to Chaya's list. A page's media is found by `detector.js` in every frame (the phone's detector
  and frame agent in one), which the app reads by evaluating a call under a random name instead of
  handing the page a bridge; `chaya-core`'s `found` ports the classifier, the ad hosts and the core of
  the ranker and namer. A stream found on a page is looked up by the engine for its qualities, with the
  page as referrer (`--referer`); a video site's page is looked up as its link is.
- **Ad blocking** (`chaya-core`'s `adblock`, `desktop/app/src-tauri/src/ads.rs`) is the phone's
  `adblock/` ported to Rust, with its tests and the same lists. On Windows the page's WebView2 hands
  each request to the app (`WebResourceRequested`), where the phone uses `shouldInterceptRequest`.
  Where the phone's page script asks `ChayaCosmetic` for the CSS of the classes and ids it found, the
  desktop's `hider.js` keeps them until the app asks, under a random name, and the app evaluates the CSS
  back into the document: again no bridge.
- `desktop/app/src-tauri` hands `chaya-core` to the window as commands (`recognise_link`, `look_up`,
  `download` and the download controls) and sends it `download` events. The window
  may call only those and set its title bar's theme (`capabilities/default.json`). Its content
  security policy loads nothing from outside the app except a looked-up video's poster.
- `desktop/app/ui` is the window, drawn with [`desktop/design`](../desktop/design/README.md).
  `assemble-ui.mjs` copies both into `desktop/app/dist`, which Tauri packs into the app.
