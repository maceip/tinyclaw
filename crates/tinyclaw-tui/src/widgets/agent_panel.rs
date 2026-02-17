use crate::app::AgentEntry;
use ratatui::prelude::*;
use ratatui::widgets::{Block, Borders, List, ListItem};
use tinyclaw_core::events::AgentStatus;

pub fn render(
    f: &mut Frame,
    area: Rect,
    agents: &[AgentEntry],
    selected: usize,
    highlighted: bool,
) {
    let border_style = if highlighted {
        Style::default().fg(Color::Yellow)
    } else {
        Style::default()
    };

    let items: Vec<ListItem> = agents
        .iter()
        .enumerate()
        .map(|(i, agent)| {
            let (indicator, style) = match agent.status {
                AgentStatus::Idle => ("*", Style::default().fg(Color::Green)),
                AgentStatus::Processing => ("~", Style::default().fg(Color::Yellow)),
                AgentStatus::Error => ("!", Style::default().fg(Color::Red)),
            };

            let content = format!(
                "{} {} ({})",
                indicator, agent.id, agent.messages_handled
            );

            let item = ListItem::new(content).style(style);
            if i == selected && highlighted {
                item.style(style.add_modifier(Modifier::REVERSED))
            } else {
                item
            }
        })
        .collect();

    let block = Block::default()
        .borders(Borders::ALL)
        .title(" Agents ")
        .border_style(border_style);

    if items.is_empty() {
        let list = List::new(vec![ListItem::new("  No agents configured")])
            .block(block);
        f.render_widget(list, area);
    } else {
        let list = List::new(items).block(block);
        f.render_widget(list, area);
    }
}
