# Chaya — Comprehensive Engineering Roadmap

**Purpose of this document:** a single, self-contained execution spec a
coding agent (or engineer) can work from directly, PR by PR, without
re-investigating what's already been established. Every phase names exact
files, exact classes/functions, the concrete problem, the fix approach, and
the acceptance criteria. Do not re-derive facts already stated here as
"verified" — they were confirmed by actually building the project, reading
the referenced source, or decompiling the referenced library in a prior
session.

## 0. Ground truth (verified, not assumed)

- **This is not a paper project.** `./gradlew assembleDebug` succeeds today.
  The app has a working WebView browser, network+DOM media detection, OkHttp
  downloads, Media3 HLS/DASH downloads, Room persistence, and a foreground
  service — all present in `app/src/main/java/com/chaya/app/`.
- **Zero automated tests existed** before [[pr:katariyaVivek/chaya#1]]. That
  PR added the first 20 JVM unit tests and wired `testDebugUnitTest` into
  `.github/workflows/build.yml` ahead of `assembleDebug`.
- **Two real bugs were found and fixed** in that same PR:
  1. `DownloadService` (`app/src/main/java/com/chaya/app/download/DownloadService.kt`)
     was registered in `AndroidManifest.xml` and targeted by
     `DownloadNotification`'s `PendingIntent`s, but no code anywhere called
     `startService`/`startForegroundService` on it. `DownloadManager` now
     calls `ContextCompat.startForegroundService()` in `startDownload()` and
     `resumeDownload()`.
  2. `StreamDownloader.pauseStream()` called Media3's app-wide
     `DownloadManager.pauseDownloads()`, which paused every active stream
     download, not just the one the user tapped. Confirmed via `javap` on
     the actual `media3-exoplayer-1.5.1.aar` that
     `DownloadManager.setStopReason(String id, int reason)` is the real
     per-item primitive; `pauseStream`/`resumeStream` now use it.
- **Environment**: building requires JDK 17, Android SDK platform 35 +
  build-tools 35.0.0, and Gradle 8.9 (the wrapper jar is deliberately not
  checked in — see `BUILD_AND_TEST.md` and `.gitignore`). CI
  (`.github/workflows/build.yml`) installs Gradle directly via
  `gradle/actions/setup-gradle@v4` rather than using `./gradlew`. Any new
  local environment must replicate this (install JDK 17, SDK cmdline-tools,
  accept licenses for `platforms;android-35` and `build-tools;35.0.0`,
  either generate the wrapper with `gradle wrapper --gradle-version 8.9` or
  invoke `gradle` directly).

Every phase below assumes this baseline. Work through phases in order where
there are dependencies (noted per phase); otherwise phases can run in
parallel PRs.

---

## Phase 0 — Close known correctness gaps

Bugs identified while reading the code, not yet fixed. Each is independently
shippable.

### 0.1 Media3 resume-after-process-death race

**File:** `app/src/main/java/com/chaya/app/streaming/StreamDownloader.kt`

**Problem:** `taskToContentId`/`contentIdToTask` are plain in-memory maps
populated only by `track(taskId)`, called from `startStreamDownload`. If the
app process dies mid-download and restarts, `DownloadManager.restore()`
(`app/src/main/java/com/chaya/app/download/DownloadManager.kt`) reloads
`DownloadTask`s from Room and flips any `DOWNLOADING`/`QUEUED` row to
`PAUSED`, but a fresh `StreamDownloader` instance (lazily created via
`obtainStreamDownloader()`) starts with empty maps. When the user then taps
resume, `resumeStream()` checks
`taskToContentId[taskId]` — which is `null` — so it falls through to
`startStreamDownload`, re-adding the download from scratch under a **new**
content ID even though Media3's own `SimpleCache`/`StandaloneDatabaseProvider`
still has the real download indexed under the **old** content ID from the
previous process. This likely either double-downloads or orphans the
original cached segments (needs to be confirmed empirically, see task below).

**Fix approach:**
1. On `StreamDownloader` construction, read `downloadManager.currentDownloads`
   (populated from the persistent `DownloadIndex`/`StandaloneDatabaseProvider`
   backing store, which survives process death) and rebuild
   `taskToContentId`/`contentIdToTask` by matching each `Download.request.id`
   back to the corresponding Room row's URL (the content ID is currently
   `"chaya_<counter>"`, which carries no URL — **this needs to change** to a
   stable, derivable ID, e.g. `"chaya_task_<taskId>"`, generated from the
   `DownloadTask.id` instead of an internal `AtomicLong` counter, so it can be
   reconstructed after restart without a lookup table at all).
2. Once content IDs are derived from `taskId` directly, `track(taskId)` and
   the map bookkeeping can be deleted entirely — `"chaya_task_$taskId"` is
   always computable from either direction.
3. Add a unit test using Robolectric (see Phase 1 for the Robolectric setup
   this depends on) or a fake `DownloadIndex`/`WritableDownloadIndex` that:
   simulates a `StreamDownloader` instance, adds a download, "kills" it
   (drop the instance), constructs a fresh `StreamDownloader` against the
   same `cache`/`databaseProvider`, and asserts `resumeStream` finds the
   existing download rather than re-adding it.

**Acceptance criteria:** a fresh `StreamDownloader` instance can resume a
stream download that was started by a previous instance against the same
`cacheDir`, without creating a duplicate `DownloadRequest`. Covered by an
automated test, not just manual reasoning.

### 0.2 Destructive Room migration

**File:** `app/src/main/java/com/chaya/app/database/ChayaDatabase.kt`

**Problem:** `.fallbackToDestructiveMigration()` wipes the entire `downloads`
table on any schema version bump. Acceptable while `version = 2` was reached
during initial development with no real users; not acceptable going forward
— the next schema change (e.g. adding a column for Phase 2's error taxonomy,
see below) will silently delete every user's download history.

**Fix approach:**
1. Bump `version` to `3` the next time a schema-affecting change lands (do
   this in the *same PR* that needs the new column — don't bump speculatively).
2. Write an explicit `Migration(2, 3)` using `ALTER TABLE downloads ADD COLUMN ...`
   with a sensible default, matching Room's standard migration pattern.
3. Remove `.fallbackToDestructiveMigration()` and add
   `.addMigrations(MIGRATION_2_3)` instead.
4. Add a Room migration test using
   `androidx.room.testing.MigrationTestHelper` (add
   `androidx.room:room-testing` as a `testImplementation` dependency) that
   creates a v2 database, inserts a row, runs the migration, and asserts the
   row survives with the expected default for the new column.

**Acceptance criteria:** upgrading the app across the version bump preserves
existing download rows; a `MigrationTestHelper` test proves it.

### 0.3 Deprecated icon usage

**File:** `app/src/main/java/com/chaya/app/downloads/DownloadsScreen.kt` line
~373 (compiler already emits: `'val Icons.Filled.OpenInNew: ImageVector' is
deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.OpenInNew.`)

**Fix approach:** grep the whole `app/src/main/java` tree for
`Icons.Filled.` / `Icons.Default.` usages that have an `Icons.AutoMirrored.Filled.*`
equivalent (directional icons: arrows, `OpenInNew`, etc. — icons without a
left/right implication like `Movie`, `MusicNote`, `Delete` don't need
changing). Replace each, re-run `./gradlew compileDebugKotlin` and confirm
the specific deprecation warning is gone from the output.

**Acceptance criteria:** `./gradlew compileDebugKotlin` produces no
deprecation warnings for icon usage.

### 0.4 Extension-only media detection ("any media" gap)

**Files:** `app/src/main/java/com/chaya/app/detection/MediaInterceptor.kt`,
`app/src/main/assets/detection/chaya_media_detector.js`

**Problem:** Both detection layers gate on a hardcoded extension allowlist
(`mediaExtensions` set in `MediaInterceptor.kt`; the regex in
`looksLikeMedia()` in the JS file). A media URL served from an endpoint with
no recognizable extension (e.g. `/stream?id=42`, `/media/abc123`) is invisible
to detection even though the response is genuinely `video/mp4` — this is the
literal gap between "detects common containers by extension" and the "any
media" ambition stated in the product brief.

**Fix approach (network layer, `MediaInterceptor.kt`):**
1. `shouldInterceptRequest` currently only inspects the **request** (URL,
   Accept header) and always returns `null` (observer-only, doesn't touch the
   response). To sniff `Content-Type` you need the **response**, which means
   either:
   - (a) Making `shouldInterceptRequest` actually issue the request itself via
     OkHttp, inspect the response `Content-Type`/`Content-Length` headers,
     and return a `WebResourceResponse` wrapping the same bytes back to the
     WebView (turns observer-only into a real proxy — bigger change, more
     risk of breaking page loads if done wrong), or
   - (b) Leave network-level detection extension-based (cheap, zero risk) and
     rely on the DOM/JS layer plus a **new, opt-in "verify unknown media"
     step**: when the user's page has no successfully detected media after a
     configurable delay, offer a "Scan more thoroughly" affordance that walks
     `<video>`/`<audio>` elements' resolved `currentSrc` (already done) but
     additionally issues a `HEAD` request (via a small Kotlin suspend
     function using the existing OkHttp client) to any suspicious same-origin
     URL found in inline `<script>` JSON blobs or `fetch`/XHR calls already
     captured by `chaya_media_detector.js`'s hooks, checking the real
     `Content-Type` before offering it as a download.
   Recommendation: start with (b) — it's additive, doesn't touch the
   observer-only contract that `BUILD_AND_TEST.md` explicitly documents as a
   design decision, and is much lower risk.
2. Add the `Content-Type` check as a small standalone function,
   `suspend fun sniffContentType(url: String, headers: Map<String,String>): String?`,
   in a new `app/src/main/java/com/chaya/app/detection/ContentTypeSniffer.kt`,
   unit-testable with `MockWebServer` (depends on Phase 1's test
   infrastructure) independent of any WebView plumbing.
3. Wire it into `MediaBridge`'s XHR/fetch hook path (`onMediaDetectedWithType`
   already exists) so any `fetch`/XHR URL the JS layer captures that lacks a
   recognized extension gets a HEAD-request Content-Type check before being
   discarded.

**Acceptance criteria:** a URL with no recognizable extension but a real
`video/mp4` `Content-Type` on `HEAD` gets surfaced in the detected-media
sheet. Covered by a `MockWebServer` test for `ContentTypeSniffer` plus a
manual verification note in the PR (this one is hard to fully automate
without an instrumented WebView test — acceptable to note as "manually
verified against a MockWebServer-backed test page" if instrumented coverage
isn't ready yet).

**Also update:** any user-facing copy claiming unqualified "any media"
support (README, Play Store listing copy if it exists) to something honest
like "any direct file, plus HLS/DASH streams" until MediaSource/blob-based
players (out of scope — see Phase 3) are addressed.

### 0.5 Notification permission UX

**File:** `app/src/main/java/com/chaya/app/browser/BrowserScreen.kt`
(`requestPermissionAndDownload`, `notifPermissionLauncher`, ~line 195-260)

**Problem:** `POST_NOTIFICATIONS` is requested with zero explanation, lazily,
the first time the user taps download. Android's own guidance recommends a
rationale before the system dialog when the permission isn't obviously tied
to the triggering action from the user's point of view.

**Fix approach:** before calling `notifPermissionLauncher.launch(...)`, show
a bottom sheet or `AlertDialog` (following `DetectedMediaSheet.kt`'s existing
`ModalBottomSheet` pattern for visual consistency) with one sentence: "Chaya
shows download progress in a notification so you can track it — allow
notifications?" with "Allow"/"Not now" actions. "Not now" proceeds to
download anyway (matches current fallback behavior — silent download without
a progress notification).

**Acceptance criteria:** first download attempt shows the rationale sheet
before the system permission dialog; declining still starts the download.

---

## Phase 1 — Instrumented & integration test infrastructure

**Dependency:** none of these strictly block each other, but 1.1 (Robolectric
setup) is a prerequisite for the Phase 0.1 test and should land first.

### 1.1 Robolectric unit tests for `DownloadManager`'s state machine

**New files:** `app/src/test/java/com/chaya/app/download/DownloadManagerTest.kt`
plus a `testImplementation("org.robolectric:robolectric:4.16.1")` and
`testImplementation("androidx.test.ext:junit:1.3.0")` addition to
`app/build.gradle.kts` / new version entries in `gradle/libs.versions.toml`
(check current stable versions before pinning — 4.16.1 / 1.3.0 were current
as of this writing, verify via `https://github.com/robolectric/robolectric/releases`
and `https://developer.android.com/jetpack/androidx/releases/test` before
committing to exact numbers).

**What to test** (`DownloadManager` in
`app/src/main/java/com/chaya/app/download/DownloadManager.kt` is currently
**completely untested** — it's the most important class in the app):
- `startDownload()` on a plain file URL creates a `QUEUED`→`DOWNLOADING` task,
  inserts a Room row via the injected `DownloadDao`, and calls into
  `HttpDownloader.start()` with the right `taskId`/`url`/`saveFile`.
- `pauseDownload()` on a `DOWNLOADING` HTTP task cancels the downloader call
  and transitions state to `PAUSED`, preserving `downloadedBytes` from the
  partial file on disk.
- `pauseDownload()` on a task that isn't `DOWNLOADING` is a no-op (guard
  clause `if (t.state != DownloadState.DOWNLOADING) return`).
- `resumeDownload()` on a `PAUSED`/`FAILED`/`CANCELLED` task re-invokes the
  downloader with the correct `fromBytes` (partial file size) and transitions
  to `DOWNLOADING`.
- `resumeDownload()` on a `COMPLETED`/`QUEUED`/`DOWNLOADING` task is a no-op
  (`resumableStates` guard).
- `cancelDownload()` transitions to `CANCELLED` and calls
  `downloader.cancel(id)`.
- `deleteTask()` removes the Room row, deletes the on-disk file, and removes
  the task from the `downloads` `StateFlow`.
- `restore()` moves any persisted `DOWNLOADING`/`QUEUED` row to `PAUSED` on
  load (the documented "app was killed mid-download" recovery behavior) —
  this is exactly the kind of behavior that's easy to accidentally break
  and currently has zero test coverage.
- The `completeTask`/`failTask` private state transitions, exercised
  indirectly by invoking the `onComplete` callback passed to a fake
  `HttpDownloader`.

**Approach:** `HttpDownloader` and `StreamDownloader` are concrete classes,
not interfaces — either (a) extract a minimal interface
(`interface MediaDownloader { fun start(...); fun cancel(taskId: Long) }`)
that `HttpDownloader` implements and inject it into `DownloadManager` (small,
clean refactor, improves testability permanently), or (b) use MockK's
`mockkConstructor`/relaxed mocking to stub `HttpDownloader` without an
interface. Prefer (a) — it's the more durable fix and matches how
`StreamDownloader.Listener` is already structured as an interface in the
same file.

Use Turbine (`app.cash.turbine`, already added as a dependency in
[[pr:katariyaVivek/chaya#1]]) to assert `StateFlow<List<DownloadTask>>`
emissions: `downloadManager.downloads.test { assertEquals(..., awaitItem()) }`.
Use an in-memory Room database via
`Room.inMemoryDatabaseBuilder(context, ChayaDatabase::class.java).build()`
(needs Robolectric's `context`, hence the Robolectric dependency) rather than
mocking `DownloadDao` — it's more faithful and Room's in-memory builder is
fast enough for unit tests.

**Acceptance criteria:** every state transition listed above has at least
one passing test; `./gradlew testDebugUnitTest` stays green.

### 1.2 `MockWebServer`-backed downloader tests

**New file:** `app/src/test/java/com/chaya/app/download/HttpDownloaderIntegrationTest.kt`

**Dependency to add:** `testImplementation("com.squareup.okhttp3:mockwebserver:<okhttp-version>")`
— match whatever OkHttp version is already pinned in
`gradle/libs.versions.toml` (`okhttp = "4.12.0"` currently; use the mockwebserver
artifact from the same major/minor line).

**Why this matters:** every existing test (including the ones this document's
Phase 0/1.1 describes) either tests pure logic or mocks the network layer
entirely. Nothing in the repo proves `HttpDownloader` actually downloads
real bytes correctly over real HTTP semantics. This is the test that
actually substantiates "downloads media" as opposed to "calls functions in
the right order."

**What to test, against a real embedded HTTP server:**
- A full download of a small fixture (a few KB of synthetic bytes is fine,
  doesn't need to be real media) completes and the resulting file's bytes
  exactly match what the server sent.
- Resume: start a download, cancel partway (simulate by closing the
  connection or using `MockResponse.throttleBody`), verify the partial file
  size, call `start()` again with `fromBytes` set to that size and a `Range`
  header, and assert the server received the correct `Range: bytes=N-` header
  (via `RecordedRequest` from `mockWebServer.takeRequest()`) and the final
  file is byte-for-byte complete with no duplicated/missing bytes.
- A server that ignores the `Range` header and returns `200` + the full body
  instead of `206` — confirm `HttpDownloader`'s existing guard (`val resumed = resp.code == 206 && fromBytes > 0`)
  correctly restarts from zero rather than corrupting the file by appending
  the full body onto existing partial bytes.
- `Content-Disposition` header round-trip: server sets a
  `Content-Disposition: attachment; filename="real-name.mp4"` header, assert
  `onMeta` fires with that name before any bytes are written.
- A 403/404/500 response surfaces as `Result.failure` with a message
  containing the status code.
- Cancellation mid-transfer: call `cancel(taskId)` while streaming and assert
  `onComplete` is **never** invoked (the documented "silent cancel" contract
  in `HttpDownloader`'s KDoc) and the partial file is preserved, not deleted.

**Acceptance criteria:** all of the above pass against a real (embedded, not
mocked) HTTP server, proving the resume/redirect/cancel contracts that the
rest of the app depends on.

### 1.3 Compose UI tests

**New files under** `app/src/androidTest/java/com/chaya/app/` (this is a new
source set — `androidTest`, not `test`; needs
`androidx.compose.ui:ui-test-junit4` and `androidx.test.ext:junit` as
`androidTestImplementation`, plus `androidx.compose.ui:ui-test-manifest` as
`debugImplementation` — check current AndroidX Compose testing artifact
versions against the `composeBom` version already pinned
(`composeBom = "2024.12.01"` — use the BOM to resolve compatible test artifact
versions automatically rather than pinning them separately).

**What to test** (these need a running emulator/device — see 1.4 for the CI
wiring):
- `BrowserScreen`: typing a URL and pressing go loads it (assert on WebView
  state via a fake/instrumented URL, or at minimum assert the URL bar reflects
  `viewModel.uiState.value.url` after submission — avoid depending on real
  network in UI tests where possible; prefer testing the ViewModel wiring
  and reserve true page loads for a small, explicit "smoke test" against a
  local `MockWebServer`-served HTML fixture instead of the live internet).
- `DetectedMediaSheet` (`app/src/main/java/com/chaya/app/ui/components/DetectedMediaSheet.kt`):
  given a list of `DetectedMedia`, tapping a row invokes the `onDownload`
  callback with that item.
- `DownloadsScreen` (`app/src/main/java/com/chaya/app/downloads/DownloadsScreen.kt`):
  for each `DownloadState`, the correct action icons render (per
  `ActionsRow`'s existing `when (task.state)` branches) and tapping them
  invokes the corresponding `DownloadsViewModel` call — this is a direct,
  cheap regression guard for exactly the kind of state/action mismatch bugs
  Phase 0 found elsewhere.
- `QualitySelectorSheet` (`app/src/main/java/com/chaya/app/ui/components/QualitySelectorSheet.kt`):
  toggling track checkboxes updates the enabled/disabled state of the
  "Download Selected" button (`enabled = selectedTracks.any { it }`).

**Acceptance criteria:** each listed interaction has a passing
`createComposeRule()`-based test exercising real Compose semantics (not
just ViewModel unit tests) — these are what actually catch "the button
doesn't do what the icon implies."

### 1.4 CI wiring for instrumented tests

**File:** `.github/workflows/build.yml`

**Approach:** add a second job, `instrumented-tests`, using
`reactivecircus/android-emulator-runner@v2` (check current major version)
targeting API 33 or 34 (matches `targetSdk = 35` closely enough while staying
within typical CI emulator image availability), running
`./gradlew connectedDebugAndroidTest`. Because emulator boot is slow (2-5
min) and this repo's `assembleDebug`+`testDebugUnitTest` currently complete in
under 4 minutes total, **do not** make this job a required check blocking
every PR by default — gate it with `workflow_dispatch` and a scheduled nightly
trigger, or make it required only for PRs touching `app/src/main/java/com/chaya/app/browser/`,
`downloads/`, or `ui/` (paths-filter). Document the choice in the workflow
file's comments so it isn't silently forgotten.

**Acceptance criteria:** `connectedDebugAndroidTest` runs green on a manual
or nightly trigger against an emulator; PR-blocking CI stays fast.

---

## Phase 2 — Bug detection & observability system

This is the part of the original ask with the least existing plan. The app
runs on arbitrary user devices against arbitrary third-party websites — a
solo maintainer needs a way to know what's actually breaking without relying
on users filing detailed bug reports.

### 2.1 Crash reporting — self-hosted, not Crashlytics (initially)

**Rationale for self-hosted over Firebase Crashlytics:** `plan.md`'s v0.5
section explicitly frames distribution as an open "Play Store or direct APK
decision," and DESIGN.md's brand personality ("fluid, assured, soft," "quiet
competence") plus the anti-reference to gimmicky/overstimulating patterns
implies a deliberately minimal, non-invasive product stance. Crashlytics
pulls in Google Play Services and phones-home by default, which is a real
commitment (App Tracking / data-safety disclosure, Play Services as a hard
dependency, an account to manage) that shouldn't be taken on speculatively
before the distribution channel is even decided. Build the self-hosted
version first; revisit Crashlytics only if/when Play Store is confirmed as
the distribution channel (Phase 5).

**New files:**
- `app/src/main/java/com/chaya/app/diagnostics/CrashReporter.kt` — installs
  `Thread.setDefaultUncaughtExceptionHandler` in `ChayaApplication.onCreate()`
  (`app/src/main/java/com/chaya/app/ChayaApplication.kt`), wrapping the
  previous default handler (chain to it after writing the report, so the
  process still crashes/logs normally — don't swallow crashes silently).
  On an uncaught exception: serialize a `CrashReport` (timestamp, thread
  name, full stack trace, app version from `BuildConfig.VERSION_NAME`,
  Android API level, device model, and the last N entries from the
  structured event log described in 2.3) to a file under
  `context.filesDir/crash_reports/`, then re-throw to the original handler.
- `app/src/main/java/com/chaya/app/diagnostics/DiagnosticsScreen.kt` — a new
  Compose screen (add to `ChayaNavHost` in
  `app/src/main/java/com/chaya/app/ui/navigation/ChayaNavHost.kt` and
  `Screen.kt`) listing saved crash reports and event-log exports, each with a
  share-intent (`Intent.ACTION_SEND`, `text/plain`) so a user can send Chaya's
  maintainer a report manually — no automatic network upload, matching the
  offline-first stance.
- Surface access to `DiagnosticsScreen` from the Downloads screen's top bar
  (a small icon button) rather than hiding it — this is meant to be usable by
  real users hitting real bugs, not a hidden debug menu.

**Acceptance criteria:** forcing an uncaught exception (a debug-only "throw
test crash" button, removed or `BuildConfig.DEBUG`-gated before release) 
produces a readable crash report file, visible in `DiagnosticsScreen`,
shareable via the system share sheet.

### 2.2 Download error taxonomy

**Files:** `app/src/main/java/com/chaya/app/download/DownloadTask.kt`,
`DownloadManager.kt` (`failTask`), `app/src/main/java/com/chaya/app/downloads/DownloadsScreen.kt`
(error text rendering)

**Problem:** `DownloadTask.errorMessage` is `error.message?.take(180)` —
whatever raw exception message OkHttp/Media3 happened to produce
(`"HTTP 403: Forbidden"`, `"timeout"`, `"Unable to resolve host..."`), shown
verbatim to the user with no categorization, no differentiated retry
affordance, and no way to aggregate "what's actually failing across users."

**Fix approach:**
1. New file `app/src/main/java/com/chaya/app/download/DownloadError.kt`:
   ```kotlin
   sealed class DownloadError(val userMessage: String, val retryable: Boolean) {
       data class Network(val cause: Throwable) : DownloadError("Connection lost — check your network", retryable = true)
       data class HttpStatus(val code: Int) : DownloadError(/* 401/403 -> "Access denied — the link may have expired"; 404 -> "File not found on the server"; 5xx -> "Server error — try again shortly" */, retryable = code >= 500 || code == 429)
       object StorageFull : DownloadError("Not enough storage space", retryable = false)
       object UnsupportedFormat : DownloadError("This format isn't supported yet", retryable = false)
       object Cancelled : DownloadError("Cancelled", retryable = false)
       data class Unknown(val cause: Throwable) : DownloadError("Something went wrong", retryable = true)
   }
   ```
   with a `fun DownloadError.Companion.from(throwable: Throwable): DownloadError`
   classifier inspecting the exception type/message (`IOException` with
   `UnknownHostException`/`SocketTimeoutException` causes → `Network`;
   parse an `HTTP (\d+)` pattern already embedded in `HttpDownloader`'s
   `IOException("HTTP ${resp.code}: ${resp.message}")` → `HttpStatus`; etc).
2. Change `DownloadTask.errorMessage: String?` to store the classified
   `DownloadError` (requires a Room `TypeConverter` similar to the existing
   `Converters.kt` for `DownloadState`, and — per Phase 0.2 — a proper Room
   migration for the column type/shape change, not another destructive wipe).
3. `DownloadsScreen.kt`'s `ActionsRow`/error text rendering shows
   `error.userMessage` instead of the raw string, and only offers a "Retry"
   action when `error.retryable` is true (currently retry is always offered
   for `FAILED` state regardless of whether retrying could possibly help —
   e.g. retrying a 404 is pointless).
4. Unit test the classifier exhaustively: feed it a `SocketTimeoutException`,
   an `IOException("HTTP 403: Forbidden")`, an `IOException("HTTP 500: ...")`,
   a generic `RuntimeException`, and assert the right `DownloadError` subtype
   and `retryable` flag each time.

**Acceptance criteria:** the downloads screen shows a human, categorized
error message per failure type, retry is only offered when it's plausible
to help, and the classifier has full unit test coverage.

### 2.3 Structured on-device event log

**New files:**
- `app/src/main/java/com/chaya/app/diagnostics/EventLog.kt` — a small
  singleton (or a class held on `ChayaApplication`, matching the existing
  `downloadManager`/`database` pattern in `ChayaApplication.kt`) exposing
  `fun record(event: ChayaEvent)` and backed by a bounded in-memory ring
  buffer (e.g. `ArrayDeque` capped at 500 entries) that also appends to a
  rotating on-disk log file (simple newline-delimited JSON, rotate at e.g.
  1MB, keep 2 files) under `context.filesDir/event_log/`.
- `ChayaEvent` sealed class covering: `MediaDetected(url, source, mimeType)`,
  `DownloadStateChanged(taskId, from, to)`, `DownloadFailed(taskId, error: DownloadError)`,
  `PageLoaded(url)`, `CaughtException(tag, throwable)` — deliberately no raw
  URLs with query-string tokens/cookies logged verbatim; strip query params
  before recording (privacy — this log may be shared by a user for
  debugging, don't let it leak session tokens).

**Where to call `EventLog.record(...)`:** every state transition already
funneled through `DownloadManager`'s private `apply`/`completeTask`/`failTask`
functions (single choke point — add the call there, not scattered
everywhere), every `onMediaDetected` call in `BrowserViewModel`, and the
`CrashReporter` from 2.1 reads the last N entries into each crash report.

**Surface in `DiagnosticsScreen`** (2.1): a scrollable, monospace event list
with timestamps, and an export-to-share action alongside crash reports.

**Acceptance criteria:** performing a detect→download→complete cycle
produces a readable, chronological event trail in `DiagnosticsScreen`;
no raw query strings/tokens appear in exported logs (covered by a unit test
on the URL-stripping logic).

### 2.4 Local-only detection/download insights (opt-in)

**New file:** `app/src/main/java/com/chaya/app/diagnostics/InsightsScreen.kt`
(or a section within `DiagnosticsScreen`)

**What:** aggregate counts already implicit in `EventLog`'s entries — media
detected per domain, download success/fail rate — computed on-device,
displayed locally, never transmitted anywhere. This directly answers "is
detection coverage actually working" without needing any telemetry
infrastructure or user data collection. Gate this behind a settings toggle
defaulting **off** (respect user attention — this is a diagnostics tool for
when something feels wrong, not an always-on dashboard) so it doesn't
contradict "calm surfaces, alive details."

**Acceptance criteria:** toggling insights on shows aggregated, accurate
counts derived from real `EventLog` entries; toggling off stops aggregation
(no wasted computation) and the data is never sent over the network (verify
by confirming no new network client is introduced in this feature's diff).

### 2.5 StrictMode + LeakCanary in debug builds

**File:** `app/build.gradle.kts`, `ChayaApplication.kt`

**Fix:** add `debugImplementation("com.squareup.leakcanary:leakcanary-android:<current-version>")`
(check current version before pinning) and, in `ChayaApplication.onCreate()`,
gate a `StrictMode.setThreadPolicy(...)`/`setVmPolicy(...)` call behind
`if (BuildConfig.DEBUG)` catching disk/network-on-main-thread violations.
Near-zero implementation cost; catches exactly the kind of accidental
main-thread Room/file access that's easy to introduce while implementing the
other phases in this document.

**Acceptance criteria:** a debug build with LeakCanary installed flags no
leaks after a full detect→download→complete→delete cycle through the app's
main screens; StrictMode logs no violations during the same walkthrough.

---

## Phase 3 — "Any media, robustly"

Concrete hardening beyond Phase 0.4's Content-Type sniffing.

### 3.1 Redirect chain header forwarding

**File:** `app/src/main/java/com/chaya/app/download/HttpDownloader.kt`

**Problem to verify:** the shared `OkHttpClient` has
`.followRedirects(true).followSslRedirects(true)`, but OkHttp does **not**
forward custom headers like `Cookie`/`Referer` across a redirect to a
different host by default (only `Authorization` is dropped cross-host by
OkHttp's built-in `followRedirects`; `Cookie`/`Referer` set via
`Request.Builder().header(...)` on the *original* request are NOT
automatically re-applied to the redirected request unless the redirect
target is same-host, per OkHttp's `RetryAndFollowUpInterceptor` behavior).
This matters concretely because CDNs very commonly redirect to a
signed-URL host (e.g. `videosite.com/video/123` → `cdn-signed.videosite.com/...?sig=...`),
and if the original `Referer`/`Cookie` were required for authorization on
that redirect, the download could silently fail with a 403 on the redirected
host even though the initial request succeeded.

**Fix approach:** write the `MockWebServer` test *first* (two `MockWebServer`
instances or one server returning a 302 to a second path on the same server,
simulating cross-host via `MockResponse().setResponseCode(302).setHeader("Location", ...)`)
to establish the actual current behavior empirically — don't assume OkHttp's
behavior without proving it against this exact client configuration. If the
test shows headers are dropped and that breaks a real scenario, add a custom
`Interceptor` (network interceptor, added to the shared `OkHttpClient.Builder()`
in `HttpDownloader.kt`) that re-applies `Cookie`/`Referer`/`User-Agent` on
every request in the redirect chain, not just the first.

**Acceptance criteria:** a `MockWebServer` test with a 302 redirect chain
proves `Cookie`/`Referer`/`User-Agent` headers reach the final redirected
request.

### 3.2 Pre-flight storage space check

**File:** `app/src/main/java/com/chaya/app/download/HttpDownloader.kt` or
`DownloadManager.kt` (`startHttp`/`startStream`)

**Fix:** before starting a download, check
`StatFs(saveDir.path).availableBytes` against the response's
`Content-Length` (available only after the first response — so this is
really two checks: a rough pre-flight check using a sane fixed threshold,
e.g. refuse to start if fewer than ~50MB free regardless of file size, plus
a mid-download check inside `HttpDownloader`'s write loop that aborts
cleanly with a specific `DownloadError.StorageFull` — see Phase 2.2 — the
moment `availableBytes` drops below a small buffer, rather than letting the
`FileOutputStream.write` throw a raw, unclassified `IOException` when the
disk actually fills).

**Acceptance criteria:** a download started with insufficient disk space (a
test can shrink the effective free-space threshold via a small testable
seam, e.g. inject the `StatFs` check as a function parameter with a default)
fails fast with `DownloadError.StorageFull`, not a generic I/O exception.

### 3.3 Document the explicit non-goal: MediaSource/blob-only players

**File:** README or a new `LIMITATIONS.md`; also update any marketing/store
copy per Phase 0.4's note.

**What:** many video sites serve exclusively via `MediaSource.appendBuffer`
(fed by JS, no network-visible manifest at all — YouTube's own player is a
prominent example, and per `plan.md`'s own "What Not to Build First" table,
YouTube support is explicitly and deliberately out of scope for
legal/policy reasons too). Write one paragraph stating plainly: Chaya
detects and downloads direct media files and HLS/DASH streams; it does not
and will not attempt to intercept MediaSource-fed playback, both because
it's technically exotic (would require either a custom WebView-embedded
media pipeline or Chromium-level hooks like Aloha Core's, which
`aloha_core_analysis.md` and `plan.md` already correctly ruled out for this
project's scope) and because major sites using it (YouTube foremost) are
explicitly excluded on policy grounds already.

**Acceptance criteria:** one file exists stating the scope boundary in plain
language; no code changes required for this item — it's a documentation and
expectation-setting task that prevents "why can't it download from
YouTube/Netflix/etc." being treated as an open bug.

---

## Phase 4 — Premium UX polish

The design system already exists and is well-specified (`DESIGN.md`,
`app/src/main/java/com/chaya/app/ui/theme/{Color,Theme,Motion,Type}.kt`). The
work here is a consistency **audit and fill-gaps** pass, not a redesign.

### 4.1 Motion consistency audit

**Reference tokens** (already implemented, don't reinvent):
`ChayaMotion.pressScale()` extension function in `Motion.kt`
(default `pressedScale = 0.96f`, used via `Modifier.pressScale()`),
`ChayaMotion.springSmooth()`, `ChayaMotion.tweenStandard()`/`tweenShort()`,
`StaggeredAppear` composable for first-composition fade-rise entrances.

**Task:** grep every `Composable` under `app/src/main/java/com/chaya/app/ui/`
and `browser/`, `downloads/` for tappable elements (`clickable`, `IconButton`,
`Button`, `Card(onClick = ...)`) and confirm each one applies
`Modifier.pressScale()`. Spot-checked examples already doing this correctly:
`DetectedMediaSheet.kt`'s `DetectedMediaRow`, `DownloadsScreen.kt`'s
`ActionIcon`. Confirm coverage extends to every icon button in
`BrowserScreen.kt`'s toolbar (`NavAction` composable) and any bare
`IconButton`s that might have been added without the modifier.

**Acceptance criteria:** every tappable surface in the app applies
`pressScale` (or an equivalent, deliberate reason is documented inline for
any exception); verified via a screenshot/recording pass (`browser_record`)
tapping through every screen showing the press feedback is present and
smooth.

### 4.2 Empty/loading/error state completeness pass

**Reference pattern already established:**
`DownloadsScreen.kt`'s `EmptyDownloads` composable (72dp tonal icon circle +
"Nothing saved yet" — matches DESIGN.md's "soft icon circle + one teaching
line, never 'nothing here'" spec exactly) and `DetectedMediaSheet.kt`'s
empty-list text ("Nothing found yet. Play or scroll the page.").

**Task:** confirm every list-bearing screen has an equivalent state:
`QualitySelectorSheet.kt`'s `Loading`/`Error` states already exist (verified
in this session) — confirm the `Error` state's copy and layout match the
same visual register as `EmptyDownloads` (currently it's a plain
`Text` + `FilledTonalButton`, no icon circle — consider adding one for
consistency, or explicitly note in the PR why the quality-picker error state
is intentionally simpler).

**Acceptance criteria:** a side-by-side screenshot set (via `preview_start` +
`browser_screenshot`, or real device screenshots) of every list/sheet in
both its empty and populated states, confirming visual consistency with
`DESIGN.md`'s stated pattern.

### 4.3 Dark/light parity screenshot pass

**Reference:** `Theme.kt`'s `LightColorScheme`/`DarkColorScheme`, both fully
specified already — this is a verification task, not an implementation task.

**Task:** capture every screen (`BrowserScreen`, `DetectedMediaSheet`,
`QualitySelectorSheet`, `DownloadsScreen` in each `DownloadState`,
`PlayerScreen`) in both light and dark theme, side by side, and check for:
contrast issues, any hardcoded color bypassing `MaterialTheme.colorScheme`/
`LocalChayaColors` (grep for raw `Color(0x...)` literals outside
`Color.kt` itself — `PlayerScreen.kt` currently uses
`Color.White.copy(alpha = 0.92f)` for the top bar text color deliberately,
per its "always near-black regardless of theme" comment — confirm this and
any similar deliberate exception is intentional, not an oversight).

**Acceptance criteria:** a documented screenshot set proving parity;
any found hardcoded-color bypass is either justified inline (comment) or
fixed to use theme tokens.

### 4.4 Accessibility pass

**Task:**
1. Compute actual WCAG AA contrast ratios (4.5:1 for body text, 3:1 for
   large text) for every `onSurface`-on-`surface`, `onSurfaceVariant`-on-
   `surfaceContainerHigh`, etc. pairing actually used in the app, using the
   literal hex values in `Color.kt` — don't eyeball it, compute it (a small
   throwaway script or an online WCAG contrast checker against the exact
   hex pairs is sufficient; this doesn't need to be an automated test, but
   the computed ratios should be recorded in the PR description).
2. Audit every `IconButton`/tappable icon for a minimum 48dp touch target
   (Compose `IconButton` defaults to 48dp, but confirm no custom `Modifier.size()`
   override shrinks it below that anywhere).
3. Run TalkBack manually (or via `adb shell settings put secure enabled_accessibility_services ...`
   in an emulator) across the browser toolbar and downloads list, confirming
   every `Icon`'s `contentDescription` is meaningful (spot check: several
   already have good descriptions like `"Pause"`, `"Cancel"`, `"Resume"` in
   `DownloadsScreen.kt`'s `ActionsRow` — confirm this is universal, including
   `BrowserScreen.kt`'s toolbar).

**Acceptance criteria:** documented contrast ratios for every real color
pairing meet WCAG AA; no touch target found under 48dp; TalkBack
walkthrough notes recorded in the PR with any fixes applied.

### 4.5 First-run onboarding

**Problem:** the app currently opens straight into an empty WebView
(`BrowserViewModel`'s initial `homeVisible = true` state — check
`BrowserScreen.kt` for what the home/start screen currently shows) with no
explanation of the floating action button or media-detection sheet.

**Fix approach:** a single, skippable overlay or the home screen itself
gaining one explanatory line + a subtle pointer to the FAB, shown only once
(persist a `"onboarding_seen"` boolean via a small
`SharedPreferences`/`DataStore` — `androidx.datastore:datastore-preferences`
is the modern replacement for raw `SharedPreferences` if not already a
dependency, check `libs.versions.toml` first). Keep it to one screen, no
multi-slide carousel — matches "calm surfaces, alive details," not a
guided tour.

**Acceptance criteria:** first launch shows the onboarding hint; subsequent
launches don't; screenshot proof included in the PR.

---

## Phase 5 — Distribution readiness

Per `plan.md`'s own v0.5 section, still largely accurate; concretized here.

### 5.1 Release signing configuration

**File:** `app/build.gradle.kts`

**Problem:** only a `release { isMinifyEnabled = true; proguardFiles(...) }`
block exists — no `signingConfig`. `./gradlew assembleRelease` today would
produce an unsigned APK unusable for any real distribution channel.

**Fix:** add a `signingConfigs { release { ... } }` block reading
keystore path/passwords from environment variables or a local
(gitignored) `keystore.properties` file — never commit real credentials.
Document the expected env vars in `BUILD_AND_TEST.md`. If this is going to
GitHub Releases (see 5.2), also add a CI job step that decodes a
base64-encoded keystore secret from GitHub Actions secrets and signs the
release build there.

### 5.2 Direct-APK vs Play Store decision

Per `plan.md`: Play Store requires resolving the copyrighted-content
download policy risk it already flags. Direct APK via GitHub Releases
matches the existing CI artifact upload
(`chaya-debug-apk` in `.github/workflows/build.yml`) and the "browser-based
media manager for files you own or have permission to download"
positioning `plan.md` itself suggests. **This is a product/business decision
for the repo owner, not something a coding agent should decide unilaterally**
— flag it for explicit sign-off before building distribution infrastructure
around either choice.

### 5.3 Privacy policy

Required regardless of channel given `INTERNET`, cookie access via
`CookieManager`, and (per Phase 2) any diagnostics data collected, even
though it's local-only. A short, honest policy stating: what data Chaya
accesses (browsing session cookies, for the sole purpose of downloading
authenticated media the user is already viewing), what it stores (download
history, locally, in Room), and what it never does (no analytics network
calls, no ad SDKs, no crash data leaves the device without the user
explicitly sharing it via Phase 2.1's share-intent).

---

## Suggested execution order

Numbered for a single coding agent working sequentially; items on the same
line can be split into parallel PRs if multiple agents/sessions are
available.

1. **0.1** (Media3 resume race) + **0.2** (Room migration) — do these first
   because 0.1's fix (content-ID scheme change) and 0.2's migration pattern
   affect files that later phases (2.2's error taxonomy needs a migration
   too) will touch again; better to establish the migration discipline once.
2. **1.1** (Robolectric + `DownloadManager` state-machine tests) — this also
   validates 0.1's fix.
3. **0.3** (deprecated icons) — trivial, any time, but easy to bundle with 1.1's PR review pass.
4. **1.2** (`MockWebServer` downloader tests) — independent of 1.1, can run
   in parallel.
5. **0.4** (Content-Type sniffing) + **3.1** (redirect header forwarding) —
   both depend on `MockWebServer` infrastructure from 1.2.
6. **2.2** (error taxonomy) + **2.3** (event log) — highest debugging
   leverage for the codebase's actual size; do before 2.1 since the crash
   reporter's report format wants the event log to already exist.
7. **2.1** (crash reporter) + **2.5** (StrictMode/LeakCanary) — small, can
   land together.
8. **1.3** (Compose UI tests) + **1.4** (CI emulator wiring) — can run
   anytime after 1.1, ideally before Phase 4 so the polish pass has UI test
   coverage as a regression net.
9. **Phase 4** (all sub-items) — can run concurrently with 6-8 since it's
   almost entirely Compose UI files, minimal overlap with core logic.
10. **0.5** (notification rationale) — small, bundle with Phase 4's UX pass.
11. **3.2** (storage check) + **3.3** (limitations doc) — any time, low risk.
12. **2.4** (opt-in insights) — after 2.3, lower priority than 2.1-2.3.
13. **Phase 5** — last, and 5.2 explicitly needs the repo owner's decision
    before any code is written against it.

## Verification standard for every PR in this roadmap

Every PR must, at minimum:
1. Run `./gradlew testDebugUnitTest assembleDebug` (or the equivalent Gradle
   invocation given this repo's no-checked-in-wrapper setup — see Ground
   Truth section) and report the exact result, not an assumption.
2. Add or update tests for the specific behavior the PR changes — this
   roadmap names the exact test file/class to add per item; don't skip it.
3. For any UI-visible change (Phase 4 items, Phase 0.5, Phase 2's
   `DiagnosticsScreen`), capture before/after screenshots and include them in
   the PR description per this project's own screenshot-verification
   discipline.
4. Leave the target branch green — no PR should merge with a broken build or
   a newly-failing test.
