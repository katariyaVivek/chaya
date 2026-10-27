//! Telling what a pasted link is: one video or post on a supported site, an account whose posts
//! can be saved together, some other web address, or not a link at all.
//!
//! Ported from the Android app's `PlatformMatcher` and `ProfileMatcher`
//! (`app/src/main/java/com/chaya/app/platform/`), with the same test table, so both apps agree on
//! which links count.

use serde::{Deserialize, Serialize};
use url::Url;

/// The sites Chaya can fetch a video or post from when given a link to it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Platform {
    YouTube,
    Instagram,
    TikTok,
    /// X, formerly Twitter.
    X,
}

impl Platform {
    pub fn display_name(self) -> &'static str {
        match self {
            Platform::YouTube => "YouTube",
            Platform::Instagram => "Instagram",
            Platform::TikTok => "TikTok",
            Platform::X => "X",
        }
    }
}

/// A link to one video or post. `url` is the link as given (trimmed); `id` names the item on its site.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct MediaLink {
    pub platform: Platform,
    pub id: String,
    pub url: String,
}

impl MediaLink {
    /// What a lookup is finding. An Instagram post or a tweet may hold only pictures, so for those
    /// it is the post; a reel, a YouTube video or a TikTok is a video.
    pub fn looking_for(&self) -> &'static str {
        match self.platform {
            Platform::X => "post",
            Platform::Instagram if self.id.split('/').next() == Some("p") => "post",
            _ => "video",
        }
    }
}

/// An account on Instagram or X, whose posts can be saved together as one ZIP.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct AccountLink {
    pub platform: Platform,
    pub username: String,
    pub url: String,
}

/// What a pasted piece of text is, for the address bar.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Link {
    Media {
        #[serde(flatten)]
        link: MediaLink,
        #[serde(rename = "site")]
        site: &'static str,
        #[serde(rename = "lookingFor")]
        looking_for: &'static str,
    },
    Account {
        #[serde(flatten)]
        link: AccountLink,
        site: &'static str,
    },
    /// An http(s) address on some other site, or a page on a supported site that is neither.
    Web { url: String },
    /// Words, or anything else that is not an http(s) address.
    Text { text: String },
}

/// Recognises `input` as the address bar would.
pub fn recognise(input: &str) -> Link {
    let text = input.trim();
    if let Some(link) = match_media(text) {
        let site = link.platform.display_name();
        let looking_for = link.looking_for();
        return Link::Media { link, site, looking_for };
    }
    if let Some(link) = match_account(text) {
        let site = link.platform.display_name();
        return Link::Account { link, site };
    }
    match parse_http(text) {
        Some(_) => Link::Web { url: text.to_string() },
        None => Link::Text { text: text.to_string() },
    }
}

const YOUTUBE_HOSTS: &[&str] = &["youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com"];
const TIKTOK_HOSTS: &[&str] = &["tiktok.com", "www.tiktok.com", "m.tiktok.com", "vm.tiktok.com", "vt.tiktok.com"];
const INSTAGRAM_HOSTS: &[&str] = &["instagram.com", "www.instagram.com", "m.instagram.com"];
const X_HOSTS: &[&str] = &["twitter.com", "www.twitter.com", "mobile.twitter.com", "x.com", "www.x.com", "t.co"];

/// Recognises a link to a single video or post. Profile pages, search pages and other sites are `None`.
pub fn match_media(input: &str) -> Option<MediaLink> {
    let url = input.trim();
    let parsed = parse_http(url)?;
    let host = parsed.host_str()?.to_ascii_lowercase();
    let parts = segments(&parsed);
    let found = |platform, id: &str| Some(MediaLink { platform, id: id.to_string(), url: url.to_string() });

    if YOUTUBE_HOSTS.contains(&host.as_str()) || host.ends_with(".youtube.com") || host == "youtu.be" {
        if host == "youtu.be" {
            return parts.first().and_then(|id| found(Platform::YouTube, id));
        }
        if let Some(id) = query_param(&parsed, "v") {
            return found(Platform::YouTube, id);
        }
        if parts.len() >= 2 && matches!(parts[0], "shorts" | "embed" | "live") {
            return found(Platform::YouTube, parts[1]);
        }
        return None;
    }
    if TIKTOK_HOSTS.contains(&host.as_str()) {
        if let Some(i) = parts.iter().position(|p| *p == "video") {
            if let Some(id) = parts.get(i + 1) {
                return found(Platform::TikTok, id);
            }
        }
        if parts.len() >= 2 && parts[0] == "t" {
            return found(Platform::TikTok, parts[1]);
        }
        if host == "vm.tiktok.com" || host == "vt.tiktok.com" {
            return parts.first().and_then(|id| found(Platform::TikTok, id));
        }
        return None;
    }
    if INSTAGRAM_HOSTS.contains(&host.as_str()) {
        if parts.len() >= 2 && matches!(parts[0], "p" | "reel" | "reels" | "tv" | "stories") {
            let id = parts.iter().take(3).copied().collect::<Vec<_>>().join("/");
            return found(Platform::Instagram, &id);
        }
        return None;
    }
    if X_HOSTS.contains(&host.as_str()) {
        if host == "t.co" {
            return parts.first().and_then(|id| found(Platform::X, id));
        }
        if let Some(i) = parts.iter().position(|p| *p == "status") {
            if let Some(id) = parts.get(i + 1) {
                return found(Platform::X, id);
            }
        }
        return None;
    }
    None
}

const ACCOUNT_INSTAGRAM_HOSTS: &[&str] = &["instagram.com", "www.instagram.com", "m.instagram.com"];
const ACCOUNT_X_HOSTS: &[&str] =
    &["x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", "mobile.x.com"];
const INSTAGRAM_PAGES: &[&str] = &[
    "p",
    "reel",
    "reels",
    "tv",
    "stories",
    "explore",
    "accounts",
    "direct",
    "about",
    "developer",
    "legal",
    "web",
    "challenge",
    "emails",
    "privacy",
    "terms",
    "session",
    "oauth",
    "directory",
];
const X_PAGES: &[&str] = &[
    "home",
    "explore",
    "notifications",
    "messages",
    "i",
    "settings",
    "search",
    "compose",
    "login",
    "logout",
    "signup",
    "tos",
    "privacy",
    "hashtag",
    "intent",
    "share",
    "jobs",
    "download",
    "account",
];
const INSTAGRAM_TABS: &[&str] = &["posts", "reels", "tagged"];
const X_TABS: &[&str] = &["media", "with_replies", "highlights", "likes", "photo"];

/// Recognises a link to an account on Instagram or X: instagram.com/someone, x.com/someone, or their
/// posts and media pages. A post, a reel or any of the sites' own pages is not an account.
pub fn match_account(input: &str) -> Option<AccountLink> {
    let url = input.trim();
    let parsed = parse_http(url)?;
    let host = parsed.host_str()?.to_ascii_lowercase();
    let parts = segments(&parsed);
    let name = *parts.first()?;
    if parts.len() > 2 {
        return None;
    }
    let tab = parts.get(1).copied();
    let lower = name.to_ascii_lowercase();
    let platform = if ACCOUNT_INSTAGRAM_HOSTS.contains(&host.as_str())
        && !INSTAGRAM_PAGES.contains(&lower.as_str())
        && tab.is_none_or(|t| INSTAGRAM_TABS.contains(&t))
    {
        Platform::Instagram
    } else if ACCOUNT_X_HOSTS.contains(&host.as_str())
        && !X_PAGES.contains(&lower.as_str())
        && tab.is_none_or(|t| X_TABS.contains(&t))
    {
        Platform::X
    } else {
        return None;
    };
    if !is_username(platform, name) {
        return None;
    }
    Some(AccountLink { platform, username: name.to_string(), url: url.to_string() })
}

fn is_username(platform: Platform, name: &str) -> bool {
    let ok = |max: usize, extra: &[char]| {
        (1..=max).contains(&name.len()) && name.chars().all(|c| c.is_ascii_alphanumeric() || extra.contains(&c))
    };
    match platform {
        Platform::Instagram => ok(30, &['.', '_']) && !name.starts_with('.') && !name.ends_with('.'),
        Platform::X => ok(15, &['_']),
        _ => false,
    }
}

fn parse_http(text: &str) -> Option<Url> {
    let url = Url::parse(text).ok()?;
    matches!(url.scheme(), "http" | "https").then_some(url)
}

/// The path's non-empty segments, still percent-encoded, like Java's `URI.rawPath`.
fn segments(url: &Url) -> Vec<&str> {
    url.path().split('/').filter(|s| !s.is_empty()).collect()
}

/// The first non-empty value of `name` in the query, still percent-encoded.
fn query_param<'a>(url: &'a Url, name: &str) -> Option<&'a str> {
    url.query()?
        .split('&')
        .map(|pair| pair.split_once('=').unwrap_or((pair, "")))
        .find(|(key, value)| *key == name && !value.is_empty())
        .map(|(_, value)| value)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_media(url: &str, platform: Platform, id: &str) {
        let link = match_media(url).unwrap_or_else(|| panic!("{url} did not match"));
        assert_eq!(link.platform, platform, "platform of {url}");
        assert_eq!(link.id, id, "id of {url}");
    }

    #[test]
    fn links_from_the_android_table_match() {
        assert_media("https://www.youtube.com/watch?v=dQw4w9WgXcQ", Platform::YouTube, "dQw4w9WgXcQ");
        assert_media("https://youtu.be/dQw4w9WgXcQ", Platform::YouTube, "dQw4w9WgXcQ");
        assert_media("https://youtube.com/shorts/abc123", Platform::YouTube, "abc123");
        assert_media("https://music.youtube.com/watch?v=music123", Platform::YouTube, "music123");
        assert_media(
            "https://www.tiktok.com/@creator/video/7390123456789012345",
            Platform::TikTok,
            "7390123456789012345",
        );
        assert_media("https://vm.tiktok.com/ZMabc123/", Platform::TikTok, "ZMabc123");
        assert_media("https://www.tiktok.com/t/ZPabc123/", Platform::TikTok, "ZPabc123");
        assert_media("https://www.instagram.com/p/Cabc123/", Platform::Instagram, "p/Cabc123");
        assert_media("https://www.instagram.com/reel/Cdef456/", Platform::Instagram, "reel/Cdef456");
        assert_media(
            "https://www.instagram.com/stories/account/123456789/",
            Platform::Instagram,
            "stories/account/123456789",
        );
        assert_media("https://twitter.com/user/status/1234567890", Platform::X, "1234567890");
        assert_media("https://x.com/user/status/1234567890", Platform::X, "1234567890");
        assert_media("https://t.co/abc123", Platform::X, "abc123");
    }

    #[test]
    fn other_link_shapes_people_share_also_match() {
        assert_media("https://m.youtube.com/watch?v=abc123&t=42s", Platform::YouTube, "abc123");
        assert_media("https://www.youtube.com/live/liveid99", Platform::YouTube, "liveid99");
        assert_media("https://www.youtube.com/embed/embedid1", Platform::YouTube, "embedid1");
        assert_media("https://www.youtube.com/watch?feature=share&v=late123", Platform::YouTube, "late123");
        assert_media("https://www.instagram.com/reels/Creel789/", Platform::Instagram, "reels/Creel789");
        assert_media("https://mobile.twitter.com/user/status/987654321?s=20", Platform::X, "987654321");
        assert_media("https://vt.tiktok.com/ZSabc/", Platform::TikTok, "ZSabc");
    }

    #[test]
    fn hosts_are_matched_ignoring_case_and_surrounding_spaces() {
        assert_media("  HTTPS://WWW.YOUTUBE.COM/watch?v=Case1  ", Platform::YouTube, "Case1");
    }

    #[test]
    fn the_link_is_kept_as_given_apart_from_trimming() {
        let link = match_media("  https://youtu.be/abc123?si=xyz  ").unwrap();
        assert_eq!(link.url, "https://youtu.be/abc123?si=xyz");
    }

    #[test]
    fn pages_that_are_not_a_single_video_do_not_match() {
        for url in [
            "https://www.youtube.com/",
            "https://www.youtube.com/@creator",
            "https://www.youtube.com/results?search_query=cats",
            "https://www.instagram.com/someone/",
            "https://www.tiktok.com/@creator",
            "https://x.com/home",
        ] {
            assert_eq!(match_media(url), None, "{url}");
        }
    }

    #[test]
    fn unsupported_and_malformed_links_do_not_match() {
        for url in [
            "https://example.com/watch?v=123",
            "not-a-url",
            "",
            "ftp://youtube.com/watch?v=abc",
            "https://notyoutube.com/watch?v=abc",
            "https://youtube.com.evil.example/watch?v=abc",
        ] {
            assert_eq!(match_media(url), None, "{url}");
        }
    }

    #[test]
    fn a_post_may_hold_only_pictures_so_its_lookup_is_for_the_post() {
        let looking_for = |url| match_media(url).unwrap().looking_for();
        assert_eq!(looking_for("https://www.instagram.com/p/DAbc123/"), "post");
        assert_eq!(looking_for("https://x.com/someone/status/123"), "post");
        assert_eq!(looking_for("https://www.instagram.com/reel/DAbc123/"), "video");
        assert_eq!(looking_for("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), "video");
    }

    #[test]
    fn account_pages_on_instagram_and_x_are_recognised() {
        assert_eq!(
            match_account("https://www.instagram.com/some.one_1/"),
            Some(AccountLink {
                platform: Platform::Instagram,
                username: "some.one_1".into(),
                url: "https://www.instagram.com/some.one_1/".into(),
            })
        );
        assert_eq!(match_account("https://instagram.com/someone/posts").unwrap().username, "someone");
        assert_eq!(match_account("https://x.com/Some_One").unwrap().platform, Platform::X);
        assert_eq!(match_account("https://twitter.com/Some_One/media").unwrap().username, "Some_One");
    }

    #[test]
    fn posts_reels_and_the_sites_own_pages_are_not_accounts() {
        for url in [
            "https://www.instagram.com/p/Cabc123/",
            "https://www.instagram.com/reel/Cabc/",
            "https://www.instagram.com/explore/",
            "https://www.instagram.com/accounts/login/",
            "https://x.com/someone/status/123",
            "https://x.com/home",
            "https://x.com/i/flow/login",
            "https://x.com/",
            "https://www.youtube.com/@someone",
            "https://x.com/name_that_is_far_too_long",
            "ftp://x.com/someone",
            "https://www.instagram.com/.dotted/",
        ] {
            assert_eq!(match_account(url), None, "{url}");
        }
    }

    #[test]
    fn the_address_bar_tells_media_accounts_pages_and_words_apart() {
        assert!(matches!(recognise("https://youtu.be/abc"), Link::Media { site: "YouTube", .. }));
        assert!(matches!(recognise("https://x.com/someone"), Link::Account { site: "X", .. }));
        assert_eq!(recognise(" https://example.com/page "), Link::Web { url: "https://example.com/page".into() });
        assert_eq!(recognise("monsoon timelapse"), Link::Text { text: "monsoon timelapse".into() });
        assert_eq!(recognise("ftp://example.com"), Link::Text { text: "ftp://example.com".into() });
    }

    #[test]
    fn the_window_receives_a_flat_object_with_a_kind() {
        let json = serde_json::to_value(recognise("https://www.instagram.com/p/Cabc123/")).unwrap();
        assert_eq!(
            json,
            serde_json::json!({
                "kind": "media",
                "platform": "instagram",
                "id": "p/Cabc123",
                "url": "https://www.instagram.com/p/Cabc123/",
                "site": "Instagram",
                "lookingFor": "post",
            })
        );
        let json = serde_json::to_value(recognise("https://x.com/someone")).unwrap();
        assert_eq!(json["kind"], "account");
        assert_eq!(json["username"], "someone");
        assert_eq!(json["platform"], "x");
    }
}
