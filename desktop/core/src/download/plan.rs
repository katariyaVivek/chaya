//! From a looked-up link and the choice a person made to the downloads that save it: one file for a
//! quality or the sound, every picture and video for a post.

use std::path::Path;

use crate::formats;
use crate::lookup::can_save;
use crate::media::{Format, Media};

use super::http::Source;
use super::manager::{Kind, NewDownload};
use super::names;

/// The choice that saves a whole post.
pub const WHOLE_POST: &str = "post";

/// What saving `choice` from `media` takes, or `None` when there is no such choice (an answer that changed
/// since the window showed it) or it is not one the app can save yet. `can_join` and `streams` as for
/// `Lookup::from_media`.
pub fn plan(
    media: &Media,
    choice: &str,
    page_url: &str,
    folder: &Path,
    can_join: impl Fn(&Format) -> bool,
    streams: bool,
) -> Option<Vec<NewDownload>> {
    let base = |choice: String, kind, file_name, source| NewDownload {
        title: media.title.clone(),
        page_url: page_url.to_string(),
        choice,
        kind,
        file_name,
        folder: folder.to_path_buf(),
        source,
        sound: None,
        thumbnail: media.thumbnail_url.clone(),
        group: None,
    };

    if choice == WHOLE_POST {
        if media.items.is_empty() {
            return None;
        }
        let several = media.items.len() > 1;
        let plans = media.items.iter().enumerate().map(|(i, item)| {
            let n = (i + 1).to_string();
            let (kind, word) = if item.is_video { (Kind::Video, "Video") } else { (Kind::Picture, "Picture") };
            let name = names::file_name(&media.title, several.then_some(n.as_str()), &item.ext);
            let source = Source { url: item.url.clone(), headers: item.headers.clone(), ..Source::default() };
            NewDownload {
                thumbnail: if item.is_video { media.thumbnail_url.clone() } else { Some(item.url.clone()) },
                group: Some(page_url.to_string()),
                ..base(format!("{word} {n}"), kind, name, source)
            }
        });
        return Some(plans.collect());
    }

    let found = formats::choices(media, can_join).into_iter().find(|c| c.label == choice)?;
    if !can_save(&found, streams) {
        return None;
    }
    let ext = found.file.ext.clone().unwrap_or_else(|| if found.is_audio_only { "m4a".into() } else { "mp4".into() });
    let (kind, label) = match (found.is_audio_only, found.quality) {
        (true, _) => (Kind::Audio, None),
        (false, Some(_)) => (Kind::Video, Some(found.label.as_str())),
        (false, None) => (Kind::Video, None),
    };
    // Picture and sound joined are always an MP4: only MP4 pictures and M4A sound are paired (formats). A
    // stream, whatever its pieces are, is saved as an MP4, or an M4A for sound only.
    let ext = match (found.needs_merge() || found.file.is_savable_stream(), found.is_audio_only) {
        (true, true) => "m4a".to_string(),
        (true, false) => "mp4".to_string(),
        (false, _) => ext,
    };
    let name = names::file_name(&media.title, label, &ext);
    let source_of = |f: &Format| Source {
        url: f.url.clone(),
        headers: f.headers.clone(),
        expected_size: f.size_bytes,
        stream: f.is_savable_stream(),
        fragments: f.fragments.clone(),
    };
    let mut download = base(found.label.clone(), kind, name, source_of(&found.file));
    download.sound = found.audio_to_merge.as_ref().map(source_of);
    Some(vec![download])
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::media::{parse, PostItem};
    use std::path::PathBuf;

    const NEVER: fn(&Format) -> bool = |_| false;

    fn zoo() -> Media {
        parse(include_str!("../../../../app/src/test/resources/platform/youtube_me_at_the_zoo.json")).unwrap()
    }

    #[test]
    fn the_sound_is_saved_under_the_title_with_the_sites_headers() {
        let folder = PathBuf::from("C:/Users/me/Downloads");
        let plans = plan(&zoo(), "Audio only", "https://youtu.be/jNQXAC9IVRw", &folder, NEVER, false).unwrap();
        assert_eq!(plans.len(), 1);
        let p = &plans[0];
        assert_eq!(p.file_name, "Me at the zoo.m4a");
        assert_eq!(p.kind, Kind::Audio);
        assert_eq!(p.choice, "Audio only");
        assert!(p.source.url.ends_with("/140"));
        assert_eq!(p.source.headers.get("User-Agent").map(String::as_str), Some("Mozilla/5.0"));
        assert_eq!(p.folder, folder);
        assert_eq!(p.page_url, "https://youtu.be/jNQXAC9IVRw");
    }

    #[test]
    fn a_ready_made_quality_is_named_by_it() {
        let media = Media {
            title: "Clip".into(),
            formats: vec![Format {
                id: "22".into(),
                url: "https://cdn.example/22".into(),
                ext: Some("mp4".into()),
                width: Some(1280),
                height: Some(720),
                video_codec: Some("avc1".into()),
                audio_codec: Some("mp4a".into()),
                ..Format::default()
            }],
            ..Media::default()
        };
        let plans = plan(&media, "720p", "https://x.com/a/status/1", Path::new("D"), NEVER, false).unwrap();
        assert_eq!(plans[0].file_name, "Clip (720p).mp4");
        assert_eq!(plans[0].kind, Kind::Video);
    }

    #[test]
    fn a_choice_that_is_not_offered_or_needs_joining_plans_nothing() {
        assert!(plan(&zoo(), "1080p", "u", Path::new("D"), NEVER, false).is_none());
        // Without joining, the 240p that needs it is not offered.
        assert!(plan(&zoo(), "240p", "u", Path::new("D"), NEVER, false).is_none());
        assert!(plan(&zoo(), WHOLE_POST, "u", Path::new("D"), NEVER, false).is_none());
    }

    #[test]
    fn a_quality_whose_picture_and_sound_come_apart_fetches_both_for_joining() {
        let plans = plan(&zoo(), "240p", "https://youtu.be/jNQXAC9IVRw", Path::new("D"), |_| true, true).unwrap();
        let p = &plans[0];
        assert_eq!(p.file_name, "Me at the zoo (240p).mp4");
        assert_eq!(p.kind, Kind::Video);
        assert!(p.source.url.ends_with("/133"), "the H.264 picture: {}", p.source.url);
        let sound = p.sound.as_ref().expect("the sound to join");
        assert!(sound.url.ends_with("/140"));
        assert_eq!(sound.expected_size, Some(309_288));
        assert!(p.source.expected_size.is_some(), "sizes known, so progress counts both");
    }

    #[test]
    fn a_stream_is_saved_as_an_mp4_from_its_playlist() {
        let media = Media {
            title: "Clip".into(),
            formats: vec![Format {
                id: "hls-1080".into(),
                url: "https://video.example/1080/index.m3u8".into(),
                ext: Some("ts".into()),
                protocol: Some("m3u8_native".into()),
                height: Some(1080),
                ..Format::default()
            }],
            ..Media::default()
        };
        assert!(plan(&media, "1080p", "u", Path::new("D"), |_| true, false).is_none(), "not without streams");
        let plans = plan(&media, "1080p", "u", Path::new("D"), |_| true, true).unwrap();
        assert_eq!(plans[0].file_name, "Clip (1080p).mp4");
        assert!(plans[0].source.stream);
        assert!(plans[0].source.url.ends_with("/index.m3u8"));
        assert_eq!(plans[0].sound, None);
        assert!(plans[0].source.fragments.is_empty(), "its playlist lists its pieces");
    }

    #[test]
    fn a_dash_stream_is_saved_from_the_pieces_yt_dlp_listed() {
        let pieces = vec!["https://cdn.example/v/init.mp4".to_string(), "https://cdn.example/v/1.m4s".to_string()];
        let media = Media {
            title: "Clip".into(),
            formats: vec![Format {
                id: "v720".into(),
                url: "https://cdn.example/manifest.mpd".into(),
                ext: Some("mp4".into()),
                protocol: Some("http_dash_segments".into()),
                height: Some(720),
                fragments: pieces.clone(),
                ..Format::default()
            }],
            ..Media::default()
        };
        let plans = plan(&media, "720p", "u", Path::new("D"), |_| true, true).unwrap();
        assert_eq!(plans[0].file_name, "Clip (720p).mp4");
        assert!(plans[0].source.stream);
        assert_eq!(plans[0].source.fragments, pieces);
    }

    #[test]
    fn a_post_saves_every_picture_and_video_numbered_and_together() {
        let item = |url: &str, is_video, ext: &str| PostItem {
            url: url.into(),
            is_video,
            ext: ext.into(),
            width: None,
            height: None,
            headers: [("Referer".to_string(), "https://www.instagram.com/".to_string())].into(),
        };
        let media = Media {
            title: "Monsoon trip".into(),
            thumbnail_url: Some("https://cdn.example/poster.jpg".into()),
            items: vec![
                item("https://cdn.example/1.jpg", false, "jpg"),
                item("https://cdn.example/2.mp4", true, "mp4"),
            ],
            ..Media::default()
        };
        let plans = plan(&media, WHOLE_POST, "https://www.instagram.com/p/C1/", Path::new("D"), NEVER, false).unwrap();
        let names: Vec<&str> = plans.iter().map(|p| p.file_name.as_str()).collect();
        assert_eq!(names, ["Monsoon trip (1).jpg", "Monsoon trip (2).mp4"]);
        assert_eq!(plans.iter().map(|p| p.choice.as_str()).collect::<Vec<_>>(), ["Picture 1", "Video 2"]);
        assert_eq!(plans[0].kind, Kind::Picture);
        assert_eq!(plans[0].thumbnail.as_deref(), Some("https://cdn.example/1.jpg"));
        assert!(plans.iter().all(|p| p.group.as_deref() == Some("https://www.instagram.com/p/C1/")));
        assert_eq!(plans[1].source.headers.get("Referer").map(String::as_str), Some("https://www.instagram.com/"));

        let single = Media { items: vec![item("https://cdn.example/1.jpg", false, "jpg")], ..media };
        assert_eq!(
            plan(&single, WHOLE_POST, "u", Path::new("D"), NEVER, false).unwrap()[0].file_name,
            "Monsoon trip.jpg"
        );
    }
}
