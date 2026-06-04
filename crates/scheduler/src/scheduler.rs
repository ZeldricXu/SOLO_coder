use std::sync::Arc;
use tokio::sync::RwLock;

use common::models::{EdgeNode, SchedulingStrategy as StrategyType, SchedulingDecision, ContentType};
use common::error::{CdnResult, CdnError};
use common::utils::content_type_from_url;

use node_manager::NodeRegistry;

use crate::strategies::{
    SchedulingStrategy, WeightedRoundRobinStrategy, LeastConnectionsStrategy,
    GeoLocationPriorityStrategy, HybridStrategy, ContentAwareStrategy, SchedulingContext,
};
use crate::geo_routing::GeoLocationResolver;

pub struct TrafficScheduler {
    registry: NodeRegistry,
    geo_resolver: GeoLocationResolver,
    strategies: Arc<RwLock<StrategyRegistry>>,
}

struct StrategyRegistry {
    wrr: WeightedRoundRobinStrategy,
    lc: LeastConnectionsStrategy,
    geo: GeoLocationPriorityStrategy,
    hybrid: HybridStrategy,
    content_aware: ContentAwareStrategy,
}

impl TrafficScheduler {
    pub fn new(registry: NodeRegistry, geo_resolver: GeoLocationResolver) -> Self {
        TrafficScheduler {
            registry,
            geo_resolver,
            strategies: Arc::new(RwLock::new(StrategyRegistry {
                wrr: WeightedRoundRobinStrategy::new(),
                lc: LeastConnectionsStrategy,
                geo: GeoLocationPriorityStrategy,
                hybrid: HybridStrategy::new(),
                content_aware: ContentAwareStrategy::new(),
            })),
        }
    }

    pub async fn schedule(
        &self,
        strategy: StrategyType,
        user_ip: &str,
        domain: &str,
        path: &str,
    ) -> CdnResult<SchedulingDecision> {
        let nodes = self.registry.get_online_nodes().await?;
        
        if nodes.is_empty() {
            return Err(CdnError::NoAvailableNodes);
        }

        let user_region = Some(self.geo_resolver.get_region_for_ip(user_ip));
        
        let context = SchedulingContext {
            user_ip: user_ip.to_string(),
            user_region,
            domain: domain.to_string(),
            path: path.to_string(),
            content_size: None,
            content_type: content_type_from_url(path),
        };

        let strategies = self.strategies.read().await;
        
        let decision = match strategy {
            StrategyType::WeightedRoundRobin => strategies.wrr.select_node(&nodes, &context)?,
            StrategyType::LeastConnections => strategies.lc.select_node(&nodes, &context)?,
            StrategyType::GeoLocationPriority => strategies.geo.select_node(&nodes, &context)?,
        };

        tracing::debug!(
            "Scheduled request from {} to node {} (region: {})",
            user_ip,
            decision.node_id,
            decision.region
        );

        Ok(decision)
    }

    pub async fn schedule_hybrid(
        &self,
        user_ip: &str,
        domain: &str,
        path: &str,
    ) -> CdnResult<SchedulingDecision> {
        let nodes = self.registry.get_online_nodes().await?;
        
        if nodes.is_empty() {
            return Err(CdnError::NoAvailableNodes);
        }

        let user_region = Some(self.geo_resolver.get_region_for_ip(user_ip));
        
        let context = SchedulingContext {
            user_ip: user_ip.to_string(),
            user_region,
            domain: domain.to_string(),
            path: path.to_string(),
            content_size: None,
            content_type: content_type_from_url(path),
        };

        let strategies = self.strategies.read().await;
        strategies.hybrid.select_node(&nodes, &context)
    }

    pub async fn schedule_for_region(
        &self,
        region: &str,
        strategy: StrategyType,
        user_ip: &str,
        domain: &str,
        path: &str,
    ) -> CdnResult<SchedulingDecision> {
        let nodes = self.registry.get_nodes_by_region(region).await?;
        
        if nodes.is_empty() {
            return self.schedule(strategy, user_ip, domain, path).await;
        }

        let context = SchedulingContext {
            user_ip: user_ip.to_string(),
            user_region: Some(region.to_string()),
            domain: domain.to_string(),
            path: path.to_string(),
            content_size: None,
            content_type: content_type_from_url(path),
        };

        let strategies = self.strategies.read().await;
        
        let decision = match strategy {
            StrategyType::WeightedRoundRobin => strategies.wrr.select_node(&nodes, &context)?,
            StrategyType::LeastConnections => strategies.lc.select_node(&nodes, &context)?,
            StrategyType::GeoLocationPriority => strategies.geo.select_node(&nodes, &context)?,
        };

        Ok(decision)
    }

    pub async fn schedule_by_content_type(
        &self,
        user_ip: &str,
        domain: &str,
        path: &str,
        content_type: Option<ContentType>,
    ) -> CdnResult<SchedulingDecision> {
        let nodes = self.registry.get_online_nodes().await?;
        
        if nodes.is_empty() {
            return Err(CdnError::NoAvailableNodes);
        }

        let user_region = Some(self.geo_resolver.get_region_for_ip(user_ip));
        let ct = content_type.unwrap_or_else(|| content_type_from_url(path));

        let context = SchedulingContext {
            user_ip: user_ip.to_string(),
            user_region,
            domain: domain.to_string(),
            path: path.to_string(),
            content_size: None,
            content_type: ct,
        };

        let strategies = self.strategies.read().await;
        strategies.content_aware.select_node(&nodes, &context)
    }

    pub async fn get_available_nodes_count(&self) -> CdnResult<usize> {
        let nodes = self.registry.get_online_nodes().await?;
        Ok(nodes.len())
    }

    pub async fn get_nodes_by_region(&self, region: &str) -> CdnResult<Vec<EdgeNode>> {
        self.registry.get_nodes_by_region(region).await
    }

    pub fn geo_resolver(&self) -> &GeoLocationResolver {
        &self.geo_resolver
    }
}

impl Clone for TrafficScheduler {
    fn clone(&self) -> Self {
        TrafficScheduler {
            registry: self.registry.clone(),
            geo_resolver: GeoLocationResolver::new(),
            strategies: self.strategies.clone(),
        }
    }
}
