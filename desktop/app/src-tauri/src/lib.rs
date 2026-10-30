//! Chaya for Windows: the Tauri app. The window is web content built from `desktop/design`; what
//! the app knows lives in `chaya-core`, and this crate only hands it to the window.

mod ads;
mod browser;
mod speech;
mod update;
mod window;

use std::path::PathBuf;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use chaya_core::download::archive::{Account, ListRequest, Lister};
use chaya_core::download::error::DownloadError;
use chaya_core::download::join::Joiner;
use chaya_core::download::manager::{Event, Fresh, Manager as DownloadManager, NewArchive, TaskView};
use chaya_core::download::plan::plan;
use chaya_core::engine::{AnswerCache, Engine};
use chaya_core::library;
use chaya_core::links::{self, Link};
use chaya_core::lookup::{Lookup, SignIn};
use chaya_core::media::{Failure, FailureKind, Format, Media};
use chaya_core::session::{self, CookieFile};
use chaya_core::subtitles::{self, Track};
use chaya_core::video::Videos;
use chaya_core::{serve, video};
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, State};
use tauri_plugin_opener::OpenerExt;

/// What the address bar holds: a video or post, an account, another page, or words.
#[tauri::command]
fn recognise_link(text: String) -> Link {
    links::recognise(&text)
}

struct Lookups {
    engine: Engine,
    /// One lookup at a time: each is a Python process, and a second would only compete with the first.
    turn: Arc<Mutex<()>>,
    answers: Arc<Mutex<AnswerCache>>,
}

impl Lookups {
    /// What the engine found at `url`: a kept answer when there is one and `fresh` is false.
    async fn media(&self, url: String, fresh: bool) -> Result<Media, Failure> {
        self.answer(url.clone(), fresh, move |engine| engine.look_up(&url)).await
    }

    /// What the engine found at the address of a stream found on the page at `referer`, kept as `url`'s answer.
    /// What the engine found at `url` with the person's sign-in for its site, which they chose; the cookie file is
    /// deleted as soon as the lookup ends. The answer is kept as `url`'s, for the download that follows.
    async fn signed_in_media(&self, url: String, cookies: CookieFile) -> Result<Media, Failure> {
        self.answer(url.clone(), true, move |engine| {
            let media = engine.look_up_signed_in(&url, cookies.path());
            drop(cookies);
            media
        })
        .await
    }

    /// With `cookies`, the browser's sign-in for the stream (`session::CookieFile`), kept until this returns.
    async fn stream_media(&self, url: String, referer: String, cookies: Option<CookieFile>) -> Result<Media, Failure> {
        let key = url.clone();
        self.answer(key, false, move |engine| {
            let media = engine.look_up_stream(&url, &referer, cookies.as_ref().map(CookieFile::path));
            // The file is deleted here, as soon as the lookup ends, whether it worked or not.
            drop(cookies);
            media
        })
        .await
    }

    async fn answer(
        &self,
        url: String,
        fresh: bool,
        look: impl FnOnce(&Engine) -> Result<Media, Failure> + Send + 'static,
    ) -> Result<Media, Failure> {
        let (engine, turn, answers) = (self.engine.clone(), self.turn.clone(), self.answers.clone());
        // The engine blocks while it runs, so it gets a thread of its own and the window stays responsive.
        tauri::async_runtime::spawn_blocking(move || {
            let _turn = turn.lock().unwrap_or_else(PoisonError::into_inner);
            // Inside the turn, so asking twice for one link waits for the first answer instead of looking twice.
            if !fresh {
                if let Some(media) = answers.lock().unwrap_or_else(PoisonError::into_inner).get(&url, Instant::now()) {
                    return Ok(media);
                }
            }
            let media = look(&engine)?;
            answers.lock().unwrap_or_else(PoisonError::into_inner).put(&url, media.clone(), Instant::now());
            Ok(media)
        })
        .await
        .unwrap_or(Err(Failure { kind: FailureKind::Engine, detail: None }))
    }
}

/// Looks up a link to a video or post on YouTube, Instagram, TikTok or X, or a Pinterest pin, with the bundled
/// engine. Only such links are looked up here; a stream found on a page is looked up by `browser::look_up_found`.
/// Nothing else reaches yt-dlp.
///
/// `use_sign_in`: the person tapped *Use my sign-in*, so the engine gets the browser's sign-in for the site, in a
/// file deleted when the lookup ends. Never without that tap: sites can limit accounts used to download. When a
/// lookup fails for want of a sign-in, the answer says what the person's could do (`SignIn`).
#[tauri::command]
async fn look_up(
    url: String,
    use_sign_in: Option<bool>,
    app: AppHandle,
    lookups: State<'_, Lookups>,
    downloads: State<'_, Downloads>,
) -> Result<Lookup, ()> {
    let Some(link) = links::match_media(&url) else {
        return Ok(Lookup::failed(FailureKind::Unsupported));
    };
    let use_sign_in = use_sign_in.unwrap_or(false);
    let answer = if use_sign_in {
        let cookies = browser::site_sign_in(&app, link.platform).await;
        // Signed out since the offer was made: the site asks for a sign-in again.
        let file = session::is_signed_in(link.platform, &cookies)
            .then(|| app.path().app_cache_dir().ok())
            .flatten()
            .and_then(|dir| CookieFile::write(&dir.join("sign-in"), &cookies, "").ok().flatten());
        match file {
            Some(file) => lookups.signed_in_media(link.url, file).await,
            None => Err(Failure { kind: FailureKind::NeedsLogin, detail: None }),
        }
    } else {
        lookups.media(link.url, false).await
    };
    Ok(match answer {
        Ok(media) => Lookup::from_media(&media, |f| downloads.can_join(f), downloads.joins),
        Err(failure) => {
            let wants_sign_in = matches!(failure.kind, FailureKind::NeedsLogin | FailureKind::BotCheck);
            let signed_in = wants_sign_in
                && session::is_signed_in(link.platform, &browser::site_sign_in(&app, link.platform).await);
            Lookup::failed_with(&failure, SignIn::after(link.platform, failure.kind, use_sign_in, signed_in))
        }
    })
}

struct Downloads {
    manager: DownloadManager,
    /// Where files are saved: the person's Downloads folder.
    folder: PathBuf,
    /// Whether the app carries the ffmpeg that joins picture and sound, and turns a stream into an MP4: what
    /// lets it offer those qualities.
    joins: bool,
}

impl Downloads {
    /// Which picture-only tracks can be joined to sound: all of them (they are MP4s, `formats` sees to that)
    /// when the app carries ffmpeg, none without it. Lookups and downloads both ask here, so what the window
    /// offers is always what a download can save.
    fn can_join(&self, _: &Format) -> bool {
        self.joins
    }
}

/// Saves `choice` ("720p", "Audio only", or "post" for every picture and video in a post) from the link at
/// `url`, which the window looked up a moment ago.
#[tauri::command]
async fn download(
    url: String,
    choice: String,
    lookups: State<'_, Lookups>,
    downloads: State<'_, Downloads>,
) -> Result<Vec<TaskView>, String> {
    let link = links::match_media(&url).ok_or_else(|| FailureKind::Unsupported.message().to_string())?;
    let media = lookups.media(link.url.clone(), false).await.map_err(|f| f.kind.message().to_string())?;
    let plans = plan(&media, &choice, &link.url, &downloads.folder, |f| downloads.can_join(f), downloads.joins)
        .ok_or_else(|| "This choice can't be saved any more. Look the link up again.".to_string())?;
    Ok(plans.into_iter().map(|p| TaskView::from(&downloads.manager.add(p))).collect())
}

/// Whether the browser holds the person's sign-in for the account's site at `url`, for the account card to offer
/// saving with it.
#[tauri::command]
async fn account_signed_in(url: String, app: AppHandle) -> bool {
    let Some(link) = links::match_account(&url) else { return false };
    session::is_signed_in(link.platform, &browser::site_sign_in(&app, link.platform).await)
}

/// Saves everything the account at `url` has posted as one ZIP in Downloads, listed with the person's sign-in only
/// when they chose it (`use_sign_in`).
#[tauri::command]
fn save_account(url: String, use_sign_in: bool, downloads: State<'_, Downloads>) -> Result<Vec<TaskView>, String> {
    let link = links::match_account(&url).ok_or("This isn't an account Chaya can save.")?;
    let site = link.platform.display_name();
    let task = downloads.manager.add_archive(NewArchive {
        title: format!("@{} · {site} posts", link.username),
        file_name: format!("{} ({site}).zip", link.username),
        account: Account { platform: link.platform, username: link.username, sign_in: use_sign_in },
        page_url: link.url,
        folder: downloads.folder.clone(),
    });
    Ok(vec![TaskView::from(&task)])
}

/// Lists an account with the engine's `profile` command. The person's sign-in, when they chose it, is read from the
/// browser as it is now and written to a cookie file that is deleted as soon as the listing ends.
fn account_lister(app: AppHandle, engine: Engine) -> Lister {
    Arc::new(move |request: &ListRequest| {
        let cookies = if request.account.sign_in {
            let platform = request.account.platform;
            // Called off the runtime's threads, so it may wait for the browser here.
            let cookies = tauri::async_runtime::block_on(browser::site_sign_in(&app, platform));
            if !session::is_signed_in(platform, &cookies) {
                return Err(DownloadError::SignInAgain(platform));
            }
            let dir = app.path().app_cache_dir().map_err(|_| DownloadError::CantWrite)?.join("sign-in");
            let file = CookieFile::write(&dir, &cookies, "").map_err(|e| DownloadError::from_io(&e))?;
            Some(file.ok_or(DownloadError::SignInAgain(platform))?)
        } else {
            None
        };
        let listed = engine.list_profile(request, cookies.as_ref().map(CookieFile::path));
        drop(cookies);
        listed
    })
}

#[tauri::command]
fn list_downloads(downloads: State<'_, Downloads>) -> Vec<TaskView> {
    downloads.manager.list().iter().map(TaskView::from).collect()
}

/// The grid's tiles, as the phone's library: the saved downloads among `kept` (what the window's narrowing keeps),
/// newest first, a post's files on one tile.
#[tauri::command]
fn library_tiles(kept: Vec<u64>, downloads: State<'_, Downloads>) -> Vec<library::Tile> {
    let dated: Vec<library::Dated> = downloads
        .manager
        .list()
        .iter()
        .filter(|task| kept.contains(&task.id))
        .map(|task| library::Dated::from(&TaskView::from(task)))
        .collect();
    library::tiles(&dated)
}

/// `chaya-file://localhost/ID`: the saved picture, video or sound of download ID, for the grid, the viewer and the
/// player. Only Chaya's own window may ask (never a page in the browser, whose webviews share the scheme), only by a
/// download's number (never a path), and only for a saved picture, video or sound. A video or sound comes in the
/// parts the player asks for (`serve`), so it seeks without being read whole.
fn saved_file(app: &AppHandle, from: &str, request: &tauri::http::Request<Vec<u8>>) -> tauri::http::Response<Vec<u8>> {
    let status = |code: u16| tauri::http::Response::builder().status(code).body(Vec::new()).unwrap_or_default();
    if from != "main" {
        return status(403);
    }
    let path = request.uri().path().trim_matches('/');
    // A saved video's poster, which the window made from it ("poster-12": convertFileSrc would encode a slash).
    if let Some(id) = path.strip_prefix("poster-").and_then(|id| id.parse().ok()) {
        return match std::fs::read(app.state::<Videos>().poster(id)) {
            Ok(bytes) => tauri::http::Response::builder()
                .header("Content-Type", "image/jpeg")
                .header("Cache-Control", "no-cache")
                .body(bytes)
                .unwrap_or_default(),
            Err(_) => status(404),
        };
    }
    let id = path.parse().ok();
    let Some(task) = id.and_then(|id| app.state::<Downloads>().manager.get(id)) else {
        return status(404);
    };
    if task.state != chaya_core::download::manager::State::Completed {
        return status(404);
    }
    let name = task.path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    if let Some(kind) = library::picture_type(&name) {
        return match std::fs::read(&task.path) {
            Ok(bytes) => tauri::http::Response::builder()
                .header("Content-Type", kind)
                .header("Cache-Control", "max-age=3600")
                .body(bytes)
                .unwrap_or_default(),
            Err(_) => status(404),
        };
    }
    let Some(kind) = serve::media_type(&name) else { return status(404) };
    let range = request.headers().get("range").and_then(|r| r.to_str().ok());
    let answer = serve::answer(&task.path, kind, range);
    // The window takes a saved video's poster from it with a canvas, which a video from another origin would leave
    // unreadable; only Chaya's own window gets an answer here at all.
    let mut response =
        tauri::http::Response::builder().status(answer.status).header("Access-Control-Allow-Origin", "*");
    for (name, value) in answer.headers {
        response = response.header(name, value);
    }
    response.body(answer.body).unwrap_or_default()
}

/// The subtitles kept next to saved video `id` ("name.en.srt"), each language's lines and when each shows.
#[tauri::command]
fn video_subtitles(id: u64, downloads: State<'_, Downloads>) -> Vec<Track> {
    downloads.manager.get(id).map(|task| subtitles::tracks_for(&task.path)).unwrap_or_default()
}

/// Where playback of saved video `id` starts: where it stopped last time, or the start (`video::VideoData`).
#[tauri::command]
fn playback_start(id: u64, videos: State<'_, Videos>) -> f64 {
    videos.load(id).resume_at()
}

/// Keeps `jpeg` (base64) as saved video `id`'s poster: a frame the window took from it, for the list and the grid.
#[tauri::command]
fn keep_poster(id: u64, jpeg: String, videos: State<'_, Videos>) -> Result<(), String> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD.decode(jpeg).map_err(|_| "Not a picture.".to_string())?;
    videos.keep_poster(id, &bytes).map_err(|_| "The poster couldn't be kept.".to_string())
}

/// Where playback of saved video `id` stopped, `at` seconds into its `duration`.
#[tauri::command]
fn playback_stopped(id: u64, at: f64, duration: Option<f64>, videos: State<'_, Videos>) {
    let _ = videos.stopped(id, at, duration);
}

/// The list's sections, as the phone's: what runs, what needs attention, then the days things were saved on, for a
/// person `offset` minutes east of UTC (the window's own time zone).
#[tauri::command]
fn download_sections(offset: i32, downloads: State<'_, Downloads>) -> Vec<library::Section> {
    let dated: Vec<library::Dated> =
        downloads.manager.list().iter().map(|task| library::Dated::from(&TaskView::from(task))).collect();
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs());
    library::sections(&dated, now, offset)
}

#[tauri::command]
fn pause_download(id: u64, downloads: State<'_, Downloads>) {
    downloads.manager.pause(id);
}

#[tauri::command]
fn resume_download(id: u64, downloads: State<'_, Downloads>) {
    downloads.manager.resume(id);
}

/// What Chaya kept about a download (`video`) goes with it: its number may be given to another download.
#[tauri::command]
fn cancel_download(id: u64, downloads: State<'_, Downloads>, videos: State<'_, Videos>) {
    downloads.manager.cancel(id);
    videos.forget(id);
}

#[tauri::command]
fn remove_download(id: u64, downloads: State<'_, Downloads>, videos: State<'_, Videos>) {
    downloads.manager.remove(id);
    videos.forget(id);
}

/// Tries a failed download again. The sites' addresses expire, so the link is looked up again first for a
/// fresh one (both halves' for a joined download); if that lookup fails, the old addresses are tried.
#[tauri::command]
async fn retry_download(id: u64, lookups: State<'_, Lookups>, downloads: State<'_, Downloads>) -> Result<(), ()> {
    let Some(task) = downloads.manager.get(id) else { return Ok(()) };
    // An account's ZIP carries on from its folder; there is no link to look up again.
    if task.account.is_some() {
        downloads.manager.retry(id, None);
        return Ok(());
    }
    let fresh: Option<Fresh> = match lookups.media(task.page_url.clone(), true).await {
        Ok(media) => {
            let choice = if task.group.is_some() { chaya_core::download::plan::WHOLE_POST } else { &task.choice };
            plan(&media, choice, &task.page_url, &downloads.folder, |f| downloads.can_join(f), downloads.joins)
                .and_then(|plans| plans.into_iter().find(|p| p.choice == task.choice))
                .map(|p| Fresh { source: p.source, sound: p.sound, subtitles: p.subtitles })
        }
        Err(_) => None,
    };
    downloads.manager.retry(id, fresh);
    Ok(())
}

/// Opens a finished file in the app Windows uses for it (a video Chaya can't play, or one the person wants elsewhere).
#[tauri::command]
fn open_download(id: u64, app: AppHandle, downloads: State<'_, Downloads>) -> Result<(), String> {
    let task = downloads.manager.get(id).ok_or("This download is gone.")?;
    app.opener()
        .open_path(task.path.to_string_lossy(), None::<&str>)
        .map_err(|_| "Windows couldn't open the file.".into())
}

/// Shows a finished file in its folder in File Explorer.
#[tauri::command]
fn show_download(id: u64, app: AppHandle, downloads: State<'_, Downloads>) -> Result<(), String> {
    let task = downloads.manager.get(id).ok_or("This download is gone.")?;
    app.opener().reveal_item_in_dir(&task.path).map_err(|_| "Windows couldn't show the folder.".into())
}

/// What the window hears about downloads, as `download` events.
#[derive(Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
enum DownloadEvent {
    Changed {
        task: Box<TaskView>,
    },
    #[serde(rename_all = "camelCase")]
    Progress {
        id: u64,
        done: u64,
        total: Option<u64>,
        bytes_per_second: u64,
    },
    Removed {
        id: u64,
    },
}

impl From<Event> for DownloadEvent {
    fn from(event: Event) -> DownloadEvent {
        match event {
            Event::Changed(task) => DownloadEvent::Changed { task: Box::new(TaskView::from(&*task)) },
            Event::Progress { id, done, total, bytes_per_second } => {
                DownloadEvent::Progress { id, done, total, bytes_per_second }
            }
            Event::Removed(id) => DownloadEvent::Removed { id },
        }
    }
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .register_asynchronous_uri_scheme_protocol("chaya-file", |ctx, request, responder| {
            let (app, from) = (ctx.app_handle().clone(), ctx.webview_label().to_string());
            // Read off the window's thread: a picture, or a part of a video, can be many megabytes.
            std::thread::spawn(move || responder.respond(saved_file(&app, &from, &request)));
        })
        .setup(|app| {
            let engine_dir = app.path().resource_dir()?.join("engine");
            let cache_dir = app.path().app_cache_dir()?.join("yt-dlp");
            std::fs::create_dir_all(&cache_dir)?;
            app.manage(Lookups {
                engine: Engine::in_folder(&engine_dir, cache_dir),
                turn: Arc::new(Mutex::new(())),
                answers: Arc::new(Mutex::new(AnswerCache::default())),
            });

            let client = chaya_core::reqwest::Client::builder()
                .connect_timeout(Duration::from_secs(20))
                .read_timeout(Duration::from_secs(30))
                .build()?;
            app.manage(ads::Ads::new(app.handle(), client.clone())?);
            app.manage(speech::Speaking::new(app.path().app_data_dir()?.join("speech"), &engine_dir, client.clone()));
            tauri::async_runtime::spawn(speech::Speaking::work(app.handle().clone()));
            let handle = app.handle().clone();
            let manager = DownloadManager::open(
                app.path().app_data_dir()?.join("downloads.json"),
                client,
                tauri::async_runtime::handle().inner().clone(),
                Box::new(move |event| {
                    // A video just saved gets its subtitles made, when the person asked that of every download.
                    if let Event::Changed(task) = &event {
                        handle.state::<speech::Speaking>().downloaded(&handle, task);
                    }
                    let _ = handle.emit("download", DownloadEvent::from(event));
                }),
            );
            // What a page downloads is fetched with the browser's sign-in, read from its cookie store at each start.
            let browser = app.handle().clone();
            manager.set_session(Some(Arc::new(move |url: &str| browser::session_headers(&browser, url))));
            let joiner = Joiner::find(&engine_dir);
            let joins = joiner.is_some();
            manager.set_joiner(joiner);
            // gallery-dl keeps its own cache, apart from yt-dlp's.
            let gallery_cache = app.path().app_cache_dir()?.join("gallery-dl");
            std::fs::create_dir_all(&gallery_cache)?;
            let lister_engine = Engine { cache_dir: gallery_cache, ..app.state::<Lookups>().engine.clone() };
            manager.set_lister(Some(account_lister(app.handle().clone(), lister_engine)));
            // Windows names a Downloads folder for everyone; elsewhere it may be unset, so fall back to the usual one.
            let folder =
                app.path().download_dir().or_else(|_| app.path().home_dir().map(|home| home.join("Downloads")))?;
            app.manage(Downloads { manager, folder, joins });
            app.manage(Videos::in_folder(app.path().app_data_dir()?.join(video::FOLDER)));
            let tabs =
                browser::Tabs::open(app.path().app_data_dir()?.join("tabs.json"), &app.state::<ads::Ads>().blocker);
            app.manage(tabs);
            app.manage(browser::Agent::default());
            if let Some(main) = app.get_webview_window("main") {
                window::place(&main, app.path().app_data_dir()?.join("window.json"));
            }
            app.manage(update::Updates::default());
            update::start(app.handle(), app.path().app_data_dir()?.join("update.json"));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            recognise_link,
            window::snap_layouts,
            update::update_waiting,
            update::install_update,
            look_up,
            download,
            list_downloads,
            download_sections,
            library_tiles,
            pause_download,
            resume_download,
            cancel_download,
            remove_download,
            retry_download,
            open_download,
            show_download,
            video_subtitles,
            playback_start,
            playback_stopped,
            keep_poster,
            speech::speech_status,
            speech::set_speech,
            speech::delete_speech_model,
            speech::make_subtitles,
            speech::made_subtitles,
            speech::stop_subtitles,
            browser::open_page,
            browser::place_page,
            browser::show_page,
            browser::page_picture,
            browser::page_action,
            browser::list_tabs,
            browser::switch_tab,
            browser::close_tab,
            browser::go_home,
            browser::page_media,
            account_signed_in,
            save_account,
            browser::save_found,
            browser::look_up_found,
            browser::download_found,
            ads::ad_blocking,
            ads::block_ads,
            ads::allow_ads_on,
        ])
        .run(tauri::generate_context!())
        .expect("the window could not be opened");
}
