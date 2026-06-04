use dashmap::DashMap;
use std::sync::Arc;
use tracing::info;

use crate::extractor::TraceExtractor;
use crate::topology::TopologyBuilder;
use common::log::LogEvent;
use common::topology::ServiceTopology;

pub struct TopologyService {
    topology_builder: Arc<TopologyBuilder>,
    extractor: TraceExtractor,
    traces: Arc<DashMap<String, Vec<common::topology::CallTrace>>>,
}

impl TopologyService {
    pub fn new() -> Self {
        let topology_builder = Arc::new(TopologyBuilder::new());
        let extractor = TraceExtractor::new(topology_builder.clone());

        Self {
            topology_builder,
            extractor,
            traces: Arc::new(DashMap::new()),
        }
    }

    pub fn process_log(&self, log: &LogEvent) {
        let trace = self.extractor.extract_from_log(log);

        if let Some(t) = trace {
            if let Some(request_id) = &t.request_id {
                let call_trace = common::topology::CallTrace {
                    trace_id: request_id.clone(),
                    spans: vec![common::topology::Span {
                        span_id: uuid::Uuid::new_v4().to_string(),
                        service_name: t.source_service.clone().unwrap_or_default(),
                        operation_name: "call".to_string(),
                        start_time: t.timestamp,
                        duration_ms: t.latency_ms.unwrap_or(0.0),
                        parent_span_id: None,
                        tags: std::collections::HashMap::new(),
                    }],
                };

                self.traces
                    .entry(request_id.clone())
                    .or_default()
                    .push(call_trace);
            }
        }
    }

    pub fn process_log_batch(&self, logs: &[LogEvent]) {
        for log in logs {
            self.process_log(log);
        }
    }

    pub fn get_topology(&self) -> ServiceTopology {
        self.topology_builder.get_topology()
    }

    pub fn get_service_edges(&self, service_name: &str) -> Vec<common::topology::ServiceEdge> {
        self.topology_builder.get_edges_for_service(service_name)
    }

    pub fn get_traces(&self, limit: usize) -> Vec<common::topology::CallTrace> {
        self.traces
            .iter()
            .flat_map(|entry| entry.value().clone())
            .take(limit)
            .collect()
    }

    pub fn get_trace_count(&self) -> usize {
        self.traces.len()
    }

    pub fn cleanup_old_traces(&self, max_age: chrono::Duration) {
        let now = chrono::Utc::now();
        let to_remove: Vec<String> = self
            .traces
            .iter()
            .filter(|entry| {
                entry.value().iter().all(|trace| {
                    if let Some(span) = trace.spans.first() {
                        now - span.start_time > max_age
                    } else {
                        true
                    }
                })
            })
            .map(|entry| entry.key().clone())
            .collect();

        for key in to_remove {
            self.traces.remove(&key);
        }
    }
}

impl Default for TopologyService {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use common::log::{LogLevel, LogEvent};

    fn create_test_log(message: &str, service: &str) -> LogEvent {
        LogEvent {
            id: uuid::Uuid::new_v4(),
            timestamp: chrono::Utc::now(),
            hostname: "test-host".to_string(),
            service: service.to_string(),
            level: LogLevel::Info,
            message: message.to_string(),
            fields: std::collections::HashMap::new(),
            source_file: "test.log".to_string(),
            raw: None,
        }
    }

    #[test]
    fn test_service_topology_building() {
        let service = TopologyService::new();

        let log1 = create_test_log("calling user-service took 100ms", "api-gateway");
        let log2 = create_test_log("calling order-service took 200ms", "api-gateway");

        service.process_log(&log1);
        service.process_log(&log2);

        let topology = service.get_topology();

        assert!(!topology.nodes.is_empty());
    }
}
