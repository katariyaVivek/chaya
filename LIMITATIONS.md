# What Chaya does and does not do

These are deliberate scope decisions, not open bugs.

## What Chaya covers

- **Media on the pages you browse:** direct audio and video files (MP4, WebM, MP3, …) and HLS/DASH
  streams (`.m3u8`, `.mpd`), found from network traffic and from the page itself.
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
- **Joining HEVC or AV1 pictures.** Only H.264 is joined for now, so those qualities are not offered.
- **Tabs after the app is closed.** Open tabs last while Chaya runs; when Android closes the app,
  it starts again with one tab.
- **Browser extensions.** Android's WebView, which Chaya's browser is built on, cannot run them, so
  uBlock Origin itself cannot be added; the built-in blocker uses its lists instead.
- **What uBlock Origin does beyond its lists.** Its scriptlets and procedural filters are not
  applied, so ads a site serves from its own servers inside the video (YouTube's, for one) still
  play, and some pages keep an empty space where an ad was.
- **Saving streams as standalone files.** HLS/DASH downloads play in the app; exporting them as MP4
  is not built yet.

## Your responsibility

Chaya is for media you own or have permission to download. A site's terms of service may restrict
downloading; following them is up to you.
