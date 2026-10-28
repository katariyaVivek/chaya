//! The browser's sign-in, for what a page downloads: the cookies the built-in browser holds for an address, as the
//! phone sends its WebView's with a page's downloads (`DownloadManager.sessionHeaders`).
//!
//! They are read from the browser's own cookie store each time a download starts or resumes, and are never
//! written into the list of downloads. A stream found on a page is looked up by yt-dlp with them in a cookie file
//! that is deleted as soon as the lookup ends ([`CookieFile`]).

use std::io;
use std::path::{Path, PathBuf};

use crate::links::Platform;

/// One cookie the browser holds for an address.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct SessionCookie {
    pub name: String,
    pub value: String,
    /// The cookie's domain; None for a cookie only for the host that set it.
    pub domain: Option<String>,
    pub path: Option<String>,
    pub secure: bool,
    pub http_only: bool,
    /// When it expires, in seconds since 1970; None for a cookie that lasts as long as the browser.
    pub expires: Option<i64>,
}

/// The `Cookie` header for `cookies`, as the browser sends them; None when there are none worth sending.
pub fn cookie_header(cookies: &[SessionCookie]) -> Option<String> {
    let pairs: Vec<String> = cookies
        .iter()
        .filter(|c| !c.name.is_empty() && is_header_safe(&c.name) && is_header_safe(&c.value))
        .map(|c| format!("{}={}", c.name, c.value))
        .collect();
    (!pairs.is_empty()).then(|| pairs.join("; "))
}

/// A header's text a request can carry: no line breaks or other control characters.
pub fn is_header_safe(text: &str) -> bool {
    !text.chars().any(|c| c.is_control())
}

/// `cookies` (the browser's for an address on `host`) in the Netscape cookie file format yt-dlp reads.
pub fn netscape(cookies: &[SessionCookie], host: &str) -> String {
    let mut text = String::from("# Netscape HTTP Cookie File\n");
    for c in cookies {
        let fields = [&c.name, &c.value, c.domain.as_deref().unwrap_or(host), c.path.as_deref().unwrap_or("/")];
        if c.name.is_empty() || fields.iter().any(|f| f.contains(['\t', '\n', '\r'])) {
            continue;
        }
        let domain = c.domain.as_deref().unwrap_or(host);
        // A cookie for a domain covers its subdomains; one without a domain is for its host alone.
        let subdomains = if c.domain.is_some() { "TRUE" } else { "FALSE" };
        let dotted = match (c.domain.is_some(), domain.starts_with('.')) {
            (true, false) => format!(".{domain}"),
            _ => domain.to_string(),
        };
        let line_domain = if c.http_only { format!("#HttpOnly_{dotted}") } else { dotted };
        text.push_str(&format!(
            "{line_domain}\t{subdomains}\t{}\t{}\t{}\t{}\t{}\n",
            c.path.as_deref().unwrap_or("/"),
            if c.secure { "TRUE" } else { "FALSE" },
            c.expires.unwrap_or(0).max(0),
            c.name,
            c.value,
        ));
    }
    text
}

/// The addresses whose cookies are the person's sign-in on `platform`, as the phone reads them (`WebViewSignIn`).
pub fn sign_in_sites(platform: Platform) -> &'static [&'static str] {
    match platform {
        Platform::YouTube => &["youtube.com"],
        Platform::Instagram => &["instagram.com"],
        Platform::TikTok => &["tiktok.com"],
        Platform::X => &["x.com", "twitter.com"],
        // As on the phone: a pin is looked up without a sign-in, and none is offered.
        Platform::Pinterest => &[],
    }
}

/// Whether Chaya offers the person's sign-in on `platform` at all.
pub fn signs_in(platform: Platform) -> bool {
    !sign_in_sites(platform).is_empty()
}

/// Whether `cookies` (the browser's for `platform`'s sites) hold a sign-in: cookies that exist only while signed in.
pub fn is_signed_in(platform: Platform, cookies: &[SessionCookie]) -> bool {
    let markers: &[&str] = match platform {
        Platform::YouTube => &["SAPISID", "__Secure-3PAPISID", "SID"],
        Platform::Instagram => &["sessionid"],
        Platform::TikTok => &["sessionid", "sid_tt"],
        Platform::X => &["auth_token"],
        Platform::Pinterest => &[],
    };
    cookies.iter().any(|c| markers.contains(&c.name.as_str()) && !c.value.is_empty())
}

/// A cookie file for one lookup, deleted when it is dropped, whether the lookup worked or not.
#[derive(Debug)]
pub struct CookieFile {
    path: PathBuf,
}

impl CookieFile {
    /// Writes `cookies` (the browser's for an address on `host`) into a new file in `dir`; None when there are none.
    pub fn write(dir: &Path, cookies: &[SessionCookie], host: &str) -> io::Result<Option<CookieFile>> {
        if cookie_header(cookies).is_none() {
            return Ok(None);
        }
        std::fs::create_dir_all(dir)?;
        let name = format!("sign-in-{}-{}.txt", std::process::id(), unique());
        let path = dir.join(name);
        let file = CookieFile { path };
        std::fs::write(&file.path, netscape(cookies, host))?;
        Ok(Some(file))
    }

    pub fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for CookieFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

/// A number no other cookie file of this run has.
fn unique() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_nanos() as u64);
    now ^ NEXT.fetch_add(1, Ordering::Relaxed).rotate_left(32)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cookie(name: &str, value: &str) -> SessionCookie {
        SessionCookie { name: name.into(), value: value.into(), ..SessionCookie::default() }
    }

    #[test]
    fn the_header_carries_each_cookie_as_the_browser_sends_it() {
        assert_eq!(cookie_header(&[cookie("sid", "abc"), cookie("lang", "en")]).as_deref(), Some("sid=abc; lang=en"));
        assert_eq!(cookie_header(&[]), None);
        assert_eq!(cookie_header(&[cookie("", "x")]), None);
        assert_eq!(
            cookie_header(&[cookie("bad", "a\r\nX-Injected: 1"), cookie("ok", "1")]).as_deref(),
            Some("ok=1"),
            "a cookie that would break the header is left out"
        );
    }

    #[test]
    fn the_cookie_file_is_what_yt_dlp_reads() {
        let cookies = [
            SessionCookie {
                name: "sid".into(),
                value: "abc".into(),
                domain: Some("example.com".into()),
                path: Some("/".into()),
                secure: true,
                http_only: true,
                expires: Some(1_900_000_000),
            },
            cookie("host_only", "1"),
        ];
        assert_eq!(
            netscape(&cookies, "www.example.com"),
            "# Netscape HTTP Cookie File\n\
             #HttpOnly_.example.com\tTRUE\t/\tTRUE\t1900000000\tsid\tabc\n\
             www.example.com\tFALSE\t/\tFALSE\t0\thost_only\t1\n"
        );
    }

    #[test]
    fn a_site_counts_as_signed_in_only_with_its_own_sign_in_cookie() {
        assert!(is_signed_in(Platform::Instagram, &[cookie("csrftoken", "x"), cookie("sessionid", "1%3Aabc")]));
        assert!(!is_signed_in(Platform::Instagram, &[cookie("csrftoken", "x"), cookie("mid", "y")]));
        assert!(!is_signed_in(Platform::Instagram, &[cookie("sessionid", "")]));
        assert!(is_signed_in(Platform::X, &[cookie("auth_token", "t")]));
        assert!(is_signed_in(Platform::YouTube, &[cookie("SAPISID", "s")]));
        assert_eq!(sign_in_sites(Platform::X), ["x.com", "twitter.com"]);
        assert!(signs_in(Platform::X) && !signs_in(Platform::Pinterest));
    }

    #[test]
    fn a_cookie_file_is_deleted_when_its_lookup_ends_and_none_is_written_without_cookies() {
        let dir = tempfile::tempdir().unwrap();
        let file = CookieFile::write(dir.path(), &[cookie("sid", "abc")], "example.com").unwrap().unwrap();
        let path = file.path().to_path_buf();
        assert!(std::fs::read_to_string(&path).unwrap().contains("\tsid\tabc\n"));
        drop(file);
        assert!(!path.exists());
        assert!(CookieFile::write(dir.path(), &[], "example.com").unwrap().is_none());
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 0);
    }
}
