use serde::{Deserialize, Serialize};
use std::env;

use crate::infra::error::AppResult;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub database: DatabaseConfig,
    pub redis: RedisConfig,
    pub security: SecurityConfig,
    pub tee: crate::domain::config::TEEConfig,
    pub masking: crate::domain::config::MaskingConfig,
    pub federated: crate::domain::config::FederatedConfig,
    pub mpc: crate::domain::config::MPCConfig,
    pub classification: crate::domain::config::ClassificationConfig,
    pub dp: crate::domain::config::DPConfig,
    pub audit: crate::domain::config::AuditConfig,
    pub sharding: crate::domain::config::ShardingConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
    pub workers: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DatabaseConfig {
    pub url: String,
    pub max_connections: u32,
    pub min_connections: u32,
    pub acquire_timeout_seconds: u64,
    pub idle_timeout_seconds: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RedisConfig {
    pub url: String,
    pub max_connections: u32,
    pub connection_timeout_seconds: u64,
    pub response_timeout_seconds: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SecurityConfig {
    pub session_ttl_seconds: i64,
    pub signature_max_age_seconds: i64,
    pub api_key_header: String,
    pub enable_tls: bool,
    pub cert_path: Option<String>,
    pub key_path: Option<String>,
}

impl AppConfig {
    pub fn load() -> AppResult<Self> {
        let database_url = env::var("DATABASE_URL")
            .unwrap_or_else(|_| "postgres://localhost:5432/zero_trust".to_string());

        let redis_url = env::var("REDIS_URL")
            .unwrap_or_else(|_| "redis://localhost:6379".to_string());

        let server_port = env::var("SERVER_PORT")
            .ok()
            .and_then(|p| p.parse::<u16>().ok())
            .unwrap_or(8080);

        Ok(Self {
            server: ServerConfig {
                host: env::var("SERVER_HOST").unwrap_or_else(|_| "0.0.0.0".to_string()),
                port: server_port,
                workers: env::var("SERVER_WORKERS")
                    .ok()
                    .and_then(|w| w.parse::<usize>().ok())
                    .unwrap_or(num_cpus::get()),
            },
            database: DatabaseConfig {
                url: database_url,
                max_connections: env::var("DB_MAX_CONN")
                    .ok()
                    .and_then(|c| c.parse::<u32>().ok())
                    .unwrap_or(100),
                min_connections: env::var("DB_MIN_CONN")
                    .ok()
                    .and_then(|c| c.parse::<u32>().ok())
                    .unwrap_or(5),
                acquire_timeout_seconds: env::var("DB_ACQUIRE_TIMEOUT")
                    .ok()
                    .and_then(|t| t.parse::<u64>().ok())
                    .unwrap_or(30),
                idle_timeout_seconds: env::var("DB_IDLE_TIMEOUT")
                    .ok()
                    .and_then(|t| t.parse::<u64>().ok())
                    .unwrap_or(600),
            },
            redis: RedisConfig {
                url: redis_url,
                max_connections: env::var("REDIS_MAX_CONN")
                    .ok()
                    .and_then(|c| c.parse::<u32>().ok())
                    .unwrap_or(50),
                connection_timeout_seconds: env::var("REDIS_CONN_TIMEOUT")
                    .ok()
                    .and_then(|t| t.parse::<u64>().ok())
                    .unwrap_or(10),
                response_timeout_seconds: env::var("REDIS_RESP_TIMEOUT")
                    .ok()
                    .and_then(|t| t.parse::<u64>().ok())
                    .unwrap_or(10),
            },
            security: SecurityConfig {
                session_ttl_seconds: env::var("SESSION_TTL")
                    .ok()
                    .and_then(|t| t.parse::<i64>().ok())
                    .unwrap_or(3600),
                signature_max_age_seconds: env::var("SIGNATURE_MAX_AGE")
                    .ok()
                    .and_then(|t| t.parse::<i64>().ok())
                    .unwrap_or(300),
                api_key_header: env::var("API_KEY_HEADER")
                    .unwrap_or_else(|_| "X-API-Key".to_string()),
                enable_tls: env::var("ENABLE_TLS")
                    .ok()
                    .and_then(|t| t.parse::<bool>().ok())
                    .unwrap_or(false),
                cert_path: env::var("TLS_CERT_PATH").ok(),
                key_path: env::var("TLS_KEY_PATH").ok(),
            },
            tee: crate::domain::config::TEEConfig {
                enclave_type: env::var("TEE_TYPE").unwrap_or_else(|_| "sgx".to_string()),
                attestation_server: env::var("TEE_ATTESTATION_SERVER")
                    .unwrap_or_else(|_| "https://attestation.example.com".to_string()),
                max_enclaves: env::var("TEE_MAX_ENCLAVES")
                    .ok()
                    .and_then(|v| v.parse::<u32>().ok())
                    .unwrap_or(10),
                enclave_memory_mb: env::var("TEE_MEMORY_MB")
                    .ok()
                    .and_then(|v| v.parse::<u32>().ok())
                    .unwrap_or(512),
                attestation_timeout_ms: env::var("TEE_ATTESTATION_TIMEOUT")
                    .ok()
                    .and_then(|v| v.parse::<u64>().ok())
                    .unwrap_or(5000),
            },
            masking: crate::domain::config::MaskingConfig {
                default_masking_character: env::var("MASK_CHAR")
                    .ok()
                    .and_then(|c| c.chars().next())
                    .unwrap_or('*'),
                keep_prefix: env::var("MASK_KEEP_PREFIX")
                    .ok()
                    .and_then(|v| v.parse::<usize>().ok())
                    .unwrap_or(2),
                keep_suffix: env::var("MASK_KEEP_SUFFIX")
                    .ok()
                    .and_then(|v| v.parse::<usize>().ok())
                    .unwrap_or(2),
                enabled_rules: env::var("MASK_RULES")
                    .ok()
                    .map(|s| s.split(',').map(|s| s.to_string()).collect())
                    .unwrap_or_else(|| vec!["email".to_string(), "phone".to_string(), "id_card".to_string()]),
            },
            federated: crate::domain::config::FederatedConfig {
                max_participants: env::var("FL_MAX_PARTICIPANTS")
                    .ok()
                    .and_then(|v| v.parse::<u32>().ok())
                    .unwrap_or(100),
                min_participants: env::var("FL_MIN_PARTICIPANTS")
                    .ok()
                    .and_then(|v| v.parse::<u32>().ok())
                    .unwrap_or(3),
                aggregation_timeout_ms: env::var("FL_AGG_TIMEOUT")
                    .ok()
                    .and_then(|v| v.parse::<u64>().ok())
                    .unwrap_or(30000),
                encryption_enabled: env::var("FL_ENCRYPTION")
                    .ok()
                    .and_then(|v| v.parse::<bool>().ok())
                    .unwrap_or(true),
            },
            mpc: crate::domain::config::MPCConfig {
                protocol: env::var("MPC_PROTOCOL").unwrap_or_else(|_| "spdz2k".to_string()),
                threshold: env::var("MPC_THRESHOLD")
                    .ok()
                    .and_then(|v| v.parse::<u32>().ok())
                    .unwrap_or(2),
                compute_timeout_ms: env::var("MPC_COMPUTE_TIMEOUT")
                    .ok()
                    .and_then(|v| v.parse::<u64>().ok())
                    .unwrap_or(60000),
                security_level: env::var("MPC_SECURITY_LEVEL")
                    .ok()
                    .and_then(|v| v.parse::<u32>().ok())
                    .unwrap_or(128),
            },
            classification: crate::domain::config::ClassificationConfig {
                scan_depth: env::var("CLASSIFY_SCAN_DEPTH")
                    .ok()
                    .and_then(|v| v.parse::<u32>().ok())
                    .unwrap_or(3),
                max_file_size_mb: env::var("CLASSIFY_MAX_FILE_SIZE")
                    .ok()
                    .and_then(|v| v.parse::<u64>().ok())
                    .unwrap_or(100),
                enabled_patterns: env::var("CLASSIFY_PATTERNS")
                    .ok()
                    .map(|s| s.split(',').map(|s| s.to_string()).collect())
                    .unwrap_or_else(|| vec![
                        "pii".to_string(),
                        "financial".to_string(),
                        "health".to_string(),
                    ]),
                auto_apply_policy: env::var("CLASSIFY_AUTO_APPLY")
                    .ok()
                    .and_then(|v| v.parse::<bool>().ok())
                    .unwrap_or(true),
            },
            dp: crate::domain::config::DPConfig {
                default_epsilon: env::var("DP_EPSILON")
                    .ok()
                    .and_then(|v| v.parse::<f64>().ok())
                    .unwrap_or(1.0),
                default_delta: env::var("DP_DELTA")
                    .ok()
                    .and_then(|v| v.parse::<f64>().ok())
                    .unwrap_or(1e-5),
                max_budget_per_hour: env::var("DP_MAX_BUDGET")
                    .ok()
                    .and_then(|v| v.parse::<f64>().ok())
                    .unwrap_or(10.0),
                noise_distribution: env::var("DP_NOISE")
                    .unwrap_or_else(|_| "laplace".to_string()),
            },
            audit: crate::domain::config::AuditConfig {
                hash_algorithm: env::var("AUDIT_HASH")
                    .unwrap_or_else(|_| "sha256".to_string()),
                chain_interval_seconds: env::var("AUDIT_CHAIN_INTERVAL")
                    .ok()
                    .and_then(|v| v.parse::<u64>().ok())
                    .unwrap_or(60),
                retention_days: env::var("AUDIT_RETENTION")
                    .ok()
                    .and_then(|v| v.parse::<u32>().ok())
                    .unwrap_or(365),
                integrity_check_interval_seconds: env::var("AUDIT_INTEGRITY_INTERVAL")
                    .ok()
                    .and_then(|v| v.parse::<u64>().ok())
                    .unwrap_or(3600),
            },
            sharding: crate::domain::config::ShardingConfig {
                algorithm: env::var("SHARD_ALG").unwrap_or_else(|_| "shamir".to_string()),
                default_threshold: env::var("SHARD_THRESHOLD")
                    .ok()
                    .and_then(|v| v.parse::<u32>().ok())
                    .unwrap_or(3),
                default_total_shares: env::var("SHARD_TOTAL")
                    .ok()
                    .and_then(|v| v.parse::<u32>().ok())
                    .unwrap_or(5),
                key_size_bits: env::var("SHARD_KEY_SIZE")
                    .ok()
                    .and_then(|v| v.parse::<u32>().ok())
                    .unwrap_or(256),
            },
        })
    }
}
