//! Running the engine: the bundled Python with `chaya_engine`, as a program.
//!
//! The phone calls the engine through Chaquopy. On Windows the app starts
//! `python -I run.py extract URL --cache DIR --fragments --subtitles [--js-runtime qjs.exe]` and reads the one line of JSON it
//! prints
//! (`chaya_engine/cli.py`). A lookup that runs too long is stopped; an engine that crashes, or prints something
//! that is not its answer, is reported as the engine's failure, not the link's.

use std::ffi::OsString;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::Value;

use crate::download::archive::{ListRequest, Listed};
use crate::download::error::DownloadError;
use crate::links::Platform;
use crate::media::{self, Failure, FailureKind, Media};

/// How long one lookup may take. yt-dlp gives up on a silent server after 20 s and tries twice more, and a
/// YouTube page with a challenge to solve takes several seconds on its own.
pub const LOOKUP_TIMEOUT: Duration = Duration::from_secs(120);
/// How long listing an account may take. gallery-dl goes at the site's pace, and a big account takes many minutes;
/// a pause stops it sooner (`ListRequest::stop`).
pub const LISTING_TIMEOUT: Duration = Duration::from_secs(4 * 60 * 60);

#[derive(Clone, Debug)]
pub struct Engine {
    pub python: PathBuf,
    pub script: PathBuf,
    /// QuickJS, for YouTube's challenges; `None` in a development engine without one.
    pub js_runtime: Option<PathBuf>,
    /// yt-dlp's cache (YouTube's player scripts, which are slow to fetch).
    pub cache_dir: PathBuf,
    pub timeout: Duration,
}

impl Engine {
    /// The engine `desktop/engine/prepare.py` put in `folder`. Its own Python on Windows; elsewhere, for
    /// development, `CHAYA_ENGINE_PYTHON` or `python3`.
    pub fn in_folder(folder: &Path, cache_dir: PathBuf) -> Engine {
        let bundled = folder.join("python").join("python.exe");
        let python = if bundled.is_file() {
            bundled
        } else {
            std::env::var_os("CHAYA_ENGINE_PYTHON").map(PathBuf::from).unwrap_or_else(|| "python3".into())
        };
        let qjs = folder.join("qjs.exe");
        Engine {
            python,
            script: folder.join("run.py"),
            js_runtime: qjs.is_file().then_some(qjs),
            cache_dir,
            timeout: LOOKUP_TIMEOUT,
        }
    }

    /// Looks `url` up: its title, poster, and the formats it can be saved in, or why not.
    pub fn look_up(&self, url: &str) -> Result<Media, Failure> {
        self.extract(url, None, None)
    }

    /// Looks `url` up with the person's sign-in for its site (`session::CookieFile`), only when they chose it.
    pub fn look_up_signed_in(&self, url: &str, cookies: &Path) -> Result<Media, Failure> {
        self.extract(url, None, Some(cookies))
    }

    /// Looks up a stream's own address, found on the page at `referer` (an HLS playlist, a DASH manifest), for its
    /// qualities; its server may answer only the page's player, so the page goes with the request, and with
    /// `cookies` (the browser's for the stream, `session::CookieFile`) when it has any.
    pub fn look_up_stream(&self, url: &str, referer: &str, cookies: Option<&Path>) -> Result<Media, Failure> {
        self.extract(url, Some(referer), cookies)
    }

    fn extract(&self, url: &str, referer: Option<&str>, cookies: Option<&Path>) -> Result<Media, Failure> {
        let mut args: Vec<OsString> =
            vec!["extract".into(), url.into(), "--cache".into(), self.cache_dir.clone().into()];
        if let Some(cookies) = cookies {
            args.extend(["--cookies".into(), cookies.into()]);
        }
        if let Some(referer) = referer {
            args.extend(["--referer".into(), referer.into()]);
        }
        // DASH formats with their pieces listed: the app fetches those itself (`download::stream`). And the site's
        // subtitles, which a video's download saves beside it (`subtitles`).
        args.extend(["--fragments".into(), "--subtitles".into()]);
        if let Some(qjs) = &self.js_runtime {
            args.extend(["--js-runtime".into(), qjs.clone().into()]);
        }
        media::parse(&self.run(&args)?)
    }

    /// Lists everything `request.account` has posted into `request.list`, for its ZIP (`profiles.list_profile`), with
    /// `cookies` (the person's sign-in, `session::CookieFile`) only when they chose it. Stops when `request.stop`
    /// appears.
    pub fn list_profile(&self, request: &ListRequest, cookies: Option<&Path>) -> Result<Listed, DownloadError> {
        let account = &request.account;
        let mut args: Vec<OsString> = vec![
            "profile".into(),
            account.site().into(),
            account.username.clone().into(),
            "--cache".into(),
            self.cache_dir.clone().into(),
            "--out".into(),
            request.list.clone().into(),
            "--stop".into(),
            request.stop.clone().into(),
        ];
        if let Some(cookies) = cookies {
            args.extend(["--cookies".into(), cookies.into()]);
        }
        let line = self.run_for(&args, LISTING_TIMEOUT).map_err(|failure| match failure.kind {
            FailureKind::Network => DownloadError::Network,
            _ => DownloadError::CouldNotList,
        })?;
        listing_summary(&line, account.platform, cookies.is_some())
    }

    /// The engine's versions and whether its pieces fit together, without using the network.
    pub fn selftest(&self) -> Result<Value, Failure> {
        let mut args: Vec<OsString> = vec!["selftest".into()];
        if let Some(qjs) = &self.js_runtime {
            args.extend(["--js-runtime".into(), qjs.clone().into()]);
        }
        let line = self.run(&args)?;
        serde_json::from_str(&line).map_err(|e| Failure::new(FailureKind::Engine, format!("Unreadable selftest: {e}")))
    }

    /// Runs the engine with `args` and returns the last line it printed.
    fn run(&self, args: &[OsString]) -> Result<String, Failure> {
        self.run_for(args, self.timeout)
    }

    fn run_for(&self, args: &[OsString], timeout: Duration) -> Result<String, Failure> {
        let mut command = Command::new(&self.python);
        command
            .arg("-I")
            .arg(&self.script)
            .args(args)
            // Only the app's own pinned yt-dlp runs: no plugins from the person's yt-dlp folders, which would see
            // everything the engine is given, a sign-in included.
            .env("YTDLP_NO_PLUGINS", "1")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        hide_console_window(&mut command);
        let mut child = command.spawn().map_err(|e| {
            Failure::new(FailureKind::Engine, format!("Could not start {}: {e}", self.python.display()))
        })?;

        let stdout = read_in_background(child.stdout.take());
        let stderr = read_in_background(child.stderr.take());
        let status = wait_at_most(&mut child, timeout);
        let out = stdout.join().unwrap_or_default();
        let err = stderr.join().unwrap_or_default();

        let Some(status) = status else {
            return Err(Failure::new(
                FailureKind::Network,
                format!("The lookup took longer than {} s and was stopped.", timeout.as_secs()),
            ));
        };
        if !status.success() {
            return Err(Failure::new(FailureKind::Engine, format!("The engine exited with {status}: {}", tail(&err))));
        }
        out.lines()
            .rev()
            .map(str::trim)
            .find(|line| !line.is_empty())
            .map(str::to_string)
            .ok_or_else(|| Failure::new(FailureKind::Engine, format!("The engine printed nothing: {}", tail(&err))))
    }
}

/// What the engine's listing summary says: {"count": n}, {"stopped": true}, or {"error": {"kind": ...}}, the kinds
/// explained as the phone's `ProfileLister` explains them.
fn listing_summary(line: &str, platform: Platform, signed_in: bool) -> Result<Listed, DownloadError> {
    let summary: Value = serde_json::from_str(line).map_err(|_| DownloadError::CouldNotList)?;
    if let Some(kind) = summary.get("error").and_then(|e| e.get("kind")).and_then(Value::as_str) {
        return Err(match kind {
            "needs_login" | "bot_check" if signed_in => DownloadError::SignInAgain(platform),
            "needs_login" | "bot_check" => DownloadError::SignInNeeded(platform),
            "private" => DownloadError::PrivateAccount,
            "unavailable" => DownloadError::NoSuchAccount,
            "network" => DownloadError::Network,
            _ => DownloadError::CouldNotList,
        });
    }
    if summary.get("stopped").and_then(Value::as_bool) == Some(true) {
        return Ok(Listed::Stopped);
    }
    match summary.get("count") {
        Some(_) => Ok(Listed::Whole),
        None => Err(DownloadError::CouldNotList),
    }
}

/// Without this, Windows opens a console window for every lookup.
#[cfg(windows)]
fn hide_console_window(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
}

#[cfg(not(windows))]
fn hide_console_window(_: &mut Command) {}

/// Reads a pipe to its end on its own thread, so a chatty engine never blocks on a full pipe.
fn read_in_background(pipe: Option<impl Read + Send + 'static>) -> thread::JoinHandle<String> {
    thread::spawn(move || {
        let mut bytes = Vec::new();
        if let Some(mut pipe) = pipe {
            let _ = pipe.read_to_end(&mut bytes);
        }
        String::from_utf8_lossy(&bytes).into_owned()
    })
}

/// The exit status, or `None` after stopping the engine when it runs past `timeout`.
fn wait_at_most(child: &mut Child, timeout: Duration) -> Option<std::process::ExitStatus> {
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return Some(status),
            Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(25)),
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return None;
            }
        }
    }
}

/// The end of what the engine wrote to stderr, where Python puts the reason for a crash.
fn tail(text: &str) -> String {
    let text = text.trim();
    let start = text.char_indices().rev().nth(399).map_or(0, |(i, _)| i);
    text[start..].to_string()
}

/// Answers kept for a while, so going back to a link does not look it up again. Twenty minutes, as on the
/// phone: the addresses inside an answer stop working not long after.
#[derive(Debug, Default)]
pub struct AnswerCache {
    answers: Vec<(String, Instant, Media)>,
}

pub const ANSWERS_KEPT_FOR: Duration = Duration::from_secs(20 * 60);

impl AnswerCache {
    pub fn get(&mut self, url: &str, now: Instant) -> Option<Media> {
        self.answers.retain(|(_, at, _)| now.saturating_duration_since(*at) < ANSWERS_KEPT_FOR);
        self.answers.iter().find(|(u, _, _)| u == url).map(|(_, _, media)| media.clone())
    }

    pub fn put(&mut self, url: &str, media: Media, now: Instant) {
        self.answers.retain(|(u, _, _)| u != url);
        self.answers.push((url.to_string(), now, media));
        // A handful is plenty for going back and forth.
        if self.answers.len() > 20 {
            self.answers.remove(0);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    /// A Python to run stand-in engines with, or `None` when this computer has none (the test then says so).
    fn python() -> Option<PathBuf> {
        ["python3", "python"]
            .into_iter()
            .map(PathBuf::from)
            .find(|p| Command::new(p).arg("--version").output().is_ok_and(|o| o.status.success()))
    }

    /// An engine whose run.py is `body`, in a fresh folder.
    fn fake_engine(body: &str, timeout: Duration) -> Option<(Engine, tempfile::TempDir)> {
        let Some(python) = python() else {
            eprintln!("No Python here; skipping the engine runner test.");
            return None;
        };
        let folder = tempfile::tempdir().unwrap();
        fs::write(folder.path().join("run.py"), body).unwrap();
        let engine = Engine {
            python,
            script: folder.path().join("run.py"),
            js_runtime: Some(folder.path().join("qjs.exe")),
            cache_dir: folder.path().join("cache"),
            timeout,
        };
        Some((engine, folder))
    }

    #[test]
    fn the_last_line_the_engine_prints_is_its_answer_and_it_is_given_the_link_and_folders() {
        let Some((engine, _dir)) = fake_engine(
            r#"
import json, sys
print("a warning yt-dlp printed")
print(json.dumps({"media": {"title": " ".join(sys.argv[1:]), "formats": []}}))
"#,
            LOOKUP_TIMEOUT,
        ) else {
            return;
        };
        let media = engine.look_up("https://youtu.be/x").unwrap();
        let expected = format!(
            "extract https://youtu.be/x --cache {} --fragments --subtitles --js-runtime {}",
            engine.cache_dir.display(),
            engine.js_runtime.as_ref().unwrap().display()
        );
        assert_eq!(media.title, expected);
    }

    #[test]
    fn an_account_is_listed_with_its_folders_and_a_sign_in_only_when_chosen() {
        use crate::download::archive::Account;
        let Some((engine, dir)) = fake_engine(
            r#"
import json, sys
args = sys.argv[1:]
out = args[args.index("--out") + 1]
with open(out, "w") as f:
    f.write(json.dumps({"args": args}))
print(json.dumps({"count": 1}))
"#,
            LOOKUP_TIMEOUT,
        ) else {
            return;
        };
        let request = ListRequest {
            account: Account { platform: Platform::X, username: "someone".into(), sign_in: true },
            list: dir.path().join("list.jsonl"),
            stop: dir.path().join("stop"),
        };
        let cookies = dir.path().join("cookies.txt");
        assert_eq!(engine.list_profile(&request, Some(&cookies)), Ok(Listed::Whole));
        let written: Value = serde_json::from_str(&fs::read_to_string(&request.list).unwrap()).unwrap();
        let expected: Vec<String> = vec![
            "profile".into(),
            "twitter".into(),
            "someone".into(),
            "--cache".into(),
            engine.cache_dir.display().to_string(),
            "--out".into(),
            request.list.display().to_string(),
            "--stop".into(),
            request.stop.display().to_string(),
            "--cookies".into(),
            cookies.display().to_string(),
        ];
        assert_eq!(written["args"], serde_json::json!(expected));
    }

    #[test]
    fn a_listings_summary_is_explained_as_on_the_phone() {
        let ig = Platform::Instagram;
        assert_eq!(listing_summary(r#"{"count": 12}"#, ig, false), Ok(Listed::Whole));
        assert_eq!(listing_summary(r#"{"stopped": true, "count": 3}"#, ig, false), Ok(Listed::Stopped));
        let error = |kind: &str| format!(r#"{{"error": {{"kind": "{kind}", "detail": "x"}}}}"#);
        assert_eq!(listing_summary(&error("needs_login"), ig, false), Err(DownloadError::SignInNeeded(ig)));
        assert_eq!(listing_summary(&error("needs_login"), ig, true), Err(DownloadError::SignInAgain(ig)));
        assert_eq!(listing_summary(&error("private"), ig, true), Err(DownloadError::PrivateAccount));
        assert_eq!(listing_summary(&error("unavailable"), ig, true), Err(DownloadError::NoSuchAccount));
        assert_eq!(listing_summary(&error("network"), ig, false), Err(DownloadError::Network));
        assert_eq!(listing_summary(&error("something"), ig, false), Err(DownloadError::CouldNotList));
        assert_eq!(listing_summary("not json", ig, false), Err(DownloadError::CouldNotList));
    }

    #[test]
    fn the_engine_never_loads_yt_dlp_plugins() {
        let Some((engine, _dir)) = fake_engine(
            r#"
import json, os
print(json.dumps({"media": {"title": os.environ.get("YTDLP_NO_PLUGINS", "plugins on"), "formats": []}}))
"#,
            LOOKUP_TIMEOUT,
        ) else {
            return;
        };
        assert_eq!(engine.look_up_signed_in("https://www.instagram.com/p/x/", Path::new("c.txt")).unwrap().title, "1");
    }

    #[test]
    fn a_stream_found_on_a_page_is_looked_up_with_the_page_as_referrer() {
        let Some((engine, _dir)) = fake_engine(
            r#"
import json, sys
print(json.dumps({"media": {"title": " ".join(sys.argv[1:]), "formats": []}}))
"#,
            LOOKUP_TIMEOUT,
        ) else {
            return;
        };
        let media = engine.look_up_stream("https://cdn.example/v/out.mpd", "https://example.com/watch", None).unwrap();
        assert!(
            media.title.starts_with("extract https://cdn.example/v/out.mpd --cache ")
                && media.title.contains(" --referer https://example.com/watch --fragments"),
            "{}",
            media.title
        );
        assert!(!media.title.contains("--cookies"), "no sign-in unless the browser has one for the stream");

        let cookies = Path::new("sign-in.txt");
        let media = engine.look_up_stream("https://cdn.example/v/out.mpd", "https://example.com/watch", Some(cookies));
        assert!(media.unwrap().title.contains(" --cookies sign-in.txt --referer "));
    }

    #[test]
    fn the_links_own_failure_keeps_its_kind() {
        let Some((engine, _dir)) =
            fake_engine(r#"print('{"error": {"kind": "private", "detail": "Private video"}}')"#, LOOKUP_TIMEOUT)
        else {
            return;
        };
        assert_eq!(engine.look_up("https://youtu.be/x").unwrap_err().kind, FailureKind::Private);
    }

    #[test]
    fn a_crash_is_the_engines_failure_with_pythons_reason() {
        let Some((engine, _dir)) = fake_engine("raise SystemExit('No module named yt_dlp')", LOOKUP_TIMEOUT) else {
            return;
        };
        let failure = engine.look_up("https://youtu.be/x").unwrap_err();
        assert_eq!(failure.kind, FailureKind::Engine);
        assert!(failure.detail.unwrap().contains("No module named yt_dlp"));
    }

    #[test]
    fn an_engine_that_prints_nothing_or_nonsense_has_failed() {
        let Some((silent, _a)) = fake_engine("pass", LOOKUP_TIMEOUT) else { return };
        assert_eq!(silent.look_up("https://youtu.be/x").unwrap_err().kind, FailureKind::Engine);
        let Some((nonsense, _b)) = fake_engine("print('Traceback?')", LOOKUP_TIMEOUT) else { return };
        assert_eq!(nonsense.look_up("https://youtu.be/x").unwrap_err().kind, FailureKind::Engine);
    }

    #[test]
    fn a_lookup_that_runs_too_long_is_stopped() {
        let Some((engine, _dir)) = fake_engine("import time\ntime.sleep(30)", Duration::from_millis(500)) else {
            return;
        };
        let started = Instant::now();
        let failure = engine.look_up("https://youtu.be/x").unwrap_err();
        assert_eq!(failure.kind, FailureKind::Network);
        assert!(started.elapsed() < Duration::from_secs(10), "took {:?}", started.elapsed());
    }

    #[test]
    fn a_missing_python_is_the_engines_failure() {
        let engine = Engine {
            python: "/nowhere/python.exe".into(),
            script: "run.py".into(),
            js_runtime: None,
            cache_dir: "cache".into(),
            timeout: LOOKUP_TIMEOUT,
        };
        assert_eq!(engine.look_up("https://youtu.be/x").unwrap_err().kind, FailureKind::Engine);
    }

    #[test]
    fn a_folder_without_its_own_python_or_quickjs_uses_the_computers_python() {
        let folder = tempfile::tempdir().unwrap();
        let engine = Engine::in_folder(folder.path(), "cache".into());
        assert_eq!(engine.script, folder.path().join("run.py"));
        assert_eq!(engine.js_runtime, None);
        assert!(!engine.python.starts_with(folder.path()));

        fs::create_dir(folder.path().join("python")).unwrap();
        fs::write(folder.path().join("python").join("python.exe"), "").unwrap();
        fs::write(folder.path().join("qjs.exe"), "").unwrap();
        let bundled = Engine::in_folder(folder.path(), "cache".into());
        assert_eq!(bundled.python, folder.path().join("python").join("python.exe"));
        assert_eq!(bundled.js_runtime, Some(folder.path().join("qjs.exe")));
    }

    #[test]
    fn answers_are_kept_for_twenty_minutes() {
        let mut cache = AnswerCache::default();
        let start = Instant::now();
        cache.put("https://youtu.be/a", Media { title: "A".into(), ..Media::default() }, start);
        assert_eq!(cache.get("https://youtu.be/a", start + Duration::from_secs(19 * 60)).unwrap().title, "A");
        assert!(cache.get("https://youtu.be/b", start).is_none());
        assert!(cache.get("https://youtu.be/a", start + ANSWERS_KEPT_FOR).is_none());
    }

    #[test]
    fn only_the_end_of_a_long_crash_is_kept() {
        let long = "x".repeat(1000) + "the reason";
        let kept = tail(&long);
        assert_eq!(kept.chars().count(), 400);
        assert!(kept.ends_with("the reason"));
        assert_eq!(tail("  short  "), "short");
    }
}
