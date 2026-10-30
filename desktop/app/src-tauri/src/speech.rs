//! Subtitles made on the PC, for the window (`chaya-core`'s `speech`): *Make subtitles* on a saved video, or every
//! video downloaded when the person turns that on. One video at a time, the rest waiting their turn; the window hears
//! how each goes as `speech` events, and finds the subtitles next to the video when they are made.

use std::collections::{HashMap, VecDeque};
use std::path::Path;
use std::sync::{Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

use chaya_core::download::join::Joiner;
use chaya_core::download::manager::{Kind, State as TaskState, Task};
use chaya_core::reqwest::Client;
use chaya_core::speech::models::{self, Model};
use chaya_core::speech::whisper::Whisper;
use chaya_core::speech::{Settings, Speech, SpeechError, Step, Tools};
use chaya_core::subtitles;
use chaya_core::video::Videos;
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::{watch, Notify};

use crate::Downloads;

/// How often a model's fetch tells the window how far it has got.
const PROGRESS_EVERY: Duration = Duration::from_millis(250);

pub struct Speaking {
    speech: Speech,
    /// ffmpeg and whisper.cpp, when the engine carries both; without them, subtitles are not offered.
    tools: Option<Tools>,
    client: Client,
    jobs: Mutex<Jobs>,
    wake: Notify,
}

#[derive(Default)]
struct Jobs {
    waiting: VecDeque<u64>,
    /// The video being listened to, and what stops it.
    running: Option<(u64, watch::Sender<bool>)>,
    /// How each video asked for has got on, for the window.
    views: HashMap<u64, JobView>,
}

/// How making a video's subtitles is going, as the window shows it.
#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JobView {
    pub id: u64,
    /// "waiting", "model" (fetching it), "sound", "listening", "done" or "failed".
    pub state: &'static str,
    /// For "model": bytes of the model fetched, of `total`.
    pub done: u64,
    pub total: u64,
    /// For "listening": how far, and (with "done") whether on the graphics card.
    pub percent: u8,
    /// For "failed": why, in words.
    pub message: Option<String>,
    /// For "done": the language of the subtitles made ("en"), whether they were heard on the graphics card, and how
    /// many seconds listening took (with the sound, not the model's fetch), for the person to compare.
    pub language: Option<String>,
    pub card: bool,
    pub seconds: Option<u64>,
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

impl Speaking {
    /// Chaya's speech folder, with the ffmpeg and whisper.cpp of `engine_dir`.
    pub fn new(folder: std::path::PathBuf, engine_dir: &Path, client: Client) -> Speaking {
        let tools = match (Joiner::find(engine_dir), Whisper::find(engine_dir)) {
            (Some(joiner), Some(whisper)) => Some(Tools { ffmpeg: joiner.ffmpeg, whisper }),
            _ => None,
        };
        Speaking { speech: Speech::in_folder(folder), tools, client, jobs: Mutex::default(), wake: Notify::new() }
    }

    /// Asks for download `id`'s subtitles to be made, after any already asked for.
    fn ask(&self, app: &AppHandle, id: u64) {
        {
            let mut jobs = lock(&self.jobs);
            let busy = jobs.waiting.contains(&id) || jobs.running.as_ref().is_some_and(|(r, _)| *r == id);
            if busy || self.tools.is_none() {
                return;
            }
            jobs.waiting.push_back(id);
        }
        self.show(app, JobView { id, state: "waiting", ..JobView::default() });
        self.wake.notify_one();
    }

    /// A video downloaded: its subtitles are made when the person asked for every download's, and it came without any.
    pub fn downloaded(&self, app: &AppHandle, task: &Task) {
        let wanted = task.kind == Kind::Video
            && task.state == TaskState::Completed
            && self.speech.settings().every_download
            && subtitles::files_for(&task.path).is_empty();
        if wanted {
            self.ask(app, task.id);
        }
    }

    fn show(&self, app: &AppHandle, view: JobView) {
        lock(&self.jobs).views.insert(view.id, view.clone());
        let _ = app.emit("speech", view);
    }

    /// Makes the subtitles asked for, one video at a time, for as long as the app runs.
    pub async fn work(app: AppHandle) {
        loop {
            let speaking = app.state::<Speaking>();
            let next = lock(&speaking.jobs).waiting.pop_front();
            let Some(id) = next else {
                speaking.wake.notified().await;
                continue;
            };
            let (tx, stop) = watch::channel(false);
            lock(&speaking.jobs).running = Some((id, tx));
            let result = speaking.make(&app, id, &stop).await;
            lock(&speaking.jobs).running = None;
            match result {
                Ok(done) => speaking.show(&app, JobView { id, state: "done", ..done }),
                Err(SpeechError::Stopped) => {
                    lock(&speaking.jobs).views.remove(&id);
                    let _ = app.emit("speech", JobView { id, state: "stopped", ..JobView::default() });
                }
                Err(error) => speaking
                    .show(&app, JobView { id, state: "failed", message: Some(error.message()), ..JobView::default() }),
            }
        }
    }

    /// Makes download `id`'s subtitles; the language they are in, where they were heard, and how long that took.
    async fn make(&self, app: &AppHandle, id: u64, stop: &watch::Receiver<bool>) -> Result<JobView, SpeechError> {
        let tools = self.tools.as_ref().ok_or(SpeechError::NoProgram)?;
        let task = app.state::<Downloads>().manager.get(id).filter(|t| t.state == TaskState::Completed);
        let task = task.ok_or(SpeechError::NoSound)?;
        let settings = self.speech.settings();
        let model = settings.model();
        let mut told = Instant::now() - PROGRESS_EVERY;
        let mut began = None;
        let made = self
            .speech
            .make(id, &task.path, &settings, tools, &self.client, stop, &mut |step| {
                if !matches!(step, Step::Model { .. }) {
                    began.get_or_insert_with(Instant::now);
                }
                let view = match step {
                    Step::Model { done, total } => {
                        if told.elapsed() < PROGRESS_EVERY && done < total {
                            return;
                        }
                        told = Instant::now();
                        JobView { id, state: "model", done, total, ..JobView::default() }
                    }
                    Step::Sound => JobView { id, state: "sound", ..JobView::default() },
                    Step::Listening { percent, card } => {
                        JobView { id, state: "listening", percent, card, ..JobView::default() }
                    }
                };
                self.show(app, view);
            })
            .await?;
        let language = made.path.file_stem().and_then(|s| s.to_str()).and_then(|s| s.rsplit_once('.')).map(|(_, l)| l);
        let language = language.map(str::to_string);
        let seconds = began.map(|began| began.elapsed().as_secs());
        let _ = app.state::<Videos>().heard(id, made.transcript, &model.id);
        Ok(JobView { id, language, card: made.card, seconds, ..JobView::default() })
    }
}

/// A speech model as Settings › Speech recognition lists it.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelView {
    id: String,
    name: String,
    size: u64,
    note: String,
    default: bool,
    /// Here, whole.
    fetched: bool,
}

/// Settings › Speech recognition: whether subtitles can be made here at all, and on a graphics card (the Vulkan build,
/// with Windows' Vulkan), what the person chose, the models, and how each video asked for is getting on.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SpeechStatus {
    available: bool,
    card: bool,
    settings: Settings,
    models: Vec<ModelView>,
    jobs: Vec<JobView>,
}

#[tauri::command]
pub fn speech_status(speaking: State<'_, Speaking>) -> SpeechStatus {
    let models = models::all()
        .iter()
        .map(|m: &Model| ModelView {
            id: m.id.clone(),
            name: m.name.clone(),
            size: m.size,
            note: m.note.clone(),
            default: m.default,
            fetched: speaking.speech.models.is_fetched(m),
        })
        .collect();
    SpeechStatus {
        available: speaking.tools.is_some(),
        card: speaking.tools.as_ref().is_some_and(|tools| tools.whisper.on_card().is_some()),
        settings: speaking.speech.settings(),
        models,
        jobs: lock(&speaking.jobs).views.values().cloned().collect(),
    }
}

#[tauri::command]
pub fn set_speech(settings: Settings, speaking: State<'_, Speaking>) -> Result<(), String> {
    let settings = Settings { model: settings.model().id.clone(), ..settings };
    speaking.speech.save_settings(&settings).map_err(|_| "Chaya couldn't save this setting.".to_string())
}

/// Deletes a speech model from the PC; it is fetched again when next needed. Not while subtitles are being made.
#[tauri::command]
pub fn delete_speech_model(id: String, speaking: State<'_, Speaking>) -> Result<(), String> {
    let model = models::find(&id).ok_or("There is no such model.")?;
    if lock(&speaking.jobs).running.is_some() {
        return Err("Subtitles are being made. Delete the model once they are done.".into());
    }
    speaking.speech.models.delete(model);
    Ok(())
}

#[tauri::command]
pub fn make_subtitles(id: u64, app: AppHandle, speaking: State<'_, Speaking>) {
    speaking.ask(&app, id);
}

/// Stops making download `id`'s subtitles, or takes it out of the queue. A model's fetch keeps what arrived.
#[tauri::command]
pub fn stop_subtitles(id: u64, app: AppHandle, speaking: State<'_, Speaking>) {
    let mut jobs = lock(&speaking.jobs);
    if let Some((_, stop)) = jobs.running.as_ref().filter(|(running, _)| *running == id) {
        let _ = stop.send(true);
        return;
    }
    jobs.waiting.retain(|waiting| *waiting != id);
    jobs.views.remove(&id);
    drop(jobs);
    let _ = app.emit("speech", JobView { id, state: "stopped", ..JobView::default() });
}
