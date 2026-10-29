//! Listening to a video: ffmpeg turns its sound into the 16 kHz mono WAV whisper.cpp hears, then whisper.cpp's
//! command-line program (`desktop/engine/build_whisper.sh`) writes what was said, each segment and each word with its
//! times, as JSON (`-ojf`), saying how far it has got as it goes (`-pp`). Both run with no console window, and stop
//! when asked.
//!
//! whisper-cli reads its arguments in the PC's code page, not Unicode, so it runs from inside Chaya's speech folder
//! and is given only short relative paths there ("models/ggml-tiny.bin", "work/12/sound.wav"): a person's name with
//! letters outside that code page, in the path of their profile, never reaches it.

use std::path::{Path, PathBuf};
use std::process::Stdio;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;
use tokio::sync::watch;

use crate::subtitles::Line;

use super::SpeechError;

/// whisper.cpp's command-line program.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Whisper {
    pub program: PathBuf,
}

impl Whisper {
    /// The whisper-cli in the engine folder, or `CHAYA_WHISPER` in development; `None` when there is neither, and the
    /// app then offers no subtitles made on the PC.
    pub fn find(engine_folder: &Path) -> Option<Whisper> {
        ["whisper-cli.exe", "whisper-cli"]
            .iter()
            .map(|name| engine_folder.join(name))
            .find(|path| path.is_file())
            .or_else(|| std::env::var_os("CHAYA_WHISPER").map(PathBuf::from).filter(|p| p.is_file()))
            .map(|program| Whisper { program })
    }
}

/// One word whisper.cpp heard, and when (milliseconds); `p` is how sure it was, from 0 to 1.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Word {
    pub text: String,
    pub start: u64,
    pub end: u64,
    pub p: f32,
}

/// One stretch of speech as whisper.cpp wrote it, its words with their times.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Segment {
    pub start: u64,
    pub end: u64,
    pub text: String,
    #[serde(default)]
    pub words: Vec<Word>,
}

/// What whisper.cpp heard in a video: the language (detected, or the one it was told), and what was said.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Transcript {
    pub language: String,
    pub segments: Vec<Segment>,
}

impl Transcript {
    /// The subtitle lines: a segment each, as whisper.cpp ended them, leaving out the silences it marks.
    pub fn lines(&self) -> Vec<Line> {
        self.segments
            .iter()
            .filter(|s| !s.text.is_empty() && !is_marker(&s.text) && s.end > s.start)
            .map(|s| Line { start: s.start, end: s.end, text: s.text.clone() })
            .collect()
    }
}

/// whisper.cpp's word for a stretch with nothing said in it.
fn is_marker(text: &str) -> bool {
    matches!(text, "[BLANK_AUDIO]" | "[ Silence ]" | "[SILENCE]")
}

/// What `transcribe` is doing: turning the sound into WAV, then listening, `percent` of the way through.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Stage {
    Sound,
    Listening { percent: u8 },
}

/// What `transcribe` needs: the folder whisper-cli runs in, and inside it the model, a work folder for this video,
/// the language ("en"), or None to detect it, and how many of the processor's threads it may use.
pub struct Request<'a> {
    pub folder: &'a Path,
    pub model: &'a Path,
    pub work: &'a Path,
    pub language: Option<&'a str>,
    pub threads: usize,
}

/// How many threads whisper.cpp listens with: about one a core (Windows counts each core twice when it runs two
/// threads at once, which gains whisper.cpp little), at least four where there are, and at most twelve.
pub fn threads() -> usize {
    let logical = std::thread::available_parallelism().map_or(4, |n| n.get());
    (logical / 2).max(logical.min(4)).min(12)
}

/// Turns `video`'s sound into WAV with `ffmpeg`, listens to it with `whisper`, and reads what was heard. `stop`
/// turning true stops whichever is running (`SpeechError::Stopped`). The work folder is left for the caller to delete.
pub async fn transcribe(
    ffmpeg: &Path,
    whisper: &Whisper,
    video: &Path,
    request: &Request<'_>,
    stop: &watch::Receiver<bool>,
    on_stage: &mut (dyn FnMut(Stage) + Send),
) -> Result<Transcript, SpeechError> {
    let relative = |path: &Path| path.strip_prefix(request.folder).map(Path::to_path_buf).unwrap_or(path.into());
    tokio::fs::create_dir_all(request.work).await.map_err(|_| SpeechError::CantWrite)?;
    let sound = request.work.join("sound.wav");
    let out = request.work.join("words");

    on_stage(Stage::Sound);
    let mut command = Command::new(ffmpeg);
    command
        .args(["-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i"])
        .arg(video)
        .args(["-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-f", "wav"])
        .arg(&sound);
    if !run(command, stop, &mut |_| {}).await? {
        return Err(SpeechError::NoSound);
    }

    on_stage(Stage::Listening { percent: 0 });
    let mut command = Command::new(&whisper.program);
    command
        .current_dir(request.folder)
        .arg("-m")
        .arg(relative(request.model))
        .arg("-f")
        .arg(relative(&sound))
        .args(["-l", request.language.unwrap_or("auto"), "-t", &request.threads.to_string(), "-ojf", "-pp", "-of"])
        .arg(relative(&out));
    let listened = run(command, stop, &mut |line| {
        if let Some(percent) = progress(line) {
            on_stage(Stage::Listening { percent });
        }
    })
    .await?;
    let json = tokio::fs::read(out.with_extension("json")).await;
    match (listened, json) {
        (true, Ok(json)) => parse(&String::from_utf8_lossy(&json)),
        _ => Err(SpeechError::CouldNotListen),
    }
}

/// Runs `command` until it ends (true when it succeeded) or `stop` turns true (`SpeechError::Stopped`), handing each
/// line it writes to its error stream to `on_line`.
async fn run(
    mut command: Command,
    stop: &watch::Receiver<bool>,
    on_line: &mut (dyn FnMut(&str) + Send),
) -> Result<bool, SpeechError> {
    command.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped()).kill_on_drop(true);
    #[cfg(windows)]
    command.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    let mut child = command.spawn().map_err(|_| SpeechError::NoProgram)?;
    let mut lines = BufReader::new(child.stderr.take().ok_or(SpeechError::NoProgram)?).lines();
    let mut stop = stop.clone();
    loop {
        tokio::select! {
            line = lines.next_line() => match line {
                Ok(Some(line)) => on_line(&line),
                _ => break,
            },
            _ = stop.wait_for(|stopped| *stopped) => return Err(SpeechError::Stopped),
        }
    }
    let status = tokio::select! {
        status = child.wait() => status,
        _ = stop.wait_for(|stopped| *stopped) => return Err(SpeechError::Stopped),
    };
    Ok(status.is_ok_and(|s| s.success()))
}

/// How far whisper-cli says it has got: "whisper_print_progress_callback: progress =  42%".
fn progress(line: &str) -> Option<u8> {
    let (_, rest) = line.split_once("progress =")?;
    let percent: u32 = rest.trim().trim_end_matches('%').trim().parse().ok()?;
    Some(percent.min(100) as u8)
}

/// Reads whisper-cli's full JSON (`-ojf`): the language it heard, and each segment with its words. Tokens that are
/// not words (`[_BEG_]`, `[_TT_150]`) are left out, and a word whisper.cpp split into pieces (" Amer", "icans") is put
/// back together. whisper-cli escapes only quotes and backslashes in what it writes, so a control character in what it
/// heard is taken out before reading.
pub fn parse(json: &str) -> Result<Transcript, SpeechError> {
    let cleaned: String =
        json.chars().map(|c| if c.is_control() && !matches!(c, '\n' | '\r' | '\t') { ' ' } else { c }).collect();
    // A "p" or "t_dtw" whisper-cli could not compute is written as nan or inf, which JSON has no word for.
    let cleaned = cleaned
        .replace(": -nan", ": null")
        .replace(": nan", ": null")
        .replace(": -inf", ": null")
        .replace(": inf", ": null");
    let root: Value = serde_json::from_str(&cleaned).map_err(|_| SpeechError::Unreadable)?;
    let language = root["result"]["language"].as_str().unwrap_or("").to_string();
    let segments = root["transcription"].as_array().ok_or(SpeechError::Unreadable)?;
    let offset = |v: &Value, end: &str| v["offsets"][end].as_u64();
    let segments = segments
        .iter()
        .filter_map(|s| {
            let mut words: Vec<Word> = Vec::new();
            for token in s["tokens"].as_array().into_iter().flatten() {
                let text = token["text"].as_str().unwrap_or("");
                if text.starts_with("[_") || text.is_empty() {
                    continue;
                }
                let (Some(start), Some(end)) = (offset(token, "from"), offset(token, "to")) else { continue };
                let p = token["p"].as_f64().unwrap_or(0.0) as f32;
                match words.last_mut() {
                    // A piece of the word before: no space in front of it.
                    Some(word) if !text.starts_with(' ') => {
                        word.text.push_str(text);
                        word.end = word.end.max(end);
                        word.p = word.p.min(p);
                    }
                    _ => words.push(Word { text: text.trim_start().to_string(), start, end, p }),
                }
            }
            words.retain(|w| !w.text.trim().is_empty());
            Some(Segment {
                start: offset(s, "from")?,
                end: offset(s, "to")?,
                text: s["text"].as_str().unwrap_or("").trim().to_string(),
                words,
            })
        })
        .collect();
    Ok(Transcript { language, segments })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// What whisper-cli 1.9.5 writes with `-ojf` (as `output_json` in examples/cli/cli.cpp does), for the start of
    /// the JFK sample: model and system details, then each segment, its tokens and their times.
    const JFK: &str = include_str!("../../tests/fixtures/whisper-jfk.json");

    #[test]
    fn whisper_cpps_json_is_read_into_segments_and_words() {
        let transcript = parse(JFK).unwrap();
        assert_eq!(transcript.language, "en");
        assert_eq!(transcript.segments.len(), 2);
        let first = &transcript.segments[0];
        assert_eq!((first.start, first.end), (0, 7_600));
        assert_eq!(first.text, "And so my fellow Americans,");
        let words: Vec<&str> = first.words.iter().map(|w| w.text.as_str()).collect();
        assert_eq!(words, ["And", "so", "my", "fellow", "Americans,"], "pieces of a word put back together");
        assert_eq!((first.words[4].start, first.words[4].end), (3_200, 4_400));
        assert!((first.words[4].p - 0.71).abs() < 0.001, "the least sure piece");
        assert_eq!(transcript.segments[1].words.first().map(|w| w.text.as_str()), Some("ask"));
    }

    #[test]
    fn its_lines_are_the_segments_without_the_silences() {
        let mut transcript = parse(JFK).unwrap();
        transcript.segments.push(Segment { start: 11_000, end: 12_000, text: "[BLANK_AUDIO]".into(), words: vec![] });
        let lines = transcript.lines();
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[1].text, "ask not what your country can do for you, ask what you can do for your country.");
        assert_eq!((lines[1].start, lines[1].end), (7_600, 11_000));
    }

    #[test]
    fn what_whisper_cli_writes_that_json_has_no_word_for_is_still_read() {
        let json =
            "{\"result\": {\"language\": \"de\"}, \"transcription\": [{\"offsets\": {\"from\": 0, \"to\": 1000},\
            \"text\": \" Hallo\u{7}\", \"tokens\": [{\"text\": \" Hallo\", \"offsets\": {\"from\": 0, \"to\": 900},\
            \"p\": -nan, \"t_dtw\": -1}]}]}";
        let transcript = parse(json).unwrap();
        assert_eq!(transcript.language, "de");
        assert_eq!(transcript.segments[0].words[0].text, "Hallo");
        assert_eq!(transcript.segments[0].words[0].p, 0.0);
        assert_eq!(parse("not json"), Err(SpeechError::Unreadable));
        assert_eq!(parse("{\"result\": {}}"), Err(SpeechError::Unreadable));
    }

    #[test]
    fn progress_is_read_from_what_whisper_cli_says() {
        assert_eq!(progress("whisper_print_progress_callback: progress =  42%"), Some(42));
        assert_eq!(progress("whisper_print_progress_callback: progress = 100%"), Some(100));
        assert_eq!(progress("whisper_print_progress_callback: progress = 272%"), Some(100));
        assert_eq!(progress("whisper_full_with_state: auto-detected language: en (p = 0.97)"), None);
    }

    #[test]
    fn whisper_listens_with_about_a_thread_a_core() {
        let n = threads();
        assert!((1..=12).contains(&n), "{n}");
    }
}
