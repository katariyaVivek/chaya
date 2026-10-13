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
| [#34](https://github.com/katariyaVivek/chaya/pull/34) | yt-dlp, its solver scripts and gallery-dl keep themselves current: newer releases fetched from PyPI about once a day, verified, compiled, checked and used from the next start, with a fallback to the last good copy; shown and switchable in Diagnostics |
| [#35](https://github.com/katariyaVivek/chaya/pull/35) | A finished stream is saved as a real MP4 file (M4A for sound only), copied without re-encoding when MP4 can hold it; if that can't be done it stays in the cache, still plays, and offers Save as MP4. Older stream downloads get Save as MP4 too |
| [#36](https://github.com/katariyaVivek/chaya/pull/36) | Tabs come back after Android closes the app, with their history; up to 50 open, the four shown last kept in memory; a full-screen tab grid with pictures of the pages, search, swipe to close with Undo, and Close all tabs |
| [#37](https://github.com/katariyaVivek/chaya/pull/37) | Bookmarks and history: a star on the address, a History screen by day with search, delete, Clear history and a switch to stop saving it, a Bookmarks screen, bookmarks first among the quick sites, and suggestions under the address bar while typing; all kept on the phone only |
| [#38](https://github.com/katariyaVivek/chaya/pull/38) | YouTube downloads fetch three 10 MB pieces at a time, each written at its place in the file; a resume fetches only the pieces still missing, and a server that refuses a range gets the rest one at a time |
| [#39](https://github.com/katariyaVivek/chaya/pull/39) | Downloads as a library: a grid of what is done (videos by a frame, a post's pictures as one tile), a full-screen picture viewer with zoom, an account's ZIP opened to its files (view, share, save one), filters by kind and site, search by title, Share on every finished file, and several chosen at once to share or delete |
| [#40](https://github.com/katariyaVivek/chaya/pull/40) | A page whose renderer crashes no longer takes the whole app down: the page is opened again (once), other tabs load again when shown, and a page's popup window survives it too |
| [#41](https://github.com/katariyaVivek/chaya/pull/41) | Video in players embedded from another server is found: a small script inside the frame reports what its player loaded (even a stream whose address and type say nothing), the page's detector passes it on, and only addresses the browser really loaded are kept |
| [#43](https://github.com/katariyaVivek/chaya/pull/43) | A page's drop-down (7movies' season list) no longer closes debug builds: LeakCanary is installed without the watcher that threw on WebView's dialogs. Swipe sideways along the bottom bar to switch to the tab beside this one |
| [#44](https://github.com/katariyaVivek/chaya/pull/44) | A player in a frame no longer lists its stream's pieces as videos, one more with every piece played, nor its playlist of seek thumbnails; a page titled with the site's slogan names its video after the page's heading. Also: a public Instagram post of pictures is saved without a sign-in (it showed "unavailable") |
| [#45](https://github.com/katariyaVivek/chaya/pull/45) | A page's name is found when its heading is not a lone h1 (the biggest title near the top); a lookup of an Instagram post or a tweet says "Finding the post…", since it may hold only pictures; stream requests carry the player's Origin, and why a stream could not be saved as MP4 is in Diagnostics |

## Next

The six-part plan after v0.4.0 has shipped (#34 to #39); it is in
[`history/plan-after-v0.4.md`](history/plan-after-v0.4.md). What is left:

0. **First: 7movies.ac.** Since #44 the sheet shows one stream, but still named after the site's
   slogan (its title heading is not a lone h1), and the owner reports the episode still does not
   download (an earlier try saved 729 B). #45 looks for the heading as the biggest title near the
   top of the page. The download "finished" with 721.8 KB that could not be saved as MP4: a short
   decoy or error stream, most likely. #45 sends the player's Origin with stream requests and writes
   why a save failed to Diagnostics (tag `stream-save`), and what the server answered for the
   first six requests of each stream download (`stream-fetch`: type and length, or the HTTP
   status). The 721.8 KB copy played as 0:00, so it held no video. Read both if it still fails.
1. **Check the video-site path end to end on a phone.** The engine, the join and the UI have each
   been tested, but a real lookup followed by a real download has not run in one piece. A
   walkthrough scenario for it would make that repeatable.
2. **Decide whether YouTube is practical on a phone.** `device-tests.yml` logs how long and how much
   memory the solver needs on YouTube's current player (`ChayaDevice` in `device-numbers.txt`, a
   run artifact). The first full run (all 17 device tests passing) has those numbers; read them
   before deciding.
3. **More picture formats for joining.** Only H.264 is joined today. HEVC and AV1 muxing depends on
   the Android version and has not been verified, so those qualities are not offered.
4. **Time YouTube's three pieces at once on a phone** (#38). Compare a long YouTube download with
   v0.4.0. If YouTube limits speed by address rather than by connection, change
   `HttpDownloader.PIECES_AT_ONCE`.
5. **A fresh address after a 403.** A YouTube download refused twice fails and keeps what it has,
   but Retry asks with the same, possibly expired, address. Asking the engine again first would
   let Retry carry on by itself.
6. **Downloads on Wi-Fi only**, as an opt-in setting, **off by default**. The owner mostly downloads
   on mobile data, so nothing may ever wait for Wi-Fi unless the person turns this on.
7. Ads inside YouTube's own videos need uBlock Origin's scriptlets, which the blocker does not run.
   Revisit only if a safe, narrow way appears.

## Known rough edges

- The emulator walkthrough sometimes loses the emulator itself: the app is reported as not running,
  then `adb` goes silent. It happened twice after a stream download that pulled in an extra 720p
  variant (fixed since), once each on #35 and #37, and twice running on #39. Each time it was
  loading the local test page, whose ad and looping background play by themselves in every tab that
  shows it, decoded on the host. Since #39 those two clips are 160×90 at 2 frames a second. After it
  happened once more on #40, the main clip stopped autoplaying too, and since #41 the emulator has
  6 GB instead of 4. It happened twice more on #43, once at the very first screen, so it is likely the
  runner's memory: since #43 the build's Gradle and Kotlin daemons are stopped before the emulator
  starts, and a failed run prints any out-of-memory kill. If it still happens, split the walkthrough
  across two emulator runs.
- Gradle's parallel project execution is off: with Chaquopy it made `kspDebugKotlin` fail on a
  project lock. The app is a single module, so nothing is lost.
