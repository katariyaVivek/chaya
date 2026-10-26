//! The engine `desktop/engine/prepare.py` put together, run for real. Set `CHAYA_ENGINE_DIR` to its folder
//! (CI does, on Windows, for the engine the installer carries); without it this test only says it was skipped.
//! Nothing here uses the network.

use std::path::PathBuf;

use chaya_core::engine::Engine;

#[test]
fn the_prepared_engine_starts_and_its_pieces_fit_together() {
    let Some(folder) = std::env::var_os("CHAYA_ENGINE_DIR").map(PathBuf::from) else {
        eprintln!("CHAYA_ENGINE_DIR is not set; skipping the prepared engine's check.");
        return;
    };
    let cache = tempfile::tempdir().unwrap();
    let engine = Engine::in_folder(&folder, cache.path().to_path_buf());
    let status = engine.selftest().unwrap_or_else(|f| panic!("the engine did not run: {f:?}"));

    let manifest: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(folder.join("engine.json")).unwrap()).unwrap();
    // yt-dlp writes its version with zero-padded months ("2026.08.19") where the pin has "2026.8.19".
    let pinned = manifest["packages"]["yt-dlp"].as_str().unwrap().split('.').map(|p| p.parse::<u32>().unwrap());
    let running = status["yt_dlp"].as_str().unwrap().split('.').map(|p| p.parse::<u32>().unwrap());
    assert!(pinned.eq(running), "yt-dlp {} runs, {} was bundled", status["yt_dlp"], manifest["packages"]["yt-dlp"]);
    assert_eq!(status["gallery_dl"], manifest["packages"]["gallery-dl"]);
    assert_eq!(status["post_links_known"], true, "gallery-dl does not know Instagram and X post links");

    if engine.js_runtime.is_some() {
        let qjs = &status["js_runtime"];
        assert_eq!(qjs["found"], true, "yt-dlp cannot run the bundled QuickJS: {qjs}");
        assert_eq!(qjs["name"], "quickjs-ng");
        assert_eq!(qjs["version"], manifest["quickjs"]);
        assert_eq!(qjs["supported"], true, "yt-dlp does not accept this QuickJS for YouTube: {qjs}");
    }
}

#[tokio::test]
async fn the_prepared_ffmpeg_joins_a_picture_and_its_sound() {
    use chaya_core::download::http::Outcome;
    use chaya_core::download::join::Joiner;

    let Some(folder) = std::env::var_os("CHAYA_ENGINE_DIR").map(PathBuf::from) else {
        eprintln!("CHAYA_ENGINE_DIR is not set; skipping the prepared ffmpeg's check.");
        return;
    };
    let Some(joiner) =
        ["ffmpeg.exe", "ffmpeg"].iter().map(|n| folder.join(n)).find(|p| p.is_file()).map(|ffmpeg| Joiner { ffmpeg })
    else {
        eprintln!("This engine carries no ffmpeg; skipping its check.");
        return;
    };
    let fixtures = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests").join("fixtures");
    let out = tempfile::tempdir().unwrap();
    let joined = out.path().join("Clip (240p).mp4.part");
    let (_tx, stop) = tokio::sync::watch::channel(false);

    let result = joiner.join(&fixtures.join("picture.mp4"), &fixtures.join("sound.m4a"), &joined, &stop).await;

    assert_eq!(result, Ok(Outcome::Done));
    let bytes = std::fs::read(&joined).unwrap();
    assert_eq!(&bytes[4..8], b"ftyp");
    let at = |name: &[u8]| bytes.windows(4).position(|w| w == name).unwrap();
    assert!(at(b"moov") < at(b"mdat"), "the index comes first, so players can start at once");
}
