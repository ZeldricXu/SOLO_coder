use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Map;
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Entity {
    pub id: String,
    pub r#type: String,
    pub status: EntityStatus,
    pub attributes: Map<String, serde_json::Value>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum EntityStatus {
    Active,
    Inactive,
    Provisioning,
    Deprovisioning,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Config {
    pub config_id: String,
    pub namespace: String,
    pub version: u32,
    pub parameters: HashMap<String, serde_json::Value>,
    pub enabled: bool,
    pub applied_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RunInstance {
    pub run_id: String,
    pub entity_id: String,
    pub phase: RunPhase,
    pub progress: f64,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_detail: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RunPhase {
    Pending,
    Initializing,
    Running,
    Finalizing,
    Completed,
    Failed,
    Rollback,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MetricsSnapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub metrics: MetricsData,
    pub dimensions: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct MetricsData {
    pub throughput: u64,
    pub latency_p99: u64,
    pub error_rate: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceCreateRequest {
    pub r#type: String,
    pub config: serde_json::Value,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceCreateResponse {
    pub id: String,
    pub status: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceStatusResponse {
    pub id: String,
    pub status: String,
    pub progress: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchOperationRequest {
    pub operations: Vec<BatchOperation>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchOperation {
    pub action: String,
    pub id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchOperationResponse {
    pub batch_id: String,
    pub results: Vec<BatchResult>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchResult {
    pub id: String,
    pub success: bool,
    pub message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse<T> {
    pub code: u16,
    pub data: Option<T>,
    pub message: Option<String>,
}

impl<T> ApiResponse<T> {
    pub fn success(data: T) -> Self {
        Self {
            code: 200,
            data: Some(data),
            message: None,
        }
    }

    pub fn created(data: T) -> Self {
        Self {
            code: 201,
            data: Some(data),
            message: None,
        }
    }

    pub fn error(code: u16, message: impl Into<String>) -> Self {
        Self {
            code,
            data: None,
            message: Some(message.into()),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HandlerRequest {
    pub trace_id: String,
    pub namespace: String,
    pub params: serde_json::Value,
    pub payload: serde_json::Value,
}

#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error("参数验证失败: {0}")]
    ValidationError(String),
    #[error("上游服务响应超时")]
    TimeoutError,
    #[error("资源未找到: {0}")]
    NotFound(String),
    #[error("认证失败: {0}")]
    Unauthorized(String),
    #[error("权限不足: {0}")]
    Forbidden(String),
    #[error("服务限流")]
    RateLimited,
    #[error("内部处理错误: {0}")]
    InternalError(String),
    #[error("配置错误: {0}")]
    ConfigError(String),
    #[error("并发冲突: {0}")]
    Conflict(String),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Event {
    pub event_id: String,
    pub event_type: String,
    pub aggregate_id: String,
    pub version: u64,
    pub payload: serde_json::Value,
    pub metadata: HashMap<String, String>,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Snapshot {
    pub snapshot_id: String,
    pub aggregate_id: String,
    pub version: u64,
    pub state: serde_json::Value,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
    pub shutdown_timeout: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DatabaseConfig {
    pub url: String,
    pub pool_size: u32,
    pub connect_timeout: u64,
    pub idle_timeout: u64,
    pub max_lifetime: u64,
    pub acquire_timeout: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct RedisConfig {
    pub url: String,
    pub pool_size: u32,
    pub connect_timeout: u64,
    pub idle_timeout: u64,
    pub max_lifetime: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LoggingConfig {
    pub dir: String,
    pub level: String,
    pub format: String,
    pub rotation: String,
    pub retention_days: u32,
    pub compression: bool,
    pub ansi_colors: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct JwtConfig {
    pub secret: String,
    pub expiry_seconds: u64,
    pub refresh_expiry_seconds: u64,
    pub algorithm: String,
    pub issuer: String,
    pub audience: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct RateLimitConfig {
    pub per_minute: u32,
    pub per_hour: u32,
    pub per_day: u32,
    pub burst_size: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct CacheConfig {
    pub ttl_seconds: u64,
    pub max_entries: u64,
    pub eviction_policy: String,
    pub local_cache_enabled: bool,
    pub redis_cache_enabled: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct MetricsConfig {
    pub enabled: bool,
    pub port: u16,
    pub endpoint: String,
    pub histogram_buckets: Vec<f64>,
    pub export_prometheus: bool,
    pub export_otlp: bool,
    pub otlp_endpoint: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct CircuitBreakerConfig {
    pub failure_threshold: f64,
    pub timeout_seconds: u64,
    pub half_open_max_calls: u32,
    pub success_threshold: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SidecarConfig {
    pub cpu_limit: String,
    pub memory_limit: String,
    pub injection_mode: String,
    pub auto_restart: bool,
    pub liveness_probe_enabled: bool,
    pub readiness_probe_enabled: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SchedulerConfig {
    pub enabled: bool,
    pub thread_pool_size: u32,
    pub default_retry_count: u32,
    pub default_retry_interval: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct FaultInjectionConfig {
    pub enabled: bool,
    pub probability: f64,
    pub default_duration_seconds: u64,
    pub auto_rollback: bool,
    pub rollback_timeout_seconds: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct TrafficControlConfig {
    pub default_policy: String,
    pub canary_enabled: bool,
    pub canary_percentage: u32,
    pub mirroring_enabled: bool,
    pub circuit_breaker_enabled: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct EventStoreConfig {
    pub snapshot_interval: u64,
    pub max_events_per_snapshot: u64,
    pub snapshot_retention_count: u32,
    pub auto_compaction: bool,
    pub compaction_interval_seconds: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub database: DatabaseConfig,
    pub redis: RedisConfig,
    pub logging: LoggingConfig,
    pub jwt: JwtConfig,
    pub rate_limit: RateLimitConfig,
    pub cache: CacheConfig,
    pub metrics: MetricsConfig,
    pub circuit_breaker: CircuitBreakerConfig,
    pub sidecar: SidecarConfig,
    pub scheduler: SchedulerConfig,
    pub fault_injection: FaultInjectionConfig,
    pub traffic_control: TrafficControlConfig,
    pub event_store: EventStoreConfig,
}

impl AppConfig {
    pub fn load() -> Result<Self, AppError> {
        let _ = dotenvy::dotenv();

        let environment = std::env::var("RUST_ENV")
            .unwrap_or_else(|_| "development".to_string())
            .to_lowercase();

        let config_dir = std::env::var("CONFIG_DIR")
            .unwrap_or_else(|_| "./config".to_string());

        let mut builder = config::Config::builder()
            .add_source(config::File::from(format!("{}/default.toml", config_dir)))
            .add_source(config::File::from(format!("{}/{}.toml", config_dir, environment)));

        if let Ok(local_config) = std::env::var("LOCAL_CONFIG") {
            builder = builder.add_source(config::File::from(local_config));
        }

        builder = builder
            .add_source(config::Environment::with_prefix("APP").separator("__"))
            .add_source(config::Environment::with_prefix("DATA_TRANSFORMER").separator("__"));

        let config = builder
            .build()
            .map_err(|e| AppError::ConfigError(format!("加载配置失败: {}", e)))?;

        let app_config: AppConfig = config
            .try_deserialize()
            .map_err(|e| AppError::ConfigError(format!("解析配置失败: {}", e)))?;

        Ok(app_config)
    }

    pub fn environment() -> String {
        std::env::var("RUST_ENV")
            .unwrap_or_else(|_| "development".to_string())
            .to_lowercase()
    }

    pub fn is_development() -> bool {
        Self::environment() == "development"
    }

    pub fn is_staging() -> bool {
        Self::environment() == "staging"
    }

    pub fn is_production() -> bool {
        Self::environment() == "production"
    }
}

pub fn generate_id(prefix: &str) -> String {
    format!("{}_{}", prefix, Uuid::new_v4().to_string().replace("-", ""))
}

pub fn now_utc() -> DateTime<Utc> {
    Utc::now()
}
