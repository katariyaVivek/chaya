//! A clip saved as a video of its own (step 8): cut exactly from the saved video, from its first line's time (not the
//! keyframe before it), scaled to 720p, 1080p or left as it is, with its subtitles drawn on by libass, and encoded by
//! the engine's ffmpeg as H.264 with Windows' own encoder (on the graphics card when there is one, else on the
//! processor) or, where Windows has none, as MPEG-4; the sound as AAC. Or its subtitles alone, as an SRT file timed
//! from 0:00.
//!
//! The subtitles are written as an ASS file for libass, in one of three looks (Outline, Box, Large), with one
//! language or two; each language keeps the colour, font and size the person gave it in the player. Two languages are
//! drawn as one block, line by line, so neither ever covers the other.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;

use tokio::io::{AsyncBufReadExt, AsyncReadExt, BufReader};
use tokio::process::Command;
use tokio::sync::watch;

use crate::subtitles::{self, Line, Span};

/// How big the video is made: as tall as 720 or 1080 pixels (never taller than it was), or as it is.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Quality {
    P720,
    P1080,
    Original,
}

/// How the subtitles look: white words outlined in black (as the player draws them), on a dark box, or large.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Look {
    Outline,
    Boxed,
    Large,
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
    /// As ASS writes a colour: &HAABBGGRR, opaque. The same as the player's (`--subtitle-*` in the design tokens).
    fn ass(self) -> &'static str {
        match self {
            Color::White => "&H00FFFFFF",
            Color::Yellow => "&H005CE4FF",
            Color::Cyan => "&H00FFE37F",
            Color::Green => "&H008AF0A6",
            Color::Pink => "&H00D1A3FF",
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
    fn scale(self) -> f64 {
        match self {
            Size::S => 0.8,
            Size::M => 1.0,
            Size::L => 1.2,
            Size::Xl => 1.45,
        }
    }
}

/// A language drawn on: its lines and its style.
pub struct Language<'a> {
    pub lines: &'a [Line],
    pub style: Style,
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
    pub sound: bool,
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
    let base = height.min(width) as f64 * if burn.look == Look::Large { 0.075 } else { 0.052 };
    let style = |name: &str, language: &Language<'_>| {
        let (font, bold) = language.style.font.ass();
        let size = (base * language.style.size.scale()).round();
        let (border, outline_colour, outline, shadow) = match burn.look {
            // Black around each letter, and a soft shadow, as the player draws them.
            Look::Outline | Look::Large => (1, "&H00000000", (size * 0.07).max(1.0), (size * 0.02).round()),
            // A box behind the words, three quarters dark, padded.
            Look::Boxed => (3, "&H40000000", (size * 0.22).max(2.0), 0.0),
        };
        format!(
            "Style: {name},{font},{size},{colour},{colour},{outline_colour},&H80000000,{bold},0,0,0,100,100,0,0,{border},\
             {outline:.1},{shadow},2,{margin_x},{margin_x},{margin_y},1",
            colour = language.style.color.ass(),
            bold = if bold { -1 } else { 0 },
            margin_x = (width as f64 * 0.06).round(),
            margin_y = (height as f64 * 0.055).round(),
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
    for slice in slices(burn.first.lines, second, start, end) {
        let (one, two) = (slice.first.as_deref().map(ass_text), slice.second.as_deref().map(ass_text));
        let text = match (one, two) {
            (None, None) => continue,
            (Some(one), None) => format!("{{\\rFirst}}{one}"),
            (None, Some(two)) => format!("{{\\rSecond}}{two}"),
            (Some(one), Some(two)) if burn.second_on_top => format!("{{\\rSecond}}{two}\\N{{\\rFirst}}{one}"),
            (Some(one), Some(two)) => format!("{{\\rFirst}}{one}\\N{{\\rSecond}}{two}"),
        };
        out += &format!("Dialogue: 0,{},{},First,,0,0,0,,{text}\n", ass_time(slice.start), ass_time(slice.end));
    }
    out
}

/// A stretch of a clip (milliseconds from its start) and what shows in it, in each of two languages.
#[derive(Debug, PartialEq, Eq)]
struct Slice {
    start: u64,
    end: u64,
    first: Option<String>,
    second: Option<String>,
}

/// What shows when in a clip from `start` to `end`, in `first` and `second` (which may hold no lines): a slice for
/// every stretch in which neither changes, timed from the clip's start, the same words straight on one slice.
fn slices(first: &[Line], second: &[Line], start: u64, end: u64) -> Vec<Slice> {
    let (one, two) = (subtitles::timeline(first), subtitles::timeline(second));
    let mut moments = vec![start, end];
    for span in one.iter().chain(&two) {
        moments.extend([span.start, span.end].into_iter().filter(|t| (start..=end).contains(t)));
    }
    moments.sort_unstable();
    moments.dedup();
    let text_at = |spans: &[Span], lines: &[Line], at: u64| {
        subtitles::line_at(spans, at).map(|i| lines[i].text.trim().to_string()).filter(|t| !t.is_empty())
    };
    let mut out: Vec<Slice> = Vec::new();
    for pair in moments.windows(2) {
        let (from, to) = (pair[0], pair[1]);
        let (a, b) = (text_at(&one, first, from), text_at(&two, second, from));
        if a.is_none() && b.is_none() {
            continue;
        }
        match out.last_mut() {
            Some(last) if last.end == from - start && last.first == a && last.second == b => last.end = to - start,
            _ => out.push(Slice { start: from - start, end: to - start, first: a, second: b }),
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
    let output = Command::new(ffmpeg)
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
    let sound = said.lines().any(|l| l.trim_start().starts_with("Stream #") && l.contains(": Audio:"));
    Some(Probe { width, height, duration, sound })
}

/// The encoders `ffmpeg` has, to try in turn: Windows' H.264 encoder on the graphics card, then on the processor, then
/// MPEG-4.
pub async fn encoders(ffmpeg: &Path) -> Vec<Encoder> {
    let listed = Command::new(ffmpeg).args(["-hide_banner", "-encoders"]).stdin(Stdio::null()).output().await;
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
        let mut command = Command::new(ffmpeg);
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
            let mut command = Command::new(ffmpeg);
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
            first: Language { lines: &lines, style: Style::default() },
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
            first: Language { lines: &lines, style: Style::default() },
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
            first: Language { lines: &english, style: Style::default() },
            second: Some(Language { lines: &french, style }),
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
            first: Language { lines: &lines, style: Style::default() },
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
        assert_eq!(read_probe(said), Some(Probe { width: 320, height: 180, duration: Some(62_500), sound: true }));
        assert_eq!(read_probe("Stream #0:0: Audio: aac"), None, "no picture");
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
                first: Language { lines: &lines, style: Style::default() },
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
                first: Language { lines: &lines, style: Style::default() },
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
