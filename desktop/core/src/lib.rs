//! What Chaya for Windows does, kept apart from its window so it builds and tests anywhere.

pub mod browse;
pub mod download;
pub mod engine;
pub mod formats;
pub mod found;
pub mod links;
pub mod lookup;
pub mod media;

/// The HTTP client downloads use, so the app builds it with the same version.
pub use reqwest;
