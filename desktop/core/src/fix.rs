//! Subtitles put right by the person, in the file next to the video that players read: a line's words corrected (or the
//! line taken out), and the timing of a whole track moved: shifted, or stretched between two lines when it drifts (a
//! file made for another cut of the video, or for another frame rate). The file keeps its format, SRT or WebVTT.

use std::path::Path;

use crate::subtitles::{self, Line};

/// How far a stretch may go: a track at most this much faster or slower than the video (25 against 23.976 frames a
/// second is 4%; a file for another cut can be further off, but not twice as fast).
const STRETCH: f64 = 0.25;
/// Two lines closer than this are too close to stretch between: a small mistake in either would stretch it wildly.
const NEAREST_ANCHORS: u64 = 5_000;
/// The longest line kept, in characters, as the window's field allows.
const LONGEST_TEXT: usize = 500;

/// A change of a track's timing: every time `t` becomes `t × scale + offset` (milliseconds).
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Retime {
    pub scale: f64,
    pub offset: f64,
}

/// What the person asked for: a shift by `by` milliseconds, or the line at `from` to start at `to` while the line
/// already put right at `fixed` stays where it is.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum Ask {
    Shift { by: i64 },
    Through { fixed: u64, from: u64, to: u64 },
}

impl Retime {
    pub fn shift(by: i64) -> Retime {
        Retime { scale: 1.0, offset: by as f64 }
    }

    /// The change `ask` means: a stretch keeps `fixed` where it is and takes `from` to `to`; when the two lines are
    /// too close, or the stretch would be more than `STRETCH`, it is a plain shift of `from` to `to`.
    pub fn of(ask: Ask) -> Retime {
        match ask {
            Ask::Shift { by } => Retime::shift(by),
            Ask::Through { fixed, from, to } => {
                let shift = Retime::shift(to as i64 - from as i64);
                if from.abs_diff(fixed) < NEAREST_ANCHORS || to.abs_diff(fixed) < NEAREST_ANCHORS {
                    return shift;
                }
                let scale = (to as f64 - fixed as f64) / (from as f64 - fixed as f64);
                if !(1.0 - STRETCH..=1.0 + STRETCH).contains(&scale) {
                    return shift;
                }
                Retime { scale, offset: fixed as f64 - fixed as f64 * scale }
            }
        }
    }

    fn at(&self, ms: u64) -> f64 {
        ms as f64 * self.scale + self.offset
    }

    /// `lines` with their times changed: kept in order; a line that would end before the video starts is left out,
    /// and one that would start before it starts at 0.
    pub fn apply(&self, lines: &[Line]) -> Vec<Line> {
        lines
            .iter()
            .filter_map(|line| {
                let (start, end) = (self.at(line.start), self.at(line.end));
                if end <= 0.0 {
                    return None;
                }
                let start = start.max(0.0).round() as u64;
                Some(Line { start, end: (end.round() as u64).max(start + 1), text: line.text.clone() })
            })
            .collect()
    }
}

/// `lines` with line `index` saying `text` instead: its breaks kept, spaces at its ends taken off; empty, the line is
/// taken out. `None` when there is no such line.
pub fn with_text(lines: &[Line], index: usize, text: &str) -> Option<Vec<Line>> {
    if index >= lines.len() {
        return None;
    }
    let text: String = text
        .replace("\r\n", "\n")
        .split('\n')
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .collect::<Vec<_>>()
        .join("\n")
        .chars()
        .take(LONGEST_TEXT)
        .collect();
    let mut out = lines.to_vec();
    if text.is_empty() {
        out.remove(index);
    } else {
        out[index].text = text;
    }
    Some(out)
}

/// Lines as a WebVTT file.
pub fn to_vtt(lines: &[Line]) -> String {
    let stamp =
        |ms: u64| format!("{:02}:{:02}:{:02}.{:03}", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    let mut out = String::from("WEBVTT\n\n");
    for line in lines {
        out.push_str(&format!("{} --> {}\n{}\n\n", stamp(line.start), stamp(line.end), line.text));
    }
    out
}

/// Writes `lines` to the subtitle file at `path`, in its own format (WebVTT for ".vtt", SRT for the rest), whole to a
/// file beside it first so a crash never leaves half.
pub fn write(path: &Path, lines: &[Line]) -> std::io::Result<()> {
    let vtt = path.extension().is_some_and(|e| e.eq_ignore_ascii_case("vtt"));
    let text = if vtt { to_vtt(lines) } else { subtitles::to_srt(lines) };
    let part = path.with_extension(format!("{}.part", path.extension().and_then(|e| e.to_str()).unwrap_or("srt")));
    std::fs::write(&part, text)?;
    std::fs::rename(&part, path)
}

/// The file the player shows `language`'s subtitles from, for the video at `video` (the first of that language, as
/// `subtitles::tracks_for` lists them).
pub fn file_of(video: &Path, language: Option<&str>) -> Option<std::path::PathBuf> {
    subtitles::files_for(video).into_iter().find(|f| f.language.as_deref() == language).map(|f| f.path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn line(start: u64, end: u64, text: &str) -> Line {
        Line { start, end, text: text.into() }
    }

    fn times(lines: &[Line]) -> Vec<(u64, u64)> {
        lines.iter().map(|l| (l.start, l.end)).collect()
    }

    fn walk() -> Vec<Line> {
        vec![
            line(1_000, 4_000, "One"),
            line(5_000, 8_000, "Two"),
            line(20_000, 24_000, "Three"),
            line(60_000, 64_000, "Four"),
        ]
    }

    #[test]
    fn a_shift_moves_every_line_and_leaves_out_what_would_end_before_the_video() {
        assert_eq!(
            times(&Retime::shift(1_500).apply(&walk())),
            [(2_500, 5_500), (6_500, 9_500), (21_500, 25_500), (61_500, 65_500)]
        );
        let earlier = Retime::shift(-4_500).apply(&walk());
        // "One" ended at 4 s: now before the start, it is gone; "Two" starts at 0.5 s.
        assert_eq!(times(&earlier), [(500, 3_500), (15_500, 19_500), (55_500, 59_500)]);
        assert_eq!(times(&Retime::shift(-1_500).apply(&walk()))[0], (0, 2_500), "cut at the start, not gone");
    }

    #[test]
    fn two_lines_put_right_stretch_the_rest_between_and_beyond_them() {
        // "Three" was put right at 20 s; "Four" is said at 61.5 s, not 60: everything after 20 s stretches by 1.5/40.
        let retime = Retime::of(Ask::Through { fixed: 20_000, from: 60_000, to: 61_500 });
        assert!((retime.scale - 1.0375).abs() < 1e-9);
        let lines = retime.apply(&walk());
        assert_eq!(lines[2].start, 20_000, "the first line put right stays");
        assert_eq!(lines[3].start, 61_500, "the second goes where it was said");
        assert_eq!(lines[3].end, 65_650);
        assert_eq!(lines[0].start, 288, "before the first, the stretch carries on backwards");
    }

    #[test]
    fn a_stretch_too_close_or_too_wild_is_a_shift() {
        // 2 s apart: a stretch from so close would magnify any slip, so the second line is shifted to.
        assert_eq!(Retime::of(Ask::Through { fixed: 20_000, from: 22_000, to: 23_000 }), Retime::shift(1_000));
        // Twice as fast is not a frame rate: a shift.
        assert_eq!(Retime::of(Ask::Through { fixed: 0, from: 30_000, to: 60_000 }), Retime::shift(30_000));
        assert_eq!(Retime::of(Ask::Shift { by: -250 }), Retime::shift(-250));
    }

    #[test]
    fn what_the_window_asks_is_read() {
        let shift: Ask = serde_json::from_str(r#"{"kind": "shift", "by": -500}"#).unwrap();
        assert_eq!(shift, Ask::Shift { by: -500 });
        let through: Ask =
            serde_json::from_str(r#"{"kind": "through", "fixed": 6200, "from": 26200, "to": 27000}"#).unwrap();
        assert_eq!(through, Ask::Through { fixed: 6_200, from: 26_200, to: 27_000 });
        assert!(serde_json::from_str::<Ask>(r#"{"kind": "spin", "by": 1}"#).is_err());
    }

    #[test]
    fn a_line_is_corrected_or_taken_out() {
        let fixed = with_text(&walk(), 1, "  Two, said right \r\n\r\n second half  ").unwrap();
        assert_eq!(fixed[1].text, "Two, said right\nsecond half");
        assert_eq!(times(&fixed), times(&walk()), "its time stays");
        let without = with_text(&walk(), 0, "   ").unwrap();
        assert_eq!(without.iter().map(|l| l.text.as_str()).collect::<Vec<_>>(), ["Two", "Three", "Four"]);
        assert_eq!(with_text(&walk(), 4, "No such line"), None);
        assert_eq!(with_text(&walk(), 2, &"x".repeat(900)).unwrap()[2].text.len(), LONGEST_TEXT);
    }

    #[test]
    fn a_file_is_written_in_its_own_format_and_reads_back_the_same() {
        let dir = tempfile::tempdir().unwrap();
        let lines = vec![line(1_000, 4_000, "One"), line(3_723_456, 3_725_000, "Two\nlines")];
        for name in ["Clip.en.srt", "Clip.fr.vtt"] {
            let path = dir.path().join(name);
            write(&path, &lines).unwrap();
            let text = std::fs::read_to_string(&path).unwrap();
            assert_eq!(text.starts_with("WEBVTT"), name.ends_with(".vtt"), "{text}");
            assert_eq!(subtitles::parse(&text), lines, "{name}");
            assert!(!path.with_extension(format!("{}.part", &name[name.len() - 3..])).exists());
        }
        std::fs::write(dir.path().join("Clip.mp4"), b"").unwrap();
        let video = dir.path().join("Clip.mp4");
        assert_eq!(file_of(&video, Some("fr")), Some(dir.path().join("Clip.fr.vtt")));
        assert_eq!(file_of(&video, Some("de")), None);
    }
}
