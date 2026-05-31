use crate::types::{AppError, MetricsData, MetricsSnapshot, generate_id, now_utc};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tracing;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricValue {
    pub value: f64,
    pub timestamp: DateTime<Utc>,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistogramData {
    pub count: u64,
    pub sum: f64,
    pub min: f64,
    pub max: f64,
    pub avg: f64,
    pub p50: f64,
    pub p95: f64,
    pub p99: f64,
    pub p999: f64,
}

#[derive(Debug, Clone, Serialize)]
pub struct MetricsQuery {
    pub metric_name: String,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub labels: Option<HashMap<String, String>>,
    pub aggregation: Option<AggregationType>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AggregationType {
    Sum,
    Avg,
    Min,
    Max,
    Count,
    Rate,
}

pub struct MetricsCollector {
    counters: Arc<DashMap<String, RwLock<Counter>>>,
    gauges: Arc<DashMap<String, RwLock<Gauge>>>,
    histograms: Arc<DashMap<String, RwLock<Histogram>>>,
    summaries: Arc<DashMap<String, RwLock<Summary>>>,
    snapshots: Arc<RwLock<Vec<MetricsSnapshot>>>,
    max_snapshots: usize,
}

struct Counter {
    value: u64,
    labels: HashMap<String, String>,
}

struct Gauge {
    value: f64,
    labels: HashMap<String, String>,
}

struct Histogram {
    buckets: Vec<f64>,
    counts: Vec<u64>,
    sum: f64,
    min: f64,
    max: f64,
    values: Vec<f64>,
    max_values: usize,
    labels: HashMap<String, String>,
}

struct Summary {
    quantiles: Vec<f64>,
    values: Vec<f64>,
    sum: f64,
    count: u64,
    max_values: usize,
    labels: HashMap<String, String>,
}

impl MetricsCollector {
    pub fn new() -> Self {
        let collector = Self {
            counters: Arc::new(DashMap::new()),
            gauges: Arc::new(DashMap::new()),
            histograms: Arc::new(DashMap::new()),
            summaries: Arc::new(DashMap::new()),
            snapshots: Arc::new(RwLock::new(Vec::new())),
            max_snapshots: 10080,
        };

        collector.start_snapshot_collector();
        collector
    }

    pub fn register_counter(&self, name: &str, labels: HashMap<String, String>) {
        self.counters.insert(
            name.to_string(),
            RwLock::new(Counter { value: 0, labels }),
        );
        tracing::info!(metric = %name, "注册计数器");
    }

    pub fn register_gauge(&self, name: &str, labels: HashMap<String, String>) {
        self.gauges.insert(
            name.to_string(),
            RwLock::new(Gauge { value: 0.0, labels }),
        );
        tracing::info!(metric = %name, "注册仪表盘");
    }

    pub fn register_histogram(&self, name: &str, buckets: Vec<f64>, labels: HashMap<String, String>) {
        self.histograms.insert(
            name.to_string(),
            RwLock::new(Histogram {
                buckets,
                counts: vec![0; buckets.len() + 1],
                sum: 0.0,
                min: f64::INFINITY,
                max: f64::NEG_INFINITY,
                values: Vec::new(),
                max_values: 10000,
                labels,
            }),
        );
        tracing::info!(metric = %name, "注册直方图");
    }

    pub fn register_summary(&self, name: &str, quantiles: Vec<f64>, labels: HashMap<String, String>) {
        self.summaries.insert(
            name.to_string(),
            RwLock::new(Summary {
                quantiles,
                values: Vec::new(),
                sum: 0.0,
                count: 0,
                max_values: 10000,
                labels,
            }),
        );
        tracing::info!(metric = %name, "注册摘要");
    }

    pub fn increment_counter(&self, name: &str, value: u64) {
        if let Some(counter) = self.counters.get(name) {
            let mut c = counter.write();
            c.value += value;
        } else {
            self.register_counter(name, HashMap::new());
            if let Some(counter) = self.counters.get(name) {
                counter.write().value = value;
            }
        }
    }

    pub fn decrement_counter(&self, name: &str, value: u64) {
        if let Some(counter) = self.counters.get(name) {
            let mut c = counter.write();
            c.value = c.value.saturating_sub(value);
        }
    }

    pub fn set_gauge(&self, name: &str, value: f64) {
        if let Some(gauge) = self.gauges.get(name) {
            gauge.write().value = value;
        } else {
            self.register_gauge(name, HashMap::new());
            if let Some(gauge) = self.gauges.get(name) {
                gauge.write().value = value;
            }
        }
    }

    pub fn record_histogram(&self, name: &str, value: f64) {
        if let Some(histogram) = self.histograms.get(name) {
            let mut h = histogram.write();
            h.sum += value;
            h.min = h.min.min(value);
            h.max = h.max.max(value);

            for (i, bucket) in h.buckets.iter().enumerate() {
                if value <= *bucket {
                    h.counts[i] += 1;
                    break;
                }
                if i == h.buckets.len() - 1 {
                    h.counts[i + 1] += 1;
                }
            }

            h.values.push(value);
            if h.values.len() > h.max_values {
                h.values.remove(0);
            }
        } else {
            let buckets = vec![0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 10.0, 60.0];
            self.register_histogram(name, buckets, HashMap::new());
            if let Some(histogram) = self.histograms.get(name) {
                let mut h = histogram.write();
                h.sum += value;
                h.min = h.min.min(value);
                h.max = h.max.max(value);
                h.values.push(value);
            }
        }
    }

    pub fn record_summary(&self, name: &str, value: f64) {
        if let Some(summary) = self.summaries.get(name) {
            let mut s = summary.write();
            s.sum += value;
            s.count += 1;
            s.values.push(value);
            if s.values.len() > s.max_values {
                s.values.remove(0);
            }
        } else {
            let quantiles = vec![0.5, 0.9, 0.95, 0.99, 0.999];
            self.register_summary(name, quantiles, HashMap::new());
            if let Some(summary) = self.summaries.get(name) {
                let mut s = summary.write();
                s.sum += value;
                s.count += 1;
                s.values.push(value);
            }
        }
    }

    pub fn get_counter(&self, name: &str) -> Option<u64> {
        self.counters.get(name).map(|c| c.read().value)
    }

    pub fn get_gauge(&self, name: &str) -> Option<f64> {
        self.gauges.get(name).map(|g| g.read().value)
    }

    pub fn get_histogram_data(&self, name: &str) -> Option<HistogramData> {
        self.histograms.get(name).map(|h| {
            let h = h.read();
            let mut sorted = h.values.clone();
            sorted.sort_by(|a, b| a.partial_cmp(b).unwrap());

            HistogramData {
                count: h.values.len() as u64,
                sum: h.sum,
                min: h.min,
                max: h.max,
                avg: if h.values.is_empty() { 0.0 } else { h.sum / h.values.len() as f64 },
                p50: percentile(&sorted, 50.0),
                p95: percentile(&sorted, 95.0),
                p99: percentile(&sorted, 99.0),
                p999: percentile(&sorted, 99.9),
            }
        })
    }

    pub fn query_metrics(&self, query: MetricsQuery) -> Result<Vec<MetricValue>, AppError> {
        let mut results = Vec::new();
        let now = now_utc();
        let start = query.start_time.unwrap_or(now - chrono::Duration::hours(1));
        let end = query.end_time.unwrap_or(now);

        let snapshots = self.snapshots.read();
        for snapshot in snapshots.iter() {
            if snapshot.timestamp < start || snapshot.timestamp > end {
                continue;
            }

            if let Some(labels) = &query.labels {
                let mut match_all = true;
                for (k, v) in labels {
                    if snapshot.dimensions.get(k) != Some(v) {
                        match_all = false;
                        break;
                    }
                }
                if !match_all {
                    continue;
                }
            }

            let value = match query.metric_name.as_str() {
                "throughput" => snapshot.metrics.throughput as f64,
                "latency_p99" => snapshot.metrics.latency_p99 as f64,
                "error_rate" => snapshot.metrics.error_rate,
                _ => continue,
            };

            results.push(MetricValue {
                value,
                timestamp: snapshot.timestamp,
                labels: snapshot.dimensions.clone(),
            });
        }

        if let Some(agg) = query.aggregation {
            results = aggregate_metrics(results, agg);
        }

        Ok(results)
    }

    pub fn get_current_metrics(&self) -> Result<MetricsData, AppError> {
        let throughput = self.get_counter("requests_total").unwrap_or(0);
        let latency_p99 = self.get_histogram_data("request_latency_ms")
            .map(|h| h.p99 as u64)
            .unwrap_or(0);
        
        let total = self.get_counter("requests_total").unwrap_or(0);
        let errors = self.get_counter("requests_errors").unwrap_or(0);
        let error_rate = if total > 0 { errors as f64 / total as f64 } else { 0.0 };

        Ok(MetricsData {
            throughput,
            latency_p99,
            error_rate,
        })
    }

    pub fn create_snapshot(&self, dimensions: HashMap<String, String>) -> MetricsSnapshot {
        let metrics = self.get_current_metrics().unwrap_or(MetricsData {
            throughput: 0,
            latency_p99: 0,
            error_rate: 0.0,
        });

        let snapshot = MetricsSnapshot {
            snapshot_id: generate_id("snap"),
            timestamp: now_utc(),
            metrics,
            dimensions,
        };

        let mut snapshots = self.snapshots.write();
        snapshots.push(snapshot.clone());
        while snapshots.len() > self.max_snapshots {
            snapshots.remove(0);
        }

        snapshot
    }

    fn start_snapshot_collector(&self) {
        let collector_clone = self.clone();
        
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(60));
            loop {
                interval.tick().await;
                
                let mut dimensions = HashMap::new();
                dimensions.insert("host".to_string(), get_hostname());
                dimensions.insert("region".to_string(), "cn-east".to_string());
                
                collector_clone.create_snapshot(dimensions);
                tracing::debug!("指标快照已采集");
            }
        });
    }

    pub fn export_prometheus(&self) -> String {
        let mut output = String::new();

        for entry in self.counters.iter() {
            let (name, counter) = entry.pair();
            output.push_str(&format!("# HELP {} Counter metric\n", name));
            output.push_str(&format!("# TYPE {} counter\n", name));
            let labels_str = format_labels(&counter.read().labels);
            output.push_str(&format!("{}{} {}\n", name, labels_str, counter.read().value));
        }

        for entry in self.gauges.iter() {
            let (name, gauge) = entry.pair();
            output.push_str(&format!("# HELP {} Gauge metric\n", name));
            output.push_str(&format!("# TYPE {} gauge\n", name));
            let labels_str = format_labels(&gauge.read().labels);
            output.push_str(&format!("{}{} {}\n", name, labels_str, gauge.read().value));
        }

        for entry in self.histograms.iter() {
            let (name, histogram) = entry.pair();
            let h = histogram.read();
            output.push_str(&format!("# HELP {} Histogram metric\n", name));
            output.push_str(&format!("# TYPE {} histogram\n", name));
            
            for (i, bucket) in h.buckets.iter().enumerate() {
                let labels = format!("{}le=\"{}\"{}", 
                    if h.labels.is_empty() { "" } else { "," },
                    bucket,
                    format_labels(&h.labels)
                );
                output.push_str(&format!("{}_bucket{{{}}} {}\n", name, labels, h.counts[i]));
            }
            output.push_str(&format!("{}_sum{} {}\n", name, format_labels(&h.labels), h.sum));
            output.push_str(&format!("{}_count{} {}\n", name, format_labels(&h.labels), h.values.len() as u64));
        }

        output
    }

    pub fn list_metrics(&self) -> Vec<String> {
        let mut metrics = Vec::new();
        for entry in self.counters.iter() {
            metrics.push(format!("counter:{}", entry.key()));
        }
        for entry in self.gauges.iter() {
            metrics.push(format!("gauge:{}", entry.key()));
        }
        for entry in self.histograms.iter() {
            metrics.push(format!("histogram:{}", entry.key()));
        }
        for entry in self.summaries.iter() {
            metrics.push(format!("summary:{}", entry.key()));
        }
        metrics
    }

    pub fn reset_metrics(&self) {
        for counter in self.counters.iter() {
            counter.write().value = 0;
        }
        for gauge in self.gauges.iter() {
            gauge.write().value = 0.0;
        }
        for histogram in self.histograms.iter() {
            let mut h = histogram.write();
            h.counts = vec![0; h.buckets.len() + 1];
            h.sum = 0.0;
            h.min = f64::INFINITY;
            h.max = f64::NEG_INFINITY;
            h.values.clear();
        }
        for summary in self.summaries.iter() {
            let mut s = summary.write();
            s.sum = 0.0;
            s.count = 0;
            s.values.clear();
        }
        tracing::info!("指标已重置");
    }
}

impl Clone for MetricsCollector {
    fn clone(&self) -> Self {
        Self {
            counters: self.counters.clone(),
            gauges: self.gauges.clone(),
            histograms: self.histograms.clone(),
            summaries: self.summaries.clone(),
            snapshots: self.snapshots.clone(),
            max_snapshots: self.max_snapshots,
        }
    }
}

fn percentile(sorted_values: &[f64], p: f64) -> f64 {
    if sorted_values.is_empty() {
        return 0.0;
    }
    let index = (p / 100.0 * (sorted_values.len() - 1) as f64).round() as usize;
    sorted_values.get(index).copied().unwrap_or(0.0)
}

fn format_labels(labels: &HashMap<String, String>) -> String {
    if labels.is_empty() {
        return String::new();
    }
    let parts: Vec<String> = labels
        .iter()
        .map(|(k, v)| format!("{}=\"{}\"", k, v.replace('"', "\\\"")))
        .collect();
    format!("{{{}}}", parts.join(","))
}

fn aggregate_metrics(values: Vec<MetricValue>, agg: AggregationType) -> Vec<MetricValue> {
    if values.is_empty() {
        return values;
    }

    let aggregated_value = match agg {
        AggregationType::Sum => values.iter().map(|v| v.value).sum(),
        AggregationType::Avg => values.iter().map(|v| v.value).sum::<f64>() / values.len() as f64,
        AggregationType::Min => values.iter().map(|v| v.value).fold(f64::INFINITY, f64::min),
        AggregationType::Max => values.iter().map(|v| v.value).fold(f64::NEG_INFINITY, f64::max),
        AggregationType::Count => values.len() as f64,
        AggregationType::Rate => {
            if values.len() >= 2 {
                let first = values.first().unwrap();
                let last = values.last().unwrap();
                let duration = last.timestamp - first.timestamp;
                let seconds = duration.num_seconds() as f64;
                if seconds > 0.0 {
                    (last.value - first.value) / seconds
                } else {
                    0.0
                }
            } else {
                0.0
            }
        }
    };

    vec![MetricValue {
        value: aggregated_value,
        timestamp: values.last().map(|v| v.timestamp).unwrap_or_else(now_utc),
        labels: HashMap::new(),
    }]
}

fn get_hostname() -> String {
    std::env::var("HOSTNAME").unwrap_or_else(|_| "node-1".to_string())
}
