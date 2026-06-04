use dashmap::DashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tracing::{debug, info};

use common::pattern::{LogPattern, PatternChangeEvent, PatternChangeType, DrainConfig};

#[derive(Debug, Clone)]
pub struct PatternTreeNode {
    pub children: Arc<DashMap<String, PatternTreeNode>>,
    pub pattern_id: Option<u64>,
}

impl PatternTreeNode {
    fn new() -> Self {
        Self {
            children: Arc::new(DashMap::new()),
            pattern_id: None,
        }
    }
}

pub struct Drain {
    config: DrainConfig,
    root: PatternTreeNode,
    patterns: Arc<DashMap<u64, LogPattern>>,
    next_pattern_id: Arc<AtomicU64>,
}

impl Drain {
    pub fn new(config: DrainConfig) -> Self {
        Self {
            config,
            root: PatternTreeNode::new(),
            patterns: Arc::new(DashMap::new()),
            next_pattern_id: Arc::new(AtomicU64::new(1)),
        }
    }

    pub fn process_log(&self, log_content: &str) -> Option<PatternChangeEvent> {
        let tokens = self.tokenize(log_content);

        if tokens.is_empty() {
            return None;
        }

        let (existing_pattern, node_path) = self.search_pattern(&tokens);

        if let Some(pattern_id) = existing_pattern {
            if let Some(mut pattern) = self.patterns.get_mut(&pattern_id) {
                pattern.count += 1;
                pattern.last_seen = chrono::Utc::now();
            }
            None
        } else {
            let pattern_id = self.next_pattern_id.fetch_add(1, Ordering::Relaxed);
            let template = self.create_template(&tokens);
            let pattern = LogPattern {
                id: pattern_id,
                template: template.clone(),
                tokens,
                count: 1,
                first_seen: chrono::Utc::now(),
                last_seen: chrono::Utc::now(),
            };

            self.patterns.insert(pattern_id, pattern);
            self.insert_pattern(&node_path, pattern_id);

            debug!("New pattern discovered: {}", template);

            Some(PatternChangeEvent {
                change_type: PatternChangeType::New,
                pattern_id,
                pattern_template: template,
                timestamp: chrono::Utc::now(),
                description: "New log pattern detected".to_string(),
            })
        }
    }

    fn tokenize(&self, content: &str) -> Vec<String> {
        let re = regex::Regex::new(r"\s+").unwrap();
        re.split(content)
            .filter(|s| !s.is_empty())
            .take(self.config.max_tokens)
            .map(|s| s.to_string())
            .collect()
    }

    fn search_pattern(&self, tokens: &[String]) -> (Option<u64>, Vec<String>) {
        let mut current = &self.root;
        let mut path = Vec::new();

        for (i, token) in tokens.iter().enumerate() {
            if i >= self.config.depth {
                break;
            }

            let is_number = regex::Regex::new(r"^\d+$").unwrap().is_match(token);
            let is_hex = regex::Regex::new(r"^0x[0-9a-fA-F]+$").unwrap().is_match(token);
            let is_uuid = regex::Regex::new(
                r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
            ).unwrap().is_match(token);

            let key = if is_number || is_hex || is_uuid {
                "<*>".to_string()
            } else if token.len() > self.config.similarity_threshold as usize {
                "<*>".to_string()
            } else {
                token.clone()
            };

            path.push(key.clone());

            if let Some(child) = current.children.get(&key) {
                current = child.value();
            } else if let Some(wildcard) = current.children.get("<*>") {
                current = wildcard.value();
            } else {
                return (None, path);
            }
        }

        (current.pattern_id, path)
    }

    fn insert_pattern(&self, path: &[String], pattern_id: u64) {
        let mut current = &self.root;

        for key in path {
            current = current.children
                .entry(key.clone())
                .or_insert_with(PatternTreeNode::new);
        }

        if let Some(mut entry) = current.children.get_mut(path.last().unwrap()) {
            entry.pattern_id = Some(pattern_id);
        }
    }

    fn create_template(&self, tokens: &[String]) -> String {
        tokens
            .iter()
            .map(|t| {
                let is_number = regex::Regex::new(r"^\d+$").unwrap().is_match(t);
                let is_hex = regex::Regex::new(r"^0x[0-9a-fA-F]+$").unwrap().is_match(t);
                let is_uuid = regex::Regex::new(
                    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
                ).unwrap().is_match(t);

                if is_number || is_hex || is_uuid {
                    "{}".to_string()
                } else if t.len() > self.config.similarity_threshold as usize {
                    "{}".to_string()
                } else {
                    t.clone()
                }
            })
            .collect::<Vec<_>>()
            .join(" ")
    }

    pub fn get_all_patterns(&self) -> Vec<LogPattern> {
        self.patterns
            .iter()
            .map(|entry| entry.value().clone())
            .collect()
    }

    pub fn get_pattern(&self, pattern_id: u64) -> Option<LogPattern> {
        self.patterns.get(&pattern_id).map(|p| p.clone())
    }

    pub fn get_pattern_count(&self) -> usize {
        self.patterns.len()
    }

    pub fn cleanup_old_patterns(&self, max_age: chrono::Duration) {
        let now = chrono::Utc::now();
        let to_remove: Vec<u64> = self.patterns
            .iter()
            .filter(|entry| now - entry.last_seen > max_age)
            .map(|entry| entry.id)
            .collect();

        for id in to_remove {
            self.patterns.remove(&id);
        }
    }
}

impl Default for Drain {
    fn default() -> Self {
        Self::new(DrainConfig::default())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_basic_pattern_clustering() {
        let drain = Drain::default();

        let log1 = "User 123 logged in from 192.168.1.1";
        let log2 = "User 456 logged in from 10.0.0.1";

        let result1 = drain.process_log(log1);
        let result2 = drain.process_log(log2);

        assert!(result1.is_some());
        assert!(result2.is_none());
        assert_eq!(drain.get_pattern_count(), 1);
    }

    #[test]
    fn test_different_patterns() {
        let drain = Drain::default();

        let log1 = "User logged in";
        let log2 = "Database connection failed";

        let result1 = drain.process_log(log1);
        let result2 = drain.process_log(log2);

        assert!(result1.is_some());
        assert!(result2.is_some());
        assert_eq!(drain.get_pattern_count(), 2);
    }
}
