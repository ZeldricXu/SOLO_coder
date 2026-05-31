use std::sync::Arc;
use std::time::Instant;
use uuid::Uuid;
use tokio::sync::Mutex;
use crate::models::{MetricsCollector, Snapshot};

#[derive(Debug, Clone)]
pub struct ExecutionContext {
    pub trace_id: String,
    pub start_time: Instant,
    pub namespace: String,
    metrics: Arc<Mutex<MetricsCollector>>,
}

impl ExecutionContext {
    pub fn new(namespace: impl Into<String>) -> Self {
        Self {
            trace_id: Uuid::new_v4().simple().to_string(),
            start_time: Instant::now(),
            namespace: namespace.into(),
            metrics: Arc::new(Mutex::new(MetricsCollector::new())),
        }
    }

    pub fn with_trace_id(namespace: impl Into<String>, trace_id: impl Into<String>) -> Self {
        Self {
            trace_id: trace_id.into(),
            start_time: Instant::now(),
            namespace: namespace.into(),
            metrics: Arc::new(Mutex::new(MetricsCollector::new())),
        }
    }

    pub async fn record_event(&self, latency_ms: f64) {
        self.metrics.lock().await.record_event(latency_ms);
    }

    pub async fn record_error(&self) {
        self.metrics.lock().await.record_error();
    }

    pub async fn snapshot(&self) -> Snapshot {
        self.metrics.lock().await.snapshot()
    }

    pub async fn reset_metrics(&self) {
        self.metrics.lock().await.reset();
    }

    pub fn elapsed_ms(&self) -> f64 {
        self.start_time.elapsed().as_secs_f64() * 1000.0
    }
}

pub struct ContextGuard {
    context: ExecutionContext,
}

impl ContextGuard {
    pub fn new(ctx: ExecutionContext) -> Self {
        Self { context: ctx }
    }

    pub fn context(&self) -> &ExecutionContext {
        &self.context
    }
}

impl Drop for ContextGuard {
    fn drop(&mut self) {
        tracing::debug!(
            trace_id = %self.context.trace_id,
            elapsed_ms = %self.context.elapsed_ms(),
            "Context cleanup"
        );
    }
}
