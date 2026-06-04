use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Mutex;
use tokio::time::Instant;
use tracing::{debug, info, warn};

#[derive(Debug, Clone)]
pub struct OperatorMetrics {
    pub name: String,
    pub received_count: u64,
    pub sent_count: u64,
    pub error_count: u64,
    pub channel_backlog: usize,
    pub avg_processing_time_ms: f64,
    pub last_updated: Instant,
}

impl OperatorMetrics {
    pub fn new(name: String) -> Self {
        Self {
            name,
            received_count: 0,
            sent_count: 0,
            error_count: 0,
            channel_backlog: 0,
            avg_processing_time_ms: 0.0,
            last_updated: Instant::now(),
        }
    }

    pub fn record_processing_time(&mut self, duration_ms: f64) {
        self.avg_processing_time_ms = (self.avg_processing_time_ms * self.received_count as f64 + duration_ms)
            / (self.received_count + 1) as f64;
    }

    pub fn throughput_per_sec(&self) -> f64 {
        let elapsed = self.last_updated.elapsed().as_secs_f64();
        if elapsed < 1.0 {
            0.0
        } else {
            self.sent_count as f64 / elapsed
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub enum AlertLevel {
    Info,
    Warning,
    Critical,
}

#[derive(Debug, Clone)]
pub struct SupervisorAlert {
    pub level: AlertLevel,
    pub operator_name: String,
    pub message: String,
    pub timestamp: Instant,
}

pub struct PipelineSupervisor {
    metrics: Arc<Mutex<HashMap<String, OperatorMetrics>>>,
    alerts: Arc<Mutex<Vec<SupervisorAlert>>>,
    backlog_threshold: usize,
    latency_threshold_ms: f64,
    error_rate_threshold: f64,
    check_interval: Duration,
}

impl PipelineSupervisor {
    pub fn new() -> Self {
        Self::with_config(1000, 1000.0, 0.05, Duration::from_secs(10))
    }

    pub fn with_config(
        backlog_threshold: usize,
        latency_threshold_ms: f64,
        error_rate_threshold: f64,
        check_interval: Duration,
    ) -> Self {
        Self {
            metrics: Arc::new(Mutex::new(HashMap::new())),
            alerts: Arc::new(Mutex::new(Vec::new())),
            backlog_threshold,
            latency_threshold_ms,
            error_rate_threshold,
            check_interval,
        }
    }

    pub async fn register_operator(&self, name: String) {
        let mut metrics = self.metrics.lock().await;
        metrics.insert(name.clone(), OperatorMetrics::new(name));
    }

    pub async fn record_received(&self, operator_name: &str) {
        let mut metrics = self.metrics.lock().await;
        if let Some(m) = metrics.get_mut(operator_name) {
            m.received_count += 1;
            m.last_updated = Instant::now();
        }
    }

    pub async fn record_sent(&self, operator_name: &str, count: usize, backlog: usize) {
        let mut metrics = self.metrics.lock().await;
        if let Some(m) = metrics.get_mut(operator_name) {
            m.sent_count += count as u64;
            m.channel_backlog = backlog;
            m.last_updated = Instant::now();
        }
    }

    pub async fn record_error(&self, operator_name: &str) {
        let mut metrics = self.metrics.lock().await;
        if let Some(m) = metrics.get_mut(operator_name) {
            m.error_count += 1;
            m.last_updated = Instant::now();
        }
    }

    pub async fn record_processing_time(&self, operator_name: &str, duration_ms: f64) {
        let mut metrics = self.metrics.lock().await;
        if let Some(m) = metrics.get_mut(operator_name) {
            m.record_processing_time(duration_ms);
        }
    }

    pub async fn check_and_alert(&self) -> Vec<SupervisorAlert> {
        let mut new_alerts = Vec::new();
        let metrics = self.metrics.lock().await;

        for (name, m) in metrics.iter() {
            if m.channel_backlog >= self.backlog_threshold {
                new_alerts.push(SupervisorAlert {
                    level: AlertLevel::Critical,
                    operator_name: name.clone(),
                    message: format!(
                        "Channel backlog {} exceeds threshold {}",
                        m.channel_backlog, self.backlog_threshold
                    ),
                    timestamp: Instant::now(),
                });
                warn!(
                    "Supervisor ALERT: Operator {} backlog={} exceeds threshold",
                    name, m.channel_backlog
                );
            }

            if m.avg_processing_time_ms > self.latency_threshold_ms && m.received_count > 10 {
                new_alerts.push(SupervisorAlert {
                    level: AlertLevel::Warning,
                    operator_name: name.clone(),
                    message: format!(
                        "Average processing time {:.2}ms exceeds threshold {:.2}ms",
                        m.avg_processing_time_ms, self.latency_threshold_ms
                    ),
                    timestamp: Instant::now(),
                });
                warn!(
                    "Supervisor ALERT: Operator {} latency={:.2}ms exceeds threshold",
                    name, m.avg_processing_time_ms
                );
            }

            let error_rate = if m.received_count > 0 {
                m.error_count as f64 / m.received_count as f64
            } else {
                0.0
            };
            if error_rate > self.error_rate_threshold && m.received_count > 10 {
                new_alerts.push(SupervisorAlert {
                    level: AlertLevel::Critical,
                    operator_name: name.clone(),
                    message: format!(
                        "Error rate {:.2}% exceeds threshold {:.2}%",
                        error_rate * 100.0,
                        self.error_rate_threshold * 100.0
                    ),
                    timestamp: Instant::now(),
                });
                warn!(
                    "Supervisor ALERT: Operator {} error rate={:.2}% exceeds threshold",
                    name,
                    error_rate * 100.0
                );
            }
        }

        if !new_alerts.is_empty() {
            let mut alerts = self.alerts.lock().await;
            alerts.extend(new_alerts.clone());
            let alert_count = alerts.len();
            if alert_count > 1000 {
                alerts.drain(0..alert_count - 1000);
            }
        }

        new_alerts
    }

    pub async fn get_metrics(&self) -> HashMap<String, OperatorMetrics> {
        self.metrics.lock().await.clone()
    }

    pub async fn get_alerts(&self) -> Vec<SupervisorAlert> {
        self.alerts.lock().await.clone()
    }

    pub async fn run_monitor_loop(self: Arc<Self>) {
        info!("Pipeline supervisor monitor started");
        let mut interval = tokio::time::interval(self.check_interval);

        loop {
            interval.tick().await;
            self.check_and_alert().await;

            let metrics = self.get_metrics().await;
            for (name, m) in metrics.iter() {
                debug!(
                    "Supervisor stats: {} received={}, sent={}, errors={}, backlog={}, latency={:.2}ms",
                    name, m.received_count, m.sent_count, m.error_count, m.channel_backlog, m.avg_processing_time_ms
                );
            }
        }
    }
}

impl Default for PipelineSupervisor {
    fn default() -> Self {
        Self::new()
    }
}
