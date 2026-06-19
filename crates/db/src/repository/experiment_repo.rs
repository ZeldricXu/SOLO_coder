use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::error::DbResult;

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct Experiment {
    pub id: Uuid,
    pub name: String,
    pub model_name: String,
    pub status: String,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct ExperimentGroup {
    pub id: Uuid,
    pub experiment_id: Uuid,
    pub group_name: String,
    pub model_version_id: Uuid,
    pub traffic_percent: i32,
    pub is_control: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct ExperimentMetric {
    pub id: Uuid,
    pub experiment_id: Uuid,
    pub metric_name: String,
    pub metric_type: String,
    pub description: Option<String>,
    pub unit: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, sqlx::FromRow)]
pub struct ExperimentResult {
    pub id: Uuid,
    pub experiment_id: Uuid,
    pub group_name: String,
    pub metric_name: String,
    pub sample_count: i64,
    pub mean_value: Option<f64>,
    pub std_value: Option<f64>,
    pub p95_value: Option<f64>,
    pub p99_value: Option<f64>,
    pub computed_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateExperimentParams {
    pub name: String,
    pub model_name: String,
    pub status: Option<String>,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateExperimentParams {
    pub status: Option<String>,
    pub start_time: Option<Option<DateTime<Utc>>>,
    pub end_time: Option<Option<DateTime<Utc>>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateExperimentGroupParams {
    pub experiment_id: Uuid,
    pub group_name: String,
    pub model_version_id: Uuid,
    pub traffic_percent: i32,
    pub is_control: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateExperimentMetricParams {
    pub experiment_id: Uuid,
    pub metric_name: String,
    pub metric_type: String,
    pub description: Option<String>,
    pub unit: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateExperimentResultParams {
    pub experiment_id: Uuid,
    pub group_name: String,
    pub metric_name: String,
    pub sample_count: i64,
    pub mean_value: Option<f64>,
    pub std_value: Option<f64>,
    pub p95_value: Option<f64>,
    pub p99_value: Option<f64>,
}

#[async_trait]
pub trait ExperimentRepository: Send + Sync {
    async fn create_experiment(&self, params: &CreateExperimentParams) -> DbResult<Experiment>;
    async fn get_experiment_by_id(&self, id: Uuid) -> DbResult<Option<Experiment>>;
    async fn get_experiment_by_name(&self, name: &str) -> DbResult<Option<Experiment>>;
    async fn list_experiments(&self, status: Option<&str>, model_name: Option<&str>, limit: i64, offset: i64) -> DbResult<Vec<Experiment>>;
    async fn list_active_experiments(&self, model_name: &str) -> DbResult<Vec<Experiment>>;
    async fn update_experiment(&self, id: Uuid, params: &UpdateExperimentParams) -> DbResult<Experiment>;
    async fn start_experiment(&self, id: Uuid) -> DbResult<Experiment>;
    async fn end_experiment(&self, id: Uuid) -> DbResult<Experiment>;
    async fn delete_experiment(&self, id: Uuid) -> DbResult<()>;

    async fn create_experiment_group(&self, params: &CreateExperimentGroupParams) -> DbResult<ExperimentGroup>;
    async fn get_experiment_group_by_id(&self, id: Uuid) -> DbResult<Option<ExperimentGroup>>;
    async fn list_experiment_groups(&self, experiment_id: Uuid) -> DbResult<Vec<ExperimentGroup>>;
    async fn get_control_group(&self, experiment_id: Uuid) -> DbResult<Option<ExperimentGroup>>;
    async fn update_group_traffic(&self, id: Uuid, traffic_percent: i32) -> DbResult<ExperimentGroup>;
    async fn delete_experiment_group(&self, id: Uuid) -> DbResult<()>;

    async fn create_experiment_metric(&self, params: &CreateExperimentMetricParams) -> DbResult<ExperimentMetric>;
    async fn list_experiment_metrics(&self, experiment_id: Uuid) -> DbResult<Vec<ExperimentMetric>>;
    async fn delete_experiment_metric(&self, id: Uuid) -> DbResult<()>;

    async fn create_experiment_result(&self, params: &CreateExperimentResultParams) -> DbResult<ExperimentResult>;
    async fn list_experiment_results(&self, experiment_id: Uuid) -> DbResult<Vec<ExperimentResult>>;
    async fn get_group_results(&self, experiment_id: Uuid, group_name: &str) -> DbResult<Vec<ExperimentResult>>;
    async fn get_metric_results(&self, experiment_id: Uuid, metric_name: &str) -> DbResult<Vec<ExperimentResult>>;
    async fn upsert_experiment_result(&self, params: &CreateExperimentResultParams) -> DbResult<ExperimentResult>;
    async fn delete_experiment_results(&self, experiment_id: Uuid) -> DbResult<()>;
}
