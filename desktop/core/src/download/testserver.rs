//! A small HTTP server for the download tests, standing in for a video site's file server: it serves one file
//! at any path, or several at their own (a stream's playlist and pieces), honours `Range`, and can ignore it,
//! refuse, drop the connection part-way, or send slowly. Nothing reaches the internet.

use std::collections::{HashMap, HashSet};
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

#[derive(Clone, Debug, Default)]
pub struct Behaviour {
    /// Answer every request with this status and no body.
    pub status: Option<u16>,
    /// Send the whole file with 200 even when a range is asked for.
    pub ignore_range: bool,
    /// Close the first connection for each path after this many bytes of the body.
    pub cut_first_after: Option<usize>,
    /// Pause between 16 KiB pieces of the body.
    pub chunk_delay: Duration,
    /// Answer a range with an end ("bytes=a-b") with the whole file, as a server that serves no pieces does.
    pub refuse_pieces: bool,
    /// Answer requests for this path, and only this one, with 403.
    pub refuse_path: Option<String>,
}

/// One file served at any path, or files at their paths (anything else is not found).
enum Files {
    One(Vec<u8>),
    At(HashMap<String, Vec<u8>>),
}

pub struct TestServer {
    port: u16,
    requests: Arc<Mutex<Vec<String>>>,
}

impl TestServer {
    /// Serves `body` at any path.
    pub fn start(body: Vec<u8>, behaviour: Behaviour) -> TestServer {
        TestServer::serving(Files::One(body), behaviour)
    }

    /// Serves each file at its path ("/index.m3u8"); paths are matched lowercased.
    pub fn with_files(files: Vec<(String, Vec<u8>)>, behaviour: Behaviour) -> TestServer {
        TestServer::serving(Files::At(files.into_iter().map(|(p, b)| (p.to_lowercase(), b)).collect()), behaviour)
    }

    fn serving(files: Files, behaviour: Behaviour) -> TestServer {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let seen = requests.clone();
        let files = Arc::new(files);
        let cut = Arc::new(Mutex::new(HashSet::new()));
        thread::spawn(move || {
            for stream in listener.incoming() {
                let Ok(stream) = stream else { continue };
                let (files, behaviour, seen, cut) = (files.clone(), behaviour.clone(), seen.clone(), cut.clone());
                thread::spawn(move || {
                    let _ = serve(stream, &files, &behaviour, &cut, &seen);
                });
            }
        });
        TestServer { port, requests }
    }

    pub fn url(&self, path: &str) -> String {
        format!("http://127.0.0.1:{}{path}", self.port)
    }

    /// Each request's head, lowercased.
    pub fn requests(&self) -> Vec<String> {
        self.requests.lock().unwrap().clone()
    }
}

fn serve(
    mut stream: TcpStream,
    files: &Files,
    behaviour: &Behaviour,
    cut: &Mutex<HashSet<String>>,
    seen: &Mutex<Vec<String>>,
) -> std::io::Result<()> {
    let mut reader = BufReader::new(stream.try_clone()?);
    let mut head = String::new();
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 || line == "\r\n" {
            break;
        }
        head.push_str(&line);
    }
    let head = head.to_lowercase();
    seen.lock().unwrap().push(head.clone());

    // "get /path http/1.1"
    let path = head.split_whitespace().nth(1).unwrap_or("/").to_string();
    let refused = behaviour.refuse_path.as_ref().is_some_and(|p| p.to_lowercase() == path);
    let status = behaviour.status.or(refused.then_some(403));
    let body = match files {
        Files::One(body) => Some(body),
        Files::At(files) => files.get(&path),
    };
    let (Some(body), None) = (body, status) else {
        let status = status.unwrap_or(404);
        return write!(stream, "HTTP/1.1 {status} Refused\r\ncontent-length: 0\r\nconnection: close\r\n\r\n");
    };
    let range = head.lines().find_map(|l| l.strip_prefix("range: bytes=")).and_then(|r| {
        let (start, end) = r.trim().split_once('-')?;
        Some((start.parse::<usize>().ok()?, end.parse::<usize>().ok()))
    });
    let range = range.filter(|(_, end)| !behaviour.ignore_range && !(behaviour.refuse_pieces && end.is_some()));
    let total = body.len();
    let end = range.and_then(|(_, end)| end).map_or(total, |end| (end + 1).min(total));
    let (status, start) = match range.map(|(start, _)| start) {
        Some(start) if start >= total => {
            return write!(
                stream,
                "HTTP/1.1 416 Range Not Satisfiable\r\ncontent-range: bytes */{total}\r\ncontent-length: 0\r\nconnection: close\r\n\r\n"
            );
        }
        Some(start) => ("206 Partial Content", start),
        None => ("200 OK", 0),
    };
    let mut response = format!("HTTP/1.1 {status}\r\ncontent-length: {}\r\nconnection: close\r\n", end - start);
    if status.starts_with("206") {
        response.push_str(&format!("content-range: bytes {start}-{}/{total}\r\n", end - 1));
    }
    response.push_str("\r\n");
    stream.write_all(response.as_bytes())?;

    let first = cut.lock().unwrap().insert(path);
    let limit = if first { behaviour.cut_first_after.unwrap_or(usize::MAX) } else { usize::MAX };
    let mut sent = 0;
    for piece in body[start..end].chunks(16 * 1024) {
        let piece = &piece[..piece.len().min(limit.saturating_sub(sent))];
        if piece.is_empty() {
            break;
        }
        stream.write_all(piece)?;
        sent += piece.len();
        if !behaviour.chunk_delay.is_zero() {
            thread::sleep(behaviour.chunk_delay);
        }
    }
    stream.flush()?;
    // Closing here is what "connection: close" promises, and what a dropped connection looks like.
    stream.shutdown(std::net::Shutdown::Both)
}
