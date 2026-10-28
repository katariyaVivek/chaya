//! Narrowing the Downloads list, as the phone's library does (`app/src/main/java/com/chaya/app/library/
//! LibraryModel.kt`): by what a download is (its `Kind`), by the site it came from, and by words in its name. The
//! site is worked out here, with the phone's tests; the window keeps a download when all three agree.

use serde::Serialize;
use url::Url;

use crate::links::is_pinterest_host;

/// Where a download came from, for the list's site filter.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Site {
    YouTube,
    Instagram,
    TikTok,
    X,
    Pinterest,
    Other,
}

/// The site of a download found at `page` (the link looked up, or the page it was on), or, without one, at the
/// file's own address `file`.
pub fn site_of(page: &str, file: &str) -> Site {
    let address = if page.trim().is_empty() { file } else { page };
    let Some(host) = Url::parse(address.trim()).ok().and_then(|u| u.host_str().map(str::to_ascii_lowercase)) else {
        return Site::Other;
    };
    let on = |domain: &str| host == domain || host.strip_suffix(domain).is_some_and(|rest| rest.ends_with('.'));
    if on("youtube.com") || on("youtu.be") || on("googlevideo.com") {
        Site::YouTube
    } else if on("instagram.com") || on("cdninstagram.com") {
        Site::Instagram
    } else if on("tiktok.com") {
        Site::TikTok
    } else if on("x.com") || on("twitter.com") || on("twimg.com") {
        Site::X
    } else if on("pinimg.com") || is_pinterest_host(&host) {
        Site::Pinterest
    } else {
        Site::Other
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sites_come_from_the_page_the_download_was_found_on() {
        // The phone's table (`LibraryModelTest`).
        assert_eq!(site_of("https://www.youtube.com/watch?v=1", ""), Site::YouTube);
        assert_eq!(site_of("https://www.instagram.com/p/abc/", ""), Site::Instagram);
        assert_eq!(site_of("https://www.tiktok.com/@a/video/1", ""), Site::TikTok);
        assert_eq!(site_of("https://x.com/a/status/1", ""), Site::X);
        assert_eq!(site_of("https://news.example/a", ""), Site::Other);
        assert_eq!(site_of("https://m.youtube.com/shorts/x", ""), Site::YouTube);
        assert_eq!(site_of("https://twitter.com/a/status/1", ""), Site::X);
        assert_eq!(site_of("https://notyoutube.com/x", ""), Site::Other);
        assert_eq!(site_of("https://www.pinterest.co.uk/pin/1/", ""), Site::Pinterest);
        assert_eq!(site_of("https://pin.it/abc", ""), Site::Pinterest);
        assert_eq!(site_of("https://notpinterest.com/pin/1/", ""), Site::Other);
    }

    #[test]
    fn without_a_page_the_files_own_address_says() {
        assert_eq!(site_of("", "https://rr3---sn-abc.googlevideo.com/videoplayback?id=1"), Site::YouTube);
        assert_eq!(site_of(" ", "https://scontent.cdninstagram.com/v/a.jpg"), Site::Instagram);
        assert_eq!(site_of("", "https://pbs.twimg.com/media/a.jpg"), Site::X);
        assert_eq!(site_of("", "https://i.pinimg.com/originals/a.jpg"), Site::Pinterest);
        assert_eq!(site_of("", "not an address"), Site::Other);
        // The page wins over the file: a video on a news page is the news site's.
        assert_eq!(site_of("https://news.example/a", "https://cdn.example/v.mp4"), Site::Other);
    }
}
