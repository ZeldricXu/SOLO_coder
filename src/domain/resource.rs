use serde::{Serialize, Deserialize};
use chrono::{DateTime, Utc};
use uuid::Uuid;
use serde_json::Value;
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ResourceStatus {
    Provisioning,
    Running,
    Stopped,
    Failed,
    Deleted,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Resource {
    pub id: String,
    pub resource_type: String,
    pub status: ResourceStatus,
    pub config: Value,
    pub labels: HashMap<String, String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl Resource {
    pub fn new(resource_type: impl Into<String>, config: Value) -> Self {
        let now = Utc::now();
        Self {
            id: format!("rsc_{}", Uuid::new_v4().simple()),
            resource_type: resource_type.into(),
            status: ResourceStatus::Provisioning,
            config,
            labels: HashMap::new(),
            created_at: now,
            updated_at: now,
        }
    }

    pub fn with_label(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.labels.insert(key.into(), value.into());
        self
    }

    pub fn set_status(&mut self, status: ResourceStatus) {
        self.status = status;
        self.updated_at = Utc::now();
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceCreateRequest {
    pub resource_type: String,
    pub config: Value,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceStatusResponse {
    pub id: String,
    pub status: String,
    pub progress: Option<f32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchOperation {
    pub action: String,
    pub id: String,
    pub params: Option<Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchRequest {
    pub operations: Vec<BatchOperation>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchResponseData {
    pub batch_id: String,
    pub results: Vec<BatchResultItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchResultItem {
    pub id: String,
    pub success: bool,
    pub status: Option<String>,
    pub error: Option<String>,
}
