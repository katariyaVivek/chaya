//! The built-in browser's rules, without its window: what typed text opens, where a page may go, and which of
//! a page's downloads Chaya saves in its own list.
//!
//! As on the phone (`BrowserScreen.navigateToUrl`): an address opens as it is, a bare `example.com` opens over
//! https, and anything else is searched for on Google.

use std::collections::BTreeMap;
use std::path::Path;

use url::Url;

use crate::download::http::Source;
use crate::download::manager::{Kind, NewDownload};
use crate::download::names;
use crate::found::{Found, FoundKind};
use crate::links::Link;

/// The page that searches for `words`.
pub fn search_url(words: &str) -> String {
    let mut url = Url::parse("https://www.google.com/search").expect("a fixed address");
    url.query_pairs_mut().append_pair("q", words.trim());
    url.into()
}

/// The page `link` opens in the browser: a page, an account's page, or a search. `None` for a video or post on
/// a supported site, which is looked up instead.
/// Where to sign in for `link`: a video's or post's own page, where its site asks for a sign-in and returns to it
/// once given; anything else, as `page_for` opens it.
pub fn sign_in_page(link: &Link) -> Option<String> {
    match link {
        Link::Media { link, .. } => Some(link.url.clone()),
        other => page_for(other),
    }
}

pub fn page_for(link: &Link) -> Option<String> {
    match link {
        Link::Media { .. } => None,
        Link::Account { link, .. } => Some(link.url.clone()),
        Link::Web { url } => Some(url.clone()),
        Link::Text { text } => {
            let text = text.trim();
            if text.is_empty() {
                return None;
            }
            // "example.com/page", as on the phone: a dot and no spaces is an address.
            let bare = (text.contains('.') && !text.contains(char::is_whitespace) && !text.contains("://"))
                .then(|| format!("https://{text}"))
                .filter(|url| Url::parse(url).is_ok_and(|u| u.host_str().is_some_and(|h| h.contains('.'))));
            Some(bare.unwrap_or_else(|| search_url(text)))
        }
    }
}

/// Whether a page may go to `url`: the web (http and https), and a blank page. Anything else (files on the
/// PC, the app's own pages, other programs' links) is refused.
pub fn may_open(url: &Url) -> bool {
    matches!(url.scheme(), "http" | "https") || url.as_str() == "about:blank"
}

/// What a file at `url` is, by its name: a video, a sound, a picture, or a stream (an HLS playlist). `None` for
/// anything else.
pub fn media_kind(url: &Url) -> Option<(Kind, bool)> {
    let name = url.path_segments()?.next_back()?.to_ascii_lowercase();
    let ext = name.rsplit_once('.')?.1;
    Some(match ext {
        "mp4" | "m4v" | "webm" | "mkv" | "mov" | "avi" | "flv" | "3gp" | "ogv" | "wmv" => (Kind::Video, false),
        "mp3" | "m4a" | "aac" | "ogg" | "oga" | "opus" | "wav" | "flac" | "weba" => (Kind::Audio, false),
        "jpg" | "jpeg" | "png" | "gif" | "webp" | "avif" | "bmp" | "heic" => (Kind::Picture, false),
        "m3u8" => (Kind::Video, true),
        _ => return None,
    })
}

/// A download a page started (a link to a file, a "Save" button): when it is a video, a sound, a picture or a
/// stream, Chaya saves it in its own list, with the page as the referrer; `None` for anything else, which the
/// browser saves as any browser would. `streams`: whether the app can save HLS streams (it carries ffmpeg).
pub fn page_download(url: &str, page_url: &str, page_title: &str, folder: &Path, streams: bool) -> Option<NewDownload> {
    let parsed = Url::parse(url).ok().filter(|u| matches!(u.scheme(), "http" | "https"))?;
    let (kind, stream) = media_kind(&parsed)?;
    if stream && !streams {
        return None;
    }
    let raw = parsed.path_segments()?.next_back()?.to_string();
    let decoded = percent_decode(&raw);
    let (stem, ext) = decoded.rsplit_once('.')?;
    let (title, ext) = match stream {
        // A playlist's own name ("index", "master") says nothing; the page's title does.
        true => (page_title.trim().to_string(), "mp4".to_string()),
        false => (stem.to_string(), ext.to_ascii_lowercase()),
    };
    let title = if title.is_empty() { "Download".to_string() } else { title };
    let mut headers = BTreeMap::new();
    if Url::parse(page_url).is_ok_and(|u| matches!(u.scheme(), "http" | "https")) {
        headers.insert("Referer".to_string(), page_url.to_string());
    }
    Some(NewDownload {
        file_name: names::file_name(&title, None, &ext),
        title,
        page_url: page_url.to_string(),
        choice: match kind {
            Kind::Video => "Video",
            Kind::Audio => "Audio",
            Kind::Picture => "Picture",
            Kind::Archive => "Archive",
        }
        .to_string(),
        kind,
        folder: folder.to_path_buf(),
        // What the page downloads is fetched as the browser would: with its sign-in for the file's address.
        source: Source { url: url.to_string(), headers, stream, session: true, ..Source::default() },
        sound: None,
        thumbnail: (kind == Kind::Picture).then(|| url.to_string()),
        group: None,
    })
}

/// Whether the app can save `found`: a file, and with `streams` (the app carries ffmpeg) an HLS stream. A DASH
/// stream found on a page it cannot save yet.
pub fn can_save_found(found: &Found, streams: bool) -> bool {
    match found.kind {
        FoundKind::Video | FoundKind::Audio => true,
        FoundKind::Stream => streams,
        FoundKind::Dash => false,
    }
}

/// What saving `found` from the page at `page_url` takes: one download, named after it, with the page (or the
/// frame its player is in) as referrer. `None` when the app cannot save it.
pub fn found_download(found: &Found, page_url: &str, folder: &Path, streams: bool) -> Option<NewDownload> {
    if !can_save_found(found, streams) {
        return None;
    }
    let url = Url::parse(&found.url).ok()?;
    let named = url
        .path_segments()
        .and_then(|mut s| s.next_back())
        .and_then(|name| name.rsplit_once('.'))
        .map(|(_, ext)| ext.to_ascii_lowercase())
        .filter(|ext| media_kind(&url).is_some_and(|(_, stream)| !stream) && !ext.is_empty());
    let (kind, ext, choice) = match found.kind {
        FoundKind::Audio => (Kind::Audio, named.unwrap_or_else(|| "m4a".into()), "Audio"),
        FoundKind::Stream => (Kind::Video, "mp4".to_string(), "Stream"),
        _ => (Kind::Video, named.unwrap_or_else(|| "mp4".into()), "Video"),
    };
    let headers: BTreeMap<String, String> = Url::parse(&found.referer)
        .ok()
        .filter(|u| matches!(u.scheme(), "http" | "https"))
        .map(|_| [("Referer".to_string(), found.referer.clone())].into())
        .unwrap_or_default();
    Some(NewDownload {
        title: found.name.clone(),
        page_url: page_url.to_string(),
        choice: choice.to_string(),
        kind,
        file_name: names::file_name(&found.name, None, &ext),
        folder: folder.to_path_buf(),
        source: Source {
            url: found.url.clone(),
            headers,
            stream: found.kind == FoundKind::Stream,
            // What the page showed is fetched as the browser fetched it: with its sign-in for the file's address.
            session: true,
            ..Source::default()
        },
        sound: None,
        thumbnail: None,
        group: None,
    })
}

/// "Monsoon%20trip" → "Monsoon trip". Bytes that are not UTF-8 are kept as they were written.
fn percent_decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let hex = |b: u8| (b as char).to_digit(16);
        match (bytes[i], bytes.get(i + 1).copied().and_then(hex), bytes.get(i + 2).copied().and_then(hex)) {
            (b'%', Some(high), Some(low)) => {
                out.push((high * 16 + low) as u8);
                i += 3;
            }
            (b, ..) => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8(out).unwrap_or_else(|_| text.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::links::recognise;

    #[test]
    fn a_video_is_opened_only_to_sign_in_to_its_site() {
        let link = recognise("https://www.instagram.com/p/abc/");
        assert_eq!(page_for(&link), None, "a pasted video is looked up, not opened");
        assert_eq!(sign_in_page(&link).as_deref(), Some("https://www.instagram.com/p/abc/"));
        assert_eq!(sign_in_page(&recognise("example.com")).as_deref(), Some("https://example.com"));
    }

    #[test]
    fn typed_text_opens_an_address_a_bare_site_or_a_search_as_on_the_phone() {
        assert_eq!(page_for(&recognise("https://example.com/page")).as_deref(), Some("https://example.com/page"));
        assert_eq!(page_for(&recognise("example.com/videos")).as_deref(), Some("https://example.com/videos"));
        assert_eq!(
            page_for(&recognise("monsoon timelapse 4k")).as_deref(),
            Some("https://www.google.com/search?q=monsoon+timelapse+4k")
        );
        assert_eq!(page_for(&recognise("v1.2")).as_deref(), Some("https://www.google.com/search?q=v1.2"));
        assert_eq!(
            page_for(&recognise("ftp://example.com")).as_deref(),
            Some("https://www.google.com/search?q=ftp%3A%2F%2Fexample.com")
        );
        assert_eq!(page_for(&recognise("https://x.com/someone")).as_deref(), Some("https://x.com/someone"));
        assert_eq!(page_for(&recognise("https://youtu.be/abc")), None, "a video is looked up");
    }

    #[test]
    fn a_page_may_go_only_to_the_web() {
        let ok = |u: &str| may_open(&Url::parse(u).unwrap());
        assert!(ok("https://example.com/") && ok("http://example.com/") && ok("about:blank"));
        assert!(!ok("file:///C:/Users/me/secrets.txt"));
        assert!(!ok("tauri://localhost/index.html") && !ok("ipc://localhost/list_downloads"));
        assert!(!ok("ms-settings:privacy") && !ok("javascript:alert(1)") && !ok("data:text/html,hi"));
    }

    #[test]
    fn files_are_told_apart_by_their_names() {
        let kind = |u: &str| media_kind(&Url::parse(u).unwrap());
        assert_eq!(kind("https://cdn.example/a/clip.MP4?token=1"), Some((Kind::Video, false)));
        assert_eq!(kind("https://cdn.example/song.m4a"), Some((Kind::Audio, false)));
        assert_eq!(kind("https://cdn.example/photo.webp"), Some((Kind::Picture, false)));
        assert_eq!(kind("https://cdn.example/live/index.m3u8"), Some((Kind::Video, true)));
        assert_eq!(kind("https://cdn.example/report.pdf"), None);
        assert_eq!(kind("https://cdn.example/download"), None);
    }

    #[test]
    fn a_pages_video_download_is_saved_in_chayas_list_with_the_page_as_referrer() {
        let folder = Path::new("C:/Users/me/Downloads");
        let d = page_download(
            "https://cdn.example/files/Monsoon%20over%20the%20Ghats.mp4",
            "https://example.com/videos/monsoon",
            "Monsoon videos",
            folder,
            true,
        )
        .unwrap();
        assert_eq!(d.file_name, "Monsoon over the Ghats.mp4");
        assert_eq!(d.kind, Kind::Video);
        assert_eq!(d.page_url, "https://example.com/videos/monsoon");
        assert_eq!(d.source.headers.get("Referer").map(String::as_str), Some("https://example.com/videos/monsoon"));
        assert!(!d.source.stream);
        assert_eq!(d.folder, folder);
    }

    #[test]
    fn a_pages_stream_is_named_after_the_page_and_saved_as_mp4_when_the_app_can() {
        let d = page_download(
            "https://cdn.example/v/master.m3u8",
            "https://example.com/watch",
            "Lecture 4",
            Path::new("D"),
            true,
        )
        .unwrap();
        assert_eq!(d.file_name, "Lecture 4.mp4");
        assert!(d.source.stream);
        assert!(d.source.session, "a page's download is fetched with the browser's sign-in");
        assert!(page_download("https://cdn.example/v/master.m3u8", "https://example.com/", "T", Path::new("D"), false)
            .is_none());
    }

    #[test]
    fn other_files_are_left_to_the_browser() {
        assert!(page_download("https://example.com/report.pdf", "https://example.com/", "T", Path::new("D"), true)
            .is_none());
        assert!(
            page_download("blob:https://example.com/123", "https://example.com/", "T", Path::new("D"), true).is_none()
        );
        assert!(
            page_download("data:video/mp4;base64,AAAA", "https://example.com/", "T", Path::new("D"), true).is_none()
        );
    }

    fn found(url: &str, kind: FoundKind, name: &str) -> Found {
        Found {
            url: url.into(),
            kind,
            name: name.into(),
            host: "cdn.example".into(),
            main: true,
            ad: false,
            referer: "https://player.example/embed/7".into(),
        }
    }

    #[test]
    fn what_a_page_shows_is_saved_under_its_name_with_its_player_as_referrer() {
        let video = found("https://cdn.example/v/a8f3c29e.webm?sig=1", FoundKind::Video, "Monsoon over the Ghats");
        let d = found_download(&video, "https://films.example.com/monsoon", Path::new("D"), false).unwrap();
        assert_eq!(d.file_name, "Monsoon over the Ghats.webm");
        assert_eq!(d.source.headers.get("Referer").map(String::as_str), Some("https://player.example/embed/7"));
        assert_eq!(d.page_url, "https://films.example.com/monsoon");
        assert!(!d.source.stream);
        assert!(d.source.session, "what a page showed is fetched with the browser's sign-in");

        let unnamed = found("https://cdn.example/play?id=7", FoundKind::Audio, "Rain Song");
        assert_eq!(found_download(&unnamed, "u", Path::new("D"), false).unwrap().file_name, "Rain Song.m4a");

        let stream = found("https://cdn.example/v/index.m3u8", FoundKind::Stream, "Lecture 4");
        assert!(found_download(&stream, "u", Path::new("D"), false).is_none(), "not without ffmpeg");
        let d = found_download(&stream, "u", Path::new("D"), true).unwrap();
        assert_eq!((d.file_name.as_str(), d.source.stream, d.kind), ("Lecture 4.mp4", true, Kind::Video));

        let dash = found("https://cdn.example/v/manifest.mpd", FoundKind::Dash, "Lecture 4");
        assert!(found_download(&dash, "u", Path::new("D"), true).is_none(), "DASH from a page comes later");
    }

    #[test]
    fn names_written_with_percent_signs_are_read() {
        assert_eq!(percent_decode("Monsoon%20trip%E2%80%94day%201"), "Monsoon trip—day 1");
        assert_eq!(percent_decode("100%25"), "100%");
        assert_eq!(percent_decode("bad%zz"), "bad%zz");
    }
}
