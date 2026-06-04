use dashmap::DashMap;
use std::sync::Arc;
use tracing::debug;

use common::topology::{ServiceNode, ServiceEdge, ServiceTopology, EdgeMetrics};

#[derive(Debug, Clone)]
pub struct TopologyBuilder {
    nodes: Arc<DashMap<String, ServiceNode>>,
    edges: Arc<DashMap<(String, String), ServiceEdge>>,
}

impl TopologyBuilder {
    pub fn new() -> Self {
        Self {
            nodes: Arc::new(DashMap::new()),
            edges: Arc::new(DashMap::new()),
        }
    }

    pub fn add_service(&self, service_name: &str, hostname: &str) {
        self.nodes
            .entry(service_name.to_string())
            .and_modify(|node| {
                if !node.hosts.contains(&hostname.to_string()) {
                    node.hosts.push(hostname.to_string());
                }
            })
            .or_insert_with(|| ServiceNode {
                service_name: service_name.to_string(),
                hosts: vec![hostname.to_string()],
                qps: 0.0,
                error_rate: 0.0,
                p50_latency: 0.0,
                p95_latency: 0.0,
                p99_latency: 0.0,
            });
    }

    pub fn record_call(&self, from_service: &str, to_service: &str, latency_ms: f64, is_error: bool) {
        let edge_key = (from_service.to_string(), to_service.to_string());

        self.edges
            .entry(edge_key)
            .and_modify(|edge| {
                update_edge_metrics(&mut edge.metrics, latency_ms, is_error);
            })
            .or_insert_with(|| {
                let mut metrics = EdgeMetrics::default();
                update_edge_metrics(&mut metrics, latency_ms, is_error);
                ServiceEdge {
                    from_service: from_service.to_string(),
                    to_service: to_service.to_string(),
                    metrics,
                }
            });
    }

    pub fn get_topology(&self) -> ServiceTopology {
        let nodes: Vec<ServiceNode> = self
            .nodes
            .iter()
            .map(|entry| entry.value().clone())
            .collect();

        let edges: Vec<ServiceEdge> = self
            .edges
            .iter()
            .map(|entry| entry.value().clone())
            .collect();

        ServiceTopology { nodes, edges }
    }

    pub fn get_edges_for_service(&self, service_name: &str) -> Vec<ServiceEdge> {
        self.edges
            .iter()
            .filter(|entry| {
                entry.0 == service_name || entry.1 == service_name
            })
            .map(|entry| entry.value().clone())
            .collect()
    }

    pub fn cleanup(&self) {
        self.nodes.clear();
        self.edges.clear();
    }
}

impl Default for TopologyBuilder {
    fn default() -> Self {
        Self::new()
    }
}

fn update_edge_metrics(metrics: &mut EdgeMetrics, latency_ms: f64, is_error: bool) {
    metrics.total_calls += 1;
    metrics.error_count += if is_error { 1 } else { 0 };
    metrics.total_latency += latency_ms;
    metrics.min_latency = metrics.min_latency.min(latency_ms);
    metrics.max_latency = metrics.max_latency.max(latency_ms);

    metrics.qps = metrics.total_calls as f64 / 60.0;
    metrics.error_rate = metrics.error_count as f64 / metrics.total_calls as f64;
    metrics.avg_latency = metrics.total_latency / metrics.total_calls as f64;

    let percentile = |p| {
        let index = (metrics.latency_samples.len() as f64 * p).round() as usize;
        metrics.latency_samples.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        metrics.latency_samples.get(index).copied().unwrap_or(0.0)
    };

    if metrics.latency_samples.len() < 1000 {
        metrics.latency_samples.push(latency_ms);
    }

    metrics.p50_latency = percentile(0.5);
    metrics.p95_latency = percentile(0.95);
    metrics.p99_latency = percentile(0.99);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_topology_builder() {
        let builder = TopologyBuilder::new();

        builder.add_service("api-gateway", "host1");
        builder.add_service("user-service", "host2");

        builder.record_call("api-gateway", "user-service", 100.0, false);
        builder.record_call("api-gateway", "user-service", 150.0, true);
        builder.record_call("api-gateway", "user-service", 200.0, false);

        let topology = builder.get_topology();

        assert_eq!(topology.nodes.len(), 2);
        assert_eq!(topology.edges.len(), 1);
        assert_eq!(topology.edges[0].metrics.total_calls, 3);
        assert_eq!(topology.edges[0].metrics.error_count, 1);
    }
}
