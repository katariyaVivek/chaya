//! Fetching one file over HTTP into a `.part` file, resuming from what is already there.
//!
//! As on the phone (`HttpDownloader`): the site's own headers are sent (its user agent and referer), a
//! partial file is continued with a `Range` request, a server that ignores the range starts the file
//! again, and a dropped connection is tried again from where it stopped. Stopping (pause, cancel) leaves
//! the `.part` file as it is; the caller decides whether to keep it.

use std::collections::BTreeMap;
use std::path::Path;
use std::time::Duration;

use futures_util::StreamExt;
use reqwest::header::{HeaderMap, CONTENT_LENGTH, CONTENT_RANGE, RANGE};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use tokio::fs::OpenOptions;
use tokio::io::{AsyncWriteExt, BufWriter};
use tokio::sync::watch;

use super::error::DownloadError;
use super::pieces::{self, PieceOutcome};

/// What to fetch: an address and the headers its server expects.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Source {
    pub url: String,
    #[serde(default)]
    pub headers: BTreeMap<String, String>,
    /// The size the site gave, which decides whether a file is fetched in pieces.
    #[serde(default)]
    pub expected_size: Option<u64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Progress {
    pub done: u64,
    pub total: Option<u64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    /// The whole file is in the `.part` file.
    Done,
    /// The stop signal was raised; the `.part` file keeps what arrived.
    Stopped,
}

/// Tries after a dropped connection before giving up, and how long to wait before each.
const RETRY_WAITS: [Duration; 2] = [Duration::from_secs(1), Duration::from_secs(3)];

/// Fetches `source` into `part`, continuing what `part` already holds. `stop` turning true stops it.
pub async fn fetch(
    client: &Client,
    source: &Source,
    part: &Path,
    stop: &watch::Receiver<bool>,
    on_progress: &mut (dyn FnMut(Progress) + Send),
) -> Result<Outcome, DownloadError> {
    // YouTube's servers send one request at about the speed of playback; several pieces at once go faster.
    if let Some(total) = source.expected_size.filter(|_| pieces::wanted(source)) {
        match pieces::fetch(client, source, part, pieces::Layout::for_file(total), stop, on_progress).await? {
            PieceOutcome::Finished(outcome) => return Ok(outcome),
            // The server would not serve pieces: the rest comes the ordinary way.
            PieceOutcome::NoPieces => {}
        }
    }
    let mut waits = RETRY_WAITS.iter();
    loop {
        match fetch_once(client, source, part, stop.clone(), on_progress).await {
            Err(DownloadError::Network | DownloadError::Incomplete) if !*stop.borrow() => match waits.next() {
                Some(wait) => {
                    if stoppable_sleep(*wait, stop.clone()).await {
                        return Ok(Outcome::Stopped);
                    }
                }
                None => return Err(DownloadError::Network),
            },
            other => return other,
        }
    }
}

/// Returns once the stop signal is raised (or its sender is gone). The guard `wait_for` returns is dropped
/// here, so the futures waiting on this stay `Send`.
pub(crate) async fn until_stopped(stop: &mut watch::Receiver<bool>) {
    let _ = stop.wait_for(|stopped| *stopped).await;
}

/// Waits `duration`; true when the stop signal came first.
async fn stoppable_sleep(duration: Duration, mut stop: watch::Receiver<bool>) -> bool {
    let stopped = stop.clone();
    tokio::select! {
        _ = tokio::time::sleep(duration) => *stopped.borrow(),
        _ = until_stopped(&mut stop) => true,
    }
}

async fn fetch_once(
    client: &Client,
    source: &Source,
    part: &Path,
    mut stop: watch::Receiver<bool>,
    on_progress: &mut (dyn FnMut(Progress) + Send),
) -> Result<Outcome, DownloadError> {
    if *stop.borrow() {
        return Ok(Outcome::Stopped);
    }
    let have = tokio::fs::metadata(part).await.map(|m| m.len()).unwrap_or(0);
    let mut request = client.get(&source.url);
    for (name, value) in &source.headers {
        request = request.header(name.as_str(), value.as_str());
    }
    if have > 0 {
        request = request.header(RANGE, format!("bytes={have}-"));
    }
    let response = tokio::select! {
        response = request.send() => response.map_err(|e| DownloadError::from_http(&e))?,
        _ = until_stopped(&mut stop) => return Ok(Outcome::Stopped),
    };

    let status = response.status().as_u16();
    let (start, total) = match status {
        206 => match content_range(response.headers()) {
            // Continuing where the file stops.
            Some((start, total)) if start == have => (have, total),
            // A range the request did not ask for: start the file again rather than piece it together wrong.
            _ => {
                remove(part).await?;
                return Err(DownloadError::Incomplete);
            }
        },
        // The server ignored the range and sends the whole file: start again.
        200 => (0, content_length(response.headers())),
        // Nothing left to send: the file may already be whole.
        416 if have > 0 => {
            if content_range_total(response.headers()) == Some(have) {
                on_progress(Progress { done: have, total: Some(have) });
                return Ok(Outcome::Done);
            }
            remove(part).await?;
            return Err(DownloadError::Incomplete);
        }
        _ => return Err(DownloadError::Http(status)),
    };
    let total = total.or_else(|| content_length(response.headers()).map(|length| start + length));

    let file = OpenOptions::new()
        .create(true)
        .write(true)
        .append(start > 0)
        .truncate(start == 0)
        .open(part)
        .await
        .map_err(|e| DownloadError::from_io(&e))?;
    let mut file = BufWriter::with_capacity(256 * 1024, file);
    let mut done = start;
    on_progress(Progress { done, total });

    let mut body = response.bytes_stream();
    loop {
        let chunk = tokio::select! {
            chunk = body.next() => chunk,
            _ = until_stopped(&mut stop) => {
                file.flush().await.map_err(|e| DownloadError::from_io(&e))?;
                return Ok(Outcome::Stopped);
            }
        };
        match chunk {
            Some(Ok(bytes)) => {
                file.write_all(&bytes).await.map_err(|e| DownloadError::from_io(&e))?;
                done += bytes.len() as u64;
                on_progress(Progress { done, total });
            }
            Some(Err(_)) => {
                // Keep what arrived, so the next try continues from it.
                file.flush().await.map_err(|e| DownloadError::from_io(&e))?;
                return Err(DownloadError::Network);
            }
            None => break,
        }
    }
    file.flush().await.map_err(|e| DownloadError::from_io(&e))?;
    file.get_ref().sync_all().await.map_err(|e| DownloadError::from_io(&e))?;
    match total {
        Some(total) if done != total => Err(DownloadError::Incomplete),
        _ => Ok(Outcome::Done),
    }
}

async fn remove(part: &Path) -> Result<(), DownloadError> {
    match tokio::fs::remove_file(part).await {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(DownloadError::from_io(&e)),
        _ => Ok(()),
    }
}

fn content_length(headers: &HeaderMap) -> Option<u64> {
    headers.get(CONTENT_LENGTH)?.to_str().ok()?.trim().parse().ok()
}

/// "bytes 100-999/1000" → (100, Some(1000)); a total of "*" is unknown.
fn content_range(headers: &HeaderMap) -> Option<(u64, Option<u64>)> {
    let value = headers.get(CONTENT_RANGE)?.to_str().ok()?.trim();
    let (range, total) = value.strip_prefix("bytes ")?.split_once('/')?;
    let start = range.split_once('-')?.0.trim().parse().ok()?;
    Some((start, total.trim().parse().ok()))
}

/// "bytes */1000" → 1000, as a 416 answer gives it.
fn content_range_total(headers: &HeaderMap) -> Option<u64> {
    let value = headers.get(CONTENT_RANGE)?.to_str().ok()?;
    value.rsplit_once('/')?.1.trim().parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::download::testserver::{Behaviour, TestServer};

    fn body(len: usize) -> Vec<u8> {
        (0..len).map(|i| (i % 251) as u8).collect()
    }

    async fn run(server: &TestServer, part: &Path) -> (Result<Outcome, DownloadError>, Vec<Progress>) {
        let (_tx, stop) = watch::channel(false);
        let source = Source {
            url: server.url("/file"),
            headers: [("User-Agent".to_string(), "Chaya-test".to_string())].into(),
            expected_size: None,
        };
        let mut seen = Vec::new();
        let result = fetch(&Client::new(), &source, part, &stop, &mut |p| seen.push(p)).await;
        (result, seen)
    }

    #[tokio::test]
    async fn a_whole_file_arrives_with_the_sites_headers() {
        let content = body(300_000);
        let server = TestServer::start(content.clone(), Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");

        let (result, seen) = run(&server, &part).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert_eq!(std::fs::read(&part).unwrap(), content);
        assert_eq!(seen.last(), Some(&Progress { done: 300_000, total: Some(300_000) }));
        assert!(server.requests()[0].contains("user-agent: chaya-test"), "{:?}", server.requests());
    }

    #[tokio::test]
    async fn a_partial_file_is_continued_with_a_range_request() {
        let content = body(100_000);
        let server = TestServer::start(content.clone(), Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");
        std::fs::write(&part, &content[..40_000]).unwrap();

        let (result, seen) = run(&server, &part).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert_eq!(std::fs::read(&part).unwrap(), content);
        assert!(server.requests()[0].contains("range: bytes=40000-"));
        assert_eq!(seen[0], Progress { done: 40_000, total: Some(100_000) });
    }

    #[tokio::test]
    async fn a_server_that_ignores_the_range_gets_the_file_started_again() {
        let content = body(50_000);
        let server = TestServer::start(content.clone(), Behaviour { ignore_range: true, ..Behaviour::default() });
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");
        std::fs::write(&part, b"stale bytes from an older copy").unwrap();

        let (result, _) = run(&server, &part).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert_eq!(std::fs::read(&part).unwrap(), content);
    }

    #[tokio::test]
    async fn a_dropped_connection_is_resumed_from_where_it_stopped() {
        let content = body(200_000);
        let server =
            TestServer::start(content.clone(), Behaviour { cut_first_after: Some(70_000), ..Behaviour::default() });
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");

        let (result, _) = run(&server, &part).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert_eq!(std::fs::read(&part).unwrap(), content);
        let requests = server.requests();
        assert_eq!(requests.len(), 2);
        assert!(requests[1].contains("range: bytes="), "the second request continues: {}", requests[1]);
    }

    #[tokio::test]
    async fn an_error_status_is_reported_and_nothing_is_written() {
        let server = TestServer::start(body(10), Behaviour { status: Some(403), ..Behaviour::default() });
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");

        let (result, _) = run(&server, &part).await;

        assert_eq!(result, Err(DownloadError::Http(403)));
        assert!(!part.exists());
        assert_eq!(server.requests().len(), 1, "a refusal is not retried as it is");
    }

    #[tokio::test]
    async fn a_file_already_whole_is_recognised_from_the_servers_416() {
        let content = body(5_000);
        let server = TestServer::start(content.clone(), Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");
        std::fs::write(&part, &content).unwrap();

        let (result, _) = run(&server, &part).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert_eq!(std::fs::read(&part).unwrap(), content);
    }

    #[tokio::test]
    async fn stopping_keeps_what_arrived() {
        let content = body(2_000_000);
        let server = TestServer::start(
            content.clone(),
            Behaviour { chunk_delay: Duration::from_millis(20), ..Behaviour::default() },
        );
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.mp4.part");
        let (stop_tx, stop) = watch::channel(false);
        let source = Source { url: server.url("/file"), headers: BTreeMap::new(), expected_size: None };

        let mut seen = 0u64;
        let result = fetch(&Client::new(), &source, &part, &stop, &mut |p| {
            seen = p.done;
            if p.done > 100_000 {
                let _ = stop_tx.send(true);
            }
        })
        .await;

        assert_eq!(result, Ok(Outcome::Stopped));
        let kept = std::fs::read(&part).unwrap();
        assert!(!kept.is_empty() && kept.len() < content.len());
        assert_eq!(kept, content[..kept.len()]);
        assert_eq!(kept.len() as u64, seen);
    }

    #[tokio::test]
    async fn an_unreachable_site_is_a_network_failure() {
        let dir = tempfile::tempdir().unwrap();
        let (_tx, stop) = watch::channel(false);
        // A port nothing listens on: bind one, then close it.
        let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        let source =
            Source { url: format!("http://127.0.0.1:{port}/file"), headers: BTreeMap::new(), expected_size: None };

        let result = fetch(&Client::new(), &source, &dir.path().join("a.part"), &stop, &mut |_| {}).await;

        assert_eq!(result, Err(DownloadError::Network));
    }

    #[test]
    fn content_ranges_are_read() {
        let mut headers = HeaderMap::new();
        headers.insert(CONTENT_RANGE, "bytes 100-999/1000".parse().unwrap());
        assert_eq!(content_range(&headers), Some((100, Some(1000))));
        headers.insert(CONTENT_RANGE, "bytes 0-9/*".parse().unwrap());
        assert_eq!(content_range(&headers), Some((0, None)));
        headers.insert(CONTENT_RANGE, "bytes */1000".parse().unwrap());
        assert_eq!(content_range_total(&headers), Some(1000));
    }
}
