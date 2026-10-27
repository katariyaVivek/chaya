//! Where the window opens: where the person last left it, or, the first time, large in the middle of the screen.
//!
//! Sizes and positions are the screen's own pixels (physical), as Windows gives them.

use std::io;
use std::path::Path;

use serde::{Deserialize, Serialize};

/// A rectangle on the screens: the window's outer position and inner size, or a screen's work area (the screen
/// less the taskbar).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Bounds {
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
}

impl Bounds {
    fn holds(&self, x: i64, y: i64) -> bool {
        let (left, top) = (i64::from(self.x), i64::from(self.y));
        (left..left + i64::from(self.width)).contains(&x) && (top..top + i64::from(self.height)).contains(&y)
    }
}

/// How the window was left: its size and place when not maximized, and whether it was maximized.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Placement {
    pub bounds: Bounds,
    pub maximized: bool,
}

/// The largest the window opens the first time, in the screen's points (scaled by its scale factor).
const FIRST_MAX: (f64, f64) = (1600.0, 1000.0);
/// How much of the work area the window takes the first time.
const FIRST_SHARE: f64 = 0.85;

/// The window the first time on a screen with work area `work` at `scale`: 85% of it, no larger than 1600×1000
/// points, in its middle. A browser opens big; Chaya's old 1100×760 left most of a 1080p screen empty.
pub fn first(work: Bounds, scale: f64) -> Bounds {
    let scale = if scale.is_finite() && scale > 0.0 { scale } else { 1.0 };
    let side = |available: u32, max: f64| ((f64::from(available) * FIRST_SHARE).min(max * scale)).round() as u32;
    let width = side(work.width, FIRST_MAX.0);
    let height = side(work.height, FIRST_MAX.1);
    Bounds {
        x: work.x + ((work.width - width) / 2) as i32,
        y: work.y + ((work.height - height) / 2) as i32,
        width,
        height,
    }
}

/// `saved`, to open the window as it was left, fitted to the screen it was on; None when that screen is gone (its
/// title bar would land off every screen in `work_areas`), so the window opens as the first time instead.
pub fn restore(saved: Placement, work_areas: &[Bounds]) -> Option<Placement> {
    let b = saved.bounds;
    // The middle of the title bar is what the person grabs to move the window back.
    let grip = (i64::from(b.x) + i64::from(b.width) / 2, i64::from(b.y) + 16);
    let area = work_areas.iter().find(|a| a.holds(grip.0, grip.1))?;
    // A screen whose resolution went down since: the window shrinks to it rather than spilling off.
    let width = b.width.min(area.width);
    let height = b.height.min(area.height);
    Some(Placement { bounds: Bounds { width, height, ..b }, maximized: saved.maximized })
}

/// The placement kept at `path`; None when there is none or it can't be read.
pub fn read(path: &Path) -> Option<Placement> {
    serde_json::from_slice(&std::fs::read(path).ok()?).ok()
}

pub fn write(path: &Path, placement: &Placement) -> io::Result<()> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    std::fs::write(path, serde_json::to_vec(placement)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bounds(x: i32, y: i32, width: u32, height: u32) -> Bounds {
        Bounds { x, y, width, height }
    }

    #[test]
    fn the_first_window_takes_most_of_the_screen_in_its_middle() {
        // 1920×1080 less a 48px taskbar.
        assert_eq!(first(bounds(0, 0, 1920, 1032), 1.0), bounds(160, 77, 1600, 877));
        // At 150% the same screen is 1280×688 points: the window is 85% of it.
        let work = bounds(0, 0, 2880, 1548);
        let w = first(work, 1.5);
        assert_eq!((w.width, w.height), (2400, 1316));
        assert_eq!((w.x, w.y), (240, 116));
    }

    #[test]
    fn a_very_large_screen_does_not_get_a_window_the_size_of_a_wall() {
        let w = first(bounds(0, 0, 5120, 2840), 1.0);
        assert_eq!((w.width, w.height), (1600, 1000));
        assert_eq!((w.x, w.y), (1760, 920));
    }

    #[test]
    fn the_window_opens_where_it_was_left_and_maximized_if_it_was() {
        let saved = Placement { bounds: bounds(200, 100, 1400, 900), maximized: true };
        assert_eq!(restore(saved, &[bounds(0, 0, 1920, 1032)]), Some(saved));
    }

    #[test]
    fn a_window_left_on_a_screen_that_is_gone_opens_as_the_first_time() {
        // Left on a second screen to the right, now unplugged.
        let saved = Placement { bounds: bounds(2100, 100, 1400, 900), maximized: false };
        assert_eq!(restore(saved, &[bounds(0, 0, 1920, 1032)]), None);
        // Or with its title bar above the top of the only screen.
        let above = Placement { bounds: bounds(200, -400, 1400, 900), maximized: false };
        assert_eq!(restore(above, &[bounds(0, 0, 1920, 1032)]), None);
    }

    #[test]
    fn a_window_larger_than_its_screen_is_now_shrinks_to_it() {
        let saved = Placement { bounds: bounds(0, 0, 2400, 1400), maximized: false };
        let fitted = restore(saved, &[bounds(0, 0, 1920, 1032)]).unwrap();
        assert_eq!(fitted.bounds, bounds(0, 0, 1920, 1032));
    }

    #[test]
    fn a_placement_is_kept_and_read_back_and_a_broken_file_is_ignored() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("window.json");
        assert_eq!(read(&path), None);
        let placement = Placement { bounds: bounds(-1800, 40, 1500, 950), maximized: false };
        write(&path, &placement).unwrap();
        assert_eq!(read(&path), Some(placement));
        std::fs::write(&path, b"{ not json").unwrap();
        assert_eq!(read(&path), None);
    }
}
