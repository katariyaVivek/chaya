//! Subtitles put right from the player (`chaya-core`'s `fix`): a line's words corrected or the line taken out, and a
//! track's timing shifted or stretched, in one language or every language next to the video. Each change keeps the
//! files as they were before it, so Undo puts them back exactly; the last few changes of each video, while the app runs.

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard, PoisonError};

use chaya_core::download::manager::State as TaskState;
use chaya_core::fix::{self, Ask, Retime};
use chaya_core::subtitles::{self, Track};
use tauri::State;

use crate::Downloads;

/// The most changes of one video that can be undone.
const UNDOS: usize = 20;

/// The files of a change as they were before it.
type Before = Vec<(PathBuf, Vec<u8>)>;

/// Each video's changes that can be undone, the latest last.
#[derive(Default)]
pub struct Fixes(Mutex<HashMap<u64, Vec<Before>>>);

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

/// The saved video `id`'s file.
fn video_of(id: u64, downloads: &Downloads) -> Result<PathBuf, String> {
    let task = downloads.manager.get(id).filter(|t| t.state == TaskState::Completed);
    task.map(|t| t.path).ok_or_else(|| "This video isn't saved any more.".to_string())
}

impl Fixes {
    /// Writes `changed` (each file and its new lines), keeping the files as they were for Undo.
    fn change(&self, id: u64, changed: Vec<(PathBuf, Vec<subtitles::Line>)>) -> Result<(), String> {
        let before: Before = changed
            .iter()
            .map(|(path, _)| std::fs::read(path).map(|bytes| (path.clone(), bytes)))
            .collect::<Result<_, _>>()
            .map_err(|_| "Chaya couldn't read the subtitles next to the video.".to_string())?;
        for (path, lines) in &changed {
            fix::write(path, lines).map_err(|_| "Chaya couldn't write the subtitles next to the video.".to_string())?;
        }
        let mut undos = lock(&self.0);
        let kept = undos.entry(id).or_default();
        kept.push(before);
        if kept.len() > UNDOS {
            kept.remove(0);
        }
        Ok(())
    }
}

/// Line `index` of download `id`'s subtitles in `language` says `text` instead (empty: the line is taken out). Its
/// subtitles after.
#[tauri::command]
pub fn edit_line(
    id: u64,
    language: Option<String>,
    index: usize,
    text: String,
    downloads: State<'_, Downloads>,
    fixes: State<'_, Fixes>,
) -> Result<Vec<Track>, String> {
    let video = video_of(id, &downloads)?;
    let path = fix::file_of(&video, language.as_deref()).ok_or("Those subtitles aren't next to the video any more.")?;
    let lines =
        subtitles::parse(&String::from_utf8_lossy(&std::fs::read(&path).map_err(|_| "Chaya couldn't read them.")?));
    let lines = fix::with_text(&lines, index, &text).ok_or("That line isn't there any more.")?;
    fixes.change(id, vec![(path, lines)])?;
    Ok(subtitles::tracks_for(&video))
}

/// Download `id`'s subtitles in `language` (or in every language, `every`) retimed as `ask` says. Its subtitles after.
#[tauri::command]
pub fn retime_subtitles(
    id: u64,
    language: Option<String>,
    ask: Ask,
    every: bool,
    downloads: State<'_, Downloads>,
    fixes: State<'_, Fixes>,
) -> Result<Vec<Track>, String> {
    let video = video_of(id, &downloads)?;
    let retime = Retime::of(ask);
    let files: Vec<PathBuf> = if every {
        subtitles::files_for(&video).into_iter().map(|f| f.path).collect()
    } else {
        fix::file_of(&video, language.as_deref()).into_iter().collect()
    };
    if files.is_empty() {
        return Err("Those subtitles aren't next to the video any more.".into());
    }
    let changed = files
        .into_iter()
        .filter_map(|path| {
            let lines = subtitles::parse(&String::from_utf8_lossy(&std::fs::read(&path).ok()?));
            (!lines.is_empty()).then(|| (path, retime.apply(&lines)))
        })
        .collect();
    fixes.change(id, changed)?;
    Ok(subtitles::tracks_for(&video))
}

/// Puts back download `id`'s subtitle files as they were before the last change. Its subtitles after.
#[tauri::command]
pub fn undo_subtitles(id: u64, downloads: State<'_, Downloads>, fixes: State<'_, Fixes>) -> Result<Vec<Track>, String> {
    let video = video_of(id, &downloads)?;
    let before = lock(&fixes.0).get_mut(&id).and_then(Vec::pop).ok_or("There's nothing to undo.")?;
    for (path, bytes) in before {
        std::fs::write(&path, bytes).map_err(|_| "Chaya couldn't put the subtitles back.".to_string())?;
    }
    Ok(subtitles::tracks_for(&video))
}

/// How many of download `id`'s changes can be undone.
#[tauri::command]
pub fn subtitle_undos(id: u64, fixes: State<'_, Fixes>) -> usize {
    lock(&fixes.0).get(&id).map_or(0, Vec::len)
}
