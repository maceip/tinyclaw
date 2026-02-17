use crate::widgets::{agent_panel, message_log, stats_panel, team_view};
use crossterm::event::{self, Event, KeyCode, KeyEventKind};
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use ratatui::prelude::*;
use ratatui::widgets::{Block, Borders, Tabs};
use std::io;
use std::time::{Duration, Instant};
use tinyclaw_core::events::{AgentStatus, EventBus, TinyClawEvent};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Panel {
    Agents,
    Messages,
    Stats,
}

impl Panel {
    fn next(self) -> Self {
        match self {
            Panel::Agents => Panel::Messages,
            Panel::Messages => Panel::Stats,
            Panel::Stats => Panel::Agents,
        }
    }
}

/// Tracked state for the dashboard.
pub struct DashboardState {
    active_panel: Panel,
    agents: Vec<AgentEntry>,
    messages: Vec<MessageEntry>,
    total_messages: u64,
    errors: u64,
    start_time: Instant,
    scroll_offset: usize,
    selected_agent: usize,
}

#[derive(Debug, Clone)]
pub struct AgentEntry {
    pub id: String,
    pub status: AgentStatus,
    pub messages_handled: u64,
}

#[derive(Debug, Clone)]
pub struct MessageEntry {
    pub timestamp: String,
    pub channel: String,
    pub sender: String,
    pub preview: String,
    pub agent: Option<String>,
    pub is_response: bool,
}

impl DashboardState {
    fn new() -> Self {
        Self {
            active_panel: Panel::Agents,
            agents: Vec::new(),
            messages: Vec::new(),
            total_messages: 0,
            errors: 0,
            start_time: Instant::now(),
            scroll_offset: 0,
            selected_agent: 0,
        }
    }

    fn handle_event(&mut self, event: TinyClawEvent) {
        match event {
            TinyClawEvent::MessageReceived {
                channel,
                sender,
                agent,
                message_preview,
            } => {
                self.total_messages += 1;
                self.messages.push(MessageEntry {
                    timestamp: chrono::Local::now().format("%H:%M:%S").to_string(),
                    channel,
                    sender,
                    preview: message_preview,
                    agent,
                    is_response: false,
                });
                // Keep last 200 messages
                if self.messages.len() > 200 {
                    self.messages.remove(0);
                }
            }
            TinyClawEvent::MessageCompleted {
                agent, message_id, ..
            } => {
                if let Some(agent_id) = &agent {
                    if let Some(a) = self.agents.iter_mut().find(|a| &a.id == agent_id) {
                        a.messages_handled += 1;
                    }
                }
                self.messages.push(MessageEntry {
                    timestamp: chrono::Local::now().format("%H:%M:%S").to_string(),
                    channel: String::new(),
                    sender: String::new(),
                    preview: format!("Completed: {}", message_id),
                    agent,
                    is_response: true,
                });
                if self.messages.len() > 200 {
                    self.messages.remove(0);
                }
            }
            TinyClawEvent::AgentStatusChanged { agent_id, status } => {
                if let Some(a) = self.agents.iter_mut().find(|a| a.id == agent_id) {
                    a.status = status;
                } else {
                    self.agents.push(AgentEntry {
                        id: agent_id,
                        status,
                        messages_handled: 0,
                    });
                }
            }
            TinyClawEvent::Error { .. } => {
                self.errors += 1;
            }
            TinyClawEvent::MessageProcessing { agent, .. } => {
                if let Some(agent_id) = &agent {
                    if let Some(a) = self.agents.iter_mut().find(|a| &a.id == agent_id) {
                        a.status = AgentStatus::Processing;
                    }
                }
            }
        }
    }
}

/// Launch the TUI dashboard, subscribing to the event bus.
pub async fn run_dashboard(event_bus: EventBus) -> anyhow::Result<()> {
    // Setup terminal
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    crossterm::execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;

    let mut state = DashboardState::new();
    let mut events_rx = event_bus.subscribe();

    let tick_rate = Duration::from_millis(250);

    loop {
        // Drain pending events
        while let Ok(evt) = events_rx.try_recv() {
            state.handle_event(evt);
        }

        terminal.draw(|f| draw_ui(f, &state))?;

        // Handle keyboard input
        if event::poll(tick_rate)? {
            if let Event::Key(key) = event::read()? {
                if key.kind != KeyEventKind::Press {
                    continue;
                }
                match key.code {
                    KeyCode::Char('q') => break,
                    KeyCode::Tab => {
                        state.active_panel = state.active_panel.next();
                    }
                    KeyCode::Char('j') | KeyCode::Down => match state.active_panel {
                        Panel::Agents => {
                            if state.selected_agent + 1 < state.agents.len() {
                                state.selected_agent += 1;
                            }
                        }
                        Panel::Messages => {
                            state.scroll_offset = state.scroll_offset.saturating_add(1);
                        }
                        _ => {}
                    },
                    KeyCode::Char('k') | KeyCode::Up => match state.active_panel {
                        Panel::Agents => {
                            state.selected_agent = state.selected_agent.saturating_sub(1);
                        }
                        Panel::Messages => {
                            state.scroll_offset = state.scroll_offset.saturating_sub(1);
                        }
                        _ => {}
                    },
                    _ => {}
                }
            }
        }
    }

    // Restore terminal
    disable_raw_mode()?;
    crossterm::execute!(terminal.backend_mut(), LeaveAlternateScreen)?;
    terminal.show_cursor()?;

    Ok(())
}

fn draw_ui(f: &mut Frame, state: &DashboardState) {
    let size = f.area();

    // Top bar + main content
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Length(3), Constraint::Min(0)])
        .split(size);

    // Tab bar
    let tabs = Tabs::new(vec!["Agents", "Messages", "Stats"])
        .block(Block::default().borders(Borders::ALL).title(" TinyClaw Dashboard "))
        .select(match state.active_panel {
            Panel::Agents => 0,
            Panel::Messages => 1,
            Panel::Stats => 2,
        })
        .highlight_style(Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD));
    f.render_widget(tabs, chunks[0]);

    // 3-column layout
    let main_chunks = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([
            Constraint::Percentage(25),
            Constraint::Percentage(50),
            Constraint::Percentage(25),
        ])
        .split(chunks[1]);

    // Left: Agents
    let agent_highlight = state.active_panel == Panel::Agents;
    agent_panel::render(f, main_chunks[0], &state.agents, state.selected_agent, agent_highlight);

    // Center: Message log
    let msg_highlight = state.active_panel == Panel::Messages;
    message_log::render(f, main_chunks[1], &state.messages, state.scroll_offset, msg_highlight);

    // Right: Stats + Team view
    let right_chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Percentage(50), Constraint::Percentage(50)])
        .split(main_chunks[2]);

    let stats_highlight = state.active_panel == Panel::Stats;
    stats_panel::render(
        f,
        right_chunks[0],
        state.total_messages,
        state.errors,
        state.start_time,
        stats_highlight,
    );
    team_view::render(f, right_chunks[1], &state.agents);
}
