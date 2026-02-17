use ratatui::prelude::*;
use ratatui::widgets::{Block, Borders, Paragraph};
use std::time::Instant;

pub fn render(
    f: &mut Frame,
    area: Rect,
    total_messages: u64,
    errors: u64,
    start_time: Instant,
    highlighted: bool,
) {
    let border_style = if highlighted {
        Style::default().fg(Color::Yellow)
    } else {
        Style::default()
    };

    let uptime = start_time.elapsed();
    let hours = uptime.as_secs() / 3600;
    let minutes = (uptime.as_secs() % 3600) / 60;
    let seconds = uptime.as_secs() % 60;

    let text = vec![
        Line::from(vec![
            Span::styled("Messages: ", Style::default().fg(Color::DarkGray)),
            Span::styled(
                total_messages.to_string(),
                Style::default().fg(Color::White).add_modifier(Modifier::BOLD),
            ),
        ]),
        Line::from(vec![
            Span::styled("Errors:   ", Style::default().fg(Color::DarkGray)),
            Span::styled(
                errors.to_string(),
                if errors > 0 {
                    Style::default().fg(Color::Red)
                } else {
                    Style::default().fg(Color::Green)
                },
            ),
        ]),
        Line::from(vec![
            Span::styled("Uptime:   ", Style::default().fg(Color::DarkGray)),
            Span::styled(
                format!("{:02}:{:02}:{:02}", hours, minutes, seconds),
                Style::default().fg(Color::White),
            ),
        ]),
        Line::from(""),
        Line::from(Span::styled(
            "q=quit  tab=panel  j/k=scroll",
            Style::default().fg(Color::DarkGray),
        )),
    ];

    let block = Block::default()
        .borders(Borders::ALL)
        .title(" Stats ")
        .border_style(border_style);

    let para = Paragraph::new(text).block(block);
    f.render_widget(para, area);
}
