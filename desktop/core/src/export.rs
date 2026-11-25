//! A clip saved as a video of its own (step 8): cut exactly from the saved video, from its first line's time (not the
//! keyframe before it), scaled to 720p, 1080p or left as it is, with its subtitles drawn on by libass, and encoded by
//! the engine's ffmpeg as H.264 with Windows' own encoder (on the graphics card when there is one, else on the
//! processor) or, where Windows has none, as MPEG-4; the sound as AAC. Or its subtitles alone, as an SRT file timed
//! from 0:00.
//!
//! The subtitles are written as an ASS file for libass, in one of the looks (Outline, Box, Large, and the word looks:
//! Highlight, Karaoke, Pop, One at a time), with one language or two; each language keeps the colour, font and size
//! the person gave it in the player. Two languages are drawn as one block, line by line, so neither ever covers the
//! other.
//!
//! The word looks follow each word as it is said, so they need each word's time: Chaya has them for subtitles it made
//! (what whisper.cpp heard, kept in the video's file). A line is drawn word by word only when its words are the ones
//! heard, in order and at their times; a line put right since, or subtitles from a site, are drawn whole, never with a
//! word's time guessed. Only the main language follows its words; a second stays a plain line with it.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;

use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};
use tokio::process::Command;
use tokio::sync::watch;

use crate::program::program;
use crate::speech::whisper::Word;
use crate::subtitles::{self, Line, Span};

/// How big the video is made: as tall as 720 or 1080 pixels (never taller than it was), or as it is.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Quality {
    P720,
    P1080,
    Original,
}

/// How the subtitles look: white words outlined in black (as the player draws them), on a dark box, or large; or
/// following the words as they are said.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Look {
    Outline,
    Boxed,
    Large,
    /// The line stays; the word being said is in the accent colour.
    Highlight,
    /// The words fill with the accent colour, left to right, as they are said.
    Karaoke,
    /// The word being said is in the accent colour, a little bigger, and settles.
    Pop,
    /// A few words at a time, large, higher up: only those being said, the one being said in the accent colour.
    OneAtATime,
}

/// What the word being said does in a look.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Now {
    /// Nothing: the line is drawn whole.
    Nothing,
    Coloured,
    Filled,
    Grown,
    /// Only it and its neighbours are shown, it in the accent colour.
    Alone,
}

/// A look, as what it draws: how big (of the picture's narrow side), on a box or outlined, raised above the usual
/// place, and what the word being said does.
#[derive(Clone, Copy, Debug, PartialEq)]
struct Shape {
    size: f64,
    boxed: bool,
    raised: bool,
    now: Now,
}

impl Look {
    fn shape(self) -> Shape {
        let shape = |size, boxed, raised, now| Shape { size, boxed, raised, now };
        match self {
            Look::Outline => shape(0.052, false, false, Now::Nothing),
            Look::Boxed => shape(0.052, true, false, Now::Nothing),
            Look::Large => shape(0.075, false, false, Now::Nothing),
            Look::Highlight => shape(0.058, false, false, Now::Coloured),
            Look::Karaoke => shape(0.058, false, false, Now::Filled),
            Look::Pop => shape(0.062, false, false, Now::Grown),
            Look::OneAtATime => shape(0.095, false, true, Now::Alone),
        }
    }

    /// Whether it follows each word's time; without them, its lines are drawn whole.
    pub fn by_word(self) -> bool {
        self.shape().now != Now::Nothing
    }
}

/// A language's own style, as the player's Subtitles tab keeps it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Style {
    pub color: Color,
    pub font: Font,
    pub size: Size,
}

impl Default for Style {
    fn default() -> Style {
        Style { color: Color::White, font: Font::Sans, size: Size::M }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Color {
    White,
    Yellow,
    Cyan,
    Green,
    Pink,
}

impl Color {
    /// Red, green and blue: the same as the player's (`--subtitle-*` in the design tokens).
    pub(crate) fn rgb(self) -> [u8; 3] {
        match self {
            Color::White => [0xFF, 0xFF, 0xFF],
            Color::Yellow => [0xFF, 0xE4, 0x5C],
            Color::Cyan => [0x7F, 0xE3, 0xFF],
            Color::Green => [0xA6, 0xF0, 0x8A],
            Color::Pink => [0xFF, 0xA3, 0xD1],
        }
    }

    /// As ASS writes a colour: &HAABBGGRR, opaque.
    fn ass(self) -> String {
        let [r, g, b] = self.rgb();
        format!("&H00{b:02X}{g:02X}{r:02X}")
    }

    /// The colour the word being said takes in the word looks: white lights up yellow, every other colour white, so
    /// it always stands out and is the same on every PC.
    pub fn accent(self) -> Color {
        if self == Color::White {
            Color::Yellow
        } else {
            Color::White
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Font {
    Sans,
    Serif,
    Display,
    Mono,
}

impl Font {
    /// The Windows font drawn with, and whether bold. Scripts it lacks (Hindi, Arabic, Chinese...) come from Windows'
    /// own fallback.
    fn ass(self) -> (&'static str, bool) {
        match self {
            Font::Sans => ("Segoe UI", true),
            Font::Serif => ("Georgia", true),
            Font::Display => ("Segoe UI Black", false),
            Font::Mono => ("Consolas", true),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum Size {
    S,
    M,
    L,
    #[serde(rename = "XL")]
    Xl,
}

impl Size {
    /// The same steps as the player's.
    pub(crate) fn scale(self) -> f64 {
        match self {
            Size::S => 0.8,
            Size::M => 1.0,
            Size::L => 1.2,
            Size::Xl => 1.45,
        }
    }
}

/// A language drawn on: its lines and its style, and the words heard in it with their times, when Chaya made these
/// subtitles (empty when it did not).
pub struct Language<'a> {
    pub lines: &'a [Line],
    pub style: Style,
    pub words: &'a [Word],
}

/// The subtitles drawn on a clip: one language, or two (the second above the first when `second_on_top`).
pub struct Burn<'a> {
    pub look: Look,
    pub first: Language<'a>,
    pub second: Option<Language<'a>>,
    pub second_on_top: bool,
}

/// A video's picture size and length, as ffmpeg reads them.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Probe {
    pub width: u32,
    pub height: u32,
    /// Milliseconds, when it says.
    pub duration: Option<u64>,
    /// Frames a second, when it says (an average, for a video whose frames come at uneven times).
    pub frame_rate: Option<f64>,
    pub sound: bool,
    /// The sound's samples a second and channels, when there is sound and it says.
    pub sample_rate: Option<u32>,
    pub channels: Option<u32>,
}

/// What encoded the picture.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Encoder {
    /// Windows' H.264 encoder, on the graphics card.
    GraphicsCard,
    /// Windows' H.264 encoder, on the processor.
    Windows,
    /// MPEG-4, where Windows has no H.264 encoder.
    Mpeg4,
    /// A GIF, which has no sound.
    Gif,
}

#[derive(Debug, PartialEq, Eq)]
pub enum ExportError {
    /// ffmpeg is not in the engine, or would not start.
    NoProgram,
    /// ffmpeg could not read the video.
    CantRead,
    /// The clip's folder or file could not be written.
    CantWrite,
    /// Every encoder failed; what ffmpeg said last.
    Failed(String),
    /// What was asked can't be made of this clip, and why.
    Unfit(String),
    Stopped,
}

impl ExportError {
    pub fn message(&self) -> String {
        match self {
            ExportError::NoProgram => "Chaya's ffmpeg is missing, so it can't export.".into(),
            ExportError::CantRead => "Chaya couldn't read this video.".into(),
            ExportError::CantWrite => "Chaya couldn't write the clip next to the video.".into(),
            ExportError::Failed(said) if said.is_empty() => "ffmpeg couldn't make this clip.".into(),
            ExportError::Failed(said) => format!("ffmpeg couldn't make this clip: {said}"),
            ExportError::Unfit(why) => why.clone(),
            ExportError::Stopped => "Stopped.".into(),
        }
    }
}

// ── Sizes ──

/// The size a picture `width` by `height` is made for `quality`: as tall as asked, never taller than it was, both
/// sides even (H.264 needs that), the shape kept.
pub fn size_for(quality: Quality, width: u32, height: u32) -> (u32, u32) {
    let even = |n: f64| ((n / 2.0).round() as u32).max(1) * 2;
    let tallest = match quality {
        Quality::P720 => 720,
        Quality::P1080 => 1080,
        Quality::Original => height,
    };
    let (w, h) = (width.max(2) as f64, height.max(2) as f64);
    // A picture on its side (a phone's) is limited by its narrow side, as "720p" means for it too.
    let narrow = w.min(h);
    let scale = if quality == Quality::Original { 1.0 } else { (tallest as f64 / narrow).min(1.0) };
    (even(w * scale), even(h * scale))
}

/// The bit rate H.264 is asked for at `width` by `height`: about what sites stream such pictures at, a little more.
fn bit_rate(width: u32, height: u32) -> u32 {
    let pixels = width as u64 * height as u64;
    // 8 Mb/s for 1080p, scaled by pixels, between 1.5 and 24.
    ((pixels * 8_000_000 / (1920 * 1080)) as u32).clamp(1_500_000, 24_000_000)
}

// ── Subtitles: ASS for the picture, SRT for the file ──

/// "0:01:02.35": ASS's time, in hundredths.
fn ass_time(ms: u64) -> String {
    let cs = (ms + 5) / 10;
    format!("{}:{:02}:{:02}.{:02}", cs / 360_000, cs / 6_000 % 60, cs / 100 % 60, cs % 100)
}

/// A line as ASS shows it: its breaks as \N, and no braces or backslashes that ASS would read as commands.
fn ass_text(text: &str) -> String {
    text.trim().replace('\\', "\u{29F5}").replace('{', "(").replace('}', ")").replace("\r\n", "\n").replace('\n', "\\N")
}

/// The ASS file that draws `burn` on a clip from `start` to `end` (milliseconds in the video), `width` by `height`
/// pixels: its times from 0:00, and only what shows in the clip.
pub fn ass(burn: &Burn<'_>, start: u64, end: u64, width: u32, height: u32) -> String {
    let shape = burn.look.shape();
    let base = height.min(width) as f64 * shape.size;
    let style = |name: &str, language: &Language<'_>| {
        let (font, bold) = language.style.font.ass();
        let size = (base * language.style.size.scale()).round();
        let (border, outline_colour, outline, shadow) = if shape.boxed {
            // A box behind the words, three quarters dark, padded.
            (3, "&H40000000", (size * 0.22).max(2.0), 0.0)
        } else {
            // Black around each letter, and a soft shadow, as the player draws them.
            (1, "&H00000000", (size * 0.07).max(1.0), (size * 0.02).round())
        };
        format!(
            "Style: {name},{font},{size},{colour},{colour},{outline_colour},&H80000000,{bold},0,0,0,100,100,0,0,{border},\
             {outline:.1},{shadow},2,{margin_x},{margin_x},{margin_y},1",
            colour = language.style.color.ass(),
            bold = if bold { -1 } else { 0 },
            margin_x = (width as f64 * 0.06).round(),
            // A raised look sits in the lower third, clear of the usual place.
            margin_y = (height as f64 * if shape.raised { 0.16 } else { 0.055 }).round(),
        )
    };
    let mut out = format!(
        "[Script Info]\nScriptType: v4.00+\nPlayResX: {width}\nPlayResY: {height}\nWrapStyle: 0\nScaledBorderAndShadow: \
         yes\nYCbCr Matrix: None\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, \
         OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, \
         Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"
    );
    out += &style("First", &burn.first);
    out.push('\n');
    if let Some(second) = &burn.second {
        out += &style("Second", second);
        out.push('\n');
    }
    out += "\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n";
    let second = burn.second.as_ref().map(|s| s.lines).unwrap_or(&[]);
    // Each main line's words with their times, for a look that follows them.
    let heard: Vec<Option<Vec<&Word>>> = if shape.now == Now::Nothing {
        Vec::new()
    } else {
        burn.first.lines.iter().map(|line| words_of(line, burn.first.words)).collect()
    };
    let colours = (burn.first.style.color.ass(), burn.first.style.color.accent().ass());
    for slice in slices(burn.first.lines, second, start, end) {
        let two = slice.second.as_deref().map(ass_text);
        let words = slice.first_line.and_then(|i| heard.get(i)).and_then(|w| w.as_deref());
        if let (Some(words), Some(_)) = (words, &slice.first) {
            let at = Moments { clip: start, from: start + slice.start, to: start + slice.end };
            for (from, to, one) in by_word(shape.now, words, at, &colours) {
                let text = together(Some(one), two.clone(), burn.second_on_top);
                out += &format!("Dialogue: 0,{},{},First,,0,0,0,,{text}\n", ass_time(from), ass_time(to));
            }
            continue;
        }
        let one = slice.first.as_deref().map(ass_text);
        if one.is_none() && two.is_none() {
            continue;
        }
        let text = together(one.map(|one| format!("{{\\rFirst}}{one}")), two, burn.second_on_top);
        out += &format!("Dialogue: 0,{},{},First,,0,0,0,,{text}\n", ass_time(slice.start), ass_time(slice.end));
    }
    out
}

/// One block of the main language's text (already marked `\rFirst`) and the second's line, either on top.
fn together(one: Option<String>, two: Option<String>, second_on_top: bool) -> String {
    let two = two.map(|two| format!("{{\\rSecond}}{two}"));
    match (one, two) {
        (Some(one), Some(two)) if second_on_top => format!("{two}\\N{one}"),
        (Some(one), Some(two)) => format!("{one}\\N{two}"),
        (one, two) => one.or(two).unwrap_or_default(),
    }
}

/// How far from a line's first and last word Chaya's lines may start and end (it shows them a little early and keeps
/// them a little late).
const WORDS_SLACK: u64 = 1_000;

/// `line`'s words with their times: the run of `words` said within it whose words are the line's, in order. None for a
/// line whose words are not those heard (put right since, or from a site), or with no words.
fn words_of<'w>(line: &Line, words: &'w [Word]) -> Option<Vec<&'w Word>> {
    let wanted: Vec<&str> = line.text.split_whitespace().collect();
    if wanted.is_empty() {
        return None;
    }
    let near: Vec<&Word> = words
        .iter()
        .filter(|w| !w.text.trim().is_empty())
        .filter(|w| w.start + WORDS_SLACK >= line.start && w.end <= line.end + WORDS_SLACK)
        .collect();
    near.windows(wanted.len())
        .find(|run| run.iter().zip(&wanted).all(|(w, t)| w.text.trim() == *t))
        .map(<[&Word]>::to_vec)
}

/// Where a stretch of a clip is: the clip's start, and the stretch's start and end, all in the video's milliseconds.
#[derive(Clone, Copy)]
struct Moments {
    clip: u64,
    from: u64,
    to: u64,
}

/// The most words shown at once by One at a time, and the most letters.
const AT_A_TIME: usize = 3;
const AT_A_TIME_CHARS: usize = 16;

/// The events that draw a main line said as `words`, over the stretch `at`, for `now`: each its start and end in the
/// clip's time and its text (marked `\rFirst`). `colours` are the language's colour and its accent, as ASS writes them.
fn by_word(now: Now, words: &[&Word], at: Moments, colours: &(String, String)) -> Vec<(u64, u64, String)> {
    let (colour, accent) = colours;
    let clip = |t: u64| t.clamp(at.from, at.to) - at.clip;
    let text = |w: &Word| ass_text(&w.text);
    if now == Now::Filled {
        // One event: each word fills over its own time (\kf), the pauses between them (\k) ride on the space before
        // the next. Times are counted in hundredths from the event's start, each from the rounded moment before it, so
        // they never drift. A word said before the stretch begins is already filled.
        let cs = |t: u64| (t.clamp(at.from, at.to) - at.from).div_ceil(10);
        let mut line = format!("{{\\rFirst\\1c{accent}&\\2c{colour}&}}");
        let mut cursor = 0;
        for (i, word) in words.iter().enumerate() {
            let space = if i > 0 { " " } else { "" };
            let (said, done) = (cs(word.start).max(cursor), cs(word.end).max(cursor));
            if said > cursor {
                line += &format!("{{\\k{}}}{space}", said - cursor);
            } else {
                line += space;
            }
            line += &format!("{{\\kf{}}}{}", done - said, text(word));
            cursor = done;
        }
        return vec![(clip(at.from), clip(at.to), line)];
    }

    // The others are a run of events, one for each stretch in which the word being said stays the same: a word is
    // being said from its start until the next one starts (the pause after it included), and none before the first.
    let mut moments: Vec<u64> = words.iter().map(|w| w.start.clamp(at.from, at.to)).collect();
    moments.push(at.from);
    moments.push(at.to);
    moments.sort_unstable();
    moments.dedup();
    let groups = if now == Now::Alone { groups(words) } else { (0..words.len()).map(|i| (i, i + 1)).collect() };
    let mut out: Vec<(u64, u64, String)> = Vec::new();
    for pair in moments.windows(2) {
        let (from, to) = (pair[0], pair[1]);
        let saying = words.iter().rposition(|w| w.start <= from);
        // The words shown: the whole line, or for One at a time the group being said (the first before anything is).
        let (a, b) = match now {
            Now::Alone => {
                saying.and_then(|i| groups.iter().copied().find(|(a, b)| (*a..*b).contains(&i))).unwrap_or(groups[0])
            }
            _ => (0, words.len()),
        };
        let mark = match now {
            Now::Grown => format!("{{\\1c{accent}&\\fscx125\\fscy125\\t(0,160,\\fscx108\\fscy108)}}"),
            _ => format!("{{\\1c{accent}&}}"),
        };
        let shown: Vec<String> = (a..b)
            .map(|i| if Some(i) == saying { format!("{mark}{}{{\\rFirst}}", text(words[i])) } else { text(words[i]) })
            .collect();
        let line = format!("{{\\rFirst}}{}", shown.join(" "));
        match out.last_mut() {
            Some(last) if last.2 == line && last.1 == clip(from) => last.1 = clip(to),
            _ => out.push((clip(from), clip(to), line)),
        }
    }
    out
}

/// One at a time's groups of words, as ranges of `words`: up to three words and sixteen letters, a group ending after
/// a word that ends a phrase.
fn groups(words: &[&Word]) -> Vec<(usize, usize)> {
    let mut out = Vec::new();
    let mut from = 0;
    let mut chars = 0;
    for (i, word) in words.iter().enumerate() {
        let len = word.text.trim().chars().count();
        if i > from && (i - from >= AT_A_TIME || chars + 1 + len > AT_A_TIME_CHARS) {
            out.push((from, i));
            from = i;
            chars = 0;
        }
        chars += if i > from { 1 + len } else { len };
        if word.text.trim().ends_with(['.', ',', '!', '?', ';', ':']) {
            out.push((from, i + 1));
            from = i + 1;
            chars = 0;
        }
    }
    if from < words.len() {
        out.push((from, words.len()));
    }
    out
}

/// A stretch of a clip (milliseconds from its start) and what shows in it, in each of two languages.
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct Slice {
    pub(crate) start: u64,
    pub(crate) end: u64,
    pub(crate) first: Option<String>,
    pub(crate) second: Option<String>,
    /// Which of the main language's lines shows, when one does.
    pub(crate) first_line: Option<usize>,
}

/// What shows when in a clip from `start` to `end`, in `first` and `second` (which may hold no lines): a slice for
/// every stretch in which neither changes, timed from the clip's start, the same words straight on one slice.
pub(crate) fn slices(first: &[Line], second: &[Line], start: u64, end: u64) -> Vec<Slice> {
    let (one, two) = (subtitles::timeline(first), subtitles::timeline(second));
    let mut moments = vec![start, end];
    for span in one.iter().chain(&two) {
        moments.extend([span.start, span.end].into_iter().filter(|t| (start..=end).contains(t)));
    }
    moments.sort_unstable();
    moments.dedup();
    let line_at = |spans: &[Span], lines: &[Line], at: u64| {
        subtitles::line_at(spans, at).filter(|i| !lines[*i].text.trim().is_empty())
    };
    let mut out: Vec<Slice> = Vec::new();
    for pair in moments.windows(2) {
        let (from, to) = (pair[0], pair[1]);
        let (i, j) = (line_at(&one, first, from), line_at(&two, second, from));
        if i.is_none() && j.is_none() {
            continue;
        }
        let (a, b) = (i.map(|i| first[i].text.trim().to_string()), j.map(|j| second[j].text.trim().to_string()));
        match out.last_mut() {
            Some(last) if last.end == from - start && last.first_line == i && last.first == a && last.second == b => {
                last.end = to - start
            }
            _ => out.push(Slice { start: from - start, end: to - start, first: a, second: b, first_line: i }),
        }
    }
    out
}

/// One SRT for a clip in two languages: each line with the other language's under it (or `second_on_top`, over it),
/// timed from 0:00.
pub fn srt_together(first: &[Line], second: &[Line], second_on_top: bool, start: u64, end: u64) -> String {
    let lines: Vec<Line> = slices(first, second, start, end)
        .into_iter()
        .map(|slice| {
            let text = match (slice.first, slice.second) {
                (Some(a), Some(b)) if second_on_top => format!("{b}\n{a}"),
                (Some(a), Some(b)) => format!("{a}\n{b}"),
                (a, b) => a.or(b).unwrap_or_default(),
            };
            Line { start: slice.start, end: slice.end, text }
        })
        .collect();
    subtitles::to_srt(&lines)
}

/// The SRT for a clip from `start` to `end`: the lines that show in it, timed from 0:00 and cut at its edges.
pub fn srt(lines: &[Line], start: u64, end: u64) -> String {
    let inside: Vec<Line> = lines
        .iter()
        .filter(|l| l.end > start && l.start < end)
        .map(|l| Line { start: l.start.max(start) - start, end: l.end.min(end) - start, text: l.text.clone() })
        .collect();
    subtitles::to_srt(&inside)
}

// ── Names ──

/// The file a clip is saved as, next to the video: "Video title - Clip name.mp4", without what Windows forbids in a
/// name, and "(2)" after it when that is taken.
pub fn file_for(folder: &Path, video: &str, clip: &str, extension: &str) -> PathBuf {
    let clean = |s: &str| {
        let s: String = s.chars().map(|c| if "<>:\"/\\|?*".contains(c) || c.is_control() { ' ' } else { c }).collect();
        s.split_whitespace().collect::<Vec<_>>().join(" ").trim_matches(['.', ' ']).chars().take(80).collect::<String>()
    };
    let (video, clip) = (clean(video), clean(clip));
    let stem = match (video.is_empty(), clip.is_empty()) {
        (false, false) => format!("{video} - {clip}"),
        (true, false) => clip,
        (false, true) => format!("{video} - clip"),
        (true, true) => "Clip".into(),
    };
    let mut path = folder.join(format!("{stem}.{extension}"));
    let mut n = 2;
    while path.exists() {
        path = folder.join(format!("{stem} ({n}).{extension}"));
        n += 1;
    }
    path
}

// ── ffmpeg ──

/// The size and length of `video`'s picture, as `ffmpeg -i` says them.
pub async fn probe(ffmpeg: &Path, video: &Path) -> Result<Probe, ExportError> {
    let output = program(ffmpeg)
        .args(["-hide_banner", "-nostdin", "-i"])
        .arg(video)
        .stdin(Stdio::null())
        .output()
        .await
        .map_err(|_| ExportError::NoProgram)?;
    read_probe(&String::from_utf8_lossy(&output.stderr)).ok_or(ExportError::CantRead)
}

/// What `ffmpeg -i` printed about a file: its first picture's size, its length, and whether it has sound.
pub fn read_probe(said: &str) -> Option<Probe> {
    let video = said.lines().find(|l| l.trim_start().starts_with("Stream #") && l.contains(": Video:"))?;
    // "..., yuv420p(progressive), 1920x1080 [SAR 1:1 DAR 16:9], ..." : the first "WxH" of digits.
    let (width, height) = video.split([',', ' ']).find_map(|word| {
        let (w, h) = word.split_once('x')?;
        Some((w.parse::<u32>().ok().filter(|w| *w > 0)?, h.parse::<u32>().ok().filter(|h| *h > 0)?))
    })?;
    let duration = said.lines().find_map(|l| {
        let rest = l.trim_start().strip_prefix("Duration: ")?;
        let clock = rest.split(',').next()?;
        let mut parts = clock.split(':');
        let (h, m, s) = (
            parts.next()?.parse::<f64>().ok()?,
            parts.next()?.parse::<f64>().ok()?,
            parts.next()?.parse::<f64>().ok()?,
        );
        Some(((h * 3600.0 + m * 60.0 + s) * 1000.0).round() as u64)
    });
    // "..., 29.97 fps, 29.97 tbr, 30k tbn": the frames a second it says first, else the base rate it guesses.
    let pieces: Vec<&str> = video.split(',').map(str::trim).collect();
    let frame_rate = pieces
        .iter()
        .find_map(|p| p.strip_suffix(" fps"))
        .or_else(|| pieces.iter().find_map(|p| p.strip_suffix(" tbr")))
        .and_then(|n| n.trim().parse::<f64>().ok())
        .filter(|r| r.is_finite() && *r > 0.0);
    // "Audio: aac (LC) (mp4a / 0x6134706D), 48000 Hz, stereo, fltp, 128 kb/s".
    let audio = said.lines().find(|l| l.trim_start().starts_with("Stream #") && l.contains(": Audio:"));
    let heard: Vec<&str> = audio.map(|l| l.split(',').map(str::trim).collect()).unwrap_or_default();
    let at = heard.iter().position(|p| p.ends_with(" Hz"));
    let sample_rate = at.and_then(|i| heard[i].strip_suffix(" Hz")?.trim().parse::<u32>().ok()).filter(|r| *r > 0);
    let channels = at.and_then(|i| heard.get(i + 1)).and_then(|layout| channel_count(layout));
    Some(Probe { width, height, duration, frame_rate, sound: audio.is_some(), sample_rate, channels })
}

/// How many channels a layout ffmpeg names has: "mono", "stereo", "5.1(side)", "7.1", "2 channels".
fn channel_count(layout: &str) -> Option<u32> {
    let layout = layout.trim();
    match layout {
        "mono" => return Some(1),
        "stereo" => return Some(2),
        "quad" => return Some(4),
        _ => {}
    }
    if let Some(n) = layout.strip_suffix(" channels").or_else(|| layout.strip_suffix(" channel")) {
        return n.trim().parse().ok().filter(|n| *n > 0);
    }
    // "5.1" is five channels and a low-frequency one; a layout's own detail ("(side)") doesn't count.
    let bare = layout.split('(').next()?;
    let (main, low) = bare.split_once('.')?;
    Some(main.parse::<u32>().ok()? + low.parse::<u32>().ok()?).filter(|n| *n > 0)
}

/// The encoders `ffmpeg` has, to try in turn: Windows' H.264 encoder on the graphics card, then on the processor, then
/// MPEG-4.
pub async fn encoders(ffmpeg: &Path) -> Vec<Encoder> {
    let listed = program(ffmpeg).args(["-hide_banner", "-encoders"]).stdin(Stdio::null()).output().await;
    let listed = listed.map(|o| String::from_utf8_lossy(&o.stdout).into_owned()).unwrap_or_default();
    let mut encoders = Vec::new();
    if listed.contains(" h264_mf ") {
        encoders.extend([Encoder::GraphicsCard, Encoder::Windows]);
    }
    encoders.push(Encoder::Mpeg4);
    encoders
}

/// A clip to save as a video: from `start` to `end` of `video` (milliseconds), at `quality`, with `burn` drawn on it,
/// to `out`. Its ASS file is written in `work`, a folder of its own.
pub struct Job<'a> {
    pub video: &'a Path,
    pub start: u64,
    pub end: u64,
    pub quality: Quality,
    pub burn: Option<Burn<'a>>,
    /// The most bytes the video may take (a size to fit, 10 or 25 MB), when one was chosen.
    pub fit: Option<u64>,
    /// The sound's loudness evened (EBU R128, as streaming services and podcasts expect it).
    pub even_loudness: bool,
    /// A GIF instead of a video: no sound, its own frame rate and palette.
    pub gif: bool,
    pub out: &'a Path,
    pub work: &'a Path,
}

/// A size to fit worked out for a clip: its picture's size, and the bit rates of picture and sound.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Fit {
    pub width: u32,
    pub height: u32,
    pub video: u32,
    pub audio: u32,
}

/// How a clip `length` milliseconds long, its picture `width` by `height`, fits in `bytes`: the bit rate that leaves
/// room for the file's own parts, the sound's share (less for a small file), and the picture made smaller (1080, 720,
/// 540, 480, 360, 240 on its narrow side) until each pixel has enough. `None` when even the smallest wouldn't look of
/// anything: the clip is too long for that size.
pub fn fit(bytes: u64, length: u64, width: u32, height: u32, sound: bool) -> Option<Fit> {
    let seconds = (length as f64 / 1000.0).max(0.5);
    // A twentieth for the MP4's own parts and the encoder's slips.
    let total = bytes as f64 * 8.0 * 0.95 / seconds;
    let audio = match (sound, total) {
        (false, _) => 0.0,
        (true, t) if t < 1_000_000.0 => 64_000.0,
        (true, t) if t < 2_500_000.0 => 96_000.0,
        _ => 128_000.0,
    };
    let video = (total - audio).min(24_000_000.0);
    if video < 150_000.0 {
        return None;
    }
    let narrow = width.min(height);
    let sizes = [narrow, 1080, 720, 540, 480, 360, 240];
    let mut candidates = sizes.iter().copied().filter(|&n| n <= narrow).peekable();
    let mut last = (width, height);
    while let Some(n) = candidates.next() {
        let scale = n as f64 / narrow.max(1) as f64;
        let even = |v: f64| ((v / 2.0).round() as u32).max(1) * 2;
        let (w, h) = (even(width as f64 * scale), even(height as f64 * scale));
        last = (w, h);
        // Bits for each pixel of each frame, at 30 a second: under 0.05 H.264 falls apart.
        if video / (w as f64 * h as f64 * 30.0) >= 0.05 || candidates.peek().is_none() {
            break;
        }
    }
    Some(Fit { width: last.0, height: last.1, video: video as u32, audio: audio as u32 })
}

/// The longest clip made into a GIF, in milliseconds: longer, a GIF grows past what anyone would send.
pub const LONGEST_GIF: u64 = 60_000;

/// A GIF's width for `quality` (480 or 640 pixels, never wider than the video), and its frames a second.
fn gif_size(quality: Quality, width: u32, height: u32) -> (u32, u32) {
    let widest = match quality {
        Quality::P720 => 480,
        Quality::P1080 => 640,
        Quality::Original => width.min(960),
    };
    let w = widest.min(width).max(2) / 2 * 2;
    let h = ((height as f64 * w as f64 / width.max(1) as f64 / 2.0).round() as u32).max(1) * 2;
    (w, h)
}
const GIF_FRAMES: u32 = 12;

/// Inside a filter's options, a path quoted: '...' with its quotes escaped, as ffmpeg's filter syntax reads it.
fn filter_path(path: &Path) -> String {
    let path = path.to_string_lossy().replace('\\', "/");
    format!("'{}'", path.replace('\'', "'\\''").replace(':', "\\:"))
}

/// Saves `job` as a video with `ffmpeg`, trying `encoders` in turn: on to the next when one fails (the graphics card
/// may have no H.264 encoder, or Windows none at all); or as a GIF. With a size to fit, the bit rate and size come from
/// `fit`, and a file that still comes out too big is made again with less. `fonts` is a folder of fonts for libass to
/// use (the tests'; on Windows its own are found). `on_progress` hears how far, 0 to 100. The encoder used.
pub async fn export(
    ffmpeg: &Path,
    job: &Job<'_>,
    encoders: &[Encoder],
    fonts: Option<&Path>,
    stop: &watch::Receiver<bool>,
    on_progress: &mut (dyn FnMut(u32) + Send),
) -> Result<Encoder, ExportError> {
    let probe = probe(ffmpeg, job.video).await?;
    let length = job.end.saturating_sub(job.start).max(1);
    if job.gif && length > LONGEST_GIF {
        return Err(ExportError::Unfit(
            "A GIF is for a short clip: one minute at most. Trim it, or export it as a video.".into(),
        ));
    }
    let plan = match job.fit.filter(|_| !job.gif) {
        Some(bytes) => Some(fit(bytes, length, probe.width, probe.height, probe.sound).ok_or_else(|| {
            ExportError::Unfit(format!(
                "This clip is too long to fit in {} MB. Trim it, or choose a bigger size.",
                bytes / 1_000_000
            ))
        })?),
        None => None,
    };
    let (width, height) = match (&plan, job.gif) {
        (_, true) => gif_size(job.quality, probe.width, probe.height),
        (Some(plan), _) => {
            // Never larger than the quality asked for.
            let asked = size_for(job.quality, probe.width, probe.height);
            if plan.width * plan.height < asked.0 * asked.1 {
                (plan.width, plan.height)
            } else {
                asked
            }
        }
        (None, _) => size_for(job.quality, probe.width, probe.height),
    };
    tokio::fs::create_dir_all(job.work).await.map_err(|_| ExportError::CantWrite)?;
    let mut filters = Vec::new();
    if job.gif {
        filters.push(format!("fps={GIF_FRAMES}"));
    }
    filters.extend([format!("scale={width}:{height}:flags=lanczos"), "setsar=1".to_string()]);
    if let Some(burn) = &job.burn {
        let ass_path = job.work.join("clip.ass");
        tokio::fs::write(&ass_path, ass(burn, job.start, job.end, width, height))
            .await
            .map_err(|_| ExportError::CantWrite)?;
        let mut filter = format!("subtitles=filename={}", filter_path(&ass_path));
        if let Some(fonts) = fonts {
            filter += &format!(":fontsdir={}", filter_path(fonts));
        }
        filters.push(filter);
    }
    let picture = filters.join(",");
    let input = |command: &mut Command| {
        command
            .args(["-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-progress", "pipe:1", "-nostats", "-ss"])
            .arg(seconds(job.start))
            .arg("-i")
            .arg(job.video)
            .arg("-t")
            .arg(seconds(length))
            .args(["-sn", "-dn", "-map_metadata", "-1"]);
    };

    if job.gif {
        let part = job.out.with_extension("gif.part");
        let mut command = program(ffmpeg);
        input(&mut command);
        // A palette made for this clip, so its colours aren't muddy, and a fine pattern where they blend.
        command
            .arg("-filter_complex")
            .arg(format!("[0:v:0]{picture},split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4"))
            .args(["-an", "-loop", "0", "-f", "gif"])
            .arg(&part);
        let (finished, said) = run(command, &part, length, stop, on_progress).await?;
        if !finished {
            let _ = tokio::fs::remove_file(&part).await;
            return Err(ExportError::Failed(last_said(&said)));
        }
        tokio::fs::rename(&part, job.out).await.map_err(|_| ExportError::CantWrite)?;
        on_progress(100);
        return Ok(Encoder::Gif);
    }

    let picture = format!("{picture},format=yuv420p");
    let sound =
        if job.even_loudness && probe.sound { Some("loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000") } else { None };
    let part = job.out.with_extension("mp4.part");
    let mut said = String::new();
    for &encoder in encoders.iter().filter(|e| **e != Encoder::Gif) {
        // With a size to fit, at most three tries, each with less where the last came out too big.
        // A size to fit is a ceiling: with room to spare, the usual bit rate, not the whole allowance.
        let mut rate = plan.map(|p| p.video.min(bit_rate(width, height)));
        for _ in 0..3 {
            let mut command = program(ffmpeg);
            input(&mut command);
            command.args(["-map", "0:v:0", "-map", "0:a:0?", "-vf"]).arg(&picture);
            if let Some(sound) = sound {
                command.arg("-af").arg(sound);
            }
            let bits = rate.unwrap_or_else(|| bit_rate(width, height)).to_string();
            match (encoder, rate) {
                (Encoder::GraphicsCard | Encoder::Windows, _) => {
                    command.args(["-c:v", "h264_mf", "-b:v"]).arg(&bits);
                    if rate.is_some() {
                        command.args(["-rate_control", "cbr"]);
                    }
                    if encoder == Encoder::GraphicsCard {
                        command.args(["-hw_encoding", "1"]);
                    }
                }
                (_, Some(_)) => {
                    command
                        .args(["-c:v", "mpeg4", "-tag:v", "mp4v", "-b:v"])
                        .arg(&bits)
                        .arg("-maxrate")
                        .arg(&bits)
                        .arg("-bufsize")
                        .arg(&bits);
                }
                (_, None) => {
                    command.args(["-c:v", "mpeg4", "-q:v", "3", "-tag:v", "mp4v"]);
                }
            }
            let audio = plan.map_or(160_000, |p| p.audio.max(32_000)).to_string();
            command.args(["-c:a", "aac", "-b:a"]).arg(audio).args(["-movflags", "+faststart", "-f", "mp4"]).arg(&part);
            let (finished, last) = run(command, &part, length, stop, on_progress).await?;
            said = last;
            if !finished {
                let _ = tokio::fs::remove_file(&part).await;
                break;
            }
            let made = tokio::fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);
            match (job.fit, rate) {
                (Some(bytes), Some(was)) if made > bytes => {
                    // Too big: again, with as much less as it was over, and a little more.
                    rate = Some((was as f64 * bytes as f64 / made as f64 * 0.92) as u32);
                    continue;
                }
                _ => {}
            }
            tokio::fs::rename(&part, job.out).await.map_err(|_| ExportError::CantWrite)?;
            on_progress(100);
            return Ok(encoder);
        }
        if job.fit.is_some() && tokio::fs::metadata(&part).await.is_ok() {
            let _ = tokio::fs::remove_file(&part).await;
            return Err(ExportError::Unfit(
                "Chaya couldn't make this clip small enough. Trim it, or choose a bigger size.".into(),
            ));
        }
        // Give the encoder that failed a moment to let go of the graphics card before the next one.
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    Err(ExportError::Failed(last_said(&said)))
}

/// The last thing ffmpeg said, for the window.
fn last_said(said: &str) -> String {
    said.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or("").trim().chars().take(200).collect()
}

/// Runs `command` (one ffmpeg, writing `part`), telling `on_progress` how far through a clip `length` milliseconds long
/// it is. Whether it finished well, and what it said; stopped, it is killed and `part` deleted.
async fn run(
    mut command: Command,
    part: &Path,
    length: u64,
    stop: &watch::Receiver<bool>,
    on_progress: &mut (dyn FnMut(u32) + Send),
) -> Result<(bool, String), ExportError> {
    command.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped()).kill_on_drop(true);
    let mut child = command.spawn().map_err(|_| ExportError::NoProgram)?;
    let stdout = child.stdout.take().ok_or(ExportError::NoProgram)?;
    let mut stderr = child.stderr.take().ok_or(ExportError::NoProgram)?;
    let errors = tokio::spawn(async move {
        let mut text = String::new();
        let _ = stderr.read_to_string(&mut text).await;
        text
    });
    let mut lines = BufReader::new(stdout).lines();
    let mut stop = stop.clone();
    let mut shown = 0;
    // How it ended: true when ffmpeg finished well, false when not, None when stopped.
    let finished = loop {
        let line = tokio::select! {
            line = lines.next_line() => line,
            _ = stop.wait_for(|stopped| *stopped) => break None,
        };
        match line {
            Ok(Some(line)) => {
                // "out_time_us=5320000": how much of the clip is made.
                if let Some(us) = line.strip_prefix("out_time_us=").and_then(|v| v.trim().parse::<u64>().ok()) {
                    let percent = ((us / 1000) * 100 / length).min(99) as u32;
                    if percent > shown {
                        shown = percent;
                        on_progress(percent);
                    }
                }
            }
            _ => break Some(child.wait().await.map(|s| s.success()).unwrap_or(false)),
        }
    };
    let Some(finished) = finished else {
        let _ = child.kill().await;
        let _ = tokio::fs::remove_file(part).await;
        return Err(ExportError::Stopped);
    };
    Ok((finished, errors.await.unwrap_or_default()))
}

/// "12.345": milliseconds as ffmpeg takes a time.
fn seconds(ms: u64) -> String {
    format!("{}.{:03}", ms / 1000, ms % 1000)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn line(start: u64, end: u64, text: &str) -> Line {
        Line { start, end, text: text.into() }
    }

    fn walk() -> Vec<Line> {
        vec![
            line(1_000, 4_000, "This is a walk in the park."),
            line(5_000, 8_000, "The numbers count the seconds."),
            line(9_000, 12_000, "Chaya draws these lines itself,"),
            line(13_000, 16_000, "and lists them\nbeside the video."),
        ]
    }

    #[test]
    fn sizes_are_as_tall_as_asked_never_taller_and_even() {
        assert_eq!(size_for(Quality::P720, 1920, 1080), (1280, 720));
        assert_eq!(size_for(Quality::P1080, 1920, 1080), (1920, 1080));
        assert_eq!(size_for(Quality::P1080, 1280, 720), (1280, 720), "never made bigger");
        assert_eq!(size_for(Quality::Original, 1281, 721), (1282, 722), "even");
        // A phone's picture, standing: 720p is 720 wide.
        assert_eq!(size_for(Quality::P720, 1080, 1920), (720, 1280));
        assert_eq!(size_for(Quality::P720, 854, 480), (854, 480));
        assert_eq!(size_for(Quality::P720, 3840, 1606), (1722, 720));
    }

    #[test]
    fn an_ass_file_holds_what_shows_in_the_clip_timed_from_its_start() {
        let lines = walk();
        let burn = Burn {
            look: Look::Outline,
            first: Language { lines: &lines, style: Style::default(), words: &[] },
            second: None,
            second_on_top: false,
        };
        let ass = ass(&burn, 5_000, 16_000, 1280, 720);
        assert!(ass.contains("PlayResX: 1280\nPlayResY: 720"));
        assert!(ass.contains("Style: First,Segoe UI,37,&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,-1,"), "{ass}");
        let events: Vec<&str> = ass.lines().filter(|l| l.starts_with("Dialogue:")).collect();
        assert_eq!(
            events,
            [
                "Dialogue: 0,0:00:00.00,0:00:03.00,First,,0,0,0,,{\\rFirst}The numbers count the seconds.",
                "Dialogue: 0,0:00:04.00,0:00:07.00,First,,0,0,0,,{\\rFirst}Chaya draws these lines itself,",
                "Dialogue: 0,0:00:08.00,0:00:11.00,First,,0,0,0,,{\\rFirst}and lists them\\Nbeside the video.",
            ]
        );
    }

    #[test]
    fn a_clip_that_starts_mid_line_shows_the_rest_of_it() {
        let lines = walk();
        let burn = Burn {
            look: Look::Outline,
            first: Language { lines: &lines, style: Style::default(), words: &[] },
            second: None,
            second_on_top: false,
        };
        let ass = ass(&burn, 6_500, 9_500, 1920, 1080);
        let events: Vec<&str> = ass.lines().filter(|l| l.starts_with("Dialogue:")).collect();
        assert_eq!(
            events[0],
            "Dialogue: 0,0:00:00.00,0:00:01.50,First,,0,0,0,,{\\rFirst}The numbers count the seconds."
        );
        assert_eq!(
            events[1],
            "Dialogue: 0,0:00:02.50,0:00:03.00,First,,0,0,0,,{\\rFirst}Chaya draws these lines itself,"
        );
    }

    #[test]
    fn two_languages_are_one_block_either_on_top_each_in_its_style() {
        let english = walk();
        let french: Vec<Line> = english.iter().map(|l| Line { text: format!("FR {}", l.text), ..l.clone() }).collect();
        let style = Style { color: Color::Yellow, font: Font::Serif, size: Size::L };
        let mut burn = Burn {
            look: Look::Boxed,
            first: Language { lines: &english, style: Style::default(), words: &[] },
            second: Some(Language { lines: &french, style, words: &[] }),
            second_on_top: true,
        };
        let ass = ass(&burn, 1_000, 4_000, 1920, 1080);
        // Yellow (&H005CE4FF), Georgia, 1.2 times the size, on a box (BorderStyle 3).
        assert!(
            ass.contains(
                "Style: Second,Georgia,67,&H005CE4FF,&H005CE4FF,&H40000000,&H80000000,-1,0,0,0,100,100,0,0,3,"
            ),
            "{ass}"
        );
        assert!(ass.contains("{\\rSecond}FR This is a walk in the park.\\N{\\rFirst}This is a walk in the park."));
        burn.second_on_top = false;
        let ass = super::ass(&burn, 1_000, 4_000, 1920, 1080);
        assert!(ass.contains("{\\rFirst}This is a walk in the park.\\N{\\rSecond}FR This is a walk in the park."));
    }

    #[test]
    fn large_is_larger_and_text_cannot_command_libass() {
        let lines = vec![line(0, 2_000, "Not {\\b1}bold\\N, braces kept as words")];
        let large = Burn {
            look: Look::Large,
            first: Language { lines: &lines, style: Style::default(), words: &[] },
            second: None,
            second_on_top: false,
        };
        let ass = ass(&large, 0, 2_000, 1280, 720);
        assert!(ass.contains("Style: First,Segoe UI,54,"), "{ass}");
        assert!(ass.contains("{\\rFirst}Not (\u{29F5}b1)bold\u{29F5}N, braces kept as words"), "{ass}");
    }

    #[test]
    fn a_size_to_fit_sets_the_bit_rates_and_makes_the_picture_smaller_until_each_pixel_has_enough() {
        // A minute in 10 MB: 1.27 Mb/s, 96 kb/s of it sound; 1080p and 720p would be too thin, 540p is not.
        assert_eq!(
            fit(10_000_000, 60_000, 1920, 1080, true),
            Some(Fit { width: 960, height: 540, video: 1_170_666, audio: 96_000 })
        );
        // Twenty seconds in 25 MB, no sound: room enough for 720p as it is.
        assert_eq!(
            fit(25_000_000, 20_000, 1280, 720, false),
            Some(Fit { width: 1280, height: 720, video: 9_500_000, audio: 0 })
        );
        // A standing phone video keeps its shape.
        let standing = fit(10_000_000, 60_000, 1080, 1920, true).unwrap();
        assert_eq!((standing.width, standing.height), (540, 960));
        // Half an hour in 10 MB is not a video any more.
        assert_eq!(fit(10_000_000, 1_800_000, 1920, 1080, true), None);
    }

    #[test]
    fn two_languages_go_in_one_srt_line_by_line_either_on_top() {
        let english = walk();
        let french: Vec<Line> =
            english.iter().take(2).map(|l| Line { text: format!("FR {}", l.text), ..l.clone() }).collect();
        let srt = srt_together(&english, &french, false, 1_000, 12_000);
        assert_eq!(
            srt,
            "1\n00:00:00,000 --> 00:00:03,000\nThis is a walk in the park.\nFR This is a walk in the park.\n\n\
             2\n00:00:04,000 --> 00:00:07,000\nThe numbers count the seconds.\nFR The numbers count the seconds.\n\n\
             3\n00:00:08,000 --> 00:00:11,000\nChaya draws these lines itself,\n\n"
        );
        let on_top = srt_together(&english, &french, true, 1_000, 4_000);
        assert!(on_top.contains("FR This is a walk in the park.\nThis is a walk in the park."), "{on_top}");
    }

    #[test]
    fn an_srt_for_a_clip_is_timed_from_its_start_and_cut_at_its_edges() {
        let srt = srt(&walk(), 6_000, 13_500);
        assert_eq!(
            srt,
            "1\n00:00:00,000 --> 00:00:02,000\nThe numbers count the seconds.\n\n\
             2\n00:00:03,000 --> 00:00:06,000\nChaya draws these lines itself,\n\n\
             3\n00:00:07,000 --> 00:00:07,500\nand lists them\nbeside the video.\n\n"
        );
    }

    #[test]
    fn a_clip_is_named_after_its_video_and_never_over_another() {
        let dir = tempfile::tempdir().unwrap();
        let first = file_for(dir.path(), "A walk: in the park?", "The \"best\" bit", "mp4");
        assert_eq!(first.file_name().unwrap(), "A walk in the park - The best bit.mp4");
        std::fs::write(&first, b"").unwrap();
        assert_eq!(
            file_for(dir.path(), "A walk: in the park?", "The \"best\" bit", "mp4").file_name().unwrap(),
            "A walk in the park - The best bit (2).mp4"
        );
        assert_eq!(file_for(dir.path(), "", "", "srt").file_name().unwrap(), "Clip.srt");
    }

    #[test]
    fn what_ffmpeg_says_of_a_file_is_read() {
        let said = "Input #0, mov,mp4,m4a,3gp,3g2,mj2, from 'a.mp4':\n  Duration: 00:01:02.50, start: 0.000000, bitrate: 120 kb/s\n  \
                    Stream #0:0[0x1](und): Video: h264 (Constrained Baseline) (avc1 / 0x31637661), yuv420p(progressive), 320x180 [SAR 1:1 DAR 16:9], 14 kb/s, 10 fps\n  \
                    Stream #0:1[0x2](und): Audio: aac (LC) (mp4a / 0x6134706D), 22050 Hz, mono, fltp, 16 kb/s (default)\n";
        assert_eq!(
            read_probe(said),
            Some(Probe {
                width: 320,
                height: 180,
                duration: Some(62_500),
                frame_rate: Some(10.0),
                sound: true,
                sample_rate: Some(22_050),
                channels: Some(1),
            })
        );
        assert_eq!(read_probe("Stream #0:0: Audio: aac"), None, "no picture");
    }

    fn word(text: &str, start: u64, end: u64) -> Word {
        Word { text: format!(" {text}"), start, end, p: 0.9 }
    }

    /// "We walked to the park", as whisper.cpp heard it, and the line Chaya wrote for it (shown a little early and kept
    /// a little late); then a line put right since, whose words are not those heard.
    fn heard() -> (Vec<Line>, Vec<Word>) {
        let lines = vec![line(800, 2_900, "We walked to the park"), line(4_000, 5_000, "Something else")];
        let words = vec![
            word("We", 1_000, 1_200),
            word("walked", 1_250, 1_600),
            word("to", 1_700, 1_800),
            word("the", 1_850, 1_950),
            word("park", 2_000, 2_600),
            word("Some", 4_100, 4_400),
            word("thing", 4_400, 4_800),
        ];
        (lines, words)
    }

    fn by_word_ass(look: Look, words: &[Word], lines: &[Line], start: u64, end: u64) -> String {
        let burn = Burn {
            look,
            first: Language { lines, style: Style::default(), words },
            second: None,
            second_on_top: false,
        };
        ass(&burn, start, end, 1280, 720)
    }

    fn dialogue(ass: &str) -> Vec<&str> {
        ass.lines().filter(|l| l.starts_with("Dialogue:")).collect()
    }

    #[test]
    fn a_line_follows_its_words_only_when_they_are_the_words_heard() {
        let (lines, words) = heard();
        let found = words_of(&lines[0], &words).unwrap();
        assert_eq!(found.iter().map(|w| w.text.trim()).collect::<Vec<_>>(), ["We", "walked", "to", "the", "park"]);
        // Put right since: "Something else" was heard as "Some thing".
        assert_eq!(words_of(&lines[1], &words), None);
        // A site's subtitles: no words at all.
        assert_eq!(words_of(&lines[0], &[]), None);
        // The same words said a minute later are not this line's.
        let later: Vec<Word> =
            words.iter().map(|w| Word { start: w.start + 60_000, end: w.end + 60_000, ..w.clone() }).collect();
        assert_eq!(words_of(&lines[0], &later), None);
    }

    #[test]
    fn highlight_colours_the_word_being_said_and_draws_a_line_put_right_whole() {
        let (lines, words) = heard();
        let ass = by_word_ass(Look::Highlight, &words, &lines, 0, 6_000);
        let events = dialogue(&ass);
        let yellow = "\\1c&H005CE4FF&";
        // Before the first word, the line with nothing lit; then one event for each word, lit until the next starts.
        assert_eq!(events[0], "Dialogue: 0,0:00:00.80,0:00:01.00,First,,0,0,0,,{\\rFirst}We walked to the park");
        assert_eq!(
            events[1],
            format!("Dialogue: 0,0:00:01.00,0:00:01.25,First,,0,0,0,,{{\\rFirst}}{{{yellow}}}We{{\\rFirst}} walked to the park")
        );
        assert_eq!(
            events[5],
            format!("Dialogue: 0,0:00:02.00,0:00:02.90,First,,0,0,0,,{{\\rFirst}}We walked to the {{{yellow}}}park{{\\rFirst}}")
        );
        assert_eq!(events.iter().filter(|e| e.contains(yellow)).count(), 5);
        assert_eq!(events[6], "Dialogue: 0,0:00:04.00,0:00:05.00,First,,0,0,0,,{\\rFirst}Something else");
        assert_eq!(events.len(), 7);
    }

    #[test]
    fn karaoke_fills_each_word_over_its_own_time_and_the_times_add_up() {
        let (lines, words) = heard();
        let ass = by_word_ass(Look::Karaoke, &words, &lines, 0, 6_000);
        let events = dialogue(&ass);
        assert_eq!(
            events[0],
            "Dialogue: 0,0:00:00.80,0:00:02.90,First,,0,0,0,,{\\rFirst\\1c&H005CE4FF&\\2c&H00FFFFFF&}\
             {\\k20}{\\kf20}We{\\k5} {\\kf35}walked{\\k10} {\\kf10}to{\\k5} {\\kf10}the{\\k5} {\\kf60}park"
        );
        // Every hundredth from the line's start to its last word's end is counted once.
        let total: u64 = events[0]
            .split("{\\k")
            .skip(1)
            .map(|part| part.trim_start_matches('f').split('}').next().unwrap().parse::<u64>().unwrap())
            .sum();
        assert_eq!(total, (2_600 - 800) / 10);
        // A clip that starts as "walked" is being said: "We" is already filled, "walked" fills over what is left.
        let ass = by_word_ass(Look::Karaoke, &words, &lines, 1_500, 6_000);
        assert!(dialogue(&ass)[0].contains("{\\kf0}We {\\kf10}walked{\\k10} {\\kf10}to"), "{ass}");
    }

    #[test]
    fn pop_grows_the_word_being_said_and_it_settles() {
        let (lines, words) = heard();
        let ass = by_word_ass(Look::Pop, &words, &lines, 0, 6_000);
        let events = dialogue(&ass);
        assert!(events[2].ends_with("{\\rFirst}We {\\1c&H005CE4FF&\\fscx125\\fscy125\\t(0,160,\\fscx108\\fscy108)}walked{\\rFirst} to the park"), "{}", events[2]);
        // Its letters are a little larger than Highlight's.
        let size = |ass: &str| {
            ass.lines()
                .find(|l| l.starts_with("Style: First"))
                .unwrap()
                .split(',')
                .nth(2)
                .unwrap()
                .parse::<u32>()
                .unwrap()
        };
        assert!(size(&ass) > size(&by_word_ass(Look::Highlight, &words, &lines, 0, 6_000)));
    }

    #[test]
    fn one_at_a_time_shows_a_few_words_large_and_raised() {
        let (lines, words) = heard();
        let ass = by_word_ass(Look::OneAtATime, &words, &lines, 0, 6_000);
        // Each event's start and the words it shows, tags taken off: "We walked to" until "the" is said, then "the park";
        // the word being said lit.
        let shown: Vec<(String, String)> = dialogue(&ass)
            .iter()
            .take(6)
            .map(|e| {
                let fields: Vec<&str> = e.splitn(10, ',').collect();
                let mut plain = String::new();
                let mut inside = false;
                for c in fields[9].chars() {
                    match c {
                        '{' => inside = true,
                        '}' => inside = false,
                        c if !inside => plain.push(c),
                        _ => {}
                    }
                }
                (fields[1].to_string(), plain)
            })
            .collect();
        let at = |t: &str, w: &str| (t.to_string(), w.to_string());
        assert_eq!(
            shown,
            [
                at("0:00:00.80", "We walked to"),
                at("0:00:01.00", "We walked to"),
                at("0:00:01.25", "We walked to"),
                at("0:00:01.70", "We walked to"),
                at("0:00:01.85", "the park"),
                at("0:00:02.00", "the park"),
            ]
        );
        assert!(dialogue(&ass)[2].contains("We {\\1c&H005CE4FF&}walked{\\rFirst} to"));
        let style = ass.lines().find(|l| l.starts_with("Style: First")).unwrap();
        let fields: Vec<&str> = style.split(',').collect();
        // Larger than Large, and in the lower third: MarginV 16 % of 720.
        assert!(fields[2].parse::<u32>().unwrap() > 54, "{style}");
        assert_eq!(fields[21], "115");
    }

    #[test]
    fn one_at_a_time_groups_end_at_a_phrase_and_stay_short() {
        let words: Vec<Word> = ["Well,", "this", "is", "extraordinarily", "good", "news", "today."]
            .iter()
            .enumerate()
            .map(|(i, t)| word(t, i as u64 * 300, i as u64 * 300 + 250))
            .collect();
        let refs: Vec<&Word> = words.iter().collect();
        // "Well," ends a phrase; "extraordinarily" won't fit with what's before it; three words at most.
        assert_eq!(groups(&refs), [(0, 1), (1, 3), (3, 4), (4, 7)]);
    }

    #[test]
    fn with_two_languages_the_second_stays_a_plain_line_with_the_words() {
        let (lines, words) = heard();
        let french = [line(800, 2_900, "Nous sommes allés au parc")];
        let burn = Burn {
            look: Look::Highlight,
            first: Language { lines: &lines, style: Style::default(), words: &words },
            second: Some(Language { lines: &french, style: Style::default(), words: &[] }),
            second_on_top: true,
        };
        let ass = ass(&burn, 0, 3_000, 1280, 720);
        let events = dialogue(&ass);
        assert!(events.iter().all(|e| e.contains(",,{\\rSecond}Nous sommes allés au parc\\N{\\rFirst}")), "{ass}");
        assert!(events[1].contains("{\\1c&H005CE4FF&}We{\\rFirst}"));
    }

    #[test]
    fn a_word_look_without_words_draws_lines_whole_and_the_old_looks_never_follow_words() {
        let (lines, words) = heard();
        for look in [Look::Highlight, Look::Karaoke, Look::Pop, Look::OneAtATime] {
            assert!(look.by_word());
            let ass = by_word_ass(look, &[], &lines, 0, 6_000);
            assert_eq!(
                dialogue(&ass)[0],
                "Dialogue: 0,0:00:00.80,0:00:02.90,First,,0,0,0,,{\\rFirst}We walked to the park",
                "{look:?}"
            );
        }
        for look in [Look::Outline, Look::Boxed, Look::Large] {
            assert!(!look.by_word());
            assert_eq!(dialogue(&by_word_ass(look, &words, &lines, 0, 6_000)).len(), 2, "{look:?}");
        }
    }

    #[test]
    fn the_accent_stands_out_from_every_colour() {
        assert_eq!(Color::White.accent(), Color::Yellow);
        for color in [Color::Yellow, Color::Cyan, Color::Green, Color::Pink] {
            assert_eq!(color.accent(), Color::White);
        }
    }

    /// The colours a GIF holds: its global table and its first image's own, read from the header (uncompressed).
    fn gif_colours(gif: &[u8]) -> Vec<[u8; 3]> {
        let table = |at: usize, packed: u8| -> Vec<[u8; 3]> {
            let n = 2usize << (packed & 7);
            (0..n).map(|k| [gif[at + 3 * k], gif[at + 3 * k + 1], gif[at + 3 * k + 2]]).collect()
        };
        assert_eq!(&gif[..3], b"GIF", "not a GIF");
        let mut colours = Vec::new();
        let mut at = 13;
        if gif[10] & 0x80 != 0 {
            colours = table(at, gif[10]);
            at += 3 * colours.len();
        }
        // Extensions (a loop count, a frame's timing) come before the image: each is sub-blocks ending at a 0.
        while gif[at] == 0x21 {
            at += 2;
            while gif[at] != 0 {
                at += gif[at] as usize + 1;
            }
            at += 1;
        }
        if gif[at] == 0x2C && gif[at + 9] & 0x80 != 0 {
            colours.extend(table(at + 10, gif[at + 9]));
        }
        colours
    }

    /// With CI's ffmpeg (`CHAYA_FFMPEG`, Chaya's own cut-down build: its subtitles filter, palette filters and GIF
    /// encoder), Highlight drawn by libass over a dark field (an ASS drawing over the whole picture, drawn first), in
    /// DejaVu Sans, as Linux has no Segoe UI and Chaya's libass looks no further than the fonts folder it is given: no
    /// accent colour among the frame's colours before the first word is said, the accent while one is.
    #[tokio::test]
    async fn libass_lights_the_word_being_said() {
        let Some(ffmpeg) = std::env::var_os("CHAYA_FFMPEG").map(PathBuf::from) else {
            eprintln!("skipped: no CHAYA_FFMPEG");
            return;
        };
        let video = Path::new(env!("CARGO_MANIFEST_DIR")).join("../app/tour/fixtures/A walk in the park.mp4");
        let fonts = Path::new("/usr/share/fonts/truetype/dejavu");
        let (lines, words) = heard();
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("words.ass");
        let burn = Burn {
            look: Look::Highlight,
            first: Language { lines: &lines, style: Style::default(), words: &words },
            second: None,
            second_on_top: false,
        };
        let header = "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n";
        let field = "Dialogue: 0,0:00:00.00,0:01:00.00,First,,0,0,0,,\
                     {\\an7\\pos(0,0)\\1c&H202020&\\bord0\\shad0\\p1}m 0 0 l 320 0 320 180 0 180{\\p0}\n";
        std::fs::write(
            &file,
            ass(&burn, 0, 3_000, 320, 180)
                .replace(header, &format!("{header}{field}"))
                // Chaya's ffmpeg finds fonts by Windows' own list there; here, only those in `fonts`, by name.
                .replace("Segoe UI", "DejaVu Sans"),
        )
        .unwrap();
        let mut filter = format!("subtitles=filename={}", filter_path(&file));
        if fonts.exists() {
            filter += &format!(":fontsdir={}", filter_path(fonts));
        }
        let accent_at = |ms: u64| {
            let (ffmpeg, video, filter, out) =
                (ffmpeg.clone(), video.clone(), filter.clone(), dir.path().join(format!("{ms}.gif")));
            async move {
                // Seeking the input keeps the video's own times (-copyts), which the subtitles are drawn by.
                let graph = format!(
                    "[0:v]{filter},split[a][b];[a]palettegen=max_colors=32:stats_mode=single[p];[b][p]paletteuse"
                );
                let done = program(&ffmpeg)
                    .args([
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-copyts",
                        "-ss",
                        &format!("{:.3}", ms as f64 / 1000.0),
                    ])
                    .arg("-i")
                    .arg(&video)
                    .args(["-filter_complex", &graph, "-frames:v", "1", "-f", "gif"])
                    .arg(&out)
                    .output()
                    .await
                    .unwrap();
                assert!(done.status.success(), "{}", String::from_utf8_lossy(&done.stderr));
                // Yellow (255, 228, 92) as the palette keeps it, give or take.
                let near = |c: &[u8; 3]| c[0].abs_diff(255) < 40 && c[1].abs_diff(228) < 40 && c[2].abs_diff(92) < 50;
                gif_colours(&std::fs::read(&out).unwrap()).iter().any(near)
            }
        };
        assert!(!accent_at(900).await, "nothing lit before the first word");
        assert!(accent_at(2_200).await, "the word being said is lit");
    }

    #[test]
    fn the_frame_rate_and_the_sound_ffmpeg_names_are_read() {
        let said = |video: &str, audio: &str| {
            format!(
                "  Duration: 00:00:10.00, start: 0.000000, bitrate: 2000 kb/s\n  \
                 Stream #0:0[0x1](und): Video: h264 (High), yuv420p(tv, bt709), 1920x1080 [SAR 1:1 DAR 16:9], {video}\n  \
                 Stream #0:1[0x2](eng): Audio: {audio}\n"
            )
        };
        let read = |video: &str, audio: &str| read_probe(&said(video, audio)).unwrap();
        let a = read(
            "5000 kb/s, 29.97 fps, 29.97 tbr, 30k tbn (default)",
            "aac (LC) (mp4a / 0x6134706D), 48000 Hz, stereo, fltp, 128 kb/s",
        );
        assert_eq!((a.frame_rate, a.sample_rate, a.channels), (Some(29.97), Some(48_000), Some(2)));
        // No "fps" (a stream with its frames at uneven times says only its base rate), and the layouts named in words.
        let b = read("25 tbr, 12800 tbn", "ac3 (ac-3 / 0x332D6361), 44100 Hz, 5.1(side), fltp, 384 kb/s");
        assert_eq!((b.frame_rate, b.sample_rate, b.channels), (Some(25.0), Some(44_100), Some(6)));
        let c = read("23.98 fps, 23.98 tbr", "pcm_s16le ([1][0][0][0] / 0x0001), 96000 Hz, 2 channels, s16, 3072 kb/s");
        assert_eq!((c.frame_rate, c.sample_rate, c.channels), (Some(23.98), Some(96_000), Some(2)));
        // A rate written as "1k tbr", or none at all, is not guessed at.
        assert_eq!(read("1k tbr, 1k tbn", "opus, 48000 Hz, 7.1, fltp").frame_rate, None);
        assert_eq!(read("1k tbr, 1k tbn", "opus, 48000 Hz, 7.1, fltp").channels, Some(8));
        // Sound it says nothing of is still sound.
        let d = read_probe("Stream #0:0: Video: h264, 640x360, 30 fps\n  Stream #0:1: Audio: aac\n").unwrap();
        assert_eq!((d.sound, d.sample_rate, d.channels), (true, None, None));
        let mute = read_probe("Stream #0:0: Video: h264, 640x360, 30 fps\n").unwrap();
        assert_eq!((mute.sound, mute.sample_rate, mute.channels, mute.frame_rate), (false, None, None, Some(30.0)));
    }

    #[test]
    fn channel_layouts_are_counted() {
        for (layout, count) in [
            ("mono", 1),
            ("stereo", 2),
            ("quad", 4),
            ("2.1", 3),
            ("5.0", 5),
            ("5.1", 6),
            ("5.1(side)", 6),
            ("7.1", 8),
            ("6 channels", 6),
        ] {
            assert_eq!(channel_count(layout), Some(count), "{layout}");
        }
        assert_eq!(channel_count("fltp"), None);
        assert_eq!(channel_count("0 channels"), None);
    }

    #[test]
    fn a_colour_is_written_for_ass_as_its_red_green_and_blue_say() {
        // The values the player's tokens have (--subtitle-*), as ASS spells them: blue, green, red.
        for (color, ass) in [
            (Color::White, "&H00FFFFFF"),
            (Color::Yellow, "&H005CE4FF"),
            (Color::Cyan, "&H00FFE37F"),
            (Color::Green, "&H008AF0A6"),
            (Color::Pink, "&H00D1A3FF"),
        ] {
            assert_eq!(color.ass(), ass);
        }
    }

    /// With CI's ffmpeg (`CHAYA_FFMPEG`), the tour's clip cut from 5 s for 11 s at 720p (it is 320 by 180, so it stays
    /// that), the subtitles drawn on, as MPEG-4 (Linux has no Windows encoder): its length, size and streams.
    #[tokio::test]
    async fn a_clip_is_cut_exactly_with_its_subtitles_drawn_on() {
        let Some(ffmpeg) = std::env::var_os("CHAYA_FFMPEG").map(PathBuf::from) else {
            eprintln!("skipped: no CHAYA_FFMPEG");
            return;
        };
        let fonts = Path::new("/usr/share/fonts/truetype/dejavu");
        let video = Path::new(env!("CARGO_MANIFEST_DIR")).join("../app/tour/fixtures/A walk in the park.mp4");
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("clip.mp4");
        let lines = walk();
        let job = Job {
            video: &video,
            start: 5_000,
            end: 16_000,
            quality: Quality::P720,
            burn: Some(Burn {
                look: Look::Boxed,
                first: Language { lines: &lines, style: Style::default(), words: &[] },
                second: None,
                second_on_top: false,
            }),
            fit: None,
            even_loudness: false,
            gif: false,
            out: &out,
            work: &dir.path().join("work"),
        };
        let (_tx, stop) = watch::channel(false);
        let mut heard = Vec::new();
        let encoders = encoders(&ffmpeg).await;
        assert_eq!(encoders.last(), Some(&Encoder::Mpeg4));
        let used =
            export(&ffmpeg, &job, &[Encoder::Mpeg4], fonts.exists().then_some(fonts), &stop, &mut |p| heard.push(p))
                .await
                .unwrap();
        assert_eq!(used, Encoder::Mpeg4);
        assert_eq!(heard.last(), Some(&100));
        let made = probe(&ffmpeg, &out).await.unwrap();
        assert_eq!((made.width, made.height, made.sound), (320, 180, true));
        // Exactly 11 s, give or take a frame of this 10-frames-a-second clip.
        let duration = made.duration.unwrap();
        assert!((10_900..=11_100).contains(&duration), "{duration}");
        assert!(!dir.path().join("clip.mp4.part").exists());
        assert!(std::fs::read_to_string(dir.path().join("work/clip.ass")).unwrap().contains("The numbers count"));

        // Stopped, nothing is left.
        let (tx, stop) = watch::channel(true);
        let gone = dir.path().join("stopped.mp4");
        let stopped = Job { out: &gone, ..job };
        assert_eq!(
            export(&ffmpeg, &stopped, &[Encoder::Mpeg4], None, &stop, &mut |_| {}).await,
            Err(ExportError::Stopped)
        );
        drop(tx);
        assert!(!gone.exists() && !gone.with_extension("mp4.part").exists());
    }

    /// With CI's ffmpeg: a GIF, a clip fitted to a size, and a clip with its loudness evened, each made.
    #[tokio::test]
    async fn a_gif_a_size_to_fit_and_even_loudness() {
        let Some(ffmpeg) = std::env::var_os("CHAYA_FFMPEG").map(PathBuf::from) else {
            eprintln!("skipped: no CHAYA_FFMPEG");
            return;
        };
        let video = Path::new(env!("CARGO_MANIFEST_DIR")).join("../app/tour/fixtures/A walk in the park.mp4");
        let dir = tempfile::tempdir().unwrap();
        let (_tx, stop) = watch::channel(false);
        let lines = walk();
        let base = |out: &'static str| Job {
            video: &video,
            start: 5_000,
            end: 11_000,
            quality: Quality::P720,
            burn: None,
            fit: None,
            even_loudness: false,
            gif: false,
            out: Box::leak(Box::new(dir.path().join(out))),
            work: Box::leak(Box::new(dir.path().join(format!("work-{out}")))),
        };

        let gif = Job {
            gif: true,
            burn: Some(Burn {
                look: Look::Outline,
                first: Language { lines: &lines, style: Style::default(), words: &[] },
                second: None,
                second_on_top: false,
            }),
            ..base("clip.gif")
        };
        assert_eq!(export(&ffmpeg, &gif, &[Encoder::Mpeg4], None, &stop, &mut |_| {}).await, Ok(Encoder::Gif));
        let bytes = std::fs::read(gif.out).unwrap();
        assert!(bytes.starts_with(b"GIF89a"), "a GIF");
        // As wide as the video (320 is less than 480), at its own shape.
        assert_eq!((u16::from_le_bytes([bytes[6], bytes[7]]), u16::from_le_bytes([bytes[8], bytes[9]])), (320, 180));
        let long = Job { gif: true, end: 5_000 + LONGEST_GIF + 1_000, ..base("long.gif") };
        assert!(matches!(
            export(&ffmpeg, &long, &[Encoder::Mpeg4], None, &stop, &mut |_| {}).await,
            Err(ExportError::Unfit(_))
        ));

        // Room to spare: no bigger than the usual bit rate makes it, not the whole 50 MB.
        let roomy = Job { fit: Some(50_000_000), ..base("roomy.mp4") };
        export(&ffmpeg, &roomy, &[Encoder::Mpeg4], None, &stop, &mut |_| {}).await.unwrap();
        let roomy = std::fs::metadata(roomy.out).unwrap().len();
        assert!(roomy < 5_000_000, "{roomy} bytes");
        let fitted = Job { fit: Some(250_000), ..base("fitted.mp4") };
        assert_eq!(export(&ffmpeg, &fitted, &[Encoder::Mpeg4], None, &stop, &mut |_| {}).await, Ok(Encoder::Mpeg4));
        let size = std::fs::metadata(fitted.out).unwrap().len();
        assert!(size <= 250_000, "{size} bytes");
        assert!(probe(&ffmpeg, fitted.out).await.unwrap().duration.is_some_and(|d| (5_800..=6_200).contains(&d)));
        let too_long = Job { fit: Some(20_000), ..base("too-long.mp4") };
        assert!(matches!(
            export(&ffmpeg, &too_long, &[Encoder::Mpeg4], None, &stop, &mut |_| {}).await,
            Err(ExportError::Unfit(_))
        ));

        let even = Job { even_loudness: true, ..base("even.mp4") };
        assert_eq!(export(&ffmpeg, &even, &[Encoder::Mpeg4], None, &stop, &mut |_| {}).await, Ok(Encoder::Mpeg4));
        assert!(probe(&ffmpeg, even.out).await.unwrap().sound);
    }

    /// With CI's ffmpeg: every encoder it has tried in turn, an encoder that fails handing on to the next. On Linux
    /// there is no h264_mf, so the first two fail as on a PC without them; on Windows, the one that worked is said.
    #[tokio::test]
    async fn an_encoder_that_fails_hands_on_to_the_next() {
        let Some(ffmpeg) = std::env::var_os("CHAYA_FFMPEG").map(PathBuf::from) else {
            eprintln!("skipped: no CHAYA_FFMPEG");
            return;
        };
        let video = Path::new(env!("CARGO_MANIFEST_DIR")).join("../app/tour/fixtures/A walk in the park.mp4");
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("clip.mp4");
        let job = Job {
            video: &video,
            start: 0,
            end: 2_000,
            quality: Quality::Original,
            burn: None,
            fit: None,
            even_loudness: false,
            gif: false,
            out: &out,
            work: dir.path(),
        };
        let (_tx, stop) = watch::channel(false);
        let has = encoders(&ffmpeg).await;
        let used =
            export(&ffmpeg, &job, &[Encoder::GraphicsCard, Encoder::Windows, Encoder::Mpeg4], None, &stop, &mut |_| {})
                .await
                .unwrap();
        eprintln!("this ffmpeg has {has:?}; the clip was made with {used:?}");
        if has == [Encoder::Mpeg4] {
            assert_eq!(used, Encoder::Mpeg4);
            let none = export(&ffmpeg, &job, &[Encoder::Windows], None, &stop, &mut |_| {}).await;
            assert!(matches!(none, Err(ExportError::Failed(said)) if !said.is_empty()));
        }
        assert!(probe(&ffmpeg, &out).await.unwrap().duration.is_some_and(|d| (1_800..=2_200).contains(&d)));
    }
}
