//! What Chaya for Windows does, kept apart from its window so it builds and tests anywhere.

pub mod adblock;
pub mod browse;
pub mod download;
pub mod engine;
pub mod formats;
pub mod found;
pub mod library;
pub mod links;
pub mod lookup;
pub mod media;
pub mod placement;
pub mod serve;
pub mod session;
pub mod subtitles;
pub mod video;

/// The HTTP client downloads use, so the app builds it with the same version.
pub use reqwest;
