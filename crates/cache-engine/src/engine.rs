use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

use common::error::{CdnResult, CdnError};
use common::models::{CacheEvictionPolicy, CacheRule};

use crate::cache::{Cache, CacheEntry};
use crate::policies::create_policy;
use crate::key_generator::CacheKeyGenerator;

pub struct CacheEngine {
    caches: Arc<RwLock<HashMap<String, Cache<String>>>>,
    key_generator: Arc<RwLock<CacheKeyGenerator>>,
    default_max_size_bytes: u64,
}

impl CacheEngine {
    pub fn new(default_max_size_bytes: u64) -> Self {
        CacheEngine {
            caches: Arc::new(RwLock::new(HashMap::new())),
            key_generator: Arc::new(RwLock::new(CacheKeyGenerator::new())),
            default_max_size_bytes,
        }
    }

    pub async fn add_cache(
        &self,
        name: String,
        policy: CacheEvictionPolicy,
        max_size_bytes: Option<u64>,
    ) -> CdnResult<()> {
        let mut caches = self.caches.write().await;
        
        if caches.contains_key(&name) {
            return Err(CdnError::CacheError(format!("Cache '{}' already exists", name)));
        }

        let max_size = max_size_bytes.unwrap_or(self.default_max_size_bytes);
        let eviction_policy = create_policy(policy);
        let cache = Cache::new(eviction_policy, max_size);
        
        caches.insert(name, cache);
        
        Ok(())
    }

    pub async fn remove_cache(&self, name: &str) -> bool {
        let mut caches = self.caches.write().await;
        caches.remove(name).is_some()
    }

    pub async fn get(
        &self,
        cache_name: &str,
        domain: &str,
        path: &str,
        query_params: &HashMap<String, String>,
        user_agent: Option<&str>,
        referer: Option<&str>,
    ) -> CdnResult<Option<CacheEntry>> {
        let key_gen = self.key_generator.read().await;
        let (cache_key, _) = key_gen.generate_key(domain, path, query_params, user_agent, referer);
        drop(key_gen);

        let mut caches = self.caches.write().await;
        let cache = caches.get_mut(cache_name)
            .ok_or_else(|| CdnError::CacheError(format!("Cache '{}' not found", cache_name)))?;

        Ok(cache.get(&cache_key).cloned())
    }

    pub async fn put(
        &self,
        cache_name: &str,
        domain: &str,
        path: &str,
        query_params: &HashMap<String, String>,
        user_agent: Option<&str>,
        referer: Option<&str>,
        content: Vec<u8>,
        content_type: String,
        custom_ttl: Option<u64>,
    ) -> CdnResult<String> {
        let key_gen = self.key_generator.read().await;
        let (cache_key, ttl) = key_gen.generate_key(domain, path, query_params, user_agent, referer);
        drop(key_gen);

        let final_ttl = custom_ttl.unwrap_or(ttl);

        let mut caches = self.caches.write().await;
        let cache = caches.get_mut(cache_name)
            .ok_or_else(|| CdnError::CacheError(format!("Cache '{}' not found", cache_name)))?;

        cache.insert(cache_key.clone(), content, content_type, final_ttl)?;
        
        Ok(cache_key)
    }

    pub async fn invalidate(
        &self,
        cache_name: &str,
        domain: &str,
        path_pattern: &str,
    ) -> CdnResult<usize> {
        let mut caches = self.caches.write().await;
        let cache = caches.get_mut(cache_name)
            .ok_or_else(|| CdnError::CacheError(format!("Cache '{}' not found", cache_name)))?;

        let keys_to_remove: Vec<String> = cache.keys()
            .filter(|k| {
                if path_pattern.contains('*') || path_pattern.contains("**") {
                    common::utils::match_path_pattern(path_pattern, k)
                } else {
                    k.contains(path_pattern)
                }
            })
            .cloned()
            .collect();

        let count = keys_to_remove.len();
        for key in keys_to_remove {
            cache.remove(&key);
        }

        Ok(count)
    }

    pub async fn clear_cache(&self, cache_name: &str) -> CdnResult<()> {
        let mut caches = self.caches.write().await;
        let cache = caches.get_mut(cache_name)
            .ok_or_else(|| CdnError::CacheError(format!("Cache '{}' not found", cache_name)))?;
        
        cache.clear();
        Ok(())
    }

    pub async fn add_cache_rule(&self, rule: CacheRule) {
        let mut key_gen = self.key_generator.write().await;
        key_gen.add_rule(rule);
    }

    pub async fn remove_cache_rule(&self, rule_id: &uuid::Uuid) {
        let mut key_gen = self.key_generator.write().await;
        key_gen.remove_rule(rule_id);
    }

    pub async fn get_cache_stats(&self, cache_name: &str) -> CdnResult<CacheStats> {
        let caches = self.caches.read().await;
        let cache = caches.get(cache_name)
            .ok_or_else(|| CdnError::CacheError(format!("Cache '{}' not found", cache_name)))?;

        Ok(CacheStats {
            hit_rate: cache.hit_rate(),
            entry_count: cache.entry_count(),
            size_bytes: cache.size_bytes(),
            hits: cache.hits(),
            misses: cache.misses(),
        })
    }

    pub async fn cleanup_expired(&self) -> CdnResult<HashMap<String, usize>> {
        let mut caches = self.caches.write().await;
        let mut results = HashMap::new();

        for (name, cache) in caches.iter_mut() {
            let cleaned = cache.cleanup_expired();
            results.insert(name.clone(), cleaned);
        }

        Ok(results)
    }

    pub async fn list_caches(&self) -> Vec<String> {
        let caches = self.caches.read().await;
        caches.keys().cloned().collect()
    }

    pub async fn get_cache_usage(&self, cache_name: &str) -> CdnResult<CacheUsage> {
        let caches = self.caches.read().await;
        let cache = caches.get(cache_name)
            .ok_or_else(|| CdnError::CacheError(format!("Cache '{}' not found", cache_name)))?;

        Ok(CacheUsage {
            used_bytes: cache.size_bytes(),
            max_bytes: cache.max_size_bytes(),
            available_bytes: cache.available_bytes(),
            usage_ratio: cache.usage_ratio(),
        })
    }

    pub async fn ensure_space_for_push(&self, cache_name: &str, content_size: u64) -> CdnResult<bool> {
        let mut caches = self.caches.write().await;
        let cache = caches.get_mut(cache_name)
            .ok_or_else(|| CdnError::CacheError(format!("Cache '{}' not found", cache_name)))?;

        if cache.available_bytes() >= content_size {
            return Ok(true);
        }

        if cache.usage_ratio() >= HIGH_WATERMARK_RATIO {
            let target_bytes = (cache.max_size_bytes() as f64 * LOW_WATERMARK_RATIO) as u64;
            cache.evict_to_target(target_bytes);
        }

        if cache.available_bytes() < content_size {
            let needed_bytes = content_size.saturating_sub(cache.available_bytes());
            let target_bytes = cache.size_bytes().saturating_sub(needed_bytes);
            cache.evict_to_target(target_bytes);
        }

        Ok(cache.available_bytes() >= content_size)
    }
}

impl Clone for CacheEngine {
    fn clone(&self) -> Self {
        CacheEngine {
            caches: self.caches.clone(),
            key_generator: self.key_generator.clone(),
            default_max_size_bytes: self.default_max_size_bytes,
        }
    }
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct CacheStats {
    pub hit_rate: f64,
    pub entry_count: usize,
    pub size_bytes: u64,
    pub hits: u64,
    pub misses: u64,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct CacheUsage {
    pub used_bytes: u64,
    pub max_bytes: u64,
    pub available_bytes: u64,
    pub usage_ratio: f64,
}

const HIGH_WATERMARK_RATIO: f64 = 0.9;
const LOW_WATERMARK_RATIO: f64 = 0.8;

#[cfg(test)]
mod tests {
    use super::*;
    use common::models::CacheEvictionPolicy;

    #[tokio::test]
    async fn test_get_cache_usage() {
        let engine = CacheEngine::new(1000);
        engine.add_cache("test".to_string(), CacheEvictionPolicy::LRU, Some(1000)).await.unwrap();
        
        let usage = engine.get_cache_usage("test").await.unwrap();
        assert_eq!(usage.used_bytes, 0);
        assert_eq!(usage.max_bytes, 1000);
        assert_eq!(usage.available_bytes, 1000);
        assert!((usage.usage_ratio - 0.0).abs() < 0.001);
    }

    #[tokio::test]
    async fn test_ensure_space_for_push_with_available_space() {
        let engine = CacheEngine::new(1000);
        engine.add_cache("test".to_string(), CacheEvictionPolicy::LRU, Some(1000)).await.unwrap();
        
        let result = engine.ensure_space_for_push("test", 500).await.unwrap();
        assert!(result);
    }

    #[tokio::test]
    async fn test_ensure_space_for_push_with_eviction() {
        let engine = CacheEngine::new(1000);
        engine.add_cache("test".to_string(), CacheEvictionPolicy::LRU, Some(1000)).await.unwrap();
        
        let params: HashMap<String, String> = HashMap::new();
        for i in 0..5 {
            let content = vec![0u8; 200];
            engine.put("test", "example.com", &format!("/path{}", i), &params, None, None, content, "text/plain".to_string(), None).await.unwrap();
        }
        
        let stats = engine.get_cache_stats("test").await.unwrap();
        assert_eq!(stats.size_bytes, 1000);
        
        let result = engine.ensure_space_for_push("test", 300).await.unwrap();
        assert!(result);
        
        let usage = engine.get_cache_usage("test").await.unwrap();
        assert!(usage.available_bytes >= 300);
        assert!(usage.usage_ratio <= 0.8);
    }

    #[tokio::test]
    async fn test_ensure_space_for_push_high_watermark_trigger() {
        let engine = CacheEngine::new(1000);
        engine.add_cache("test".to_string(), CacheEvictionPolicy::LRU, Some(1000)).await.unwrap();
        
        let params: HashMap<String, String> = HashMap::new();
        for i in 0..5 {
            let content = vec![0u8; 200];
            engine.put("test", "example.com", &format!("/path{}", i), &params, None, None, content, "text/plain".to_string(), None).await.unwrap();
        }
        
        let result = engine.ensure_space_for_push("test", 100).await.unwrap();
        assert!(result);
        
        let usage = engine.get_cache_usage("test").await.unwrap();
        assert!(usage.usage_ratio <= 0.8);
    }

    #[tokio::test]
    async fn test_ensure_space_for_push_impossible() {
        let engine = CacheEngine::new(1000);
        engine.add_cache("test".to_string(), CacheEvictionPolicy::LRU, Some(1000)).await.unwrap();
        
        let result = engine.ensure_space_for_push("test", 1500).await.unwrap();
        assert!(!result);
    }
}
