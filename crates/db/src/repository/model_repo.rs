use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use uuid::Uuid;

use crate::error::DbResult;

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct Model {
    pub id: Uuid,
    pub name: String,
    pub category: String,
    pub description: Option<String>,
    pub latest_version: i32,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct ModelVersion {
    pub id: Uuid,
    pub model_id: Uuid,
    pub version: i32,
    pub framework: String,
    pub status: String,
    pub minio_bucket: Option<String>,
    pub minio_object_path: Option<String>,
    pub gpu_memory_required_mb: Option<i32>,
    pub input_schema: Option<Value>,
    pub output_schema: Option<Value>,
    pub preprocess_pipeline: Option<Value>,
    pub postprocess_pipeline: Option<Value>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct ModelDeployment {
    pub id: Uuid,
    pub model_version_id: Uuid,
    pub gpu_device_id: Uuid,
    pub status: String,
    pub loaded_at: Option<DateTime<Utc>>,
    pub last_used_at: Option<DateTime<Utc>>,
    pub request_count: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateModelParams {
    pub name: String,
    pub category: String,
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateModelVersionParams {
    pub model_id: Uuid,
    pub version: i32,
    pub framework: String,
    pub status: Option<String>,
    pub minio_bucket: Option<String>,
    pub minio_object_path: Option<String>,
    pub gpu_memory_required_mb: Option<i32>,
    pub input_schema: Option<Value>,
    pub output_schema: Option<Value>,
    pub preprocess_pipeline: Option<Value>,
    pub postprocess_pipeline: Option<Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateModelParams {
    pub name: Option<String>,
    pub category: Option<String>,
    pub description: Option<Option<String>>,
    pub latest_version: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateModelVersionParams {
    pub status: Option<String>,
    pub minio_bucket: Option<Option<String>>,
    pub minio_object_path: Option<Option<String>>,
    pub gpu_memory_required_mb: Option<Option<i32>>,
    pub input_schema: Option<Option<Value>>,
    pub output_schema: Option<Option<Value>>,
}

#[async_trait]
pub trait ModelRepository: Send + Sync {
    async fn create_model(&self, params: &CreateModelParams) -> DbResult<Model>;
    async fn get_model_by_id(&self, id: Uuid) -> DbResult<Option<Model>>;
    async fn get_model_by_name(&self, name: &str) -> DbResult<Option<Model>>;
    async fn list_models(&self, category: Option<&str>, limit: i64, offset: i64) -> DbResult<Vec<Model>>;
    async fn update_model(&self, id: Uuid, params: &UpdateModelParams) -> DbResult<Model>;
    async fn delete_model(&self, id: Uuid) -> DbResult<()>;

    async fn create_model_version(&self, params: &CreateModelVersionParams) -> DbResult<ModelVersion>;
    async fn get_model_version_by_id(&self, id: Uuid) -> DbResult<Option<ModelVersion>>;
    async fn get_model_versions(&self, model_id: Uuid) -> DbResult<Vec<ModelVersion>>;
    async fn get_model_version(&self, model_id: Uuid, version: i32) -> DbResult<Option<ModelVersion>>;
    async fn get_latest_model_version(&self, model_id: Uuid) -> DbResult<Option<ModelVersion>>;
    async fn update_model_version(&self, id: Uuid, params: &UpdateModelVersionParams) -> DbResult<ModelVersion>;
    async fn delete_model_version(&self, id: Uuid) -> DbResult<()>;

    async fn create_deployment(&self, model_version_id: Uuid, gpu_device_id: Uuid, status: &str) -> DbResult<ModelDeployment>;
    async fn get_deployment_by_id(&self, id: Uuid) -> DbResult<Option<ModelDeployment>>;
    async fn list_deployments_by_model_version(&self, model_version_id: Uuid) -> DbResult<Vec<ModelDeployment>>;
    async fn list_deployments_by_gpu(&self, gpu_device_id: Uuid) -> DbResult<Vec<ModelDeployment>>;
    async fn list_deployments_by_status(&self, status: &str) -> DbResult<Vec<ModelDeployment>>;
    async fn update_deployment_status(&self, id: Uuid, status: &str) -> DbResult<ModelDeployment>;
    async fn increment_request_count(&self, id: Uuid) -> DbResult<()>;
    async fn update_last_used_at(&self, id: Uuid) -> DbResult<()>;
    async fn delete_deployment(&self, id: Uuid) -> DbResult<()>;
}
