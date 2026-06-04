use redis::{Client, AsyncCommands, aio::MultiplexedConnection};
use std::time::Duration;
use uuid::Uuid;
use serde::{de::DeserializeOwned, Serialize};

use crate::error::CdnResult;
use crate::config::RedisConfig;
use crate::models::{Heartbeat, NodeMetrics, EdgeNode};

#[derive(Clone)]
pub struct RedisClient {
    client: Client,
}

impl RedisClient {
    pub fn new(config: &RedisConfig) -> CdnResult<Self> {
        let client = Client::open(config.url.as_str())?;
        Ok(RedisClient { client })
    }

    pub async fn get_connection(&self) -> CdnResult<MultiplexedConnection> {
        let conn = self.client.get_multiplexed_async_connection().await?;
        Ok(conn)
    }

    pub async fn store_heartbeat(&self, heartbeat: &Heartbeat, ttl_seconds: u64) -> CdnResult<()> {
        let mut conn = self.get_connection().await?;
        let key = format!("heartbeat:{}", heartbeat.node_id);
        let value = serde_json::to_string(heartbeat)?;
        
        let _: () = conn.set_ex(key, value, ttl_seconds).await?;
        Ok(())
    }

    pub async fn get_heartbeat(&self, node_id: Uuid) -> CdnResult<Option<Heartbeat>> {
        let mut conn = self.get_connection().await?;
        let key = format!("heartbeat:{}", node_id);
        
        let value: Option<String> = conn.get(key).await?;
        match value {
            Some(v) => Ok(Some(serde_json::from_str(&v)?)),
            None => Ok(None),
        }
    }

    pub async fn store_node_metrics(&self, metrics: &NodeMetrics, ttl_seconds: u64) -> CdnResult<()> {
        let mut conn = self.get_connection().await?;
        let key = format!("metrics:latest:{}", metrics.node_id);
        let value = serde_json::to_string(metrics)?;
        let value_clone = value.clone();
        
        let _: () = conn.set_ex(key, value, ttl_seconds).await?;

        let history_key = format!("metrics:history:{}", metrics.node_id);
        let score = metrics.timestamp.timestamp() as f64;
        let _: () = conn.zadd(&history_key, value_clone, score).await?;
        
        Ok(())
    }

    pub async fn get_latest_metrics(&self, node_id: Uuid) -> CdnResult<Option<NodeMetrics>> {
        let mut conn = self.get_connection().await?;
        let key = format!("metrics:latest:{}", node_id);
        
        let value: Option<String> = conn.get(key).await?;
        match value {
            Some(v) => Ok(Some(serde_json::from_str(&v)?)),
            None => Ok(None),
        }
    }

    pub async fn store_node_status(&self, node: &EdgeNode, ttl_seconds: u64) -> CdnResult<()> {
        let mut conn = self.get_connection().await?;
        let key = format!("node:status:{}", node.id);
        let value = serde_json::to_string(node)?;
        
        let _: () = conn.set_ex(key, value, ttl_seconds).await?;
        
        let region_key = format!("node:region:{}", node.region);
        let _: () = conn.sadd(region_key, node.id.to_string()).await?;
        
        let _: () = conn.sadd("nodes:all", node.id.to_string()).await?;
        
        Ok(())
    }

    pub async fn get_node_status(&self, node_id: Uuid) -> CdnResult<Option<EdgeNode>> {
        let mut conn = self.get_connection().await?;
        let key = format!("node:status:{}", node_id);
        
        let value: Option<String> = conn.get(key).await?;
        match value {
            Some(v) => Ok(Some(serde_json::from_str(&v)?)),
            None => Ok(None),
        }
    }

    pub async fn set_config_value<T: Serialize>(&self, key: &str, value: &T, ttl: Option<Duration>) -> CdnResult<()> {
        let mut conn = self.get_connection().await?;
        let serialized = serde_json::to_string(value)?;
        
        match ttl {
            Some(duration) => {
                let _: () = conn.set_ex(key, serialized, duration.as_secs()).await?;
            }
            None => {
                let _: () = conn.set(key, serialized).await?;
            }
        }
        Ok(())
    }

    pub async fn get_config_value<T: DeserializeOwned>(&self, key: &str) -> CdnResult<Option<T>> {
        let mut conn = self.get_connection().await?;
        let value: Option<String> = conn.get(key).await?;
        
        match value {
            Some(v) => Ok(Some(serde_json::from_str(&v)?)),
            None => Ok(None),
        }
    }

    pub async fn publish(&self, channel: &str, message: &str) -> CdnResult<()> {
        let mut conn = self.get_connection().await?;
        let _: () = conn.publish(channel, message).await?;
        Ok(())
    }

    pub async fn acquire_lock(&self, key: &str, ttl_seconds: u64) -> CdnResult<bool> {
        let mut conn = self.get_connection().await?;
        let result: bool = conn.set_nx(key, "locked").await?;
        
        if result {
            let _: () = conn.expire(key, ttl_seconds as i64).await?;
        }
        
        Ok(result)
    }

    pub async fn release_lock(&self, key: &str) -> CdnResult<()> {
        let mut conn = self.get_connection().await?;
        let _: () = conn.del(key).await?;
        Ok(())
    }

    pub async fn increment_counter(&self, key: &str, amount: i64) -> CdnResult<i64> {
        let mut conn = self.get_connection().await?;
        let result: i64 = conn.incr(key, amount).await?;
        Ok(result)
    }

    pub async fn get_counter(&self, key: &str) -> CdnResult<i64> {
        let mut conn = self.get_connection().await?;
        let result: Option<i64> = conn.get(key).await?;
        Ok(result.unwrap_or(0))
    }

    pub async fn delete_key(&self, key: &str) -> CdnResult<()> {
        let mut conn = self.get_connection().await?;
        let _: () = conn.del(key).await?;
        Ok(())
    }

    pub async fn key_exists(&self, key: &str) -> CdnResult<bool> {
        let mut conn = self.get_connection().await?;
        let exists: bool = conn.exists(key).await?;
        Ok(exists)
    }
}
