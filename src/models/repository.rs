use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct Repository {
    pub id: Uuid,
    pub organization_id: Uuid,
    pub team_id: Option<Uuid>,
    pub provider: String,
    pub provider_id: String,
    pub name: String,
    pub full_name: String,
    pub webhook_secret: String,
    pub is_active: bool,
    pub last_sync_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct RepositoryWithDetails {
    pub id: Uuid,
    pub organization_id: Uuid,
    pub team_id: Option<Uuid>,
    pub team_name: Option<String>,
    pub provider: String,
    pub provider_id: String,
    pub name: String,
    pub full_name: String,
    pub is_active: bool,
    pub last_sync_at: Option<DateTime<Utc>>,
    pub mr_count: i64,
    pub pending_reviews: i64,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreateRepositoryRequest {
    pub provider: String,
    pub provider_id: String,
    pub name: String,
    pub full_name: String,
    pub team_id: Option<Uuid>,
}

#[derive(Debug, Deserialize)]
pub struct RepositoryQuery {
    pub team_id: Option<Uuid>,
    pub provider: Option<String>,
    pub is_active: Option<bool>,
    pub page: Option<i32>,
    pub per_page: Option<i32>,
}

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct WebhookLog {
    pub id: Uuid,
    pub provider: String,
    pub repo_id: Option<Uuid>,
    pub event_type: String,
    pub delivery_id: Option<String>,
    pub payload: serde_json::Value,
    pub status: i32,
    pub error_message: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct DiffSnapshot {
    pub id: Uuid,
    pub merge_request_id: Uuid,
    pub storage_key: String,
    pub checksum: String,
    pub line_count: i32,
    pub changed_files: i32,
    pub created_at: DateTime<Utc>,
}
