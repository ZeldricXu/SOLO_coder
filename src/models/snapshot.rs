use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricsSnapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub metrics: HashMap<String, f64>,
    pub dimensions: HashMap<String, String>,
}

impl MetricsSnapshot {
    pub fn new(dimensions: HashMap<String, String>) -> Self {
        Self {
            snapshot_id: format!("snap_{}", uuid::Uuid::new_v4().simple()),
            timestamp: Utc::now(),
            metrics: HashMap::new(),
            dimensions,
        }
    }

    pub fn with_metrics(
        dimensions: HashMap<String, String>,
        metrics: HashMap<String, f64>,
    ) -> Self {
        Self {
            snapshot_id: format!("snap_{}", uuid::Uuid::new_v4().simple()),
            timestamp: Utc::now(),
            metrics,
            dimensions,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricRecord {
    pub name: String,
    pub value: f64,
    #[serde(default)]
    pub timestamp: DateTime<Utc>,
    #[serde(default)]
    pub dimensions: HashMap<String, String>,
}

impl MetricRecord {
    pub fn new(name: impl Into<String>, value: f64) -> Self {
        Self {
            name: name.into(),
            value,
            timestamp: Utc::now(),
            dimensions: HashMap::new(),
        }
    }

    pub fn with_dimension(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.dimensions.insert(key.into(), value.into());
        self
    }
}
