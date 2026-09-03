# Chaya — Roadmap to a Real, Premium, Tested App

Status check first: Chaya is **not** purely on paper. `./gradlew assembleDebug`
succeeds today and produces a working APK with WebView browsing, network+DOM
media detection, OkHttp downloads, Media3 HLS/DASH downloads, Room
persistence, and a foreground service — verified by actually building it in
this session, not by reading the docs. What it lacks: any automated tests
(zero existed before this thread), some real bugs (two fixed in
[[pr:katariyaVivek/chaya#1]]), instrumented/UI verification, and the
observability + polish that separates "compiles" from "premium and
trustworthy." This document is the execution plan for closing that gap,
sized as independent PRs a cheaper model/engineer can pick up one at a time.

## Already shipped ([[pr:katariyaVivek/chaya#1]])

- `DownloadService` is now actually started (`ContextCompat.startForegroundService`)
  — previously declared but never invoked, so downloads had no foreground
  protection and the pause/cancel notification never appeared.
- Per-stream pause fixed: `StreamDownloader` was calling Media3's app-wide
  `pauseDownloads()`, silently pausing every other active stream download.
  Switched to `DownloadManager.setStopReason(id, reason)` (verified against
  the real 1.5.1 API via `javap`).
- First unit tests in the repo (20 cases): `Content-Disposition` parsing,
  filename/stream-classification helpers. CI now runs
  `testDebugUnitTest` before `assembleDebug`.

## Phase 0 — Finish making the existing feature set trustworthy (1–2 PRs)

Bugs found while reading the code that are still open:

1. **Media3 resume race**: `resumeStream` checks `currentDownloads.any { state == STATE_STOPPED }`
   before deciding to call `setStopReason` vs re-adding the download. Add a
   unit test (Robolectric or a fake `DownloadIndex`) proving resume after a
   process restart (task known to Room, unknown to a fresh in-memory
   `StreamDownloader.taskToContentId` map) doesn't silently no-op.
2. **`fallbackToDestructiveMigration()`** on `ChayaDatabase` wipes download
   history on every schema bump. Fine for pre-1.0, but write a one-line
   `Migration` for the *next* schema change instead of continuing to
   destructively wipe — users will have real history by then.
3. **Deprecated icon usage** (`Icons.Filled.OpenInNew` etc. — compiler already
   warns). Sweep and switch to `Icons.AutoMirrored.Filled.*` equivalents.
4. **`MediaInterceptor`/JS detector extension allowlist**: "any media" is
   currently bounded by a hardcoded extension list (mp4/webm/mp3/m3u8/...).
   Add a fallback path: sniff `Content-Type` response headers for any
   `video/*`, `audio/*`, or `application/octet-stream` response over a size
   threshold, not just known extensions — this is the concrete gap between
   "detects common containers" and the "any media" claim.
5. **Notification permission UX**: currently only requested lazily on first
   download tap with no explanation. Add a one-time rationale (why Chaya
   needs it) before the system dialog, per Android's permission best
   practices.

## Phase 1 — Instrumented & integration tests (2–3 PRs)

JVM unit tests alone don't prove downloads work end-to-end. Add:

- **Robolectric** tests for `DownloadManager`'s state machine (queued →
  downloading → paused → resumed → completed/failed) using a fake
  `HttpDownloader`/`StreamDownloader` and an in-memory Room DB
  (`Room.inMemoryDatabaseBuilder`), asserting on the `StateFlow<List<DownloadTask>>`
  with Turbine.
- **Espresso/Compose UI tests** for: URL bar navigation, media-sheet
  open/tap/download, downloads-screen pause/resume/cancel/delete actions,
  quality picker selection.
- **A tiny local HTTP test server** (OkHttp `MockWebServer`) serving a real
  small MP4/HLS fixture, so `HttpDownloader`/`StreamDownloader` are tested
  against actual bytes/range requests instead of mocks only — this is what
  actually proves "downloads real media," not just "calls the right methods."
- Wire an `connectedCheck` (needs an emulator — GitHub Actions
  `reactivecircus/android-emulator-runner`) into a separate CI job so PRs
  don't wait on it by default but nightly/manual runs get real device
  coverage.

## Phase 2 — Bug detection & observability system (the part with no plan yet)

This app runs on arbitrary user devices against arbitrary websites — you
need to *know* when it breaks in the field, not just when it compiles.

1. **Crash reporting**: add a lightweight, privacy-respecting crash reporter.
   Two real choices:
   - Firebase Crashlytics (free, minimal setup, industry standard) — but
     pulls in Google Play Services and a network dependency into every
     build.
   - Self-hosted: catch uncaught exceptions via `Thread.setDefaultUncaughtExceptionHandler`,
     write a structured crash report (stack trace + last N app events) to
     local storage, surface it in a "Diagnostics" screen the user can export
     as a zip/share-intent — no third-party SDK, fully offline, matches the
     "calm, trustworthy" brand register in DESIGN.md.
   Given the anti-Play-Services stance implied by "direct APK decision" in
   plan.md v0.5, start with the self-hosted approach; add Crashlytics only if
   Play Store distribution is confirmed later.
2. **In-app error taxonomy**: today `DownloadTask.errorMessage` is a raw
   truncated exception message shown verbatim to the user (`"HTTP 403:
   Forbidden"`, `"timeout"`). Build a small `DownloadError` sealed class
   (network, auth/403, storage-full, unsupported-format, cancelled-by-user,
   unknown) with a user-facing message + a retry affordance per category —
   this is both a UX and observability win: aggregate error categories to
   see what's actually failing for users.
3. **Structured event log**: a rolling in-memory + on-disk ring buffer
   (bounded size, e.g. 500 events) recording detection events, download
   state transitions, and caught exceptions with timestamps. Exportable from
   a debug-only "Diagnostics" screen (`BuildConfig.DEBUG` gated, or a
   long-press easter egg in release). This is the single highest-leverage
   piece for a solo dev debugging "it doesn't work on my phone" reports.
4. **Detection telemetry (opt-in, local-only)**: count how many media items
   get detected per domain, how many downloads start vs. complete vs. fail,
   stored locally only (no network egress) and shown in an "Insights" panel.
   Feeds directly into knowing whether detection coverage claims hold up.
5. **StrictMode + LeakCanary in debug builds**: catch main-thread disk/network
   violations and memory leaks during development, before they reach users.
   Near-zero cost to add, standard practice, currently absent.

## Phase 3 — "Any media, robustly" (2–3 PRs)

Concrete gaps versus the "download all media, any media" ambition:

- **Content-Type sniffing fallback** (see Phase 0.4) for URLs without a
  recognized extension.
- **Redirect chain handling**: confirm `OkHttpClient.followRedirects(true)`
  correctly forwards `Referer`/`Cookie` across cross-origin redirects (CDNs
  frequently redirect to a signed-URL host) — write a `MockWebServer` test
  for a 302 chain with header forwarding.
- **Authenticated media (cookies/tokens in query params)**: verify the
  session-cookie forwarding path (`sessionHeaders()`) survives when
  Referer-based auth (Range validation, hotlink protection) is in play —
  another concrete `MockWebServer` scenario.
- **Large-file / low-storage handling**: `HttpDownloader` doesn't
  pre-check available disk space before starting — add a check via
  `StatFs` and surface a clear "not enough storage" error instead of a raw
  `IOException` mid-download.
- **Blob:/MediaSource (MSE) streams**: many video sites (not just HLS/DASH)
  serve via `MediaSource.appendBuffer` with no network-visible manifest at
  all. This is explicitly out of scope for v0.1–v0.3 per plan.md's own "What
  Not to Build First" table — keep it that way; document it as a known,
  intentional limitation rather than a silent gap, so "any media" claims in
  user-facing copy get scoped honestly (e.g. "any direct file or HLS/DASH
  stream" instead of unqualified "any media").

## Phase 4 — Premium UX polish (3–4 PRs, can run in parallel with Phase 2/3)

DESIGN.md already specifies a considered, restrained design system (OKLCH
palettes, motion tokens, component vocabulary) — the gap is consistent
*application*, not design direction:

1. **Motion audit**: confirm every interactive surface (media sheet rows,
   quality picker checkboxes, download action icons) actually uses the
   `pressScale`/`ChayaMotion` tokens already defined in `ui/theme/Motion.kt`
   — spot-check suggests some rows do, some raw `IconButton`s may not.
2. **Loading/empty/error state pass**: confirm every list (detected media,
   downloads, quality picker) has a considered empty state (DESIGN.md says
   "soft icon circle + one teaching line, never 'nothing here'") and a
   considered error state, not a raw exception string.
3. **Dark/light parity check**: DESIGN.md commits to equal craft in both
   themes — do a side-by-side screenshot pass (this is where `preview_start`
   + `browser_screenshot`/device screenshots earn their keep) across both
   themes for every screen.
4. **Accessibility pass**: WCAG AA contrast check on the actual rendered
   OKLCH-derived colors (compute contrast ratios, don't eyeball), 48dp touch
   target audit on icon buttons, TalkBack pass on the browser toolbar and
   downloads list.
5. **First-run / onboarding**: currently the app opens straight into an
   empty WebView with no explanation of what the FAB/media sheet does.
   A single, skippable, one-screen "here's how Chaya works" moment (in
   keeping with "calm surfaces, alive details," not a multi-slide tour).

## Phase 5 — Distribution readiness (per plan.md v0.5, largely still valid)

- Privacy policy (required regardless of distribution channel given
  `INTERNET`/cookie access).
- Direct-APK vs Play Store decision — Play Store requires resolving the
  copyrighted-content download policy risk plan.md already flags; direct
  APK (GitHub Releases, matching the existing CI artifact) sidesteps it and
  matches "a browser-based media manager for files you own or have
  permission to download" positioning.
- App signing config for release builds (currently debug-only signing is
  implied — `validateSigningDebug` ran in this session's build, no release
  signing config exists yet).

## Suggested execution order for a cheaper model

Each numbered item below is sized to be one focused PR, verifiable by
running `./gradlew testDebugUnitTest assembleDebug` plus (once Phase 1 lands)
the instrumented suite:

1. Phase 0.1–0.3 (Media3 resume race test, Room migration, deprecated icons)
2. Phase 1 Robolectric state-machine tests for `DownloadManager`
3. Phase 0.4 (Content-Type sniffing fallback) + its `MockWebServer` test
4. Phase 2.2–2.3 (error taxonomy + structured event log) — highest
   debugging leverage for the least code
5. Phase 1 Compose UI tests
6. Phase 4 (UX polish passes) — can run concurrently with 2–5 since it
   touches mostly Compose UI files, not core logic
7. Phase 2.1 (crash reporter), Phase 3 remaining items, Phase 5

Each PR should be small enough to review in one sitting and should leave
`main` green (tests + build) — exactly the discipline this thread's first PR
already established.
