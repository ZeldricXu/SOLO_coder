use serde::{Serialize, Deserialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum EnvStatus {
    Pending,
    Running,
    Stopped,
    Terminated,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum EnvType {
    Preview,
    Staging,
    Dev,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceConfig {
    pub cpu_cores: f64,
    pub memory_gb: f64,
    pub storage_gb: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PreviewEnvironment {
    pub id: Uuid,
    pub name: String,
    pub env_type: EnvType,
    pub branch_name: String,
    pub git_url: String,
    pub status: EnvStatus,
    pub creator: String,
    pub owner_team: String,
    pub created_at: DateTime<Utc>,
    pub expires_at: DateTime<Utc>,
    pub last_heartbeat: Option<DateTime<Utc>>,
    pub resources: ResourceConfig,
    pub endpoints: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProvisionRequest {
    pub name: String,
    pub env_type: EnvType,
    pub branch_name: String,
    pub git_url: String,
    pub creator: String,
    pub owner_team: String,
    pub resource_config: ResourceConfig,
    pub ttl_hours: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UsageStats {
    pub env_id: Uuid,
    pub total_hours: f64,
    pub cpu_seconds: f64,
    pub memory_gb_hours: f64,
    pub requests_count: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CleanupPolicy {
    pub auto_terminate_hours: u32,
    pub max_concurrent_per_team: u32,
    pub max_total_preview_envs: u32,
}
