use crate::monitoring::domain::{
    MetricId, MetricRecord, MetricValue, Labels, MetricsSnapshot
};
use crate::monitoring::core::{Counter, Gauge, Histogram, MetricsCollector};
use crate::monitoring::registry::{InMemoryRegistry, SimpleCollector};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Clone)]
pub struct MetricsServiceImpl<C: MetricsCollector> {
    collector: Arc<C>,
}

impl<C: MetricsCollector> MetricsServiceImpl<C> {
    pub fn new(collector: Arc<C>) -> Self {
        Self { collector }
    }
}

pub type MetricsService = MetricsServiceImpl<SimpleCollector<InMemoryRegistry>>;

impl MetricsService {
    pub fn new() -> Self {
        let registry = InMemoryRegistry::new();
        let collector = Arc::new(SimpleCollector::new(registry));
        Self::new(collector)
    }
}

impl Default for MetricsService {
    fn default() -> Self {
        Self::new()
    }
}

impl<C: MetricsCollector> MetricsServiceImpl<C> {
    pub fn increment(&self, id: impl Into<MetricId>, labels: Labels) {
        let counter = self.collector.counter(id, labels);
        counter.inc();
    }

    pub fn increment_by(&self, id: impl Into<MetricId>, labels: Labels, amount: u64) {
        let counter = self.collector.counter(id, labels);
        counter.inc_by(amount);
    }

    pub fn set_gauge(&self, id: impl Into<MetricId>, labels: Labels, value: f64) {
        let gauge = self.collector.gauge(id, labels);
        gauge.set(value);
    }

    pub fn observe_histogram(&self, id: impl Into<MetricId>, labels: Labels, value: f64) {
        let hist = self.collector.histogram(id, labels);
        hist.observe(value);
    }

    pub fn time<F, R>(&self, id: impl Into<MetricId>, labels: Labels, f: F) -> R
    where
        F: FnOnce() -> R,
    {
        let start = std::time::Instant::now();
        let result = f();
        let duration = start.elapsed().as_secs_f64();
        self.observe_histogram(id, labels, duration);
        result
    }

    pub fn snapshot(&self) -> MetricsSnapshot {
        self.collector.snapshot()
    }

    pub fn aggregate(&self) -> AggregatedMetrics {
        let snapshot = self.snapshot();
        let mut counters = std::collections::HashMap::new();
        let mut gauges = std::collections::HashMap::new();
        let mut histograms: std::collections::HashMap<String, Vec<f64>> = std::collections::HashMap::new();

        for record in &snapshot.records {
            let key = record.id.to_string();
            match record.value {
                MetricValue::Counter(v) => {
                    *counters.entry(key).or_insert(0) += v;
                }
                MetricValue::Gauge(v) => {
                    gauges.insert(key, v);
                }
                MetricValue::HistogramSample(v) => {
                    histograms.entry(key).or_default().push(v);
                }
            }
        }

        let histogram_stats = histograms.into_iter().map(|(k, samples)| {
            (k, HistogramStats::from_samples(&samples))
        }).collect();

        AggregatedMetrics {
            counters,
            gauges,
            histograms: histogram_stats,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregatedMetrics {
    pub counters: std::collections::HashMap<String, u64>,
    pub gauges: std::collections::HashMap<String, f64>,
    pub histograms: std::collections::HashMap<String, HistogramStats>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistogramStats {
    pub count: usize,
    pub sum: f64,
    pub avg: f64,
    pub min: f64,
    pub max: f64,
    pub p50: f64,
    pub p95: f64,
    pub p99: f64,
}

impl HistogramStats {
    fn from_samples(samples: &[f64]) -> Self {
        if samples.is_empty() {
            return Self {
                count: 0,
                sum: 0.0,
                avg: 0.0,
                min: 0.0,
                max: 0.0,
                p50: 0.0,
                p95: 0.0,
                p99: 0.0,
            };
        }

        let mut sorted = samples.to_vec();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));

        let sum: f64 = sorted.iter().sum();
        let count = sorted.len();
        let avg = sum / count as f64;
        let min = sorted[0];
        let max = sorted[count - 1];

        let p50 = Self::percentile(&sorted, 0.5);
        let p95 = Self::percentile(&sorted, 0.95);
        let p99 = Self::percentile(&sorted, 0.99);

        Self { count, sum, avg, min, max, p50, p95, p99 }
    }

    fn percentile(sorted: &[f64], p: f64) -> f64 {
        if sorted.is_empty() {
            return 0.0;
        }
        let idx = ((sorted.len() as f64 - 1.0) * p) as usize;
        sorted[idx]
    }
}
