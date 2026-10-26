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
- **Players embedded from other servers.** Many pages play their video in a frame from another
  server. A small script of Chaya's runs inside such frames. It only looks at what the frame's own
  player has already loaded, and tells the page's media list about video and audio. It sends nothing
  anywhere and makes no request of its own. It cannot reach the app; the page's detector passes its
  findings on, and Chaya keeps only addresses the browser really loaded. A file from such a player
  is downloaded naming the player's page as the referrer, as the player's own requests do.
- **Storage.** Downloads are saved to app-private storage; on Android 10 and newer, each finished
  download is also copied to your phone's public media folders (Movies, Music, Pictures, Downloads), where
  other apps can see it. Download history (addresses, file names, progress) is kept in
  a database on your phone.
- **Notifications.** Download progress, only after you grant the system permission, and only after
  an in-app explanation on first use.

## What Chaya stores, and where

- Download history: **on your phone only**.
- Frames taken from your videos for the downloads grid, and files taken out of an account's ZIP
  to view or share: **on your phone only**, in the app's cache. They are deleted with their
  download, and Android may clear them sooner. *Share* hands a file only to the app you pick, and
  *Save to phone* puts a copy in your phone's own folders.
- Browsing history (each page's address, title, when you last opened it and how often) and
  bookmarks: **on your phone only**, in the app's database. They are used only to show the History
  and Bookmarks screens and the suggestions under the address bar. History → ⋮ → *Clear history*
  deletes the last hour, the last day or everything; × beside a page deletes just that page; the
  *Save history* switch on the History screen stops pages being kept at all. × beside a bookmark,
  or the star beside the address, removes it.
- Ad blocker settings (whether it is on, and the sites you let show ads): **on your phone only**.
- Open tabs (their addresses, titles, back and forward history, a small picture of each page and
  the site's icon), so they come back after Android closes the app: **on your phone only**, in
  app-private files. Closing a tab deletes its files; *Close all tabs* in the tab grid's ⋮ menu
  deletes them all.
- Newer copies of yt-dlp, yt-dlp-ejs and gallery-dl fetched from PyPI, and when they were checked:
  **on your phone only**, in app-private files.
- Diagnostics (crash reports, an event log with addresses stripped of their query and fragment):
  **on your phone only**, in app-private files. Nothing is uploaded automatically; a report leaves
  the phone only when you tap Share.

## Network traffic

Chaya talks only to the sites involved in what you do:

- the pages you open, and the media you download from them;
- same-origin checks of a page's media types, only after you tap *Scan more thoroughly*;
- for a YouTube, Instagram, TikTok, X or Pinterest video, that site, which yt-dlp (running on your phone)
  contacts to find the video's files (for an Instagram post, its pictures too), and for an X post with
  pictures, a Pinterest pin, or an Instagram post yt-dlp could not read, that site again, which gallery-dl (also running
  on your phone) contacts to list the post's pictures, both when you paste or share a link and when the page you
  are viewing is a single video on one of those sites;
- for the ad blocker, about once a week, easylist.to, to fetch fresh copies of the EasyList and
  EasyPrivacy lists. The request carries no cookies and nothing about you or the pages you visit;
  the lists are then used on your phone. Blocked requests never leave the phone at all.
- to keep the video-site engine current, about once a day, pypi.org, to ask whether yt-dlp,
  yt-dlp-ejs or gallery-dl have a newer release, and files.pythonhosted.org (where PyPI keeps its
  files) to fetch one when they do. The requests carry no cookies and nothing about you, the links
  you look up or the pages you visit. Turn this off under Downloads › ⋮ › Diagnostics › *Keep it up
  to date*.

## Chaya for Windows

Chaya for Windows is in development and not released yet. It keeps the same promises, with these
differences:

- **Pages you open** in its built-in browser load as in any browser (Microsoft Edge WebView2), and keep
  their cookies in Chaya's own WebView2 folder on your PC. A page cannot reach the app.
- **Finding media on a page.** A small script of Chaya's runs in the pages you open, frames included. It
  only looks at what the page's players already loaded, makes no request of its own and sends nothing
  anywhere; Chaya reads its list to show you what can be saved.
- **Downloads never carry a page's cookies.** A video, sound or picture a page downloads is fetched by
  Chaya with the page as referrer only, so content behind a login may not download yet.
- **Links to YouTube, Instagram, TikTok and X** are looked up with yt-dlp on your PC, without any
  sign-in. So is a stream found on a page when you ask for its qualities: yt-dlp reads that stream's
  playlist from its server, naming the page as the referrer, as its player would.

## What Chaya never does

- No analytics, no tracking, no ads, no third-party SDKs.
- No account and no Chaya server: there is nowhere for your data to go.

## Contact

Open an issue on the GitHub repository for privacy questions.
