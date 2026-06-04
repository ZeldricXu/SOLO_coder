use uuid::Uuid;
use redis::AsyncCommands;
use common::redis::RedisClient;
use common::error::CdnResult;
use crate::hierarchy::CacheHierarchy;

#[derive(Debug, Clone)]
pub enum HierarchicalFetchResult {
    Hit { content: String, source_node_id: Uuid },
    Miss,
    Error(String),
}

pub struct HierarchicalFetcher {
    hierarchy: CacheHierarchy,
    redis: RedisClient,
}

impl HierarchicalFetcher {
    pub fn new(hierarchy: CacheHierarchy, redis: RedisClient) -> Self {
        HierarchicalFetcher { hierarchy, redis }
    }

    pub async fn fetch_content(&self, edge_node_id: Uuid, cache_key: &str) -> HierarchicalFetchResult {
        let local_result = self.query_node_cache(edge_node_id, cache_key).await;
        match local_result {
            Ok(Some(content)) => return HierarchicalFetchResult::Hit {
                content,
                source_node_id: edge_node_id,
            },
            Ok(None) => {}
            Err(e) => return HierarchicalFetchResult::Error(e.to_string()),
        }

        let ancestors = self.hierarchy.get_ancestors(edge_node_id);
        for ancestor_id in &ancestors {
            match self.query_node_cache(*ancestor_id, cache_key).await {
                Ok(Some(content)) => {
                    self.fill_cache_chain(edge_node_id, &ancestors, cache_key, &content).await;
                    return HierarchicalFetchResult::Hit {
                        content,
                        source_node_id: *ancestor_id,
                    };
                }
                Ok(None) => continue,
                Err(e) => tracing::warn!("query ancestor {} cache error: {}", ancestor_id, e),
            }
        }

        HierarchicalFetchResult::Miss
    }

    pub async fn fill_cache(&self, node_id: Uuid, cache_key: &str, content: &str) {
        if let Err(e) = self.write_node_cache(node_id, cache_key, content).await {
            tracing::warn!("fill cache for node {} failed: {}", node_id, e);
        }
    }

    async fn query_node_cache(&self, node_id: Uuid, cache_key: &str) -> CdnResult<Option<String>> {
        let mut conn = self.redis.get_connection().await?;
        let key = format!("hcache:{}:{}", node_id, cache_key);
        let value: Option<String> = conn.get(&key).await?;
        Ok(value)
    }

    async fn write_node_cache(&self, node_id: Uuid, cache_key: &str, content: &str) -> CdnResult<()> {
        let mut conn = self.redis.get_connection().await?;
        let key = format!("hcache:{}:{}", node_id, cache_key);
        let _: () = conn.set_ex(&key, content, 3600_u64).await?;
        Ok(())
    }

    async fn fill_cache_chain(
        &self,
        edge_node_id: Uuid,
        ancestors: &[Uuid],
        cache_key: &str,
        content: &str,
    ) {
        let fill_target = self.hierarchy.get_parent(edge_node_id);
        if let Some(target_id) = fill_target {
            if ancestors.contains(&target_id) {
                self.fill_cache(edge_node_id, cache_key, content).await;
            }
        } else {
            self.fill_cache(edge_node_id, cache_key, content).await;
        }
    }

    pub fn hierarchy(&self) -> &CacheHierarchy {
        &self.hierarchy
    }

    pub fn redis(&self) -> &RedisClient {
        &self.redis
    }
}
