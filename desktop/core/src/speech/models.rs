//! The speech models whisper.cpp can listen with, fetched once, when first needed, from Hugging Face
//! (`ggerganov/whisper.cpp`), checked against the SHA-256 Chaya pins (`models.json`), and kept in Chaya's data folder.
//! A model being fetched is "ggml-NAME.bin.part" until it is whole and its sum is right, so a fetch stopped part-way
//! carries on from what arrived, and a file whose sum is wrong is never used.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;

use reqwest::Client;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::watch;

use crate::download::http::{self, Outcome, Progress, Source};

use super::SpeechError;

/// Where the models come from: Hugging Face's copy of whisper.cpp's own.
pub const SOURCE: &str = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main";

/// One speech model: its id ("tiny", "large-v3-turbo-q5_0"), the name the window shows, its file, its size in bytes,
/// the SHA-256 Chaya pins for it, a word on how it listens, and whether it is the one used until another is chosen.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Model {
    pub id: String,
    pub name: String,
    pub file: String,
    pub size: u64,
    pub sha256: String,
    pub note: String,
    #[serde(default)]
    pub default: bool,
}

/// Every model offered, smallest first.
pub fn all() -> &'static [Model] {
    static MODELS: OnceLock<Vec<Model>> = OnceLock::new();
    MODELS.get_or_init(|| serde_json::from_str(include_str!("models.json")).expect("models.json is a list of models"))
}

/// The model `id`, if it is one Chaya offers.
pub fn find(id: &str) -> Option<&'static Model> {
    all().iter().find(|m| m.id == id)
}

/// The model used until the person picks another: Large v3 Turbo, quantized.
pub fn default_model() -> &'static Model {
    all().iter().find(|m| m.default).unwrap_or(&all()[0])
}

/// The folder the models are kept in, and where they are fetched from.
#[derive(Clone, Debug)]
pub struct Models {
    folder: PathBuf,
    source: String,
}

impl Models {
    pub fn in_folder(folder: PathBuf) -> Models {
        Models { folder, source: SOURCE.to_string() }
    }

    /// Models fetched from `source` instead of Hugging Face (a local test server).
    pub fn from_source(folder: PathBuf, source: &str) -> Models {
        Models { folder, source: source.trim_end_matches('/').to_string() }
    }

    pub fn path(&self, model: &Model) -> PathBuf {
        self.folder.join(&model.file)
    }

    fn part(&self, model: &Model) -> PathBuf {
        self.folder.join(format!("{}.part", model.file))
    }

    /// Whether `model` is here, whole. Its sum was checked when it arrived, so here only its size is.
    pub fn is_fetched(&self, model: &Model) -> bool {
        std::fs::metadata(self.path(model)).is_ok_and(|m| m.is_file() && m.len() == model.size)
    }

    /// How much of `model` has arrived so far, for a fetch stopped part-way.
    pub fn arrived(&self, model: &Model) -> u64 {
        std::fs::metadata(self.part(model)).map_or(0, |m| m.len())
    }

    /// Deletes `model`, and whatever of it had arrived.
    pub fn delete(&self, model: &Model) {
        let _ = std::fs::remove_file(self.path(model));
        let _ = std::fs::remove_file(self.part(model));
    }

    /// `model` here, fetching it first when it is not: carrying on from what arrived before, then checking its sum.
    /// `stop` turning true stops the fetch and keeps what arrived (`SpeechError::Stopped`).
    pub async fn get(
        &self,
        client: &Client,
        model: &Model,
        stop: &watch::Receiver<bool>,
        on_progress: &mut (dyn FnMut(Progress) + Send),
    ) -> Result<PathBuf, SpeechError> {
        let path = self.path(model);
        if self.is_fetched(model) {
            return Ok(path);
        }
        tokio::fs::create_dir_all(&self.folder).await.map_err(|_| SpeechError::CantWrite)?;
        let part = self.part(model);
        // A file as long as the model, or longer, that failed its sum: nothing to carry on from.
        if self.arrived(model) >= model.size {
            let _ = std::fs::remove_file(&part);
        }
        let source = Source {
            url: format!("{}/{}", self.source, model.file),
            expected_size: Some(model.size),
            ..Source::default()
        };
        match http::fetch(client, &source, &part, stop, on_progress).await {
            Ok(Outcome::Done) => {}
            Ok(Outcome::Stopped) => return Err(SpeechError::Stopped),
            Err(error) => return Err(SpeechError::ModelFetch(error)),
        }
        let check = part.clone();
        let sum = tokio::task::spawn_blocking(move || sha256(&check)).await.ok().flatten();
        if sum.as_deref() != Some(model.sha256.as_str()) {
            let _ = std::fs::remove_file(&part);
            return Err(SpeechError::WrongSum);
        }
        std::fs::rename(&part, &path).map_err(|_| SpeechError::CantWrite)?;
        Ok(path)
    }
}

/// A file's SHA-256, as lowercase hex.
pub fn sha256(path: &Path) -> Option<String> {
    let mut file = std::fs::File::open(path).ok()?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0; 1 << 20];
    loop {
        let read = file.read(&mut buffer).ok()?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Some(hasher.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::download::testserver::{Behaviour, TestServer};

    #[test]
    fn five_models_are_offered_smallest_first_large_v3_turbo_by_default() {
        let names: Vec<&str> = all().iter().map(|m| m.name.as_str()).collect();
        assert_eq!(names, ["Tiny", "Small", "Large v3 Turbo", "Medium", "Large v3"]);
        assert!(all().windows(2).all(|pair| pair[0].size < pair[1].size));
        assert_eq!(default_model().id, "large-v3-turbo-q5_0");
        assert_eq!(all().iter().filter(|m| m.default).count(), 1);
        for model in all() {
            assert_eq!(model.file, format!("ggml-{}.bin", model.id));
            assert!(model.sha256.len() == 64 && model.sha256.bytes().all(|b| b.is_ascii_hexdigit()), "{}", model.id);
        }
        assert_eq!(find("tiny").map(|m| m.size), Some(77_691_713));
        assert!(find("../tiny").is_none());
    }

    /// The tests' stand-in model: a few hundred kilobytes, with its own sum.
    fn stand_in(body: &[u8]) -> Model {
        let mut hasher = Sha256::new();
        hasher.update(body);
        Model {
            id: "test".into(),
            name: "Test".into(),
            file: "ggml-test.bin".into(),
            size: body.len() as u64,
            sha256: hasher.finalize().iter().map(|b| format!("{b:02x}")).collect(),
            note: String::new(),
            default: false,
        }
    }

    fn body() -> Vec<u8> {
        (0..300_000).map(|i| (i % 251) as u8).collect()
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_model_is_fetched_checked_and_kept() {
        let server = TestServer::start(body(), Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let models = Models::from_source(dir.path().join("models"), &server.url("/"));
        let model = stand_in(&body());
        let (_tx, stop) = watch::channel(false);
        let mut seen = 0;
        let path = models.get(&Client::new(), &model, &stop, &mut |p| seen = p.done).await.unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), body());
        assert!(models.is_fetched(&model) && seen == model.size);
        assert!(server.requests().iter().any(|r| r.contains("/ggml-test.bin")));
        // Fetched once: asked again, it is not fetched again.
        let asked = server.requests().len();
        models.get(&Client::new(), &model, &stop, &mut |_| {}).await.unwrap();
        assert_eq!(server.requests().len(), asked);
        models.delete(&model);
        assert!(!models.is_fetched(&model) && !path.exists());
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_fetch_stopped_part_way_carries_on_from_what_arrived() {
        let server = TestServer::start(body(), Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let models = Models::from_source(dir.path().to_path_buf(), &server.url("/"));
        let model = stand_in(&body());
        std::fs::write(dir.path().join("ggml-test.bin.part"), &body()[..100_000]).unwrap();
        assert_eq!(models.arrived(&model), 100_000);
        let (_tx, stop) = watch::channel(false);
        models.get(&Client::new(), &model, &stop, &mut |_| {}).await.unwrap();
        assert_eq!(std::fs::read(models.path(&model)).unwrap(), body());
        let requests = server.requests().join("\n").to_ascii_lowercase();
        assert!(requests.contains("range: bytes=100000-"), "{requests}");
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_model_whose_sum_is_wrong_is_refused_and_not_kept() {
        let mut altered = body();
        altered[1234] ^= 0xFF;
        let server = TestServer::start(altered, Behaviour::default());
        let dir = tempfile::tempdir().unwrap();
        let models = Models::from_source(dir.path().to_path_buf(), &server.url("/"));
        let model = stand_in(&body());
        let (_tx, stop) = watch::channel(false);
        let result = models.get(&Client::new(), &model, &stop, &mut |_| {}).await;
        assert_eq!(result, Err(SpeechError::WrongSum));
        assert!(!models.is_fetched(&model) && models.arrived(&model) == 0, "nothing of it is kept");
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_model_the_server_refuses_says_so() {
        let server = TestServer::start(body(), Behaviour { status: Some(404), ..Behaviour::default() });
        let dir = tempfile::tempdir().unwrap();
        let models = Models::from_source(dir.path().to_path_buf(), &server.url("/"));
        let (_tx, stop) = watch::channel(false);
        let result = models.get(&Client::new(), &stand_in(&body()), &stop, &mut |_| {}).await;
        assert!(matches!(result, Err(SpeechError::ModelFetch(_))), "{result:?}");
    }

    /// The model CI fetched for the tests (`CHAYA_WHISPER_MODEL`, Tiny) is the one Chaya pins, byte for byte.
    #[test]
    fn the_model_ci_fetched_has_the_pinned_sum() {
        let Some(path) = std::env::var_os("CHAYA_WHISPER_MODEL").map(PathBuf::from) else {
            eprintln!("CHAYA_WHISPER_MODEL is not set; skipping the pinned sum's check.");
            return;
        };
        let name = path.file_name().unwrap().to_string_lossy().into_owned();
        let model = all().iter().find(|m| m.file == name).expect("a model Chaya offers");
        assert_eq!(std::fs::metadata(&path).unwrap().len(), model.size, "{name}'s size");
        assert_eq!(sha256(&path).as_deref(), Some(model.sha256.as_str()), "{name}'s SHA-256");
    }
}
