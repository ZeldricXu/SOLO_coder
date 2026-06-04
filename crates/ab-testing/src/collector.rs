use std::collections::HashMap;
use std::sync::Arc;
use uuid::Uuid;
use chrono::Utc;
use tokio::sync::RwLock;

use crate::models::{ExperimentMetrics, ExperimentGroup};

pub struct MetricsCollector {
    metrics: Arc<RwLock<HashMap<Uuid, Vec<ExperimentMetrics>>>>,
}

impl Clone for MetricsCollector {
    fn clone(&self) -> Self {
        MetricsCollector {
            metrics: self.metrics.clone(),
        }
    }
}

impl MetricsCollector {
    pub fn new() -> Self {
        MetricsCollector {
            metrics: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn record_metric(
        &self,
        experiment_id: Uuid,
        group: ExperimentGroup,
        cache_hit_rate: f64,
        avg_latency_ms: f64,
        origin_fetch_rate: f64,
        qoe_score: f64,
    ) {
        let metric = ExperimentMetrics {
            experiment_id,
            group,
            sample_size: 1,
            cache_hit_rate,
            avg_latency_ms,
            origin_fetch_rate,
            user_qoe_score: qoe_score,
            collected_at: Utc::now(),
        };
        let mut metrics = self.metrics.write().await;
        metrics.entry(experiment_id).or_default().push(metric);
    }

    pub async fn get_metrics(&self, experiment_id: Uuid) -> Vec<ExperimentMetrics> {
        let metrics = self.metrics.read().await;
        metrics.get(&experiment_id).cloned().unwrap_or_default()
    }

    pub async fn get_metrics_by_group(
        &self,
        experiment_id: Uuid,
        group: ExperimentGroup,
    ) -> Vec<ExperimentMetrics> {
        let metrics = self.metrics.read().await;
        metrics
            .get(&experiment_id)
            .map(|m| {
                m.iter()
                    .filter(|item| item.group == group)
                    .cloned()
                    .collect()
            })
            .unwrap_or_default()
    }
}
