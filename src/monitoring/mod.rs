pub mod domain;
pub mod core;
pub mod registry;
pub mod service;

pub use domain::{MetricId, MetricType, MetricValue, Label, Labels, MetricRecord};
pub use service::MetricsService;

use serde::{Serialize, Deserialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricStatistics {
    pub count: usize,
    pub sum: f64,
    pub avg: f64,
    pub min: f64,
    pub max: f64,
    pub p50: f64,
    pub p95: f64,
    pub p99: f64,
}

pub struct MetricCollector {
    inner: MetricsService,
}

impl MetricCollector {
    pub fn new() -> Self {
        Self { inner: MetricsService::new() }
    }

    pub fn increment(&self, name: &str) {
        self.inner.increment(name.to_string(), Labels::new());
    }

    pub fn record_value(&self, name: &str, value: f64) {
        self.inner.observe_histogram(name.to_string(), Labels::new(), value);
    }

    pub fn set_gauge(&self, name: &str, value: f64) {
        self.inner.set_gauge(name.to_string(), Labels::new(), value);
    }
}

impl Clone for MetricCollector {
    fn clone(&self) -> Self {
        Self { inner: self.inner.clone() }
    }
}

pub struct MetricsAggregator {
    inner: MetricsService,
}

impl MetricsAggregator {
    pub fn new(_collector: MetricCollector) -> Self {
        Self { inner: MetricsService::new() }
    }

    pub async fn get_all_metric_names(&self) -> Vec<String> {
        let snapshot = self.inner.snapshot();
        let mut names: Vec<String> = snapshot.records.iter()
            .map(|r| r.id.to_string())
            .collect();
        names.sort();
        names.dedup();
        names
    }

    pub async fn get_statistics(&self, name: &str) -> Option<MetricStatistics> {
        let aggregated = self.inner.aggregate();
        
        if let Some(&v) = aggregated.counters.get(name) {
            return Some(MetricStatistics {
                count: 1,
                sum: v as f64,
                avg: v as f64,
                min: v as f64,
                max: v as f64,
                p50: v as f64,
                p95: v as f64,
                p99: v as f64,
            });
        }

        if let Some(&v) = aggregated.gauges.get(name) {
            return Some(MetricStatistics {
                count: 1,
                sum: v,
                avg: v,
                min: v,
                max: v,
                p50: v,
                p95: v,
                p99: v,
            });
        }

        if let Some(stats) = aggregated.histograms.get(name) {
            return Some(MetricStatistics {
                count: stats.count,
                sum: stats.sum,
                avg: stats.avg,
                min: stats.min,
                max: stats.max,
                p50: stats.p50,
                p95: stats.p95,
                p99: stats.p99,
            });
        }

        None
    }
}

impl Clone for MetricsAggregator {
    fn clone(&self) -> Self {
        Self { inner: self.inner.clone() }
    }
}
