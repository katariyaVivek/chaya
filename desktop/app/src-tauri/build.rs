//! Tauri's build step. On Windows the app manifest (Common Controls v6, which Tauri's windows and dialogs
//! need) is embedded by the linker into every target, the test binaries included. Tauri's own step puts it
//! into the app only, and a test that opens a real window would otherwise fail to start
//! (STATUS_ENTRYPOINT_NOT_FOUND).
fn main() {
    let msvc = std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc");
    let windows = if msvc {
        let manifest = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("windows-app-manifest.xml");
        println!("cargo:rerun-if-changed={}", manifest.display());
        println!("cargo:rustc-link-arg=/MANIFEST:EMBED");
        println!("cargo:rustc-link-arg=/MANIFESTINPUT:{}", manifest.display());
        tauri_build::WindowsAttributes::new_without_app_manifest()
    } else {
        tauri_build::WindowsAttributes::new()
    };
    tauri_build::try_build(tauri_build::Attributes::new().windows_attributes(windows))
        .expect("the app's build step failed");
}
