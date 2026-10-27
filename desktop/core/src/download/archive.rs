//! A ZIP of everything an Instagram or X account has posted, made as the phone makes it (`DownloadManager`'s
//! archives): the account's files are listed first (by the engine, through a [`Lister`]), then fetched one by one
//! into a folder beside the ZIP, then packed. Where it has got is read from that folder ("name.zip.files"): its
//! list, each file whole or left out, so a pause, a closed app and Retry all carry on from the right place.

use std::io::{self, BufRead, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::error::DownloadError;
use crate::links::Platform;

/// The list of what the archive holds, one JSON object a line ({"name", "url", "http_headers"}).
pub const LIST: &str = "list.jsonl";
/// Its appearance asks the listing to stop at its next file.
pub const STOP: &str = "stop";
/// Beside a file the site no longer had, or would not send: it is left out and named in the note.
pub const LEFT_OUT: &str = ".gone";
/// The note in the ZIP naming the files that could not be saved.
pub const NOT_SAVED_NOTE: &str = "not-saved.txt";

/// A file is asked for again after these waits, then left out (or, when the connection or the site is what fails,
/// the archive stops so the person can try again later).
pub const RETRY_WAITS: [Duration; 3] = [Duration::from_secs(3), Duration::from_secs(15), Duration::from_secs(60)];

/// The account an archive holds, and whether the person chose to list it with their sign-in.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Account {
    pub platform: Platform,
    pub username: String,
    #[serde(default)]
    pub sign_in: bool,
}

impl Account {
    /// gallery-dl's name for the site.
    pub fn site(&self) -> &'static str {
        match self.platform {
            Platform::X => "twitter",
            _ => "instagram",
        }
    }
}

/// What a listing is asked to do: list `account`'s files into `list` (written as "list.partial" until whole), and
/// stop when `stop` appears.
#[derive(Clone, Debug)]
pub struct ListRequest {
    pub account: Account,
    pub list: PathBuf,
    pub stop: PathBuf,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Listed {
    /// `list` holds everything the account has posted.
    Whole,
    /// `stop` appeared; there is no list.
    Stopped,
}

/// Lists an account (the engine's `profile` command, with the person's sign-in only when they chose it). It may run
/// for minutes, so it is called off the runtime's threads.
pub type Lister = Arc<dyn Fn(&ListRequest) -> Result<Listed, DownloadError> + Send + Sync>;

/// How far an archive has got, for the window. Kept in memory only, and worked out again from its folder.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArchiveProgress {
    /// Files found so far while listing, or in the finished list.
    pub found: usize,
    /// Files fetched (or left out) so far.
    pub saved: usize,
    /// Still finding out what the account holds.
    pub listing: bool,
}

/// One file of the archive. `name` is its name in the ZIP, the same however often the account is listed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    pub url: String,
    pub headers: Vec<(String, String)>,
}

/// The folder an archive's files wait in beside its ZIP: "name.zip.files".
pub fn folder_of(zip: &Path) -> PathBuf {
    let mut name = zip.file_name().unwrap_or_default().to_os_string();
    name.push(".files");
    zip.with_file_name(name)
}

/// The partial list a listing writes as it goes, to count what it has found.
pub fn partial_of(list: &Path) -> PathBuf {
    let mut name = list.file_name().unwrap_or_default().to_os_string();
    name.push(".partial");
    list.with_file_name(name)
}

/// The entries of a finished list, each name once, skipping lines that cannot be used.
pub fn read_entries(list: &Path) -> io::Result<Vec<Entry>> {
    let file = std::fs::File::open(list)?;
    let mut seen = std::collections::HashSet::new();
    let mut entries = Vec::new();
    for line in io::BufReader::new(file).lines() {
        let Some(entry) = serde_json::from_str::<Value>(&line?).ok().as_ref().and_then(parse) else { continue };
        if seen.insert(entry.name.to_lowercase()) {
            entries.push(entry);
        }
    }
    Ok(entries)
}

fn parse(json: &Value) -> Option<Entry> {
    let url = json.get("url")?.as_str().filter(|u| u.starts_with("http://") || u.starts_with("https://"))?;
    // A name is a plain file name: nothing that could reach outside the archive's folder, nor a hidden file.
    let name = json.get("name")?.as_str()?.rsplit(['/', '\\']).next()?.trim();
    if name.is_empty() || name.starts_with('.') || name.contains(':') {
        return None;
    }
    let headers = json
        .get("http_headers")
        .and_then(Value::as_object)
        .map(|h| {
            h.iter()
                .filter_map(|(k, v)| Some((k.clone(), v.as_str()?.to_string())))
                .filter(|(_, v)| !v.is_empty())
                .collect()
        })
        .unwrap_or_default();
    Some(Entry { name: name.to_string(), url: url.to_string(), headers })
}

/// Lines in `file` so far: how many files a listing has found.
pub fn count_lines(file: &Path) -> usize {
    std::fs::read(file).map_or(0, |bytes| bytes.split(|b| *b == b'\n').filter(|l| !l.is_empty()).count())
}

/// Whether `entry` is done: fetched whole, or left out.
pub fn is_done(folder: &Path, entry: &Entry) -> bool {
    folder.join(&entry.name).exists() || folder.join(format!("{}{LEFT_OUT}", entry.name)).exists()
}

/// Packs every fetched file into `out`, as they are (pictures and videos are compressed already, so storing them is
/// faster and no bigger), with a note naming the files that could not be saved.
pub fn pack(folder: &Path, entries: &[Entry], out: &Path) -> Result<(), DownloadError> {
    let io_error = |e: &io::Error| DownloadError::from_io(e);
    let zip_error = |e: zip::result::ZipError| match e {
        zip::result::ZipError::Io(e) => DownloadError::from_io(&e),
        _ => DownloadError::CantWrite,
    };
    let file = std::fs::File::create(out).map_err(|e| io_error(&e))?;
    let mut zip = zip::ZipWriter::new(io::BufWriter::new(file));
    let mut missing = Vec::new();
    for entry in entries {
        let path = folder.join(&entry.name);
        let Ok(meta) = std::fs::metadata(&path) else {
            missing.push(entry.name.as_str());
            continue;
        };
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored)
            .large_file(meta.len() >= u64::from(u32::MAX))
            .last_modified_time(zip_time(meta.modified().ok()));
        zip.start_file(entry.name.as_str(), options).map_err(zip_error)?;
        let mut source = std::fs::File::open(&path).map_err(|e| io_error(&e))?;
        io::copy(&mut source, &mut zip).map_err(|e| io_error(&e))?;
    }
    if !missing.is_empty() {
        let options = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Stored)
            .last_modified_time(zip_time(Some(SystemTime::now())));
        zip.start_file(NOT_SAVED_NOTE, options).map_err(zip_error)?;
        let note = format!(
            "Chaya couldn't save these: the site no longer had them, or would not send them.\n\n{}\n",
            missing.join("\n")
        );
        zip.write_all(note.as_bytes()).map_err(|e| io_error(&e))?;
    }
    let mut writer = zip.finish().map_err(zip_error)?;
    writer.flush().map_err(|e| io_error(&e))?;
    Ok(())
}

/// A file's time as a ZIP keeps it (to two seconds, from 1980): its civil date and time in UTC.
fn zip_time(time: Option<SystemTime>) -> zip::DateTime {
    let seconds = time.and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map_or(0, |d| d.as_secs());
    let (days, rest) = ((seconds / 86_400) as i64, seconds % 86_400);
    // Days since 1970 to a civil date (Howard Hinnant's algorithm).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u8;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u8;
    let year = (yoe + era * 400 + i64::from(month <= 2)) as u16;
    let (hour, minute, second) = ((rest / 3600) as u8, (rest % 3600 / 60) as u8, (rest % 60) as u8);
    zip::DateTime::from_date_and_time(year, month, day, hour, minute, second).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;

    fn list(lines: &[&str]) -> (tempfile::TempDir, PathBuf) {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join(LIST);
        std::fs::write(&path, lines.join("\n")).unwrap();
        (dir, path)
    }

    #[test]
    fn a_list_gives_each_file_once_with_a_plain_name_and_its_headers() {
        let (_dir, path) = list(&[
            r#"{"name": "2024-05-01 Cabc 1.jpg", "url": "https://cdn.example/a.jpg", "http_headers": {"Referer": "https://www.instagram.com/", "Empty": ""}}"#,
            r#"{"name": "2024-05-01 Cabc 1.jpg", "url": "https://cdn.example/again.jpg"}"#,
            r#"{"name": "../../evil.exe", "url": "https://cdn.example/b.mp4"}"#,
            r#"{"name": ".hidden", "url": "https://cdn.example/c.mp4"}"#,
            r#"{"name": "C:evil.mp4", "url": "https://cdn.example/d.mp4"}"#,
            r#"{"name": "file.jpg", "url": "file:///etc/passwd"}"#,
            "not json",
            "",
        ]);
        let entries = read_entries(&path).unwrap();
        assert_eq!(
            entries,
            vec![
                Entry {
                    name: "2024-05-01 Cabc 1.jpg".into(),
                    url: "https://cdn.example/a.jpg".into(),
                    headers: vec![("Referer".into(), "https://www.instagram.com/".into())],
                },
                Entry { name: "evil.exe".into(), url: "https://cdn.example/b.mp4".into(), headers: vec![] },
            ]
        );
    }

    #[test]
    fn the_zip_holds_each_file_as_it_is_and_names_those_left_out() {
        let dir = tempfile::tempdir().unwrap();
        let folder = dir.path().join("someone.zip.files");
        std::fs::create_dir_all(&folder).unwrap();
        std::fs::write(folder.join("a.jpg"), b"picture").unwrap();
        std::fs::write(folder.join("b.mp4"), b"video").unwrap();
        std::fs::File::create(folder.join(format!("c.jpg{LEFT_OUT}"))).unwrap();
        let entry = |name: &str| Entry { name: name.into(), url: "https://cdn.example/x".into(), headers: vec![] };
        let entries = [entry("a.jpg"), entry("b.mp4"), entry("c.jpg")];
        assert!(entries.iter().all(|e| is_done(&folder, e)));

        let out = dir.path().join("someone.zip");
        pack(&folder, &entries, &out).unwrap();

        let mut zip = zip::ZipArchive::new(std::fs::File::open(&out).unwrap()).unwrap();
        let names: Vec<String> = (0..zip.len()).map(|i| zip.by_index(i).unwrap().name().unwrap().to_string()).collect();
        assert_eq!(names, ["a.jpg", "b.mp4", NOT_SAVED_NOTE]);
        let mut text = String::new();
        zip.by_name("b.mp4").unwrap().read_to_string(&mut text).unwrap();
        assert_eq!(text, "video");
        let mut note = String::new();
        zip.by_name(NOT_SAVED_NOTE).unwrap().read_to_string(&mut note).unwrap();
        assert!(note.ends_with("c.jpg\n"), "{note}");
        assert_eq!(zip.by_name("a.jpg").unwrap().compression(), zip::CompressionMethod::Stored);
    }

    #[test]
    fn a_files_time_goes_into_the_zip_as_its_date() {
        // 2024-05-01 12:34:56 UTC.
        let time = zip_time(Some(UNIX_EPOCH + Duration::from_secs(1_714_566_896)));
        assert_eq!((time.year(), time.month(), time.day()), (2024, 5, 1));
        assert_eq!((time.hour(), time.minute(), time.second()), (12, 34, 56));
    }

    #[test]
    fn the_folder_and_partial_list_sit_beside_what_they_are_for() {
        let zip = Path::new("/d/someone (Instagram).zip");
        assert_eq!(folder_of(zip), Path::new("/d/someone (Instagram).zip.files"));
        assert_eq!(
            partial_of(&folder_of(zip).join(LIST)),
            Path::new("/d/someone (Instagram).zip.files/list.jsonl.partial")
        );
    }
}
