# Chaya Privacy Policy

**Last updated:** October 2026. Chaya is distributed as a direct APK through GitHub Releases: no Play
Store account, no third-party SDKs.

## What Chaya accesses

- **Your browsing session (WebView cookies, User-Agent, Referer).** Used for one purpose: downloading
  media you are already viewing, including content behind your own login. Cookies are sent with the
  download request for that file and are not stored anywhere except Android's own WebView cookie
  jar.
- **Your sign-in on YouTube, Instagram, TikTok or X, only if you choose it.** When a video needs a
  sign-in and the browser is signed in, Chaya offers *Use my sign-in*. If you tap it, that site's
  cookies are written to a temporary file in the app's private storage for yt-dlp (or gallery-dl,
  for a post's pictures) to use, and the
  file is deleted as soon as the lookup ends, whether it worked or not. Chaya never uses your
  sign-in without that tap.
- **Saving a whole Instagram or X account.** The account sheet asks whether to use your sign-in
  (*Save all posts with my sign-in*) or not (*Try without signing in*). That choice is stored with the
  download, so resuming it later never switches to your sign-in by itself. The cookies are used only
  while the account's posts are listed, then deleted; the files themselves are fetched without them.
- **Storage.** Downloads are saved to app-private storage; on Android 10 and newer, each finished
  download is also copied to your phone's public media folders (Movies, Music, Pictures, Downloads), where
  other apps can see it. Download history (addresses, file names, progress) is kept in
  a database on your phone.
- **Notifications.** Download progress, only after you grant the system permission, and only after
  an in-app explanation on first use.

## What Chaya stores, and where

- Download history: **on your phone only**.
- Ad blocker settings (whether it is on, and the sites you let show ads): **on your phone only**.
- Diagnostics (crash reports, an event log with addresses stripped of their query and fragment):
  **on your phone only**, in app-private files. Nothing is uploaded automatically; a report leaves
  the phone only when you tap Share.

## Network traffic

Chaya talks only to the sites involved in what you do:

- the pages you open, and the media you download from them;
- same-origin checks of a page's media types, only after you tap *Scan more thoroughly*;
- for a YouTube, Instagram, TikTok or X video, that site, which yt-dlp (running on your phone)
  contacts to find the video's files, and for an Instagram or X post with pictures, that site again,
  which gallery-dl (also running on your phone) contacts to list the post's pictures, both when you paste or share a link and when the page you
  are viewing is a single video on one of those sites;
- for the ad blocker, about once a week, easylist.to, to fetch fresh copies of the EasyList and
  EasyPrivacy lists. The request carries no cookies and nothing about you or the pages you visit;
  the lists are then used on your phone. Blocked requests never leave the phone at all.

## What Chaya never does

- No analytics, no tracking, no ads, no third-party SDKs.
- No account and no Chaya server: there is nowhere for your data to go.

## Contact

Open an issue on the GitHub repository for privacy questions.
