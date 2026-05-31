use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use crate::utils::error::{Result, PlatformError};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ConfigId(pub String);

impl ConfigId {
    pub fn new(id: impl Into<String>) -> Self {
        Self(id.into())
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for ConfigId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ConfigStatus {
    Active,
    Archived,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigVersion {
    pub config_id: ConfigId,
    pub namespace: String,
    pub version: u64,
    pub parameters: HashMap<String, serde_json::Value>,
    pub enabled: bool,
    pub applied_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub status: ConfigStatus,
}

impl ConfigVersion {
    pub fn new(
        config_id: ConfigId,
        namespace: impl Into<String>,
        parameters: HashMap<String, serde_json::Value>,
    ) -> Self {
        Self {
            config_id,
            namespace: namespace.into(),
            version: 1,
            parameters,
            enabled: true,
            applied_at: Utc::now(),
            description: None,
            status: ConfigStatus::Active,
        }
    }

    pub fn next_version(&self) -> Self {
        let mut new = self.clone();
        new.version = self.version + 1;
        new.applied_at = Utc::now();
        new
    }

    pub fn validate(&self) -> Result<()> {
        if self.config_id.as_str().is_empty() {
            return Err(PlatformError::Validation("config_id cannot be empty".to_string()));
        }
        if self.namespace.is_empty() {
            return Err(PlatformError::Validation("namespace cannot be empty".to_string()));
        }
        if self.version == 0 {
            return Err(PlatformError::Validation("version must be at least 1".to_string()));
        }
        Ok(())
    }

    pub fn get_param<T: for<'de> Deserialize<'de>>(&self, key: &str) -> Option<T> {
        self.parameters
            .get(key)
            .and_then(|v| serde_json::from_value(v.clone()).ok())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateConfigCommand {
    pub config_id: String,
    pub namespace: String,
    pub parameters: HashMap<String, serde_json::Value>,
    #[serde(default)]
    pub enabled: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateConfigCommand {
    pub parameters: HashMap<String, serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enabled: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RollbackCommand {
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
