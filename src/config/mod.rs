use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub mod dynamic_config;
pub use dynamic_config::{
    DeploymentScene, SceneTeeConfig, ConfigVersion, ConfigurationListener,
    DynamicConfigManager, MpcStrategyConfig, MaskingAsyncConfig,
};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub tee: TeeConfig,
    pub mpc: MpcConfig,
    pub masking: MaskingConfig,
    pub classification: ClassificationConfig,
    pub dp: DpConfig,
    pub auditlog: AuditLogConfig,
    pub shamir: ShamirConfig,
    pub federated: FederatedConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
    pub max_concurrent: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TeeConfig {
    pub enabled: bool,
    pub max_enclaves: usize,
    pub attestation_timeout_ms: u64,
    pub supported_techs: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpcConfig {
    pub enabled: bool,
    pub min_participants: usize,
    pub max_participants: usize,
    pub protocol_timeout_secs: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingConfig {
    pub enabled: bool,
    pub default_mask_char: char,
    pub mask_email: bool,
    pub mask_phone: bool,
    pub mask_id_card: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClassificationConfig {
    pub enabled: bool,
    pub scan_depth: u32,
    pub patterns: HashMap<String, String>,
    pub min_confidence: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DpConfig {
    pub enabled: bool,
    pub default_epsilon: f64,
    pub default_delta: f64,
    pub max_budget_per_query: f64,
    pub global_budget: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditLogConfig {
    pub enabled: bool,
    pub chain_verify_interval_secs: u64,
    pub max_logs_per_block: usize,
    pub hash_algorithm: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShamirConfig {
    pub enabled: bool,
    pub default_threshold: usize,
    pub default_shares: usize,
    pub prime_bits: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FederatedConfig {
    pub enabled: bool,
    pub max_clients: usize,
    pub aggregation_rounds: u32,
    pub min_clients_per_round: usize,
    pub timeout_secs: u64,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            server: ServerConfig {
                host: "127.0.0.1".to_string(),
                port: 8080,
                max_concurrent: 1000,
            },
            tee: TeeConfig {
                enabled: true,
                max_enclaves: 64,
                attestation_timeout_ms: 30000,
                supported_techs: vec!["SGX".to_string(), "SEV".to_string(), "TrustZone".to_string()],
            },
            mpc: MpcConfig {
                enabled: true,
                min_participants: 2,
                max_participants: 10,
                protocol_timeout_secs: 300,
            },
            masking: MaskingConfig {
                enabled: true,
                default_mask_char: '*',
                mask_email: true,
                mask_phone: true,
                mask_id_card: true,
            },
            classification: ClassificationConfig {
                enabled: true,
                scan_depth: 3,
                patterns: {
                    let mut m = HashMap::new();
                    m.insert("email".to_string(), r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}".to_string());
                    m.insert("phone".to_string(), r"1[3-9]\d{9}".to_string());
                    m.insert("id_card".to_string(), r"[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]".to_string());
                    m
                },
                min_confidence: 0.7,
            },
            dp: DpConfig {
                enabled: true,
                default_epsilon: 1.0,
                default_delta: 1e-5,
                max_budget_per_query: 0.5,
                global_budget: 10.0,
            },
            auditlog: AuditLogConfig {
                enabled: true,
                chain_verify_interval_secs: 3600,
                max_logs_per_block: 1000,
                hash_algorithm: "SHA256".to_string(),
            },
            shamir: ShamirConfig {
                enabled: true,
                default_threshold: 3,
                default_shares: 5,
                prime_bits: 256,
            },
            federated: FederatedConfig {
                enabled: true,
                max_clients: 100,
                aggregation_rounds: 10,
                min_clients_per_round: 2,
                timeout_secs: 300,
            },
        }
    }
}
