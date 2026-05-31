use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse<T> {
    pub code: u16,
    pub data: Option<T>,
    pub message: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pagination: Option<Pagination>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Pagination {
    pub total: u64,
    pub page: u32,
    pub size: u32,
}

impl<T> ApiResponse<T> {
    pub fn success(data: T) -> Self {
        Self {
            code: 200,
            data: Some(data),
            message: None,
            pagination: None,
        }
    }

    pub fn created(data: T) -> Self {
        Self {
            code: 201,
            data: Some(data),
            message: None,
            pagination: None,
        }
    }

    pub fn error(code: u16, message: impl Into<String>) -> Self {
        Self {
            code,
            data: None,
            message: Some(message.into()),
            pagination: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceRequest {
    pub r#type: String,
    pub config: serde_json::Value,
    pub labels: std::collections::HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceResponse {
    pub id: String,
    pub status: String,
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
    pub status: String,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Resource {
    pub id: String,
    pub r#type: String,
    pub status: String,
    pub attributes: std::collections::HashMap<String, String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunInstance {
    pub run_id: String,
    pub entity_id: String,
    pub phase: String,
    pub progress: f64,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_detail: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct IdGenerator;

impl IdGenerator {
    pub fn generate(prefix: &str) -> String {
        format!("{}_{}", prefix, Uuid::new_v4().simple())
    }
}

pub fn now() -> DateTime<Utc> {
    Utc::now()
}

pub fn format_time(dt: DateTime<Utc>) -> String {
    dt.to_rfc3339_opts(chrono::SecondsFormat::Secs, true)
}
