use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Metrics {
    pub throughput: f64,
    pub latency_p99: f64,
    pub error_rate: f64,
    #[serde(flatten)]
    pub additional: HashMap<String, f64>,
}

impl Metrics {
    pub fn new(throughput: f64, latency_p99: f64, error_rate: f64) -> Self {
        Self {
            throughput,
            latency_p99,
            error_rate,
            additional: HashMap::new(),
        }
    }

    pub fn merge(&mut self, other: &Metrics) {
        self.throughput = (self.throughput + other.throughput) / 2.0;
        self.latency_p99 = self.latency_p99.max(other.latency_p99);
        self.error_rate = (self.error_rate + other.error_rate) / 2.0;

        for (k, v) in &other.additional {
            *self.additional.entry(k.clone()).or_insert(0.0) = (*v + self.additional.get(k).unwrap_or(&0.0)) / 2.0;
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Snapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub metrics: Metrics,
    pub dimensions: HashMap<String, String>,
}

impl Snapshot {
    pub fn new(metrics: Metrics, dimensions: HashMap<String, String>) -> Self {
        Self {
            snapshot_id: format!("snap_{}", Uuid::new_v4().simple()),
            timestamp: Utc::now(),
            metrics,
            dimensions,
        }
    }

    pub fn get_dimension(&self, key: &str) -> Option<&String> {
        self.dimensions.get(key)
    }

    pub fn with_host(mut self, host: impl Into<String>) -> Self {
        self.dimensions.insert("host".to_string(), host.into());
        self
    }

    pub fn with_region(mut self, region: impl Into<String>) -> Self {
        self.dimensions.insert("region".to_string(), region.into());
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SnapshotQuery {
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub dimensions: Option<HashMap<String, String>>,
    pub metric_names: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SnapshotAggregate {
    pub avg_throughput: f64,
    pub avg_latency_p99: f64,
    pub avg_error_rate: f64,
    pub snapshot_count: usize,
    pub time_range: (DateTime<Utc>, DateTime<Utc>),
}
