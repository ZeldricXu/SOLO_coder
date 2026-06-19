use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::DbResult;

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct GpuDevice {
    pub id: Uuid,
    pub node_id: String,
    pub gpu_uuid: String,
    pub name: String,
    pub total_memory_mb: i32,
    pub driver_version: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateGpuDeviceParams {
    pub node_id: String,
    pub gpu_uuid: String,
    pub name: String,
    pub total_memory_mb: i32,
    pub driver_version: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateGpuDeviceParams {
    pub name: Option<String>,
    pub total_memory_mb: Option<i32>,
    pub driver_version: Option<Option<String>>,
    pub node_id: Option<String>,
}

#[async_trait]
pub trait GpuRepository: Send + Sync {
    async fn create_gpu_device(&self, params: &CreateGpuDeviceParams) -> DbResult<GpuDevice>;
    async fn get_gpu_device_by_id(&self, id: Uuid) -> DbResult<Option<GpuDevice>>;
    async fn get_gpu_device_by_uuid(&self, gpu_uuid: &str) -> DbResult<Option<GpuDevice>>;
    async fn list_gpu_devices(&self, node_id: Option<&str>, min_memory_mb: Option<i32>, limit: i64, offset: i64) -> DbResult<Vec<GpuDevice>>;
    async fn list_gpu_devices_by_node(&self, node_id: &str) -> DbResult<Vec<GpuDevice>>;
    async fn list_available_gpu_devices(&self, required_memory_mb: i32) -> DbResult<Vec<GpuDevice>>;
    async fn update_gpu_device(&self, id: Uuid, params: &UpdateGpuDeviceParams) -> DbResult<GpuDevice>;
    async fn delete_gpu_device(&self, id: Uuid) -> DbResult<()>;
    async fn delete_gpu_device_by_uuid(&self, gpu_uuid: &str) -> DbResult<()>;
    async fn count_gpu_devices(&self) -> DbResult<i64>;
    async fn count_gpu_devices_by_node(&self, node_id: &str) -> DbResult<i64>;
}
