//! What Chaya for Windows does, kept apart from its window so it builds and tests anywhere.

pub mod adblock;
pub mod ai;
pub mod browse;
pub mod chapters;
pub mod clips;
pub mod copied;
pub mod download;
pub mod engine;
pub mod export;
pub mod fix;
pub mod formats;
pub mod found;
pub mod highlights;
pub mod library;
pub mod links;
pub mod lookup;
pub mod media;
pub mod own;
pub mod placement;
pub mod search;
pub mod serve;
pub mod session;
pub mod sound;
pub mod speech;
pub mod subtitles;
pub mod translate;
pub mod video;

/// The HTTP client downloads use, so the app builds it with the same version.
pub use reqwest;
