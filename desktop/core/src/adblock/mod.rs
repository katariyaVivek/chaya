//! Chaya for Windows' ad blocker: the phone's (`app/src/main/java/com/chaya/app/adblock`), ported, with the same
//! lists (EasyList and EasyPrivacy, as uBlock Origin starts with) and the same rules about what it does and skips.
//!
//! - [`engine`] reads the lists and judges a request or a page. It knows nothing of the window.
//! - [`lists`] reads the copies the app carries, or fresher ones fetched from easylist.to once a week.
//! - [`settings`] keeps whether ads are blocked, and the sites the person lets show them.
//! - [`AdBlocker`] holds all three for the browser's page: what is blocked there, and how much.

mod blocker;
mod cosmetic;
pub mod engine;
mod filter;
pub mod lists;
pub mod settings;

use std::cell::OnceCell;
use std::net::IpAddr;

pub use blocker::{AdBlocker, Shield};
pub use engine::{Builder, FilterEngine};

/// What a request is for, as far as can be told. Ad lists name the kinds of request a rule applies to (`$script`,
/// `$image`...). A request whose kind cannot be told is [`kind::UNKNOWN`] and meets only rules that name no kind,
/// so a guess never blocks something a rule did not mean.
pub mod kind {
    pub const UNKNOWN: u16 = 0;
    pub const SCRIPT: u16 = 1;
    pub const IMAGE: u16 = 1 << 1;
    pub const STYLESHEET: u16 = 1 << 2;
    pub const XHR: u16 = 1 << 3;
    pub const SUBDOCUMENT: u16 = 1 << 4;
    pub const MEDIA: u16 = 1 << 5;
    pub const FONT: u16 = 1 << 6;
    pub const WEBSOCKET: u16 = 1 << 7;
    pub const PING: u16 = 1 << 8;
    pub const OBJECT: u16 = 1 << 9;
    pub const OTHER: u16 = 1 << 10;
    pub const ALL: u16 = (1 << 11) - 1;
    /// A whole document: the page itself, never blocked, or a frame in it ([`SUBDOCUMENT`]). Not a list's kind.
    pub const DOCUMENT: u16 = 1 << 15;

    /// The kind a list option names, or None when the option is not a kind of request.
    pub(crate) fn of_option(name: &str) -> Option<u16> {
        Some(match name {
            "script" => SCRIPT,
            "image" => IMAGE,
            "stylesheet" | "css" => STYLESHEET,
            "xmlhttprequest" | "xhr" => XHR,
            "subdocument" | "frame" => SUBDOCUMENT,
            "media" => MEDIA,
            "font" => FONT,
            "websocket" => WEBSOCKET,
            "ping" | "beacon" => PING,
            "object" | "object-subrequest" => OBJECT,
            "other" => OTHER,
            _ => return None,
        })
    }

    /// What an address's file name says it is, for a request the browser does not say the kind of.
    pub fn by_extension(url: &str) -> u16 {
        let path = url.split(['#', '?']).next().unwrap_or("");
        let path = path.split_once("://").map_or(path, |(_, rest)| rest.split_once('/').map_or("", |(_, p)| p));
        let file = path.rsplit('/').next().unwrap_or("");
        let Some((_, extension)) = file.rsplit_once('.') else { return UNKNOWN };
        match extension.to_ascii_lowercase().as_str() {
            "js" | "mjs" => SCRIPT,
            "css" => STYLESHEET,
            "png" | "jpg" | "jpeg" | "gif" | "webp" | "svg" | "ico" | "avif" | "bmp" => IMAGE,
            "woff" | "woff2" | "ttf" | "otf" | "eot" => FONT,
            "mp4" | "webm" | "m4a" | "m4v" | "mp3" | "ogg" | "oga" | "opus" | "wav" | "m3u8" | "mpd" | "m4s" => MEDIA,
            "html" | "htm" => SUBDOCUMENT,
            _ => UNKNOWN,
        }
    }
}

/// The name a host is registered under (www.example.co.uk → example.co.uk); first and third party compare these.
pub type SiteOf = fn(&str) -> String;

/// Sites by the public suffix list: www.bbc.co.uk → bbc.co.uk. An address by number is its own site.
pub fn public_suffix_site(host: &str) -> String {
    let bare = host.trim_start_matches('[').trim_end_matches(']');
    if bare.parse::<IpAddr>().is_ok() {
        return host.to_string();
    }
    psl::domain_str(host).unwrap_or(host).to_string()
}

/// The page a request comes from, worked out once per page: its host, its site (the name registered for it, such
/// as example.co.uk), and what the lists' page-wide exceptions say about it.
#[derive(Clone, Debug)]
pub struct PageContext {
    pub url: String,
    pub host: String,
    pub site: String,
    /// A `$document` exception: nothing on this page is blocked or hidden.
    pub allow_all: bool,
    /// An `$elemhide` exception: nothing on this page is hidden.
    pub no_hiding: bool,
    /// A `$generichide` exception: only rules written for this site hide anything.
    pub no_generic_hiding: bool,
}

/// One request being judged; its site is worked out only when a rule asks.
pub(crate) struct Request<'a> {
    pub url: &'a str,
    pub lower: &'a str,
    pub host: &'a str,
    pub kind: u16,
    site: OnceCell<String>,
    site_of: SiteOf,
}

impl<'a> Request<'a> {
    pub fn new(url: &'a str, lower: &'a str, host: &'a str, kind: u16, site_of: SiteOf) -> Request<'a> {
        Request { url, lower, host, kind, site: OnceCell::new(), site_of }
    }

    pub fn is_third_party(&self, page: &PageContext) -> bool {
        *self.site.get_or_init(|| (self.site_of)(self.host)) != page.site
    }
}

/// The host of an address, lower case, without user, port or trailing dot; empty when there is none.
pub fn host_of(url: &str) -> String {
    let Some((_, rest)) = url.split_once("://") else { return String::new() };
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    // user:password@host — the host starts after the last @.
    let host_port = authority.rsplit_once('@').map_or(authority, |(_, h)| h);
    let host = match host_port.strip_prefix('[') {
        Some(v6) => v6.split_once(']').map_or(host_port, |(h, _)| &host_port[..h.len() + 2]),
        None => host_port.split(':').next().unwrap_or(""),
    };
    host.to_lowercase().trim_end_matches('.').to_string()
}

#[cfg(test)]
mod tests;
