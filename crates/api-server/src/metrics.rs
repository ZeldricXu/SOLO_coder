use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use axum::{extract::State, http::StatusCode, response::IntoResponse};
use tokio::sync::RwLock;

pub struct CdnMetrics {
    pub nodes_online: AtomicU64,
    pub nodes_total: AtomicU64,
    pub requests_total: AtomicU64,
    pub requests_success: AtomicU64,
    pub requests_failed: AtomicU64,
    pub cache_hits: AtomicU64,
    pub cache_misses: AtomicU64,
    pub scheduling_latency_us: Arc<RwLock<Vec<u64>>>,
    pub bandwidth_usage_bytes: AtomicU64,
    pub origin_fetches: AtomicU64,
    pub active_connections: AtomicU64,
    pub bandwidth_capacity_bytes: AtomicU64,
}

impl CdnMetrics {
    pub fn new() -> Self {
        Self {
            nodes_online: AtomicU64::new(0),
            nodes_total: AtomicU64::new(0),
            requests_total: AtomicU64::new(0),
            requests_success: AtomicU64::new(0),
            requests_failed: AtomicU64::new(0),
            cache_hits: AtomicU64::new(0),
            cache_misses: AtomicU64::new(0),
            scheduling_latency_us: Arc::new(RwLock::new(Vec::with_capacity(1000))),
            bandwidth_usage_bytes: AtomicU64::new(0),
            origin_fetches: AtomicU64::new(0),
            active_connections: AtomicU64::new(0),
            bandwidth_capacity_bytes: AtomicU64::new(0),
        }
    }

    pub async fn record_scheduling_latency(&self, latency_us: u64) {
        let mut latencies = self.scheduling_latency_us.write().await;
        if latencies.len() >= 1000 {
            latencies.remove(0);
        }
        latencies.push(latency_us);
    }

    pub async fn calculate_percentile(&self, p: f64) -> u64 {
        let latencies = self.scheduling_latency_us.read().await;
        if latencies.is_empty() {
            return 0;
        }
        let mut sorted = latencies.clone();
        sorted.sort_unstable();
        let idx = ((p / 100.0) * (sorted.len() - 1) as f64).round() as usize;
        sorted[idx.min(sorted.len() - 1)]
    }

    pub async fn render_prometheus(&self) -> String {
        let nodes_online = self.nodes_online.load(Ordering::Relaxed);
        let nodes_total = self.nodes_total.load(Ordering::Relaxed);
        let requests_total = self.requests_total.load(Ordering::Relaxed);
        let requests_success = self.requests_success.load(Ordering::Relaxed);
        let requests_failed = self.requests_failed.load(Ordering::Relaxed);
        let cache_hits = self.cache_hits.load(Ordering::Relaxed);
        let cache_misses = self.cache_misses.load(Ordering::Relaxed);
        let bandwidth_usage = self.bandwidth_usage_bytes.load(Ordering::Relaxed);
        let bandwidth_capacity = self.bandwidth_capacity_bytes.load(Ordering::Relaxed);
        let origin_fetches = self.origin_fetches.load(Ordering::Relaxed);
        let active_connections = self.active_connections.load(Ordering::Relaxed);

        let p50 = self.calculate_percentile(50.0).await;
        let p95 = self.calculate_percentile(95.0).await;
        let p99 = self.calculate_percentile(99.0).await;

        let cache_hit_rate = if cache_hits + cache_misses > 0 {
            cache_hits as f64 / (cache_hits + cache_misses) as f64
        } else {
            0.0
        };

        let bandwidth_utilization = if bandwidth_capacity > 0 {
            bandwidth_usage as f64 / bandwidth_capacity as f64
        } else {
            0.0
        };

        format!(
            "# HELP cdn_nodes_online Number of online nodes\n\
             # TYPE cdn_nodes_online gauge\n\
             cdn_nodes_online {nodes_online}\n\
             # HELP cdn_nodes_total Total number of registered nodes\n\
             # TYPE cdn_nodes_total gauge\n\
             cdn_nodes_total {nodes_total}\n\
             # HELP cdn_requests_total Total number of requests\n\
             # TYPE cdn_requests_total counter\n\
             cdn_requests_total {requests_total}\n\
             # HELP cdn_requests_success_total Total number of successful requests\n\
             # TYPE cdn_requests_success_total counter\n\
             cdn_requests_success_total {requests_success}\n\
             # HELP cdn_requests_failed_total Total number of failed requests\n\
             # TYPE cdn_requests_failed_total counter\n\
             cdn_requests_failed_total {requests_failed}\n\
             # HELP cdn_cache_hits_total Total cache hits\n\
             # TYPE cdn_cache_hits_total counter\n\
             cdn_cache_hits_total {cache_hits}\n\
             # HELP cdn_cache_misses_total Total cache misses\n\
             # TYPE cdn_cache_misses_total counter\n\
             cdn_cache_misses_total {cache_misses}\n\
             # HELP cdn_cache_hit_rate Cache hit rate\n\
             # TYPE cdn_cache_hit_rate gauge\n\
             cdn_cache_hit_rate {cache_hit_rate}\n\
             # HELP cdn_scheduling_latency_p50 Scheduling latency P50 in microseconds\n\
             # TYPE cdn_scheduling_latency_p50 gauge\n\
             cdn_scheduling_latency_p50 {p50}\n\
             # HELP cdn_scheduling_latency_p95 Scheduling latency P95 in microseconds\n\
             # TYPE cdn_scheduling_latency_p95 gauge\n\
             cdn_scheduling_latency_p95 {p95}\n\
             # HELP cdn_scheduling_latency_p99 Scheduling latency P99 in microseconds\n\
             # TYPE cdn_scheduling_latency_p99 gauge\n\
             cdn_scheduling_latency_p99 {p99}\n\
             # HELP cdn_bandwidth_usage_bytes Total bandwidth usage in bytes\n\
             # TYPE cdn_bandwidth_usage_bytes counter\n\
             cdn_bandwidth_usage_bytes {bandwidth_usage}\n\
             # HELP cdn_bandwidth_capacity_bytes Total bandwidth capacity in bytes\n\
             # TYPE cdn_bandwidth_capacity_bytes gauge\n\
             cdn_bandwidth_capacity_bytes {bandwidth_capacity}\n\
             # HELP cdn_bandwidth_utilization Bandwidth utilization ratio\n\
             # TYPE cdn_bandwidth_utilization gauge\n\
             cdn_bandwidth_utilization {bandwidth_utilization}\n\
             # HELP cdn_origin_fetches_total Total origin fetches\n\
             # TYPE cdn_origin_fetches_total counter\n\
             cdn_origin_fetches_total {origin_fetches}\n\
             # HELP cdn_active_connections Number of active connections\n\
             # TYPE cdn_active_connections gauge\n\
             cdn_active_connections {active_connections}\n"
        )
    }
}

use crate::server::AppState;

pub async fn metrics_handler(State(state): State<AppState>) -> impl IntoResponse {
    (StatusCode::OK, state.metrics.render_prometheus().await)
}
