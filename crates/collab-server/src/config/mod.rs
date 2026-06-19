use std::time::Duration;
use serde::{Deserialize, Serialize};

pub use collab_auth::AuthConfig;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub database: DatabaseConfig,
    pub redis: RedisConfig,
    pub auth: AuthConfig,
    pub snapshot: SnapshotConfig,
    pub ratelimit: RateLimitConfig,
    pub websocket: WebSocketConfig,
    pub metrics: MetricsConfig,
    pub storage: StorageConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
    pub workers: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DatabaseConfig {
    pub url: String,
    pub max_connections: u32,
    pub min_connections: u32,
    pub acquire_timeout_secs: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RedisConfig {
    pub url: String,
    pub max_connections: u32,
    pub pubsub_channel_prefix: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SnapshotConfig {
    pub interval_secs: u64,
    pub min_ops_before_snapshot: u64,
    pub storage_backend: StorageBackend,
    pub local_dir: String,
    pub s3_bucket: Option<String>,
    pub s3_region: Option<String>,
    pub s3_prefix: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum StorageBackend {
    Local,
    S3,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RateLimitConfig {
    pub per_connection_ops_per_min: u64,
    pub per_document_ops_per_sec: u64,
    pub per_connection_burst: u64,
    pub per_document_burst: u64,
    pub max_ws_connections: usize,
    pub cleanup_interval_secs: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WebSocketConfig {
    pub heartbeat_interval_secs: u64,
    pub client_timeout_secs: u64,
    pub max_message_size: usize,
    pub max_frame_size: usize,
    pub session_resume_window_secs: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricsConfig {
    pub enabled: bool,
    pub endpoint: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageConfig {
    pub snapshot_compression: bool,
    pub max_snapshot_history: u32,
    pub oplog_retention_days: u32,
}

impl Default for AppConfig {
    fn default() -> Self {
        AppConfig {
            server: ServerConfig {
                host: "0.0.0.0".to_string(),
                port: 3000,
                workers: num_cpus::get(),
            },
            database: DatabaseConfig {
                url: "postgres://postgres:postgres@localhost:5432/collab".to_string(),
                max_connections: 50,
                min_connections: 5,
                acquire_timeout_secs: 10,
            },
            redis: RedisConfig {
                url: "redis://localhost:6379/0".to_string(),
                max_connections: 30,
                pubsub_channel_prefix: "collab:".to_string(),
            },
            auth: AuthConfig {
                jwt_secret: "change-me-in-production-please-change".to_string(),
                jwt_issuer: "collab-engine".to_string(),
                jwt_audience: "collab-engine".to_string(),
                jwt_expiry_secs: 86400,
                share_token_expiry_secs: 3600 * 24 * 7,
            },
            snapshot: SnapshotConfig {
                interval_secs: 60,
                min_ops_before_snapshot: 100,
                storage_backend: StorageBackend::Local,
                local_dir: "./data/snapshots".to_string(),
                s3_bucket: None,
                s3_region: None,
                s3_prefix: None,
            },
            ratelimit: RateLimitConfig {
                per_connection_ops_per_min: 6000,
                per_document_ops_per_sec: 500,
                per_connection_burst: 500,
                per_document_burst: 200,
                max_ws_connections: 10000,
                cleanup_interval_secs: 300,
            },
            websocket: WebSocketConfig {
                heartbeat_interval_secs: 15,
                client_timeout_secs: 60,
                max_message_size: 1 << 20,
                max_frame_size: 1 << 16,
                session_resume_window_secs: 300,
            },
            metrics: MetricsConfig {
                enabled: true,
                endpoint: "/metrics".to_string(),
            },
            storage: StorageConfig {
                snapshot_compression: true,
                max_snapshot_history: 100,
                oplog_retention_days: 90,
            },
        }
    }
}

impl AppConfig {
    pub fn from_env() -> Self {
        let mut config = Self::default();
        if let Ok(url) = std::env::var("DATABASE_URL") {
            config.database.url = url;
        }
        if let Ok(url) = std::env::var("REDIS_URL") {
            config.redis.url = url;
        }
        if let Ok(secret) = std::env::var("JWT_SECRET") {
            config.auth.jwt_secret = secret;
        }
        if let Some(port) = std::env::var("PORT").ok().and_then(|s| s.parse::<u16>().ok()) {
            config.server.port = port;
        }
        if let Ok(host) = std::env::var("HOST") {
            config.server.host = host;
        }
        config
    }

    pub fn heartbeat_interval(&self) -> Duration {
        Duration::from_secs(self.websocket.heartbeat_interval_secs)
    }

    pub fn client_timeout(&self) -> Duration {
        Duration::from_secs(self.websocket.client_timeout_secs)
    }

    pub fn snapshot_interval(&self) -> Duration {
        Duration::from_secs(self.snapshot.interval_secs)
    }
}
