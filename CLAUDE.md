# CLAUDE.md

Guidance for coding agents working in this repository. `AGENTS.md` points here; keep this the one
copy.

## What this is

Chaya is a native Android app (Kotlin, Jetpack Compose, one `:app` module): a WebView browser that
detects the media on a page and downloads it, plus links from YouTube, Instagram, TikTok and X
looked up by yt-dlp running inside the app. It is a working app with releases, not a plan.

## Read first

| Doc | For |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How the app is put together, package by package, and where each kind of test lives |
| [`docs/BUILD_AND_TEST.md`](docs/BUILD_AND_TEST.md) | Toolchain, commands, CI workflows, releases |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | What is released, merged, and next |
| [`docs/PLAN.md`](docs/PLAN.md) | The working plan for the next features, and how work has gone; start here when resuming |
| [`DESIGN.md`](DESIGN.md), [`PRODUCT.md`](PRODUCT.md) | Design tokens and product register; UI work must follow them |
| [`LIMITATIONS.md`](LIMITATIONS.md), [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Scope and privacy promises; a change that affects either must update it |

`docs/history/` is background only; do not treat it as current. `aloha-core-study/` is a gitignored
local checkout of Aloha Browser's source, used for that research; it is not in the repository.

## Commands

```sh
gradle testDebugUnitTest      # all JVM/Robolectric tests; run before every push
gradle assembleDebug
python -m pytest app/src/test/python
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

## Things that have bitten us

- Media3's `DownloadManager.currentDownloads` leaves out completed and failed downloads; look those
  up in `downloadIndex`. Used without a `DownloadService`, the manager also starts paused.
- `HttpDownloader` must keep a call registered until its body is read, or pause/cancel during the
  transfer do nothing.
- Kotlin's inlined functions report line numbers beyond the end of the file in stack traces;
  find the real call site, not the reported line.
- Gradle parallel project execution stays off (Chaquopy + KSP fail on a project lock).
- quickjs-kt requires compileSdk 36 and a Kotlin compiler that reads 2.4 metadata; KSP 2.3 needs
  AGP 8.13 or newer. Upgrade these together.
