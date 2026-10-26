//! Chaya for Windows: the Tauri app. The window is web content built from `desktop/design`; what
//! the app knows lives in `chaya-core`, and this crate only hands it to the window.

use std::sync::{Arc, Mutex, PoisonError};
use std::time::Instant;

use chaya_core::engine::{AnswerCache, Engine};
use chaya_core::formats::is_h264;
use chaya_core::links::{self, Link};
use chaya_core::lookup::Lookup;
use chaya_core::media::FailureKind;
use tauri::Manager;

/// What the address bar holds: a video or post, an account, another page, or words.
#[tauri::command]
fn recognise_link(text: String) -> Link {
    links::recognise(&text)
}

struct Lookups {
    engine: Engine,
    /// One lookup at a time: each is a Python process, and a second would only compete with the first.
    turn: Arc<Mutex<()>>,
    answers: Arc<Mutex<AnswerCache>>,
}

/// Looks up a link to a video or post on YouTube, Instagram, TikTok or X with the bundled engine. Only such
/// links are looked up: anything else never reaches yt-dlp.
#[tauri::command]
async fn look_up(url: String, state: tauri::State<'_, Lookups>) -> Result<Lookup, ()> {
    let Some(link) = links::match_media(&url) else {
        return Ok(Lookup::failed(FailureKind::Unsupported));
    };
    let (engine, turn, answers) = (state.engine.clone(), state.turn.clone(), state.answers.clone());
    // The engine blocks while it runs, so it gets a thread of its own and the window stays responsive.
    let found = tauri::async_runtime::spawn_blocking(move || {
        let _turn = turn.lock().unwrap_or_else(PoisonError::into_inner);
        // Inside the turn, so asking twice for one link waits for the first answer instead of a second lookup.
        if let Some(media) = answers.lock().unwrap_or_else(PoisonError::into_inner).get(&link.url, Instant::now()) {
            return Ok(media);
        }
        let media = engine.look_up(&link.url)?;
        answers.lock().unwrap_or_else(PoisonError::into_inner).put(&link.url, media.clone(), Instant::now());
        Ok(media)
    })
    .await;
    Ok(match found {
        // Joining picture and sound comes with downloads; until then, what the phone can join (H.264).
        Ok(Ok(media)) => Lookup::from_media(&media, is_h264),
        Ok(Err(failure)) => Lookup::from_failure(&failure),
        Err(_) => Lookup::failed(FailureKind::Engine),
    })
}

pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            let engine_dir = app.path().resource_dir()?.join("engine");
            let cache_dir = app.path().app_cache_dir()?.join("yt-dlp");
            std::fs::create_dir_all(&cache_dir)?;
            app.manage(Lookups {
                engine: Engine::in_folder(&engine_dir, cache_dir),
                turn: Arc::new(Mutex::new(())),
                answers: Arc::new(Mutex::new(AnswerCache::default())),
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![recognise_link, look_up])
        .run(tauri::generate_context!())
        .expect("the window could not be opened");
}
