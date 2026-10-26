//! Downloading what a lookup found: fetching files, naming them, and keeping track of every download.

pub mod error;
pub mod http;
pub mod join;
pub mod manager;
pub mod names;
pub mod pieces;
pub mod plan;

#[cfg(test)]
pub(crate) mod testserver;
