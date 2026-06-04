use uuid::Uuid;
use common::models::EdgeNode;
use common::redis::RedisClient;
use crate::hierarchy::CacheHierarchy;
use crate::fetch::{HierarchicalFetcher, HierarchicalFetchResult};

pub struct HierarchicalCacheManager {
    fetcher: HierarchicalFetcher,
}

impl HierarchicalCacheManager {
    pub fn new(nodes: &[EdgeNode], redis: RedisClient) -> Self {
        let hierarchy = CacheHierarchy::build_from_nodes(nodes);
        let fetcher = HierarchicalFetcher::new(hierarchy, redis);
        HierarchicalCacheManager { fetcher }
    }

    pub async fn request_content(&self, edge_node_id: Uuid, cache_key: &str) -> HierarchicalFetchResult {
        self.fetcher.fetch_content(edge_node_id, cache_key).await
    }

    pub fn update_hierarchy(&mut self, nodes: &[EdgeNode]) {
        let hierarchy = CacheHierarchy::build_from_nodes(nodes);
        let redis = self.fetcher.redis().clone();
        self.fetcher = HierarchicalFetcher::new(hierarchy, redis);
    }

    pub fn get_node_lineage(&self, node_id: Uuid) -> Vec<Uuid> {
        let mut lineage = vec![node_id];
        let ancestors = self.fetcher.hierarchy().get_ancestors(node_id);
        lineage.extend(ancestors);
        lineage
    }
}
