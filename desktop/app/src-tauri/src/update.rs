//! Chaya keeping itself up to date (`tauri-plugin-updater`). At most once a day it asks GitHub for the newest
//! Windows release (`latest.json` on the `windows-latest` release, `tauri.conf.json`); when there is one, the
//! window offers it in the settings, and nothing is fetched or installed until the person clicks. What is fetched
//! is checked against the key in `tauri.conf.json` before it runs, so only Chaya's own releases install.

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_updater::{Update, UpdaterExt};

/// How long after one check the next may be made.
const EVERY: u64 = 24 * 60 * 60;

/// The update found, kept until the person installs it.
#[derive(Default)]
pub struct Updates(Mutex<Option<Update>>);

/// What the window shows of it.
#[derive(Clone, Serialize)]
pub struct UpdateView {
    version: String,
    notes: Option<String>,
}

/// When the last check was made (`update.json` in the app's data folder).
#[derive(Default, Serialize, Deserialize)]
struct Checked {
    at: u64,
}

/// Whether a check is due at `now`, the last having been at `last` (seconds since 1970). A clock set back counts
/// as due, so a check is never put off for years.
fn due(last: Option<u64>, now: u64) -> bool {
    last.is_none_or(|last| now < last || now - last >= EVERY)
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

fn read(path: &Path) -> Option<u64> {
    serde_json::from_slice::<Checked>(&std::fs::read(path).ok()?).ok().map(|c| c.at)
}

/// Checks for an update in the background, when one is due, and tells the window (`update`) if there is one.
pub fn start(app: &AppHandle, path: PathBuf) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let at = now();
        if !due(read(&path), at) {
            return;
        }
        let Ok(updater) = app.updater() else { return };
        let found = updater.check().await;
        // A failed check (offline, or no release yet) is tried again on the next start, not in a day.
        let Ok(found) = found else { return };
        let _ = std::fs::write(&path, serde_json::to_vec(&Checked { at }).unwrap_or_default());
        if let Some(update) = found {
            let view = UpdateView { version: update.version.clone(), notes: update.body.clone() };
            *app.state::<Updates>().0.lock().unwrap_or_else(|e| e.into_inner()) = Some(update);
            let _ = app.emit_to("main", "update", view);
        }
    });
}

/// The update waiting, if any, for a window that missed the event.
#[tauri::command]
pub fn update_waiting(updates: State<'_, Updates>) -> Option<UpdateView> {
    let update = updates.0.lock().unwrap_or_else(|e| e.into_inner());
    update.as_ref().map(|u| UpdateView { version: u.version.clone(), notes: u.body.clone() })
}

/// Fetches the update, checks its signature, installs it and starts Chaya again. On Windows the installer closes
/// Chaya itself; downloads running are paused and carry on after, as after any restart.
#[tauri::command]
pub async fn install_update(app: AppHandle, updates: State<'_, Updates>) -> Result<(), String> {
    let update = updates.0.lock().unwrap_or_else(|e| e.into_inner()).take();
    let Some(update) = update else { return Err("There is no update waiting.".into()) };
    update
        .download_and_install(|_, _| {}, || {})
        .await
        .map_err(|_| "The update couldn't be installed. Chaya will offer it again tomorrow.".to_string())?;
    app.restart();
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The update key's public half in tauri.conf.json is the one the releases are signed with: a file signed by
    /// the release key (tests/fixtures) checks out against it, and a changed file does not.
    #[test]
    fn the_key_in_the_config_checks_what_the_release_key_signed() {
        use base64::Engine;
        let config: serde_json::Value = serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        let text = |b64: &str| {
            String::from_utf8(base64::engine::general_purpose::STANDARD.decode(b64.trim()).unwrap()).unwrap()
        };
        let key = minisign_verify::PublicKey::decode(&text(config["plugins"]["updater"]["pubkey"].as_str().unwrap()))
            .unwrap();
        let signature =
            minisign_verify::Signature::decode(&text(include_str!("../tests/fixtures/signed.txt.sig"))).unwrap();
        let file = include_bytes!("../tests/fixtures/signed.txt");
        assert!(key.verify(file, &signature, true).is_ok());
        assert!(key.verify(b"something else", &signature, true).is_err());
    }

    #[test]
    fn a_check_is_made_once_a_day_and_after_the_clock_is_set_back() {
        assert!(due(None, 1_000), "never checked");
        assert!(!due(Some(1_000), 1_000 + EVERY - 1));
        assert!(due(Some(1_000), 1_000 + EVERY));
        assert!(due(Some(5_000), 1_000), "the clock went back");
    }
}
