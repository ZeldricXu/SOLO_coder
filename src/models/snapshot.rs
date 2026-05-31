use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Metrics {
    pub throughput: Option<f64>,
    pub latency_p50: Option<f64>,
    pub latency_p99: Option<f64>,
    pub error_rate: Option<f64>,
    pub success_count: Option<u64>,
    pub failure_count: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Snapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub metrics: Metrics,
    pub dimensions: HashMap<String, String>,
}

impl Snapshot {
    pub fn new() -> Self {
        Self {
            snapshot_id: format!("snap_{}", Uuid::new_v4().simple()),
            timestamp: Utc::now(),
            metrics: Metrics::default(),
            dimensions: HashMap::new(),
        }
    }

    pub fn with_metrics(mut self, metrics: Metrics) -> Self {
        self.metrics = metrics;
        self
    }

    pub fn with_dimension<K: Into<String>, V: Into<String>>(mut self, key: K, value: V) -> Self {
        self.dimensions.insert(key.into(), value.into());
        self
    }

    pub fn calculate_health_score(&self) -> f64 {
        let error_rate = self.metrics.error_rate.unwrap_or(0.0);
        let latency_p99 = self.metrics.latency_p99.unwrap_or(0.0);
        let throughput = self.metrics.throughput.unwrap_or(0.0);

        let error_score = (1.0 - error_rate).max(0.0);
        let latency_score = if latency_p99 < 100.0 { 1.0 } else if latency_p99 < 500.0 { 0.8 } else { 0.5 };
        let throughput_score = if throughput > 1000.0 { 1.0 } else if throughput > 100.0 { 0.7 } else { 0.3 };

        (error_score * 0.4 + latency_score * 0.3 + throughput_score * 0.3) * 100.0
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DriftDetectionResult {
    pub feature_name: String,
    pub drift_score: f64,
    pub threshold: f64,
    pub is_drifted: bool,
    pub baseline_mean: f64,
    pub current_mean: f64,
}

impl DriftDetectionResult {
    pub fn new(feature_name: String, drift_score: f64, threshold: f64) -> Self {
        Self {
            feature_name,
            drift_score,
            threshold,
            is_drifted: drift_score > threshold,
            baseline_mean: 0.0,
            current_mean: 0.0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_snapshot_creation() {
        let metrics = Metrics {
            throughput: Some(1500.0),
            latency_p50: Some(50.0),
            latency_p99: Some(250.0),
            error_rate: Some(0.001),
            success_count: Some(999),
            failure_count: Some(1),
        };

        let snapshot = Snapshot::new()
            .with_metrics(metrics)
            .with_dimension("host", "node-1")
            .with_dimension("region", "cn-east");

        assert!(snapshot.snapshot_id.starts_with("snap_"));
        assert_eq!(snapshot.metrics.throughput, Some(1500.0));
        assert_eq!(snapshot.metrics.error_rate, Some(0.001));
        assert_eq!(snapshot.dimensions.get("host"), Some(&"node-1".to_string()));
        assert_eq!(snapshot.dimensions.get("region"), Some(&"cn-east".to_string()));
    }

    #[test]
    fn test_health_score_calculation() {
        let good_metrics = Metrics {
            throughput: Some(2000.0),
            latency_p99: Some(50.0),
            error_rate: Some(0.0),
            ..Default::default()
        };
        let good_snapshot = Snapshot::new().with_metrics(good_metrics);
        let good_score = good_snapshot.calculate_health_score();
        assert!(good_score > 80.0);

        let bad_metrics = Metrics {
            throughput: Some(10.0),
            latency_p99: Some(1000.0),
            error_rate: Some(0.5),
            ..Default::default()
        };
        let bad_snapshot = Snapshot::new().with_metrics(bad_metrics);
        let bad_score = bad_snapshot.calculate_health_score();
        assert!(bad_score < 60.0);
    }

    #[test]
    fn test_drift_detection() {
        let drifted = DriftDetectionResult::new("feature_1".to_string(), 0.8, 0.5);
        assert!(drifted.is_drifted);
        assert_eq!(drifted.drift_score, 0.8);

        let not_drifted = DriftDetectionResult::new("feature_2".to_string(), 0.3, 0.5);
        assert!(!not_drifted.is_drifted);
    }
}
