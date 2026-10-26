//! A small HTTP server for the download tests, standing in for a video site's file server: it honours
//! `Range`, and can ignore it, refuse, drop the connection part-way, or send slowly. Nothing reaches the
//! internet.

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
    /// Close the first connection after this many bytes of the body.
    pub cut_first_after: Option<usize>,
    /// Pause between 16 KiB pieces of the body.
    pub chunk_delay: Duration,
}

pub struct TestServer {
    port: u16,
    requests: Arc<Mutex<Vec<String>>>,
}

impl TestServer {
    pub fn start(body: Vec<u8>, behaviour: Behaviour) -> TestServer {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let seen = requests.clone();
        let body = Arc::new(body);
        thread::spawn(move || {
            for (index, stream) in listener.incoming().enumerate() {
                let Ok(stream) = stream else { continue };
                let (body, behaviour, seen) = (body.clone(), behaviour.clone(), seen.clone());
                thread::spawn(move || {
                    let _ = serve(stream, &body, &behaviour, index == 0, &seen);
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
    body: &[u8],
    behaviour: &Behaviour,
    first: bool,
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

    if let Some(status) = behaviour.status {
        return write!(stream, "HTTP/1.1 {status} Refused\r\ncontent-length: 0\r\nconnection: close\r\n\r\n");
    }
    let range_start = head
        .lines()
        .find_map(|l| l.strip_prefix("range: bytes="))
        .and_then(|r| r.trim().trim_end_matches('-').parse::<usize>().ok())
        .filter(|_| !behaviour.ignore_range);
    let total = body.len();
    let (status, start) = match range_start {
        Some(start) if start >= total => {
            return write!(
                stream,
                "HTTP/1.1 416 Range Not Satisfiable\r\ncontent-range: bytes */{total}\r\ncontent-length: 0\r\nconnection: close\r\n\r\n"
            );
        }
        Some(start) => ("206 Partial Content", start),
        None => ("200 OK", 0),
    };
    let mut response = format!("HTTP/1.1 {status}\r\ncontent-length: {}\r\nconnection: close\r\n", total - start);
    if start > 0 {
        response.push_str(&format!("content-range: bytes {start}-{}/{total}\r\n", total - 1));
    }
    response.push_str("\r\n");
    stream.write_all(response.as_bytes())?;

    let limit = if first { behaviour.cut_first_after.unwrap_or(usize::MAX) } else { usize::MAX };
    let mut sent = 0;
    for piece in body[start..].chunks(16 * 1024) {
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
