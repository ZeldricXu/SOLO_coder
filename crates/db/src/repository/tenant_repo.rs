use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::DbResult;

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct Tenant {
    pub id: Uuid,
    pub name: String,
    pub api_key: String,
    pub api_key_hash: String,
    pub qps_limit: i32,
    pub rate_limit_per_minute: i32,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateTenantParams {
    pub name: String,
    pub api_key: String,
    pub api_key_hash: String,
    pub qps_limit: Option<i32>,
    pub rate_limit_per_minute: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateTenantParams {
    pub name: Option<String>,
    pub qps_limit: Option<i32>,
    pub rate_limit_per_minute: Option<i32>,
    pub api_key: Option<String>,
    pub api_key_hash: Option<String>,
}

#[async_trait]
pub trait TenantRepository: Send + Sync {
    async fn create_tenant(&self, params: &CreateTenantParams) -> DbResult<Tenant>;
    async fn get_tenant_by_id(&self, id: Uuid) -> DbResult<Option<Tenant>>;
    async fn get_tenant_by_api_key(&self, api_key: &str) -> DbResult<Option<Tenant>>;
    async fn get_tenant_by_api_key_hash(&self, api_key_hash: &str) -> DbResult<Option<Tenant>>;
    async fn list_tenants(&self, limit: i64, offset: i64) -> DbResult<Vec<Tenant>>;
    async fn update_tenant(&self, id: Uuid, params: &UpdateTenantParams) -> DbResult<Tenant>;
    async fn delete_tenant(&self, id: Uuid) -> DbResult<()>;
    async fn rotate_api_key(&self, id: Uuid, new_api_key: &str, new_api_key_hash: &str) -> DbResult<Tenant>;
    async fn update_rate_limits(&self, id: Uuid, qps_limit: i32, rate_limit_per_minute: i32) -> DbResult<Tenant>;
}
