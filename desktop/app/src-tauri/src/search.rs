//! The search across every saved video's lines (`chaya-core`'s `search`), from the Downloads list's find box. The
//! index is kept in memory only, never written anywhere; before each search it looks again at the subtitle files when
//! the list changed or a second has passed, and reads only those that changed.

use std::sync::{Arc, Mutex, PoisonError};
use std::time::Instant;

use chaya_core::download::manager::{Kind, State as TaskState};
use chaya_core::own::name_of;
use chaya_core::search::{Index, Results, Video, MOST};
use tauri::State;

use crate::Downloads;

#[derive(Default)]
pub struct Searching(Arc<Mutex<Index>>);

/// The lines of every saved video and sound (or of those `among`, the ones the list's narrowing keeps) that hold every
/// word of `query`: at most `MOST`, by video in the list's order (newest first), then by time; and how many in all.
#[tauri::command]
pub async fn search_lines(
    query: String,
    among: Option<Vec<u64>>,
    downloads: State<'_, Downloads>,
    searching: State<'_, Searching>,
) -> Result<Results, ()> {
    let videos: Vec<Video> = downloads
        .manager
        .list()
        .into_iter()
        .filter(|t| t.state == TaskState::Completed && matches!(t.kind, Kind::Video | Kind::Audio))
        .map(|t| Video { id: t.id, title: if t.title.is_empty() { name_of(&t.path) } else { t.title }, path: t.path })
        .collect();
    let index = searching.0.clone();
    // Files are looked at, and many lines gone through: off the window's thread.
    tauri::async_runtime::spawn_blocking(move || {
        let mut index = index.lock().unwrap_or_else(PoisonError::into_inner);
        index.keep_up(&videos, Instant::now());
        index.search(&query, among.as_deref(), MOST)
    })
    .await
    .map_err(|_| ())
}
