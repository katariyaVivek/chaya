//! Settings › AI model (`chaya-core`'s `ai`): the service, its address and model, kept in `ai.json` in the app's data
//! folder, and its key, kept only in Windows Credential Manager (as "Chaya AI model: SERVICE") and read from there each
//! time it is used. Nothing here is used by anything else yet: translation and highlights (steps 5 and 6) will be.

use std::path::PathBuf;

use chaya_core::ai::{self, AiError, Endpoint, Service, Settings, SERVICES};
use serde::Serialize;
use tauri::State;

/// Where `ai.json` is.
pub struct AiPlace(pub PathBuf);

/// The Credential Manager entry for `service`'s key.
fn entry(service: &str) -> Option<keyring::Entry> {
    keyring::Entry::new("Chaya AI model", service).ok()
}

/// `service`'s key, from Credential Manager.
fn key_of(service: &str) -> Option<String> {
    entry(service)?.get_password().ok()
}

/// What the window calls a service in a sentence.
fn named(settings: &Settings) -> &'static str {
    match settings.service() {
        Some(service) if service.id != "custom" => service.name,
        _ => "The service",
    }
}

fn endpoint(settings: &Settings) -> Result<Endpoint, String> {
    let key = settings.service.as_deref().and_then(key_of);
    Endpoint::new(settings, key).map_err(|error| error.message(named(settings)))
}

/// Settings › AI model as the window shows it: the services, what the person chose, which services have a key saved,
/// and which programs on this PC are running.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AiStatus {
    services: &'static [Service],
    settings: Settings,
    keys: Vec<&'static str>,
    running: Vec<&'static str>,
}

#[tauri::command]
pub async fn ai_status(place: State<'_, AiPlace>) -> Result<AiStatus, ()> {
    let keys = SERVICES.iter().filter(|s| s.key && key_of(s.id).is_some()).map(|s| s.id).collect();
    Ok(AiStatus { services: SERVICES, settings: Settings::load(&place.0), keys, running: ai::running_here().await })
}

#[tauri::command]
pub fn set_ai(settings: Settings, place: State<'_, AiPlace>) -> Result<(), String> {
    let known = settings.service.as_deref().is_none_or(|id| ai::find(id).is_some());
    if !known {
        return Err("There is no such service.".into());
    }
    settings.save(&place.0).map_err(|_| "Chaya couldn't save this setting.".to_string())
}

/// Keeps `key` for `service` in Credential Manager, in place of any before it.
#[tauri::command]
pub fn set_ai_key(service: String, key: String) -> Result<(), String> {
    let known = ai::find(&service).filter(|s| s.key).ok_or("This service takes no key.")?;
    let key = key.trim();
    if key.is_empty() || key.len() > 4096 || key.chars().any(char::is_whitespace) {
        return Err("That doesn't look like a key. Paste it again.".into());
    }
    entry(known.id)
        .and_then(|entry| entry.set_password(key).ok())
        .ok_or_else(|| "Windows Credential Manager couldn't keep the key.".to_string())
}

/// Removes `service`'s key from Credential Manager.
#[tauri::command]
pub fn forget_ai_key(service: String) -> Result<(), String> {
    let known = ai::find(&service).ok_or("There is no such service.")?;
    match entry(known.id).map(|entry| entry.delete_credential()) {
        Some(Ok(())) | Some(Err(keyring::Error::NoEntry)) => Ok(()),
        _ => Err("Windows Credential Manager couldn't remove the key.".into()),
    }
}

/// The models `settings`' service offers (as chosen, before they are saved).
#[tauri::command]
pub async fn ai_models(settings: Settings) -> Result<Vec<String>, String> {
    let endpoint = endpoint(&settings)?;
    ai::models(&endpoint).await.map_err(|error| error.message(named(&settings)))
}

/// Settings › AI model's *Test*: asks the chosen model a small question; how many seconds its answer took.
#[tauri::command]
pub async fn test_ai(settings: Settings) -> Result<f64, String> {
    let model = settings.model.clone().filter(|m| !m.trim().is_empty()).ok_or(AiError::NotSetUp.message(""))?;
    let endpoint = endpoint(&settings)?;
    let took = ai::test(&endpoint, model.trim()).await.map_err(|error| error.message(named(&settings)))?;
    Ok(took.as_secs_f64())
}
