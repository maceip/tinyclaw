//! Android JNI bridge for TinyClaw.
//!
//! This crate produces a `cdylib` (`libtinyclaw_android.so`) that is loaded by
//! the Android foreground service via JNI.  It starts a tokio runtime and runs
//! the inference engine + HTTP API.
//!
//! The Android app (Kotlin) is responsible for:
//! - Creating the foreground service with notification
//! - Managing START_STICKY lifecycle
//! - Calling nativeStart/nativeStop JNI functions

// Functions and statics are used via JNI when compiled for Android target
#![allow(dead_code)]

use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::{Arc, Mutex as StdMutex, OnceLock};
use tinyclaw_core::config::Settings;
use tinyclaw_core::events::EventBus;
use tinyclaw_core::queue::QueueDir;
use tokio::sync::{broadcast, Mutex};

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static SHUTDOWN: OnceLock<broadcast::Sender<()>> = OnceLock::new();

// Shared state accessible from all JNI functions
static SHARED_QUEUE: OnceLock<Arc<QueueDir>> = OnceLock::new();
static SHARED_EVENT_BUS: OnceLock<EventBus> = OnceLock::new();
static SHARED_DATA_DIR: OnceLock<PathBuf> = OnceLock::new();
static SHARED_SETTINGS: OnceLock<Arc<Mutex<Settings>>> = OnceLock::new();
// Buffered events for Android polling
static EVENT_BUFFER: OnceLock<Arc<StdMutex<VecDeque<String>>>> = OnceLock::new();

fn get_runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("Failed to create tokio runtime")
    })
}

fn get_event_buffer() -> &'static Arc<StdMutex<VecDeque<String>>> {
    EVENT_BUFFER.get_or_init(|| Arc::new(StdMutex::new(VecDeque::new())))
}

/// Initialize tracing for the current platform.
fn init_tracing() {
    #[cfg(target_os = "android")]
    {
        use android_logger::Config;
        use log::LevelFilter;

        android_logger::init_once(
            Config::default()
                .with_max_level(LevelFilter::Info)
                .with_tag("TinyClaw"),
        );

        let _ = tracing_log::LogTracer::init();
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = tracing_subscriber::fmt().with_ansi(true).try_init();
    }
}

async fn start_tinyclaw(data_dir: &str, model_id: &str) -> anyhow::Result<()> {
    let data_path = PathBuf::from(data_dir);
    let tinyclaw_dir = data_path.join(".tinyclaw");

    // Ensure directory structure exists
    tokio::fs::create_dir_all(tinyclaw_dir.join("queue/incoming")).await?;
    tokio::fs::create_dir_all(tinyclaw_dir.join("queue/processing")).await?;
    tokio::fs::create_dir_all(tinyclaw_dir.join("queue/outgoing")).await?;
    tokio::fs::create_dir_all(tinyclaw_dir.join("logs")).await?;

    // Load or create settings, applying the selected model
    let settings_path = tinyclaw_dir.join("settings.json");
    let mut settings = if settings_path.exists() {
        Settings::load(&settings_path)?
    } else {
        Settings {
            channels: Default::default(),
            models: Default::default(),
            monitoring: Default::default(),
            http: tinyclaw_core::config::HttpSettings {
                enabled: true,
                port: 8787,
                cors_origins: Vec::new(),
            },
            freehold: Default::default(),
            agents: Default::default(),
            teams: Default::default(),
            pairing_enabled: false,
            providers: Default::default(),
        }
    };

    // Apply model selected in the Android UI
    settings.models.local.model = model_id.to_string();
    settings.http.enabled = true;
    settings.save(&settings_path)?;

    tracing::info!(
        model = model_id,
        port = settings.http.port,
        "Starting TinyClaw"
    );

    let (shutdown_tx, _) = broadcast::channel::<()>(1);
    let _ = SHUTDOWN.set(shutdown_tx.clone());

    // Initialize queue
    let queue = Arc::new(QueueDir::new(tinyclaw_dir.join("queue")).await?);
    let _ = SHARED_QUEUE.set(queue.clone());
    let _ = SHARED_DATA_DIR.set(tinyclaw_dir.clone());

    // Event bus with Android buffer
    let event_bus = EventBus::new(256);
    let _ = SHARED_EVENT_BUS.set(event_bus.clone());

    // Spawn event buffer consumer
    let mut event_rx = event_bus.subscribe();
    let buffer = get_event_buffer().clone();
    tokio::spawn(async move {
        loop {
            match event_rx.recv().await {
                Ok(evt) => {
                    if let Ok(json) = serde_json::to_string(&evt) {
                        if let Ok(mut buf) = buffer.lock() {
                            buf.push_back(json);
                            // Keep max 500 buffered events
                            while buf.len() > 500 {
                                buf.pop_front();
                            }
                        }
                    }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => continue,
                Err(broadcast::error::RecvError::Closed) => break,
            }
        }
    });

    // Store settings for JNI access
    let shared_settings = Arc::new(Mutex::new(settings.clone()));
    let _ = SHARED_SETTINGS.set(shared_settings.clone());

    // Initialize inference engine
    let engine = Arc::new(
        tinyclaw_inference::InferenceEngine::new(
            model_id,
            "You are TinyClaw, a helpful AI assistant running locally on an Android device.",
            &tinyclaw_dir,
        )
        .await?,
    );

    // Build provider registry
    let server_url = "http://127.0.0.1:18787";
    let registry = tinyclaw_inference::ProviderRegistry::build_default(
        &settings.providers.default_provider,
        server_url,
        settings.providers.anthropic_cli_path.clone(),
        settings.providers.openai_cli_path.clone(),
    );
    let registry = Some(Arc::new(Mutex::new(registry)));

    // Spawn queue processor with all features
    let eb = Some(event_bus.clone());
    tokio::spawn(tinyclaw_inference::processor::run_queue_processor_full(
        queue.clone(),
        engine.clone(),
        tinyclaw_dir.clone(),
        shutdown_tx.subscribe(),
        registry,
        settings.pairing_enabled,
        settings.agents.clone(),
        settings.teams.clone(),
        eb,
    ));

    // Spawn HTTP API (always enabled on Android for bookmarklet access)
    let http = tinyclaw_http::HttpServer::new(queue.clone(), settings.http.clone());
    tokio::spawn(async move {
        if let Err(e) = http.start(shutdown_tx.subscribe()).await {
            tracing::error!(error = %e, "HTTP server error");
        }
    });

    tracing::info!("TinyClaw started on Android");
    Ok(())
}

async fn start_tinyclaw_with_config(config_json: &str) -> anyhow::Result<()> {
    let settings: Settings = serde_json::from_str(config_json)?;
    let data_dir = SHARED_DATA_DIR
        .get()
        .map(|p| p.clone())
        .unwrap_or_else(|| PathBuf::from("/data/data/com.tinyclaw/files/.tinyclaw"));

    let tinyclaw_dir = if data_dir.ends_with(".tinyclaw") {
        data_dir.clone()
    } else {
        data_dir.join(".tinyclaw")
    };

    // Save settings
    let settings_path = tinyclaw_dir.join("settings.json");
    settings.save(&settings_path)?;

    // Start with the settings' model
    let parent = tinyclaw_dir
        .parent()
        .map(|p| p.to_string_lossy().to_string())
        .unwrap_or_default();
    start_tinyclaw(&parent, &settings.models.local.model).await
}

// ─── JNI exports (Android only) ───────────────────────────────────────────

#[cfg(target_os = "android")]
mod jni_bridge {
    use super::*;
    use jni::objects::{JClass, JString};
    use jni::sys::{jboolean, jint, jobject, JNI_FALSE, JNI_TRUE};
    use jni::JNIEnv;

    fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
        env.get_string(s).ok().map(|s| s.into())
    }

    fn string_to_jobject<'a>(env: &mut JNIEnv<'a>, s: &str) -> jobject {
        match env.new_string(s) {
            Ok(js) => js.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }

    // ─── Service lifecycle ──────────────────────────────────────────────

    /// Called from `TinyClawService.nativeStart(dataDir, modelId)`.
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_TinyClawService_nativeStart(
        mut env: JNIEnv,
        _class: JClass,
        data_dir: JString,
        model_id: JString,
    ) -> jint {
        init_tracing();

        let data_dir: String = match jstring_to_string(&mut env, &data_dir) {
            Some(s) => s,
            None => return -1,
        };

        let model_id: String = match jstring_to_string(&mut env, &model_id) {
            Some(s) => s,
            None => return -2,
        };

        tracing::info!(data_dir = %data_dir, model_id = %model_id, "nativeStart called");

        let rt = get_runtime();
        rt.spawn(async move {
            if let Err(e) = start_tinyclaw(&data_dir, &model_id).await {
                tracing::error!(error = %e, "Failed to start TinyClaw");
            }
        });

        0
    }

    /// Called from `TinyClawService.nativeStartWithConfig(configJson)`.
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_TinyClawService_nativeStartWithConfig(
        mut env: JNIEnv,
        _class: JClass,
        config_json: JString,
    ) -> jint {
        init_tracing();

        let json: String = match jstring_to_string(&mut env, &config_json) {
            Some(s) => s,
            None => return -1,
        };

        tracing::info!("nativeStartWithConfig called");

        let rt = get_runtime();
        rt.spawn(async move {
            if let Err(e) = start_tinyclaw_with_config(&json).await {
                tracing::error!(error = %e, "Failed to start TinyClaw with config");
            }
        });

        0
    }

    /// Called from `TinyClawService.nativeStop()`.
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_TinyClawService_nativeStop(
        _env: JNIEnv,
        _class: JClass,
    ) -> jint {
        tracing::info!("nativeStop called");
        if let Some(tx) = SHUTDOWN.get() {
            let _ = tx.send(());
        }
        0
    }

    // ─── Status ─────────────────────────────────────────────────────────

    /// Called from `TinyClawBridge.nativeGetStatus()`.
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_TinyClawBridge_nativeGetStatus<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
    ) -> jobject {
        let running = SHUTDOWN.get().is_some();
        let json = serde_json::json!({
            "running": running,
            "version": env!("CARGO_PKG_VERSION"),
        });
        string_to_jobject(&mut env, &json.to_string())
    }

    // Keep legacy method for backward compat
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_MainActivity_nativeGetStatus<'local>(
        env: JNIEnv<'local>,
        class: JClass<'local>,
    ) -> jobject {
        Java_com_tinyclaw_TinyClawBridge_nativeGetStatus(env, class)
    }

    // ─── Messaging ──────────────────────────────────────────────────────

    /// Send a message to a specific agent. Returns JSON with message_id.
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_TinyClawBridge_nativeSendMessage<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        agent_id: JString<'local>,
        message: JString<'local>,
    ) -> jobject {
        let agent_id = jstring_to_string(&mut env, &agent_id).unwrap_or_default();
        let message_text = jstring_to_string(&mut env, &message).unwrap_or_default();

        let queue = match SHARED_QUEUE.get() {
            Some(q) => q.clone(),
            None => {
                return string_to_jobject(
                    &mut env,
                    r#"{"error":"Service not running"}"#,
                );
            }
        };

        let message_id = tinyclaw_core::channel::generate_message_id();

        let incoming = tinyclaw_core::IncomingMessage {
            channel: tinyclaw_core::Channel::Android,
            sender: "Android".into(),
            sender_id: "android_user".into(),
            message: message_text,
            timestamp: tinyclaw_core::channel::now_millis(),
            message_id: message_id.clone(),
            agent: if agent_id.is_empty() {
                None
            } else {
                Some(agent_id)
            },
            files: None,
            conversation_id: None,
            from_agent: None,
        };

        let rt = get_runtime();
        match rt.block_on(queue.enqueue(&incoming)) {
            Ok(()) => {
                let result = serde_json::json!({ "message_id": message_id });
                string_to_jobject(&mut env, &result.to_string())
            }
            Err(e) => {
                let result = serde_json::json!({ "error": e.to_string() });
                string_to_jobject(&mut env, &result.to_string())
            }
        }
    }

    /// Poll for outgoing responses. Returns JSON array of responses.
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_TinyClawBridge_nativePollResponses<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
    ) -> jobject {
        let queue = match SHARED_QUEUE.get() {
            Some(q) => q.clone(),
            None => return string_to_jobject(&mut env, "[]"),
        };

        let rt = get_runtime();
        let responses = match rt.block_on(queue.poll_outgoing("android_")) {
            Ok(r) => r,
            Err(_) => return string_to_jobject(&mut env, "[]"),
        };

        let mut results = Vec::new();
        for (path, response) in responses {
            results.push(serde_json::json!({
                "message_id": response.message_id,
                "message": response.message,
                "agent": response.agent,
                "sender": response.sender,
                "timestamp": response.timestamp,
            }));
            let _ = rt.block_on(queue.ack_outgoing(&path));
        }

        let json = serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string());
        string_to_jobject(&mut env, &json)
    }

    // ─── Settings ───────────────────────────────────────────────────────

    /// Get current settings as JSON.
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_TinyClawBridge_nativeGetSettings<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
    ) -> jobject {
        // Try shared state first, fall back to file
        if let Some(settings) = SHARED_SETTINGS.get() {
            let rt = get_runtime();
            let s = rt.block_on(settings.lock());
            let json = serde_json::to_string(&*s).unwrap_or_else(|_| "{}".to_string());
            return string_to_jobject(&mut env, &json);
        }

        // Fall back to reading from file
        if let Some(data_dir) = SHARED_DATA_DIR.get() {
            let path = data_dir.join("settings.json");
            if let Ok(s) = Settings::load(&path) {
                let json = serde_json::to_string(&s).unwrap_or_else(|_| "{}".to_string());
                return string_to_jobject(&mut env, &json);
            }
        }

        string_to_jobject(&mut env, "{}")
    }

    /// Update settings from JSON. Returns true on success.
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_TinyClawBridge_nativeUpdateSettings(
        mut env: JNIEnv,
        _class: JClass,
        json: JString,
    ) -> jboolean {
        let json_str = match jstring_to_string(&mut env, &json) {
            Some(s) => s,
            None => return JNI_FALSE,
        };

        let new_settings: Settings = match serde_json::from_str(&json_str) {
            Ok(s) => s,
            Err(e) => {
                tracing::error!(error = %e, "Invalid settings JSON");
                return JNI_FALSE;
            }
        };

        // Save to file
        if let Some(data_dir) = SHARED_DATA_DIR.get() {
            let path = data_dir.join("settings.json");
            if let Err(e) = new_settings.save(&path) {
                tracing::error!(error = %e, "Failed to save settings");
                return JNI_FALSE;
            }
        }

        // Update shared state
        if let Some(settings) = SHARED_SETTINGS.get() {
            let rt = get_runtime();
            let mut s = rt.block_on(settings.lock());
            *s = new_settings;
        }

        JNI_TRUE
    }

    // ─── Pairing ────────────────────────────────────────────────────────

    /// List paired senders as JSON.
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_TinyClawBridge_nativeListPairedSenders<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
    ) -> jobject {
        let data_dir = match SHARED_DATA_DIR.get() {
            Some(d) => d,
            None => return string_to_jobject(&mut env, "{}"),
        };

        let state = tinyclaw_core::pairing::load_state(data_dir);
        let json = serde_json::to_string(&state).unwrap_or_else(|_| "{}".to_string());
        string_to_jobject(&mut env, &json)
    }

    /// Approve a pairing code. Returns the sender_id on success, empty string on failure.
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_TinyClawBridge_nativeApprovePairing<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        code: JString<'local>,
    ) -> jobject {
        let code_str = match jstring_to_string(&mut env, &code) {
            Some(s) => s,
            None => return string_to_jobject(&mut env, ""),
        };

        let data_dir = match SHARED_DATA_DIR.get() {
            Some(d) => d,
            None => return string_to_jobject(&mut env, ""),
        };

        let mut state = tinyclaw_core::pairing::load_state(data_dir);
        match tinyclaw_core::pairing::approve_pairing_code(&mut state, &code_str, data_dir) {
            Ok(sender_id) => string_to_jobject(&mut env, &sender_id),
            Err(_) => string_to_jobject(&mut env, ""),
        }
    }

    /// Revoke a paired sender. Returns true on success.
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_TinyClawBridge_nativeRevokePairing(
        mut env: JNIEnv,
        _class: JClass,
        sender_id: JString,
    ) -> jboolean {
        let sid = match jstring_to_string(&mut env, &sender_id) {
            Some(s) => s,
            None => return JNI_FALSE,
        };

        let data_dir = match SHARED_DATA_DIR.get() {
            Some(d) => d,
            None => return JNI_FALSE,
        };

        let mut state = tinyclaw_core::pairing::load_state(data_dir);
        match tinyclaw_core::pairing::revoke_sender(&mut state, &sid, data_dir) {
            Ok(()) => JNI_TRUE,
            Err(_) => JNI_FALSE,
        }
    }

    // ─── Events ─────────────────────────────────────────────────────────

    /// Drain buffered events as JSON array.
    #[no_mangle]
    pub extern "system" fn Java_com_tinyclaw_TinyClawBridge_nativeGetEvents<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
    ) -> jobject {
        let buffer = get_event_buffer();
        let events: Vec<String> = if let Ok(mut buf) = buffer.lock() {
            buf.drain(..).collect()
        } else {
            Vec::new()
        };

        // Events are already JSON strings, wrap in array
        let json = format!("[{}]", events.join(","));
        string_to_jobject(&mut env, &json)
    }
}

// ─── Non-Android stub for testing ─────────────────────────────────────────

#[cfg(not(target_os = "android"))]
pub async fn start(data_dir: &str, model_id: &str) -> anyhow::Result<()> {
    init_tracing();
    start_tinyclaw(data_dir, model_id).await
}
