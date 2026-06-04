use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

use common::models::{EdgeNode, SchedulingDecision, NodeStatus, ContentType};
use common::error::{CdnResult, CdnError};
use common::utils::{calculate_dynamic_weight, calculate_bandwidth_utilization};

pub trait SchedulingStrategy: Send + Sync {
    fn name(&self) -> &str;
    fn select_node(&self, nodes: &[EdgeNode], context: &SchedulingContext) -> CdnResult<SchedulingDecision>;
}

pub struct SchedulingContext {
    pub user_ip: String,
    pub user_region: Option<String>,
    pub domain: String,
    pub path: String,
    pub content_size: Option<u64>,
    pub content_type: ContentType,
}

pub struct WeightedRoundRobinStrategy {
    counter: Arc<AtomicUsize>,
}

impl WeightedRoundRobinStrategy {
    pub fn new() -> Self {
        WeightedRoundRobinStrategy {
            counter: Arc::new(AtomicUsize::new(0)),
        }
    }

    fn build_weighted_list(&self, nodes: &[EdgeNode]) -> Vec<usize> {
        let mut weighted = Vec::new();
        
        for (idx, node) in nodes.iter().enumerate() {
            let dynamic_weight = calculate_dynamic_weight(
                node.weight,
                node.current_load,
                calculate_bandwidth_utilization(node.bandwidth_usage, node.bandwidth_capacity),
                0.0,
            );
            
            for _ in 0..dynamic_weight {
                weighted.push(idx);
            }
        }
        
        weighted
    }
}

impl SchedulingStrategy for WeightedRoundRobinStrategy {
    fn name(&self) -> &str {
        "WeightedRoundRobin"
    }

    fn select_node(&self, nodes: &[EdgeNode], _context: &SchedulingContext) -> CdnResult<SchedulingDecision> {
        let online_nodes: Vec<EdgeNode> = nodes
            .iter()
            .filter(|n| n.status == NodeStatus::Online)
            .cloned()
            .collect();

        if online_nodes.is_empty() {
            return Err(CdnError::NoAvailableNodes);
        }

        let weighted_indices = self.build_weighted_list(&online_nodes);
        
        if weighted_indices.is_empty() {
            let node = &online_nodes[0];
            return Ok(SchedulingDecision {
                node_id: node.id,
                node_ip: node.ip_address,
                region: node.region.clone(),
                confidence: 1.0,
                reason: "Fallback to first available node".to_string(),
            });
        }

        let idx = self.counter.fetch_add(1, Ordering::SeqCst) % weighted_indices.len();
        let node_idx = weighted_indices[idx];
        let node = &online_nodes[node_idx];

        Ok(SchedulingDecision {
            node_id: node.id,
            node_ip: node.ip_address,
            region: node.region.clone(),
            confidence: 0.8,
            reason: format!("Weighted round-robin selection (weight: {})", node.weight),
        })
    }
}

pub struct LeastConnectionsStrategy;

impl LeastConnectionsStrategy {
    pub fn new() -> Self {
        LeastConnectionsStrategy
    }
}

impl SchedulingStrategy for LeastConnectionsStrategy {
    fn name(&self) -> &str {
        "LeastConnections"
    }

    fn select_node(&self, nodes: &[EdgeNode], _context: &SchedulingContext) -> CdnResult<SchedulingDecision> {
        let online_nodes: Vec<&EdgeNode> = nodes
            .iter()
            .filter(|n| n.status == NodeStatus::Online)
            .collect();

        if online_nodes.is_empty() {
            return Err(CdnError::NoAvailableNodes);
        }

        let mut scored_nodes: Vec<_> = online_nodes
            .iter()
            .map(|node| {
                let bandwidth_util = calculate_bandwidth_utilization(
                    node.bandwidth_usage,
                    node.bandwidth_capacity,
                );
                let score = (node.weight as f64) * (1.0 + node.current_load) * (1.0 + bandwidth_util);
                (score, node)
            })
            .collect();

        scored_nodes.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
        
        let best_node = scored_nodes[0].1;
        let score = scored_nodes[0].0;

        Ok(SchedulingDecision {
            node_id: best_node.id,
            node_ip: best_node.ip_address,
            region: best_node.region.clone(),
            confidence: 0.85,
            reason: format!("Least connections score: {:.2}", score),
        })
    }
}

pub struct GeoLocationPriorityStrategy;

impl GeoLocationPriorityStrategy {
    pub fn new() -> Self {
        GeoLocationPriorityStrategy
    }
}

impl SchedulingStrategy for GeoLocationPriorityStrategy {
    fn name(&self) -> &str {
        "GeoLocationPriority"
    }

    fn select_node(&self, nodes: &[EdgeNode], context: &SchedulingContext) -> CdnResult<SchedulingDecision> {
        let online_nodes: Vec<&EdgeNode> = nodes
            .iter()
            .filter(|n| n.status == NodeStatus::Online)
            .collect();

        if online_nodes.is_empty() {
            return Err(CdnError::NoAvailableNodes);
        }

        let user_region = context.user_region.as_deref().unwrap_or("");
        
        let same_region_nodes: Vec<&EdgeNode> = online_nodes
            .iter()
            .filter(|n| n.region == user_region)
            .cloned()
            .collect();

        let candidate_nodes = if !same_region_nodes.is_empty() {
            same_region_nodes
        } else {
            online_nodes.iter().cloned().collect()
        };

        let mut scored_nodes: Vec<_> = candidate_nodes
            .iter()
            .map(|node| {
                let region_match = if node.region == user_region { 1.0 } else { 0.0 };
                let bandwidth_util = calculate_bandwidth_utilization(
                    node.bandwidth_usage,
                    node.bandwidth_capacity,
                );
                let load_factor = 1.0 - (node.current_load.min(1.0) * 0.3);
                let bandwidth_factor = 1.0 - (bandwidth_util.min(1.0) * 0.3);
                let score = region_match * 0.4 + load_factor * 0.3 + bandwidth_factor * 0.3;
                (score, node)
            })
            .collect();

        scored_nodes.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));
        
        let best_node = scored_nodes[0].1;
        let confidence = scored_nodes[0].0;

        Ok(SchedulingDecision {
            node_id: best_node.id,
            node_ip: best_node.ip_address,
            region: best_node.region.clone(),
            confidence,
            reason: format!(
                "Geo-priority selection (region match: {}, load: {:.2}, bw: {:.2})",
                user_region,
                best_node.current_load,
                calculate_bandwidth_utilization(best_node.bandwidth_usage, best_node.bandwidth_capacity)
            ),
        })
    }
}

pub struct HybridStrategy {
    wrr: WeightedRoundRobinStrategy,
    lc: LeastConnectionsStrategy,
    geo: GeoLocationPriorityStrategy,
}

impl HybridStrategy {
    pub fn new() -> Self {
        HybridStrategy {
            wrr: WeightedRoundRobinStrategy::new(),
            lc: LeastConnectionsStrategy,
            geo: GeoLocationPriorityStrategy,
        }
    }
}

impl SchedulingStrategy for HybridStrategy {
    fn name(&self) -> &str {
        "Hybrid"
    }

    fn select_node(&self, nodes: &[EdgeNode], context: &SchedulingContext) -> CdnResult<SchedulingDecision> {
        let geo_decision = self.geo.select_node(nodes, context)?;
        
        if geo_decision.confidence >= 0.7 {
            return Ok(geo_decision);
        }

        let lc_decision = self.lc.select_node(nodes, context)?;
        
        if lc_decision.confidence >= 0.7 {
            return Ok(lc_decision);
        }

        self.wrr.select_node(nodes, context)
    }
}

pub struct ContentAwareStrategy {
    lc: LeastConnectionsStrategy,
    wrr: WeightedRoundRobinStrategy,
    geo: GeoLocationPriorityStrategy,
}

impl ContentAwareStrategy {
    pub fn new() -> Self {
        ContentAwareStrategy {
            lc: LeastConnectionsStrategy::new(),
            wrr: WeightedRoundRobinStrategy::new(),
            geo: GeoLocationPriorityStrategy::new(),
        }
    }

    fn select_live_stream(&self, nodes: &[EdgeNode], context: &SchedulingContext) -> CdnResult<SchedulingDecision> {
        let online_nodes: Vec<&EdgeNode> = nodes
            .iter()
            .filter(|n| n.status == NodeStatus::Online)
            .collect();

        if online_nodes.is_empty() {
            return Err(CdnError::NoAvailableNodes);
        }

        let user_region = context.user_region.as_deref().unwrap_or("");

        let mut scored_nodes: Vec<_> = online_nodes
            .iter()
            .map(|node| {
                let region_match = if node.region == user_region { 1.0 } else { 0.0 };
                let bandwidth_util = calculate_bandwidth_utilization(
                    node.bandwidth_usage,
                    node.bandwidth_capacity,
                );
                let load_score = 1.0 - node.current_load.min(1.0);
                let bandwidth_score = 1.0 - bandwidth_util.min(1.0);
                let score = region_match * 0.4 + load_score * 0.35 + bandwidth_score * 0.25;
                (score, *node)
            })
            .collect();

        scored_nodes.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));

        let best_node = scored_nodes[0].1;
        let confidence = scored_nodes[0].0;

        Ok(SchedulingDecision {
            node_id: best_node.id,
            node_ip: best_node.ip_address,
            region: best_node.region.clone(),
            confidence,
            reason: format!(
                "LiveStream scheduling (region: {}, load: {:.2}, bw_util: {:.2})",
                user_region,
                best_node.current_load,
                calculate_bandwidth_utilization(best_node.bandwidth_usage, best_node.bandwidth_capacity)
            ),
        })
    }

    fn select_vod(&self, nodes: &[EdgeNode], _context: &SchedulingContext) -> CdnResult<SchedulingDecision> {
        let online_nodes: Vec<&EdgeNode> = nodes
            .iter()
            .filter(|n| n.status == NodeStatus::Online)
            .collect();

        if online_nodes.is_empty() {
            return Err(CdnError::NoAvailableNodes);
        }

        let mut scored_nodes: Vec<_> = online_nodes
            .iter()
            .map(|node| {
                let bandwidth_util = calculate_bandwidth_utilization(
                    node.bandwidth_usage,
                    node.bandwidth_capacity,
                );
                let bandwidth_remaining = 1.0 - bandwidth_util.min(1.0);
                let dynamic_weight = calculate_dynamic_weight(
                    node.weight,
                    node.current_load,
                    bandwidth_util,
                    0.0,
                );
                let score = bandwidth_remaining * 0.6 + (dynamic_weight as f64 / 100.0).min(1.0) * 0.4;
                (score, *node)
            })
            .collect();

        scored_nodes.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));

        let best_node = scored_nodes[0].1;
        let confidence = scored_nodes[0].0;

        Ok(SchedulingDecision {
            node_id: best_node.id,
            node_ip: best_node.ip_address,
            region: best_node.region.clone(),
            confidence,
            reason: format!(
                "Vod scheduling (bw_remaining: {:.2}, weight: {})",
                1.0 - calculate_bandwidth_utilization(best_node.bandwidth_usage, best_node.bandwidth_capacity).min(1.0),
                best_node.weight
            ),
        })
    }
}

impl SchedulingStrategy for ContentAwareStrategy {
    fn name(&self) -> &str {
        "ContentAware"
    }

    fn select_node(&self, nodes: &[EdgeNode], context: &SchedulingContext) -> CdnResult<SchedulingDecision> {
        match context.content_type {
            ContentType::LiveStream => self.select_live_stream(nodes, context),
            ContentType::Vod => self.select_vod(nodes, context),
            ContentType::StaticAsset => self.geo.select_node(nodes, context),
        }
    }
}
