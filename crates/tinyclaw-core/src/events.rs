use serde::{Deserialize, Serialize};

/// Events emitted by the system for cross-crate communication (TUI, logging, etc.)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum TinyClawEvent {
    MessageReceived {
        channel: String,
        sender: String,
        agent: Option<String>,
        message_preview: String,
    },
    MessageProcessing {
        agent: Option<String>,
        message_id: String,
    },
    MessageCompleted {
        agent: Option<String>,
        message_id: String,
        response_len: usize,
        duration_ms: u64,
    },
    AgentStatusChanged {
        agent_id: String,
        status: AgentStatus,
    },
    Error {
        context: String,
        message: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum AgentStatus {
    Idle,
    Processing,
    Error,
}

/// Broadcast-based event bus for system-wide event distribution.
#[derive(Clone)]
pub struct EventBus {
    sender: tokio::sync::broadcast::Sender<TinyClawEvent>,
}

impl EventBus {
    pub fn new(capacity: usize) -> Self {
        let (sender, _) = tokio::sync::broadcast::channel(capacity);
        Self { sender }
    }

    pub fn emit(&self, event: TinyClawEvent) {
        // Ignore send errors (no subscribers)
        let _ = self.sender.send(event);
    }

    pub fn subscribe(&self) -> tokio::sync::broadcast::Receiver<TinyClawEvent> {
        self.sender.subscribe()
    }
}

impl Default for EventBus {
    fn default() -> Self {
        Self::new(256)
    }
}
