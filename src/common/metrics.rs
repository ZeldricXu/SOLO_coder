use std::sync::Arc;
use parking_lot::Mutex;
use chrono::{Utc, DateTime};
use serde::{Serialize, Deserialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StatsSnapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub metrics: Metrics,
    pub dimensions: std::collections::HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Metrics {
    pub throughput: u64,
    pub latency_p50: u64,
    pub latency_p99: u64,
    pub error_rate: f64,
    pub success_count: u64,
    pub error_count: u64,
}

#[derive(Debug, Clone)]
pub struct MetricsCollector {
    inner: Arc<Mutex<MetricsInner>>,
}

#[derive(Debug)]
struct MetricsInner {
    latencies: Vec<u64>,
    success_count: u64,
    error_count: u64,
    start_time: DateTime<Utc>,
    dimensions: std::collections::HashMap<String, String>,
}

impl MetricsCollector {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Mutex::new(MetricsInner {
                latencies: Vec::new(),
                success_count: 0,
                error_count: 0,
                start_time: Utc::now(),
                dimensions: std::collections::HashMap::new(),
            })),
        }
    }

    pub fn with_dimension(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.inner.lock().dimensions.insert(key.into(), value.into());
        self
    }

    pub fn record_success(&self, latency_ms: u64) {
        let mut inner = self.inner.lock();
        inner.latencies.push(latency_ms);
        inner.success_count += 1;
    }

    pub fn record_error(&self, latency_ms: u64) {
        let mut inner = self.inner.lock();
        inner.latencies.push(latency_ms);
        inner.error_count += 1;
    }

    pub fn snapshot(&self) -> StatsSnapshot {
        let inner = self.inner.lock();
        let total = inner.success_count + inner.error_count;
        let error_rate = if total > 0 {
            inner.error_count as f64 / total as f64
        } else {
            0.0
        };

        let elapsed = (Utc::now() - inner.start_time).num_seconds() as u64;
        let throughput = if elapsed > 0 { total / elapsed } else { total };

        let mut sorted_latencies = inner.latencies.clone();
        sorted_latencies.sort();

        let latency_p50 = percentile(&sorted_latencies, 50);
        let latency_p99 = percentile(&sorted_latencies, 99);

        StatsSnapshot {
            snapshot_id: Uuid::new_v4().to_string(),
            timestamp: Utc::now(),
            metrics: Metrics {
                throughput,
                latency_p50,
                latency_p99,
                error_rate,
                success_count: inner.success_count,
                error_count: inner.error_count,
            },
            dimensions: inner.dimensions.clone(),
        }
    }

    pub fn reset(&self) {
        let mut inner = self.inner.lock();
        inner.latencies.clear();
        inner.success_count = 0;
        inner.error_count = 0;
        inner.start_time = Utc::now();
    }
}

fn percentile(sorted: &[u64], p: usize) -> u64 {
    if sorted.is_empty() {
        return 0;
    }
    let index = (p * (sorted.len() - 1)) / 100;
    sorted[index.min(sorted.len() - 1)]
}

impl Default for MetricsCollector {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthCheck {
    pub status: String,
    pub version: String,
    pub uptime_seconds: u64,
    pub timestamp: DateTime<Utc>,
    pub components: std::collections::HashMap<String, ComponentHealth>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ComponentHealth {
    pub status: String,
    pub message: Option<String>,
    pub last_check: DateTime<Utc>,
}

pub struct HealthChecker {
    start_time: DateTime<Utc>,
    components: Arc<Mutex<std::collections::HashMap<String, ComponentHealth>>>,
}

impl HealthChecker {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            start_time: Utc::now(),
            components: Arc::new(Mutex::new(std::collections::HashMap::new())),
        })
    }

    pub fn set_component_health(&self, name: impl Into<String>, healthy: bool, message: Option<String>) {
        self.components.lock().insert(
            name.into(),
            ComponentHealth {
                status: if healthy { "healthy".into() } else { "unhealthy".into() },
                message,
                last_check: Utc::now(),
            },
        );
    }

    pub fn check(&self) -> HealthCheck {
        let components = self.components.lock().clone();
        let all_healthy = components.values().all(|c| c.status == "healthy");
        let uptime = (Utc::now() - self.start_time).num_seconds() as u64;

        HealthCheck {
            status: if all_healthy { "healthy".into() } else { "degraded".into() },
            version: env!("CARGO_PKG_VERSION").into(),
            uptime_seconds: uptime,
            timestamp: Utc::now(),
            components,
        }
    }
}
