use anyhow::{Context, Result};
use dashmap::DashMap;
use metrics::{Counter, Gauge, Histogram};
use metrics_exporter_prometheus::{Matcher, PrometheusBuilder, PrometheusHandle};
use once_cell::sync::Lazy;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const INFERENCE_LATENCY_MS: &str = "inference.latency";
pub const INFERENCE_REQUESTS_TOTAL: &str = "inference.requests.total";
pub const INFERENCE_QPS: &str = "inference.qps";
pub const INFERENCE_SUCCESS_TOTAL: &str = "inference.requests.success";
pub const INFERENCE_FAILURE_TOTAL: &str = "inference.requests.failure";
pub const GPU_UTILIZATION_PERCENT: &str = "gpu.utilization";
pub const GPU_MEMORY_USED_MB: &str = "gpu.memory_used";
pub const GPU_MEMORY_TOTAL_MB: &str = "gpu.memory_total";
pub const MODEL_LOADED_COUNT: &str = "model.loaded";
pub const BATCH_SIZE: &str = "inference.batch_size";
pub const ROUTING_DECISIONS_TOTAL: &str = "routing.decisions";
pub const RATE_LIMIT_HITS_TOTAL: &str = "rate_limit.hits";
pub const DB_QUERY_LATENCY_MS: &str = "db.query.latency";
pub const REDIS_OP_LATENCY_MS: &str = "redis.op.latency";

fn init_descriptions() {
    metrics::describe_histogram!(
        INFERENCE_LATENCY_MS,
        metrics::Unit::Milliseconds,
        "Inference request latency in milliseconds, broken down by model name, version, and status"
    );
    metrics::describe_counter!(
        INFERENCE_REQUESTS_TOTAL,
        "Total number of inference requests, broken down by model name, version, and status"
    );
    metrics::describe_gauge!(
        INFERENCE_QPS,
        metrics::Unit::CountPerSecond,
        "Inference queries per second, broken down by model name"
    );
    metrics::describe_counter!(
        INFERENCE_SUCCESS_TOTAL,
        "Total number of successful inference requests, broken down by model name and version"
    );
    metrics::describe_counter!(
        INFERENCE_FAILURE_TOTAL,
        "Total number of failed inference requests, broken down by model name, version, and error type"
    );
    metrics::describe_gauge!(
        GPU_UTILIZATION_PERCENT,
        metrics::Unit::Percent,
        "GPU utilization percentage, broken down by GPU ID and model name"
    );
    metrics::describe_gauge!(
        GPU_MEMORY_USED_MB,
        "GPU memory used in megabytes, broken down by GPU ID"
    );
    metrics::describe_gauge!(
        GPU_MEMORY_TOTAL_MB,
        "Total GPU memory in megabytes, broken down by GPU ID"
    );
    metrics::describe_gauge!(
        MODEL_LOADED_COUNT,
        "Number of models currently loaded on a node, broken down by node ID"
    );
    metrics::describe_histogram!(
        BATCH_SIZE,
        "Dynamic inference batch size, broken down by model name"
    );
    metrics::describe_counter!(
        ROUTING_DECISIONS_TOTAL,
        "Total number of routing decisions, broken down by model name, version ID, and strategy"
    );
    metrics::describe_counter!(
        RATE_LIMIT_HITS_TOTAL,
        "Total number of rate limit hits, broken down by tenant ID"
    );
    metrics::describe_histogram!(
        DB_QUERY_LATENCY_MS,
        metrics::Unit::Milliseconds,
        "Database query latency in milliseconds, broken down by query type"
    );
    metrics::describe_histogram!(
        REDIS_OP_LATENCY_MS,
        metrics::Unit::Milliseconds,
        "Redis operation latency in milliseconds, broken down by operation type"
    );
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpuMetrics {
    pub utilization_percent: f64,
    pub memory_used_mb: u64,
    pub memory_total_mb: u64,
    pub temperature_c: f64,
}

pub struct MetricsRegistry {
    handle: PrometheusHandle,
    gpu_cache: RwLock<HashMap<String, GpuMetrics>>,
}

static REGISTRY: Lazy<MetricsRegistry> = Lazy::new(|| MetricsRegistry::new().expect("Failed to init metrics registry"));

impl MetricsRegistry {
    pub fn global() -> &'static MetricsRegistry {
        &REGISTRY
    }

    pub fn new() -> Result<Self> {
        let latency_buckets = vec![
            1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0,
        ];

        let batch_buckets = vec![1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0, 128.0, 256.0, 512.0];

        let handle = PrometheusBuilder::new()
            .set_buckets_for_metric(
                Matcher::Full(INFERENCE_LATENCY_MS.to_string()),
                &latency_buckets,
            )
            .context("Failed to set latency buckets")?
            .set_buckets_for_metric(
                Matcher::Full(DB_QUERY_LATENCY_MS.to_string()),
                &latency_buckets,
            )
            .context("Failed to set db query latency buckets")?
            .set_buckets_for_metric(
                Matcher::Full(REDIS_OP_LATENCY_MS.to_string()),
                &latency_buckets,
            )
            .context("Failed to set redis latency buckets")?
            .set_buckets_for_metric(
                Matcher::Full(BATCH_SIZE.to_string()),
                &batch_buckets,
            )
            .context("Failed to set batch size buckets")?
            .install_recorder()
            .context("Failed to install Prometheus recorder")?;

        init_descriptions();

        Ok(Self {
            handle,
            gpu_cache: RwLock::new(HashMap::new()),
        })
    }

    pub fn render(&self) -> String {
        self.handle.render()
    }

    pub fn get_gpu_metrics(&self, gpu_id: &str) -> Option<GpuMetrics> {
        self.gpu_cache.read().get(gpu_id).cloned()
    }

    pub fn all_gpu_metrics(&self) -> HashMap<String, GpuMetrics> {
        self.gpu_cache.read().clone()
    }
}

fn inference_latency_histogram(model_name: &str, version: &str, status: &str) -> Histogram {
    metrics::histogram!(
        INFERENCE_LATENCY_MS,
        "model_name" => model_name.to_string(),
        "version" => version.to_string(),
        "status" => status.to_string(),
    )
}

fn inference_requests_counter(model_name: &str, version: &str, status: &str) -> Counter {
    metrics::counter!(
        INFERENCE_REQUESTS_TOTAL,
        "model_name" => model_name.to_string(),
        "version" => version.to_string(),
        "status" => status.to_string(),
    )
}

fn gpu_utilization_gauge(gpu_id: &str, model_name: &str) -> Gauge {
    metrics::gauge!(
        GPU_UTILIZATION_PERCENT,
        "gpu_id" => gpu_id.to_string(),
        "model_name" => model_name.to_string(),
    )
}

fn gpu_memory_used_gauge(gpu_id: &str) -> Gauge {
    metrics::gauge!(
        GPU_MEMORY_USED_MB,
        "gpu_id" => gpu_id.to_string(),
    )
}

fn model_loaded_count_gauge(node_id: &str) -> Gauge {
    metrics::gauge!(
        MODEL_LOADED_COUNT,
        "node_id" => node_id.to_string(),
    )
}

fn batch_size_histogram(model_name: &str) -> Histogram {
    metrics::histogram!(
        BATCH_SIZE,
        "model_name" => model_name.to_string(),
    )
}

fn routing_decisions_counter(model_name: &str, version_id: &str, strategy: &str) -> Counter {
    metrics::counter!(
        ROUTING_DECISIONS_TOTAL,
        "model_name" => model_name.to_string(),
        "version_id" => version_id.to_string(),
        "strategy" => strategy.to_string(),
    )
}

fn rate_limit_hits_counter(tenant_id: &str) -> Counter {
    metrics::counter!(
        RATE_LIMIT_HITS_TOTAL,
        "tenant_id" => tenant_id.to_string(),
    )
}

fn db_query_latency_histogram(query_type: &str) -> Histogram {
    metrics::histogram!(
        DB_QUERY_LATENCY_MS,
        "query_type" => query_type.to_string(),
    )
}

fn redis_op_latency_histogram(op: &str) -> Histogram {
    metrics::histogram!(
        REDIS_OP_LATENCY_MS,
        "op" => op.to_string(),
    )
}

pub fn record_inference_latency(model_name: &str, version: &str, status: &str, duration_ms: f64) {
    inference_latency_histogram(model_name, version, status).record(duration_ms);
}

pub fn record_gpu_metrics(gpu_id: &str, metrics: &GpuMetrics) {
    gpu_utilization_gauge(gpu_id, "unknown").set(metrics.utilization_percent);
    gpu_memory_used_gauge(gpu_id).set(metrics.memory_used_mb as f64);

    REGISTRY
        .gpu_cache
        .write()
        .insert(gpu_id.to_string(), metrics.clone());
}

pub fn increment_requests(model_name: &str, version: &str, status: &str) {
    inference_requests_counter(model_name, version, status).increment(1);
}

pub fn increment_routing_decision(model_name: &str, version_id: &str, strategy: &str) {
    routing_decisions_counter(model_name, version_id, strategy).increment(1);
}

pub fn increment_rate_limit_hit(tenant_id: &str) {
    rate_limit_hits_counter(tenant_id).increment(1);
}

pub fn record_batch_size(model_name: &str, batch_size: f64) {
    batch_size_histogram(model_name).record(batch_size);
}

pub fn record_db_query_latency(query_type: &str, duration: Duration) {
    let duration_ms = duration.as_secs_f64() * 1000.0;
    db_query_latency_histogram(query_type).record(duration_ms);
}

pub fn record_redis_op_latency(op: &str, duration: Duration) {
    let duration_ms = duration.as_secs_f64() * 1000.0;
    redis_op_latency_histogram(op).record(duration_ms);
}

pub fn set_model_loaded_count(node_id: &str, count: u64) {
    model_loaded_count_gauge(node_id).set(count as f64);
}

fn inference_success_counter(model_name: &str, version: &str) -> Counter {
    metrics::counter!(
        INFERENCE_SUCCESS_TOTAL,
        "model_name" => model_name.to_string(),
        "version" => version.to_string(),
    )
}

fn inference_failure_counter(model_name: &str, version: &str, error_type: &str) -> Counter {
    metrics::counter!(
        INFERENCE_FAILURE_TOTAL,
        "model_name" => model_name.to_string(),
        "version" => version.to_string(),
        "error_type" => error_type.to_string(),
    )
}

fn inference_qps_gauge(model_name: &str) -> Gauge {
    metrics::gauge!(
        INFERENCE_QPS,
        "model_name" => model_name.to_string(),
    )
}

fn gpu_memory_total_gauge(gpu_id: &str) -> Gauge {
    metrics::gauge!(
        GPU_MEMORY_TOTAL_MB,
        "gpu_id" => gpu_id.to_string(),
    )
}

pub fn increment_success(model_name: &str, version: &str) {
    inference_success_counter(model_name, version).increment(1);
    increment_requests(model_name, version, "success");
    COLLECTOR.track_request(model_name);
}

pub fn increment_failure(model_name: &str, version: &str, error_type: &str) {
    inference_failure_counter(model_name, version, error_type).increment(1);
    increment_requests(model_name, version, "failure");
    COLLECTOR.track_request(model_name);
}

pub fn record_gpu_memory_total(gpu_id: &str, total_mb: u64) {
    gpu_memory_total_gauge(gpu_id).set(total_mb as f64);
}

struct QpsWindow {
    timestamps: Vec<Instant>,
}

impl QpsWindow {
    fn new() -> Self {
        Self {
            timestamps: Vec::new(),
        }
    }

    fn add(&mut self, now: Instant) {
        self.timestamps.push(now);
    }

    fn cleanup(&mut self, window: Duration) {
        let cutoff = Instant::now() - window;
        self.timestamps.retain(|&t| t > cutoff);
    }

    fn qps(&self, window: Duration) -> f64 {
        if self.timestamps.is_empty() {
            return 0.0;
        }
        self.timestamps.len() as f64 / window.as_secs_f64()
    }
}

pub struct MetricsCollector {
    qps_windows: Arc<DashMap<String, QpsWindow>>,
    window_duration: Duration,
}

impl MetricsCollector {
    pub fn new(window_secs: u64) -> Self {
        Self {
            qps_windows: Arc::new(DashMap::new()),
            window_duration: Duration::from_secs(window_secs),
        }
    }

    pub fn global() -> &'static MetricsCollector {
        &COLLECTOR
    }

    pub fn track_request(&self, model_name: &str) {
        let now = Instant::now();
        self.qps_windows
            .entry(model_name.to_string())
            .or_insert_with(QpsWindow::new)
            .add(now);
    }

    pub fn update_qps_metrics(&self) {
        let window = self.window_duration;
        for mut entry in self.qps_windows.iter_mut() {
            let model_name = entry.key().clone();
            let window_data = entry.value_mut();
            window_data.cleanup(window);
            let qps = window_data.qps(window);
            inference_qps_gauge(&model_name).set(qps);
        }
    }

    pub fn get_qps(&self, model_name: &str) -> f64 {
        let window = self.window_duration;
        self.qps_windows
            .get(model_name)
            .map(|w| {
                let mut w_inner = w.value().clone();
                w_inner.cleanup(window);
                w_inner.qps(window)
            })
            .unwrap_or(0.0)
    }

    pub fn all_qps(&self) -> Vec<(String, f64)> {
        let window = self.window_duration;
        let mut results = Vec::new();
        for entry in self.qps_windows.iter() {
            let model_name = entry.key().clone();
            let mut w_inner = entry.value().clone();
            w_inner.cleanup(window);
            results.push((model_name, w_inner.qps(window)));
        }
        results
    }

    pub fn window_duration(&self) -> Duration {
        self.window_duration
    }
}

impl Default for MetricsCollector {
    fn default() -> Self {
        Self::new(60)
    }
}

impl Clone for QpsWindow {
    fn clone(&self) -> Self {
        Self {
            timestamps: self.timestamps.clone(),
        }
    }
}

static COLLECTOR: Lazy<MetricsCollector> = Lazy::new(MetricsCollector::default);

pub fn update_qps_metrics() {
    COLLECTOR.update_qps_metrics();
}

pub fn get_model_qps(model_name: &str) -> f64 {
    COLLECTOR.get_qps(model_name)
}

pub fn get_all_qps() -> Vec<(String, f64)> {
    COLLECTOR.all_qps()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_gpu_metrics_struct() {
        let m = GpuMetrics {
            utilization_percent: 75.5,
            memory_used_mb: 4096,
            memory_total_mb: 8192,
            temperature_c: 65.0,
        };
        assert_eq!(m.utilization_percent, 75.5);
        assert_eq!(m.memory_used_mb, 4096);
    }

    #[test]
    fn test_metrics_collector_new() {
        let c = MetricsCollector::new(30);
        assert_eq!(c.window_duration(), Duration::from_secs(30));
    }

    #[test]
    fn test_metrics_collector_default() {
        let c = MetricsCollector::default();
        assert_eq!(c.window_duration(), Duration::from_secs(60));
    }

    #[test]
    fn test_metrics_collector_track_and_get() {
        let c = MetricsCollector::new(60);
        assert_eq!(c.get_qps("model_a"), 0.0);

        c.track_request("model_a");
        c.track_request("model_a");
        c.track_request("model_b");

        let qps_a = c.get_qps("model_a");
        assert!(qps_a > 0.0);

        let all = c.all_qps();
        assert_eq!(all.len(), 2);
    }
}
