# CLAUDE.md

Guidance for coding agents working in this repository. `AGENTS.md` points here; keep this the one
copy.

## What this is

Chaya is a native Android app (Kotlin, Jetpack Compose, one `:app` module): a WebView browser that
detects the media on a page and downloads it, plus links from YouTube, Instagram, TikTok and X
looked up by yt-dlp running inside the app. It is a working app with releases, not a plan. A
Windows version (Tauri 2, in `desktop/`) has started.

## Read first

| Doc | For |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How the app is put together, package by package, and where each kind of test lives |
| [`docs/BUILD_AND_TEST.md`](docs/BUILD_AND_TEST.md) | Toolchain, commands, CI workflows, releases |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | What is released, merged, and next; start here when resuming |
| [`DESIGN.md`](DESIGN.md), [`PRODUCT.md`](PRODUCT.md) | Design tokens and product register; UI work must follow them |
| [`desktop/design/README.md`](desktop/design/README.md) | The Windows app's own design system (tokens, components, motion); desktop UI work follows it |
| [`LIMITATIONS.md`](LIMITATIONS.md), [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Scope and privacy promises; a change that affects either must update it |

`docs/history/` is background only; do not treat it as current. `aloha-core-study/` is a gitignored
local checkout of Aloha Browser's source, used for that research; it is not in the repository.

## Commands

```sh
gradle testDebugUnitTest      # all JVM/Robolectric tests; run before every push
gradle assembleDebug
python -m pytest app/src/test/python
cd desktop && cargo test --workspace   # Chaya for Windows (desktop/README.md)
```

There is no checked-in wrapper jar; CI calls `gradle` directly. Building needs JDK 17, Android SDK
platform 36, Gradle 8.14.3 and Python 3.13 (Chaquopy). Versions are in `gradle/libs.versions.toml`.

## Working rules

- **Tests come with the change.** Bug fixes get a test that fails without the fix. Network code is
  tested against MockWebServer, not the internet. Work that only a device can check (Python,
  QuickJS, `MediaMuxer`) gets an `androidTest` and runs on the `ui-check` label.
- **Room migrations are explicit.** Bump the version, add a `Migration` and a migration test; never
  destructive migration.
- **Privacy boundaries are features.** Keep: the per-navigation bridge capability, redirect-free
  same-origin content-type checks only after the person opts in, URLs scrubbed before logging,
  nothing uploaded, sign-in cookies used only when the person chooses and deleted afterwards.
- **Stacked PRs.** Larger work goes up as a stack, merged in order. To update a PR, merge its base
  branch into it; do not rebase or force-push shared branches. A fix that belongs low in the stack
  goes into the lowest PR that needs it and is then merged upward, one branch at a time.
- **Commit messages** say what changed for the person using the app and why, in plain sentences,
  and name what verified it.
- **One PR per piece of work**, merged once every check is green. Add the `ui-check` label to any PR
  that touches the browser, downloads or anything only a device can show: it runs the instrumented
  tests and the emulator walkthrough, which have caught real bugs (#28, #32).
- **CI is the Android build.** In the cloud container the proxy blocks `dl.google.com`, so the
  Android Gradle plugin cannot be fetched. Pure-Kotlin code can still be compiled and tested with a
  scratch Kotlin/JVM Gradle project whose source set points at the repo's files; keep such code free
  of Android imports so this stays possible.
- **Chaya for Windows** (`desktop/`) is Tauri 2. Code that does not need the window goes in
  `desktop/core`, plain Rust, tested with `cargo test`. Its UI follows `desktop/design`.
- **pytest rewrites tracked `.pyc` files** under `__pycache__/`; run
  `git checkout -- '*__pycache__*'` before committing.

## Things that have bitten us

- Media3's `DownloadManager.currentDownloads` leaves out completed and failed downloads; look those
  up in `downloadIndex`. Used without a `DownloadService`, the manager also starts paused.
- Media3 recognises HLS only by the exact type `application/x-mpegURL`; given
  `application/vnd.apple.mpegurl` it treats the address as one plain file. Pass every stream type
  through `StreamMime.forMedia3` before it reaches Media3.
- `HttpDownloader` must keep a call registered until its body is read, or pause/cancel during the
  transfer do nothing.
- Every WebView must handle `onRenderProcessGone`, including the throwaway one a page's popup gets.
  Otherwise one renderer crash ends the whole app, with no Java crash report.
- LeakCanary's root view watcher reads its own resources through every dialog, and WebView's dialogs (a
  `<select>` list, a date picker) carry WebView's resources, so debug builds closed on any drop-down. Debug
  builds install LeakCanary without it (`app/src/debug/.../LeakWatching.kt`).
- Kotlin's inlined functions report line numbers beyond the end of the file in stack traces;
  find the real call site, not the reported line.
- Gradle parallel project execution stays off (Chaquopy + KSP fail on a project lock).
- quickjs-kt requires compileSdk 36 and a Kotlin compiler that reads 2.4 metadata; KSP 2.3 needs
  AGP 8.13 or newer. Upgrade these together.
