# Build and test

## What you need

- JDK 17
- Android SDK platform 36 and build-tools 35.0.0
- Gradle 8.14.3. **The wrapper jar is not checked in**: either install Gradle and call `gradle`
  directly (what CI does), run `gradle wrapper --gradle-version 8.14.3` once to get `./gradlew`, or
  open the project in Android Studio, which generates it.
- Python 3.13 on the `PATH`. Chaquopy uses it at build time to install yt-dlp into the app.

Library versions are in `gradle/libs.versions.toml`.

## Build

```sh
gradle assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Tests

```sh
gradle testDebugUnitTest                      # JVM + Robolectric (no device)
gradle testDebugUnitTest --tests 'com.chaya.app.download.*'
python -m pytest app/src/test/python          # the yt-dlp engine; needs the pinned yt-dlp (see engine.yml)
gradle connectedDebugAndroidTest              # on a connected device or emulator
```

What each suite covers is in [`ARCHITECTURE.md`](ARCHITECTURE.md#tests).

## CI

| Workflow | When | What |
|---|---|---|
| `build.yml` | every PR and push to `main` | unit tests, then the debug APK. Test-only changes skip the APK. A failing test prints its message, and the reports are uploaded. |
| `build.yml` (manual) | *Run workflow* | also runs the instrumented tests on an emulator |
| `build.yml` (tag `v*`) | pushing a version tag | tests, signed release APK, GitHub Release |
| `engine.yml` | PRs touching the Python engine | pytest with the yt-dlp versions pinned in `app/build.gradle.kts` |
| `device-tests.yml` | `ui-check` label on a PR, or manual | `connectedDebugAndroidTest` in a cloud emulator |
| `emulator-check.yml` | `ui-check` label on a PR, or manual | `walkthrough.py` drives the app; screenshots, screen text and logs are uploaded as `emulator-evidence` |

## Releases

1. **Create a keystore once**, and back it up: losing it means a new app identity.
   ```sh
   keytool -genkeypair -keystore chaya-release.keystore -alias chaya \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. **Local release builds:** copy `keystore.properties.example` to `keystore.properties`
   (gitignored) and fill it in. Without it, release builds fall back to debug signing, for
   checking only; never distribute that APK.
3. **CI releases:** set the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`
   and `KEY_PASSWORD`, then push a tag (`git tag v0.4.0 && git push origin v0.4.0`).

Chaya ships as a direct APK through GitHub Releases. The Play Store is deferred: a media downloader
is close to its copyrighted-content policy, and the diagnostics assume no Play Services.

## Manual check on a phone

1. **Browse:** enter an address; back, forward and refresh work, and the keyboard closes after Go.
2. **A page with a video file:** the floating pill appears; tap it. The page's main video is first,
   with the page title, and likely ads are folded away. Tap *Download*.
3. **A stream (`.m3u8`/`.mpd`):** the sheet shows *Stream* and a stream-pieces-hidden note.
   *Download* starts at the best quality; *Quality* lists one entry per rendition.
4. **Extensionless media:** on a page with nothing detected, *Scan more thoroughly* appears after a
   moment. Only after you tap it does Chaya check same-origin candidates (at most ten).
5. **A video-site link:** paste a YouTube, Instagram, TikTok or X link with the Paste chip, or
   share it to Chaya. The sheet finds the video and lists qualities.
6. **Background:** start a download and go Home. The notification shows progress, Pause and
   Cancel.
7. **Downloads:** filters, pause/resume/retry, swipe to delete with Undo, open a file, and play a
   finished stream in the in-app player.
8. **Appearance:** Downloads › ⋮ › Appearance switches between Same as phone, Light and Dark at
   once, status bar included, and the choice survives a restart.
9. **Persistence:** finish a download, force-stop the app, reopen: it is still listed. A download
   that was running comes back paused.
