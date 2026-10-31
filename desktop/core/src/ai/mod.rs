//! The person's own AI model, for translation and highlights (steps 5 and 6 of docs/PLAN.md): a service they have an
//! account with and its key, or a model running on their own PC through Ollama or LM Studio. Two protocols cover them
//! all: OpenAI's chat completions, which most services and both local programs speak, and Anthropic's Messages.
//! Streaming is off; Chaya asks for JSON and checks the reply is what it asked for.
//!
//! The key is never kept here: the app reads it from Windows Credential Manager each time and hands it in, and it is
//! sent only to the service it belongs to. A plain `http://` address is accepted only on this PC or the local network,
//! so a key never crosses the internet unencrypted.

#[cfg(test)]
mod stand_in;

use std::net::IpAddr;
use std::path::Path;
use std::time::{Duration, Instant};

use reqwest::{Client, RequestBuilder, Response, Url};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

/// How a service is spoken to.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Protocol {
    /// OpenAI's chat completions (`/chat/completions`, `/models`, a bearer key).
    OpenAi,
    /// Anthropic's Messages (`/v1/messages`, `/v1/models`, `x-api-key`).
    Anthropic,
}

/// A service Chaya knows.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Service {
    pub id: &'static str,
    pub name: &'static str,
    pub protocol: Protocol,
    /// Its address, ending before `/chat/completions`; empty for an address the person gives.
    pub address: &'static str,
    /// Whether it needs a key (a program on the PC does not).
    pub key: bool,
    /// A program on this PC, looked for at its usual address.
    pub local: bool,
    /// Where its keys are made, for the settings to point at.
    pub keys_at: &'static str,
}

const fn service(id: &'static str, name: &'static str, protocol: Protocol, address: &'static str) -> Service {
    Service { id, name, protocol, address, key: true, local: false, keys_at: "" }
}

/// Every service offered, as the settings list them.
pub const SERVICES: &[Service] = &[
    Service {
        keys_at: "https://platform.openai.com/api-keys",
        ..service("openai", "OpenAI", Protocol::OpenAi, "https://api.openai.com/v1")
    },
    Service {
        keys_at: "https://console.anthropic.com/settings/keys",
        ..service("anthropic", "Anthropic", Protocol::Anthropic, "https://api.anthropic.com/v1")
    },
    Service {
        keys_at: "https://aistudio.google.com/apikey",
        ..service("gemini", "Gemini", Protocol::OpenAi, "https://generativelanguage.googleapis.com/v1beta/openai")
    },
    Service {
        keys_at: "https://platform.deepseek.com/api_keys",
        ..service("deepseek", "DeepSeek", Protocol::OpenAi, "https://api.deepseek.com")
    },
    Service {
        keys_at: "https://openrouter.ai/keys",
        ..service("openrouter", "OpenRouter", Protocol::OpenAi, "https://openrouter.ai/api/v1")
    },
    Service {
        keys_at: "https://console.mistral.ai/api-keys",
        ..service("mistral", "Mistral", Protocol::OpenAi, "https://api.mistral.ai/v1")
    },
    Service {
        keys_at: "https://console.groq.com/keys",
        ..service("groq", "Groq", Protocol::OpenAi, "https://api.groq.com/openai/v1")
    },
    Service { keys_at: "https://console.x.ai", ..service("xai", "xAI", Protocol::OpenAi, "https://api.x.ai/v1") },
    Service { key: false, local: true, ..service("ollama", "Ollama", Protocol::OpenAi, "http://127.0.0.1:11434/v1") },
    Service {
        key: false,
        local: true,
        ..service("lmstudio", "LM Studio", Protocol::OpenAi, "http://127.0.0.1:1234/v1")
    },
    service("custom", "Another service (OpenAI-compatible)", Protocol::OpenAi, ""),
];

pub fn find(id: &str) -> Option<&'static Service> {
    SERVICES.iter().find(|s| s.id == id)
}

/// What the person chose in Settings › AI model (`ai.json`; never the key).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub service: Option<String>,
    /// The address, for a service the person names, or a program on the PC at another port.
    pub address: Option<String>,
    pub model: Option<String>,
}

impl Settings {
    pub fn load(path: &Path) -> Settings {
        std::fs::read(path).ok().and_then(|bytes| serde_json::from_slice(&bytes).ok()).unwrap_or_default()
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        if let Some(folder) = path.parent() {
            std::fs::create_dir_all(folder)?;
        }
        std::fs::write(path, serde_json::to_vec_pretty(self)?)
    }

    pub fn service(&self) -> Option<&'static Service> {
        self.service.as_deref().and_then(find)
    }

    /// Where the chosen service is: the address the person gave, or the service's own.
    pub fn address(&self) -> Option<String> {
        let service = self.service()?;
        let given = self.address.as_deref().map(str::trim).filter(|a| !a.is_empty());
        let address = given.unwrap_or(service.address).trim_end_matches('/');
        (!address.is_empty()).then(|| address.to_string())
    }
}

/// Why the model could not be reached or used, in words a person can act on.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AiError {
    /// No service is chosen, or no model.
    NotSetUp,
    /// The service needs a key and none is saved.
    NoKey,
    /// An address that is not one (or plain `http://` beyond the local network).
    BadAddress,
    /// The service did not answer: offline, or a program on the PC that is not running.
    Unreachable,
    /// The service refused the key.
    RefusedKey,
    /// Too many requests: try again, after this many seconds if the service said.
    RateLimited(Option<u64>),
    /// The service has no model of that name.
    NoSuchModel,
    /// The model answered, but not with the JSON asked for.
    NotJson,
    /// The service answered with an error it described (its words, shortened).
    Said(String),
    /// The service answered with an error and no words for it.
    Status(u16),
}

impl AiError {
    pub fn message(&self, service: &str) -> String {
        match self {
            AiError::NotSetUp => "Choose a service and a model first.".into(),
            AiError::NoKey => format!("Paste your {service} key first."),
            AiError::BadAddress => {
                "That address can't be used. It has to start with https:// (or http:// on this PC or your network)."
                    .into()
            }
            AiError::Unreachable => format!("{service} didn't answer. Check that it's running, or your connection."),
            AiError::RefusedKey => format!("{service} refused the key. Check it, or make a new one."),
            AiError::RateLimited(Some(seconds)) => {
                format!("{service} is asking for fewer requests. Try again in {seconds} s.")
            }
            AiError::RateLimited(None) => format!("{service} is asking for fewer requests. Try again in a minute."),
            AiError::NoSuchModel => format!("{service} has no model of that name. Pick one from its list."),
            AiError::NotJson => "The model answered, but not in the form Chaya asked for. Try another model.".into(),
            AiError::Said(words) => format!("{service} said: {words}"),
            AiError::Status(code) => format!("{service} answered with an error ({code})."),
        }
    }
}

/// A service as it is reached: how, where, and with which key.
pub struct Endpoint {
    pub protocol: Protocol,
    pub address: Url,
    pub key: Option<String>,
}

impl Endpoint {
    /// The chosen service at `settings`' address, with `key` (read from Credential Manager by the app).
    pub fn new(settings: &Settings, key: Option<String>) -> Result<Endpoint, AiError> {
        let service = settings.service().ok_or(AiError::NotSetUp)?;
        let address = settings.address().ok_or(AiError::BadAddress)?;
        let address = Url::parse(&format!("{address}/")).map_err(|_| AiError::BadAddress)?;
        let local = address.host_str().is_some_and(on_this_network);
        if !(address.scheme() == "https" || (address.scheme() == "http" && local)) {
            return Err(AiError::BadAddress);
        }
        let key = key.filter(|k| !k.trim().is_empty());
        if service.key && service.id != "custom" && key.is_none() {
            return Err(AiError::NoKey);
        }
        Ok(Endpoint { protocol: service.protocol, address, key })
    }

    /// A client for it: a program on this PC or the network is reached directly, never through the PC's proxy.
    pub fn client(&self) -> Client {
        let local = self.address.host_str().is_some_and(on_this_network);
        let builder = Client::builder().timeout(Duration::from_secs(180)).connect_timeout(Duration::from_secs(10));
        let builder = if local { builder.no_proxy() } else { builder };
        builder.build().unwrap_or_default()
    }

    fn at(&self, path: &str) -> Url {
        self.address.join(path).unwrap_or_else(|_| self.address.clone())
    }

    fn signed(&self, request: RequestBuilder) -> RequestBuilder {
        match (self.protocol, &self.key) {
            (Protocol::OpenAi, Some(key)) => request.bearer_auth(key),
            (Protocol::Anthropic, Some(key)) => {
                request.header("x-api-key", key).header("anthropic-version", "2023-06-01")
            }
            (Protocol::Anthropic, None) => request.header("anthropic-version", "2023-06-01"),
            (Protocol::OpenAi, None) => request,
        }
    }
}

/// `host` is this PC or the local network: a name ending in .local or "localhost", or a private address.
fn on_this_network(host: &str) -> bool {
    let host = host.trim_start_matches('[').trim_end_matches(']');
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => ip.is_loopback() || ip.is_private() || ip.is_link_local(),
        Ok(IpAddr::V6(ip)) => ip.is_loopback() || (ip.segments()[0] & 0xfe00) == 0xfc00,
        Err(_) => host == "localhost" || host.ends_with(".local") || host.ends_with(".localhost"),
    }
}

/// What a failed answer means.
async fn failure(response: Response) -> AiError {
    let status = response.status().as_u16();
    let retry = response.headers().get("retry-after").and_then(|v| v.to_str().ok()).and_then(|v| v.trim().parse().ok());
    let body: Value = response.json().await.unwrap_or(Value::Null);
    // OpenAI's {"error": {"message"}}, Anthropic's {"error": {"type", "message"}}, or a bare {"error": "..."}.
    let said = body["error"]["message"].as_str().or(body["error"].as_str()).or(body["message"].as_str()).unwrap_or("");
    let lower = said.to_lowercase();
    match status {
        401 | 403 => AiError::RefusedKey,
        // Gemini says a key is wrong with 400.
        400 if lower.contains("api key") => AiError::RefusedKey,
        429 => AiError::RateLimited(retry),
        404 => AiError::NoSuchModel,
        _ if lower.contains("model") && (lower.contains("not found") || lower.contains("does not exist")) => {
            AiError::NoSuchModel
        }
        _ if !said.trim().is_empty() => AiError::Said(said.chars().take(200).collect::<String>().trim().to_string()),
        _ => AiError::Status(status),
    }
}

async fn send(request: RequestBuilder) -> Result<Value, AiError> {
    let response = request.send().await.map_err(|_| AiError::Unreachable)?;
    if !response.status().is_success() {
        return Err(failure(response).await);
    }
    response.json().await.map_err(|_| AiError::Status(200))
}

/// The models the service offers to chat with, by name, sorted; none when it lists none (the person types one).
pub async fn models(endpoint: &Endpoint) -> Result<Vec<String>, AiError> {
    let request = endpoint.signed(endpoint.client().get(endpoint.at("models")));
    let listed = send(request).await?;
    let mut names: Vec<String> = listed["data"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|m| m["id"].as_str())
        // Gemini names its models "models/gemini-2.5-flash"; it is asked for them without the prefix.
        .map(|id| id.trim_start_matches("models/").to_string())
        .filter(|id| !NOT_FOR_CHAT.iter().any(|word| id.contains(word)))
        .collect();
    names.sort();
    names.dedup();
    Ok(names)
}

/// Words in the names of models made for something other than text (OpenAI lists them all together).
const NOT_FOR_CHAT: &[&str] =
    &["embed", "tts", "whisper", "dall-e", "moderation", "image", "audio", "realtime", "transcribe", "rerank"];

/// A question for the model: what it is for (`system`), what is asked (`user`), and how long its answer may run.
pub struct Ask<'a> {
    pub model: &'a str,
    pub system: &'a str,
    pub user: &'a str,
    pub max_tokens: u32,
}

/// What the model answered, as text.
pub async fn ask(endpoint: &Endpoint, ask: &Ask<'_>) -> Result<String, AiError> {
    let client = endpoint.client();
    match endpoint.protocol {
        Protocol::OpenAi => {
            // Nothing but the model and the messages: a temperature or a token limit is refused by some models.
            let body = json!({
                "model": ask.model,
                "messages": [{"role": "system", "content": ask.system}, {"role": "user", "content": ask.user}],
                "stream": false,
            });
            let answer = send(endpoint.signed(client.post(endpoint.at("chat/completions")).json(&body))).await?;
            let content = &answer["choices"][0]["message"]["content"];
            // A string, or (some services) a list of parts.
            content
                .as_str()
                .map(str::to_string)
                .or_else(|| content.as_array().map(|parts| parts.iter().filter_map(|p| p["text"].as_str()).collect()))
                .ok_or(AiError::NotJson)
        }
        Protocol::Anthropic => {
            let body = json!({
                "model": ask.model,
                "max_tokens": ask.max_tokens,
                "system": ask.system,
                "messages": [{"role": "user", "content": ask.user}],
            });
            let answer = send(endpoint.signed(client.post(endpoint.at("messages")).json(&body))).await?;
            let text: String = answer["content"]
                .as_array()
                .into_iter()
                .flatten()
                .filter(|part| part["type"] == "text")
                .filter_map(|part| part["text"].as_str())
                .collect();
            Ok(text)
        }
    }
}

/// What the model answered, as the JSON object asked for: read out of a fenced block or words around it, and
/// accepted only when `wanted` says it is what was asked for.
pub async fn ask_json(endpoint: &Endpoint, ask: &Ask<'_>, wanted: impl Fn(&Value) -> bool) -> Result<Value, AiError> {
    let text = self::ask(endpoint, ask).await?;
    json_in(&text).filter(|value| wanted(value)).ok_or(AiError::NotJson)
}

/// The JSON object in a model's answer: all of it, or a ```json block, or from its first `{` to its last `}`.
pub fn json_in(text: &str) -> Option<Value> {
    let text = text.trim();
    let fenced = text.split("```").nth(1).map(|block| block.trim_start_matches("json").trim());
    let braced = text.find('{').zip(text.rfind('}')).filter(|(a, b)| a < b).map(|(a, b)| &text[a..=b]);
    [Some(text), fenced, braced]
        .into_iter()
        .flatten()
        .find_map(|candidate| serde_json::from_str::<Value>(candidate).ok().filter(Value::is_object))
}

/// Settings › AI model's *Test*: a small question the model must answer in JSON; how long it took.
pub async fn test(endpoint: &Endpoint, model: &str) -> Result<Duration, AiError> {
    let started = Instant::now();
    let question = Ask {
        model,
        system: "You answer with JSON only: one object, and nothing before or after it.",
        user: r#"Reply with exactly this JSON object: {"ok": true, "word": "Chaya"}"#,
        max_tokens: 64,
    };
    ask_json(endpoint, &question, |v| v["ok"] == true).await?;
    Ok(started.elapsed())
}

/// The programs on this PC that are running at their usual address (Ollama, LM Studio), for the settings to say so:
/// all asked at once, and briefly (Windows takes a while to give up on a port nothing listens on).
pub async fn running_here() -> Vec<&'static str> {
    let client = Client::builder().no_proxy().timeout(Duration::from_millis(700)).build().unwrap_or_default();
    let mut asking = tokio::task::JoinSet::new();
    for service in SERVICES.iter().filter(|s| s.local) {
        let client = client.clone();
        asking.spawn(async move {
            let answered = client.get(format!("{}/models", service.address)).send().await;
            answered.is_ok_and(|r| r.status().is_success()).then_some(service.id)
        });
    }
    let mut running: Vec<&'static str> = asking.join_all().await.into_iter().flatten().collect();
    running.sort_unstable();
    running
}

#[cfg(test)]
mod tests {
    use super::stand_in::{Answer, StandIn};
    use super::*;

    fn at(stand_in: &StandIn, service: &str, key: Option<&str>) -> Endpoint {
        let settings =
            Settings { service: Some(service.into()), address: Some(stand_in.url("/v1")), model: Some("m".into()) };
        Endpoint::new(&settings, key.map(String::from)).unwrap()
    }

    fn question() -> Ask<'static> {
        Ask { model: "small-model", system: "Answer in JSON.", user: "Say ok.", max_tokens: 100 }
    }

    fn openai_answer(text: &str) -> Answer {
        Answer::ok(json!({"choices": [{"message": {"role": "assistant", "content": text}}]}).to_string())
    }

    #[tokio::test]
    async fn openais_protocol_asks_with_the_key_and_reads_the_reply() {
        let stand_in = StandIn::start(vec![("/v1/chat/completions", openai_answer(r#"{"ok": true}"#))]);
        let endpoint = at(&stand_in, "openai", Some("sk-test"));
        let reply = ask_json(&endpoint, &question(), |v| v["ok"] == true).await;
        assert_eq!(reply, Ok(json!({"ok": true})));
        let sent = &stand_in.sent()[0];
        assert_eq!((sent.method.as_str(), sent.path.as_str()), ("POST", "/v1/chat/completions"));
        assert_eq!(sent.authorization.as_deref(), Some("Bearer sk-test"));
        let body: Value = serde_json::from_str(&sent.body).unwrap();
        assert_eq!(body["model"], "small-model");
        assert_eq!(body["messages"][0]["role"], "system");
        assert_eq!(body["messages"][1]["content"], "Say ok.");
        assert_eq!(body["stream"], false);
    }

    #[tokio::test]
    async fn anthropics_protocol_asks_with_its_headers_and_reads_the_text_parts() {
        let reply = json!({"content": [{"type": "text", "text": "{\"ok\": "}, {"type": "text", "text": "true}"}]});
        let stand_in = StandIn::start(vec![("/v1/messages", Answer::ok(reply.to_string()))]);
        let endpoint = at(&stand_in, "anthropic", Some("sk-ant-test"));
        assert_eq!(ask_json(&endpoint, &question(), |v| v["ok"] == true).await, Ok(json!({"ok": true})));
        let sent = &stand_in.sent()[0];
        assert_eq!(sent.path, "/v1/messages");
        assert_eq!(sent.api_key.as_deref(), Some("sk-ant-test"));
        assert_eq!(sent.authorization, None, "the key goes only where Anthropic reads it");
        let body: Value = serde_json::from_str(&sent.body).unwrap();
        assert_eq!(body["system"], "Answer in JSON.");
        assert_eq!(body["max_tokens"], 100);
        assert_eq!(body["messages"][0]["role"], "user");
    }

    #[tokio::test]
    async fn a_refused_key_and_a_rate_limit_are_told_apart_on_both_protocols() {
        let refused =
            Answer::status(401, r#"{"error": {"type": "authentication_error", "message": "invalid x-api-key"}}"#);
        let limited = Answer {
            status: 429,
            headers: vec![("Retry-After", "20")],
            body: r#"{"error": {"message": "Rate limit reached"}}"#.into(),
        };
        for (service, path) in [("openai", "/v1/chat/completions"), ("anthropic", "/v1/messages")] {
            let stand_in = StandIn::start(vec![(path, refused.clone())]);
            assert_eq!(
                ask(&at(&stand_in, service, Some("k")), &question()).await,
                Err(AiError::RefusedKey),
                "{service}"
            );
            let stand_in = StandIn::start(vec![(path, limited.clone())]);
            let error = ask(&at(&stand_in, service, Some("k")), &question()).await;
            assert_eq!(error, Err(AiError::RateLimited(Some(20))), "{service}");
        }
        // Gemini says a wrong key with 400.
        let gemini = Answer::status(400, r#"[{"error": {"code": 400}}]"#);
        let stand_in = StandIn::start(vec![("/v1/chat/completions", gemini)]);
        assert_eq!(ask(&at(&stand_in, "gemini", Some("k")), &question()).await, Err(AiError::Status(400)));
        let gemini =
            Answer::status(400, r#"{"error": {"message": "API key not valid. Please pass a valid API key."}}"#);
        let stand_in = StandIn::start(vec![("/v1/chat/completions", gemini)]);
        assert_eq!(ask(&at(&stand_in, "gemini", Some("k")), &question()).await, Err(AiError::RefusedKey));
    }

    #[tokio::test]
    async fn a_model_that_answers_in_prose_is_not_taken_for_json() {
        let stand_in =
            StandIn::start(vec![("/v1/chat/completions", openai_answer("Sure! Here is a summary of the video."))]);
        let endpoint = at(&stand_in, "openai", Some("k"));
        assert_eq!(ask_json(&endpoint, &question(), |_| true).await, Err(AiError::NotJson));
        // JSON, but not what was asked for.
        let stand_in = StandIn::start(vec![("/v1/chat/completions", openai_answer(r#"{"answer": "ok"}"#))]);
        let endpoint = at(&stand_in, "openai", Some("k"));
        assert_eq!(ask_json(&endpoint, &question(), |v| v["ok"] == true).await, Err(AiError::NotJson));
    }

    #[test]
    fn json_is_found_in_a_fenced_block_or_among_words() {
        assert_eq!(json_in(r#"{"ok": true}"#), Some(json!({"ok": true})));
        assert_eq!(json_in("```json\n{\"ok\": true}\n```"), Some(json!({"ok": true})));
        assert_eq!(json_in("Here you go: {\"ok\": true} Hope that helps!"), Some(json!({"ok": true})));
        assert_eq!(json_in("[1, 2]"), None, "an object, not a list");
        assert_eq!(json_in("no json here"), None);
    }

    #[tokio::test]
    async fn the_models_listed_are_those_for_text_without_gemini_s_prefix() {
        let listed = json!({"data": [
            {"id": "gpt-4.1-mini"}, {"id": "text-embedding-3-small"}, {"id": "whisper-1"},
            {"id": "models/gemini-2.5-flash"}, {"id": "dall-e-3"}, {"id": "gpt-4.1"}
        ]});
        let stand_in = StandIn::start(vec![("/v1/models", Answer::ok(listed.to_string()))]);
        let names = models(&at(&stand_in, "openai", Some("k"))).await.unwrap();
        assert_eq!(names, ["gemini-2.5-flash", "gpt-4.1", "gpt-4.1-mini"]);
        assert_eq!(stand_in.sent()[0].method, "GET");
    }

    #[tokio::test]
    async fn test_asks_a_small_question_and_says_how_long_the_answer_took() {
        let stand_in = StandIn::start(vec![(
            "/v1/chat/completions",
            openai_answer("```json\n{\"ok\": true, \"word\": \"Chaya\"}\n```"),
        )]);
        assert!(test(&at(&stand_in, "ollama", None), "llama3.2").await.is_ok());
        assert_eq!(stand_in.sent()[0].authorization, None, "no key for a program on the PC");
        let stand_in = StandIn::start(vec![(
            "/v1/chat/completions",
            Answer::status(404, r#"{"error": {"message": "model 'x' not found"}}"#),
        )]);
        assert_eq!(test(&at(&stand_in, "ollama", None), "x").await, Err(AiError::NoSuchModel));
    }

    #[tokio::test]
    async fn a_service_that_is_not_there_is_unreachable() {
        let settings = Settings {
            service: Some("lmstudio".into()),
            address: Some("http://127.0.0.1:9/v1".into()),
            model: Some("m".into()),
        };
        let endpoint = Endpoint::new(&settings, None).unwrap();
        assert_eq!(ask(&endpoint, &question()).await, Err(AiError::Unreachable));
    }

    #[test]
    fn a_key_goes_only_over_https_or_within_this_network() {
        let with = |service: &str, address: &str| {
            let settings =
                Settings { service: Some(service.into()), address: Some(address.into()), model: Some("m".into()) };
            Endpoint::new(&settings, Some("k".into())).map(|e| e.address.to_string())
        };
        assert_eq!(with("custom", "https://llm.example.com/v1/"), Ok("https://llm.example.com/v1/".into()));
        assert_eq!(with("custom", "http://192.168.1.20:1234/v1"), Ok("http://192.168.1.20:1234/v1/".into()));
        assert_eq!(with("custom", "http://localhost:8080/v1"), Ok("http://localhost:8080/v1/".into()));
        assert_eq!(
            with("custom", "http://llm.example.com/v1").err(),
            Some(AiError::BadAddress),
            "plain http, far away"
        );
        assert_eq!(with("custom", "ftp://x/").err(), Some(AiError::BadAddress));
        assert_eq!(with("custom", "").err(), Some(AiError::BadAddress), "a service the person names needs an address");
        let openai = Settings { service: Some("openai".into()), ..Settings::default() };
        assert_eq!(Endpoint::new(&openai, None).err(), Some(AiError::NoKey));
        assert_eq!(Endpoint::new(&openai, Some("k".into())).unwrap().address.as_str(), "https://api.openai.com/v1/");
        assert_eq!(Endpoint::new(&Settings::default(), None).err(), Some(AiError::NotSetUp));
    }

    #[test]
    fn settings_are_kept_without_the_key() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("ai.json");
        assert_eq!(Settings::load(&path), Settings::default());
        let chosen = Settings { service: Some("groq".into()), address: None, model: Some("llama-3.3-70b".into()) };
        chosen.save(&path).unwrap();
        assert_eq!(Settings::load(&path), chosen);
        assert!(!std::fs::read_to_string(&path).unwrap().to_lowercase().contains("key"));
    }

    #[test]
    fn every_failure_has_a_sentence_naming_the_service() {
        for error in [
            AiError::NotSetUp,
            AiError::NoKey,
            AiError::BadAddress,
            AiError::Unreachable,
            AiError::RefusedKey,
            AiError::RateLimited(Some(5)),
            AiError::RateLimited(None),
            AiError::NoSuchModel,
            AiError::NotJson,
            AiError::Said("Quota exceeded.".into()),
            AiError::Status(502),
        ] {
            let message = error.message("Groq");
            assert!(message.ends_with('.') && message.len() > 20, "{error:?}: {message}");
        }
    }
}
