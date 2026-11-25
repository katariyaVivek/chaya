//! Clips for an editor (step 11 of the plan): the clips a person chose, cut from the saved video and put in order, as
//! one FCPXML file that Final Cut Pro and DaVinci Resolve open. Nothing is encoded and the video is not copied: each
//! clip is a cut of the original file, named by its `file:///` address, so the editor works from the picture and sound
//! as they were saved. The subtitles chosen come as titles over each cut (two languages in one title, as they are
//! burned in), timed inside it.
//!
//! The idea, and the shape of the file (resources, then a project whose spine holds the cuts, titles connected to the
//! cut they belong to), is OpenFilm's way of handing a timeline to an editor (MIT). The writer is Chaya's own: it has
//! one video and clips in milliseconds, nothing else.
//!
//! FCPXML counts in fractions of a second on the picture's own frame grid, so the video's frame rate is read and
//! every edge is put on a whole frame: 29.97 frames a second is 30000/1001, never a decimal. A clip is a frame long
//! at least, and ends inside the video.
//!
//! Premiere Pro does not read this file (it reads Final Cut 7's XML, "xmeml"); that is a second writer.

use std::fmt::Write as _;
use std::path::Path;

use crate::clips::Clip;
use crate::export::{self, Burn, Probe};

/// The file's version: Final Cut Pro 10.6 and later, which DaVinci Resolve reads too.
const VERSION: &str = "1.10";

/// The title Final Cut ships with, found by its own name when the file is opened, not from this PC.
const BASIC_TITLE: &str = ".../Titles.localized/Bumper:Opener.localized/Basic Title.localized/Basic Title.moti";

/// The sample rates a FCPXML asset may name.
const AUDIO_RATES: [u32; 7] = [32_000, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000];

/// Frames a second as a fraction: 30000/1001 is "29.97".
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rate {
    pub num: u32,
    pub den: u32,
}

/// The rates video really has, which a measured 23.98 or 59.94 means exactly.
const STANDARD: [Rate; 12] = [
    Rate { num: 24_000, den: 1_001 },
    Rate { num: 24, den: 1 },
    Rate { num: 25, den: 1 },
    Rate { num: 30_000, den: 1_001 },
    Rate { num: 30, den: 1 },
    Rate { num: 48_000, den: 1_001 },
    Rate { num: 48, den: 1 },
    Rate { num: 50, den: 1 },
    Rate { num: 60_000, den: 1_001 },
    Rate { num: 60, den: 1 },
    Rate { num: 120_000, den: 1_001 },
    Rate { num: 120, den: 1 },
];

impl Rate {
    fn fps(self) -> f64 {
        f64::from(self.num) / f64::from(self.den)
    }

    /// The rate nearest `measured` frames a second: a standard one when it is within half a percent of it, else the
    /// whole number nearest (a screen recording's 10 or 15); 30 when nothing is known.
    pub fn nearest(measured: Option<f64>) -> Rate {
        let Some(fps) = measured.filter(|f| f.is_finite() && *f > 0.0) else { return Rate { num: 30, den: 1 } };
        let closest = STANDARD.iter().copied().min_by(|a, b| (a.fps() - fps).abs().total_cmp(&(b.fps() - fps).abs()));
        match closest {
            Some(rate) if (rate.fps() - fps).abs() / fps < 0.005 => rate,
            _ => Rate { num: fps.round().clamp(1.0, 240.0) as u32, den: 1 },
        }
    }

    /// The whole frame nearest `ms` milliseconds in.
    fn frame_at(self, ms: u64) -> u64 {
        let (num, den) = (u128::from(self.num), u128::from(self.den));
        ((u128::from(ms) * num + den * 500) / (den * 1_000)) as u64
    }

    /// The first whole frame at or after `ms` milliseconds in: enough frames to hold that long.
    fn frames_to_hold(self, ms: u64) -> u64 {
        let (num, den) = (u128::from(self.num), u128::from(self.den));
        ((u128::from(ms) * num).div_ceil(den * 1_000)) as u64
    }

    /// `frames` frames long, as FCPXML writes a time: "0s", "2s", "1001/30000s", in lowest terms.
    fn time(self, frames: u64) -> String {
        let (n, d) = (u128::from(frames) * u128::from(self.den), u128::from(self.num));
        if n == 0 {
            return "0s".into();
        }
        let g = gcd(n, d);
        let (n, d) = (n / g, d / g);
        if d == 1 {
            format!("{n}s")
        } else {
            format!("{n}/{d}s")
        }
    }
}

fn gcd(a: u128, b: u128) -> u128 {
    if b == 0 {
        a
    } else {
        gcd(b, a % b)
    }
}

/// The video the clips are cut from.
pub struct Source<'a> {
    /// Its title, which names the event.
    pub title: &'a str,
    pub path: &'a Path,
    pub probe: Probe,
}

/// The address an editor opens `path` by: `file:///C:/Users/me/Videos/a%20video.mp4`, or `file://server/share/…` for a
/// network share. Windows' own spelling (`\\?\C:\…`, `\\?\UNC\server\share\…`, `C:\…`) is read on any system, so the
/// same path gives the same address wherever this runs.
pub fn file_url(path: &Path) -> String {
    let text = path.to_string_lossy().replace('\\', "/");
    let text = match text.strip_prefix("//?/") {
        Some(rest) => match rest.strip_prefix("UNC/") {
            Some(share) => format!("//{share}"),
            None => rest.to_string(),
        },
        None => text,
    };
    let (host, tail) = match text.strip_prefix("//") {
        Some(rest) => {
            let (host, tail) = rest.split_once('/').unwrap_or((rest, ""));
            (host.to_string(), format!("/{tail}"))
        }
        None => (String::new(), if text.starts_with('/') { text } else { format!("/{text}") }),
    };
    let bytes = tail.as_bytes();
    let mut out = format!("file://{host}");
    for (i, &b) in bytes.iter().enumerate() {
        // A drive's colon stays ("/C:/"); any other is a byte like the rest.
        let drive_colon = b == b':' && i == 2 && bytes[1].is_ascii_alphabetic();
        if b.is_ascii_alphanumeric() || b"-._~/".contains(&b) || drive_colon {
            out.push(b as char);
        } else {
            let _ = write!(out, "%{b:02X}");
        }
    }
    out
}

/// Text for an attribute: no characters XML forbids, one line, and `&`, `<`, `>` and both quotes written out.
fn attribute(text: &str) -> String {
    let mut out = String::new();
    for c in text.split_whitespace().collect::<Vec<_>>().join(" ").chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            c if is_xml_char(c) => out.push(c),
            _ => {}
        }
    }
    out
}

/// Text for an element's content: as `attribute`, but a line break stays one.
fn content(text: &str) -> String {
    text.chars()
        .filter(|c| *c == '\n' || is_xml_char(*c))
        .map(|c| match c {
            '&' => "&amp;".to_string(),
            '<' => "&lt;".to_string(),
            '>' => "&gt;".to_string(),
            c => c.to_string(),
        })
        .collect()
}

/// A character XML 1.0 may hold (tab, line breaks and everything from space up, but not the controls or the
/// surrogates' and 0xFFFE/0xFFFF gaps).
fn is_xml_char(c: char) -> bool {
    matches!(c, '\t' | '\n' | '\r' | '\u{20}'..='\u{D7FF}' | '\u{E000}'..='\u{FFFD}' | '\u{10000}'..='\u{10FFFF}')
}

/// The sample rate an asset may name, nearest `measured`: 48000 when nothing is known.
fn audio_rate(measured: Option<u32>) -> u32 {
    let Some(rate) = measured else { return 48_000 };
    AUDIO_RATES.iter().copied().min_by_key(|r| r.abs_diff(rate)).unwrap_or(48_000)
}

/// "mono", "stereo" or "surround", as a sequence says its sound.
fn layout(channels: Option<u32>) -> &'static str {
    match channels {
        Some(1) => "mono",
        Some(n) if n > 2 => "surround",
        _ => "stereo",
    }
}

/// The FCPXML file for `clips` of `source`, in the order given, one after the other; with the subtitles of `titles`
/// (its look is not used: a title is Final Cut's own, in the language's colour and size) over each.
pub fn document(source: &Source<'_>, clips: &[Clip], titles: Option<&Burn<'_>>) -> String {
    let probe = &source.probe;
    let rate = Rate::nearest(probe.frame_rate);
    let longest = clips.iter().map(|c| c.end).max().unwrap_or(0);
    let asset_frames = rate.frames_to_hold(probe.duration.unwrap_or(longest)).max(1);
    let name = source.path.file_stem().map_or_else(String::new, |s| s.to_string_lossy().into_owned());

    // Each clip put on the frame grid, inside the video, a frame long at least.
    struct Cut<'c> {
        clip: &'c Clip,
        start: u64,
        frames: u64,
        offset: u64,
    }
    let mut cuts = Vec::new();
    let mut offset = 0;
    for clip in clips {
        let start = rate.frame_at(clip.start).min(asset_frames - 1);
        let end = rate.frame_at(clip.end).clamp(start + 1, asset_frames);
        cuts.push(Cut { clip, start, frames: end - start, offset });
        offset += end - start;
    }

    let mut out = String::new();
    let _ =
        writeln!(out, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE fcpxml>\n<fcpxml version=\"{VERSION}\">");
    out.push_str("  <resources>\n");
    let _ = writeln!(
        out,
        "    <format id=\"r1\" frameDuration=\"{}\" width=\"{}\" height=\"{}\"/>",
        rate.time(1),
        probe.width,
        probe.height
    );
    let sound = if probe.sound {
        format!(
            " hasAudio=\"1\" audioSources=\"1\" audioChannels=\"{}\" audioRate=\"{}\"",
            probe.channels.unwrap_or(2),
            audio_rate(probe.sample_rate)
        )
    } else {
        String::new()
    };
    let _ = writeln!(
        out,
        "    <asset id=\"r2\" name=\"{}\" start=\"0s\" duration=\"{}\" hasVideo=\"1\" format=\"r1\" videoSources=\"1\"{sound}>",
        attribute(&name),
        rate.time(asset_frames)
    );
    let _ = writeln!(out, "      <media-rep kind=\"original-media\" src=\"{}\"/>", file_url(source.path));
    out.push_str("    </asset>\n");
    if titles.is_some() {
        let _ = writeln!(out, "    <effect id=\"r3\" name=\"Basic Title\" uid=\"{BASIC_TITLE}\"/>");
    }
    out.push_str("  </resources>\n");

    let project = match clips {
        [only] => only.name.as_str(),
        _ => "Clips",
    };
    let total: u64 = cuts.iter().map(|c| c.frames).sum();
    let _ = writeln!(
        out,
        "  <library>\n    <event name=\"{}\">\n      <project name=\"{}\">",
        attribute(source.title),
        attribute(project)
    );
    let _ = writeln!(
        out,
        "        <sequence format=\"r1\" duration=\"{}\" tcStart=\"0s\" tcFormat=\"NDF\" audioLayout=\"{}\" audioRate=\"48k\">\n          <spine>",
        rate.time(total),
        layout(probe.sound.then_some(probe.channels).flatten())
    );

    let mut styles = 0;
    for cut in &cuts {
        let _ = writeln!(
            out,
            "            <asset-clip ref=\"r2\" name=\"{}\" offset=\"{}\" start=\"{}\" duration=\"{}\" format=\"r1\" tcFormat=\"NDF\">",
            attribute(&cut.clip.name),
            rate.time(cut.offset),
            rate.time(cut.start),
            rate.time(cut.frames)
        );
        if let Some(burn) = titles {
            let second = burn.second.as_ref().map(|s| s.lines).unwrap_or(&[]);
            for slice in export::slices(burn.first.lines, second, cut.clip.start, cut.clip.end) {
                // The title lies inside the cut, on whole frames of it; one shorter than a frame is not drawn.
                let from = rate.frame_at(slice.start).min(cut.frames);
                let to = rate.frame_at(slice.end).min(cut.frames);
                if to <= from {
                    continue;
                }
                let first = (slice.first.as_deref(), burn.first.style);
                let other = burn.second.as_ref().map(|s| (slice.second.as_deref(), s.style));
                let mut runs: Vec<(&str, export::Style)> = Vec::new();
                match (first.0, other.and_then(|o| o.0.map(|t| (t, o.1)))) {
                    (Some(a), Some(b)) if burn.second_on_top => runs.extend([b, (a, first.1)]),
                    (Some(a), Some(b)) => runs.extend([(a, first.1), b]),
                    (Some(a), None) => runs.push((a, first.1)),
                    (None, Some(b)) => runs.push(b),
                    (None, None) => continue,
                }
                let label =
                    runs[0].0.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(60).collect::<String>();
                let _ = writeln!(
                    out,
                    "              <title ref=\"r3\" lane=\"1\" offset=\"{}\" name=\"{}\" start=\"0s\" duration=\"{}\">",
                    // A connected clip's offset is counted in its parent's own time, from where the parent's cut starts.
                    rate.time(cut.start + from),
                    attribute(&label),
                    rate.time(to - from)
                );
                out.push_str("                <text>");
                let mut defs = String::new();
                let scale = f64::from(probe.width.min(probe.height)) / f64::from(probe.height.max(1));
                for (i, (text, style)) in runs.iter().enumerate() {
                    styles += 1;
                    let [r, g, b] = style.color.rgb();
                    let _ = write!(
                        out,
                        "<text-style ref=\"ts{styles}\">{}{}</text-style>",
                        if i > 0 { "\n" } else { "" },
                        content(text)
                    );
                    let _ = writeln!(
                        defs,
                        "                <text-style-def id=\"ts{styles}\"><text-style font=\"Helvetica\" fontSize=\"{}\" \
                         fontColor=\"{} {} {} 1\" strokeColor=\"0 0 0 1\" strokeWidth=\"-2\" alignment=\"center\"/></text-style-def>",
                        number(SIZE * style.size.scale() * scale),
                        number(f64::from(r) / 255.0),
                        number(f64::from(g) / 255.0),
                        number(f64::from(b) / 255.0),
                    );
                }
                out.push_str("</text>\n");
                out.push_str(&defs);
                out.push_str("                <adjust-transform position=\"0 -40\"/>\n              </title>\n");
            }
        }
        out.push_str("            </asset-clip>\n");
    }
    out.push_str("          </spine>\n        </sequence>\n      </project>\n    </event>\n  </library>\n</fcpxml>\n");
    out
}

/// The size of a title's letters at medium, in Final Cut's points on a 1080-high picture: about what the burned-in
/// subtitles measure there.
const SIZE: f64 = 56.0;

/// A number as short as it can be, to four decimals.
fn number(n: f64) -> String {
    let text = format!("{n:.4}");
    text.trim_end_matches('0').trim_end_matches('.').to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::export::{Color, Font, Language, Look, Size, Style};
    use crate::subtitles::Line;
    use std::path::PathBuf;

    fn probe(fps: Option<f64>) -> Probe {
        Probe {
            width: 1920,
            height: 1080,
            duration: Some(60_000),
            frame_rate: fps,
            sound: true,
            sample_rate: Some(48_000),
            channels: Some(2),
        }
    }

    fn clip(id: u32, name: &str, start: u64, end: u64) -> Clip {
        Clip { id, name: name.into(), start, end }
    }

    fn source<'a>(path: &'a Path, probe: Probe) -> Source<'a> {
        Source { title: "A walk in the park", path, probe }
    }

    fn parse(xml: &str) -> roxmltree::Document<'_> {
        roxmltree::Document::parse_with_options(
            xml,
            roxmltree::ParsingOptions { allow_dtd: true, ..Default::default() },
        )
        .unwrap_or_else(|e| panic!("not XML: {e}\n{xml}"))
    }

    fn element<'a, 'b>(doc: &'a roxmltree::Document<'b>, name: &str) -> roxmltree::Node<'a, 'b> {
        doc.descendants().find(|n| n.has_tag_name(name)).unwrap_or_else(|| panic!("no <{name}>"))
    }

    /// A time attribute as frames on `rate`'s grid; panics when it is not exactly on it.
    fn on_grid(time: &str, rate: Rate) -> u64 {
        let seconds = time.strip_suffix('s').unwrap_or_else(|| panic!("{time} has no s"));
        let (n, d) = seconds.split_once('/').map_or((seconds, "1"), |(n, d)| (n, d));
        let (n, d): (u128, u128) = (n.parse().unwrap(), d.parse().unwrap());
        // frames = n/d * num/den
        let top = n * u128::from(rate.num);
        let bottom = d * u128::from(rate.den);
        assert_eq!(top % bottom, 0, "{time} is between frames at {rate:?}");
        (top / bottom) as u64
    }

    #[test]
    fn one_clip_is_one_cut_of_the_video_on_the_frame_grid() {
        let path = PathBuf::from("/home/me/Videos/A walk in the park.mp4");
        let xml = document(&source(&path, probe(Some(30.0))), &[clip(1, "The turn", 10_000, 25_000)], None);
        let doc = parse(&xml);
        let root = doc.root_element();
        assert_eq!(root.tag_name().name(), "fcpxml");
        assert_eq!(root.attribute("version"), Some("1.10"));
        let format = element(&doc, "format");
        assert_eq!(format.attribute("frameDuration"), Some("1/30s"));
        assert_eq!((format.attribute("width"), format.attribute("height")), (Some("1920"), Some("1080")));
        let asset = element(&doc, "asset");
        assert_eq!(asset.attribute("duration"), Some("60s"));
        assert_eq!(asset.attribute("hasAudio"), Some("1"));
        assert_eq!(asset.attribute("audioChannels"), Some("2"));
        assert_eq!(
            element(&doc, "media-rep").attribute("src"),
            Some("file:///home/me/Videos/A%20walk%20in%20the%20park.mp4")
        );
        let cut = element(&doc, "asset-clip");
        assert_eq!(cut.attribute("name"), Some("The turn"));
        assert_eq!(
            (cut.attribute("offset"), cut.attribute("start"), cut.attribute("duration")),
            (Some("0s"), Some("10s"), Some("15s"))
        );
        assert_eq!(element(&doc, "sequence").attribute("duration"), Some("15s"));
        assert_eq!(element(&doc, "project").attribute("name"), Some("The turn"));
        assert_eq!(element(&doc, "event").attribute("name"), Some("A walk in the park"));
        assert!(!xml.contains("<title"), "no subtitles were asked for");
    }

    #[test]
    fn clips_follow_each_other_and_every_time_is_on_the_frame_grid() {
        let path = PathBuf::from("/v/a.mp4");
        for (fps, frame_duration) in [
            (23.976, "1001/24000s"),
            (23.98, "1001/24000s"),
            (24.0, "1/24s"),
            (25.0, "1/25s"),
            (29.97, "1001/30000s"),
            (30.0, "1/30s"),
            (59.94, "1001/60000s"),
            (60.0, "1/60s"),
            (10.0, "1/10s"),
        ] {
            let clips = [clip(1, "A", 1_234, 9_876), clip(2, "B", 20_017, 41_999), clip(3, "C", 59_990, 60_000)];
            let xml = document(&source(&path, probe(Some(fps))), &clips, None);
            let doc = parse(&xml);
            let rate = Rate::nearest(Some(fps));
            assert_eq!(element(&doc, "format").attribute("frameDuration"), Some(frame_duration), "{fps}");
            let mut offset = 0;
            let mut cuts = 0;
            for cut in doc.descendants().filter(|n| n.has_tag_name("asset-clip")) {
                let frames = on_grid(cut.attribute("duration").unwrap(), rate);
                assert!(frames >= 1, "{fps}: a clip is a frame long at least");
                assert_eq!(on_grid(cut.attribute("offset").unwrap(), rate), offset, "{fps}: back to back");
                let start = on_grid(cut.attribute("start").unwrap(), rate);
                assert!(
                    start + frames <= on_grid(element(&doc, "asset").attribute("duration").unwrap(), rate),
                    "{fps}"
                );
                offset += frames;
                cuts += 1;
            }
            assert_eq!(cuts, 3);
            assert_eq!(
                on_grid(element(&doc, "sequence").attribute("duration").unwrap(), rate),
                offset,
                "{fps}: the spine's length"
            );
        }
    }

    #[test]
    fn a_frame_rate_of_29_97_is_30000_over_1001_exactly() {
        let path = PathBuf::from("/v/a.mp4");
        let xml = document(&source(&path, probe(Some(29.97))), &[clip(1, "Ten", 10_000, 20_000)], None);
        let doc = parse(&xml);
        // 10 s is 299.7 frames, so 10 s is frame 300 (10.01 s) and 20 s is frame 599: each edge goes to its nearest
        // frame, and the clip is the 299 frames between them, a hair under 10 s.
        let cut = element(&doc, "asset-clip");
        assert_eq!(cut.attribute("start"), Some("1001/100s"));
        assert_eq!(cut.attribute("duration"), Some("299299/30000s"));
    }

    #[test]
    fn rates_are_standard_when_close_whole_when_not_and_thirty_when_unknown() {
        assert_eq!(Rate::nearest(Some(23.976)), Rate { num: 24_000, den: 1_001 });
        assert_eq!(Rate::nearest(Some(29.970_029_97)), Rate { num: 30_000, den: 1_001 });
        assert_eq!(Rate::nearest(Some(59.94)), Rate { num: 60_000, den: 1_001 });
        assert_eq!(Rate::nearest(Some(24.0)), Rate { num: 24, den: 1 });
        assert_eq!(Rate::nearest(Some(15.0)), Rate { num: 15, den: 1 });
        assert_eq!(Rate::nearest(Some(12.4)), Rate { num: 12, den: 1 });
        assert_eq!(Rate::nearest(None), Rate { num: 30, den: 1 });
        assert_eq!(Rate::nearest(Some(0.0)), Rate { num: 30, den: 1 });
        assert_eq!(Rate::nearest(Some(f64::NAN)), Rate { num: 30, den: 1 });
    }

    #[test]
    fn a_clip_past_the_end_or_shorter_than_a_frame_is_kept_inside_the_video() {
        let path = PathBuf::from("/v/a.mp4");
        let clips =
            [clip(1, "Past", 59_000, 90_000), clip(2, "Sliver", 10_000, 10_001), clip(3, "Beyond", 99_000, 99_500)];
        let xml = document(&source(&path, probe(Some(30.0))), &clips, None);
        let doc = parse(&xml);
        let cuts: Vec<_> = doc
            .descendants()
            .filter(|n| n.has_tag_name("asset-clip"))
            .map(|n| (n.attribute("start").unwrap(), n.attribute("duration").unwrap()))
            .collect();
        // 59 s for the 1 s there is; a clip a frame long; and the last frame for one wholly past the end.
        assert_eq!(cuts, [("59s", "1s"), ("10s", "1/30s"), ("1799/30s", "1/30s")]);
    }

    #[test]
    fn a_video_with_no_sound_or_no_length_is_still_a_project() {
        let path = PathBuf::from("/v/a.mp4");
        let mute = Probe { sound: false, sample_rate: None, channels: None, duration: None, ..probe(Some(25.0)) };
        let xml = document(&source(&path, mute), &[clip(1, "A", 2_000, 6_000)], None);
        let doc = parse(&xml);
        let asset = element(&doc, "asset");
        assert_eq!(asset.attribute("hasAudio"), None);
        // With no length told, the video is as long as its last clip reaches.
        assert_eq!(asset.attribute("duration"), Some("6s"));
        assert_eq!(element(&doc, "asset-clip").attribute("duration"), Some("4s"));
    }

    #[test]
    fn the_sound_is_named_as_final_cut_names_it() {
        assert_eq!((audio_rate(Some(48_000)), audio_rate(Some(44_100)), audio_rate(None)), (48_000, 44_100, 48_000));
        // A rate the format has no name for goes to the nearest one it has.
        assert_eq!(audio_rate(Some(22_050)), 32_000);
        assert_eq!(audio_rate(Some(47_999)), 48_000);
        assert_eq!(
            (layout(Some(1)), layout(Some(2)), layout(Some(6)), layout(None)),
            ("mono", "stereo", "surround", "stereo")
        );
        let path = PathBuf::from("/v/a.mp4");
        let odd = Probe { sample_rate: Some(22_050), channels: Some(1), ..probe(Some(30.0)) };
        let doc_text = document(&source(&path, odd), &[clip(1, "A", 0, 1_000)], None);
        let doc = parse(&doc_text);
        assert_eq!(element(&doc, "asset").attribute("audioRate"), Some("32000"));
        assert_eq!(element(&doc, "sequence").attribute("audioLayout"), Some("mono"));
    }

    #[test]
    fn paths_become_file_addresses_wherever_they_are_read() {
        assert_eq!(file_url(Path::new("/home/me/a b.mp4")), "file:///home/me/a%20b.mp4");
        assert_eq!(
            file_url(Path::new(r"C:\Users\me\Videos\Un été à Paris #1.mp4")),
            "file:///C:/Users/me/Videos/Un%20%C3%A9t%C3%A9%20%C3%A0%20Paris%20%231.mp4"
        );
        // The long spelling Windows gives a canonical path.
        assert_eq!(file_url(Path::new(r"\\?\D:\Clips\a.mp4")), "file:///D:/Clips/a.mp4");
        assert_eq!(file_url(Path::new(r"\\nas\media\My Videos\a.mp4")), "file://nas/media/My%20Videos/a.mp4");
        assert_eq!(file_url(Path::new(r"\\?\UNC\nas\media\a.mp4")), "file://nas/media/a.mp4");
        // Characters a URL treats as its own are written out, a colon beyond a drive's too.
        assert_eq!(file_url(Path::new("/v/100%/a&b=c?d:e.mp4")), "file:///v/100%25/a%26b%3Dc%3Fd%3Ae.mp4");
    }

    #[test]
    fn names_and_subtitles_with_markup_in_them_are_read_back_as_they_were() {
        let path = PathBuf::from("/v/Tom & Jerry <3.mp4");
        let line = |start, end, text: &str| Line { start, end, text: text.into() };
        let lines = [line(10_000, 14_000, "5 < 6 & \"x\" > 'y'"), line(15_000, 18_000, "tab\there\u{1}")];
        let burn = Burn {
            look: Look::Outline,
            first: Language { lines: &lines, style: Style::default() },
            second: None,
            second_on_top: false,
        };
        let name = "Tom & \"Jerry\" <3\nnext line";
        let xml = document(&source(&path, probe(Some(30.0))), &[clip(1, name, 9_000, 20_000)], Some(&burn));
        let doc = parse(&xml);
        assert_eq!(element(&doc, "asset").attribute("name"), Some("Tom & Jerry <3"));
        assert_eq!(element(&doc, "asset-clip").attribute("name"), Some("Tom & \"Jerry\" <3 next line"));
        let texts: Vec<String> =
            doc.descendants().filter(|n| n.has_tag_name("title")).map(|t| element_text(t)).collect();
        assert_eq!(texts, ["5 < 6 & \"x\" > 'y'", "tab\there"], "the control character is left out, the rest kept");
    }

    fn element_text(title: roxmltree::Node<'_, '_>) -> String {
        let text = title.children().find(|n| n.has_tag_name("text")).unwrap();
        text.descendants().filter(|n| n.is_text()).filter_map(|n| n.text()).collect()
    }

    #[test]
    fn subtitles_are_titles_inside_their_clip_in_the_clips_own_time() {
        let path = PathBuf::from("/v/a.mp4");
        let line = |start, end, text: &str| Line { start, end, text: text.into() };
        // The first line starts before the clip does, the last ends after it.
        let english = [
            line(8_000, 12_000, "Before and in"),
            line(14_000, 16_000, "Wholly in"),
            line(19_000, 30_000, "In and after"),
        ];
        let spanish = [line(14_000, 16_000, "Del todo")];
        let burn = Burn {
            look: Look::Boxed,
            first: Language { lines: &english, style: Style::default() },
            second: Some(Language {
                lines: &spanish,
                style: Style { color: Color::Yellow, font: Font::Serif, size: Size::Xl },
            }),
            second_on_top: true,
        };
        let xml = document(&source(&path, probe(Some(30.0))), &[clip(1, "Clip", 10_000, 20_000)], Some(&burn));
        let doc = parse(&xml);
        let rate = Rate::nearest(Some(30.0));
        let titles: Vec<_> = doc.descendants().filter(|n| n.has_tag_name("title")).collect();
        assert_eq!(titles.len(), 3);
        // Each title is a child of its cut, on lane 1, with the effect it names defined and its times on the grid and
        // inside the cut (10 s to 20 s of the video, counted from the cut's start).
        let cut = element(&doc, "asset-clip");
        let (cut_start, cut_frames) =
            (on_grid(cut.attribute("start").unwrap(), rate), on_grid(cut.attribute("duration").unwrap(), rate));
        let mut seen = Vec::new();
        for title in &titles {
            assert_eq!(title.parent(), Some(cut));
            assert_eq!(title.attribute("lane"), Some("1"));
            assert_eq!(title.attribute("ref"), Some("r3"));
            let at = on_grid(title.attribute("offset").unwrap(), rate);
            let frames = on_grid(title.attribute("duration").unwrap(), rate);
            assert!(at >= cut_start && at + frames <= cut_start + cut_frames, "inside the cut");
            seen.push((at - cut_start, frames));
        }
        // 10 to 12 s; 14 to 16 s (both languages); 19 to 20 s.
        assert_eq!(seen, [(0, 60), (120, 60), (270, 30)]);
        assert!(doc.descendants().any(|n| n.has_tag_name("effect") && n.attribute("id") == Some("r3")));
        // Two languages: the second on top, each in its own run and colour (yellow is 1 0.8941 0.3608).
        let two = titles[1];
        assert_eq!(element_text(two), "Del todo\nWholly in");
        let runs: Vec<_> =
            two.descendants().filter(|n| n.has_tag_name("text-style") && n.attribute("ref").is_some()).collect();
        assert_eq!(runs.len(), 2);
        let defs: Vec<_> = two.descendants().filter(|n| n.has_tag_name("text-style-def")).collect();
        for (run, def) in runs.iter().zip(&defs) {
            assert_eq!(run.attribute("ref"), def.attribute("id"), "every run's style is defined");
        }
        fn style<'a, 'b>(def: &roxmltree::Node<'a, 'b>) -> roxmltree::Node<'a, 'b> {
            def.children().find(|n| n.has_tag_name("text-style")).unwrap()
        }
        assert_eq!(style(&defs[0]).attribute("fontColor"), Some("1 0.8941 0.3608 1"));
        assert_eq!(style(&defs[1]).attribute("fontColor"), Some("1 1 1 1"));
        // Extra large is bigger than medium.
        let size = |def: &roxmltree::Node<'_, '_>| style(def).attribute("fontSize").unwrap().parse::<f64>().unwrap();
        assert!(size(&defs[0]) > size(&defs[1]));
        // Style ids are never used twice in the file.
        let mut ids: Vec<_> = doc
            .descendants()
            .filter(|n| n.has_tag_name("text-style-def"))
            .map(|n| n.attribute("id").unwrap())
            .collect();
        let all = ids.len();
        ids.sort_unstable();
        ids.dedup();
        assert_eq!(ids.len(), all);
    }

    #[test]
    fn every_reference_in_the_file_points_at_something_defined() {
        let path = PathBuf::from("/v/a.mp4");
        let line = |start, end, text: &str| Line { start, end, text: text.into() };
        let lines = [line(0, 3_000, "One"), line(3_000, 6_000, "Two")];
        let burn = Burn {
            look: Look::Large,
            first: Language { lines: &lines, style: Style::default() },
            second: None,
            second_on_top: false,
        };
        let xml = document(
            &source(&path, probe(Some(25.0))),
            &[clip(1, "A", 0, 6_000), clip(2, "B", 20_000, 26_000)],
            Some(&burn),
        );
        let doc = parse(&xml);
        let ids: Vec<_> = doc.descendants().filter_map(|n| n.attribute("id")).collect();
        for node in doc.descendants() {
            for (name, value) in node.attributes().map(|a| (a.name(), a.value())) {
                if matches!(name, "ref" | "format") && node.tag_name().name() != "text-style" {
                    assert!(ids.contains(&value), "<{}> {name}=\"{value}\" is not defined", node.tag_name().name());
                }
            }
        }
        // Every text style a run names is defined in its title.
        for title in doc.descendants().filter(|n| n.has_tag_name("title")) {
            let defined: Vec<_> = title
                .descendants()
                .filter(|n| n.has_tag_name("text-style-def"))
                .filter_map(|n| n.attribute("id"))
                .collect();
            for run in title.descendants().filter(|n| n.has_tag_name("text-style") && n.attribute("ref").is_some()) {
                assert!(defined.contains(&run.attribute("ref").unwrap()));
            }
        }
    }

    /// With CI's ffmpeg (`CHAYA_FFMPEG`), the tour's video as ffmpeg reads it, made into a project of two clips with its
    /// English subtitles as titles: its frame rate, size, length and sound as the file names them.
    #[tokio::test]
    async fn the_tours_video_read_by_ffmpeg_is_a_project_on_its_own_frames() {
        let Some(ffmpeg) = std::env::var_os("CHAYA_FFMPEG").map(PathBuf::from) else {
            eprintln!("skipped: no CHAYA_FFMPEG");
            return;
        };
        let fixtures = Path::new(env!("CARGO_MANIFEST_DIR")).join("../app/tour/fixtures");
        let video = fixtures.join("A walk in the park.mp4");
        let probe = export::probe(&ffmpeg, &video).await.unwrap();
        let lines =
            crate::subtitles::parse(&std::fs::read_to_string(fixtures.join("A walk in the park.en.srt")).unwrap());
        let burn = Burn {
            look: Look::Outline,
            first: Language { lines: &lines, style: Style::default() },
            second: None,
            second_on_top: false,
        };
        let clips = [clip(1, "Counting the walk", 5_000, 16_000), clip(2, "The end", 25_000, 29_000)];
        let xml = document(&Source { title: "A walk in the park", path: &video, probe }, &clips, Some(&burn));
        let doc = parse(&xml);
        let rate = Rate::nearest(probe.frame_rate);
        let format = element(&doc, "format");
        assert_eq!(format.attribute("frameDuration"), Some(rate.time(1).as_str()));
        assert_eq!((format.attribute("width"), format.attribute("height")), (Some("320"), Some("180")));
        assert_eq!(element(&doc, "asset").attribute("hasAudio"), Some("1"));
        assert!(element(&doc, "media-rep").attribute("src").unwrap().ends_with("/A%20walk%20in%20the%20park.mp4"));
        // 11 s and 4 s, back to back.
        assert_eq!(on_grid(element(&doc, "sequence").attribute("duration").unwrap(), rate), rate.frame_at(15_000));
        assert!(doc.descendants().any(|n| n.has_tag_name("title")), "the clips' lines are titles");
    }
}
