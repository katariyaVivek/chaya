# What Chaya does and does not do

These are deliberate scope decisions, not open bugs.

## What Chaya covers

- **Media on the pages you browse:** direct audio and video files (MP4, WebM, MP3, …) and HLS/DASH
  streams (`.m3u8`, `.mpd`), found from network traffic and from the page itself. A finished
  stream is saved as an MP4 file (an M4A for sound only), copied as it is without re-encoding
  when the MP4 container can take it, which covers nearly every stream; otherwise it is
  re-encoded and the download says so. A stream that cannot be saved, or there is no room to
  save, stays in Chaya's cache, where it still plays, with *Save as MP4* to try again.
- **Extensionless endpoints** whose server reports a media `Content-Type`, after you tap *Scan more
  thoroughly* (same-origin only, at most ten checks, no redirects followed).
- **Links to a single video on YouTube, Instagram, TikTok and X**, looked up by yt-dlp running on
  the phone, with one choice per quality. Where a site serves picture and sound separately, Chaya
  downloads both and joins them into one MP4.
- **Pictures in Instagram and X posts**, carousels and tweets of several photos included: every
  picture and video in the post, saved together or one at a time. A tweet with one video and some
  photos offers only the video.
- **Everything an Instagram or X account has posted, as one ZIP** (posts only: not stories,
  highlights or reels tabs). Without a sign-in a site shows a visitor little or nothing (Instagram a
  few recent posts at most, X no timeline), so the person can choose to use their sign-in. Listing
  goes at the site's own pace; a big account takes minutes. Files the site no longer has, or keeps
  refusing to send after a few tries, are named in a `not-saved.txt` inside the ZIP.
- **The engine keeps itself current.** About once a day Chaya asks PyPI for newer releases of
  yt-dlp, its YouTube solver scripts (yt-dlp-ejs) and gallery-dl, and uses them from the next start
  once they pass a check, so a site's change is usually handled within days of yt-dlp's fix, without
  a new APK. It can be turned off under Downloads › ⋮ › Diagnostics.
- Pause, resume and retry, with a reason shown when something fails.
- **Blocking ads and trackers** in the browser with EasyList and EasyPrivacy, the lists uBlock
  Origin starts with: requests to ad and tracking servers are stopped, ad boxes are hidden, and ad
  pop-ups are refused. It can be turned off everywhere or for one site.

## What Chaya does not do

- **DRM-protected content** (Netflix and other services using Encrypted Media Extensions /
  Widevine). It cannot and must not be captured.
- **Players fed only through `MediaSource`** on other sites, where no file or manifest ever
  crosses the network. Supporting them would need a custom media pipeline or Chromium-level hooks.
- **Live broadcasts**, and videos with nothing saveable; Chaya says so instead of trying.
- **Content behind a sign-in, unless you choose it.** When a site needs one and the browser is
  signed in, Chaya offers to use that sign-in for the lookup. Sites can limit accounts used for
  automated downloads, so this is always your call.
- **Updating anything but those three Python packages.** Only pure-Python releases of yt-dlp,
  yt-dlp-ejs and gallery-dl are fetched. Python itself, the libraries bundled beside them
  (`requests`, `urllib3` and the rest) and Chaya's own code change only with a new APK, so a release
  that needs a newer library than the APK carries, or another Python, is skipped until then.
- **Joining HEVC or AV1 pictures.** Only H.264 is joined for now, so those qualities are not offered.
- **More than 50 tabs, or more than four in memory.** Up to 50 tabs can be open. Only the four shown
  most recently keep their page in memory; the others keep their address and history and load
  again when shown, as Chrome's do. Tabs come back after Android closes the app, but a page's own
  state (a form half filled in, how far a video had played) does not.
- **Bookmark folders, import, export or sync.** Bookmarks are one list, kept on this phone; there is
  no way yet to bring them in from another browser or take them out, and nothing is synced.
- **Browser extensions.** Android's WebView, which Chaya's browser is built on, cannot run them, so
  uBlock Origin itself cannot be added; the built-in blocker uses its lists instead.
- **What uBlock Origin does beyond its lists.** Its scriptlets and procedural filters are not
  applied, so ads a site serves from its own servers inside the video (YouTube's, for one) still
  play, and some pages keep an empty space where an ad was.

## Your responsibility

Chaya is for media you own or have permission to download. A site's terms of service may restrict
downloading; following them is up to you.
