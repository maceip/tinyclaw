use crate::app::MessageEntry;
use ratatui::prelude::*;
use ratatui::widgets::{Block, Borders, Paragraph, Wrap};

pub fn render(
    f: &mut Frame,
    area: Rect,
    messages: &[MessageEntry],
    scroll_offset: usize,
    highlighted: bool,
) {
    let border_style = if highlighted {
        Style::default().fg(Color::Yellow)
    } else {
        Style::default()
    };

    let block = Block::default()
        .borders(Borders::ALL)
        .title(" Messages ")
        .border_style(border_style);

    if messages.is_empty() {
        let para = Paragraph::new("  Waiting for messages...")
            .block(block)
            .wrap(Wrap { trim: true });
        f.render_widget(para, area);
        return;
    }

    let lines: Vec<Line> = messages
        .iter()
        .skip(scroll_offset)
        .flat_map(|msg| {
            let style = if msg.is_response {
                Style::default().fg(Color::Cyan)
            } else {
                Style::default()
            };

            let agent_tag = msg
                .agent
                .as_ref()
                .map(|a| format!("[{}] ", a))
                .unwrap_or_default();

            vec![
                Line::from(vec![
                    Span::styled(
                        format!("{} ", msg.timestamp),
                        Style::default().fg(Color::DarkGray),
                    ),
                    Span::styled(
                        format!("{} ", msg.channel),
                        Style::default().fg(Color::Blue),
                    ),
                    Span::styled(agent_tag, Style::default().fg(Color::Magenta)),
                    Span::styled(&msg.sender, Style::default().fg(Color::Green)),
                ]),
                Line::from(Span::styled(
                    format!("  {}", msg.preview),
                    style,
                )),
            ]
        })
        .collect();

    let para = Paragraph::new(lines)
        .block(block)
        .wrap(Wrap { trim: true });
    f.render_widget(para, area);
}
