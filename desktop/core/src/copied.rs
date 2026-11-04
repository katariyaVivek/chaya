//! The key that saves the link just copied, from anywhere in Windows: off until the person turns it on (Settings ›
//! Copied links), then Ctrl+Alt+D reads what was copied, finds a link to a video or post in it, and saves it at the
//! quality the person chose, without Chaya in front. What was copied is read only when the key is pressed, and only
//! the link in it is looked up, as a link pasted into the address bar would be.

use std::path::Path;

use serde::{Deserialize, Serialize};

use crate::download::plan::WHOLE_POST;
use crate::formats;
use crate::links;
use crate::lookup::can_save;
use crate::media::{Format, Media};

/// The key, as Windows names it and as the window shows it.
pub const KEY: &str = "Ctrl+Alt+D";
/// The setting's file in Chaya's data folder.
const FILE: &str = "copied-link.json";
/// What was copied is looked through this far for a link: a page of text copied by mistake is not read whole.
const LONGEST: usize = 10_000;

/// The quality the key saves: the best there is, the best up to 1080p or 720p, or the sound alone.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Wanted {
    #[default]
    Best,
    UpTo1080,
    UpTo720,
    Sound,
}

/// Whether the key is on, and what it saves.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Settings {
    #[serde(default)]
    pub on: bool,
    #[serde(default)]
    pub quality: Wanted,
}

impl Settings {
    /// The setting kept in `folder`; off when there is none or it can't be read.
    pub fn load(folder: &Path) -> Settings {
        std::fs::read(folder.join(FILE)).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
    }

    pub fn save(&self, folder: &Path) -> std::io::Result<()> {
        std::fs::create_dir_all(folder)?;
        std::fs::write(folder.join(FILE), serde_json::to_vec_pretty(self)?)
    }
}

/// The first link to a video or post in what was copied (`links::match_media`): a link alone, or one in a sentence,
/// in brackets or quotes. `None` when there is none.
pub fn link_in(copied: &str) -> Option<String> {
    let end = copied.char_indices().nth(LONGEST).map_or(copied.len(), |(i, _)| i);
    copied[..end]
        .split_whitespace()
        .map(|word| {
            word.trim_start_matches(|c: char| "<\"'([{«“‘".contains(c))
                .trim_end_matches(|c: char| ">\"')]}»”’,;:.!?".contains(c))
        })
        .find_map(|word| links::match_media(word).map(|link| link.url))
}

/// The choice the key saves of `media` (a label `download::plan::plan` takes), at `wanted`: a post with pictures in
/// it whole; else the best quality the app can save, or the best up to 1080p or 720p (the smallest there is when all
/// are larger), or the sound alone. A video with no picture to choose falls back to its sound, and asked for sound,
/// one without sound alone to nothing: then the person chooses. `can_join` and `streams` as for `Lookup::from_media`.
pub fn choose(media: &Media, wanted: Wanted, can_join: impl Fn(&Format) -> bool, streams: bool) -> Option<String> {
    if !media.items.is_empty() {
        return Some(WHOLE_POST.to_string());
    }
    let offered: Vec<formats::Choice> =
        formats::choices(media, can_join).into_iter().filter(|c| can_save(c, streams)).collect();
    let sound = offered.iter().find(|c| c.is_audio_only);
    let pictures: Vec<&formats::Choice> = offered.iter().filter(|c| !c.is_audio_only).collect();
    let most = match wanted {
        Wanted::Sound => return sound.map(|c| c.label.clone()),
        Wanted::Best => None,
        Wanted::UpTo1080 => Some(1080),
        Wanted::UpTo720 => Some(720),
    };
    let picked = match most {
        None => pictures.first().copied(),
        Some(most) => {
            pictures.iter().find(|c| c.quality.is_none_or(|q| q <= most)).or_else(|| pictures.last()).copied()
        }
    };
    picked.or(sound).map(|c| c.label.clone())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::media::parse;

    /// A video in 1080p, 720p and 360p, each a whole file, and its sound alone.
    fn video() -> Media {
        let format = |id: &str, height: Option<u32>, vcodec: &str, acodec: &str| {
            format!(
                r#"{{"format_id": "{id}", "url": "https://cdn.example/{id}", "ext": "mp4", "protocol": "https",
                "width": {w}, "height": {h}, "vcodec": "{vcodec}", "acodec": "{acodec}", "tbr": 1000}}"#,
                w = height.map_or("null".into(), |h| (h * 16 / 9).to_string()),
                h = height.map_or("null".into(), |h| h.to_string()),
            )
        };
        let formats = [
            format("360", Some(360), "avc1", "mp4a"),
            format("720", Some(720), "avc1", "mp4a"),
            format("1080", Some(1080), "avc1", "mp4a"),
            format("140", None, "none", "mp4a.40.2"),
        ];
        parse(&format!(r#"{{"media": {{"title": "A walk", "formats": [{}]}}}}"#, formats.join(","))).unwrap()
    }

    const NONE: fn(&Format) -> bool = |_| false;

    #[test]
    fn the_link_is_found_in_what_was_copied() {
        assert_eq!(link_in("https://youtu.be/dQw4w9WgXcQ").as_deref(), Some("https://youtu.be/dQw4w9WgXcQ"));
        assert_eq!(
            link_in("  Look at this (https://www.youtube.com/watch?v=dQw4w9WgXcQ). It's good!\n").as_deref(),
            Some("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        );
        assert_eq!(
            link_in("<https://www.tiktok.com/@a/video/7234567890123456789>").as_deref(),
            Some("https://www.tiktok.com/@a/video/7234567890123456789")
        );
        assert_eq!(
            link_in("https://example.com/page and https://youtu.be/abc").as_deref(),
            Some("https://youtu.be/abc")
        );
        assert_eq!(link_in("https://www.youtube.com/@channel"), None, "a channel is not a video");
        assert_eq!(link_in("just some words"), None);
        assert_eq!(link_in(""), None);
        let far = format!("{} https://youtu.be/abc", "word ".repeat(LONGEST));
        assert_eq!(link_in(&far), None, "a link past the first ten thousand characters is not looked for");
    }

    #[test]
    fn the_quality_saved_is_the_one_chosen_or_the_nearest_below() {
        let media = video();
        assert_eq!(choose(&media, Wanted::Best, NONE, false).as_deref(), Some("1080p"));
        assert_eq!(choose(&media, Wanted::UpTo1080, NONE, false).as_deref(), Some("1080p"));
        assert_eq!(choose(&media, Wanted::UpTo720, NONE, false).as_deref(), Some("720p"));
        assert_eq!(choose(&media, Wanted::Sound, NONE, false).as_deref(), Some("Audio only"));
    }

    #[test]
    fn when_every_quality_is_larger_the_smallest_is_saved_and_without_pictures_the_sound() {
        let mut media = video();
        media.formats.retain(|f| f.id == "1080" || f.id == "140");
        assert_eq!(choose(&media, Wanted::UpTo720, NONE, false).as_deref(), Some("1080p"));
        media.formats.retain(|f| f.id == "140");
        assert_eq!(choose(&media, Wanted::Best, NONE, false).as_deref(), Some("Audio only"));
        let mut silent = video();
        silent.formats.retain(|f| f.id != "140");
        assert_eq!(choose(&silent, Wanted::Sound, NONE, false), None, "no sound alone: the person chooses");
    }

    #[test]
    fn a_post_with_pictures_is_saved_whole() {
        let post = parse(
            r#"{"media": {"title": "A post", "items": [
                {"url": "https://cdn.example/1.jpg", "kind": "image", "ext": "jpg"},
                {"url": "https://cdn.example/2.mp4", "kind": "video", "ext": "mp4"}]}}"#,
        )
        .unwrap();
        assert!(!post.items.is_empty(), "the fixture is read as a post");
        assert_eq!(choose(&post, Wanted::Sound, NONE, false).as_deref(), Some(WHOLE_POST));
    }

    #[test]
    fn the_setting_is_off_until_turned_on_and_kept() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(Settings::load(dir.path()), Settings { on: false, quality: Wanted::Best });
        let on = Settings { on: true, quality: Wanted::UpTo720 };
        on.save(dir.path()).unwrap();
        assert_eq!(Settings::load(dir.path()), on);
        std::fs::write(dir.path().join(FILE), "not json").unwrap();
        assert_eq!(Settings::load(dir.path()), Settings::default(), "a broken file is off");
        assert_eq!(serde_json::to_string(&Wanted::UpTo1080).unwrap(), r#""upTo1080""#);
    }
}
