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
- Pause, resume and retry, with a reason shown when something fails.

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
- **Saving streams as standalone files.** HLS/DASH downloads play in the app; exporting them as MP4
  is not built yet.

## Your responsibility

Chaya is for media you own or have permission to download. A site's terms of service may restrict
downloading; following them is up to you.
