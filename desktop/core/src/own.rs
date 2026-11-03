//! The person's own videos and sounds: files already on this PC (a meeting, an interview) that they drop on Chaya's
//! window or pick with *Add from this PC*. Chaya lists each where it is, as a finished entry in the Downloads list
//! (`download::manager::Manager::add_own`), so the player, making subtitles, translation, highlights, clips and export
//! work on it as on a download. The file is never copied, moved or changed, and taking it off the list keeps it.

use std::path::{Path, PathBuf};

use crate::download::manager::{Kind, Manager, Task};

/// What Chaya takes as a video, by the file's extension: what WebView2 plays, and what ffmpeg reads for the rest.
pub const VIDEOS: &[&str] = &["mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv"];
/// What Chaya takes as a sound.
pub const SOUNDS: &[&str] = &["mp3", "m4a", "aac", "wav", "flac", "ogg", "opus"];

/// Why a file was not added.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refused {
    /// Nothing is there (moved, deleted, or a drive that is gone).
    Missing,
    /// A folder, not a file.
    Folder,
    /// A file, but not a video or a sound by its name.
    NotMedia,
}

impl Refused {
    /// Why, in words, naming the file.
    pub fn message(self, name: &str) -> String {
        match self {
            Refused::Missing => format!("{name} isn't there any more."),
            Refused::Folder => format!("{name} is a folder. Drop the videos in it instead."),
            Refused::NotMedia => format!("{name} isn't a video or a sound Chaya can open."),
        }
    }
}

/// A video or a sound, by the extension of `path`'s name; None for anything else.
pub fn kind_of(path: &Path) -> Option<Kind> {
    let ext = path.extension()?.to_str()?.to_ascii_lowercase();
    if VIDEOS.contains(&ext.as_str()) {
        Some(Kind::Video)
    } else if SOUNDS.contains(&ext.as_str()) {
        Some(Kind::Audio)
    } else {
        None
    }
}

/// What the file at `path` is, when Chaya can list it: an existing file that is a video or a sound.
pub fn check(path: &Path) -> Result<Kind, Refused> {
    let meta = std::fs::metadata(path).map_err(|_| Refused::Missing)?;
    if meta.is_dir() {
        return Err(Refused::Folder);
    }
    kind_of(path).ok_or(Refused::NotMedia)
}

/// The name the list shows for the file at `path`: its own, without the extension ("Team meeting" for
/// "Team meeting.mp4").
pub fn title_of(path: &Path) -> String {
    let stem = path.file_stem().map(|s| s.to_string_lossy().trim().to_string()).unwrap_or_default();
    if stem.is_empty() {
        path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
    } else {
        stem
    }
}

/// The file's name, as a refusal names it.
pub fn name_of(path: &Path) -> String {
    path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| path.display().to_string())
}

/// Whether `a` and `b` are the same file: the same path, or, for two paths ending in the same name, the same once
/// Windows has said where each really is (another case, a folder reached another way). Paths whose names differ are
/// told apart without asking Windows, so checking a file against a long list stays quick.
pub fn same_file(a: &Path, b: &Path) -> bool {
    if a == b {
        return true;
    }
    let names = (a.file_name().and_then(|n| n.to_str()), b.file_name().and_then(|n| n.to_str()));
    if !matches!(names, (Some(x), Some(y)) if x.eq_ignore_ascii_case(y)) {
        return false;
    }
    match (std::fs::canonicalize(a), std::fs::canonicalize(b)) {
        (Ok(a), Ok(b)) => a == b,
        _ => false,
    }
}

/// What came of adding files: each one listed (new, or the entry it already had), in the order given and once each;
/// the numbers of those already listed; and why each of the rest was not added, in words.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Added {
    pub tasks: Vec<Task>,
    pub already: Vec<u64>,
    pub refused: Vec<String>,
}

/// Lists each of `paths` that is a video or a sound in `manager`, where it is (`Manager::add_own`).
pub fn add_all(manager: &Manager, paths: &[PathBuf]) -> Added {
    let mut added = Added::default();
    for path in paths {
        match check(path) {
            Ok(kind) => {
                let (task, new) = manager.add_own(path, title_of(path), kind);
                if added.tasks.iter().any(|t| t.id == task.id) {
                    continue;
                }
                if !new {
                    added.already.push(task.id);
                }
                added.tasks.push(task);
            }
            Err(why) => added.refused.push(why.message(&name_of(path))),
        }
    }
    added
}

#[cfg(test)]
mod tests {
    use super::*;
    use reqwest::Client;
    use tokio::runtime::Handle;

    #[tokio::test(flavor = "multi_thread")]
    async fn files_dropped_together_are_listed_or_refused_each_with_its_reason() {
        let dir = tempfile::tempdir().unwrap();
        let manager = Manager::open(
            dir.path().join("data").join("downloads.json"),
            Client::new(),
            Handle::current(),
            Box::new(|_| {}),
        );
        let at = |name: &str| dir.path().join(name);
        for name in ["Talk.mp4", "Podcast.mp3", "Notes.txt"] {
            std::fs::write(at(name), name).unwrap();
        }
        let (known, _) = manager.add_own(&at("Talk.mp4"), "Talk".into(), Kind::Video);

        let added =
            add_all(&manager, &[at("Talk.mp4"), at("Podcast.mp3"), at("Notes.txt"), at("Gone.mkv"), at("Podcast.mp3")]);

        let listed: Vec<(&str, Kind)> = added.tasks.iter().map(|t| (t.title.as_str(), t.kind)).collect();
        assert_eq!(listed, [("Talk", Kind::Video), ("Podcast", Kind::Audio)]);
        assert_eq!(added.already, [known.id]);
        assert_eq!(
            added.refused,
            ["Notes.txt isn't a video or a sound Chaya can open.", "Gone.mkv isn't there any more."]
        );
        assert_eq!(manager.list().len(), 2);
    }

    #[test]
    fn videos_and_sounds_are_known_by_their_extension_in_any_case() {
        for name in ["a.mp4", "a.M4V", "a.mov", "a.mkv", "a.webm", "a.avi", "a.WMV"] {
            assert_eq!(kind_of(Path::new(name)), Some(Kind::Video), "{name}");
        }
        for name in ["a.mp3", "a.m4a", "a.aac", "a.wav", "a.FLAC", "a.ogg", "a.opus"] {
            assert_eq!(kind_of(Path::new(name)), Some(Kind::Audio), "{name}");
        }
        for name in ["a.txt", "a.srt", "a.jpg", "a.exe", "mp4", "a.mp4.part", ""] {
            assert_eq!(kind_of(Path::new(name)), None, "{name}");
        }
    }

    #[test]
    fn a_file_is_checked_for_being_there_a_file_and_a_video_or_sound() {
        let dir = tempfile::tempdir().unwrap();
        let video = dir.path().join("Meeting.MP4");
        let notes = dir.path().join("Notes.txt");
        let folder = dir.path().join("Holiday.mp4");
        std::fs::write(&video, b"video").unwrap();
        std::fs::write(&notes, b"notes").unwrap();
        std::fs::create_dir(&folder).unwrap();

        assert_eq!(check(&video), Ok(Kind::Video));
        assert_eq!(check(&notes), Err(Refused::NotMedia));
        assert_eq!(check(&folder), Err(Refused::Folder));
        assert_eq!(check(&dir.path().join("Gone.mp4")), Err(Refused::Missing));
        assert_eq!(Refused::NotMedia.message("Notes.txt"), "Notes.txt isn't a video or a sound Chaya can open.");
    }

    #[test]
    fn the_title_is_the_files_name_without_its_extension() {
        assert_eq!(title_of(Path::new("C:/Videos/Team meeting.mp4")), "Team meeting");
        assert_eq!(title_of(Path::new("C:/Videos/Interview.final.mkv")), "Interview.final");
        assert_eq!(name_of(Path::new("C:/Videos/Team meeting.mp4")), "Team meeting.mp4");
    }

    #[test]
    fn the_same_file_is_known_by_another_spelling_of_its_path() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir(dir.path().join("Videos")).unwrap();
        let video = dir.path().join("Videos").join("Talk.mp4");
        std::fs::write(&video, b"video").unwrap();
        let roundabout = dir.path().join("Videos").join("..").join("Videos").join("Talk.mp4");

        assert!(same_file(&video, &video));
        assert!(same_file(&video, &roundabout));
        assert!(!same_file(&video, &dir.path().join("Videos").join("Other.mp4")));
    }
}
