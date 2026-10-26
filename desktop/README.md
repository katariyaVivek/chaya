# Chaya for Windows

The desktop version of Chaya, built with [Tauri 2](https://v2.tauri.app/). The window is web content
drawn with the design system in [`design/`](design/README.md), shown by Windows' own WebView2
(Chromium). The work is done in Rust.

It is early. This build opens the window, follows Windows' light or dark setting (or the person's
choice), and tells what a pasted link is: a video or post on YouTube, Instagram, TikTok or X, an
account, another page, or words. Looking links up and downloading them come next
([`../docs/ROADMAP.md`](../docs/ROADMAP.md)).

## Layout

| Path | What it is |
|---|---|
| `design/` | The design system: tokens, fonts, components, motion. Plain CSS and an ES module |
| `core/` | `chaya-core`, plain Rust with no window or web view in it, so it builds and tests anywhere. `links` recognises links, ported from the phone's `PlatformMatcher` and `ProfileMatcher` with the same test table |
| `app/ui/` | The window: `index.html`, `app.css`, `main.js` |
| `app/scripts/assemble-ui.mjs` | Puts `ui/` and the design system together in `app/dist/`, which Tauri serves. Runs before every dev run and build |
| `app/src-tauri/` | The Tauri app: window settings (`tauri.conf.json`), what the window may call (`capabilities/`), and the commands that hand `chaya-core` to the window (`src/lib.rs`) |

Keep anything that does not need the window in `core/`, so it stays testable without one.

## Building on Windows

You need:

- Rust (stable) from <https://rustup.rs>, with the MSVC toolchain.
- Visual Studio Build Tools with *Desktop development with C++*.
- Node 22.
- WebView2, which Windows 11 already has. The installer fetches it from Microsoft on a PC without it.

Then, in `desktop/app`:

```sh
npm ci           # the Tauri CLI
npm run dev      # the app, rebuilt as you change it
npm run build    # the installer, in desktop/target/release/bundle/nsis/
```

For titles in Cabinet Grotesk, download it once into `design/fonts/` first
([`design/fonts/README.md`](design/fonts/README.md)). Its licence keeps it out of git, so installers
built by CI use Geist for titles.

## Tests

```sh
cd desktop && cargo test --workspace     # chaya-core
cd desktop/design && npm test            # the design system
```

On Linux, building `app/src-tauri` needs WebKitGTK (`libwebkit2gtk-4.1-dev`), the same as Tauri
there. `core` needs nothing beyond Rust.

CI: `desktop.yml` tests `core` on Linux, then lints, tests and builds the installer on Windows and keeps
it as the run's `chaya-windows-installer` artifact. `desktop-design.yml` runs the design system's tests.

## Privacy

The window loads only files packed into the app (its content security policy allows nothing else)
and makes no network requests. Fonts are bundled, never fetched. The phone app's privacy promises
carry over as the desktop app gains lookups and downloads.
