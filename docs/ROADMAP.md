# Roadmap

Where Chaya stands and what comes next. Update this file in the PR that changes it. Finished
planning documents live in [`history/`](history/README.md).

## Released

**v0.3.0** (September 2026): browser with network and DOM detection, file and HLS/DASH downloads
with pause/resume/retry, classified errors, on-device diagnostics, signed APK on GitHub Releases.
The PR-by-PR record is [`history/engineering-roadmap-v0.3.md`](history/engineering-roadmap-v0.3.md).

**v0.4.0** (October 2026): a smarter media list (main video first, real names, ads folded away,
one entry per quality), the redesigned UI, and links from YouTube, Instagram, TikTok and X looked
up by yt-dlp on the phone, with picture and sound joined into one MP4. Merged as a stack:

| PR | What it adds |
|---|---|
| [#17](https://github.com/katariyaVivek/chaya/pull/17) | Main video first with real names, ads folded away, stream pieces hidden, one quality-picker entry per rendition; the `ui-check` emulator walkthrough; fixes that let stream downloads start at all |
| [#18](https://github.com/katariyaVivek/chaya/pull/18) | Redesigned Downloads screen, address bar, start screen, download pill, one-tap download |
| [#19](https://github.com/katariyaVivek/chaya/pull/19) | yt-dlp inside the app (Chaquopy, QuickJS solver) and the format selector. Also the toolchain moves: Kotlin 2.4, AGP 8.13, Gradle 8.14, compileSdk 36 |
| [#20](https://github.com/katariyaVivek/chaya/pull/20) | Picture plus sound saved as one MP4 (`MediaMuxer`), engine-supplied headers, Room v5 |
| [#21](https://github.com/katariyaVivek/chaya/pull/21) | On-device tests for the engine, the solver and the join |
| [#22](https://github.com/katariyaVivek/chaya/pull/22) | Paste or share a YouTube, Instagram, TikTok or X link and pick a quality; optional use of the browser's sign-in |
| [#23](https://github.com/katariyaVivek/chaya/pull/23) | Follow-up from review: a video-site answer is looked up again once its addresses may have expired; DASH told apart by protocol |

## Merged since v0.4.0

| PR | What it adds |
|---|---|
| [#25](https://github.com/katariyaVivek/chaya/pull/25) | The new icon (an original and its shadow-copy) and a Light / Dark / Same as phone setting |
| [#26](https://github.com/katariyaVivek/chaya/pull/26) | YouTube downloads fetched in 10 MB ranges, as yt-dlp does, so they are not held to playback speed |
| [#27](https://github.com/katariyaVivek/chaya/pull/27) | Every picture and video in an Instagram or X post (gallery-dl), saved together or one at a time |
| [#28](https://github.com/katariyaVivek/chaya/pull/28) | Everything an Instagram or X account has posted, as one ZIP; with the person's sign-in only when they choose it |
| [#29](https://github.com/katariyaVivek/chaya/pull/29) | An account ZIP asks again for a file that fails on the way, and leaves out one that never comes instead of stopping |
| [#30](https://github.com/katariyaVivek/chaya/pull/30) | EasyList and EasyPrivacy built in: ad and tracker requests blocked, ad boxes hidden, ad pop-ups refused; on/off and per site from the address bar's shield; lists refreshed weekly |
| [#31](https://github.com/katariyaVivek/chaya/pull/31) | A finished download's notification comes once, not again with every later download |
| [#32](https://github.com/katariyaVivek/chaya/pull/32) | Up to ten tabs, each with its own page and history; links that open a new window open a new tab. Also: a page's first ads are blocked too |

## Next

The working plan for the next features, in order and in detail, is [`PLAN.md`](PLAN.md): yt-dlp
keeping itself current, streams saved as MP4, tabs restored in a tab grid, bookmarks and history,
faster YouTube, and a downloads library. The list below is the longer view.

1. **Save streams as real MP4 files.** HLS/DASH downloads live in Media3's cache today, so they
   play in the app but cannot be shared or opened elsewhere. The redesigned UI (#18) is waiting
   for this before it offers Share and *Save as MP4* for streams.
2. **Check the video-site path end to end on a phone.** The engine, the join and the UI have each
   been tested, but a real lookup followed by a real download has not run in one piece. A
   walkthrough scenario for it would make that repeatable.
3. **Decide whether YouTube is practical on a phone.** `device-tests.yml` logs how long and how much
   memory the solver needs on YouTube's current player (`ChayaDevice` in `device-numbers.txt`, a
   run artifact). The first full run (all 17 device tests passing) has those numbers; read them
   before deciding.
4. **Keep yt-dlp current.** Sites change often and the bundled yt-dlp is pinned; self-update (or a
   release cadence that tracks yt-dlp) is not built yet.
5. **More picture formats for joining.** Only H.264 is joined today. HEVC and AV1 muxing depends on
   the Android version and has not been verified, so those qualities are not offered.

## Known rough edges

- The emulator walkthrough lost the emulator itself twice (the app reported as not running, then
  `adb` silent), both times after a stream download that pulled in an extra 720p variant (190 MB
  for a 184p pick). Since that bug was fixed (184p now saves 21 MB) the walkthrough has passed. If
  the emulator dies again, give it more memory or play a lighter video.
- Gradle's parallel project execution is off: with Chaquopy it made `kspDebugKotlin` fail on a
  project lock. The app is a single module, so nothing is lost.
