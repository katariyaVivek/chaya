//! A saved video's clips (`chaya-core`'s `clips`), made in the player: from lines chosen in the transcript (Ctrl+B),
//! from highlights, and trimmed on its timeline. Kept in the video's file (`videos/ID.json`). And the video's sound as
//! the timeline draws it (`chaya-core`'s `sound`), read once and kept beside it ("videos/ID.peaks").

use std::path::PathBuf;

use chaya_core::clips::{Clip, NewClip};
use chaya_core::download::manager::State as TaskState;
use chaya_core::sound::{self, SoundError};
use chaya_core::video::Videos;
use serde::Serialize;
use tauri::{AppHandle, Manager, State};

use crate::Downloads;

/// The engine's ffmpeg, which reads a video's sound for the timeline and exports clips; `None` when the engine has
/// none.
pub struct Ffmpeg(pub Option<PathBuf>);

/// How loud a video's sound is, `per_second` times a second, each a byte (base64).
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Peaks {
    per_second: u32,
    peaks: String,
}

/// Download `id`'s sound peaks: kept ones, or read now (a few seconds for an hour of video) and kept.
#[tauri::command]
pub async fn sound_peaks(id: u64, app: AppHandle) -> Result<Peaks, String> {
    use base64::Engine;
    let videos = app.state::<Videos>();
    let kept = std::fs::read(videos.peaks(id)).ok().filter(|p| !p.is_empty());
    let peaks = match kept {
        Some(peaks) => peaks,
        None => {
            let ffmpeg =
                app.state::<Ffmpeg>().0.clone().ok_or("Chaya's ffmpeg is missing, so it can't read the sound.")?;
            let task = app.state::<Downloads>().manager.get(id).filter(|t| t.state == TaskState::Completed);
            let task = task.ok_or("This video isn't saved any more.")?;
            let peaks = sound::peaks(&ffmpeg, &task.path).await.map_err(|error| match error {
                SoundError::NoProgram => "Chaya's ffmpeg wouldn't start, so it can't read the sound.",
                SoundError::NoSound => "This video has no sound.",
            })?;
            let _ = videos.keep_peaks(id, &peaks);
            peaks
        }
    };
    Ok(Peaks { per_second: sound::PER_SECOND, peaks: base64::engine::general_purpose::STANDARD.encode(peaks) })
}

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
