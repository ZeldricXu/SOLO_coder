use redis::{aio::ConnectionManager, AsyncCommands, Client, RedisResult};

use crate::infra::config::RedisConfig;
use crate::infra::error::{AppError, AppResult};

#[derive(Clone)]
pub struct Cache {
    client: Client,
    connection_manager: ConnectionManager,
}

impl Cache {
    pub async fn new(config: &RedisConfig) -> AppResult<Self> {
        let client = Client::open(config.url.as_str())
            .map_err(|e| AppError::CacheError(format!("Failed to create Redis client: {}", e)))?;

        let connection_manager = ConnectionManager::new(client.clone())
            .await
            .map_err(|e| AppError::CacheError(format!("Failed to create connection manager: {}", e)))?;

        Ok(Self {
            client,
            connection_manager,
        })
    }

    pub async fn get(&self, key: &str) -> AppResult<Option<String>> {
        let mut con = self.connection_manager.clone();
        let result: RedisResult<Option<String>> = con.get(key).await;
        result.map_err(|e| AppError::CacheError(format!("Redis get error: {}", e)))
    }

    pub async fn set(&self, key: &str, value: &str, ttl_seconds: u64) -> AppResult<()> {
        let mut con = self.connection_manager.clone();
        let _: () = con
            .set_ex(key, value, ttl_seconds as usize)
            .await
            .map_err(|e| AppError::CacheError(format!("Redis set error: {}", e)))?;
        Ok(())
    }

    pub async fn delete(&self, key: &str) -> AppResult<()> {
        let mut con = self.connection_manager.clone();
        let _: () = con
            .del(key)
            .await
            .map_err(|e| AppError::CacheError(format!("Redis delete error: {}", e)))?;
        Ok(())
    }

    pub async fn exists(&self, key: &str) -> AppResult<bool> {
        let mut con = self.connection_manager.clone();
        let result: bool = con
            .exists(key)
            .await
            .map_err(|e| AppError::CacheError(format!("Redis exists error: {}", e)))?;
        Ok(result)
    }

    pub async fn increment(&self, key: &str) -> AppResult<i64> {
        let mut con = self.connection_manager.clone();
        let result: i64 = con
            .incr(key, 1)
            .await
            .map_err(|e| AppError::CacheError(format!("Redis incr error: {}", e)))?;
        Ok(result)
    }

    pub async fn increment_by(&self, key: &str, value: i64) -> AppResult<i64> {
        let mut con = self.connection_manager.clone();
        let result: i64 = con
            .incr(key, value)
            .await
            .map_err(|e| AppError::CacheError(format!("Redis incrby error: {}", e)))?;
        Ok(result)
    }

    pub async fn expire(&self, key: &str, ttl_seconds: u64) -> AppResult<()> {
        let mut con = self.connection_manager.clone();
        let _: () = con
            .expire(key, ttl_seconds as usize)
            .await
            .map_err(|e| AppError::CacheError(format!("Redis expire error: {}", e)))?;
        Ok(())
    }
}
