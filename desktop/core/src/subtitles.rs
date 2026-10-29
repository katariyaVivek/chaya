//! Subtitles: read from SRT and WebVTT into lines, the line shown at a time, which of a site's tracks to save with a
//! video, and the SRT files kept next to it ("name.en.srt"), where VLC and other players find them too.
//!
//! Chaya draws subtitles itself rather than with `<track>`, so a later step can stack two languages and style each;
//! the window gets the lines and a timeline of which one shows when (`timeline`).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::media::{Media, SubtitleTrack};

/// One subtitle line: shown from `start` until `end` (milliseconds), its text without styling, lines joined by "\n".
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Line {
    pub start: u64,
    pub end: u64,
    pub text: String,
}

/// Reads SRT or WebVTT (told apart by nothing: both are blocks of a timing line and text) into lines, in the order
/// they start. Blocks without a timing line (VTT's header, NOTE, STYLE and REGION, an SRT's stray numbers) are left
/// out, as are lines with no text once their styling is taken off.
pub fn parse(text: &str) -> Vec<Line> {
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    // YouTube's machine captions roll: each line comes again at the top of the next, with each word's time inside it.
    let rolling = text.contains("><c>") || has_inline_times(text);
    let mut lines: Vec<Line> = Vec::new();
    let mut cue: Cue = None;
    // A text line right after a line of spaces, kept apart: when a timing line follows it, it was the next block's
    // number, in a file whose blocks are parted by spaces instead of empty lines.
    let mut after_spaces = false;
    let mut maybe_number = false;
    for raw in text.lines() {
        let line = raw.trim_end_matches('\r');
        if let Some((start, end)) = timing(line) {
            if maybe_number {
                if let Some((_, _, text)) = cue.as_mut() {
                    text.pop();
                }
            }
            close(&mut cue, &mut lines);
            cue = Some((start, end, Vec::new()));
            (after_spaces, maybe_number) = (false, false);
        } else if line.is_empty() {
            close(&mut cue, &mut lines);
            (after_spaces, maybe_number) = (false, false);
        } else if let Some((_, _, text)) = cue.as_mut() {
            maybe_number = after_spaces;
            after_spaces = line.trim().is_empty();
            text.push(line.to_string());
        }
    }
    close(&mut cue, &mut lines);
    lines.sort_by_key(|l| l.start);
    if rolling {
        settle_rolling(&mut lines);
    }
    lines
}

type Cue = Option<(u64, u64, Vec<String>)>;

/// Ends the cue being read, keeping it when it has text and lasts.
fn close(cue: &mut Cue, lines: &mut Vec<Line>) {
    if let Some((start, end, text)) = cue.take() {
        let text = text.iter().map(|l| clean(l)).filter(|l| !l.is_empty()).collect::<Vec<_>>().join("\n");
        if !text.is_empty() && end > start {
            lines.push(Line { start, end, text });
        }
    }
}

/// YouTube's machine captions as one line after another: the line carried over from the cue before is taken off
/// each cue, and the moments a cue only holds what was already shown are left out.
fn settle_rolling(lines: &mut Vec<Line>) {
    let mut shown: Vec<String> = Vec::new();
    lines.retain_mut(|line| {
        let mut rows: Vec<&str> = line.text.split('\n').collect();
        while rows.first().is_some_and(|row| shown.iter().any(|s| s == row)) {
            rows.remove(0);
        }
        let kept = rows.join("\n");
        shown = line.text.split('\n').map(str::to_string).collect();
        if kept.is_empty() {
            return false;
        }
        line.text = kept;
        true
    });
}

fn has_inline_times(text: &str) -> bool {
    text.match_indices('<').any(|(i, _)| {
        let rest = &text[i + 1..];
        rest.find('>').is_some_and(|close| time(&rest[..close]).is_some())
    })
}

/// "00:01:02,500 --> 00:01:04,000" (SRT) or "01:02.500 --> 01:04.000 align:start" (VTT, with its cue settings).
fn timing(line: &str) -> Option<(u64, u64)> {
    let (start, rest) = line.split_once("-->")?;
    let end = rest.split_whitespace().next()?;
    Some((time(start.trim())?, time(end)?))
}

/// "hh:mm:ss,ttt", "hh:mm:ss.ttt" or "mm:ss.ttt", in milliseconds; a fraction of fewer than three digits is read
/// as the decimal it is.
fn time(text: &str) -> Option<u64> {
    let (clock, fraction) = match text.split_once([',', '.']) {
        Some((clock, fraction)) => (clock, fraction),
        None => (text, ""),
    };
    let parts: Vec<&str> = clock.split(':').collect();
    if !(2..=3).contains(&parts.len()) || fraction.len() > 3 {
        return None;
    }
    let number = |s: &str| (!s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())).then(|| s.parse::<u64>().ok())?;
    let mut seconds = 0;
    for (i, part) in parts.iter().enumerate() {
        let n = number(part)?;
        if i > 0 && n >= 60 {
            return None;
        }
        seconds = seconds * 60 + n;
    }
    let millis = match fraction {
        "" => 0,
        f => number(f)? * 10u64.pow(3 - f.len() as u32),
    };
    Some(seconds * 1000 + millis)
}

/// A text line without its styling: HTML-like tags (`<i>`, `<font>`, VTT's `<c.yellow>`, `<v Name>` and word times),
/// SSA overrides (`{\an8}`), and entities read as the characters they stand for.
fn clean(line: &str) -> String {
    let mut out = String::with_capacity(line.len());
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '<' if line.contains('>') => {
                for c in chars.by_ref() {
                    if c == '>' {
                        break;
                    }
                }
            }
            '{' if chars.peek() == Some(&'\\') => {
                for c in chars.by_ref() {
                    if c == '}' {
                        break;
                    }
                }
            }
            c => out.push(c),
        }
    }
    let out = out
        .replace("&nbsp;", " ")
        .replace("&lrm;", "")
        .replace("&rlm;", "")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&");
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Lines as an SRT file, numbered from 1.
pub fn to_srt(lines: &[Line]) -> String {
    let stamp =
        |ms: u64| format!("{:02}:{:02}:{:02},{:03}", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    let mut out = String::new();
    for (i, line) in lines.iter().enumerate() {
        out.push_str(&format!("{}\n{} --> {}\n{}\n\n", i + 1, stamp(line.start), stamp(line.end), line.text));
    }
    out
}

/// A stretch of time and the line shown in it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub struct Span {
    pub start: u64,
    pub end: u64,
    /// Its index in the lines.
    pub line: usize,
}

/// Which line shows when: stretches that do not overlap, in order, with nothing between lines. Where two lines
/// overlap, the one that started later shows; when it ends, an earlier one still running shows again. The window
/// finds the line at a time in this by halving.
pub fn timeline(lines: &[Line]) -> Vec<Span> {
    let mut edges: Vec<u64> = lines.iter().flat_map(|l| [l.start, l.end]).collect();
    edges.sort_unstable();
    edges.dedup();
    let mut spans: Vec<Span> = Vec::new();
    for pair in edges.windows(2) {
        let (start, end) = (pair[0], pair[1]);
        // `lines` are in the order they start, so the last one running is the latest started.
        let Some(line) = lines.iter().rposition(|l| l.start <= start && l.end >= end) else { continue };
        match spans.last_mut() {
            Some(last) if last.line == line && last.end == start => last.end = end,
            _ => spans.push(Span { start, end, line }),
        }
    }
    spans
}

/// The line shown at `at` milliseconds, by its index, from `timeline`'s spans.
pub fn line_at(spans: &[Span], at: u64) -> Option<usize> {
    let i = spans.partition_point(|s| s.start <= at).checked_sub(1)?;
    (at < spans[i].end).then_some(spans[i].line)
}

/// A site's subtitles to fetch for a download, in the language its file is named by ("en"), with the headers the
/// site's server expects (the video's own).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct FromSite {
    pub language: String,
    pub url: String,
    #[serde(default)]
    pub headers: BTreeMap<String, String>,
}

/// The subtitles to save with a video from `media`, fetched with `headers` (its file's).
pub fn to_save(media: &Media, headers: &BTreeMap<String, String>) -> Option<FromSite> {
    let track = pick(&media.subtitles, media.language.as_deref())?;
    Some(FromSite { language: file_language(&track.language)?, url: track.url.clone(), headers: headers.clone() })
}

/// Which of a site's tracks is saved with a video in `language` (the video's own, when the site says): its own
/// language's subtitles, then a machine caption in that language (yt-dlp marks the original one "-orig"); a video
/// whose language is not known gets the site's first subtitles. Machine captions in other languages are machine
/// translations, never taken.
pub fn pick<'a>(tracks: &'a [SubtitleTrack], language: Option<&str>) -> Option<&'a SubtitleTrack> {
    let base = |tag: &str| tag.split(['-', '_']).next().unwrap_or("").to_ascii_lowercase();
    let own = language.map(base).filter(|l| !l.is_empty());
    let made = || tracks.iter().filter(|t| !t.automatic);
    let machine = || tracks.iter().filter(|t| t.automatic);
    match &own {
        Some(own) => made()
            .find(|t| base(&t.language) == *own)
            .or_else(|| machine().find(|t| t.language.ends_with("-orig") && base(&t.language) == *own))
            .or_else(|| machine().find(|t| base(&t.language) == *own)),
        None => made().next().or_else(|| machine().find(|t| t.language.ends_with("-orig"))),
    }
}

/// The language as it is written into a file name: "en", "pt-BR", "zh-Hans"; YouTube's "-orig" mark and a named
/// track's own suffix ("en-nP7-2PuUl7o") are left off. None when nothing in it names a language.
pub fn file_language(tag: &str) -> Option<String> {
    let tag = tag.strip_suffix("-orig").unwrap_or(tag);
    let mut parts = tag.split(['-', '_']);
    let first = parts.next()?;
    if !(2..=3).contains(&first.len()) || !first.bytes().all(|b| b.is_ascii_alphabetic()) {
        return None;
    }
    let mut out = first.to_ascii_lowercase();
    for part in parts {
        let script = part.len() == 4 && part.bytes().all(|b| b.is_ascii_alphabetic());
        let region = (part.len() == 2 && part.bytes().all(|b| b.is_ascii_alphabetic()))
            || (part.len() == 3 && part.bytes().all(|b| b.is_ascii_digit()));
        if !(script || region) {
            break;
        }
        out.push('-');
        out.push_str(part);
    }
    Some(out)
}

/// Where subtitles in `language` are kept for the video at `video`: "Clip (720p).en.srt" beside "Clip (720p).mp4".
pub fn file_for(video: &Path, language: &str) -> PathBuf {
    let stem = video.file_stem().unwrap_or_default().to_string_lossy();
    video.with_file_name(format!("{stem}.{language}.srt"))
}

/// A subtitles file found next to a video, and the language its name gives (None for "Clip.srt").
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Found {
    pub language: Option<String>,
    pub path: PathBuf,
}

/// The SRT and VTT files next to the video at `video` that belong to it ("Clip.srt", "Clip.en.srt", "Clip.pt-BR.vtt"),
/// by language; not another video's ("Clip (1).en.srt").
pub fn files_for(video: &Path) -> Vec<Found> {
    let (Some(folder), Some(stem)) = (video.parent(), video.file_stem().and_then(|s| s.to_str())) else {
        return Vec::new();
    };
    let Ok(entries) = std::fs::read_dir(folder) else { return Vec::new() };
    let mut found: Vec<Found> = entries
        .filter_map(Result::ok)
        .filter_map(|entry| {
            let name = entry.file_name().into_string().ok()?;
            let (rest, ext) = name.rsplit_once('.')?;
            if !(ext.eq_ignore_ascii_case("srt") || ext.eq_ignore_ascii_case("vtt")) {
                return None;
            }
            let language = if rest == stem {
                None
            } else {
                let tag = rest.strip_prefix(stem)?.strip_prefix('.')?;
                Some(file_language(tag).filter(|l| l.len() == tag.len())?)
            };
            entry.file_type().ok()?.is_file().then(|| Found { language, path: entry.path() })
        })
        .collect();
    found.sort_by(|a, b| a.language.cmp(&b.language).then(a.path.cmp(&b.path)));
    found
}

/// One language's subtitles for a video, as the player gets them: the lines, and which shows when.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Track {
    pub language: Option<String>,
    pub lines: Vec<Line>,
    pub spans: Vec<Span>,
}

/// Every language of subtitles kept next to the video at `video`, read; files that hold no lines are left out.
pub fn tracks_for(video: &Path) -> Vec<Track> {
    files_for(video)
        .into_iter()
        .filter_map(|found| {
            let bytes = std::fs::read(&found.path).ok()?;
            let lines = parse(&String::from_utf8_lossy(&bytes));
            let spans = timeline(&lines);
            (!lines.is_empty()).then_some(Track { language: found.language, lines, spans })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn texts(lines: &[Line]) -> Vec<&str> {
        lines.iter().map(|l| l.text.as_str()).collect()
    }

    #[test]
    fn an_srt_is_read_with_its_times_and_lines() {
        let srt =
            "\u{feff}1\r\n00:00:01,000 --> 00:00:02,500\r\nHello there.\r\n\r\n2\r\n00:01:02,050 --> 01:00:04,000\r\n\
                   Two lines,\r\nof one subtitle.\r\n\r\n";
        let lines = parse(srt);
        assert_eq!(
            lines,
            [
                Line { start: 1000, end: 2500, text: "Hello there.".into() },
                Line { start: 62_050, end: 3_604_000, text: "Two lines,\nof one subtitle.".into() },
            ]
        );
    }

    #[test]
    fn a_vtt_is_read_without_its_header_notes_styles_or_cue_settings() {
        let vtt = "WEBVTT - made by hand\nKind: captions\nLanguage: en\n\nSTYLE\n::cue { color: yellow }\n\n\
                   NOTE a note,\nover two lines\n\nintro\n00:01.000 --> 00:04.000 align:start position:10%\n\
                   <v Roger>Hi, <i>Bob</i>.</v>\n\n00:00:04.500 --> 00:00:06.000\n<c.yellow>Hello</c> &amp; goodbye\n";
        let lines = parse(vtt);
        assert_eq!(texts(&lines), ["Hi, Bob.", "Hello & goodbye"]);
        assert_eq!((lines[0].start, lines[0].end), (1000, 4000));
        assert_eq!((lines[1].start, lines[1].end), (4500, 6000));
    }

    #[test]
    fn styling_tags_and_overrides_come_off_and_entities_are_read() {
        let srt =
            "1\n00:00:01,000 --> 00:00:02,000\n{\\an8}<font color=\"#ffff00\">Up <b>here</b></font>&nbsp;&lt;3\n\n\
                   2\n00:00:03,000 --> 00:00:04,000\n<i></i>\n\n";
        assert_eq!(texts(&parse(srt)), ["Up here <3"], "a line with nothing left is left out");
    }

    #[test]
    fn times_are_read_in_every_spelling_the_files_use() {
        assert_eq!(time("01:02:03,456"), Some(3_723_456));
        assert_eq!(time("01:02:03.456"), Some(3_723_456));
        assert_eq!(time("02:03.4"), Some(123_400));
        assert_eq!(time("00:00:05"), Some(5000));
        assert_eq!(time("1:2"), Some(62_000));
        assert_eq!(time("00:61.000"), None);
        assert_eq!(time("aa:00.000"), None);
        assert_eq!(time("00:00:01.0000"), None);
        assert_eq!(timing("00:01.000 --> 00:02.000 line:0"), Some((1000, 2000)));
        assert_eq!(timing("not --> a time"), None);
    }

    #[test]
    fn broken_blocks_are_skipped_and_the_rest_kept() {
        let srt = "1\n00:00:01,000 -> 00:00:02,000\nNo arrow.\n\n2\n00:00:05,000 --> 00:00:03,000\nBackwards.\n\n\
                   3\n00:00:06,000 --> 00:00:07,000\nKept.\n";
        assert_eq!(texts(&parse(srt)), ["Kept."]);
    }

    #[test]
    fn blocks_parted_by_spaces_rather_than_empty_lines_still_part() {
        let srt = "1\n00:00:01,000 --> 00:00:02,000\nFirst.\n \n2\n00:00:03,000 --> 00:00:04,000\nSecond.\n";
        assert_eq!(texts(&parse(srt)), ["First.", "Second."]);
    }

    #[test]
    fn lines_come_in_the_order_they_start() {
        let srt = "2\n00:00:05,000 --> 00:00:06,000\nLater.\n\n1\n00:00:01,000 --> 00:00:02,000\nSooner.\n";
        assert_eq!(texts(&parse(srt)), ["Sooner.", "Later."]);
    }

    /// YouTube's machine captions, as yt-dlp's VTT has them: each cue repeats the line before, with word times.
    const ROLLING: &str = "WEBVTT\nKind: captions\nLanguage: en\n\n\
        00:00:00.000 --> 00:00:02.490 align:start position:0%\n \nall<00:00:00.400><c> right</c><00:00:00.800><c> so</c>\n\n\
        00:00:02.490 --> 00:00:02.500 align:start position:0%\nall right so\n \n\n\
        00:00:02.500 --> 00:00:05.000 align:start position:0%\nall right so\nhere<00:00:03.000><c> we</c><00:00:03.500><c> are</c>\n\n\
        00:00:05.000 --> 00:00:05.010 align:start position:0%\nhere we are\n \n\n\
        00:00:05.010 --> 00:00:07.000 align:start position:0%\nhere we are\nin<00:00:05.500><c> front</c>\n";

    #[test]
    fn youtubes_rolling_captions_become_one_line_after_another() {
        let lines = parse(ROLLING);
        assert_eq!(texts(&lines), ["all right so", "here we are", "in front"]);
        assert_eq!((lines[1].start, lines[1].end), (2500, 5000));
    }

    #[test]
    fn a_line_said_twice_in_a_film_stays_twice() {
        let srt = "1\n00:00:01,000 --> 00:00:02,000\nNo!\n\n2\n00:00:02,000 --> 00:00:03,000\nNo!\n";
        assert_eq!(texts(&parse(srt)), ["No!", "No!"]);
    }

    #[test]
    fn written_as_srt_and_read_again_the_lines_are_the_same() {
        let lines = parse(ROLLING);
        let srt = to_srt(&lines);
        assert!(srt.starts_with("1\n00:00:00,000 --> 00:00:02,490\nall right so\n\n2\n"), "{srt}");
        assert_eq!(parse(&srt), lines);
        assert_eq!(
            to_srt(&[Line { start: 3_723_456, end: 3_724_000, text: "a".into() }]).lines().nth(1),
            Some("01:02:03,456 --> 01:02:04,000")
        );
    }

    fn line(start: u64, end: u64) -> Line {
        Line { start, end, text: format!("{start}") }
    }

    #[test]
    fn the_line_at_a_time_is_the_one_running_then_and_none_between_lines() {
        let lines = [line(1000, 2000), line(3000, 4000)];
        let spans = timeline(&lines);
        assert_eq!(line_at(&spans, 0), None);
        assert_eq!(line_at(&spans, 1000), Some(0));
        assert_eq!(line_at(&spans, 1999), Some(0));
        assert_eq!(line_at(&spans, 2000), None, "a line ends at its end");
        assert_eq!(line_at(&spans, 3500), Some(1));
        assert_eq!(line_at(&spans, 9000), None);
        assert_eq!(line_at(&[], 1000), None);
    }

    #[test]
    fn where_lines_overlap_the_later_shows_and_the_earlier_comes_back_after_it() {
        // A long line, a short one said over it, then one that starts as the long one ends.
        let lines = [line(0, 5000), line(1000, 2000), line(5000, 6000)];
        let spans = timeline(&lines);
        assert_eq!(
            spans,
            [
                Span { start: 0, end: 1000, line: 0 },
                Span { start: 1000, end: 2000, line: 1 },
                Span { start: 2000, end: 5000, line: 0 },
                Span { start: 5000, end: 6000, line: 2 },
            ]
        );
        assert_eq!(line_at(&spans, 1500), Some(1));
        assert_eq!(line_at(&spans, 2500), Some(0));
        // Two lines starting together: the one listed later, as the file has them.
        assert_eq!(line_at(&timeline(&[line(0, 3000), line(0, 1000)]), 500), Some(1));
    }

    fn track(language: &str, automatic: bool) -> SubtitleTrack {
        SubtitleTrack { language: language.into(), url: format!("https://subs.example/{language}"), automatic }
    }

    #[test]
    fn the_videos_own_language_is_saved_made_subtitles_before_machine_captions() {
        let tracks = [track("es", false), track("en-US", false), track("en-orig", true), track("en", true)];
        assert_eq!(pick(&tracks, Some("en")).map(|t| t.language.as_str()), Some("en-US"));
        let only_machine = [track("de", true), track("en", true), track("en-orig", true)];
        assert_eq!(pick(&only_machine, Some("en")).map(|t| t.language.as_str()), Some("en-orig"));
        assert_eq!(pick(&[track("en", true)], Some("en-GB")).map(|t| t.language.as_str()), Some("en"));
        // A Spanish video with only English subtitles and Spanish captions: the captions, in the words spoken.
        let spanish = [track("en", false), track("es-orig", true), track("en", true)];
        assert_eq!(pick(&spanish, Some("es")).map(|t| t.language.as_str()), Some("es-orig"));
    }

    #[test]
    fn without_the_videos_language_the_first_subtitles_or_the_original_captions_are_saved() {
        assert_eq!(pick(&[track("fr", false), track("en", false)], None).map(|t| t.language.as_str()), Some("fr"));
        assert_eq!(
            pick(&[track("fr", true), track("ja-orig", true)], None).map(|t| t.language.as_str()),
            Some("ja-orig")
        );
        assert_eq!(pick(&[track("fr", true)], None), None, "a machine translation never");
        assert_eq!(pick(&[track("fr", false)], Some("de")), None, "nothing in the video's language");
        assert_eq!(pick(&[], Some("en")), None);
    }

    #[test]
    fn languages_are_written_into_names_as_players_read_them() {
        assert_eq!(file_language("en").as_deref(), Some("en"));
        assert_eq!(file_language("en-orig").as_deref(), Some("en"));
        assert_eq!(file_language("pt-BR").as_deref(), Some("pt-BR"));
        assert_eq!(file_language("zh-Hans").as_deref(), Some("zh-Hans"));
        assert_eq!(file_language("es-419").as_deref(), Some("es-419"));
        assert_eq!(file_language("en-nP7-2PuUl7o").as_deref(), Some("en"));
        assert_eq!(file_language("EN").as_deref(), Some("en"));
        assert_eq!(file_language("live_chat"), None);
        assert_eq!(file_language("../x"), None);
        assert_eq!(file_language(""), None);
    }

    #[test]
    fn a_videos_subtitles_are_the_files_named_after_it() {
        let dir = tempfile::tempdir().unwrap();
        let video = dir.path().join("Clip (720p).mp4");
        for name in [
            "Clip (720p).mp4",
            "Clip (720p).en.srt",
            "Clip (720p).pt-BR.VTT",
            "Clip (720p).srt",
            "Clip (720p) (1).en.srt",
            "Clip (720p).en.txt",
            "Clip (720p).not a language.srt",
            "Other.en.srt",
        ] {
            std::fs::write(dir.path().join(name), "").unwrap();
        }
        let found = files_for(&video);
        let names: Vec<(Option<&str>, String)> = found
            .iter()
            .map(|f| (f.language.as_deref(), f.path.file_name().unwrap().to_string_lossy().into_owned()))
            .collect();
        assert_eq!(
            names,
            [
                (None, "Clip (720p).srt".to_string()),
                (Some("en"), "Clip (720p).en.srt".to_string()),
                (Some("pt-BR"), "Clip (720p).pt-BR.VTT".to_string()),
            ]
        );
        assert_eq!(file_for(&video, "en"), dir.path().join("Clip (720p).en.srt"));
        assert!(files_for(&dir.path().join("missing").join("x.mp4")).is_empty());
    }

    #[test]
    fn the_player_gets_each_languages_lines_and_when_each_shows() {
        let dir = tempfile::tempdir().unwrap();
        let video = dir.path().join("Clip.mp4");
        std::fs::write(dir.path().join("Clip.en.srt"), to_srt(&[line(1000, 2000), line(3000, 4000)])).unwrap();
        std::fs::write(dir.path().join("Clip.es.vtt"), "WEBVTT\n\n00:01.000 --> 00:02.000\nHola\n").unwrap();
        std::fs::write(dir.path().join("Clip.de.srt"), "nothing here").unwrap();
        let tracks = tracks_for(&video);
        assert_eq!(tracks.iter().map(|t| t.language.as_deref()).collect::<Vec<_>>(), [Some("en"), Some("es")]);
        assert_eq!(texts(&tracks[0].lines), ["1000", "3000"]);
        assert_eq!(line_at(&tracks[0].spans, 3500), Some(1));
        assert_eq!(texts(&tracks[1].lines), ["Hola"]);
    }
}
