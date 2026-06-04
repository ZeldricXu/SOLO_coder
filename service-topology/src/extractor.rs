use crate::topology::TopologyBuilder;
use common::log::LogEvent;
use regex::Regex;
use std::sync::Arc;
use tracing::debug;

pub struct TraceExtractor {
    topology_builder: Arc<TopologyBuilder>,
    request_id_patterns: Vec<Regex>,
    latency_patterns: Vec<Regex>,
    service_call_patterns: Vec<Regex>,
}

impl TraceExtractor {
    pub fn new(topology_builder: Arc<TopologyBuilder>) -> Self {
        Self {
            topology_builder,
            request_id_patterns: vec![
                Regex::new(r"request_id[=:]\s*([a-zA-Z0-9-]+)").unwrap(),
                Regex::new(r"trace_id[=:]\s*([a-zA-Z0-9-]+)").unwrap(),
                Regex::new(r"X-Request-ID[=:]\s*([a-zA-Z0-9-]+)").unwrap(),
            ],
            latency_patterns: vec![
                Regex::new(r"latency[=:]\s*(\d+\.?\d*)\s*ms").unwrap(),
                Regex::new(r"duration[=:]\s*(\d+\.?\d*)\s*ms").unwrap(),
                Regex::new(r"took[=:]\s*(\d+\.?\d*)\s*ms").unwrap(),
                Regex::new(r"(\d+\.?\d*)\s*ms").unwrap(),
            ],
            service_call_patterns: vec![
                Regex::new(r"calling\s+(\w+-\w+|\w+)").unwrap(),
                Regex::new(r"request to\s+(\w+-\w+|\w+)").unwrap(),
                Regex::new(r"invoking\s+(\w+-\w+|\w+)").unwrap(),
            ],
        }
    }

    pub fn extract_from_log(&self, log: &LogEvent) -> Option<ExtractedTrace> {
        let request_id = self.extract_request_id(&log.message);
        let latency = self.extract_latency(&log.message);
        let target_service = self.extract_target_service(&log.message);
        let is_error = self.is_error_log(log);

        debug!(
            "Extracted from log: request_id={:?}, latency={:?}, target={:?}, error={}",
            request_id, latency, target_service, is_error
        );

        if let Some(target) = target_service {
            self.topology_builder.add_service(&log.service, &log.hostname);
            self.topology_builder.add_service(&target, "unknown");

            if let Some(lat) = latency {
                self.topology_builder.record_call(
                    &log.service,
                    &target,
                    lat,
                    is_error,
                );
            }
        }

        if request_id.is_some() || latency.is_some() || target_service.is_some() {
            Some(ExtractedTrace {
                request_id,
                latency_ms: latency,
                source_service: Some(log.service.clone()),
                target_service,
                is_error,
                timestamp: log.timestamp,
            })
        } else {
            None
        }
    }

    fn extract_request_id(&self, message: &str) -> Option<String> {
        for pattern in &self.request_id_patterns {
            if let Some(caps) = pattern.captures(message) {
                return Some(caps[1].to_string());
            }
        }
        None
    }

    fn extract_latency(&self, message: &str) -> Option<f64> {
        for pattern in &self.latency_patterns {
            if let Some(caps) = pattern.captures(message) {
                if let Ok(latency) = caps[1].parse::<f64>() {
                    return Some(latency);
                }
            }
        }
        None
    }

    fn extract_target_service(&self, message: &str) -> Option<String> {
        for pattern in &self.service_call_patterns {
            if let Some(caps) = pattern.captures(message) {
                return Some(caps[1].to_string());
            }
        }
        None
    }

    fn is_error_log(&self, log: &LogEvent) -> bool {
        use common::log::LogLevel;
        matches!(log.level, LogLevel::Error | LogLevel::Fatal)
            || log.message.to_lowercase().contains("error")
            || log.message.to_lowercase().contains("exception")
            || log.message.to_lowercase().contains("failed")
    }
}

#[derive(Debug, Clone)]
pub struct ExtractedTrace {
    pub request_id: Option<String>,
    pub latency_ms: Option<f64>,
    pub source_service: Option<String>,
    pub target_service: Option<String>,
    pub is_error: bool,
    pub timestamp: chrono::DateTime<chrono::Utc>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::log::{LogLevel, LogEvent};

    fn create_test_log(message: &str, level: LogLevel) -> LogEvent {
        LogEvent {
            id: uuid::Uuid::new_v4(),
            timestamp: chrono::Utc::now(),
            hostname: "test-host".to_string(),
            service: "api-gateway".to_string(),
            level,
            message: message.to_string(),
            fields: std::collections::HashMap::new(),
            source_file: "test.log".to_string(),
            raw: None,
        }
    }

    #[test]
    fn test_extract_latency() {
        let builder = Arc::new(TopologyBuilder::new());
        let extractor = TraceExtractor::new(builder);

        let log = create_test_log("Request processed in 150ms", LogLevel::Info);
        let trace = extractor.extract_from_log(&log);

        assert!(trace.is_some());
        assert_eq!(trace.unwrap().latency_ms, Some(150.0));
    }

    #[test]
    fn test_extract_request_id() {
        let builder = Arc::new(TopologyBuilder::new());
        let extractor = TraceExtractor::new(builder);

        let log = create_test_log(
            "Processing request request_id=abc-123-def",
            LogLevel::Info,
        );
        let trace = extractor.extract_from_log(&log);

        assert!(trace.is_some());
        assert_eq!(trace.unwrap().request_id, Some("abc-123-def".to_string()));
    }

    #[test]
    fn test_error_detection() {
        let builder = Arc::new(TopologyBuilder::new());
        let extractor = TraceExtractor::new(builder);

        let log = create_test_log("Failed to process request", LogLevel::Error);
        let trace = extractor.extract_from_log(&log);

        assert!(trace.is_some());
        assert!(trace.unwrap().is_error);
    }
}
