use crate::app::AgentEntry;
use ratatui::prelude::*;
use ratatui::widgets::{Block, Borders, Paragraph};
use tinyclaw_core::events::AgentStatus;

pub fn render(f: &mut Frame, area: Rect, agents: &[AgentEntry]) {
    let block = Block::default()
        .borders(Borders::ALL)
        .title(" Team Activity ");

    if agents.is_empty() {
        let para = Paragraph::new("  No team activity").block(block);
        f.render_widget(para, area);
        return;
    }

    // Simple visualization: show agents and their processing connections
    let lines: Vec<Line> = agents
        .iter()
        .map(|agent| {
            let (icon, style) = match agent.status {
                AgentStatus::Idle => (".", Style::default().fg(Color::DarkGray)),
                AgentStatus::Processing => (">", Style::default().fg(Color::Yellow)),
                AgentStatus::Error => ("x", Style::default().fg(Color::Red)),
            };

            Line::from(vec![
                Span::styled(format!(" {} ", icon), style),
                Span::styled(&agent.id, Style::default().fg(Color::White)),
                Span::styled(
                    format!(" ({} msgs)", agent.messages_handled),
                    Style::default().fg(Color::DarkGray),
                ),
            ])
        })
        .collect();

    let para = Paragraph::new(lines).block(block);
    f.render_widget(para, area);
}
