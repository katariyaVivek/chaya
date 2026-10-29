//! What the engine found at a link, read from the JSON `chaya_engine.extract` prints.
//!
//! Ported from the phone's `PlatformMedia` (`app/src/main/java/com/chaya/app/platform/PlatformMedia.kt`),
//! with the same reading of yt-dlp's fields.

use std::collections::BTreeMap;

use serde::Serialize;
use serde_json::{Map, Value};

/// One file or stream yt-dlp found for a video: a quality, audio only, or a ready-made combination.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Format {
    pub id: String,
    pub url: String,
    pub ext: Option<String>,
    pub protocol: Option<String>,
    pub width: Option<i64>,
    pub height: Option<i64>,
    pub fps: Option<f64>,
    pub video_codec: Option<String>,
    pub audio_codec: Option<String>,
    /// Average bitrate in kilobits per second, when yt-dlp knows it.
    pub bitrate_kbps: Option<f64>,
    pub audio_bitrate_kbps: Option<f64>,
    pub size_bytes: Option<u64>,
    pub note: Option<String>,
    pub language: Option<String>,
    /// Headers the file's server expects (referer, user agent...), applied when downloading.
    pub headers: BTreeMap<String, String>,
    /// yt-dlp's rank for an audio track's language: 10 original, 5 the site's default, -1 other dubs.
    pub language_preference: Option<i64>,
    /// "SDR", "HDR10", "HLG" and so on.
    pub dynamic_range: Option<String>,
    /// A DASH format's pieces in order (its initialization section first), as yt-dlp found them in the
    /// manifest; empty for anything else.
    pub fragments: Vec<String>,
}

impl Format {
    // yt-dlp writes "none" for a missing track. Anything else, including unknown, counts as present:
    // simple extractors often leave the codecs out of a file that has both.
    pub fn has_video(&self) -> bool {
        self.video_codec.as_deref() != Some("none")
    }

    pub fn has_audio(&self) -> bool {
        self.audio_codec.as_deref() != Some("none")
    }

    /// No picture, and sound that is present or at least not ruled out.
    pub fn is_audio_only(&self) -> bool {
        let audio = self.audio_codec.as_deref();
        audio != Some("none")
            && (self.video_codec.as_deref() == Some("none")
                || (self.video_codec.is_none() && audio.is_some_and(|a| !a.is_empty())))
    }

    /// A plain file at one address, as opposed to an HLS or DASH manifest of many pieces.
    pub fn is_direct_file(&self) -> bool {
        let protocol = self.protocol.as_deref().unwrap_or("").to_ascii_lowercase();
        let address = self.url.to_ascii_lowercase();
        if protocol.contains("m3u8") || protocol.contains("dash") || protocol.contains("m3u") {
            return false;
        }
        !address.contains(".m3u8") && !address.contains(".mpd")
    }

    /// An HLS stream: a playlist of pieces.
    pub fn is_hls(&self) -> bool {
        let protocol = self.protocol.as_deref().unwrap_or("").to_ascii_lowercase();
        protocol.contains("m3u") || (!protocol.contains("dash") && self.url.to_ascii_lowercase().contains(".m3u8"))
    }

    /// A stream the app can save (`download::stream`): HLS, whose playlist lists its pieces, or DASH, whose pieces
    /// yt-dlp listed. A DASH stream without them it cannot.
    pub fn is_savable_stream(&self) -> bool {
        !self.is_direct_file() && (self.is_hls() || !self.fragments.is_empty())
    }

    /// A single file with both picture and sound.
    pub fn is_complete_file(&self) -> bool {
        self.is_direct_file() && self.has_video() && self.has_audio()
    }

    /// YouTube's "DRC" tracks are the same sound with its loudness range squeezed.
    pub fn is_loudness_compressed(&self) -> bool {
        self.note.as_deref().is_some_and(|n| n.to_ascii_lowercase().contains("drc"))
            || self.id.to_ascii_lowercase().ends_with("-drc")
    }

    /// HDR looks washed out where it cannot be shown, so SDR is preferred.
    pub fn is_standard_range(&self) -> bool {
        self.dynamic_range.as_deref().is_none_or(|r| r.eq_ignore_ascii_case("sdr"))
    }

    /// The side people name a quality by: 1920x1080 and 1080x1920 are both "1080p".
    pub fn quality(&self) -> Option<i64> {
        match (self.width, self.height) {
            (Some(w), Some(h)) if w > 0 && h > 0 => Some(w.min(h)),
            (_, Some(h)) if h > 0 => Some(h),
            _ => None,
        }
    }
}

/// One picture or video in a post: an Instagram carousel, or a tweet with several photos.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct PostItem {
    pub url: String,
    #[serde(rename = "isVideo")]
    pub is_video: bool,
    pub ext: String,
    pub width: Option<i64>,
    pub height: Option<i64>,
    #[serde(skip)]
    pub headers: BTreeMap<String, String>,
}

/// Subtitles a site has for a video, as the engine lists them (`--subtitles`): a language ("en", "pt-BR", or
/// YouTube's "en-orig" for the language spoken), where the file is (WebVTT or SRT), and whether the site made it
/// by machine.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct SubtitleTrack {
    pub language: String,
    pub url: String,
    pub automatic: bool,
}

/// Everything known about a link: what it is and what it can be saved as. A post with pictures has
/// `items`, every picture and video in it, and no `formats`.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Media {
    pub id: Option<String>,
    pub title: String,
    pub author: Option<String>,
    pub duration_seconds: Option<f64>,
    pub thumbnail_url: Option<String>,
    pub page_url: Option<String>,
    pub extractor: Option<String>,
    pub is_live: bool,
    pub formats: Vec<Format>,
    pub items: Vec<PostItem>,
    /// The language spoken in the video, when the site says.
    pub language: Option<String>,
    pub subtitles: Vec<SubtitleTrack>,
}

/// Why a link could not be turned into something to download.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum FailureKind {
    BotCheck,
    Private,
    NeedsLogin,
    Geo,
    Unsupported,
    Unavailable,
    Live,
    NoFormat,
    Network,
    /// The engine itself did not run or answered nonsense.
    Engine,
    Unknown,
}

impl FailureKind {
    /// What the window says, as the phone does.
    pub fn message(self) -> &'static str {
        match self {
            FailureKind::BotCheck => {
                "YouTube wants to check that you're not a bot. Signing in to YouTube in Chaya's browser can help."
            }
            FailureKind::Private => "This video is private.",
            FailureKind::NeedsLogin => "This needs a signed-in account.",
            FailureKind::Geo => "This video isn't available in your country.",
            FailureKind::Unsupported => "This link isn't supported.",
            FailureKind::Unavailable => "This video is unavailable or has been removed.",
            FailureKind::Live => "This is a live broadcast. Live videos can't be saved yet.",
            FailureKind::NoFormat => "Chaya can't save any of this video's formats yet.",
            FailureKind::Network => "Couldn't reach the site. Check your connection and try again.",
            FailureKind::Engine => "The video finder couldn't start. Try again in a moment.",
            FailureKind::Unknown => "Couldn't find a video to download at this link.",
        }
    }

    /// The engine's lowercase kind names; anything unrecognised is `Unknown`.
    pub fn from_engine(name: Option<&str>) -> FailureKind {
        match name.unwrap_or("").to_ascii_lowercase().as_str() {
            "bot_check" => FailureKind::BotCheck,
            "private" => FailureKind::Private,
            "needs_login" => FailureKind::NeedsLogin,
            "geo" => FailureKind::Geo,
            "unsupported" => FailureKind::Unsupported,
            "unavailable" => FailureKind::Unavailable,
            "live" => FailureKind::Live,
            "no_format" => FailureKind::NoFormat,
            "network" => FailureKind::Network,
            "engine" => FailureKind::Engine,
            _ => FailureKind::Unknown,
        }
    }
}

/// A failed lookup. `detail` is the engine's own words, for diagnostics, never shown as the message.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Failure {
    pub kind: FailureKind,
    pub detail: Option<String>,
}

impl Failure {
    pub fn new(kind: FailureKind, detail: impl Into<String>) -> Failure {
        Failure { kind, detail: Some(detail.into()) }
    }
}

/// Reads what `chaya_engine.extract` printed: `{"media": {...}}` or `{"error": {"kind", "detail"}}`.
pub fn parse(json: &str) -> Result<Media, Failure> {
    let root: Value = serde_json::from_str(json)
        .map_err(|e| Failure::new(FailureKind::Engine, format!("Unreadable engine output: {e}")))?;
    if let Some(error) = root.get("error").and_then(Value::as_object) {
        return Err(Failure {
            kind: FailureKind::from_engine(text(error, "kind").as_deref()),
            detail: text(error, "detail"),
        });
    }
    let media = root
        .get("media")
        .and_then(Value::as_object)
        .ok_or_else(|| Failure::new(FailureKind::Engine, "The engine returned neither media nor an error."))?;

    let list = |key| media.get(key).and_then(Value::as_array).map(Vec::as_slice).unwrap_or_default();
    Ok(Media {
        id: text(media, "id"),
        title: text(media, "title").unwrap_or_else(|| "Untitled".into()),
        author: text(media, "uploader").or_else(|| text(media, "channel")),
        duration_seconds: number(media, "duration").filter(|d| *d > 0.0),
        thumbnail_url: text(media, "thumbnail"),
        page_url: text(media, "webpage_url"),
        extractor: text(media, "extractor_key"),
        is_live: media.get("is_live").and_then(Value::as_bool).unwrap_or(false),
        formats: list("formats").iter().filter_map(Value::as_object).filter_map(format).collect(),
        items: list("items").iter().filter_map(Value::as_object).filter_map(item).collect(),
        language: text(media, "language"),
        subtitles: list("subtitles").iter().filter_map(Value::as_object).filter_map(subtitle_track).collect(),
    })
}

fn subtitle_track(t: &Map<String, Value>) -> Option<SubtitleTrack> {
    Some(SubtitleTrack {
        language: text(t, "language")?,
        url: text(t, "url").filter(|u| u.starts_with("https://") || u.starts_with("http://"))?,
        automatic: t.get("automatic").and_then(Value::as_bool).unwrap_or(false),
    })
}

fn format(f: &Map<String, Value>) -> Option<Format> {
    let url = text(f, "url")?;
    Some(Format {
        id: text(f, "format_id").unwrap_or_else(|| url.clone()),
        ext: text(f, "ext"),
        protocol: text(f, "protocol"),
        width: integer(f, "width"),
        height: integer(f, "height"),
        fps: number(f, "fps"),
        video_codec: text(f, "vcodec"),
        audio_codec: text(f, "acodec"),
        bitrate_kbps: number(f, "tbr"),
        audio_bitrate_kbps: number(f, "abr"),
        size_bytes: number(f, "filesize").or_else(|| number(f, "filesize_approx")).map(|n| n as u64),
        note: text(f, "format_note"),
        language: text(f, "language"),
        headers: headers(f),
        language_preference: integer(f, "language_preference"),
        dynamic_range: text(f, "dynamic_range"),
        fragments: f
            .get("fragments")
            .and_then(Value::as_array)
            .map(|pieces| pieces.iter().filter_map(|p| p.as_str().map(String::from)).collect())
            .unwrap_or_default(),
        url,
    })
}

fn item(i: &Map<String, Value>) -> Option<PostItem> {
    Some(PostItem {
        url: text(i, "url")?,
        is_video: text(i, "kind").as_deref() == Some("video"),
        ext: text(i, "ext").unwrap_or_else(|| "jpg".into()),
        width: integer(i, "width"),
        height: integer(i, "height"),
        headers: headers(i),
    })
}

fn headers(object: &Map<String, Value>) -> BTreeMap<String, String> {
    object
        .get("http_headers")
        .and_then(Value::as_object)
        .map(|h| h.iter().filter_map(|(k, v)| v.as_str().map(|v| (k.clone(), v.to_string()))).collect())
        .unwrap_or_default()
}

fn text(object: &Map<String, Value>, key: &str) -> Option<String> {
    let value = match object.get(key)? {
        Value::String(s) => s.trim().to_string(),
        Value::Number(n) => n.to_string(),
        _ => return None,
    };
    (!value.is_empty()).then_some(value)
}

fn number(object: &Map<String, Value>, key: &str) -> Option<f64> {
    object.get(key)?.as_f64().filter(|n| n.is_finite())
}

fn integer(object: &Map<String, Value>, key: &str) -> Option<i64> {
    number(object, key).map(|n| n as i64)
}

#[cfg(test)]
mod tests {
    use super::*;

    const FULL: &str = r#"{"media": {
        "id": "dQw4w9WgXcQ", "title": "Never Gonna Give You Up", "uploader": "Rick Astley", "channel": "RickAstleyVEVO",
        "duration": 213, "webpage_url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "extractor_key": "Youtube",
        "is_live": false, "thumbnail": "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
        "formats": [
          {"format_id": "18", "url": "https://rr1.googlevideo.com/videoplayback?id=18", "ext": "mp4", "protocol": "https",
           "width": 640, "height": 360, "fps": 25, "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "tbr": 392.5,
           "abr": 96, "filesize": null, "filesize_approx": 10485760, "format_note": "360p", "dynamic_range": "SDR",
           "http_headers": {"User-Agent": "Mozilla/5.0", "Accept-Language": "en-us,en;q=0.5"}},
          {"format_id": "140", "url": "https://rr1.googlevideo.com/videoplayback?id=140", "ext": "m4a",
           "vcodec": "none", "acodec": "mp4a.40.2", "abr": 129.5, "filesize": 3433514, "filesize_approx": 3500000,
           "language": "en", "language_preference": 10},
          {"format_id": "no-address", "url": null, "ext": "mp4"}
        ]}}"#;

    #[test]
    fn a_video_with_its_formats_is_read_in_full() {
        let media = parse(FULL).unwrap();
        assert_eq!(media.id.as_deref(), Some("dQw4w9WgXcQ"));
        assert_eq!(media.title, "Never Gonna Give You Up");
        assert_eq!(media.author.as_deref(), Some("Rick Astley"));
        assert_eq!(media.duration_seconds, Some(213.0));
        assert_eq!(media.thumbnail_url.as_deref(), Some("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg"));
        assert_eq!(media.extractor.as_deref(), Some("Youtube"));
        assert!(!media.is_live);
        // The format without an address is skipped
        assert_eq!(media.formats.iter().map(|f| f.id.as_str()).collect::<Vec<_>>(), ["18", "140"]);
        assert!(media.items.is_empty());
    }

    #[test]
    fn a_format_keeps_its_details() {
        let media = parse(FULL).unwrap();
        let video = &media.formats[0];
        assert_eq!(video.ext.as_deref(), Some("mp4"));
        assert_eq!((video.width, video.height, video.fps), (Some(640), Some(360), Some(25.0)));
        assert_eq!(video.video_codec.as_deref(), Some("avc1.42001E"));
        assert_eq!(video.bitrate_kbps, Some(392.5));
        assert_eq!(video.audio_bitrate_kbps, Some(96.0));
        assert_eq!(video.headers.get("User-Agent").map(String::as_str), Some("Mozilla/5.0"));
        assert_eq!(video.dynamic_range.as_deref(), Some("SDR"));
        assert_eq!(video.language_preference, None);
        // The approximate size stands in for an unknown exact one; an exact one wins
        assert_eq!(video.size_bytes, Some(10_485_760));
        let audio = &media.formats[1];
        assert_eq!(audio.size_bytes, Some(3_433_514));
        assert_eq!(audio.language.as_deref(), Some("en"));
        assert_eq!(audio.language_preference, Some(10));
    }

    #[test]
    fn missing_and_null_fields_stay_missing() {
        let media = parse(
            r#"{"media": {"title": null, "uploader": null, "channel": "The Channel", "duration": 0,
            "thumbnail": "  ", "formats": null}}"#,
        )
        .unwrap();
        assert_eq!(media.title, "Untitled");
        assert_eq!(media.author.as_deref(), Some("The Channel"));
        assert_eq!(media.duration_seconds, None);
        assert_eq!(media.thumbnail_url, None);
        assert!(media.formats.is_empty());
    }

    #[test]
    fn a_dash_formats_pieces_are_read_in_order() {
        let media = parse(
            r#"{"media": {"title": "T", "formats": [
            {"format_id": "v720", "url": "https://cdn.example/manifest.mpd", "protocol": "http_dash_segments",
             "fragments": ["https://cdn.example/init.mp4", "https://cdn.example/1.m4s"]},
            {"format_id": "18", "url": "https://cdn.example/18"}
        ]}}"#,
        )
        .unwrap();
        assert_eq!(media.formats[0].fragments, ["https://cdn.example/init.mp4", "https://cdn.example/1.m4s"]);
        assert!(media.formats[1].fragments.is_empty());
    }

    #[test]
    fn a_videos_language_and_its_sites_subtitles_are_read() {
        let media = parse(
            r#"{"media": {"title": "T", "language": "en", "subtitles": [
            {"language": "en", "url": "https://www.youtube.com/api/timedtext?v=1&lang=en&fmt=vtt", "automatic": false},
            {"language": "en-orig", "url": "https://www.youtube.com/api/timedtext?v=1&kind=asr", "automatic": true},
            {"language": "fr", "url": "file:///C:/secret.txt"},
            {"url": "https://subs.example/none"}
        ]}}"#,
        )
        .unwrap();
        assert_eq!(media.language.as_deref(), Some("en"));
        let languages: Vec<(&str, bool)> = media.subtitles.iter().map(|t| (t.language.as_str(), t.automatic)).collect();
        assert_eq!(languages, [("en", false), ("en-orig", true)], "only tracks with a language and a web address");
        assert!(parse(r#"{"media": {"title": "T"}}"#).unwrap().subtitles.is_empty());
    }

    #[test]
    fn a_live_stream_is_flagged() {
        assert!(parse(r#"{"media": {"title": "Live", "is_live": true}}"#).unwrap().is_live);
    }

    #[test]
    fn an_engine_error_keeps_its_kind_and_detail() {
        let failure = parse(r#"{"error": {"kind": "private", "detail": "Private video. Sign in"}}"#).unwrap_err();
        assert_eq!(failure, Failure { kind: FailureKind::Private, detail: Some("Private video. Sign in".into()) });
    }

    #[test]
    fn each_engine_kind_maps_to_its_own_and_unfamiliar_ones_to_unknown() {
        for (name, kind) in [
            ("bot_check", FailureKind::BotCheck),
            ("needs_login", FailureKind::NeedsLogin),
            ("geo", FailureKind::Geo),
            ("unsupported", FailureKind::Unsupported),
            ("unavailable", FailureKind::Unavailable),
            ("network", FailureKind::Network),
            ("UNAVAILABLE", FailureKind::Unavailable),
            ("brand_new", FailureKind::Unknown),
        ] {
            assert_eq!(FailureKind::from_engine(Some(name)), kind, "{name}");
        }
        assert_eq!(parse(r#"{"error": {}}"#).unwrap_err().kind, FailureKind::Unknown);
    }

    #[test]
    fn output_that_is_not_what_the_engine_promises_is_an_engine_problem() {
        assert_eq!(parse("Traceback (most recent call last):").unwrap_err().kind, FailureKind::Engine);
        assert_eq!(parse(r#"{"something": "else"}"#).unwrap_err().kind, FailureKind::Engine);
    }

    #[test]
    fn a_posts_pictures_and_videos_are_read_in_order() {
        let media = parse(
            r#"{"media": {"title": "A post", "formats": [], "items": [
            {"url": "https://cdn.example/1.jpg", "kind": "image", "ext": "jpg", "width": 1080, "height": 1350,
             "http_headers": {"Referer": "https://www.instagram.com/"}},
            {"url": "https://cdn.example/2.mp4", "kind": "video", "ext": "mp4"},
            {"kind": "image"}
        ]}}"#,
        )
        .unwrap();
        assert_eq!(media.items.len(), 2);
        assert!(!media.items[0].is_video && media.items[1].is_video);
        assert_eq!(media.items[0].headers.get("Referer").map(String::as_str), Some("https://www.instagram.com/"));
    }

    #[test]
    fn tracks_and_files_are_told_apart_as_on_the_phone() {
        let f = |v: Option<&str>, a: Option<&str>| Format {
            video_codec: v.map(Into::into),
            audio_codec: a.map(Into::into),
            url: "https://cdn.example/f".into(),
            ..Format::default()
        };
        assert!(!f(Some("none"), Some("mp4a")).has_video());
        assert!(f(None, None).has_video() && f(None, None).has_audio());
        assert!(f(Some("none"), Some("mp4a")).is_audio_only());
        assert!(f(None, Some("mp4a")).is_audio_only());
        assert!(!f(None, None).is_audio_only());
        assert!(!f(Some("none"), Some("none")).is_audio_only(), "a storyboard is neither");

        let manifest = Format { protocol: Some("m3u8_native".into()), ..f(None, None) };
        assert!(!manifest.is_direct_file() && manifest.is_hls());
        let mpd = Format { url: "https://cdn.example/a.MPD".into(), ..f(None, None) };
        assert!(!mpd.is_direct_file() && !mpd.is_hls());
        let dash = Format { protocol: Some("http_dash_segments".into()), ..f(None, None) };
        assert!(!dash.is_direct_file() && !dash.is_hls());
        let by_address = Format { url: "https://cdn.example/v/index.m3u8?t=1".into(), ..f(None, None) };
        assert!(by_address.is_hls() && !f(None, None).is_hls());
        assert!(manifest.is_savable_stream() && !dash.is_savable_stream(), "DASH only with its pieces");
        let listed = Format { fragments: vec!["https://cdn.example/init.mp4".into()], ..dash };
        assert!(listed.is_savable_stream() && !f(None, None).is_savable_stream());

        let drc = Format { id: "251-drc".into(), ..f(None, None) };
        assert!(drc.is_loudness_compressed());
        assert!(Format { note: Some("medium, DRC".into()), ..f(None, None) }.is_loudness_compressed());

        assert!(f(None, None).is_standard_range());
        assert!(!Format { dynamic_range: Some("HDR10".into()), ..f(None, None) }.is_standard_range());

        let vertical = Format { width: Some(1080), height: Some(1920), ..f(None, None) };
        assert_eq!(vertical.quality(), Some(1080));
        assert_eq!(Format { height: Some(720), ..f(None, None) }.quality(), Some(720));
        assert_eq!(Format { height: Some(0), ..f(None, None) }.quality(), None);
    }

    #[test]
    fn every_kind_has_a_message_a_person_can_read() {
        for kind in [
            FailureKind::BotCheck,
            FailureKind::Private,
            FailureKind::NeedsLogin,
            FailureKind::Geo,
            FailureKind::Unsupported,
            FailureKind::Unavailable,
            FailureKind::Live,
            FailureKind::NoFormat,
            FailureKind::Network,
            FailureKind::Engine,
            FailureKind::Unknown,
        ] {
            let message = kind.message();
            // The window's own words: the phone's "this browser" is "Chaya's browser" here.
            assert!(message.ends_with('.') && !message.contains("this browser"), "{kind:?}: {message}");
        }
    }
}
