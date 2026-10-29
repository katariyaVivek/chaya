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
    /// The subtitle lines: short ones that follow the speech (`lines`), leaving out the silences whisper.cpp marks.
    pub fn lines(&self) -> Vec<Line> {
        super::lines::lines(self)
    }
}

/// whisper.cpp's word for a stretch with nothing said in it.
pub(crate) fn is_marker(text: &str) -> bool {
    matches!(text, "[BLANK_AUDIO]" | "[ Silence ]" | "[SILENCE]")
}

/// What `transcribe` is doing: turning the sound into WAV, then listening, `percent` of the way through.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Stage {
    Sound,
    Listening { percent: u8 },
}

/// What `transcribe` needs: the folder whisper-cli runs in, and inside it the model and which of whisper.cpp's
/// attention-head presets times its words (`dtw`, "large.v3.turbo"), a work folder for this video, the language ("en"),
/// or None to detect it, and how many of the processor's threads it may use.
pub struct Request<'a> {
    pub folder: &'a Path,
    pub model: &'a Path,
    pub dtw: &'a str,
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
        .args(["-l", request.language.unwrap_or("auto"), "-t", &request.threads.to_string(), "-ojf", "-pp"])
        // Each word's time from the attention heads (DTW), which needs flash attention off: with it on, every time
        // comes back as -1.
        .args(["-dtw", request.dtw, "-nfa", "-of"])
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
///
/// A word starts at its first piece's DTW time (`t_dtw`, in hundredths of a second: when the attention heads say it
/// was said). It lasts until its last piece's, which for a word with punctuation after it ("Americans,") is about when
/// it ended, or else twice a usual word's length; never past the next word's start. Without that cap a word followed
/// by silence would run on through it, and the silence is where a line ends. Without DTW times (-1, as with flash
/// attention on), a word has whisper.cpp's own rougher times.
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
    // Whether every word has a DTW time.
    let mut timed = true;
    let mut segments: Vec<Segment> = segments
        .iter()
        .filter_map(|s| {
            let mut words: Vec<Word> = Vec::new();
            for token in s["tokens"].as_array().into_iter().flatten() {
                let text = token["text"].as_str().unwrap_or("");
                if text.starts_with("[_") || text.is_empty() {
                    continue;
                }
                let (Some(from), Some(to)) = (offset(token, "from"), offset(token, "to")) else { continue };
                let dtw = token["t_dtw"].as_i64().filter(|t| *t >= 0).map(|t| t as u64 * 10);
                timed &= dtw.is_some();
                let (start, end) = (dtw.unwrap_or(from), dtw.unwrap_or(to));
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
    if timed {
        end_words(&mut segments);
    }
    Ok(Transcript { language, segments })
}

/// A word whose length is known only from its start lasts no longer than this many times the usual word's.
const LONGEST_WORD: u64 = 2;

/// Ends each word timed by DTW at its last piece's time, or twice a usual word's length after its start if that is
/// later; never after the next word starts, nor the last of a segment after the segment's end.
fn end_words(segments: &mut [Segment]) {
    let starts: Vec<u64> = segments.iter().flat_map(|s| s.words.iter().map(|w| w.start)).collect();
    let mut lengths: Vec<u64> = starts.windows(2).map(|w| w[1].saturating_sub(w[0])).collect();
    lengths.sort_unstable();
    // The usual word: the median gap from one word's start to the next, between a fifth and a half of a second.
    let usual = lengths.get(lengths.len() / 2).copied().unwrap_or(400).clamp(200, 500);
    let mut next = starts.iter().skip(1);
    for segment in segments.iter_mut() {
        let count = segment.words.len();
        for (i, word) in segment.words.iter_mut().enumerate() {
            let mut until = next.next().copied().unwrap_or(u64::MAX);
            if i + 1 == count && segment.end > word.start {
                until = until.min(segment.end);
            }
            word.end = until.min(word.end.max(word.start + LONGEST_WORD * usual)).max(word.start);
        }
    }
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

    /// What whisper-cli 1.9.5 heard, with Large v3 Turbo and its DTW times (`-dtw large.v3.turbo -nfa`), in George W.
    /// Bush's address on the loss of the shuttle Columbia, 1 February 2003 (a work of the US government, in the public
    /// domain; the recording is on Wikimedia Commons), cut down to what `parse` reads.
    const COLUMBIA: &str = include_str!("../../tests/fixtures/whisper-columbia.json");

    #[test]
    fn a_word_starts_at_its_dtw_time_and_ends_at_its_punctuation_or_soon_after() {
        let transcript = parse(COLUMBIA).unwrap();
        let words: Vec<&Word> = transcript.segments.iter().flat_map(|s| &s.words).collect();
        let find = |text: &str| words.iter().find(|w| w.text == text).unwrap();
        // " Americans" at 1.82 s, its comma at 2.88 s; " this" at 3.58 s.
        assert_eq!((find("Americans,").start, find("Americans,").end), (1_820, 2_880));
        assert_eq!(find("this").start, 3_580);
        // " news" at 4.92 s and " and" not until 6.42 s: the word is taken to last twice a usual word, and the rest is
        // the pause after it.
        let news = find("news");
        assert_eq!(news.start, 4_920);
        assert!(news.end > 5_100 && news.end < 6_000, "{news:?}");
        for pair in words.windows(2) {
            assert!(pair[0].start <= pair[0].end && pair[0].end <= pair[1].start, "{pair:?}");
        }
    }

    #[test]
    fn a_recorded_speech_comes_out_in_short_lines_that_follow_it() {
        let transcript = parse(COLUMBIA).unwrap();
        let lines = transcript.lines();
        for line in &lines {
            eprintln!("{:>6} {:>6} {:>4}  {}", line.start, line.end, line.text.chars().count(), line.text);
        }
        assert_eq!(transcript.segments.len(), 39);
        assert!(lines.len() >= 70, "{} lines", lines.len());
        let said: Vec<&str> = transcript.segments.iter().flat_map(|s| s.text.split_whitespace()).collect();
        let shown: Vec<&str> = lines.iter().flat_map(|l| l.text.split_whitespace()).collect();
        assert_eq!(shown, said, "every word, once, in order");
        for pair in lines.windows(2) {
            assert!(pair[0].start < pair[0].end && pair[0].end <= pair[1].start, "{pair:?}");
        }
        for line in &lines {
            let one_word = !line.text.contains(' ');
            assert!(one_word || line.text.chars().count() <= 44, "{line:?}");
            assert!(line.end - line.start <= 5_500, "{line:?}");
            for word in ["the", "a", "of", "to", "our"] {
                assert!(!line.text.ends_with(&format!(" {word}")), "{line:?}");
            }
        }
        let text = |i: usize| lines[i].text.as_str();
        assert!((0..lines.len()).any(|i| text(i) == "My fellow Americans,"), "a pause after a comma");
        assert!((0..lines.len()).all(|i| !text(i).ends_with("overlook the")), "not where whisper.cpp's segment ended");
    }

    #[test]
    fn without_dtw_times_lines_are_cut_by_whisper_cpps_own() {
        let mut transcript = parse(JFK).unwrap();
        transcript.segments.push(Segment { start: 11_000, end: 12_000, text: "[BLANK_AUDIO]".into(), words: vec![] });
        let lines = transcript.lines();
        let texts: Vec<&str> = lines.iter().map(|l| l.text.as_str()).collect();
        assert_eq!(
            texts,
            [
                "And so my fellow Americans,",
                "ask not what your country can do for you,",
                "ask what you can do for your country."
            ]
        );
        assert_eq!(lines[0].start, 0);
        assert_eq!(lines[2].end, 11_000 + 300);
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
