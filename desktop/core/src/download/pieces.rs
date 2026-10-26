//! Fetching a file in pieces, several at once, for YouTube's servers (`googlevideo.com`), which send one
//! request at about the speed of playback. As on the phone (`HttpDownloader`, `PieceLog`): 10 MB pieces, three
//! at a time, each written at its place in the `.part` file, and `<part>.pieces` listing the pieces that are
//! whole, so a resume asks only for the missing ones. A server that answers a piece with anything but that
//! piece gets the rest of the file the ordinary way.

use std::collections::BTreeSet;
use std::io::SeekFrom;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use futures_util::stream::{FuturesUnordered, StreamExt};
use reqwest::header::{CONTENT_RANGE, RANGE};
use reqwest::Client;
use tokio::fs::OpenOptions;
use tokio::io::{AsyncSeekExt, AsyncWriteExt};
use tokio::sync::watch;

use super::error::DownloadError;
use super::http::{until_stopped, Outcome, Progress, Source};

pub const PIECE: u64 = 10 * 1024 * 1024;
pub const AT_ONCE: usize = 3;

/// How a file of `total` bytes is fetched: in pieces of `piece` bytes, `at_once` at a time.
#[derive(Clone, Copy, Debug)]
pub struct Layout {
    pub total: u64,
    pub piece: u64,
    pub at_once: usize,
}

impl Layout {
    /// YouTube's file, as the phone fetches it.
    pub fn for_file(total: u64) -> Layout {
        Layout { total, piece: PIECE, at_once: AT_ONCE }
    }
}

/// Whether `source` is on a server that is faster in pieces.
pub fn wanted(source: &Source) -> bool {
    url::Url::parse(&source.url)
        .ok()
        .and_then(|u| u.host_str().map(str::to_ascii_lowercase))
        .is_some_and(|host| host == "googlevideo.com" || host.ends_with(".googlevideo.com"))
}

/// How it went: the file is whole, it was stopped, or the server would not serve pieces (the `.part` file then
/// holds the whole pieces from its start, ready to be continued the ordinary way).
#[derive(Debug, PartialEq, Eq)]
pub enum PieceOutcome {
    Finished(Outcome),
    NoPieces,
}

pub fn log_path(part: &Path) -> PathBuf {
    let mut name = part.file_name().unwrap_or_default().to_os_string();
    name.push(".pieces");
    part.with_file_name(name)
}

/// Fetches `source` into `part` in pieces, as `layout` says.
pub async fn fetch(
    client: &Client,
    source: &Source,
    part: &Path,
    layout: Layout,
    stop: &watch::Receiver<bool>,
    on_progress: &mut (dyn FnMut(Progress) + Send),
) -> Result<PieceOutcome, DownloadError> {
    let Layout { total, piece, at_once } = layout;
    let log = log_path(part);
    let count = total.div_ceil(piece);
    let mut whole = whole_pieces(part, &log, piece).await;
    whole.retain(|i| *i < count);
    // The file at its full length, so each piece can be written at its place.
    let file = OpenOptions::new().create(true).write(true).truncate(false).open(part).await.map_err(io)?;
    file.set_len(total).await.map_err(io)?;
    drop(file);

    let range = |i: u64| (i * piece, ((i + 1) * piece).min(total) - 1);
    let arrived = Arc::new(AtomicU64::new(whole.iter().map(|i| range(*i).1 - range(*i).0 + 1).sum()));
    let mut waiting: Vec<u64> = (0..count).filter(|i| !whole.contains(i)).collect();
    waiting.reverse();
    let mut running = FuturesUnordered::new();
    let mut ticker = tokio::time::interval(Duration::from_millis(200));
    let mut stop_signal = stop.clone();
    on_progress(Progress { done: arrived.load(Ordering::Relaxed), total: Some(total) });

    loop {
        while running.len() < at_once {
            let Some(i) = waiting.pop() else { break };
            let (start, end) = range(i);
            let (client, source, part, arrived) = (client.clone(), source.clone(), part.to_path_buf(), arrived.clone());
            running.push(async move { (i, fetch_piece(&client, &source, &part, (start, end, total), &arrived).await) });
        }
        if running.is_empty() {
            break;
        }
        tokio::select! {
            Some((i, result)) = running.next() => match result {
                Ok(()) => {
                    whole.insert(i);
                    note_whole(&log, i).await?;
                }
                Err(PieceError::Refused) => {
                    drop(running);
                    keep_leading_pieces(part, &log, &whole, piece).await?;
                    return Ok(PieceOutcome::NoPieces);
                }
                Err(PieceError::Failed(error)) => return Err(error),
            },
            _ = ticker.tick() => on_progress(Progress { done: arrived.load(Ordering::Relaxed), total: Some(total) }),
            _ = until_stopped(&mut stop_signal) => return Ok(PieceOutcome::Finished(Outcome::Stopped)),
        }
    }
    on_progress(Progress { done: total, total: Some(total) });
    let _ = tokio::fs::remove_file(&log).await;
    Ok(PieceOutcome::Finished(Outcome::Done))
}

enum PieceError {
    /// The server answered with something other than the piece.
    Refused,
    Failed(DownloadError),
}

/// Fetches bytes `start..=end` into their place in `part`, trying a dropped connection twice more.
async fn fetch_piece(
    client: &Client,
    source: &Source,
    part: &Path,
    piece: (u64, u64, u64),
    arrived: &AtomicU64,
) -> Result<(), PieceError> {
    let mut tries = 0;
    loop {
        let mut written = 0;
        match fetch_piece_once(client, source, part, piece, arrived, &mut written).await {
            Err(PieceError::Failed(DownloadError::Network)) if tries < 2 => {
                tries += 1;
                arrived.fetch_sub(written, Ordering::Relaxed);
                tokio::time::sleep(Duration::from_secs(1 + 2 * tries)).await;
            }
            Err(error) => {
                arrived.fetch_sub(written, Ordering::Relaxed);
                return Err(error);
            }
            Ok(()) => return Ok(()),
        }
    }
}

/// `piece` is (first byte, last byte, the file's length).
async fn fetch_piece_once(
    client: &Client,
    source: &Source,
    part: &Path,
    (start, end, total): (u64, u64, u64),
    arrived: &AtomicU64,
    written: &mut u64,
) -> Result<(), PieceError> {
    let failed = |e: &reqwest::Error| PieceError::Failed(DownloadError::from_http(e));
    let mut request = client.get(&source.url).header(RANGE, format!("bytes={start}-{end}"));
    for (name, value) in &source.headers {
        request = request.header(name.as_str(), value.as_str());
    }
    let response = request.send().await.map_err(|e| failed(&e))?;
    match response.status().as_u16() {
        206 => {}
        // A refusal is the address's, not the pieces': it fails the download, for Retry to look it up again.
        status @ (401 | 403 | 404 | 410 | 429) => return Err(PieceError::Failed(DownloadError::Http(status))),
        _ => return Err(PieceError::Refused),
    }
    // "bytes start-end/total": the piece asked for, of a file as long as expected. A size yt-dlp only estimated
    // is caught here, and the file then comes the ordinary way.
    let answered = response.headers().get(CONTENT_RANGE).and_then(|v| v.to_str().ok()).and_then(|v| {
        let (range, length) = v.strip_prefix("bytes ")?.split_once('/')?;
        Some((range.split_once('-')?.0.parse::<u64>().ok()?, length.parse::<u64>().ok()?))
    });
    if answered != Some((start, total)) {
        return Err(PieceError::Refused);
    }

    let mut file = OpenOptions::new().write(true).open(part).await.map_err(|e| PieceError::Failed(io(e)))?;
    file.seek(SeekFrom::Start(start)).await.map_err(|e| PieceError::Failed(io(e)))?;
    let mut body = response.bytes_stream();
    let length = end - start + 1;
    while let Some(chunk) = body.next().await {
        let chunk = chunk.map_err(|_| PieceError::Failed(DownloadError::Network))?;
        let room = (length - *written).min(chunk.len() as u64) as usize;
        file.write_all(&chunk[..room]).await.map_err(|e| PieceError::Failed(io(e)))?;
        *written += room as u64;
        arrived.fetch_add(room as u64, Ordering::Relaxed);
    }
    file.flush().await.map_err(|e| PieceError::Failed(io(e)))?;
    if *written != length {
        return Err(PieceError::Failed(DownloadError::Network));
    }
    Ok(())
}

/// The pieces already whole: those the log lists, or for a `.part` file from an ordinary download, the whole
/// pieces at its start.
async fn whole_pieces(part: &Path, log: &Path, piece: u64) -> BTreeSet<u64> {
    if let Ok(text) = tokio::fs::read_to_string(log).await {
        return text.lines().filter_map(|l| l.trim().parse().ok()).collect();
    }
    let length = tokio::fs::metadata(part).await.map_or(0, |m| m.len());
    (0..length / piece).collect()
}

/// Notes a whole piece, before anything else depends on it.
async fn note_whole(log: &Path, i: u64) -> Result<(), DownloadError> {
    let mut file = OpenOptions::new().create(true).append(true).open(log).await.map_err(io)?;
    file.write_all(format!("{i}\n").as_bytes()).await.map_err(io)?;
    file.sync_all().await.map_err(io)
}

/// Cuts `part` back to the whole pieces at its start, for the ordinary way to continue from, and forgets the log.
async fn keep_leading_pieces(part: &Path, log: &Path, whole: &BTreeSet<u64>, piece: u64) -> Result<(), DownloadError> {
    let leading = (0..).take_while(|i| whole.contains(i)).count() as u64;
    let file = OpenOptions::new().write(true).open(part).await.map_err(io)?;
    file.set_len(leading * piece).await.map_err(io)?;
    let _ = tokio::fs::remove_file(log).await;
    Ok(())
}

fn io(error: std::io::Error) -> DownloadError {
    DownloadError::from_io(&error)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::download::testserver::{Behaviour, TestServer};

    fn body(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i * 7 % 255) as u8).collect()
    }

    fn source(server: &TestServer) -> Source {
        Source { url: server.url("/videoplayback"), headers: Default::default(), expected_size: None, stream: false }
    }

    #[test]
    fn only_youtubes_file_servers_are_fetched_in_pieces() {
        let at =
            |url: &str| Source { url: url.into(), headers: Default::default(), expected_size: None, stream: false };
        assert!(wanted(&at("https://rr3---sn-abc.googlevideo.com/videoplayback?id=1")));
        assert!(!wanted(&at("https://www.youtube.com/watch?v=x")));
        assert!(!wanted(&at("https://googlevideo.com.example/videoplayback")));
        assert!(!wanted(&at("https://cdn.tiktok.example/video.mp4")));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn the_pieces_are_fetched_three_at_a_time_and_put_in_their_places() {
        let content = body(1_050_000);
        let server = TestServer::start(content.clone(), Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");
        let (_tx, stop) = watch::channel(false);
        let mut last = None;

        let result = fetch(
            &Client::new(),
            &source(&server),
            &part,
            Layout { total: 1_050_000, piece: 100_000, at_once: 3 },
            &stop,
            &mut |p| last = Some(p),
        )
        .await;

        assert_eq!(result, Ok(PieceOutcome::Finished(Outcome::Done)));
        assert_eq!(std::fs::read(&part).unwrap(), content);
        assert!(!log_path(&part).exists(), "the log goes once the file is whole");
        assert_eq!(last, Some(Progress { done: 1_050_000, total: Some(1_050_000) }));
        let requests = server.requests();
        assert_eq!(requests.len(), 11);
        assert!(requests.iter().any(|r| r.contains("range: bytes=1000000-1049999")), "the last piece is short");
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_resume_asks_only_for_the_pieces_the_log_does_not_list() {
        let content = body(500_000);
        let server = TestServer::start(content.clone(), Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");
        // Pieces 1 and 3 arrived before the pause; their bytes are in place.
        let mut partial = vec![0u8; 500_000];
        for i in [1usize, 3] {
            partial[i * 100_000..(i + 1) * 100_000].copy_from_slice(&content[i * 100_000..(i + 1) * 100_000]);
        }
        std::fs::write(&part, &partial).unwrap();
        std::fs::write(log_path(&part), "1\n3\n").unwrap();
        let (_tx, stop) = watch::channel(false);

        let result = fetch(
            &Client::new(),
            &source(&server),
            &part,
            Layout { total: 500_000, piece: 100_000, at_once: 3 },
            &stop,
            &mut |_| {},
        )
        .await;

        assert_eq!(result, Ok(PieceOutcome::Finished(Outcome::Done)));
        assert_eq!(std::fs::read(&part).unwrap(), content);
        let mut asked: Vec<String> = server
            .requests()
            .iter()
            .filter_map(|r| r.lines().find(|l| l.starts_with("range:")).map(String::from))
            .collect();
        asked.sort();
        assert_eq!(asked, ["range: bytes=0-99999", "range: bytes=200000-299999", "range: bytes=400000-499999"]);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_file_from_an_ordinary_download_keeps_the_whole_pieces_at_its_start() {
        let content = body(300_000);
        let server = TestServer::start(content.clone(), Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");
        std::fs::write(&part, &content[..150_000]).unwrap();
        let (_tx, stop) = watch::channel(false);

        let result = fetch(
            &Client::new(),
            &source(&server),
            &part,
            Layout { total: 300_000, piece: 100_000, at_once: 3 },
            &stop,
            &mut |_| {},
        )
        .await;

        assert_eq!(result, Ok(PieceOutcome::Finished(Outcome::Done)));
        assert_eq!(std::fs::read(&part).unwrap(), content);
        assert_eq!(server.requests().len(), 2, "piece 0 was already whole");
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_server_that_will_not_serve_pieces_leaves_the_leading_pieces_for_the_ordinary_way() {
        let server = TestServer::start(body(300_000), Behaviour { refuse_pieces: true, ..Behaviour::default() });
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");
        let (_tx, stop) = watch::channel(false);

        let result = fetch(
            &Client::new(),
            &source(&server),
            &part,
            Layout { total: 300_000, piece: 100_000, at_once: 3 },
            &stop,
            &mut |_| {},
        )
        .await;

        assert_eq!(result, Ok(PieceOutcome::NoPieces));
        assert_eq!(std::fs::metadata(&part).unwrap().len(), 0, "no whole piece to keep");
        assert!(!log_path(&part).exists());
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_refused_piece_fails_the_download_for_retry() {
        let server = TestServer::start(body(10), Behaviour { status: Some(403), ..Behaviour::default() });
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");
        let (_tx, stop) = watch::channel(false);

        let result = fetch(
            &Client::new(),
            &source(&server),
            &part,
            Layout { total: 300_000, piece: 100_000, at_once: 3 },
            &stop,
            &mut |_| {},
        )
        .await;

        assert_eq!(result, Err(DownloadError::Http(403)));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn stopping_keeps_the_log_of_whole_pieces() {
        let content = body(2_000_000);
        let server =
            TestServer::start(content, Behaviour { chunk_delay: Duration::from_millis(10), ..Behaviour::default() });
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");
        let (stop_tx, stop) = watch::channel(false);

        let result = fetch(
            &Client::new(),
            &source(&server),
            &part,
            Layout { total: 2_000_000, piece: 50_000, at_once: 3 },
            &stop,
            &mut |p| {
                if p.done > 300_000 {
                    let _ = stop_tx.send(true);
                }
            },
        )
        .await;

        assert_eq!(result, Ok(PieceOutcome::Finished(Outcome::Stopped)));
        let listed = std::fs::read_to_string(log_path(&part)).unwrap();
        assert!(!listed.trim().is_empty(), "whole pieces are remembered");
    }
}
