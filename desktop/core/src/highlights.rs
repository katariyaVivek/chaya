//! The moments of a video worth watching on their own, found by the person's AI model (`ai`), as Vimpo's AI
//! Highlights: the model is given the video's title and its subtitles, numbered line by line, and answers with ranges
//! of lines, each with a title and the reason it stands out. Chaya checks every range (inside the video, in order,
//! not overlapping) and turns lines into times, so a highlight starts with a line and never in the middle of a word.
//!
//! A long video goes in parts, each asked for its share of the highlights with a score, and the best are kept.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::watch;

use crate::ai::{self, AiError, Ask, Endpoint};
use crate::subtitles::Line;

/// A part of the subtitles in one request, at most this many characters of them.
const PART: usize = 60_000;
/// How long the model's answer may run (Anthropic's protocol asks).
const MAX_TOKENS: u32 = 4_096;
/// The most highlights asked for, and the fewest.
pub const MOST: usize = 20;

/// A highlight: its title, why it stands out, its lines (from 0, both included), and its times in milliseconds.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Highlight {
    pub title: String,
    pub reason: String,
    pub first: usize,
    pub last: usize,
    pub start: u64,
    pub end: u64,
}

const SYSTEM: &str = "You find the moments of a video worth watching on their own: a clear claim, a striking story, \
a turn, a joke that lands, a useful answer. Each is a run of whole lines that makes sense without the rest, usually \
twenty seconds to two minutes long. Give each a short title (a few words, no quotes) and the reason it stands out \
(one short sentence). Answer with one JSON object and nothing else.";

/// What highlights are found in: the video's title, its subtitles, and the language they are in ("French"), which the
/// titles and reasons are written in too.
pub struct Video<'a> {
    pub title: &'a str,
    pub lines: &'a [Line],
    pub language: &'a str,
}

/// Finds about `count` highlights in `video` with `model`; the best of them, in the order they come in the video.
/// `stop` turning true stops it.
pub async fn find(
    endpoint: &Endpoint,
    model: &str,
    video: &Video<'_>,
    count: usize,
    stop: &watch::Receiver<bool>,
) -> Result<Vec<Highlight>, AiError> {
    let lines = video.lines;
    let count = count.clamp(1, MOST);
    let parts = parts(lines);
    let mut found: Vec<(f64, Highlight)> = Vec::new();
    for (from, to) in &parts {
        // Each part asked for its share, and a little more to choose from.
        let share = (count * (to - from)).div_ceil(lines.len().max(1)).max(1) + usize::from(parts.len() > 1);
        let user = question(video, *from, *to, share);
        let ask = Ask { model, system: SYSTEM, user: &user, max_tokens: MAX_TOKENS };
        let mut stop = stop.clone();
        let mut reply = Err(AiError::NotJson);
        // A reply that isn't the JSON asked for, once more.
        for _ in 0..2 {
            reply = tokio::select! {
                reply = ai::ask_json(endpoint, &ask, |v| v["highlights"].is_array()) => reply,
                _ = stop.wait_for(|stopped| *stopped) => return Err(AiError::Stopped),
            };
            if !matches!(reply, Err(AiError::NotJson)) {
                break;
            }
        }
        found.extend(read(&reply?, lines, *from, *to));
    }
    Ok(best(found, count))
}

/// The subtitles in parts of at most `PART` characters, as (first line, line after the last).
fn parts(lines: &[Line]) -> Vec<(usize, usize)> {
    let mut parts = Vec::new();
    let (mut from, mut chars) = (0, 0);
    for (i, line) in lines.iter().enumerate() {
        if chars > 0 && chars + line.text.len() > PART {
            parts.push((from, i));
            from = i;
            chars = 0;
        }
        chars += line.text.len() + 16;
    }
    if from < lines.len() {
        parts.push((from, lines.len()));
    }
    parts
}

fn clock(ms: u64) -> String {
    let seconds = ms / 1000;
    format!("{}:{:02}", seconds / 60, seconds % 60)
}

/// The question for lines `from..to`: the title, the lines numbered (from 1, with their times), and how many to find.
fn question(video: &Video<'_>, from: usize, to: usize, count: usize) -> String {
    let (title, lines) = (video.title, video.lines);
    let numbered: Vec<String> = (from..to)
        .map(|n| format!("{} [{}] {}", n + 1, clock(lines[n].start), lines[n].text.replace('\n', " ")))
        .collect();
    format!(
        "Video title: {title}\n\nIts subtitles, line by line (number [time] text):\n{lines}\n\nFind the {count} best \
         highlights in these lines. Answer with {{\"highlights\": [{{\"from\": first line's number, \"to\": last \
         line's number, \"title\": \"…\", \"reason\": \"…\", \"score\": 1 to 10}}]}}, using only the numbers {first} to \
         {last}. Write the titles and reasons in {language}.",
        lines = numbered.join("\n"),
        language = video.language,
        first = from + 1,
        last = to,
    )
}

/// The highlights in a reply that can be used: inside lines `from..to`, the right way round, with a title; a range
/// given backwards is turned round, and its times come from its lines. Each with its score (5 when not given).
fn read(reply: &Value, lines: &[Line], from: usize, to: usize) -> Vec<(f64, Highlight)> {
    let number = |v: &Value| v.as_u64().or_else(|| v.as_str()?.trim().parse().ok()).map(|n| n as usize);
    reply["highlights"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|item| {
            let (a, b) = (number(&item["from"])?, number(&item["to"])?);
            let (first, last) = (a.min(b).checked_sub(1)?, a.max(b).checked_sub(1)?);
            if first < from || last >= to {
                return None;
            }
            let title = item["title"].as_str()?.trim().trim_matches('"').chars().take(80).collect::<String>();
            if title.is_empty() {
                return None;
            }
            let reason = item["reason"].as_str().unwrap_or("").trim().chars().take(240).collect::<String>();
            let score = item["score"].as_f64().unwrap_or(5.0);
            let (start, end) = (lines[first].start, lines[last].end);
            Some((score, Highlight { title, reason, first, last, start, end }))
        })
        .collect()
}

/// The `count` best highlights by score, none overlapping another, in the order they come in the video.
fn best(mut found: Vec<(f64, Highlight)>, count: usize) -> Vec<Highlight> {
    found.sort_by(|a, b| b.0.total_cmp(&a.0));
    let mut kept: Vec<Highlight> = Vec::new();
    for (_, highlight) in found {
        let overlaps = kept.iter().any(|k| highlight.first <= k.last && k.first <= highlight.last);
        if !overlaps && kept.len() < count {
            kept.push(highlight);
        }
    }
    kept.sort_by_key(|h| h.first);
    kept
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;
    use crate::ai::stand_in::{Answer, StandIn};
    use crate::ai::Settings;

    fn endpoint(stand_in: &StandIn) -> Endpoint {
        let settings =
            Settings { service: Some("openai".into()), address: Some(stand_in.url("/v1")), model: Some("m".into()) };
        Endpoint::new(&settings, Some("k".into())).unwrap()
    }

    /// `count` lines, each 4 s long with a second between.
    fn lines(count: usize) -> Vec<Line> {
        (0..count as u64)
            .map(|i| Line { start: i * 5_000, end: i * 5_000 + 4_000, text: format!("Line {} of the talk.", i + 1) })
            .collect()
    }

    fn answering(highlights: Value) -> StandIn {
        let content = json!({"highlights": highlights}).to_string();
        let body = json!({"choices": [{"message": {"content": content}}]}).to_string();
        StandIn::start(vec![("/v1/chat/completions", Answer::ok(body))])
    }

    async fn run(stand_in: &StandIn, lines: &[Line], count: usize) -> Result<Vec<Highlight>, AiError> {
        let (_tx, stop) = watch::channel(false);
        let video = Video { title: "A talk", lines, language: "French" };
        find(&endpoint(stand_in), "m", &video, count, &stop).await
    }

    #[tokio::test]
    async fn highlights_come_back_in_order_timed_by_their_lines() {
        let stand_in = answering(json!([
            {"from": 30, "to": 34, "title": "The turn", "reason": "Where it changes.", "score": 7},
            {"from": 3, "to": 6, "title": "A clear claim", "reason": "With numbers.", "score": 9},
        ]));
        let found = run(&stand_in, &lines(50), 2).await.unwrap();
        let titles: Vec<&str> = found.iter().map(|h| h.title.as_str()).collect();
        assert_eq!(titles, ["A clear claim", "The turn"], "in the order they come in the video");
        // Lines 3 to 6: from line 3's start to line 6's end.
        assert_eq!((found[0].first, found[0].last, found[0].start, found[0].end), (2, 5, 10_000, 29_000));
        let body: Value = serde_json::from_str(&stand_in.sent()[0].body).unwrap();
        let user = body["messages"][1]["content"].as_str().unwrap();
        assert!(user.contains("Video title: A talk"));
        assert!(user.contains("3 [0:10] Line 3 of the talk."), "numbered, with times");
        assert!(user.contains("Find the 2 best highlights"));
        assert!(user.contains("Write the titles and reasons in French."));
    }

    #[tokio::test]
    async fn ranges_outside_the_video_backwards_overlapping_or_untitled_are_put_right_or_left_out() {
        let stand_in = answering(json!([
            {"from": 0, "to": 3, "title": "Before the first line", "score": 9},
            {"from": 48, "to": 60, "title": "Past the end", "score": 9},
            {"from": 12, "to": 10, "title": "Backwards", "score": 8},
            {"from": 11, "to": 14, "title": "Overlaps a better one", "score": 6},
            {"from": 20, "to": 22, "title": "", "score": 9},
            {"from": "40", "to": "42", "title": "\"Numbers as words\"", "score": 5},
        ]));
        let found = run(&stand_in, &lines(50), 5).await.unwrap();
        let kept: Vec<(&str, usize, usize)> = found.iter().map(|h| (h.title.as_str(), h.first, h.last)).collect();
        assert_eq!(kept, [("Backwards", 9, 11), ("Numbers as words", 39, 41)]);
    }

    #[tokio::test]
    async fn no_more_than_asked_for_the_best_by_score() {
        let stand_in = answering(json!([
            {"from": 1, "to": 2, "title": "Fine", "score": 4},
            {"from": 10, "to": 12, "title": "Best", "score": 10},
            {"from": 20, "to": 22, "title": "Good", "score": 8},
        ]));
        let found = run(&stand_in, &lines(30), 2).await.unwrap();
        assert_eq!(found.iter().map(|h| h.title.as_str()).collect::<Vec<_>>(), ["Best", "Good"]);
    }

    #[tokio::test]
    async fn a_model_that_answers_in_prose_is_asked_once_more_then_said_so() {
        let body = json!({"choices": [{"message": {"content": "Here are some great moments from the talk!"}}]});
        let stand_in = StandIn::start(vec![("/v1/chat/completions", Answer::ok(body.to_string()))]);
        assert_eq!(run(&stand_in, &lines(10), 3).await, Err(AiError::NotJson));
        assert_eq!(stand_in.sent().len(), 2);
    }

    #[test]
    fn a_long_video_goes_in_parts_each_asked_for_its_share() {
        let long: Vec<Line> = (0..6_000u64)
            .map(|i| Line {
                start: i * 3_000,
                end: i * 3_000 + 2_500,
                text: "A fairly ordinary line of a long talk.".into(),
            })
            .collect();
        let parts = parts(&long);
        assert!(parts.len() > 1, "{parts:?}");
        assert_eq!(parts.first().unwrap().0, 0);
        assert_eq!(parts.last().unwrap().1, long.len());
        assert!(parts.windows(2).all(|p| p[0].1 == p[1].0), "every line in one part");
        let video = Video { title: "Long", lines: &long, language: "English" };
        let question = question(&video, parts[1].0, parts[1].1, 2);
        assert!(question.len() < PART + 30_000);
        assert!(question.contains(&format!("using only the numbers {} to {}", parts[1].0 + 1, parts[1].1)));
    }
}
