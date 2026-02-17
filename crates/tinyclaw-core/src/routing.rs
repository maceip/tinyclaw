use crate::message::{AgentConfig, TeamConfig};
use std::collections::HashMap;

/// Result of routing an incoming message to an agent or team.
#[derive(Debug, Clone)]
pub enum RouteResult {
    /// Message targets a specific agent by name/prefix.
    SingleAgent(String),
    /// Message targets a team.
    TeamBroadcast(String),
    /// No routing prefix found; use default agent.
    DefaultAgent,
}

/// Parsed mention extracted from an agent response.
#[derive(Debug, Clone)]
pub struct TeammateMention {
    pub agent_id: String,
    pub message: String,
}

/// Parse agent routing from a message. Matches `@agent_name` or `@team_name` prefix.
pub fn parse_agent_routing(
    message: &str,
    agents: &HashMap<String, AgentConfig>,
    teams: &HashMap<String, TeamConfig>,
) -> RouteResult {
    let trimmed = message.trim();

    // Check for @prefix routing
    if !trimmed.starts_with('@') {
        return RouteResult::DefaultAgent;
    }

    // Extract the name after @
    let rest = &trimmed[1..];
    let name = rest
        .split_once(|c: char| c.is_whitespace())
        .map(|(n, _)| n)
        .unwrap_or(rest)
        .to_lowercase();

    // Check agents first (exact match or prefix)
    for agent_id in agents.keys() {
        if agent_id.to_lowercase() == name
            || agent_id.to_lowercase().starts_with(&name)
        {
            return RouteResult::SingleAgent(agent_id.clone());
        }
    }

    // Check teams
    for team_id in teams.keys() {
        if team_id.to_lowercase() == name
            || team_id.to_lowercase().starts_with(&name)
        {
            return RouteResult::TeamBroadcast(team_id.clone());
        }
    }

    RouteResult::DefaultAgent
}

/// Extract teammate mentions from an agent's response.
/// Mention syntax: `[@agent_id: message]`
pub fn extract_teammate_mentions(response: &str) -> Vec<TeammateMention> {
    let mut mentions = Vec::new();
    let mut search_from = 0;

    while let Some(start) = response[search_from..].find("[@") {
        let abs_start = search_from + start;
        let after_bracket = abs_start + 2;

        // Find the colon separator
        if let Some(colon_offset) = response[after_bracket..].find(':') {
            let colon_pos = after_bracket + colon_offset;
            let agent_id = response[after_bracket..colon_pos].trim().to_string();

            // Find the closing bracket
            if let Some(end_offset) = response[colon_pos..].find(']') {
                let end_pos = colon_pos + end_offset;
                let message = response[colon_pos + 1..end_pos].trim().to_string();

                if !agent_id.is_empty() && !message.is_empty() {
                    mentions.push(TeammateMention { agent_id, message });
                }

                search_from = end_pos + 1;
            } else {
                search_from = colon_pos + 1;
            }
        } else {
            search_from = after_bracket;
        }
    }

    mentions
}

/// Strip the @agent_name prefix from a message, returning the remaining text.
pub fn strip_routing_prefix(message: &str) -> String {
    let trimmed = message.trim();
    if !trimmed.starts_with('@') {
        return trimmed.to_string();
    }

    match trimmed[1..].split_once(|c: char| c.is_whitespace()) {
        Some((_, rest)) => rest.trim().to_string(),
        None => String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_extract_mentions() {
        let response = "I'll ask my teammate. [@coder: please review this code] and also [@reviewer: check for bugs]";
        let mentions = extract_teammate_mentions(response);
        assert_eq!(mentions.len(), 2);
        assert_eq!(mentions[0].agent_id, "coder");
        assert_eq!(mentions[0].message, "please review this code");
        assert_eq!(mentions[1].agent_id, "reviewer");
        assert_eq!(mentions[1].message, "check for bugs");
    }

    #[test]
    fn test_strip_prefix() {
        assert_eq!(strip_routing_prefix("@coder hello world"), "hello world");
        assert_eq!(strip_routing_prefix("no prefix"), "no prefix");
        assert_eq!(strip_routing_prefix("@coder"), "");
    }
}
