use serde::{de::DeserializeOwned, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use lru::LruCache;
use parking_lot::Mutex;
use tracing::{debug, info, warn};

use crate::infra::cache::Cache;
use crate::infra::error::{AppError, AppResult};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheConfig {
    pub l1_max_size: usize,
    pub l1_ttl_seconds: u64,
    pub l2_ttl_seconds: u64,
    pub l2_enabled: bool,
    pub cache_name: String,
}

impl Default for CacheConfig {
    fn default() -> Self {
        Self {
            l1_max_size: 1000,
            l1_ttl_seconds: 60,
            l2_ttl_seconds: 300,
            l2_enabled: true,
            cache_name: "default".to_string(),
        }
    }
}

#[derive(Debug, Clone)]
struct CacheEntry<V> {
    value: V,
    expires_at: Instant,
}

pub struct MultiLevelCache<V>
where
    V: Clone + Serialize + DeserializeOwned + Send + Sync + 'static,
{
    config: CacheConfig,
    l1_cache: Arc<Mutex<LruCache<String, CacheEntry<V>>>>,
    l2_cache: Option<Arc<Cache>>,
    stats: Arc<Mutex<CacheStats>>,
}

#[derive(Debug, Clone, Default, Serialize)]
pub struct CacheStats {
    pub l1_hits: u64,
    pub l1_misses: u64,
    pub l2_hits: u64,
    pub l2_misses: u64,
    pub l1_evictions: u64,
    pub l2_writes: u64,
    pub l2_deletes: u64,
    pub total_requests: u64,
}

impl CacheStats {
    pub fn hit_rate(&self) -> f64 {
        if self.total_requests == 0 {
            0.0
        } else {
            (self.l1_hits + self.l2_hits) as f64 / self.total_requests as f64
        }
    }

    pub fn l1_hit_rate(&self) -> f64 {
        if self.l1_hits + self.l1_misses == 0 {
            0.0
        } else {
            self.l1_hits as f64 / (self.l1_hits + self.l1_misses) as f64
        }
    }

    pub fn l2_hit_rate(&self) -> f64 {
        if self.l2_hits + self.l2_misses == 0 {
            0.0
        } else {
            self.l2_hits as f64 / (self.l2_hits + self.l2_misses) as f64
        }
    }

    fn record_l1_hit(&mut self) {
        self.l1_hits += 1;
        self.total_requests += 1;
    }

    fn record_l1_miss(&mut self) {
        self.l1_misses += 1;
    }

    fn record_l2_hit(&mut self) {
        self.l2_hits += 1;
        self.total_requests += 1;
    }

    fn record_l2_miss(&mut self) {
        self.l2_misses += 1;
        self.total_requests += 1;
    }

    fn record_l1_eviction(&mut self) {
        self.l1_evictions += 1;
    }

    fn record_l2_write(&mut self) {
        self.l2_writes += 1;
    }

    fn record_l2_delete(&mut self) {
        self.l2_deletes += 1;
    }
}

impl<V> MultiLevelCache<V>
where
    V: Clone + Serialize + DeserializeOwned + Send + Sync + 'static,
{
    pub fn new(config: CacheConfig) -> Self {
        let l1_cache = LruCache::new(config.l1_max_size.try_into().unwrap_or(1000));
        
        Self {
            config,
            l1_cache: Arc::new(Mutex::new(l1_cache)),
            l2_cache: None,
            stats: Arc::new(Mutex::new(CacheStats::default())),
        }
    }

    pub fn with_l2(config: CacheConfig, l2_cache: Arc<Cache>) -> Self {
        let mut cache = Self::new(config);
        cache.l2_cache = Some(l2_cache);
        cache
    }

    pub async fn get(&self, key: &str) -> AppResult<Option<V>> {
        let cache_key = self.build_key(key);
        
        if let Some(entry) = self.get_l1(&cache_key) {
            self.stats.lock().record_l1_hit();
            debug!("[{}] L1 cache hit for key: {}", self.config.cache_name, key);
            return Ok(Some(entry));
        }
        self.stats.lock().record_l1_miss();

        if let Some(l2) = &self.l2_cache {
            if let Some(value_str) = l2.get(&cache_key).await? {
                let value: V = serde_json::from_str(&value_str)
                    .map_err(|e| AppError::CacheError(format!("Failed to deserialize L2 value: {}", e)))?;
                
                let ttl = Duration::from_secs(self.config.l1_ttl_seconds);
                self.put_l1(&cache_key, value.clone(), ttl);
                
                self.stats.lock().record_l2_hit();
                debug!("[{}] L2 cache hit for key: {}", self.config.cache_name, key);
                return Ok(Some(value));
            }
            self.stats.lock().record_l2_miss();
        } else {
            self.stats.lock().record_l2_miss();
        }

        debug!("[{}] Cache miss for key: {}", self.config.cache_name, key);
        Ok(None)
    }

    pub async fn put(&self, key: &str, value: V, ttl_seconds: Option<u64>) -> AppResult<()> {
        let cache_key = self.build_key(key);
        let ttl = Duration::from_secs(ttl_seconds.unwrap_or(self.config.l1_ttl_seconds));
        
        self.put_l1(&cache_key, value.clone(), ttl);

        if let Some(l2) = &self.l2_cache {
            let value_str = serde_json::to_string(&value)
                .map_err(|e| AppError::CacheError(format!("Failed to serialize value for L2: {}", e)))?;
            
            let l2_ttl = ttl_seconds.unwrap_or(self.config.l2_ttl_seconds);
            l2.set(&cache_key, &value_str, l2_ttl).await?;
            self.stats.lock().record_l2_write();
        }

        debug!("[{}] Cache put for key: {}, TTL: {:?}s", self.config.cache_name, key, ttl_seconds);
        Ok(())
    }

    pub async fn delete(&self, key: &str) -> AppResult<()> {
        let cache_key = self.build_key(key);
        
        self.delete_l1(&cache_key);
        
        if let Some(l2) = &self.l2_cache {
            l2.delete(&cache_key).await?;
            self.stats.lock().record_l2_delete();
        }

        debug!("[{}] Cache delete for key: {}", self.config.cache_name, key);
        Ok(())
    }

    pub async fn invalidate_pattern(&self, pattern: &str) -> AppResult<()> {
        let mut l1 = self.l1_cache.lock();
        let keys_to_remove: Vec<String> = l1
            .iter()
            .filter(|(k, _)| k.contains(pattern))
            .map(|(k, _)| k.clone())
            .collect();
        
        for key in keys_to_remove {
            l1.pop(&key);
        }
        
        info!("[{}] Invalidated {} L1 entries matching pattern: {}", 
              self.config.cache_name, keys_to_remove.len(), pattern);

        Ok(())
    }

    pub async fn warm_up(&self, entries: Vec<(String, V)>, ttl_seconds: Option<u64>) -> AppResult<usize> {
        let mut count = 0;
        for (key, value) in entries {
            self.put(&key, value, ttl_seconds).await?;
            count += 1;
        }
        info!("[{}] Cache warm-up completed, loaded {} entries", self.config.cache_name, count);
        Ok(count)
    }

    pub fn stats(&self) -> CacheStats {
        self.stats.lock().clone()
    }

    pub fn reset_stats(&self) {
        *self.stats.lock() = CacheStats::default();
        info!("[{}] Cache stats reset", self.config.cache_name);
    }

    pub async fn clear(&self) -> AppResult<()> {
        self.l1_cache.lock().clear();
        
        if let Some(l2) = &self.l2_cache {
            let pattern = format!("{}:*", self.config.cache_name);
            warn!("[{}] L2 cache clear pattern: {}", self.config.cache_name, pattern);
        }

        info!("[{}] Cache cleared", self.config.cache_name);
        Ok(())
    }

    fn get_l1(&self, key: &str) -> Option<V> {
        let mut l1 = self.l1_cache.lock();
        if let Some(entry) = l1.get(key) {
            if entry.expires_at > Instant::now() {
                return Some(entry.value.clone());
            } else {
                l1.pop(key);
                self.stats.lock().record_l1_eviction();
            }
        }
        None
    }

    fn put_l1(&self, key: &str, value: V, ttl: Duration) {
        let mut l1 = self.l1_cache.lock();
        
        if l1.len() >= self.config.l1_max_size {
            if let Some((evicted_key, _)) = l1.pop_lru() {
                debug!("[{}] L1 evicted key: {}", self.config.cache_name, evicted_key);
                self.stats.lock().record_l1_eviction();
            }
        }

        let entry = CacheEntry {
            value,
            expires_at: Instant::now() + ttl,
        };
        l1.put(key.to_string(), entry);
    }

    fn delete_l1(&self, key: &str) {
        let mut l1 = self.l1_cache.lock();
        l1.pop(key);
    }

    fn build_key(&self, key: &str) -> String {
        format!("{}:{}", self.config.cache_name, key)
    }
}

impl<V> Clone for MultiLevelCache<V>
where
    V: Clone + Serialize + DeserializeOwned + Send + Sync + 'static,
{
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            l1_cache: Arc::clone(&self.l1_cache),
            l2_cache: self.l2_cache.clone(),
            stats: Arc::clone(&self.stats),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_l1_cache_basic_operations() {
        let config = CacheConfig {
            l1_max_size: 100,
            l1_ttl_seconds: 60,
            l2_ttl_seconds: 300,
            l2_enabled: false,
            cache_name: "test".to_string(),
        };

        let cache: MultiLevelCache<String> = MultiLevelCache::new(config);
        
        assert!(cache.get("key1").await.unwrap().is_none());
        
        cache.put("key1", "value1".to_string(), None).await.unwrap();
        
        let result = cache.get("key1").await.unwrap();
        assert_eq!(result, Some("value1".to_string()));
        
        cache.delete("key1").await.unwrap();
        assert!(cache.get("key1").await.unwrap().is_none());
    }

    #[tokio::test]
    async fn test_l1_cache_ttl_expiry() {
        let config = CacheConfig {
            l1_max_size: 100,
            l1_ttl_seconds: 1,
            l2_ttl_seconds: 300,
            l2_enabled: false,
            cache_name: "test_ttl".to_string(),
        };

        let cache: MultiLevelCache<String> = MultiLevelCache::new(config);
        
        cache.put("key1", "value1".to_string(), Some(1)).await.unwrap();
        assert_eq!(cache.get("key1").await.unwrap(), Some("value1".to_string()));
        
        tokio::time::sleep(Duration::from_secs(2)).await;
        assert!(cache.get("key1").await.unwrap().is_none());
    }

    #[tokio::test]
    async fn test_l1_cache_lru_eviction() {
        let config = CacheConfig {
            l1_max_size: 3,
            l1_ttl_seconds: 60,
            l2_ttl_seconds: 300,
            l2_enabled: false,
            cache_name: "test_lru".to_string(),
        };

        let cache: MultiLevelCache<String> = MultiLevelCache::new(config);
        
        cache.put("key1", "value1".to_string(), None).await.unwrap();
        cache.put("key2", "value2".to_string(), None).await.unwrap();
        cache.put("key3", "value3".to_string(), None).await.unwrap();
        
        cache.get("key1").await.unwrap();
        
        cache.put("key4", "value4".to_string(), None).await.unwrap();
        
        assert!(cache.get("key2").await.unwrap().is_none());
        assert_eq!(cache.get("key1").await.unwrap(), Some("value1".to_string()));
        assert_eq!(cache.get("key3").await.unwrap(), Some("value3".to_string()));
        assert_eq!(cache.get("key4").await.unwrap(), Some("value4".to_string()));
    }

    #[tokio::test]
    async fn test_cache_stats() {
        let config = CacheConfig {
            l1_max_size: 100,
            l1_ttl_seconds: 60,
            l2_ttl_seconds: 300,
            l2_enabled: false,
            cache_name: "test_stats".to_string(),
        };

        let cache: MultiLevelCache<String> = MultiLevelCache::new(config);
        
        cache.put("key1", "value1".to_string(), None).await.unwrap();
        
        cache.get("key1").await.unwrap();
        cache.get("key2").await.unwrap();
        cache.get("key1").await.unwrap();
        
        let stats = cache.stats();
        assert_eq!(stats.l1_hits, 2);
        assert_eq!(stats.l1_misses, 1);
        assert_eq!(stats.total_requests, 3);
        assert_eq!(stats.hit_rate(), 2.0 / 3.0);
    }
}
