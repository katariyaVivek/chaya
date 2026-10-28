//! The Downloads list, as the phone shows its downloads.
//!
//! Narrowed as the phone's library is (`app/src/main/java/com/chaya/app/library/LibraryModel.kt`): by what a
//! download is (its `Kind`), by the site it came from, and by words in its name. The site is worked out here, with
//! the phone's tests; the window keeps a download when all three agree.
//!
//! In sections as the phone's list is (`app/src/main/java/com/chaya/app/downloads/DownloadSections.kt`): what is
//! running, what needs attention, then what was saved, by the day it was saved in the person's time zone.

use serde::Serialize;
use url::Url;

use crate::download::manager::TaskView;
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

/// One titled run of the list: its downloads, in the order shown.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Section {
    pub title: String,
    pub ids: Vec<u64>,
}

/// What the sections and the grid need of a download: its state ("queued", "downloading", "paused", "completed",
/// "failed"), when it was added and when it was saved or failed (seconds since 1970), and the post it came from.
#[derive(Clone, Debug, Default)]
pub struct Dated {
    pub id: u64,
    pub state: &'static str,
    pub added: u64,
    pub ended: Option<u64>,
    pub group: Option<String>,
}

impl From<&TaskView> for Dated {
    fn from(task: &TaskView) -> Dated {
        Dated { id: task.id, state: task.state, added: task.added, ended: task.ended, group: task.group.clone() }
    }
}

/// One tile of the grid: a saved download, or the files of one post together (`key` "task:3" or "post:…").
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Tile {
    pub key: String,
    pub ids: Vec<u64>,
}

/// The grid's tiles, as the phone's library (`libraryItems`): saved downloads only, newest first, a post's files on
/// one tile in the order they were saved. A post with one file among `tasks` (the rest narrowed away) is an
/// ordinary tile.
pub fn tiles(tasks: &[Dated]) -> Vec<Tile> {
    let saved: Vec<&Dated> = tasks.iter().filter(|t| t.state == "completed").collect();
    let mut tiles: Vec<(u64, Tile)> = Vec::new();
    let mut posts: Vec<(&str, usize)> = Vec::new();
    for task in &saved {
        let at = task.ended.unwrap_or(task.added);
        let siblings = task.group.as_deref().map(|g| saved.iter().filter(|t| t.group.as_deref() == Some(g)).count());
        match (task.group.as_deref(), siblings) {
            (Some(group), Some(n)) if n >= 2 => match posts.iter().find(|(g, _)| *g == group) {
                Some((_, i)) => {
                    let (newest, tile) = &mut tiles[*i];
                    *newest = (*newest).max(at);
                    tile.ids.push(task.id);
                }
                None => {
                    posts.push((group, tiles.len()));
                    tiles.push((at, Tile { key: format!("post:{group}"), ids: vec![task.id] }));
                }
            },
            _ => tiles.push((at, Tile { key: format!("task:{}", task.id), ids: vec![task.id] })),
        }
    }
    for (_, tile) in &mut tiles {
        tile.ids.sort_unstable();
    }
    // Newest first; among equals, the later download first, as the list has them.
    tiles.sort_by(|(a, x), (b, y)| b.cmp(a).then(y.ids[0].cmp(&x.ids[0])));
    tiles.into_iter().map(|(_, tile)| tile).collect()
}

const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/// The list's sections at `now` (seconds since 1970) for a person `offset` minutes east of UTC: "In progress"
/// (newest started first), "Needs attention" (newest failure first), then "Today", "Yesterday" and "3 Oct" (newest
/// saved first). Sections with nothing in them are left out.
pub fn sections(tasks: &[Dated], now: u64, offset: i32) -> Vec<Section> {
    let mut out = Vec::new();
    let mut push = |title: String, mut items: Vec<&Dated>, at: fn(&Dated) -> u64| {
        if items.is_empty() {
            return;
        }
        items.sort_by(|a, b| at(b).cmp(&at(a)).then(b.id.cmp(&a.id)));
        out.push(Section { title, ids: items.iter().map(|t| t.id).collect() });
    };
    let ended = |t: &Dated| t.ended.unwrap_or(t.added);
    let of = |states: &[&str]| tasks.iter().filter(|t| states.contains(&t.state)).collect::<Vec<_>>();
    push("In progress".into(), of(&["queued", "downloading", "paused"]), |t| t.added);
    push("Needs attention".into(), of(&["failed"]), ended);

    // Saved downloads by the day they were saved on, in the person's time zone; downloads kept before the time they
    // were saved was, by the day they were added.
    let day = |secs: u64| (secs as i64 + i64::from(offset) * 60).div_euclid(86_400);
    let today = day(now);
    let mut saved = of(&["completed"]);
    saved.sort_by(|a, b| ended(b).cmp(&ended(a)).then(b.id.cmp(&a.id)));
    let mut days: Vec<(i64, Vec<&Dated>)> = Vec::new();
    for task in saved {
        let d = day(ended(task));
        match days.last_mut() {
            Some((last, items)) if *last == d => items.push(task),
            _ => days.push((d, vec![task])),
        }
    }
    for (d, items) in days {
        let title = match today - d {
            0 => "Today".to_string(),
            1 => "Yesterday".to_string(),
            _ => {
                let (_, month, date) = civil(d);
                format!("{date} {}", MONTHS[month as usize - 1])
            }
        };
        push(title, items, ended);
    }
    out
}

/// The type a picture named `file_name` is served as to the window, or None for anything that is not a picture.
pub fn picture_type(file_name: &str) -> Option<&'static str> {
    let ext = file_name.rsplit_once('.')?.1.to_ascii_lowercase();
    Some(match ext.as_str() {
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "webp" => "image/webp",
        "gif" => "image/gif",
        "avif" => "image/avif",
        "bmp" => "image/bmp",
        _ => return None,
    })
}

/// The year, month (1–12) and day of the month `days` days after 1 January 1970 (Howard Hinnant's `civil_from_days`).
fn civil(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (yoe + era * 400 + i64::from(month <= 2), month, day)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Wednesday 8 October 2026, noon UTC, as the phone's `DownloadSectionsTest` has it.
    const NOW: u64 = 1_791_460_800;
    const DAY: u64 = 86_400;

    fn task(id: u64, state: &'static str, ended_days_ago: u64, added_days_ago: u64) -> Dated {
        let ended = (state == "completed" || state == "failed").then_some(NOW - ended_days_ago * DAY);
        Dated { id, state, added: NOW - added_days_ago * DAY, ended, group: None }
    }

    fn titles(sections: &[Section]) -> Vec<&str> {
        sections.iter().map(|s| s.title.as_str()).collect()
    }

    #[test]
    fn running_work_comes_first_then_what_needs_attention_then_the_days_things_were_saved() {
        let tasks = [
            task(1, "downloading", 0, 0),
            task(2, "paused", 0, 1),
            task(3, "failed", 0, 0),
            task(4, "completed", 0, 0),
            task(5, "completed", 1, 1),
            task(6, "completed", 5, 5),
            task(7, "completed", 0, 0),
        ];
        let sections = sections(&tasks, NOW, 0);
        assert_eq!(titles(&sections), ["In progress", "Needs attention", "Today", "Yesterday", "3 Oct"]);
        // Newest started first; on one day, the latest saved first.
        assert_eq!(sections[0].ids, [1, 2]);
        assert_eq!(sections[1].ids, [3]);
        assert_eq!(sections[2].ids, [7, 4]);
        assert_eq!(sections[3].ids, [5]);
        assert_eq!(sections[4].ids, [6]);
    }

    #[test]
    fn sections_with_nothing_in_them_are_left_out() {
        assert_eq!(titles(&sections(&[task(1, "completed", 0, 0)], NOW, 0)), ["Today"]);
        assert!(sections(&[], NOW, 0).is_empty());
    }

    #[test]
    fn a_download_saved_late_in_the_evening_stays_on_its_own_day_in_the_persons_time_zone() {
        // 23:30 UTC on 7 Oct is already 8 Oct in UTC+2, so it counts as today there.
        let late =
            Dated { id: 1, state: "completed", added: NOW - DAY, ended: Some(NOW - 12 * 3600 - 30 * 60), group: None };
        assert_eq!(titles(&sections(std::slice::from_ref(&late), NOW, 0)), ["Yesterday"]);
        assert_eq!(titles(&sections(&[late], NOW, 120)), ["Today"]);
    }

    #[test]
    fn a_download_kept_before_its_save_time_was_counts_from_when_it_was_added() {
        let old = Dated { id: 1, state: "completed", added: NOW - 40 * DAY, ended: None, group: None };
        assert_eq!(titles(&sections(&[old], NOW, 0)), ["29 Aug"]);
    }

    /// The phone's library table (`LibraryModelTest`): a video, a post of two pictures and a video, a song, one
    /// still running, an account's ZIP, and a picture; each saved a second after the one before.
    fn library() -> Vec<Dated> {
        let post = Some("https://www.instagram.com/p/abc/".to_string());
        (1..=8)
            .map(|id| Dated {
                id,
                state: if id == 6 { "downloading" } else { "completed" },
                added: id * 1000,
                ended: Some(id * 1000),
                group: (2..=4).contains(&id).then(|| post.clone()).flatten(),
            })
            .collect()
    }

    fn keys(tiles: &[Tile]) -> Vec<&str> {
        tiles.iter().map(|t| t.key.as_str()).collect()
    }

    #[test]
    fn saved_downloads_only_newest_first_a_posts_files_on_one_tile_in_the_order_they_were_saved() {
        let tiles = tiles(&library());
        assert_eq!(keys(&tiles), ["task:8", "task:7", "task:5", "post:https://www.instagram.com/p/abc/", "task:1"]);
        assert_eq!(tiles[3].ids, [2, 3, 4]);
    }

    #[test]
    fn a_post_with_one_file_left_after_narrowing_is_an_ordinary_tile() {
        // Videos only: the post's video and the first download.
        let videos: Vec<Dated> = library().into_iter().filter(|t| t.id == 4 || t.id == 1).collect();
        assert_eq!(keys(&tiles(&videos)), ["task:4", "task:1"]);
    }

    #[test]
    fn only_pictures_are_served_to_the_window_by_their_kind() {
        assert_eq!(picture_type("Sunset (1).JPG"), Some("image/jpeg"));
        assert_eq!(picture_type("Cat.webp"), Some("image/webp"));
        assert_eq!(picture_type("Party.gif"), Some("image/gif"));
        assert_eq!(picture_type("Clip.mp4"), None);
        assert_eq!(picture_type("page.html"), None);
        assert_eq!(picture_type("no extension"), None);
    }

    #[test]
    fn days_are_named_by_the_calendar() {
        assert_eq!(civil(0), (1970, 1, 1));
        assert_eq!(civil(20_734), (2026, 10, 8));
        assert_eq!(civil(11_016), (2000, 2, 29));
        assert_eq!(civil(-1), (1969, 12, 31));
    }

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
