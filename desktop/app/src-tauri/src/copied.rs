//! Ctrl+Alt+D, from anywhere in Windows, saves the link just copied (`chaya-core`'s `copied`), when the person has
//! turned it on in Settings › Copied links. The key is Windows' own (`tauri-plugin-global-shortcut`), registered only
//! while it is on. What was copied is read when the key is pressed, never before or after; the window hears how it
//! went as `copied-link` events: nothing to save, saving it, or a link the person must look at themselves (a sign-in,
//! or a choice the setting can't make), which the window then shows as if it had been pasted.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, PoisonError};

use chaya_core::copied::{self, Settings, Wanted};
use chaya_core::video::Videos;
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, State, UserAttentionType};
use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Modifiers, Shortcut, ShortcutState};

use crate::{save_media, Downloads, Lookups};

pub struct Copied {
    folder: PathBuf,
    settings: Mutex<Settings>,
    /// Why the key could not be registered (another app holds it), while that is so.
    problem: Mutex<Option<String>>,
    /// While a press is being handled: another press then does nothing, so a link is never saved twice.
    busy: AtomicBool,
}

/// The setting as the window shows it.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CopiedView {
    on: bool,
    quality: Wanted,
    key: &'static str,
    problem: Option<String>,
}

/// What happened when the key was pressed.
#[derive(Clone, Debug, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
enum CopiedEvent {
    /// Nothing that was copied is a link Chaya can save.
    Nothing,
    /// The link is being looked up.
    Looking { url: String },
    /// It is being saved: its title, and the choice saved ("720p", "Audio only", "post").
    Saving { url: String, title: String, choice: String },
    /// The person must look at it: the window shows it as if pasted.
    Show { url: String },
}

fn key() -> Shortcut {
    Shortcut::new(Some(Modifiers::CONTROL | Modifiers::ALT), Code::KeyD)
}

impl Copied {
    /// The setting kept in `folder` (Chaya's data folder), and the key registered when it is on.
    pub fn start(app: &AppHandle, folder: PathBuf) -> Copied {
        let settings = Settings::load(&folder);
        let copied =
            Copied { folder, settings: Mutex::new(settings), problem: Mutex::new(None), busy: AtomicBool::new(false) };
        if settings.on {
            copied.listen(app, true);
        }
        copied
    }

    fn view(&self) -> CopiedView {
        let settings = *self.settings.lock().unwrap_or_else(PoisonError::into_inner);
        let problem = self.problem.lock().unwrap_or_else(PoisonError::into_inner).clone();
        CopiedView { on: settings.on, quality: settings.quality, key: copied::KEY, problem }
    }

    /// Registers the key (`on`) or lets it go; when another app holds it, says so.
    fn listen(&self, app: &AppHandle, on: bool) {
        let shortcuts = app.global_shortcut();
        let problem = if on {
            if shortcuts.is_registered(key()) {
                None
            } else {
                shortcuts
                    .on_shortcut(key(), |app, _, event| {
                        let busy = &app.state::<Copied>().busy;
                        if event.state == ShortcutState::Pressed && !busy.swap(true, Ordering::SeqCst) {
                            let app = app.clone();
                            tauri::async_runtime::spawn(async move {
                                save_copied(&app).await;
                                app.state::<Copied>().busy.store(false, Ordering::SeqCst);
                            });
                        }
                    })
                    .err()
                    .map(|_| format!("Another app is using {}. Close it, or turn this off and on again.", copied::KEY))
            }
        } else {
            let _ = shortcuts.unregister(key());
            None
        };
        *self.problem.lock().unwrap_or_else(PoisonError::into_inner) = problem;
    }
}

#[tauri::command]
pub fn copied_link(copied: State<'_, Copied>) -> CopiedView {
    copied.view()
}

/// Turns the key on or off, and sets what it saves.
#[tauri::command]
pub fn set_copied_link(
    on: bool,
    quality: Wanted,
    app: AppHandle,
    copied: State<'_, Copied>,
) -> Result<CopiedView, String> {
    let settings = Settings { on, quality };
    settings.save(&copied.folder).map_err(|_| "The setting couldn't be kept.".to_string())?;
    *copied.settings.lock().unwrap_or_else(PoisonError::into_inner) = settings;
    copied.listen(&app, on);
    Ok(copied.view())
}

/// The key was pressed: what was copied is read, and its link saved at the quality chosen.
async fn save_copied(app: &AppHandle) {
    let say = |event: CopiedEvent| {
        let _ = app.emit("copied-link", event);
    };
    let Some(url) = clipboard_text().as_deref().and_then(copied::link_in) else {
        say(CopiedEvent::Nothing);
        come_forward(app, false);
        return;
    };
    say(CopiedEvent::Looking { url: url.clone() });
    let quality = app.state::<Copied>().settings.lock().unwrap_or_else(PoisonError::into_inner).quality;
    let lookups = app.state::<Lookups>();
    let downloads = app.state::<Downloads>();
    let saved = match lookups.media(url.clone(), false).await {
        Ok(media) => copied::choose(&media, quality, |f| downloads.can_join(f), downloads.joins).and_then(|choice| {
            let tasks = save_media(&media, &choice, &url, &downloads, &app.state::<Videos>())?;
            (!tasks.is_empty()).then(|| CopiedEvent::Saving { url: url.clone(), title: media.title.clone(), choice })
        }),
        Err(_) => None,
    };
    match saved {
        Some(saving) => {
            say(saving);
            come_forward(app, false);
        }
        // A sign-in, a link gone, a choice to make: shown as if pasted, with what the card offers for it.
        None => {
            say(CopiedEvent::Show { url });
            come_forward(app, true);
        }
    }
}

/// Chaya's window, brought in front when the person must look at something (`focus`); else, when it is not in
/// front, its taskbar button lights up once.
fn come_forward(app: &AppHandle, focus: bool) {
    let Some(window) = app.get_webview_window("main") else { return };
    if focus {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    } else if !window.is_focused().unwrap_or(false) {
        let _ = window.request_user_attention(Some(UserAttentionType::Informational));
    }
}

/// The text on Windows' clipboard, when there is some.
#[cfg(windows)]
fn clipboard_text() -> Option<String> {
    use windows::Win32::Foundation::{HANDLE, HGLOBAL};
    use windows::Win32::System::DataExchange::{
        CloseClipboard, GetClipboardData, IsClipboardFormatAvailable, OpenClipboard,
    };
    use windows::Win32::System::Memory::{GlobalLock, GlobalSize, GlobalUnlock};
    use windows::Win32::System::Ole::CF_UNICODETEXT;

    let format = u32::from(CF_UNICODETEXT.0);
    // Another app may hold the clipboard for a moment: tried a few times.
    let mut opened = false;
    for _ in 0..5 {
        if unsafe { OpenClipboard(None) }.is_ok() {
            opened = true;
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    if !opened {
        return None;
    }
    let text = (|| {
        unsafe { IsClipboardFormatAvailable(format) }.ok()?;
        let HANDLE(data) = unsafe { GetClipboardData(format) }.ok()?;
        let memory = HGLOBAL(data);
        let words = unsafe { GlobalSize(memory) } / 2;
        let start = unsafe { GlobalLock(memory) } as *const u16;
        if start.is_null() {
            return None;
        }
        let all = unsafe { std::slice::from_raw_parts(start, words) };
        let end = all.iter().position(|&w| w == 0).unwrap_or(all.len());
        let text = String::from_utf16_lossy(&all[..end]);
        let _ = unsafe { GlobalUnlock(memory) };
        Some(text)
    })();
    let _ = unsafe { CloseClipboard() };
    text
}

#[cfg(not(windows))]
fn clipboard_text() -> Option<String> {
    None
}
