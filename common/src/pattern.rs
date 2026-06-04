use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogPattern {
    pub id: Uuid,
    pub template: String,
    pub template_tokens: Vec<Token>,
    pub count: u64,
    pub first_seen: DateTime<Utc>,
    pub last_seen: DateTime<Utc>,
    pub example_message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum Token {
    String(String),
    Wildcard,
}

impl LogPattern {
    pub fn new(template: String, example: String) -> Self {
        let now = Utc::now();
        Self {
            id: Uuid::new_v4(),
            template,
            template_tokens: Vec::new(),
            count: 1,
            first_seen: now,
            last_seen: now,
            example_message: example,
        }
    }

    pub fn update(&mut self, message: &str) {
        self.count += 1;
        self.last_seen = Utc::now();
        if self.example_message.is_empty() {
            self.example_message = message.to_string();
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PatternTreeNode {
    pub token: Token,
    pub children: Vec<PatternTreeNode>,
    pub pattern_id: Option<Uuid>,
}

impl PatternTreeNode {
    pub fn new(token: Token) -> Self {
        Self {
            token,
            children: Vec::new(),
            pattern_id: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PatternCluster {
    pub center_pattern: LogPattern,
    pub similar_patterns: Vec<LogPattern>,
    pub size: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PatternChangeEvent {
    pub event_type: PatternChangeType,
    pub pattern: LogPattern,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum PatternChangeType {
    NewPattern,
    PatternUpdated,
    RarePattern,
    BurstPattern,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DrainConfig {
    pub similarity_threshold: f64,
    pub max_depth: usize,
    pub max_children: usize,
    pub max_clusters: usize,
    pub similarity_percent: f64,
}

impl Default for DrainConfig {
    fn default() -> Self {
        Self {
            similarity_threshold: 0.4,
            max_depth: 4,
            max_children: 100,
            max_clusters: 10000,
            similarity_percent: 0.5,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PatternStats {
    pub total_patterns: usize,
    pub total_messages: u64,
    pub new_patterns_last_hour: usize,
    pub top_patterns: Vec<(String, u64)>,
}
