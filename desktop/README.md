# Chaya for Windows

The desktop version of Chaya, built with [Tauri 2](https://v2.tauri.app/). The window is web content
drawn with the design system in [`design/`](design/README.md), shown by Windows' own WebView2
(Chromium). The work is done in Rust.

It is early. This build opens the window, follows Windows' light or dark setting (or the person's
choice), and tells what a pasted link is: a video or post on YouTube, Instagram, TikTok or X, an
account, another page, or words. A video or post is looked up with yt-dlp, as on the phone, and the
window shows its title, poster and the qualities it can be saved in (or what a post holds, or why it
can't be). Each choice downloads into the Downloads folder, with pause, resume, cancel and retry, and
the Downloads list keeps track across restarts. Qualities that arrive as separate picture and sound
(most of YouTube's), and streams, come next ([`../docs/ROADMAP.md`](../docs/ROADMAP.md)).

## Layout

| Path | What it is |
|---|---|
| `design/` | The design system: tokens, fonts, components, motion. Plain CSS and an ES module |
| `core/` | `chaya-core`, plain Rust with no window or web view in it, so it builds and tests anywhere. `links` recognises links (the phone's `PlatformMatcher` and `ProfileMatcher`); `engine` runs the engine; `media` reads its answer (`PlatformMedia`); `formats` picks one choice per quality (`FormatSelector`); `lookup` is what the window is sent. Each is ported with the phone's tests. `download` saves files: `http` fetches one with resume (`HttpDownloader`), `manager` keeps every download and its state (`DownloadManager`), `plan` turns a choice into downloads, `names` makes Windows-safe names, `error` explains failures (`DownloadError`) |
| `engine/` | `prepare.py` puts together the engine the installer carries, in `app/src-tauri/engine/` (not in git); `run.py` starts it |
| `app/ui/` | The window: `index.html`, `app.css`, `main.js` |
| `app/scripts/assemble-ui.mjs` | Puts `ui/` and the design system together in `app/dist/`, which Tauri serves. Runs before every dev run and build |
| `app/src-tauri/` | The Tauri app: window settings (`tauri.conf.json`), what the window may call (`capabilities/`), and the commands that hand `chaya-core` to the window (`src/lib.rs`) |

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
python desktop/engine/prepare.py
```

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
- `engine/run.py`: what the app starts, `python -I run.py extract URL --cache DIR --js-runtime qjs.exe`
  (`chaya_engine/cli.py`). It prints one line of JSON, the same answer the phone reads.

`chaya-core`'s `engine` starts it with no console window, one lookup at a time, stops it after two
minutes, and keeps answers for twenty minutes. Only links `links` recognises as a video or post are ever
passed to it. A change to `chaya_engine` must keep both apps working: pytest covers it for both.

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
- Only what the app can save is offered: plain files. A quality that needs its picture and sound
  joined, or a stream, is left out and the card says better qualities are coming.

## Tests

```sh
cd desktop && cargo test --workspace     # chaya-core
python -m pytest desktop/engine          # putting the engine together
python -m pytest app/src/test/python     # chaya_engine, shared with the phone
cd desktop/design && npm test            # the design system
```

`CHAYA_ENGINE_DIR=<an engine folder> cargo test` also runs the prepared engine for real
(`core/tests/bundled_engine.rs`), without the network; CI does this on Windows.

On Linux, building `app/src-tauri` needs WebKitGTK (`libwebkit2gtk-4.1-dev`), the same as Tauri
there. `core` needs nothing beyond Rust.

CI: `desktop.yml` tests `core` and the packaging on Linux; on Windows it puts the engine together,
checks it, lints, tests and builds the installer, and keeps it as the run's `chaya-windows-installer`
artifact. `desktop-design.yml` runs the design system's tests.

## Privacy

The window's own files, fonts included, are packed into the app; nothing is fetched to draw it. The
network is used only for what the person asks: a pasted link to a video or post is looked up with
yt-dlp, which asks that site about it; its poster is shown from the site's image server, without
saying which page asked (`referrerpolicy="no-referrer"`); and a download fetches the file from the
site's server with the headers yt-dlp names for it. No sign-in is used and nothing is sent anywhere
else. The list of downloads stays on the PC.
