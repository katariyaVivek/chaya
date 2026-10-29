//! Subtitles made on the PC: a saved video's sound is listened to by whisper.cpp with a speech model the person
//! picks (`models`), fetched the first time it is needed, and what was said is saved next to the video as
//! "name.en.srt" (`subtitles`), in the language heard or the one the person picked. Nothing is uploaded: the model is
//! the only thing fetched, once, and the listening is done on the PC (`whisper`).
//!
//! Everything lives in Chaya's data folder under `speech/`: `models/` (the models), `work/ID/` (a video's sound and
//! what whisper.cpp wrote, while it listens), and `settings.json` (the model picked, the language, and whether every
//! download gets subtitles).

pub mod lines;
pub mod models;
pub mod whisper;

use std::path::{Path, PathBuf};

use reqwest::Client;
use serde::{Deserialize, Serialize};
use tokio::sync::watch;

use crate::download::error::DownloadError;
use crate::download::http::Progress;
use crate::subtitles;

use self::models::{Model, Models};
use self::whisper::{Heard, Request, Stage, Transcript, Whisper};

/// Why subtitles could not be made.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SpeechError {
    /// whisper.cpp or ffmpeg is not in the engine, or would not start.
    NoProgram,
    ModelFetch(DownloadError),
    /// The model that arrived is not the one Chaya pins.
    WrongSum,
    /// ffmpeg found no sound it could read.
    NoSound,
    /// whisper.cpp stopped with an error.
    CouldNotListen,
    /// whisper.cpp's answer could not be read.
    Unreadable,
    /// Nothing was said, or nothing whisper.cpp could make out.
    NothingHeard,
    CantWrite,
    /// The person stopped it.
    Stopped,
}

impl SpeechError {
    /// What the window says.
    pub fn message(self) -> String {
        match self {
            SpeechError::NoProgram => {
                "Chaya couldn't start its speech recognition. Installing Chaya again puts it back.".into()
            }
            SpeechError::ModelFetch(error) => format!("The speech model couldn't be fetched. {}", error.message()),
            SpeechError::WrongSum => {
                "The speech model that arrived isn't the one Chaya expects, so it wasn't kept. Try again.".into()
            }
            SpeechError::NoSound => "This file has no sound Chaya can read.".into(),
            SpeechError::CouldNotListen | SpeechError::Unreadable => "Speech recognition stopped with an error.".into(),
            SpeechError::NothingHeard => "No speech was heard in this video.".into(),
            SpeechError::CantWrite => "Chaya couldn't write the subtitles next to the video.".into(),
            SpeechError::Stopped => "Stopped.".into(),
        }
    }
}

/// What the person chose in Settings › Speech recognition.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    /// The model listened with (`models::Model::id`).
    pub model: String,
    /// The language spoken ("en"), or None to have whisper.cpp detect it.
    pub language: Option<String>,
    /// Make subtitles for every video downloaded that came without any.
    pub every_download: bool,
    /// Listen on the graphics card, where there is one (`whisper`'s Vulkan build); the processor only when false.
    #[serde(default = "yes")]
    pub graphics_card: bool,
}

fn yes() -> bool {
    true
}

impl Default for Settings {
    fn default() -> Settings {
        Settings {
            model: models::default_model().id.clone(),
            language: None,
            every_download: false,
            graphics_card: true,
        }
    }
}

impl Settings {
    /// The model picked, or the default when the one named is not offered any more.
    pub fn model(&self) -> &'static Model {
        models::find(&self.model).unwrap_or_else(models::default_model)
    }
}

/// What one video's subtitles are being made from, and how far it has got.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Step {
    /// The model is being fetched: `done` of `total` bytes.
    Model {
        done: u64,
        total: u64,
    },
    Sound,
    Listening {
        percent: u8,
    },
}

/// The programs that make subtitles: ffmpeg for the sound, whisper.cpp to listen.
#[derive(Clone, Debug)]
pub struct Tools {
    pub ffmpeg: PathBuf,
    pub whisper: Whisper,
}

/// Subtitles made: where they were saved, what was heard (kept in the video's own file, `video`), and whether it was
/// heard on the graphics card.
#[derive(Clone, Debug, PartialEq)]
pub struct Made {
    pub path: PathBuf,
    pub transcript: Transcript,
    pub card: bool,
}

/// Chaya's speech folder.
#[derive(Clone, Debug)]
pub struct Speech {
    folder: PathBuf,
    pub models: Models,
}

impl Speech {
    pub fn in_folder(folder: PathBuf) -> Speech {
        let models = Models::in_folder(folder.join("models"));
        Speech { folder, models }
    }

    /// With models fetched from `source` (a local test server).
    pub fn with_source(folder: PathBuf, source: &str) -> Speech {
        let models = Models::from_source(folder.join("models"), source);
        Speech { folder, models }
    }

    pub fn settings(&self) -> Settings {
        std::fs::read(self.folder.join("settings.json"))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }

    pub fn save_settings(&self, settings: &Settings) -> std::io::Result<()> {
        std::fs::create_dir_all(&self.folder)?;
        std::fs::write(self.folder.join("settings.json"), serde_json::to_vec_pretty(settings)?)
    }

    /// Makes subtitles for download `id`, the video at `video`, as `settings` say: fetches their model if it is not
    /// here yet, listens in their language (or detects it), on the graphics card if they allow it, and saves what was
    /// heard next to the video as "name.LANGUAGE.srt", replacing a file of that name. `stop` turning true stops it;
    /// what a model fetch had brought is kept for next time.
    #[allow(clippy::too_many_arguments)]
    pub async fn make(
        &self,
        id: u64,
        video: &Path,
        settings: &Settings,
        tools: &Tools,
        client: &Client,
        stop: &watch::Receiver<bool>,
        on_step: &mut (dyn FnMut(Step) + Send),
    ) -> Result<Made, SpeechError> {
        let (model, language) = (settings.model(), settings.language.as_deref());
        let total = model.size;
        let path = self
            .models
            .get(client, model, stop, &mut |p: Progress| on_step(Step::Model { done: p.done, total }))
            .await?;
        let work = self.folder.join("work").join(id.to_string());
        let request = Request {
            folder: &self.folder,
            model: &path,
            dtw: &model.dtw,
            work: &work,
            language,
            threads: whisper::threads(),
            card: settings.graphics_card,
        };
        let heard = whisper::transcribe(&tools.ffmpeg, &tools.whisper, video, &request, stop, &mut |stage| {
            on_step(match stage {
                Stage::Sound => Step::Sound,
                Stage::Listening { percent } => Step::Listening { percent },
            })
        })
        .await;
        let _ = tokio::fs::remove_dir_all(&work).await;
        let Heard { transcript, card } = heard?;
        let lines = transcript.lines();
        if lines.is_empty() {
            return Err(SpeechError::NothingHeard);
        }
        let tag = language.or(Some(transcript.language.as_str())).and_then(subtitles::file_language);
        let path = subtitles::file_for(video, tag.as_deref().unwrap_or("und"));
        tokio::fs::write(&path, subtitles::to_srt(&lines)).await.map_err(|_| SpeechError::CantWrite)?;
        Ok(Made { path, transcript, card })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_start_with_the_default_model_detecting_the_language_for_no_download() {
        let dir = tempfile::tempdir().unwrap();
        let speech = Speech::in_folder(dir.path().join("speech"));
        let settings = speech.settings();
        let default = Settings {
            model: "large-v3-turbo-q5_0".into(),
            language: None,
            every_download: false,
            graphics_card: true,
        };
        assert_eq!(settings, default);
        // Settings saved before the graphics card could be chosen use it.
        std::fs::create_dir_all(dir.path().join("speech")).unwrap();
        std::fs::write(
            dir.path().join("speech").join("settings.json"),
            r#"{"model": "tiny", "language": null, "everyDownload": true}"#,
        )
        .unwrap();
        assert!(speech.settings().graphics_card);
        let chosen =
            Settings { model: "tiny".into(), language: Some("es".into()), every_download: true, graphics_card: false };
        speech.save_settings(&chosen).unwrap();
        assert_eq!(speech.settings(), chosen);
        assert_eq!(speech.settings().model().name, "Tiny");
        let gone = Settings { model: "a model no longer offered".into(), ..chosen };
        assert_eq!(gone.model().id, "large-v3-turbo-q5_0");
    }

    #[test]
    fn every_failure_has_a_sentence_for_the_window() {
        for error in [
            SpeechError::NoProgram,
            SpeechError::ModelFetch(DownloadError::Network),
            SpeechError::WrongSum,
            SpeechError::NoSound,
            SpeechError::CouldNotListen,
            SpeechError::Unreadable,
            SpeechError::NothingHeard,
            SpeechError::CantWrite,
        ] {
            let message = error.message();
            assert!(message.ends_with('.') && message.len() > 20, "{error:?}: {message}");
        }
    }

    /// The JFK sample whisper.cpp ships (public domain), as a video's sound, listened to for real by whisper-cli
    /// (`CHAYA_WHISPER`) with the Tiny model (`CHAYA_WHISPER_MODEL`), the sound made by ffmpeg (`CHAYA_FFMPEG`). CI sets
    /// all three; without them this test says it was skipped.
    #[tokio::test(flavor = "multi_thread")]
    async fn the_jfk_sample_comes_back_as_what_was_said_saved_next_to_it() {
        let env = |name| std::env::var_os(name).map(PathBuf::from).filter(|p| p.is_file());
        let (Some(whisper), Some(model), Some(ffmpeg)) =
            (env("CHAYA_WHISPER"), env("CHAYA_WHISPER_MODEL"), env("CHAYA_FFMPEG"))
        else {
            eprintln!("CHAYA_WHISPER, CHAYA_WHISPER_MODEL or CHAYA_FFMPEG is not set; skipping the JFK sample.");
            return;
        };
        let dir = tempfile::tempdir().unwrap();
        // The model where Chaya keeps it, as if fetched before; nothing is fetched here.
        let speech = Speech::with_source(dir.path().join("speech"), "http://127.0.0.1:9");
        let tiny = models::find("tiny").unwrap();
        let settings = Settings { model: tiny.id.clone(), ..Settings::default() };
        std::fs::create_dir_all(dir.path().join("speech").join("models")).unwrap();
        std::fs::copy(&model, speech.models.path(tiny)).unwrap();
        let downloads = dir.path().join("Downloads");
        std::fs::create_dir_all(&downloads).unwrap();
        let video = downloads.join("Inaugural address.m4a");
        std::fs::copy(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/jfk.m4a"), &video).unwrap();
        // With CHAYA_WHISPER_VULKAN too (Windows, with a Vulkan driver that runs on the processor), the Vulkan build.
        let vulkan = env("CHAYA_WHISPER_VULKAN");
        let card = vulkan.is_some() && std::env::var_os("CHAYA_EXPECT_CARD").is_some();
        let tools = Tools { ffmpeg, whisper: Whisper { program: whisper, vulkan } };
        let (_tx, stop) = watch::channel(false);
        let mut steps = Vec::new();

        let made =
            speech.make(1, &video, &settings, &tools, &Client::new(), &stop, &mut |s| steps.push(s)).await.unwrap();

        assert_eq!(made.transcript.language, "en");
        assert_eq!(made.card, card, "on the graphics card when one was expected");
        assert_eq!(made.path, downloads.join("Inaugural address.en.srt"));
        let srt = std::fs::read_to_string(&made.path).unwrap();
        let lines = subtitles::parse(&srt);
        let heard = lines.iter().map(|l| l.text.as_str()).collect::<Vec<_>>().join(" ").to_lowercase();
        assert!(heard.contains("ask not what your country can do for you"), "{srt}");
        assert!(lines.len() >= 2 && lines.iter().all(|l| l.end - l.start <= 5_500), "short lines: {srt}");
        let words: Vec<&str> =
            made.transcript.segments.iter().flat_map(|s| &s.words).map(|w| w.text.as_str()).collect();
        assert!(words.iter().any(|w| w.trim_matches(|c: char| !c.is_alphanumeric()).eq_ignore_ascii_case("country")));
        assert_eq!(steps.first(), Some(&Step::Sound));
        assert!(steps.iter().any(|s| matches!(s, Step::Listening { .. })));
        assert!(!dir.path().join("speech").join("work").join("1").exists(), "its work folder is gone");
    }
}
