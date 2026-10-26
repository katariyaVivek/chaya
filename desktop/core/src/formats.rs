//! Turns what yt-dlp found into choices a person can read, best first, ending with "Audio only".
//!
//! Ported from the phone's `FormatSelector` (`app/src/main/java/com/chaya/app/platform/FormatSelector.kt`),
//! with its tests: only what the app can really save is offered. Beyond the phone's, a picture-only HLS stream
//! is paired with sound when nothing better reaches its quality, and a sound-only stream is the audio when
//! there is no sound file.

use std::cmp::Ordering;
use std::collections::BTreeMap;

use crate::media::{Format, Media};

/// One thing a person can pick: a quality of the video, or just its audio.
#[derive(Clone, Debug, PartialEq)]
pub struct Choice {
    /// "1080p", "720p60", "Video", "Audio only".
    pub label: String,
    /// Facts under the label, e.g. "MP4 · ≈ 48.0 MB".
    pub detail: String,
    /// Picture quality in pixels (the shorter side); `None` for audio only.
    pub quality: Option<i64>,
    /// What gets downloaded: a complete video, a picture-only track, or an audio-only track.
    pub file: Format,
    /// Sound to join to `file` when it has none of its own.
    pub audio_to_merge: Option<Format>,
    pub is_audio_only: bool,
}

impl Choice {
    pub fn needs_merge(&self) -> bool {
        self.audio_to_merge.is_some()
    }

    /// Total download size, when the sites said how big the pieces are.
    pub fn size_bytes(&self) -> Option<u64> {
        self.file.size_bytes.map(|size| size + self.audio_to_merge.as_ref().and_then(|a| a.size_bytes).unwrap_or(0))
    }
}

/// Larger first, the way the phone's `compareByDescending` chains read.
fn desc<T: PartialOrd>(a: T, b: T) -> Ordering {
    b.partial_cmp(&a).unwrap_or(Ordering::Equal)
}

/// Prefers MP4 (plays everywhere), then SDR, then the higher bitrate.
fn by_preference(a: &Format, b: &Format) -> Ordering {
    desc(is_ext(a, "mp4"), is_ext(b, "mp4"))
        .then(desc(a.is_standard_range(), b.is_standard_range()))
        .then(desc(a.bitrate_kbps.unwrap_or(0.0), b.bitrate_kbps.unwrap_or(0.0)))
}

/// For picture-only tracks joined to sound: MP4, then H.264, then SDR, then bitrate.
fn by_merge_compatibility(a: &Format, b: &Format) -> Ordering {
    desc(is_ext(a, "mp4"), is_ext(b, "mp4"))
        .then(desc(is_h264(a), is_h264(b)))
        .then(desc(a.is_standard_range(), b.is_standard_range()))
        .then(desc(a.bitrate_kbps.unwrap_or(0.0), b.bitrate_kbps.unwrap_or(0.0)))
}

/// The original language (never a dub), then the plain track over the loudness-compressed one, then M4A (it
/// joins to MP4 without re-encoding), then the higher bitrate.
fn by_audio_preference(a: &Format, b: &Format) -> Ordering {
    let rate = |f: &Format| f.audio_bitrate_kbps.or(f.bitrate_kbps).unwrap_or(0.0);
    desc(a.language_preference.unwrap_or(0), b.language_preference.unwrap_or(0))
        .then(desc(!a.is_loudness_compressed(), !b.is_loudness_compressed()))
        .then(desc(is_ext(a, "m4a"), is_ext(b, "m4a")))
        .then(desc(rate(a), rate(b)))
}

fn is_ext(f: &Format, ext: &str) -> bool {
    f.ext.as_deref() == Some(ext)
}

/// Whether a picture-only track is H.264, the one kind of picture the phone joins to sound.
pub fn is_h264(f: &Format) -> bool {
    f.video_codec.as_deref().is_some_and(|c| c.starts_with("avc"))
}

/// The best of `formats` by `order`; the first among equals, as a stable sort would give.
fn best<'a>(
    formats: impl IntoIterator<Item = &'a Format>,
    order: fn(&Format, &Format) -> Ordering,
) -> Option<&'a Format> {
    let mut sorted: Vec<&Format> = formats.into_iter().collect();
    sorted.sort_by(|a, b| order(a, b));
    sorted.first().copied()
}

/// The choices for `media`. `can_join` says whether the app can put a picture-only track together with a separate
/// sound file into one MP4; qualities that exist only as separate tracks it cannot join are left out rather than
/// offered and then failing.
pub fn choices(media: &Media, can_join: impl Fn(&Format) -> bool) -> Vec<Choice> {
    // Saving a live broadcast needs a different approach than saving a finished video.
    if media.is_live {
        return Vec::new();
    }
    let audio_only: Vec<&Format> = media.formats.iter().filter(|f| f.is_audio_only() && f.is_direct_file()).collect();
    let audio_streams = || media.formats.iter().filter(|f| f.is_audio_only() && f.is_hls());
    let best_audio =
        best(audio_only.iter().copied(), by_audio_preference).or_else(|| best(audio_streams(), by_audio_preference));
    let merge_audio =
        best(audio_only.iter().copied().filter(|f| is_ext(f, "m4a") || is_ext(f, "mp4")), by_audio_preference);
    // A picture-only stream takes the sound file when there is one, else the best sound-only stream.
    let stream_sound = merge_audio.or_else(|| best(audio_streams(), by_audio_preference));

    let mut by_quality: BTreeMap<i64, Vec<&Format>> = BTreeMap::new();
    for format in media.formats.iter().filter(|f| f.has_video() && !f.is_audio_only()) {
        if let Some(quality) = format.quality() {
            by_quality.entry(quality).or_default().push(format);
        }
    }
    let mut choices: Vec<Choice> = by_quality
        .iter()
        .rev()
        .filter_map(|(quality, candidates)| choice_for(*quality, candidates, (merge_audio, stream_sound), &can_join))
        .collect();

    if choices.is_empty() {
        // A bare link to a video file has no picture size to name a quality by. Offer the best complete file.
        let unsized_files =
            media.formats.iter().filter(|f| f.is_complete_file() && f.quality().is_none() && !f.is_audio_only());
        if let Some(file) = best(unsized_files, by_preference) {
            choices.push(choice("Video".into(), None, file.clone(), None, false));
        }
    }
    if let Some(audio) = best_audio {
        choices.push(choice("Audio only".into(), None, audio.clone(), None, true));
    }
    choices
}

/// One choice per quality. A ready-made file wins; then, when joining is possible, a separate picture and sound;
/// then an HLS stream with picture and sound; then a picture-only HLS stream and sound; a DASH stream, which the
/// app cannot save yet, is the last resort.
fn choice_for(
    quality: i64,
    candidates: &[&Format],
    (merge_audio, stream_sound): (Option<&Format>, Option<&Format>),
    can_join: &impl Fn(&Format) -> bool,
) -> Option<Choice> {
    let complete_file = best(candidates.iter().copied().filter(|f| f.is_complete_file()), by_preference);
    let picture_only = best(
        candidates.iter().copied().filter(|f| {
            f.is_direct_file() && f.audio_codec.as_deref() == Some("none") && is_ext(f, "mp4") && can_join(f)
        }),
        by_merge_compatibility,
    );
    let streams = || candidates.iter().copied().filter(|f| !f.is_direct_file());
    let complete_stream = best(streams().filter(|f| f.is_hls() && f.has_audio()), by_preference);
    let picture_stream = best(
        streams().filter(|f| f.is_hls() && f.audio_codec.as_deref() == Some("none") && can_join(f)),
        by_merge_compatibility,
    );
    let other_stream = best(streams().filter(|f| !f.is_hls() && f.has_audio()), by_preference);

    let (file, audio) = if let Some(file) = complete_file {
        (file, None)
    } else if let (Some(picture), Some(audio)) = (picture_only, merge_audio) {
        (picture, Some(audio))
    } else if let Some(stream) = complete_stream {
        (stream, None)
    } else if let (Some(picture), Some(audio)) = (picture_stream, stream_sound) {
        (picture, Some(audio))
    } else {
        (other_stream?, None)
    };
    let fps = file.fps.filter(|fps| *fps > 30.0).map(|fps| (fps as i64).to_string()).unwrap_or_default();
    Some(choice(format!("{quality}p{fps}"), Some(quality), file.clone(), audio.cloned(), false))
}

fn choice(
    label: String,
    quality: Option<i64>,
    file: Format,
    audio_to_merge: Option<Format>,
    is_audio_only: bool,
) -> Choice {
    let mut choice = Choice { label, detail: String::new(), quality, file, audio_to_merge, is_audio_only };
    // A stream is saved as an MP4, or an M4A for sound only, whatever its pieces are.
    let saved_as = match (choice.file.is_hls(), is_audio_only) {
        (true, true) => Some("m4a"),
        (true, false) => Some("mp4"),
        (false, _) => choice.file.ext.as_deref(),
    };
    let mut facts: Vec<String> = saved_as.iter().map(|e| e.to_uppercase()).collect();
    if let Some(size) = choice.size_bytes().filter(|s| *s > 0) {
        facts.push(format!("≈ {}", file_size(size)));
    }
    choice.detail = facts.join(" · ");
    choice
}

/// "48.0 MB", as the phone writes sizes.
pub fn file_size(bytes: u64) -> String {
    const KB: f64 = 1024.0;
    let b = bytes as f64;
    if b >= KB * KB * KB {
        format!("{:.1} GB", b / (KB * KB * KB))
    } else if b >= KB * KB {
        format!("{:.1} MB", b / (KB * KB))
    } else if b >= KB {
        format!("{:.1} KB", b / KB)
    } else {
        format!("{bytes} B")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::media::parse;

    fn media(formats: Vec<Format>) -> Media {
        Media { title: "Title".into(), formats, ..Media::default() }
    }

    fn format(id: &str) -> Format {
        Format {
            id: id.into(),
            url: format!("https://cdn.example/{id}"),
            ext: Some("mp4".into()),
            protocol: Some("https".into()),
            ..Format::default()
        }
    }

    /// A single file with picture and sound, like YouTube's format 18 or a TikTok download.
    fn complete(id: &str, width: Option<i64>, height: Option<i64>) -> Format {
        Format {
            width,
            height,
            video_codec: Some("avc1.42001E".into()),
            audio_codec: Some("mp4a.40.2".into()),
            ..format(id)
        }
    }

    fn picture_only(id: &str, width: i64, height: i64, vcodec: &str) -> Format {
        Format {
            width: Some(width),
            height: Some(height),
            video_codec: Some(vcodec.into()),
            audio_codec: Some("none".into()),
            ..format(id)
        }
    }

    fn audio(id: &str, ext: &str) -> Format {
        Format {
            ext: Some(ext.into()),
            video_codec: Some("none".into()),
            audio_codec: Some("mp4a.40.2".into()),
            ..format(id)
        }
    }

    fn stream(id: &str, width: i64, height: i64) -> Format {
        Format {
            protocol: Some("m3u8_native".into()),
            url: format!("https://cdn.example/{id}/index.m3u8"),
            ..complete(id, Some(width), Some(height))
        }
    }

    fn dash(id: &str, width: i64, height: i64) -> Format {
        Format { protocol: Some("http_dash_segments".into()), ..complete(id, Some(width), Some(height)) }
    }

    fn labels(choices: &[Choice]) -> Vec<&str> {
        choices.iter().map(|c| c.label.as_str()).collect()
    }

    fn ids(choices: &[Choice]) -> Vec<&str> {
        choices.iter().map(|c| c.file.id.as_str()).collect()
    }

    const NEVER: fn(&Format) -> bool = |_| false;
    const ALWAYS: fn(&Format) -> bool = |_| true;

    #[test]
    fn ready_made_files_come_best_first_and_end_with_audio_only() {
        let m = media(vec![
            complete("18", Some(640), Some(360)),
            complete("22", Some(1280), Some(720)),
            Format { audio_bitrate_kbps: Some(129.0), ..audio("140", "m4a") },
        ]);
        let c = choices(&m, NEVER);
        assert_eq!(labels(&c), ["720p", "360p", "Audio only"]);
        assert_eq!(c.iter().map(|c| c.quality).collect::<Vec<_>>(), [Some(720), Some(360), None]);
        assert_eq!(c.iter().map(|c| c.is_audio_only).collect::<Vec<_>>(), [false, false, true]);
    }

    #[test]
    fn qualities_only_as_separate_tracks_are_hidden_without_joining() {
        let m = media(vec![
            complete("18", Some(640), Some(360)),
            picture_only("137", 1920, 1080, "avc1.640028"),
            audio("140", "m4a"),
        ]);
        assert_eq!(labels(&choices(&m, NEVER)), ["360p", "Audio only"]);
    }

    #[test]
    fn separate_picture_and_sound_are_paired_when_they_can_be_joined() {
        let m = media(vec![
            complete("18", Some(640), Some(360)),
            Format { size_bytes: Some(40_000_000), ..picture_only("137", 1920, 1080, "avc1.640028") },
            Format { size_bytes: Some(3_000_000), ..audio("140", "m4a") },
        ]);
        let hd = choices(&m, ALWAYS).remove(0);
        assert_eq!(hd.label, "1080p");
        assert!(hd.needs_merge());
        assert_eq!(hd.file.id, "137");
        assert_eq!(hd.audio_to_merge.as_ref().unwrap().id, "140");
        assert_eq!(hd.size_bytes(), Some(43_000_000));
    }

    #[test]
    fn for_a_join_the_mp4_h264_picture_beats_webm_and_av1() {
        let m = media(vec![
            Format { ext: Some("webm".into()), bitrate_kbps: Some(2500.0), ..picture_only("248", 1920, 1080, "vp9") },
            Format { bitrate_kbps: Some(1800.0), ..picture_only("399", 1920, 1080, "av01.0.08M.08") },
            Format { bitrate_kbps: Some(4000.0), ..picture_only("137", 1920, 1080, "avc1.640028") },
            audio("140", "m4a"),
        ]);
        assert_eq!(choices(&m, ALWAYS)[0].file.id, "137");
    }

    #[test]
    fn a_webm_only_quality_is_not_offered_for_joining() {
        let m = media(vec![
            Format { ext: Some("webm".into()), ..picture_only("248", 1920, 1080, "vp9") },
            audio("140", "m4a"),
        ]);
        assert_eq!(labels(&choices(&m, ALWAYS)), ["Audio only"]);
    }

    #[test]
    fn sound_that_joins_mp4_without_reencoding_wins_and_webm_sound_is_not_paired() {
        let m = media(vec![
            picture_only("137", 1920, 1080, "avc1.640028"),
            Format { audio_bitrate_kbps: Some(160.0), ..audio("251", "webm") },
            Format { audio_bitrate_kbps: Some(129.0), ..audio("140", "m4a") },
        ]);
        let c = choices(&m, ALWAYS);
        assert_eq!(c[0].audio_to_merge.as_ref().unwrap().id, "140");
        assert_eq!(c.last().unwrap().file.id, "140");

        let webm_only = media(vec![picture_only("137", 1920, 1080, "avc1.640028"), audio("251", "webm")]);
        assert_eq!(ids(&choices(&webm_only, ALWAYS)), ["251"]);
    }

    #[test]
    fn the_original_language_beats_a_dub_and_plain_sound_beats_its_drc_copy() {
        let m = media(vec![
            picture_only("137", 1920, 1080, "avc1.640028"),
            Format { audio_bitrate_kbps: Some(129.0), language_preference: Some(-1), ..audio("140-1", "m4a") },
            Format { audio_bitrate_kbps: Some(48.0), language_preference: Some(10), ..audio("140-0", "m4a") },
        ]);
        let c = choices(&m, ALWAYS);
        assert_eq!(c[0].audio_to_merge.as_ref().unwrap().id, "140-0");

        let drc = media(vec![
            Format { audio_bitrate_kbps: Some(130.0), note: Some("medium, DRC".into()), ..audio("140-drc", "m4a") },
            Format { audio_bitrate_kbps: Some(129.0), note: Some("medium".into()), ..audio("140", "m4a") },
            Format { audio_bitrate_kbps: Some(200.0), ..audio("251-drc", "m4a") },
        ]);
        assert_eq!(ids(&choices(&drc, NEVER)), ["140"]);
    }

    #[test]
    fn sdr_beats_hdr_but_hdr_alone_is_still_offered() {
        let m = media(vec![
            Format {
                bitrate_kbps: Some(9000.0),
                dynamic_range: Some("HDR10".into()),
                ..complete("hdr", Some(1920), Some(1080))
            },
            Format {
                bitrate_kbps: Some(4000.0),
                dynamic_range: Some("SDR".into()),
                ..complete("sdr", Some(1920), Some(1080))
            },
        ]);
        assert_eq!(ids(&choices(&m, NEVER)), ["sdr"]);
        let alone =
            media(vec![Format { dynamic_range: Some("HDR10".into()), ..complete("hdr", Some(1920), Some(1080)) }]);
        assert_eq!(ids(&choices(&alone, NEVER)), ["hdr"]);
    }

    #[test]
    fn only_pictures_the_app_can_join_are_paired() {
        let m = media(vec![
            picture_only("h264-1080", 1920, 1080, "avc1.640028"),
            picture_only("av1-2160", 3840, 2160, "av01.0.12M.08"),
            picture_only("h264-720", 1280, 720, "avc1.4d401f"),
            audio("140", "m4a"),
        ]);
        assert_eq!(labels(&choices(&m, is_h264)), ["1080p", "720p", "Audio only"]);
    }

    #[test]
    fn a_ready_made_file_beats_a_join_and_a_join_beats_a_manifest() {
        let m = media(vec![
            complete("22", Some(1280), Some(720)),
            picture_only("136", 1280, 720, "avc1.4d401f"),
            audio("140", "m4a"),
        ]);
        let first = choices(&m, ALWAYS).remove(0);
        assert_eq!(first.file.id, "22");
        assert!(!first.needs_merge());

        let m = media(vec![
            stream("hls-1080", 1920, 1080),
            picture_only("137", 1920, 1080, "avc1.640028"),
            audio("140", "m4a"),
        ]);
        assert_eq!(choices(&m, ALWAYS)[0].file.id, "137");
        assert_eq!(choices(&m, NEVER)[0].file.id, "hls-1080");
    }

    #[test]
    fn a_manifest_is_offered_when_nothing_else_reaches_that_quality_but_not_without_sound() {
        let m = media(vec![complete("18", Some(640), Some(360)), stream("hls-1080", 1920, 1080)]);
        let c = choices(&m, NEVER);
        assert_eq!(labels(&c), ["1080p", "360p"]);
        assert!(!c[0].file.is_direct_file());

        let silent = media(vec![Format { audio_codec: Some("none".into()), ..stream("hls-v", 1280, 720) }]);
        assert!(choices(&silent, ALWAYS).is_empty());
    }

    #[test]
    fn a_picture_only_stream_is_paired_with_sound_when_nothing_better_reaches_its_quality() {
        let picture = |id: &str, h: i64| Format { audio_codec: Some("none".into()), ..stream(id, h * 16 / 9, h) };
        let sound_stream =
            Format { width: None, height: None, video_codec: Some("none".into()), ..stream("hls-audio", 0, 0) };
        // As X answers: picture-only streams, a sound-only stream, and one ready-made file.
        let m = media(vec![
            picture("hls-1080", 1080),
            picture("hls-720", 720),
            complete("http-720", Some(1280), Some(720)),
            sound_stream.clone(),
        ]);
        let c = choices(&m, ALWAYS);
        assert_eq!(labels(&c), ["1080p", "720p", "Audio only"]);
        assert_eq!(c[0].file.id, "hls-1080");
        assert_eq!(c[0].audio_to_merge.as_ref().unwrap().id, "hls-audio");
        assert_eq!(c[1].file.id, "http-720", "the ready-made file wins");
        assert_eq!(c[2].file.id, "hls-audio", "the sound-only stream is the audio when there is no sound file");
        assert_eq!(c[2].detail, "M4A", "what it is saved as");

        // A sound file is preferred to a sound stream, and nothing is paired without joining.
        let with_file = media(vec![picture("hls-1080", 1080), sound_stream, audio("140", "m4a")]);
        assert_eq!(choices(&with_file, ALWAYS)[0].audio_to_merge.as_ref().unwrap().id, "140");
        assert_eq!(labels(&choices(&with_file, NEVER)), ["Audio only"]);
    }

    #[test]
    fn an_hls_stream_is_preferred_to_dash_which_is_the_last_resort() {
        let m = media(vec![dash("dash-1080", 1920, 1080), stream("hls-1080", 1920, 1080)]);
        assert_eq!(choices(&m, ALWAYS)[0].file.id, "hls-1080");
        let only_dash = media(vec![dash("dash-1080", 1920, 1080)]);
        assert_eq!(choices(&only_dash, ALWAYS)[0].file.id, "dash-1080");
    }

    #[test]
    fn labels_name_the_shorter_side_and_a_high_frame_rate() {
        let vertical = media(vec![complete("540", Some(576), Some(1024)), complete("1080", Some(1080), Some(1920))]);
        assert_eq!(labels(&choices(&vertical, NEVER)), ["1080p", "576p"]);
        let rates = media(vec![
            Format { fps: Some(60.0), ..complete("fast", Some(1920), Some(1080)) },
            Format { fps: Some(30.0), ..complete("normal", Some(1280), Some(720)) },
            complete("unknown", Some(640), Some(360)),
        ]);
        assert_eq!(labels(&choices(&rates, NEVER)), ["1080p60", "720p", "360p"]);
    }

    #[test]
    fn unknown_codecs_are_complete_files_as_simple_sites_report_them() {
        let m = media(vec![
            Format { width: Some(640), height: Some(360), ..format("http-632") },
            Format { width: Some(1280), height: Some(720), ..format("http-2176") },
        ]);
        let c = choices(&m, NEVER);
        assert_eq!(labels(&c), ["720p", "360p"]);
        assert!(c.iter().all(|c| !c.is_audio_only));
    }

    #[test]
    fn several_files_at_one_quality_give_one_choice_preferring_mp4_then_bitrate() {
        let m = media(vec![
            Format { ext: Some("webm".into()), bitrate_kbps: Some(3000.0), ..complete("web", Some(1280), Some(720)) },
            Format { bitrate_kbps: Some(1000.0), ..complete("mp4-low", Some(1280), Some(720)) },
            Format { bitrate_kbps: Some(2000.0), ..complete("mp4-high", Some(1280), Some(720)) },
        ]);
        assert_eq!(ids(&choices(&m, NEVER)), ["mp4-high"]);
    }

    #[test]
    fn live_broadcasts_storyboards_and_nothing_offer_nothing() {
        let live = Media { is_live: true, ..media(vec![complete("live", Some(1280), Some(720))]) };
        assert!(choices(&live, ALWAYS).is_empty());
        let storyboard = media(vec![Format {
            ext: Some("mhtml".into()),
            video_codec: Some("none".into()),
            audio_codec: Some("none".into()),
            width: Some(48),
            height: Some(27),
            ..format("sb0")
        }]);
        assert!(choices(&storyboard, ALWAYS).is_empty());
        assert!(choices(&media(vec![]), ALWAYS).is_empty());
    }

    #[test]
    fn a_bare_video_link_is_offered_as_plain_video_until_sized_qualities_exist() {
        let bare = media(vec![Format { size_bytes: Some(2048), ..complete("direct", None, None) }]);
        let c = choices(&bare, NEVER);
        assert_eq!(labels(&c), ["Video"]);
        assert_eq!(c[0].quality, None);
        assert_eq!(c[0].detail, "MP4 · ≈ 2.0 KB");

        let mixed = media(vec![complete("sized", Some(1280), Some(720)), complete("unsized", None, None)]);
        assert_eq!(labels(&choices(&mixed, NEVER)), ["720p"]);

        let several = media(vec![
            Format { ext: Some("webm".into()), bitrate_kbps: Some(5000.0), ..complete("web", None, None) },
            Format { bitrate_kbps: Some(500.0), ..complete("mp4-low", None, None) },
            Format { bitrate_kbps: Some(900.0), ..complete("mp4-high", None, None) },
        ]);
        assert_eq!(ids(&choices(&several, NEVER)), ["mp4-high"]);
    }

    #[test]
    fn bare_audio_links_and_pages_of_only_sound_offer_audio_only() {
        let mp3 = media(vec![Format { ext: Some("mp3".into()), video_codec: Some("none".into()), ..format("mp3") }]);
        let c = choices(&mp3, NEVER);
        assert_eq!(labels(&c), ["Audio only"]);
        assert!(c[0].is_audio_only);

        let both = media(vec![complete("clip", None, None), audio("song", "m4a")]);
        assert_eq!(labels(&choices(&both, NEVER)), ["Video", "Audio only"]);
    }

    #[test]
    fn details_give_the_container_and_the_size_of_both_pieces() {
        let m = media(vec![
            Format { size_bytes: Some(50_331_648), ..complete("sized", Some(1280), Some(720)) },
            complete("unsized", Some(640), Some(360)),
            Format { size_bytes: Some(4_194_304), ..audio("a", "m4a") },
        ]);
        let details: Vec<String> = choices(&m, NEVER).into_iter().map(|c| c.detail).collect();
        assert_eq!(details, ["MP4 · ≈ 48.0 MB", "MP4", "M4A · ≈ 4.0 MB"]);

        let joined = media(vec![
            Format { size_bytes: Some(31_457_280), ..picture_only("137", 1920, 1080, "avc1.640028") },
            Format { size_bytes: Some(3_145_728), ..audio("140", "m4a") },
        ]);
        assert_eq!(choices(&joined, ALWAYS)[0].detail, "MP4 · ≈ 33.0 MB");

        let unknown_audio = media(vec![
            Format { size_bytes: Some(20_971_520), ..picture_only("137", 1920, 1080, "avc1.640028") },
            audio("140", "m4a"),
        ]);
        assert_eq!(choices(&unknown_audio, ALWAYS)[0].size_bytes(), Some(20_971_520));
    }

    /// The phone's saved yt-dlp answer for a real YouTube video ("Me at the zoo"), shared with its test.
    const ZOO: &str = include_str!("../../../app/src/test/resources/platform/youtube_me_at_the_zoo.json");

    #[test]
    fn a_real_youtube_answer_is_read_in_full() {
        let zoo = parse(ZOO).unwrap();
        assert_eq!(zoo.id.as_deref(), Some("jNQXAC9IVRw"));
        assert_eq!(zoo.title, "Me at the zoo");
        assert_eq!(zoo.author.as_deref(), Some("jawed"));
        assert_eq!(zoo.duration_seconds, Some(19.0));
        assert_eq!(zoo.formats.len(), 24);
        assert!(zoo.formats.iter().all(|f| !f.is_complete_file()), "YouTube keeps picture and sound apart");
    }

    #[test]
    fn on_real_youtube_formats_each_quality_pairs_an_h264_picture_with_the_aac_sound() {
        let zoo = parse(ZOO).unwrap();
        let without = choices(&zoo, NEVER);
        assert_eq!(labels(&without), ["Audio only"]);
        assert_eq!(without[0].file.id, "140");

        let with = choices(&zoo, ALWAYS);
        assert_eq!(labels(&with), ["240p", "144p", "Audio only"]);
        let pictures: Vec<&Choice> = with.iter().filter(|c| !c.is_audio_only).collect();
        assert_eq!(pictures.iter().map(|c| c.file.id.as_str()).collect::<Vec<_>>(), ["133", "160"]);
        assert!(pictures.iter().all(|c| c.audio_to_merge.as_ref().unwrap().id == "140"));
        let offered: Vec<&Format> =
            with.iter().flat_map(|c| [Some(&c.file), c.audio_to_merge.as_ref()]).flatten().collect();
        assert!(offered.iter().all(|f| f.is_direct_file() && !is_ext(f, "webm") && !f.is_loudness_compressed()));
    }

    #[test]
    fn sizes_are_written_as_on_the_phone() {
        assert_eq!(file_size(512), "512 B");
        assert_eq!(file_size(2048), "2.0 KB");
        assert_eq!(file_size(50_331_648), "48.0 MB");
        assert_eq!(file_size(1_288_490_189), "1.2 GB");
    }
}
