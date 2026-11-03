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

    // The moments anything changes, in either language, inside the clip; between each two, what shows.
    let first = subtitles::timeline(burn.first.lines);
    let second = burn.second.as_ref().map(|s| subtitles::timeline(s.lines)).unwrap_or_default();
    let mut moments = vec![start, end];
    for span in first.iter().chain(&second) {
        moments.extend([span.start, span.end].into_iter().filter(|t| (start..=end).contains(t)));
    }
    moments.sort_unstable();
    moments.dedup();
    let text_at = |spans: &[Span], lines: &[Line], at: u64| {
        subtitles::line_at(spans, at).map(|i| ass_text(&lines[i].text)).filter(|t| !t.is_empty())
    };
    let mut events: Vec<(u64, u64, String)> = Vec::new();
    for pair in moments.windows(2) {
        let (from, to) = (pair[0], pair[1]);
        let one = text_at(&first, burn.first.lines, from);
        let two = burn.second.as_ref().and_then(|s| text_at(&second, s.lines, from));
        let text = match (one, two) {
            (None, None) => continue,
            (Some(one), None) => format!("{{\\rFirst}}{one}"),
            (None, Some(two)) => format!("{{\\rSecond}}{two}"),
            (Some(one), Some(two)) if burn.second_on_top => format!("{{\\rSecond}}{two}\\N{{\\rFirst}}{one}"),
            (Some(one), Some(two)) => format!("{{\\rFirst}}{one}\\N{{\\rSecond}}{two}"),
        };
        // The same words straight on are one event, not a flicker between two.
        match events.last_mut() {
            Some(last) if last.1 == from && last.2 == text => last.1 = to,
            _ => events.push((from, to, text)),
        }
    }
    for (from, to, text) in events {
        out += &format!("Dialogue: 0,{},{},First,,0,0,0,,{text}\n", ass_time(from - start), ass_time(to - start));
    }
    out
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
    pub out: &'a Path,
    pub work: &'a Path,
}

/// Inside a filter's options, a path quoted: '...' with its quotes escaped, as ffmpeg's filter syntax reads it.
fn filter_path(path: &Path) -> String {
    let path = path.to_string_lossy().replace('\\', "/");
    format!("'{}'", path.replace('\'', "'\\''").replace(':', "\\:"))
}

/// Saves `job` as a video with `ffmpeg`, trying `encoders` in turn: on to the next when one fails (the graphics card
/// may have no H.264 encoder, or Windows none at all). `fonts` is a folder of fonts for libass to use (the tests'; on
/// Windows its own are found). `on_progress` hears how far, 0 to 100. The encoder used.
pub async fn export(
    ffmpeg: &Path,
    job: &Job<'_>,
    encoders: &[Encoder],
    fonts: Option<&Path>,
    stop: &watch::Receiver<bool>,
    on_progress: &mut (dyn FnMut(u32) + Send),
) -> Result<Encoder, ExportError> {
    let probe = probe(ffmpeg, job.video).await?;
    let (width, height) = size_for(job.quality, probe.width, probe.height);
    let length = job.end.saturating_sub(job.start).max(1);
    tokio::fs::create_dir_all(job.work).await.map_err(|_| ExportError::CantWrite)?;
    let mut filters = vec![format!("scale={width}:{height}:flags=lanczos"), "setsar=1".to_string()];
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
    filters.push("format=yuv420p".into());
    let part = job.out.with_extension("mp4.part");
    let mut said = String::new();
    for &encoder in encoders {
        let mut command = Command::new(ffmpeg);
        command
            .args(["-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-ss"])
            .arg(seconds(job.start))
            .arg("-i")
            .arg(job.video)
            .arg("-t")
            .arg(seconds(length))
            .args(["-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-map_metadata", "-1"])
            .arg("-vf")
            .arg(filters.join(","));
        match encoder {
            Encoder::GraphicsCard | Encoder::Windows => {
                command.args(["-c:v", "h264_mf", "-b:v"]).arg(bit_rate(width, height).to_string());
                if encoder == Encoder::GraphicsCard {
                    command.args(["-hw_encoding", "1"]);
                }
            }
            Encoder::Mpeg4 => {
                command.args(["-c:v", "mpeg4", "-q:v", "3", "-tag:v", "mp4v"]);
            }
        }
        command
            .args([
                "-c:a",
                "aac",
                "-b:a",
                "160k",
                "-movflags",
                "+faststart",
                "-f",
                "mp4",
                "-progress",
                "pipe:1",
                "-nostats",
            ])
            .arg(&part)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
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
            let _ = tokio::fs::remove_file(&part).await;
            return Err(ExportError::Stopped);
        };
        said = errors.await.unwrap_or_default();
        if finished {
            tokio::fs::rename(&part, job.out).await.map_err(|_| ExportError::CantWrite)?;
            on_progress(100);
            return Ok(encoder);
        }
        let _ = tokio::fs::remove_file(&part).await;
        // Give the encoder that failed a moment to let go of the graphics card before the next one.
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
    let last = said.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or("").trim().chars().take(200).collect();
    Err(ExportError::Failed(last))
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
