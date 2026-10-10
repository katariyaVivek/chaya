<img src="docs/brand/chaya-icon.svg" width="96" alt="Chaya's icon: a media tile and its shadow-copy">

# Chaya

**A native Android browser that finds the media on a page, and saves it.**

Open a page → Chaya finds the video → tap → saved, under the video's real name. It handles direct
files, HLS/DASH streams with a quality picker, and links from YouTube, Instagram, TikTok and X, with
pause, resume and retry throughout.

> Chaya (Sanskrit): light and shadow, speed. The interface follows: **fluid, assured, soft**: calm
> surfaces, confident motion, zero clutter.

[![Build APK](https://github.com/katariyaVivek/chaya/actions/workflows/build.yml/badge.svg)](https://github.com/katariyaVivek/chaya/actions/workflows/build.yml)
![Latest release](https://img.shields.io/github/v/release/katariyaVivek/chaya)

**Install:** download `app-release.apk` from [Releases](https://github.com/katariyaVivek/chaya/releases)
and allow installs from unknown apps when asked.

---

## What it does

| | |
|---|---|
| **Browse** | A full browser: address bar, back/forward, fullscreen video, a session that survives leaving the page |
| **Find** | Network sniffing and an injected page scanner (Shadow DOM, iframes, fetch/XHR) run together; extensionless media is checked only when you ask |
| **Make sense of it** | The page's main video comes first with its title; likely ads are folded away; stream pieces are hidden; one entry per quality |
| **Video sites** | Paste, share or browse a YouTube, Instagram, TikTok or X link: yt-dlp, running on the phone, finds the qualities; picture and sound are joined into one MP4. Instagram and X posts with pictures (gallery-dl) save every photo |
| **Block ads** | EasyList and EasyPrivacy, the lists uBlock Origin starts with, block ad and tracker requests, hide ad boxes and refuse ad pop-ups; off for any site with one switch |
| **Download** | Files over OkHttp with `Range` resume; HLS/DASH through Media3; a foreground service with progress, pause and cancel |
| **Keep** | Download history in Room with explicit migrations; finished files appear in your Movies, Music, Pictures or Downloads |

### Privacy by construction

- The page-to-app bridge needs a **256-bit capability issued per navigation**, given only to the top
  document; cross-origin iframes cannot call native code.
- Media-type checks are **same-origin, redirect-free and opt-in**, so your cookies cannot be sent
  elsewhere.
- Diagnostics stay on the phone (addresses scrubbed before logging); your sign-in on a video site is
  used **only when you tap to allow it**, and the temporary copy is deleted right after.
- The ad blocker's lists are fetched weekly from easylist.to with nothing about you attached;
  blocked requests never leave the phone.
- No analytics, no ads, no account, no server. Details in [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md).

---

## Engineering highlights

- **A tested state machine.** `QUEUED → DOWNLOADING ⇄ PAUSED → COMPLETED | FAILED | CANCELLED`, with
  task IDs shared by memory, Room and the downloaders, so a download killed mid-way resumes instead
  of starting over.
- **Failures are classified.** Network, HTTP status, storage full, unsupported, could-not-combine:
  each has a plain message and decides whether *Retry* is offered.
- **Two-file downloads that survive anything.** Picture, then sound, then a lossless `MediaMuxer`
  join. What is left to do is worked out from the files present, so pause, a killed app and a
  failed join all recover without downloading again.
- **Tests at every level.** JVM and Robolectric tests, MockWebServer transfers over real HTTP, a
  Media3 stream pipeline served locally, pytest for the Python engine, on-device tests for yt-dlp,
  QuickJS and the join, and an emulator walkthrough that uploads screenshots.
- **A design system in code.** OKLCH-derived palette (warm Porcelain light, Violet Charcoal dark),
  spring-only motion tokens, one component vocabulary. See [`DESIGN.md`](DESIGN.md).

How it fits together: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## What it doesn't do

No DRM-protected services, no `MediaSource`-only players outside the supported sites, no live
broadcasts. Chaya is for media you own or have permission to download. See
[`LIMITATIONS.md`](LIMITATIONS.md).

---

## Project map

```
app/src/main/java/com/chaya/app/
├── adblock/       ad blocker: filter engine (plain Kotlin), lists and weekly update, per-page sessions
├── browser/       WebView shell, address bar, view model
├── detection/     interceptor, JS bridge, ranking, naming, ad hosts, content-type checks
├── platform/      video-site links: matcher, yt-dlp engine, JS solver, format selection, sign-in
├── download/      DownloadManager, OkHttp downloader, MP4 join, errors, foreground service
├── streaming/     Media3 HLS/DASH downloads and manifest tracks
├── database/      Room (explicit migrations)
├── diagnostics/   event log, crash reports, local insights
├── downloads/     Downloads screen
└── ui/            theme, sheets, player, navigation
app/src/main/python/chaya_engine/   yt-dlp wrapper run through Chaquopy
app/src/main/assets/detection/      the injected page scanner
app/src/main/assets/adblock/        EasyList and EasyPrivacy as shipped
```

The ad lists in `app/src/main/assets/adblock/` are [EasyList and EasyPrivacy](https://easylist.to/),
by The EasyList authors, used under the
[Creative Commons Attribution-ShareAlike 3.0](https://creativecommons.org/licenses/by-sa/3.0/) licence.

## Docs

| | |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How the app works, and where its tests live |
| [`docs/BUILD_AND_TEST.md`](docs/BUILD_AND_TEST.md) | Toolchain, commands, CI, releases, a manual check |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | What is merged, released and next |
| [`PRODUCT.md`](PRODUCT.md) · [`DESIGN.md`](DESIGN.md) | Product register and design system |
| [`LIMITATIONS.md`](LIMITATIONS.md) · [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) | Scope and privacy |
| [`CLAUDE.md`](CLAUDE.md) | Guide for coding agents |
| [`docs/history/`](docs/history/README.md) | Finished plans and research |

## Build from source

JDK 17, Android SDK 36, Gradle 8.14.3 and Python 3.13, then
`gradle testDebugUnitTest assembleDebug`. There is no checked-in wrapper; see
[`docs/BUILD_AND_TEST.md`](docs/BUILD_AND_TEST.md).
