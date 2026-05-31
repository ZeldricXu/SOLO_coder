use serde::{Serialize, Deserialize};
use chrono::{DateTime, Utc};
use uuid::Uuid;
use serde_json::Value;
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub config_id: String,
    pub namespace: String,
    pub version: u32,
    pub parameters: HashMap<String, Value>,
    pub enabled: bool,
    pub description: Option<String>,
    pub created_by: String,
    pub applied_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl Config {
    pub fn new(namespace: impl Into<String>, created_by: impl Into<String>) -> Self {
        let now = Utc::now();
        Self {
            config_id: format!("cfg_{}", Uuid::new_v4().simple()),
            namespace: namespace.into(),
            version: 1,
            parameters: HashMap::new(),
            enabled: true,
            description: None,
            created_by: created_by.into(),
            applied_at: None,
            created_at: now,
            updated_at: now,
        }
    }

    pub fn with_parameter(mut self, key: impl Into<String>, value: Value) -> Self {
        self.parameters.insert(key.into(), value);
        self
    }

    pub fn with_description(mut self, description: impl Into<String>) -> Self {
        self.description = Some(description.into());
        self
    }

    pub fn bump_version(&mut self) {
        self.version += 1;
        self.updated_at = Utc::now();
    }

    pub fn mark_applied(&mut self) {
        self.applied_at = Some(Utc::now());
        self.updated_at = Utc::now();
    }

    pub fn get_param<T: serde::de::DeserializeOwned>(&self, key: &str) -> Option<T> {
        self.parameters.get(key)
            .and_then(|v| serde_json::from_value(v.clone()).ok())
    }

    pub fn get_param_or<T: serde::de::DeserializeOwned>(&self, key: &str, default: T) -> T {
        self.get_param(key).unwrap_or(default)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigCreateRequest {
    pub namespace: String,
    pub parameters: HashMap<String, Value>,
    pub enabled: Option<bool>,
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigUpdateRequest {
    pub parameters: Option<HashMap<String, Value>>,
    pub enabled: Option<bool>,
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigQuery {
    pub namespace: Option<String>,
    pub enabled: Option<bool>,
    pub page: u32,
    pub page_size: u32,
}

impl Default for ConfigQuery {
    fn default() -> Self {
        Self {
            namespace: None,
            enabled: None,
            page: 1,
            page_size: 20,
        }
    }
}
