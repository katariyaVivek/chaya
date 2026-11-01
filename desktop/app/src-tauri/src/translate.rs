//! A saved video's subtitles translated by the person's AI model (`chaya-core`'s `translate`), from the player's
//! *Translate*: one video at a time, the rest waiting their turn. The window hears how each goes as `translate`
//! events. What is translated is kept in the video's file (`videos/ID.json`) after every batch, so a translation
//! stopped or failed part-way carries on from there; a whole one is saved next to the video as "name.es.srt".

use std::collections::{HashMap, VecDeque};
use std::path::Path;
use std::sync::{Mutex, MutexGuard, PoisonError};
use std::time::{SystemTime, UNIX_EPOCH};

use chaya_core::ai::{Service, Settings};
use chaya_core::download::manager::State as TaskState;
use chaya_core::subtitles::{self, Line};
use chaya_core::translate::{self, Job, TranslateError};
use chaya_core::video::{Translation, Videos};
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::{watch, Notify};

use crate::ai::{self as person_ai, AiPlace};
use crate::Downloads;

/// A translation asked for: of download `id`'s subtitles in `from`, into `to` ("es"), whose name is `into`
/// ("Spanish", for the model).
#[derive(Clone, Debug, PartialEq)]
struct Wanted {
    id: u64,
    from: Option<String>,
    to: String,
    into: String,
}

#[derive(Default)]
pub struct Translating {
    jobs: Mutex<Jobs>,
    wake: Notify,
}

#[derive(Default)]
struct Jobs {
    waiting: VecDeque<Wanted>,
    running: Option<(u64, watch::Sender<bool>)>,
    views: HashMap<u64, TranslateView>,
}

/// How translating a video is going, as the window shows it.
#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TranslateView {
    pub id: u64,
    /// "waiting", "translating", "done", "failed" or "stopped".
    pub state: &'static str,
    /// Lines translated, of `total`.
    pub done: usize,
    pub total: usize,
    /// The language translated into ("es").
    pub language: String,
    /// The model translating ("Gemini · gemini-3.5-flash-lite").
    pub model: String,
    pub message: Option<String>,
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

impl Translating {
    fn show(&self, app: &AppHandle, view: TranslateView) {
        lock(&self.jobs).views.insert(view.id, view.clone());
        let _ = app.emit("translate", view);
    }

    /// Translates what was asked for, one video at a time, for as long as the app runs.
    pub async fn work(app: AppHandle) {
        loop {
            let translating = app.state::<Translating>();
            let next = lock(&translating.jobs).waiting.pop_front();
            let Some(wanted) = next else {
                translating.wake.notified().await;
                continue;
            };
            let (tx, stop) = watch::channel(false);
            lock(&translating.jobs).running = Some((wanted.id, tx));
            let base = TranslateView { id: wanted.id, language: wanted.to.clone(), ..TranslateView::default() };
            let result = translating.translate(&app, &wanted, &stop).await;
            lock(&translating.jobs).running = None;
            match result {
                Ok(view) => translating.show(&app, TranslateView { state: "done", ..view }),
                Err((TranslateError::Stopped, _)) => {
                    lock(&translating.jobs).views.remove(&wanted.id);
                    let _ = app.emit("translate", TranslateView { state: "stopped", ..base });
                }
                Err((_, message)) => {
                    translating.show(&app, TranslateView { state: "failed", message: Some(message), ..base })
                }
            }
        }
    }

    /// Translates `wanted`, carrying on from a translation by the same model left part-way.
    async fn translate(
        &self,
        app: &AppHandle,
        wanted: &Wanted,
        stop: &watch::Receiver<bool>,
    ) -> Result<TranslateView, (TranslateError, String)> {
        let failed = |message: &str| (TranslateError::Incomplete(0), message.to_string());
        let settings = Settings::load(&app.state::<AiPlace>().0);
        let service = settings.service().ok_or_else(|| failed("Set up your AI model in Settings › AI model first."))?;
        let model = settings.model.clone().filter(|m| !m.trim().is_empty());
        let model = model.ok_or_else(|| failed("Choose a model in Settings › AI model first."))?;
        let endpoint = person_ai::endpoint(&settings).map_err(|message| failed(&message))?;
        let task = app.state::<Downloads>().manager.get(wanted.id).filter(|t| t.state == TaskState::Completed);
        let task = task.ok_or_else(|| failed("This video isn't saved any more."))?;
        let source = subtitles::tracks_for(&task.path).into_iter().find(|t| t.language == wanted.from);
        let source = source.ok_or_else(|| failed("These subtitles aren't next to the video any more."))?;
        let texts: Vec<String> = source.lines.iter().map(|l| l.text.clone()).collect();

        let named = if service.id == "custom" { "The service" } else { service.name };
        let label = label(service, &model);
        let videos = app.state::<Videos>();
        let same = |t: &&Translation| t.from == wanted.from && t.to == wanted.to && t.model == label;
        let kept = videos.load(wanted.id).translations.iter().find(same).cloned();
        let made = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs());
        let mut translation = kept.filter(|t| t.lines.len() == texts.len()).unwrap_or(Translation {
            from: wanted.from.clone(),
            to: wanted.to.clone(),
            model: label.clone(),
            made,
            lines: Vec::new(),
            in_file: false,
        });
        let view = |done: &[Option<String>]| TranslateView {
            id: wanted.id,
            state: "translating",
            done: done.iter().flatten().count(),
            total: texts.len(),
            language: wanted.to.clone(),
            model: label.clone(),
            message: None,
        };
        self.show(app, view(&translation.lines));

        let job = Job { title: &task.title, lines: &texts, into: &wanted.into, model: model.trim() };
        let mut lines = std::mem::take(&mut translation.lines);
        let result = translate::translate(&endpoint, &job, &mut lines, stop, &mut |done| {
            let _ = videos.translated(wanted.id, &Translation { lines: done.to_vec(), ..translation.clone() });
            self.show(app, view(done));
        })
        .await;
        translation.lines = lines;
        let _ = videos.translated(wanted.id, &translation);
        result.map_err(|error| {
            let message = error.message(named);
            (error, message)
        })?;

        save(&videos, wanted.id, &task.path, &source.lines, &translation).map_err(|message| failed(&message))?;
        Ok(view(&translation.lines))
    }
}

/// Saves whole `translation` next to the video at `video` as "name.es.srt", timed as the original's `lines`, and
/// marks it as the version there.
fn save(videos: &Videos, id: u64, video: &Path, lines: &[Line], translation: &Translation) -> Result<(), String> {
    if lines.len() != translation.lines.len() || !translation.is_whole() {
        return Err("The original subtitles have changed since. Translate them again.".into());
    }
    let translated: Vec<Line> = lines
        .iter()
        .zip(&translation.lines)
        .map(|(line, text)| Line { text: text.clone().unwrap_or_default(), ..line.clone() })
        .collect();
    std::fs::write(subtitles::file_for(video, &translation.to), subtitles::to_srt(&translated))
        .map_err(|_| "Chaya couldn't write the translation next to the video.".to_string())?;
    let _ = videos.in_file(id, &translation.to, &translation.model);
    Ok(())
}

/// A version of a video's translation, as the player lists them.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionView {
    to: String,
    from: Option<String>,
    model: String,
    made: u64,
    whole: bool,
    in_file: bool,
}

/// Every translation of download `id`'s subtitles, a version for each language and model.
#[tauri::command]
pub fn video_translations(id: u64, videos: State<'_, Videos>) -> Vec<VersionView> {
    let data = videos.load(id);
    data.translations
        .iter()
        .map(|t| VersionView {
            to: t.to.clone(),
            from: t.from.clone(),
            model: t.model.clone(),
            made: t.made,
            whole: t.is_whole(),
            in_file: t.in_file,
        })
        .collect()
}

/// Saves download `id`'s translation into `to` by `model` next to the video, in place of the version there.
#[tauri::command]
pub fn use_translation(
    id: u64,
    to: String,
    model: String,
    videos: State<'_, Videos>,
    downloads: State<'_, Downloads>,
) -> Result<(), String> {
    let task = downloads
        .manager
        .get(id)
        .filter(|t| t.state == TaskState::Completed)
        .ok_or("This video isn't saved any more.")?;
    let data = videos.load(id);
    let translation = data
        .translations
        .iter()
        .find(|t| t.to == to && t.model == model && t.is_whole())
        .ok_or("That version isn't whole. Translate again with that model.")?;
    let source = subtitles::tracks_for(&task.path).into_iter().find(|t| t.language == translation.from);
    let source = source.ok_or("The original subtitles aren't next to the video any more.")?;
    save(&videos, id, &task.path, &source.lines, translation)
}

/// Translates download `id`'s subtitles in `from` into `to` ("es", named `into`, "Spanish"), after any asked for
/// before.
#[tauri::command]
pub fn translate_video(
    id: u64,
    from: Option<String>,
    to: String,
    into: String,
    app: AppHandle,
    translating: State<'_, Translating>,
) -> Result<(), String> {
    let to = subtitles::file_language(&to).ok_or("Chaya doesn't know that language.")?;
    if from.as_deref() == Some(to.as_str()) {
        return Err("These subtitles are already in that language.".into());
    }
    let into = into.trim().chars().take(60).collect::<String>();
    {
        let mut jobs = lock(&translating.jobs);
        let busy = jobs.waiting.iter().any(|w| w.id == id) || jobs.running.as_ref().is_some_and(|(r, _)| *r == id);
        if busy {
            return Ok(());
        }
        jobs.waiting.push_back(Wanted { id, from, to: to.clone(), into });
    }
    translating.show(&app, TranslateView { id, state: "waiting", language: to, ..TranslateView::default() });
    translating.wake.notify_one();
    Ok(())
}

/// Stops translating download `id`, or takes it out of the queue; what is translated is kept.
#[tauri::command]
pub fn stop_translation(id: u64, app: AppHandle, translating: State<'_, Translating>) {
    let mut jobs = lock(&translating.jobs);
    if let Some((_, stop)) = jobs.running.as_ref().filter(|(running, _)| *running == id) {
        let _ = stop.send(true);
        return;
    }
    jobs.waiting.retain(|w| w.id != id);
    jobs.views.remove(&id);
    drop(jobs);
    let _ = app.emit("translate", TranslateView { id, state: "stopped", ..TranslateView::default() });
}

/// How each video asked for is getting on, for a window opened again.
#[tauri::command]
pub fn translations_status(translating: State<'_, Translating>) -> Vec<TranslateView> {
    lock(&translating.jobs).views.values().cloned().collect()
}

/// What the window says of the person's model beside *Translate*: its name, or None when it is not set up.
#[tauri::command]
pub fn translator(place: State<'_, AiPlace>) -> Option<String> {
    let settings = Settings::load(&place.0);
    let service = settings.service()?;
    let model = settings.model.filter(|m| !m.trim().is_empty())?;
    Some(label(service, &model))
}

/// A model as a translation is labelled: "Gemini · gemini-3.5-flash-lite".
pub(crate) fn label(service: &Service, model: &str) -> String {
    format!("{} · {}", if service.id == "custom" { "Your service" } else { service.name }, model.trim())
}
