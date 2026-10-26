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
/// since the window showed it) or it is not one the app can save yet.
pub fn plan(
    media: &Media,
    choice: &str,
    page_url: &str,
    folder: &Path,
    can_join: impl Fn(&Format) -> bool,
) -> Option<Vec<NewDownload>> {
    let base = |choice: String, kind, file_name, source| NewDownload {
        title: media.title.clone(),
        page_url: page_url.to_string(),
        choice,
        kind,
        file_name,
        folder: folder.to_path_buf(),
        source,
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
            let source = Source { url: item.url.clone(), headers: item.headers.clone() };
            NewDownload {
                thumbnail: if item.is_video { media.thumbnail_url.clone() } else { Some(item.url.clone()) },
                group: Some(page_url.to_string()),
                ..base(format!("{word} {n}"), kind, name, source)
            }
        });
        return Some(plans.collect());
    }

    let found = formats::choices(media, can_join).into_iter().find(|c| c.label == choice)?;
    // Joining picture and sound comes with the next part of downloads.
    if !can_save(&found) || found.needs_merge() {
        return None;
    }
    let ext = found.file.ext.clone().unwrap_or_else(|| if found.is_audio_only { "m4a".into() } else { "mp4".into() });
    let (kind, label) = match (found.is_audio_only, found.quality) {
        (true, _) => (Kind::Audio, None),
        (false, Some(_)) => (Kind::Video, Some(found.label.as_str())),
        (false, None) => (Kind::Video, None),
    };
    let name = names::file_name(&media.title, label, &ext);
    let source = Source { url: found.file.url.clone(), headers: found.file.headers.clone() };
    Some(vec![base(found.label.clone(), kind, name, source)])
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
        let plans = plan(&zoo(), "Audio only", "https://youtu.be/jNQXAC9IVRw", &folder, NEVER).unwrap();
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
        let plans = plan(&media, "720p", "https://x.com/a/status/1", Path::new("D"), NEVER).unwrap();
        assert_eq!(plans[0].file_name, "Clip (720p).mp4");
        assert_eq!(plans[0].kind, Kind::Video);
    }

    #[test]
    fn a_choice_that_is_not_offered_or_needs_joining_plans_nothing() {
        assert!(plan(&zoo(), "1080p", "u", Path::new("D"), NEVER).is_none());
        // With joining allowed the 240p choice exists, but joining is not here yet.
        assert!(plan(&zoo(), "240p", "u", Path::new("D"), |_| true).is_none());
        assert!(plan(&zoo(), WHOLE_POST, "u", Path::new("D"), NEVER).is_none());
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
        let plans = plan(&media, WHOLE_POST, "https://www.instagram.com/p/C1/", Path::new("D"), NEVER).unwrap();
        let names: Vec<&str> = plans.iter().map(|p| p.file_name.as_str()).collect();
        assert_eq!(names, ["Monsoon trip (1).jpg", "Monsoon trip (2).mp4"]);
        assert_eq!(plans.iter().map(|p| p.choice.as_str()).collect::<Vec<_>>(), ["Picture 1", "Video 2"]);
        assert_eq!(plans[0].kind, Kind::Picture);
        assert_eq!(plans[0].thumbnail.as_deref(), Some("https://cdn.example/1.jpg"));
        assert!(plans.iter().all(|p| p.group.as_deref() == Some("https://www.instagram.com/p/C1/")));
        assert_eq!(plans[1].source.headers.get("Referer").map(String::as_str), Some("https://www.instagram.com/"));

        let single = Media { items: vec![item("https://cdn.example/1.jpg", false, "jpg")], ..media };
        assert_eq!(plan(&single, WHOLE_POST, "u", Path::new("D"), NEVER).unwrap()[0].file_name, "Monsoon trip.jpg");
    }
}
