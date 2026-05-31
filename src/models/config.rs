use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub config_id: String,
    pub namespace: String,
    pub version: u32,
    pub parameters: serde_json::Value,
    pub enabled: bool,
    pub applied_at: DateTime<Utc>,
}

impl Config {
    pub fn new(namespace: impl Into<String>, parameters: serde_json::Value) -> Self {
        Self {
            config_id: format!("cfg_{}", Uuid::new_v4().simple()),
            namespace: namespace.into(),
            version: 1,
            parameters,
            enabled: true,
            applied_at: Utc::now(),
        }
    }

    pub fn new_version(&self, new_params: serde_json::Value) -> Self {
        Self {
            config_id: self.config_id.clone(),
            namespace: self.namespace.clone(),
            version: self.version + 1,
            parameters: new_params,
            enabled: true,
            applied_at: Utc::now(),
        }
    }

    pub fn get_param<T: serde::de::DeserializeOwned>(&self, key: &str) -> Option<T> {
        self.parameters
            .get(key)
            .and_then(|v| serde_json::from_value(v.clone()).ok())
    }

    pub fn get_timeout(&self, default: u64) -> u64 {
        self.get_param("timeout").unwrap_or(default)
    }

    pub fn get_retries(&self, default: u32) -> u32 {
        self.get_param("retries").unwrap_or(default)
    }

    pub fn get(&self, key: &str) -> Option<&serde_json::Value> {
        self.parameters.get(key)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigQuery {
    pub namespace: Option<String>,
    pub enabled_only: bool,
    pub latest_only: bool,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn test_config_creation() {
        let params = json!({"timeout": 30, "retries": 3});
        let config = Config::new("staging", params.clone());
        
        assert!(config.config_id.starts_with("cfg_"));
        assert_eq!(config.namespace, "staging");
        assert_eq!(config.version, 1);
        assert_eq!(config.parameters, params);
        assert!(config.enabled);
    }

    #[test]
    fn test_config_new_version() {
        let config = Config::new("prod", json!({"timeout": 30}));
        let new_config = config.new_version(json!({"timeout": 60, "retries": 5}));
        
        assert_eq!(new_config.config_id, config.config_id);
        assert_eq!(new_config.version, 2);
        assert_eq!(new_config.get_timeout(0), 60);
        assert_eq!(new_config.get_retries(0), 5);
    }

    #[test]
    fn test_get_param() {
        let config = Config::new("test", json!({"timeout": 30, "enabled": true}));
        
        assert_eq!(config.get_param::<u64>("timeout"), Some(30));
        assert_eq!(config.get_param::<bool>("enabled"), Some(true));
        assert_eq!(config.get_param::<String>("missing"), None);
    }
}
