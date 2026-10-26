//! Chaya for Windows: the Tauri app. The window is web content built from `desktop/design`; what
//! the app knows lives in `chaya-core`, and this crate only hands it to the window.

use chaya_core::links::{self, Link};

/// What the address bar holds: a video or post, an account, another page, or words.
#[tauri::command]
fn recognise_link(text: String) -> Link {
    links::recognise(&text)
}

pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![recognise_link])
        .run(tauri::generate_context!())
        .expect("the window could not be opened");
}
