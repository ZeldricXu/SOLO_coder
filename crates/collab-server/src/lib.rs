pub mod error;
pub mod config;
pub mod ws;
pub mod presence;
pub mod storage;
pub mod snapshot;
pub mod ratelimit;
pub mod health;
pub mod broadcast;

pub use error::{AppError, AppResult};
pub use config::{AppConfig, StorageBackend};

use crate::broadcast::StreamPublisher;
use crate::storage::BatchingOplogWriter;
use collab_auth::AuthService;
use metrics::{counter, gauge, histogram};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

pub struct AppState {
    pub config: AppConfig,
    pub db_pool: sqlx::PgPool,
    pub redis_pool: bb8::Pool<bb8_redis::RedisConnectionManager>,
    pub ws_manager: ws::ConnectionManager,
    pub presence_tracker: presence::PresenceTracker,
    pub rate_limiter: ratelimit::RateLimiter,
    pub snapshot_service: snapshot::SnapshotService,
    pub broadcaster: Arc<StreamPublisher>,
    pub auth_service: Arc<AuthService>,
    pub oplog_writer: Arc<BatchingOplogWriter>,
    pub active_connections: AtomicUsize,
    pub started_at: chrono::DateTime<chrono::Utc>,
}

impl std::fmt::Debug for AppState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AppState")
            .field("config", &self.config)
            .field("active_connections", &self.active_connections)
            .field("started_at", &self.started_at)
            .finish_non_exhaustive()
    }
}

impl AppState {
    pub fn increment_connections(&self) -> usize {
        let prev = self.active_connections.fetch_add(1, Ordering::SeqCst);
        gauge!("collab_active_connections").set((prev + 1) as f64);
        prev + 1
    }

    pub fn decrement_connections(&self) -> usize {
        let prev = self.active_connections.fetch_sub(1, Ordering::SeqCst);
        let new = prev.saturating_sub(1);
        gauge!("collab_active_connections").set(new as f64);
        new
    }

    pub fn active_connections(&self) -> usize {
        self.active_connections.load(Ordering::SeqCst)
    }

    pub fn can_accept_connection(&self) -> bool {
        self.active_connections() < self.config.ratelimit.max_ws_connections
    }

    pub fn track_op(&self, op_type: &str) {
        counter!("collab_ops_total", "type" => op_type.to_string()).increment(1);
    }
}

pub fn init_metrics() {
    let metrics_port = std::env::var("METRICS_PORT")
        .ok()
        .and_then(|s| s.parse::<u16>().ok())
        .unwrap_or(9090);
    let recorder = metrics_exporter_prometheus::PrometheusBuilder::new()
        .with_http_listener(([0, 0, 0, 0], metrics_port))
        .set_buckets(&[0.0001, 0.0005, 0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0])
        .unwrap()
        .install_recorder()
        .unwrap();

    gauge!("collab_active_connections").set(0f64);
    gauge!("collab_active_documents").set(0f64);
    counter!("collab_ops_total", "type" => "insert").absolute(0);
    counter!("collab_ops_total", "type" => "delete").absolute(0);
    histogram!("collab_op_latency_seconds");
}
