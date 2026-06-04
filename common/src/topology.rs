use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct ServiceNode {
    pub name: String,
    pub service_type: Option<String>,
    pub instance_id: Option<String>,
}

impl ServiceNode {
    pub fn new(name: String) -> Self {
        Self {
            name,
            service_type: None,
            instance_id: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct ServiceEdge {
    pub from: String,
    pub to: String,
}

impl ServiceEdge {
    pub fn new(from: String, to: String) -> Self {
        Self { from, to }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EdgeMetrics {
    pub qps: f64,
    pub error_rate: f64,
    pub p50_latency_ms: f64,
    pub p90_latency_ms: f64,
    pub p95_latency_ms: f64,
    pub p99_latency_ms: f64,
    pub total_requests: u64,
    pub total_errors: u64,
}

impl Default for EdgeMetrics {
    fn default() -> Self {
        Self {
            qps: 0.0,
            error_rate: 0.0,
            p50_latency_ms: 0.0,
            p90_latency_ms: 0.0,
            p95_latency_ms: 0.0,
            p99_latency_ms: 0.0,
            total_requests: 0,
            total_errors: 0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServiceTopology {
    pub nodes: HashMap<String, ServiceNode>,
    pub edges: HashMap<ServiceEdge, EdgeMetrics>,
    pub last_updated: DateTime<Utc>,
    pub window_start: DateTime<Utc>,
    pub window_end: DateTime<Utc>,
}

impl ServiceTopology {
    pub fn new(window_start: DateTime<Utc>, window_end: DateTime<Utc>) -> Self {
        Self {
            nodes: HashMap::new(),
            edges: HashMap::new(),
            last_updated: Utc::now(),
            window_start,
            window_end,
        }
    }

    pub fn add_node(&mut self, node: ServiceNode) {
        self.nodes.insert(node.name.clone(), node);
    }

    pub fn add_or_update_edge(&mut self, edge: ServiceEdge, metrics: EdgeMetrics) {
        self.edges.insert(edge, metrics);
        self.last_updated = Utc::now();
    }

    pub fn get_dependencies(&self, service: &str) -> Vec<String> {
        self.edges
            .keys()
            .filter(|e| e.from == service)
            .map(|e| e.to.clone())
            .collect()
    }

    pub fn get_dependents(&self, service: &str) -> Vec<String> {
        self.edges
            .keys()
            .filter(|e| e.to == service)
            .map(|e| e.from.clone())
            .collect()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CallTrace {
    pub request_id: Uuid,
    pub spans: Vec<Span>,
    pub start_time: DateTime<Utc>,
    pub end_time: Option<DateTime<Utc>>,
    pub success: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Span {
    pub span_id: Uuid,
    pub parent_span_id: Option<Uuid>,
    pub service_name: String,
    pub operation_name: String,
    pub start_time: DateTime<Utc>,
    pub duration_ms: f64,
    pub status: SpanStatus,
    pub tags: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum SpanStatus {
    Success,
    Error,
    Unknown,
}
