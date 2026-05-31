use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use tracing::{info, warn, error, debug, trace};
use uuid::Uuid;
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogDimensions {
    #[serde(flatten)]
    pub fields: HashMap<String, String>,
}

impl LogDimensions {
    pub fn new() -> Self {
        Self {
            fields: HashMap::new(),
        }
    }

    pub fn with_host(mut self, host: &str) -> Self {
        self.fields.insert("host".to_string(), host.to_string());
        self
    }

    pub fn with_region(mut self, region: &str) -> Self {
        self.fields.insert("region".to_string(), region.to_string());
        self
    }

    pub fn insert(&mut self, key: &str, value: &str) {
        self.fields.insert(key.to_string(), value.to_string());
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StructuredLog {
    pub timestamp: DateTime<Utc>,
    pub level: String,
    pub message: String,
    pub trace_id: String,
    pub span_id: Option<String>,
    pub dimensions: LogDimensions,
}

impl StructuredLog {
    pub fn new(level: &str, message: &str, trace_id: &str) -> Self {
        Self {
            timestamp: Utc::now(),
            level: level.to_string(),
            message: message.to_string(),
            trace_id: trace_id.to_string(),
            span_id: None,
            dimensions: LogDimensions::new(),
        }
    }

    pub fn with_dimensions(mut self, dimensions: LogDimensions) -> Self {
        self.dimensions = dimensions;
        self
    }

    pub fn with_span_id(mut self, span_id: &str) -> Self {
        self.span_id = Some(span_id.to_string());
        self
    }
}

#[derive(Debug, Clone)]
pub struct Logger {
    default_dimensions: LogDimensions,
}

impl Logger {
    pub fn new(default_dimensions: LogDimensions) -> Self {
        Self { default_dimensions }
    }

    pub fn init() {
        tracing_subscriber::fmt()
            .json()
            .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
            .with_target(true)
            .with_thread_ids(true)
            .init();
    }

    pub fn info(&self, message: &str, trace_id: Option<&str>) {
        let default_trace_id = Uuid::new_v4().to_string();
        let trace_id = trace_id.unwrap_or(&default_trace_id);
        info!(
            trace_id = %trace_id,
            message = %message,
            dimensions = ?self.default_dimensions.fields,
        );
    }

    pub fn warn(&self, message: &str, trace_id: Option<&str>) {
        let default_trace_id = Uuid::new_v4().to_string();
        let trace_id = trace_id.unwrap_or(&default_trace_id);
        warn!(
            trace_id = %trace_id,
            message = %message,
            dimensions = ?self.default_dimensions.fields,
        );
    }

    pub fn error(&self, message: &str, trace_id: Option<&str>, error: Option<&str>) {
        let default_trace_id = Uuid::new_v4().to_string();
        let trace_id = trace_id.unwrap_or(&default_trace_id);
        error!(
            trace_id = %trace_id,
            message = %message,
            error = %error.unwrap_or(""),
            dimensions = ?self.default_dimensions.fields,
        );
    }

    pub fn debug(&self, message: &str, trace_id: Option<&str>) {
        let default_trace_id = Uuid::new_v4().to_string();
        let trace_id = trace_id.unwrap_or(&default_trace_id);
        debug!(
            trace_id = %trace_id,
            message = %message,
            dimensions = ?self.default_dimensions.fields,
        );
    }

    pub fn trace(&self, message: &str, trace_id: Option<&str>) {
        let default_trace_id = Uuid::new_v4().to_string();
        let trace_id = trace_id.unwrap_or(&default_trace_id);
        trace!(
            trace_id = %trace_id,
            message = %message,
            dimensions = ?self.default_dimensions.fields,
        );
    }

    pub fn structured_log(&self, log: StructuredLog) -> String {
        serde_json::to_string(&log).unwrap_or_default()
    }

    pub fn generate_trace_id() -> String {
        Uuid::new_v4().to_string()
    }
}

pub fn init_logger() {
    Logger::init();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_log_dimensions() {
        let mut dims = LogDimensions::new()
            .with_host("node-1")
            .with_region("cn-east");
        dims.insert("service", "api-gateway");
        
        assert_eq!(dims.fields.get("host"), Some(&"node-1".to_string()));
        assert_eq!(dims.fields.get("region"), Some(&"cn-east".to_string()));
        assert_eq!(dims.fields.get("service"), Some(&"api-gateway".to_string()));
    }

    #[test]
    fn test_structured_log() {
        let log = StructuredLog::new("INFO", "Test message", "trace-123")
            .with_span_id("span-456");
        
        assert_eq!(log.level, "INFO");
        assert_eq!(log.message, "Test message");
        assert_eq!(log.trace_id, "trace-123");
        assert_eq!(log.span_id, Some("span-456".to_string()));
    }
}
