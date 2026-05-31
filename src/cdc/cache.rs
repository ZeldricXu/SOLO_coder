use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};
use async_trait::async_trait;
use crate::cdc::ChangeEvent;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum CacheTier {
    L1,
    L2,
    Both,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheEntry<T> {
    pub key: String,
    pub value: T,
    pub created_at: Instant,
    pub accessed_at: Instant,
    pub access_count: u64,
    pub tier: CacheTier,
    pub ttl_seconds: u64,
}

impl<T: Clone> CacheEntry<T> {
    pub fn new(key: String, value: T, ttl_seconds: u64) -> Self {
        let now = Instant::now();
        Self {
            key,
            value,
            created_at: now,
            accessed_at: now,
            access_count: 0,
            tier: CacheTier::L1,
            ttl_seconds,
        }
    }

    pub fn is_expired(&self) -> bool {
        if self.ttl_seconds == 0 {
            return false;
        }
        self.created_at.elapsed() > Duration::from_secs(self.ttl_seconds)
    }

    pub fn access(&mut self) {
        self.accessed_at = Instant::now();
        self.access_count += 1;
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum EvictionPolicy {
    LRU,
    LFU,
    FIFO,
    TTL,
}

#[derive(Debug, Clone)]
pub struct L1CacheConfig {
    pub max_size: usize,
    pub default_ttl_seconds: u64,
    pub eviction_policy: EvictionPolicy,
    pub max_memory_mb: Option<usize>,
}

impl Default for L1CacheConfig {
    fn default() -> Self {
        Self {
            max_size: 10000,
            default_ttl_seconds: 300,
            eviction_policy: EvictionPolicy::LRU,
            max_memory_mb: Some(64),
        }
    }
}

#[derive(Debug, Clone)]
pub struct L2CacheConfig {
    pub connection_string: String,
    pub namespace: String,
    pub default_ttl_seconds: u64,
    pub enabled: bool,
    pub compress: bool,
}

impl Default for L2CacheConfig {
    fn default() -> Self {
        Self {
            connection_string: "redis://127.0.0.1:6379".to_string(),
            namespace: "streamsql:cdc".to_string(),
            default_ttl_seconds: 3600,
            enabled: false,
            compress: true,
        }
    }
}

#[derive(Debug, Clone)]
pub struct MultiLevelCacheConfig {
    pub l1: L1CacheConfig,
    pub l2: L2CacheConfig,
    pub enable_l2: bool,
    pub warmup_on_start: bool,
    pub write_through: bool,
}

impl Default for MultiLevelCacheConfig {
    fn default() -> Self {
        Self {
            l1: L1CacheConfig::default(),
            l2: L2CacheConfig::default(),
            enable_l2: false,
            warmup_on_start: false,
            write_through: true,
        }
    }
}

#[async_trait]
pub trait DistributedCache: Send + Sync {
    async fn get(&self, key: &str) -> Option<Vec<u8>>;
    async fn set(&self, key: &str, value: &[u8], ttl_seconds: u64) -> bool;
    async fn delete(&self, key: &str) -> bool;
    async fn clear(&self, pattern: Option<&str>) -> usize;
    async fn exists(&self, key: &str) -> bool;
    async fn multi_get(&self, keys: &[String]) -> HashMap<String, Vec<u8>>;
    async fn multi_set(&self, entries: &[(String, Vec<u8>)], ttl_seconds: u64) -> usize;
}

pub struct MockDistributedCache {
    data: RwLock<HashMap<String, (Vec<u8>, Instant, u64)>>,
}

impl MockDistributedCache {
    pub fn new() -> Self {
        Self {
            data: RwLock::new(HashMap::new()),
        }
    }

    fn is_expired(entry: &(Vec<u8>, Instant, u64)) -> bool {
        if entry.2 == 0 {
            return false;
        }
        entry.1.elapsed() > Duration::from_secs(entry.2)
    }
}

#[async_trait]
impl DistributedCache for MockDistributedCache {
    async fn get(&self, key: &str) -> Option<Vec<u8>> {
        let data = self.data.read().await;
        if let Some(entry) = data.get(key) {
            if !Self::is_expired(entry) {
                return Some(entry.0.clone());
            }
        }
        None
    }

    async fn set(&self, key: &str, value: &[u8], ttl_seconds: u64) -> bool {
        let mut data = self.data.write().await;
        data.insert(key.to_string(), (value.to_vec(), Instant::now(), ttl_seconds));
        true
    }

    async fn delete(&self, key: &str) -> bool {
        let mut data = self.data.write().await;
        data.remove(key).is_some()
    }

    async fn clear(&self, _pattern: Option<&str>) -> usize {
        let mut data = self.data.write().await;
        let count = data.len();
        data.clear();
        count
    }

    async fn exists(&self, key: &str) -> bool {
        let data = self.data.read().await;
        data.get(key).map(|e| !Self::is_expired(e)).unwrap_or(false)
    }

    async fn multi_get(&self, keys: &[String]) -> HashMap<String, Vec<u8>> {
        let mut result = HashMap::new();
        let data = self.data.read().await;
        for key in keys {
            if let Some(entry) = data.get(key) {
                if !Self::is_expired(entry) {
                    result.insert(key.clone(), entry.0.clone());
                }
            }
        }
        result
    }

    async fn multi_set(&self, entries: &[(String, Vec<u8>)], ttl_seconds: u64) -> usize {
        let mut data = self.data.write().await;
        let now = Instant::now();
        let mut count = 0;
        for (key, value) in entries {
            data.insert(key.clone(), (value.clone(), now, ttl_seconds));
            count += 1;
        }
        count
    }
}

pub struct LocalCache<T: Clone + Send + Sync> {
    config: L1CacheConfig,
    data: RwLock<HashMap<String, CacheEntry<T>>>,
    access_order: RwLock<VecDeque<String>>,
}

impl<T: Clone + Send + Sync> LocalCache<T> {
    pub fn new(config: L1CacheConfig) -> Self {
        Self {
            config,
            data: RwLock::new(HashMap::new()),
            access_order: RwLock::new(VecDeque::new()),
        }
    }

    pub async fn get(&self, key: &str) -> Option<T> {
        let mut data = self.data.write().await;
        if let Some(entry) = data.get_mut(key) {
            if entry.is_expired() {
                data.remove(key);
                return None;
            }
            entry.access();
            self.update_access_order(key).await;
            return Some(entry.value.clone());
        }
        None
    }

    pub async fn set(&self, key: String, value: T, ttl_seconds: Option<u64>) {
        let ttl = ttl_seconds.unwrap_or(self.config.default_ttl_seconds);
        let entry = CacheEntry::new(key.clone(), value, ttl);

        self.evict_if_needed().await;

        let mut data = self.data.write().await;
        data.insert(key.clone(), entry);
        self.update_access_order(&key).await;
    }

    pub async fn delete(&self, key: &str) -> bool {
        let mut data = self.data.write().await;
        data.remove(key).is_some()
    }

    pub async fn exists(&self, key: &str) -> bool {
        let data = self.data.read().await;
        data.contains_key(key)
    }

    pub async fn size(&self) -> usize {
        let data = self.data.read().await;
        data.len()
    }

    pub async fn clear(&self) -> usize {
        let mut data = self.data.write().await;
        let count = data.len();
        data.clear();
        self.access_order.write().await.clear();
        count
    }

    pub async fn multi_get(&self, keys: &[String]) -> HashMap<String, T> {
        let mut result = HashMap::new();
        for key in keys {
            if let Some(value) = self.get(key).await {
                result.insert(key.clone(), value);
            }
        }
        result
    }

    pub async fn multi_set(&self, entries: Vec<(String, T)>, ttl_seconds: Option<u64>) {
        for (key, value) in entries {
            self.set(key, value, ttl_seconds).await;
        }
    }

    async fn update_access_order(&self, key: &str) {
        let mut order = self.access_order.write().await;
        order.retain(|k| k != key);
        order.push_front(key.to_string());
        if order.len() > self.config.max_size {
            order.pop_back();
        }
    }

    async fn evict_if_needed(&self) {
        let data = self.data.read().await;
        if data.len() < self.config.max_size {
            return;
        }
        drop(data);

        match self.config.eviction_policy {
            EvictionPolicy::LRU => self.evict_lru().await,
            EvictionPolicy::LFU => self.evict_lfu().await,
            EvictionPolicy::FIFO => self.evict_fifo().await,
            EvictionPolicy::TTL => self.evict_expired().await,
        }
    }

    async fn evict_lru(&self) {
        let mut order = self.access_order.write().await;
        if let Some(key) = order.pop_back() {
            let mut data = self.data.write().await;
            data.remove(&key);
        }
    }

    async fn evict_lfu(&self) {
        let data = self.data.read().await;
        if let Some(min_key) = data.values()
            .min_by_key(|e| e.access_count)
            .map(|e| e.key.clone())
        {
            drop(data);
            let mut data = self.data.write().await;
            data.remove(&min_key);
            let mut order = self.access_order.write().await;
            order.retain(|k| k != &min_key);
        }
    }

    async fn evict_fifo(&self) {
        let data = self.data.read().await;
        if let Some(oldest_key) = data.values()
            .min_by_key(|e| e.created_at)
            .map(|e| e.key.clone())
        {
            drop(data);
            let mut data = self.data.write().await;
            data.remove(&oldest_key);
            let mut order = self.access_order.write().await;
            order.retain(|k| k != &oldest_key);
        }
    }

    async fn evict_expired(&self) {
        let mut data = self.data.write().await;
        let expired_keys: Vec<String> = data.values()
            .filter(|e| e.is_expired())
            .map(|e| e.key.clone())
            .collect();
        for key in &expired_keys {
            data.remove(key);
        }
        if !expired_keys.is_empty() {
            let mut order = self.access_order.write().await;
            for key in &expired_keys {
                order.retain(|k| k != key);
            }
        }
    }

    pub async fn get_stats(&self) -> LocalCacheStats {
        let data = self.data.read().await;
        let order = self.access_order.read().await;
        LocalCacheStats {
            size: data.len(),
            max_size: self.config.max_size,
            hit_count: 0,
            miss_count: 0,
            eviction_count: 0,
            oldest_access: order.back().cloned(),
            newest_access: order.front().cloned(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct LocalCacheStats {
    pub size: usize,
    pub max_size: usize,
    pub hit_count: u64,
    pub miss_count: u64,
    pub eviction_count: u64,
    pub oldest_access: Option<String>,
    pub newest_access: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct CacheMetrics {
    pub l1_hits: u64,
    pub l1_misses: u64,
    pub l2_hits: u64,
    pub l2_misses: u64,
    pub l1_hit_rate: f64,
    pub l2_hit_rate: f64,
    pub total_hit_rate: f64,
    pub l1_size: usize,
    pub l2_enabled: bool,
}

pub struct MultiLevelCache {
    config: MultiLevelCacheConfig,
    l1: LocalCache<Vec<u8>>,
    l2: Option<Arc<dyn DistributedCache>>,
    metrics: Arc<RwLock<CacheMetricsInternal>>,
    namespace: String,
}

#[derive(Debug, Default)]
struct CacheMetricsInternal {
    l1_hits: u64,
    l1_misses: u64,
    l2_hits: u64,
    l2_misses: u64,
}

impl MultiLevelCache {
    pub fn new(config: MultiLevelCacheConfig) -> Self {
        let l1 = LocalCache::new(config.l1.clone());
        let l2 = if config.enable_l2 {
            Some(Arc::new(MockDistributedCache::new()) as Arc<dyn DistributedCache>)
        } else {
            None
        };

        Self {
            config,
            l1,
            l2,
            metrics: Arc::new(RwLock::new(CacheMetricsInternal::default())),
            namespace: config.l2.namespace.clone(),
        }
    }

    pub fn with_distributed_cache(
        config: MultiLevelCacheConfig,
        l2: Arc<dyn DistributedCache>,
    ) -> Self {
        let l1 = LocalCache::new(config.l1.clone());
        Self {
            config,
            l1,
            l2: Some(l2),
            metrics: Arc::new(RwLock::new(CacheMetricsInternal::default())),
            namespace: config.l2.namespace.clone(),
        }
    }

    fn build_key(&self, key: &str) -> String {
        format!("{}:{}", self.namespace, key)
    }

    pub async fn get<T: for<'de> Deserialize<'de> + Clone>(&self, key: &str) -> Option<T> {
        let l1_bytes = self.l1.get(key).await;

        if let Some(bytes) = l1_bytes {
            self.record_l1_hit().await;
            return bincode::deserialize(&bytes).ok();
        }

        self.record_l1_miss().await;

        if let Some(l2_cache) = &self.l2 {
            let full_key = self.build_key(key);
            if let Some(bytes) = l2_cache.get(&full_key).await {
                self.record_l2_hit().await;
                self.l1.set(key.to_string(), bytes.clone(), None).await;
                return bincode::deserialize(&bytes).ok();
            }
            self.record_l2_miss().await;
        }

        None
    }

    pub async fn set<T: Serialize>(&self, key: String, value: &T, ttl_seconds: Option<u64>) {
        let bytes = bincode::serialize(value).unwrap_or_default();

        self.l1.set(key.clone(), bytes.clone(), ttl_seconds).await;

        if self.config.write_through {
            if let Some(l2_cache) = &self.l2 {
                let full_key = self.build_key(&key);
                let ttl = ttl_seconds.unwrap_or(self.config.l2.default_ttl_seconds);
                l2_cache.set(&full_key, &bytes, ttl).await;
            }
        }
    }

    pub async fn delete(&self, key: &str) -> bool {
        let l1_deleted = self.l1.delete(key).await;

        if let Some(l2_cache) = &self.l2 {
            let full_key = self.build_key(key);
            let l2_deleted = l2_cache.delete(&full_key).await;
            return l1_deleted || l2_deleted;
        }

        l1_deleted
    }

    pub async fn exists(&self, key: &str) -> bool {
        if self.l1.exists(key).await {
            return true;
        }

        if let Some(l2_cache) = &self.l2 {
            let full_key = self.build_key(key);
            return l2_cache.exists(&full_key).await;
        }

        false
    }

    pub async fn multi_get<T: for<'de> Deserialize<'de> + Clone>(
        &self,
        keys: &[String],
    ) -> HashMap<String, T> {
        let mut result = HashMap::new();
        let mut missing_keys = Vec::new();

        for key in keys {
            if let Some(value) = self.get::<T>(key).await {
                result.insert(key.clone(), value);
            } else {
                missing_keys.push(key.clone());
            }
        }

        result
    }

    pub async fn multi_set<T: Serialize>(
        &self,
        entries: Vec<(String, T)>,
        ttl_seconds: Option<u64>,
    ) {
        for (key, value) in entries {
            self.set(key, &value, ttl_seconds).await;
        }
    }

    pub async fn clear(&self) {
        self.l1.clear().await;

        if let Some(l2_cache) = &self.l2 {
            l2_cache.clear(Some(&format!("{}:*", self.namespace))).await;
        }
    }

    pub async fn warmup<T: Serialize>(&self, entries: Vec<(String, T)>) {
        for (key, value) in entries {
            self.set(key, &value, None).await;
        }
    }

    async fn record_l1_hit(&self) {
        let mut metrics = self.metrics.write().await;
        metrics.l1_hits += 1;
    }

    async fn record_l1_miss(&self) {
        let mut metrics = self.metrics.write().await;
        metrics.l1_misses += 1;
    }

    async fn record_l2_hit(&self) {
        let mut metrics = self.metrics.write().await;
        metrics.l2_hits += 1;
    }

    async fn record_l2_miss(&self) {
        let mut metrics = self.metrics.write().await;
        metrics.l2_misses += 1;
    }

    pub async fn get_metrics(&self) -> CacheMetrics {
        let metrics = self.metrics.read().await;
        let l1_size = self.l1.size().await;

        let l1_total = metrics.l1_hits + metrics.l1_misses;
        let l1_hit_rate = if l1_total > 0 {
            metrics.l1_hits as f64 / l1_total as f64
        } else {
            0.0
        };

        let l2_total = metrics.l2_hits + metrics.l2_misses;
        let l2_hit_rate = if l2_total > 0 {
            metrics.l2_hits as f64 / l2_total as f64
        } else {
            0.0
        };

        let total_hits = metrics.l1_hits + metrics.l2_hits;
        let total_accesses = l1_total;
        let total_hit_rate = if total_accesses > 0 {
            total_hits as f64 / total_accesses as f64
        } else {
            0.0
        };

        CacheMetrics {
            l1_hits: metrics.l1_hits,
            l1_misses: metrics.l1_misses,
            l2_hits: metrics.l2_hits,
            l2_misses: metrics.l2_misses,
            l1_hit_rate,
            l2_hit_rate,
            total_hit_rate,
            l1_size,
            l2_enabled: self.l2.is_some(),
        }
    }

    pub async fn reset_metrics(&self) {
        let mut metrics = self.metrics.write().await;
        *metrics = CacheMetricsInternal::default();
    }

    pub async fn get_l1_stats(&self) -> LocalCacheStats {
        self.l1.get_stats().await
    }
}

pub struct EventCache {
    cache: MultiLevelCache,
    table_keys: RwLock<HashMap<String, Vec<String>>>,
}

impl EventCache {
    pub fn new(config: MultiLevelCacheConfig) -> Self {
        Self {
            cache: MultiLevelCache::new(config),
            table_keys: RwLock::new(HashMap::new()),
        }
    }

    pub fn with_cache(cache: MultiLevelCache) -> Self {
        Self {
            cache,
            table_keys: RwLock::new(HashMap::new()),
        }
    }

    pub async fn cache_event(&self, event: &ChangeEvent, ttl_seconds: Option<u64>) {
        let key = format!("event:{}", event.event_id);
        self.cache.set(key, event, ttl_seconds).await;

        let table_key = format!("{}.{}", event.source.database, event.source.table);
        let mut table_keys = self.table_keys.write().await;
        table_keys
            .entry(table_key)
            .or_insert_with(Vec::new)
            .push(format!("event:{}", event.event_id));
    }

    pub async fn cache_events(&self, events: &[ChangeEvent], ttl_seconds: Option<u64>) {
        for event in events {
            self.cache_event(event, ttl_seconds).await;
        }
    }

    pub async fn get_event(&self, event_id: &str) -> Option<ChangeEvent> {
        self.cache.get(&format!("event:{}", event_id)).await
    }

    pub async fn get_events_by_table(&self, database: &str, table: &str) -> Vec<ChangeEvent> {
        let table_key = format!("{}.{}", database, table);
        let table_keys = self.table_keys.read().await;

        let keys = table_keys.get(&table_key).cloned().unwrap_or_default();
        drop(table_keys);

        let mut result = Vec::new();
        for key in keys {
            if let Some(event) = self.cache.get::<ChangeEvent>(&key).await {
                result.push(event);
            }
        }
        result
    }

    pub async fn invalidate_table(&self, database: &str, table: &str) -> usize {
        let table_key = format!("{}.{}", database, table);
        let mut table_keys = self.table_keys.write().await;

        let keys = table_keys.remove(&table_key).unwrap_or_default();
        let mut count = 0;
        for key in keys {
            if self.cache.delete(&key).await {
                count += 1;
            }
        }
        count
    }

    pub async fn invalidate_by_transaction(&self, transaction_id: &str) -> usize {
        0
    }

    pub async fn clear_all(&self) {
        self.cache.clear().await;
        self.table_keys.write().await.clear();
    }

    pub async fn get_metrics(&self) -> CacheMetrics {
        self.cache.get_metrics().await
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InvalidationTrigger {
    Timeout,
    NewVersion,
    ExternalEvent,
    Manual,
    MemoryPressure,
}

#[derive(Debug, Clone)]
pub struct CacheInvalidationRule {
    pub pattern: String,
    pub trigger: InvalidationTrigger,
    pub ttl_seconds: Option<u64>,
    pub auto_propagate: bool,
}

impl CacheInvalidationRule {
    pub fn new_ttl(pattern: impl Into<String>, ttl_seconds: u64) -> Self {
        Self {
            pattern: pattern.into(),
            trigger: InvalidationTrigger::Timeout,
            ttl_seconds: Some(ttl_seconds),
            auto_propagate: true,
        }
    }

    pub fn new_on_new_version(pattern: impl Into<String>) -> Self {
        Self {
            pattern: pattern.into(),
            trigger: InvalidationTrigger::NewVersion,
            ttl_seconds: None,
            auto_propagate: true,
        }
    }

    pub fn matches(&self, key: &str) -> bool {
        key.starts_with(&self.pattern) || self.pattern == "*"
    }
}

pub struct CacheWarmupConfig {
    pub keys: Vec<String>,
    pub parallel: bool,
    pub max_concurrency: usize,
    pub timeout_ms: u64,
}

impl Default for CacheWarmupConfig {
    fn default() -> Self {
        Self {
            keys: Vec::new(),
            parallel: true,
            max_concurrency: 10,
            timeout_ms: 30000,
        }
    }
}
