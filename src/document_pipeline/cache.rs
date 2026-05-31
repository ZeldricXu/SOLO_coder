use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use parking_lot::RwLock;
use tracing::{info, debug};

use crate::utils::metrics::MetricsCollector;
use super::parser::{Document, DocumentFormat};
use super::splitter::Chunk;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum CacheTier {
    L1,
    L2,
    Both,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CacheEvictionPolicy {
    LRU,
    LFU,
    FIFO,
    TTL,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheConfig {
    pub l1_max_entries: usize,
    pub l1_ttl_seconds: u64,
    pub l2_max_entries: usize,
    pub l2_ttl_seconds: u64,
    pub eviction_policy: CacheEvictionPolicy,
    pub enable_prefetch: bool,
    pub prefetch_threshold: f32,
    pub warmup_on_start: bool,
    pub warmup_keys: Vec<String>,
}

impl Default for CacheConfig {
    fn default() -> Self {
        Self {
            l1_max_entries: 1000,
            l1_ttl_seconds: 300,
            l2_max_entries: 10000,
            l2_ttl_seconds: 3600,
            eviction_policy: CacheEvictionPolicy::LRU,
            enable_prefetch: true,
            prefetch_threshold: 0.7,
            warmup_on_start: false,
            warmup_keys: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheEntry<T> {
    pub key: String,
    pub value: T,
    pub created_at: DateTime<Utc>,
    pub accessed_at: DateTime<Utc>,
    pub access_count: u64,
    pub tier: CacheTier,
    pub ttl_seconds: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineCacheValue {
    pub document: Document,
    pub chunks: Vec<Chunk>,
    pub format: DocumentFormat,
    pub content_hash: String,
    pub processing_time_ms: u64,
    pub tier: CacheTier,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheStats {
    pub l1_hits: u64,
    pub l1_misses: u64,
    pub l2_hits: u64,
    pub l2_misses: u64,
    pub l1_size: usize,
    pub l2_size: usize,
    pub total_evictions: u64,
    pub hit_rate: f64,
    pub avg_latency_saved_ms: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheInvalidationRequest {
    pub keys: Vec<String>,
    pub invalidate_pattern: Option<String>,
    pub tier: CacheTier,
}

struct L1Cache {
    entries: Arc<DashMap<String, CacheEntry<PipelineCacheValue>>>,
    access_order: Arc<RwLock<Vec<String>>>,
    config: CacheConfig,
}

impl L1Cache {
    fn new(config: CacheConfig) -> Self {
        Self {
            entries: Arc::new(DashMap::new()),
            access_order: Arc::new(RwLock::new(Vec::new())),
            config,
        }
    }

    fn get(&self, key: &str) -> Option<CacheEntry<PipelineCacheValue>> {
        if let Some(mut entry) = self.entries.get_mut(key) {
            if self.is_expired(&entry) {
                self.entries.remove(key);
                return None;
            }
            entry.accessed_at = Utc::now();
            entry.access_count += 1;
            entry.value.tier = CacheTier::L1;
            
            let mut order = self.access_order.write();
            if let Some(pos) = order.iter().position(|k| k == key) {
                order.remove(pos);
            }
            order.push(key.to_string());
            
            Some(entry.clone())
        } else {
            None
        }
    }

    fn insert(&self, key: String, mut value: PipelineCacheValue) {
        self.evict_if_needed();
        
        value.tier = CacheTier::L1;
        let entry = CacheEntry {
            key: key.clone(),
            value,
            created_at: Utc::now(),
            accessed_at: Utc::now(),
            access_count: 1,
            tier: CacheTier::L1,
            ttl_seconds: self.config.l1_ttl_seconds,
        };
        
        self.entries.insert(key.clone(), entry);
        
        let mut order = self.access_order.write();
        order.push(key);
    }

    fn invalidate(&self, key: &str) {
        self.entries.remove(key);
        let mut order = self.access_order.write();
        if let Some(pos) = order.iter().position(|k| k == key) {
            order.remove(pos);
        }
    }

    fn invalidate_pattern(&self, pattern: &str) -> usize {
        let keys_to_remove: Vec<String> = self.entries
            .iter()
            .filter(|entry| entry.key().contains(pattern))
            .map(|entry| entry.key().clone())
            .collect();
        
        let count = keys_to_remove.len();
        for key in &keys_to_remove {
            self.invalidate(key);
        }
        count
    }

    fn clear(&self) {
        self.entries.clear();
        self.access_order.write().clear();
    }

    fn is_expired(&self, entry: &CacheEntry<PipelineCacheValue>) -> bool {
        let expiry = entry.created_at + chrono::Duration::seconds(entry.ttl_seconds as i64);
        Utc::now() > expiry
    }

    fn evict_if_needed(&self) {
        while self.entries.len() >= self.config.l1_max_entries {
            let evicted_key = {
                let mut order = self.access_order.write();
                if order.is_empty() {
                    break;
                }
                order.remove(0)
            };
            
            self.entries.remove(&evicted_key);
            debug!("L1 cache evicted key: {}", evicted_key);
        }
    }

    fn len(&self) -> usize {
        self.entries.len()
    }
}

struct L2Cache {
    entries: Arc<DashMap<String, CacheEntry<PipelineCacheValue>>>,
    access_frequency: Arc<DashMap<String, u64>>,
    config: CacheConfig,
}

impl L2Cache {
    fn new(config: CacheConfig) -> Self {
        Self {
            entries: Arc::new(DashMap::new()),
            access_frequency: Arc::new(DashMap::new()),
            config,
        }
    }

    fn get(&self, key: &str) -> Option<CacheEntry<PipelineCacheValue>> {
        if let Some(mut entry) = self.entries.get_mut(key) {
            if self.is_expired(&entry) {
                self.entries.remove(key);
                self.access_frequency.remove(key);
                return None;
            }
            entry.accessed_at = Utc::now();
            entry.access_count += 1;
            entry.value.tier = CacheTier::L2;
            
            *self.access_frequency.entry(key.to_string()).or_insert(0) += 1;
            
            Some(entry.clone())
        } else {
            None
        }
    }

    fn insert(&self, key: String, mut value: PipelineCacheValue) {
        self.evict_if_needed();
        
        value.tier = CacheTier::L2;
        let entry = CacheEntry {
            key: key.clone(),
            value,
            created_at: Utc::now(),
            accessed_at: Utc::now(),
            access_count: 1,
            tier: CacheTier::L2,
            ttl_seconds: self.config.l2_ttl_seconds,
        };
        
        self.entries.insert(key.clone(), entry);
        self.access_frequency.insert(key, 1);
    }

    fn invalidate(&self, key: &str) {
        self.entries.remove(key);
        self.access_frequency.remove(key);
    }

    fn invalidate_pattern(&self, pattern: &str) -> usize {
        let keys_to_remove: Vec<String> = self.entries
            .iter()
            .filter(|entry| entry.key().contains(pattern))
            .map(|entry| entry.key().clone())
            .collect();
        
        let count = keys_to_remove.len();
        for key in &keys_to_remove {
            self.invalidate(key);
        }
        count
    }

    fn clear(&self) {
        self.entries.clear();
        self.access_frequency.clear();
    }

    fn is_expired(&self, entry: &CacheEntry<PipelineCacheValue>) -> bool {
        let expiry = entry.created_at + chrono::Duration::seconds(entry.ttl_seconds as i64);
        Utc::now() > expiry
    }

    fn evict_if_needed(&self) {
        while self.entries.len() >= self.config.l2_max_entries {
            let mut min_freq = u64::MAX;
            let mut evict_key = None;
            
            for entry in self.access_frequency.iter() {
                if *entry.value() < min_freq {
                    min_freq = *entry.value();
                    evict_key = Some(entry.key().clone());
                }
            }
            
            if let Some(key) = evict_key {
                self.entries.remove(&key);
                self.access_frequency.remove(&key);
                debug!("L2 cache evicted key: {}", key);
            } else {
                break;
            }
        }
    }

    fn len(&self) -> usize {
        self.entries.len()
    }
}

pub struct MultiLevelCache {
    l1: L1Cache,
    l2: L2Cache,
    config: CacheConfig,
    metrics: MetricsCollector,
    l1_hits: Arc<std::sync::atomic::AtomicU64>,
    l1_misses: Arc<std::sync::atomic::AtomicU64>,
    l2_hits: Arc<std::sync::atomic::AtomicU64>,
    l2_misses: Arc<std::sync::atomic::AtomicU64>,
    total_evictions: Arc<std::sync::atomic::AtomicU64>,
    total_latency_saved: Arc<std::sync::atomic::AtomicU64>,
    prefetch_queue: Arc<RwLock<Vec<String>>>,
}

impl MultiLevelCache {
    pub fn new(config: CacheConfig, metrics: MetricsCollector) -> Self {
        let cache = Self {
            l1: L1Cache::new(config.clone()),
            l2: L2Cache::new(config.clone()),
            config,
            metrics,
            l1_hits: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            l1_misses: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            l2_hits: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            l2_misses: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            total_evictions: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            total_latency_saved: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            prefetch_queue: Arc::new(RwLock::new(Vec::new())),
        };
        
        if cache.config.warmup_on_start {
            cache.warmup();
        }
        
        cache
    }

    pub async fn get(&self, key: &str) -> Option<PipelineCacheValue> {
        debug!("Cache lookup for key: {}", key);
        
        if let Some(entry) = self.l1.get(key) {
            self.l1_hits.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            self.metrics.increment_counter("cache_l1_hit");
            self.record_latency_saved(entry.value.processing_time_ms);
            debug!("L1 cache hit for key: {}", key);
            
            if self.config.enable_prefetch {
                self.schedule_prefetch(key);
            }
            
            return Some(entry.value);
        }
        
        self.l1_misses.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.metrics.increment_counter("cache_l1_miss");
        
        if let Some(entry) = self.l2.get(key) {
            self.l2_hits.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            self.metrics.increment_counter("cache_l2_hit");
            self.record_latency_saved(entry.value.processing_time_ms);
            debug!("L2 cache hit for key: {}, promoting to L1", key);
            
            let mut l1_value = entry.value.clone();
            l1_value.tier = CacheTier::L1;
            self.l1.insert(key.to_string(), l1_value);
            
            let mut result = entry.value;
            result.tier = CacheTier::L2;
            return Some(result);
        }
        
        self.l2_misses.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.metrics.increment_counter("cache_l2_miss");
        debug!("Cache miss for key: {}", key);
        
        None
    }

    pub async fn insert(&self, key: String, mut value: PipelineCacheValue, tier: CacheTier) {
        debug!("Inserting into cache: key={}, tier={:?}", key, tier);
        
        match tier {
            CacheTier::L1 => {
                value.tier = CacheTier::L1;
                self.l1.insert(key, value);
            }
            CacheTier::L2 => {
                value.tier = CacheTier::L2;
                self.l2.insert(key, value);
            }
            CacheTier::Both => {
                let mut l2_value = value.clone();
                l2_value.tier = CacheTier::L2;
                self.l2.insert(key.clone(), l2_value);
                
                let mut l1_value = value;
                l1_value.tier = CacheTier::L1;
                self.l1.insert(key, l1_value);
            }
        }
        
        self.metrics.increment_counter("cache_insert");
    }

    pub fn invalidate(&self, request: CacheInvalidationRequest) -> usize {
        let mut invalidated = 0;
        
        match request.tier {
            CacheTier::L1 => {
                for key in &request.keys {
                    self.l1.invalidate(key);
                    invalidated += 1;
                }
                if let Some(pattern) = &request.invalidate_pattern {
                    invalidated += self.l1.invalidate_pattern(pattern);
                }
            }
            CacheTier::L2 => {
                for key in &request.keys {
                    self.l2.invalidate(key);
                    invalidated += 1;
                }
                if let Some(pattern) = &request.invalidate_pattern {
                    invalidated += self.l2.invalidate_pattern(pattern);
                }
            }
            CacheTier::Both => {
                for key in &request.keys {
                    self.l1.invalidate(key);
                    self.l2.invalidate(key);
                    invalidated += 2;
                }
                if let Some(pattern) = &request.invalidate_pattern {
                    invalidated += self.l1.invalidate_pattern(pattern);
                    invalidated += self.l2.invalidate_pattern(pattern);
                }
            }
        }
        
        info!("Cache invalidation complete, {} entries removed", invalidated);
        self.metrics.increment_counter("cache_invalidation");
        invalidated
    }

    pub fn clear(&self, tier: CacheTier) {
        match tier {
            CacheTier::L1 => self.l1.clear(),
            CacheTier::L2 => self.l2.clear(),
            CacheTier::Both => {
                self.l1.clear();
                self.l2.clear();
            }
        }
        info!("Cache cleared for tier: {:?}", tier);
    }

    pub fn stats(&self) -> CacheStats {
        let l1_hits = self.l1_hits.load(std::sync::atomic::Ordering::Relaxed);
        let l1_misses = self.l1_misses.load(std::sync::atomic::Ordering::Relaxed);
        let l2_hits = self.l2_hits.load(std::sync::atomic::Ordering::Relaxed);
        let l2_misses = self.l2_misses.load(std::sync::atomic::Ordering::Relaxed);
        
        let total_requests = l1_hits + l1_misses;
        let hit_rate = if total_requests > 0 {
            (l1_hits + l2_hits) as f64 / total_requests as f64
        } else {
            0.0
        };
        
        let total_latency_saved = self.total_latency_saved.load(std::sync::atomic::Ordering::Relaxed);
        let total_hits = l1_hits + l2_hits;
        let avg_latency_saved_ms = if total_hits > 0 {
            total_latency_saved as f64 / total_hits as f64
        } else {
            0.0
        };
        
        CacheStats {
            l1_hits,
            l1_misses,
            l2_hits,
            l2_misses,
            l1_size: self.l1.len(),
            l2_size: self.l2.len(),
            total_evictions: self.total_evictions.load(std::sync::atomic::Ordering::Relaxed),
            hit_rate,
            avg_latency_saved_ms,
        }
    }

    pub fn warmup(&self) {
        info!("Starting cache warmup with {} keys", self.config.warmup_keys.len());
        let keys = self.config.warmup_keys.clone();
        let mut queue = self.prefetch_queue.write();
        queue.extend(keys);
    }

    pub async fn prefetch(&self, keys: Vec<String>) {
        info!("Prefetching {} keys into cache", keys.len());
        for key in keys {
            self.prefetch_queue.write().push(key);
        }
    }

    fn schedule_prefetch(&self, current_key: &str) {
        let hit_rate = self.stats().hit_rate;
        if hit_rate >= self.config.prefetch_threshold {
            debug!("Prefetch threshold reached, scheduling prefetch for related keys");
            let mut queue = self.prefetch_queue.write();
            if let Some(base_key) = current_key.split(':').next() {
                queue.push(format!("{}:next", base_key));
            }
        }
    }

    fn record_latency_saved(&self, processing_time_ms: u64) {
        self.total_latency_saved.fetch_add(processing_time_ms, std::sync::atomic::Ordering::Relaxed);
    }

    pub fn generate_cache_key(data: &[u8], format: &DocumentFormat, namespace: &str) -> String {
        use sha2::{Sha256, Digest};
        let mut hasher = Sha256::new();
        hasher.update(data);
        hasher.update(format!("{:?}", format).as_bytes());
        hasher.update(namespace.as_bytes());
        let hash = format!("{:x}", hasher.finalize());
        format!("doc:{}", hash)
    }

    pub fn config(&self) -> &CacheConfig {
        &self.config
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::document_pipeline::parser::{DocumentFormat, Document};

    fn create_test_cache_value() -> PipelineCacheValue {
        PipelineCacheValue {
            document: Document {
                document_id: "doc_test".to_string(),
                title: "Test".to_string(),
                content: "Test content".to_string(),
                format: DocumentFormat::Txt,
                metadata: HashMap::new(),
                created_at: Utc::now(),
            },
            chunks: Vec::new(),
            format: DocumentFormat::Txt,
            content_hash: "abc123".to_string(),
            processing_time_ms: 100,
            tier: CacheTier::L1,
        }
    }

    #[tokio::test]
    async fn test_l1_cache_hit_miss() {
        let config = CacheConfig::default();
        let metrics = MetricsCollector::new();
        let cache = MultiLevelCache::new(config, metrics);
        
        let key = "test_key".to_string();
        let value = create_test_cache_value();
        
        assert!(cache.get(&key).await.is_none());
        
        cache.insert(key.clone(), value.clone(), CacheTier::L1).await;
        
        let result = cache.get(&key).await;
        assert!(result.is_some());
        assert_eq!(result.unwrap().content_hash, "abc123");
        
        let stats = cache.stats();
        assert_eq!(stats.l1_hits, 1);
        assert_eq!(stats.l1_misses, 1);
    }

    #[tokio::test]
    async fn test_l2_cache_promotion() {
        let config = CacheConfig::default();
        let metrics = MetricsCollector::new();
        let cache = MultiLevelCache::new(config, metrics);
        
        let key = "test_key".to_string();
        let value = create_test_cache_value();
        
        cache.insert(key.clone(), value.clone(), CacheTier::L2).await;
        
        assert_eq!(cache.l1.len(), 0);
        assert_eq!(cache.l2.len(), 1);
        
        let result = cache.get(&key).await;
        assert!(result.is_some());
        
        assert_eq!(cache.l1.len(), 1);
        assert_eq!(cache.l2.len(), 1);
        
        let stats = cache.stats();
        assert_eq!(stats.l2_hits, 1);
    }

    #[tokio::test]
    async fn test_cache_invalidation() {
        let config = CacheConfig::default();
        let metrics = MetricsCollector::new();
        let cache = MultiLevelCache::new(config, metrics);
        
        cache.insert("key1".to_string(), create_test_cache_value(), CacheTier::Both).await;
        cache.insert("key2".to_string(), create_test_cache_value(), CacheTier::Both).await;
        
        let invalidated = cache.invalidate(CacheInvalidationRequest {
            keys: vec!["key1".to_string()],
            invalidate_pattern: None,
            tier: CacheTier::Both,
        });
        
        assert_eq!(invalidated, 2);
        
        assert!(cache.get("key1").await.is_none());
        assert!(cache.get("key2").await.is_some());
    }

    #[tokio::test]
    async fn test_cache_eviction() {
        let config = CacheConfig {
            l1_max_entries: 2,
            ..Default::default()
        };
        let metrics = MetricsCollector::new();
        let cache = MultiLevelCache::new(config, metrics);
        
        cache.insert("key1".to_string(), create_test_cache_value(), CacheTier::L1).await;
        cache.insert("key2".to_string(), create_test_cache_value(), CacheTier::L1).await;
        cache.insert("key3".to_string(), create_test_cache_value(), CacheTier::L1).await;
        
        assert_eq!(cache.l1.len(), 2);
    }

    #[test]
    fn test_generate_cache_key() {
        let data = b"test content";
        let format = DocumentFormat::Txt;
        let namespace = "test";
        
        let key1 = MultiLevelCache::generate_cache_key(data, &format, namespace);
        let key2 = MultiLevelCache::generate_cache_key(data, &format, namespace);
        
        assert_eq!(key1, key2);
        assert!(key1.starts_with("doc:"));
    }

    #[tokio::test]
    async fn test_cache_stats() {
        let config = CacheConfig::default();
        let metrics = MetricsCollector::new();
        let cache = MultiLevelCache::new(config, metrics);
        
        cache.insert("key1".to_string(), create_test_cache_value(), CacheTier::L1).await;
        cache.insert("key2".to_string(), create_test_cache_value(), CacheTier::L2).await;
        
        cache.get("key1").await;
        cache.get("key2").await;
        cache.get("nonexistent").await;
        
        let stats = cache.stats();
        assert_eq!(stats.l1_size, 1);
        assert_eq!(stats.l2_size, 1);
        assert_eq!(stats.l1_hits, 1);
        assert_eq!(stats.l2_hits, 1);
        assert_eq!(stats.l2_misses, 1);
        assert!(stats.hit_rate > 0.0);
    }

    #[tokio::test]
    async fn test_pattern_invalidation() {
        let config = CacheConfig::default();
        let metrics = MetricsCollector::new();
        let cache = MultiLevelCache::new(config, metrics);
        
        cache.insert("user:1:doc".to_string(), create_test_cache_value(), CacheTier::L1).await;
        cache.insert("user:2:doc".to_string(), create_test_cache_value(), CacheTier::L1).await;
        cache.insert("other:key".to_string(), create_test_cache_value(), CacheTier::L1).await;
        
        let invalidated = cache.invalidate(CacheInvalidationRequest {
            keys: Vec::new(),
            invalidate_pattern: Some("user:".to_string()),
            tier: CacheTier::L1,
        });
        
        assert_eq!(invalidated, 2);
        assert!(cache.get("other:key").await.is_some());
    }
}
