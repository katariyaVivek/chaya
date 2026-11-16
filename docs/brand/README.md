# Chaya's icon

**The two suns**, on the phone and on Windows: an amber sun and its shadow-twin, one line of light through both.
Chaya (छाया) means shadow; in the story Chhaya was made from the sun-wife's shadow, and a download is a copy of what
was on the page. The mark's rules, and how it moves on Windows, are in
[`desktop/design/README.md`](../../desktop/design/README.md) (Mark) and `CLAUDE.md` (Chaya's mark).

| File | What it is |
|---|---|
| [`chaya-icon.svg`](chaya-icon.svg) | Master picture, for anything outside the apps (README, store pages) |
| [`android-icon.mjs`](android-icon.mjs) | Writes that picture and the phone's launcher icon in `app/src/main/res/drawable/` from the mark's constants in `desktop/design/chaya-mark.js` |

The phone's launcher icon is an adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`):

- **Background:** Stage's night ground (`#16151A`, the Windows icon's plate), the same in light and dark mode.
- **Foreground:** the mark at rest, centred, inside the 66-unit safe circle so no launcher mask clips it. An Android
  vector has no masks, so the gaps the mark cuts with masks are clip paths here: each sun drawn above and below the
  line of light, the twin also outside the sun's ring.
- **Monochrome:** for Android 13+ themed icons, the same shapes in one colour, the twin at half strength.

To change the icon, change the mark's constants and run `node docs/brand/android-icon.mjs` (and, for Windows,
`node desktop/app/scripts/icons.mjs`). Do not edit the generated XML by hand.

## On Windows

In Chaya for Windows the mark is alive: the line draws as a download goes and the twin, the copy, slides out from behind the sun as it arrives. Its
rules are in [`../../desktop/design/README.md`](../../desktop/design/README.md) (Mark).

Its geometry lives in one place, [`desktop/design/chaya-mark.js`](../../desktop/design/chaya-mark.js), as named
constants. The icon files in `desktop/app/src-tauri/icons/` are made from it: in `desktop/app`, run

    node scripts/icons.mjs

It writes `icon.svg` (48 px and up) and `icon-small.svg` (32 px and down, the mark larger and its gap and hairline
wider so they survive at 16 px), renders each with Tauri's `tauri icon`, keeps the files Windows uses, and builds
`icon.ico` from the small source's 16, 24 and 32 px pictures and the large one's 48, 64 and 256.
