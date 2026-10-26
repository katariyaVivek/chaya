//! What the built-in browser found on a page, from what its detector saw (`app/src-tauri/src/detector.js`):
//! the page's main video first, named after the page, then its other media, likely ads last; stream pieces and
//! the thumbnails a player shows while seeking never appear, and byte ranges of one file are one item.
//!
//! Ported from the phone's `MediaUrlClassifier`, `AdHosts` and the core of `MediaRanker` and `MediaNamer`. What
//! a page's detector reports comes from the page, so everything is checked here: only http(s) addresses, of
//! bounded length, in bounded number.

use std::collections::HashSet;

use serde::{Deserialize, Serialize};
use url::Url;

/// What the detector reports: what it saw, and about the page.
#[derive(Clone, Debug, Default, Deserialize)]
pub struct Report {
    #[serde(default)]
    pub items: Vec<Seen>,
    #[serde(default)]
    pub meta: PageMeta,
}

/// One address the page's players loaded or named.
#[derive(Clone, Debug, Default, Deserialize)]
pub struct Seen {
    pub url: String,
    /// How it was seen: "video", "audio", "source", "fetch", "xhr", "resource", "meta".
    #[serde(default)]
    pub how: String,
    /// "hls", "dash" or "file", as the detector judged by name, type or the answer's first characters.
    #[serde(default)]
    pub kind: String,
    /// The answer's Content-Type, when it was seen.
    #[serde(default, rename = "type")]
    pub content_type: String,
    /// The frame it was seen in, when that is not the page itself: its requests name it as their referrer.
    #[serde(default)]
    pub frame: String,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PageMeta {
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub og_title: String,
    #[serde(default)]
    pub site_name: String,
    /// The page's one visible h1.
    #[serde(default)]
    pub heading: String,
    #[serde(default)]
    pub players: Vec<Player>,
}

/// A video or audio element on the page.
#[derive(Clone, Debug, Default, Deserialize)]
pub struct Player {
    /// What it plays, or "" for a stream fed to it piece by piece (a `blob:` address).
    #[serde(default)]
    pub src: String,
    #[serde(default)]
    pub w: f64,
    #[serde(default)]
    pub h: f64,
    #[serde(default)]
    pub visible: bool,
    #[serde(default)]
    pub playing: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum FoundKind {
    Video,
    Audio,
    /// An HLS stream, which the app saves as an MP4.
    Stream,
    /// A DASH stream, which the app cannot save from a page yet.
    Dash,
}

/// One thing the window lists.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Found {
    pub url: String,
    pub kind: FoundKind,
    /// "Monsoon over the Ghats", "SoundHelix Song 1", "Video 2".
    pub name: String,
    /// Where it comes from, without "www.".
    pub host: String,
    /// The page's main video.
    pub main: bool,
    /// From an ad network or an ad-only address: listed last, apart.
    pub ad: bool,
    /// The page or frame that loaded it, sent as the referrer when it is downloaded.
    pub referer: String,
}

/// At most this many addresses are read from a report, each at most this long.
const MAX_ITEMS: usize = 200;
const MAX_URL: usize = 4096;

/// The page's media from `report`, the main video first. `page_url` is where the page is.
pub fn found(report: &Report, page_url: &str) -> Vec<Found> {
    let page_host = host_of(page_url).unwrap_or_default();
    let meta = &report.meta;
    let base_name = page_name(meta, &page_host);

    let mut seen_urls = HashSet::new();
    let mut items: Vec<(Found, usize)> = Vec::new();
    for (order, seen) in report.items.iter().take(MAX_ITEMS).enumerate() {
        if seen.url.len() > MAX_URL {
            continue;
        }
        let Ok(url) = Url::parse(&seen.url) else { continue };
        if !matches!(url.scheme(), "http" | "https") {
            continue;
        }
        let url = without_range_params(&url);
        if is_segment(&url) || !seen_urls.insert(url.to_string()) {
            continue;
        }
        let Some(kind) = kind_of(&url, seen) else { continue };
        if matches!(kind, FoundKind::Stream | FoundKind::Dash) && is_thumbnail_track(&url) {
            continue;
        }
        let frame = Url::parse(&seen.frame).ok().filter(|f| matches!(f.scheme(), "http" | "https"));
        items.push((
            Found {
                url: url.to_string(),
                kind,
                name: String::new(),
                host: url.host_str().unwrap_or_default().trim_start_matches("www.").to_string(),
                main: false,
                ad: is_ad_url(&url),
                referer: frame.map(String::from).unwrap_or_else(|| page_url.to_string()),
            },
            order,
        ));
    }

    // The main video: what the biggest visible player plays; for a player fed piece by piece, the page's stream.
    let biggest = meta
        .players
        .iter()
        .filter(|p| p.visible && p.w * p.h >= 120.0 * 90.0)
        .max_by(|a, b| (a.w * a.h).total_cmp(&(b.w * b.h)));
    let playing_file = biggest.and_then(|p| Url::parse(&p.src).ok()).map(|u| without_range_params(&u).to_string());
    let main = items
        .iter()
        .position(|(f, _)| !f.ad && playing_file.as_deref() == Some(f.url.as_str()))
        .or_else(|| items.iter().position(|(f, _)| !f.ad && matches!(f.kind, FoundKind::Stream | FoundKind::Dash)))
        .or_else(|| items.iter().position(|(f, _)| !f.ad && f.kind == FoundKind::Video));
    if let Some(i) = main {
        items[i].0.main = true;
    }

    // Main first, then the rest as they came, ads last.
    items.sort_by_key(|(f, order)| (!f.main, f.ad, *order));
    let mut counts = [0usize; 4];
    for (f, _) in &mut items {
        let n = &mut counts[f.kind as usize];
        *n += 1;
        f.name = if f.main {
            base_name.clone().or_else(|| human_file_name(&f.url)).unwrap_or_else(|| noun(f.kind).to_string())
        } else {
            human_file_name(&f.url).unwrap_or_else(|| format!("{} {}", noun(f.kind), *n))
        };
    }
    items.into_iter().map(|(f, _)| f).collect()
}

fn noun(kind: FoundKind) -> &'static str {
    match kind {
        FoundKind::Video => "Video",
        FoundKind::Audio => "Audio",
        FoundKind::Stream | FoundKind::Dash => "Stream",
    }
}

/// What a page is called: its Open Graph title, else its one heading, else its title, without the site's name.
fn page_name(meta: &PageMeta, page_host: &str) -> Option<String> {
    let site = (!meta.site_name.trim().is_empty()).then_some(meta.site_name.as_str());
    [meta.og_title.as_str(), meta.heading.as_str(), meta.title.as_str()]
        .into_iter()
        .filter_map(|t| clean_page_title(t, site, Some(page_host)))
        .next()
}

/// What `url` is, by its name first, then by what the detector judged. `None` for something that is not media.
fn kind_of(url: &Url, seen: &Seen) -> Option<FoundKind> {
    let ext = extension_of(url);
    let mime = seen.content_type.split(';').next().unwrap_or("").trim().to_ascii_lowercase();
    let by_name = match ext.as_str() {
        "m3u8" => Some(FoundKind::Stream),
        "mpd" => Some(FoundKind::Dash),
        "mp3" | "m4a" | "aac" | "ogg" | "oga" | "opus" | "wav" | "flac" | "mka" => Some(FoundKind::Audio),
        "mp4" | "m4v" | "webm" | "mov" | "mkv" | "avi" | "wmv" | "3gp" | "flv" => Some(FoundKind::Video),
        _ => None,
    };
    by_name.or(match (seen.kind.as_str(), mime.as_str()) {
        ("hls", _) | (_, "application/vnd.apple.mpegurl" | "application/x-mpegurl" | "audio/mpegurl") => {
            Some(FoundKind::Stream)
        }
        ("dash", _) | (_, "application/dash+xml") => Some(FoundKind::Dash),
        (_, m) if m.starts_with("audio/") => Some(FoundKind::Audio),
        (_, m) if m.starts_with("video/") => Some(FoundKind::Video),
        // A player's element: media whatever its address says.
        ("file", _) if matches!(seen.how.as_str(), "video" | "audio" | "source") => {
            Some(if seen.how == "audio" { FoundKind::Audio } else { FoundKind::Video })
        }
        _ => None,
    })
}

/// Pieces of a stream, useless on their own: `.ts`, `.m4s`, `seg-12`, `chunk_3`, `init.mp4`.
pub fn is_segment(url: &Url) -> bool {
    let name = file_name_of(url).to_ascii_lowercase();
    if name.is_empty() {
        return false;
    }
    if matches!(name.rsplit_once('.').map(|(_, e)| e), Some("ts" | "m4s" | "cmfv" | "cmfa" | "m4f")) {
        return true;
    }
    let stem = name.rsplit_once('.').map_or(name.as_str(), |(s, _)| s);
    let words: Vec<&str> = stem.split(['-', '_', '.']).collect();
    // "init", "video_init", "init-stream0"
    if words.contains(&"init") {
        return true;
    }
    // "seg-12", "segment_3", "chunk0001", "frag12"
    let numbered = |word: &str| {
        ["segment", "fragment", "chunk", "frag", "seg"].iter().any(|prefix| {
            word.strip_prefix(prefix).is_some_and(|rest| !rest.is_empty() && rest.chars().all(|c| c.is_ascii_digit()))
        })
    };
    words.iter().any(|w| numbered(w))
        || words.windows(2).any(|pair| {
            ["seg", "segment", "chunk", "frag", "fragment"].contains(&pair[0])
                && !pair[1].is_empty()
                && pair[1].chars().all(|c| c.is_ascii_digit())
        })
}

/// A playlist of the pictures a player shows while seeking: `tiles.m3u8`, `storyboard.mpd`.
pub fn is_thumbnail_track(url: &Url) -> bool {
    let name = file_name_of(url).to_ascii_lowercase();
    let stem = name.rsplit_once('.').map_or(name.as_str(), |(s, _)| s);
    stem.split(['-', '_', '.']).any(|w| {
        matches!(
            w,
            "tile"
                | "tiles"
                | "thumb"
                | "thumbs"
                | "thumbnail"
                | "thumbnails"
                | "sprite"
                | "sprites"
                | "storyboard"
                | "storyboards"
                | "trickplay"
        )
    })
}

/// The whole file a byte-range request asks part of: without its fragment and range parameters.
pub fn without_range_params(url: &Url) -> Url {
    let mut url = url.clone();
    url.set_fragment(None);
    let kept: Vec<(String, String)> = url
        .query_pairs()
        .filter(|(name, value)| {
            let is_range_name =
                matches!(name.to_ascii_lowercase().as_str(), "bytestart" | "byteend" | "range" | "bytes");
            let is_range_value = !value.is_empty()
                && value.split_once('-').map_or(value.chars().all(|c| c.is_ascii_digit()), |(a, b)| {
                    !a.is_empty() && a.chars().all(|c| c.is_ascii_digit()) && b.chars().all(|c| c.is_ascii_digit())
                });
            !(is_range_name && is_range_value)
        })
        .map(|(n, v)| (n.into_owned(), v.into_owned()))
        .collect();
    if url.query().is_some() {
        if kept.is_empty() {
            url.set_query(None);
        } else {
            url.query_pairs_mut().clear().extend_pairs(kept);
        }
    }
    url
}

/// Ad networks whose video is an advertisement; subdomains count too. Curated, as on the phone: a wrong entry
/// hides a real video.
const AD_HOSTS: &[&str] = &[
    "doubleclick.net",
    "2mdn.net",
    "googlesyndication.com",
    "googleadservices.com",
    "imasdk.googleapis.com",
    "adnxs.com",
    "adsrvr.org",
    "amazon-adsystem.com",
    "pubmatic.com",
    "rubiconproject.com",
    "openx.net",
    "casalemedia.com",
    "indexww.com",
    "spotxchange.com",
    "spotx.tv",
    "springserve.com",
    "fwmrm.net",
    "freewheel.tv",
    "stickyadstv.com",
    "teads.tv",
    "outbrain.com",
    "taboola.com",
    "criteo.com",
    "criteo.net",
    "moatads.com",
    "adsafeprotected.com",
    "doubleverify.com",
    "innovid.com",
    "tremorhub.com",
    "vidazoo.com",
    "connatix.com",
    "primis.tech",
    "aniview.com",
    "lkqd.net",
    "bfmio.com",
    "serving-sys.com",
    "flashtalking.com",
    "adform.net",
    "smartadserver.com",
    "yieldmo.com",
    "sharethrough.com",
    "unrulymedia.com",
    "triplelift.com",
    "3lift.com",
    "media.net",
    "inmobi.com",
    "vungle.com",
    "applovin.com",
    "adcolony.com",
    "unityads.unity3d.com",
];

/// Path parts only ad delivery uses.
const AD_PATHS: &[&str] = &[
    "ad",
    "ads",
    "adserver",
    "adunit",
    "adunits",
    "vast",
    "vpaid",
    "vmap",
    "preroll",
    "midroll",
    "postroll",
    "adcreative",
    "adcreatives",
    "ad_creatives",
];

pub fn is_ad_url(url: &Url) -> bool {
    let host = url.host_str().unwrap_or_default().to_ascii_lowercase();
    AD_HOSTS.iter().any(|ad| host == *ad || host.ends_with(&format!(".{ad}")))
        || url.path().to_ascii_lowercase().split('/').any(|part| AD_PATHS.contains(&part))
}

fn file_name_of(url: &Url) -> String {
    url.path_segments().and_then(|mut s| s.next_back().map(String::from)).unwrap_or_default()
}

fn extension_of(url: &Url) -> String {
    file_name_of(url).rsplit_once('.').map(|(_, e)| e.to_ascii_lowercase()).unwrap_or_default()
}

fn host_of(url: &str) -> Option<String> {
    Url::parse(url).ok()?.host_str().map(str::to_ascii_lowercase)
}

/// A page title without the site's name: "Big Buck Bunny - YouTube" → "Big Buck Bunny". `None` when nothing
/// but the site's name is left.
pub fn clean_page_title(title: &str, site_name: Option<&str>, page_host: Option<&str>) -> Option<String> {
    let collapsed = title.split_whitespace().collect::<Vec<_>>().join(" ");
    if collapsed.is_empty() {
        return None;
    }
    let site_keys: Vec<String> = site_name
        .map(comparable)
        .into_iter()
        .chain(page_host.map(brand_of))
        .filter(|k| k.chars().count() >= 3)
        .collect();
    if site_keys.is_empty() {
        return Some(collapsed);
    }
    let parts: Vec<&str> = split_title(&collapsed);
    if parts.len() == 1 {
        return (!is_site_name(&collapsed, &site_keys)).then_some(collapsed);
    }
    let count = parts.len();
    let kept: Vec<&str> = parts.into_iter().filter(|p| !is_site_name(p, &site_keys)).collect();
    match kept.len() {
        0 => None,
        // Nothing was the site's name: the title as it is written.
        n if n == count => Some(collapsed),
        _ => Some(kept.join(" - ")),
    }
}

/// Splits "Title | Site", "Title - Site", "Site: Title" at their separators (with spaces around them).
fn split_title(title: &str) -> Vec<&str> {
    let mut parts = Vec::new();
    let mut start = 0;
    let chars: Vec<(usize, char)> = title.char_indices().collect();
    for (i, &(at, c)) in chars.iter().enumerate() {
        let spaced = i > 0 && chars[i - 1].1 == ' ' && chars.get(i + 1).is_some_and(|(_, n)| *n == ' ');
        let colon = c == ':' && chars.get(i + 1).is_some_and(|(_, n)| *n == ' ');
        if (spaced && matches!(c, '|' | '-' | '–' | '—' | '·' | '•')) || colon {
            let part = title[start..at].trim();
            if !part.is_empty() {
                parts.push(part);
            }
            start = at + c.len_utf8();
        }
    }
    let last = title[start..].trim();
    if !last.is_empty() {
        parts.push(last);
    }
    parts
}

fn is_site_name(part: &str, site_keys: &[String]) -> bool {
    let key = comparable(part);
    !key.is_empty()
        && site_keys.iter().any(|site| {
            key == *site
                || key.contains(site.as_str())
                || (site.contains(key.as_str()) && key.chars().count() as f64 >= site.chars().count() as f64 * 0.6)
        })
}

/// Letters and digits only, lowercase: "YouTube" and "you-tube" compare equal.
fn comparable(text: &str) -> String {
    text.chars().filter(|c| c.is_alphanumeric()).flat_map(char::to_lowercase).collect()
}

/// "www.youtube.com" → "youtube", "m.example.co.uk" → "example".
fn brand_of(host: &str) -> String {
    let labels: Vec<&str> = host.split('.').filter(|l| !l.is_empty()).collect();
    if labels.len() < 2 {
        return comparable(host);
    }
    let second = labels[labels.len() - 2];
    let brand = if second.len() <= 3 && labels.len() >= 3 { labels[labels.len() - 3] } else { second };
    comparable(brand)
}

/// A file name worth showing ("Big Buck Bunny"), or `None` for a hash, an id or packaging words.
pub fn human_file_name(url: &str) -> Option<String> {
    const TECHNICAL: &[&str] = &[
        "mp4",
        "m4v",
        "webm",
        "mov",
        "mkv",
        "m3u8",
        "mpd",
        "ts",
        "m4a",
        "mp3",
        "aac",
        "opus",
        "ogg",
        "flac",
        "wav",
        "h264",
        "h265",
        "hevc",
        "avc",
        "avc1",
        "vp9",
        "av1",
        "hd",
        "sd",
        "fhd",
        "uhd",
        "hls",
        "dash",
        "cmaf",
        "index",
        "master",
        "playlist",
        "manifest",
        "chunklist",
        "video",
        "audio",
        "stream",
        "media",
        "file",
        "default",
        "main",
        "output",
        "source",
        "src",
        "prog",
        "progressive",
        "high",
        "low",
        "med",
        "medium",
        "mobile",
        "desktop",
        "url",
        "dl",
        "download",
        "clip",
    ];
    let url = Url::parse(url).ok()?;
    let raw = file_name_of(&url);
    let decoded = percent_decode(&raw);
    let base = decoded.rsplit_once('.').map_or(decoded.as_str(), |(b, _)| b).trim().to_string();
    if base.is_empty() {
        return None;
    }
    let tokens = words(&base);
    let hexish =
        |t: &str| t.len() >= 8 && t.chars().all(|c| c.is_ascii_hexdigit()) && t.chars().any(|c| c.is_ascii_digit());
    if tokens.iter().any(|t| hexish(t)) {
        return None;
    }
    let letters = base.chars().filter(|c| c.is_alphabetic()).count();
    let digits = base.chars().filter(|c| c.is_ascii_digit()).count();
    if letters == 0 || digits as f64 / (letters + digits) as f64 >= 0.35 {
        return None;
    }
    let human = tokens.iter().filter(|t| {
        t.chars().count() >= 3
            && t.chars().all(char::is_alphabetic)
            && t.chars().any(|c| "aeiouyAEIOUY".contains(c))
            && !TECHNICAL.contains(&t.to_lowercase().as_str())
    });
    (human.count() > 0).then(|| tokens.join(" "))
}

/// Splits a file name into words at separators and lower-to-upper case changes: "bigBuck_bunny" → big Buck bunny.
fn words(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut word = String::new();
    let mut previous_lower = false;
    for c in text.chars() {
        if c.is_whitespace() || matches!(c, '_' | '.' | '+' | '-') {
            if !word.is_empty() {
                out.push(std::mem::take(&mut word));
            }
            previous_lower = false;
            continue;
        }
        if c.is_uppercase() && previous_lower && !word.is_empty() {
            out.push(std::mem::take(&mut word));
        }
        previous_lower = c.is_lowercase();
        word.push(c);
    }
    if !word.is_empty() {
        out.push(word);
    }
    out
}

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

    fn seen(url: &str, how: &str, kind: &str) -> Seen {
        Seen { url: url.into(), how: how.into(), kind: kind.into(), ..Seen::default() }
    }

    fn u(url: &str) -> Url {
        Url::parse(url).unwrap()
    }

    #[test]
    fn stream_pieces_are_told_apart_as_on_the_phone() {
        for piece in [
            "https://cdn.example/v/seg-12.ts",
            "https://cdn.example/v/720/segment_3.m4s",
            "https://cdn.example/v/chunk0001.mp4",
            "https://cdn.example/v/frag12.mp4",
            "https://cdn.example/v/init.mp4",
            "https://cdn.example/v/video_init.mp4",
            "https://cdn.example/v/init-stream0.m4s",
            "https://cdn.example/v/x.cmfv",
        ] {
            assert!(is_segment(&u(piece)), "{piece}");
        }
        for whole in [
            "https://cdn.example/v/index.m3u8",
            "https://cdn.example/v/Monsoon.mp4",
            "https://cdn.example/v/initial-trailer.mp4",
            "https://cdn.example/v/segments-explained.mp4",
        ] {
            assert!(!is_segment(&u(whole)), "{whole}");
        }
    }

    #[test]
    fn byte_ranges_of_one_file_are_that_file() {
        assert_eq!(
            without_range_params(&u("https://cdn.example/v.mp4?bytestart=0&byteend=999&token=a#t=3")).as_str(),
            "https://cdn.example/v.mp4?token=a"
        );
        assert_eq!(
            without_range_params(&u("https://cdn.example/v.mp4?range=0-1000")).as_str(),
            "https://cdn.example/v.mp4"
        );
        assert_eq!(
            without_range_params(&u("https://cdn.example/v.mp4?range=all")).as_str(),
            "https://cdn.example/v.mp4?range=all",
            "a word is not a range"
        );
    }

    #[test]
    fn ads_and_seek_thumbnails_are_recognised() {
        assert!(is_ad_url(&u("https://pagead2.googlesyndication.com/x/creative.mp4")));
        assert!(is_ad_url(&u("https://video.example.com/vast/preroll.mp4")));
        assert!(!is_ad_url(&u("https://cdn.example.com/videos/advanced-lesson.mp4")));
        assert!(is_thumbnail_track(&u("https://cdn.example/v/tiles.m3u8")));
        assert!(is_thumbnail_track(&u("https://cdn.example/v/storyboard.mpd")));
        assert!(!is_thumbnail_track(&u("https://cdn.example/v/index.m3u8")));
    }

    #[test]
    fn page_titles_lose_the_sites_name() {
        let clean = |t: &str, site: Option<&str>, host: &str| clean_page_title(t, site, Some(host));
        assert_eq!(clean("Big Buck Bunny - YouTube", None, "www.youtube.com").as_deref(), Some("Big Buck Bunny"));
        assert_eq!(
            clean("NDTV: Monsoon arrives in Kerala", None, "www.ndtv.com").as_deref(),
            Some("Monsoon arrives in Kerala")
        );
        assert_eq!(
            clean("Lecture 4 | Example Academy", Some("Example Academy"), "learn.example.org").as_deref(),
            Some("Lecture 4")
        );
        assert_eq!(clean("YouTube", None, "www.youtube.com"), None);
        assert_eq!(clean("Well-known places", None, "travel.example.com").as_deref(), Some("Well-known places"));
    }

    #[test]
    fn readable_file_names_are_kept_and_hashes_are_not() {
        assert_eq!(human_file_name("https://cdn.example/Big_Buck_Bunny.mp4").as_deref(), Some("Big Buck Bunny"));
        assert_eq!(human_file_name("https://cdn.example/SoundHelix-Song-1.mp3").as_deref(), Some("Sound Helix Song 1"));
        assert_eq!(human_file_name("https://cdn.example/193039199_mp4_h264_aac_hd_7.mp4"), None);
        assert_eq!(human_file_name("https://cdn.example/a8f3c29e1b7d.mp4"), None);
        assert_eq!(human_file_name("https://cdn.example/master.m3u8"), None);
    }

    #[test]
    fn the_main_video_is_the_biggest_players_named_after_the_page() {
        let report = Report {
            items: vec![
                seen("https://cdn.example/promo/teaser.mp4", "video", "file"),
                seen("https://cdn.example/films/monsoon_hd.mp4", "video", "file"),
                seen("https://cdn.example/films/monsoon_hd.mp4?bytestart=0&byteend=100", "fetch", "file"),
                seen("https://cdn.example/music/Rain_Song.mp3", "audio", "file"),
            ],
            meta: PageMeta {
                title: "Monsoon over the Ghats | Example Films".into(),
                site_name: "Example Films".into(),
                players: vec![
                    Player {
                        src: "https://cdn.example/promo/teaser.mp4".into(),
                        w: 320.0,
                        h: 180.0,
                        visible: true,
                        playing: false,
                    },
                    Player {
                        src: "https://cdn.example/films/monsoon_hd.mp4".into(),
                        w: 1280.0,
                        h: 720.0,
                        visible: true,
                        playing: true,
                    },
                ],
                ..PageMeta::default()
            },
        };
        let list = found(&report, "https://films.example.com/monsoon");
        assert_eq!(list.len(), 3, "the byte range is the same file");
        assert!(list[0].main);
        assert_eq!(list[0].name, "Monsoon over the Ghats");
        assert_eq!(list[0].url, "https://cdn.example/films/monsoon_hd.mp4");
        assert_eq!((list[1].name.as_str(), list[1].kind), ("teaser", FoundKind::Video));
        assert_eq!((list[2].name.as_str(), list[2].kind), ("Rain Song", FoundKind::Audio));
        assert_eq!(list[0].referer, "https://films.example.com/monsoon");
    }

    #[test]
    fn a_player_fed_piece_by_piece_makes_the_pages_stream_main_and_pieces_never_show() {
        let report = Report {
            items: vec![
                seen("https://ads.doubleclick.net/creative/a.mp4", "video", "file"),
                seen("https://cdn.example/v/seg-1.ts", "resource", ""),
                seen("https://cdn.example/v/tiles.m3u8", "fetch", "hls"),
                Seen {
                    url: "https://player.example/api/manifest?id=7".into(),
                    how: "fetch".into(),
                    kind: "hls".into(),
                    frame: "https://player.example/embed/7".into(),
                    ..Seen::default()
                },
            ],
            meta: PageMeta {
                og_title: "Lecture 4: Rivers".into(),
                players: vec![Player { src: String::new(), w: 960.0, h: 540.0, visible: true, playing: true }],
                ..PageMeta::default()
            },
        };
        let list = found(&report, "https://learn.example.org/4");
        let names: Vec<(&str, FoundKind, bool, bool)> =
            list.iter().map(|f| (f.name.as_str(), f.kind, f.main, f.ad)).collect();
        assert_eq!(
            names,
            [("Lecture 4: Rivers", FoundKind::Stream, true, false), ("Video 1", FoundKind::Video, false, true)]
        );
        assert_eq!(list[0].referer, "https://player.example/embed/7", "its frame is its referrer");
        assert_eq!(list[1].host, "ads.doubleclick.net");
    }

    #[test]
    fn what_a_page_reports_is_checked() {
        let long = format!("https://cdn.example/{}.mp4", "a".repeat(5000));
        let report = Report {
            items: vec![
                seen("javascript:alert(1)", "video", "file"),
                seen("file:///C:/secret.mp4", "video", "file"),
                seen("blob:https://example.com/123", "video", "file"),
                seen(&long, "video", "file"),
                seen("https://cdn.example/page.html", "fetch", ""),
                seen("https://cdn.example/watch", "video", "file"),
                Seen { frame: "file:///C:/frame.html".into(), ..seen("https://cdn.example/a.webm", "fetch", "file") },
            ],
            meta: PageMeta::default(),
        };
        let list = found(&report, "https://example.com/");
        let urls: Vec<&str> = list.iter().map(|f| f.url.as_str()).collect();
        assert_eq!(
            urls,
            ["https://cdn.example/watch", "https://cdn.example/a.webm"],
            "an element's own source is media"
        );
        assert_eq!(list[1].referer, "https://example.com/", "a frame that is not on the web is not a referrer");
    }
}
