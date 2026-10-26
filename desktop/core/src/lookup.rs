//! What the window shows for a looked-up link: the video and its choices, a post's contents, or why not.

use serde::Serialize;

use crate::formats::{self, Choice};
use crate::media::{Failure, FailureKind, Media};

/// A lookup's result as the window receives it.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Lookup {
    Found(Found),
    Failed { reason: FailureKind, message: &'static str },
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Found {
    pub title: String,
    pub author: Option<String>,
    /// "3:33", "1:02:05".
    pub duration: Option<String>,
    pub thumbnail: Option<String>,
    /// Qualities to choose from, best first, ending with audio only. Empty for a post.
    pub choices: Vec<ChoiceView>,
    /// For a post of pictures (and maybe videos), what is in it; each is saved as it is.
    pub post: Option<PostView>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ChoiceView {
    pub label: String,
    pub detail: String,
    #[serde(rename = "audioOnly")]
    pub audio_only: bool,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct PostView {
    pub pictures: usize,
    pub videos: usize,
}

impl Lookup {
    pub fn failed(kind: FailureKind) -> Lookup {
        Lookup::Failed { reason: kind, message: kind.message() }
    }

    pub fn from_failure(failure: &Failure) -> Lookup {
        Lookup::failed(failure.kind)
    }

    /// `can_join` as for `formats::choices`: which picture-only tracks the app can join to sound.
    pub fn from_media(media: &Media, can_join: impl Fn(&crate::media::Format) -> bool) -> Lookup {
        if media.is_live {
            return Lookup::failed(FailureKind::Live);
        }
        let post = (!media.items.is_empty()).then(|| PostView {
            pictures: media.items.iter().filter(|i| !i.is_video).count(),
            videos: media.items.iter().filter(|i| i.is_video).count(),
        });
        let choices: Vec<ChoiceView> = if post.is_some() {
            Vec::new()
        } else {
            formats::choices(media, can_join).iter().map(ChoiceView::from).collect()
        };
        if post.is_none() && choices.is_empty() {
            return Lookup::failed(FailureKind::NoFormat);
        }
        Lookup::Found(Found {
            title: media.title.clone(),
            author: media.author.clone(),
            duration: media.duration_seconds.map(duration),
            thumbnail: media.thumbnail_url.clone(),
            choices,
            post,
        })
    }
}

impl From<&Choice> for ChoiceView {
    fn from(choice: &Choice) -> ChoiceView {
        ChoiceView { label: choice.label.clone(), detail: choice.detail.clone(), audio_only: choice.is_audio_only }
    }
}

/// "3:33" or "1:02:05".
pub fn duration(seconds: f64) -> String {
    let total = seconds.round() as u64;
    let (h, m, s) = (total / 3600, total / 60 % 60, total % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::formats::is_h264;
    use crate::media::{parse, PostItem};

    #[test]
    fn a_video_shows_its_facts_and_choices() {
        let zoo = parse(include_str!("../../../app/src/test/resources/platform/youtube_me_at_the_zoo.json")).unwrap();
        let Lookup::Found(found) = Lookup::from_media(&zoo, is_h264) else { panic!("not found") };
        assert_eq!(found.title, "Me at the zoo");
        assert_eq!(found.author.as_deref(), Some("jawed"));
        assert_eq!(found.duration.as_deref(), Some("0:19"));
        assert_eq!(found.choices.iter().map(|c| c.label.as_str()).collect::<Vec<_>>(), ["240p", "144p", "Audio only"]);
        assert!(found.choices.last().unwrap().audio_only);
        assert_eq!(found.post, None);
    }

    #[test]
    fn a_post_counts_its_pictures_and_videos() {
        let item = |is_video| PostItem {
            url: "https://cdn.example/x".into(),
            is_video,
            ext: "jpg".into(),
            width: None,
            height: None,
            headers: Default::default(),
        };
        let post =
            Media { title: "A post".into(), items: vec![item(false), item(false), item(true)], ..Media::default() };
        let Lookup::Found(found) = Lookup::from_media(&post, is_h264) else { panic!("not found") };
        assert_eq!(found.post, Some(PostView { pictures: 2, videos: 1 }));
        assert!(found.choices.is_empty());
    }

    #[test]
    fn a_live_broadcast_or_nothing_savable_is_explained() {
        let live = Media { title: "Live".into(), is_live: true, ..Media::default() };
        assert_eq!(Lookup::from_media(&live, is_h264), Lookup::failed(FailureKind::Live));
        let empty = Media { title: "Nothing".into(), ..Media::default() };
        assert_eq!(Lookup::from_media(&empty, is_h264), Lookup::failed(FailureKind::NoFormat));
    }

    #[test]
    fn the_window_receives_a_kind_and_a_message() {
        let json = serde_json::to_value(Lookup::failed(FailureKind::Private)).unwrap();
        assert_eq!(
            json,
            serde_json::json!({"kind": "failed", "reason": "private", "message": "This video is private."})
        );
        let zoo = parse(include_str!("../../../app/src/test/resources/platform/youtube_me_at_the_zoo.json")).unwrap();
        let json = serde_json::to_value(Lookup::from_media(&zoo, is_h264)).unwrap();
        assert_eq!(json["kind"], "found");
        assert_eq!(
            json["choices"][2],
            serde_json::json!({"label": "Audio only", "detail": "M4A · ≈ 302.0 KB", "audioOnly": true})
        );
    }

    #[test]
    fn durations_read_like_a_players() {
        assert_eq!(duration(19.0), "0:19");
        assert_eq!(duration(213.4), "3:33");
        assert_eq!(duration(3725.0), "1:02:05");
    }
}
