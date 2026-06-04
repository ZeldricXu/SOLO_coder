use std::sync::Arc;
use tokio::sync::RwLock;

use common::error::{CdnResult};
use common::redis::RedisClient;

pub struct ConfigPublisher {
    redis: RedisClient,
    subscribers: Arc<RwLock<Vec<String>>>,
}

impl ConfigPublisher {
    pub fn new(redis: RedisClient) -> Self {
        ConfigPublisher {
            redis,
            subscribers: Arc::new(RwLock::new(Vec::new())),
        }
    }

    pub async fn publish_config(&self, config_type: &str, version: u64, data: &serde_json::Value) -> CdnResult<()> {
        let message = serde_json::json!({
            "type": config_type,
            "version": version,
            "data": data,
            "timestamp": chrono::Utc::now().to_rfc3339(),
        });

        self.redis.publish(
            &format!("config:{}", config_type),
            &message.to_string(),
        ).await?;

        tracing::info!("Published config update for {} version {}", config_type, version);
        Ok(())
    }

    pub async fn publish_to_node(&self, node_id: uuid::Uuid, config_type: &str, data: &serde_json::Value) -> CdnResult<()> {
        let message = serde_json::json!({
            "type": config_type,
            "data": data,
            "target_node": node_id.to_string(),
            "timestamp": chrono::Utc::now().to_rfc3339(),
        });

        self.redis.publish(
            &format!("node:{}:config", node_id),
            &message.to_string(),
        ).await?;

        Ok(())
    }

    pub async fn broadcast_config(&self, config_type: &str, data: &serde_json::Value) -> CdnResult<()> {
        let message = serde_json::json!({
            "type": config_type,
            "data": data,
            "broadcast": true,
            "timestamp": chrono::Utc::now().to_rfc3339(),
        });

        self.redis.publish("config:broadcast", &message.to_string()).await?;

        Ok(())
    }
}

impl Clone for ConfigPublisher {
    fn clone(&self) -> Self {
        ConfigPublisher {
            redis: self.redis.clone(),
            subscribers: self.subscribers.clone(),
        }
    }
}
