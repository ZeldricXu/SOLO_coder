use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Metrics {
    #[serde(default = "default_throughput")]
    pub throughput: f64,
    #[serde(default = "default_latency")]
    pub latency_p50: f64,
    #[serde(default = "default_latency")]
    pub latency_p95: f64,
    #[serde(default = "default_latency")]
    pub latency_p99: f64,
    #[serde(default = "default_error_rate")]
    pub error_rate: f64,
    #[serde(default)]
    pub success_count: u64,
    #[serde(default)]
    pub total_count: u64,
}

fn default_throughput() -> f64 {
    0.0
}

fn default_latency() -> f64 {
    0.0
}

fn default_error_rate() -> f64 {
    0.0
}

impl Metrics {
    pub fn new() -> Self {
        Self {
            throughput: 0.0,
            latency_p50: 0.0,
            latency_p95: 0.0,
            latency_p99: 0.0,
            error_rate: 0.0,
            success_count: 0,
            total_count: 0,
        }
    }

    pub fn record_success(&mut self, latency_ms: f64) {
        self.total_count += 1;
        self.success_count += 1;
        self.latency_p50 = latency_ms;
        self.latency_p95 = latency_ms * 1.2;
        self.latency_p99 = latency_ms * 1.5;
        self.error_rate = if self.total_count > 0 {
            (self.total_count - self.success_count) as f64 / self.total_count as f64
        } else {
            0.0
        };
    }

    pub fn record_error(&mut self) {
        self.total_count += 1;
        self.error_rate = if self.total_count > 0 {
            (self.total_count - self.success_count) as f64 / self.total_count as f64
        } else {
            0.0
        };
    }

    pub fn merge(&mut self, other: &Metrics) {
        self.total_count += other.total_count;
        self.success_count += other.success_count;
        self.throughput += other.throughput;
        self.latency_p50 = (self.latency_p50 + other.latency_p50) / 2.0;
        self.latency_p95 = (self.latency_p95 + other.latency_p95) / 2.0;
        self.latency_p99 = (self.latency_p99 + other.latency_p99) / 2.0;
        self.error_rate = if self.total_count > 0 {
            (self.total_count - self.success_count) as f64 / self.total_count as f64
        } else {
            0.0
        };
    }
}

impl Default for Metrics {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Snapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub metrics: Metrics,
    pub dimensions: std::collections::HashMap<String, String>,
}

impl Snapshot {
    pub fn new(metrics: Metrics) -> Self {
        Self {
            snapshot_id: format!("snap_{}", Uuid::new_v4().simple()),
            timestamp: Utc::now(),
            metrics,
            dimensions: std::collections::HashMap::new(),
        }
    }

    pub fn with_dimension(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.dimensions.insert(key.into(), value.into());
        self
    }

    pub fn with_dimensions(mut self, dims: std::collections::HashMap<String, String>) -> Self {
        self.dimensions = dims;
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_metrics_recording() {
        let mut metrics = Metrics::new();
        
        metrics.record_success(100.0);
        metrics.record_success(150.0);
        metrics.record_error();
        
        assert_eq!(metrics.total_count, 3);
        assert_eq!(metrics.success_count, 2);
        assert!((metrics.error_rate - 1.0 / 3.0).abs() < 0.001);
    }

    #[test]
    fn test_snapshot_creation() {
        let metrics = Metrics::new();
        let snapshot = Snapshot::new(metrics)
            .with_dimension("host", "node-1")
            .with_dimension("region", "cn-east");
        
        assert!(snapshot.snapshot_id.starts_with("snap_"));
        assert_eq!(snapshot.dimensions.get("host"), Some(&"node-1".to_string()));
        assert_eq!(snapshot.dimensions.get("region"), Some(&"cn-east".to_string()));
    }

    #[test]
    fn test_metrics_merge() {
        let mut m1 = Metrics::new();
        m1.record_success(100.0);
        
        let mut m2 = Metrics::new();
        m2.record_success(200.0);
        m2.record_error();
        
        m1.merge(&m2);
        
        assert_eq!(m1.total_count, 3);
        assert_eq!(m1.success_count, 2);
    }
}
