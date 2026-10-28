//! Joining a picture-only file and a sound-only file into one MP4 (a WebM for a VP8 or VP9 picture), and turning a
//! saved stream into one, with the cut-down ffmpeg the app carries (`desktop/engine/build_ffmpeg.sh`). Tracks are copied as they are: nothing
//! is re-encoded, so this takes seconds and loses nothing. As with the engine, Windows opens no console window
//! for it.

use std::path::{Path, PathBuf};
use std::process::Stdio;

use tokio::process::Command;
use tokio::sync::watch;

use super::error::DownloadError;
use super::http::Outcome;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Joiner {
    pub ffmpeg: PathBuf,
}

impl Joiner {
    /// The ffmpeg in the engine folder, or `CHAYA_FFMPEG` in development; `None` when there is neither, and the
    /// app then offers no quality that needs joining.
    pub fn find(engine_folder: &Path) -> Option<Joiner> {
        ["ffmpeg.exe", "ffmpeg"]
            .iter()
            .map(|name| engine_folder.join(name))
            .find(|path| path.is_file())
            .or_else(|| std::env::var_os("CHAYA_FFMPEG").map(PathBuf::from).filter(|p| p.is_file()))
            .map(|ffmpeg| Joiner { ffmpeg })
    }

    /// Writes `picture` and `sound` into `out` as one MP4. `stop` turning true stops it and removes `out`.
    pub async fn join(
        &self,
        picture: &Path,
        sound: &Path,
        out: &Path,
        stop: &watch::Receiver<bool>,
    ) -> Result<Outcome, DownloadError> {
        // The first picture track of one and the first sound track of the other.
        let maps = ["-map", "0:v:0", "-map", "1:a:0"];
        self.copy(&[picture, sound], &maps, out, stop, DownloadError::CouldNotJoin).await
    }

    /// Writes a saved stream (MPEG-TS, fragmented MP4, or bare AAC sound) into `out` as an MP4: its first picture
    /// track and its first sound track, whichever it has.
    pub async fn convert(
        &self,
        stream: &Path,
        out: &Path,
        stop: &watch::Receiver<bool>,
    ) -> Result<Outcome, DownloadError> {
        let maps = ["-map", "0:v:0?", "-map", "0:a:0?"];
        self.copy(&[stream], &maps, out, stop, DownloadError::CouldNotConvert).await
    }

    /// Copies the `maps` tracks of `inputs` into `out` as they are; `failed` is the error when ffmpeg cannot.
    async fn copy(
        &self,
        inputs: &[&Path],
        maps: &[&str],
        out: &Path,
        stop: &watch::Receiver<bool>,
        failed: DownloadError,
    ) -> Result<Outcome, DownloadError> {
        let mut command = Command::new(&self.ffmpeg);
        command.args(["-hide_banner", "-loglevel", "error", "-nostdin", "-y"]);
        for input in inputs {
            command.arg("-i").arg(input);
        }
        // A WebM for a VP8 or VP9 picture (`Choice::saved_as`), named so: "name.webm.part". Otherwise an MP4,
        // with the index at the front, so players can start before reading the whole file.
        let name = out.file_name().map(|n| n.to_string_lossy().to_ascii_lowercase()).unwrap_or_default();
        let into: &[&str] = if name.trim_end_matches(".part").ends_with(".webm") {
            &["-c", "copy", "-f", "webm"]
        } else {
            &["-c", "copy", "-movflags", "+faststart", "-f", "mp4"]
        };
        command
            .args(maps)
            .args(into)
            .arg(out)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        #[cfg(windows)]
        command.creation_flags(0x0800_0000); // CREATE_NO_WINDOW

        let child = command.spawn().map_err(|_| failed)?;
        let mut stop = stop.clone();
        let result = tokio::select! {
            output = child.wait_with_output() => Some(output),
            _ = stop.wait_for(|stopped| *stopped) => None,
        };
        match result {
            Some(Ok(output)) if output.status.success() => Ok(Outcome::Done),
            Some(_) => {
                let _ = tokio::fs::remove_file(out).await;
                Err(failed)
            }
            // Dropping the child stopped ffmpeg; a half-written file is no use.
            None => {
                let _ = tokio::fs::remove_file(out).await;
                Ok(Outcome::Stopped)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PICTURE: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/picture.mp4");
    const SOUND: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/sound.m4a");
    const STREAM: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/stream.ts");
    /// VP9 picture, Opus sound, and the two together, one second each, as YouTube's and many players' WebM is.
    const WEBM_PICTURE: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/picture.webm");
    const WEBM_SOUND: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/sound.webm");
    const WEBM_STREAM: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/stream.webm");

    /// A WebM file: Matroska's EBML header naming "webm", with a VP9 picture and Opus sound.
    fn is_vp9_and_opus_webm(saved: &[u8]) -> bool {
        let has = |text: &[u8]| saved.windows(text.len()).any(|w| w == text);
        saved.starts_with(&[0x1A, 0x45, 0xDF, 0xA3]) && has(b"webm") && has(b"V_VP9") && has(b"A_OPUS")
    }

    /// The ffmpeg to test with: `CHAYA_FFMPEG`, or one on the PATH. CI installs one; without it the joining
    /// tests say they were skipped.
    fn joiner() -> Option<Joiner> {
        let ffmpeg = std::env::var_os("CHAYA_FFMPEG").map(PathBuf::from).unwrap_or_else(|| "ffmpeg".into());
        let works = std::process::Command::new(&ffmpeg).arg("-version").output().is_ok_and(|o| o.status.success());
        if !works {
            eprintln!("No ffmpeg here; skipping the joining test.");
        }
        works.then_some(Joiner { ffmpeg })
    }

    #[tokio::test]
    async fn a_picture_and_its_sound_become_one_mp4() {
        let Some(joiner) = joiner() else { return };
        let folder = tempfile::tempdir().unwrap();
        let out = folder.path().join("Clip (720p).mp4.part");
        let (_tx, stop) = watch::channel(false);

        let result = joiner.join(Path::new(PICTURE), Path::new(SOUND), &out, &stop).await;

        assert_eq!(result, Ok(Outcome::Done));
        let joined = std::fs::read(&out).unwrap();
        let picture = std::fs::metadata(PICTURE).unwrap().len() as usize;
        assert!(joined.len() > picture, "holds both tracks: {} bytes", joined.len());
        // An MP4 starts with its type box, and with faststart the index (moov) comes before the data (mdat).
        assert_eq!(&joined[4..8], b"ftyp");
        let at = |name: &[u8]| joined.windows(4).position(|w| w == name).unwrap();
        assert!(at(b"moov") < at(b"mdat"));
    }

    #[tokio::test]
    async fn a_vp9_picture_and_its_opus_sound_become_one_webm() {
        let Some(joiner) = joiner() else { return };
        let folder = tempfile::tempdir().unwrap();
        let out = folder.path().join("Clip (2160p).webm.part");
        let (_tx, stop) = watch::channel(false);

        let result = joiner.join(Path::new(WEBM_PICTURE), Path::new(WEBM_SOUND), &out, &stop).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert!(is_vp9_and_opus_webm(&std::fs::read(&out).unwrap()));
    }

    #[tokio::test]
    async fn a_saved_webm_stream_becomes_a_webm_with_its_picture_and_sound() {
        let Some(joiner) = joiner() else { return };
        let folder = tempfile::tempdir().unwrap();
        let out = folder.path().join("Clip (1080p).webm.part");
        let (_tx, stop) = watch::channel(false);

        let result = joiner.convert(Path::new(WEBM_STREAM), &out, &stop).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert!(is_vp9_and_opus_webm(&std::fs::read(&out).unwrap()));
    }

    #[tokio::test]
    async fn a_file_that_is_not_media_cannot_be_joined_and_leaves_nothing() {
        let Some(joiner) = joiner() else { return };
        let folder = tempfile::tempdir().unwrap();
        let broken = folder.path().join("picture");
        std::fs::write(&broken, b"<html>an error page, not a video</html>").unwrap();
        let out = folder.path().join("Clip.mp4.part");
        let (_tx, stop) = watch::channel(false);

        let result = joiner.join(&broken, Path::new(SOUND), &out, &stop).await;

        assert_eq!(result, Err(DownloadError::CouldNotJoin));
        assert!(!out.exists());
    }

    #[tokio::test]
    async fn a_saved_stream_becomes_an_mp4_with_its_picture_and_sound() {
        let Some(joiner) = joiner() else { return };
        let folder = tempfile::tempdir().unwrap();
        let out = folder.path().join("Clip (720p).mp4.part");
        let (_tx, stop) = watch::channel(false);

        let result = joiner.convert(Path::new(STREAM), &out, &stop).await;

        assert_eq!(result, Ok(Outcome::Done));
        let saved = std::fs::read(&out).unwrap();
        assert_eq!(&saved[4..8], b"ftyp");
        let at = |name: &[u8]| saved.windows(4).position(|w| w == name);
        assert!(at(b"moov").unwrap() < at(b"mdat").unwrap());
        // Both tracks: H.264 picture and AAC sound, copied from the stream.
        assert!(at(b"avcC").is_some() && at(b"mp4a").is_some());
    }

    #[tokio::test]
    async fn a_stream_that_is_not_media_cannot_be_saved_and_leaves_nothing() {
        let Some(joiner) = joiner() else { return };
        let folder = tempfile::tempdir().unwrap();
        let broken = folder.path().join("Clip.mp4.stream");
        std::fs::write(&broken, b"<html>an error page where a piece should be</html>").unwrap();
        let out = folder.path().join("Clip.mp4.part");
        let (_tx, stop) = watch::channel(false);

        let result = joiner.convert(&broken, &out, &stop).await;

        assert_eq!(result, Err(DownloadError::CouldNotConvert));
        assert!(!out.exists());
    }

    #[tokio::test]
    async fn a_missing_ffmpeg_is_a_failed_join() {
        let joiner = Joiner { ffmpeg: "/nowhere/ffmpeg.exe".into() };
        let (_tx, stop) = watch::channel(false);
        let result = joiner.join(Path::new(PICTURE), Path::new(SOUND), Path::new("out.mp4"), &stop).await;
        assert_eq!(result, Err(DownloadError::CouldNotJoin));
    }

    #[test]
    fn the_engine_folders_ffmpeg_is_found() {
        let folder = tempfile::tempdir().unwrap();
        std::fs::write(folder.path().join("ffmpeg.exe"), "").unwrap();
        assert_eq!(Joiner::find(folder.path()), Some(Joiner { ffmpeg: folder.path().join("ffmpeg.exe") }));
    }
}
