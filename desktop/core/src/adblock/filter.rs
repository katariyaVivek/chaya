//! One network rule, such as `||ads.example.com/banner^$script,third-party`, after parsing, and the sites a rule
//! is limited to.

use super::{kind, PageContext, Request};

#[derive(Clone)]
pub(crate) struct NetworkFilter {
    /// The address pattern without its anchors: `*` stands for anything, `^` for a separator (any character that
    /// is not a letter, digit or one of `_-.%`, or the end of the address). Lower case unless `match_case`.
    pub pattern: String,
    /// `||`: the pattern starts at the beginning of the address's host or of one of its labels.
    pub host_anchored: bool,
    /// `|` at the start: the pattern starts at the beginning of the address.
    pub start_anchored: bool,
    /// `|` at the end: the pattern ends at the end of the address.
    pub end_anchored: bool,
    pub match_case: bool,
    /// The kinds of request it applies to; `kind::ALL` when the rule names none.
    pub kinds: u16,
    /// The rule names no kind of request, so it also applies to requests of unknown kind.
    pub any_kind: bool,
    /// Third-party requests only (`Some(true)`), first-party only (`Some(false)`), or both.
    pub third_party: Option<bool>,
    pub domains: Option<DomainConstraint>,
    pub important: bool,
}

impl NetworkFilter {
    pub fn matches(&self, request: &Request<'_>, page: &PageContext) -> bool {
        self.applies(request, page) && self.matches_address(if self.match_case { request.url } else { request.lower })
    }

    /// Whether the rule's options allow it for this request on this page, its address aside.
    pub fn applies(&self, request: &Request<'_>, page: &PageContext) -> bool {
        let kind = request.kind;
        let kind_ok = if self.any_kind {
            kind == kind::UNKNOWN || self.kinds & kind != 0
        } else {
            kind != kind::UNKNOWN && self.kinds & kind != 0
        };
        kind_ok && self.allowed_on(page) && self.third_party.is_none_or(|third| third == request.is_third_party(page))
    }

    pub fn allowed_on(&self, page: &PageContext) -> bool {
        self.domains.as_ref().is_none_or(|d| d.allows(&page.host, &page.site))
    }

    /// Whether the address matches the pattern, honouring the anchors.
    pub fn matches_address(&self, url: &str) -> bool {
        let url = url.as_bytes();
        if self.host_anchored {
            let Some(host_start) = find(url, b"://").map(|i| i + 3) else { return false };
            let host_end = host_end_of(url, host_start);
            let mut start = host_start;
            while start < host_end {
                if self.match_from(url, 0, start) {
                    return true;
                }
                match url[start..host_end].iter().position(|&c| c == b'.') {
                    Some(dot) => start += dot + 1,
                    None => return false,
                }
            }
            return false;
        }
        if self.start_anchored {
            return self.match_from(url, 0, 0);
        }
        let pattern = self.pattern.as_bytes();
        let Some(&first) = pattern.first() else { return true };
        // Unanchored: try every place the pattern's first plain character appears.
        if first == b'*' || first == b'^' {
            return (0..=url.len()).any(|start| self.match_from(url, 0, start));
        }
        (0..url.len()).any(|start| url[start] == first && self.match_from(url, 0, start))
    }

    fn match_from(&self, url: &[u8], pattern_index: usize, url_index: usize) -> bool {
        let pattern = self.pattern.as_bytes();
        let (mut i, mut j) = (pattern_index, url_index);
        while i < pattern.len() {
            match pattern[i] {
                b'*' => {
                    let mut next = i;
                    while next < pattern.len() && pattern[next] == b'*' {
                        next += 1;
                    }
                    if next == pattern.len() {
                        return true;
                    }
                    return (j..=url.len()).any(|from| self.match_from(url, next, from));
                }
                b'^' => {
                    if j == url.len() {
                        // A separator may also be the end of the address.
                        i += 1;
                        continue;
                    }
                    if !is_separator(url[j]) {
                        return false;
                    }
                    i += 1;
                    j += 1;
                }
                c => {
                    if j >= url.len() || url[j] != c {
                        return false;
                    }
                    i += 1;
                    j += 1;
                }
            }
        }
        !self.end_anchored || j == url.len()
    }
}

pub(crate) fn is_separator(c: u8) -> bool {
    // A byte of a character beyond ASCII is part of a letter, as far as the lists are concerned.
    !(c.is_ascii_alphanumeric() || c >= 0x80 || matches!(c, b'_' | b'-' | b'.' | b'%'))
}

/// Where the host part of an address ends: at its path, query, fragment or port.
fn host_end_of(url: &[u8], host_start: usize) -> usize {
    url[host_start..].iter().position(|c| matches!(c, b'/' | b'?' | b'#' | b':')).map_or(url.len(), |i| host_start + i)
}

fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack.windows(needle.len()).position(|w| w == needle)
}

/// Which pages a rule applies on (`domain=a.com|~b.a.com`). A name ending in `.*` stands for that name under any
/// ending (`example.*` for example.com, example.co.uk...).
#[derive(Clone)]
pub(crate) struct DomainConstraint {
    include: Vec<String>,
    exclude: Vec<String>,
}

impl DomainConstraint {
    pub fn allows(&self, host: &str, site: &str) -> bool {
        if self.exclude.iter().any(|d| covers(d, host, site)) {
            return false;
        }
        self.include.is_empty() || self.include.iter().any(|d| covers(d, host, site))
    }

    /// Parses `a.com|~b.a.com` (a `domain=` option) or `a.com,~b.a.com` (a hiding rule's sites).
    pub fn parse(value: &str, separator: char) -> Option<DomainConstraint> {
        let (mut include, mut exclude) = (Vec::new(), Vec::new());
        for raw in value.split(separator) {
            let name = raw.trim().to_lowercase();
            match name.strip_prefix('~') {
                _ if name.is_empty() => {}
                Some(excluded) => exclude.push(excluded.to_string()),
                None => include.push(name),
            }
        }
        (!include.is_empty() || !exclude.is_empty()).then_some(DomainConstraint { include, exclude })
    }
}

/// Whether a site named in a rule covers a page on `host` (whose site is `site`).
pub(crate) fn covers(domain: &str, host: &str, site: &str) -> bool {
    if let Some(name) = domain.strip_suffix(".*") {
        return site.split('.').next() == Some(name);
    }
    host == domain || host.strip_suffix(domain).is_some_and(|rest| rest.ends_with('.'))
}
