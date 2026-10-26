//! Every download and its state, as on the phone (`DownloadManager`):
//!
//!   Queued → Downloading ⇄ Paused → Completed | Failed
//!
//! Up to three run at once; the rest wait their turn. The list is kept in a JSON file and written on every
//! change of state (progress stays in memory). A download that was running when the app closed comes back
//! paused, its `.part` file ready to continue. Cancelling deletes what arrived; removing a finished download
//! from the list keeps its file.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use reqwest::Client;
use serde::{Deserialize, Serialize};
use tokio::runtime::Handle;
use tokio::sync::watch;

use super::error::DownloadError;
use super::http::{self, Outcome, Progress, Source};
use super::join::Joiner;
use super::names;
use super::pieces;
use super::stream;

pub const AT_ONCE: usize = 3;
/// How often progress reaches the window.
const PROGRESS_EVERY: Duration = Duration::from_millis(250);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    Video,
    Audio,
    Picture,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "state", content = "error", rename_all = "lowercase")]
pub enum State {
    Queued,
    Downloading,
    Paused,
    Completed,
    Failed(DownloadError),
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Task {
    pub id: u64,
    pub title: String,
    /// The link that was looked up, so Retry can look it up again for a fresh address.
    pub page_url: String,
    /// The choice it was saved as: "720p", "Audio only", or a post's "Picture 3".
    pub choice: String,
    pub kind: Kind,
    pub path: PathBuf,
    pub source: Source,
    pub thumbnail: Option<String>,
    /// The post it came from, so a post's files stay together.
    pub group: Option<String>,
    #[serde(flatten)]
    pub state: State,
    pub done: u64,
    pub total: Option<u64>,
    /// Seconds since 1970, when it was added.
    pub added: u64,
    /// For a quality whose picture and sound come apart: the sound, fetched after the picture (`source`) and
    /// joined to it.
    #[serde(default)]
    pub sound: Option<Source>,
    /// Where a joined download has got to.
    #[serde(default)]
    pub step: Option<Step>,
}

/// The steps of a download whose picture and sound come apart, or that is a stream. What is left is decided
/// from the files that exist ("name.picture", "name.sound", "name.stream"), so pause, a closed app and Retry
/// all carry on from the right place.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Step {
    Picture,
    Sound,
    Joining,
    /// A stream that arrived whole, being copied into an MP4.
    Saving,
}

impl Task {
    pub fn part_path(&self) -> PathBuf {
        with_suffix(&self.path, ".part")
    }

    /// "name.picture" or "name.sound", one half of a joined download, or "name.stream", a stream before it
    /// becomes an MP4; each once whole.
    fn half(&self, which: &str) -> PathBuf {
        with_suffix(&self.path, &format!(".{which}"))
    }

    /// Every file a download leaves on the way to its own: the `.part`, the halves, and their piece logs.
    fn leftovers(&self) -> Vec<PathBuf> {
        let mut files = vec![self.part_path()];
        for half in [self.half("picture"), self.half("sound"), self.half("stream")] {
            files.push(with_suffix(&half, ".part"));
            files.push(half);
        }
        let logs: Vec<PathBuf> = files.iter().flat_map(|f| [pieces::log_path(f), stream::log_path(f)]).collect();
        files.extend(logs);
        files
    }
}

fn with_suffix(path: &Path, suffix: &str) -> PathBuf {
    let mut name = path.file_name().unwrap_or_default().to_os_string();
    name.push(suffix);
    path.with_file_name(name)
}

/// A download as the window shows it.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskView {
    pub id: u64,
    pub title: String,
    pub choice: String,
    pub kind: Kind,
    pub file_name: String,
    pub folder: String,
    pub thumbnail: Option<String>,
    pub group: Option<String>,
    /// "queued", "downloading", "paused", "completed" or "failed".
    pub state: &'static str,
    /// Why it failed, in words.
    pub error: Option<String>,
    pub retryable: bool,
    pub done: u64,
    pub total: Option<u64>,
    /// "picture", "sound" or "joining", for a download whose picture and sound come apart; "saving" for a
    /// stream being copied into an MP4.
    pub step: Option<Step>,
}

impl From<&Task> for TaskView {
    fn from(task: &Task) -> TaskView {
        let (state, error) = match task.state {
            State::Queued => ("queued", None),
            State::Downloading => ("downloading", None),
            State::Paused => ("paused", None),
            State::Completed => ("completed", None),
            State::Failed(error) => ("failed", Some(error)),
        };
        TaskView {
            id: task.id,
            title: task.title.clone(),
            choice: task.choice.clone(),
            kind: task.kind,
            file_name: task.path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default(),
            folder: task.path.parent().map(|f| f.display().to_string()).unwrap_or_default(),
            thumbnail: task.thumbnail.clone(),
            group: task.group.clone(),
            state,
            error: error.map(DownloadError::message),
            retryable: error.is_some_and(DownloadError::retryable),
            done: task.done,
            total: task.total,
            step: task.step.filter(|_| task.state == State::Downloading),
        }
    }
}

/// What the window asked to save.
#[derive(Clone, Debug)]
pub struct NewDownload {
    pub title: String,
    pub page_url: String,
    pub choice: String,
    pub kind: Kind,
    /// The name it is saved as; made unique in `folder`.
    pub file_name: String,
    pub folder: PathBuf,
    pub source: Source,
    /// The sound to join to `source`, for a quality whose picture and sound come apart.
    pub sound: Option<Source>,
    pub thumbnail: Option<String>,
    pub group: Option<String>,
}

/// A download's addresses, from looking its link up again: the sites' addresses expire.
#[derive(Clone, Debug, PartialEq)]
pub struct Fresh {
    pub source: Source,
    pub sound: Option<Source>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Event {
    /// Boxed: a task is large next to the other events, which come many times a second.
    Changed(Box<Task>),
    Progress {
        id: u64,
        done: u64,
        total: Option<u64>,
        bytes_per_second: u64,
    },
    Removed(u64),
}

type Listener = Box<dyn Fn(Event) + Send + Sync>;

#[derive(Clone)]
pub struct Manager {
    inner: Arc<Inner>,
}

struct Inner {
    client: Client,
    runtime: Handle,
    store: PathBuf,
    at_once: usize,
    listener: Listener,
    tasks: Mutex<Vec<Task>>,
    /// The stop signal of each download that is running.
    running: Mutex<HashMap<u64, watch::Sender<bool>>>,
    /// What joins picture and sound, and turns streams into MP4s; without it, such downloads fail.
    joiner: Mutex<Option<Joiner>>,
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

impl Manager {
    /// The downloads kept in `store`, those that were running brought back paused.
    pub fn open(store: PathBuf, client: Client, runtime: Handle, listener: Listener) -> Manager {
        Manager::with_limit(store, client, runtime, listener, AT_ONCE)
    }

    pub fn with_limit(store: PathBuf, client: Client, runtime: Handle, listener: Listener, at_once: usize) -> Manager {
        let mut tasks: Vec<Task> =
            std::fs::read(&store).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
        for task in &mut tasks {
            if matches!(task.state, State::Downloading | State::Queued) {
                task.state = State::Paused;
            }
        }
        let manager = Manager {
            inner: Arc::new(Inner {
                client,
                runtime,
                store,
                at_once,
                listener,
                tasks: Mutex::new(tasks),
                running: Mutex::new(HashMap::new()),
                joiner: Mutex::new(None),
            }),
        };
        manager.save();
        manager
    }

    /// Sets what joins picture and sound, and saves streams (`Joiner::find`).
    pub fn set_joiner(&self, joiner: Option<Joiner>) {
        *lock(&self.inner.joiner) = joiner;
    }

    /// Newest first.
    pub fn list(&self) -> Vec<Task> {
        let mut tasks = lock(&self.inner.tasks).clone();
        tasks.sort_by(|a, b| b.added.cmp(&a.added).then(b.id.cmp(&a.id)));
        tasks
    }

    pub fn get(&self, id: u64) -> Option<Task> {
        lock(&self.inner.tasks).iter().find(|t| t.id == id).cloned()
    }

    pub fn add(&self, new: NewDownload) -> Task {
        let task = {
            let mut tasks = lock(&self.inner.tasks);
            let taken: HashSet<String> = tasks
                .iter()
                .filter(|t| t.state != State::Completed)
                .filter_map(|t| t.path.file_name()?.to_str().map(str::to_lowercase))
                .collect();
            let task = Task {
                id: tasks.iter().map(|t| t.id).max().unwrap_or(0) + 1,
                title: new.title,
                page_url: new.page_url,
                choice: new.choice,
                kind: new.kind,
                path: names::unique_path(&new.folder, &new.file_name, &taken),
                source: new.source,
                thumbnail: new.thumbnail,
                group: new.group,
                state: State::Queued,
                done: 0,
                total: None,
                added: SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_secs()),
                sound: new.sound,
                step: None,
            };
            tasks.push(task.clone());
            task
        };
        self.changed(task.id);
        self.pump();
        self.get(task.id).unwrap_or(task)
    }

    pub fn pause(&self, id: u64) {
        if self.set_state(id, |s| matches!(s, State::Queued | State::Downloading), State::Paused) {
            self.stop(id);
        }
    }

    pub fn resume(&self, id: u64) {
        if self.set_state(id, |s| *s == State::Paused, State::Queued) {
            self.pump();
        }
    }

    /// Tries a failed download again, from where it stopped, with `fresh` addresses when the link was looked
    /// up again. They replace both halves of a joined download, since either may have expired; addresses for
    /// something else (a quality that is now one file where it was two) are not used.
    pub fn retry(&self, id: u64, fresh: Option<Fresh>) {
        {
            let mut tasks = lock(&self.inner.tasks);
            let Some(task) = tasks.iter_mut().find(|t| t.id == id && matches!(t.state, State::Failed(_))) else {
                return;
            };
            if let Some(fresh) = fresh.filter(|f| f.sound.is_some() == task.sound.is_some()) {
                task.source = fresh.source;
                task.sound = fresh.sound;
            }
            task.state = State::Queued;
        }
        self.changed(id);
        self.pump();
    }

    /// Stops a download and deletes what arrived; a running download deletes the rest as it stops (`run`).
    pub fn cancel(&self, id: u64) {
        let Some(task) = self.take(id) else { return };
        self.stop(id);
        for file in task.leftovers() {
            let _ = std::fs::remove_file(file);
        }
    }

    /// Takes a finished or failed download off the list. A finished file stays where it is.
    pub fn remove(&self, id: u64) {
        let Some(task) = self.get(id) else { return };
        match task.state {
            State::Completed => {
                self.take(id);
            }
            _ => self.cancel(id),
        }
    }

    fn take(&self, id: u64) -> Option<Task> {
        let task = {
            let mut tasks = lock(&self.inner.tasks);
            let index = tasks.iter().position(|t| t.id == id)?;
            tasks.remove(index)
        };
        self.save();
        (self.inner.listener)(Event::Removed(id));
        Some(task)
    }

    fn stop(&self, id: u64) {
        if let Some(stop) = lock(&self.inner.running).remove(&id) {
            let _ = stop.send(true);
        }
        self.pump();
    }

    /// Changes `id`'s state to `to` when `from` accepts its current one; true when it did.
    fn set_state(&self, id: u64, from: impl Fn(&State) -> bool, to: State) -> bool {
        {
            let mut tasks = lock(&self.inner.tasks);
            match tasks.iter_mut().find(|t| t.id == id && from(&t.state)) {
                Some(task) => task.state = to,
                None => return false,
            }
        }
        self.changed(id);
        true
    }

    /// Starts waiting downloads, oldest first, while fewer than `at_once` run.
    fn pump(&self) {
        loop {
            let next = {
                let running = lock(&self.inner.running);
                if running.len() >= self.inner.at_once {
                    return;
                }
                let mut tasks = lock(&self.inner.tasks);
                let Some(task) = tasks
                    .iter_mut()
                    .filter(|t| t.state == State::Queued && !running.contains_key(&t.id))
                    .min_by_key(|t| (t.added, t.id))
                else {
                    return;
                };
                task.state = State::Downloading;
                task.clone()
            };
            let (stop_tx, stop_rx) = watch::channel(false);
            lock(&self.inner.running).insert(next.id, stop_tx);
            self.changed(next.id);
            let manager = self.clone();
            self.inner.runtime.spawn(async move { manager.run(next, stop_rx).await });
        }
    }

    async fn run(&self, task: Task, stop: watch::Receiver<bool>) {
        let part = task.part_path();
        let result = match prepare_folder(&task.path).await {
            Err(error) => Err(error),
            // A file as it is.
            Ok(()) if task.sound.is_none() && !task.source.stream => {
                self.fetch_into(&task, &task.source, &part, (0, Some(0)), &stop).await
            }
            Ok(()) => self.fetch_and_copy(&task, &stop).await,
        };
        // Still registered means nobody paused or cancelled it while it ran.
        let still_running = lock(&self.inner.running).remove(&task.id).is_some();
        if self.get(task.id).is_none() {
            // Cancelled while it ran. Cancelling deleted what had arrived, but a file opened since (the `.part`
            // as the server answered, a half renamed into place) is deleted here, now that nothing writes.
            for file in task.leftovers() {
                let _ = std::fs::remove_file(file);
            }
            self.pump();
            return;
        }
        match result {
            // Whole, even if a pause came at the last moment: a finished file is finished.
            Ok(Outcome::Done) => {
                let state = match finish(&part, &task.path) {
                    Ok(path) => {
                        if let Some(t) = lock(&self.inner.tasks).iter_mut().find(|t| t.id == task.id) {
                            // The file's own size: for a join or a stream, not what was fetched to make it.
                            let size = file_size(&path);
                            t.path = path;
                            t.step = None;
                            t.done = size;
                            t.total = Some(size);
                        }
                        State::Completed
                    }
                    Err(error) => State::Failed(error),
                };
                self.set_state(task.id, |s| matches!(s, State::Downloading | State::Paused), state);
            }
            // Paused or cancelled: whoever stopped it already changed its state.
            Ok(Outcome::Stopped) => {}
            Err(error) if still_running => {
                self.set_state(task.id, |s| *s == State::Downloading, State::Failed(error));
            }
            Err(_) => {}
        }
        self.pump();
    }

    /// Fetches `source` into `part`. Its progress is counted among other files: `before` bytes already
    /// whole, and `after` bytes (when known) still to come.
    async fn fetch_into(
        &self,
        task: &Task,
        source: &Source,
        part: &Path,
        (before, after): (u64, Option<u64>),
        stop: &watch::Receiver<bool>,
    ) -> Result<Outcome, DownloadError> {
        let mut meter = Meter::new(task.id);
        let manager = self.clone();
        http::fetch(&self.inner.client, source, part, stop, &mut |p| {
            // Halves of unknown size count once they start, rather than leaving no total at all.
            let total = p.total.map(|this| before + this + after.unwrap_or(0));
            manager.progress(&mut meter, Progress { done: before + p.done, total })
        })
        .await
    }

    /// The picture, then the sound, each into its half once whole, then the two joined into the `.part` file; or
    /// for a stream, its pieces into "name.stream", then copied into an MP4. Halves already whole (from before a
    /// pause, or a closed app) are not fetched again.
    async fn fetch_and_copy(&self, task: &Task, stop: &watch::Receiver<bool>) -> Result<Outcome, DownloadError> {
        let joiner = lock(&self.inner.joiner).clone().ok_or(match task.sound {
            Some(_) => DownloadError::CouldNotJoin,
            None => DownloadError::CouldNotConvert,
        })?;
        let halves: Vec<(Option<Step>, &Source, PathBuf)> = match &task.sound {
            Some(sound) => vec![
                (Some(Step::Picture), &task.source, task.half("picture")),
                (Some(Step::Sound), sound, task.half("sound")),
            ],
            None => vec![(None, &task.source, task.half("stream"))],
        };
        let mut before = 0;
        for (i, (step, source, file)) in halves.iter().enumerate() {
            // The halves still to come, by the size the site gave; this one's own is the server's (or for a
            // stream, worked out from its pieces), which is nearer.
            let after = halves[i + 1..].iter().map(|(_, source, _)| source.expected_size).sum::<Option<u64>>();
            if !file.exists() {
                if let Some(step) = step {
                    self.set_step(task.id, *step);
                }
                let part = with_suffix(file, ".part");
                if self.fetch_into(task, source, &part, (before, after), stop).await? == Outcome::Stopped {
                    return Ok(Outcome::Stopped);
                }
                std::fs::rename(&part, file).map_err(|e| DownloadError::from_io(&e))?;
            }
            before += file_size(file);
        }
        let outcome = match halves.as_slice() {
            [(_, _, picture), (_, _, sound)] => {
                self.set_step(task.id, Step::Joining);
                joiner.join(picture, sound, &task.part_path(), stop).await?
            }
            [(_, _, stream)] => {
                self.set_step(task.id, Step::Saving);
                joiner.convert(stream, &task.part_path(), stop).await?
            }
            _ => unreachable!("a download has one source or two"),
        };
        if outcome == Outcome::Done {
            for (_, _, file) in &halves {
                let _ = std::fs::remove_file(file);
            }
        }
        Ok(outcome)
    }

    fn set_step(&self, id: u64, step: Step) {
        if let Some(task) = lock(&self.inner.tasks).iter_mut().find(|t| t.id == id) {
            task.step = Some(step);
        }
        self.changed(id);
    }

    fn progress(&self, meter: &mut Meter, progress: Progress) {
        if let Some(task) = lock(&self.inner.tasks).iter_mut().find(|t| t.id == meter.id) {
            task.done = progress.done;
            task.total = progress.total;
        }
        if let Some(bytes_per_second) = meter.tick(progress.done) {
            (self.inner.listener)(Event::Progress {
                id: meter.id,
                done: progress.done,
                total: progress.total,
                bytes_per_second,
            });
        }
    }

    fn changed(&self, id: u64) {
        self.save();
        if let Some(task) = self.get(id) {
            (self.inner.listener)(Event::Changed(Box::new(task)));
        }
    }

    /// Written whole to a temporary file and moved into place, so a crash never leaves half a list.
    fn save(&self) {
        let tasks = lock(&self.inner.tasks).clone();
        let Ok(json) = serde_json::to_vec_pretty(&tasks) else { return };
        let temporary = self.inner.store.with_extension("json.saving");
        if let Some(folder) = self.inner.store.parent() {
            let _ = std::fs::create_dir_all(folder);
        }
        if std::fs::write(&temporary, json).is_ok() {
            let _ = std::fs::rename(&temporary, &self.inner.store);
        }
    }
}

fn file_size(path: &Path) -> u64 {
    std::fs::metadata(path).map_or(0, |m| m.len())
}

async fn prepare_folder(path: &Path) -> Result<(), DownloadError> {
    match path.parent() {
        Some(folder) => tokio::fs::create_dir_all(folder).await.map_err(|e| DownloadError::from_io(&e)),
        None => Ok(()),
    }
}

/// Moves the whole `.part` file to its name, or the next free one if a file of that name appeared meanwhile.
fn finish(part: &Path, path: &Path) -> Result<PathBuf, DownloadError> {
    let target = if path.exists() {
        let folder = path.parent().unwrap_or(Path::new("."));
        let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("Download");
        names::unique_path(folder, name, &HashSet::new())
    } else {
        path.to_path_buf()
    };
    std::fs::rename(part, &target).map_err(|e| DownloadError::from_io(&e))?;
    Ok(target)
}

/// Speed over the last few seconds, reported at most every `PROGRESS_EVERY`.
struct Meter {
    id: u64,
    samples: Vec<(Instant, u64)>,
    last_report: Option<Instant>,
}

impl Meter {
    fn new(id: u64) -> Meter {
        Meter { id, samples: Vec::new(), last_report: None }
    }

    fn tick(&mut self, done: u64) -> Option<u64> {
        let now = Instant::now();
        self.samples.push((now, done));
        self.samples.retain(|(at, _)| now.duration_since(*at) <= Duration::from_secs(3));
        if self.last_report.is_some_and(|at| now.duration_since(at) < PROGRESS_EVERY) {
            return None;
        }
        self.last_report = Some(now);
        let (first_at, first_done) = self.samples[0];
        let seconds = now.duration_since(first_at).as_secs_f64();
        Some(if seconds > 0.2 { ((done.saturating_sub(first_done)) as f64 / seconds) as u64 } else { 0 })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::download::testserver::{Behaviour, TestServer};
    use std::sync::Arc;

    struct Setup {
        manager: Manager,
        events: Arc<Mutex<Vec<Event>>>,
        folder: tempfile::TempDir,
    }

    fn setup(at_once: usize) -> Setup {
        let folder = tempfile::tempdir().unwrap();
        let events = Arc::new(Mutex::new(Vec::new()));
        let seen = events.clone();
        let manager = Manager::with_limit(
            folder.path().join("data").join("downloads.json"),
            Client::new(),
            Handle::current(),
            Box::new(move |e| seen.lock().unwrap().push(e)),
            at_once,
        );
        Setup { manager, events, folder }
    }

    fn new_download(setup: &Setup, server: &TestServer, name: &str) -> NewDownload {
        NewDownload {
            title: "Me at the zoo".into(),
            page_url: "https://www.youtube.com/watch?v=jNQXAC9IVRw".into(),
            choice: "Audio only".into(),
            kind: Kind::Audio,
            file_name: name.into(),
            folder: setup.folder.path().join("Downloads"),
            source: Source {
                url: server.url("/file"),
                headers: Default::default(),
                expected_size: None,
                ..Source::default()
            },
            sound: None,
            thumbnail: None,
            group: None,
        }
    }

    fn body(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i % 253) as u8).collect()
    }

    async fn wait_for(manager: &Manager, id: u64, want: impl Fn(&State) -> bool) -> Task {
        for _ in 0..400 {
            if let Some(task) = manager.get(id).filter(|t| want(&t.state)) {
                return task;
            }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
        panic!("download {id} never reached the state wanted: {:?}", manager.get(id));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_download_is_saved_under_its_name_and_remembered() {
        let content = body(400_000);
        let server = TestServer::start(content.clone(), Behaviour::default());
        let s = setup(AT_ONCE);

        let task = s.manager.add(new_download(&s, &server, "Me at the zoo.m4a"));
        let done = wait_for(&s.manager, task.id, |st| *st == State::Completed).await;

        assert_eq!(done.path, s.folder.path().join("Downloads").join("Me at the zoo.m4a"));
        assert_eq!(std::fs::read(&done.path).unwrap(), content);
        assert!(!done.part_path().exists());
        assert_eq!((done.done, done.total), (400_000, Some(400_000)));

        let states: Vec<State> = s
            .events
            .lock()
            .unwrap()
            .iter()
            .filter_map(|e| match e {
                Event::Changed(t) => Some(t.state),
                _ => None,
            })
            .collect();
        assert_eq!(states.first(), Some(&State::Queued));
        assert!(states.contains(&State::Downloading));
        assert_eq!(states.last(), Some(&State::Completed));

        // Opened again, the finished download is still listed.
        let again = Manager::open(
            s.folder.path().join("data").join("downloads.json"),
            Client::new(),
            Handle::current(),
            Box::new(|_| {}),
        );
        assert_eq!(again.list(), vec![done]);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_paused_download_keeps_what_arrived_and_continues_from_it() {
        let content = body(3_000_000);
        let server = TestServer::start(
            content.clone(),
            Behaviour { chunk_delay: Duration::from_millis(5), ..Behaviour::default() },
        );
        let s = setup(AT_ONCE);

        let task = s.manager.add(new_download(&s, &server, "Clip.mp4"));
        wait_for(&s.manager, task.id, |_| s.manager.get(task.id).is_some_and(|t| t.done > 200_000)).await;
        s.manager.pause(task.id);
        let paused = wait_for(&s.manager, task.id, |st| *st == State::Paused).await;
        tokio::time::sleep(Duration::from_millis(200)).await;
        let kept = std::fs::metadata(paused.part_path()).unwrap().len();
        assert!(kept > 0 && kept < content.len() as u64);

        s.manager.resume(task.id);
        let done = wait_for(&s.manager, task.id, |st| *st == State::Completed).await;
        assert_eq!(std::fs::read(&done.path).unwrap(), content);
        assert!(server.requests().iter().any(|r| r.contains("range: bytes=")), "resumed with a range");
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn cancelling_deletes_what_arrived_and_the_entry() {
        let server = TestServer::start(
            body(3_000_000),
            Behaviour { chunk_delay: Duration::from_millis(5), ..Behaviour::default() },
        );
        let s = setup(AT_ONCE);

        let task = s.manager.add(new_download(&s, &server, "Clip.mp4"));
        wait_for(&s.manager, task.id, |_| s.manager.get(task.id).is_some_and(|t| t.done > 100_000)).await;
        s.manager.cancel(task.id);
        tokio::time::sleep(Duration::from_millis(300)).await;

        assert!(s.manager.get(task.id).is_none());
        assert!(!task.part_path().exists());
        assert!(!task.path.exists());
        assert!(s.events.lock().unwrap().contains(&Event::Removed(task.id)));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_refused_download_fails_and_retry_with_a_fresh_address_finishes_it() {
        let refusing = TestServer::start(body(10), Behaviour { status: Some(403), ..Behaviour::default() });
        let content = body(100_000);
        let fresh = TestServer::start(content.clone(), Behaviour::default());
        let s = setup(AT_ONCE);

        let task = s.manager.add(new_download(&s, &refusing, "Clip.mp4"));
        let failed = wait_for(&s.manager, task.id, |st| matches!(st, State::Failed(_))).await;
        assert_eq!(failed.state, State::Failed(DownloadError::Http(403)));

        let source =
            Source { url: fresh.url("/file"), headers: Default::default(), expected_size: None, ..Source::default() };
        s.manager.retry(task.id, Some(Fresh { source, sound: None }));
        let done = wait_for(&s.manager, task.id, |st| *st == State::Completed).await;
        assert_eq!(std::fs::read(&done.path).unwrap(), content);
        assert_eq!(done.source.url, fresh.url("/file"));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn downloads_beyond_the_limit_wait_their_turn() {
        let server = TestServer::start(
            body(1_500_000),
            Behaviour { chunk_delay: Duration::from_millis(5), ..Behaviour::default() },
        );
        let s = setup(2);

        let ids: Vec<u64> =
            ["a.mp4", "b.mp4", "c.mp4"].iter().map(|n| s.manager.add(new_download(&s, &server, n)).id).collect();
        let states: Vec<State> = ids.iter().map(|id| s.manager.get(*id).unwrap().state).collect();
        assert_eq!(states, [State::Downloading, State::Downloading, State::Queued]);

        for id in ids {
            wait_for(&s.manager, id, |st| *st == State::Completed).await;
        }
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn two_downloads_of_one_name_get_two_files() {
        let server = TestServer::start(body(1_000), Behaviour::default());
        let s = setup(AT_ONCE);

        let first = s.manager.add(new_download(&s, &server, "Clip.mp4"));
        let second = s.manager.add(new_download(&s, &server, "Clip.mp4"));

        assert_eq!(second.path.file_name().unwrap(), "Clip (2).mp4");
        wait_for(&s.manager, first.id, |st| *st == State::Completed).await;
        wait_for(&s.manager, second.id, |st| *st == State::Completed).await;
        assert!(first.path.exists() && second.path.exists());
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_download_running_when_the_app_closed_comes_back_paused() {
        let folder = tempfile::tempdir().unwrap();
        let store = folder.path().join("downloads.json");
        let task = Task {
            id: 7,
            title: "Clip".into(),
            page_url: "https://youtu.be/x".into(),
            choice: "720p".into(),
            kind: Kind::Video,
            path: folder.path().join("Clip (720p).mp4"),
            source: Source {
                url: "https://cdn.example/x".into(),
                headers: Default::default(),
                expected_size: None,
                ..Source::default()
            },
            thumbnail: None,
            group: None,
            state: State::Downloading,
            done: 10,
            total: Some(100),
            added: 1,
            sound: None,
            step: None,
        };
        std::fs::write(&store, serde_json::to_vec(&vec![task.clone()]).unwrap()).unwrap();

        let manager = Manager::open(store, Client::new(), Handle::current(), Box::new(|_| {}));

        assert_eq!(manager.list(), vec![Task { state: State::Paused, ..task }]);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn removing_a_finished_download_keeps_its_file() {
        let server = TestServer::start(body(1_000), Behaviour::default());
        let s = setup(AT_ONCE);
        let task = s.manager.add(new_download(&s, &server, "Clip.mp4"));
        let done = wait_for(&s.manager, task.id, |st| *st == State::Completed).await;

        s.manager.remove(task.id);

        assert!(s.manager.list().is_empty());
        assert!(done.path.exists());
    }

    #[test]
    fn the_window_sees_the_file_its_state_and_why_it_failed() {
        let task = Task {
            id: 3,
            title: "Me at the zoo".into(),
            page_url: "p".into(),
            choice: "Audio only".into(),
            kind: Kind::Audio,
            path: PathBuf::from("Downloads").join("Me at the zoo.m4a"),
            source: Source { url: "u".into(), headers: Default::default(), expected_size: None, ..Source::default() },
            thumbnail: None,
            group: None,
            state: State::Failed(DownloadError::Http(403)),
            done: 10,
            total: Some(100),
            added: 0,
            sound: None,
            step: None,
        };
        let view = serde_json::to_value(TaskView::from(&task)).unwrap();
        assert_eq!(view["fileName"], "Me at the zoo.m4a");
        assert_eq!(view["folder"], "Downloads");
        assert_eq!(view["state"], "failed");
        assert_eq!(view["error"], "The site refused the download. Its address may have expired.");
        assert_eq!(view["retryable"], true);
        assert_eq!(view["kind"], "audio");
    }

    fn joiner() -> Option<Joiner> {
        let ffmpeg = std::env::var_os("CHAYA_FFMPEG").map(PathBuf::from).unwrap_or_else(|| "ffmpeg".into());
        let works = std::process::Command::new(&ffmpeg).arg("-version").output().is_ok_and(|o| o.status.success());
        if !works {
            eprintln!("No ffmpeg here; skipping the joined download test.");
        }
        works.then_some(Joiner { ffmpeg })
    }

    const PICTURE: &[u8] = include_bytes!("../../tests/fixtures/picture.mp4");
    const SOUND: &[u8] = include_bytes!("../../tests/fixtures/sound.m4a");

    fn joined_download(s: &Setup, picture: &TestServer, sound: &TestServer) -> NewDownload {
        NewDownload {
            choice: "240p".into(),
            kind: Kind::Video,
            file_name: "Me at the zoo (240p).mp4".into(),
            source: Source {
                url: picture.url("/133"),
                headers: Default::default(),
                expected_size: Some(PICTURE.len() as u64),
                ..Source::default()
            },
            sound: Some(Source {
                url: sound.url("/140"),
                headers: Default::default(),
                expected_size: Some(SOUND.len() as u64),
                ..Source::default()
            }),
            ..new_download(s, picture, "unused.mp4")
        }
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_quality_whose_picture_and_sound_come_apart_is_saved_as_one_mp4() {
        let Some(joiner) = joiner() else { return };
        let (picture, sound) = (
            TestServer::start(PICTURE.to_vec(), Behaviour::default()),
            TestServer::start(SOUND.to_vec(), Behaviour::default()),
        );
        let s = setup(AT_ONCE);
        s.manager.set_joiner(Some(joiner));

        let task = s.manager.add(joined_download(&s, &picture, &sound));
        let done = wait_for(&s.manager, task.id, |st| *st == State::Completed).await;

        let file = std::fs::read(&done.path).unwrap();
        assert_eq!(&file[4..8], b"ftyp");
        assert!(file.len() > PICTURE.len(), "picture and sound are both in it");
        assert!(done.leftovers().iter().all(|f| !f.exists()), "the halves are gone");
        assert_eq!(done.step, None);
        assert_eq!(done.total, Some(file.len() as u64), "the joined file's size, not the halves'");
        let steps: Vec<Step> = s
            .events
            .lock()
            .unwrap()
            .iter()
            .filter_map(|e| match e {
                Event::Changed(t) => t.step,
                _ => None,
            })
            .collect();
        assert!(
            steps.contains(&Step::Picture) && steps.contains(&Step::Sound) && steps.contains(&Step::Joining),
            "{steps:?}"
        );
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_joined_download_carries_on_from_the_halves_already_whole() {
        let Some(joiner) = joiner() else { return };
        let (picture, sound) = (
            TestServer::start(PICTURE.to_vec(), Behaviour::default()),
            TestServer::start(SOUND.to_vec(), Behaviour::default()),
        );
        let s = setup(AT_ONCE);
        s.manager.set_joiner(Some(joiner));
        // The picture arrived before the app closed.
        let folder = s.folder.path().join("Downloads");
        std::fs::create_dir_all(&folder).unwrap();
        std::fs::write(folder.join("Me at the zoo (240p).mp4.picture"), PICTURE).unwrap();

        let task = s.manager.add(joined_download(&s, &picture, &sound));
        wait_for(&s.manager, task.id, |st| *st == State::Completed).await;

        assert!(picture.requests().is_empty(), "the whole picture is not fetched again");
        assert_eq!(sound.requests().len(), 1);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn retry_gives_a_joined_download_a_fresh_address_for_its_sound_too() {
        let Some(joiner) = joiner() else { return };
        let picture = TestServer::start(PICTURE.to_vec(), Behaviour::default());
        let expired = TestServer::start(Vec::new(), Behaviour { status: Some(403), ..Behaviour::default() });
        let fresh = TestServer::start(SOUND.to_vec(), Behaviour::default());
        let s = setup(AT_ONCE);
        s.manager.set_joiner(Some(joiner));

        let new = joined_download(&s, &picture, &expired);
        let task = s.manager.add(new.clone());
        let failed = wait_for(&s.manager, task.id, |st| matches!(st, State::Failed(_))).await;
        assert_eq!(failed.state, State::Failed(DownloadError::Http(403)));

        let sound = Source { url: fresh.url("/140"), ..new.sound.clone().unwrap() };
        s.manager.retry(task.id, Some(Fresh { source: new.source.clone(), sound: Some(sound) }));
        let done = wait_for(&s.manager, task.id, |st| *st == State::Completed).await;

        assert_eq!(done.sound.unwrap().url, fresh.url("/140"));
        assert_eq!(picture.requests().len(), 1, "the whole picture is not fetched again");
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn fresh_addresses_of_another_shape_are_not_used() {
        let server = TestServer::start(Vec::new(), Behaviour { status: Some(403), ..Behaviour::default() });
        let s = setup(AT_ONCE);
        let task = s.manager.add(new_download(&s, &server, "Clip.mp4"));
        wait_for(&s.manager, task.id, |st| matches!(st, State::Failed(_))).await;

        let other = Source { url: "http://127.0.0.1:9/other".into(), ..task.source.clone() };
        s.manager.retry(task.id, Some(Fresh { source: other.clone(), sound: Some(other) }));
        let again = wait_for(&s.manager, task.id, |st| matches!(st, State::Failed(_))).await;

        assert_eq!((again.source, again.sound), (task.source, None));
    }

    const STREAM: &[u8] = include_bytes!("../../tests/fixtures/stream.ts");

    /// The stream fixture served as an HLS playlist of four pieces, as "/index.m3u8", cut on MPEG-TS's 188-byte
    /// packets as a packager cuts it.
    fn stream_server() -> TestServer {
        let packets = STREAM.len() / 188;
        let mut playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n".to_string();
        let mut files = Vec::new();
        for i in 0..4 {
            let (start, end) = (packets * i / 4 * 188, packets * (i + 1) / 4 * 188);
            playlist.push_str(&format!("#EXTINF:0.25,\nseg-{i}.ts\n"));
            files.push((format!("/seg-{i}.ts"), STREAM[start..end].to_vec()));
        }
        playlist.push_str("#EXT-X-ENDLIST\n");
        files.push(("/index.m3u8".to_string(), playlist.into_bytes()));
        TestServer::with_files(files, Behaviour::default())
    }

    fn stream_download(s: &Setup, server: &TestServer) -> NewDownload {
        NewDownload {
            choice: "720p".into(),
            kind: Kind::Video,
            file_name: "Clip (720p).mp4".into(),
            source: Source {
                url: server.url("/index.m3u8"),
                headers: Default::default(),
                expected_size: None,
                stream: true,
                ..Source::default()
            },
            ..new_download(s, server, "unused.mp4")
        }
    }

    fn steps_seen(s: &Setup) -> Vec<Step> {
        s.events
            .lock()
            .unwrap()
            .iter()
            .filter_map(|e| match e {
                Event::Changed(t) => t.step,
                _ => None,
            })
            .collect()
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_stream_is_saved_as_one_mp4() {
        let Some(joiner) = joiner() else { return };
        let server = stream_server();
        let s = setup(AT_ONCE);
        s.manager.set_joiner(Some(joiner));

        let task = s.manager.add(stream_download(&s, &server));
        let done = wait_for(&s.manager, task.id, |st| *st == State::Completed).await;

        let file = std::fs::read(&done.path).unwrap();
        assert_eq!(&file[4..8], b"ftyp");
        let has = |name: &[u8]| file.windows(4).any(|w| w == name);
        assert!(has(b"avcC") && has(b"mp4a"), "the stream's picture and sound are both in it");
        assert!(done.leftovers().iter().all(|f| !f.exists()), "the stream's pieces are gone");
        assert_eq!(steps_seen(&s), [Step::Saving]);
        assert_eq!(server.requests().len(), 5, "the playlist and four pieces");
        assert_eq!((done.done, done.total), (file.len() as u64, Some(file.len() as u64)), "the MP4's size");
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_picture_only_stream_is_joined_to_its_sound() {
        let Some(joiner) = joiner() else { return };
        let (picture, sound) = (stream_server(), TestServer::start(SOUND.to_vec(), Behaviour::default()));
        let s = setup(AT_ONCE);
        s.manager.set_joiner(Some(joiner));

        let new = NewDownload {
            sound: Some(Source {
                url: sound.url("/audio.m4a"),
                headers: Default::default(),
                expected_size: None,
                ..Source::default()
            }),
            ..stream_download(&s, &picture)
        };
        let task = s.manager.add(new);
        let done = wait_for(&s.manager, task.id, |st| *st == State::Completed).await;

        let file = std::fs::read(&done.path).unwrap();
        assert_eq!(&file[4..8], b"ftyp");
        assert!(done.leftovers().iter().all(|f| !f.exists()));
        assert_eq!(steps_seen(&s), [Step::Picture, Step::Sound, Step::Joining]);
    }

    const FRAGMENTED: &[u8] = include_bytes!("../../tests/fixtures/picture-fragmented.mp4");

    #[tokio::test(flavor = "multi_thread")]
    async fn a_dash_picture_from_its_listed_pieces_is_joined_to_its_sound() {
        let Some(joiner) = joiner() else { return };
        // As a DASH representation comes: its initialization section (ftyp and moov), then pieces of fragmented
        // MP4, here the rest of the file in two.
        let cuts = [0, 805, 805 + (FRAGMENTED.len() - 805) / 2, FRAGMENTED.len()];
        let files: Vec<(String, Vec<u8>)> =
            (0..3).map(|i| (format!("/v/{i}.m4s"), FRAGMENTED[cuts[i]..cuts[i + 1]].to_vec())).collect();
        let picture = TestServer::with_files(files, Behaviour::default());
        let sound = TestServer::start(SOUND.to_vec(), Behaviour::default());
        let s = setup(AT_ONCE);
        s.manager.set_joiner(Some(joiner));

        let new = NewDownload {
            source: Source {
                url: "https://cdn.example/manifest.mpd".into(),
                stream: true,
                fragments: (0..3).map(|i| picture.url(&format!("/v/{i}.m4s"))).collect(),
                ..Source::default()
            },
            sound: Some(Source { url: sound.url("/audio.m4a"), ..Source::default() }),
            ..stream_download(&s, &picture)
        };
        let task = s.manager.add(new);
        let done = wait_for(&s.manager, task.id, |st| *st == State::Completed).await;

        let file = std::fs::read(&done.path).unwrap();
        let has = |name: &[u8]| file.windows(4).any(|w| w == name);
        assert!(has(b"ftyp") && has(b"avcC") && has(b"mp4a"));
        assert!(!has(b"moof"), "one plain MP4, not fragments");
        assert_eq!(picture.requests().len(), 3, "the listed pieces, and no manifest");
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_streams_own_size_counts_over_the_sites_estimate() {
        let Some(joiner) = joiner() else { return };
        let (picture, sound) = (stream_server(), TestServer::start(SOUND.to_vec(), Behaviour::default()));
        let s = setup(AT_ONCE);
        s.manager.set_joiner(Some(joiner));

        // yt-dlp's estimate of the stream is far below what it is.
        let mut new = stream_download(&s, &picture);
        new.source.expected_size = Some(1_000);
        new.sound = Some(Source {
            url: sound.url("/audio.m4a"),
            headers: Default::default(),
            expected_size: Some(SOUND.len() as u64),
            ..Source::default()
        });
        let task = s.manager.add(new);
        wait_for(&s.manager, task.id, |st| *st == State::Completed).await;

        let seen: Vec<(u64, Option<u64>)> = s
            .events
            .lock()
            .unwrap()
            .iter()
            .filter_map(|e| match e {
                Event::Changed(t) if t.step == Some(Step::Sound) => Some((t.done, t.total)),
                _ => None,
            })
            .collect();
        assert_eq!(seen, [(STREAM.len() as u64, Some((STREAM.len() + SOUND.len()) as u64))]);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_sound_of_unknown_size_still_leaves_a_total_to_show() {
        let Some(joiner) = joiner() else { return };
        let (picture, sound) = (stream_server(), TestServer::start(SOUND.to_vec(), Behaviour::default()));
        let s = setup(AT_ONCE);
        s.manager.set_joiner(Some(joiner));

        let new = NewDownload {
            sound: Some(Source {
                url: sound.url("/audio.m4a"),
                headers: Default::default(),
                expected_size: None,
                ..Source::default()
            }),
            ..stream_download(&s, &picture)
        };
        let task = s.manager.add(new);
        wait_for(&s.manager, task.id, |st| *st == State::Completed).await;

        let at_sound: Vec<Option<u64>> = s
            .events
            .lock()
            .unwrap()
            .iter()
            .filter_map(|e| match e {
                Event::Changed(t) if t.step == Some(Step::Sound) => Some(t.total),
                _ => None,
            })
            .collect();
        assert_eq!(at_sound, [Some(STREAM.len() as u64)], "the picture's size, until the sound's is known");
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn without_ffmpeg_a_stream_says_it_cannot_be_saved() {
        let server = stream_server();
        let s = setup(AT_ONCE);

        let task = s.manager.add(stream_download(&s, &server));
        let failed = wait_for(&s.manager, task.id, |st| matches!(st, State::Failed(_))).await;

        assert_eq!(failed.state, State::Failed(DownloadError::CouldNotConvert));
        assert!(server.requests().is_empty(), "nothing is fetched that could not be saved");
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn without_ffmpeg_a_joined_download_says_it_cannot_join() {
        let (picture, sound) = (
            TestServer::start(PICTURE.to_vec(), Behaviour::default()),
            TestServer::start(SOUND.to_vec(), Behaviour::default()),
        );
        let s = setup(AT_ONCE);

        let task = s.manager.add(joined_download(&s, &picture, &sound));
        let failed = wait_for(&s.manager, task.id, |st| matches!(st, State::Failed(_))).await;

        assert_eq!(failed.state, State::Failed(DownloadError::CouldNotJoin));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn cancelling_a_joined_download_deletes_its_halves() {
        let server = TestServer::start(
            body(3_000_000),
            Behaviour { chunk_delay: Duration::from_millis(5), ..Behaviour::default() },
        );
        let s = setup(AT_ONCE);
        s.manager.set_joiner(Some(Joiner { ffmpeg: "ffmpeg".into() }));
        let folder = s.folder.path().join("Downloads");
        std::fs::create_dir_all(&folder).unwrap();
        std::fs::write(folder.join("Me at the zoo (240p).mp4.picture"), PICTURE).unwrap();

        let task = s.manager.add(joined_download(&s, &server, &server));
        wait_for(&s.manager, task.id, |_| s.manager.get(task.id).is_some_and(|t| t.step == Some(Step::Sound))).await;
        s.manager.cancel(task.id);
        tokio::time::sleep(Duration::from_millis(300)).await;

        assert!(task.leftovers().iter().all(|f| !f.exists()), "{:?}", task.leftovers());
    }

    #[test]
    fn a_task_is_stored_with_its_state_alongside() {
        let json = serde_json::to_value(Task {
            id: 1,
            title: "T".into(),
            page_url: "p".into(),
            choice: "720p".into(),
            kind: Kind::Video,
            path: "C:/Downloads/T (720p).mp4".into(),
            source: Source { url: "u".into(), headers: Default::default(), expected_size: None, ..Source::default() },
            thumbnail: None,
            group: None,
            state: State::Failed(DownloadError::Http(403)),
            done: 0,
            total: None,
            added: 0,
            sound: None,
            step: None,
        })
        .unwrap();
        assert_eq!(json["state"], "failed");
        assert_eq!(json["error"], serde_json::json!({"kind": "http", "status": 403}));
    }
}
