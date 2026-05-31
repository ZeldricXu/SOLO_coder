use std::sync::Arc;
use async_trait::async_trait;
use tokio::sync::RwLock;
use std::collections::HashMap;
use std::time::{Duration, Instant};

use crate::common::error::AppResult;
use crate::ports::mod::CachePort;

struct CacheEntry {
    value: String,
    expires_at: Option<Instant>,
}

pub struct InMemoryCache {
    data: Arc<RwLock<HashMap<String, CacheEntry>>>,
}

impl InMemoryCache {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            data: Arc::new(RwLock::new(HashMap::new())),
        })
    }

    async fn cleanup_expired(&self) {
        let mut data = self.data.write().await;
        let now = Instant::now();
        data.retain(|_, entry| {
            entry.expires_at.map(|e| e > now).unwrap_or(true)
        });
    }
}

#[async_trait]
impl CachePort for InMemoryCache {
    async fn get(&self, key: &str) -> AppResult<Option<String>> {
        self.cleanup_expired().await;
        let data = self.data.read().await;
        Ok(data.get(key).map(|e| e.value.clone()))
    }

    async fn set(&self, key: &str, value: &str, ttl_seconds: Option<u64>) -> AppResult<()> {
        let mut data = self.data.write().await;
        let expires_at = ttl_seconds.map(|ttl| Instant::now() + Duration::from_secs(ttl));
        data.insert(key.to_string(), CacheEntry {
            value: value.to_string(),
            expires_at,
        });
        Ok(())
    }

    async fn delete(&self, key: &str) -> AppResult<()> {
        let mut data = self.data.write().await;
        data.remove(key);
        Ok(())
    }

    async fn exists(&self, key: &str) -> AppResult<bool> {
        self.cleanup_expired().await;
        let data = self.data.read().await;
        Ok(data.contains_key(key))
    }

    async fn publish(&self, _channel: &str, _message: &str) -> AppResult<u64> {
        Ok(0)
    }
}
