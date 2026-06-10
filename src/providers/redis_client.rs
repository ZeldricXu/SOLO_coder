use std::time::Duration;

use redis::{AsyncCommands, Client};
use serde::{de::DeserializeOwned, Serialize};

use crate::{config::RedisConfig, utils::AppResult};

#[derive(Clone)]
pub struct RedisClient {
    client: Client,
    max_connections: u32,
}

impl RedisClient {
    pub async fn new(config: &RedisConfig) -> AppResult<Self> {
        let client = Client::open(config.url.as_str())?;
        Ok(Self {
            client,
            max_connections: config.max_connections,
        })
    }

    pub async fn with_url(url: &str, max_connections: u32) -> AppResult<Self> {
        let client = Client::open(url)?;
        Ok(Self {
            client,
            max_connections,
        })
    }

    async fn get_connection(&self) -> AppResult<redis::aio::MultiplexedConnection> {
        let conn = self.client.get_multiplexed_tokio_connection().await?;
        Ok(conn)
    }

    pub async fn get<T: DeserializeOwned>(&self, key: &str) -> AppResult<Option<T>> {
        let mut conn = self.get_connection().await?;
        let result: Option<String> = conn.get(key).await?;
        match result {
            Some(s) => {
                let value: T = serde_json::from_str(&s)?;
                Ok(Some(value))
            }
            None => Ok(None),
        }
    }

    pub async fn get_raw(&self, key: &str) -> AppResult<Option<String>> {
        let mut conn = self.get_connection().await?;
        let result: Option<String> = conn.get(key).await?;
        Ok(result)
    }

    pub async fn set<T: Serialize>(&self, key: &str, value: &T, expire: Option<Duration>) -> AppResult<()> {
        let mut conn = self.get_connection().await?;
        let serialized = serde_json::to_string(value)?;
        
        match expire {
            Some(dur) => {
                conn.set_ex(key, serialized, dur.as_secs() as usize).await?;
            }
            None => {
                conn.set(key, serialized).await?;
            }
        }
        Ok(())
    }

    pub async fn set_raw(&self, key: &str, value: &str, expire: Option<Duration>) -> AppResult<()> {
        let mut conn = self.get_connection().await?;
        match expire {
            Some(dur) => {
                conn.set_ex(key, value, dur.as_secs() as usize).await?;
            }
            None => {
                conn.set(key, value).await?;
            }
        }
        Ok(())
    }

    pub async fn set_ex<T: Serialize>(&self, key: &str, value: &T, seconds: u64) -> AppResult<()> {
        let mut conn = self.get_connection().await?;
        let serialized = serde_json::to_string(value)?;
        conn.set_ex(key, serialized, seconds as usize).await?;
        Ok(())
    }

    pub async fn set_ex_raw(&self, key: &str, value: &str, seconds: u64) -> AppResult<()> {
        let mut conn = self.get_connection().await?;
        conn.set_ex(key, value, seconds as usize).await?;
        Ok(())
    }

    pub async fn del(&self, key: &str) -> AppResult<()> {
        let mut conn = self.get_connection().await?;
        conn.del(key).await?;
        Ok(())
    }

    pub async fn exists(&self, key: &str) -> AppResult<bool> {
        let mut conn = self.get_connection().await?;
        let result: bool = conn.exists(key).await?;
        Ok(result)
    }

    pub async fn incr(&self, key: &str) -> AppResult<i64> {
        let mut conn = self.get_connection().await?;
        let result: i64 = conn.incr(key).await?;
        Ok(result)
    }

    pub async fn incr_by(&self, key: &str, amount: i64) -> AppResult<i64> {
        let mut conn = self.get_connection().await?;
        let result: i64 = conn.incr_by(key, amount).await?;
        Ok(result)
    }

    pub async fn acquire_lock(&self, lock_key: &str, expire: Duration) -> AppResult<bool> {
        let mut conn = self.get_connection().await?;
        let result: Option<String> = redis::cmd("SET")
            .arg(lock_key)
            .arg("1")
            .arg("NX")
            .arg("EX")
            .arg(expire.as_secs() as usize)
            .query_async(&mut conn)
            .await?;
        Ok(result.is_some())
    }

    pub async fn release_lock(&self, lock_key: &str) -> AppResult<()> {
        let mut conn = self.get_connection().await?;
        conn.del(lock_key).await?;
        Ok(())
    }

    pub async fn check_and_set_delivery_id(&self, delivery_id: &str, expire: Duration) -> AppResult<bool> {
        let key = format!("webhook:delivery:{}", delivery_id);
        let mut conn = self.get_connection().await?;
        let result: Option<String> = redis::cmd("SET")
            .arg(&key)
            .arg("1")
            .arg("NX")
            .arg("EX")
            .arg(expire.as_secs() as usize)
            .query_async(&mut conn)
            .await?;
        Ok(result.is_some())
    }
}
