//! Why a download stopped, in words a person can act on. Ported from the phone's `DownloadError`: raw
//! network or file-system text is never shown, every failure is sorted into one of these first.

use serde::{Deserialize, Serialize};

use crate::links::Platform;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "status", rename_all = "snake_case")]
pub enum DownloadError {
    /// The connection dropped, timed out, or the site could not be reached.
    Network,
    /// The server answered with an error status.
    Http(u16),
    /// The disk filled up.
    StorageFull,
    /// The file could not be written (no permission, a folder that is gone).
    CantWrite,
    /// The server said the file was complete before it was.
    Incomplete,
    /// Picture and sound arrived, but could not be put together into one file.
    CouldNotJoin,
    /// A stream arrived, but could not be copied into an MP4.
    CouldNotConvert,
    /// A stream's playlist could not be read, or a piece of it not decrypted.
    UnreadableStream,
    /// A stream protected with DRM, which only the site's own player can open.
    Protected,
    /// An account the site shows only to signed-in people, listed without the person's sign-in.
    SignInNeeded(Platform),
    /// An account listed with the person's sign-in, which the site no longer accepts.
    SignInAgain(Platform),
    /// A private account the person does not follow.
    PrivateAccount,
    /// An account that does not exist, or was removed.
    NoSuchAccount,
    /// An account with nothing posted that Chaya can save.
    NothingToSave,
    /// An account that could not be listed, for no reason the engine could tell.
    CouldNotList,
}

impl DownloadError {
    pub fn message(self) -> String {
        match self {
            DownloadError::Network => "The connection was lost. Check your network and try again.".into(),
            DownloadError::Http(401 | 403 | 410) => {
                "The site refused the download. Its address may have expired.".into()
            }
            DownloadError::Http(404) => "The site no longer has this file.".into(),
            DownloadError::Http(429) => "The site is asking for fewer downloads. Try again in a while.".into(),
            DownloadError::Http(500..=599) => "The site had a problem. Try again in a while.".into(),
            DownloadError::Http(code) => format!("The download failed (HTTP {code})."),
            DownloadError::StorageFull => "There isn't enough space on the disk.".into(),
            DownloadError::CantWrite => "Chaya couldn't write the file to its folder.".into(),
            DownloadError::Incomplete => "The download ended before the whole file arrived.".into(),
            DownloadError::CouldNotJoin => "Chaya couldn't join the picture and sound into one file.".into(),
            DownloadError::CouldNotConvert => "Chaya couldn't save the stream as a file.".into(),
            DownloadError::UnreadableStream => "Chaya couldn't read this stream.".into(),
            DownloadError::Protected => "This stream is protected, so it can't be saved.".into(),
            DownloadError::SignInNeeded(site) => {
                let site = site.display_name();
                format!(
                    "{site} shows this account only to signed-in people. Sign in to {site} in Chaya's browser, then save \
                     it with your sign-in."
                )
            }
            DownloadError::SignInAgain(site) => {
                format!("{} wants you to sign in again in Chaya's browser.", site.display_name())
            }
            DownloadError::PrivateAccount => "This account is private, and you don't follow it.".into(),
            DownloadError::NoSuchAccount => "This account doesn't exist, or was removed.".into(),
            DownloadError::NothingToSave => "There was nothing to save.".into(),
            DownloadError::CouldNotList => "Chaya couldn't list this account.".into(),
        }
    }

    /// Whether Retry can help as things stand. An expired address can: Retry looks the link up again first.
    pub fn retryable(self) -> bool {
        !matches!(
            self,
            DownloadError::StorageFull
                | DownloadError::CantWrite
                | DownloadError::Http(404)
                | DownloadError::Protected
                | DownloadError::SignInNeeded(_)
                | DownloadError::PrivateAccount
                | DownloadError::NoSuchAccount
                | DownloadError::NothingToSave
        )
    }

    /// Whether the download's address may have expired, so Retry should look the link up again.
    pub fn address_expired(self) -> bool {
        matches!(self, DownloadError::Http(401 | 403 | 410))
    }

    pub fn from_io(error: &std::io::Error) -> DownloadError {
        // ENOSPC on Linux, ERROR_DISK_FULL and ERROR_HANDLE_DISK_FULL on Windows.
        if matches!(error.raw_os_error(), Some(28 | 39 | 112)) || error.kind() == std::io::ErrorKind::StorageFull {
            return DownloadError::StorageFull;
        }
        DownloadError::CantWrite
    }

    pub fn from_http(error: &reqwest::Error) -> DownloadError {
        match error.status() {
            Some(status) => DownloadError::Http(status.as_u16()),
            None => DownloadError::Network,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn statuses_are_explained_and_only_hopeless_ones_lose_retry() {
        assert!(DownloadError::Http(403).message().contains("expired"));
        assert!(DownloadError::Http(403).retryable() && DownloadError::Http(403).address_expired());
        assert!(!DownloadError::Http(404).retryable());
        assert!(DownloadError::Http(503).retryable() && !DownloadError::Http(503).address_expired());
        assert_eq!(DownloadError::Http(418).message(), "The download failed (HTTP 418).");
        assert!(!DownloadError::StorageFull.retryable());
        assert!(DownloadError::Network.retryable());
        assert!(!DownloadError::Protected.retryable(), "a protected stream stays protected");
        assert!(!DownloadError::SignInNeeded(Platform::Instagram).retryable(), "the same listing finds the same");
        assert!(DownloadError::SignInAgain(Platform::Instagram).retryable(), "once signed in again, it lists");
        assert!(!DownloadError::PrivateAccount.retryable() && DownloadError::CouldNotList.retryable());
    }

    #[test]
    fn a_full_disk_is_told_apart_from_other_write_failures() {
        assert_eq!(DownloadError::from_io(&std::io::Error::from_raw_os_error(28)), DownloadError::StorageFull);
        assert_eq!(DownloadError::from_io(&std::io::Error::from_raw_os_error(112)), DownloadError::StorageFull);
        let denied = std::io::Error::from(std::io::ErrorKind::PermissionDenied);
        assert_eq!(DownloadError::from_io(&denied), DownloadError::CantWrite);
    }

    #[test]
    fn every_message_is_a_sentence_without_raw_text() {
        for error in [
            DownloadError::Network,
            DownloadError::Http(403),
            DownloadError::Http(404),
            DownloadError::Http(429),
            DownloadError::Http(502),
            DownloadError::StorageFull,
            DownloadError::CantWrite,
            DownloadError::Incomplete,
            DownloadError::CouldNotJoin,
            DownloadError::CouldNotConvert,
            DownloadError::UnreadableStream,
            DownloadError::Protected,
            DownloadError::SignInNeeded(Platform::Instagram),
            DownloadError::SignInAgain(Platform::X),
            DownloadError::PrivateAccount,
            DownloadError::NoSuchAccount,
            DownloadError::NothingToSave,
            DownloadError::CouldNotList,
        ] {
            let message = error.message();
            assert!(message.ends_with('.') && !message.contains('—'), "{error:?}: {message}");
        }
    }
}
