//! Names for saved files that Windows accepts and people recognise: "Title (720p).mp4".

use std::collections::HashSet;
use std::path::{Path, PathBuf};

/// The longest title kept in a name, so the whole path stays well inside Windows' limits.
const MAX_STEM_CHARS: usize = 120;

/// "Title (720p).mp4", or "Title.m4a" without a `label`. Characters Windows forbids become spaces, and a
/// name Windows reserves for devices ("CON", "NUL"...) is kept from being one.
pub fn file_name(title: &str, label: Option<&str>, ext: &str) -> String {
    let mut stem = clean(title);
    if stem.is_empty() {
        stem = "Download".into();
    }
    if stem.chars().count() > MAX_STEM_CHARS {
        stem = stem.chars().take(MAX_STEM_CHARS).collect::<String>().trim_end().to_string();
    }
    if let Some(label) = label.map(clean).filter(|l| !l.is_empty()) {
        stem = format!("{stem} ({label})");
    }
    if is_reserved(&stem) {
        stem = format!("{stem}_");
    }
    let ext = clean(ext).replace(['.', ' '], "");
    if ext.is_empty() {
        stem
    } else {
        format!("{stem}.{}", ext.to_ascii_lowercase())
    }
}

fn clean(text: &str) -> String {
    let replaced: String =
        text.chars().map(|c| if c.is_control() || r#"<>:"/\|?*"#.contains(c) { ' ' } else { c }).collect();
    let collapsed = replaced.split_whitespace().collect::<Vec<_>>().join(" ");
    // Windows drops trailing dots and spaces, so a name ending in them would not be the name asked for.
    collapsed.trim_end_matches(['.', ' ']).to_string()
}

fn is_reserved(stem: &str) -> bool {
    let base = stem.split('.').next().unwrap_or("").trim().to_ascii_uppercase();
    matches!(base.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || ((base.starts_with("COM") || base.starts_with("LPT"))
            && base.len() == 4
            && base.as_bytes()[3].is_ascii_digit()
            && base.as_bytes()[3] != b'0')
}

/// `name` in `folder`, or "name (2).ext", "name (3).ext"... when that is taken by a file, by a download's
/// `.part`, or by another download still on its way (`taken`, compared without case, as Windows does).
pub fn unique_path(folder: &Path, name: &str, taken: &HashSet<String>) -> PathBuf {
    let (stem, ext) = match name.rsplit_once('.') {
        Some((stem, ext)) if !stem.is_empty() => (stem, format!(".{ext}")),
        _ => (name, String::new()),
    };
    let free = |candidate: &str| {
        !taken.contains(&candidate.to_lowercase())
            && !folder.join(candidate).exists()
            && !folder.join(format!("{candidate}.part")).exists()
    };
    if free(name) {
        return folder.join(name);
    }
    (2..)
        .map(|n| format!("{stem} ({n}){ext}"))
        .find(|candidate| free(candidate))
        .map(|candidate| folder.join(candidate))
        .expect("an unused name exists")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_carry_the_title_and_the_quality() {
        assert_eq!(file_name("Me at the zoo", Some("240p"), "mp4"), "Me at the zoo (240p).mp4");
        assert_eq!(file_name("Me at the zoo", None, "M4A"), "Me at the zoo.m4a");
    }

    #[test]
    fn characters_windows_forbids_are_replaced_and_spaces_tidied() {
        assert_eq!(file_name("AC/DC: Live? \"Best\" <of> |2024|*", None, "mp4"), "AC DC Live Best of 2024.mp4");
        assert_eq!(file_name("Line\none\ttwo", None, "mp4"), "Line one two.mp4");
        assert_eq!(file_name("Ends with dots...", Some("720p"), "mp4"), "Ends with dots (720p).mp4");
    }

    #[test]
    fn empty_long_and_device_names_are_made_safe() {
        assert_eq!(file_name("  ///  ", None, "mp4"), "Download.mp4");
        let long = "a".repeat(300);
        assert_eq!(file_name(&long, Some("1080p"), "mp4"), format!("{} (1080p).mp4", "a".repeat(MAX_STEM_CHARS)));
        assert_eq!(file_name("con", None, "mp4"), "con_.mp4");
        assert_eq!(file_name("COM1", None, "jpg"), "COM1_.jpg");
        assert_eq!(file_name("COM0", None, "jpg"), "COM0.jpg");
        assert_eq!(file_name("Console", None, "mp4"), "Console.mp4");
    }

    #[test]
    fn a_taken_name_gets_a_number() {
        let folder = tempfile::tempdir().unwrap();
        let none = HashSet::new();
        assert_eq!(unique_path(folder.path(), "Clip.mp4", &none), folder.path().join("Clip.mp4"));

        std::fs::write(folder.path().join("Clip.mp4"), "").unwrap();
        std::fs::write(folder.path().join("Clip (2).mp4.part"), "").unwrap();
        let taken: HashSet<String> = ["clip (3).mp4".to_string()].into();
        assert_eq!(unique_path(folder.path(), "Clip.mp4", &taken), folder.path().join("Clip (4).mp4"));
        assert_eq!(unique_path(folder.path(), "Readme", &none), folder.path().join("Readme"));
    }
}
