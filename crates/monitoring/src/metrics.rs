use std::sync::Arc;
use std::collections::HashMap;
use tokio::sync::RwLock;

use common::error::{CdnResult};
use common::models::NodeMetrics;
use common::db::Database;
use common::redis::RedisClient;

pub struct MetricsStore {
    db: Database,
    redis: RedisClient,
    recent_metrics: Arc<RwLock<HashMap<uuid::Uuid, Vec<NodeMetrics>>>>,
    max_history_size: usize,
}

impl MetricsStore {
    pub fn new(db: Database, redis: RedisClient) -> Self {
        MetricsStore {
            db,
            redis,
            recent_metrics: Arc::new(RwLock::new(HashMap::new())),
            max_history_size: 100,
        }
    }

    pub async fn record_metrics(&self, metrics: NodeMetrics) -> CdnResult<()> {
        self.db.insert_node_metrics(&metrics).await?;
        self.redis.store_node_metrics(&metrics, 3600).await?;

        let mut recent = self.recent_metrics.write().await;
        let entry = recent.entry(metrics.node_id).or_insert_with(Vec::new);
        entry.push(metrics);
        
        if entry.len() > self.max_history_size {
            entry.remove(0);
        }

        Ok(())
    }

    pub async fn get_latest_metrics(&self, node_id: uuid::Uuid) -> Option<NodeMetrics> {
        let recent = self.recent_metrics.read().await;
        recent.get(&node_id).and_then(|v| v.last().cloned())
    }

    pub async fn get_metrics_history(&self, node_id: uuid::Uuid) -> Vec<NodeMetrics> {
        let recent = self.recent_metrics.read().await;
        recent.get(&node_id).cloned().unwrap_or_default()
    }

    pub async fn get_all_latest_metrics(&self) -> HashMap<uuid::Uuid, NodeMetrics> {
        let recent = self.recent_metrics.read().await;
        let mut result = HashMap::new();
        for (node_id, metrics) in recent.iter() {
            if let Some(latest) = metrics.last() {
                result.insert(*node_id, latest.clone());
            }
        }
        result
    }
}

impl Clone for MetricsStore {
    fn clone(&self) -> Self {
        MetricsStore {
            db: self.db.clone(),
            redis: self.redis.clone(),
            recent_metrics: self.recent_metrics.clone(),
            max_history_size: self.max_history_size,
        }
    }
}
