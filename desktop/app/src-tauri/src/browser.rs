//! The built-in browser: tabs, each a page in a webview of its own inside the window, where the window's own content
//! (`ui/main.js`) leaves room for it and says so. One tab is shown at a time (or none, while the window shows its
//! own home); the window draws the bar and the tabs around it and asks here to go back, forward, reload, switch or
//! close. Up to ten tabs, as on the phone at first; a tab's webview is made the first time it is shown, so the tabs
//! kept from the last run (`tabs.json`: each one's address and title) cost nothing until then.
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
//!
//! Ads are blocked as on the phone (`ads`): the page's requests on Windows, its ad pop-ups, and the ad boxes in
//! its top document.

use std::collections::hash_map::RandomState;
use std::hash::{BuildHasher, Hasher};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use chaya_core::adblock::{kind, AdBlocker, Shield};
use chaya_core::browse;
use chaya_core::found::{self, Found, FoundKind, Report};
use chaya_core::links::{self, Platform};
use chaya_core::session::{self, CookieFile, SessionCookie};
use serde::{Deserialize, Serialize};
use tauri::webview::{DownloadEvent, NewWindowResponse, PageLoadEvent, WebviewBuilder};
use tauri::{AppHandle, Emitter, LogicalPosition, LogicalSize, Manager, Url, Webview, WebviewUrl};

use chaya_core::download::plan::plan;
use chaya_core::lookup::Lookup;
use chaya_core::media::{FailureKind, Media};

use crate::ads::{self, Ads};
use crate::{Downloads, Lookups};

/// Most tabs open at once.
pub const MAX_TABS: usize = 10;

/// Where the page goes in the window, in the window's own (logical) pixels.
#[derive(Clone, Copy, Debug, PartialEq, Deserialize)]
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

/// A page event, and the tab it is about.
#[derive(Clone, Serialize)]
struct TabEvent {
    tab: u64,
    #[serde(flatten)]
    event: PageEvent,
}

/// One tab: what the app knows about its page.
struct Tab {
    id: u64,
    url: String,
    /// Its title, for the tab and for naming what it downloads.
    title: String,
    loading: bool,
    /// The name its detector keeps its list under, and what was found on it when the window last asked.
    key: String,
    found: Vec<Found>,
    /// Its own page and count of what was blocked; the rules and the person's choices are the browser's.
    blocker: Arc<AdBlocker>,
    /// Whether its webview exists: a tab kept from the last run has none until it is shown.
    opened: bool,
}

impl Tab {
    fn new(id: u64, url: String, title: String, blocker: &AdBlocker) -> Tab {
        Tab {
            id,
            url,
            title,
            loading: false,
            key: format!("__chaya_{}", random_word()),
            found: Vec::new(),
            blocker: Arc::new(blocker.for_another_page()),
            opened: false,
        }
    }
}

/// The browser's tabs, the one shown, and where its page goes in the window.
#[derive(Default)]
pub struct Tabs(Mutex<TabsInner>);

#[derive(Default)]
struct TabsInner {
    tabs: Vec<Tab>,
    /// The tab shown; None while the window shows its own home.
    active: Option<u64>,
    next: u64,
    bounds: Option<Bounds>,
    /// Where the tabs are kept for the next run.
    store: Option<PathBuf>,
    fullscreen: Option<Fullscreen>,
}

/// A page showing something fullscreen (a video's own fullscreen button): the window is fullscreen, and that tab's
/// page covers all of it, until the page leaves fullscreen (Esc, or its own button) or another tab or the home is
/// shown.
struct Fullscreen {
    tab: u64,
    /// Whether the window was fullscreen already, so leaving gives it back as it was.
    window_was: bool,
}

/// A tab as it is kept for the next run: where it was, and what it was called.
#[derive(Serialize, Deserialize)]
struct Kept {
    url: String,
    title: String,
}

impl Tabs {
    /// The tabs kept at `store` from the last run, none of them shown yet.
    pub fn open(store: PathBuf, blocker: &AdBlocker) -> Tabs {
        let kept: Vec<Kept> =
            std::fs::read(&store).ok().and_then(|bytes| serde_json::from_slice(&bytes).ok()).unwrap_or_default();
        let tabs: Vec<Tab> = kept
            .into_iter()
            .filter(|k| Url::parse(&k.url).is_ok_and(|u| browse::may_open(&u) && u.scheme() != "about"))
            .take(MAX_TABS)
            .enumerate()
            .map(|(i, k)| Tab::new(i as u64 + 1, k.url, k.title, blocker))
            .collect();
        let next = tabs.len() as u64 + 1;
        Tabs(Mutex::new(TabsInner { tabs, active: None, next, bounds: None, store: Some(store), fullscreen: None }))
    }
}

/// A tab as the window shows it.
#[derive(Clone, Serialize)]
pub struct TabView {
    id: u64,
    title: String,
    url: String,
    loading: bool,
}

/// Every tab, in order, and the one shown.
#[derive(Clone, Serialize)]
pub struct TabsView {
    tabs: Vec<TabView>,
    active: Option<u64>,
}

fn tabs(app: &AppHandle) -> MutexGuard<'_, TabsInner> {
    app.state::<Tabs>().inner().0.lock().unwrap_or_else(|e| e.into_inner())
}

/// A tab's webview's label.
fn label(id: u64) -> String {
    format!("page-{id}")
}

/// The tab whose webview is `page`.
fn tab_of(page: &Webview) -> Option<u64> {
    page.label().strip_prefix("page-")?.parse().ok()
}

/// The tab shown, and its page.
fn active_page(app: &AppHandle) -> Option<(u64, Webview)> {
    let id = tabs(app).active?;
    Some((id, app.get_webview(&label(id))?))
}

/// The page of the tab `id`, while the tab is open.
pub fn tab_page(app: &AppHandle, id: u64) -> Option<Webview> {
    tabs(app).tabs.iter().any(|t| t.id == id && t.opened).then(|| app.get_webview(&label(id)))?
}

/// The ad blocker of the tab `id`; None once it is closed.
pub fn tab_blocker(app: &AppHandle, id: u64) -> Option<Arc<AdBlocker>> {
    tabs(app).tabs.iter().find(|t| t.id == id).map(|t| t.blocker.clone())
}

/// Whether the tab `id` is the one shown.
pub fn is_active(app: &AppHandle, id: u64) -> bool {
    tabs(app).active == Some(id)
}

/// The ad blocker of the tab shown.
pub fn active_blocker(app: &AppHandle) -> Option<Arc<AdBlocker>> {
    let inner = tabs(app);
    let id = inner.active?;
    inner.tabs.iter().find(|t| t.id == id).map(|t| t.blocker.clone())
}

fn view(inner: &TabsInner) -> TabsView {
    TabsView {
        tabs: inner
            .tabs
            .iter()
            .map(|t| TabView { id: t.id, title: t.title.clone(), url: t.url.clone(), loading: t.loading })
            .collect(),
        active: inner.active,
    }
}

/// Tells the window how the tabs are now, and keeps them for the next run.
fn tabs_changed(app: &AppHandle) {
    let (view, store, kept) = {
        let inner = tabs(app);
        let kept: Vec<Kept> = inner
            .tabs
            .iter()
            .filter(|t| !t.url.is_empty() && !t.url.starts_with("about:"))
            .map(|t| Kept { url: t.url.clone(), title: t.title.clone() })
            .collect();
        (view(&inner), inner.store.clone(), kept)
    };
    let _ = app.emit("tabs", view);
    if let Some(store) = store {
        if let Ok(json) = serde_json::to_vec(&kept) {
            let saving = store.with_extension("json.saving");
            if std::fs::write(&saving, json).is_ok() {
                let _ = std::fs::rename(&saving, &store);
            }
        }
    }
}

/// Changes what the app knows of the tab `id`, then tells the window.
fn update_tab(app: &AppHandle, id: u64, change: impl FnOnce(&mut Tab)) {
    let found = {
        let mut inner = tabs(app);
        inner.tabs.iter_mut().find(|t| t.id == id).map(change).is_some()
    };
    if found {
        tabs_changed(app);
    }
}

#[tauri::command]
pub fn list_tabs(app: AppHandle) -> TabsView {
    view(&tabs(&app))
}

/// The browser's user agent, sent with what a page downloads as its sign-in is.
#[derive(Default)]
pub struct Agent(Mutex<Option<String>>);

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

/// What `call`, evaluated in the page's top document, returns: a string, or None when the page did not answer.
pub async fn ask(page: &Webview, call: String) -> Option<String> {
    let (tx, rx) = tokio::sync::oneshot::channel::<String>();
    let tx = Mutex::new(Some(tx));
    page.eval_with_callback(call, move |answer| {
        if let Some(tx) = tx.lock().unwrap_or_else(|e| e.into_inner()).take() {
            let _ = tx.send(answer);
        }
    })
    .ok()?;
    let answer = tokio::time::timeout(Duration::from_secs(3), rx).await.ok()?.ok()?;
    // The answer is the call's value written as JSON.
    serde_json::from_str::<String>(&answer).ok()
}

fn emit(app: &AppHandle, tab: u64, event: PageEvent) {
    let _ = app.emit("page", TabEvent { tab, event });
}

/// Opens what `text` names (an address, a site, an account, or words to search for): in the tab shown, or in a new
/// tab when `new_tab` asks for one or none is shown (from the window's home). `bounds` is where the page goes. Returns
/// the address opened, for the address bar.
#[tauri::command]
pub async fn open_page(
    text: String,
    bounds: Bounds,
    sign_in: Option<bool>,
    new_tab: Option<bool>,
    app: AppHandle,
) -> Result<String, String> {
    // A video's link is looked up, not opened; only to sign in to its site is its page opened.
    let link = links::recognise(&text);
    let address = if sign_in.unwrap_or(false) { browse::sign_in_page(&link) } else { browse::page_for(&link) };
    let address = address.ok_or("This is looked up, not opened.")?;
    let url = Url::parse(&address).map_err(|_| "This isn't an address Chaya can open.".to_string())?;
    if !browse::may_open(&url) {
        return Err("Chaya opens only web pages.".into());
    }
    tabs(&app).bounds = Some(bounds);
    match active_page(&app).filter(|_| !new_tab.unwrap_or(false)) {
        Some((_, page)) => {
            page.navigate(url).map_err(|_| "The page couldn't be opened.".to_string())?;
            let _ = page.show();
        }
        None => {
            new_tab_at(&app, url)?;
        }
    }
    Ok(address)
}

/// Opens `url` in a new tab and shows it.
fn new_tab_at(app: &AppHandle, url: Url) -> Result<u64, String> {
    let id = {
        let mut inner = tabs(app);
        if inner.tabs.len() >= MAX_TABS {
            return Err(format!("{MAX_TABS} tabs are open. Close one to open another."));
        }
        let id = inner.next;
        inner.next += 1;
        let blocker = app.state::<Ads>().blocker.clone();
        let tab = Tab::new(id, url.to_string(), String::new(), &blocker);
        // Beside the tab it came from, as browsers place a new tab; at the end from the home.
        let at =
            inner.active.and_then(|a| inner.tabs.iter().position(|t| t.id == a)).map_or(inner.tabs.len(), |i| i + 1);
        inner.tabs.insert(at, tab);
        id
    };
    show_tab(app, id)?;
    Ok(id)
}

/// Shows the tab `id` in the page's place, hiding the one shown before; its page is made the first time.
fn show_tab(app: &AppHandle, id: u64) -> Result<(), String> {
    if tabs(app).fullscreen.as_ref().is_some_and(|f| f.tab != id) {
        leave_fullscreen(app);
    }
    let (previous, opened, url, bounds) = {
        let mut inner = tabs(app);
        let previous = inner.active.replace(id).filter(|p| *p != id);
        let tab = inner.tabs.iter_mut().find(|t| t.id == id).ok_or("This tab is closed.")?;
        let opened = std::mem::replace(&mut tab.opened, true);
        (previous, opened, tab.url.clone(), inner.bounds)
    };
    if let Some(page) = previous.and_then(|p| app.get_webview(&label(p))) {
        let _ = page.hide();
    }
    if opened {
        if let Some(page) = app.get_webview(&label(id)) {
            if let Some(bounds) = bounds {
                let _ = page.set_position(position(bounds));
                let _ = page.set_size(size(bounds));
            }
            let _ = page.show();
        }
    } else if let Err(error) = make_page(app, id, &url, bounds) {
        update_tab(app, id, |t| t.opened = false);
        return Err(error);
    }
    tabs_changed(app);
    Ok(())
}

/// Makes the tab `id`'s page and sends it to `url`: blank first, so ad blocking is in place before it loads anything.
fn make_page(app: &AppHandle, id: u64, url: &str, bounds: Option<Bounds>) -> Result<(), String> {
    let url = Url::parse(url).map_err(|_| "This isn't an address Chaya can open.".to_string())?;
    let window = app.get_window("main").ok_or("The window is gone.")?;
    let ads = app.state::<Ads>();
    ads.start();
    let (key, blocker) = {
        let inner = tabs(app);
        let tab = inner.tabs.iter().find(|t| t.id == id).ok_or("This tab is closed.")?;
        (tab.key.clone(), tab.blocker.clone())
    };
    let hide_key = format!("__chaya_{}", random_word());
    let (events, titles, popups) = (app.clone(), app.clone(), app.clone());
    let (navigations, popup_blocker) = (blocker.clone(), blocker.clone());
    let blank = Url::parse("about:blank").expect("a valid address");
    let builder = WebviewBuilder::new(label(id), WebviewUrl::External(blank))
        .on_navigation(move |url| {
            let allowed = browse::may_open(url);
            if allowed {
                navigations.begin_page(url.as_str());
            }
            allowed
        })
        .initialization_script_for_all_frames(detector(&key, &random_word()))
        .initialization_script(ads::hider(&hide_key))
        .on_page_load(move |_, payload| {
            let url = payload.url().to_string();
            let started = payload.event() == PageLoadEvent::Started;
            let known = url.clone();
            update_tab(&events, id, move |t| {
                if started {
                    t.found.clear();
                }
                if !known.starts_with("about:") {
                    t.url = known;
                }
                t.loading = started;
            });
            emit(&events, id, if started { PageEvent::Loading { url } } else { PageEvent::Loaded { url } });
        })
        .on_document_title_changed(move |_, title| {
            let kept = title.clone();
            update_tab(&titles, id, move |t| t.title = kept);
            emit(&titles, id, PageEvent::Title { title });
        })
        .on_new_window(move |url, _| {
            // An ad's window is refused without asking; the shield counts it. Any other opens in a new tab, as a
            // link that opens a new window does in a browser (and on the phone); once the tabs are full, the window
            // asks instead.
            if browse::may_open(&url) && !popup_blocker.should_block_popup(url.as_str()) {
                let app = popups.clone();
                // Not from inside the page's own callback: the new tab's page is made on the window's thread.
                tauri::async_runtime::spawn(async move {
                    if new_tab_at(&app, url.clone()).is_err() {
                        emit(&app, id, PageEvent::Popup { url: url.to_string() });
                    }
                });
            }
            NewWindowResponse::Deny
        })
        .on_download(page_download);
    let bounds = bounds.unwrap_or(Bounds { x: 0.0, y: 0.0, width: 1.0, height: 1.0 });
    let page = window
        .add_child(builder, position(bounds), size(bounds))
        .map_err(|_| "The page couldn't be opened.".to_string())?;
    let _ = ads::block_requests(&page, blocker);
    let _ = page_input::attach(&page, app.clone(), id);
    page.navigate(url).map_err(|_| "The page couldn't be opened.".to_string())?;
    tauri::async_runtime::spawn(learn_agent(app.clone()));
    tauri::async_runtime::spawn(ads::hide_ads(app.clone(), hide_key, id));
    Ok(())
}

/// Shows the tab `id`, the page where `bounds` says.
#[tauri::command]
pub fn switch_tab(id: u64, bounds: Bounds, app: AppHandle) -> Result<(), String> {
    tabs(&app).bounds = Some(bounds);
    show_tab(&app, id)
}

/// Closes the tab `id`. Closing the tab shown shows the one beside it, or the window's home when it was the last.
#[tauri::command]
pub fn close_tab(id: u64, app: AppHandle) -> Result<(), String> {
    if tabs(&app).fullscreen.as_ref().is_some_and(|f| f.tab == id) {
        leave_fullscreen(&app);
    }
    let next = {
        let mut inner = tabs(&app);
        let Some(at) = inner.tabs.iter().position(|t| t.id == id) else { return Ok(()) };
        inner.tabs.remove(at);
        if inner.active == Some(id) {
            inner.active = None;
            inner.tabs.get(at).or_else(|| inner.tabs.last()).map(|t| t.id)
        } else {
            None
        }
    };
    if let Some(page) = app.get_webview(&label(id)) {
        let _ = page.close();
    }
    match next {
        Some(next) => show_tab(&app, next),
        None => {
            tabs_changed(&app);
            Ok(())
        }
    }
}

/// The window's own home: every tab stays open behind it, hidden.
#[tauri::command]
pub fn go_home(app: AppHandle) {
    leave_fullscreen(&app);
    let previous = tabs(&app).active.take();
    if let Some(page) = previous.and_then(|id| app.get_webview(&label(id))) {
        let _ = page.hide();
    }
    tabs_changed(&app);
}

/// Reads the browser's user agent from the window's own content, once: the page's could be told anything.
async fn learn_agent(app: AppHandle) {
    if app.state::<Agent>().0.lock().unwrap_or_else(|e| e.into_inner()).is_some() {
        return;
    }
    let Some(window) = app.get_webview("main") else { return };
    let agent = ask(&window, "navigator.userAgent".into()).await;
    if let Some(agent) = agent.filter(|a| (1..=512).contains(&a.len()) && session::is_header_safe(a)) {
        *app.state::<Agent>().0.lock().unwrap_or_else(|e| e.into_inner()) = Some(agent);
    }
}

/// The cookies the browser holds for `url`, as it would send them there. They may wait on the browser, so this is
/// never called on the window's thread.
fn browser_cookies(app: &AppHandle, url: &Url) -> Vec<SessionCookie> {
    // One cookie store serves every webview of the app, so the window's own reads it when no page is open.
    let page = app.webviews().into_iter().find(|(label, _)| label.starts_with("page-")).map(|(_, page)| page);
    let Some(webview) = page.or_else(|| app.get_webview("main")) else { return Vec::new() };
    webview
        .cookies_for_url(url.clone())
        .unwrap_or_default()
        .iter()
        .map(|c| SessionCookie {
            name: c.name().to_string(),
            value: c.value().to_string(),
            // For the address's own host: these are the cookies the browser sends there, and no further.
            domain: None,
            path: None,
            secure: c.secure().unwrap_or(false),
            http_only: c.http_only().unwrap_or(false),
            expires: c.expires_datetime().map(|t| t.unix_timestamp()),
        })
        .collect()
}

/// The person's sign-in on `platform`, as the browser holds it: the cookies of each of its sites, for the site
/// and its subdomains, as the phone reads them (`WebViewSignIn`). It may wait on the browser, so it runs off the
/// window's thread.
pub async fn site_sign_in(app: &AppHandle, platform: Platform) -> Vec<SessionCookie> {
    let app = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        session::sign_in_sites(platform)
            .iter()
            .filter_map(|site| Url::parse(&format!("https://www.{site}/")).ok().map(|url| (site, url)))
            .flat_map(|(site, url)| {
                browser_cookies(&app, &url)
                    .into_iter()
                    .map(|c| SessionCookie { domain: Some(site.to_string()), ..c })
                    .collect::<Vec<_>>()
            })
            .collect()
    })
    .await
    .unwrap_or_default()
}

/// The browser's sign-in for `url`, for a download saved from a page (`Source::session`): its cookies there, and
/// its user agent. Read each time the download starts; nothing of it is kept.
pub fn session_headers(app: &AppHandle, url: &str) -> Vec<(String, String)> {
    let Ok(url) = Url::parse(url) else { return Vec::new() };
    if !matches!(url.scheme(), "http" | "https") {
        return Vec::new();
    }
    let mut headers = Vec::new();
    if let Some(cookies) = session::cookie_header(&browser_cookies(app, &url)) {
        headers.push(("Cookie".to_string(), cookies));
    }
    if let Some(agent) = app.state::<Agent>().0.lock().unwrap_or_else(|e| e.into_inner()).clone() {
        headers.push(("User-Agent".to_string(), agent));
    }
    headers
}

/// The browser's sign-in for a stream found on the page, in a file for yt-dlp that is deleted when the lookup ends;
/// None when it has none there.
async fn stream_sign_in(app: &AppHandle, url: &str) -> Option<CookieFile> {
    let url = Url::parse(url).ok().filter(|u| matches!(u.scheme(), "http" | "https"))?;
    let dir = app.path().app_cache_dir().ok()?.join("sign-in");
    let app = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let host = url.host_str().unwrap_or_default().to_string();
        CookieFile::write(&dir, &browser_cookies(&app, &url), &host).ok().flatten()
    })
    .await
    .ok()
    .flatten()
}

/// A download the page started: Chaya's own when it is media (`browse::page_download`), else the browser's.
fn page_download(page: Webview, event: DownloadEvent<'_>) -> bool {
    let DownloadEvent::Requested { url, .. } = event else { return true };
    let app = page.app_handle();
    let page_url = page.url().map(|u| u.to_string()).unwrap_or_default();
    let id = tab_of(&page);
    let title =
        id.and_then(|id| tabs(app).tabs.iter().find(|t| t.id == id).map(|t| t.title.clone())).unwrap_or_default();
    let downloads = app.state::<Downloads>();
    match browse::page_download(url.as_str(), &page_url, &title, &downloads.folder, downloads.joins) {
        Some(new) => {
            let task = downloads.manager.add(new);
            let name = task.path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            emit(app, id.unwrap_or_default(), PageEvent::Saving { name });
            // The browser's own download is cancelled: Chaya's list has it.
            false
        }
        None => true,
    }
}

/// Moves the pages to where the window now leaves room for them: every open tab's, so a tab shown later is in place.
#[tauri::command]
pub fn place_page(bounds: Bounds, app: AppHandle) {
    let (open, fullscreen) = {
        let mut inner = tabs(&app);
        inner.bounds = Some(bounds);
        let open: Vec<u64> = inner.tabs.iter().filter(|t| t.opened).map(|t| t.id).collect();
        (open, inner.fullscreen.as_ref().map(|f| f.tab))
    };
    // The window's layout changes as it goes fullscreen; the fullscreen page keeps covering all of it.
    let window = window_bounds(&app);
    for id in open {
        if let Some(page) = app.get_webview(&label(id)) {
            let bounds = page_bounds(id, fullscreen, bounds, window);
            let _ = page.set_position(position(bounds));
            let _ = page.set_size(size(bounds));
        }
    }
}

/// Where the tab `id`'s page goes: where the window leaves room for it (`bounds`), or, while it shows something
/// fullscreen, the whole `window`.
fn page_bounds(id: u64, fullscreen: Option<u64>, bounds: Bounds, window: Option<Bounds>) -> Bounds {
    match window {
        Some(window) if fullscreen == Some(id) => window,
        _ => bounds,
    }
}

/// The window's inside, from its top left corner, in the logical points the page is placed in.
fn window_bounds(app: &AppHandle) -> Option<Bounds> {
    let window = app.get_window("main")?;
    let inner = window.inner_size().ok()?.to_logical::<f64>(window.scale_factor().ok()?);
    Some(Bounds { x: 0.0, y: 0.0, width: inner.width, height: inner.height })
}

/// The tab `tab`'s page shows something fullscreen (`on`), or no longer does. Only the tab shown may take the window.
#[cfg_attr(not(windows), allow(dead_code))]
fn page_fullscreen(app: &AppHandle, tab: u64, on: bool) {
    if !on {
        if tabs(app).fullscreen.as_ref().is_some_and(|f| f.tab == tab) {
            leave_fullscreen(app);
        }
        return;
    }
    let Some(window) = app.get_window("main") else { return };
    if !is_active(app, tab) {
        return;
    }
    let window_was = window.is_fullscreen().unwrap_or(false);
    tabs(app).fullscreen.get_or_insert(Fullscreen { tab, window_was });
    let _ = window.set_fullscreen(true);
    if let (Some(page), Some(bounds)) = (app.get_webview(&label(tab)), window_bounds(app)) {
        let _ = page.set_position(position(bounds));
        let _ = page.set_size(size(bounds));
    }
}

/// The window and the page as they were before a page went fullscreen; the page is asked to leave fullscreen too,
/// for when it is shown again.
fn leave_fullscreen(app: &AppHandle) {
    let (left, bounds) = {
        let mut inner = tabs(app);
        (inner.fullscreen.take(), inner.bounds)
    };
    let Some(left) = left else { return };
    if let Some(window) = app.get_window("main") {
        if !left.window_was {
            let _ = window.set_fullscreen(false);
        }
    }
    if let Some(page) = app.get_webview(&label(left.tab)) {
        let _ = page.eval("document.fullscreenElement && document.exitFullscreen()");
        if let Some(bounds) = bounds {
            let _ = page.set_position(position(bounds));
            let _ = page.set_size(size(bounds));
        }
    }
}

/// Shows or hides the tab shown's page, while the window shows something in its place (the downloads, a panel).
#[tauri::command]
pub fn show_page(visible: bool, app: AppHandle) {
    if let Some((_, page)) = active_page(&app) {
        let _ = if visible { page.show() } else { page.hide() };
    }
}

/// A still picture of the page as it is, as a `data:` address (a JPEG), for the window to show in the page's place
/// while a panel lies over it: the page is drawn over the window, so nothing the window draws can lie over the page
/// itself. None when there is no page, or off Windows.
#[tauri::command]
pub async fn page_picture(app: AppHandle) -> Option<String> {
    let (_, page) = active_page(&app)?;
    picture::take(&page).await
}

#[cfg(windows)]
mod picture {
    use std::time::Duration;

    use base64::Engine;
    use tokio::sync::oneshot;
    use webview2_com::CapturePreviewCompletedHandler;
    use webview2_com::Microsoft::Web::WebView2::Win32::*;
    use windows::Win32::Foundation::E_OUTOFMEMORY;
    use windows::Win32::System::Com::{IStream, STATFLAG_NONAME, STATSTG, STREAM_SEEK_SET};
    use windows::Win32::UI::Shell::SHCreateMemStream;

    pub async fn take(page: &tauri::Webview) -> Option<String> {
        let (tx, rx) = oneshot::channel();
        page.with_webview(move |webview| {
            if let Err(e) = capture(&webview.controller(), tx) {
                eprintln!("Chaya: the page's picture could not be taken: {e}");
            }
        })
        .ok()?;
        let jpeg = tokio::time::timeout(Duration::from_secs(2), rx).await.ok()?.ok()??;
        Some(format!("data:image/jpeg;base64,{}", base64::engine::general_purpose::STANDARD.encode(jpeg)))
    }

    fn capture(controller: &ICoreWebView2Controller, tx: oneshot::Sender<Option<Vec<u8>>>) -> windows_core::Result<()> {
        // SAFETY: called on the window's thread (`with_webview`), on the page's own live webview; the stream is kept
        // alive by the handler until WebView2 has written the picture into it.
        unsafe {
            let stream = SHCreateMemStream(None).ok_or_else(|| windows_core::Error::from(E_OUTOFMEMORY))?;
            let written = stream.clone();
            let handler = CapturePreviewCompletedHandler::create(Box::new(move |result| {
                let _ = tx.send(result.ok().and_then(|()| read_all(&written).ok()));
                Ok(())
            }));
            controller.CoreWebView2()?.CapturePreview(COREWEBVIEW2_CAPTURE_PREVIEW_IMAGE_FORMAT_JPEG, &stream, &handler)
        }
    }

    /// Everything written into `stream`.
    unsafe fn read_all(stream: &IStream) -> windows_core::Result<Vec<u8>> {
        // SAFETY: `stream` is a live memory stream; the buffer is as long as the length given with it.
        unsafe {
            let mut stat = STATSTG::default();
            stream.Stat(&mut stat, STATFLAG_NONAME)?;
            stream.Seek(0, STREAM_SEEK_SET, None)?;
            let mut bytes = vec![0u8; usize::try_from(stat.cbSize).unwrap_or(0)];
            let mut read = 0u32;
            stream
                .Read(bytes.as_mut_ptr().cast(), u32::try_from(bytes.len()).unwrap_or(u32::MAX), Some(&mut read))
                .ok()?;
            bytes.truncate(read as usize);
            Ok(bytes)
        }
    }
}

#[cfg(not(windows))]
mod picture {
    /// WebKitGTK's page is not pictured; the window shows its ground in the page's place instead.
    pub async fn take(_page: &tauri::Webview) -> Option<String> {
        None
    }
}

/// What a tab key pressed with the focus in a page does, for the window to act on as if pressed in its own bar
/// (`tab-key`): "new-tab" (Ctrl+T), "close-tab" (Ctrl+W), "next-tab" and "previous-tab" (Ctrl+Tab, Ctrl+Shift+Tab),
/// "tab-1" to "tab-9" (Ctrl+1 to 9), "address" (Ctrl+L). None for every other key, which stays the page's. `key` is
/// Windows' virtual key; Ctrl with Alt is AltGr, which types a character.
#[cfg_attr(not(windows), allow(dead_code))]
fn tab_key(key: u32, ctrl: bool, shift: bool, alt: bool) -> Option<String> {
    if !ctrl || alt {
        return None;
    }
    let name = match (key, shift) {
        (0x54, false) => "new-tab".to_string(),
        (0x57, false) => "close-tab".to_string(),
        (0x09, false) => "next-tab".to_string(),
        (0x09, true) => "previous-tab".to_string(),
        (0x31..=0x39, false) => format!("tab-{}", key - 0x30),
        (0x4C, false) => "address".to_string(),
        _ => return None,
    };
    Some(name)
}

/// The tab keys pressed in a page go to the window: WebView2 keeps the keys pressed in a page for the page, so
/// otherwise Ctrl+W, Ctrl+T and Ctrl+Tab would work only while the focus is in Chaya's own bar. And a page's
/// `app-region: drag` is ignored (wry turns it on for every webview): only Chaya's own title bar moves the window.
#[cfg(windows)]
mod page_input {
    use tauri::{AppHandle, Emitter, Manager};
    use webview2_com::Microsoft::Web::WebView2::Win32::*;
    use webview2_com::{AcceleratorKeyPressedEventHandler, ContainsFullScreenElementChangedEventHandler};
    use windows::Win32::UI::Input::KeyboardAndMouse::{GetKeyState, VIRTUAL_KEY, VK_CONTROL, VK_MENU, VK_SHIFT};
    use windows_core::Interface;

    pub fn attach(page: &tauri::Webview, app: AppHandle, tab: u64) -> tauri::Result<()> {
        page.with_webview(move |webview| {
            let controller = webview.controller();
            if let Err(e) = no_drag(&controller) {
                eprintln!("Chaya: a page could still drag the window: {e}");
            }
            if let Err(e) = watch_fullscreen(&controller, app.clone(), tab) {
                eprintln!("Chaya: a page's fullscreen could not be followed: {e}");
            }
            if let Err(e) = forward_keys(&controller, app) {
                eprintln!("Chaya: the page's tab keys could not be set up: {e}");
            }
        })
    }

    /// A page's fullscreen (a video's own button) takes the whole window, as in any browser; left to WebView2, it
    /// would fill only the page's place under the bar.
    fn watch_fullscreen(controller: &ICoreWebView2Controller, app: AppHandle, tab: u64) -> windows_core::Result<()> {
        let handler = ContainsFullScreenElementChangedEventHandler::create(Box::new(move |sender, _| {
            let Some(webview) = sender else { return Ok(()) };
            let mut on = windows_core::BOOL::default();
            // SAFETY: WebView2 calls this on the window's thread with the page's live webview.
            unsafe { webview.ContainsFullScreenElement(&mut on)? };
            let (app, on) = (app.clone(), on.as_bool());
            // Off the window's thread: the window's size is asked for, which that thread answers.
            tauri::async_runtime::spawn(async move { super::page_fullscreen(&app, tab, on) });
            Ok(())
        }));
        let mut token = 0i64;
        // SAFETY: called on the window's thread (`with_webview`), on the page's own live webview.
        unsafe { controller.CoreWebView2()?.add_ContainsFullScreenElementChanged(&handler, &mut token) }
    }

    /// Takes effect from the page's next navigation: the page is made on about:blank and sent on after this.
    fn no_drag(controller: &ICoreWebView2Controller) -> windows_core::Result<()> {
        // SAFETY: called on the window's thread (`with_webview`), on the page's own live webview.
        unsafe {
            controller
                .CoreWebView2()?
                .Settings()?
                .cast::<ICoreWebView2Settings9>()?
                .SetIsNonClientRegionSupportEnabled(false)
        }
    }

    fn forward_keys(controller: &ICoreWebView2Controller, app: AppHandle) -> windows_core::Result<()> {
        let handler = AcceleratorKeyPressedEventHandler::create(Box::new(move |_, args| {
            let Some(args) = args else { return Ok(()) };
            // SAFETY: WebView2 calls this on the window's thread with live arguments, as its documentation says.
            unsafe {
                let mut kind = COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN;
                args.KeyEventKind(&mut kind)?;
                if kind != COREWEBVIEW2_KEY_EVENT_KIND_KEY_DOWN {
                    return Ok(());
                }
                let mut key = 0u32;
                args.VirtualKey(&mut key)?;
                let down = |k: VIRTUAL_KEY| GetKeyState(i32::from(k.0)) < 0;
                if let Some(name) = super::tab_key(key, down(VK_CONTROL), down(VK_SHIFT), down(VK_MENU)) {
                    args.SetHandled(true)?;
                    // A key held down repeats; one press closes one tab.
                    let mut status = COREWEBVIEW2_PHYSICAL_KEY_STATUS::default();
                    args.PhysicalKeyStatus(&mut status)?;
                    if !status.WasKeyDown.as_bool() {
                        // The bar's address takes typing next: the keyboard goes to Chaya's own webview first.
                        if name == "address" || name == "new-tab" {
                            if let Some(main) = app.get_webview("main") {
                                tauri::async_runtime::spawn(async move { main.set_focus() });
                            }
                        }
                        let _ = app.emit_to("main", "tab-key", name);
                    }
                }
            }
            Ok(())
        }));
        let mut token = 0i64;
        // SAFETY: called on the window's thread (`with_webview`), on the page's own live webview.
        unsafe { controller.add_AcceleratorKeyPressed(&handler, &mut token) }
    }
}

#[cfg(not(windows))]
mod page_input {
    /// WebKitGTK passes the keys a page leaves alone on to the window, which has its own, and has no `app-region`.
    pub fn attach(_page: &tauri::Webview, _app: tauri::AppHandle, _tab: u64) -> tauri::Result<()> {
        Ok(())
    }
}

/// "back", "forward", "reload" or "stop".
#[tauri::command]
pub fn page_action(action: String, app: AppHandle) -> Result<(), String> {
    let (_, page) = active_page(&app).ok_or("No page is open.")?;
    let result = match action.as_str() {
        "back" => page.eval("history.back()"),
        "forward" => page.eval("history.forward()"),
        "reload" => page.reload(),
        "stop" => page.eval("window.stop()"),
        _ => return Err(format!("Unknown action: {action}")),
    };
    result.map_err(|_| "The page didn't respond.".into())
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
/// YouTube does, loads nothing new), what its players have loaded so far, the main video first, and how many ads
/// were blocked on it.
#[derive(Clone, Serialize)]
pub struct PageMedia {
    url: String,
    items: Vec<FoundView>,
    ads: Shield,
}

#[tauri::command]
pub async fn page_media(app: AppHandle) -> Result<PageMedia, String> {
    let (id, page) = active_page(&app).ok_or("No page is open.")?;
    let (key, blocker) = {
        let inner = tabs(&app);
        let tab = inner.tabs.iter().find(|t| t.id == id).ok_or("No page is open.")?;
        (tab.key.clone(), tab.blocker.clone())
    };
    // The page's own document runs this; a page whose detector is missing (a page that failed to load) has none.
    let call =
        format!("(function () {{ var read = window['{key}']; return typeof read === 'function' ? read() : ''; }})()");
    let answer = ask(&page, call).await.ok_or("The page didn't respond.")?;
    // The page's word is checked by `found`.
    let mut report: Report = Some(answer)
        .filter(|text| text.len() <= 2 * 1024 * 1024)
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default();
    // An ad the blocker stops is never offered, even where its address was seen.
    report.items.retain(|seen| !blocker.blocks(&seen.url, kind::MEDIA));
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
    if let Some(tab) = tabs(&app).tabs.iter_mut().find(|t| t.id == id) {
        tab.found = list;
    }
    Ok(PageMedia { url: page_url, items: views, ads: blocker.shield() })
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
    let sign_in = stream_sign_in(&app, &item.url).await;
    Ok(match lookups.stream_media(item.url.clone(), item.referer.clone(), sign_in).await {
        Ok(media) => Lookup::from_media(&named(media, &item), |f| downloads.can_join(f), downloads.joins),
        Err(failure) => Lookup::from_failure(&failure),
    })
}

/// Saves `choice` ("720p", "Audio only") of a stream found on the page, as `look_up_found` offered it.
#[tauri::command]
pub async fn download_found(url: String, choice: String, app: AppHandle) -> Result<Vec<crate::TaskView>, String> {
    let item = found_stream(&app, &url).ok_or("This is no longer on the page.")?;
    let page_url = active_page(&app).and_then(|(_, p)| p.url().ok()).map(|u| u.to_string()).unwrap_or_default();
    let lookups = app.state::<Lookups>();
    let downloads = app.state::<Downloads>();
    let sign_in = stream_sign_in(&app, &item.url).await;
    let media =
        lookups.stream_media(item.url.clone(), item.referer.clone(), sign_in).await.map_err(|f| f.kind.message())?;
    let mut plans =
        plan(&named(media, &item), &choice, &page_url, &downloads.folder, |f| downloads.can_join(f), downloads.joins)
            .ok_or("This choice can't be saved any more. Look it up again.")?;
    // The stream is fetched as the page's player fetched it: with the browser's sign-in for its server.
    for new in &mut plans {
        new.source.session = true;
        if let Some(sound) = &mut new.sound {
            sound.session = true;
        }
    }
    Ok(plans.into_iter().map(|p| crate::TaskView::from(&downloads.manager.add(p))).collect())
}

/// What was last found on the tab shown at `url` that `wanted` accepts.
fn found_on_page(app: &AppHandle, url: &str, wanted: impl Fn(&Found) -> bool) -> Option<Found> {
    let inner = tabs(app);
    let id = inner.active?;
    inner.tabs.iter().find(|t| t.id == id)?.found.iter().find(|f| f.url == url && wanted(f)).cloned()
}

/// The stream at `url` in what the page was last found to hold.
fn found_stream(app: &AppHandle, url: &str) -> Option<Found> {
    found_on_page(app, url, |f| matches!(f.kind, FoundKind::Stream | FoundKind::Dash))
}

/// A stream's own address names nothing ("manifest", "index"): it is called what the page calls it.
fn named(mut media: Media, item: &Found) -> Media {
    media.title = item.name.clone();
    media
}

/// Saves what the window listed at `url` on the page.
#[tauri::command]
pub fn save_found(url: String, app: AppHandle) -> Result<Vec<crate::TaskView>, String> {
    let item = found_on_page(&app, &url, |_| true).ok_or("This is no longer on the page.")?;
    let page_url = active_page(&app).and_then(|(_, p)| p.url().ok()).map(|u| u.to_string()).unwrap_or_default();
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
    fn a_page_showing_something_fullscreen_covers_the_window_and_the_others_keep_their_place() {
        let bar = Bounds { x: 0.0, y: 99.0, width: 1200.0, height: 700.0 };
        let window = Bounds { x: 0.0, y: 0.0, width: 1920.0, height: 1080.0 };
        assert_eq!(page_bounds(2, Some(2), bar, Some(window)), window);
        assert_eq!(page_bounds(1, Some(2), bar, Some(window)), bar, "another tab, hidden behind it");
        assert_eq!(page_bounds(2, None, bar, Some(window)), bar, "left fullscreen");
        assert_eq!(page_bounds(2, Some(2), bar, None), bar, "the window's size unknown");
    }

    #[test]
    fn the_tab_keys_pressed_in_a_page_go_to_the_window_and_no_other_key_does() {
        let ctrl = |key, shift| tab_key(key, true, shift, false);
        assert_eq!(ctrl(0x54, false).as_deref(), Some("new-tab"));
        assert_eq!(ctrl(0x57, false).as_deref(), Some("close-tab"));
        assert_eq!(ctrl(0x09, false).as_deref(), Some("next-tab"));
        assert_eq!(ctrl(0x09, true).as_deref(), Some("previous-tab"));
        assert_eq!(ctrl(0x31, false).as_deref(), Some("tab-1"));
        assert_eq!(ctrl(0x39, false).as_deref(), Some("tab-9"));
        assert_eq!(ctrl(0x4C, false).as_deref(), Some("address"));
        // The page's own: Ctrl+C, Ctrl+0 (its zoom), Ctrl+Shift+T, a W typed, and AltGr+W (Ctrl+Alt), which types.
        assert_eq!(ctrl(0x43, false), None);
        assert_eq!(ctrl(0x30, false), None);
        assert_eq!(ctrl(0x54, true), None);
        assert_eq!(tab_key(0x57, false, false, false), None);
        assert_eq!(tab_key(0x57, true, false, true), None);
    }

    #[test]
    fn the_detector_carries_this_pages_names_and_nothing_left_to_fill() {
        let script = detector("__chaya_key1", "nonce1");
        assert!(script.contains("'__chaya_key1'") && script.contains("'nonce1'"));
        assert!(!script.contains("__CHAYA_KEY__") && !script.contains("__CHAYA_NONCE__"));
    }
}
