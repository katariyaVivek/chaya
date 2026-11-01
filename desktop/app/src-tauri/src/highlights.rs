//! A saved video's highlights, found by the person's AI model (`chaya-core`'s `highlights`) from the player's
//! Highlights tab, in the subtitles shown; kept in the video's file (`videos/ID.json`) for the next time it opens.

use std::collections::HashMap;
use std::sync::{Mutex, PoisonError};
use std::time::{SystemTime, UNIX_EPOCH};

use chaya_core::ai::{AiError, Settings};
use chaya_core::download::manager::State as TaskState;
use chaya_core::highlights;
use chaya_core::subtitles;
use chaya_core::video::{Highlights, Videos};
use tauri::{AppHandle, Manager, State};
use tokio::sync::watch;

use crate::ai::{self as person_ai, AiPlace};
use crate::translate::label;
use crate::Downloads;

/// The searches running, each with what stops it.
#[derive(Default)]
pub struct Finding(Mutex<HashMap<u64, watch::Sender<bool>>>);

/// Finds about `count` highlights in download `id`'s subtitles in `from` (named `language`, "French", which their
/// titles are written in), keeps them, and gives them back.
#[tauri::command]
pub async fn find_highlights(
    id: u64,
    from: Option<String>,
    language: String,
    count: usize,
    app: AppHandle,
) -> Result<Highlights, String> {
    let settings = Settings::load(&app.state::<AiPlace>().0);
    let service = settings.service().ok_or("Set up your AI model in Settings › AI model first.")?;
    let model = settings.model.clone().filter(|m| !m.trim().is_empty());
    let model = model.ok_or("Choose a model in Settings › AI model first.")?;
    let endpoint = person_ai::endpoint(&settings)?;
    let task = app.state::<Downloads>().manager.get(id).filter(|t| t.state == TaskState::Completed);
    let task = task.ok_or("This video isn't saved any more.")?;
    let source = subtitles::tracks_for(&task.path).into_iter().find(|t| t.language == from);
    let source = source.ok_or("These subtitles aren't next to the video any more.")?;

    let (tx, stop) = watch::channel(false);
    app.state::<Finding>().0.lock().unwrap_or_else(PoisonError::into_inner).insert(id, tx);
    let language = language.trim().chars().take(60).collect::<String>();
    let video = highlights::Video { title: &task.title, lines: &source.lines, language: &language };
    let found = highlights::find(&endpoint, model.trim(), &video, count, &stop).await;
    app.state::<Finding>().0.lock().unwrap_or_else(PoisonError::into_inner).remove(&id);
    let named = if service.id == "custom" { "The service" } else { service.name };
    let items = found.map_err(|error| error.message(named))?;
    if items.is_empty() {
        return Err(AiError::NotJson.message(named));
    }
    let made = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs());
    let kept = Highlights { from, model: label(service, &model), made, items };
    let _ = app.state::<Videos>().highlighted(id, kept.clone());
    Ok(kept)
}

/// Stops finding download `id`'s highlights.
#[tauri::command]
pub fn stop_highlights(id: u64, finding: State<'_, Finding>) {
    if let Some(stop) = finding.0.lock().unwrap_or_else(PoisonError::into_inner).get(&id) {
        let _ = stop.send(true);
    }
}

/// The highlights found for download `id` before, if any.
#[tauri::command]
pub fn video_highlights(id: u64, videos: State<'_, Videos>) -> Option<Highlights> {
    videos.load(id).highlights
}
