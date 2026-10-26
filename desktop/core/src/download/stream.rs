//! Saving an HLS stream, which comes as a playlist of short pieces rather than as one file. As on the phone,
//! where Media3 fetches a stream's pieces: the playlist is read (and when it lists the stream at several
//! qualities, the best one's), its pieces are fetched in order, three at a time, with the site's headers,
//! pieces encrypted with AES-128 are decrypted with their key, and each is written after the one before into
//! the `.part` file: MPEG-TS, or fragmented MP4 after its initialization section. ffmpeg then copies that into
//! an MP4 (`join.rs`). `<part>.segments` notes how many pieces are written, so a resume asks only for the rest.
//! A stream protected with DRM (SAMPLE-AES, or a key system other than plain AES-128) is refused.

use std::collections::{BTreeMap, HashMap};
use std::io::SeekFrom;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use aes::cipher::{block_padding::Pkcs7, BlockModeDecrypt, KeyIvInit};
use futures_util::stream::{self, StreamExt};
use reqwest::header::RANGE;
use reqwest::Client;
use tokio::fs::OpenOptions;
use tokio::io::{AsyncSeekExt, AsyncWriteExt};
use tokio::sync::{watch, Mutex};
use url::Url;

use super::error::DownloadError;
use super::http::{until_stopped, Outcome, Progress, Source};

/// Pieces fetched at once, as for YouTube's files (`pieces`).
pub const AT_ONCE: usize = 3;

/// Tries after a dropped connection, and how long to wait before each.
const RETRY_WAITS: [Duration; 2] = [Duration::from_secs(1), Duration::from_secs(3)];

/// One thing to fetch and write, in order: a piece of the stream, or the initialization section that comes
/// before pieces of fragmented MP4.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Piece {
    pub url: Url,
    /// (first byte, length), for a piece that is part of a larger file.
    pub range: Option<(u64, u64)>,
    pub key: Option<Key>,
}

/// An AES-128 key, fetched from `url`, and the IV this piece was encrypted with.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Key {
    pub url: Url,
    pub iv: [u8; 16],
}

/// The key named by the last `EXT-X-KEY`: its address, and the IV it gives, if any.
type KeyInForce = Option<(Url, Option<[u8; 16]>)>;

#[derive(Debug, PartialEq)]
pub(crate) enum Playlist {
    /// The stream at several qualities, each its own playlist, with its bandwidth.
    Variants(Vec<(u64, Url)>),
    Pieces(Vec<Piece>),
}

pub fn log_path(part: &Path) -> PathBuf {
    let mut name = part.file_name().unwrap_or_default().to_os_string();
    name.push(".segments");
    part.with_file_name(name)
}

/// Fetches the stream whose playlist is `source` into `part`, continuing what `part` already holds.
pub async fn fetch(
    client: &Client,
    source: &Source,
    part: &Path,
    stop: &watch::Receiver<bool>,
    on_progress: &mut (dyn FnMut(Progress) + Send),
) -> Result<Outcome, DownloadError> {
    let mut stop_signal = stop.clone();
    let pieces = tokio::select! {
        pieces = pieces_of(client, source) => pieces?,
        _ = until_stopped(&mut stop_signal) => return Ok(Outcome::Stopped),
    };
    let log = log_path(part);
    let (mut count, mut written) = resume_point(part, &log, pieces.len()).await;
    let mut file = OpenOptions::new().create(true).write(true).truncate(false).open(part).await.map_err(io)?;
    // Anything after the pieces the log counts is a piece that was being written when the app stopped.
    file.set_len(written).await.map_err(io)?;
    file.seek(SeekFrom::End(0)).await.map_err(io)?;

    // How big the whole stream is: what yt-dlp estimated, until a few pieces have shown their size.
    let estimate = |count: usize, written: u64| {
        let measured = (count >= AT_ONCE.min(pieces.len())).then(|| written * pieces.len() as u64 / count as u64);
        measured.or(source.expected_size).map(|total| total.max(written))
    };
    let arrived = AtomicU64::new(written);
    let keys = Mutex::new(HashMap::new());
    let (headers, keys, arrived_so_far) = (&source.headers, &keys, &arrived);
    // Owned pieces, and references copied into each future, keep this future `Send` for the runtime.
    let mut queue = stream::iter(pieces[count..].to_vec())
        .map(move |piece| async move { fetch_piece(client, headers, &piece, keys, arrived_so_far).await })
        .buffered(AT_ONCE);
    let mut ticker = tokio::time::interval(Duration::from_millis(200));
    on_progress(Progress { done: written, total: estimate(count, written) });

    loop {
        tokio::select! {
            next = queue.next() => match next {
                Some(Ok(bytes)) => {
                    file.write_all(&bytes).await.map_err(io)?;
                    file.flush().await.map_err(io)?;
                    count += 1;
                    written += bytes.len() as u64;
                    note(&log, count, written, pieces.len()).await?;
                }
                Some(Err(error)) => return Err(error),
                None => break,
            },
            _ = ticker.tick() => {
                let done = arrived.load(Ordering::Relaxed).max(written);
                on_progress(Progress { done, total: estimate(count, written).map(|t| t.max(done)) });
            }
            _ = until_stopped(&mut stop_signal) => return Ok(Outcome::Stopped),
        }
    }
    file.sync_all().await.map_err(io)?;
    on_progress(Progress { done: written, total: Some(written) });
    let _ = tokio::fs::remove_file(&log).await;
    Ok(Outcome::Done)
}

/// The pieces of the stream at `source`: its playlist's, or for a playlist of qualities, the best one's.
async fn pieces_of(client: &Client, source: &Source) -> Result<Vec<Piece>, DownloadError> {
    let mut url = Url::parse(&source.url).map_err(|_| DownloadError::UnreadableStream)?;
    for _ in 0..2 {
        // Pieces named relative to the playlist are found from where it really is, after any redirect.
        let (at, body) = fetch_bytes(client, &source.headers, &url, None, None).await?;
        match parse(&String::from_utf8_lossy(&body), &at)? {
            Playlist::Pieces(pieces) => return Ok(pieces),
            Playlist::Variants(variants) => {
                url = variants.into_iter().max_by_key(|(bandwidth, _)| *bandwidth).map(|(_, url)| url).unwrap_or(url);
            }
        }
    }
    // A playlist of qualities whose best quality is again a playlist of qualities.
    Err(DownloadError::UnreadableStream)
}

/// Reads an HLS playlist (RFC 8216), with addresses in it taken relative to `base`.
pub(crate) fn parse(text: &str, base: &Url) -> Result<Playlist, DownloadError> {
    let mut lines = text.trim_start_matches('\u{feff}').lines().map(str::trim).filter(|l| !l.is_empty());
    if !lines.next().is_some_and(|first| first.starts_with("#EXTM3U")) {
        return Err(DownloadError::UnreadableStream);
    }
    let address = |uri: &str| base.join(uri).map_err(|_| DownloadError::UnreadableStream);
    let mut variants = Vec::new();
    let mut pieces = Vec::new();
    // The bandwidth of the quality the next address is the playlist of.
    let mut variant: Option<u64> = None;
    let mut sequence = 0u64;
    // Pieces of the stream listed so far, not counting initialization sections.
    let mut segments = 0u64;
    let mut key: KeyInForce = None;
    let mut map: Option<Piece> = None;
    let mut map_written: Option<Piece> = None;
    let mut range: Option<(u64, Option<u64>)> = None;
    let mut previous_end: Option<(Url, u64)> = None;

    for line in lines {
        if let Some(tag) = line.strip_prefix('#') {
            let (name, value) = tag.split_once(':').unwrap_or((tag, ""));
            match name {
                "EXT-X-STREAM-INF" => {
                    variant = Some(attributes(value).get("BANDWIDTH").and_then(|b| b.parse().ok()).unwrap_or(0));
                }
                "EXT-X-MEDIA-SEQUENCE" => sequence = value.parse().unwrap_or(0),
                "EXT-X-KEY" => key = key_of(&attributes(value), &address)?,
                "EXT-X-MAP" => {
                    let a = attributes(value);
                    let uri = a.get("URI").ok_or(DownloadError::UnreadableStream)?;
                    let range = match a.get("BYTERANGE") {
                        Some(r) => byte_range(r).map(|(length, offset)| Some((offset.unwrap_or(0), length)))?,
                        None => None,
                    };
                    // Decrypted with the key in force where it is declared, as yt-dlp does.
                    map = Some(Piece { url: address(uri)?, range, key: key_at(&key, sequence + segments) });
                }
                "EXT-X-BYTERANGE" => range = Some(byte_range(value)?),
                _ => {}
            }
            continue;
        }
        let url = address(line)?;
        if let Some(bandwidth) = variant.take() {
            variants.push((bandwidth, url));
            continue;
        }
        // An initialization section goes before the first piece that needs it.
        if let Some(init) = map.clone().filter(|m| map_written.as_ref() != Some(m)) {
            map_written = Some(init.clone());
            pieces.push(init);
        }
        let range = range.take().map(|(length, offset)| {
            let start =
                offset.or_else(|| previous_end.as_ref().filter(|(at, _)| *at == url).map(|(_, end)| *end)).unwrap_or(0);
            (start, length)
        });
        if let Some((start, length)) = range {
            previous_end = Some((url.clone(), start + length));
        }
        let key = key_at(&key, sequence + segments);
        pieces.push(Piece { url, range, key });
        segments += 1;
    }
    match (variants.is_empty(), pieces.is_empty()) {
        (false, _) => Ok(Playlist::Variants(variants)),
        (true, false) => Ok(Playlist::Pieces(pieces)),
        (true, true) => Err(DownloadError::UnreadableStream),
    }
}

/// The key in force for the piece numbered `n` in the stream: the IV given with the key, or else `n`.
fn key_at(key: &KeyInForce, n: u64) -> Option<Key> {
    key.as_ref().map(|(url, iv)| Key { url: url.clone(), iv: iv.unwrap_or((n as u128).to_be_bytes()) })
}

/// `METHOD=AES-128,URI="...",IV=0x...`: the key's address and IV, `None` for `METHOD=NONE`.
fn key_of(
    a: &BTreeMap<String, String>,
    address: &impl Fn(&str) -> Result<Url, DownloadError>,
) -> Result<KeyInForce, DownloadError> {
    match a.get("METHOD").map(String::as_str) {
        None | Some("NONE") => Ok(None),
        Some("AES-128") if a.get("KEYFORMAT").is_none_or(|format| format == "identity") => {
            let url = address(a.get("URI").ok_or(DownloadError::UnreadableStream)?)?;
            let iv = match a.get("IV") {
                Some(iv) => {
                    let digits = iv.trim_start_matches("0x").trim_start_matches("0X");
                    let iv = u128::from_str_radix(digits, 16).map_err(|_| DownloadError::UnreadableStream)?;
                    Some(iv.to_be_bytes())
                }
                None => None,
            };
            Ok(Some((url, iv)))
        }
        // SAMPLE-AES, or a key that only a DRM system can open.
        _ => Err(DownloadError::Protected),
    }
}

/// An attribute list, `NAME=value,NAME="quoted, with commas"`.
fn attributes(list: &str) -> BTreeMap<String, String> {
    let mut found = BTreeMap::new();
    let mut rest = list.trim();
    while let Some((name, after)) = rest.split_once('=') {
        let (value, next) = match after.strip_prefix('"') {
            Some(quoted) => quoted.split_once('"').unwrap_or((quoted, "")),
            None => after.split_once(',').unwrap_or((after, "")),
        };
        found.insert(name.trim().to_ascii_uppercase(), value.trim().to_string());
        rest = next.trim_start_matches(',').trim_start();
    }
    found
}

/// "1000@200" → (1000, Some(200)): a length, and where it starts when given.
fn byte_range(value: &str) -> Result<(u64, Option<u64>), DownloadError> {
    let (length, offset) = value.split_once('@').map_or((value, None), |(l, o)| (l, Some(o)));
    let length = length.trim().parse().map_err(|_| DownloadError::UnreadableStream)?;
    let offset = offset.map(|o| o.trim().parse().map_err(|_| DownloadError::UnreadableStream)).transpose()?;
    Ok((length, offset))
}

/// Where to carry on from: the pieces the log counts and their bytes, when the log is of this stream's pieces
/// and the `.part` file still holds them; otherwise the start.
async fn resume_point(part: &Path, log: &Path, pieces: usize) -> (usize, u64) {
    let length = tokio::fs::metadata(part).await.map_or(0, |m| m.len());
    let noted = tokio::fs::read_to_string(log).await.ok().and_then(|text| {
        let mut numbers = text.split_whitespace().map(|n| n.parse::<u64>().ok());
        Some((numbers.next()??, numbers.next()??, numbers.next()??))
    });
    match noted {
        Some((count, bytes, of)) if of == pieces as u64 && count <= of && bytes <= length => (count as usize, bytes),
        _ => (0, 0),
    }
}

/// Notes that `count` of `of` pieces, `bytes` in all, are written.
async fn note(log: &Path, count: usize, bytes: u64, of: usize) -> Result<(), DownloadError> {
    tokio::fs::write(log, format!("{count} {bytes} {of}\n")).await.map_err(io)
}

/// Fetches `piece` and decrypts it when it is encrypted.
async fn fetch_piece(
    client: &Client,
    headers: &BTreeMap<String, String>,
    piece: &Piece,
    keys: &Mutex<HashMap<Url, [u8; 16]>>,
    arrived: &AtomicU64,
) -> Result<Vec<u8>, DownloadError> {
    let (_, mut bytes) = fetch_bytes(client, headers, &piece.url, piece.range, Some(arrived)).await?;
    if let Some(key) = &piece.key {
        let secret = key_bytes(client, headers, &key.url, keys).await?;
        let length = cbc::Decryptor::<aes::Aes128>::new(&secret.into(), &key.iv.into())
            .decrypt_padded::<Pkcs7>(&mut bytes)
            .map_err(|_| DownloadError::UnreadableStream)?
            .len();
        bytes.truncate(length);
    }
    Ok(bytes)
}

/// The key at `url`, fetched once for all the pieces it opens.
async fn key_bytes(
    client: &Client,
    headers: &BTreeMap<String, String>,
    url: &Url,
    keys: &Mutex<HashMap<Url, [u8; 16]>>,
) -> Result<[u8; 16], DownloadError> {
    let mut keys = keys.lock().await;
    if let Some(key) = keys.get(url) {
        return Ok(*key);
    }
    let (_, body) = fetch_bytes(client, headers, url, None, None).await?;
    let key: [u8; 16] = body.try_into().map_err(|_| DownloadError::UnreadableStream)?;
    keys.insert(url.clone(), key);
    Ok(key)
}

/// Fetches `url` (bytes `range` of it, when given), trying a dropped connection twice more; returns where it
/// really came from and what it holds. What arrives is counted in `arrived` as it comes.
async fn fetch_bytes(
    client: &Client,
    headers: &BTreeMap<String, String>,
    url: &Url,
    range: Option<(u64, u64)>,
    arrived: Option<&AtomicU64>,
) -> Result<(Url, Vec<u8>), DownloadError> {
    let mut waits = RETRY_WAITS.iter();
    loop {
        let mut received = 0;
        let result = fetch_bytes_once(client, headers, url, range, arrived, &mut received).await;
        if let (Err(_), Some(arrived)) = (&result, arrived) {
            arrived.fetch_sub(received, Ordering::Relaxed);
        }
        match result {
            Err(DownloadError::Network) => match waits.next() {
                Some(wait) => tokio::time::sleep(*wait).await,
                None => return Err(DownloadError::Network),
            },
            other => return other,
        }
    }
}

async fn fetch_bytes_once(
    client: &Client,
    headers: &BTreeMap<String, String>,
    url: &Url,
    range: Option<(u64, u64)>,
    arrived: Option<&AtomicU64>,
    received: &mut u64,
) -> Result<(Url, Vec<u8>), DownloadError> {
    let mut request = client.get(url.as_str());
    for (name, value) in headers {
        request = request.header(name.as_str(), value.as_str());
    }
    if let Some((start, length)) = range {
        request = request.header(RANGE, format!("bytes={start}-{}", start + length.max(1) - 1));
    }
    let response = request.send().await.map_err(|e| DownloadError::from_http(&e))?;
    let status = response.status().as_u16();
    if !matches!(status, 200 | 206) {
        return Err(DownloadError::Http(status));
    }
    let at = response.url().clone();
    let mut body = Vec::new();
    let mut chunks = response.bytes_stream();
    while let Some(chunk) = chunks.next().await {
        let chunk = chunk.map_err(|_| DownloadError::Network)?;
        body.extend_from_slice(&chunk);
        *received += chunk.len() as u64;
        if let Some(arrived) = arrived {
            arrived.fetch_add(chunk.len() as u64, Ordering::Relaxed);
        }
    }
    // A server that ignored the range sent the whole file: the piece is cut out of it.
    if let (200, Some((start, length))) = (status, range) {
        let end = usize::try_from(start + length).map_err(|_| DownloadError::Incomplete)?;
        body = body.get(start as usize..end).ok_or(DownloadError::Incomplete)?.to_vec();
    }
    Ok((at, body))
}

fn io(error: std::io::Error) -> DownloadError {
    DownloadError::from_io(&error)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::download::testserver::{Behaviour, TestServer};
    use aes::cipher::BlockModeEncrypt;

    fn base() -> Url {
        Url::parse("https://cdn.example/video/720/index.m3u8").unwrap()
    }

    fn pieces(text: &str) -> Vec<Piece> {
        match parse(text, &base()).unwrap() {
            Playlist::Pieces(pieces) => pieces,
            other => panic!("not pieces: {other:?}"),
        }
    }

    fn body(len: usize, seed: usize) -> Vec<u8> {
        (0..len).map(|i| ((i + seed) % 251) as u8).collect()
    }

    #[test]
    fn a_playlists_pieces_are_read_in_order_with_their_addresses() {
        let found = pieces(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:7\n\
             #EXTINF:6.0,\nseg-0.ts\n#EXTINF:6.0,\n../shared/seg-1.ts?token=a\n\
             #EXTINF:4.0,\nhttps://other.example/seg-2.ts\n#EXT-X-ENDLIST\n",
        );
        let urls: Vec<&str> = found.iter().map(|p| p.url.as_str()).collect();
        assert_eq!(
            urls,
            [
                "https://cdn.example/video/720/seg-0.ts",
                "https://cdn.example/video/shared/seg-1.ts?token=a",
                "https://other.example/seg-2.ts"
            ]
        );
        assert!(found.iter().all(|p| p.range.is_none() && p.key.is_none()));
    }

    #[test]
    fn an_encrypted_pieces_iv_is_its_own_or_its_number_in_the_stream() {
        let found = pieces(
            "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:6,\na.ts\n\
             #EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example/k2\",IV=0x000102030405060708090A0B0C0D0E0F\n\
             #EXTINF:6,\nb.ts\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:6,\nc.ts\n",
        );
        let first = found[0].key.as_ref().unwrap();
        assert_eq!(first.url.as_str(), "https://cdn.example/video/720/key.bin");
        assert_eq!(first.iv, 5u128.to_be_bytes(), "the first piece is number 5");
        let second = found[1].key.as_ref().unwrap();
        assert_eq!(second.url.as_str(), "https://keys.example/k2");
        assert_eq!(second.iv, core::array::from_fn(|i| i as u8));
        assert_eq!(found[2].key, None);
    }

    #[test]
    fn pieces_that_are_ranges_of_one_file_follow_each_other() {
        let found = pieces(
            "#EXTM3U\n#EXT-X-MAP:URI=\"main.mp4\",BYTERANGE=\"720@0\"\n#EXTINF:4,\n#EXT-X-BYTERANGE:1000@720\n\
             main.mp4\n#EXTINF:4,\n#EXT-X-BYTERANGE:500\nmain.mp4\n",
        );
        let ranges: Vec<Option<(u64, u64)>> = found.iter().map(|p| p.range).collect();
        assert_eq!(ranges, [Some((0, 720)), Some((720, 1000)), Some((1720, 500))]);
    }

    #[test]
    fn an_initialization_section_comes_once_before_its_pieces_and_again_when_it_changes() {
        let found = pieces(
            "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\n1.m4s\n#EXTINF:4,\n2.m4s\n\
             #EXT-X-DISCONTINUITY\n#EXT-X-MAP:URI=\"init-b.mp4\"\n#EXTINF:4,\n3.m4s\n",
        );
        let names: Vec<&str> = found.iter().map(|p| p.url.path_segments().unwrap().next_back().unwrap()).collect();
        assert_eq!(names, ["init.mp4", "1.m4s", "2.m4s", "init-b.mp4", "3.m4s"]);
    }

    #[test]
    fn an_initialization_section_is_decrypted_only_by_a_key_declared_before_it() {
        let clear =
            pieces("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\n1.m4s\n");
        assert_eq!((clear[0].key.is_none(), clear[1].key.is_some()), (true, true));
        let encrypted = pieces(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\",IV=0x1\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\n1.m4s\n",
        );
        assert_eq!(encrypted[0].key.as_ref().unwrap().iv, 1u128.to_be_bytes());
    }

    #[test]
    fn a_playlist_of_qualities_lists_each_with_its_bandwidth() {
        let found = parse(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS=\"avc1.4d401e,mp4a.40.2\"\n\
             360/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720\n720/index.m3u8\n",
            &Url::parse("https://cdn.example/video/master.m3u8").unwrap(),
        )
        .unwrap();
        let Playlist::Variants(variants) = found else { panic!("not qualities") };
        assert_eq!(variants[1].0, 2_400_000);
        assert_eq!(variants[1].1.as_str(), "https://cdn.example/video/720/index.m3u8");
    }

    #[test]
    fn a_stream_with_drm_is_refused_and_a_page_that_is_not_a_playlist_unreadable() {
        for key in [
            "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,AAAA\",KEYFORMAT=\"urn:uuid:edef8ba9\"",
            "#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"k\"",
        ] {
            let text = format!("#EXTM3U\n{key}\n#EXTINF:6,\na.ts\n");
            assert_eq!(parse(&text, &base()), Err(DownloadError::Protected), "{key}");
        }
        assert_eq!(parse("<html>Not found</html>", &base()), Err(DownloadError::UnreadableStream));
        assert_eq!(parse("#EXTM3U\n#EXT-X-ENDLIST\n", &base()), Err(DownloadError::UnreadableStream));
    }

    #[test]
    fn attribute_lists_keep_quoted_commas() {
        let a = attributes("BANDWIDTH=800000,CODECS=\"avc1.4d401e,mp4a.40.2\",RESOLUTION=640x360");
        assert_eq!(a["CODECS"], "avc1.4d401e,mp4a.40.2");
        assert_eq!(a["RESOLUTION"], "640x360");
        assert_eq!(a["BANDWIDTH"], "800000");
    }

    /// A playlist of `parts` served with them, as "/index.m3u8" and "/seg-N.ts".
    fn stream(parts: &[Vec<u8>], behaviour: Behaviour) -> TestServer {
        let mut playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:2\n".to_string();
        let mut files = Vec::new();
        for (i, part) in parts.iter().enumerate() {
            playlist.push_str(&format!("#EXTINF:2.0,\nseg-{i}.ts\n"));
            files.push((format!("/seg-{i}.ts"), part.clone()));
        }
        playlist.push_str("#EXT-X-ENDLIST\n");
        files.push(("/index.m3u8".into(), playlist.into_bytes()));
        TestServer::with_files(files, behaviour)
    }

    fn source(server: &TestServer, path: &str) -> Source {
        Source {
            url: server.url(path),
            headers: [("Referer".to_string(), "https://site.example/".to_string())].into(),
            expected_size: None,
            stream: true,
        }
    }

    async fn run(source: &Source, part: &Path) -> (Result<Outcome, DownloadError>, Vec<Progress>) {
        let (_tx, stop) = watch::channel(false);
        let mut seen = Vec::new();
        let result = fetch(&Client::new(), source, part, &stop, &mut |p| seen.push(p)).await;
        (result, seen)
    }

    #[tokio::test]
    async fn a_streams_pieces_are_written_one_after_another_with_the_sites_headers() {
        let parts: Vec<Vec<u8>> = (0..7).map(|i| body(40_000 + i * 1000, i)).collect();
        let server = stream(&parts, Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("Clip.mp4.stream.part");

        let (result, seen) = run(&source(&server, "/index.m3u8"), &part).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert_eq!(std::fs::read(&part).unwrap(), parts.concat());
        let total = parts.concat().len() as u64;
        assert_eq!(seen.last(), Some(&Progress { done: total, total: Some(total) }));
        let requests = server.requests();
        assert_eq!(requests.len(), 8, "the playlist and each piece once");
        assert!(requests.iter().all(|r| r.contains("referer: https://site.example/")), "{requests:?}");
        assert!(!log_path(&part).exists());
    }

    #[tokio::test]
    async fn a_playlist_of_qualities_gets_its_best_saved() {
        let parts = [body(5_000, 1), body(5_000, 2)];
        let mut files: Vec<(String, Vec<u8>)> = vec![
            (
                "/master.m3u8".into(),
                b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=300000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000\nhigh.m3u8\n"
                    .to_vec(),
            ),
            ("/low.m3u8".into(), b"#EXTM3U\n#EXTINF:2,\nlow.ts\n".to_vec()),
            ("/high.m3u8".into(), b"#EXTM3U\n#EXTINF:2,\nh0.ts\n#EXTINF:2,\nh1.ts\n".to_vec()),
            ("/low.ts".into(), body(10, 9)),
        ];
        files.push(("/h0.ts".into(), parts[0].clone()));
        files.push(("/h1.ts".into(), parts[1].clone()));
        let server = TestServer::with_files(files, Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.part");

        let (result, _) = run(&source(&server, "/master.m3u8"), &part).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert_eq!(std::fs::read(&part).unwrap(), parts.concat());
    }

    fn encrypt(plain: &[u8], key: [u8; 16], iv: [u8; 16]) -> Vec<u8> {
        let mut buffer = plain.to_vec();
        buffer.resize(plain.len() + 16, 0);
        let length = cbc::Encryptor::<aes::Aes128>::new(&key.into(), &iv.into())
            .encrypt_padded::<Pkcs7>(&mut buffer, plain.len())
            .unwrap()
            .len();
        buffer.truncate(length);
        buffer
    }

    #[tokio::test]
    async fn pieces_encrypted_with_aes_128_are_decrypted_with_a_key_fetched_once() {
        let key = *b"0123456789abcdef";
        let plain: Vec<Vec<u8>> = (0..4).map(|i| body(9_000 + i * 7, i)).collect();
        let mut playlist = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:20\n#EXT-X-KEY:METHOD=AES-128,URI=\"/key\"\n".to_string();
        let mut files = vec![("/key".to_string(), key.to_vec())];
        for (i, piece) in plain.iter().enumerate() {
            playlist.push_str(&format!("#EXTINF:2,\nseg-{i}.ts\n"));
            files.push((format!("/seg-{i}.ts"), encrypt(piece, key, (20 + i as u128).to_be_bytes())));
        }
        files.push(("/index.m3u8".into(), playlist.into_bytes()));
        let server = TestServer::with_files(files, Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.part");

        let (result, _) = run(&source(&server, "/index.m3u8"), &part).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert_eq!(std::fs::read(&part).unwrap(), plain.concat());
        assert_eq!(server.requests().iter().filter(|r| r.starts_with("get /key ")).count(), 1);
    }

    #[tokio::test]
    async fn a_resume_asks_only_for_the_pieces_the_log_does_not_count() {
        let parts: Vec<Vec<u8>> = (0..5).map(|i| body(20_000, i)).collect();
        let server = stream(&parts, Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.part");
        // Two pieces written and noted, and half of a third that was being written when the app closed.
        let mut kept = parts[..2].concat();
        kept.extend_from_slice(&parts[2][..7_000]);
        std::fs::write(&part, kept).unwrap();
        std::fs::write(log_path(&part), "2 40000 5\n").unwrap();

        let (result, seen) = run(&source(&server, "/index.m3u8"), &part).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert_eq!(std::fs::read(&part).unwrap(), parts.concat());
        let asked: Vec<String> = server.requests().iter().map(|r| r.lines().next().unwrap().to_string()).collect();
        assert!(!asked.iter().any(|r| r.contains("/seg-0.ts") || r.contains("/seg-1.ts")), "{asked:?}");
        assert_eq!(seen[0].done, 40_000);
    }

    #[tokio::test]
    async fn a_log_of_another_stream_starts_it_again() {
        let parts: Vec<Vec<u8>> = (0..3).map(|i| body(1_000, i)).collect();
        let server = stream(&parts, Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.part");
        std::fs::write(&part, body(5_000, 99)).unwrap();
        std::fs::write(log_path(&part), "2 2000 9\n").unwrap();

        let (result, _) = run(&source(&server, "/index.m3u8"), &part).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert_eq!(std::fs::read(&part).unwrap(), parts.concat());
    }

    #[tokio::test]
    async fn a_refused_piece_fails_the_download_for_retry_to_look_up_again() {
        let parts: Vec<Vec<u8>> = (0..4).map(|i| body(1_000, i)).collect();
        let server = stream(&parts, Behaviour { refuse_path: Some("/seg-2.ts".into()), ..Behaviour::default() });
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.part");

        let (result, _) = run(&source(&server, "/index.m3u8"), &part).await;

        assert_eq!(result, Err(DownloadError::Http(403)));
        // The pieces before it stay, noted, for Retry to continue from.
        assert_eq!(std::fs::read(&part).unwrap(), parts[..2].concat());
        assert_eq!(std::fs::read_to_string(log_path(&part)).unwrap(), "2 2000 4\n");
    }

    #[tokio::test]
    async fn a_dropped_connection_is_tried_again() {
        let parts: Vec<Vec<u8>> = (0..2).map(|i| body(60_000, i)).collect();
        let server = stream(&parts, Behaviour { cut_first_after: Some(20_000), ..Behaviour::default() });
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.part");

        let (result, seen) = run(&source(&server, "/index.m3u8"), &part).await;

        assert_eq!(result, Ok(Outcome::Done));
        assert_eq!(std::fs::read(&part).unwrap(), parts.concat());
        assert!(server.requests().len() > 3, "the cut pieces were asked for again");
        assert!(seen.iter().all(|p| p.done <= 120_000), "a cut try's bytes are not counted twice: {seen:?}");
    }

    #[tokio::test]
    async fn stopping_keeps_the_whole_pieces_and_their_log() {
        let parts: Vec<Vec<u8>> = (0..20).map(|i| body(100_000, i)).collect();
        let server = stream(&parts, Behaviour { chunk_delay: Duration::from_millis(10), ..Behaviour::default() });
        let dir = tempfile::tempdir().unwrap();
        let part = dir.path().join("a.part");
        let (stop_tx, stop) = watch::channel(false);

        let result = fetch(&Client::new(), &source(&server, "/index.m3u8"), &part, &stop, &mut |p| {
            if p.done > 300_000 {
                let _ = stop_tx.send(true);
            }
        })
        .await;

        assert_eq!(result, Ok(Outcome::Stopped));
        let (count, bytes) = resume_point(&part, &log_path(&part), parts.len()).await;
        assert!(count > 0 && count < parts.len(), "{count} pieces");
        assert_eq!(std::fs::read(&part).unwrap()[..bytes as usize], parts[..count].concat());
    }
}
