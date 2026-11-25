//! Clips for Premiere Pro (step 13 of the plan): the clips a person chose as one sequence in Final Cut 7's XML
//! ("xmeml", version 5), which Premiere Pro imports and exports, and DaVinci Resolve reads too. As with FCPXML
//! (`fcpxml`), nothing is encoded and the video is not copied: it is one `file`, named once in full and then by its
//! id, and each clip is a cut of it, a video `clipitem` and an audio one linked so they move together.
//!
//! The cuts are the ones `fcpxml::plan` makes, on the same frames. xmeml counts whole frames on a timebase with an
//! `ntsc` flag: 29.97 frames a second is timebase 30, NTSC, and frame 300 is the same frame in both files.
//!
//! The subtitles go beside the project as an SRT timed to the sequence (`captions`), which Premiere imports as a
//! caption track that can be restyled there. xmeml's text generators draw differently in every editor, so they are
//! not used.

use std::fmt::Write as _;

use crate::clips::Clip;
use crate::export::{self, Probe};
use crate::fcpxml::{attribute, audio_rate, file_url, plan, Rate, Source};
use crate::subtitles::{self, Line};

/// `<rate>`: the timebase and whether it is NTSC's (each frame 1001/1000 of the timebase's).
fn rate_xml(rate: Rate) -> String {
    let (timebase, ntsc) =
        if rate.den == 1_001 { (rate.num / 1_000, "TRUE") } else { (rate.num / rate.den.max(1), "FALSE") };
    format!("<rate><timebase>{timebase}</timebase><ntsc>{ntsc}</ntsc></rate>")
}

/// The address Premiere names a file by: `file://localhost/C:/…`, or `file://server/share/…` for a network share.
pub fn path_url(path: &std::path::Path) -> String {
    let url = file_url(path);
    match url.strip_prefix("file:///") {
        Some(local) => format!("file://localhost/{local}"),
        None => url,
    }
}

/// The video's picture, as a `samplecharacteristics`.
fn picture(probe: &Probe, rate: Rate) -> String {
    format!(
        "<samplecharacteristics>{}<width>{}</width><height>{}</height><pixelaspectratio>square</pixelaspectratio>\
         <fielddominance>none</fielddominance></samplecharacteristics>",
        rate_xml(rate),
        probe.width,
        probe.height
    )
}

/// The video's sound, as a `samplecharacteristics`.
fn sound(probe: &Probe) -> String {
    format!(
        "<samplecharacteristics><depth>16</depth><samplerate>{}</samplerate></samplecharacteristics>",
        audio_rate(probe.sample_rate)
    )
}

/// The xmeml file for `clips` of `source`, in the order given, one after the other.
pub fn document(source: &Source<'_>, clips: &[Clip]) -> String {
    let probe = &source.probe;
    let plan = plan(source, clips);
    let rate = rate_xml(plan.rate);
    let total: u64 = plan.cuts.iter().map(|c| c.frames).sum();
    let file_name = source.file_name();
    let channels = probe.channels.unwrap_or(2);
    let name = match clips {
        [only] => only.name.as_str(),
        _ => "Clips",
    };

    // The file, in full the first time it is named, then by its id.
    let mut named = false;
    let mut file = || {
        if std::mem::replace(&mut named, true) {
            return "<file id=\"file-1\"/>".to_string();
        }
        let audio = if probe.sound {
            format!("<audio>{}<channelcount>{channels}</channelcount></audio>", sound(probe))
        } else {
            String::new()
        };
        format!(
            "<file id=\"file-1\"><name>{}</name><pathurl>{}</pathurl>{rate}<duration>{}</duration>\
             <media><video>{}</video>{audio}</media></file>",
            attribute(&file_name),
            attribute(&path_url(source.path)),
            plan.asset_frames,
            picture(probe, plan.rate),
        )
    };
    // Each clip's picture and sound, linked: both clipitems name both.
    let links = |i: usize| {
        let mut out = format!(
            "<link><linkclipref>clipitem-v{i}</linkclipref><mediatype>video</mediatype><trackindex>1</trackindex>\
             <clipindex>{i}</clipindex></link>"
        );
        if probe.sound {
            let _ = write!(
                out,
                "<link><linkclipref>clipitem-a{i}</linkclipref><mediatype>audio</mediatype><trackindex>1</trackindex>\
                 <clipindex>{i}</clipindex><groupindex>1</groupindex></link>"
            );
        }
        out
    };
    let item = |kind: char, i: usize, cut: &crate::fcpxml::Cut<'_>, file: String| {
        let source_track = if kind == 'a' {
            "<sourcetrack><mediatype>audio</mediatype><trackindex>1</trackindex></sourcetrack>"
        } else {
            ""
        };
        format!(
            "          <clipitem id=\"clipitem-{kind}{i}\"><name>{}</name><enabled>TRUE</enabled><duration>{}</duration>\
             {rate}<start>{}</start><end>{}</end><in>{}</in><out>{}</out>{file}{source_track}{}</clipitem>\n",
            attribute(&cut.clip.name),
            plan.asset_frames,
            cut.offset,
            cut.offset + cut.frames,
            cut.start,
            cut.start + cut.frames,
            links(i),
        )
    };

    let mut out = String::new();
    out.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE xmeml>\n<xmeml version=\"5\">\n");
    let _ = writeln!(
        out,
        "  <sequence id=\"sequence-1\"><name>{}</name><duration>{total}</duration>{rate}\
         <timecode>{rate}<string>00:00:00:00</string><frame>0</frame><displayformat>NDF</displayformat></timecode>",
        attribute(&format!("{} - {name}", source.title))
    );
    out.push_str("    <media>\n      <video>\n");
    let _ = writeln!(out, "        <format>{}</format>", picture(probe, plan.rate));
    out.push_str("        <track>\n");
    for (i, cut) in plan.cuts.iter().enumerate() {
        out += &item('v', i + 1, cut, file());
    }
    out.push_str("        </track>\n      </video>\n");
    if probe.sound {
        let _ = writeln!(
            out,
            "      <audio>\n        <numOutputChannels>{}</numOutputChannels>\n        <format>{}</format>\n        <track>",
            channels.min(2),
            sound(probe)
        );
        for (i, cut) in plan.cuts.iter().enumerate() {
            out += &item('a', i + 1, cut, file());
        }
        out.push_str("        </track>\n      </audio>\n");
    }
    out.push_str("    </media>\n  </sequence>\n</xmeml>\n");
    out
}

/// The SRT for the sequence: each clip's lines in `first` (and `second` with them, each line over the other when
/// `second_on_top`), moved to where the clip sits in it, cut at the clip's edges.
pub fn captions(source: &Source<'_>, clips: &[Clip], first: &[Line], second: &[Line], second_on_top: bool) -> String {
    let plan = plan(source, clips);
    let rate = plan.rate;
    let mut lines = Vec::new();
    for cut in &plan.cuts {
        let (from, to) = (rate.ms_at(cut.start), rate.ms_at(cut.start + cut.frames));
        let at = rate.ms_at(cut.offset);
        let ends = at + (to - from);
        for slice in export::slices(first, second, from, to) {
            let text = match (slice.first, slice.second) {
                (Some(a), Some(b)) if second_on_top => format!("{b}\n{a}"),
                (Some(a), Some(b)) => format!("{a}\n{b}"),
                (a, b) => a.or(b).unwrap_or_default(),
            };
            lines.push(Line { start: at + slice.start, end: (at + slice.end).min(ends), text });
        }
    }
    subtitles::to_srt(&lines)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::{Path, PathBuf};

    fn probe(fps: f64, sound: bool) -> Probe {
        Probe {
            width: 1920,
            height: 1080,
            duration: Some(60_000),
            frame_rate: Some(fps),
            sound,
            sample_rate: sound.then_some(48_000),
            channels: sound.then_some(2),
        }
    }

    fn clip(id: u32, name: &str, start: u64, end: u64) -> Clip {
        Clip { id, name: name.into(), start, end }
    }

    fn parse(xml: &str) -> roxmltree::Document<'_> {
        roxmltree::Document::parse_with_options(
            xml,
            roxmltree::ParsingOptions { allow_dtd: true, ..Default::default() },
        )
        .unwrap_or_else(|e| panic!("not XML: {e}\n{xml}"))
    }

    fn child_text<'a>(node: roxmltree::Node<'a, '_>, name: &str) -> &'a str {
        node.children().find(|n| n.has_tag_name(name)).and_then(|n| n.text()).unwrap_or_else(|| panic!("no <{name}>"))
    }

    fn number(node: roxmltree::Node<'_, '_>, name: &str) -> u64 {
        child_text(node, name).parse().unwrap()
    }

    #[test]
    fn rates_are_a_timebase_and_whether_it_is_ntsc() {
        for (fps, timebase, ntsc) in [
            (23.976, "24", "TRUE"),
            (24.0, "24", "FALSE"),
            (25.0, "25", "FALSE"),
            (29.97, "30", "TRUE"),
            (59.94, "60", "TRUE"),
            (10.0, "10", "FALSE"),
        ] {
            let path = PathBuf::from("/v/a.mp4");
            let xml = document(&Source { title: "A", path: &path, probe: probe(fps, true) }, &[clip(1, "A", 0, 1_000)]);
            let doc = parse(&xml);
            let sequence = doc.descendants().find(|n| n.has_tag_name("sequence")).unwrap();
            let rate = sequence.children().find(|n| n.has_tag_name("rate")).unwrap();
            assert_eq!((child_text(rate, "timebase"), child_text(rate, "ntsc")), (timebase, ntsc), "{fps}");
        }
    }

    #[test]
    fn clips_are_linked_cuts_of_one_file_on_the_frames_fcpxml_uses() {
        let path = PathBuf::from(r"C:\Users\me\Videos\A walk.mp4");
        let clips = [clip(1, "First", 1_234, 9_876), clip(2, "Tom & \"Jerry\"", 20_017, 41_999)];
        let source = Source { title: "A walk", path: &path, probe: probe(29.97, true) };
        let xml = document(&source, &clips);
        let doc = parse(&xml);
        let plan = plan(&source, &clips);

        let items: Vec<_> = doc.descendants().filter(|n| n.has_tag_name("clipitem")).collect();
        assert_eq!(items.len(), 4, "a picture and a sound for each clip");
        let (video, audio): (Vec<&roxmltree::Node<'_, '_>>, Vec<&roxmltree::Node<'_, '_>>) =
            items.iter().partition(|n| n.attribute("id").unwrap().starts_with("clipitem-v"));
        for (i, cut) in plan.cuts.iter().enumerate() {
            for item in [video[i], audio[i]] {
                assert_eq!(number(*item, "in"), cut.start);
                assert_eq!(number(*item, "out"), cut.start + cut.frames);
                assert_eq!(number(*item, "start"), cut.offset);
                assert_eq!(number(*item, "end"), cut.offset + cut.frames);
                // Each names both halves of its clip.
                let refs: Vec<_> = item
                    .descendants()
                    .filter(|n| n.has_tag_name("linkclipref"))
                    .map(|n| n.text().unwrap().to_string())
                    .collect();
                assert_eq!(refs, [format!("clipitem-v{}", i + 1), format!("clipitem-a{}", i + 1)]);
            }
        }
        assert_eq!(child_text(*video[1], "name"), "Tom & \"Jerry\"");
        // The sequence is as long as its clips.
        let sequence = doc.descendants().find(|n| n.has_tag_name("sequence")).unwrap();
        assert_eq!(number(sequence, "duration"), plan.cuts.iter().map(|c| c.frames).sum::<u64>());
        assert_eq!(child_text(sequence, "name"), "A walk - Clips");

        // One file: in full once, by its id after.
        let files: Vec<_> = doc.descendants().filter(|n| n.has_tag_name("file")).collect();
        assert_eq!(files.len(), 4);
        assert!(files.iter().all(|f| f.attribute("id") == Some("file-1")));
        let full: Vec<_> = files.iter().filter(|f| f.has_children()).collect();
        assert_eq!(full.len(), 1);
        assert_eq!(child_text(*full[0], "pathurl"), "file://localhost/C:/Users/me/Videos/A%20walk.mp4");
        assert_eq!(child_text(*full[0], "name"), "A walk.mp4");
        // The audio clips take the file's sound.
        assert!(audio.iter().all(|a| a.descendants().any(|n| n.has_tag_name("sourcetrack"))));
    }

    #[test]
    fn a_video_without_sound_has_no_audio_track() {
        let path = PathBuf::from("/v/a.mp4");
        let xml = document(&Source { title: "A", path: &path, probe: probe(25.0, false) }, &[clip(1, "A", 0, 2_000)]);
        let doc = parse(&xml);
        assert!(!doc.descendants().any(|n| n.has_tag_name("audio")));
        assert!(!doc.descendants().any(|n| n.has_tag_name("linkclipref") && n.text() == Some("clipitem-a1")));
        let item = doc.descendants().find(|n| n.has_tag_name("clipitem")).unwrap();
        assert_eq!((number(item, "in"), number(item, "out")), (0, 50));
    }

    #[test]
    fn network_shares_and_local_paths_are_named_as_premiere_names_them() {
        assert_eq!(path_url(Path::new(r"\\nas\media\a b.mp4")), "file://nas/media/a%20b.mp4");
        assert_eq!(path_url(Path::new(r"\\?\D:\Clips\a.mp4")), "file://localhost/D:/Clips/a.mp4");
        assert_eq!(path_url(Path::new("/home/me/a.mp4")), "file://localhost/home/me/a.mp4");
    }

    #[test]
    fn captions_are_timed_to_where_each_clip_sits_in_the_sequence() {
        let path = PathBuf::from("/v/a.mp4");
        let line = |start, end, text: &str| Line { start, end, text: text.into() };
        let english = [line(10_000, 12_000, "In the first"), line(29_000, 33_000, "Across the second's start")];
        let french = [line(10_000, 12_000, "Dans le premier")];
        // At 25 frames a second every millisecond count is exact: 8 s, then 10 s.
        let clips = [clip(1, "One", 8_000, 16_000), clip(2, "Two", 30_000, 40_000)];
        let source = Source { title: "A", path: &path, probe: probe(25.0, true) };
        let srt = captions(&source, &clips, &english, &french, true);
        let read = subtitles::parse(&srt);
        assert_eq!(
            read,
            [
                // 2 s into the first clip, French over English.
                line(2_000, 4_000, "Dans le premier\nIn the first"),
                // The second clip starts 8 s into the sequence, and its line from where the clip begins.
                line(8_000, 11_000, "Across the second's start"),
            ]
        );
        // One language alone.
        let one = subtitles::parse(&captions(&source, &clips, &english, &[], false));
        assert_eq!(one[0].text, "In the first");
    }
}
