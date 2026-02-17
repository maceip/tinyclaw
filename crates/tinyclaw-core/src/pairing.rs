use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

const PAIRING_ALPHABET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const CODE_LENGTH: usize = 8;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedSender {
    pub code: String,
    pub name: String,
    pub paired_at: u64,
    pub channel: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PairingState {
    /// Approved senders: sender_id -> PairedSender
    pub paired: HashMap<String, PairedSender>,
    /// Pending codes: code -> sender_id
    #[serde(default)]
    pub pending: HashMap<String, String>,
}

#[derive(Debug)]
pub enum PairingResult {
    Paired,
    PendingApproval(String),
}

/// Generate a random 8-character pairing code from the allowed alphabet.
pub fn generate_pairing_code() -> String {
    use rand::Rng;
    let mut rng = rand::rng();
    (0..CODE_LENGTH)
        .map(|_| {
            let idx = rng.random_range(0..PAIRING_ALPHABET.len());
            PAIRING_ALPHABET[idx] as char
        })
        .collect()
}

fn pairing_path(data_dir: &Path) -> PathBuf {
    data_dir.join("pairing.json")
}

/// Load pairing state from disk.
pub fn load_state(data_dir: &Path) -> PairingState {
    let path = pairing_path(data_dir);
    match std::fs::read_to_string(&path) {
        Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
        Err(_) => PairingState::default(),
    }
}

/// Save pairing state to disk.
pub fn save_state(data_dir: &Path, state: &PairingState) -> anyhow::Result<()> {
    let path = pairing_path(data_dir);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let content = serde_json::to_string_pretty(state)?;
    std::fs::write(path, content)?;
    Ok(())
}

/// Check if a sender is paired. If not, generate a pending code.
pub fn ensure_sender_paired(
    state: &mut PairingState,
    sender_id: &str,
    _channel: &str,
    data_dir: &Path,
) -> PairingResult {
    if state.paired.contains_key(sender_id) {
        return PairingResult::Paired;
    }

    // Check if there's already a pending code for this sender
    for (code, sid) in &state.pending {
        if sid == sender_id {
            return PairingResult::PendingApproval(code.clone());
        }
    }

    // Generate a new code
    let code = generate_pairing_code();
    state.pending.insert(code.clone(), sender_id.to_string());
    let _ = save_state(data_dir, state);

    PairingResult::PendingApproval(code)
}

/// Approve a pending pairing code. Returns the sender_id on success.
pub fn approve_pairing_code(
    state: &mut PairingState,
    code: &str,
    data_dir: &Path,
) -> anyhow::Result<String> {
    let sender_id = state
        .pending
        .remove(code)
        .ok_or_else(|| anyhow::anyhow!("Unknown pairing code: {}", code))?;

    state.paired.insert(
        sender_id.clone(),
        PairedSender {
            code: code.to_string(),
            name: sender_id.clone(),
            paired_at: crate::channel::now_millis(),
            channel: String::new(),
        },
    );

    save_state(data_dir, state)?;
    Ok(sender_id)
}

/// Revoke a paired sender.
pub fn revoke_sender(
    state: &mut PairingState,
    sender_id: &str,
    data_dir: &Path,
) -> anyhow::Result<()> {
    if state.paired.remove(sender_id).is_none() {
        anyhow::bail!("Sender not found: {}", sender_id);
    }
    save_state(data_dir, state)?;
    Ok(())
}
