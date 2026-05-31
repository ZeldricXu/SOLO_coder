use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Snapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub metrics: Metrics,
    pub dimensions: std::collections::HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Metrics {
    pub throughput: f64,
    #[serde(rename = "latency_p99")]
    pub latency_p99: f64,
    #[serde(rename = "error_rate")]
    pub error_rate: f64,
}

impl Snapshot {
    pub fn new() -> Self {
        Self {
            snapshot_id: crate::models::IdGenerator::generate("snap"),
            timestamp: Utc::now(),
            metrics: Metrics {
                throughput: 0.0,
                latency_p99: 0.0,
                error_rate: 0.0,
            },
            dimensions: std::collections::HashMap::new(),
        }
    }

    pub fn with_throughput(mut self, value: f64) -> Self {
        self.metrics.throughput = value;
        self
    }

    pub fn with_latency_p99(mut self, value: f64) -> Self {
        self.metrics.latency_p99 = value;
        self
    }

    pub fn with_error_rate(mut self, value: f64) -> Self {
        self.metrics.error_rate = value;
        self
    }

    pub fn with_dimension(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.dimensions.insert(key.into(), value.into());
        self
    }
}

impl Default for Snapshot {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Default)]
pub struct MetricsCollector {
    events_processed: u64,
    errors: u64,
    latencies: Vec<f64>,
    start_time: std::time::Instant,
}

impl MetricsCollector {
    pub fn new() -> Self {
        Self {
            events_processed: 0,
            errors: 0,
            latencies: Vec::new(),
            start_time: std::time::Instant::now(),
        }
    }

    pub fn record_event(&mut self, latency_ms: f64) {
        self.events_processed += 1;
        self.latencies.push(latency_ms);
    }

    pub fn record_error(&mut self) {
        self.errors += 1;
    }

    pub fn snapshot(&self) -> Snapshot {
        let elapsed = self.start_time.elapsed().as_secs_f64();
        let throughput = if elapsed > 0.0 {
            self.events_processed as f64 / elapsed
        } else {
            0.0
        };

        let latency_p99 = if self.latencies.is_empty() {
            0.0
        } else {
            let mut sorted = self.latencies.clone();
            sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
            let idx = ((sorted.len() - 1) as f64 * 0.99) as usize;
            sorted[idx]
        };

        let error_rate = if self.events_processed > 0 {
            self.errors as f64 / self.events_processed as f64
        } else {
            0.0
        };

        Snapshot::new()
            .with_throughput(throughput)
            .with_latency_p99(latency_p99)
            .with_error_rate(error_rate)
    }

    pub fn reset(&mut self) {
        self.events_processed = 0;
        self.errors = 0;
        self.latencies.clear();
        self.start_time = std::time::Instant::now();
    }
}
