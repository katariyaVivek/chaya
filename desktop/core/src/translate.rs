//! A video's subtitles translated by the person's own AI model (`ai`), as Vimpo's Smart Translation does (docs/
//! vimpo-reference.md §4): every request carries the video's title and the whole transcript, so a misheard name is
//! put right and a term is translated the same way each time, and the lines to translate go out numbered and must
//! come back with the same numbers, one for one, so the translation stays in step with the original.
//!
//! The lines go a batch at a time, small enough for any model. A line a reply leaves out (or merges into the one
//! before) is asked for again on its own. What is translated is handed back after every batch to be kept, so a
//! translation stopped or failed part-way carries on from there and nothing is paid for twice.

use std::time::Duration;

use serde_json::Value;
use tokio::sync::watch;

use crate::ai::{self, AiError, Ask, Endpoint};

/// At most this many lines, and characters of them, in one request.
const BATCH_LINES: usize = 40;
const BATCH_CHARS: usize = 3_000;
/// The whole transcript goes with each request while it is this short; a longer one, the lines around the batch.
const WHOLE: usize = 60_000;
const AROUND: usize = 16_000;
/// How often a line left out of its batch is asked for on its own.
const ALONE: usize = 2;
/// How often a request refused for too many requests is tried again, and the longest wait before it.
const RATE_TRIES: usize = 4;
const LONGEST_WAIT: u64 = 90;
/// How long a translation may run on, for Anthropic's protocol, which asks.
const MAX_TOKENS: u32 = 8_192;

/// What is translated: the video's title, its lines, the language they go into ("Spanish"), and the model.
pub struct Job<'a> {
    pub title: &'a str,
    pub lines: &'a [String],
    pub into: &'a str,
    pub model: &'a str,
}

/// Why a translation stopped short.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TranslateError {
    Ai(AiError),
    /// Every line was asked for, and this many never came back.
    Incomplete(usize),
    Stopped,
}

impl TranslateError {
    pub fn message(&self, service: &str) -> String {
        match self {
            TranslateError::Ai(error) => error.message(service),
            TranslateError::Incomplete(1) => "The model left out a line. Try again: what's translated is kept.".into(),
            TranslateError::Incomplete(n) => {
                format!("The model left out {n} lines. Try again: what's translated is kept.")
            }
            TranslateError::Stopped => "Stopped.".into(),
        }
    }
}

/// Translates `job`'s lines into `done` (one place per line; those already there are kept), and hands `done` to
/// `on_batch` after every batch. `stop` turning true stops it between requests or during one.
pub async fn translate(
    endpoint: &Endpoint,
    job: &Job<'_>,
    done: &mut Vec<Option<String>>,
    stop: &watch::Receiver<bool>,
    on_batch: &mut (dyn FnMut(&[Option<String>]) + Send),
) -> Result<(), TranslateError> {
    done.resize(job.lines.len(), None);
    for batch in batches(job.lines, done) {
        let answered = ask(endpoint, job, &batch, stop).await;
        match answered {
            Ok(lines) => keep(lines, &batch, done),
            // A reply that wasn't the JSON asked for: each line on its own, below.
            Err(TranslateError::Ai(AiError::NotJson)) => {}
            Err(error) => return Err(error),
        }
        let missing: Vec<usize> = batch.iter().copied().filter(|&n| done[n].is_none()).collect();
        for n in missing {
            for _ in 0..ALONE {
                match ask(endpoint, job, &[n], stop).await {
                    Ok(lines) => keep(lines, &[n], done),
                    Err(TranslateError::Ai(AiError::NotJson)) => {}
                    Err(error) => return Err(error),
                }
                if done[n].is_some() {
                    break;
                }
            }
        }
        on_batch(done);
    }
    match done.iter().filter(|line| line.is_none()).count() {
        0 => Ok(()),
        missing => Err(TranslateError::Incomplete(missing)),
    }
}

/// The lines not yet translated, in runs of consecutive lines no longer than a batch.
fn batches(lines: &[String], done: &[Option<String>]) -> Vec<Vec<usize>> {
    let mut batches: Vec<Vec<usize>> = Vec::new();
    let mut chars = 0;
    for n in (0..lines.len()).filter(|&n| done[n].is_none()) {
        let fits = batches.last().is_some_and(|b| {
            b.len() < BATCH_LINES && chars + lines[n].len() <= BATCH_CHARS && b.last() == Some(&(n - 1))
        });
        if !fits {
            batches.push(Vec::new());
            chars = 0;
        }
        chars += lines[n].len();
        batches.last_mut().expect("a batch").push(n);
    }
    batches
}

/// Keeps each translation the reply gave for a line it was asked for; anything else in it is left.
fn keep(lines: Vec<(usize, String)>, asked: &[usize], done: &mut [Option<String>]) {
    for (n, text) in lines {
        if asked.contains(&n) && done[n].is_none() {
            done[n] = Some(text);
        }
    }
}

const SYSTEM: &str = "You translate a video's subtitles. Translate each numbered line on its own and keep its \
number: never merge lines, split one, leave one out or change their order. Use the title and the whole transcript \
to get names and terms right (put right a name the transcript misheard) and to translate each term the same way \
every time. Write natural, short subtitles. Answer with one JSON object and nothing else.";

/// Asks for lines `asked` (numbered from 0 here, from 1 to the model), waiting out a rate limit; what came back, by
/// line.
async fn ask(
    endpoint: &Endpoint,
    job: &Job<'_>,
    asked: &[usize],
    stop: &watch::Receiver<bool>,
) -> Result<Vec<(usize, String)>, TranslateError> {
    let user = question(job, asked);
    let question = Ask { model: job.model, system: SYSTEM, user: &user, max_tokens: MAX_TOKENS };
    let mut stop = stop.clone();
    for tried in 1.. {
        if *stop.borrow() {
            return Err(TranslateError::Stopped);
        }
        let reply = tokio::select! {
            reply = ai::ask_json(endpoint, &question, |v| v["lines"].is_array()) => reply,
            _ = stop.wait_for(|stopped| *stopped) => return Err(TranslateError::Stopped),
        };
        match reply {
            Ok(reply) => return Ok(read(&reply, job.lines.len())),
            Err(AiError::RateLimited(after)) if tried < RATE_TRIES => {
                let wait = Duration::from_secs(after.unwrap_or(20).min(LONGEST_WAIT));
                tokio::select! {
                    _ = tokio::time::sleep(wait) => {}
                    _ = stop.wait_for(|stopped| *stopped) => return Err(TranslateError::Stopped),
                }
            }
            Err(error) => return Err(TranslateError::Ai(error)),
        }
    }
    unreachable!("the loop returns")
}

/// The question for lines `asked`: the title, the transcript (all of it, or the lines around), and the lines.
fn question(job: &Job<'_>, asked: &[usize]) -> String {
    let numbered = |n: usize| format!("{}: {}", n + 1, job.lines[n].replace('\n', " "));
    let whole: usize = job.lines.iter().map(|l| l.len() + 8).sum();
    let (first, last) = (asked[0], asked[asked.len() - 1]);
    let around = if whole <= WHOLE {
        0..job.lines.len()
    } else {
        // As many lines either side as fit, half before and half after.
        let mut from = first;
        let mut to = last + 1;
        let mut room = AROUND;
        while room > 0 && (from > 0 || to < job.lines.len()) {
            if from > 0 {
                from -= 1;
                room = room.saturating_sub(job.lines[from].len() + 8);
            }
            if to < job.lines.len() {
                room = room.saturating_sub(job.lines[to].len() + 8);
                to += 1;
            }
        }
        from..to
    };
    let transcript: Vec<String> = around.map(numbered).collect();
    let lines: Vec<String> = asked.iter().map(|&n| numbered(n)).collect();
    let numbers: Vec<String> = asked.iter().map(|n| (n + 1).to_string()).collect();
    format!(
        "Video title: {title}\n\nThe transcript, for context (number: line):\n{transcript}\n\nTranslate these lines \
         into {into}. Answer with {{\"lines\": [{{\"n\": number, \"text\": \"the translation\"}}]}}, one item for each \
         of the numbers {numbers} and no others:\n{lines}",
        title = job.title,
        transcript = transcript.join("\n"),
        into = job.into,
        numbers = numbers.join(", "),
        lines = lines.join("\n"),
    )
}

/// The lines in a reply, by line (from 0), each translation trimmed and not empty.
fn read(reply: &Value, count: usize) -> Vec<(usize, String)> {
    reply["lines"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|item| {
            let n = item["n"].as_u64().or_else(|| item["n"].as_str()?.trim().parse().ok())? as usize;
            let text = item["text"].as_str()?.trim();
            (n >= 1 && n <= count && !text.is_empty()).then(|| (n - 1, text.to_string()))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use serde_json::json;

    use super::*;
    use crate::ai::stand_in::{Answer, Sent, StandIn};
    use crate::ai::Settings;

    fn endpoint(stand_in: &StandIn) -> Endpoint {
        let settings =
            Settings { service: Some("openai".into()), address: Some(stand_in.url("/v1")), model: Some("m".into()) };
        Endpoint::new(&settings, Some("k".into())).unwrap()
    }

    /// The numbers a request asks for, from its "one item for each of the numbers …" line.
    fn asked(sent: &Sent) -> Vec<usize> {
        let body: Value = serde_json::from_str(&sent.body).unwrap();
        let user = body["messages"][1]["content"].as_str().unwrap().to_string();
        let numbers = user.split("each of the numbers ").nth(1).unwrap().split(" and no others").next().unwrap();
        numbers.split(", ").map(|n| n.parse().unwrap()).collect()
    }

    fn reply(items: Vec<(usize, String)>) -> Answer {
        let lines: Vec<Value> = items.into_iter().map(|(n, text)| json!({"n": n, "text": text})).collect();
        let content = json!({"lines": lines}).to_string();
        Answer::ok(json!({"choices": [{"message": {"content": content}}]}).to_string())
    }

    /// A model that answers each line asked for with "ES n", except as `leave(n, alone)` says.
    fn model(leave: impl Fn(usize, bool) -> bool + Send + Sync + 'static) -> StandIn {
        StandIn::answering(move |sent| {
            let asked = asked(sent);
            let alone = asked.len() == 1;
            reply(asked.into_iter().filter(|&n| !leave(n, alone)).map(|n| (n, format!("ES {n}"))).collect())
        })
    }

    fn lines(count: usize) -> Vec<String> {
        (1..=count).map(|n| format!("Line number {n} of the talk.")).collect()
    }

    async fn run(stand_in: &StandIn, lines: &[String], done: &mut Vec<Option<String>>) -> Result<(), TranslateError> {
        let job = Job { title: "A walk in the park", lines, into: "Spanish", model: "small" };
        let (_tx, stop) = watch::channel(false);
        translate(&endpoint(stand_in), &job, done, &stop, &mut |_| {}).await
    }

    #[tokio::test]
    async fn every_line_goes_out_numbered_in_batches_and_comes_back_in_step() {
        let stand_in = model(|_, _| false);
        let source = lines(100);
        let mut done = Vec::new();
        run(&stand_in, &source, &mut done).await.unwrap();
        let expected: Vec<Option<String>> = (1..=100).map(|n| Some(format!("ES {n}"))).collect();
        assert_eq!(done, expected);
        let sent = stand_in.sent();
        assert_eq!(sent.len(), 3, "100 lines in batches of 40");
        assert_eq!(asked(&sent[0]), (1..=40).collect::<Vec<_>>());
        assert_eq!(asked(&sent[2]), (81..=100).collect::<Vec<_>>());
        // Each batch carries the title and the whole transcript.
        let body: Value = serde_json::from_str(&sent[2].body).unwrap();
        let user = body["messages"][1]["content"].as_str().unwrap();
        assert!(user.contains("Video title: A walk in the park"));
        assert!(user.contains("1: Line number 1 of the talk.") && user.contains("100: Line number 100 of the talk."));
        assert!(user.contains("into Spanish"));
    }

    #[tokio::test]
    async fn a_line_the_reply_leaves_out_is_asked_for_on_its_own() {
        // Line 7 is dropped from its batch, but given when asked alone.
        let stand_in = model(|n, alone| n == 7 && !alone);
        let mut done = Vec::new();
        run(&stand_in, &lines(10), &mut done).await.unwrap();
        assert_eq!(done[6].as_deref(), Some("ES 7"));
        let sent = stand_in.sent();
        assert_eq!(sent.len(), 2);
        assert_eq!(asked(&sent[1]), [7]);
    }

    #[tokio::test]
    async fn two_lines_merged_into_one_leave_the_second_to_be_asked_again() {
        // Lines 4 and 5 come back as one item numbered 4, as a model that merges them answers.
        let stand_in = StandIn::answering(|sent| {
            let asked = asked(sent);
            if asked.len() == 1 {
                return reply(vec![(asked[0], format!("ES {}", asked[0]))]);
            }
            let items = asked.into_iter().filter(|&n| n != 5).map(|n| {
                let text = if n == 4 { "ES 4 y ES 5".to_string() } else { format!("ES {n}") };
                (n, text)
            });
            reply(items.collect())
        });
        let mut done = Vec::new();
        run(&stand_in, &lines(8), &mut done).await.unwrap();
        assert_eq!(done[4].as_deref(), Some("ES 5"), "every line has its own, in step");
        assert_eq!(done.iter().flatten().count(), 8);
    }

    #[tokio::test]
    async fn a_line_never_given_leaves_the_rest_kept_and_says_how_many() {
        let stand_in = model(|n, _| n == 3);
        let mut done = Vec::new();
        let result = run(&stand_in, &lines(5), &mut done).await;
        assert_eq!(result, Err(TranslateError::Incomplete(1)));
        assert_eq!(done.iter().flatten().count(), 4);
        assert_eq!(stand_in.sent().len(), 1 + ALONE, "the batch, then the line alone twice");
        // Asked again, only the line missing goes out: nothing is paid for twice.
        let stand_in = model(|_, _| false);
        run(&stand_in, &lines(5), &mut done).await.unwrap();
        assert_eq!(stand_in.sent().iter().map(asked).collect::<Vec<_>>(), [vec![3]]);
    }

    #[tokio::test]
    async fn a_rate_limit_is_waited_out_and_a_refused_key_stops_it() {
        let tries = Arc::new(Mutex::new(0));
        let counted = tries.clone();
        let stand_in = StandIn::answering(move |sent| {
            *counted.lock().unwrap() += 1;
            if *counted.lock().unwrap() == 1 {
                return Answer { status: 429, headers: vec![("Retry-After", "0")], body: "{}".into() };
            }
            reply(asked(sent).into_iter().map(|n| (n, format!("ES {n}"))).collect())
        });
        let mut done = Vec::new();
        run(&stand_in, &lines(3), &mut done).await.unwrap();
        assert_eq!(*tries.lock().unwrap(), 2);
        let stand_in = StandIn::answering(|_| Answer::status(401, r#"{"error": {"message": "bad key"}}"#));
        let mut done = Vec::new();
        assert_eq!(run(&stand_in, &lines(3), &mut done).await, Err(TranslateError::Ai(AiError::RefusedKey)));
    }

    #[test]
    fn a_long_transcript_goes_as_the_lines_around_the_batch() {
        let source: Vec<String> = (1..=3000).map(|n| format!("A rather long line of the talk, number {n}.")).collect();
        let job = Job { title: "Long", lines: &source, into: "Hindi", model: "m" };
        let asked: Vec<usize> = (1500..1540).collect();
        let question = question(&job, &asked);
        assert!(question.len() < AROUND + 8_000, "{}", question.len());
        assert!(question.contains("1490: ") && question.contains("1550: "));
        assert!(!question.contains("\n1: "), "not the start of a long video");
    }

    #[test]
    fn batches_are_runs_of_lines_not_yet_translated() {
        let source = lines(10);
        let mut done = vec![None; 10];
        done[2] = Some("x".into());
        done[3] = Some("x".into());
        assert_eq!(batches(&source, &done), [vec![0, 1], vec![4, 5, 6, 7, 8, 9]]);
        let long: Vec<String> = (0..5).map(|_| "x".repeat(1_400)).collect();
        assert_eq!(batches(&long, &[None, None, None, None, None]), [vec![0, 1], vec![2, 3], vec![4]], "by characters");
    }

    #[test]
    fn a_reply_s_lines_are_read_by_number_whatever_the_number_s_type() {
        let reply = json!({"lines": [{"n": 1, "text": " Hola "}, {"n": "2", "text": "Adiós"}, {"n": 9, "text": "x"},
                                     {"n": 3, "text": ""}]});
        assert_eq!(read(&reply, 3), [(0, "Hola".to_string()), (1, "Adiós".to_string())]);
    }
}
