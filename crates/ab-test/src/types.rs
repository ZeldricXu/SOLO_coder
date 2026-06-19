use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;
use utoipa::ToSchema;

pub use common::types::ExperimentStatus;

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct MetricValue {
    pub name: String,
    pub sample_count: u64,
    pub mean: f64,
    pub std: f64,
    pub min: f64,
    pub max: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct GroupResult {
    pub group_name: String,
    pub model_version_id: Uuid,
    pub metrics: HashMap<String, MetricValue>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct StatSignificance {
    pub metric_name: String,
    pub group_name: String,
    pub control_group: String,
    pub p_value: f64,
    pub t_stat: Option<f64>,
    pub z_stat: Option<f64>,
    pub is_significant: bool,
    pub effect_size: f64,
    pub confidence_interval: (f64, f64),
    pub confidence_level: f64,
    pub uplift_percent: f64,
    pub relative_change: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct ExperimentReport {
    pub experiment_id: Uuid,
    pub name: String,
    pub total_users: u64,
    pub duration_days: f64,
    pub groups: Vec<GroupResult>,
    pub stat_significance: Vec<StatSignificance>,
    pub conclusions: Vec<String>,
    pub generated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct MetricObservation {
    pub group_name: String,
    pub metric_name: String,
    pub value: f64,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, ToSchema)]
pub struct PendingObservation {
    pub experiment_id: Uuid,
    pub group_name: String,
    pub metric_name: String,
    pub value: f64,
    pub timestamp: DateTime<Utc>,
}

pub trait ExperimentExt {
    fn all_groups(&self) -> Vec<common::types::ExperimentGroup>;
    fn total_traffic(&self) -> u8;
}

impl ExperimentExt for common::types::Experiment {
    fn all_groups(&self) -> Vec<common::types::ExperimentGroup> {
        let mut groups = Vec::new();
        groups.push(self.control_group.clone());
        groups.extend(self.experiment_groups.clone());
        groups
    }

    fn total_traffic(&self) -> u8 {
        self.all_groups()
            .iter()
            .map(|g| g.traffic_percent)
            .sum()
    }
}
