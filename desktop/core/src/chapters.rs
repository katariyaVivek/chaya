//! A video's chapters, as its maker named them on the site (YouTube's, from the times in the description), which the
//! engine lists with the video (`chaya_engine.extract._chapters`). Chaya keeps them with the saved video (`video`) and
//! the window marks them on the seek bar and on the clip timeline, where a clip's edges snap to them too.

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// The most chapters kept: more is not a video's chapters but a list of something else.
const MOST: usize = 500;
/// The longest title kept, in characters.
const LONGEST_TITLE: usize = 100;

/// A chapter: from `start` to `end`, in milliseconds, and its title.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Chapter {
    pub start: u64,
    pub end: u64,
    pub title: String,
}

/// The chapters the engine listed (`[{"start": 0.0, "end": 42.0, "title": "Intro"}]`, in seconds), put right: in order
/// of their start, each ending where the next begins at the latest, none past the video's end (`duration`, in seconds,
/// when it is known), one without a title named for its place ("Chapter 3"). A video with fewer than two has none:
/// one chapter the length of the video marks nothing.
pub fn read(listed: &[Value], duration: Option<f64>) -> Vec<Chapter> {
    let length = duration.filter(|d| d.is_finite() && *d > 0.0).map(ms);
    let mut chapters: Vec<Chapter> = listed
        .iter()
        .filter_map(Value::as_object)
        .filter_map(|c| {
            let seconds = |key| c.get(key).and_then(Value::as_f64).filter(|s| s.is_finite() && *s >= 0.0);
            let (start, end) = (ms(seconds("start")?), ms(seconds("end")?));
            let title = c.get("title").and_then(Value::as_str).unwrap_or("").split_whitespace().collect::<Vec<_>>();
            Some(Chapter { start, end, title: title.join(" ").chars().take(LONGEST_TITLE).collect() })
        })
        .collect();
    chapters.sort_by_key(|c| c.start);
    chapters.dedup_by_key(|c| c.start);
    let starts: Vec<u64> = chapters.iter().map(|c| c.start).skip(1).collect();
    for (chapter, next) in chapters.iter_mut().zip(starts.into_iter().map(Some).chain(std::iter::once(None))) {
        if let Some(next) = next {
            chapter.end = chapter.end.min(next);
        }
        if let Some(length) = length {
            chapter.end = chapter.end.min(length);
        }
    }
    chapters.retain(|c| c.end > c.start);
    chapters.truncate(MOST);
    if chapters.len() < 2 {
        return Vec::new();
    }
    for (n, chapter) in chapters.iter_mut().enumerate() {
        if chapter.title.is_empty() {
            chapter.title = format!("Chapter {}", n + 1);
        }
    }
    chapters
}

fn ms(seconds: f64) -> u64 {
    (seconds * 1000.0).round() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn listed(json: &str) -> Vec<Value> {
        serde_json::from_str(json).unwrap()
    }

    fn times(chapters: &[Chapter]) -> Vec<(u64, u64, &str)> {
        chapters.iter().map(|c| (c.start, c.end, c.title.as_str())).collect()
    }

    #[test]
    fn chapters_are_read_in_milliseconds_and_in_order() {
        let chapters = read(
            &listed(
                r#"[{"start": 42.0, "end": 300.5, "title": "The   walk"},
                    {"start": 0, "end": 42, "title": "Intro"},
                    {"start": 300.5, "end": 420, "title": ""}]"#,
            ),
            Some(420.0),
        );
        assert_eq!(
            times(&chapters),
            [(0, 42_000, "Intro"), (42_000, 300_500, "The walk"), (300_500, 420_000, "Chapter 3")]
        );
    }

    #[test]
    fn a_chapter_ends_where_the_next_begins_and_none_runs_past_the_video() {
        let chapters = read(
            &listed(
                r#"[{"start": 0, "end": 60, "title": "One"}, {"start": 50, "end": 120, "title": "Two"},
                    {"start": 50, "end": 70, "title": "Two again"}, {"start": 110, "end": 200, "title": "Three"},
                    {"start": 130, "end": 140, "title": "After the end"}]"#,
            ),
            Some(120.0),
        );
        assert_eq!(times(&chapters), [(0, 50_000, "One"), (50_000, 110_000, "Two"), (110_000, 120_000, "Three")]);
    }

    #[test]
    fn one_chapter_or_nonsense_is_no_chapters() {
        assert!(read(&listed(r#"[{"start": 0, "end": 420, "title": "The video"}]"#), Some(420.0)).is_empty());
        assert!(read(&listed(r#"[{"start": "a"}, {"end": 5}, 7, {"start": -1, "end": 3}]"#), None).is_empty());
        assert!(read(&[], None).is_empty());
        let long = format!(r#"[{{"start": 0, "end": 1, "title": "{}"}}, {{"start": 1, "end": 2}}]"#, "x".repeat(300));
        assert_eq!(read(&listed(&long), None)[0].title.len(), LONGEST_TITLE);
    }
}
