//! A saved video's clips: the parts of it the person cut out, as Vimpo's Studio makes them, each with a name and its
//! times in milliseconds. The window picks them (lines chosen in the transcript, edges dragged on its timeline and
//! snapped to the lines, highlights added); here they are put right before they are kept in the video's file
//! (`videos/ID.json`), whatever the window sent: the right way round, long enough, inside the video, named, and the
//! same clip never kept twice. Clips may overlap: two cuts of one moment are both kept.

use serde::{Deserialize, Serialize};

/// The shortest clip, in milliseconds.
pub const SHORTEST: u64 = 500;
/// The most clips a video keeps.
pub const MOST: usize = 500;
/// The longest name, in characters.
const LONGEST_NAME: usize = 80;

/// A clip: its number (unique in its video, never given again), its name, and where it starts and ends.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Clip {
    pub id: u32,
    pub name: String,
    pub start: u64,
    pub end: u64,
}

/// A clip asked for: its name (empty for "Clip 3") and times.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct NewClip {
    #[serde(default)]
    pub name: String,
    pub start: u64,
    pub end: u64,
}

/// `start..end` put right: turned round when backwards, inside a video `duration` long when that is known, and at
/// least `SHORTEST` long (longer at the end when it can be, else earlier); `None` when the video is shorter than that.
fn range(start: u64, end: u64, duration: Option<u64>) -> Option<(u64, u64)> {
    let (mut start, mut end) = (start.min(end), start.max(end));
    if let Some(duration) = duration {
        if duration < SHORTEST {
            return None;
        }
        start = start.min(duration - SHORTEST);
        end = end.min(duration);
    }
    if end < start + SHORTEST {
        end = start + SHORTEST;
    }
    Some((start, end))
}

fn tidy_name(name: &str) -> String {
    name.split_whitespace().collect::<Vec<_>>().join(" ").chars().take(LONGEST_NAME).collect()
}

/// The video's clips in order: by where they start, then where they end.
fn order(clips: &mut [Clip]) {
    clips.sort_by_key(|c| (c.start, c.end, c.id));
}

/// Adds `wanted` to `clips`, each put right (`range`) and named ("Clip 4" when it has no name); one with the same times
/// as a clip there already is that clip, not another. The ids of the clips asked for, in the order asked, and `None`
/// for one that can't be made (a video too short, or too many clips).
pub fn add(clips: &mut Vec<Clip>, wanted: &[NewClip], duration: Option<u64>) -> Vec<Option<u32>> {
    let mut next = clips.iter().map(|c| c.id).max().map_or(1, |id| id + 1);
    let ids = wanted
        .iter()
        .map(|new| {
            let (start, end) = range(new.start, new.end, duration)?;
            if let Some(same) = clips.iter().find(|c| c.start == start && c.end == end) {
                return Some(same.id);
            }
            if clips.len() >= MOST {
                return None;
            }
            let name = tidy_name(&new.name);
            let name = if name.is_empty() { format!("Clip {next}") } else { name };
            clips.push(Clip { id: next, name, start, end });
            next += 1;
            Some(next - 1)
        })
        .collect();
    order(clips);
    ids
}

/// Clip `changed.id` given `changed`'s name and times, put right (a name left empty keeps the one it had); false when
/// there is no such clip.
pub fn change(clips: &mut [Clip], changed: &Clip, duration: Option<u64>) -> bool {
    let Some((start, end)) = range(changed.start, changed.end, duration) else { return false };
    let Some(clip) = clips.iter_mut().find(|c| c.id == changed.id) else { return false };
    let name = tidy_name(&changed.name);
    if !name.is_empty() {
        clip.name = name;
    }
    clip.start = start;
    clip.end = end;
    order(clips);
    true
}

/// Removes clip `id`; false when there is none.
pub fn remove(clips: &mut Vec<Clip>, id: u32) -> bool {
    let before = clips.len();
    clips.retain(|c| c.id != id);
    clips.len() < before
}

#[cfg(test)]
mod tests {
    use super::*;

    fn new(name: &str, start: u64, end: u64) -> NewClip {
        NewClip { name: name.into(), start, end }
    }

    fn times(clips: &[Clip]) -> Vec<(u32, &str, u64, u64)> {
        clips.iter().map(|c| (c.id, c.name.as_str(), c.start, c.end)).collect()
    }

    #[test]
    fn clips_are_kept_in_order_named_each_with_its_own_number() {
        let mut clips = Vec::new();
        let ids = add(&mut clips, &[new("The turn", 60_000, 75_000), new("  ", 10_000, 20_000)], Some(300_000));
        assert_eq!(ids, [Some(1), Some(2)]);
        assert_eq!(times(&clips), [(2, "Clip 2", 10_000, 20_000), (1, "The turn", 60_000, 75_000)]);
        // A number is never given again, even after its clip goes.
        assert!(remove(&mut clips, 1));
        assert!(!remove(&mut clips, 1));
        assert_eq!(add(&mut clips, &[new("", 0, 5_000)], None), [Some(3)]);
        assert_eq!(times(&clips)[0], (3, "Clip 3", 0, 5_000));
    }

    #[test]
    fn a_range_is_put_right_backwards_too_short_or_past_the_end() {
        let mut clips = Vec::new();
        add(
            &mut clips,
            &[new("Backwards", 9_000, 4_000), new("Too short", 30_000, 30_100), new("Past the end", 95_000, 120_000)],
            Some(100_000),
        );
        assert_eq!(
            times(&clips),
            [(1, "Backwards", 4_000, 9_000), (2, "Too short", 30_000, 30_500), (3, "Past the end", 95_000, 100_000)]
        );
        // At the very end, it is made long enough by starting earlier.
        assert_eq!(add(&mut clips, &[new("Last moment", 99_900, 100_000)], Some(100_000)), [Some(4)]);
        assert_eq!((clips[3].start, clips[3].end), (99_500, 100_000));
        // A video shorter than a clip can be has none.
        assert_eq!(add(&mut clips, &[new("", 0, 300)], Some(300)), [None]);
    }

    #[test]
    fn overlapping_clips_are_both_kept_the_same_one_only_once() {
        let mut clips = Vec::new();
        add(&mut clips, &[new("Whole answer", 10_000, 40_000), new("Its best part", 20_000, 30_000)], None);
        // A highlight added twice, or a selection made into a clip again, is the clip there already.
        let again = add(&mut clips, &[new("Whole answer, again", 10_000, 40_000), new("", 40_000, 10_000)], None);
        assert_eq!(again, [Some(1), Some(1)]);
        assert_eq!(times(&clips), [(1, "Whole answer", 10_000, 40_000), (2, "Its best part", 20_000, 30_000)]);
    }

    #[test]
    fn a_clip_changed_is_put_right_and_moved_into_its_place() {
        let mut clips = Vec::new();
        add(&mut clips, &[new("First", 10_000, 20_000), new("Second", 30_000, 40_000)], None);
        let moved = Clip { id: 1, name: "  First,   later ".into(), start: 52_000, end: 45_000 };
        assert!(change(&mut clips, &moved, Some(50_000)));
        assert_eq!(times(&clips), [(2, "Second", 30_000, 40_000), (1, "First, later", 45_000, 50_000)]);
        // A name left empty keeps the one it had.
        assert!(change(&mut clips, &Clip { id: 2, name: String::new(), start: 30_000, end: 41_000 }, None));
        assert_eq!(clips[0].name, "Second");
        assert!(!change(&mut clips, &Clip { id: 9, name: "None".into(), start: 0, end: 1_000 }, None));
    }

    #[test]
    fn a_long_name_is_cut_and_there_is_a_most() {
        let mut clips = Vec::new();
        add(&mut clips, &[new(&"word ".repeat(40), 0, 1_000)], None);
        assert_eq!(clips[0].name.chars().count(), LONGEST_NAME);
        let many: Vec<NewClip> = (1..MOST as u64 + 5).map(|i| new("", i * 1_000, i * 1_000 + 800)).collect();
        let ids = add(&mut clips, &many, None);
        assert_eq!(clips.len(), MOST);
        assert_eq!(ids.iter().filter(|id| id.is_none()).count(), 5);
    }
}
