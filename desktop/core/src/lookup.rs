//! What the window shows for a looked-up link: the video and its choices, a post's contents, or why not.

use serde::Serialize;

use crate::formats::{self, Choice};
use crate::media::{Failure, FailureKind, Media};

/// A lookup's result as the window receives it.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum Lookup {
    Found(Found),
    Failed {
        reason: FailureKind,
        message: &'static str,
        /// What the person's sign-in could do, when the site wants one (`SignIn`).
        #[serde(rename = "signIn", skip_serializing_if = "Option::is_none")]
        sign_in: Option<SignIn>,
    },
}

/// What the window offers when a site wants a sign-in, as the phone's link sheet does.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum SignIn {
    /// The built-in browser is signed in to the site: *Use my sign-in* looks the link up again with it.
    Use,
    /// It is not: *Sign in to {site}* opens the link in the browser, where the site asks for one.
    SignInFirst,
}

impl SignIn {
    /// What to offer after a lookup failed with `kind`: nothing unless the site wants a sign-in and this lookup
    /// did not already use the person's. `signed_in`: whether the browser is signed in to the site.
    pub fn after(kind: FailureKind, used_sign_in: bool, signed_in: bool) -> Option<SignIn> {
        if !matches!(kind, FailureKind::NeedsLogin | FailureKind::BotCheck) || used_sign_in {
            return None;
        }
        Some(if signed_in { SignIn::Use } else { SignIn::SignInFirst })
    }
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
    /// Whether better qualities exist that the app cannot save yet (picture and sound apart, or a stream it
    /// cannot save).
    #[serde(rename = "moreLater")]
    pub more_later: bool,
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
        Lookup::Failed { reason: kind, message: kind.message(), sign_in: None }
    }

    pub fn from_failure(failure: &Failure) -> Lookup {
        Lookup::failed(failure.kind)
    }

    /// A failed lookup of a link, with what the person's sign-in could do about it (`SignIn::after`).
    pub fn failed_with(failure: &Failure, sign_in: Option<SignIn>) -> Lookup {
        Lookup::Failed { reason: failure.kind, message: failure.kind.message(), sign_in }
    }

    /// `can_join` as for `formats::choices`: which picture-only tracks the app can join to sound. `streams`:
    /// whether it can save streams.
    pub fn from_media(media: &Media, can_join: impl Fn(&crate::media::Format) -> bool, streams: bool) -> Lookup {
        if media.is_live {
            return Lookup::failed(FailureKind::Live);
        }
        let post = (!media.items.is_empty()).then(|| PostView {
            pictures: media.items.iter().filter(|i| !i.is_video).count(),
            videos: media.items.iter().filter(|i| i.is_video).count(),
        });
        let (choices, more_later) = if post.is_some() {
            (Vec::new(), false)
        } else {
            let offered: Vec<ChoiceView> = formats::choices(media, can_join)
                .iter()
                .filter(|c| can_save(c, streams))
                .map(ChoiceView::from)
                .collect();
            // Every choice there is, if the app could join and save every stream: anything beyond what is offered
            // comes later.
            let more = formats::choices(media, |_| true)
                .iter()
                .filter(|c| !c.is_audio_only)
                .any(|c| !offered.iter().any(|o| o.label == c.label));
            (offered, more)
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
            more_later,
        })
    }
}

/// Whether the app can save `choice` yet: plain files, which it fetches as they are, and with `streams`, streams
/// whose pieces it can list (HLS, and DASH as yt-dlp listed them), which it fetches and ffmpeg copies into an
/// MP4.
pub fn can_save(choice: &Choice, streams: bool) -> bool {
    let savable = |f: &crate::media::Format| f.is_direct_file() || (streams && f.is_savable_stream());
    savable(&choice.file) && choice.audio_to_merge.as_ref().is_none_or(savable)
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
        let Lookup::Found(found) = Lookup::from_media(&zoo, is_h264, true) else { panic!("not found") };
        assert!(!found.more_later);
        let Lookup::Found(without_joining) = Lookup::from_media(&zoo, |_| false, false) else { panic!("not found") };
        assert_eq!(without_joining.choices.len(), 1, "only the sound");
        assert!(without_joining.more_later, "240p and 144p come once joining does");
        assert_eq!(found.title, "Me at the zoo");
        assert_eq!(found.author.as_deref(), Some("jawed"));
        assert_eq!(found.duration.as_deref(), Some("0:19"));
        assert_eq!(found.choices.iter().map(|c| c.label.as_str()).collect::<Vec<_>>(), ["240p", "144p", "Audio only"]);
        assert!(found.choices.last().unwrap().audio_only);
        assert_eq!(found.post, None);
    }

    #[test]
    fn a_stream_is_offered_when_the_app_can_save_streams_and_dash_comes_later() {
        let format = |id: &str, protocol: &str, height: i64| crate::media::Format {
            id: id.into(),
            url: format!("https://cdn.example/{id}"),
            ext: Some("mp4".into()),
            protocol: Some(protocol.into()),
            height: Some(height),
            ..Default::default()
        };
        let media = Media {
            title: "Clip".into(),
            formats: vec![
                format("dash-1080", "http_dash_segments", 1080),
                format("hls-720", "m3u8_native", 720),
                format("http-360", "https", 360),
            ],
            ..Media::default()
        };
        let Lookup::Found(found) = Lookup::from_media(&media, |_| true, true) else { panic!("not found") };
        assert_eq!(found.choices.iter().map(|c| c.label.as_str()).collect::<Vec<_>>(), ["720p", "360p"]);
        assert!(found.more_later, "1080p is a DASH stream without its pieces listed");

        let Lookup::Found(without) = Lookup::from_media(&media, |_| false, false) else { panic!("not found") };
        assert_eq!(without.choices.iter().map(|c| c.label.as_str()).collect::<Vec<_>>(), ["360p"]);
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
        let Lookup::Found(found) = Lookup::from_media(&post, is_h264, true) else { panic!("not found") };
        assert_eq!(found.post, Some(PostView { pictures: 2, videos: 1 }));
        assert!(found.choices.is_empty());
    }

    #[test]
    fn a_live_broadcast_or_nothing_savable_is_explained() {
        let live = Media { title: "Live".into(), is_live: true, ..Media::default() };
        assert_eq!(Lookup::from_media(&live, is_h264, true), Lookup::failed(FailureKind::Live));
        let empty = Media { title: "Nothing".into(), ..Media::default() };
        assert_eq!(Lookup::from_media(&empty, is_h264, true), Lookup::failed(FailureKind::NoFormat));
    }

    #[test]
    fn the_window_receives_a_kind_and_a_message() {
        let json = serde_json::to_value(Lookup::failed(FailureKind::Private)).unwrap();
        assert_eq!(
            json,
            serde_json::json!({"kind": "failed", "reason": "private", "message": "This video is private."})
        );
        let login = Failure { kind: FailureKind::NeedsLogin, detail: None };
        let json = serde_json::to_value(Lookup::failed_with(&login, Some(SignIn::SignInFirst))).unwrap();
        assert_eq!(json["signIn"], "signInFirst");
        let zoo = parse(include_str!("../../../app/src/test/resources/platform/youtube_me_at_the_zoo.json")).unwrap();
        let json = serde_json::to_value(Lookup::from_media(&zoo, is_h264, true)).unwrap();
        assert_eq!(json["kind"], "found");
        assert_eq!(
            json["choices"][2],
            serde_json::json!({"label": "Audio only", "detail": "M4A · ≈ 302.0 KB", "audioOnly": true})
        );
    }

    #[test]
    fn a_sign_in_is_offered_only_when_the_site_wants_one_and_it_was_not_used() {
        use FailureKind::*;
        assert_eq!(SignIn::after(NeedsLogin, false, true), Some(SignIn::Use));
        assert_eq!(SignIn::after(BotCheck, false, true), Some(SignIn::Use));
        assert_eq!(SignIn::after(NeedsLogin, false, false), Some(SignIn::SignInFirst));
        assert_eq!(SignIn::after(NeedsLogin, true, true), None, "it was used, and was not enough");
        for kind in [Private, Geo, Unavailable, Network, Engine, Unknown] {
            assert_eq!(SignIn::after(kind, false, true), None, "{kind:?}");
        }
    }

    #[test]
    fn durations_read_like_a_players() {
        assert_eq!(duration(19.0), "0:19");
        assert_eq!(duration(213.4), "3:33");
        assert_eq!(duration(3725.0), "1:02:05");
    }
}
