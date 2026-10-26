# Fonts

All fonts ship inside the app. None is loaded from a font service.

| File | Family | License | In the repository |
|---|---|---|---|
| `Geist-Variable.woff2` | Geist, weights 100–900 | SIL Open Font License 1.1, `OFL-Geist.txt` (from the `geist` npm package, 1.8.2) | Yes |
| `GeistMono-Variable.woff2` | Geist Mono, weights 100–900 | the same | Yes |
| `CabinetGrotesk-Variable.ttf` | Cabinet Grotesk, weights 100–900 (Chaya uses 600) | © Indian Type Foundry, ITF Free Font License | **No**, by its licence |

## Cabinet Grotesk

Cabinet Grotesk is a Fontshare "closed source" font. Its licence allows embedding it in a desktop
app, but not sharing the file through a repository or with anyone else: each person who builds Chaya
downloads their own copy from Fontshare. `.gitignore` keeps `CabinetGrotesk-*` out of git.

1. Download Cabinet Grotesk from <https://www.fontshare.com/fonts/cabinet-grotesk>.
2. From the download, copy `CabinetGrotesk-Variable.ttf` here, unchanged. The licence forbids
   converting it (to WOFF2, say) or subsetting it, so it is used as the TTF it comes as.

Without it, `.title` and `.wordmark` use Geist 600 and the window shows one missing-file request.

The font's licence asks that it be credited to Indian Type Foundry by name; the app does this in its
Appearance panel.
