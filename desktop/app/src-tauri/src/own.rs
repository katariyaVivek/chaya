//! The person's own videos and sounds (`chaya-core`'s `own`): files dropped on the window, or picked with *Add from
//! this PC*, listed where they are as finished entries in the Downloads list. Nothing is copied, moved, changed or
//! uploaded; taking one off the list keeps the file. A drop on a page in the built-in browser is the window's too: the
//! page never gets the file (`browser::make_page`).

use std::path::PathBuf;

use chaya_core::download::manager::{Manager as DownloadManager, TaskView};
use chaya_core::own::{self, SOUNDS, VIDEOS};
use serde::Serialize;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_dialog::DialogExt;

use crate::Downloads;

/// What came of adding files, as the window shows it: every file listed (new, or the entry it had), the numbers of
/// those that were already listed, and why each of the rest was not added.
#[derive(Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Added {
    tasks: Vec<TaskView>,
    already: Vec<u64>,
    refused: Vec<String>,
}

impl From<own::Added> for Added {
    fn from(added: own::Added) -> Added {
        Added {
            tasks: added.tasks.iter().map(TaskView::from).collect(),
            already: added.already,
            refused: added.refused,
        }
    }
}

/// Lists the videos and sounds among `paths` (dropped on the window) where they are.
#[tauri::command]
pub async fn add_own_videos(paths: Vec<PathBuf>, downloads: State<'_, Downloads>) -> Result<Added, String> {
    add(downloads.manager.clone(), paths).await
}

/// Checks and lists `paths` off the window's thread: a file on a network drive may be slow to answer.
async fn add(manager: DownloadManager, paths: Vec<PathBuf>) -> Result<Added, String> {
    tauri::async_runtime::spawn_blocking(move || own::add_all(&manager, &paths).into())
        .await
        .map_err(|_| "The files couldn't be added.".to_string())
}

/// *Add from this PC*: Windows' own file picker, for videos and sounds, several at once; what was picked is listed
/// where it is. Nothing, when the person closes the picker.
#[tauri::command]
pub async fn choose_own_videos(app: AppHandle) -> Result<Added, String> {
    let (tx, rx) = tokio::sync::oneshot::channel();
    let mut dialog = app
        .dialog()
        .file()
        .set_title("Add videos and sounds from this PC")
        .add_filter("Videos and sounds", &[VIDEOS, SOUNDS].concat())
        .add_filter("Videos", VIDEOS)
        .add_filter("Sounds", SOUNDS);
    if let Some(window) = app.get_webview_window("main") {
        dialog = dialog.set_parent(&window);
    }
    dialog.pick_files(move |picked| {
        let _ = tx.send(picked);
    });
    let picked = rx.await.map_err(|_| "Windows' file picker closed unexpectedly.".to_string())?;
    let paths: Vec<PathBuf> = picked.unwrap_or_default().into_iter().filter_map(|p| p.into_path().ok()).collect();
    add(app.state::<Downloads>().manager.clone(), paths).await
}
