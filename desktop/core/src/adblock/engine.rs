//! Decides what to block and hide, from ad lists in the Adblock Plus format that EasyList and EasyPrivacy use
//! (the lists uBlock Origin starts with): the phone's `FilterEngine`, ported.
//!
//! Network rules block requests: most are a bare host (`||ads.example.com^`), kept in a set and looked up by the
//! request's host and its parents; the rest are indexed by one word of their pattern and tried only on addresses
//! containing that word. Hiding rules are handed over whole for a site, and for every site (thousands of
//! `.ad-banner`-like selectors) only for the classes and ids a page actually has.
//!
//! Not supported, so skipped rather than half-applied: regular-expression rules, scriptlets (`##+js`),
//! procedural selectors (`:has-text`...), and options that rewrite rather than block (`csp`, `removeparam`...).

use std::collections::{HashMap, HashSet};

use super::cosmetic::{self, CosmeticRules};
use super::filter::{DomainConstraint, NetworkFilter};
use super::{host_of, kind, PageContext, Request, SiteOf};

const PAGE_DOCUMENT: u8 = 1;
const PAGE_ELEMHIDE: u8 = 2;
const PAGE_GENERICHIDE: u8 = 4;

/// Words in nearly every address; a rule indexed by one of them would be tried on almost every request.
const COMMON_WORDS: [&str; 10] = ["http", "https", "www", "com", "net", "org", "js", "html", "php", "cdn"];

#[derive(Default)]
struct Rules {
    /// Bare `||host^` rules with no options.
    hosts: HashSet<String>,
    /// Other `||host^` rules, by that host.
    by_host: HashMap<String, Vec<NetworkFilter>>,
    /// The rest, by one whole word of their pattern.
    by_token: HashMap<String, Vec<NetworkFilter>>,
    /// Rules with no word to look them up by, tried on every request.
    untokenized: Vec<NetworkFilter>,
}

#[derive(PartialEq)]
enum Match {
    None,
    Plain,
    Important,
}

pub struct FilterEngine {
    site_of: SiteOf,
    blocking: Rules,
    exceptions: Rules,
    popups: Vec<NetworkFilter>,
    popup_exceptions: Vec<NetworkFilter>,
    page_exceptions: Vec<(NetworkFilter, u8)>,
    cosmetics: CosmeticRules,
    rule_count: usize,
}

impl FilterEngine {
    /// Rules in use, for the window to show.
    pub fn rule_count(&self) -> usize {
        self.rule_count
    }

    /// The name `host` is registered under, as this engine judges first and third party.
    pub fn site_of(&self, host: &str) -> String {
        (self.site_of)(host)
    }

    /// What the lists say about the page at `url`.
    pub fn page(&self, url: &str) -> PageContext {
        let lower = url.to_lowercase();
        let host = host_of(&lower);
        let site = if host.is_empty() { String::new() } else { (self.site_of)(&host) };
        let mut flags = 0;
        for (filter, flag) in &self.page_exceptions {
            if flags & flag == *flag || !filter.domains.as_ref().is_none_or(|d| d.allows(&host, &site)) {
                continue;
            }
            if filter.matches_address(if filter.match_case { url } else { &lower }) {
                flags |= flag;
            }
        }
        PageContext {
            url: url.to_string(),
            host,
            site,
            allow_all: flags & PAGE_DOCUMENT != 0,
            no_hiding: flags & (PAGE_DOCUMENT | PAGE_ELEMHIDE) != 0,
            no_generic_hiding: flags & PAGE_GENERICHIDE != 0,
        }
    }

    /// Whether a request for `url`, of kind `kind` (see [`kind`]), made by `page` should be blocked.
    pub fn should_block(&self, url: &str, kind: u16, page: &PageContext) -> bool {
        if page.allow_all {
            return false;
        }
        let lower = url.to_lowercase();
        if !lower.starts_with("http://") && !lower.starts_with("https://") {
            return false;
        }
        let host = host_of(&lower);
        if host.is_empty() {
            return false;
        }
        let request = Request::new(url, &lower, &host, kind, self.site_of);
        match find_match(&self.blocking, &request, page) {
            Match::None => false,
            Match::Important => true,
            Match::Plain => find_match(&self.exceptions, &request, page) == Match::None,
        }
    }

    /// Whether a new window opening `url` from `page` is an ad pop-up.
    pub fn should_block_popup(&self, url: &str, page: &PageContext) -> bool {
        if page.allow_all {
            return false;
        }
        let lower = url.to_lowercase();
        let host = host_of(&lower);
        if host.is_empty() {
            return false;
        }
        let request = Request::new(url, &lower, &host, kind::UNKNOWN, self.site_of);
        let popup = |f: &&NetworkFilter| {
            f.allowed_on(page)
                && f.third_party.is_none_or(|third| third == request.is_third_party(page))
                && f.matches_address(if f.match_case { url } else { &lower })
        };
        match self.popups.iter().find(popup) {
            None => false,
            Some(blocking) => blocking.important || !self.popup_exceptions.iter().any(|f| popup(&f)),
        }
    }

    /// CSS hiding what the lists hide on `page` whatever it holds: rules for its site, and the few rules for every
    /// site that cannot be looked up by a class or id. Empty when nothing is hidden there.
    pub fn page_css(&self, page: &PageContext) -> String {
        if page.allow_all || page.no_hiding {
            return String::new();
        }
        self.cosmetics.page_css(page)
    }

    /// CSS hiding the rules for every site that start with one of `names` (`.class` or `#id` on the page).
    pub fn generic_css<'a>(&self, page: &PageContext, names: impl IntoIterator<Item = &'a str>) -> String {
        if !self.hides_generically(page) {
            return String::new();
        }
        self.cosmetics.generic_css(page, names)
    }

    /// Whether rules for every site apply on `page`, so it is worth reporting its classes and ids.
    pub fn hides_generically(&self, page: &PageContext) -> bool {
        !(page.allow_all || page.no_hiding || page.no_generic_hiding)
    }
}

fn find_match(rules: &Rules, request: &Request<'_>, page: &PageContext) -> Match {
    let mut found = Match::None;
    // A bare `||host^` rule applies to the host and everything under it.
    let mut suffix = request.host;
    loop {
        if rules.hosts.contains(suffix) {
            found = Match::Plain;
        }
        for f in rules.by_host.get(suffix).into_iter().flatten() {
            if f.applies(request, page) {
                if f.important {
                    return Match::Important;
                }
                found = Match::Plain;
            }
        }
        match suffix.find('.') {
            Some(dot) => suffix = &suffix[dot + 1..],
            None => break,
        }
    }
    for word in words(request.lower) {
        for f in rules.by_token.get(word).into_iter().flatten() {
            if f.matches(request, page) {
                if f.important {
                    return Match::Important;
                }
                found = Match::Plain;
            }
        }
    }
    for f in &rules.untokenized {
        if f.matches(request, page) {
            if f.important {
                return Match::Important;
            }
            found = Match::Plain;
        }
    }
    found
}

fn is_token_char(c: u8) -> bool {
    c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'%'
}

/// The words of a lower-case address, as rules are indexed by them.
fn words(lower: &str) -> impl Iterator<Item = &str> {
    lower.split(|c: char| !c.is_ascii() || !is_token_char(c as u8)).filter(|w| !w.is_empty())
}

/// The word of `pattern` to index a rule by: a run of letters and digits that is a whole word in any address the
/// rule matches, so it is bounded by separators or anchors and never by a `*`. The longest uncommon one, or None
/// when there is none and the rule must be tried on every request.
pub(crate) fn best_token(pattern: &str, anchored_start: bool, anchored_end: bool) -> Option<String> {
    let lower = pattern.to_lowercase();
    let bytes = lower.as_bytes();
    let mut best: Option<&str> = None;
    let mut best_score = 0;
    let mut start: Option<usize> = None;
    for i in 0..=bytes.len() {
        let word = i < bytes.len() && is_token_char(bytes[i]);
        match (word, start) {
            (true, None) => start = Some(i),
            (false, Some(s)) => {
                let left_bounded = if s == 0 { anchored_start } else { bytes[s - 1] != b'*' };
                let right_bounded = if i == bytes.len() { anchored_end } else { bytes[i] != b'*' };
                if left_bounded && right_bounded && i - s >= 2 {
                    let candidate = &lower[s..i];
                    let score = (i - s) + if COMMON_WORDS.contains(&candidate) { 0 } else { 100 };
                    if score > best_score {
                        best = Some(candidate);
                        best_score = score;
                    }
                }
                start = None;
            }
            _ => {}
        }
    }
    best.map(str::to_string)
}

/// Collects rules line by line, then builds the engine.
pub struct Builder {
    site_of: SiteOf,
    blocking: Rules,
    exceptions: Rules,
    popups: Vec<NetworkFilter>,
    popup_exceptions: Vec<NetworkFilter>,
    page_exceptions: Vec<(NetworkFilter, u8)>,
    cosmetics: cosmetic::Builder,
    count: usize,
}

impl Builder {
    pub fn new(site_of: SiteOf) -> Builder {
        Builder {
            site_of,
            blocking: Rules::default(),
            exceptions: Rules::default(),
            popups: Vec::new(),
            popup_exceptions: Vec::new(),
            page_exceptions: Vec::new(),
            cosmetics: cosmetic::Builder::default(),
            count: 0,
        }
    }

    /// Adds one line of a list; comments, blank lines and rules this engine does not support are skipped.
    pub fn add(&mut self, line: &str) {
        let line = line.trim();
        if line.is_empty() || line.starts_with('!') || line.starts_with('[') {
            return;
        }
        if let Some(separator) = cosmetic::separator_in(line) {
            if self.cosmetics.add(line, separator) {
                self.count += 1;
            }
            return;
        }
        // Procedural, scriptlet and CSS-injecting rules (`#?#`, `#$#`, `#%#`, `#@?#`...) are not applied.
        if is_other_cosmetic(line) {
            return;
        }
        if self.add_network(line) {
            self.count += 1;
        }
    }

    pub fn build(self) -> FilterEngine {
        FilterEngine {
            site_of: self.site_of,
            blocking: self.blocking,
            exceptions: self.exceptions,
            popups: self.popups,
            popup_exceptions: self.popup_exceptions,
            page_exceptions: self.page_exceptions,
            cosmetics: self.cosmetics.build(),
            rule_count: self.count,
        }
    }

    fn add_network(&mut self, line: &str) -> bool {
        let (exception, line) = match line.strip_prefix("@@") {
            Some(rest) => (true, rest),
            None => (false, line),
        };
        let regex = line.len() >= 2 && line.starts_with('/') && line.ends_with('/');
        let (body, options) = match line.rfind('$') {
            Some(dollar) if !regex => (&line[..dollar], &line[dollar + 1..]),
            _ => (line, ""),
        };
        if body.len() >= 2 && body.starts_with('/') && body.ends_with('/') {
            return false; // a regular expression
        }

        let (mut positive, mut negative) = (0u16, 0u16);
        let mut third_party = None;
        let mut domains = None;
        let (mut important, mut match_case, mut popup, mut document) = (false, false, false, false);
        let mut page_flags = 0u8;
        for raw in options.split(',') {
            let option = raw.trim().to_lowercase();
            if option.is_empty() {
                continue;
            }
            let (negated, option) = match option.strip_prefix('~') {
                Some(rest) => (true, rest),
                None => (false, option.as_str()),
            };
            let (name, value) = option.split_once('=').unwrap_or((option, ""));
            if let Some(kind) = kind::of_option(name) {
                if negated {
                    negative |= kind;
                } else {
                    positive |= kind;
                }
                continue;
            }
            match name {
                "third-party" | "3p" => third_party = Some(!negated),
                "first-party" | "1p" => third_party = Some(negated),
                "domain" | "from" => domains = DomainConstraint::parse(value, '|'),
                "important" => important = true,
                "match-case" => match_case = true,
                "popup" => popup = true,
                "document" | "doc" if exception => page_flags |= PAGE_DOCUMENT,
                "document" | "doc" => document = true,
                "elemhide" | "ehide" if exception => page_flags |= PAGE_ELEMHIDE,
                "generichide" | "ghide" if exception => page_flags |= PAGE_GENERICHIDE,
                // A blocking rule that also hands the page a harmless stand-in: blocking alone is close enough.
                "redirect" | "empty" | "mp4" if !exception => {}
                "all" => {}
                // Rewrites, redirect-rule, csp, removeparam, denyallow, badfilter, elemhide on a blocking rule...
                _ => return false,
            }
        }

        let mut pattern = body;
        let (mut host_anchored, mut start_anchored, mut end_anchored) = (false, false, false);
        if let Some(rest) = pattern.strip_prefix("||") {
            host_anchored = true;
            pattern = rest;
        } else if let Some(rest) = pattern.strip_prefix('|') {
            start_anchored = true;
            pattern = rest;
        }
        if let Some(rest) = pattern.strip_suffix('|') {
            end_anchored = true;
            pattern = rest;
        }
        if !host_anchored && !start_anchored {
            pattern = pattern.trim_start_matches('*');
        }
        if !end_anchored {
            pattern = pattern.trim_end_matches('*');
        }
        let pattern = if match_case { pattern.to_string() } else { pattern.to_lowercase() };
        // A rule matching everything with nothing to narrow it would block the web; lists never mean that.
        if pattern.is_empty() && domains.is_none() && !popup && page_flags == 0 {
            return false;
        }

        let any_kind = positive == 0 && negative == 0;
        let kinds = match (positive, negative) {
            (0, 0) => kind::ALL,
            (0, negative) => kind::ALL & !negative,
            (positive, _) => positive,
        };
        let filter = NetworkFilter {
            pattern,
            host_anchored,
            start_anchored,
            end_anchored,
            match_case,
            kinds,
            any_kind,
            third_party,
            domains,
            important,
        };

        if page_flags != 0 {
            self.page_exceptions.push((filter, page_flags));
            return true;
        }
        if popup {
            let only_popups = any_kind;
            let list = if exception { &mut self.popup_exceptions } else { &mut self.popups };
            if only_popups {
                list.push(filter);
                return true;
            }
            // Also for pop-ups, as well as the kinds it names.
            list.push(filter.clone());
        }
        if document && any_kind {
            return true; // whole pages are never blocked, only what they load
        }

        let rules = if exception { &mut self.exceptions } else { &mut self.blocking };
        let bare_host = (host_anchored && !end_anchored)
            .then(|| filter.pattern.strip_suffix('^'))
            .flatten()
            .filter(|h| !h.is_empty() && h.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'.' || c == b'-'))
            .map(str::to_string);
        if let Some(host) = bare_host {
            let plain = any_kind && filter.third_party.is_none() && filter.domains.is_none() && !filter.important;
            if plain {
                rules.hosts.insert(host);
            } else {
                rules.by_host.entry(host).or_default().push(filter);
            }
            return true;
        }
        match best_token(&filter.pattern, host_anchored || start_anchored, end_anchored) {
            Some(token) => rules.by_token.entry(token).or_default().push(filter),
            None => rules.untokenized.push(filter),
        }
        true
    }
}

/// `#?#`, `#$#`, `#%#` and their `#@` exceptions.
fn is_other_cosmetic(line: &str) -> bool {
    let bytes = line.as_bytes();
    bytes.iter().enumerate().any(|(i, &c)| {
        if c != b'#' {
            return false;
        }
        let mut j = i + 1;
        if bytes.get(j) == Some(&b'@') {
            j += 1;
        }
        matches!(bytes.get(j), Some(b'?' | b'$' | b'%')) && bytes.get(j + 1) == Some(&b'#')
    })
}
