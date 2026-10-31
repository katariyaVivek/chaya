//! A stand-in AI service for the tests: it answers each path with what it was told to, and keeps what it was sent
//! (the method, the path, the headers that name the key, and the body). Nothing reaches the internet.

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};
use std::thread;

/// An answer: its status, its extra headers, and its body.
#[derive(Clone)]
pub struct Answer {
    pub status: u16,
    pub headers: Vec<(&'static str, &'static str)>,
    pub body: String,
}

impl Answer {
    pub fn ok(body: impl Into<String>) -> Answer {
        Answer { status: 200, headers: Vec::new(), body: body.into() }
    }

    pub fn status(status: u16, body: impl Into<String>) -> Answer {
        Answer { status, headers: Vec::new(), body: body.into() }
    }
}

/// What the stand-in was sent.
#[derive(Clone, Debug)]
pub struct Sent {
    pub method: String,
    pub path: String,
    pub authorization: Option<String>,
    pub api_key: Option<String>,
    pub body: String,
}

pub struct StandIn {
    port: u16,
    sent: Arc<Mutex<Vec<Sent>>>,
}

impl StandIn {
    /// Answers each path in `answers` ("/v1/chat/completions"); anything else is not found.
    pub fn start(answers: Vec<(&str, Answer)>) -> StandIn {
        let answers: Arc<HashMap<String, Answer>> =
            Arc::new(answers.into_iter().map(|(path, answer)| (path.to_string(), answer)).collect());
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let sent = Arc::new(Mutex::new(Vec::new()));
        let kept = sent.clone();
        thread::spawn(move || {
            for stream in listener.incoming() {
                let Ok(mut stream) = stream else { continue };
                let mut reader = BufReader::new(stream.try_clone().unwrap());
                let mut first = String::new();
                if reader.read_line(&mut first).is_err() {
                    continue;
                }
                let mut parts = first.split_whitespace();
                let (method, path) = (parts.next().unwrap_or("").to_string(), parts.next().unwrap_or("").to_string());
                let (mut length, mut authorization, mut api_key) = (0, None, None);
                loop {
                    let mut line = String::new();
                    if reader.read_line(&mut line).unwrap_or(0) == 0 || line == "\r\n" {
                        break;
                    }
                    let (name, value) = line.split_once(':').unwrap_or((&line, ""));
                    let value = value.trim().to_string();
                    match name.to_ascii_lowercase().as_str() {
                        "content-length" => length = value.parse().unwrap_or(0),
                        "authorization" => authorization = Some(value),
                        "x-api-key" => api_key = Some(value),
                        _ => {}
                    }
                }
                let mut body = vec![0; length];
                let _ = reader.read_exact(&mut body);
                let body = String::from_utf8_lossy(&body).into_owned();
                kept.lock().unwrap().push(Sent { method, path: path.clone(), authorization, api_key, body });
                let answer = answers.get(&path).cloned().unwrap_or_else(|| Answer::status(404, "{}"));
                let mut head = format!(
                    "HTTP/1.1 {} X\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n",
                    answer.status,
                    answer.body.len()
                );
                for (name, value) in &answer.headers {
                    head.push_str(&format!("{name}: {value}\r\n"));
                }
                head.push_str("\r\n");
                let _ = stream.write_all(head.as_bytes());
                let _ = stream.write_all(answer.body.as_bytes());
            }
        });
        StandIn { port, sent }
    }

    /// Its address, with `path` ("/v1").
    pub fn url(&self, path: &str) -> String {
        format!("http://127.0.0.1:{}{path}", self.port)
    }

    pub fn sent(&self) -> Vec<Sent> {
        self.sent.lock().unwrap().clone()
    }
}
