//! Every program Chaya starts is started through `chaya_core::program`, which keeps it from opening a console window
//! on Windows. One started any other way flashed a black window up for a moment as the player opened or a clip was
//! saved. This reads the sources, so it holds on any computer, not only on Windows.

use std::fs;
use std::path::{Path, PathBuf};

fn sources(dir: &Path, found: &mut Vec<PathBuf>) {
    for entry in fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        if path.is_dir() {
            sources(&path, found);
        } else if path.extension().is_some_and(|e| e == "rs") {
            found.push(path);
        }
    }
}

#[test]
fn every_program_is_started_without_a_console_window() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let tauri_app = root.join("../app/src-tauri/src");
    let mut files = Vec::new();
    sources(&root.join("src"), &mut files);
    if tauri_app.is_dir() {
        sources(&tauri_app, &mut files);
    }
    let started_elsewhere: Vec<String> = files
        .iter()
        .filter(|path| !path.ends_with("src/program.rs"))
        .flat_map(|path| {
            let text = fs::read_to_string(path).unwrap();
            text.lines()
                .enumerate()
                .filter(|(_, line)| line.contains("Command::new(") && !line.trim_start().starts_with("//"))
                .map(|(i, _)| format!("{}:{}", path.strip_prefix(root).unwrap_or(path).display(), i + 1))
                .collect::<Vec<_>>()
        })
        .collect();
    assert!(
        started_elsewhere.is_empty(),
        "Start programs with chaya_core::program::program (or program_blocking), which hides the console window on \
         Windows; these start one another way: {started_elsewhere:?}"
    );
}
