use std::sync::Arc;
use tokio::sync::RwLock;
use std::collections::HashMap;
use serde_json::Value;
use uuid::Uuid;

use common::error::{CdnResult};
use common::db::Database;
use common::redis::RedisClient;
use common::utils::{ConfigDiff, compute_json_diff, merge_json_diff};

pub struct IncrementalPusher {
    db: Database,
    redis: RedisClient,
    previous_versions: Arc<RwLock<HashMap<String, Value>>>,
}

impl IncrementalPusher {
    pub fn new(db: Database, redis: RedisClient) -> Self {
        IncrementalPusher {
            db,
            redis,
            previous_versions: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn push_incremental(&self, config_type: &str, new_config: &Value, target_nodes: &[Uuid]) -> CdnResult<Vec<ConfigDiff>> {
        let mut previous = self.previous_versions.write().await;
        let old_config = previous.get(config_type).cloned().unwrap_or(Value::Null);

        let diffs = compute_json_diff(&old_config, new_config);

        if diffs.is_empty() {
            previous.insert(config_type.to_string(), new_config.clone());
            return Ok(diffs);
        }

        for node_id in target_nodes {
            let result = self.push_diff_to_node(node_id, config_type, &diffs).await;
            if result.is_err() {
                self.push_full(config_type, new_config, target_nodes).await?;
                previous.insert(config_type.to_string(), new_config.clone());
                return Ok(diffs);
            }
        }

        previous.insert(config_type.to_string(), new_config.clone());
        Ok(diffs)
    }

    async fn push_diff_to_node(&self, node_id: &Uuid, config_type: &str, diffs: &[ConfigDiff]) -> CdnResult<()> {
        let message = serde_json::json!({
            "type": "incremental",
            "config_type": config_type,
            "diffs": diffs,
            "target_node": node_id.to_string(),
            "timestamp": chrono::Utc::now().to_rfc3339(),
        });

        self.redis.publish(
            &format!("node:{}:config", node_id),
            &message.to_string(),
        ).await?;

        Ok(())
    }

    pub async fn push_full(&self, config_type: &str, config: &Value, target_nodes: &[Uuid]) -> CdnResult<()> {
        for node_id in target_nodes {
            let message = serde_json::json!({
                "type": "full",
                "config_type": config_type,
                "data": config,
                "target_node": node_id.to_string(),
                "timestamp": chrono::Utc::now().to_rfc3339(),
            });

            self.redis.publish(
                &format!("node:{}:config", node_id),
                &message.to_string(),
            ).await?;
        }

        Ok(())
    }

    pub fn apply_diff(local_config: &mut Value, diffs: &[ConfigDiff]) {
        merge_json_diff(local_config, diffs);
    }
}

impl Clone for IncrementalPusher {
    fn clone(&self) -> Self {
        IncrementalPusher {
            db: self.db.clone(),
            redis: self.redis.clone(),
            previous_versions: self.previous_versions.clone(),
        }
    }
}
