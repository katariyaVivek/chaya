//! What only Chaya keeps about a saved video, in its own data folder: one JSON file per video, named by the download's
//! number as `downloads.json` numbers them ("videos/12.json"): where playback stopped, and what whisper.cpp heard in it,
//! each word with its time, when Chaya made its subtitles; its translations, highlights and clips. What a later Chaya
//! wrote that this one does not know is kept as it was. Beside it, "videos/12.jpg" is the video's poster: a frame of it, which the window takes from the
//! saved file, as a site's thumbnail address may stop working (Instagram's and TikTok's do within hours) or never was.

use std::path::PathBuf;
use std::sync::{Mutex, PoisonError};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::clips::{self, Clip, NewClip};
use crate::highlights::Highlight;
use crate::speech::whisper::{Device, Transcript};

/// The folder in Chaya's data folder that holds the videos' files.
pub const FOLDER: &str = "videos";

/// A poster bigger than this is not one the window made (it makes them a few hundred pixels wide).
const LARGEST_POSTER: usize = 1024 * 1024;

/// Playback stopped this close to the start begins at the start: nothing was watched.
const BARELY_STARTED: f64 = 5.0;
/// Playback stopped this close to the end (or within the last twentieth of a short video) begins again at the start:
/// it was watched to the end.
const NEARLY_DONE: f64 = 10.0;

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct VideoData {
    /// Where playback stopped, in seconds, and the video's length then.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stopped_at: Option<f64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration: Option<f64>,
    /// What whisper.cpp heard, the last time Chaya made subtitles for it, with which model, and where.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub transcript: Option<Transcript>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub transcript_model: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub transcript_on: Option<Device>,
    /// Its subtitles translated by the person's AI model, a version for each language and model.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub translations: Vec<Translation>,
    /// Its highlights, the last time the person's AI model found them.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub highlights: Option<Highlights>,
    /// The parts of it the person cut out (`clips`), in order.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub clips: Vec<Clip>,
    #[serde(flatten)]
    pub other: Map<String, Value>,
}

/// A translation of a video's subtitles: from which language's subtitles (`None` for a file that names none), into
/// which ("es"), by which model ("Gemini · gemini-3.5-flash-lite"), when it was begun (seconds since 1970), and a line
/// for each of the original's, `None` where it is not translated yet.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Translation {
    pub from: Option<String>,
    pub to: String,
    pub model: String,
    pub made: u64,
    pub lines: Vec<Option<String>>,
    /// The version saved next to the video as "name.es.srt", of those into its language.
    #[serde(default)]
    pub in_file: bool,
}

/// A video's highlights: found in which language's subtitles, by which model, when, and each.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Highlights {
    pub from: Option<String>,
    pub model: String,
    pub made: u64,
    pub items: Vec<Highlight>,
}

impl Translation {
    pub fn is_whole(&self) -> bool {
        self.lines.iter().all(Option::is_some)
    }
}

impl VideoData {
    /// Where playback starts when the video is opened again: where it stopped, unless that was at the very start or
    /// near enough the end.
    pub fn resume_at(&self) -> f64 {
        let Some(at) = self.stopped_at.filter(|at| at.is_finite() && *at >= BARELY_STARTED) else { return 0.0 };
        match self.duration.filter(|d| d.is_finite() && *d > 0.0) {
            Some(duration) if at >= duration - NEARLY_DONE.min(duration / 20.0) => 0.0,
            _ => at,
        }
    }
}

/// The folder of videos' files: `videos` in Chaya's data folder.
pub struct Videos {
    folder: PathBuf,
    /// Held while a file is changed: a translation's batch and a clip changed at the same moment both stay.
    changing: Mutex<()>,
}

impl Videos {
    pub fn in_folder(folder: PathBuf) -> Videos {
        Videos { folder, changing: Mutex::new(()) }
    }

    fn path(&self, id: u64) -> PathBuf {
        self.folder.join(format!("{id}.json"))
    }

    /// What is kept about download `id`; nothing yet, or a file that can't be read, is an empty one.
    pub fn load(&self, id: u64) -> VideoData {
        std::fs::read(self.path(id)).ok().and_then(|bytes| serde_json::from_slice(&bytes).ok()).unwrap_or_default()
    }

    /// Changes what is kept about download `id`, written whole to a file beside it first so a crash never leaves half.
    pub fn change(&self, id: u64, change: impl FnOnce(&mut VideoData)) -> std::io::Result<()> {
        let _changing = self.changing.lock().unwrap_or_else(PoisonError::into_inner);
        let mut data = self.load(id);
        change(&mut data);
        std::fs::create_dir_all(&self.folder)?;
        let path = self.path(id);
        let part = path.with_extension("json.part");
        // Compact: a long video's words are many.
        std::fs::write(&part, serde_json::to_vec(&data)?)?;
        std::fs::rename(&part, &path)
    }

    /// Where playback of download `id` stopped, in a video `duration` seconds long.
    pub fn stopped(&self, id: u64, at: f64, duration: Option<f64>) -> std::io::Result<()> {
        self.change(id, |data| {
            data.stopped_at = Some(at).filter(|at| at.is_finite() && *at >= 0.0);
            data.duration = duration.filter(|d| d.is_finite() && *d > 0.0).or(data.duration);
        })
    }

    /// Keeps what whisper.cpp heard in download `id`, listening with `model` `on` the graphics card or not.
    pub fn heard(&self, id: u64, transcript: Transcript, model: &str, on: Device) -> std::io::Result<()> {
        self.change(id, |data| {
            data.transcript = Some(transcript);
            data.transcript_model = Some(model.to_string());
            data.transcript_on = Some(on);
        })
    }

    /// Where download `id`'s poster is kept.
    pub fn poster(&self, id: u64) -> PathBuf {
        self.folder.join(format!("{id}.jpg"))
    }

    /// Keeps `jpeg` as download `id`'s poster: only a JPEG, and no bigger than the window makes one.
    pub fn keep_poster(&self, id: u64, jpeg: &[u8]) -> std::io::Result<()> {
        if !jpeg.starts_with(&[0xFF, 0xD8, 0xFF]) || jpeg.len() > LARGEST_POSTER {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "not a poster"));
        }
        std::fs::create_dir_all(&self.folder)?;
        let path = self.poster(id);
        let part = path.with_extension("jpg.part");
        std::fs::write(&part, jpeg)?;
        std::fs::rename(&part, &path)
    }

    /// Keeps `translation` of download `id`, in place of the one from the same subtitles, into the same language, by
    /// the same model.
    pub fn translated(&self, id: u64, translation: &Translation) -> std::io::Result<()> {
        self.change(id, |data| {
            let same =
                |t: &Translation| t.from == translation.from && t.to == translation.to && t.model == translation.model;
            match data.translations.iter_mut().find(|t| same(t)) {
                Some(kept) => *kept = translation.clone(),
                None => data.translations.push(translation.clone()),
            }
        })
    }

    /// Marks download `id`'s translation into `to` by `model` as the one saved next to the video, and no other into
    /// that language; the version marked, or `None` when there is no whole one of that model.
    pub fn in_file(&self, id: u64, to: &str, model: &str) -> std::io::Result<Option<Translation>> {
        let mut chosen = None;
        self.change(id, |data| {
            let found = data.translations.iter().position(|t| t.to == to && t.model == model && t.is_whole());
            let Some(found) = found else { return };
            for (i, translation) in data.translations.iter_mut().enumerate().filter(|(_, t)| t.to == to) {
                translation.in_file = i == found;
            }
            chosen = Some(data.translations[found].clone());
        })?;
        Ok(chosen)
    }

    /// Keeps `highlights` of download `id`, in place of any before.
    pub fn highlighted(&self, id: u64, highlights: Highlights) -> std::io::Result<()> {
        self.change(id, |data| data.highlights = Some(highlights))
    }

    /// Adds `wanted` to download `id`'s clips (`clips::add`), in a video `duration` milliseconds long when that is
    /// known: its clips after, and the ids of those asked for.
    pub fn add_clips(
        &self,
        id: u64,
        wanted: &[NewClip],
        duration: Option<u64>,
    ) -> std::io::Result<(Vec<Clip>, Vec<Option<u32>>)> {
        let mut out = (Vec::new(), Vec::new());
        self.change(id, |data| {
            let duration = duration.or(data.duration.map(|d| (d * 1000.0) as u64));
            out.1 = clips::add(&mut data.clips, wanted, duration);
            out.0 = data.clips.clone();
        })?;
        Ok(out)
    }

    /// Changes one of download `id`'s clips (`clips::change`), or removes it when `changed` is `None`: its clips after.
    pub fn change_clip(&self, id: u64, clip: u32, changed: Option<&Clip>) -> std::io::Result<Vec<Clip>> {
        let mut out = Vec::new();
        self.change(id, |data| {
            let duration = data.duration.map(|d| (d * 1000.0) as u64);
            match changed {
                Some(changed) => {
                    clips::change(&mut data.clips, &Clip { id: clip, ..changed.clone() }, duration);
                }
                None => {
                    clips::remove(&mut data.clips, clip);
                }
            }
            out = data.clips.clone();
        })?;
        Ok(out)
    }

    /// Forgets download `id`, and its poster, when it leaves the list: its number may be given to another download.
    pub fn forget(&self, id: u64) {
        let _ = std::fs::remove_file(self.path(id));
        let _ = std::fs::remove_file(self.poster(id));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stopped(at: f64, duration: Option<f64>) -> VideoData {
        VideoData { stopped_at: Some(at), duration, ..VideoData::default() }
    }

    #[test]
    fn playback_starts_where_it_stopped() {
        assert_eq!(stopped(754.5, Some(1800.0)).resume_at(), 754.5);
        assert_eq!(stopped(754.5, None).resume_at(), 754.5);
    }

    #[test]
    fn a_video_barely_started_or_watched_to_the_end_starts_at_the_start() {
        assert_eq!(VideoData::default().resume_at(), 0.0);
        assert_eq!(stopped(3.0, Some(1800.0)).resume_at(), 0.0);
        assert_eq!(stopped(1795.0, Some(1800.0)).resume_at(), 0.0);
        assert_eq!(stopped(1800.0, Some(1800.0)).resume_at(), 0.0);
        assert_eq!(stopped(f64::NAN, Some(1800.0)).resume_at(), 0.0);
        // A short clip's end is its last twentieth.
        assert_eq!(stopped(20.0, Some(30.0)).resume_at(), 20.0);
        assert_eq!(stopped(28.6, Some(30.0)).resume_at(), 0.0);
    }

    #[test]
    fn where_playback_stopped_is_kept_per_download_and_forgotten_with_it() {
        let dir = tempfile::tempdir().unwrap();
        let videos = Videos::in_folder(dir.path().join("videos"));
        assert_eq!(videos.load(3), VideoData::default());
        videos.stopped(3, 61.0, Some(300.0)).unwrap();
        videos.stopped(4, 10.0, None).unwrap();
        assert_eq!(videos.load(3).resume_at(), 61.0);
        // The length, once known, stays when a later stop does not know it.
        videos.stopped(3, 120.0, None).unwrap();
        assert_eq!(videos.load(3), stopped(120.0, Some(300.0)));
        assert!(dir.path().join("videos").join("3.json").exists());
        assert!(!dir.path().join("videos").join("3.json.part").exists());
        videos.forget(3);
        assert_eq!(videos.load(3), VideoData::default());
        assert_eq!(videos.load(4).resume_at(), 10.0);
    }

    #[test]
    fn what_whisper_heard_is_kept_beside_where_playback_stopped() {
        use crate::speech::whisper::{Segment, Word};
        let dir = tempfile::tempdir().unwrap();
        let videos = Videos::in_folder(dir.path().to_path_buf());
        videos.stopped(2, 30.0, Some(90.0)).unwrap();
        let word = Word { text: "Hello".into(), start: 0, end: 400, p: 0.9 };
        let transcript = Transcript {
            language: "en".into(),
            segments: vec![Segment { start: 0, end: 400, text: "Hello".into(), words: vec![word] }],
        };
        videos.heard(2, transcript.clone(), "tiny", Device::GraphicsCard).unwrap();
        let data = videos.load(2);
        assert_eq!(data.transcript, Some(transcript));
        assert_eq!(data.transcript_model.as_deref(), Some("tiny"));
        assert_eq!(data.transcript_on, Some(Device::GraphicsCard));
        assert_eq!(data.resume_at(), 30.0);
    }

    #[test]
    fn a_poster_is_kept_beside_and_forgotten_with_the_rest() {
        let dir = tempfile::tempdir().unwrap();
        let videos = Videos::in_folder(dir.path().join("videos"));
        let jpeg = [&[0xFF, 0xD8, 0xFF, 0xE0][..], &[7; 100]].concat();
        videos.keep_poster(4, &jpeg).unwrap();
        assert_eq!(std::fs::read(videos.poster(4)).unwrap(), jpeg);
        assert_eq!(videos.poster(4), dir.path().join("videos").join("4.jpg"));
        assert!(videos.keep_poster(5, b"<svg onload=alert(1)>").is_err(), "only a JPEG");
        assert!(videos.keep_poster(5, &[&jpeg[..], &vec![0; LARGEST_POSTER]].concat()).is_err(), "not too big");
        assert!(!videos.poster(5).exists());
        videos.forget(4);
        assert!(!videos.poster(4).exists());
    }

    #[test]
    fn a_translation_is_kept_a_version_for_each_model_and_updated_as_it_goes() {
        let dir = tempfile::tempdir().unwrap();
        let videos = Videos::in_folder(dir.path().to_path_buf());
        let mut first = Translation {
            from: Some("en".into()),
            to: "es".into(),
            model: "Gemini · flash-lite".into(),
            made: 1,
            lines: vec![Some("Hola".into()), None],
            in_file: false,
        };
        videos.translated(7, &first).unwrap();
        assert!(!videos.load(7).translations[0].is_whole());
        first.lines[1] = Some("Adiós".into());
        videos.translated(7, &first).unwrap();
        let other = Translation { model: "OpenAI · gpt-4.1".into(), ..first.clone() };
        videos.translated(7, &other).unwrap();
        let kept = videos.load(7).translations;
        assert_eq!(kept.len(), 2, "one version a model");
        assert!(kept[0].is_whole());
        assert_eq!(kept[1].model, "OpenAI · gpt-4.1");
        // The one in the file: one of each language's at a time, and only a whole one.
        assert_eq!(
            videos.in_file(7, "es", "OpenAI · gpt-4.1").unwrap().map(|t| t.model),
            Some("OpenAI · gpt-4.1".into())
        );
        let marked: Vec<bool> = videos.load(7).translations.iter().map(|t| t.in_file).collect();
        assert_eq!(marked, [false, true]);
        videos.in_file(7, "es", "Gemini · flash-lite").unwrap();
        let marked: Vec<bool> = videos.load(7).translations.iter().map(|t| t.in_file).collect();
        assert_eq!(marked, [true, false]);
        let part = Translation { to: "de".into(), lines: vec![None], ..first.clone() };
        videos.translated(7, &part).unwrap();
        assert_eq!(videos.in_file(7, "de", "Gemini · flash-lite").unwrap(), None, "not one that isn't whole");
    }

    #[test]
    fn highlights_are_kept_in_place_of_those_before() {
        let dir = tempfile::tempdir().unwrap();
        let videos = Videos::in_folder(dir.path().to_path_buf());
        let highlight = Highlight {
            title: "A claim".into(),
            reason: "Numbers.".into(),
            first: 2,
            last: 5,
            start: 10_000,
            end: 29_000,
        };
        let first = Highlights {
            from: Some("en".into()),
            model: "Gemini · flash-lite".into(),
            made: 1,
            items: vec![highlight.clone()],
        };
        videos.highlighted(3, first).unwrap();
        let again = Highlights {
            model: "OpenAI · gpt-4.1".into(),
            items: vec![highlight.clone(), highlight],
            ..videos.load(3).highlights.unwrap()
        };
        videos.highlighted(3, again.clone()).unwrap();
        assert_eq!(videos.load(3).highlights, Some(again));
    }

    #[test]
    fn clips_are_kept_put_right_inside_the_video_and_removed() {
        let dir = tempfile::tempdir().unwrap();
        let videos = Videos::in_folder(dir.path().to_path_buf());
        videos.stopped(8, 30.0, Some(60.0)).unwrap();
        let wanted = [NewClip { name: "The end".into(), start: 55_000, end: 70_000 }];
        // The video's length, as kept, keeps the clip inside it.
        let (clips, ids) = videos.add_clips(8, &wanted, None).unwrap();
        assert_eq!(ids, [Some(1)]);
        assert_eq!((clips[0].start, clips[0].end), (55_000, 60_000));
        let changed = Clip { id: 0, name: "Last words".into(), start: 50_000, end: 58_000 };
        let clips = videos.change_clip(8, 1, Some(&changed)).unwrap();
        assert_eq!(clips, [Clip { id: 1, name: "Last words".into(), start: 50_000, end: 58_000 }]);
        assert_eq!(videos.load(8).clips, clips);
        assert_eq!(videos.load(8).resume_at(), 30.0, "the rest kept");
        assert!(videos.change_clip(8, 1, None).unwrap().is_empty());
    }

    #[test]
    fn changes_made_at_the_same_moment_all_stay() {
        let dir = tempfile::tempdir().unwrap();
        let videos = std::sync::Arc::new(Videos::in_folder(dir.path().to_path_buf()));
        let writers: Vec<_> = (0..4u64)
            .map(|w| {
                let videos = videos.clone();
                std::thread::spawn(move || {
                    for i in 0..25u64 {
                        let wanted = [NewClip {
                            name: String::new(),
                            start: (w * 100 + i) * 1_000,
                            end: (w * 100 + i) * 1_000 + 900,
                        }];
                        videos.add_clips(1, &wanted, None).unwrap();
                    }
                })
            })
            .collect();
        for writer in writers {
            writer.join().unwrap();
        }
        assert_eq!(videos.load(1).clips.len(), 100);
    }

    #[test]
    fn what_a_later_chaya_wrote_is_kept() {
        let dir = tempfile::tempdir().unwrap();
        let videos = Videos::in_folder(dir.path().to_path_buf());
        std::fs::write(dir.path().join("5.json"), r#"{"stopped_at": 9.5, "chapters": [{"from": 1, "to": 2}]}"#)
            .unwrap();
        videos.stopped(5, 30.0, Some(90.0)).unwrap();
        let kept: Value = serde_json::from_slice(&std::fs::read(dir.path().join("5.json")).unwrap()).unwrap();
        assert_eq!(kept["chapters"][0]["to"], 2);
        assert_eq!(kept["stopped_at"], 30.0);
        std::fs::write(dir.path().join("6.json"), "not json").unwrap();
        assert_eq!(videos.load(6), VideoData::default());
    }
}
