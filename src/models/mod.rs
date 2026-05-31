use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Entity {
    pub id: String,
    pub entity_type: String,
    pub status: String,
    pub attributes: serde_json::Value,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub config_id: String,
    pub namespace: String,
    pub version: u32,
    pub parameters: serde_json::Value,
    pub enabled: bool,
    pub applied_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunInstance {
    pub run_id: String,
    pub entity_id: String,
    pub phase: String,
    pub progress: f64,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_detail: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Metrics {
    pub throughput: u32,
    pub latency_p99: u32,
    pub error_rate: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StatsSnapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub metrics: Metrics,
    pub dimensions: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse<T> {
    pub code: u16,
    pub data: T,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceCreateRequest {
    pub resource_type: String,
    pub config: serde_json::Value,
    pub labels: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceCreateResponse {
    pub id: String,
    pub status: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceStatus {
    pub id: String,
    pub status: String,
    pub progress: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchOperation {
    pub action: String,
    pub id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchRequest {
    pub operations: Vec<BatchOperation>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchResponse {
    pub batch_id: String,
    pub results: Vec<BatchResult>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchResult {
    pub id: String,
    pub action: String,
    pub success: bool,
    pub message: Option<String>,
}

#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error("Validation error: {0}")]
    Validation(String),
    #[error("Timeout error")]
    Timeout,
    #[error("Internal error: {0}")]
    Internal(String),
    #[error("NotFound: {0}")]
    NotFound(String),
}

impl Entity {
    pub fn new(entity_type: &str, attributes: serde_json::Value) -> Self {
        let now = Utc::now();
        Self {
            id: format!("ent_{}", Uuid::new_v4().simple()),
            entity_type: entity_type.to_string(),
            status: "active".to_string(),
            attributes,
            created_at: now,
            updated_at: now,
        }
    }
}

impl Config {
    pub fn new(namespace: &str, parameters: serde_json::Value) -> Self {
        Self {
            config_id: format!("cfg_{}", Uuid::new_v4().simple()),
            namespace: namespace.to_string(),
            version: 1,
            parameters,
            enabled: true,
            applied_at: Utc::now(),
        }
    }
}

impl RunInstance {
    pub fn new(entity_id: &str) -> Self {
        Self {
            run_id: format!("run_{}", Uuid::new_v4().simple()),
            entity_id: entity_id.to_string(),
            phase: "initializing".to_string(),
            progress: 0.0,
            started_at: Utc::now(),
            completed_at: None,
            error_detail: None,
        }
    }
}

impl StatsSnapshot {
    pub fn new(metrics: Metrics, dimensions: serde_json::Value) -> Self {
        Self {
            snapshot_id: format!("snap_{}", Uuid::new_v4().simple()),
            timestamp: Utc::now(),
            metrics,
            dimensions,
        }
    }
}
