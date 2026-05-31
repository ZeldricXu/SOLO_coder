use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub config_id: String,
    pub namespace: String,
    pub version: u32,
    pub parameters: HashMap<String, serde_json::Value>,
    pub enabled: bool,
    pub applied_at: DateTime<Utc>,
}

impl Config {
    pub fn new(namespace: &str, version: u32) -> Self {
        Self {
            config_id: format!("cfg_{}", namespace),
            namespace: namespace.to_string(),
            version,
            parameters: HashMap::new(),
            enabled: true,
            applied_at: Utc::now(),
        }
    }

    pub fn with_parameter<K: Into<String>, V: Into<serde_json::Value>>(mut self, key: K, value: V) -> Self {
        self.parameters.insert(key.into(), value.into());
        self
    }

    pub fn get_param<T: for<'de> Deserialize<'de>>(&self, key: &str) -> Option<T> {
        self.parameters.get(key)
            .and_then(|v| serde_json::from_value(v.clone()).ok())
    }

    pub fn get_param_or<T: for<'de> Deserialize<'de>>(&self, key: &str, default: T) -> T {
        self.get_param(key).unwrap_or(default)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineConfig {
    pub stages: Vec<PipelineStage>,
    pub timeout_seconds: u64,
    pub max_retries: u32,
    pub parallelism: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineStage {
    pub name: String,
    pub r#type: StageType,
    pub config: serde_json::Value,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum StageType {
    Parse,
    Split,
    Vectorize,
    Store,
    Transform,
    Validate,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_config_creation() {
        let config = Config::new("production", 1)
            .with_parameter("timeout", 30)
            .with_parameter("retries", 3);

        assert_eq!(config.config_id, "cfg_production");
        assert_eq!(config.version, 1);
        assert!(config.enabled);
        assert_eq!(config.get_param::<u32>("timeout"), Some(30));
        assert_eq!(config.get_param::<u32>("retries"), Some(3));
        assert_eq!(config.get_param_or::<u32>("missing", 0), 0);
    }
}
