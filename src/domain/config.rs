use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub config_id: String,
    pub namespace: String,
    pub version: u32,
    pub parameters: HashMap<String, serde_json::Value>,
    pub enabled: bool,
    pub applied_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl Config {
    pub fn new(namespace: impl Into<String>, parameters: HashMap<String, serde_json::Value>) -> Self {
        let now = Utc::now();
        Self {
            config_id: format!("cfg_{}", Uuid::new_v4().simple()),
            namespace: namespace.into(),
            version: 1,
            parameters,
            enabled: true,
            applied_at: None,
            created_at: now,
            updated_at: now,
        }
    }

    pub fn apply(&mut self) {
        self.applied_at = Some(Utc::now());
    }

    pub fn increment_version(&mut self) {
        self.version += 1;
        self.updated_at = Utc::now();
    }

    pub fn get_parameter<T: for<'de> Deserialize<'de>>(&self, key: &str) -> Option<T> {
        self.parameters.get(key).and_then(|v| serde_json::from_value(v.clone()).ok())
    }

    pub fn get_parameter_with_default<T: for<'de> Deserialize<'de>>(&self, key: &str, default: T) -> T {
        self.get_parameter(key).unwrap_or(default)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigDiff {
    pub config_id: String,
    pub version_a: u32,
    pub version_b: u32,
    pub added: Vec<String>,
    pub removed: Vec<String>,
    pub modified: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TEEConfig {
    pub enclave_type: String,
    pub attestation_server: String,
    pub max_enclaves: u32,
    pub enclave_memory_mb: u32,
    pub attestation_timeout_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingConfig {
    pub default_masking_character: char,
    pub keep_prefix: usize,
    pub keep_suffix: usize,
    pub enabled_rules: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FederatedConfig {
    pub max_participants: u32,
    pub min_participants: u32,
    pub aggregation_timeout_ms: u64,
    pub encryption_enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MPCConfig {
    pub protocol: String,
    pub threshold: u32,
    pub compute_timeout_ms: u64,
    pub security_level: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClassificationConfig {
    pub scan_depth: u32,
    pub max_file_size_mb: u64,
    pub enabled_patterns: Vec<String>,
    pub auto_apply_policy: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DPConfig {
    pub default_epsilon: f64,
    pub default_delta: f64,
    pub max_budget_per_hour: f64,
    pub noise_distribution: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditConfig {
    pub hash_algorithm: String,
    pub chain_interval_seconds: u64,
    pub retention_days: u32,
    pub integrity_check_interval_seconds: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShardingConfig {
    pub algorithm: String,
    pub default_threshold: u32,
    pub default_total_shares: u32,
    pub key_size_bits: u32,
}
