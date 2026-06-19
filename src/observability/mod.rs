use crate::alerter::Alerter;
use crate::collector::ring_buffer::RingBuffer;
use crate::config::ConfigHandle;
use crate::detector::RuleEngine;
use axum::{
    extract::State,
    http::StatusCode,
    response::IntoResponse,
    routing::get,
    Router,
};
use chrono::Utc;
use metrics_exporter_prometheus::{PrometheusBuilder, PrometheusHandle};
use parking_lot::Mutex;
use std::sync::Arc;
use tracing::{error, info};

pub struct AppState {
    pub config: ConfigHandle,
    pub ring_buffer: Arc<RingBuffer>,
    pub rule_engine: RuleEngine,
    pub alerter: Alerter,
    pub start_time: chrono::DateTime<Utc>,
    pub prometheus: PrometheusHandle,
    pub processed_count: Mutex<u64>,
}

pub struct ObservabilityServer {
    handle: Option<tokio::task::JoinHandle<()>>,
}

impl ObservabilityServer {
    pub fn new() -> Self {
        Self { handle: None }
    }

    pub async fn start(
        &mut self,
        config: ConfigHandle,
        ring_buffer: Arc<RingBuffer>,
        rule_engine: RuleEngine,
        alerter: Alerter,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let (host, port) = {
            let cfg = config.read().await;
            (
                cfg.observability.metrics_host.clone(),
                cfg.observability.metrics_port,
            )
        };

        let recorder = PrometheusBuilder::new().install_recorder()?;

        metrics::describe_counter!(
            "logforge_logs_processed_total",
            "Total number of log records processed"
        );
        metrics::describe_counter!(
            "logforge_alerts_fired_total",
            "Total number of alerts fired"
        );
        metrics::describe_gauge!(
            "logforge_ring_buffer_usage",
            "Current number of items in the ring buffer"
        );
        metrics::describe_gauge!(
            "logforge_ring_buffer_capacity",
            "Capacity of the ring buffer"
        );
        metrics::describe_gauge!(
            "logforge_uptime_seconds",
            "Process uptime in seconds"
        );

        let state = Arc::new(AppState {
            config,
            ring_buffer,
            rule_engine,
            alerter,
            start_time: Utc::now(),
            prometheus: recorder,
            processed_count: Mutex::new(0),
        });

        let app = Router::new()
            .route("/health", get(health_handler))
            .route("/metrics", get(metrics_handler))
            .with_state(state.clone());

        let addr = format!("{}:{}", host, port);
        info!("Observability HTTP server starting on {}", addr);

        let server = axum::Server::bind(&addr.parse()?).serve(app.into_make_service());

        let handle = tokio::spawn(async move {
            if let Err(e) = server.await {
                error!("Observability HTTP server error: {}", e);
            }
        });
        self.handle = Some(handle);
        Ok(())
    }
}

impl Default for ObservabilityServer {
    fn default() -> Self {
        Self::new()
    }
}

async fn health_handler(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let now = Utc::now();
    let uptime = (now - state.start_time).num_seconds();
    let rb_len = state.ring_buffer.len();
    let rb_cap = state.ring_buffer.capacity();
    let rb_usage_pct = if rb_cap > 0 {
        (rb_len as f64 / rb_cap as f64) * 100.0
    } else {
        0.0
    };
    let (channels, dedup) = state.alerter.stats();
    let triggered = state.rule_engine.triggered_count();
    let rule_hit_total: u64 = triggered.values().sum();

    let status = if rb_usage_pct > 95.0 {
        "DEGRADED"
    } else {
        "HEALTHY"
    };

    let json_body = serde_json::json!({
        "status": status,
        "timestamp": now.to_rfc3339(),
        "uptime_seconds": uptime,
        "ring_buffer": {
            "length": rb_len,
            "capacity": rb_cap,
            "usage_percent": format!("{:.2}%", rb_usage_pct),
        },
        "alerter": {
            "configured_channels": channels,
            "active_dedup_keys": dedup,
        },
        "rules": {
            "total_loaded": triggered.len(),
            "recent_hits_total": rule_hit_total,
        },
    });

    let status_code = if status == "HEALTHY" {
        StatusCode::OK
    } else {
        StatusCode::SERVICE_UNAVAILABLE
    };

    (status_code, axum::Json(json_body))
}

async fn metrics_handler(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let now = Utc::now();
    let uptime = (now - state.start_time).num_seconds() as f64;

    metrics::gauge!("logforge_uptime_seconds", uptime);
    metrics::gauge!(
        "logforge_ring_buffer_usage",
        state.ring_buffer.len() as f64
    );
    metrics::gauge!(
        "logforge_ring_buffer_capacity",
        state.ring_buffer.capacity() as f64
    );

    let rb_len = state.ring_buffer.len();
    let rb_cap = state.ring_buffer.capacity();
    let rb_usage = if rb_cap > 0 {
        rb_len as f64 / rb_cap as f64
    } else {
        0.0
    };
    metrics::gauge!("logforge_ring_buffer_usage_ratio", rb_usage);

    let (channels, dedup) = state.alerter.stats();
    metrics::gauge!("logforge_alerter_channels", channels as f64);
    metrics::gauge!("logforge_alerter_dedup_keys", dedup as f64);

    let triggered = state.rule_engine.triggered_count();
    for (rule_id, count) in triggered {
        metrics::counter!("logforge_rule_hits_total", count as u64, "rule_id" => rule_id);
    }

    let processed = *state.processed_count.lock() as u64;
    metrics::counter!("logforge_logs_processed_total", processed);

    let body = state.prometheus.render();
    let custom = format!(
        "{}\n\
        # HELP logforge_goroutines Number of goroutines (approximate via tokio metrics)\n\
        # TYPE logforge_goroutines gauge\n\
        logforge_goroutines {}\n\
        # HELP logforge_rss_bytes Resident set size in bytes (approximate)\n\
        # TYPE logforge_rss_bytes gauge\n\
        logforge_rss_bytes {}\n",
        body,
        num_threads_approx(),
        rss_bytes(),
    );
    (StatusCode::OK, custom)
}

fn num_threads_approx() -> f64 {
    let count = match std::fs::read_dir("/proc/self/task") {
        Ok(entries) => entries.count(),
        Err(_) => {
            use std::sync::atomic::{AtomicU64, Ordering};
            static THREADS: AtomicU64 = AtomicU64::new(0);
            THREADS.fetch_add(1, Ordering::Relaxed) as usize + 1
        }
    };
    count as f64
}

fn rss_bytes() -> f64 {
    match std::fs::read_to_string("/proc/self/status") {
        Ok(content) => {
            for line in content.lines() {
                if line.starts_with("VmRSS:") {
                    let parts: Vec<&str> = line.split_whitespace().collect();
                    if parts.len() >= 2 {
                        if let Ok(kb) = parts[1].parse::<f64>() {
                            return kb * 1024.0;
                        }
                    }
                }
            }
            0.0
        }
        Err(_) => {
            let val = procinfo_val();
            val
        }
    }
}

fn procinfo_val() -> f64 {
    use std::process::Command;
    if let Ok(out) = Command::new("ps")
        .args(&["-o", "rss=", "-p"])
        .arg(&std::process::id().to_string())
        .output()
    {
        if let Ok(s) = String::from_utf8(out.stdout) {
            if let Ok(kb) = s.trim().parse::<f64>() {
                return kb * 1024.0;
            }
        }
    }
    0.0
}
