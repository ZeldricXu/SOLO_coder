use chrono::{DateTime, Utc};
use std::collections::HashMap;
use uuid::Uuid;
use tracing::Span;

#[derive(Debug, Clone)]
pub struct RequestContext {
    pub trace_id: String,
    pub start_time: DateTime<Utc>,
    pub attributes: HashMap<String, String>,
    pub span: Option<Span>,
}

impl RequestContext {
    pub fn new() -> Self {
        Self::with_trace_id(Uuid::new_v4().to_string())
    }

    pub fn with_trace_id(trace_id: String) -> Self {
        Self {
            trace_id,
            start_time: Utc::now(),
            attributes: HashMap::new(),
            span: None,
        }
    }

    pub fn elapsed_ms(&self) -> i64 {
        (Utc::now() - self.start_time).num_milliseconds()
    }

    pub fn with_attribute(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.attributes.insert(key.into(), value.into());
        self
    }

    pub fn cleanup(&self) {
        tracing::info!(trace_id = %self.trace_id, elapsed_ms = %self.elapsed_ms(), "context_cleanup");
    }
}

impl Default for RequestContext {
    fn default() -> Self {
        Self::new()
    }
}
