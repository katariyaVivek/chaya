# Chaya Privacy Policy

**Last updated:** September 2026. Chaya is distributed as a direct APK via
GitHub Releases (no Play Store account, no third-party SDKs).

## What Chaya accesses

- **Browsing session (WebView cookies, User-Agent, Referer).** Used for one
  purpose only: downloading media you are already viewing, including
  authenticated content behind your own login. Cookies are forwarded to the
  download request for that file and never stored anywhere except Android's
  own WebView cookie jar.
- **Storage.** Completed downloads are saved to app-private storage, with an
  optional user-initiated export to public media storage (MediaStore).
  Download history (URLs, filenames, progress) lives in a local Room
  database on your device.
- **Notifications.** Download progress notifications, only after you grant
  the system permission — and only after an in-app explanation on first use.

## What Chaya stores, and where

- Download history: **on your device only** (Room database).
- Diagnostics (crash reports, event log with scrubbed URLs): **on your
  device only**, under app-private files. Nothing is uploaded automatically;
  a report leaves the phone only when you explicitly tap Share.

## What Chaya never does

- No analytics, no tracking, no ad SDKs, no network calls except the ones
  you trigger (page loads, media downloads, opt-in media-type checks).
- No account, no sign-in, no server side at all — there is nowhere for your
  data to go.

## Contact

File an issue on the GitHub repository for privacy questions.
