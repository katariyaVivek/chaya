//! Ad blocking in the built-in browser, as on the phone: EasyList and EasyPrivacy, read by `chaya-core`'s
//! `adblock`. The lists are read in the background when the browser first opens (pages load unblocked until
//! then), and fresh copies are fetched from easylist.to about once a week.
//!
//! - **Requests** (Windows): the page's webview hands each request it makes to `AdBlocker`, and an ad's or a
//!   tracker's gets an empty answer. The page's own document is never blocked. WebKitGTK, used only to try the
//!   app on Linux, offers no such hook, so there only hiding and pop-up blocking apply.
//! - **Pop-ups**: a window the lists name as an ad is refused without asking the person (`browser`).
//! - **Hiding**: `hider.js`, in the page's top document, notes the classes and ids the document holds once asked.
//!   Like the detector it hands the page nothing: the app asks it under a random name a few times a second and
//!   evaluates the CSS for what it reported back into the same document.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use chaya_core::adblock::lists::{self, Lists};
use chaya_core::adblock::settings::Settings;
use chaya_core::adblock::{public_suffix_site, AdBlocker, Shield};
use serde::{Deserialize, Serialize};
use tauri::{AppHandle, Manager, State};

use crate::browser;

/// How often the page's top document is asked what it holds.
const ASK_EVERY: Duration = Duration::from_millis(250);
/// Most names a document reports at once; the rest come at the next ask.
const MAX_NAMES: usize = 4000;

pub struct Ads {
    pub blocker: Arc<AdBlocker>,
    lists: Arc<Lists>,
    client: chaya_core::reqwest::Client,
    started: AtomicBool,
}

impl Ads {
    pub fn new(app: &AppHandle, client: chaya_core::reqwest::Client) -> tauri::Result<Ads> {
        let data = app.path().app_data_dir()?;
        let bundled = app.path().resource_dir()?.join("adblock");
        Ok(Ads {
            blocker: Arc::new(AdBlocker::new(Settings::open(data.join("adblock.json")), public_suffix_site)),
            lists: Arc::new(Lists::new(bundled, data.join("adblock"), lists::default_sources())),
            client,
            started: AtomicBool::new(false),
        })
    }

    /// Reads the lists in the background, then fetches fresh copies when a week has passed. Called when the
    /// browser first opens; later calls do nothing.
    pub fn start(&self) {
        if self.started.swap(true, Ordering::SeqCst) {
            return;
        }
        let (blocker, lists, client) = (self.blocker.clone(), self.lists.clone(), self.client.clone());
        tauri::async_runtime::spawn(async move {
            read(&blocker, &lists).await;
            if lists.update_if_due(&client, now()).await {
                read(&blocker, &lists).await;
            }
        });
    }
}

async fn read(blocker: &Arc<AdBlocker>, lists: &Arc<Lists>) {
    let lists = lists.clone();
    match tauri::async_runtime::spawn_blocking(move || lists.build_engine(public_suffix_site)).await {
        Ok(Ok(engine)) => blocker.use_engine(engine),
        Ok(Err(e)) => eprintln!("Chaya: the ad lists could not be read: {e}"),
        Err(_) => {}
    }
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs())
}

/// What the window's ad blocker panel shows.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AdsView {
    shield: Shield,
    /// When fresh lists were last fetched (seconds since 1970); None while the app's own copies are in use.
    updated: Option<u64>,
}

/// The ad blocker as the tab shown has it (its page and count), or the browser's before any tab is shown.
#[tauri::command]
pub fn ad_blocking(app: AppHandle, ads: State<'_, Ads>) -> AdsView {
    let blocker = browser::active_blocker(&app).unwrap_or_else(|| ads.blocker.clone());
    AdsView { shield: blocker.shield(), updated: ads.lists.last_updated() }
}

/// Blocks ads everywhere, or nowhere: in every tab, whose blockers share the person's choices.
#[tauri::command]
pub fn block_ads(on: bool, app: AppHandle, ads: State<'_, Ads>) -> Result<AdsView, String> {
    ads.blocker.settings().set_enabled(on).map_err(|_| "The setting couldn't be saved.".to_string())?;
    Ok(ad_blocking(app, ads))
}

/// Lets `site` show its ads, or blocks them there again. The window reloads the page.
#[tauri::command]
pub fn allow_ads_on(site: String, allowed: bool, app: AppHandle, ads: State<'_, Ads>) -> Result<AdsView, String> {
    ads.blocker.settings().set_allowed(&site, allowed).map_err(|_| "The setting couldn't be saved.".to_string())?;
    Ok(ad_blocking(app, ads))
}

/// The hider, with this page's name for it in it.
pub fn hider(key: &str) -> String {
    include_str!("hider.js").replace("__CHAYA_HIDE_KEY__", key)
}

/// What the page's top document reports when asked.
#[derive(Deserialize)]
struct Names {
    doc: String,
    url: String,
    names: Vec<String>,
}

/// Hides the ads in each document the tab `tab` shows, for as long as the tab is open. A tab not shown is asked once
/// it is: its ads are hidden within a moment of the person seeing it.
pub async fn hide_ads(app: AppHandle, key: String, tab: u64) {
    let mut last_doc = String::new();
    loop {
        tokio::time::sleep(ASK_EVERY).await;
        let (Some(page), Some(blocker)) = (browser::tab_page(&app, tab), browser::tab_blocker(&app, tab)) else {
            return;
        };
        if !browser::is_active(&app, tab) || !blocker.hides() {
            continue;
        }
        let call = format!(
            "(function () {{ var hide = window['{key}']; return typeof hide === 'function' ? hide('names') : ''; }})()"
        );
        let Some(report) = browser::ask(&page, call).await.and_then(|text| parse(&text)) else { continue };
        let new_document = report.doc != last_doc;
        let css = blocker.hiding_css(&report.url, new_document, report.names.iter().map(String::as_str));
        last_doc = report.doc;
        if css.is_empty() {
            continue;
        }
        let call = format!(
            "(function () {{ var hide = window['{key}']; if (typeof hide === 'function') hide('hide', {}, {}); }})()",
            serde_json::to_string(&last_doc).unwrap_or_default(),
            serde_json::to_string(&css).unwrap_or_default(),
        );
        let _ = page.eval(call);
    }
}

/// The document's report, checked: it is still the page's word.
fn parse(answer: &str) -> Option<Names> {
    let mut names: Names = serde_json::from_str(answer).ok().filter(|_| answer.len() <= 1024 * 1024)?;
    if names.doc.len() > 64 || names.url.len() > 4096 {
        return None;
    }
    names.names.truncate(MAX_NAMES);
    names.names.retain(|n| (2..=200).contains(&n.len()));
    Some(names)
}

#[cfg(windows)]
pub use webview2::block_requests;

/// WebKitGTK offers no way to answer a page's requests, so on Linux (where the app is only tried) nothing is.
#[cfg(not(windows))]
pub fn block_requests(_page: &tauri::Webview, _blocker: Arc<AdBlocker>) -> tauri::Result<()> {
    Ok(())
}

#[cfg(windows)]
mod webview2 {
    use std::sync::Arc;

    use chaya_core::adblock::{kind, AdBlocker};
    use webview2_com::Microsoft::Web::WebView2::Win32::*;
    use webview2_com::{take_pwstr, WebResourceRequestedEventHandler};
    use windows_core::{w, Interface, PWSTR};

    /// Answers each request the page makes: an empty 204 for an ad or a tracker, nothing (so it loads) for the
    /// rest. Set up before the page loads anything: the page opens on about:blank, and is sent on after this.
    pub fn block_requests(page: &tauri::Webview, blocker: Arc<AdBlocker>) -> tauri::Result<()> {
        page.with_webview(move |webview| {
            if let Err(e) = attach(&webview.controller(), &webview.environment(), blocker) {
                eprintln!("Chaya: ad blocking could not start: {e}");
            }
        })
    }

    fn attach(
        controller: &ICoreWebView2Controller,
        environment: &ICoreWebView2Environment,
        blocker: Arc<AdBlocker>,
    ) -> windows_core::Result<()> {
        let environment = environment.clone();
        let handler = WebResourceRequestedEventHandler::create(Box::new(move |_, args| {
            let Some(args) = args else { return Ok(()) };
            // SAFETY: WebView2 calls this on the window's thread with live arguments, as its documentation says.
            unsafe {
                let mut uri = PWSTR::null();
                args.Request()?.Uri(&mut uri)?;
                let uri = take_pwstr(uri);
                let mut context = COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL;
                args.ResourceContext(&mut context)?;
                if blocker.should_block(&uri, kind_of(context, &uri)) {
                    let response = environment.CreateWebResourceResponse(None, 204, w!("No Content"), w!(""))?;
                    args.SetResponse(&response)?;
                }
            }
            Ok(())
        }));
        let mut token = 0i64;
        // SAFETY: called on the window's thread (`with_webview`), on the page's own live webview.
        unsafe {
            let webview = controller.CoreWebView2()?;
            // The plain filter sees only the page's own document's requests. Ads come in frames from their networks'
            // sites, and those frames' requests (and workers') are watched only when asked for by source.
            match webview.cast::<ICoreWebView2_22>() {
                Ok(webview) => webview.AddWebResourceRequestedFilterWithRequestSourceKinds(
                    w!("*"),
                    COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL,
                    COREWEBVIEW2_WEB_RESOURCE_REQUEST_SOURCE_KINDS_ALL,
                )?,
                Err(_) => webview.AddWebResourceRequestedFilter(w!("*"), COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL)?,
            }
            webview.add_WebResourceRequested(&handler, &mut token)
        }
    }

    /// The kind of request WebView2 says it is, as the lists name kinds.
    fn kind_of(context: COREWEBVIEW2_WEB_RESOURCE_CONTEXT, uri: &str) -> u16 {
        match context {
            COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT => kind::DOCUMENT,
            COREWEBVIEW2_WEB_RESOURCE_CONTEXT_STYLESHEET => kind::STYLESHEET,
            COREWEBVIEW2_WEB_RESOURCE_CONTEXT_IMAGE => kind::IMAGE,
            COREWEBVIEW2_WEB_RESOURCE_CONTEXT_MEDIA | COREWEBVIEW2_WEB_RESOURCE_CONTEXT_TEXT_TRACK => kind::MEDIA,
            COREWEBVIEW2_WEB_RESOURCE_CONTEXT_FONT => kind::FONT,
            COREWEBVIEW2_WEB_RESOURCE_CONTEXT_SCRIPT => kind::SCRIPT,
            COREWEBVIEW2_WEB_RESOURCE_CONTEXT_XML_HTTP_REQUEST
            | COREWEBVIEW2_WEB_RESOURCE_CONTEXT_FETCH
            | COREWEBVIEW2_WEB_RESOURCE_CONTEXT_EVENT_SOURCE => kind::XHR,
            COREWEBVIEW2_WEB_RESOURCE_CONTEXT_WEBSOCKET => kind::WEBSOCKET,
            COREWEBVIEW2_WEB_RESOURCE_CONTEXT_PING | COREWEBVIEW2_WEB_RESOURCE_CONTEXT_CSP_VIOLATION_REPORT => {
                kind::PING
            }
            _ => kind::by_extension(uri),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_hider_carries_this_pages_name_and_nothing_left_to_fill() {
        let script = hider("__chaya_hide1");
        assert!(script.contains("'__chaya_hide1'"));
        assert!(!script.contains("__CHAYA_HIDE_KEY__"));
    }

    #[test]
    fn a_documents_report_is_checked_before_it_is_used() {
        let ok = parse(r#"{"doc":"d1","url":"https://news.example/","names":[".ad-banner","x",".article"]}"#).unwrap();
        assert_eq!(ok.names, vec![".ad-banner", ".article"], "a name too short to be a class or id is dropped");
        assert!(parse("not json").is_none());
        assert!(parse(&format!(r#"{{"doc":"{}","url":"","names":[]}}"#, "d".repeat(65))).is_none());
        let many = (0..5000).map(|i| format!("\".n{i}\"")).collect::<Vec<_>>().join(",");
        let report = parse(&format!(r#"{{"doc":"d","url":"","names":[{many}]}}"#)).unwrap();
        assert_eq!(report.names.len(), MAX_NAMES);
    }
}

/// The page's requests answered in a real WebView2, against a page served from this machine: its ad is never
/// fetched, the rest of it is. Windows only: elsewhere requests are not blocked (see `block_requests`).
#[cfg(all(test, windows))]
mod webview_tests {
    use std::io::{BufRead, BufReader, Write};
    use std::net::TcpListener;
    use std::sync::{Arc, Mutex};
    use std::time::{Duration, Instant};

    use chaya_core::adblock::settings::Settings;
    use chaya_core::adblock::{public_suffix_site, AdBlocker, Builder};
    use tauri::webview::WebviewBuilder;
    use tauri::{LogicalPosition, LogicalSize, Manager, Url, WebviewUrl};

    use super::block_requests;

    const PAGE: &str = "<!doctype html><title>News</title><p>Story</p>\
        <script src=\"/ads/banner.js\"></script><img src=\"/ads/banner.gif\"><img src=\"/photo.png\">\
        <iframe src=\"http://localhost:PORT/frame.html\"></iframe>";
    /// A frame from another site, as an ad network's is: what it loads is asked for from inside the frame.
    const FRAME: &str = "<!doctype html><img src=\"/ads/inframe.gif\"><img src=\"/frame-photo.png\">";

    /// Serves the page at any path, and notes each path asked for.
    fn serve(asked: Arc<Mutex<Vec<String>>>) -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let mut line = String::new();
                let mut reader = BufReader::new(stream.try_clone().unwrap());
                if reader.read_line(&mut line).is_err() {
                    continue;
                }
                let path = line.split_whitespace().nth(1).unwrap_or("/").to_string();
                loop {
                    let mut header = String::new();
                    if reader.read_line(&mut header).map_or(true, |n| n == 0) || header == "\r\n" {
                        break;
                    }
                }
                asked.lock().unwrap().push(path.clone());
                let page = PAGE.replace("PORT", &port.to_string());
                let (kind, body) = match path.as_str() {
                    "/frame.html" => ("text/html", FRAME.to_string()),
                    p if p.ends_with(".html") => ("text/html", page),
                    _ => ("text/plain", String::new()),
                };
                let mut stream = stream;
                let _ = write!(
                    stream,
                    "HTTP/1.1 200 OK\r\ncontent-type: {kind}\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
                    body.len()
                );
            }
        });
        port
    }

    #[test]
    fn a_pages_ads_are_never_fetched_and_the_rest_of_it_is_frames_from_other_sites_included() {
        let asked = Arc::new(Mutex::new(Vec::new()));
        let url: Url = format!("http://127.0.0.1:{}/story.html", serve(asked.clone())).parse().unwrap();
        let blocker = Arc::new(AdBlocker::new(Settings::in_memory(), public_suffix_site));
        let mut rules = Builder::new(public_suffix_site);
        rules.add("/ads/banner.");
        rules.add("/ads/inframe.");
        blocker.use_engine(rules.build());

        let (pages, watched, page_url) = (blocker.clone(), asked.clone(), url.clone());
        let app = tauri::Builder::default()
            .any_thread()
            .setup(move |app| {
                let window = app.get_window("main").expect("the window");
                let begins = pages.clone();
                let builder = WebviewBuilder::new("page", WebviewUrl::External("about:blank".parse()?)).on_navigation(
                    move |url| {
                        begins.begin_page(url.as_str());
                        true
                    },
                );
                let page = window.add_child(builder, LogicalPosition::new(0.0, 0.0), LogicalSize::new(800.0, 600.0))?;
                block_requests(&page, pages.clone())?;
                page.navigate(page_url)?;
                let handle = app.handle().clone();
                std::thread::spawn(move || {
                    let start = Instant::now();
                    while start.elapsed() < Duration::from_secs(60)
                        && !["/photo.png", "/frame-photo.png"]
                            .iter()
                            .all(|want| watched.lock().unwrap().iter().any(|p| p == want))
                    {
                        std::thread::sleep(Duration::from_millis(100));
                    }
                    // Time for a late request to arrive, were one coming.
                    std::thread::sleep(Duration::from_secs(1));
                    handle.exit(0);
                });
                Ok(())
            })
            .build(tauri::generate_context!())
            .expect("the app");
        app.run_return(|_, _| {});

        let asked = asked.lock().unwrap().clone();
        assert!(asked.iter().any(|p| p == "/story.html"), "{asked:?}");
        assert!(asked.iter().any(|p| p == "/photo.png"), "the rest of the page loads: {asked:?}");
        assert!(asked.iter().any(|p| p == "/frame-photo.png"), "and the frame's: {asked:?}");
        assert!(
            !asked.iter().any(|p| p.starts_with("/ads/")),
            "the ads are never fetched, in a frame either: {asked:?}"
        );
        assert_eq!(blocker.shield().blocked, 3);
    }
}
