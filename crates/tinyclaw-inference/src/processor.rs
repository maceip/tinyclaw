use crate::conversation::ConversationManager;
use crate::engine::InferenceEngine;
use crate::provider::{InvokeConfig, ProviderRegistry};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tinyclaw_core::channel::now_millis;
use tinyclaw_core::events::{AgentStatus, EventBus, TinyClawEvent};
use tinyclaw_core::message::{
    AgentConfig, IncomingMessage, OutgoingMessage, TeamConfig, TeamStrategy,
};
use tinyclaw_core::pairing::{self, PairingResult};
use tinyclaw_core::queue::QueueDir;
use tinyclaw_core::routing;
use tokio::sync::Mutex;

/// Run the queue processor loop. Polls incoming/ for messages, routes them
/// through pairing checks, agent routing, and multi-provider inference.
pub async fn run_queue_processor(
    queue: Arc<QueueDir>,
    engine: Arc<InferenceEngine>,
    data_dir: PathBuf,
    shutdown: tokio::sync::broadcast::Receiver<()>,
) -> anyhow::Result<()> {
    run_queue_processor_full(
        queue,
        engine,
        data_dir,
        shutdown,
        None,
        false,
        HashMap::new(),
        HashMap::new(),
        None,
    )
    .await
}

/// Full-featured queue processor with all optional systems.
pub async fn run_queue_processor_full(
    queue: Arc<QueueDir>,
    engine: Arc<InferenceEngine>,
    data_dir: PathBuf,
    mut shutdown: tokio::sync::broadcast::Receiver<()>,
    provider_registry: Option<Arc<Mutex<ProviderRegistry>>>,
    pairing_enabled: bool,
    agents: HashMap<String, AgentConfig>,
    teams: HashMap<String, TeamConfig>,
    event_bus: Option<EventBus>,
) -> anyhow::Result<()> {
    let mut poll_interval = tokio::time::interval(Duration::from_secs(1));
    let pairing_state = Arc::new(Mutex::new(pairing::load_state(&data_dir)));

    // Per-agent conversation managers
    let agent_conversations: Arc<Mutex<HashMap<String, ConversationManager>>> =
        Arc::new(Mutex::new(HashMap::new()));

    tracing::info!("Queue processor started, watching for messages");

    fn emit(bus: &Option<EventBus>, event: TinyClawEvent) {
        if let Some(ref bus) = bus {
            bus.emit(event);
        }
    }

    loop {
        tokio::select! {
            _ = poll_interval.tick() => {
                // Check reset flag
                if InferenceEngine::check_and_clear_reset_flag(&data_dir).await {
                    tracing::info!("Resetting conversation");
                    engine.reset().await;
                    agent_conversations.lock().await.clear();
                }

                // Process all pending messages (one at a time, FIFO)
                while let Some((processing_path, msg)) = queue.claim_next().await? {
                    // ── Pairing gate ──
                    if pairing_enabled {
                        let mut ps = pairing_state.lock().await;
                        match pairing::ensure_sender_paired(
                            &mut ps,
                            &msg.sender_id,
                            msg.channel.as_str(),
                            &data_dir,
                        ) {
                            PairingResult::Paired => { /* allowed */ }
                            PairingResult::PendingApproval(code) => {
                                tracing::info!(
                                    sender_id = %msg.sender_id,
                                    code = %code,
                                    "Sender not paired, approval required"
                                );
                                let response = OutgoingMessage {
                                    channel: msg.channel.clone(),
                                    sender: msg.sender.clone(),
                                    message: format!(
                                        "You need to pair first. Your pairing code is: {}\nAsk the admin to run: tinyclaw pair approve {}",
                                        code, code
                                    ),
                                    original_message: msg.message.clone(),
                                    timestamp: now_millis(),
                                    message_id: msg.message_id.clone(),
                                    agent: None,
                                    sender_id: Some(msg.sender_id.clone()),
                                    files: None,
                                };
                                let _ = queue.complete(&processing_path, &response).await;
                                continue;
                            }
                        }
                    }

                    emit(&event_bus, TinyClawEvent::MessageReceived {
                        channel: msg.channel.to_string(),
                        sender: msg.sender.clone(),
                        agent: msg.agent.clone(),
                        message_preview: msg.message.chars().take(80).collect(),
                    });

                    // ── Agent routing ──
                    let route = if let Some(ref agent_id) = msg.agent {
                        routing::RouteResult::SingleAgent(agent_id.clone())
                    } else {
                        routing::parse_agent_routing(&msg.message, &agents, &teams)
                    };

                    let response_text = match route {
                        routing::RouteResult::SingleAgent(agent_id) => {
                            emit(&event_bus, TinyClawEvent::MessageProcessing {
                                agent: Some(agent_id.clone()),
                                message_id: msg.message_id.clone(),
                            });
                            emit(&event_bus, TinyClawEvent::AgentStatusChanged {
                                agent_id: agent_id.clone(),
                                status: AgentStatus::Processing,
                            });

                            let user_msg = routing::strip_routing_prefix(&msg.message);
                            let result = invoke_for_agent(
                                &agent_id,
                                &user_msg,
                                &agents,
                                &engine,
                                &provider_registry,
                                &agent_conversations,
                            )
                            .await;

                            emit(&event_bus, TinyClawEvent::AgentStatusChanged {
                                agent_id: agent_id.clone(),
                                status: AgentStatus::Idle,
                            });

                            result
                        }
                        routing::RouteResult::TeamBroadcast(team_id) => {
                            process_team_message(
                                &team_id,
                                &msg,
                                &agents,
                                &teams,
                                &engine,
                                &provider_registry,
                                &agent_conversations,
                                &event_bus,
                            )
                            .await
                        }
                        routing::RouteResult::DefaultAgent => {
                            emit(&event_bus, TinyClawEvent::MessageProcessing {
                                agent: None,
                                message_id: msg.message_id.clone(),
                            });

                            // Use default engine (local provider)
                            match engine.process(&msg.message).await {
                                Ok(response) => response,
                                Err(e) => {
                                    tracing::error!(error = %e, "Inference error");
                                    emit(&event_bus, TinyClawEvent::Error {
                                        context: "inference".into(),
                                        message: e.to_string(),
                                    });
                                    "Sorry, I encountered an error processing your request.".to_string()
                                }
                            }
                        }
                    };

                    // ── Check for teammate mentions and spawn follow-ups ──
                    let mentions = routing::extract_teammate_mentions(&response_text);
                    for mention in &mentions {
                        if agents.contains_key(&mention.agent_id) {
                            let follow_up = IncomingMessage {
                                channel: msg.channel.clone(),
                                sender: msg.sender.clone(),
                                sender_id: msg.sender_id.clone(),
                                message: mention.message.clone(),
                                timestamp: now_millis(),
                                message_id: format!("{}_{}", msg.message_id, mention.agent_id),
                                agent: Some(mention.agent_id.clone()),
                                files: None,
                                conversation_id: msg.conversation_id.clone(),
                                from_agent: msg.agent.clone(),
                            };
                            if let Err(e) = queue.enqueue(&follow_up).await {
                                tracing::error!(error = %e, "Failed to enqueue follow-up for {}", mention.agent_id);
                            }
                        }
                    }

                    tracing::info!(
                        channel = %msg.channel,
                        sender = %msg.sender,
                        "Processing: {}...",
                        &msg.message[..msg.message.len().min(50)]
                    );

                    let response = OutgoingMessage {
                        channel: msg.channel.clone(),
                        sender: msg.sender.clone(),
                        message: response_text.clone(),
                        original_message: msg.message.clone(),
                        timestamp: now_millis(),
                        message_id: msg.message_id.clone(),
                        agent: msg.agent.clone(),
                        sender_id: Some(msg.sender_id.clone()),
                        files: None,
                    };

                    emit(&event_bus, TinyClawEvent::MessageCompleted {
                        agent: msg.agent.clone(),
                        message_id: msg.message_id.clone(),
                        response_len: response_text.len(),
                        duration_ms: 0,
                    });

                    if let Err(e) = queue.complete(&processing_path, &response).await {
                        tracing::error!(error = %e, "Failed to write response");
                        let _ = queue.retry(&processing_path).await;
                    } else {
                        tracing::info!(
                            channel = %msg.channel,
                            sender = %msg.sender,
                            len = response_text.len(),
                            "Response ready"
                        );
                    }
                }
            }
            _ = shutdown.recv() => {
                tracing::info!("Queue processor shutting down");
                break;
            }
        }
    }

    Ok(())
}

/// Invoke inference for a specific agent, using the agent's configured provider.
async fn invoke_for_agent(
    agent_id: &str,
    message: &str,
    agents: &HashMap<String, AgentConfig>,
    engine: &Arc<InferenceEngine>,
    provider_registry: &Option<Arc<Mutex<ProviderRegistry>>>,
    agent_conversations: &Arc<Mutex<HashMap<String, ConversationManager>>>,
) -> String {
    let agent_config = match agents.get(agent_id) {
        Some(c) => c,
        None => {
            return format!("Unknown agent: {}", agent_id);
        }
    };

    // Try to use the provider registry if available
    if let Some(ref registry) = provider_registry {
        let reg = registry.lock().await;
        let provider_name = &agent_config.provider;

        if let Some(provider) = reg.get(provider_name) {
            // Build conversation context for this agent
            let mut convs = agent_conversations.lock().await;
            let conv = convs
                .entry(agent_id.to_string())
                .or_insert_with(|| {
                    ConversationManager::new(format!(
                        "You are {}, a helpful AI assistant.",
                        agent_config.name
                    ))
                });

            conv.add_user_message(message.to_string());
            let context = conv.build_messages();

            let config = InvokeConfig {
                model: agent_config.model.clone(),
                max_tokens: 2048,
                working_directory: if agent_config.working_directory.is_empty() {
                    None
                } else {
                    Some(agent_config.working_directory.clone())
                },
            };

            match provider.invoke(message, &context, &config).await {
                Ok(response) => {
                    conv.add_assistant_message(response.clone());
                    return response;
                }
                Err(e) => {
                    tracing::error!(
                        error = %e,
                        agent = %agent_id,
                        provider = %provider_name,
                        "Provider invocation failed, falling back to local"
                    );
                }
            }
        }
    }

    // Fallback to local engine
    match engine.process(message).await {
        Ok(response) => response,
        Err(e) => {
            tracing::error!(error = %e, agent = %agent_id, "Inference error");
            "Sorry, I encountered an error processing your request.".to_string()
        }
    }
}

/// Process a team-targeted message using the team's configured strategy.
async fn process_team_message(
    team_id: &str,
    msg: &IncomingMessage,
    agents: &HashMap<String, AgentConfig>,
    teams: &HashMap<String, TeamConfig>,
    engine: &Arc<InferenceEngine>,
    provider_registry: &Option<Arc<Mutex<ProviderRegistry>>>,
    agent_conversations: &Arc<Mutex<HashMap<String, ConversationManager>>>,
    event_bus: &Option<EventBus>,
) -> String {
    let team = match teams.get(team_id) {
        Some(t) => t,
        None => return format!("Unknown team: {}", team_id),
    };

    let strategy = TeamStrategy::from_str(&team.strategy);
    let user_msg = routing::strip_routing_prefix(&msg.message);

    fn emit(bus: &Option<EventBus>, event: TinyClawEvent) {
        if let Some(ref bus) = bus {
            bus.emit(event);
        }
    }

    match strategy {
        TeamStrategy::LeaderRoutes => {
            // Ask the leader which agent should handle this
            let leader = &team.leader_agent;
            if leader.is_empty() || !agents.contains_key(leader) {
                return "Team has no valid leader agent configured.".to_string();
            }

            let routing_prompt = format!(
                "You are the team leader. Your team members are: {}. \
                 A user asked: \"{}\"\n\
                 Which team member should handle this? Respond with just the agent name.",
                team.agents.join(", "),
                user_msg
            );

            emit(event_bus, TinyClawEvent::AgentStatusChanged {
                agent_id: leader.clone(),
                status: AgentStatus::Processing,
            });

            let chosen = invoke_for_agent(
                leader,
                &routing_prompt,
                agents,
                engine,
                provider_registry,
                agent_conversations,
            )
            .await;

            emit(event_bus, TinyClawEvent::AgentStatusChanged {
                agent_id: leader.clone(),
                status: AgentStatus::Idle,
            });

            // Find the chosen agent
            let chosen_agent = team
                .agents
                .iter()
                .find(|a| chosen.to_lowercase().contains(&a.to_lowercase()))
                .cloned()
                .unwrap_or_else(|| team.agents.first().cloned().unwrap_or_default());

            if chosen_agent.is_empty() || !agents.contains_key(&chosen_agent) {
                return "Could not determine which agent should handle this.".to_string();
            }

            emit(event_bus, TinyClawEvent::AgentStatusChanged {
                agent_id: chosen_agent.clone(),
                status: AgentStatus::Processing,
            });

            let result = invoke_for_agent(
                &chosen_agent,
                &user_msg,
                agents,
                engine,
                provider_registry,
                agent_conversations,
            )
            .await;

            emit(event_bus, TinyClawEvent::AgentStatusChanged {
                agent_id: chosen_agent.clone(),
                status: AgentStatus::Idle,
            });

            result
        }

        TeamStrategy::Chain => {
            // Sequential: each agent sees the previous output
            let mut current_input = user_msg.clone();

            for agent_id in &team.agents {
                if !agents.contains_key(agent_id) {
                    continue;
                }

                emit(event_bus, TinyClawEvent::AgentStatusChanged {
                    agent_id: agent_id.clone(),
                    status: AgentStatus::Processing,
                });

                current_input = invoke_for_agent(
                    agent_id,
                    &current_input,
                    agents,
                    engine,
                    provider_registry,
                    agent_conversations,
                )
                .await;

                emit(event_bus, TinyClawEvent::AgentStatusChanged {
                    agent_id: agent_id.clone(),
                    status: AgentStatus::Idle,
                });
            }

            current_input
        }

        TeamStrategy::FanOut => {
            // Parallel: all agents process simultaneously
            let mut handles = Vec::new();

            for agent_id in &team.agents {
                if !agents.contains_key(agent_id) {
                    continue;
                }

                let aid = agent_id.clone();
                let umsg = user_msg.clone();
                let agents_clone = agents.clone();
                let engine_clone = engine.clone();
                let pr_clone = provider_registry.clone();
                let ac_clone = agent_conversations.clone();
                let eb_clone = event_bus.clone();

                handles.push(tokio::spawn(async move {
                    if let Some(ref bus) = eb_clone {
                        bus.emit(TinyClawEvent::AgentStatusChanged {
                            agent_id: aid.clone(),
                            status: AgentStatus::Processing,
                        });
                    }

                    let result = invoke_for_agent(
                        &aid,
                        &umsg,
                        &agents_clone,
                        &engine_clone,
                        &pr_clone,
                        &ac_clone,
                    )
                    .await;

                    if let Some(ref bus) = eb_clone {
                        bus.emit(TinyClawEvent::AgentStatusChanged {
                            agent_id: aid.clone(),
                            status: AgentStatus::Idle,
                        });
                    }

                    (aid, result)
                }));
            }

            let mut responses = Vec::new();
            for handle in handles {
                if let Ok((agent_id, response)) = handle.await {
                    responses.push(format!("[{}]: {}", agent_id, response));
                }
            }

            responses.join("\n\n")
        }
    }
}
