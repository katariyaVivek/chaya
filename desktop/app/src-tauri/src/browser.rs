//! The built-in browser: one page at a time, in a webview of its own inside the window, where the window's own
//! content (`ui/main.js`) leaves room for it and says so. The window draws the bar around the page and asks
//! here to go back, forward, reload or close.
//!
//! A page gets nothing of the app. Its webview is not the window's: the app's commands are granted to the
//! window's own content only (`capabilities/default.json`), and a page from the web is never local. A page can
//! go only to the web (`browse::may_open`); a window it tries to open is held back and the window asked. A
//! download it starts of a video, a sound, a picture or a stream goes to Chaya's list (without the page's
//! cookies); any other file is saved as any browser saves it.

use std::sync::Mutex;

use chaya_core::browse;
use chaya_core::links;
use serde::{Deserialize, Serialize};
use tauri::webview::{DownloadEvent, NewWindowResponse, PageLoadEvent, WebviewBuilder};
use tauri::{AppHandle, Emitter, LogicalPosition, LogicalSize, Manager, Url, Webview, WebviewUrl};

use crate::Downloads;

/// The page's webview.
const PAGE: &str = "page";

/// Where the page goes in the window, in the window's own (logical) pixels.
#[derive(Clone, Copy, Debug, Deserialize)]
pub struct Bounds {
    x: f64,
    y: f64,
    width: f64,
    height: f64,
}

/// What the window hears about the page, as `page` events.
#[derive(Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
enum PageEvent {
    /// The page started loading `url`.
    Loading {
        url: String,
    },
    /// The page at `url` finished loading.
    Loaded {
        url: String,
    },
    Title {
        title: String,
    },
    /// The page tried to open `url` in a new window.
    Popup {
        url: String,
    },
    /// A download the page started is saved in Chaya's list as `name`.
    Saving {
        name: String,
    },
}

/// The page's title, for naming what it downloads.
#[derive(Default)]
pub struct PageTitle(Mutex<String>);

fn emit(app: &AppHandle, event: PageEvent) {
    let _ = app.emit("page", event);
}

/// Opens what `text` names (an address, a site, an account, or words to search for) in the page, creating the
/// page where `bounds` says when there is none. Returns the address opened, for the address bar.
#[tauri::command]
pub async fn open_page(text: String, bounds: Bounds, app: AppHandle) -> Result<String, String> {
    let address = browse::page_for(&links::recognise(&text)).ok_or("This is looked up, not opened.")?;
    let url = Url::parse(&address).map_err(|_| "This isn't an address Chaya can open.".to_string())?;
    if !browse::may_open(&url) {
        return Err("Chaya opens only web pages.".into());
    }
    if let Some(page) = app.get_webview(PAGE) {
        page.navigate(url).map_err(|_| "The page couldn't be opened.".to_string())?;
        let _ = page.show();
        return Ok(address);
    }
    let window = app.get_window("main").ok_or("The window is gone.")?;
    let events = app.clone();
    let titles = app.clone();
    let popups = app.clone();
    let builder = WebviewBuilder::new(PAGE, WebviewUrl::External(url))
        .on_navigation(browse::may_open)
        .on_page_load(move |_, payload| {
            let url = payload.url().to_string();
            emit(
                &events,
                match payload.event() {
                    PageLoadEvent::Started => PageEvent::Loading { url },
                    PageLoadEvent::Finished => PageEvent::Loaded { url },
                },
            );
        })
        .on_document_title_changed(move |_, title| {
            *titles.state::<PageTitle>().0.lock().unwrap_or_else(|e| e.into_inner()) = title.clone();
            emit(&titles, PageEvent::Title { title });
        })
        .on_new_window(move |url, _| {
            if browse::may_open(&url) {
                emit(&popups, PageEvent::Popup { url: url.to_string() });
            }
            NewWindowResponse::Deny
        })
        .on_download(page_download);
    window
        .add_child(builder, position(bounds), size(bounds))
        .map_err(|_| "The page couldn't be opened.".to_string())?;
    Ok(address)
}

/// A download the page started: Chaya's own when it is media (`browse::page_download`), else the browser's.
fn page_download(page: Webview, event: DownloadEvent<'_>) -> bool {
    let DownloadEvent::Requested { url, .. } = event else { return true };
    let app = page.app_handle();
    let page_url = page.url().map(|u| u.to_string()).unwrap_or_default();
    let title = app.state::<PageTitle>().0.lock().unwrap_or_else(|e| e.into_inner()).clone();
    let downloads = app.state::<Downloads>();
    match browse::page_download(url.as_str(), &page_url, &title, &downloads.folder, downloads.joins) {
        Some(new) => {
            let task = downloads.manager.add(new);
            let name = task.path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            emit(app, PageEvent::Saving { name });
            // The browser's own download is cancelled: Chaya's list has it.
            false
        }
        None => true,
    }
}

/// Moves the page to where the window now leaves room for it.
#[tauri::command]
pub fn place_page(bounds: Bounds, app: AppHandle) {
    if let Some(page) = app.get_webview(PAGE) {
        let _ = page.set_position(position(bounds));
        let _ = page.set_size(size(bounds));
    }
}

/// Shows or hides the page, while the window shows something in its place (the downloads, the settings).
#[tauri::command]
pub fn show_page(visible: bool, app: AppHandle) {
    if let Some(page) = app.get_webview(PAGE) {
        let _ = if visible { page.show() } else { page.hide() };
    }
}

/// "back", "forward", "reload" or "stop".
#[tauri::command]
pub fn page_action(action: String, app: AppHandle) -> Result<(), String> {
    let page = app.get_webview(PAGE).ok_or("No page is open.")?;
    let result = match action.as_str() {
        "back" => page.eval("history.back()"),
        "forward" => page.eval("history.forward()"),
        "reload" => page.reload(),
        "stop" => page.eval("window.stop()"),
        _ => return Err(format!("Unknown action: {action}")),
    };
    result.map_err(|_| "The page didn't respond.".into())
}

/// Closes the page, and the window goes back to its own content.
#[tauri::command]
pub fn close_page(app: AppHandle) {
    if let Some(page) = app.get_webview(PAGE) {
        let _ = page.close();
    }
    app.state::<PageTitle>().0.lock().unwrap_or_else(|e| e.into_inner()).clear();
}

fn position(bounds: Bounds) -> LogicalPosition<f64> {
    LogicalPosition::new(bounds.x, bounds.y)
}

fn size(bounds: Bounds) -> LogicalSize<f64> {
    LogicalSize::new(bounds.width.max(1.0), bounds.height.max(1.0))
}
