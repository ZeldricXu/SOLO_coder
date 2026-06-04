use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::env;
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum Environment {
    Development,
    Staging,
    Production,
}

impl Environment {
    pub fn current() -> Self {
        match env::var("ENVIRONMENT")
            .unwrap_or_else(|_| "development".to_string())
            .to_lowercase()
            .as_str()
        {
            "production" | "prod" => Environment::Production,
            "staging" | "stage" => Environment::Staging,
            _ => Environment::Development,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            Environment::Development => "development",
            Environment::Staging => "staging",
            Environment::Production => "production",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    pub environment: Environment,
    pub center: CenterConfig,
    pub database: DatabaseConfig,
    pub redis: RedisConfig,
    pub tls: TlsConfig,
    pub monitoring: MonitoringConfig,
    pub metrics: MetricsConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CenterConfig {
    pub listen_addr: SocketAddr,
    pub metrics_addr: SocketAddr,
    pub heartbeat_interval_seconds: u64,
    pub max_heartbeat_failures: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DatabaseConfig {
    pub url: String,
    pub max_connections: u32,
    pub min_connections: u32,
    pub acquire_timeout_seconds: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RedisConfig {
    pub url: String,
    pub max_connections: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TlsConfig {
    pub acme_directory_url: String,
    pub contact_email: String,
    pub certificate_renew_days_before: u32,
    pub encryption_key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MonitoringConfig {
    pub metrics_retention_hours: u64,
    pub traffic_spike_threshold: f64,
    pub traffic_drop_threshold: f64,
    pub error_rate_threshold: f64,
    pub imbalance_threshold: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricsConfig {
    pub enabled: bool,
    pub scrape_interval_seconds: u64,
}

fn env_or(key: &str, default: &str) -> String {
    env::var(key).unwrap_or_else(|_| default.to_string())
}

fn env_or_parse<T: std::str::FromStr>(key: &str, default: T) -> T {
    env::var(key)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

impl Default for AppConfig {
    fn default() -> Self {
        Self::development()
    }
}

impl AppConfig {
    pub fn development() -> Self {
        AppConfig {
            environment: Environment::Development,
            center: CenterConfig {
                listen_addr: "0.0.0.0:8080".parse().unwrap(),
                metrics_addr: "0.0.0.0:9090".parse().unwrap(),
                heartbeat_interval_seconds: 10,
                max_heartbeat_failures: 3,
            },
            database: DatabaseConfig {
                url: "postgres://postgres:postgres@localhost:5432/cdn".to_string(),
                max_connections: 10,
                min_connections: 2,
                acquire_timeout_seconds: 30,
            },
            redis: RedisConfig {
                url: "redis://localhost:6379/0".to_string(),
                max_connections: 5,
            },
            tls: TlsConfig {
                acme_directory_url: "https://acme-staging-v02.api.letsencrypt.org/directory".to_string(),
                contact_email: "dev@example.com".to_string(),
                certificate_renew_days_before: 30,
                encryption_key: "dev-encryption-key-32-bytes-long!!".to_string(),
            },
            monitoring: MonitoringConfig {
                metrics_retention_hours: 168,
                traffic_spike_threshold: 3.0,
                traffic_drop_threshold: 0.3,
                error_rate_threshold: 0.1,
                imbalance_threshold: 0.5,
            },
            metrics: MetricsConfig {
                enabled: true,
                scrape_interval_seconds: 15,
            },
        }
    }

    pub fn staging() -> Self {
        AppConfig {
            environment: Environment::Staging,
            center: CenterConfig {
                listen_addr: "0.0.0.0:8080".parse().unwrap(),
                metrics_addr: "0.0.0.0:9090".parse().unwrap(),
                heartbeat_interval_seconds: 10,
                max_heartbeat_failures: 3,
            },
            database: DatabaseConfig {
                url: "postgres://cdn:cdn@postgres-staging:5432/cdn".to_string(),
                max_connections: 30,
                min_connections: 5,
                acquire_timeout_seconds: 30,
            },
            redis: RedisConfig {
                url: "redis://redis-staging:6379/0".to_string(),
                max_connections: 15,
            },
            tls: TlsConfig {
                acme_directory_url: "https://acme-staging-v02.api.letsencrypt.org/directory".to_string(),
                contact_email: "ops@example.com".to_string(),
                certificate_renew_days_before: 30,
                encryption_key: "staging-encryption-key-change-me!!".to_string(),
            },
            monitoring: MonitoringConfig {
                metrics_retention_hours: 360,
                traffic_spike_threshold: 2.5,
                traffic_drop_threshold: 0.4,
                error_rate_threshold: 0.05,
                imbalance_threshold: 0.3,
            },
            metrics: MetricsConfig {
                enabled: true,
                scrape_interval_seconds: 10,
            },
        }
    }

    pub fn production() -> Self {
        AppConfig {
            environment: Environment::Production,
            center: CenterConfig {
                listen_addr: "0.0.0.0:8080".parse().unwrap(),
                metrics_addr: "0.0.0.0:9090".parse().unwrap(),
                heartbeat_interval_seconds: 10,
                max_heartbeat_failures: 3,
            },
            database: DatabaseConfig {
                url: "postgres://cdn:cdn@postgres-production:5432/cdn".to_string(),
                max_connections: 100,
                min_connections: 10,
                acquire_timeout_seconds: 15,
            },
            redis: RedisConfig {
                url: "redis://redis-production:6379/0".to_string(),
                max_connections: 50,
            },
            tls: TlsConfig {
                acme_directory_url: "https://acme-v02.api.letsencrypt.org/directory".to_string(),
                contact_email: "ops@example.com".to_string(),
                certificate_renew_days_before: 30,
                encryption_key: "MUST_BE_SET_VIA_K8S_SECRET".to_string(),
            },
            monitoring: MonitoringConfig {
                metrics_retention_hours: 720,
                traffic_spike_threshold: 2.0,
                traffic_drop_threshold: 0.5,
                error_rate_threshold: 0.03,
                imbalance_threshold: 0.2,
            },
            metrics: MetricsConfig {
                enabled: true,
                scrape_interval_seconds: 5,
            },
        }
    }

    pub fn load() -> Self {
        let env = Environment::current();

        let mut config = match env {
            Environment::Development => Self::development(),
            Environment::Staging => Self::staging(),
            Environment::Production => Self::production(),
        };

        config.apply_env_overrides();

        tracing::info!(
            environment = env.as_str(),
            listen_addr = %config.center.listen_addr,
            metrics_addr = %config.center.metrics_addr,
            "Configuration loaded"
        );

        config
    }

    fn apply_env_overrides(&mut self) {
        if let Ok(v) = env::var("LISTEN_ADDR") {
            if let Ok(addr) = v.parse() {
                self.center.listen_addr = addr;
            }
        }
        if let Ok(v) = env::var("METRICS_ADDR") {
            if let Ok(addr) = v.parse() {
                self.center.metrics_addr = addr;
            }
        }
        if let Ok(v) = env::var("DATABASE_URL") {
            self.database.url = v;
        }
        if let Ok(v) = env::var("REDIS_URL") {
            self.redis.url = v;
        }
        if let Ok(v) = env::var("HEARTBEAT_INTERVAL_SECONDS") {
            if let Ok(secs) = v.parse() {
                self.center.heartbeat_interval_seconds = secs;
            }
        }
        if let Ok(v) = env::var("MAX_HEARTBEAT_FAILURES") {
            if let Ok(n) = v.parse() {
                self.center.max_heartbeat_failures = n;
            }
        }
        if let Ok(v) = env::var("TLS_ENCRYPTION_KEY") {
            self.tls.encryption_key = v;
        }
        if let Ok(v) = env::var("ACME_DIRECTORY_URL") {
            self.tls.acme_directory_url = v;
        }
        if let Ok(v) = env::var("ACME_CONTACT_EMAIL") {
            self.tls.contact_email = v;
        }
        if let Ok(v) = env::var("DB_MAX_CONNECTIONS") {
            if let Ok(n) = v.parse() {
                self.database.max_connections = n;
            }
        }
        if let Ok(v) = env::var("DB_MIN_CONNECTIONS") {
            if let Ok(n) = v.parse() {
                self.database.min_connections = n;
            }
        }
        if let Ok(v) = env::var("REDIS_MAX_CONNECTIONS") {
            if let Ok(n) = v.parse() {
                self.redis.max_connections = n;
            }
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheRule {
    pub path_pattern: String,
    pub ttl_seconds: u64,
    pub cache_key_template: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchedulingStrategy {
    pub strategy_type: String,
    pub weights: HashMap<String, u32>,
    pub health_check_enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DomainConfig {
    pub domain_name: String,
    pub origin_server: String,
    pub origin_port: u16,
    pub https_enabled: bool,
    pub cache_rules: Vec<CacheRule>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeRuntimeConfig {
    pub node_id: String,
    pub listen_addr: SocketAddr,
    pub cache_max_size_bytes: u64,
    pub cache_rules: Vec<CacheRule>,
    pub scheduling: SchedulingStrategy,
    pub domains: Vec<DomainConfig>,
    pub health_check_interval_seconds: u64,
    pub log_level: String,
}

#[derive(Debug, Clone)]
pub struct ConfigSnapshot {
    pub config: Arc<NodeRuntimeConfig>,
    pub version: u64,
    pub loaded_at: DateTime<Utc>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConfigValidationError {
    CacheTtlMustBePositive { rule_index: usize },
    OriginServerRequired { domain_index: usize },
    WeightCannotBeNegative { upstream: String },
    PathPatternCannotBeEmpty { rule_index: usize },
    CacheMaxSizeMustBePositive,
}

impl std::fmt::Display for ConfigValidationError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ConfigValidationError::CacheTtlMustBePositive { rule_index } => {
                write!(f, "Cache TTL must be positive at rule index {}", rule_index)
            }
            ConfigValidationError::OriginServerRequired { domain_index } => {
                write!(f, "Origin server is required at domain index {}", domain_index)
            }
            ConfigValidationError::WeightCannotBeNegative { upstream } => {
                write!(f, "Weight cannot be negative for upstream {}", upstream)
            }
            ConfigValidationError::PathPatternCannotBeEmpty { rule_index } => {
                write!(f, "Path pattern cannot be empty at rule index {}", rule_index)
            }
            ConfigValidationError::CacheMaxSizeMustBePositive => {
                write!(f, "Cache max size must be positive")
            }
        }
    }
}

impl std::error::Error for ConfigValidationError {}

pub fn validate_config(config: &NodeRuntimeConfig) -> Result<(), Vec<ConfigValidationError>> {
    let mut errors = Vec::new();

    if config.cache_max_size_bytes == 0 {
        errors.push(ConfigValidationError::CacheMaxSizeMustBePositive);
    }

    for (idx, rule) in config.cache_rules.iter().enumerate() {
        if rule.path_pattern.is_empty() {
            errors.push(ConfigValidationError::PathPatternCannotBeEmpty { rule_index: idx });
        }
        if rule.ttl_seconds == 0 {
            errors.push(ConfigValidationError::CacheTtlMustBePositive { rule_index: idx });
        }
    }

    for (idx, domain) in config.domains.iter().enumerate() {
        if domain.origin_server.is_empty() {
            errors.push(ConfigValidationError::OriginServerRequired { domain_index: idx });
        }
        for (rule_idx, rule) in domain.cache_rules.iter().enumerate() {
            let global_idx = config.cache_rules.len() + rule_idx;
            if rule.path_pattern.is_empty() {
                errors.push(ConfigValidationError::PathPatternCannotBeEmpty { rule_index: global_idx });
            }
            if rule.ttl_seconds == 0 {
                errors.push(ConfigValidationError::CacheTtlMustBePositive { rule_index: global_idx });
            }
        }
    }

    for (upstream, &weight) in &config.scheduling.weights {
        if weight == 0 {
            errors.push(ConfigValidationError::WeightCannotBeNegative { upstream: upstream.clone() });
        }
    }

    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors)
    }
}
