//! A saved video's clips (`chaya-core`'s `clips`), made in the player: from lines chosen in the transcript (Ctrl+B),
//! from highlights, and trimmed on its timeline. Kept in the video's file (`videos/ID.json`).

use chaya_core::clips::{Clip, NewClip};
use chaya_core::video::Videos;
use serde::Serialize;
use tauri::State;

/// A video's clips, and the ids of those just asked for (`None` for one that couldn't be made).
#[derive(Serialize)]
pub struct Added {
    clips: Vec<Clip>,
    ids: Vec<Option<u32>>,
}

fn kept<T>(result: std::io::Result<T>) -> Result<T, String> {
    result.map_err(|_| "Chaya couldn't keep this clip.".to_string())
}

/// Download `id`'s clips, in order.
#[tauri::command]
pub fn video_clips(id: u64, videos: State<'_, Videos>) -> Vec<Clip> {
    videos.load(id).clips
}

/// Adds `clips` to download `id`'s, in a video `duration` seconds long (when the window knows it).
#[tauri::command]
pub fn add_clips(
    id: u64,
    clips: Vec<NewClip>,
    duration: Option<f64>,
    videos: State<'_, Videos>,
) -> Result<Added, String> {
    let duration = duration.filter(|d| d.is_finite() && *d > 0.0).map(|d| (d * 1000.0) as u64);
    let (clips, ids) = kept(videos.add_clips(id, &clips, duration))?;
    Ok(Added { clips, ids })
}

/// Gives download `id`'s clip `clip.id` `clip`'s name and times.
#[tauri::command]
pub fn change_clip(id: u64, clip: Clip, videos: State<'_, Videos>) -> Result<Vec<Clip>, String> {
    kept(videos.change_clip(id, clip.id, Some(&clip)))
}

/// Removes download `id`'s clip `clip`.
#[tauri::command]
pub fn remove_clip(id: u64, clip: u32, videos: State<'_, Videos>) -> Result<Vec<Clip>, String> {
    kept(videos.change_clip(id, clip, None))
}
