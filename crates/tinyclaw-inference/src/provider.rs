use anyhow::Result;
use async_trait::async_trait;
use std::collections::HashMap;
use std::sync::Arc;

/// Configuration passed to providers for each invocation.
#[derive(Debug, Clone)]
pub struct InvokeConfig {
    pub model: String,
    pub max_tokens: u32,
    pub working_directory: Option<String>,
}

/// Trait for inference providers (local, Anthropic CLI, OpenAI CLI, etc.)
#[async_trait]
pub trait Provider: Send + Sync {
    fn name(&self) -> &str;

    async fn invoke(
        &self,
        prompt: &str,
        context: &[serde_json::Value],
        config: &InvokeConfig,
    ) -> Result<String>;
}

// ─── Anthropic Provider (claude CLI) ─────────────────────────────────────────

pub struct AnthropicProvider {
    cli_path: String,
}

impl AnthropicProvider {
    pub fn new(cli_path: Option<String>) -> Self {
        Self {
            cli_path: cli_path.unwrap_or_else(|| "claude".to_string()),
        }
    }
}

#[async_trait]
impl Provider for AnthropicProvider {
    fn name(&self) -> &str {
        "anthropic"
    }

    async fn invoke(
        &self,
        prompt: &str,
        _context: &[serde_json::Value],
        config: &InvokeConfig,
    ) -> Result<String> {
        let mut cmd = tokio::process::Command::new(&self.cli_path);
        cmd.args(["--model", &config.model, "--print", "--no-input", "-p", prompt]);

        if let Some(ref dir) = config.working_directory {
            cmd.current_dir(dir);
        }

        cmd.stdout(std::process::Stdio::piped());
        cmd.stderr(std::process::Stdio::piped());

        let output = cmd.output().await?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            anyhow::bail!("claude CLI failed: {}", stderr);
        }

        Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
    }
}

// ─── OpenAI Provider (codex CLI) ─────────────────────────────────────────────

pub struct OpenAiProvider {
    cli_path: String,
}

impl OpenAiProvider {
    pub fn new(cli_path: Option<String>) -> Self {
        Self {
            cli_path: cli_path.unwrap_or_else(|| "codex".to_string()),
        }
    }
}

#[async_trait]
impl Provider for OpenAiProvider {
    fn name(&self) -> &str {
        "openai"
    }

    async fn invoke(
        &self,
        prompt: &str,
        _context: &[serde_json::Value],
        config: &InvokeConfig,
    ) -> Result<String> {
        let mut cmd = tokio::process::Command::new(&self.cli_path);
        cmd.args(["--model", &config.model, "--quiet", prompt]);

        if let Some(ref dir) = config.working_directory {
            cmd.current_dir(dir);
        }

        cmd.stdout(std::process::Stdio::piped());
        cmd.stderr(std::process::Stdio::piped());

        let output = cmd.output().await?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            anyhow::bail!("codex CLI failed: {}", stderr);
        }

        Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
    }
}

// ─── Local Provider (LiteRT-LM HTTP) ────────────────────────────────────────

pub struct LocalProvider {
    server_url: String,
    http_client: reqwest::Client,
}

impl LocalProvider {
    pub fn new(server_url: &str) -> Self {
        Self {
            server_url: server_url.to_string(),
            http_client: reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(120))
                .build()
                .expect("failed to build HTTP client"),
        }
    }
}

#[async_trait]
impl Provider for LocalProvider {
    fn name(&self) -> &str {
        "local"
    }

    async fn invoke(
        &self,
        _prompt: &str,
        context: &[serde_json::Value],
        config: &InvokeConfig,
    ) -> Result<String> {
        let request_body = serde_json::json!({
            "model": config.model,
            "messages": context,
            "max_tokens": config.max_tokens,
            "stream": false
        });

        let response = self
            .http_client
            .post(format!("{}/v1/chat/completions", self.server_url))
            .json(&request_body)
            .send()
            .await?;

        if !response.status().is_success() {
            let status = response.status();
            let body = response.text().await.unwrap_or_default();
            anyhow::bail!("Inference server returned {}: {}", status, body);
        }

        let body: serde_json::Value = response.json().await?;

        let text = body["choices"][0]["message"]["content"]
            .as_str()
            .unwrap_or("Sorry, I could not generate a response.")
            .to_string();

        // Truncate at 4000 chars
        let text = if text.len() > 4000 {
            format!("{}\n\n[Response truncated...]", &text[..3900])
        } else {
            text
        };

        Ok(text)
    }
}

// ─── Provider Registry ──────────────────────────────────────────────────────

pub struct ProviderRegistry {
    providers: HashMap<String, Arc<dyn Provider>>,
    default_provider: String,
}

impl ProviderRegistry {
    pub fn new(default_provider: &str) -> Self {
        Self {
            providers: HashMap::new(),
            default_provider: default_provider.to_string(),
        }
    }

    pub fn register(&mut self, provider: Arc<dyn Provider>) {
        self.providers
            .insert(provider.name().to_string(), provider);
    }

    pub fn get(&self, name: &str) -> Option<Arc<dyn Provider>> {
        self.providers.get(name).cloned()
    }

    pub fn get_default(&self) -> Option<Arc<dyn Provider>> {
        self.get(&self.default_provider)
    }

    pub fn default_name(&self) -> &str {
        &self.default_provider
    }

    pub fn set_default(&mut self, name: &str) {
        self.default_provider = name.to_string();
    }

    pub fn list(&self) -> Vec<&str> {
        self.providers.keys().map(|s| s.as_str()).collect()
    }

    /// Build a registry with all standard providers registered.
    pub fn build_default(
        default: &str,
        local_server_url: &str,
        anthropic_cli: Option<String>,
        openai_cli: Option<String>,
    ) -> Self {
        let mut registry = Self::new(default);
        registry.register(Arc::new(LocalProvider::new(local_server_url)));
        registry.register(Arc::new(AnthropicProvider::new(anthropic_cli)));
        registry.register(Arc::new(OpenAiProvider::new(openai_cli)));
        registry
    }
}
