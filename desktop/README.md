# Chaya for Windows

The desktop version of Chaya, built with [Tauri 2](https://v2.tauri.app/). The window is web content
drawn with the design system in [`design/`](design/README.md), shown by Windows' own WebView2
(Chromium). The work is done in Rust.

It is early. This build opens the window, follows Windows' light or dark setting (or the person's
choice), and tells what a pasted link is: a video or post on YouTube, Instagram, TikTok or X, an
account, another page, or words. A video or post is looked up with yt-dlp, as on the phone, and the
window shows its title, poster and the qualities it can be saved in (or what a post holds, or why it
can't be). Each choice downloads into the Downloads folder, with pause, resume, cancel and retry, and
the Downloads list keeps track across restarts. Qualities whose picture and sound come apart (most of
YouTube's) are fetched as two files and joined into one MP4 by a cut-down ffmpeg the app carries, and
YouTube's files come in pieces, three at a time. Qualities that come as streams, HLS (X's, a plain `.m3u8`
link) or DASH (Vimeo's, Reddit's), are fetched piece by piece and saved as an MP4. Anything else typed in
the address bar (a page, a site, an account, words to search for) opens in the built-in browser, and a
video, sound or picture a page downloads is saved in the Downloads list. What a page's players load is
found and listed above the page, its main video first, each with a Download button
([`../docs/ROADMAP.md`](../docs/ROADMAP.md)).

## Layout

| Path | What it is |
|---|---|
| `design/` | The design system: tokens, fonts, components, motion. Plain CSS and an ES module |
| `core/` | `chaya-core`, plain Rust with no window or web view in it, so it builds and tests anywhere. `links` recognises links (the phone's `PlatformMatcher` and `ProfileMatcher`); `engine` runs the engine; `media` reads its answer (`PlatformMedia`); `formats` picks one choice per quality (`FormatSelector`); `lookup` is what the window is sent. Each is ported with the phone's tests. `download` saves files: `http` fetches one with resume (`HttpDownloader`), `manager` keeps every download and its state (`DownloadManager`), `plan` turns a choice into downloads, `names` makes Windows-safe names, `error` explains failures (`DownloadError`), `join` joins picture and sound, and turns a stream into an MP4, with ffmpeg, `pieces` fetches YouTube's files in pieces (`PieceLog`), `stream` fetches a stream's pieces (HLS, DASH). `browse` holds the browser's rules: what typed text opens, where a page may go, and which page downloads Chaya keeps. `found` turns what a page's detector saw into the list the window shows (`MediaUrlClassifier`, `AdHosts`, and the core of `MediaRanker` and `MediaNamer`). `adblock` is the phone's ad blocker (`FilterEngine`, `CosmeticRules`, `FilterLists`, `AdBlockSettings`) with its tests |
| `engine/` | `prepare.py` puts together the engine the installer carries, in `app/src-tauri/engine/` (not in git); `run.py` starts it; `build_ffmpeg.sh` builds the cut-down ffmpeg |
| `app/ui/` | The window: `index.html`, `app.css`, `main.js` |
| `app/scripts/assemble-ui.mjs` | Puts `ui/` and the design system together in `app/dist/`, which Tauri serves. Runs before every dev run and build |
| `app/src-tauri/` | The Tauri app: window settings (`tauri.conf.json`), what the window may call (`capabilities/`), the commands that hand `chaya-core` to the window (`src/lib.rs`), the browser's page (`src/browser.rs`), and its ad blocking (`src/ads.rs`) |

Keep anything that does not need the window in `core/`, so it stays testable without one.

## Building on Windows

You need:

- Rust (stable) from <https://rustup.rs>, with the MSVC toolchain.
- Visual Studio Build Tools with *Desktop development with C++*.
- Node 22.
- Python 3.13, to put the engine together (the app carries its own Python; this one is only for building).
- WebView2, which Windows 11 already has. The installer fetches it from Microsoft on a PC without it.

Put the engine together once, and again when `app/src/main/python` or the pins in
`app/build.gradle.kts` change:

```sh
python desktop/engine/prepare.py --ffmpeg path/to/ffmpeg.exe
```

`ffmpeg.exe` is the cut-down build that joins picture and sound and saves streams. `desktop/engine/build_ffmpeg.sh windows
OUT_DIR` makes it on Linux or in WSL (it cross-compiles with mingw-w64); or take it from the `ffmpeg`
artifact of any `desktop.yml` run. Without `--ffmpeg` the app works, but offers no quality that needs
joining and no stream.

Then, in `desktop/app`:

```sh
npm ci           # the Tauri CLI
npm run dev      # the app, rebuilt as you change it
npm run build    # the installer, in desktop/target/release/bundle/nsis/
```

For titles in Cabinet Grotesk, download it once into `design/fonts/` first
([`design/fonts/README.md`](design/fonts/README.md)). Its licence keeps it out of git, so installers
built by CI use Geist for titles.

## The engine

Looking a link up is the phone's engine, `chaya_engine` (`app/src/main/python`), with the same pinned
yt-dlp, yt-dlp-ejs and gallery-dl. The phone runs it inside the app through Chaquopy; Windows has no
Python inside the app, so the installer carries one:

- `engine/python/`: Python 3.13's Windows embeddable package (3.13.17, the last 3.13 with Windows
  downloads), checked against python.org's SHA-256.
- `engine/packages/`: the pinned packages from PyPI (Windows wheels, compiled ahead of time), and
  `chaya_engine`.
- `engine/qjs.exe`: QuickJS-ng 0.17.0, checked against its release's SHA-256. yt-dlp runs YouTube's
  challenge scripts in it, where the phone uses its embedded QuickJS through `jsc_provider`.
- `engine/run.py`: what the app starts, `python -I run.py extract URL --cache DIR --fragments --js-runtime
  qjs.exe` (`chaya_engine/cli.py`). It prints one line of JSON, the same answer the phone reads;
  `--fragments` adds each DASH format's pieces as yt-dlp listed them, which the phone, handing the manifest
  to Media3, never asks for.
- `engine/ffmpeg.exe`: FFmpeg 8.1.3, built by `build_ffmpeg.sh` from the release's commit with
  everything left out but copying tracks into an MP4: from MP4, M4A and WebM files, and from a stream's
  pieces (MPEG-TS, fragmented MP4, bare AAC or MP3). No encoders, no network, no outside libraries; its
  one decoder is AAC's, which MPEG-TS sound needs for its sample rate to be known. About 2.2 MB, under
  the LGPL; its licence and source are in `engine/licenses/`.

`chaya-core`'s `engine` starts it with no console window and without yt-dlp's plugins
(`YTDLP_NO_PLUGINS`: an add-on in the person's own yt-dlp folders would otherwise run inside it and see what
it is given, a sign-in included), one lookup at a time, stops it after two minutes, and keeps answers for
twenty minutes. Only two kinds of address are ever passed to it: links
`links` recognises as a video or post, and a stream's own address that the browser found on a page, when
the person asks for its qualities (`--referer` names the page, as its player would). A change to
`chaya_engine` must keep both apps working: pytest covers it for both.

Off Windows, `python desktop/engine/prepare.py --host` makes an engine for the computer's own Python (set
`CHAYA_ENGINE_PYTHON` to it, or `python3` is used), without QuickJS, for running the app in development.

## Downloads

- A choice becomes one download; a post's "Save all" becomes one per picture and video, numbered.
  Files are named "Title (720p).mp4" ("Title.m4a" for the sound), with what Windows forbids replaced,
  and get "(2)" when the name is taken. They go to the person's Downloads folder.
- Each is fetched into "name.part" with the site's own headers and renamed when whole. A paused or
  interrupted download continues with a `Range` request; a server that ignores it starts the file
  again; a dropped connection is tried twice more before the download fails.
- Up to three run at once. The list is `downloads.json` in the app's data folder, written on every
  change of state; downloads running when the app closed come back paused.
- **Retry** looks the link up again first, because the sites' addresses expire, and carries on from
  what arrived. Cancel deletes what arrived; removing a finished download from the list keeps the file.
- **Picture and sound apart** (most of YouTube's qualities): the picture is fetched into
  "name.picture", then the sound into "name.sound", then ffmpeg copies both into one MP4 (nothing is
  re-encoded, so it takes seconds and loses nothing) with its index at the front, and the halves are
  deleted. What is left to do is worked out from which halves exist, so pause, a closed app and Retry
  carry on from the right step. The row says which half is on its way, then "Joining picture and
  sound". Any MP4 picture joins: H.264, and the AV1 and HEVC of YouTube's higher qualities.
- **YouTube's files** (`googlevideo.com`) come in 10 MB pieces, three at a time, each written at its
  place, as on the phone: one request goes at about the speed of playback. "name.part.pieces" lists
  the whole pieces, so a resume asks only for the rest. A server that answers a piece with anything but
  that piece, or a file of another length than yt-dlp said, gets the rest the ordinary way.
- **Streams** (`stream`): for HLS, the playlist is read (for a playlist of qualities, the best one's); for
  DASH, the pieces are those yt-dlp listed from the manifest, its initialization section first. They are
  fetched in order, three at a time, with the site's headers, into "name.stream". Pieces
  encrypted with AES-128 are decrypted with their key; a stream with DRM (SAMPLE-AES, or a key only a
  DRM system opens) is refused, as on the phone. "name.stream.part.segments" counts the pieces written,
  so a resume asks only for the rest. Once whole, ffmpeg copies the stream into an MP4 ("Saving as
  MP4"), or an M4A for sound only. A picture-only stream is joined to its sound like a picture file.
  The size shown is yt-dlp's estimate until a few pieces have arrived, then worked out from them.
- What is chosen for a quality: a ready-made file, then picture and sound files joined, then a stream with
  both, then a picture-only stream joined to sound. A stream whose picture is VP8 or VP9 in WebM is not
  offered: MP4 must be told that picture's settings, which only a decoder could read, and the app's
  ffmpeg has none for pictures. The card says some qualities can't be saved yet when a link has some the
  app cannot save.

## The built-in browser

- Typing a page's address, a bare site (`example.com`), an account's address, or words opens the page
  inside the window; words search Google, as on the phone. A link to a video or post on the four sites
  is looked up instead, as before.
- The page is a WebView2 webview of its own (Tauri's multiwebview, the `unstable` feature), drawn over
  the window where the window's `.page` leaves room for it. The window draws the bar around it (back,
  forward, reload or stop, Downloads, close), tells the app where the page goes whenever its layout
  changes, and hides the page while it shows the downloads or the settings in its place.
- **A page gets nothing of the app.** The app's commands are granted to the window's own webview only
  (`capabilities/default.json`: `webviews: ["main"]`, not the whole window), and a page from the web is
  never local, so a page asking for a command is refused. A page can go only to the web (`http`, `https`,
  `about:blank`; never a file on the PC or another program's link). A page opening another window is held
  back, and the window says so with *Open it here*.
- A download a page starts of a video, a sound, a picture or an HLS playlist goes to the Downloads list,
  named after the file (a playlist after the page), with the page as referrer. Other files (a PDF, an
  installer) are saved as any browser saves them.
- **The browser's sign-in goes with what a page downloads**, as on the phone, so a video behind the
  person's own login saves. Whatever is saved from a page (a download it starts, a file or stream found on
  it) is marked `Source::session`; each time the download starts or resumes, the manager asks the browser's
  cookie store (`cookies_for_url`) for the cookies it holds for that address and adds them, with the
  browser's user agent, to that run's requests only. They are never written into `downloads.json`. A
  stream's pieces and keys from another server than its own get no cookies, and a redirect to another server
  drops them (reqwest). A stream's qualities are read by yt-dlp with the cookies for the stream's address in
  a cookie file (`session::CookieFile`), deleted as soon as the lookup ends.
- **What a page holds.** `src-tauri/src/detector.js` runs at the start of every document of the page,
  frames from other servers included, as the phone's detector and frame agent do between them. It
  watches what the page's players show and load: video and audio elements and their sources, what the
  browser loaded for the page (its resource timings), and the page's fetch and XHR answers that are media
  by their name, their type, or a playlist's first characters (`#EXTM3U`, `<MPD`). A frame tells the
  page's document by `postMessage`, with a nonce only the script knows. The app reads the list every
  1.5 s, while the page shows, by evaluating a call to it under a random name (`page_media`); the page is
  never given a way into the app. `found` checks all of it (http(s) only, bounded), leaves out stream
  pieces and the thumbnails a player shows while seeking, makes byte ranges of one file one item, folds
  likely ads to the end, and names the main video (the biggest visible player's, or the page's stream)
  after the page, as is any other stream on it. A file saves from there (`save_found`), with the page or
  the player's frame as referrer. A stream (HLS or DASH) offers *Choose quality*: the bundled yt-dlp
  reads its playlist or manifest with the page as referrer (`look_up_found`), and its qualities show in
  the same card as a link's, saved by `download_found`.
- **A video site's page.** When the page is a video or post on YouTube, Instagram, TikTok or X, it is
  looked up as its link is, and its card shows above the page. Those sites change their address without
  loading a new page, so the window follows the address the page reports each time it asks.
- **Ads are blocked**, as on the phone, with the phone's copies of EasyList and EasyPrivacy (the lists
  uBlock Origin starts with; the installer carries them from `app/src/main/assets/adblock`). `chaya-core`'s
  `adblock` is the phone's engine ported: bare `||host^` rules in a set, the rest indexed by a word of
  their pattern; exceptions, `$important`, `$third-party` (by the public suffix list), `$domain`, kinds of
  request and page-wide exceptions honoured; regular expressions, scriptlets, procedural selectors and
  rewriting options skipped, never half-applied. The lists are read in the background when the browser
  first opens (pages load unblocked until then), and fresh copies are fetched from easylist.to about once
  a week; all are fetched before any is replaced, and a copy that does not look like a list is thrown away.
  - *Requests* (Windows): the page's WebView2 hands the app every request it makes
    (`WebResourceRequested`), and an ad's or a tracker's gets an empty 204. The page's own document is
    never blocked. The page opens on `about:blank` and is sent on once this is in place, so its first
    files are judged too.
  - *Pop-ups*: a window the lists name as an ad is refused without asking.
  - *Ad boxes*: `src-tauri/src/hider.js`, in the page's top document, notes the classes and ids the
    document holds once the app asks. The app asks four times a second, under a random name, and
    evaluates the CSS for what was reported (the site's rules, and the rules for every site that start
    with one of those names) back into the same document. Like the detector it hands the page nothing.
  - What the page was found to hold is not offered when it is an ad the lists block.
  - The shield beside the address bar counts what was blocked on the page; its panel shows a site's ads,
    or every site's (`ad_blocking`, `allow_ads_on`, `block_ads`), and reloads the page. The choice is kept
    in `adblock.json` in the app's folder.
- **Using the person's sign-in for a link**, as the phone's *Use my sign-in*: a lookup that fails because
  the site wants a sign-in (or YouTube's bot check) says what the person's sign-in could do (`lookup::SignIn`).
  When the browser holds that site's sign-in cookies (`session::is_signed_in`, the phone's markers), the card
  offers *Use my sign-in*: the site's cookies go to the engine in a `CookieFile`, deleted when the lookup
  ends, and the answer is kept for the download that follows. When it does not, *Sign in to {site}* opens
  the link's own page in the browser (`browse::sign_in_page`), where the site asks for a sign-in; once
  given, the page is looked up again as a video site's page is. Never without the person's tap.
- Under Linux (development only), Tauri stacks a window's webviews instead of placing them, so the page
  shows below the window's content rather than in it, and WebKitGTK saves a page's downloads itself
  without asking the app. WebKitGTK also has no way to answer a page's requests, so there ad boxes are
  hidden and ad pop-ups refused, but requests are not blocked. Windows places the page where it is told,
  asks before every download, and blocks requests.

## Tests

```sh
cd desktop && cargo test --workspace     # chaya-core
python -m pytest desktop/engine          # putting the engine together
python -m pytest app/src/test/python     # chaya_engine, shared with the phone
cd desktop/design && npm test            # the design system
```

`CHAYA_ENGINE_DIR=<an engine folder> cargo test` also runs the prepared engine for real
(`core/tests/bundled_engine.rs`), without the network; CI does this on Windows, where the engine's ffmpeg
joins the test tracks in `core/tests/fixtures` and saves the test stream (`stream.ts`) as an MP4. The
ffmpeg tests use `CHAYA_FFMPEG`, or an `ffmpeg` on the PATH, and say they were skipped when there is none.
The stream tests serve playlists and pieces from a local server, encrypted ones included. The ad
blocker's tests read the phone's lists from `app/src/main/assets/adblock`, and fetch fresh ones from a
local server. On Windows, `ads::webview_tests` opens a real WebView2 page served from the PC and checks
that its ads are never fetched and the rest of it is.

On Linux, building `app/src-tauri` needs WebKitGTK (`libwebkit2gtk-4.1-dev`), the same as Tauri
there. `core` needs nothing beyond Rust.

**The Windows tour** (`app/tour/tour.py`, CI job `tour`) installs the installer on a Windows machine and
drives the app through WebDriver (`tauri-driver` with the msedgedriver that matches the machine's WebView2):
a real link looked up and a download cancelled, three pages in the built-in browser, the ad blocker's panel,
Night. At each step it photographs the whole screen, so the page's place in the window, Windows' fonts and
frame are seen; the pictures and `steps.json` (what each step found) are the run's `chaya-windows-tour`
artifact. A site that refuses the machine is noted, not failed on. Off Windows it runs the same way with
`tauri-driver` and WebKitWebDriver (`webkit2gtk-driver`) on a virtual display, and `--sites` pointing at
local pages.

CI: `desktop.yml` builds the cut-down ffmpeg for Windows and Linux (once per version of
`build_ffmpeg.sh`, kept in the Actions cache); tests `core` and the packaging on Linux with it; and on
Windows puts the engine together, checks it, lints, tests and builds the installer, kept as the run's
`chaya-windows-installer` artifact. `desktop-design.yml` runs the design system's tests.

## Privacy

The window's own files, fonts included, are packed into the app; nothing is fetched to draw it. The
network is used only for what the person asks: a pasted link to a video or post is looked up with
yt-dlp, which asks that site about it; its poster is shown from the site's image server, without
saying which page asked (`referrerpolicy="no-referrer"`); and a download fetches the file from the
site's server with the headers yt-dlp names for it. No sign-in is used for a link and nothing is sent
anywhere else. The list of downloads stays on the PC. Pages the person opens in the built-in browser load
as in any browser, and keep their cookies in the app's own WebView2 folder on the PC; they cannot reach
the app. What is saved from a page is fetched with the browser's cookies for that file's own address, read
from that folder each time the download starts and never kept elsewhere; a stream's lookup gets them in a
file that is deleted as soon as it ends. The ad blocker decides on the PC; about once a
week it fetches fresh lists from easylist.to, sending nothing about the person, and its settings (on or
off, the sites allowed ads) stay on the PC.
