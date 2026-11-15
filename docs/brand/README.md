# Chaya's icon

**An original and its shadow-copy.** Chaya (छाया) means shadow. In the story, Chhaya is the copy made
from Saranyu's shadow, and a download is a copy of the original. So the mark is a media tile with a
play mark (the original) with its copy behind it, falling where a shadow would: below and to the right.

| File | What it is |
|---|---|
| [`chaya-icon.svg`](chaya-icon.svg) | Master artwork, for anything outside the app (README, store pages) |
| [`generate_icon.py`](generate_icon.py) | Writes that SVG and the launcher icon in `app/src/main/res/drawable/` from one set of measurements |

This is the phone's icon. Chaya for Windows has moved to a new mark, below; the phone follows later.

The launcher icon is an adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`):

- **Background:** the violet field from the `primary` family in [`../../DESIGN.md`](../../DESIGN.md),
  lit from the upper left. It is the same in light and dark mode.
- **Foreground:** the copy (deep shade with a lavender rim), then the lit original and its play mark.
  Everything stays inside the 66-unit safe circle, so no launcher mask clips it.
- **Monochrome:** for Android 13+ themed icons. One colour only, so the copy is drawn at half strength
  and the play mark is cut out of the original.

To change the icon, edit the measurements or colours at the top of `generate_icon.py` and run
`python docs/brand/generate_icon.py`. Do not edit the generated XML by hand.

## Chaya for Windows: the two suns

Chaya for Windows has its own mark: an amber sun and its shadow-twin, one line of light through both. In the story
Chhaya was made from the sun-wife's shadow, and a download is a copy of what was on the page. In the app it is
alive: the line draws as a download goes and the twin, the copy, slides out from behind the sun as it arrives. Its
rules are in [`../../desktop/design/README.md`](../../desktop/design/README.md) (Mark).

Its geometry lives in one place, [`desktop/design/chaya-mark.js`](../../desktop/design/chaya-mark.js), as named
constants. The icon files in `desktop/app/src-tauri/icons/` are made from it: in `desktop/app`, run

    node scripts/icons.mjs

It writes `icon.svg` (48 px and up) and `icon-small.svg` (32 px and down, the mark larger and its gap and hairline
wider so they survive at 16 px), renders each with Tauri's `tauri icon`, keeps the files Windows uses, and builds
`icon.ico` from the small source's 16, 24 and 32 px pictures and the large one's 48, 64 and 256.
