//! The ad blocker for the browser's page: the rules, the person's settings, the page shown, and how much was
//! blocked there. The page's webview asks it about each request (on Windows), each window the page tries to
//! open, and what to hide in each document the page shows.

use std::sync::{Arc, Mutex, MutexGuard, PoisonError, RwLock};

use serde::Serialize;

use super::settings::Settings;
use super::{host_of, kind, FilterEngine, PageContext, SiteOf};

/// What the window shows about ad blocking on the page.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Shield {
    /// The page's site (example.com), which the person can let show ads; empty for a page that has none.
    pub site: String,
    /// Whether ads are blocked at all.
    pub enabled: bool,
    /// Whether they are blocked on this site.
    pub blocking: bool,
    /// Requests and windows blocked on the page shown now.
    pub blocked: u32,
    /// Rules in use; None while the lists are being read.
    pub rules: Option<usize>,
}

#[derive(Default)]
struct Page {
    url: String,
    site: String,
    blocked: u32,
    /// What the lists say about the page, by the rules it was worked out with.
    judged: Option<(Arc<FilterEngine>, PageContext)>,
}

pub struct AdBlocker {
    /// The person's choices and the rules are the browser's; the page, its count and its hiding are this tab's.
    settings: Arc<Settings>,
    site_of: SiteOf,
    engine: Arc<RwLock<Option<Arc<FilterEngine>>>>,
    page: Mutex<Page>,
    /// The document whose hiding rules were last worked out, as for `page`.
    hiding: Mutex<Option<(Arc<FilterEngine>, PageContext)>>,
}

impl AdBlocker {
    pub fn new(settings: Settings, site_of: SiteOf) -> AdBlocker {
        AdBlocker {
            settings: Arc::new(settings),
            site_of,
            engine: Arc::new(RwLock::new(None)),
            page: Mutex::new(Page::default()),
            hiding: Mutex::new(None),
        }
    }

    /// A blocker for another tab: the same settings and rules (and the rules read later), its own page and count.
    pub fn for_another_page(&self) -> AdBlocker {
        AdBlocker {
            settings: self.settings.clone(),
            site_of: self.site_of,
            engine: self.engine.clone(),
            page: Mutex::new(Page::default()),
            hiding: Mutex::new(None),
        }
    }

    pub fn settings(&self) -> &Settings {
        &self.settings
    }

    /// The rules to block by, once the lists are read; until then nothing is blocked.
    pub fn use_engine(&self, engine: FilterEngine) {
        *self.engine.write().unwrap_or_else(PoisonError::into_inner) = Some(Arc::new(engine));
    }

    fn engine(&self) -> Option<Arc<FilterEngine>> {
        self.engine.read().unwrap_or_else(PoisonError::into_inner).clone()
    }

    fn page(&self) -> MutexGuard<'_, Page> {
        self.page.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// A new page is loading at `url`: count afresh, and judge what it loads by it.
    pub fn begin_page(&self, url: &str) {
        let host = host_of(url);
        let web = url.starts_with("http://") || url.starts_with("https://");
        let site = if host.is_empty() || !web { String::new() } else { (self.site_of)(&host) };
        *self.page() = Page { url: url.to_string(), site, blocked: 0, judged: None };
    }

    /// Whether to block a request the page makes for `url`, of `kind` (see [`kind`]); counts it when blocked.
    /// The page's own document is never blocked; any other document is a frame in it.
    pub fn should_block(&self, url: &str, kind: u16) -> bool {
        self.judge_request(url, kind, true)
    }

    /// Whether a request for `url` would be blocked on the page shown, without counting it: what the page was
    /// found to hold is never offered when it is an ad.
    pub fn blocks(&self, url: &str, kind: u16) -> bool {
        self.judge_request(url, kind, false)
    }

    fn judge_request(&self, url: &str, kind: u16, count: bool) -> bool {
        let mut page = self.page();
        let kind = match kind {
            kind::DOCUMENT if url == page.url => return false,
            kind::DOCUMENT => kind::SUBDOCUMENT,
            kind => kind,
        };
        let Some((engine, context)) = self.judge(&mut page) else { return false };
        let block = engine.should_block(url, kind, &context);
        if block && count {
            page.blocked += 1;
        }
        block
    }

    /// Whether a window the page tries to open at `url` is an ad pop-up; counts it when it is.
    pub fn should_block_popup(&self, url: &str) -> bool {
        let mut page = self.page();
        let Some((engine, context)) = self.judge(&mut page) else { return false };
        let block = engine.should_block_popup(url, &context);
        if block {
            page.blocked += 1;
        }
        block
    }

    /// The rules and what they say about the page, when ads are blocked on it; None to let everything through.
    fn judge(&self, page: &mut Page) -> Option<(Arc<FilterEngine>, PageContext)> {
        let engine = self.engine()?;
        if page.site.is_empty() || !self.settings.blocks_on(&page.site) {
            return None;
        }
        match &page.judged {
            Some((judged_by, context)) if Arc::ptr_eq(judged_by, &engine) => Some((engine, context.clone())),
            _ => {
                let context = engine.page(&page.url);
                page.judged = Some((engine.clone(), context.clone()));
                Some((engine, context))
            }
        }
    }

    /// Whether to hide anything on the page shown now: asking its documents what they hold is pointless if not.
    pub fn hides(&self) -> bool {
        let page = self.page();
        self.engine().is_some() && !page.site.is_empty() && self.settings.blocks_on(&page.site)
    }

    /// CSS hiding the ads in the document at `url` that holds the classes and ids `names` (`.ad-box`, `#top`).
    /// `new_document` adds what is hidden there whatever it holds, for a document not asked about before.
    pub fn hiding_css<'a>(&self, url: &str, new_document: bool, names: impl IntoIterator<Item = &'a str>) -> String {
        let Some(engine) = self.engine() else { return String::new() };
        let mut hiding = self.hiding.lock().unwrap_or_else(PoisonError::into_inner);
        let context = match &*hiding {
            Some((judged_by, context)) if Arc::ptr_eq(judged_by, &engine) && context.url == url => context.clone(),
            _ => {
                let context = engine.page(url);
                *hiding = Some((engine.clone(), context.clone()));
                context
            }
        };
        if context.site.is_empty() || !self.settings.blocks_on(&context.site) {
            return String::new();
        }
        let mut css = if new_document { engine.page_css(&context) } else { String::new() };
        css.push_str(&engine.generic_css(&context, names));
        css
    }

    pub fn shield(&self) -> Shield {
        let page = self.page();
        Shield {
            site: page.site.clone(),
            enabled: self.settings.enabled(),
            blocking: !page.site.is_empty() && self.settings.blocks_on(&page.site),
            blocked: page.blocked,
            rules: self.engine().map(|e| e.rule_count()),
        }
    }
}
