//! Clips saved as videos or SRT files (`chaya-core`'s `export`), from the player's Export: one job a clip (a clip and a
//! language, for SRT), done one at a time, the rest waiting their turn. The window hears how each goes as `export`
//! events, and can stop one, try a failed one again, or show the file in its folder. Files are saved next to the
//! video, as "Video title - Clip name.mp4".

use std::collections::{BTreeMap, VecDeque};
use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard, PoisonError};

use chaya_core::download::manager::State as TaskState;
use chaya_core::export::{self, Burn, Encoder, ExportError, Job, Language, Look, Quality, Style};
use chaya_core::subtitles;
use chaya_core::video::Videos;
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_opener::OpenerExt;
use tokio::sync::{watch, Notify};

use crate::clips::Ffmpeg;
use crate::Downloads;

/// How the clips are saved, as the Export dialog chose.
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Options {
    pub save_as: SaveAs,
    pub quality: Quality,
    pub look: Look,
    /// For a video, the subtitles drawn on it: none, one, or two (the second the other language); for SRT, a file
    /// for each.
    #[serde(default)]
    pub subtitles: Vec<Chosen>,
    #[serde(default)]
    pub second_on_top: bool,
    /// A size the video must fit in, in megabytes (10 for Discord, 25 for e-mail), when one was chosen.
    #[serde(default)]
    pub fit: Option<u32>,
    /// The sound's loudness evened.
    #[serde(default)]
    pub even_loudness: bool,
    /// For SRT in two languages: one file with both, each line over the other, in place of a file for each.
    #[serde(default)]
    pub together: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SaveAs {
    Video,
    Gif,
    Srt,
}

/// A language's subtitles, named by its code ("" for a file that names none), and its style.
#[derive(Clone, Debug, Deserialize)]
pub struct Chosen {
    pub language: String,
    #[serde(default)]
    pub style: Style,
}

/// How a job is going, as the window shows it.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportView {
    pub job: u64,
    pub id: u64,
    pub clip: u32,
    /// The clip's name.
    pub name: String,
    pub save_as: SaveAs,
    /// "waiting", "exporting", "done", "failed" or "stopped".
    pub state: &'static str,
    pub percent: u32,
    pub path: Option<String>,
    pub encoder: Option<Encoder>,
    pub message: Option<String>,
}

/// A job: what was asked for, and how it is going.
struct Entry {
    options: Options,
    view: ExportView,
}

#[derive(Default)]
struct Jobs {
    next: u64,
    waiting: VecDeque<u64>,
    running: Option<(u64, watch::Sender<bool>)>,
    all: BTreeMap<u64, Entry>,
    /// The encoders the engine's ffmpeg has, asked once.
    encoders: Option<Vec<Encoder>>,
}

#[derive(Default)]
pub struct Exporting {
    jobs: Mutex<Jobs>,
    wake: Notify,
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

impl Exporting {
    /// Changes job `job`'s view and tells the window.
    fn show(&self, app: &AppHandle, job: u64, change: impl FnOnce(&mut ExportView)) {
        let view = {
            let mut jobs = lock(&self.jobs);
            let Some(entry) = jobs.all.get_mut(&job) else { return };
            change(&mut entry.view);
            entry.view.clone()
        };
        let _ = app.emit("export", view);
    }

    /// Queues a job for clip `clip` (named `name`) of download `id`; its number.
    fn queue(&self, app: &AppHandle, id: u64, clip: u32, name: &str, options: Options) -> u64 {
        let view = {
            let mut jobs = lock(&self.jobs);
            jobs.next += 1;
            let job = jobs.next;
            let view = ExportView {
                job,
                id,
                clip,
                name: name.to_string(),
                save_as: options.save_as,
                state: "waiting",
                percent: 0,
                path: None,
                encoder: None,
                message: None,
            };
            jobs.all.insert(job, Entry { options, view: view.clone() });
            jobs.waiting.push_back(job);
            view
        };
        let _ = app.emit("export", view.clone());
        self.wake.notify_one();
        view.job
    }

    /// Exports what was asked for, one job at a time, for as long as the app runs.
    pub async fn work(app: AppHandle) {
        loop {
            let exporting = app.state::<Exporting>();
            let next = lock(&exporting.jobs).waiting.pop_front();
            let Some(job) = next else {
                exporting.wake.notified().await;
                continue;
            };
            let (tx, stop) = watch::channel(false);
            lock(&exporting.jobs).running = Some((job, tx));
            exporting.show(&app, job, |v| v.state = "exporting");
            let result = exporting.export(&app, job, &stop).await;
            lock(&exporting.jobs).running = None;
            match result {
                Ok((path, encoder)) => exporting.show(&app, job, |v| {
                    v.state = "done";
                    v.percent = 100;
                    v.path = Some(path.to_string_lossy().into_owned());
                    v.encoder = encoder;
                }),
                Err(ExportError::Stopped) => exporting.show(&app, job, |v| v.state = "stopped"),
                Err(error) => exporting.show(&app, job, |v| {
                    v.state = "failed";
                    v.message = Some(error.message());
                }),
            }
        }
    }

    /// Job `job`: its file, and for a video the encoder that made it.
    async fn export(
        &self,
        app: &AppHandle,
        job: u64,
        stop: &watch::Receiver<bool>,
    ) -> Result<(PathBuf, Option<Encoder>), ExportError> {
        let (id, clip, options) = {
            let jobs = lock(&self.jobs);
            let entry = jobs.all.get(&job).ok_or(ExportError::Stopped)?;
            (entry.view.id, entry.view.clip, entry.options.clone())
        };
        let failed = |message: &str| ExportError::Failed(message.to_string());
        let task = app.state::<Downloads>().manager.get(id).filter(|t| t.state == TaskState::Completed);
        let task = task.ok_or_else(|| failed("This video isn't saved any more."))?;
        let clip = app.state::<Videos>().load(id).clips.into_iter().find(|c| c.id == clip);
        let clip = clip.ok_or_else(|| failed("This clip isn't there any more."))?;
        let folder = task.path.parent().map(PathBuf::from).ok_or(ExportError::CantWrite)?;
        let tracks = subtitles::tracks_for(&task.path);
        let track = |chosen: &Chosen| {
            let language = Some(chosen.language.clone()).filter(|l| !l.is_empty());
            tracks
                .iter()
                .find(|t| t.language == language)
                .ok_or_else(|| failed("Those subtitles aren't next to the video any more."))
        };

        if options.save_as == SaveAs::Srt && options.together && options.subtitles.len() == 2 {
            let (one, two) = (track(&options.subtitles[0])?, track(&options.subtitles[1])?);
            let both = format!(
                "{} ({} + {})",
                clip.name,
                language_or(&options.subtitles[0]),
                language_or(&options.subtitles[1])
            );
            let path = export::file_for(&folder, &task.title, &both, "srt");
            let text = export::srt_together(&one.lines, &two.lines, options.second_on_top, clip.start, clip.end);
            std::fs::write(&path, text).map_err(|_| ExportError::CantWrite)?;
            return Ok((path, None));
        }
        if options.save_as == SaveAs::Srt {
            let chosen = options.subtitles.first().ok_or_else(|| failed("Choose the subtitles to save."))?;
            let lines = &track(chosen)?.lines;
            let extension =
                if chosen.language.is_empty() { "srt".to_string() } else { format!("{}.srt", chosen.language) };
            let path = export::file_for(&folder, &task.title, &clip.name, &extension);
            std::fs::write(&path, export::srt(lines, clip.start, clip.end)).map_err(|_| ExportError::CantWrite)?;
            return Ok((path, None));
        }

        let ffmpeg = app.state::<Ffmpeg>().0.clone().ok_or(ExportError::NoProgram)?;
        let known = lock(&self.jobs).encoders.clone();
        let encoders = match known {
            Some(encoders) => encoders,
            None => {
                let found = export::encoders(&ffmpeg).await;
                lock(&self.jobs).encoders = Some(found.clone());
                found
            }
        };
        let first = options.subtitles.first().map(track).transpose()?;
        let second = options.subtitles.get(1).map(track).transpose()?;
        let burn = first.map(|first| Burn {
            look: options.look,
            first: Language { lines: &first.lines, style: options.subtitles[0].style },
            second: second.map(|second| Language { lines: &second.lines, style: options.subtitles[1].style }),
            second_on_top: options.second_on_top,
        });
        let gif = options.save_as == SaveAs::Gif;
        let out = export::file_for(&folder, &task.title, &clip.name, if gif { "gif" } else { "mp4" });
        let work = app.path().app_cache_dir().map_err(|_| ExportError::CantWrite)?.join("export").join(job.to_string());
        let job_of = Job {
            video: &task.path,
            start: clip.start,
            end: clip.end,
            quality: options.quality,
            burn,
            // Megabytes as a size limit is said: a million bytes each, under what a site means by it either way.
            fit: options.fit.filter(|_| !gif).map(|mb| u64::from(mb.clamp(1, 4_000)) * 1_000_000),
            even_loudness: options.even_loudness,
            gif,
            out: &out,
            work: &work,
        };
        let result = export::export(&ffmpeg, &job_of, &encoders, None, stop, &mut |percent| {
            self.show(app, job, |v| v.percent = percent);
        })
        .await;
        let _ = std::fs::remove_dir_all(&work);
        let encoder = result?;
        // An encoder that failed on this PC fails again: the next clips start with the one that worked.
        let mut jobs = lock(&self.jobs);
        if let Some(encoders) = jobs.encoders.as_mut() {
            encoders.retain(|e| e == &encoder || !preceding(encoders_order(), e, &encoder));
        }
        Ok((out, Some(encoder)))
    }
}

/// A language's code for a file's name ("en"), or "subtitles" for a file that names none.
fn language_or(chosen: &Chosen) -> &str {
    if chosen.language.is_empty() {
        "subtitles"
    } else {
        &chosen.language
    }
}

/// The order encoders are tried in.
fn encoders_order() -> &'static [Encoder] {
    &[Encoder::GraphicsCard, Encoder::Windows, Encoder::Mpeg4]
}

/// Whether `a` comes before `b` in `order`.
fn preceding(order: &[Encoder], a: &Encoder, b: &Encoder) -> bool {
    let at = |e: &Encoder| order.iter().position(|o| o == e);
    matches!((at(a), at(b)), (Some(a), Some(b)) if a < b)
}

/// Exports clips `clips` of download `id` as `options` say; the jobs' numbers, one a clip (a clip and a language for
/// SRT).
#[tauri::command]
pub fn export_clips(
    id: u64,
    clips: Vec<u32>,
    options: Options,
    app: AppHandle,
    videos: State<'_, Videos>,
    exporting: State<'_, Exporting>,
) -> Result<Vec<u64>, String> {
    if options.save_as == SaveAs::Srt && options.subtitles.is_empty() {
        return Err("Choose the subtitles to save.".into());
    }
    if options.subtitles.len() > 2 && options.save_as != SaveAs::Srt {
        return Err("A video shows two languages at most.".into());
    }
    let kept = videos.load(id).clips;
    let mut jobs = Vec::new();
    for clip in clips {
        let Some(found) = kept.iter().find(|c| c.id == clip) else { continue };
        match options.save_as {
            SaveAs::Video | SaveAs::Gif => jobs.push(exporting.queue(&app, id, clip, &found.name, options.clone())),
            SaveAs::Srt if options.together && options.subtitles.len() == 2 => {
                jobs.push(exporting.queue(&app, id, clip, &found.name, options.clone()))
            }
            SaveAs::Srt => {
                for chosen in &options.subtitles {
                    let one = Options { subtitles: vec![chosen.clone()], ..options.clone() };
                    jobs.push(exporting.queue(&app, id, clip, &found.name, one));
                }
            }
        }
    }
    if jobs.is_empty() {
        return Err("Those clips aren't there any more.".into());
    }
    Ok(jobs)
}

/// Stops job `job`, or takes it out of the queue.
#[tauri::command]
pub fn stop_export(job: u64, app: AppHandle, exporting: State<'_, Exporting>) {
    let mut jobs = lock(&exporting.jobs);
    if let Some((_, stop)) = jobs.running.as_ref().filter(|(running, _)| *running == job) {
        let _ = stop.send(true);
        return;
    }
    let was_waiting = jobs.waiting.contains(&job);
    jobs.waiting.retain(|j| *j != job);
    drop(jobs);
    if was_waiting {
        exporting.show(&app, job, |v| v.state = "stopped");
    }
}

/// Job `job` again, as a new job; its number.
#[tauri::command]
pub fn retry_export(job: u64, app: AppHandle, exporting: State<'_, Exporting>) -> Result<u64, String> {
    let (id, clip, name, options) = {
        let jobs = lock(&exporting.jobs);
        let entry = jobs.all.get(&job).ok_or("That export is gone.")?;
        (entry.view.id, entry.view.clip, entry.view.name.clone(), entry.options.clone())
    };
    Ok(exporting.queue(&app, id, clip, &name, options))
}

/// Shows job `job`'s file in its folder.
#[tauri::command]
pub fn show_export(job: u64, app: AppHandle, exporting: State<'_, Exporting>) -> Result<(), String> {
    let path =
        lock(&exporting.jobs).all.get(&job).and_then(|e| e.view.path.clone()).ok_or("That file isn't saved yet.")?;
    app.opener().reveal_item_in_dir(&path).map_err(|_| "Windows couldn't show the folder.".into())
}

/// Every job of this run, in order, for a window opened again.
#[tauri::command]
pub fn exports_status(exporting: State<'_, Exporting>) -> Vec<ExportView> {
    lock(&exporting.jobs).all.values().map(|e| e.view.clone()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn options_are_read_as_the_window_sends_them() {
        let options: Options = serde_json::from_str(
            r#"{"saveAs": "video", "quality": "p1080", "look": "boxed", "secondOnTop": true,
                "subtitles": [{"language": "en", "style": {"color": "yellow", "font": "serif", "size": "XL"}},
                              {"language": ""}]}"#,
        )
        .unwrap();
        assert_eq!((options.save_as, options.quality, options.look), (SaveAs::Video, Quality::P1080, Look::Boxed));
        assert!(options.second_on_top);
        assert_eq!(options.subtitles[0].style.size, export::Size::Xl);
        assert_eq!(options.subtitles[1].style, Style::default());
        assert!(serde_json::from_str::<Options>(r#"{"saveAs": "webm", "quality": "p720", "look": "outline"}"#).is_err());
        let gif: Options = serde_json::from_str(
            r#"{"saveAs": "gif", "quality": "p720", "look": "outline", "fit": 10, "evenLoudness": true, "together": true}"#,
        )
        .unwrap();
        assert_eq!((gif.save_as, gif.fit, gif.even_loudness, gif.together), (SaveAs::Gif, Some(10), true, true));
        assert_eq!(options.fit, None, "no size to fit unless chosen");
    }

    #[test]
    fn an_encoder_that_worked_puts_those_before_it_out_of_the_way() {
        let mut encoders = vec![Encoder::GraphicsCard, Encoder::Windows, Encoder::Mpeg4];
        let worked = Encoder::Windows;
        encoders.retain(|e| e == &worked || !preceding(encoders_order(), e, &worked));
        assert_eq!(encoders, [Encoder::Windows, Encoder::Mpeg4]);
    }
}
