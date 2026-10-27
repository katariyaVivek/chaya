//! The window opens where the person last left it, maximized if it was, or large in the middle of the screen the
//! first time (`chaya_core::placement`). It starts hidden (`tauri.conf.json`), so it is never seen jumping there.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use chaya_core::placement::{self, Bounds, Placement};
use tauri::{Monitor, PhysicalPosition, PhysicalSize, WebviewWindow, WindowEvent};

/// Places `window` as it was left (kept at `path`), shows it, and keeps how it is left at `path` when it closes.
pub fn place(window: &WebviewWindow, path: PathBuf) {
    let areas: Vec<Bounds> = window.available_monitors().unwrap_or_default().iter().map(work_area).collect();
    match placement::read(&path).and_then(|saved| placement::restore(saved, &areas)) {
        Some(saved) => {
            set_bounds(window, saved.bounds);
            if saved.maximized {
                let _ = window.maximize();
            }
        }
        None => {
            let screen = window.primary_monitor().ok().flatten().or_else(|| window.current_monitor().ok().flatten());
            if let Some(screen) = screen {
                set_bounds(window, placement::first(work_area(&screen), screen.scale_factor()));
            }
        }
    }
    let _ = window.show();

    // The size and place it has when not maximized, so a window closed maximized opens maximized, and goes back
    // to that size when the person restores it.
    let normal = Arc::new(Mutex::new(bounds_of(window)));
    let watched = window.clone();
    window.on_window_event(move |event| match event {
        WindowEvent::Moved(_) | WindowEvent::Resized(_) => {
            let left_as_is = !watched.is_maximized().unwrap_or(false) && !watched.is_minimized().unwrap_or(false);
            if let (true, Some(bounds)) = (left_as_is, bounds_of(&watched)) {
                *normal.lock().unwrap_or_else(|e| e.into_inner()) = Some(bounds);
            }
        }
        WindowEvent::CloseRequested { .. } => {
            let bounds = *normal.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(bounds) = bounds {
                let maximized = watched.is_maximized().unwrap_or(false);
                if let Err(e) = placement::write(&path, &Placement { bounds, maximized }) {
                    eprintln!("Chaya: where the window was left could not be kept: {e}");
                }
            }
        }
        _ => {}
    });
}

fn work_area(screen: &Monitor) -> Bounds {
    let area = screen.work_area();
    Bounds { x: area.position.x, y: area.position.y, width: area.size.width, height: area.size.height }
}

/// The window's outer position and inner size, as `set_bounds` takes them back.
fn bounds_of(window: &WebviewWindow) -> Option<Bounds> {
    let position = window.outer_position().ok()?;
    let size = window.inner_size().ok()?;
    Some(Bounds { x: position.x, y: position.y, width: size.width, height: size.height })
}

fn set_bounds(window: &WebviewWindow, bounds: Bounds) {
    let _ = window.set_size(PhysicalSize::new(bounds.width, bounds.height));
    let _ = window.set_position(PhysicalPosition::new(bounds.x, bounds.y));
}
