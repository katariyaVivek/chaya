# Design System — Chaya

This is the Android app's system. The Windows app has its own, in
[`desktop/design/`](desktop/design/README.md).

Product register. **Stage**: saved media is the light. Warm neutrals and one strong control in ink (near-black by
day, warm white by night), as Chaya for Windows has it ([`desktop/design/tokens.css`](desktop/design/tokens.css)) and
the approved phone designs draw it. Stage's amber, the mark's own light, is a signal only.

## Color

No pure black or white in the interface (a video's frame is the one near-black, `PlayerBackdrop`).

Light or dark follows the phone unless the person picks one under Downloads › ⋮ › **Appearance** (Same as phone, Light, Dark). The system bars follow the app's choice.

### Day

| Role | Hex |
|---|---|
| background (ground) | #F6F4F0 |
| surface | #FCFBF8 |
| surfaceContainer | #F0EEE9 |
| surfaceContainerHigh | #EAE7E2 |
| onSurface (ink) | #1A1917 |
| onSurfaceVariant (muted) | #6B6862 |
| outlineVariant | #DEDAD3 |
| primary | ink, #1A1917, with onPrimary the ground |
| primaryContainer | #E7E4DE |
| tertiary (amber, deep enough for an icon) | #8F5B1E |
| error | #CA322E |
| success (ext) | #338F54 |

### Night

| Role | Hex |
|---|---|
| background (ground) | #0F0E11 |
| surface | #141316 |
| surfaceContainer | #1E1D21 |
| surfaceContainerHigh | #262529 |
| onSurface (ink) | #F5F3F0 |
| onSurfaceVariant (muted) | #B5B3B2 |
| outlineVariant | #343337 |
| primary | ink, #F5F3F0, with onPrimary the ground |
| primaryContainer | #2C2B2F |
| tertiary (amber) | #F2B25A |
| error | #FF958D |
| success (ext) | #7CCD93 |

**Ink** is the one strong control: Save, Play, the chosen item. **Stage's amber** (`StageAmber`, #F2B25A, the same in
both themes, `LocalChayaColors.signal`) is a signal only: a download's progress, a page loading, the mark. It is never
text: on Day's ground it is 1.7:1. Success green is reserved for COMPLETED state dots. `StagePaletteTest` holds every
text colour to 4.5:1 on the grounds it is set on (7:1 for ink).

## Typography

System sans (FontFamily.Default), one family. Tight scale ratio ~1.15–1.25, hierarchy via weight (SemiBold titles vs Regular body) and size.

| Style | Spec |
|---|---|
| headlineMedium | 26sp / SemiBold / lh 32 / ls -0.25 |
| titleLarge | 20sp / SemiBold / lh 28 / ls -0.1 |
| titleMedium | 16sp / SemiBold / lh 22 / ls -0.1 |
| titleSmall | 14sp / SemiBold / lh 20 |
| bodyLarge | 15sp / Regular / lh 22 / ls 0.15 |
| bodyMedium | 14sp / Regular / lh 20 |
| bodySmall | 12sp / Regular / lh 16 |
| labelLarge | 13sp / Medium / lh 18 / ls 0.2 |
| labelMedium | 12sp / Medium / lh 16 / ls 0.3 |

## Shape

Rounded, generous but disciplined: sheets 28dp top radius · cards/chips 16dp · tonal icon circles 50% · inputs 24dp pill-ish · buttons 14dp.

## Elevation & surfaces

Flat-first: hairline `outlineVariant` borders or container-tone steps instead of shadows. Max shadow 2dp. Sheets use `surfaceContainerLow`.

## Motion ("butter smooth")

Springs: critical damping (no bounce). Tweens: ease-out-expo/quart.

| Token | Value |
|---|---|
| durationShort | 180ms |
| durationMedium | 300ms |
| durationLong | 450ms |
| easingStandard | cubic-bezier(0.16, 1, 0.3, 1) (out-expo) |
| springSmooth | NoBouncy, stiffness MediumLow (280) |
| pressScale | 0.96 on press, springSmooth |

Patterns: nav = fade-through (fade + slight rise/scale) · lists = staggered fade-rise entrances + animateItem reflow · progress = animated fraction · state labels = AnimatedContent crossfade/slide · FAB/badges = spring scale-in · press feedback = scale + ripple on every tappable row/card/chip.

## App icon

The two suns, as on Windows: an amber sun and its shadow-twin, one line of light through both (Chaya means shadow; a download is a copy). On Stage's night ground, same in light and dark. Master artwork, meaning and the generator are in [`docs/brand/`](docs/brand/README.md); the launcher XML is generated, never edited by hand.

## Components vocabulary

- **Tonal circle** (56dp, surfaceContainerHigh): leading media-type icon
- **State chip** (pill, 12sp medium): colored dot + label, per download state
- **Pill chips** for quick links/actions: surfaceContainerHigh fill, no border
- **Sheets**: drag handle, 28dp top corners, list rows with trailing icon action
- **Empty states**: soft icon circle + one teaching line, never "nothing here"
- **Download pill**: floats at thumb height above the bottom bar when a page has media. Poster circle, title, facts line, and one accent circle (the page's only accent action). Tapping anywhere opens the media sheet. Replaces the round download button.
- **Bottom bar**: Back, Forward, Home, Tabs and Downloads. Tabs shows the tab count in a 22dp rounded square (6dp corners, 1.75dp stroke) in place of an icon; it opens the tab grid.
- **Bookmark star**: on the site pill, before the shield. Outlined in onSurfaceVariant; filled in primary when the page is a bookmark. A snackbar says *Added to bookmarks* or *Removed from bookmarks*.
- **Address suggestions**: while the field has focus and something is typed, up to six rows in one surfaceContainerHigh block (16dp corners) under it: a star (primary) for a bookmark or a clock (onSurfaceVariant) for history, then the title over the site in labelMedium.
- **History and Bookmarks screens**: a top bar with Back, a search pill (24dp), then rows: a 36dp monogram circle (the site's first letter on surfaceContainerHigh), title over "site · time", and a trailing ×. History groups rows under Today, Yesterday and dated labelLarge headers, starts with a *Save history* switch row, and its ⋮ holds *Clear history* (last hour, last day or everything, radio options in a dialog). Reached from two pill chips under the start screen's quick sites (Bookmarks with a star, History with a clock) and from Downloads › ⋮. Bookmarks also lead the start screen's quick sites, which scroll sideways once there are any.
- **Downloads library**: the top bar has Search, a list/grid switch (grid view icon ↔ list icon) and ⋮ (*Add a video*, which opens the phone's picker for a video or sound of the person's own, then Bookmarks, History, Appearance, Diagnostics). Under it, in the list, the All/Active/Done chips; in both, one sideways row of kind chips (Videos, Pictures, Audio, ZIPs), a 1dp outlineVariant divider, and site chips (YouTube, Instagram, TikTok, X, Pinterest, Other). Each chip toggles; selected chips are primaryContainer. The grid is adaptive (112dp minimum), 4dp gaps, square tiles with 16dp corners on surfaceContainerHigh. A picture fills its tile and a video shows a frame. A tile without a picture shows its kind's icon over a three-line name. Badges are black at 55% with 10dp corners: a play mark bottom-left on videos, and a stack icon with the count top-right on a post. A chosen tile has a 3dp primary border and a check top-left; the top bar becomes surfaceContainerHigh with "N chosen", Share and Delete. A primaryContainer pill above the grid says what is still downloading and goes to the list. While searching, a primaryContainer pill (16dp corners) under the search box says how often the words are said in the saved videos (*Said 12 times in 3 videos*, a captions icon, a chevron) and opens a sheet on surfaceContainerLow: each video's title in labelLarge over its lines, the time in bodySmall onSurfaceVariant, the line in bodyMedium with the words searched for in bold.
- **Highlights (player)**: a round button with the sparkle icon, before Translate, for a video with subtitles. Its sheet: *HIGHLIGHTS · GROQ · LLAMA* over a NightContainer card (14dp corners, outlined) for each: the span (*1:20–2:05*, 12sp muted), the title (15sp SemiBold ink), why (13sp muted); a tap plays from it. Then 5, 10 or 20 as chips and *Find highlights* (*Find again*) in ink, or *Finding highlights…* over an amber bar with *Stop*. Under the seek bar's track each highlight is a 3dp amber stroke, the signal that something is there.
- **Clips (player)**: a long press on a transcript line lights it (NightOutlineVariant) and the lines up to the one tapped next; a NightContainer bar like the Undo bar says *3 lines* over *0:12–0:31 · 19 s*, with *Put right* (one line), *Clip* in ink and ×. *3 clips* (scissors) sits beside the chapter button and lists them on cards. A clip opens in a sheet: *CLIP · 0:12–0:31 · 19 S*, its name in a NightContainer field, then the timeline (96dp, 12dp corners): six frames along the top, the sound's loudness as thin muted bars under them, everything outside the clip darkened, the clip edged in amber with two ink handles (8dp) to drag, and a white line where the video is. *Save* in ink, *Play*, *Delete*.
- **Saving a clip (player)**: under the clip's timeline, *SAVE AS* over chips (*Video with subtitles*, *Video*, *Subtitles (SRT)*), then sizes (*As it is*, *10 MB*, *25 MB*, *50 MB*) for a video; *Save clip* in ink. While it is made, *Making the clip · 40%* over an amber bar, with *Stop*; then *Share* and *Save to phone* beside *Save clip*, the file's name under them. Drawn on the clip, the subtitles look as the player draws them: white, bold, on a dark rounded band near the foot, the translation under the line in the same band.
- **Translate (player)**: a round button with the translate icon before the subtitles button, shown only for a video with subtitles. Its sheet on NightGround: *TRANSLATE FROM ENGLISH INTO* over a sideways row of language chips (12dp corners; the chosen one ink with NightGround text, the rest NightContainer), the phone's language first; then *Translate* (ink, 14dp corners) and *AI model* as a muted text button, or, while it runs, *Translating into Spanish · 40 of 120 lines* over an amber progress bar (the signal) with the model and *Stop*. *SHOWN UNDER THE ORIGINAL* lists *Original only* and every version (the language over the model, and how many lines while it is part-way), the one shown outlined in ink. On the picture the translation is the second line of the same band; in the transcript it sits under each line at 14sp, a little dimmer than the line.
- **Subtitles put right (player)**: always dark, as the player is. The transcript's heading has a pencil (NightMuted, 18dp) that opens the line being said; a long press opens any line. The sheet on NightGround: *LINE AT 0:12* (12sp SemiBold, 0.8sp tracking, NightMuted) over the words in a NightContainer field (14dp corners), *Keep* (or *Take out* when emptied) as a text button in ink; then *TIMING · EVERY LINE*: a NightContainer card with an outline (*This line is said at 0:15*, and under it whether it moves every line or stretches them since the line put right before), and four equal nudge buttons (−0.5 s, −0.1 s, +0.1 s, +0.5 s). After a change a NightContainer bar with an outline (16dp corners) sits above the controls for six seconds: what changed, and *Undo*.
- **Picture viewer**: full screen on black, a pager between pictures; pinch or double-tap (2.5×, up to 5×) to zoom, and while zoomed a drag moves the picture instead of turning the page. The top bar is black at 45% with Close, the title, "2 of 5", and Share (plus Save to phone for a ZIP's file). A video page shows its frame with a 72dp round play button.
- **ZIP contents**: Back, the account's title and the file count, then the same square grid. A long press on a file offers Open, Share and Save to phone.
- **Tab grid**: full screen. A top row with a tonal **+** square (14dp corners), the tab count mark in a surfaceContainerHigh segment, and ⋮ (*Close all tabs*, confirmed); a *Search your tabs* pill (24dp); then two columns of cards, about 3:4, 24dp corners: a header row (site icon, one-line title, ×) over a picture of the page with 18dp corners. The shown tab's card is primaryContainer with onPrimaryContainer text; the others are surfaceContainerHigh. Swipe a card sideways or tap × to close, with Undo in a snackbar. The grid fades and scales in and out (tweenShort / tweenStandard).
- **Address bar**: a field on the start screen (with a Paste chip) and while editing; on a page it collapses to the site name with a lock, the ad blocker's shield and a refresh button. The shield is filled in primary while it blocks there, with a primary badge counting what it blocked (99+ past 99), and outlined in onSurfaceVariant when blocking is off for the page. Submitting closes the keyboard.
- **Page menu**: a long press on a link or a picture opens a dialog (surfaceContainerHigh, 28dp corners, at most 420dp wide): the link's words in titleSmall over its address in bodySmall onSurfaceVariant, a divider, then rows in bodyLarge. A link: *Open in new tab*, *Copy link address*, *Copy link text* (when it has words), *Download link* (a video site's link opens its sheet), *Share link*. A picture adds *Open image in new tab*, *Download image*, *Copy image address*, *Share image*. Words chosen on a page keep WebView's own bar (Copy, Select all, Share…) with *Search in new tab* added.
- **Pull to reload**: dragging a page down at its top brings a 40dp round button with the reload arrow down from the top edge, turning and fading in as it comes; past 96dp it turns primary (*Let go to reload*). A list scrolled inside the page scrolls first.
- **Media sheet**: main card first (poster stage, title, facts, Download, plus Quality for streams), then other media, likely ads folded away, and one line about hidden stream pieces.
- **Download card**: 16dp surface with a hairline border, 72x46 poster, title (two lines), a progress or status line, one tonal action for what the download needs next, and an overflow menu for the rest. Swipe left to delete, with Undo. Streams, which never report a size, get a moving bar instead of a stuck 0%, then a determinate *Saving as MP4* bar; a stream still only in the cache says *Plays in Chaya* and offers *Save as MP4* in its menu.
- **Filter chips**: All, Active, Done. Selected = primaryContainer, unselected = surfaceContainerHigh, no border.
- **Start screen**: quick sites as monogram tiles (no brand logos), then the latest downloads. No sample links, no how-it-works list.
