# Chaya's icon

**An original and its shadow-copy.** Chaya (छाया) means shadow. In the story, Chhaya is the copy made
from Saranyu's shadow, and a download is a copy of the original. So the mark is a media tile with a
play mark (the original) with its copy behind it, falling where a shadow would: below and to the right.

| File | What it is |
|---|---|
| [`chaya-icon.svg`](chaya-icon.svg) | Master artwork, for anything outside the app (README, store pages) |
| [`generate_icon.py`](generate_icon.py) | Writes the master SVG and the launcher icon in `app/src/main/res/drawable/` from one set of measurements |

The launcher icon is an adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`):

- **Background:** the violet field from the `primary` family in [`../../DESIGN.md`](../../DESIGN.md),
  lit from the upper left. It is the same in light and dark mode.
- **Foreground:** the copy (deep shade with a lavender rim), then the lit original and its play mark.
  Everything stays inside the 66-unit safe circle, so no launcher mask clips it.
- **Monochrome:** for Android 13+ themed icons. One colour only, so the copy is drawn at half strength
  and the play mark is cut out of the original.

To change the icon, edit the measurements or colours at the top of `generate_icon.py` and run
`python docs/brand/generate_icon.py`. Do not edit the generated XML by hand.
