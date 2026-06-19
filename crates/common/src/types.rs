use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use utoipa::ToSchema;
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum ModelCategory {
    Recommendation,
    Nlp,
    Cv,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum ModelFramework {
    Onnx,
    TensorRT,
    Tensorflow,
    Pytorch,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum ModelStatus {
    Pending,
    Loading,
    Online,
    Offline,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct IOSchema {
    pub name: String,
    pub dtype: String,
    pub shape: Vec<i64>,
    pub description: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct ModelVersion {
    pub id: Uuid,
    pub model_id: Uuid,
    pub version: String,
    pub framework: ModelFramework,
    pub status: ModelStatus,
    pub input_schema: Vec<IOSchema>,
    pub output_schema: Vec<IOSchema>,
    pub gpu_memory_mb: u64,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct Model {
    pub id: Uuid,
    pub name: String,
    pub category: ModelCategory,
    pub description: Option<String>,
    pub latest_version: Option<String>,
    pub versions: Vec<ModelVersion>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct InferenceRequest {
    pub request_id: String,
    pub model_name: String,
    pub version: Option<String>,
    pub inputs: serde_json::Value,
    pub parameters: Option<serde_json::Value>,
    pub user_id: Option<String>,
    pub tenant_id: Option<String>,
    pub trace_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct InferenceResponse {
    pub request_id: String,
    pub model_name: String,
    pub version: String,
    pub outputs: serde_json::Value,
    pub latency_ms: u64,
    pub gpu_id: Option<String>,
    pub trace_id: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum RoutingStrategy {
    UserHash,
    Region,
    Random,
    RoundRobin,
    Experiment,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct RouteTarget {
    pub model_version_id: Uuid,
    pub weight: u32,
    pub is_primary: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct ExperimentGroup {
    pub name: String,
    pub model_version_id: Uuid,
    pub traffic_percent: u8,
    pub config: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct MetricDefinition {
    pub name: String,
    #[serde(rename = "type")]
    pub metric_type: String,
    pub description: Option<String>,
    pub unit: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
#[serde(rename_all = "snake_case")]
pub enum ExperimentStatus {
    Draft,
    Running,
    Paused,
    Completed,
    Archived,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct Experiment {
    pub id: Uuid,
    pub name: String,
    pub model_name: String,
    pub control_group: ExperimentGroup,
    pub experiment_groups: Vec<ExperimentGroup>,
    pub metrics: Vec<MetricDefinition>,
    pub start_time: DateTime<Utc>,
    pub end_time: Option<DateTime<Utc>>,
    pub status: ExperimentStatus,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct GpuDevice {
    pub id: String,
    pub uuid: String,
    pub name: String,
    pub total_memory_mb: u64,
    pub used_memory_mb: u64,
    pub utilization_percent: f32,
    pub temperature: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct Tenant {
    pub id: Uuid,
    pub name: String,
    pub api_key: String,
    pub qps_limit: u64,
    pub rate_limit_per_minute: u64,
    pub created_at: DateTime<Utc>,
}
