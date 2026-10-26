//! The built-in browser: one page at a time, in a webview of its own inside the window, where the window's own
//! content (`ui/main.js`) leaves room for it and says so. The window draws the bar around the page and asks
//! here to go back, forward, reload or close.
//!
//! A page gets nothing of the app. Its webview is not the window's: the app's commands are granted to the
//! window's own content only (`capabilities/default.json`), and a page from the web is never local. A page can
//! go only to the web (`browse::may_open`); a window it tries to open is held back and the window asked. A
//! download it starts of a video, a sound, a picture or a stream goes to Chaya's list (without the page's
//! cookies); any other file is saved as any browser saves it.
//!
//! What a page's players load is found by `detector.js`, run in every document of the page. The app reads it
//! (`page_media`) without the page ever being handed a way in: the script keeps its list under a random name the
//! page's own scripts do not know, and the app evaluates a call to it.

use std::collections::hash_map::RandomState;
use std::hash::{BuildHasher, Hasher};
use std::sync::Mutex;
use std::time::Duration;

use chaya_core::browse;
use chaya_core::found::{self, Found, FoundKind, Report};
use chaya_core::links;
use serde::{Deserialize, Serialize};
use tauri::webview::{DownloadEvent, NewWindowResponse, PageLoadEvent, WebviewBuilder};
use tauri::{AppHandle, Emitter, LogicalPosition, LogicalSize, Manager, Url, Webview, WebviewUrl};

use chaya_core::download::plan::plan;
use chaya_core::lookup::Lookup;
use chaya_core::media::{FailureKind, Media};

use crate::{Downloads, Lookups};

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

/// What the app knows about the page: its title, for naming what it downloads; the name its detector keeps its
/// list under; and what was found on it when the window last asked.
#[derive(Default)]
pub struct PageState(Mutex<PageInner>);

#[derive(Default)]
struct PageInner {
    title: String,
    key: String,
    found: Vec<Found>,
}

fn page_state(app: &AppHandle) -> std::sync::MutexGuard<'_, PageInner> {
    app.state::<PageState>().inner().0.lock().unwrap_or_else(|e| e.into_inner())
}

/// A word no page can guess: 128 bits from the hasher's random keys, which the system seeds.
fn random_word() -> String {
    (0..2)
        .map(|i| {
            let mut hasher = RandomState::new().build_hasher();
            hasher.write_u64(i);
            format!("{:016x}", hasher.finish())
        })
        .collect()
}

/// The detector, with this page's names in it.
fn detector(key: &str, nonce: &str) -> String {
    include_str!("detector.js").replace("__CHAYA_KEY__", key).replace("__CHAYA_NONCE__", nonce)
}

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
    let key = format!("__chaya_{}", random_word());
    page_state(&app).key = key.clone();
    let events = app.clone();
    let titles = app.clone();
    let popups = app.clone();
    let builder = WebviewBuilder::new(PAGE, WebviewUrl::External(url))
        .on_navigation(browse::may_open)
        .initialization_script_for_all_frames(detector(&key, &random_word()))
        .on_page_load(move |_, payload| {
            let url = payload.url().to_string();
            if payload.event() == PageLoadEvent::Started {
                page_state(&events).found.clear();
            }
            emit(
                &events,
                match payload.event() {
                    PageLoadEvent::Started => PageEvent::Loading { url },
                    PageLoadEvent::Finished => PageEvent::Loaded { url },
                },
            );
        })
        .on_document_title_changed(move |_, title| {
            page_state(&titles).title = title.clone();
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
    let title = page_state(app).title.clone();
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
    *page_state(&app) = PageInner::default();
}

/// Something found on the page, as the window lists it.
#[derive(Clone, Serialize)]
pub struct FoundView {
    url: String,
    kind: FoundKind,
    name: String,
    host: String,
    main: bool,
    ad: bool,
    /// Whether the app can save it as it is: a file, or with ffmpeg an HLS stream.
    savable: bool,
    /// Whether its qualities can be looked up: a stream, when the app carries ffmpeg to save one.
    qualities: bool,
}

/// What the page holds, as the window last asked: where it is now (a page that changes its address itself, as
/// YouTube does, loads nothing new), and what its players have loaded so far, the main video first.
#[derive(Clone, Serialize)]
pub struct PageMedia {
    url: String,
    items: Vec<FoundView>,
}

#[tauri::command]
pub async fn page_media(app: AppHandle) -> Result<PageMedia, String> {
    let page = app.get_webview(PAGE).ok_or("No page is open.")?;
    let key = page_state(&app).key.clone();
    let (tx, rx) = tokio::sync::oneshot::channel::<String>();
    let tx = Mutex::new(Some(tx));
    // The page's own document runs this; a page whose detector is missing (a page that failed to load) has none.
    let call =
        format!("(function () {{ var read = window['{key}']; return typeof read === 'function' ? read() : ''; }})()");
    page.eval_with_callback(call, move |answer| {
        if let Some(tx) = tx.lock().unwrap_or_else(|e| e.into_inner()).take() {
            let _ = tx.send(answer);
        }
    })
    .map_err(|_| "The page didn't respond.".to_string())?;
    let answer = tokio::time::timeout(Duration::from_secs(3), rx)
        .await
        .map_err(|_| "The page didn't respond.".to_string())?
        .unwrap_or_default();
    // The answer is the script's string, written as JSON; the page's word is checked by `found`.
    let report: Report = serde_json::from_str::<String>(&answer)
        .ok()
        .filter(|text| text.len() <= 2 * 1024 * 1024)
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default();
    let page_url = page.url().map(|u| u.to_string()).unwrap_or_default();
    let list = found::found(&report, &page_url);
    let joins = app.state::<Downloads>().joins;
    let views = list
        .iter()
        .map(|f| FoundView {
            url: f.url.clone(),
            kind: f.kind,
            name: f.name.clone(),
            host: f.host.clone(),
            main: f.main,
            ad: f.ad,
            savable: browse::can_save_found(f, joins),
            qualities: joins && matches!(f.kind, FoundKind::Stream | FoundKind::Dash),
        })
        .collect();
    page_state(&app).found = list;
    Ok(PageMedia { url: page_url, items: views })
}

/// The qualities of a stream found on the page (an HLS playlist or a DASH manifest), as the bundled engine reads
/// them, with the page (or its player's frame) as referrer. Only an address the page's detector found is ever
/// looked up this way.
#[tauri::command]
pub async fn look_up_found(url: String, app: AppHandle) -> Result<Lookup, ()> {
    let Some(item) = found_stream(&app, &url) else {
        return Ok(Lookup::failed(FailureKind::Unsupported));
    };
    let lookups = app.state::<Lookups>();
    let downloads = app.state::<Downloads>();
    Ok(match lookups.stream_media(item.url.clone(), item.referer.clone()).await {
        Ok(media) => Lookup::from_media(&named(media, &item), |f| downloads.can_join(f), downloads.joins),
        Err(failure) => Lookup::from_failure(&failure),
    })
}

/// Saves `choice` ("720p", "Audio only") of a stream found on the page, as `look_up_found` offered it.
#[tauri::command]
pub async fn download_found(url: String, choice: String, app: AppHandle) -> Result<Vec<crate::TaskView>, String> {
    let item = found_stream(&app, &url).ok_or("This is no longer on the page.")?;
    let page_url = app.get_webview(PAGE).and_then(|p| p.url().ok()).map(|u| u.to_string()).unwrap_or_default();
    let lookups = app.state::<Lookups>();
    let downloads = app.state::<Downloads>();
    let media = lookups.stream_media(item.url.clone(), item.referer.clone()).await.map_err(|f| f.kind.message())?;
    let plans =
        plan(&named(media, &item), &choice, &page_url, &downloads.folder, |f| downloads.can_join(f), downloads.joins)
            .ok_or("This choice can't be saved any more. Look it up again.")?;
    Ok(plans.into_iter().map(|p| crate::TaskView::from(&downloads.manager.add(p))).collect())
}

/// The stream at `url` in what the page was last found to hold.
fn found_stream(app: &AppHandle, url: &str) -> Option<Found> {
    page_state(app)
        .found
        .iter()
        .find(|f| f.url == url && matches!(f.kind, FoundKind::Stream | FoundKind::Dash))
        .cloned()
}

/// A stream's own address names nothing ("manifest", "index"): it is called what the page calls it.
fn named(mut media: Media, item: &Found) -> Media {
    media.title = item.name.clone();
    media
}

/// Saves what the window listed at `url` on the page.
#[tauri::command]
pub fn save_found(url: String, app: AppHandle) -> Result<Vec<crate::TaskView>, String> {
    let item = page_state(&app).found.iter().find(|f| f.url == url).cloned();
    let item = item.ok_or("This is no longer on the page.")?;
    let page_url = app.get_webview(PAGE).and_then(|p| p.url().ok()).map(|u| u.to_string()).unwrap_or_default();
    let downloads = app.state::<Downloads>();
    let new = browse::found_download(&item, &page_url, &downloads.folder, downloads.joins)
        .ok_or("Chaya can't save this yet.")?;
    Ok(vec![crate::TaskView::from(&downloads.manager.add(new))])
}

fn position(bounds: Bounds) -> LogicalPosition<f64> {
    LogicalPosition::new(bounds.x, bounds.y)
}

fn size(bounds: Bounds) -> LogicalSize<f64> {
    LogicalSize::new(bounds.width.max(1.0), bounds.height.max(1.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_page_gets_names_no_page_can_guess() {
        let (a, b) = (random_word(), random_word());
        assert_eq!(a.len(), 32);
        assert!(a.chars().all(|c| c.is_ascii_hexdigit()));
        assert_ne!(a, b);
    }

    #[test]
    fn the_detector_carries_this_pages_names_and_nothing_left_to_fill() {
        let script = detector("__chaya_key1", "nonce1");
        assert!(script.contains("'__chaya_key1'") && script.contains("'nonce1'"));
        assert!(!script.contains("__CHAYA_KEY__") && !script.contains("__CHAYA_NONCE__"));
    }
}
