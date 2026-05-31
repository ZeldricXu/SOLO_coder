use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ServiceStatus {
    Active,
    Deprecated,
    Development,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServiceEntry {
    pub id: Uuid,
    pub name: String,
    pub description: String,
    pub language: String,
    pub owner: String,
    pub team: String,
    pub repository_url: String,
    pub api_doc_url: Option<String>,
    pub status: ServiceStatus,
    pub version: String,
    pub tags: Vec<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum DependencyType {
    Runtime,
    Build,
    Dev,
    Optional,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DependencyRelation {
    pub source_id: Uuid,
    pub target_id: Uuid,
    pub dep_type: DependencyType,
    pub version_constraint: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServiceSearchQuery {
    pub keyword: Option<String>,
    pub language: Option<String>,
    pub team: Option<String>,
    pub status: Option<ServiceStatus>,
    pub tags: Vec<String>,
    pub page: usize,
    pub page_size: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PagedResult<T> {
    pub items: Vec<T>,
    pub total: usize,
    pub page: usize,
    pub page_size: usize,
}
