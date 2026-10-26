//! Whether ads are blocked, and the sites the person lets show ads. Kept in a file on the PC only.

use std::collections::BTreeSet;
use std::io;
use std::path::PathBuf;
use std::sync::{Mutex, MutexGuard, PoisonError};

use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
struct Saved {
    enabled: bool,
    /// Sites (example.com, never a full address) where ads are not blocked.
    allowed_sites: BTreeSet<String>,
}

impl Default for Saved {
    fn default() -> Saved {
        Saved { enabled: true, allowed_sites: BTreeSet::new() }
    }
}

pub struct Settings {
    /// Where they are kept; None keeps them for this run only.
    path: Option<PathBuf>,
    saved: Mutex<Saved>,
}

impl Settings {
    /// The settings kept at `path`; ads are blocked everywhere when there are none yet, or they can't be read.
    pub fn open(path: PathBuf) -> Settings {
        let saved = std::fs::read(&path).ok().and_then(|bytes| serde_json::from_slice(&bytes).ok()).unwrap_or_default();
        Settings { path: Some(path), saved: Mutex::new(saved) }
    }

    /// Settings kept for this run only, for tests.
    pub fn in_memory() -> Settings {
        Settings { path: None, saved: Mutex::new(Saved::default()) }
    }

    fn saved(&self) -> MutexGuard<'_, Saved> {
        self.saved.lock().unwrap_or_else(PoisonError::into_inner)
    }

    pub fn enabled(&self) -> bool {
        self.saved().enabled
    }

    pub fn allowed_sites(&self) -> Vec<String> {
        self.saved().allowed_sites.iter().cloned().collect()
    }

    /// Whether ads are blocked on `site` right now.
    pub fn blocks_on(&self, site: &str) -> bool {
        let saved = self.saved();
        saved.enabled && !saved.allowed_sites.contains(site)
    }

    pub fn set_enabled(&self, enabled: bool) -> io::Result<()> {
        self.change(|saved| saved.enabled = enabled)
    }

    /// Lets `site` show ads, or blocks them there again.
    pub fn set_allowed(&self, site: &str, allowed: bool) -> io::Result<()> {
        if site.is_empty() {
            return Ok(());
        }
        self.change(|saved| {
            if allowed {
                saved.allowed_sites.insert(site.to_string());
            } else {
                saved.allowed_sites.remove(site);
            }
        })
    }

    fn change(&self, change: impl FnOnce(&mut Saved)) -> io::Result<()> {
        let mut saved = self.saved();
        change(&mut saved);
        let Some(path) = &self.path else { return Ok(()) };
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        // Written whole, then put in place, so a crash never leaves half a file.
        let temp = path.with_extension("json.new");
        std::fs::write(&temp, serde_json::to_vec_pretty(&*saved)?)?;
        std::fs::rename(&temp, path)
    }
}
