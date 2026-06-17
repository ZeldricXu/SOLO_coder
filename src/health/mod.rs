use std::sync::Arc;
use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::AppState;
use crate::ws::ConnectionManager;
use crate::presence::PresenceTracker;
use crate::ratelimit::RateLimiter;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum HealthStatus {
    Healthy,
    Degraded,
    Unhealthy,
}

impl HealthStatus {
    fn as_str(&self) -> &'static str {
        match self {
            HealthStatus::Healthy => "healthy",
            HealthStatus::Degraded => "degraded",
            HealthStatus::Unhealthy => "unhealthy",
        }
    }

    fn is_ready(&self) -> bool {
        !matches!(self, HealthStatus::Unhealthy)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthCheckResponse {
    pub status: HealthStatus,
    pub status_code: u16,
    pub version: String,
    pub uptime_seconds: i64,
    pub timestamp: String,
    pub components: HashMap<String, ComponentHealth>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ComponentHealth {
    pub status: HealthStatus,
    pub message: Option<String>,
    pub metrics: Option<serde_json::Value>,
}

type HashMap<K, V> = std::collections::HashMap<K, V>;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReadyCheckResponse {
    pub ready: bool,
    pub status: HealthStatus,
    pub message: String,
    pub connections: usize,
    pub max_connections: usize,
    pub connection_pressure: f64,
    pub retry_after_seconds: Option<u64>,
    pub timestamp: String,
}

#[derive(Debug, Clone)]
pub struct HealthChecker {
    db_pool: Option<sqlx::PgPool>,
    redis_pool: Option<bb8::Pool<bb8_redis::RedisConnectionManager>>,
    ws_manager: Option<ConnectionManager>,
    presence: Option<PresenceTracker>,
    rate_limiter: Option<RateLimiter>,
    app_state: Option<Arc<AppState>>,
    started_at: chrono::DateTime<chrono::Utc>,
    degraded_ws_threshold: f64,
    max_ws_connections: usize,
}

impl HealthChecker {
    pub fn new() -> Self {
        Self {
            db_pool: None,
            redis_pool: None,
            ws_manager: None,
            presence: None,
            rate_limiter: None,
            app_state: None,
            started_at: chrono::Utc::now(),
            degraded_ws_threshold: 0.8,
            max_ws_connections: 10000,
        }
    }

    pub fn with_db(mut self, pool: sqlx::PgPool) -> Self {
        self.db_pool = Some(pool);
        self
    }

    pub fn with_redis(mut self, pool: bb8::Pool<bb8_redis::RedisConnectionManager>) -> Self {
        self.redis_pool = Some(pool);
        self
    }

    pub fn with_ws(mut self, manager: ConnectionManager) -> Self {
        self.ws_manager = Some(manager);
        self
    }

    pub fn with_presence(mut self, tracker: PresenceTracker) -> Self {
        self.presence = Some(tracker);
        self
    }

    pub fn with_ratelimit(mut self, limiter: RateLimiter) -> Self {
        self.max_ws_connections = limiter.capacity();
        self.rate_limiter = Some(limiter);
        self
    }

    pub fn with_state(mut self, state: Arc<AppState>) -> Self {
        self.started_at = state.started_at;
        self.app_state = Some(state);
        self
    }

    pub async fn check_health(&self) -> HealthCheckResponse {
        let mut components: HashMap<String, ComponentHealth> = HashMap::new();
        let mut overall = HealthStatus::Healthy;

        components.insert("database".into(), self.check_database().await);
        components.insert("redis".into(), self.check_redis().await);
        components.insert("websocket".into(), self.check_websocket());
        components.insert("presence".into(), self.check_presence());
        components.insert("memory".into(), Self::check_memory());

        for comp in components.values() {
            match comp.status {
                HealthStatus::Unhealthy => overall = HealthStatus::Unhealthy,
                HealthStatus::Degraded if overall == HealthStatus::Healthy => {
                    overall = HealthStatus::Degraded
                }
                _ => {}
            }
        }

        let now = chrono::Utc::now();
        let uptime = (now - self.started_at).num_seconds();

        HealthCheckResponse {
            status: overall.clone(),
            status_code: match overall {
                HealthStatus::Healthy => 200,
                HealthStatus::Degraded => 200,
                HealthStatus::Unhealthy => 503,
            },
            version: env!("CARGO_PKG_VERSION").to_string(),
            uptime_seconds: uptime,
            timestamp: now.to_rfc3339(),
            components,
        }
    }

    pub async fn check_ready(&self) -> ReadyCheckResponse {
        let active = if let Some(state) = &self.app_state {
            state.active_connections()
        } else {
            self.ws_manager.as_ref().map(|m| m.total_connections()).unwrap_or(0)
        };

        let max = self.max_ws_connections;
        let pressure = active as f64 / max as f64;

        let mut status = HealthStatus::Healthy;
        let mut message = "Ready".to_string();
        let mut retry_after = None;

        if pressure >= 1.0 {
            status = HealthStatus::Unhealthy;
            message = "Service at capacity".to_string();
            retry_after = Some(30u64);
        } else if pressure >= self.degraded_ws_threshold {
            status = HealthStatus::Degraded;
            message = format!("High connection pressure: {:.1}%", pressure * 100.0);
        }

        let db_ok = self.check_database_simple().await;
        let redis_ok = self.check_redis_simple().await;

        if !db_ok || !redis_ok {
            status = HealthStatus::Unhealthy;
            message = if !db_ok { "Database unavailable".into() } else { "Redis unavailable".into() };
            retry_after = Some(10u64);
        }

        ReadyCheckResponse {
            ready: status.is_ready() && pressure < 1.0,
            status,
            message,
            connections: active,
            max_connections: max,
            connection_pressure: pressure,
            retry_after_seconds: retry_after,
            timestamp: chrono::Utc::now().to_rfc3339(),
        }
    }

    async fn check_database(&self) -> ComponentHealth {
        if let Some(pool) = &self.db_pool {
            match tokio::time::timeout(Duration::from_secs(3), pool.acquire()).await {
                Ok(Ok(_conn)) => {
                    let idle = pool.num_idle() as u32;
                    let size = pool.size();
                    ComponentHealth {
                        status: HealthStatus::Healthy,
                        message: None,
                        metrics: Some(serde_json::json!({
                            "connections_total": size,
                            "connections_idle": idle,
                            "connections_in_use": size.saturating_sub(idle),
                        })),
                    }
                }
                Ok(Err(e)) => ComponentHealth {
                    status: HealthStatus::Unhealthy,
                    message: Some(format!("Connection error: {}", e)),
                    metrics: None,
                },
                Err(_) => ComponentHealth {
                    status: HealthStatus::Degraded,
                    message: Some("Database ping timed out".into()),
                    metrics: None,
                },
            }
        } else {
            ComponentHealth {
                status: HealthStatus::Degraded,
                message: Some("Not configured".into()),
                metrics: None,
            }
        }
    }

    async fn check_database_simple(&self) -> bool {
        if let Some(pool) = &self.db_pool {
            tokio::time::timeout(Duration::from_secs(2), pool.acquire())
                .await
                .map(|r| r.is_ok())
                .unwrap_or(false)
        } else {
            true
        }
    }

    async fn check_redis(&self) -> ComponentHealth {
        if let Some(pool) = &self.redis_pool {
            match tokio::time::timeout(Duration::from_secs(3), pool.get()).await {
                Ok(Ok(_conn)) => {
                    ComponentHealth {
                        status: HealthStatus::Healthy,
                        message: Some("PONG".to_string()),
                        metrics: None,
                    }
                }
                Ok(Err(e)) => ComponentHealth {
                    status: HealthStatus::Unhealthy,
                    message: Some(format!("Redis error: {}", e)),
                    metrics: None,
                },
                Err(_) => ComponentHealth {
                    status: HealthStatus::Degraded,
                    message: Some("Redis ping timed out".into()),
                    metrics: None,
                },
            }
        } else {
            ComponentHealth {
                status: HealthStatus::Degraded,
                message: Some("Not configured".into()),
                metrics: None,
            }
        }
    }

    async fn check_redis_simple(&self) -> bool {
        if let Some(pool) = &self.redis_pool {
            tokio::time::timeout(Duration::from_secs(2), pool.get())
                .await
                .map(|r| r.is_ok())
                .unwrap_or(false)
        } else {
            true
        }
    }

    fn check_websocket(&self) -> ComponentHealth {
        if let Some(manager) = &self.ws_manager {
            let total = manager.total_connections();
            let rooms = manager.total_rooms();
            let pressure = total as f64 / self.max_ws_connections as f64;

            let status = if pressure >= 1.0 {
                HealthStatus::Unhealthy
            } else if pressure >= self.degraded_ws_threshold {
                HealthStatus::Degraded
            } else {
                HealthStatus::Healthy
            };

            ComponentHealth {
                status,
                message: if pressure >= self.degraded_ws_threshold {
                    Some(format!("High load: {:.1}% capacity", pressure * 100.0))
                } else {
                    None
                },
                metrics: Some(serde_json::json!({
                    "connections": total,
                    "rooms": rooms,
                    "max_connections": self.max_ws_connections,
                    "pressure_pct": (pressure * 100.0).round(),
                })),
            }
        } else {
            ComponentHealth {
                status: HealthStatus::Degraded,
                message: Some("Not configured".into()),
                metrics: None,
            }
        }
    }

    fn check_presence(&self) -> ComponentHealth {
        if let Some(presence) = &self.presence {
            let docs = presence.active_documents_count();
            let users = presence.total_online_users();
            ComponentHealth {
                status: HealthStatus::Healthy,
                message: None,
                metrics: Some(serde_json::json!({
                    "active_documents": docs,
                    "online_users": users,
                })),
            }
        } else {
            ComponentHealth {
                status: HealthStatus::Degraded,
                message: Some("Not configured".into()),
                metrics: None,
            }
        }
    }

    fn check_memory() -> ComponentHealth {
        use sysinfo::{MemoryRefreshKind, RefreshKind};
        let sysinfo = sysinfo::System::new_with_specifics(
            RefreshKind::new().with_memory(MemoryRefreshKind::everything())
        );

        let mem = sysinfo.total_memory();
        let used = sysinfo.used_memory();
        let pressure = if mem > 0 { used as f64 / mem as f64 } else { 0.0 };

        let status = if pressure >= 0.9 {
            HealthStatus::Unhealthy
        } else if pressure >= 0.75 {
            HealthStatus::Degraded
        } else {
            HealthStatus::Healthy
        };

        ComponentHealth {
            status,
            message: None,
            metrics: Some(serde_json::json!({
                "memory_total_mb": mem / 1024 / 1024,
                "memory_used_mb": used / 1024 / 1024,
                "memory_pressure_pct": (pressure * 100.0).round(),
            })),
        }
    }
}

impl Default for HealthChecker {
    fn default() -> Self {
        Self::new()
    }
}
