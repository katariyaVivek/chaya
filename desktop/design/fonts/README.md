# Fonts

All fonts ship inside the app. None is loaded from a font service.

| File | Family | License |
|---|---|---|
| `Geist-Variable.woff2` | Geist, weights 100–900 | SIL Open Font License 1.1, `OFL-Geist.txt` (from the `geist` npm package, 1.8.2) |
| `GeistMono-Variable.woff2` | Geist Mono, weights 100–900 | the same |
| `CabinetGrotesk-Semibold.woff2` | Cabinet Grotesk 600 | Fontshare's ITF Free Font License. **Not added yet** |

## Adding Cabinet Grotesk

Fontshare could not be reached from the environment this was set up in. To add it:

1. Download Cabinet Grotesk from <https://www.fontshare.com/fonts/cabinet-grotesk>.
2. Copy the Semibold (600) `.woff2` here as `CabinetGrotesk-Semibold.woff2`.
3. Read the license in the download. Check that it allows bundling the font in an app you
   distribute, and keep its text here beside the file, as with Geist.

Until then, `.title` and `.wordmark` use Geist 600, and the showcase logs one 404 for the missing
file.
