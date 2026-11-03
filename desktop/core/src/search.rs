//! A search across what is said in every saved video: the lines of the subtitles kept next to each one, in every
//! language there ("name.en.srt", "name.fr.srt"), found by words as the Downloads list's find box is typed into.
//!
//! Words are matched ignoring case and accents ("ecole" finds "École", "strasse" finds "Straße"), each anywhere in the
//! line and in any order; a line is found when it holds all of them. What matched is marked, as ranges of the line's
//! own text counted in UTF-16 units, as the window counts.
//!
//! The lines are kept in memory (`Index`), folded once when read, so a search reads no file: the app keeps one index,
//! and before a search looks again at which subtitle files each video has, re-reading only a file whose size or time
//! changed since (`Index::keep_up`), with each folder listed once.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime};

use serde::Serialize;
use unicode_normalization::char::decompose_canonical;

use crate::subtitles::{self, Line};

/// The most lines a search gives back; the rest are counted.
pub const MOST: usize = 200;
/// How long the subtitle files seen are trusted before they are looked at again.
pub const RECHECK: Duration = Duration::from_secs(1);

/// A saved video (or sound) to search the lines of: its download number, the title the list shows, and its file.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Video {
    pub id: u64,
    pub title: String,
    pub path: PathBuf,
}

/// A line found: the video's number and title, the language of its subtitles (None for "name.srt"), when it shows
/// (milliseconds), its text as the subtitles have it, and the ranges of that text that matched, in UTF-16 units.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Hit {
    pub id: u64,
    pub title: String,
    pub language: Option<String>,
    pub start: u64,
    pub end: u64,
    pub text: String,
    pub marks: Vec<[usize; 2]>,
}

/// What a search found: at most `MOST` lines, by video (in the order the videos were given) and then by time, and how
/// many lines matched in all.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct Results {
    pub hits: Vec<Hit>,
    pub total: usize,
}

/// One subtitles file as it was when read: its size and when it was last written.
type Stamp = (PathBuf, u64, Option<SystemTime>);

struct Track {
    language: Option<String>,
    lines: Vec<Line>,
    /// Every line folded (`fold`), one after another with "\n" between, so a word is looked for in the whole track at
    /// once; and where each line starts in it.
    folded: String,
    starts: Vec<usize>,
}

struct Indexed {
    video: Video,
    files: Vec<Stamp>,
    tracks: Vec<Track>,
}

/// Every saved video's lines, folded for searching.
#[derive(Default)]
pub struct Index {
    videos: Vec<Indexed>,
    checked: Option<Instant>,
}

impl Index {
    /// Looks at the subtitle files of `videos` again when the videos are not those indexed, or the files were last
    /// looked at more than `RECHECK` before `now`. True when it looked.
    pub fn keep_up(&mut self, videos: &[Video], now: Instant) -> bool {
        let same = videos.len() == self.videos.len() && videos.iter().zip(&self.videos).all(|(v, i)| *v == i.video);
        let fresh = self.checked.is_some_and(|at| now.saturating_duration_since(at) < RECHECK);
        if same && fresh {
            return false;
        }
        self.refresh(videos);
        self.checked = Some(now);
        true
    }

    /// Indexes `videos`, in their order: a video no longer among them is let go, and of the rest, only the subtitle
    /// files that are new, or whose size or time changed, are read. Each folder is listed once. How many files were
    /// read.
    pub fn refresh(&mut self, videos: &[Video]) -> usize {
        let mut folders: HashMap<PathBuf, Vec<(String, Stamp)>> = HashMap::new();
        let mut old: HashMap<u64, Indexed> = self.videos.drain(..).map(|i| (i.video.id, i)).collect();
        let mut read = 0;
        for video in videos {
            let folder = video.path.parent().map(Path::to_path_buf).unwrap_or_default();
            let listed = folders.entry(folder.clone()).or_insert_with(|| list(&folder));
            let files = files_of(&video.path, listed);
            let kept = old.remove(&video.id).filter(|i| i.video.path == video.path && i.files == files);
            let indexed = match kept {
                Some(mut kept) => {
                    kept.video.title.clone_from(&video.title);
                    kept
                }
                None => {
                    read += files.len();
                    let tracks = files.iter().filter_map(|(path, _, _)| read_track(path, &video.path)).collect();
                    Indexed { video: video.clone(), files, tracks }
                }
            };
            self.videos.push(indexed);
        }
        read
    }

    /// The lines that hold every word of `query`, in the videos `among` (all, when None), at most `most` of them.
    pub fn search(&self, query: &str, among: Option<&[u64]>, most: usize) -> Results {
        let words = words_of(query);
        let Some(longest) = words.first() else { return Results::default() };
        let mut results = Results::default();
        for indexed in &self.videos {
            if among.is_some_and(|ids| !ids.contains(&indexed.video.id)) {
                continue;
            }
            let mut found: Vec<(&Track, &Line)> = Vec::new();
            for track in &indexed.tracks {
                let mut last = None;
                for (at, _) in track.folded.match_indices(longest.as_str()) {
                    let line = track.starts.partition_point(|&s| s <= at) - 1;
                    if last == Some(line) {
                        continue;
                    }
                    last = Some(line);
                    let end = track.starts.get(line + 1).map_or(track.folded.len(), |next| next - 1);
                    let text = &track.folded[track.starts[line]..end];
                    if words[1..].iter().all(|w| text.contains(w.as_str())) {
                        found.push((track, &track.lines[line]));
                    }
                }
            }
            results.total += found.len();
            found.sort_by(|a, b| (a.1.start, &a.0.language).cmp(&(b.1.start, &b.0.language)));
            for (track, line) in found.into_iter().take(most.saturating_sub(results.hits.len())) {
                results.hits.push(Hit {
                    id: indexed.video.id,
                    title: indexed.video.title.clone(),
                    language: track.language.clone(),
                    start: line.start,
                    end: line.end,
                    text: line.text.clone(),
                    marks: marks(&line.text, &words),
                });
            }
        }
        results
    }
}

/// The files in `folder`, each with its size and time, listed once for every video in it.
fn list(folder: &Path) -> Vec<(String, Stamp)> {
    let Ok(entries) = std::fs::read_dir(folder) else { return Vec::new() };
    entries
        .filter_map(Result::ok)
        .filter_map(|entry| {
            let name = entry.file_name().into_string().ok()?;
            let meta = entry.metadata().ok().filter(std::fs::Metadata::is_file)?;
            Some((name, (entry.path(), meta.len(), meta.modified().ok())))
        })
        .collect()
}

/// Of a folder's files, the subtitles that belong to the video at `video` (`subtitles::belongs_to`), in a fixed order.
fn files_of(video: &Path, listed: &[(String, Stamp)]) -> Vec<Stamp> {
    let Some(stem) = video.file_stem().and_then(|s| s.to_str()) else { return Vec::new() };
    let mut files: Vec<Stamp> =
        listed.iter().filter(|(name, _)| subtitles::belongs_to(name, stem).is_some()).map(|(_, s)| s.clone()).collect();
    files.sort_by(|a, b| a.0.cmp(&b.0));
    files
}

fn read_track(path: &Path, video: &Path) -> Option<Track> {
    let name = path.file_name()?.to_str()?;
    let language = subtitles::belongs_to(name, video.file_stem()?.to_str()?)?;
    let bytes = std::fs::read(path).ok()?;
    let lines = subtitles::parse(&String::from_utf8_lossy(&bytes));
    if lines.is_empty() {
        return None;
    }
    let mut folded = String::new();
    let mut starts = Vec::with_capacity(lines.len());
    for line in &lines {
        if !starts.is_empty() {
            folded.push('\n');
        }
        starts.push(folded.len());
        fold_into(&line.text, &mut folded, |_| {});
    }
    Some(Track { language, lines, folded, starts })
}

/// `text` as it is searched: lower case, without accents, with letters some languages write as two ("ß", "æ") as two,
/// and any space or line break as one space.
pub fn fold(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    fold_into(text, &mut out, |_| {});
    out
}

/// Folds `text` onto `out`, calling `each` with the number of bytes each of its characters became.
fn fold_into(text: &str, out: &mut String, mut each: impl FnMut(usize)) {
    for c in text.chars() {
        let before = out.len();
        if c.is_whitespace() {
            out.push(' ');
        } else {
            for lower in c.to_lowercase() {
                match lower {
                    'ß' => out.push_str("ss"),
                    'æ' => out.push_str("ae"),
                    'œ' => out.push_str("oe"),
                    'ø' => out.push('o'),
                    'đ' | 'ð' => out.push('d'),
                    'ł' => out.push('l'),
                    'ı' => out.push('i'),
                    'þ' => out.push_str("th"),
                    '\u{2018}' | '\u{2019}' | '\u{02bc}' => out.push('\''),
                    _ => decompose_canonical(lower, |part| {
                        if !is_accent(part) {
                            out.push(part);
                        }
                    }),
                }
            }
        }
        each(out.len() - before);
    }
}

/// An accent, as taking a letter apart leaves it (the "´" of "é"): the combining marks Latin, Greek and Cyrillic
/// letters are written with. Other scripts' marks (Devanagari's vowel signs) are part of the word, and stay.
fn is_accent(c: char) -> bool {
    matches!(c, '\u{300}'..='\u{36f}' | '\u{1ab0}'..='\u{1aff}' | '\u{1dc0}'..='\u{1dff}' | '\u{20d0}'..='\u{20ff}' | '\u{fe20}'..='\u{fe2f}')
}

/// The words of a query, folded, without the punctuation at their ends ("walk," is "walk"), each once, longest first.
fn words_of(query: &str) -> Vec<String> {
    let mut words: Vec<String> = Vec::new();
    for word in fold(query).split(' ') {
        let word = word.trim_matches(|c: char| !c.is_alphanumeric());
        if !word.is_empty() && !words.iter().any(|w| w == word) {
            words.push(word.to_string());
        }
    }
    words.sort_by_key(|w| std::cmp::Reverse(w.len()));
    words
}

/// Where in `text` each of `words` is (every time), as ranges of `text` in UTF-16 units, in order and joined where they
/// touch or overlap.
fn marks(text: &str, words: &[String]) -> Vec<[usize; 2]> {
    // Each byte of the folded text, and the range of `text` (in UTF-16 units) of the character it came from.
    let mut from: Vec<[usize; 2]> = Vec::with_capacity(text.len());
    let mut folded = String::with_capacity(text.len());
    let mut units = text.chars().map(char::len_utf16);
    let mut at = 0;
    fold_into(text, &mut folded, |bytes| {
        let width = units.next().unwrap_or(1);
        from.extend(std::iter::repeat_n([at, at + width], bytes));
        at += width;
    });
    let mut ranges: Vec<[usize; 2]> = words
        .iter()
        .flat_map(|word| folded.match_indices(word.as_str()).map(|(i, w)| [from[i][0], from[i + w.len() - 1][1]]))
        .collect();
    ranges.sort_unstable();
    let mut joined: Vec<[usize; 2]> = Vec::with_capacity(ranges.len());
    for range in ranges {
        match joined.last_mut() {
            Some(last) if range[0] <= last[1] => last[1] = last[1].max(range[1]),
            _ => joined.push(range),
        }
    }
    joined
}

#[cfg(test)]
mod tests {
    use super::*;

    fn srt(lines: &[(u64, &str)]) -> String {
        let time = |ms: u64| format!("00:{:02}:{:02},{:03}", ms / 60_000, ms / 1000 % 60, ms % 1000);
        lines
            .iter()
            .enumerate()
            .map(|(i, (start, text))| format!("{}\n{} --> {}\n{text}\n\n", i + 1, time(*start), time(start + 2000)))
            .collect()
    }

    fn video(id: u64, path: &Path) -> Video {
        Video { id, title: format!("Video {id}"), path: path.to_path_buf() }
    }

    fn texts(results: &Results) -> Vec<(u64, u64, &str)> {
        results.hits.iter().map(|h| (h.id, h.start, h.text.as_str())).collect()
    }

    /// The text the marks cover, as the window would mark it.
    fn marked(hit: &Hit) -> Vec<String> {
        let units: Vec<u16> = hit.text.encode_utf16().collect();
        hit.marks.iter().map(|[a, b]| String::from_utf16(&units[*a..*b]).unwrap()).collect()
    }

    #[test]
    fn case_and_accents_are_folded_away() {
        assert_eq!(fold("École MAISON"), "ecole maison");
        assert_eq!(fold("Straße, Æsir, Œuvre, Øre"), "strasse, aesir, oeuvre, ore");
        assert_eq!(fold("niño\ncafé  crème"), "nino cafe  creme");
        assert_eq!(fold("Tiếng Việt"), "tieng viet");
        assert_eq!(fold("don\u{2019}t"), "don't");
        // Already decomposed: the accent is a character of its own, and goes.
        assert_eq!(fold("e\u{301}te\u{301}"), "ete");
        // Scripts without case or accents are left as they are.
        assert_eq!(fold("नमस्ते 你好"), "नमस्ते 你好");
    }

    #[test]
    fn a_query_is_its_words_each_once_longest_first() {
        assert_eq!(words_of("  the Walk, the PARK!  "), ["walk", "park", "the"]);
        assert_eq!(words_of("¿Qué?"), ["que"]);
        assert!(words_of(" ... ").is_empty());
    }

    fn one_video(lines: &[(u64, &str)]) -> (tempfile::TempDir, Index) {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("Talk.mp4");
        std::fs::write(&path, b"").unwrap();
        std::fs::write(dir.path().join("Talk.en.srt"), srt(lines)).unwrap();
        let mut index = Index::default();
        index.refresh(&[video(1, &path)]);
        (dir, index)
    }

    #[test]
    fn a_line_is_found_when_it_holds_every_word_in_any_order() {
        let (_dir, index) = one_video(&[
            (1000, "This is a walk in the park."),
            (5000, "The park is quiet."),
            (9000, "We walk home,\nthrough the PARK."),
            (13000, "Nothing here."),
        ]);

        assert_eq!(
            texts(&index.search("park walk", None, MOST)),
            [(1, 1000, "This is a walk in the park."), (1, 9000, "We walk home,\nthrough the PARK.")]
        );
        assert_eq!(index.search("PARK", None, MOST).total, 3);
        assert_eq!(index.search("walking", None, MOST).total, 0);
        assert_eq!(index.search("", None, MOST), Results::default());
        // Words are not found across two lines.
        assert_eq!(index.search("quiet we", None, MOST).total, 0);
    }

    #[test]
    fn what_matched_is_marked_in_the_lines_own_text() {
        let (_dir, index) =
            one_video(&[(1000, "Le café de l'École, CAFÉ crème"), (3000, "Straße und Strasse"), (5000, "😀 café")]);

        let found = index.search("cafe ecole", None, MOST);
        assert_eq!(found.hits[0].marks, [[3, 7], [13, 18], [20, 24]]);
        assert_eq!(marked(&found.hits[0]), ["café", "École", "CAFÉ"]);

        let found = index.search("strasse", None, MOST);
        assert_eq!(marked(&found.hits[0]), ["Straße", "Strasse"]);

        // Counted in UTF-16 units: the face before it is two.
        let found = index.search("café", None, MOST);
        assert_eq!(
            found.hits.iter().map(|h| h.marks.clone()).collect::<Vec<_>>(),
            [vec![[3, 7], [20, 24]], vec![[3, 7]]]
        );
        assert_eq!(marked(&found.hits[1]), ["café"]);

        // Words that overlap are one mark.
        let (_dir, index) = one_video(&[(1000, "a parkland")]);
        assert_eq!(index.search("park land", None, MOST).hits[0].marks, [[2, 10]]);
    }

    #[test]
    fn lines_come_by_video_in_the_order_given_then_by_time_across_languages() {
        let dir = tempfile::tempdir().unwrap();
        let (a, b) = (dir.path().join("A.mp4"), dir.path().join("B.mp4"));
        std::fs::write(dir.path().join("A.en.srt"), srt(&[(1000, "one walk"), (9000, "two walk")])).unwrap();
        std::fs::write(dir.path().join("A.fr.srt"), srt(&[(5000, "une walk")])).unwrap();
        std::fs::write(dir.path().join("B.srt"), srt(&[(2000, "walk on")])).unwrap();
        let mut index = Index::default();
        index.refresh(&[video(2, &b), video(1, &a)]);

        let found = index.search("walk", None, MOST);
        assert_eq!(
            texts(&found),
            [(2, 2000, "walk on"), (1, 1000, "one walk"), (1, 5000, "une walk"), (1, 9000, "two walk")]
        );
        let languages: Vec<Option<&str>> = found.hits.iter().map(|h| h.language.as_deref()).collect();
        assert_eq!(languages, [None, Some("en"), Some("fr"), Some("en")]);
        assert_eq!(found.hits[1].title, "Video 1");
        assert_eq!(found.hits[1].end, 3000);

        // Only the videos asked about.
        assert_eq!(texts(&index.search("walk", Some(&[2]), MOST)), [(2, 2000, "walk on")]);
    }

    #[test]
    fn at_most_so_many_lines_come_back_and_the_rest_are_counted() {
        let many: Vec<(u64, String)> = (0..300).map(|i| (i * 3000, format!("Line {i} of the walk"))).collect();
        let lines: Vec<(u64, &str)> = many.iter().map(|(t, s)| (*t, s.as_str())).collect();
        let (_dir, index) = one_video(&lines);

        let found = index.search("walk", None, MOST);
        assert_eq!((found.hits.len(), found.total), (MOST, 300));
        assert_eq!(found.hits[0].text, "Line 0 of the walk");
        assert_eq!(index.search("walk", None, 5).hits.len(), 5);
    }

    #[test]
    fn only_subtitles_that_changed_are_read_again() {
        let dir = tempfile::tempdir().unwrap();
        let (a, b) = (dir.path().join("A.mp4"), dir.path().join("B.mp4"));
        std::fs::write(dir.path().join("A.en.srt"), srt(&[(1000, "an old line")])).unwrap();
        std::fs::write(dir.path().join("B.en.srt"), srt(&[(1000, "another line")])).unwrap();
        // Another video's subtitles, which are not A's.
        std::fs::write(dir.path().join("A (2).en.srt"), srt(&[(1000, "not this line")])).unwrap();
        let mut index = Index::default();
        let videos = [video(1, &a), video(2, &b)];

        assert_eq!(index.refresh(&videos), 2);
        assert_eq!(index.refresh(&videos), 0, "nothing changed");
        assert_eq!(index.search("line", None, MOST).total, 2);

        // A's subtitles made again, and a translation beside them.
        std::fs::write(dir.path().join("A.en.srt"), srt(&[(1000, "a new line, longer")])).unwrap();
        std::fs::write(dir.path().join("A.fr.srt"), srt(&[(1000, "une ligne")])).unwrap();
        assert_eq!(index.refresh(&videos), 2, "A's two files, not B's");
        assert_eq!(texts(&index.search("new", None, MOST)), [(1, 1000, "a new line, longer")]);
        assert_eq!(index.search("old", None, MOST).total, 0);
        assert_eq!(index.search("ligne", None, MOST).total, 1);

        // A taken off the list: its lines go. A video added: its are read.
        let c = dir.path().join("C.mkv");
        std::fs::write(dir.path().join("C.vtt"), "WEBVTT\n\n00:01.000 --> 00:02.000\nthe third line\n").unwrap();
        assert_eq!(index.refresh(&[video(2, &b), video(3, &c)]), 1);
        assert_eq!(texts(&index.search("line", None, MOST)), [(2, 1000, "another line"), (3, 1000, "the third line")]);

        // Subtitles deleted: the video stays, with nothing to find.
        std::fs::remove_file(dir.path().join("C.vtt")).unwrap();
        index.refresh(&[video(2, &b), video(3, &c)]);
        assert_eq!(index.search("third", None, MOST).total, 0);
    }

    #[test]
    fn the_files_are_looked_at_again_only_after_a_while_or_when_the_videos_change() {
        let dir = tempfile::tempdir().unwrap();
        let a = dir.path().join("A.mp4");
        std::fs::write(dir.path().join("A.srt"), srt(&[(1000, "hello")])).unwrap();
        let mut index = Index::default();
        let start = Instant::now();
        let videos = vec![video(1, &a)];

        assert!(index.keep_up(&videos, start));
        assert!(!index.keep_up(&videos, start + Duration::from_millis(300)));
        assert!(index.keep_up(&videos, start + RECHECK + Duration::from_millis(1)));
        let renamed = vec![Video { title: "Renamed".into(), ..videos[0].clone() }];
        assert!(index.keep_up(&renamed, start + RECHECK + Duration::from_millis(2)));
        assert_eq!(index.search("hello", None, MOST).hits[0].title, "Renamed");
    }

    #[test]
    fn a_large_index_is_searched_quickly() {
        // 100 videos of 1,000 lines each, in one folder, as a Downloads folder might hold.
        let dir = tempfile::tempdir().unwrap();
        let mut videos = Vec::new();
        for id in 0..100u64 {
            let path = dir.path().join(format!("Video {id}.mp4"));
            let lines: Vec<(u64, String)> =
                (0..1000).map(|i| (i * 2000, format!("Line {i} of video {id}, where we talk about things"))).collect();
            let lines: Vec<(u64, &str)> = lines.iter().map(|(t, s)| (*t, s.as_str())).collect();
            std::fs::write(dir.path().join(format!("Video {id}.en.srt")), srt(&lines)).unwrap();
            videos.push(video(id, &path));
        }
        let mut index = Index::default();
        index.refresh(&videos);

        let began = Instant::now();
        let found = index.search("talk 999", None, MOST);
        assert_eq!(index.refresh(&videos), 0);
        let took = began.elapsed();
        assert_eq!(found.total, 100);
        // Generous, for a debug build on a busy machine; a release build takes a few milliseconds.
        assert!(took < Duration::from_secs(2), "{took:?}");
    }
}
