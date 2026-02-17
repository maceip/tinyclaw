use dashmap::DashMap;
use std::sync::Arc;
use tinyclaw_core::channel::{generate_message_id, now_millis, ChannelClient};
use tinyclaw_core::config::EmailConfig;
use tinyclaw_core::message::{Channel, IncomingMessage};
use tinyclaw_core::queue::QueueDir;

/// Email channel client using IMAP (receive) and SMTP (send).
/// Polls an IMAP mailbox for new unseen messages and sends replies via SMTP.
pub struct EmailClient {
    config: EmailConfig,
}

impl EmailClient {
    pub fn new(config: EmailConfig) -> Self {
        Self { config }
    }
}

#[derive(Debug, Clone)]
struct PendingEmail {
    from_addr: String,
    subject: String,
}

#[async_trait::async_trait]
impl ChannelClient for EmailClient {
    fn name(&self) -> &str {
        "Email"
    }

    fn channel_id(&self) -> Channel {
        Channel::Email
    }

    async fn start(
        self: Arc<Self>,
        queue: Arc<QueueDir>,
        mut shutdown: tokio::sync::broadcast::Receiver<()>,
    ) -> anyhow::Result<()> {
        let pending: Arc<DashMap<String, PendingEmail>> = Arc::new(DashMap::new());

        // Spawn outgoing queue poller (sends replies via SMTP)
        let config_out = self.config.clone();
        let queue_out = queue.clone();
        let pending_out = pending.clone();
        let mut shutdown_out = shutdown.resubscribe();
        tokio::spawn(async move {
            let mut interval =
                tokio::time::interval(std::time::Duration::from_secs(5));
            loop {
                tokio::select! {
                    _ = interval.tick() => {
                        if let Err(e) = poll_outgoing(&queue_out, &pending_out, &config_out).await {
                            tracing::error!(error = %e, "Email outgoing poll error");
                        }
                    }
                    _ = shutdown_out.recv() => break,
                }
            }
        });

        // Main IMAP polling loop
        let poll_secs = self.config.poll_interval;
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(poll_secs));

        tracing::info!(
            host = %self.config.imap_host,
            mailbox = %self.config.mailbox,
            poll_interval = poll_secs,
            "Email channel starting"
        );

        loop {
            tokio::select! {
                _ = interval.tick() => {
                    let config = self.config.clone();
                    let queue_ref = queue.clone();
                    let pending_ref = pending.clone();

                    // IMAP is blocking, run in a spawn_blocking
                    if let Err(e) = tokio::task::spawn_blocking(move || {
                        poll_imap(&config, &queue_ref, &pending_ref)
                    }).await? {
                        tracing::error!(error = %e, "IMAP poll error");
                    }
                }
                _ = shutdown.recv() => {
                    tracing::info!("Email client shutting down");
                    break;
                }
            }
        }

        Ok(())
    }
}

/// Poll IMAP for unseen messages and enqueue them.
fn poll_imap(
    config: &EmailConfig,
    queue: &QueueDir,
    pending: &DashMap<String, PendingEmail>,
) -> anyhow::Result<()> {
    let tls = native_tls::TlsConnector::builder().build()?;
    let client = imap::connect(
        (&*config.imap_host, config.imap_port),
        &config.imap_host,
        &tls,
    )?;

    let mut session = client
        .login(&config.username, &config.password)
        .map_err(|e| anyhow::anyhow!("IMAP login failed: {}", e.0))?;

    session.select(&config.mailbox)?;

    // Search for unseen messages
    let uids = session.uid_search("UNSEEN")?;
    if uids.is_empty() {
        session.logout()?;
        return Ok(());
    }

    let uid_list: String = uids
        .iter()
        .map(|u| u.to_string())
        .collect::<Vec<_>>()
        .join(",");

    let messages = session.uid_fetch(&uid_list, "(UID RFC822)")?;

    for msg in messages.iter() {
        let uid = match msg.uid {
            Some(u) => u,
            None => continue,
        };

        let body = match msg.body() {
            Some(b) => b,
            None => continue,
        };

        let parsed = match mailparse::parse_mail(body) {
            Ok(p) => p,
            Err(e) => {
                tracing::warn!(uid = uid, error = %e, "Failed to parse email");
                continue;
            }
        };

        let from = parsed
            .headers
            .iter()
            .find(|h| h.get_key_ref().eq_ignore_ascii_case("from"))
            .map(|h| h.get_value())
            .unwrap_or_else(|| "unknown".to_string());

        let subject = parsed
            .headers
            .iter()
            .find(|h| h.get_key_ref().eq_ignore_ascii_case("subject"))
            .map(|h| h.get_value())
            .unwrap_or_default();

        // Extract plain text body
        let text_body = extract_text_body(&parsed);

        if text_body.trim().is_empty() {
            continue;
        }

        let message_id = generate_message_id();

        // Extract email address from "Name <addr>" format
        let from_addr = extract_email_address(&from);
        let sender_name = extract_sender_name(&from);

        let incoming = IncomingMessage {
            channel: Channel::Email,
            sender: sender_name,
            sender_id: from_addr.clone(),
            message: text_body,
            timestamp: now_millis(),
            message_id: message_id.clone(),
            agent: None,
            files: None,
            conversation_id: None,
            from_agent: None,
        };

        // Use a runtime handle to call the async enqueue from blocking context
        let rt = tokio::runtime::Handle::current();
        if let Err(e) = rt.block_on(queue.enqueue(&incoming)) {
            tracing::error!(error = %e, "Failed to enqueue email message");
            continue;
        }

        pending.insert(
            message_id.clone(),
            PendingEmail {
                from_addr,
                subject,
            },
        );

        tracing::info!(uid = uid, from = %from, "Email message queued: {}", message_id);

        // Mark as seen
        let _ = session.uid_store(&uid.to_string(), "+FLAGS (\\Seen)");
    }

    session.logout()?;
    Ok(())
}

/// Poll outgoing queue and send replies via SMTP.
async fn poll_outgoing(
    queue: &QueueDir,
    pending: &DashMap<String, PendingEmail>,
    config: &EmailConfig,
) -> anyhow::Result<()> {
    let responses = queue.poll_outgoing("email_").await?;

    for (path, response) in responses {
        let email_info = pending.remove(&response.message_id);

        let to_addr = email_info
            .as_ref()
            .map(|(_, info)| info.from_addr.clone())
            .or_else(|| response.sender_id.clone())
            .unwrap_or_else(|| response.sender.clone());

        let subject = email_info
            .as_ref()
            .map(|(_, info)| format!("Re: {}", info.subject))
            .unwrap_or_else(|| "Re: TinyClaw".to_string());

        // Build and send email
        match send_email_reply(config, &to_addr, &subject, &response.message).await {
            Ok(()) => {
                tracing::info!(
                    to = %to_addr,
                    len = response.message.len(),
                    "Email reply sent"
                );
                queue.ack_outgoing(&path).await?;
            }
            Err(e) => {
                tracing::error!(error = %e, to = %to_addr, "Failed to send email reply");
                // Don't ack — will retry next poll
            }
        }
    }

    Ok(())
}

async fn send_email_reply(
    config: &EmailConfig,
    to: &str,
    subject: &str,
    body: &str,
) -> anyhow::Result<()> {
    use lettre::message::header::ContentType;
    use lettre::transport::smtp::authentication::Credentials;
    use lettre::{AsyncSmtpTransport, AsyncTransport, Message, Tokio1Executor};

    let email = Message::builder()
        .from(config.username.parse()?)
        .to(to.parse()?)
        .subject(subject)
        .header(ContentType::TEXT_PLAIN)
        .body(body.to_string())?;

    let creds = Credentials::new(config.username.clone(), config.password.clone());

    let mailer = AsyncSmtpTransport::<Tokio1Executor>::starttls_relay(&config.smtp_host)?
        .port(config.smtp_port)
        .credentials(creds)
        .build();

    mailer.send(email).await?;

    Ok(())
}

/// Extract the plain text body from a parsed email.
fn extract_text_body(parsed: &mailparse::ParsedMail) -> String {
    // If it's a simple message with a text body
    if parsed.subparts.is_empty() {
        return parsed.get_body().unwrap_or_default();
    }

    // Multipart: find the text/plain part
    for part in &parsed.subparts {
        let ct = part.ctype.mimetype.to_lowercase();
        if ct == "text/plain" {
            return part.get_body().unwrap_or_default();
        }
    }

    // Fallback: try the first part
    parsed
        .subparts
        .first()
        .and_then(|p| p.get_body().ok())
        .unwrap_or_default()
}

/// Extract email address from "Display Name <user@example.com>" format.
fn extract_email_address(from: &str) -> String {
    if let Some(start) = from.find('<') {
        if let Some(end) = from.find('>') {
            return from[start + 1..end].trim().to_string();
        }
    }
    from.trim().to_string()
}

/// Extract display name from "Display Name <user@example.com>" format.
fn extract_sender_name(from: &str) -> String {
    if let Some(start) = from.find('<') {
        let name = from[..start].trim().trim_matches('"');
        if !name.is_empty() {
            return name.to_string();
        }
    }
    from.trim().to_string()
}
