//! The ad lists Chaya blocks with: EasyList (ads) and EasyPrivacy (trackers), the two lists uBlock Origin starts
//! with besides its own. The app carries a copy of each (the phone's, `app/src/main/assets/adblock`), so blocking
//! works from the first page and offline. About once a week Chaya fetches fresh copies straight from easylist.to;
//! a fetch sends nothing about the person (no cookies, no history), and a copy that does not look like the list
//! is thrown away.

use std::io::{self, BufRead, BufReader};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use super::{Builder, FilterEngine, SiteOf};

pub const UPDATE_EVERY_SECS: u64 = 7 * 24 * 60 * 60;
pub const RETRY_AFTER_SECS: u64 = 6 * 60 * 60;
/// Fewer rules than this means a cut-off or wrong file, not a real copy of a list.
pub const MIN_RULES: usize = 1_000;
/// A list bigger than this is not one (EasyList is about 2 MB).
const MAX_BYTES: usize = 32 * 1024 * 1024;

/// One list: `file_name` among the copies the app carries and the fetched ones, fetched from `url`.
#[derive(Clone, Debug)]
pub struct Source {
    pub file_name: String,
    pub url: String,
}

pub fn default_sources() -> Vec<Source> {
    [
        ("easylist.txt", "https://easylist.to/easylist/easylist.txt"),
        ("easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt"),
    ]
    .into_iter()
    .map(|(file_name, url)| Source { file_name: file_name.into(), url: url.into() })
    .collect()
}

/// When the lists were last fetched and last tried, in seconds since 1970.
#[derive(Default, Deserialize, Serialize)]
struct Fetched {
    updated: u64,
    tried: u64,
}

pub struct Lists {
    /// The copies the app carries.
    bundled: PathBuf,
    /// Fetched copies, used instead of the app's; and when they were fetched.
    dir: PathBuf,
    sources: Vec<Source>,
}

impl Lists {
    pub fn new(bundled: PathBuf, dir: PathBuf, sources: Vec<Source>) -> Lists {
        Lists { bundled, dir, sources }
    }

    /// Reads every list, the fetched copy where there is one, into a new engine. Slow (a second or so): not on
    /// the window's thread.
    pub fn build_engine(&self, site_of: SiteOf) -> io::Result<FilterEngine> {
        let mut builder = Builder::new(site_of);
        for source in &self.sources {
            let fetched = self.dir.join(&source.file_name);
            let path = if fetched.is_file() { fetched } else { self.bundled.join(&source.file_name) };
            for line in BufReader::new(std::fs::File::open(path)?).lines() {
                // A line that is not text is no rule; the rest of the list still is.
                match line {
                    Ok(line) => builder.add(&line),
                    Err(e) if e.kind() == io::ErrorKind::InvalidData => continue,
                    Err(e) => return Err(e),
                }
            }
        }
        Ok(builder.build())
    }

    fn fetched(&self) -> Fetched {
        std::fs::read(self.dir.join("fetched.json"))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }

    fn note(&self, fetched: &Fetched) -> io::Result<()> {
        std::fs::create_dir_all(&self.dir)?;
        std::fs::write(self.dir.join("fetched.json"), serde_json::to_vec(fetched)?)
    }

    /// When the lists were last fetched, or None while the app's own copies are in use.
    pub fn last_updated(&self) -> Option<u64> {
        Some(self.fetched().updated).filter(|&t| t > 0)
    }

    /// Whether it is time to fetch: a week since the last fetch, and not within hours of a failed try.
    pub fn is_update_due(&self, now: u64) -> bool {
        let fetched = self.fetched();
        now.saturating_sub(fetched.updated) >= UPDATE_EVERY_SECS
            && now.saturating_sub(fetched.tried) >= RETRY_AFTER_SECS
    }

    /// Fetches fresh copies of every list when [`Lists::is_update_due`]. True when new copies were saved, so the
    /// engine should be built again; false when nothing was due or the fetch failed (the copies in use stay).
    pub async fn update_if_due(&self, client: &reqwest::Client, now: u64) -> bool {
        if !self.is_update_due(now) {
            return false;
        }
        let mut fetched = self.fetched();
        fetched.tried = now;
        if self.note(&fetched).is_err() {
            return false;
        }
        // Every list is fetched before any is replaced: a list from this week beside one from last week could
        // disagree about their exceptions.
        let mut lists = Vec::new();
        for source in &self.sources {
            match fetch(client, &source.url).await {
                Some(body) => lists.push((source, body)),
                None => return false,
            }
        }
        let saved = lists.iter().all(|(source, body)| put(&self.dir, &source.file_name, body).is_ok());
        if saved {
            fetched.updated = now;
            let _ = self.note(&fetched);
        }
        saved
    }
}

/// A list from `url`, or None when the server fails or sends something else.
async fn fetch(client: &reqwest::Client, url: &str) -> Option<Vec<u8>> {
    let mut response = client.get(url).header(reqwest::header::CACHE_CONTROL, "no-cache").send().await.ok()?;
    if !response.status().is_success() {
        return None;
    }
    let mut body = Vec::new();
    while let Some(chunk) = response.chunk().await.ok()? {
        body.extend_from_slice(&chunk);
        if body.len() > MAX_BYTES {
            return None;
        }
    }
    looks_like_list(&body).then_some(body)
}

/// Writes a list whole, then puts it in place, so a list in use is never half written.
fn put(dir: &Path, file_name: &str, body: &[u8]) -> io::Result<()> {
    std::fs::create_dir_all(dir)?;
    let temp = dir.join(format!("{file_name}.download"));
    std::fs::write(&temp, body)?;
    std::fs::rename(&temp, dir.join(file_name)).inspect_err(|_| {
        let _ = std::fs::remove_file(&temp);
    })
}

/// An Adblock Plus list: its header, then at least [`MIN_RULES`] lines.
pub(crate) fn looks_like_list(body: &[u8]) -> bool {
    body.starts_with(b"[Adblock Plus")
        && body.split(|&b| b == b'\n').skip(1).filter(|l| !l.is_empty()).count() >= MIN_RULES
}
