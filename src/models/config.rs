use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigVersion {
    pub config_id: String,
    pub namespace: String,
    pub version: u64,
    pub parameters: HashMap<String, serde_json::Value>,
    pub enabled: bool,
    pub applied_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

impl ConfigVersion {
    pub fn new(
        config_id: impl Into<String>,
        namespace: impl Into<String>,
        parameters: HashMap<String, serde_json::Value>,
    ) -> Self {
        Self {
            config_id: config_id.into(),
            namespace: namespace.into(),
            version: 1,
            parameters,
            enabled: true,
            applied_at: Utc::now(),
            description: None,
        }
    }

    pub fn increment_version(&self) -> Self {
        let mut new = self.clone();
        new.version += 1;
        new.applied_at = Utc::now();
        new
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateConfigRequest {
    pub config_id: String,
    pub namespace: String,
    pub parameters: HashMap<String, serde_json::Value>,
    #[serde(default)]
    pub enabled: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateConfigRequest {
    pub parameters: HashMap<String, serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enabled: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RollbackRequest {
    pub target_version: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigHistoryEntry {
    pub config_id: String,
    pub version: u64,
    pub applied_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}
