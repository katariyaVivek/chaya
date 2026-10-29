//! Subtitle lines that follow the speech, from the words whisper.cpp heard and when each was said: whisper.cpp's own
//! segments run to fifteen seconds and two hundred characters, more than anyone reads in one go. The rules are
//! Vimpo's (docs/vimpo-reference.md §4). What was said is first cut where a sentence ends and at clear pauses;
//! whatever is still longer than five seconds or about forty characters is cut again at its best gap between two
//! words, and the halves again, until every line fits. A gap scores higher after a comma, at a longer pause, before a
//! word that starts a clause ("and", "but", "because") or a preposition, and when it leaves the halves alike; lower
//! after a word that leads into the next ("the", "of", "to"), between an adjective and its noun, and when it leaves a
//! half too short. A piece too small to read on its own joins a neighbour. Each line shows 0.2 s before its first
//! word and stays 0.3 s after its last, and at least a second where there is room.
//!
//! The word lists are English's first, then those of other languages Chaya's people use. Languages written without
//! spaces between words (Chinese, Japanese, Thai) need rules of their own: their lines stay whisper.cpp's segments.

use crate::subtitles::Line;

use super::whisper::{is_marker, Transcript, Word};

/// A line longer than this is cut, unless it is one word.
const MOST_CHARS: usize = 44;
/// Milliseconds. A line said over longer than this is cut, unless it is one word.
const LONGEST: u64 = 5_000;
/// A pause this long between two words ends a line, unless the word before leads into the next ("of", "the"): then
/// only a pause twice as long does.
const CLEAR_PAUSE: u64 = 700;
/// A half shorter than this scores low: a line of two words flashes by.
const TOO_SHORT: usize = 12;
/// A piece this short, or said this quickly, joins a neighbour when the two fit on one line.
const TINY_CHARS: usize = 10;
const TINY_TIME: u64 = 600;
/// How much earlier than its first word a line shows, and how much longer than its last it stays.
const EARLY: u64 = 200;
const LATE: u64 = 300;
/// A line stays on screen at least this long, where the next one leaves room.
const SHORTEST_SHOWN: u64 = 1_000;

/// The subtitle lines for what whisper.cpp heard.
pub fn lines(transcript: &Transcript) -> Vec<Line> {
    let spoken = || transcript.segments.iter().filter(|s| !s.text.is_empty() && !is_marker(&s.text));
    let words = Words::of(&transcript.language);
    if words.unspaced {
        let segments = spoken().filter(|s| s.end > s.start).map(|s| (s.start, s.end, s.text.clone()));
        return shown(segments.collect());
    }
    // whisper.cpp's segments end where its window of sound does as often as where a sentence does ("overlook the" |
    // "dangers of travel"), so its words are taken as one stream.
    let all: Vec<&Word> = spoken().flat_map(|s| &s.words).filter(|w| !w.text.trim().is_empty()).collect();
    let mut pieces = Vec::new();
    let mut from = 0;
    for at in 1..=all.len() {
        let cut = at == all.len()
            || words.ends_sentence(&all[at - 1].text)
            || pause(all[at - 1], all[at])
                >= CLEAR_PAUSE * if words.leads_on(&bare(&all[at - 1].text)) { 2 } else { 1 };
        if cut {
            split(&all, from, at, &words, &mut pieces);
            from = at;
        }
    }
    let pieces = join_tiny(&all, pieces);
    shown(pieces.into_iter().map(|(from, to)| (all[from].start, all[to - 1].end, text(&all[from..to]))).collect())
}

/// The silence between two words, in milliseconds.
fn pause(before: &Word, after: &Word) -> u64 {
    after.start.saturating_sub(before.end)
}

fn text(words: &[&Word]) -> String {
    words.iter().map(|w| w.text.trim()).collect::<Vec<_>>().join(" ")
}

fn chars(words: &[&Word]) -> usize {
    words.iter().map(|w| w.text.trim().chars().count()).sum::<usize>() + words.len().saturating_sub(1)
}

fn fits(words: &[&Word]) -> bool {
    let time = words.last().map_or(0, |w| w.end).saturating_sub(words.first().map_or(0, |w| w.start));
    words.len() <= 1 || (chars(words) <= MOST_CHARS && time <= LONGEST)
}

/// Cuts words `from..to` into pieces that fit, at the best gap and then each half's, onto `pieces`.
fn split(all: &[&Word], from: usize, to: usize, words: &Words, pieces: &mut Vec<(usize, usize)>) {
    if fits(&all[from..to]) {
        pieces.push((from, to));
        return;
    }
    let best = (from + 1..to)
        .map(|at| (at, score(&all[from..to], at - from, words)))
        .fold((from + 1, f32::MIN), |best, gap| if gap.1 > best.1 { gap } else { best })
        .0;
    split(all, from, best, words, pieces);
    split(all, best, to, words, pieces);
}

/// How good a place the gap before `line[at]` is to end a line.
fn score(line: &[&Word], at: usize, words: &Words) -> f32 {
    let (before, after) = (line[at - 1], line[at]);
    let last = before.text.trim_end().chars().last().unwrap_or(' ');
    let marked = !last.is_alphanumeric();
    let mut score = 0.0;
    if words.ends_sentence(&before.text) {
        score += 5.0;
    } else if matches!(last, ';' | ':' | '—' | '–') {
        score += 3.5;
    } else if matches!(last, ',' | '،' | '、' | '，') {
        score += 3.0;
    }
    // A point for each quarter second of silence, up to four.
    score += (pause(before, after) as f32 / 250.0).min(4.0);
    let (prev, next) = (bare(&before.text), bare(&after.text));
    if words.starts_clause(&next) {
        score += 2.0;
    } else if words.prepositions.contains(&next.as_str()) {
        score += 1.0;
    }
    if !marked && words.leads_on(&prev) {
        score -= 4.0;
    }
    if words.follows_on(&next) {
        score -= 4.0;
    }
    if !marked && words.adjective_before_noun(&prev, &next) {
        score -= 2.0;
    }
    let (a, b) = (chars(&line[..at]), chars(&line[at..]));
    score += 2.0 * (1.0 - a.abs_diff(b) as f32 / (a + b).max(1) as f32);
    if a.min(b) < TOO_SHORT {
        score -= 4.0;
    }
    score
}

/// A word as the lists hold it: lower case, without the punctuation around it.
fn bare(word: &str) -> String {
    word.trim_matches(|c: char| !c.is_alphanumeric() && c != '\'').to_lowercase()
}

/// Joins each piece too small to read on its own to the neighbour it is closer to in time, when the two fit.
fn join_tiny(all: &[&Word], mut pieces: Vec<(usize, usize)>) -> Vec<(usize, usize)> {
    let tiny = |(from, to): (usize, usize)| {
        let words = &all[from..to];
        chars(words) < TINY_CHARS || words[words.len() - 1].end.saturating_sub(words[0].start) < TINY_TIME
    };
    let mut i = 0;
    while i < pieces.len() {
        if !tiny(pieces[i]) {
            i += 1;
            continue;
        }
        let (from, to) = pieces[i];
        let joined = |a: usize, b: usize| fits(&all[a..b]);
        let before = (i > 0 && joined(pieces[i - 1].0, to)).then(|| pause(all[from - 1], all[from]));
        let after = (i + 1 < pieces.len() && joined(from, pieces[i + 1].1)).then(|| pause(all[to - 1], all[to]));
        match (before, after) {
            (Some(b), a) if a.is_none_or(|a| b <= a) => {
                pieces[i - 1].1 = to;
                pieces.remove(i);
                i -= 1;
            }
            (_, Some(_)) => {
                pieces[i + 1].0 = from;
                pieces.remove(i);
            }
            _ => i += 1,
        }
    }
    pieces
}

/// The lines as shown: each from a little before its first word to a little after its last, and at least a second
/// where the next leaves room; never over the next, nor ending before its own last word.
fn shown(said: Vec<(u64, u64, String)>) -> Vec<Line> {
    let mut lines: Vec<Line> = said
        .iter()
        .map(|(start, end, text)| {
            let start = start.saturating_sub(EARLY);
            Line { start, end: (end + LATE).max(start + SHORTEST_SHOWN), text: text.clone() }
        })
        .collect();
    for i in 1..lines.len() {
        if lines[i - 1].end > lines[i].start {
            // The next line comes in early, unless the one before is still being said.
            let cut = lines[i].start.max(said[i - 1].1);
            lines[i - 1].end = cut;
            lines[i].start = cut;
        }
        lines[i].end = lines[i].end.max(lines[i].start + 1);
    }
    lines
}

/// The words a language's lines are cut by.
struct Words {
    /// Written without spaces between words: no word lists.
    unspaced: bool,
    /// Words that start a clause: a line may well start with them.
    clause: &'static [&'static str],
    /// Words that lead into the next ("the", "of", "to"): a line should not end with them.
    leading: &'static [&'static str],
    /// Prepositions, which a line may start with ("| with our space shuttle"), if less well than a clause.
    prepositions: &'static [&'static str],
    /// Words that follow on from the one before (Hindi's postpositions): a line should not start with them.
    following: &'static [&'static str],
    /// Words that end a sentence with a point, but are not a sentence's end ("Mr.", "Dr.").
    abbreviations: &'static [&'static str],
    /// English only: adjectives, kept on a line with their noun.
    adjectives: &'static [&'static str],
}

impl Words {
    /// The lists for `language` ("en"), or none for a language Chaya has none for.
    fn of(language: &str) -> Words {
        let none = Words {
            unspaced: false,
            clause: &[],
            leading: &[],
            prepositions: &[],
            following: &[],
            abbreviations: &[],
            adjectives: &[],
        };
        match language {
            "zh" | "ja" | "th" | "lo" | "my" | "km" | "yue" | "bo" => Words { unspaced: true, ..none },
            "en" => Words {
                unspaced: false,
                clause: &[
                    "and", "but", "because", "or", "so", "which", "when", "while", "if", "although", "though", "since",
                    "unless", "until", "where", "whereas", "who", "whose", "yet", "then", "before", "after",
                ],
                leading: &[
                    "the", "a", "an", "of", "to", "in", "on", "at", "for", "with", "by", "from", "into", "onto",
                    "about", "my", "your", "his", "her", "its", "our", "their", "this", "these", "those", "that's",
                    "i", "i'm", "i've", "i'll", "i'd", "we're", "you're", "they're", "it's", "is", "are", "was",
                    "were", "be", "been", "very", "really", "some", "any", "every", "each", "no", "not", "and", "or",
                    "but", "can", "could", "will", "would", "should", "must", "might", "may", "do", "does", "did",
                    "don't", "didn't", "doesn't", "can't", "won't", "have", "has", "had", "as", "than", "more", "most",
                    "less", "such", "how", "what", "which", "who", "whose",
                ],
                prepositions: &[
                    // Not "to", which leads into a verb as often ("has come | to seem").
                    "of", "in", "on", "at", "for", "with", "by", "from", "into", "onto", "about", "over", "under",
                    "through", "across", "against", "among", "beyond", "without", "within", "during", "like",
                ],
                following: &["'s", "n't"],
                abbreviations: &[
                    "mr", "mrs", "ms", "dr", "prof", "st", "sr", "jr", "vs", "etc", "e.g", "i.e", "u.s", "u.k", "no",
                    "vol", "approx", "inc", "ltd", "co", "mt", "ft",
                ],
                adjectives: &[
                    "new",
                    "old",
                    "good",
                    "great",
                    "big",
                    "small",
                    "little",
                    "long",
                    "short",
                    "high",
                    "low",
                    "young",
                    "important",
                    "few",
                    "last",
                    "first",
                    "next",
                    "other",
                    "own",
                    "same",
                    "right",
                    "wrong",
                    "different",
                    "large",
                    "best",
                    "better",
                    "worse",
                    "worst",
                    "bad",
                    "real",
                    "whole",
                    "free",
                    "full",
                    "easy",
                    "hard",
                    "early",
                    "late",
                    "major",
                    "main",
                    "only",
                    "true",
                    "simple",
                    "open",
                    "close",
                    "nice",
                    "beautiful",
                    "huge",
                    "tiny",
                    "entire",
                    "certain",
                    "special",
                    "strong",
                    "clear",
                    "black",
                    "white",
                    "red",
                    "blue",
                    "green",
                    "dark",
                    "light",
                    "deep",
                    "hot",
                    "cold",
                    "fast",
                    "slow",
                    "rich",
                    "poor",
                    "single",
                    "local",
                    "national",
                    "public",
                    "private",
                    "human",
                    "social",
                    "political",
                    "american",
                    "several",
                    "many",
                    "much",
                    "two",
                    "three",
                    "four",
                    "five",
                    "six",
                    "seven",
                    "eight",
                    "nine",
                    "ten",
                    "hundred",
                    "thousand",
                    "million",
                    "billion",
                ],
            },
            "es" => Words {
                clause: &["y", "pero", "porque", "o", "aunque", "cuando", "mientras", "si", "donde", "pues", "sino"],
                leading: &[
                    "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "a", "al", "en", "con", "por",
                    "para", "sin", "sobre", "mi", "tu", "su", "mis", "tus", "sus", "nuestro", "nuestra", "este",
                    "esta", "ese", "esa", "que", "no", "muy", "más", "y", "o", "lo", "se", "me", "te", "le",
                ],
                prepositions: &["de", "a", "en", "con", "por", "para", "sin", "sobre", "desde", "hasta", "entre"],
                abbreviations: &["sr", "sra", "dr", "dra", "etc", "ud", "uds"],
                ..none
            },
            "pt" => Words {
                clause: &["e", "mas", "porque", "ou", "embora", "quando", "enquanto", "se", "onde", "pois"],
                leading: &[
                    "o", "a", "os", "as", "um", "uma", "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas",
                    "com", "por", "para", "pelo", "pela", "sem", "meu", "minha", "seu", "sua", "este", "esta", "esse",
                    "essa", "que", "não", "muito", "mais", "e", "ou", "se", "me", "te", "lhe",
                ],
                prepositions: &["de", "em", "com", "por", "para", "sem", "sobre", "desde", "até", "entre"],
                abbreviations: &["sr", "sra", "dr", "dra", "etc"],
                ..none
            },
            "fr" => Words {
                clause: &["et", "mais", "parce", "ou", "donc", "car", "quand", "lorsque", "si", "puisque", "alors"],
                leading: &[
                    "le", "la", "les", "l'", "un", "une", "des", "de", "du", "d'", "à", "au", "aux", "en", "dans",
                    "sur", "avec", "pour", "par", "sans", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses",
                    "notre", "votre", "leur", "ce", "cette", "ces", "que", "qu'", "ne", "très", "plus", "et", "ou",
                    "je", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles", "se", "me", "te",
                ],
                prepositions: &["de", "à", "en", "dans", "sur", "avec", "pour", "par", "sans", "chez", "vers", "entre"],
                abbreviations: &["m", "mme", "dr", "etc"],
                ..none
            },
            "it" => Words {
                clause: &["e", "ma", "perché", "o", "anche", "quando", "mentre", "se", "dove", "però", "quindi"],
                leading: &[
                    "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "di", "del", "della", "a", "al", "alla",
                    "da", "in", "nel", "nella", "con", "su", "per", "tra", "fra", "mio", "mia", "suo", "sua", "questo",
                    "questa", "quel", "quella", "che", "non", "molto", "più", "e", "o", "si",
                ],
                prepositions: &["di", "a", "da", "in", "con", "su", "per", "tra", "fra", "senza", "verso"],
                abbreviations: &["sig", "dott", "ecc"],
                ..none
            },
            "de" => Words {
                clause: &["und", "aber", "weil", "oder", "denn", "dass", "wenn", "als", "obwohl", "während", "sondern"],
                leading: &[
                    "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines", "von",
                    "zu", "zum", "zur", "in", "im", "an", "am", "auf", "mit", "für", "bei", "aus", "nach", "über",
                    "unter", "mein", "meine", "dein", "sein", "seine", "ihr", "ihre", "unser", "dieser", "diese",
                    "dieses", "nicht", "sehr", "und", "oder", "ich", "du", "er", "sie", "es", "wir",
                ],
                prepositions: &[
                    "von", "zu", "in", "an", "auf", "mit", "für", "bei", "aus", "nach", "über", "unter", "ohne",
                ],
                abbreviations: &["hr", "fr", "dr", "z.b", "usw", "bzw", "ca", "nr"],
                ..none
            },
            "hi" => Words {
                clause: &["और", "लेकिन", "क्योंकि", "या", "तो", "जब", "अगर", "जो", "कि", "मगर", "इसलिए"],
                leading: &["यह", "वह", "ये", "वो", "एक", "मेरा", "मेरी", "मेरे", "आपका", "आपकी", "उसका", "उसकी", "बहुत"],
                // Postpositions and the verb's helpers come after the word they go with.
                following: &[
                    "का",
                    "की",
                    "के",
                    "में",
                    "से",
                    "को",
                    "ने",
                    "पर",
                    "तक",
                    "है",
                    "हैं",
                    "था",
                    "थी",
                    "थे",
                    "हूँ",
                    "हो",
                    "गया",
                    "गई",
                    "रहा",
                    "रही",
                    "रहे",
                    "वाला",
                    "वाली",
                    "वाले",
                    "भी",
                    "ही",
                ],
                ..none
            },
            _ => none,
        }
    }

    fn ends_sentence(&self, word: &str) -> bool {
        let word = word.trim_end_matches(['"', '\'', '”', '’', ')', ']', '»']);
        let Some(last) = word.chars().last() else { return false };
        match last {
            '?' | '!' | '…' | '।' | '॥' | '؟' | '。' | '？' | '！' => true,
            '.' => {
                let bare = word.trim_end_matches('.').trim_start_matches(|c: char| !c.is_alphanumeric());
                let lower = bare.to_lowercase();
                // "Mr." and "U.S." are not a sentence's end, nor is a single letter ("John F. Kennedy").
                !(self.abbreviations.contains(&lower.as_str()) || bare.chars().count() == 1 || bare.contains('.'))
            }
            _ => false,
        }
    }

    fn starts_clause(&self, word: &str) -> bool {
        self.clause.contains(&word)
    }

    fn leads_on(&self, word: &str) -> bool {
        self.leading.contains(&word)
    }

    fn follows_on(&self, word: &str) -> bool {
        self.following.contains(&word)
    }

    /// `prev` is an adjective (or a number) and `next` could be its noun: not a word the lists know the part of.
    fn adjective_before_noun(&self, prev: &str, next: &str) -> bool {
        if self.adjectives.is_empty() {
            return false;
        }
        let adjective = self.adjectives.contains(&prev)
            || prev.chars().all(|c| c.is_ascii_digit())
            || (prev.len() > 5
                && ["ful", "ous", "ive", "able", "ible", "less", "ical", "ish"].iter().any(|s| prev.ends_with(s)));
        let noun = !(next.is_empty()
            || self.leading.contains(&next)
            || self.clause.contains(&next)
            || self.adjectives.contains(&next));
        adjective && noun
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::speech::whisper::Segment;

    /// Words said one after another from `start`, each over `each` milliseconds, with `gap` between them.
    fn said(text: &str, start: u64, each: u64, gap: u64) -> Vec<Word> {
        let mut at = start;
        text.split(' ')
            .map(|w| {
                let word = Word { text: w.into(), start: at, end: at + each, p: 0.9 };
                at += each + gap;
                word
            })
            .collect()
    }

    fn transcript(language: &str, segments: Vec<Vec<Word>>) -> Transcript {
        let segments = segments
            .into_iter()
            .map(|words| Segment {
                start: words[0].start,
                end: words[words.len() - 1].end,
                text: text(&words.iter().collect::<Vec<_>>()),
                words,
            })
            .collect();
        Transcript { language: language.into(), segments }
    }

    fn texts(lines: &[Line]) -> Vec<&str> {
        lines.iter().map(|l| l.text.as_str()).collect()
    }

    /// The score of the gap after `word` (its first appearance) in `text`, said evenly.
    fn gap_after(text: &str, word: &str) -> f32 {
        let words = said(text, 0, 300, 50);
        let line: Vec<&Word> = words.iter().collect();
        let at = line.iter().position(|w| w.text == word).unwrap() + 1;
        score(&line, at, &Words::of("en"))
    }

    #[test]
    fn a_line_that_fits_is_left_whole() {
        let t = transcript("en", vec![said("Ask not what your country can do for you.", 0, 300, 50)]);
        assert_eq!(texts(&lines(&t)), ["Ask not what your country can do for you."]);
    }

    #[test]
    fn sentences_end_lines_but_mr_and_initials_do_not() {
        let t = transcript("en", vec![said("It rained all day. Then Mr. Smith and John F. Kennedy came.", 0, 250, 20)]);
        assert_eq!(texts(&lines(&t)), ["It rained all day.", "Then Mr. Smith and John F. Kennedy came."]);
    }

    #[test]
    fn a_clear_pause_ends_a_line() {
        let mut words = said("we waited for the bus", 0, 250, 20);
        words.extend(said("and nobody came at all", 3_000, 250, 20));
        let t = transcript("en", vec![words]);
        assert_eq!(texts(&lines(&t)), ["we waited for the bus", "and nobody came at all"]);
    }

    #[test]
    fn a_pause_after_of_or_the_ends_a_line_only_when_it_is_long() {
        let mut words = said("we are all thinking of", 0, 250, 20);
        words.extend(said("the families", 2_080, 400, 20));
        assert_eq!(texts(&lines(&transcript("en", vec![words]))), ["we are all thinking of the families"]);
        let mut words = said("we are all thinking of", 0, 250, 20);
        words.extend(said("the families", 3_000, 400, 20));
        assert_eq!(texts(&lines(&transcript("en", vec![words]))), ["we are all thinking of", "the families"]);
    }

    #[test]
    fn a_gap_before_a_preposition_scores_higher_than_inside_a_noun() {
        let text = "Mission Control lost contact with our space shuttle Columbia.";
        assert!(gap_after(text, "contact") > gap_after(text, "space"));
        assert!(gap_after(text, "contact") > gap_after(text, "lost"));
    }

    #[test]
    fn a_comma_scores_higher_than_no_comma() {
        let text = "the people who came yesterday, saw everything we built for them";
        assert!(gap_after(text, "yesterday,") > gap_after(text, "came"));
        assert!(gap_after(text, "yesterday,") > gap_after(text, "saw"));
    }

    #[test]
    fn a_longer_pause_scores_higher() {
        let en = Words::of("en");
        let with_pause = |pause: u64| {
            let mut words = said("one two three", 0, 250, 20);
            words.extend(said("four five six", 790 + pause, 250, 20));
            words
        };
        let gap = |words: &[Word]| score(&words.iter().collect::<Vec<_>>(), 3, &en);
        assert!(gap(&with_pause(300)) > gap(&with_pause(20)));
        assert!(gap(&with_pause(600)) > gap(&with_pause(300)));
    }

    #[test]
    fn a_gap_before_and_but_because_scores_higher() {
        let text = "she wanted to stay much longer but the train was leaving soon";
        assert!(gap_after(text, "longer") > gap_after(text, "much"));
        assert!(gap_after(text, "longer") > gap_after(text, "but"));
    }

    #[test]
    fn a_gap_after_the_of_or_to_scores_lower() {
        let text = "we drove all the way down to the river every single summer";
        assert!(gap_after(text, "the") < gap_after(text, "all"));
        assert!(gap_after(text, "to") < gap_after(text, "down"));
    }

    #[test]
    fn a_gap_between_an_adjective_and_its_noun_scores_lower() {
        let text = "they all came to see the beautiful house we built last summer";
        assert!(gap_after(text, "beautiful") < gap_after(text, "house"));
        let text = "they built three houses near the lake";
        assert!(gap_after(text, "three") < gap_after(text, "houses"));
    }

    #[test]
    fn balanced_halves_score_higher_and_a_half_too_short_scores_lowest() {
        let text = "alpha bravo charlie delta echo foxtrot golf hotel india juliet";
        assert!(gap_after(text, "echo") > gap_after(text, "charlie"));
        assert!(gap_after(text, "charlie") > gap_after(text, "alpha"));
        assert!(gap_after(text, "india") < gap_after(text, "golf"));
    }

    #[test]
    fn a_long_sentence_is_cut_until_every_line_fits() {
        let t = transcript(
            "en",
            vec![said(
                "and so my fellow Americans the time has come for all of us to ask what we can do for our \
                 country and what our country can do for the world we share with everyone else",
                0,
                280,
                30,
            )],
        );
        let lines = lines(&t);
        assert!(lines.len() >= 4, "{:?}", texts(&lines));
        for line in &lines {
            assert!(line.text.chars().count() <= MOST_CHARS, "{}", line.text);
            assert!(line.end - line.start <= LONGEST + EARLY + LATE, "{line:?}");
            for word in ["the", "of", "to", "our"] {
                assert!(!line.text.ends_with(&format!(" {word}")), "{}", line.text);
            }
        }
        assert_eq!(texts(&lines).join(" "), t.segments[0].text);
    }

    #[test]
    fn a_tiny_piece_joins_its_closer_neighbour() {
        let mut first = said("Yes.", 0, 300, 0);
        first.extend(said("I think we should go there", 350, 250, 20));
        let t = transcript("en", vec![first, said("Right now, before it rains.", 4_000, 250, 20)]);
        assert_eq!(texts(&lines(&t)), ["Yes. I think we should go there", "Right now, before it rains."]);
    }

    #[test]
    fn lines_show_early_and_stay_late_without_overlapping() {
        let t = transcript(
            "en",
            vec![said("The first line here.", 1_000, 250, 20), said("And the second one.", 2_200, 250, 20)],
        );
        let lines = lines(&t);
        assert_eq!(lines[0].start, 800, "0.2 s early");
        // The first line's last word ends at 2,060: the second, due at 2,000, comes in then.
        assert_eq!(lines[0].end, 2_060);
        assert_eq!(lines[1].start, 2_060);
        assert_eq!(lines[1].end, 2_200 + 4 * 270 - 20 + 300, "0.3 s late");
        let lone = transcript("en", vec![said("Hi there.", 5_000, 150, 0)]);
        let lone = super::lines(&lone);
        assert_eq!((lone[0].start, lone[0].end), (4_800, 5_800), "at least a second where there is room");
    }

    #[test]
    fn hindi_does_not_start_a_line_with_a_postposition() {
        let words = Words::of("hi");
        let s = said("हम सब ने मिलकर इस देश को आगे बढ़ाया है और हम आगे भी", 0, 300, 50);
        let line: Vec<&Word> = s.iter().collect();
        let before_ko = line.iter().position(|w| w.text == "को").unwrap();
        assert!(score(&line, before_ko, &words) < score(&line, before_ko + 1, &words));
        let before_aur = line.iter().position(|w| w.text == "और").unwrap();
        assert!(score(&line, before_aur, &words) > score(&line, before_aur - 1, &words));
        assert!(words.ends_sentence("है।"));
    }

    #[test]
    fn languages_written_without_spaces_keep_whisper_cpps_segments() {
        let words = vec![Word { text: "今日はいい天気ですね".into(), start: 0, end: 2_000, p: 0.9 }];
        let t = transcript("ja", vec![words]);
        assert_eq!(texts(&lines(&t)), ["今日はいい天気ですね"]);
        assert_eq!((lines(&t)[0].start, lines(&t)[0].end), (0, 2_300));
    }

    #[test]
    fn the_silences_whisper_cpp_marks_are_left_out() {
        let mut t = transcript("en", vec![said("Hello there everyone.", 0, 300, 20)]);
        t.segments.push(Segment { start: 2_000, end: 9_000, text: "[BLANK_AUDIO]".into(), words: vec![] });
        assert_eq!(texts(&lines(&t)), ["Hello there everyone."]);
    }
}
