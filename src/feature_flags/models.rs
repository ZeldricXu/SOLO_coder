use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureFlag {
    pub flag_id: String,
    pub name: String,
    pub description: String,
    pub enabled: bool,
    pub target_percentage: f64,
    pub rules: Vec<Rule>,
    pub user_segments: Vec<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    #[serde(default)]
    pub metadata: HashMap<String, String>,
}

impl FeatureFlag {
    pub fn new(flag_id: impl Into<String>, name: impl Into<String>) -> Self {
        let now = Utc::now();
        Self {
            flag_id: flag_id.into(),
            name: name.into(),
            description: String::new(),
            enabled: false,
            target_percentage: 0.0,
            rules: vec![],
            user_segments: vec![],
            created_at: now,
            updated_at: now,
            metadata: HashMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Rule {
    pub rule_id: String,
    pub condition: Condition,
    pub value: serde_json::Value,
}

impl Rule {
    pub fn new(condition: Condition, value: serde_json::Value) -> Self {
        Self {
            rule_id: format!("rule_{}", uuid::Uuid::new_v4().simple()),
            condition,
            value,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Condition {
    #[serde(rename = "equals")]
    Equals { field: String, value: String },
    #[serde(rename = "contains")]
    Contains { field: String, value: String },
    #[serde(rename = "greater_than")]
    GreaterThan { field: String, value: f64 },
    #[serde(rename = "less_than")]
    LessThan { field: String, value: f64 },
    #[serde(rename = "in_list")]
    InList { field: String, values: Vec<String> },
    #[serde(rename = "regex_match")]
    RegexMatch { field: String, pattern: String },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserContext {
    pub user_id: String,
    #[serde(default)]
    pub attributes: HashMap<String, serde_json::Value>,
    #[serde(default)]
    pub segments: Vec<String>,
}

impl UserContext {
    pub fn new(user_id: impl Into<String>) -> Self {
        Self {
            user_id: user_id.into(),
            attributes: HashMap::new(),
            segments: vec![],
        }
    }

    pub fn with_attribute(mut self, key: impl Into<String>, value: serde_json::Value) -> Self {
        self.attributes.insert(key.into(), value);
        self
    }

    pub fn with_segment(mut self, segment: impl Into<String>) -> Self {
        self.segments.push(segment.into());
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserSegment {
    pub segment_id: String,
    pub name: String,
    pub description: String,
    #[serde(default)]
    pub user_ids: Vec<String>,
    #[serde(default)]
    pub attributes: HashMap<String, serde_json::Value>,
    pub created_at: DateTime<Utc>,
}

impl UserSegment {
    pub fn new(segment_id: impl Into<String>, name: impl Into<String>) -> Self {
        Self {
            segment_id: segment_id.into(),
            name: name.into(),
            description: String::new(),
            user_ids: vec![],
            attributes: HashMap::new(),
            created_at: Utc::now(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateFlagRequest {
    pub flag_id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_percentage")]
    pub target_percentage: f64,
    #[serde(default)]
    pub rules: Vec<Rule>,
    #[serde(default)]
    pub user_segments: Vec<String>,
}

fn default_percentage() -> f64 { 100.0 }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateFlagRequest {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enabled: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target_percentage: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rules: Option<Vec<Rule>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub user_segments: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EvaluateRequest {
    pub flag_id: String,
    pub user: UserContext,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EvaluateResponse {
    pub flag_id: String,
    pub enabled: bool,
    pub value: Option<serde_json::Value>,
    pub matched_rules: Vec<String>,
}
