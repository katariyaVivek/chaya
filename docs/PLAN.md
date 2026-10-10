# Working plan: the next features

A temporary plan, written at the end of one session so the next can pick it up. When a part ships,
tick it here and add its row to [`ROADMAP.md`](ROADMAP.md). When everything here has shipped, move
this file to `docs/history/`.

## Resuming in a new session

Paste something like this into a new session:

> Read `CLAUDE.md` and `docs/PLAN.md`, then start on the next unticked part of the plan. Same rules
> as before: one PR per part, merge each yourself once CI is green, then send me the debug APK
> link from main.

## Where things stand (10 Oct 2026)

Merged on `main` since v0.4.0, each through a PR that was green before merging:

| PR | What |
|---|---|
| #25 | New icon (an original and its shadow-copy) and Light / Dark / Same as phone |
| #26 | YouTube files fetched in 10 MB ranges, so downloads are not held to playback speed |
| #27 | Every picture and video in an Instagram or X post (gallery-dl) |
| #28, #29 | An Instagram or X account's posts as one ZIP; a file that fails is retried, then left out |
| #30 | Built-in ad blocker (EasyList + EasyPrivacy, weekly list refresh, per-site switch) |
| #31 | A finished download's notification comes once |
| #32 | Tabs (up to 10, bottom sheet list), plus a fix so a page's first ads are blocked too |

Loose ends the owner handles from a PC: delete the old merged branches (the session's git proxy
cannot delete branches), and decide whether to push the `v0.4.0` tag. While testing a profile ZIP
that stopped at 29 of 114 files, the owner was asked what the row said ("Failed · …" or "Stopped")
and for the Diagnostics lines. #29 should have fixed the likely causes; if it happens again, start
from those lines.

## How work has gone (keep doing this)

- **One PR per part**, opened from the session's designated branch, merged by the agent once
  every check is green ("merge each when green"). After a merge, restart the designated branch from
  `main` before the next part. Then send the owner the `chaya-debug-apk` link from main's *Build
  APK* run.
- **Add the `ui-check` label** to any PR that touches the browser, downloads or anything only a
  device can show. It runs `device-tests.yml` (instrumented tests) and `emulator-check.yml` (the
  walkthrough). Both have caught real bugs: the ad blocker's first-ads race in #32, and a lost
  download in #28.
- **Codex review** is out of quota; don't wait for it. Its "usage limits" comments need no reply.
- **Local builds do not work in the cloud container.** The proxy blocks `dl.google.com`, so the
  Android Gradle plugin cannot be fetched; CI is the build. Pure-Kotlin code (like
  `adblock/engine/`) can be compiled and tested locally with a scratch Kotlin/JVM Gradle project
  that uses the cached Kotlin 2.1.0 plugin and points its `srcDir` at the repo's sources. Maven
  Central answers 429 now and then; retry after a pause. Keep such code free of Android imports so
  this stays possible.
- **Room is at version 5.** Every part below that adds a table or column bumps it by one, with a
  `Migration` and a migration test. Merge them in this file's order so the version numbers here
  hold.

## The plan, in order

The owner's priorities: parts 1 and 2 first, then the rest. Part 6 (the downloads library) is the
biggest UI change, so it goes last, once everything it shows exists.

### 1. yt-dlp keeps itself current ☐

**Why.** YouTube, Instagram, TikTok and X change often, and yt-dlp ships fixes within days. The
pinned copy (`app/build.gradle.kts`: `yt-dlp==2026.8.19`, `yt-dlp-ejs==0.8.0`,
`gallery-dl==1.32.16`) only changes with a new APK, and Chaya is sideloaded, so nothing updates it.

**Shape.**
- About once a day, at most, the app asks PyPI's JSON API (`https://pypi.org/pypi/<name>/json`)
  for the latest `yt-dlp`, `yt-dlp-ejs` and `gallery-dl`. It downloads the pure-Python wheels, which
  are zips and importable as they are, into `filesDir/engine/<name>-<version>.whl`.
- Each wheel is checked against the SHA-256 PyPI lists for it. Keep yt-dlp and yt-dlp-ejs as a
  pair: yt-dlp checks its solver scripts against the ejs release it was built with, so take the
  ejs that the new yt-dlp declares in its metadata (`Requires-Dist`).
- **Activation happens at the next app start**, never mid-run: imported Python modules cannot be
  swapped safely. Before the first `import yt_dlp`, `chaya_engine` puts the newest *trusted*
  wheels at the front of `sys.path`.
- **Trust.** A new set becomes trusted only after `selftest.status` passes with it, in a separate
  check on the first start that uses it. If the self-test fails, or the engine crashes twice in a
  row with the new set, Chaya goes back to the bundled copy and skips that version.
- Keep the last good set as well as the new one, so there is always a fallback besides the
  bundled copy.
- The Diagnostics screen shows the versions in use and when they were checked. A setting turns
  updates off.

**Files.** New `platform/EngineUpdater.kt` (Kotlin: fetch, verify, store, choose, using OkHttp
and MockWebServer-friendly base URLs). `chaya_engine/__init__.py` or a new `chaya_engine/paths.py`
(sys.path set-up). `PlatformEngine.kt` (call the set-up before the first import).
`selftest.py` (report versions).

**Tests.**
- JVM, against MockWebServer: an update is due or not; the hash is checked; a corrupt wheel is
  refused; ejs follows yt-dlp's requirement; a failure keeps the current set.
- pytest: `sys.path` ordering, and the fallback when the newest set is marked bad.
- androidTest (`ui-check`): a wheel served locally and newer than the bundled copy is imported on
  the next start, and a broken one falls back.

**Docs.** `PRIVACY_POLICY.md` gains pypi.org and files.pythonhosted.org (daily, nothing about the
person). `LIMITATIONS.md` says updates are of pure-Python packages only. `ARCHITECTURE.md` gets a
section on how the engine stays current.

**Watch out.**
- The wheels must stay pure Python; gallery-dl's and yt-dlp's are. `requests`, `urllib3` and the
  other packages Chaquopy bundles stay as they are.
- If a new yt-dlp needs a newer dependency than is bundled, refuse that version: check its
  `Requires-Dist` against what is bundled.

### 2. Streams saved as real MP4 files ☐

**Why.** HLS/DASH downloads found in pages live in Media3's cache (`streaming/StreamDownloader.kt`,
a `SimpleCache`). They play in the app but cannot be shared, seen in the gallery or opened
elsewhere. This is first under "Next" in `ROADMAP.md`.

**Shape.**
- When a stream download completes, Chaya exports it to an MP4 with **Media3 Transformer**. Add
  `media3-transformer` at the same version as the other Media3 modules, 1.5.1.
- The export reads from the cache through `CacheDataSource`, as playback already does
  (`playbackDataSourceFactory`). It transmuxes without re-encoding when the codecs allow it
  (H.264/HEVC with AAC, which covers almost all HLS). It re-encodes only as a fallback, and says so.
- Once done, the file goes to Movies through MediaStore like any other download, and the task row
  gets a file path. The cached copy is then removed, after the MP4 is verified (duration and
  tracks), so storage is not used twice.
- **States.** "Saving as MP4…" shows as a phase of the download, with progress from
  `Transformer.getProgress`. An export that fails keeps the cached copy, which still plays, and
  offers Retry.
- Old stream downloads already in the cache get a *Save as MP4* action on their card.

**Files.** New `streaming/StreamExporter.kt`; `DownloadManager` (the export phase after
`onStreamCompleted`); the cards in `downloads/DownloadsScreen.kt`. If export progress has to be
stored, a nullable column becomes Room v6 (prefer keeping it in memory, as archives do, and
working it out again from the files).

**Tests.** Robolectric cannot run Transformer, so the export itself gets an androidTest. Make a
short HLS stream on the device, as `TestMedia` does for the join, cache it, export it, and check
tracks, duration and sample counts. JVM tests cover the state machine: completed → exporting →
saved, a failed export keeping the cached copy, and the retry.

**Watch out.**
- Transformer needs the whole stream in the cache. A partly cached stream must not export.
- Large exports need storage for both copies for a while; check free space first, as two-file
  downloads already do.

### 3. Tabs come back, in a Chrome-style tab grid ☐

**Why.** Tabs (#32) last only while the app runs, and the list is a small bottom sheet. The owner
wants tabs restored after Android closes the app, and a full tab grid like Chrome's. The owner
shared a screenshot of Chrome's grid as the reference.

**The grid (from the reference).** A full-screen tab switcher, not a sheet, opened from the
bottom bar's Tabs button.
- **Top row.** A filled tonal square **+** on the left (new tab). In the middle, a segmented
  toggle: the tab-count square (the same mark as the bottom bar) and a grid icon for tab groups.
  Groups can come later; show only the count segment until then. A **⋮** menu on the right:
  *Close all tabs*, and *Select tabs* later.
- **Search your tabs.** A pill field under the top row that filters cards by title and address
  as you type.
- **Cards.** Two columns. Each card has a header row (the site's favicon, the title on one line
  with ellipsis, and **×** to close) and, below it, a thumbnail of the page as last seen. Corners
  are about 24dp, and cards are about 3:4.
- **The tab shown** is filled with `primaryContainer` (the header turns the accent colour, as
  the reference's last card does). Other cards are `surfaceContainerHigh`.
- **Gestures.** Tap opens a tab. Swiping a card sideways closes it, with *Undo* in a snackbar.
  Long-press drag to reorder is a stretch goal.
- **Motion.** The shown tab's card scales into the page when opened (ChayaMotion springs). Follow
  `DESIGN.md` tokens throughout.

**Restoring.**
- **What is saved.** Each tab's order, address, title, favicon and thumbnail, plus its back and
  forward history, saved with `WebView.saveState(Bundle)`. The Bundle is marshalled to a file per
  tab; if it cannot be read back after an update, the tab opens at its address only. Store this
  in `filesDir/tabs/`: a small JSON index, plus `<id>.state` and `<id>.webp` per tab.
- **When it is saved.** When a tab is left, when its page finishes, and when the app goes to the
  background (`onStop`).
- **On start,** every tab comes back as a card. Only the tab shown gets a live WebView. The
  others are **discarded**: address and saved state only, given a WebView when first shown, as
  Chrome does.
- **Discarding.** At most 4 live WebViews at once; the one used least recently is discarded when
  a fifth is needed. This lets the limit rise from 10 to about 50 tabs.
- **Thumbnails.** Captured when a tab is left: the WebView drawn to a bitmap at about a third of
  its size, saved as WebP at about 70% quality. Favicons come from `WebChromeClient.onReceivedIcon`.

**Files.** `browser/BrowserTabs.kt` (discarded tabs; the least recently used of the live ones).
New `browser/TabStore.kt` (files on disk; plain Kotlin where possible, so it can be tested on the
JVM). New `ui/components/TabGrid.kt`, replacing `TabsSheet.kt`. `BrowserScreen.kt` (create a
WebView when a discarded tab is shown; capture the thumbnail on switch).

**Tests.**
- JVM: the store round-trips the index; a corrupt or missing state falls back to the address;
  the oldest live tab is chosen to discard.
- Robolectric Compose: the grid searches, opens, closes with Undo, shows the tab shown, and adds
  a new tab.
- androidTest (`ui-check`): `saveState`/`restoreState` keep the back history across a recreated
  WebView.
- The walkthrough: open two tabs, kill the app, reopen, and both are there.

**Privacy.** Tabs and thumbnails stay on the phone. *Close all tabs* deletes their files. Say this
in `PRIVACY_POLICY.md`.

### 4. Bookmarks and history ☐

**Shape.**
- **History.** Each page that finishes loading is recorded: address, title, time and favicon.
  The same address seen again updates its row rather than adding a new one. Addresses are stored
  whole, on the phone only.
- A **History** screen lists pages by day, with search and delete. *Clear history* offers last
  hour, last day or everything.
- **Bookmarks.** A star in the site pill, or in the address bar's menu, adds or removes the page.
  A **Bookmarks** screen and the start screen's quick sites show them. There are no folders at
  first.
- **Suggestions.** While typing in the address bar, matches from bookmarks and history show under
  the field. Bookmarks come first, then history ranked by visits and how recent they are.
- **Where they live.** From the start screen and the Downloads › ⋮ menu (or a new browser menu,
  if one is added in part 3's top row).

**Data.** Room **v6**: tables `history (url PK, title, visitedAt, visits)` and
`bookmarks (url PK, title, createdAt)`, with `MIGRATION_5_6` and a migration test. If part 2 had
to take v6, this becomes v7.

**Tests.**
- The DAOs (Room in memory): history dedupes and orders, bookmarks add and remove, suggestions
  rank as described, and clearing removes the right rows.
- The migration test.
- Compose tests for the history list, the star and the suggestions.

**Privacy.** `PRIVACY_POLICY.md` lists history and bookmarks under "on your phone only", with how
to clear them. Recording stays on by default, with a switch on the History screen to stop it.

### 5. Faster YouTube: several parts at once ☐

**Why.** #26 fetches YouTube files (`googlevideo.com`) in 10 MB ranges, one after another, which
lifts the playback-speed limit. Fetching 3–4 ranges at the same time should fill the connection,
as yt-dlp's `--concurrent-fragments` does for fragmented formats.

**Shape.**
- In `download/HttpDownloader.kt`, for addresses where `chunkBytesFor(url)` gives a size, open
  up to 4 calls, each for its own 10 MB piece. Each writes its piece at its offset in the
  `.part` file (a `RandomAccessFile`).
- A sidecar `<file>.pieces` records which pieces are complete, so resume fetches only what is
  missing.
- Every call stays registered for pause and cancel, as `CLAUDE.md` warns. Progress is the sum of
  the pieces.
- If any piece answers with anything but 206, fall back to one piece at a time. If two answer
  403 (YouTube throttling or an expired address), stop and ask the engine for a fresh address,
  as #23 does for expired answers.
- Start with 3 calls, and check on a phone that it is actually faster: YouTube may limit the
  speed per IP, not per connection. Then decide.

**Tests.** MockWebServer serving ranges with a throttled body: all pieces arrive in order on
disk; the file matches byte for byte; pause mid-way, then resume, re-fetches only missing pieces;
cancel stops every call; a non-206 falls back to one piece. The existing `HttpDownloaderChunksTest`
cases still pass.

### 6. Downloads as a media library ☐

**Shape.**
- **Layout.** The Downloads screen gets a switch between today's list and a **grid** of
  thumbnails. Pictures show themselves; videos show a frame from `MediaMetadataRetriever`, cached.
  The list stays best for what is downloading; the grid is for what is done.
- **Viewer.** A built-in full-screen picture viewer: pinch to zoom, swipe between pictures. The
  pictures of one post are grouped, so a five-picture post is one tile with a "5" mark and swipes
  inside. Videos keep opening in the in-app player.
- **ZIPs.** Opening an account ZIP lists its contents as a grid (`java.util.zip` reads the
  listing; thumbnails come from the stored entries). Entries can be viewed, shared, or saved out
  one at a time.
- **Finding things.** Filters by kind (videos, pictures, audio, ZIPs) and by site (YouTube,
  Instagram, TikTok, X, other), plus search by title.
- **Acting on things.** **Share** on every finished item, and multi-select to share or delete
  several at once.

**Data.** Grouping a post's pictures needs a group key. Add a nullable `group_key` column (the
post's page address plus its title) in the next Room version, v7 if part 4 took v6. Downloads
started before it stay ungrouped.

**Tests.**
- Robolectric Compose: grid and list switch; filters and search; group tiles open the viewer at
  the right picture; multi-select.
- The migration test.
- A JVM test for the ZIP listing.
- The walkthrough gets a screenshot of the grid.

## Smaller items noted along the way

- Downloads on Wi-Fi only (a setting; the queue waits for unmetered networks).
- Ads inside YouTube's own videos need uBlock Origin's scriptlets, which the blocker does not run.
  Revisit only if a safe, narrow way appears.
- `ROADMAP.md`'s other "Next" items still stand:
  - an end-to-end walkthrough of a real video-site download;
  - deciding whether the YouTube solver is practical on a phone (`device-numbers.txt`);
  - joining HEVC and AV1.
