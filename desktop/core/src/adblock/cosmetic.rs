//! Element-hiding rules (`example.com##.ad-box`, `##.ad-banner`, exceptions `example.com#@#.ad-banner`), turned
//! into CSS that sets `display: none` on what they select.

use std::collections::{HashMap, HashSet};

use super::filter::{covers, DomainConstraint};
use super::PageContext;

/// A page asking about more names than this at once gets answers for the first ones only.
pub(crate) const MAX_NAMES: usize = 4000;

/// Words that make a selector something CSS cannot apply alone (uBlock's procedural selectors).
const PROCEDURAL: [&str; 21] = [
    ":-abp-",
    ":has-text(",
    ":xpath(",
    ":matches-css",
    ":style(",
    ":remove(",
    ":upward(",
    ":min-text-length(",
    ":matches-path(",
    ":watch-attr(",
    ":others(",
    ":if(",
    ":if-not(",
    ":matches-attr(",
    ":matches-prop(",
    ":nth-ancestor(",
    ":remove-attr(",
    ":remove-class(",
    ":contains(",
    ":matches-media(",
    ":shadow(",
];

struct SiteRule {
    selector: String,
    /// Sites the rule is not for (`~live.example.com`).
    except: Vec<String>,
}

impl SiteRule {
    fn applies(&self, page: &PageContext) -> bool {
        !self.except.iter().any(|d| covers(d, &page.host, &page.site))
    }
}

pub(crate) struct CosmeticRules {
    /// Rules for particular sites, by each site they name.
    by_site: HashMap<String, Vec<SiteRule>>,
    /// Rules for every site that start with a class or id, by that `.class` or `#id`.
    generic_by_name: HashMap<String, Vec<String>>,
    /// Rules for every site that cannot be looked up by a class or id (`a[href^="https://ads."]`...).
    generic_always: Vec<String>,
    /// Rules for every site except some (`~example.com##.ad`).
    generic_except: Vec<SiteRule>,
    /// Exceptions for particular sites: selectors not hidden there, by site.
    exceptions_by_site: HashMap<String, HashSet<String>>,
}

impl CosmeticRules {
    pub fn page_css(&self, page: &PageContext) -> String {
        let excepted = self.exceptions_for(page);
        let mut out = String::new();
        // Rules for `example.*` are filed under the site's first label followed by ".*".
        let keys = suffixes_of(&page.host).chain(std::iter::once(any_ending(&page.site)));
        for key in keys {
            for rule in self.by_site.get(&key).into_iter().flatten() {
                if rule.applies(page) {
                    hide(&mut out, &rule.selector, &excepted);
                }
            }
        }
        if !page.no_generic_hiding {
            for selector in &self.generic_always {
                hide(&mut out, selector, &excepted);
            }
            for rule in &self.generic_except {
                if rule.applies(page) {
                    hide(&mut out, &rule.selector, &excepted);
                }
            }
        }
        out
    }

    pub fn generic_css<'a>(&self, page: &PageContext, names: impl IntoIterator<Item = &'a str>) -> String {
        let excepted = self.exceptions_for(page);
        let mut out = String::new();
        for name in names.into_iter().take(MAX_NAMES) {
            for selector in self.generic_by_name.get(name).into_iter().flatten() {
                hide(&mut out, selector, &excepted);
            }
        }
        out
    }

    fn exceptions_for(&self, page: &PageContext) -> HashSet<&str> {
        suffixes_of(&page.host)
            .chain(std::iter::once(any_ending(&page.site)))
            .filter_map(|key| self.exceptions_by_site.get(&key))
            .flatten()
            .map(String::as_str)
            .collect()
    }
}

fn any_ending(site: &str) -> String {
    format!("{}.*", site.split('.').next().unwrap_or(""))
}

fn hide(out: &mut String, selector: &str, excepted: &HashSet<&str>) {
    if excepted.contains(selector) {
        return;
    }
    // One rule per selector: a selector this browser cannot read then loses only its own rule.
    out.push_str(selector);
    out.push_str("{display:none!important}\n");
}

#[derive(Default)]
pub(crate) struct Builder {
    by_site: HashMap<String, Vec<SiteRule>>,
    generic_by_name: HashMap<String, Vec<String>>,
    generic_always: Vec<String>,
    generic_except: Vec<SiteRule>,
    exceptions_by_site: HashMap<String, HashSet<String>>,
    generic_exceptions: HashSet<String>,
}

impl Builder {
    /// Adds a rule; false when it is one this engine does not apply. `separator` is `##` or `#@#`.
    pub fn add(&mut self, line: &str, separator: &str) -> bool {
        let Some(at) = line.find(separator) else { return false };
        let sites = &line[..at];
        let selector = line[at + separator.len()..].trim();
        if !is_plain_selector(selector) {
            return false;
        }
        let names: Vec<String> = sites.split(',').map(|s| s.trim().to_lowercase()).filter(|s| !s.is_empty()).collect();
        let include: Vec<String> = names.iter().filter(|s| !s.starts_with('~')).cloned().collect();
        let except: Vec<String> = names.iter().filter_map(|s| s.strip_prefix('~')).map(str::to_string).collect();

        if separator == "#@#" {
            if include.is_empty() {
                self.generic_exceptions.insert(selector.to_string());
            } else {
                for site in include {
                    self.exceptions_by_site.entry(site).or_default().insert(selector.to_string());
                }
            }
            return true;
        }
        if !include.is_empty() {
            for site in include {
                self.by_site
                    .entry(site)
                    .or_default()
                    .push(SiteRule { selector: selector.to_string(), except: except.clone() });
            }
        } else if DomainConstraint::parse(sites, ',').is_some() {
            self.generic_except.push(SiteRule { selector: selector.to_string(), except });
        } else {
            match leading_name(selector) {
                Some(name) => self.generic_by_name.entry(name.to_string()).or_default().push(selector.to_string()),
                None => self.generic_always.push(selector.to_string()),
            }
        }
        true
    }

    pub fn build(mut self) -> CosmeticRules {
        let gone = &self.generic_exceptions;
        if !gone.is_empty() {
            self.generic_always.retain(|s| !gone.contains(s));
            self.generic_except.retain(|r| !gone.contains(&r.selector));
            for selectors in self.generic_by_name.values_mut() {
                selectors.retain(|s| !gone.contains(s));
            }
        }
        CosmeticRules {
            by_site: self.by_site,
            generic_by_name: self.generic_by_name,
            generic_always: self.generic_always,
            generic_except: self.generic_except,
            exceptions_by_site: self.exceptions_by_site,
        }
    }
}

/// The hiding separator in `line` (`##` or `#@#`), or None when it is not an element-hiding rule.
pub(crate) fn separator_in(line: &str) -> Option<&'static str> {
    let rest = &line[line.find('#')?..];
    if rest.starts_with("#@#") {
        Some("#@#")
    } else if rest.starts_with("##") {
        Some("##")
    } else {
        None
    }
}

/// A selector CSS can apply as it is. Scriptlets (`+js(...)`), HTML filters (`^...`) and procedural selectors are
/// not; neither is anything that could end the rule early and add CSS of its own.
pub(crate) fn is_plain_selector(selector: &str) -> bool {
    if selector.is_empty() || selector.starts_with("+js(") || selector.starts_with('^') {
        return false;
    }
    if selector.contains(['{', '}', '<', '\n', '\r', '\\']) || selector.contains("/*") {
        return false;
    }
    !PROCEDURAL.iter().any(|p| selector.contains(p))
}

/// `.ad-box` for `.ad-box > span`, `#banner` for `#banner.wide`; None when the selector starts otherwise.
pub(crate) fn leading_name(selector: &str) -> Option<&str> {
    let bytes = selector.as_bytes();
    if bytes.len() < 2 || (bytes[0] != b'.' && bytes[0] != b'#') {
        return None;
    }
    let end = 1 + bytes[1..].iter().take_while(|c| c.is_ascii_alphanumeric() || **c == b'-' || **c == b'_').count();
    (end > 1).then(|| &selector[..end])
}

/// The host and each name it is under: a.b.example.com, b.example.com, example.com, com.
pub(crate) fn suffixes_of(host: &str) -> impl Iterator<Item = String> + '_ {
    let mut next = (!host.is_empty()).then_some(host);
    std::iter::from_fn(move || {
        let suffix = next?;
        next = suffix.find('.').map(|dot| &suffix[dot + 1..]);
        Some(suffix.to_string())
    })
}
