use clap::{Parser, Subcommand};
use std::path::PathBuf;
use std::sync::Arc;
use tinyclaw_core::channel::{generate_message_id, now_millis, ChannelClient};
use tinyclaw_core::config::Settings;
use tinyclaw_core::events::EventBus;
use tinyclaw_core::logging::init_logging;
use tinyclaw_core::message::{AgentConfig, Channel, IncomingMessage, TeamConfig};
use tinyclaw_core::queue::QueueDir;
use tokio::sync::Mutex;

#[derive(Parser)]
#[command(
    name = "tinyclaw",
    about = "Local AI assistant with multi-channel support"
)]
struct Cli {
    #[command(subcommand)]
    command: Commands,

    /// Path to .tinyclaw data directory
    #[arg(long, default_value = ".tinyclaw")]
    data_dir: PathBuf,
}

#[derive(Subcommand)]
enum Commands {
    /// Start TinyClaw (all channels + queue processor + heartbeat)
    Start,
    /// Show status of all components
    Status,
    /// Run interactive setup wizard
    Setup,
    /// Send a message directly and print the response
    Send {
        /// The message to send
        message: String,
    },
    /// Reset conversation (next message starts fresh)
    Reset,
    /// Show or switch the local model
    Model {
        /// Model name (e.g. gemma3-1b, phi-4-mini, gemma-3n-e4b)
        name: Option<String>,
    },
    /// Pull/download a model
    Pull {
        /// Model identifier to download
        model: String,
    },
    /// List available models
    Models,
    /// Generate bookmarklet JavaScript
    Bookmarklet,
    /// Generate platform service configuration (systemd/launchd)
    InstallService,
    /// Manage sender pairing
    Pair {
        #[command(subcommand)]
        action: PairAction,
    },
    /// Manage inference providers
    Provider {
        #[command(subcommand)]
        action: ProviderAction,
    },
    /// Manage agents
    Agent {
        #[command(subcommand)]
        action: AgentAction,
    },
    /// Manage teams
    Team {
        #[command(subcommand)]
        action: TeamAction,
    },
    /// Launch the TUI dashboard
    Dashboard,
}

#[derive(Subcommand)]
enum PairAction {
    /// List all paired senders
    List,
    /// Approve a pairing code
    Approve { code: String },
    /// Revoke a paired sender
    Revoke { sender_id: String },
}

#[derive(Subcommand)]
enum ProviderAction {
    /// List available providers
    List,
    /// Set the default provider
    SetDefault { name: String },
}

#[derive(Subcommand)]
enum AgentAction {
    /// List configured agents
    List,
    /// Add a new agent
    Add {
        id: String,
        name: String,
        #[arg(long, default_value = "anthropic")]
        provider: String,
        #[arg(long, default_value = "sonnet")]
        model: String,
    },
    /// Remove an agent
    Remove { id: String },
}

#[derive(Subcommand)]
enum TeamAction {
    /// List configured teams
    List,
    /// Add a new team
    Add {
        id: String,
        name: String,
        #[arg(long)]
        leader: String,
        #[arg(long, value_delimiter = ',')]
        members: Vec<String>,
        #[arg(long, default_value = "leader-routes")]
        strategy: String,
    },
    /// Remove a team
    Remove { id: String },
}

fn data_dir(cli: &Cli) -> PathBuf {
    cli.data_dir.clone()
}

fn settings_path(cli: &Cli) -> PathBuf {
    data_dir(cli).join("settings.json")
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();

    match &cli.command {
        Commands::Setup => cmd_setup(&cli).await,
        Commands::Start => cmd_start(&cli).await,
        Commands::Status => cmd_status(&cli).await,
        Commands::Send { message } => cmd_send(&cli, message).await,
        Commands::Reset => cmd_reset(&cli).await,
        Commands::Model { name } => cmd_model(&cli, name.as_deref()).await,
        Commands::Pull { model } => cmd_pull(model).await,
        Commands::Models => cmd_models().await,
        Commands::Bookmarklet => cmd_bookmarklet(&cli).await,
        Commands::InstallService => cmd_install_service(&cli).await,
        Commands::Pair { action } => cmd_pair(&cli, action).await,
        Commands::Provider { action } => cmd_provider(&cli, action).await,
        Commands::Agent { action } => cmd_agent(&cli, action).await,
        Commands::Team { action } => cmd_team(&cli, action).await,
        Commands::Dashboard => cmd_dashboard(&cli).await,
    }
}

async fn cmd_start(cli: &Cli) -> anyhow::Result<()> {
    let dir = data_dir(cli);

    // Load settings
    let settings = match Settings::load(&settings_path(cli)) {
        Ok(s) => s,
        Err(_) => {
            eprintln!("No configuration found. Run 'tinyclaw setup' first.");
            std::process::exit(1);
        }
    };

    // Initialize logging
    let _guard = init_logging(&dir.join("logs"))?;

    tracing::info!("Starting TinyClaw...");

    let (shutdown_tx, _) = tokio::sync::broadcast::channel::<()>(1);

    // Initialize queue
    let queue = Arc::new(QueueDir::new(dir.join("queue")).await?);

    // Initialize inference engine
    let engine = Arc::new(
        tinyclaw_inference::InferenceEngine::new(
            &settings.models.local.model,
            "You are TinyClaw, a helpful AI assistant.",
            &dir,
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
    let registry = Arc::new(Mutex::new(registry));

    // Event bus for TUI / logging
    let event_bus = EventBus::new(256);

    // Spawn queue processor with all features
    let q = queue.clone();
    let e = engine.clone();
    let d = dir.clone();
    let rx = shutdown_tx.subscribe();
    let pr = Some(registry.clone());
    let pe = settings.pairing_enabled;
    let agents = settings.agents.clone();
    let teams = settings.teams.clone();
    let eb = Some(event_bus.clone());
    tokio::spawn(async move {
        if let Err(err) = tinyclaw_inference::processor::run_queue_processor_full(
            q, e, d, rx, pr, pe, agents, teams, eb,
        )
        .await
        {
            tracing::error!(error = %err, "Queue processor error");
        }
    });

    // Spawn enabled channels
    for channel_name in &settings.channels.enabled {
        match channel_name.as_str() {
            #[cfg(feature = "discord")]
            "discord" => {
                if settings.channels.discord.bot_token.is_empty() {
                    tracing::warn!("Discord enabled but no bot token configured");
                    continue;
                }
                let client = Arc::new(tinyclaw_channel_discord::DiscordClient::new(
                    settings.channels.discord.bot_token.clone(),
                ));
                let q = queue.clone();
                let rx = shutdown_tx.subscribe();
                tokio::spawn(async move {
                    if let Err(e) = client.start(q, rx).await {
                        tracing::error!(error = %e, "Discord client error");
                    }
                });
                tracing::info!("Discord channel started");
            }
            #[cfg(feature = "telegram")]
            "telegram" => {
                if settings.channels.telegram.bot_token.is_empty() {
                    tracing::warn!("Telegram enabled but no bot token configured");
                    continue;
                }
                let client = Arc::new(tinyclaw_channel_telegram::TelegramClient::new(
                    settings.channels.telegram.bot_token.clone(),
                ));
                let q = queue.clone();
                let rx = shutdown_tx.subscribe();
                tokio::spawn(async move {
                    if let Err(e) = client.start(q, rx).await {
                        tracing::error!(error = %e, "Telegram client error");
                    }
                });
                tracing::info!("Telegram channel started");
            }
            #[cfg(feature = "email")]
            "email" => {
                if settings.channels.email.imap_host.is_empty() {
                    tracing::warn!("Email enabled but no IMAP host configured");
                    continue;
                }
                let client = Arc::new(tinyclaw_channel_email::EmailClient::new(
                    settings.channels.email.clone(),
                ));
                let q = queue.clone();
                let rx = shutdown_tx.subscribe();
                tokio::spawn(async move {
                    if let Err(e) = client.start(q, rx).await {
                        tracing::error!(error = %e, "Email client error");
                    }
                });
                tracing::info!("Email channel started");
            }
            other => {
                tracing::warn!("Unknown or disabled channel: {}", other);
            }
        }
    }

    // Spawn HTTP API if enabled
    #[cfg(feature = "http")]
    if settings.http.enabled {
        let http = tinyclaw_http::HttpServer::new(queue.clone(), settings.http.clone());
        let rx = shutdown_tx.subscribe();
        tokio::spawn(async move {
            if let Err(e) = http.start(rx).await {
                tracing::error!(error = %e, "HTTP server error");
            }
        });
        tracing::info!("HTTP API started on port {}", settings.http.port);
    }

    // Spawn heartbeat
    {
        let queue_hb = queue.clone();
        let dir_hb = dir.clone();
        let interval = settings.monitoring.heartbeat_interval;
        let mut shutdown_hb = shutdown_tx.subscribe();
        tokio::spawn(async move {
            run_heartbeat(queue_hb, dir_hb, interval, &mut shutdown_hb).await;
        });
    }

    println!("TinyClaw started. Press Ctrl+C to stop.");
    println!();
    println!("Channels: {:?}", settings.channels.enabled);
    println!(
        "Model: {} ({})",
        settings.models.local.model, settings.models.local.backend
    );
    println!("Provider: {}", settings.providers.default_provider);
    if settings.pairing_enabled {
        println!("Pairing: enabled");
    }
    if !settings.agents.is_empty() {
        println!("Agents: {:?}", settings.agents.keys().collect::<Vec<_>>());
    }
    if !settings.teams.is_empty() {
        println!("Teams: {:?}", settings.teams.keys().collect::<Vec<_>>());
    }
    if settings.http.enabled {
        println!("HTTP API: http://0.0.0.0:{}", settings.http.port);
    }

    // Wait for shutdown signal
    tokio::signal::ctrl_c().await?;
    tracing::info!("Shutting down...");
    let _ = shutdown_tx.send(());

    // Give tasks time to clean up
    tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    tracing::info!("TinyClaw stopped");

    Ok(())
}

async fn run_heartbeat(
    queue: Arc<QueueDir>,
    data_dir: PathBuf,
    interval_secs: u64,
    shutdown: &mut tokio::sync::broadcast::Receiver<()>,
) {
    // Wait for the first interval before sending
    let mut interval = tokio::time::interval(std::time::Duration::from_secs(interval_secs));
    interval.tick().await; // skip immediate tick

    loop {
        tokio::select! {
            _ = interval.tick() => {
                let heartbeat_file = data_dir.join("heartbeat.md");
                let prompt = tokio::fs::read_to_string(&heartbeat_file)
                    .await
                    .unwrap_or_else(|_| "Quick status check. Keep response brief.".to_string());

                let message_id = format!("heartbeat_{}", now_millis());
                let msg = IncomingMessage {
                    channel: Channel::Heartbeat,
                    sender: "System".into(),
                    sender_id: "heartbeat".into(),
                    message: prompt,
                    timestamp: now_millis(),
                    message_id,
                    agent: None,
                    files: None,
                    conversation_id: None,
                    from_agent: None,
                };

                if let Err(e) = queue.enqueue(&msg).await {
                    tracing::error!(error = %e, "Failed to enqueue heartbeat");
                } else {
                    tracing::info!("Heartbeat queued");
                }
            }
            _ = shutdown.recv() => {
                tracing::info!("Heartbeat shutting down");
                break;
            }
        }
    }
}

async fn cmd_setup(cli: &Cli) -> anyhow::Result<()> {
    let dir = data_dir(cli);
    std::fs::create_dir_all(&dir)?;

    println!();
    println!("TinyClaw - Setup Wizard");
    println!("=======================");
    println!();

    // Channel selection
    let channels = vec!["telegram", "discord", "email"];
    let mut enabled = Vec::new();

    for ch in &channels {
        let prompt = format!("Enable {}?", ch);
        if dialoguer::Confirm::new()
            .with_prompt(&prompt)
            .default(false)
            .interact()?
        {
            enabled.push(ch.to_string());
            println!("  {} enabled", ch);
        }
    }

    if enabled.is_empty() {
        eprintln!("No channels selected. At least one channel is required.");
        std::process::exit(1);
    }

    // Collect tokens
    let mut discord_token = String::new();
    let mut telegram_token = String::new();
    let mut email_config = tinyclaw_core::config::EmailConfig::default();

    if enabled.contains(&"discord".to_string()) {
        println!();
        println!("Enter your Discord bot token:");
        println!("(Get one at: https://discord.com/developers/applications)");
        discord_token = dialoguer::Input::<String>::new()
            .with_prompt("Token")
            .interact_text()?;
    }

    if enabled.contains(&"telegram".to_string()) {
        println!();
        println!("Enter your Telegram bot token:");
        println!("(Create a bot via @BotFather on Telegram)");
        telegram_token = dialoguer::Input::<String>::new()
            .with_prompt("Token")
            .interact_text()?;
    }

    if enabled.contains(&"email".to_string()) {
        println!();
        println!("Email channel configuration:");
        email_config.imap_host = dialoguer::Input::<String>::new()
            .with_prompt("IMAP host (e.g. imap.gmail.com)")
            .interact_text()?;
        email_config.imap_port = dialoguer::Input::<u16>::new()
            .with_prompt("IMAP port")
            .default(993)
            .interact_text()?;
        email_config.smtp_host = dialoguer::Input::<String>::new()
            .with_prompt("SMTP host (e.g. smtp.gmail.com)")
            .interact_text()?;
        email_config.smtp_port = dialoguer::Input::<u16>::new()
            .with_prompt("SMTP port")
            .default(587)
            .interact_text()?;
        email_config.username = dialoguer::Input::<String>::new()
            .with_prompt("Email address")
            .interact_text()?;
        email_config.password = dialoguer::Input::<String>::new()
            .with_prompt("Password (or app password)")
            .interact_text()?;
        email_config.poll_interval = dialoguer::Input::<u64>::new()
            .with_prompt("Poll interval (seconds)")
            .default(30)
            .interact_text()?;
    }

    // Model selection
    let models = vec![
        "gemma3-1b",
        "gemma-3n-e2b",
        "gemma-3n-e4b",
        "phi-4-mini",
        "qwen2.5-1.5b",
    ];
    println!();
    let model_idx = dialoguer::Select::new()
        .with_prompt("Select local model")
        .items(&models)
        .default(0)
        .interact()?;

    // Backend
    let backends = vec!["cpu", "gpu"];
    let backend_idx = dialoguer::Select::new()
        .with_prompt("Inference backend")
        .items(&backends)
        .default(0)
        .interact()?;

    // Heartbeat
    println!();
    let heartbeat: u64 = dialoguer::Input::new()
        .with_prompt("Heartbeat interval (seconds)")
        .default(3600)
        .interact_text()?;

    // HTTP API
    println!();
    let http_enabled = dialoguer::Confirm::new()
        .with_prompt("Enable HTTP API (for bookmarklet)?")
        .default(false)
        .interact()?;

    let http_port: u16 = if http_enabled {
        dialoguer::Input::new()
            .with_prompt("HTTP port")
            .default(8787)
            .interact_text()?
    } else {
        8787
    };

    // Build and save settings
    let settings = Settings {
        channels: tinyclaw_core::config::ChannelSettings {
            enabled,
            discord: tinyclaw_core::config::DiscordConfig {
                bot_token: discord_token,
            },
            telegram: tinyclaw_core::config::TelegramConfig {
                bot_token: telegram_token,
            },
            whatsapp: Default::default(),
            email: email_config,
        },
        models: tinyclaw_core::config::ModelSettings {
            provider: "local".to_string(),
            local: tinyclaw_core::config::LocalModelConfig {
                model: models[model_idx].to_string(),
                backend: backends[backend_idx].to_string(),
                max_tokens: 2048,
            },
        },
        monitoring: tinyclaw_core::config::MonitoringSettings {
            heartbeat_interval: heartbeat,
        },
        http: tinyclaw_core::config::HttpSettings {
            enabled: http_enabled,
            port: http_port,
            cors_origins: Vec::new(),
        },
        freehold: Default::default(),
        agents: Default::default(),
        teams: Default::default(),
        pairing_enabled: false,
        providers: Default::default(),
    };

    settings.save(&settings_path(cli))?;

    println!();
    println!("Configuration saved to {}", settings_path(cli).display());
    println!();
    println!("Start with: tinyclaw start");
    println!();

    Ok(())
}

async fn cmd_status(cli: &Cli) -> anyhow::Result<()> {
    println!("TinyClaw Status");
    println!("===============");
    println!();

    match Settings::load(&settings_path(cli)) {
        Ok(settings) => {
            println!("Configuration: Found");
            println!("  Provider: {}", settings.models.provider);
            println!("  Model: {}", settings.models.local.model);
            println!("  Backend: {}", settings.models.local.backend);
            println!("  Channels: {:?}", settings.channels.enabled);
            println!("  Heartbeat: {}s", settings.monitoring.heartbeat_interval);
            println!("  Default Provider: {}", settings.providers.default_provider);
            println!("  Pairing: {}", if settings.pairing_enabled { "enabled" } else { "disabled" });
            if !settings.agents.is_empty() {
                println!("  Agents: {:?}", settings.agents.keys().collect::<Vec<_>>());
            }
            if !settings.teams.is_empty() {
                println!("  Teams: {:?}", settings.teams.keys().collect::<Vec<_>>());
            }
            if settings.http.enabled {
                println!("  HTTP API: port {}", settings.http.port);
            }
            if settings.freehold.enabled {
                println!("  Freehold: {} ", settings.freehold.relay);
            }
        }
        Err(_) => {
            println!("Configuration: Not found");
            println!("  Run 'tinyclaw setup' to configure");
        }
    }

    Ok(())
}

async fn cmd_send(cli: &Cli, message: &str) -> anyhow::Result<()> {
    let dir = data_dir(cli);
    let queue = Arc::new(QueueDir::new(dir.join("queue")).await?);

    let message_id = generate_message_id();

    let incoming = IncomingMessage {
        channel: Channel::Manual,
        sender: "CLI".into(),
        sender_id: "manual".into(),
        message: message.to_string(),
        timestamp: now_millis(),
        message_id: message_id.clone(),
        agent: None,
        files: None,
        conversation_id: None,
        from_agent: None,
    };

    queue.enqueue(&incoming).await?;
    println!("Message queued: {}", message_id);
    println!("(Response will appear in .tinyclaw/queue/outgoing/)");

    // Poll for response with timeout
    let timeout = std::time::Duration::from_secs(120);
    let start = std::time::Instant::now();

    loop {
        if start.elapsed() > timeout {
            println!("Timed out waiting for response.");
            break;
        }

        let responses = queue.poll_outgoing("manual_").await?;
        for (path, response) in responses {
            if response.message_id == message_id {
                println!();
                println!("{}", response.message);
                queue.ack_outgoing(&path).await?;
                return Ok(());
            }
        }

        tokio::time::sleep(std::time::Duration::from_millis(500)).await;
    }

    Ok(())
}

async fn cmd_reset(cli: &Cli) -> anyhow::Result<()> {
    let flag = data_dir(cli).join("reset_flag");
    tokio::fs::write(&flag, "reset").await?;
    println!("Reset flag set.");
    println!("The next message will start a fresh conversation.");
    Ok(())
}

async fn cmd_model(cli: &Cli, name: Option<&str>) -> anyhow::Result<()> {
    let path = settings_path(cli);

    match name {
        Some(model_name) => {
            let mut settings = Settings::load(&path)?;
            settings.models.local.model = model_name.to_string();
            settings.save(&path)?;
            println!("Model switched to: {}", model_name);
            println!("Changes take effect on the next message.");
        }
        None => {
            let settings = Settings::load(&path)?;
            println!("Current model: {}", settings.models.local.model);
            println!("Backend: {}", settings.models.local.backend);
            println!();
            println!(
                "Available models: gemma3-1b, gemma-3n-e2b, gemma-3n-e4b, phi-4-mini, qwen2.5-1.5b"
            );
            println!("Switch with: tinyclaw model <name>");
        }
    }

    Ok(())
}

async fn cmd_pull(model: &str) -> anyhow::Result<()> {
    println!("Pulling model: {}...", model);
    println!();
    println!(
        "This will download the model via litert-lm. Make sure the litert-lm CLI is installed."
    );

    let status = tokio::process::Command::new("litert-lm")
        .args(["pull", model])
        .status()
        .await?;

    if status.success() {
        println!("Model {} downloaded successfully.", model);
    } else {
        eprintln!("Failed to download model. Is litert-lm installed?");
        eprintln!("Install with: cargo install litert-lm");
    }

    Ok(())
}

async fn cmd_models() -> anyhow::Result<()> {
    println!("Available models for LiteRT-LM:");
    println!();
    println!("  gemma3-1b       - Google Gemma 3 1B (smallest, fastest)");
    println!("  gemma-3n-e2b    - Google Gemma 3n E2B");
    println!("  gemma-3n-e4b    - Google Gemma 3n E4B");
    println!("  phi-4-mini      - Microsoft Phi-4 Mini");
    println!("  qwen2.5-1.5b    - Alibaba Qwen 2.5 1.5B");
    println!();
    println!("Pull a model:  tinyclaw pull <model>");
    println!("Switch model:  tinyclaw model <model>");

    Ok(())
}

async fn cmd_bookmarklet(cli: &Cli) -> anyhow::Result<()> {
    let settings = Settings::load(&settings_path(cli))?;

    if !settings.http.enabled {
        eprintln!("HTTP API is not enabled. Run 'tinyclaw setup' and enable it.");
        std::process::exit(1);
    }

    let host = if settings.freehold.enabled {
        format!(
            "https://{}",
            settings
                .freehold
                .domain
                .as_deref()
                .unwrap_or("FREEHOLD_PUBLIC_IP")
        )
    } else {
        format!("http://localhost:{}", settings.http.port)
    };

    let bookmarklet = format!(
        r#"javascript:void(fetch('{}/v1/chat',{{method:'POST',headers:{{'Content-Type':'application/json'}},body:JSON.stringify({{message:prompt('Ask TinyClaw:')}})}})\
.then(r=>r.json()).then(d=>alert(d.message)).catch(e=>alert('Error: '+e)))"#,
        host
    );

    println!("Bookmarklet JavaScript:");
    println!();
    println!("{}", bookmarklet);
    println!();
    println!("To use: Create a new bookmark in your browser and paste this as the URL.");
    if settings.freehold.enabled {
        println!();
        println!("With freehold enabled, this bookmarklet works from any network.");
        println!("Relay: {}", settings.freehold.relay);
    }

    Ok(())
}

async fn cmd_install_service(cli: &Cli) -> anyhow::Result<()> {
    let exe_path = std::env::current_exe()?;
    let data_dir_str = data_dir(cli).display().to_string();

    #[cfg(target_os = "linux")]
    {
        let unit = format!(
            r#"[Unit]
Description=TinyClaw AI Assistant
After=network.target

[Service]
ExecStart={exe} start --data-dir {data_dir}
Restart=always
Type=simple

[Install]
WantedBy=multi-user.target
"#,
            exe = exe_path.display(),
            data_dir = data_dir_str,
        );

        let service_path = "/etc/systemd/system/tinyclaw.service";
        println!("systemd service unit:");
        println!();
        println!("{}", unit);
        println!();
        println!("To install:");
        println!("  sudo tee {} << 'EOF'", service_path);
        println!("{}", unit.trim());
        println!("EOF");
        println!("  sudo systemctl daemon-reload");
        println!("  sudo systemctl enable --now tinyclaw");
    }

    #[cfg(target_os = "macos")]
    {
        let plist = format!(
            r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.tinyclaw.agent</string>
    <key>ProgramArguments</key>
    <array>
        <string>{exe}</string>
        <string>start</string>
        <string>--data-dir</string>
        <string>{data_dir}</string>
    </array>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>{data_dir}/logs/launchd.log</string>
    <key>StandardErrorPath</key>
    <string>{data_dir}/logs/launchd.err</string>
</dict>
</plist>"#,
            exe = exe_path.display(),
            data_dir = data_dir_str,
        );

        println!("launchd plist:");
        println!();
        println!("{}", plist);
        println!();
        println!("To install:");
        println!("  cp <this-file> ~/Library/LaunchAgents/com.tinyclaw.agent.plist");
        println!("  launchctl load ~/Library/LaunchAgents/com.tinyclaw.agent.plist");
    }

    #[cfg(target_os = "windows")]
    {
        println!("Windows service setup:");
        println!();
        println!("Option 1: Use NSSM (recommended):");
        println!(
            "  nssm install TinyClaw \"{}\" start --data-dir \"{}\"",
            exe_path.display(),
            data_dir_str
        );
        println!("  nssm start TinyClaw");
        println!();
        println!("Option 2: Use Task Scheduler:");
        println!("  schtasks /create /tn TinyClaw /tr \"\\\"{}\\\" start --data-dir \\\"{}\\\"\" /sc onlogon /rl highest",
            exe_path.display(), data_dir_str);
    }

    #[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
    {
        println!("Service installation not supported for this platform.");
        println!(
            "Run manually: {} start --data-dir {}",
            exe_path.display(),
            data_dir_str
        );
    }

    Ok(())
}

// ─── Pairing commands ────────────────────────────────────────────────────────

async fn cmd_pair(cli: &Cli, action: &PairAction) -> anyhow::Result<()> {
    let dir = data_dir(cli);

    match action {
        PairAction::List => {
            let state = tinyclaw_core::pairing::load_state(&dir);
            if state.paired.is_empty() && state.pending.is_empty() {
                println!("No paired senders.");
            } else {
                if !state.paired.is_empty() {
                    println!("Paired senders:");
                    for (id, info) in &state.paired {
                        println!("  {} (code: {}, paired at: {})", id, info.code, info.paired_at);
                    }
                }
                if !state.pending.is_empty() {
                    println!();
                    println!("Pending approvals:");
                    for (code, sender_id) in &state.pending {
                        println!("  {} -> {}", code, sender_id);
                    }
                }
            }
        }
        PairAction::Approve { code } => {
            let mut state = tinyclaw_core::pairing::load_state(&dir);
            match tinyclaw_core::pairing::approve_pairing_code(&mut state, code, &dir) {
                Ok(sender_id) => println!("Approved sender: {}", sender_id),
                Err(e) => eprintln!("Error: {}", e),
            }
        }
        PairAction::Revoke { sender_id } => {
            let mut state = tinyclaw_core::pairing::load_state(&dir);
            match tinyclaw_core::pairing::revoke_sender(&mut state, sender_id, &dir) {
                Ok(()) => println!("Revoked sender: {}", sender_id),
                Err(e) => eprintln!("Error: {}", e),
            }
        }
    }

    Ok(())
}

// ─── Provider commands ───────────────────────────────────────────────────────

async fn cmd_provider(cli: &Cli, action: &ProviderAction) -> anyhow::Result<()> {
    match action {
        ProviderAction::List => {
            let settings = Settings::load(&settings_path(cli))?;
            println!("Available providers:");
            println!("  local      - LiteRT-LM local inference");
            println!("  anthropic  - Anthropic Claude (via claude CLI)");
            println!("  openai     - OpenAI (via codex CLI)");
            println!();
            println!("Default: {}", settings.providers.default_provider);
        }
        ProviderAction::SetDefault { name } => {
            let valid = ["local", "anthropic", "openai"];
            if !valid.contains(&name.as_str()) {
                eprintln!("Unknown provider: {}. Valid: {:?}", name, valid);
                std::process::exit(1);
            }
            let mut settings = Settings::load(&settings_path(cli))?;
            settings.providers.default_provider = name.clone();
            settings.save(&settings_path(cli))?;
            println!("Default provider set to: {}", name);
        }
    }

    Ok(())
}

// ─── Agent commands ──────────────────────────────────────────────────────────

async fn cmd_agent(cli: &Cli, action: &AgentAction) -> anyhow::Result<()> {
    let path = settings_path(cli);

    match action {
        AgentAction::List => {
            let settings = Settings::load(&path)?;
            if settings.agents.is_empty() {
                println!("No agents configured.");
            } else {
                println!("Agents:");
                for (id, agent) in &settings.agents {
                    println!(
                        "  {} - {} (provider: {}, model: {})",
                        id, agent.name, agent.provider, agent.model
                    );
                }
            }
        }
        AgentAction::Add {
            id,
            name,
            provider,
            model,
        } => {
            let mut settings = Settings::load(&path)?;
            settings.agents.insert(
                id.clone(),
                AgentConfig {
                    name: name.clone(),
                    provider: provider.clone(),
                    model: model.clone(),
                    working_directory: String::new(),
                },
            );
            settings.save(&path)?;
            println!("Agent '{}' added.", id);
        }
        AgentAction::Remove { id } => {
            let mut settings = Settings::load(&path)?;
            if settings.agents.remove(id).is_some() {
                settings.save(&path)?;
                println!("Agent '{}' removed.", id);
            } else {
                eprintln!("Agent '{}' not found.", id);
            }
        }
    }

    Ok(())
}

// ─── Team commands ───────────────────────────────────────────────────────────

async fn cmd_team(cli: &Cli, action: &TeamAction) -> anyhow::Result<()> {
    let path = settings_path(cli);

    match action {
        TeamAction::List => {
            let settings = Settings::load(&path)?;
            if settings.teams.is_empty() {
                println!("No teams configured.");
            } else {
                println!("Teams:");
                for (id, team) in &settings.teams {
                    println!(
                        "  {} - {} (leader: {}, members: {:?}, strategy: {})",
                        id, team.name, team.leader_agent, team.agents, team.strategy
                    );
                }
            }
        }
        TeamAction::Add {
            id,
            name,
            leader,
            members,
            strategy,
        } => {
            let mut settings = Settings::load(&path)?;
            settings.teams.insert(
                id.clone(),
                TeamConfig {
                    name: name.clone(),
                    agents: members.clone(),
                    leader_agent: leader.clone(),
                    strategy: strategy.clone(),
                },
            );
            settings.save(&path)?;
            println!("Team '{}' added.", id);
        }
        TeamAction::Remove { id } => {
            let mut settings = Settings::load(&path)?;
            if settings.teams.remove(id).is_some() {
                settings.save(&path)?;
                println!("Team '{}' removed.", id);
            } else {
                eprintln!("Team '{}' not found.", id);
            }
        }
    }

    Ok(())
}

// ─── Dashboard command ───────────────────────────────────────────────────────

async fn cmd_dashboard(cli: &Cli) -> anyhow::Result<()> {
    let _settings = match Settings::load(&settings_path(cli)) {
        Ok(s) => s,
        Err(_) => {
            eprintln!("No configuration found. Run 'tinyclaw setup' first.");
            std::process::exit(1);
        }
    };

    let event_bus = EventBus::new(256);
    tinyclaw_tui::run_dashboard(event_bus).await
}
