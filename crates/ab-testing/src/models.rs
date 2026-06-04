use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use common::models::SchedulingStrategy;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum ExperimentStatus {
    Draft,
    Running,
    Paused,
    Completed,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum ExperimentGroup {
    Control,
    Treatment,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExperimentMetrics {
    pub experiment_id: Uuid,
    pub group: ExperimentGroup,
    pub sample_size: u64,
    pub cache_hit_rate: f64,
    pub avg_latency_ms: f64,
    pub origin_fetch_rate: f64,
    pub user_qoe_score: f64,
    pub collected_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Experiment {
    pub id: Uuid,
    pub name: String,
    pub description: String,
    pub control_strategy: SchedulingStrategy,
    pub treatment_strategy: SchedulingStrategy,
    pub traffic_percentage: u32,
    pub target_nodes: Vec<Uuid>,
    pub status: ExperimentStatus,
    pub metrics: Vec<ExperimentMetrics>,
    pub created_at: DateTime<Utc>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StatisticalResult {
    pub metric_name: String,
    pub control_mean: f64,
    pub treatment_mean: f64,
    pub control_std: f64,
    pub treatment_std: f64,
    pub p_value: f64,
    pub is_significant: bool,
    pub confidence_level: f64,
}
