//! What the window's `chaya-file` scheme answers for a saved file: the whole of a small one, or the part a player
//! asks for with an HTTP range, so a video seeks without being read whole. Never more than `CHUNK` at once: a player
//! asking for everything from a point gets the next part, says so in `Content-Range`, and asks again.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

/// The most one answer holds.
pub const CHUNK: u64 = 4 * 1024 * 1024;

/// An answer: its status, its headers, and its bytes.
#[derive(Debug, PartialEq, Eq)]
pub struct Answer {
    pub status: u16,
    pub headers: Vec<(&'static str, String)>,
    pub body: Vec<u8>,
}

/// The bytes asked for by `range` (an HTTP Range header: "bytes=100-", "bytes=0-499", "bytes=-500") of a file
/// `len` bytes long: the first and last byte, or None when no byte of it is in the file. Of several ranges, the
/// first; one that runs past the end stops at it.
pub fn range(range: &str, len: u64) -> Option<(u64, u64)> {
    let spec = range.trim().strip_prefix("bytes=")?.split(',').next()?.trim();
    let (first, last) = spec.split_once('-')?;
    let number = |s: &str| s.trim().parse::<u64>().ok();
    let (first, last) = match (first.trim().is_empty(), last.trim().is_empty()) {
        // The last so many bytes.
        (true, false) => {
            let n = number(last)?.min(len);
            (len.checked_sub(n)?, len.checked_sub(1)?)
        }
        (false, true) => (number(first)?, len.checked_sub(1)?),
        (false, false) => (number(first)?, number(last)?.min(len.checked_sub(1)?)),
        (true, true) => return None,
    };
    (first <= last && first < len).then_some((first, last))
}

/// The answer for `path`, of type `kind`, to a request with the Range header `asked` (or none).
pub fn answer(path: &Path, kind: &str, asked: Option<&str>) -> Answer {
    let empty = |status| Answer { status, headers: Vec::new(), body: Vec::new() };
    let Ok(mut file) = File::open(path) else { return empty(404) };
    let Ok(len) = file.metadata().map(|m| m.len()) else { return empty(404) };
    let mut headers = vec![("Content-Type", kind.to_string()), ("Accept-Ranges", "bytes".to_string())];
    let wanted = match asked {
        Some(asked) => match range(asked, len) {
            Some(wanted) => Some(wanted),
            None => {
                headers.push(("Content-Range", format!("bytes */{len}")));
                return Answer { status: 416, headers, body: Vec::new() };
            }
        },
        // Asked for whole: given whole when small, else the first part, as if that had been asked for.
        None if len <= CHUNK => None,
        None => Some((0, len - 1)),
    };
    let (first, last) = match wanted {
        Some((first, last)) => (first, last.min(first + CHUNK - 1)),
        None => (0, len.saturating_sub(1)),
    };
    let size = if len == 0 { 0 } else { last - first + 1 };
    let mut body = vec![0; size as usize];
    if file.seek(SeekFrom::Start(first)).and_then(|_| file.read_exact(&mut body)).is_err() {
        return empty(500);
    }
    headers.push(("Content-Length", size.to_string()));
    if wanted.is_none() {
        return Answer { status: 200, headers, body };
    }
    headers.push(("Content-Range", format!("bytes {first}-{last}/{len}")));
    Answer { status: 206, headers, body }
}

/// The type a saved file named `file_name` is played as: video and sound only.
pub fn media_type(file_name: &str) -> Option<&'static str> {
    let ext = file_name.rsplit_once('.')?.1.to_ascii_lowercase();
    Some(match ext.as_str() {
        "mp4" | "m4v" => "video/mp4",
        "webm" => "video/webm",
        "mov" => "video/quicktime",
        "mkv" => "video/x-matroska",
        "m4a" => "audio/mp4",
        "mp3" => "audio/mpeg",
        "ogg" | "opus" => "audio/ogg",
        "wav" => "audio/wav",
        "flac" => "audio/flac",
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ranges_are_read_as_players_send_them() {
        assert_eq!(range("bytes=0-", 1000), Some((0, 999)));
        assert_eq!(range("bytes=100-199", 1000), Some((100, 199)));
        assert_eq!(range("bytes=900-5000", 1000), Some((900, 999)));
        assert_eq!(range("bytes=-100", 1000), Some((900, 999)));
        assert_eq!(range("bytes=-5000", 1000), Some((0, 999)));
        assert_eq!(range(" bytes=10-19, 30-39", 1000), Some((10, 19)));
        assert_eq!(range("bytes=1000-", 1000), None, "past the end");
        assert_eq!(range("bytes=20-10", 1000), None);
        assert_eq!(range("bytes=-", 1000), None);
        assert_eq!(range("bytes=0-", 0), None);
        assert_eq!(range("items=0-1", 1000), None);
        assert_eq!(range("bytes=a-b", 1000), None);
    }

    fn file(len: usize) -> (tempfile::TempDir, std::path::PathBuf, Vec<u8>) {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("Clip.mp4");
        let bytes: Vec<u8> = (0..len).map(|i| (i % 251) as u8).collect();
        std::fs::write(&path, &bytes).unwrap();
        (dir, path, bytes)
    }

    fn header<'a>(answer: &'a Answer, name: &str) -> Option<&'a str> {
        answer.headers.iter().find(|(n, _)| *n == name).map(|(_, v)| v.as_str())
    }

    #[test]
    fn a_range_is_answered_with_its_bytes_and_where_they_are() {
        let (_dir, path, bytes) = file(10_000);
        let answer = answer(&path, "video/mp4", Some("bytes=1000-1999"));
        assert_eq!(answer.status, 206);
        assert_eq!(answer.body, &bytes[1000..2000]);
        assert_eq!(header(&answer, "Content-Range"), Some("bytes 1000-1999/10000"));
        assert_eq!(header(&answer, "Content-Length"), Some("1000"));
        assert_eq!(header(&answer, "Content-Type"), Some("video/mp4"));
        assert_eq!(header(&answer, "Accept-Ranges"), Some("bytes"));
    }

    #[test]
    fn everything_from_a_point_comes_a_part_at_a_time() {
        let (_dir, path, bytes) = file(CHUNK as usize * 2 + 10);
        let len = bytes.len();
        let first = answer(&path, "video/mp4", Some("bytes=5-"));
        assert_eq!(first.status, 206);
        assert_eq!(first.body.len() as u64, CHUNK);
        assert_eq!(header(&first, "Content-Range"), Some(format!("bytes 5-{}/{len}", CHUNK + 4).as_str()));
        let last = answer(&path, "video/mp4", Some(&format!("bytes={}-", CHUNK * 2)));
        assert_eq!(last.body, &bytes[CHUNK as usize * 2..]);
        // Asked for whole, a big file is answered as if its first part had been asked for.
        let whole = answer(&path, "video/mp4", None);
        assert_eq!(whole.status, 206);
        assert_eq!(header(&whole, "Content-Range"), Some(format!("bytes 0-{}/{len}", CHUNK - 1).as_str()));
    }

    #[test]
    fn a_small_file_asked_for_whole_comes_whole() {
        let (_dir, path, bytes) = file(300);
        let answer = answer(&path, "audio/mp4", None);
        assert_eq!(answer.status, 200);
        assert_eq!(answer.body, bytes);
        assert_eq!(header(&answer, "Content-Range"), None);
    }

    #[test]
    fn a_range_outside_the_file_or_a_missing_file_says_so() {
        let (_dir, path, _) = file(300);
        let outside = answer(&path, "video/mp4", Some("bytes=300-"));
        assert_eq!(outside.status, 416);
        assert_eq!(header(&outside, "Content-Range"), Some("bytes */300"));
        assert!(outside.body.is_empty());
        assert_eq!(answer(&path.with_file_name("gone.mp4"), "video/mp4", None).status, 404);
    }

    #[test]
    fn only_video_and_sound_are_played() {
        assert_eq!(media_type("Clip (720p).mp4"), Some("video/mp4"));
        assert_eq!(media_type("Clip (2160p).WEBM"), Some("video/webm"));
        assert_eq!(media_type("Song.m4a"), Some("audio/mp4"));
        assert_eq!(media_type("Cat.jpg"), None);
        assert_eq!(media_type("Clip.en.srt"), None);
        assert_eq!(media_type("account.zip"), None);
    }
}
