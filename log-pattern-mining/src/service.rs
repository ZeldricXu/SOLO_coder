use dashmap::DashMap;
use std::sync::Arc;
use tracing::{debug, info};

use common::log::LogEvent;
use common::pattern::{LogPattern, PatternChangeEvent};

use crate::drain::Drain;

pub struct PatternMiningService {
    drains: Arc<DashMap<String, Drain>>,
    change_events: Arc<DashMap<String, Vec<PatternChangeEvent>>>,
}

impl PatternMiningService {
    pub fn new() -> Self {
        Self {
            drains: Arc::new(DashMap::new()),
            change_events: Arc::new(DashMap::new()),
        }
    }

    pub fn process_log(&self, service: &str, log: &LogEvent) -> Option<PatternChangeEvent> {
        let drain = self.drains
            .entry(service.to_string())
            .or_insert_with(Drain::default);

        let event = drain.process_log(&log.message);

        if let Some(ref e) = event {
            debug!(
                "Pattern change detected for service {}: {:?}",
                service, e.change_type
            );

            self.change_events
                .entry(service.to_string())
                .or_default()
                .push(e.clone());
        }

        event
    }

    pub fn process_log_batch(&self, service: &str, logs: &[LogEvent]) -> Vec<PatternChangeEvent> {
        let mut events = Vec::new();

        for log in logs {
            if let Some(event) = self.process_log(service, log) {
                events.push(event);
            }
        }

        events
    }

    pub fn get_patterns(&self, service: &str) -> Vec<LogPattern> {
        self.drains
            .get(service)
            .map(|d| d.get_all_patterns())
            .unwrap_or_default()
    }

    pub fn get_all_patterns(&self) -> std::collections::HashMap<String, Vec<LogPattern>> {
        let mut result = std::collections::HashMap::new();

        for entry in self.drains.iter() {
            result.insert(entry.key().clone(), entry.value().get_all_patterns());
        }

        result
    }

    pub fn get_pattern_count(&self, service: &str) -> usize {
        self.drains
            .get(service)
            .map(|d| d.get_pattern_count())
            .unwrap_or(0)
    }

    pub fn get_change_events(&self, service: &str, limit: usize) -> Vec<PatternChangeEvent> {
        self.change_events
            .get(service)
            .map(|events| {
                events
                    .iter()
                    .rev()
                    .take(limit)
                    .cloned()
                    .collect()
            })
            .unwrap_or_default()
    }

    pub fn get_all_change_events(&self, limit: usize) -> std::collections::HashMap<String, Vec<PatternChangeEvent>> {
        let mut result = std::collections::HashMap::new();

        for entry in self.change_events.iter() {
            let events: Vec<_> = entry.value()
                .iter()
                .rev()
                .take(limit)
                .cloned()
                .collect();
            result.insert(entry.key().clone(), events);
        }

        result
    }

    pub fn cleanup_old_events(&self, max_age: chrono::Duration) {
        let now = chrono::Utc::now();

        for mut entry in self.change_events.iter_mut() {
            entry.retain(|e| now - e.timestamp <= max_age);
        }
    }
}

impl Default for PatternMiningService {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::log::{LogLevel, LogEvent};

    fn create_test_log(message: &str) -> LogEvent {
        LogEvent {
            id: uuid::Uuid::new_v4(),
            timestamp: chrono::Utc::now(),
            hostname: "test-host".to_string(),
            service: "test-service".to_string(),
            level: LogLevel::Info,
            message: message.to_string(),
            fields: std::collections::HashMap::new(),
            source_file: "test.log".to_string(),
            raw: None,
        }
    }

    #[test]
    fn test_service_pattern_mining() {
        let service = PatternMiningService::new();

        let log1 = create_test_log("User 123 logged in");
        let log2 = create_test_log("User 456 logged in");
        let log3 = create_test_log("Database connection error");

        let result1 = service.process_log("test", &log1);
        let result2 = service.process_log("test", &log2);
        let result3 = service.process_log("test", &log3);

        assert!(result1.is_some());
        assert!(result2.is_none());
        assert!(result3.is_some());
        assert_eq!(service.get_pattern_count("test"), 2);
    }
}
