# Chaya

**A native Android browser with smart media detection and a robust download manager.**

Open a page → Chaya finds the media flowing through it → tap → downloaded. Direct files (MP4, WebM, MP3…), extensionless endpoints verified by content-type, and HLS/DASH streams with a quality picker — with pause, resume, retry, and a classified error taxonomy throughout.

> Chaya (Sanskrit): light and shadow, speed. The interface follows: **fluid, assured, soft** — calm surfaces, confident motion, zero clutter.

[![Build APK](https://github.com/katariyaVivek/chaya/actions/workflows/build.yml/badge.svg)](https://github.com/katariyaVivek/chaya/actions/workflows/build.yml)
![Latest release](https://img.shields.io/github/v/release/katariyaVivek/chaya)

**Latest release:** [v0.3.0](https://github.com/katariyaVivek/chaya/releases/tag/v0.3.0) — signed APK attached, install directly.

---

## What it does

| Layer | How |
|---|---|
| **Browse** | Full WebView browser: URL bar, back/forward/refresh, fullscreen video, retained session |
| **Detect (network)** | `shouldInterceptRequest` observer sniffs media extensions + manifest MIME types, zero page-load interference |
| **Detect (DOM)** | Injected JS scans `<video>`/`<audio>`/`<source>`, Shadow DOM, same-origin iframes, fetch/XHR hooks, `MutationObserver` |
| **Verify (opt-in)** | Extensionless URLs (`/media/abc123`) get a redirect-free `HEAD` content-type check — only after you tap *Scan more thoroughly*, same-origin, max 10 |
| **Download files** | OkHttp: `Range` resume, session cookie/UA/Referer forwarding, Content-Disposition filenames, silent cancel, 50 MB pre-flight guard |
| **Download streams** | Media3/ExoPlayer: HLS/DASH with per-track quality picker, per-stream pause via `setStopReason`, resume-after-process-death |
| **Persist** | Room database (explicit migrations, never destructive), foreground service + notifications |

### Security model (unusual for a downloader, deliberate)

- The JS bridge requires a **256-bit per-navigation capability** injected only into the top-level document — cross-origin iframes cannot invoke native code. Verified by tests that attack the bridge with stale/invalid capabilities.
- The content-type verifier **never follows redirects**, so page cookies can't be replayed to another origin.
- Detection state is **generation-scoped**: late callbacks from a retired document cannot overwrite the new page's media list.

---

## Technical depth

**Two detection layers, one sheet.** Network-level (`MediaInterceptor`) and DOM-level (`MediaBridge` + `chaya_media_detector.js`) run in parallel, deduplicate by normalized URL, and merge into a single bottom sheet with source attribution (NETWORK / DOM / XHR_FETCH / MANIFEST).

**State machine, tested.** `DownloadManager` owns `QUEUED → DOWNLOADING ⇄ PAUSED → COMPLETED | FAILED | CANCELLED` with explicit task IDs shared across memory, Room rows, and downloader callbacks. 100+ tests pin every transition, including kill-mid-download recovery (persisted rows flip to PAUSED, Media3 content IDs derive from task IDs so resume finds the original download, not a duplicate).

**Failures are classified, not dumped.** `DownloadError` taxonomy (Network / HttpStatus / StorageFull / UnsupportedFormat / Cancelled / Unknown) with human messages and per-type retry affordance — retry is hidden for 404s and full disks, offered for 5xx and timeouts. The classifier, the Room `2→3` migration carrying it, and the UI mapping are all covered.

**Observability without telemetry.** On-device structured event log (500-entry ring + rotating files, URLs scrubbed of query/fragment at the boundary), self-hosted crash reporter (writes a file, chains to the default handler, user shares manually — nothing uploaded, ever), and opt-in local insights (per-domain detection counts, success rate). No analytics SDK, no account, no server.

**Test pyramid.** JVM unit tests (JUnit + MockK + Turbine + Robolectric) → MockWebServer integration tests proving byte-exact transfer, `Range` semantics, redirect header forwarding, and cancel contracts over real HTTP → Robolectric-hosted Compose UI tests (`createComposeRule`, no emulator) → emulator instrumented tests on a nightly/manual job. CI runs the full suite + debug APK on every PR; test-only PRs skip the APK leg; tags publish signed releases.

**Design system.** `DESIGN.md` specifies the whole register: OKLCH-computed palette (warm Porcelain light, Violet Charcoal dark — no pure black/white), tight type scale, spring-only motion tokens (`pressScale`, `springSmooth`), tonal-circle component vocabulary. The launcher icon (vessel ring + droplet-arrow) is drawn from the same tokens.

---

## What it doesn't do

Deliberate scope boundaries, documented in [`LIMITATIONS.md`](LIMITATIONS.md): no MediaSource/blob-only players (technically exotic — would need a custom media pipeline or Chromium hooks), no YouTube/DRM/ToS-restricted services. Chaya is for files you own or have permission to download.

---

## Project map

```
app/src/main/java/com/chaya/app/
├── browser/       WebView shell, ViewModel, retained-callback router
├── detection/     MediaInterceptor, MediaBridge, ContentTypeSniffer, injected JS
├── download/      DownloadManager, HttpDownloader, MediaDownloader seam,
│                  DownloadError taxonomy, foreground service + notifications
├── streaming/     StreamDownloader (Media3), ManifestHelper, track models
├── database/      Room DB (v3, explicit migrations), DAO, entity + error codec
├── diagnostics/   EventLog, ChayaEvent, CrashReporter, Insights, viewer screen
├── downloads/     Downloads list UI + ViewModel
├── ui/            Compose theme (Color/Type/Theme/Motion), sheets, player, nav
└── model/         DetectedMedia, DownloadTask
```

| Doc | What |
|---|---|
| [`BUILD_AND_TEST.md`](BUILD_AND_TEST.md) | Build, test, release-signing, distribution decision |
| [`CHAYA_ROADMAP.md`](CHAYA_ROADMAP.md) | Full engineering spec, PR by PR (all phases complete) |
| [`PRODUCT.md`](PRODUCT.md) / [`DESIGN.md`](DESIGN.md) | Product register, brand personality, design tokens |
| [`LIMITATIONS.md`](LIMITATIONS.md) | Explicit non-goals |
| [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | What Chaya accesses, stores, and never does |
| [`plan.md`](plan.md) / [`aloha_core_analysis.md`](aloha_core_analysis.md) | Original plan, Chromium-fork research |

---

## Build & install

**Easiest:** download `app-release.apk` from [Releases](https://github.com/katariyaVivek/chaya/releases) and install (allow unknown apps when asked).

**From source:** JDK 17 + Android SDK 35, then `gradle testDebugUnitTest assembleDebug` (no checked-in wrapper — install Gradle 8.9 or open once in Android Studio). Full guide in [`BUILD_AND_TEST.md`](BUILD_AND_TEST.md).

---

## Privacy

Local-first, stated plainly in [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md): browsing cookies are forwarded only to download requests for media you're viewing; history lives in on-device Room; diagnostics never leave the phone unless you tap Share. No analytics, no ads, no accounts.
