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

Rounded, generous but disciplined, and only on this scale (`ShapeScaleTest` reads the sources for it):

| Radius | For |
|---|---|
| pill (`CircleShape`) | every button, in ink or outlined; Chaya's bar and its ink circle; chips and bars rounded to half their height; tonal icon circles |
| 28dp | sheets' top corners, dialogs, floating bars that hold more than one row (the player's controls) |
| 24dp | search fields, tab cards |
| 18dp | a picture inside a card, the Make subtitles card |
| 16dp | cards, chips, tiles |
| 14dp | cards and fields in the player, Home's recent cards |
| 12dp | small chips, the clip timeline |
| 10dp | badges over a picture |
| 6dp | the tab count square |

A shape rounded to half its height is a pill, never a number set to match the height.

## Elevation & surfaces

Flat-first: hairline `outlineVariant` borders or container-tone steps instead of shadows. Max shadow 2dp, floating bars
included (`ShapeScaleTest`). Sheets use `surfaceContainerLow`.

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

Motion is spent by how often it is seen. Something met many times a day (the start page on every new tab, scrolling,
back) gets no entrance of its own; occasional things (sheets, a first-run hint, an empty state) may fade and rise.

Patterns: nav = fade-through (fade + slight rise/scale) · lists = animateItem reflow, no entrances · progress = animated fraction · state labels = AnimatedContent crossfade/slide · FAB/badges = spring scale-in · press feedback = scale (`pressScale`) + ripple on buttons, cards, chips and tiles; a full-width list row gets the ripple's highlight only, never a scale.

## Words

One label per intent, everywhere it appears: **Save** for keeping media (*Save*, *Save 1080p*, *Save audio*, *Save
anyway*; never *Download* on a button), **Resume** for picking up what stopped (a download, subtitles part-made),
**Try again** for a failure, **Stop** for work under way. An error says what happened and what may help, never
*Something went wrong*.

## App icon

The two suns, as on Windows: an amber sun and its shadow-twin, one line of light through both (Chaya means shadow; a download is a copy). On Stage's night ground, same in light and dark. Master artwork, meaning and the generator are in [`docs/brand/`](docs/brand/README.md); the launcher XML is generated, never edited by hand.

## Components vocabulary

- **Tonal circle** (56dp, surfaceContainerHigh): leading media-type icon
- **State chip** (pill, 12sp medium): colored dot + label, per download state
- **Pill chips** for quick links/actions: surfaceContainerHigh fill, no border
- **Sheets**: drag handle, 28dp top corners, list rows with trailing icon action
- **Empty states**: soft icon circle + one teaching line, never "nothing here"
- **Chaya's bar** (`home/ChayaBar`): the one bottom bar, floating at thumb height on Home, Browse, the Library and the tab grid (62dp, 14dp from the sides, 16dp above the phone's navigation). A pill on surfaceContainer with a hairline outlineVariant border holds three parts: **Home**, always the far-left slot (64dp), and **the Library**, always the far-right slot (64dp), each an icon over its name in labelMedium SemiBold; between them **Browse** on Home and in the Library, or, while browsing, the page's **Back**, **Forward** and **Tabs** as icons alone (their names do not fit on a narrow phone and are read out instead; Tabs is the count in a 22dp square, 6dp corners, filled while the tab grid is open). Every slot's icon sits in the same 28dp box, so the names under Home, Browse and the Library share a line. The place shown is lit in primaryContainer; on a page the three controls are lit together. Beside the pill is the **ink circle** (62dp, primary), always the one main thing to do here: **+** (*Open a link or search*) on Home and in the Library, **+** (*New tab*) while browsing a page with nothing to save and in the tab grid, and **Save** (the download arrow and a 12dp amber dot ringed in ink) when the page has something; while a site's video is still being looked up the arrow dims inside a turning amber ring. Save opens the page's sheet: the site's video, the account's posts, or *Found on this page*. While anything is saving, an amber ring around the Library's icon says how far (indeterminate while no size is known), on every screen: the one progress sign. Swiping sideways along the bar while browsing shows the tab beside. Home and the Library on the bar are places: from the browser they leave its tabs as they were.
- **Bookmark star**: on the site pill, before the shield. Outlined in onSurfaceVariant; filled in primary when the page is a bookmark. A snackbar says *Added to bookmarks* or *Removed from bookmarks*.
- **Address suggestions**: while the field has focus and something is typed, up to six rows in one surfaceContainerHigh block (16dp corners) under it: a star (primary) for a bookmark or a clock (onSurfaceVariant) for history, then the title over the site in labelMedium.
- **History and Bookmarks screens**: a top bar with Back, a search pill (24dp), then rows: a 36dp monogram circle (the site's first letter on surfaceContainerHigh), title over "site · time", and a trailing ×. History groups rows under Today, Yesterday and dated labelLarge headers, starts with a *Save history* switch row, and its ⋮ holds *Clear history* (last hour, last day or everything, radio options in a dialog). Reached from two pill chips under the start screen's quick sites (Bookmarks with a star, History with a clock) and from Downloads › ⋮. Bookmarks also lead the start screen's quick sites, which scroll sideways once there are any.
- **Downloads library**: the top bar has Search, a list/grid switch (grid view icon ↔ list icon) and ⋮ (*Add a video*, which opens the phone's picker for a video or sound of the person's own, then Bookmarks, History, Appearance, Diagnostics). Under it, in the list, the All/Active/Done chips; in both, one sideways row of kind chips (Videos, Pictures, Audio, ZIPs), a 1dp outlineVariant divider, and site chips (YouTube, Instagram, TikTok, X, Pinterest, Other). Each chip toggles; selected chips are primaryContainer. The grid is adaptive (112dp minimum), 4dp gaps, square tiles with 16dp corners on surfaceContainerHigh. A picture fills its tile and a video shows a frame. A tile without a picture shows its kind's icon over a three-line name. Badges are black at 55% with 10dp corners: a play mark bottom-left on videos, and a stack icon with the count top-right on a post. A chosen tile has a 3dp primary border and a check top-left; the top bar becomes surfaceContainerHigh with "N chosen", Share and Delete. A primaryContainer pill above the grid says what is still downloading and goes to the list. While searching, a primaryContainer pill (16dp corners) under the search box says how often the words are said in the saved videos (*Said 12 times in 3 videos*, a captions icon, a chevron) and opens a sheet on surfaceContainerLow: each video's title in labelLarge over its lines, the time in bodySmall onSurfaceVariant, the line in bodyMedium with the words searched for in bold.
- **Highlights (player)**: a round button with the sparkle icon, before Translate, for a video with subtitles. Its sheet: *HIGHLIGHTS · GROQ · LLAMA* over a NightContainer card (14dp corners, outlined) for each: the span (*1:20–2:05*, 12sp muted), the title (15sp SemiBold ink), why (13sp muted); a tap plays from it. Then 5, 10 or 20 as chips and *Find highlights* (*Find again*) in ink, or *Finding highlights…* over an amber bar with *Stop*. Under the seek bar's track each highlight is a 3dp amber stroke, the signal that something is there.
- **Clips (player)**: a long press on a transcript line lights it (NightOutlineVariant) and the lines up to the one tapped next; a NightContainer bar like the Undo bar says *3 lines* over *0:12–0:31 · 19 s*, with *Put right* (one line), *Clip* in ink and ×. *3 clips* (scissors) sits beside the chapter button and lists them on cards. A clip opens in a sheet: *CLIP · 0:12–0:31 · 19 S*, its name in a NightContainer field, then the timeline (96dp, 12dp corners): six frames along the top, the sound's loudness as thin muted bars under them, everything outside the clip darkened, the clip edged in amber with two ink handles (8dp) to drag, and a white line where the video is. *Save* in ink, *Play*, *Delete*.
- **Saving a clip (player)**: under the clip's timeline, *SAVE AS* over chips (*Video with subtitles*, *Video*, *Subtitles (SRT)*), then sizes (*As it is*, *10 MB*, *25 MB*, *50 MB*) for a video; *Save clip* in ink. While it is made, *Making the clip · 40%* over an amber bar, with *Stop*; then *Share* and *Save to phone* beside *Save clip*, the file's name under them. Drawn on the clip, the subtitles look as the player draws them: white, bold, on a dark rounded band near the foot, the translation under the line in the same band.
- **Deleting what was made (player)**: the transcript's heading has a bin (NightMuted, 18dp); it asks first (*Delete these subtitles?*, their translations and highlights going with them) and the *Make subtitles* card comes back. Each translation version in the Translate sheet has a × at its end. Library › ⋮ › *Speech models* lists the models on the phone with their size and *Delete*.
- **Browser start page**: Chaya's mark (the two suns, 48dp) beside its name, as Home's top row has it.
- **Make subtitles (player)**: for a video without subtitles, a NightContainer card (18dp corners, outlined) where the transcript would be: *NO SUBTITLES*, one sentence that nothing is sent, the models as two chips (Tiny, Base; the label over its note and its size or *on the phone*; the chosen one ink), and *Make subtitles* in ink (*Fetch 148 MB and make subtitles* until it is on the phone). While it works: *Fetching the Base model · 40 of 148 MB* or *Listening · 42%* over an amber bar, with *Stop*. The models row starts with Parakeet (*Best on the phone · European languages · 671 MB*), chosen until another is; a service with a saved key follows Tiny and Base (*Most accurate · seconds*), and choosing it puts the sentence about what is sent, to whom and its price above the button, which reads *Send to Groq and make subtitles*. Another video for a busy lane says *Waiting for the video before it*, with *Stop*. Making that stopped (or ended with the app) shows *40% was heard with Groq before it stopped.* and *Resume · 40%* in ink beside *Start over*. While subtitles are made, a low-importance notification names the video (or *Making subtitles for 2 videos*), how far each is, and *Stop*; *Subtitles made*, or why not, when one ends.
- **AI model (settings)**: an *IN USE* card (surfaceContainerHigh, 16dp corners) at the head: *Translation and highlights* with the service and model (or what is missing: *needs its key*, *choose a model below*), and *Make subtitles* with the phone and each service a key is saved for. Each service in the list says *Key saved* and, for Groq and OpenAI, *also makes subtitles*; their key field has a line saying their Whisper is for subtitles, not in the list of models. Choosing a service brings back the model last used with it.
- **Translate (player)**: a round button with the translate icon before the subtitles button, shown only for a video with subtitles. Its sheet on NightGround: *TRANSLATE FROM ENGLISH INTO* over a sideways row of language chips (12dp corners; the chosen one ink with NightGround text, the rest NightContainer), the phone's language first; then *Translate* (an ink pill) and *AI model* as a muted text button, or, while it runs, *Translating into Spanish · 40 of 120 lines* over an amber progress bar (the signal) with the model and *Stop*. *SHOWN UNDER THE ORIGINAL* lists *Original only* and every version (the language over the model, and how many lines while it is part-way), the one shown outlined in ink. On the picture the translation is the second line of the same band; in the transcript it sits under each line at 14sp, a little dimmer than the line.
- **Subtitles put right (player)**: always dark, as the player is. Each transcript line has a pencil at its end (NightMuted, 16dp, at 60% except the line being said) that opens it; a long press chooses lines. The sheet on NightGround: *LINE AT 0:12* (12sp SemiBold, 0.8sp tracking, NightMuted) over the words in a NightContainer field (14dp corners), and, when a translation is shown, its language's name over its line in a second field, *Keep* (or *Take out* when emptied) as a text button in ink; then *TIMING · EVERY LINE*: a NightContainer card with an outline (*This line is said at 0:15*, and under it whether it moves every line or stretches them since the line put right before), and four equal nudge buttons (−0.5 s, −0.1 s, +0.1 s, +0.5 s). After a change a NightContainer bar with an outline (16dp corners) sits above the controls for six seconds: what changed, and *Undo*.
- **Picture viewer**: full screen on black, a pager between pictures; pinch or double-tap (2.5×, up to 5×) to zoom, and while zoomed a drag moves the picture instead of turning the page. The top bar is black at 45% with Close, the title, "2 of 5", and Share (plus Save to phone for a ZIP's file). A video page shows its frame with a 72dp round play button.
- **ZIP contents**: Back, the account's title and the file count, then the same square grid. A long press on a file offers Open, Share and Save to phone.
- **Tab grid**: full screen. The header every screen has: *3 tabs* in headlineMedium and ⋮ in a 44dp tonal circle (*Close all tabs*, confirmed); a *Search your tabs* pill (24dp); then two columns of cards, about 3:4, 24dp corners: a header row (site icon, one-line title, ×) over a picture of the page with 18dp corners. A tab on its start page is *New tab*. The shown tab's card is primaryContainer with onPrimaryContainer text; the others are surfaceContainerHigh. Swipe a card sideways or tap × to close, with Undo in a snackbar. Chaya's bar stays at the foot with Tabs lit: its ink circle is *New tab*, and Tabs closes the grid. The grid fades and scales in and out (tweenShort / tweenStandard).
- **Address bar**: a field on the start screen (with a Paste chip) and while editing; on a page it collapses to the site name with a lock, the ad blocker's shield and a refresh button. The shield is filled in primary while it blocks there, with a primary badge counting what it blocked (99+ past 99), and outlined in onSurfaceVariant when blocking is off for the page. Submitting closes the keyboard.
- **Page menu**: a long press on a link or a picture opens a dialog (surfaceContainerHigh, 28dp corners, at most 420dp wide): the link's words in titleSmall over its address in bodySmall onSurfaceVariant, a divider, then rows in bodyLarge. A link: *Open in new tab*, *Copy link address*, *Copy link text* (when it has words), *Download link* (a video site's link opens its sheet), *Share link*. A picture adds *Open image in new tab*, *Download image*, *Copy image address*, *Share image*. Words chosen on a page keep WebView's own bar (Copy, Select all, Share, Web search…); its *Web search* searches in a new tab in Chaya, never in another app.
- **Pull to reload**: dragging a page down at its top brings a 40dp round button with the reload arrow down from the top edge, turning and fading in as it comes; past 96dp it turns primary (*Let go to reload*). A list scrolled inside the page scrolls first.
- **Media sheet**: main card first (poster stage, title, facts, Download, plus Quality for streams), then other media, likely ads folded away, and one line about hidden stream pieces.
- **Download card**: 16dp surface with a hairline border, 72x46 poster, title (two lines), a progress or status line, one tonal action for what the download needs next, and an overflow menu for the rest. Swipe left to delete, with Undo. Streams, which never report a size, get a moving bar instead of a stuck 0%, then a determinate *Saving as MP4* bar; a stream still only in the cache says *Plays in Chaya* and offers *Save as MP4* in its menu.
- **Filter chips**: All, Active, Done. Selected = primaryContainer, unselected = surfaceContainerHigh, no border.
- **Start screen**: quick sites as monogram tiles (no brand logos), then the latest downloads. No sample links, no how-it-works list.
