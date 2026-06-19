use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use common::error::AppError;
use common::types::RouteTarget;
use dashmap::DashMap;
use db::RedisClient;
use redis::AsyncCommands;
use serde::{de::DeserializeOwned, Serialize};
use tracing::{debug, info, warn};
use uuid::Uuid;

use crate::RoutingConfig;

const ROUTING_CONFIG_PREFIX: &str = "routing:config:";
const USER_ASSIGNMENT_PREFIX: &str = "routing:assignment:";
const ROUTE_CACHE_PREFIX: &str = "routing:route:";
const MODEL_ROUTE_INDEX_PREFIX: &str = "routing:index:model:";
const DEFAULT_CONFIG_TTL_SECS: u64 = 300;
const DEFAULT_ROUTE_TTL_SECS: u64 = 60;
const DEFAULT_LOCAL_CACHE_CAPACITY: usize = 10_000;
const DEFAULT_LOCAL_CACHE_TTL_SECS: u64 = 30;

#[derive(Debug, Clone)]
struct LruEntry<T> {
    value: T,
    inserted_at: Instant,
    expires_at: Option<Instant>,
    access_counter: Arc<AtomicU64>,
}

#[derive(Debug, Clone)]
pub struct RouteCacheKey {
    pub model_name: String,
    pub user_id: Option<String>,
}

impl RouteCacheKey {
    pub fn new(model_name: impl Into<String>, user_id: Option<impl Into<String>>) -> Self {
        Self {
            model_name: model_name.into(),
            user_id: user_id.map(|u| u.into()),
        }
    }

    pub fn to_redis_key(&self) -> String {
        match &self.user_id {
            Some(uid) => format!("{}{}:{}", ROUTE_CACHE_PREFIX, self.model_name, uid),
            None => format!("{}{}:_default", ROUTE_CACHE_PREFIX, self.model_name),
        }
    }

    pub fn to_local_key(&self) -> String {
        self.to_redis_key()
    }

    pub fn cache_key(model_name: &str, user_id: &str) -> String {
        format!("{}{}:{}", ROUTE_CACHE_PREFIX, model_name, user_id)
    }
}

struct LruCache<T: Clone> {
    map: DashMap<String, LruEntry<T>>,
    capacity: usize,
    eviction_counter: AtomicU64,
}

impl<T: Clone> LruCache<T> {
    fn new(capacity: usize) -> Self {
        Self {
            map: DashMap::with_capacity(capacity.min(1024)),
            capacity: capacity.max(1),
            eviction_counter: AtomicU64::new(0),
        }
    }

    fn get(&self, key: &str) -> Option<T> {
        let entry = self.map.get(key)?;
        if let Some(expires_at) = entry.expires_at {
            if expires_at < Instant::now() {
                drop(entry);
                self.map.remove(key);
                return None;
            }
        }
        entry.access_counter.fetch_add(1, Ordering::Relaxed);
        Some(entry.value.clone())
    }

    fn insert(&self, key: String, value: T, ttl_secs: Option<u64>) {
        if self.map.len() >= self.capacity {
            self.evict_lru();
        }

        let now = Instant::now();
        let entry = LruEntry {
            value,
            inserted_at: now,
            expires_at: ttl_secs.map(|t| now + Duration::from_secs(t)),
            access_counter: Arc::new(AtomicU64::new(1)),
        };

        self.map.insert(key, entry);
    }

    fn evict_lru(&self) {
        let evict_count = (self.capacity as f64 * 0.1).ceil() as usize;
        let evict_count = evict_count.max(1);

        let mut candidates: Vec<(u64, String, Instant)> = Vec::with_capacity(self.map.len());
        for item in self.map.iter() {
            let key = item.key().clone();
            let entry = item.value();
            let accesses = entry.access_counter.load(Ordering::Relaxed);
            candidates.push((accesses, key, entry.inserted_at));
        }

        candidates.sort_by(|a, b| {
            a.0.cmp(&b.0)
                .then_with(|| a.2.cmp(&b.2))
        });

        for (_, key, _) in candidates.iter().take(evict_count) {
            self.map.remove(key);
        }

        let total_evicted = self.eviction_counter.fetch_add(evict_count as u64, Ordering::Relaxed);
        debug!(
            "LRU evicted {} entries (total evictions: {})",
            evict_count,
            total_evicted + evict_count as u64
        );
    }

    fn invalidate_by_prefix(&self, prefix: &str) -> usize {
        let keys_to_remove: Vec<String> = self
            .map
            .iter()
            .filter(|item| item.key().starts_with(prefix))
            .map(|item| item.key().clone())
            .collect();

        let count = keys_to_remove.len();
        for key in keys_to_remove {
            self.map.remove(&key);
        }
        count
    }

    fn invalidate_all(&self) -> usize {
        let count = self.map.len();
        self.map.clear();
        count
    }

    fn len(&self) -> usize {
        self.map.len()
    }

    fn cleanup_expired(&self) -> usize {
        let now = Instant::now();
        let expired_keys: Vec<String> = self
            .map
            .iter()
            .filter(|item| {
                item.value()
                    .expires_at
                    .map(|e| e < now)
                    .unwrap_or(false)
            })
            .map(|item| item.key().clone())
            .collect();

        let count = expired_keys.len();
        for key in expired_keys {
            self.map.remove(&key);
        }
        count
    }
}

#[derive(Clone)]
pub struct RouteCache {
    redis: RedisClient,
    local_routes: Arc<LruCache<RouteTarget>>,
    local_configs: Arc<LruCache<RoutingConfig>>,
    local_assignments: Arc<LruCache<String>>,
    model_route_index: Arc<DashMap<String, DashMap<String, bool>>>,
    local_capacity: usize,
    local_ttl_secs: u64,
}

impl RouteCache {
    pub fn new(redis: RedisClient) -> Self {
        Self::with_options(redis, DEFAULT_LOCAL_CACHE_CAPACITY, DEFAULT_LOCAL_CACHE_TTL_SECS)
    }

    pub fn with_options(redis: RedisClient, local_capacity: usize, local_ttl_secs: u64) -> Self {
        Self {
            redis,
            local_routes: Arc::new(LruCache::new(local_capacity)),
            local_configs: Arc::new(LruCache::new(local_capacity.min(1000))),
            local_assignments: Arc::new(LruCache::new(local_capacity)),
            model_route_index: Arc::new(DashMap::new()),
            local_capacity,
            local_ttl_secs,
        }
    }

    pub fn local_capacity(&self) -> usize {
        self.local_capacity
    }

    pub fn local_route_count(&self) -> usize {
        self.local_routes.len()
    }

    pub fn cleanup_expired(&self) -> (usize, usize, usize) {
        (
            self.local_routes.cleanup_expired(),
            self.local_configs.cleanup_expired(),
            self.local_assignments.cleanup_expired(),
        )
    }

    fn config_key(model_name: &str) -> String {
        format!("{}{}", ROUTING_CONFIG_PREFIX, model_name)
    }

    fn assignment_key(model_name: &str, user_id: &str) -> String {
        format!("{}{}:{}", USER_ASSIGNMENT_PREFIX, model_name, user_id)
    }

    fn model_route_index_key(model_name: &str) -> String {
        format!("{}{}", MODEL_ROUTE_INDEX_PREFIX, model_name)
    }

    async fn get_serialized<T: DeserializeOwned>(&self, key: &str) -> Result<Option<T>, AppError> {
        let mut conn = self.redis.manager.clone();
        let result: Option<String> = conn.get(key).await?;

        match result {
            Some(data) => {
                let parsed = serde_json::from_str::<T>(&data)
                    .map_err(|e| AppError::Cache(format!("Failed to deserialize {}: {}", key, e)))?;
                debug!("Redis cache hit: {}", key);
                Ok(Some(parsed))
            }
            None => {
                debug!("Redis cache miss: {}", key);
                Ok(None)
            }
        }
    }

    async fn set_serialized<T: Serialize>(
        &self,
        key: &str,
        value: &T,
        ttl_secs: u64,
    ) -> Result<(), AppError> {
        let serialized = serde_json::to_string(value)
            .map_err(|e| AppError::Cache(format!("Failed to serialize {}: {}", key, e)))?;

        let mut conn = self.redis.manager.clone();
        conn.set_ex::<_, _, ()>(key, serialized, ttl_secs).await?;
        debug!("Redis cache set: {} (ttl={}s)", key, ttl_secs);
        Ok(())
    }

    fn track_model_route(&self, model_name: &str, key: &str) {
        let model_index = self
            .model_route_index
            .entry(model_name.to_string())
            .or_insert_with(DashMap::new);
        model_index.insert(key.to_string(), true);
    }

    fn untrack_model_routes(&self, model_name: &str) -> Vec<String> {
        self.model_route_index
            .remove(model_name)
            .map(|(_, map)| map.iter().map(|item| item.key().clone()).collect())
            .unwrap_or_default()
    }

    pub async fn get_cached_route(&self, key: &str) -> Result<Option<RouteTarget>, AppError> {
        if let Some(local) = self.local_routes.get(key) {
            debug!("Local route cache hit: {}", key);
            return Ok(Some(local));
        }

        if let Some(redis_val) = self.get_serialized::<RouteTarget>(key).await? {
            self.local_routes
                .insert(key.to_string(), redis_val.clone(), Some(self.local_ttl_secs));
            return Ok(Some(redis_val));
        }

        Ok(None)
    }

    pub async fn get_cached_route_by_parts(
        &self,
        model_name: &str,
        user_id: &str,
    ) -> Result<Option<RouteTarget>, AppError> {
        let key = RouteCacheKey::cache_key(model_name, user_id);
        self.get_cached_route(&key).await
    }

    pub async fn set_cached_route(
        &self,
        key: &str,
        target: RouteTarget,
        ttl_secs: Option<u64>,
    ) -> Result<(), AppError> {
        let ttl = ttl_secs.unwrap_or(DEFAULT_ROUTE_TTL_SECS);
        let local_ttl = ttl.min(self.local_ttl_secs);

        self.local_routes
            .insert(key.to_string(), target.clone(), Some(local_ttl));

        if let Some(model_name) = extract_model_name_from_key(key) {
            self.track_model_route(&model_name, key);
        }

        self.set_serialized(key, &target, ttl).await
    }

    pub async fn set_cached_route_by_parts(
        &self,
        model_name: &str,
        user_id: &str,
        target: RouteTarget,
        ttl_secs: Option<u64>,
    ) -> Result<(), AppError> {
        let key = RouteCacheKey::cache_key(model_name, user_id);
        self.set_cached_route(&key, target, ttl_secs).await
    }

    pub async fn invalidate_model_routes(&self, model_name: &str) -> Result<(), AppError> {
        let local_keys = self.untrack_model_routes(model_name);
        let local_count = local_keys.len();
        for key in &local_keys {
            self.local_routes.map.remove(key);
        }

        let pattern = format!("{}{}:*", ROUTE_CACHE_PREFIX, model_name);
        let mut conn = self.redis.manager.clone();

        let redis_keys: Vec<String> = redis::cmd("KEYS")
            .arg(&pattern)
            .query_async(&mut conn)
            .await
            .unwrap_or_default();

        if !redis_keys.is_empty() {
            let deleted: usize = conn.del(&redis_keys).await?;
            debug!(
                "Redis invalidated {} route keys for model {}",
                deleted, model_name
            );
        }

        info!(
            "Invalidated routes for model {}: local={}, redis={}",
            model_name,
            local_count,
            redis_keys.len()
        );
        Ok(())
    }

    pub async fn invalidate_cache(&self) -> Result<(), AppError> {
        let local_count = self.local_routes.invalidate_all();
        self.model_route_index.clear();

        let mut conn = self.redis.manager.clone();

        for prefix in [ROUTE_CACHE_PREFIX, ROUTING_CONFIG_PREFIX, USER_ASSIGNMENT_PREFIX].iter() {
            let pattern = format!("{}*", prefix);
            let keys: Vec<String> = redis::cmd("KEYS")
                .arg(&pattern)
                .query_async(&mut conn)
                .await
                .unwrap_or_default();
            if !keys.is_empty() {
                let _: usize = conn.del(&keys).await?;
            }
        }

        info!(
            "Full cache invalidation completed: local entries={}",
            local_count
        );
        Ok(())
    }

    pub async fn get_routing_config(&self, model_name: &str) -> Result<Option<RoutingConfig>, AppError> {
        let key = Self::config_key(model_name);

        if let Some(local) = self.local_configs.get(&key) {
            debug!("Local config cache hit: {}", model_name);
            return Ok(Some(local));
        }

        if let Some(config) = self.get_serialized::<RoutingConfig>(&key).await? {
            self.local_configs
                .insert(key, config.clone(), Some(self.local_ttl_secs));
            return Ok(Some(config));
        }

        Ok(None)
    }

    pub async fn set_routing_config(
        &self,
        model_name: &str,
        config: &RoutingConfig,
        ttl_secs: Option<u64>,
    ) -> Result<(), AppError> {
        let key = Self::config_key(model_name);
        let ttl = ttl_secs.unwrap_or(DEFAULT_CONFIG_TTL_SECS);
        let local_ttl = ttl.min(self.local_ttl_secs);

        self.local_configs
            .insert(key.clone(), config.clone(), Some(local_ttl));
        self.set_serialized(&key, config, ttl).await
    }

    pub async fn invalidate_routing_config(&self, model_name: &str) -> Result<(), AppError> {
        let key = Self::config_key(model_name);
        self.local_configs.map.remove(&key);

        let mut conn = self.redis.manager.clone();
        let deleted: usize = conn.del(&key).await?;
        if deleted > 0 {
            info!("Config cache invalidated: {} (deleted {} keys)", key, deleted);
        }
        Ok(())
    }

    pub async fn get_user_assignment(
        &self,
        model_name: &str,
        user_id: &str,
    ) -> Result<Option<String>, AppError> {
        let key = Self::assignment_key(model_name, user_id);

        if let Some(local) = self.local_assignments.get(&key) {
            debug!("Local assignment cache hit: {}", key);
            return Ok(Some(local));
        }

        let mut conn = self.redis.manager.clone();
        let result: Option<String> = conn.get(&key).await?;
        if let Some(ver) = result.as_ref() {
            self.local_assignments
                .insert(key.clone(), ver.clone(), Some(self.local_ttl_secs));
            debug!("Redis assignment cache hit: {}", key);
        } else {
            debug!("Assignment cache miss: {}", key);
        }
        Ok(result)
    }

    pub async fn set_user_assignment(
        &self,
        model_name: &str,
        user_id: &str,
        version_id: &str,
        ttl_secs: u64,
    ) -> Result<(), AppError> {
        let key = Self::assignment_key(model_name, user_id);
        let local_ttl = ttl_secs.min(self.local_ttl_secs);

        self.local_assignments
            .insert(key.clone(), version_id.to_string(), Some(local_ttl));

        let mut conn = self.redis.manager.clone();
        conn.set_ex::<_, _, ()>(&key, version_id, ttl_secs).await?;
        debug!(
            "Assignment cache set: {} -> {} (ttl={}s)",
            key, version_id, ttl_secs
        );
        Ok(())
    }

    pub async fn invalidate_user_assignments(&self, model_name: &str) -> Result<(), AppError> {
        let prefix = format!("{}{}:", USER_ASSIGNMENT_PREFIX, model_name);
        let local_count = self.local_assignments.invalidate_by_prefix(&prefix);

        let pattern = format!("{}{}:*", USER_ASSIGNMENT_PREFIX, model_name);
        let mut conn = self.redis.manager.clone();

        let keys: Vec<String> = redis::cmd("KEYS")
            .arg(&pattern)
            .query_async(&mut conn)
            .await
            .unwrap_or_default();

        if !keys.is_empty() {
            let deleted: usize = conn.del(&keys).await?;
            info!(
                "Invalidated user assignments for model {}: local={}, redis={}",
                model_name, local_count, deleted
            );
        }

        Ok(())
    }

    pub async fn check_health(&self) -> Result<(), AppError> {
        let mut conn = self.redis.manager.clone();
        redis::cmd("PING")
            .query_async::<_, String>(&mut conn)
            .await
            .map(|_| ())
            .map_err(|e| AppError::Cache(format!("Redis health check failed: {}", e)))
    }

    pub fn create_assignment_fns(
        self,
    ) -> (
        impl Fn(&str, &str) -> Option<String> + Send + Sync + Clone + 'static,
        impl Fn(&str, &str, &str, u64) + Send + Sync + Clone + 'static,
    ) {
        let cache_get = self.clone();
        let cache_set = self.clone();

        let getter = move |model_name: &str, user_id: &str| -> Option<String> {
            let cache = cache_get.clone();
            let model = model_name.to_string();
            let user = user_id.to_string();
            let handle = tokio::runtime::Handle::try_current().ok()?;
            handle.block_on(async move {
                cache.get_user_assignment(&model, &user).await.ok().flatten()
            })
        };

        let setter = move |model_name: &str, user_id: &str, version_id: &str, ttl: u64| {
            let cache = cache_set.clone();
            let model = model_name.to_string();
            let user = user_id.to_string();
            let version = version_id.to_string();
            if let Ok(_handle) = tokio::runtime::Handle::try_current() {
                tokio::spawn(async move {
                    let _ = cache.set_user_assignment(&model, &user, &version, ttl).await;
                });
            } else {
                let rt = tokio::runtime::Runtime::new();
                if let Ok(rt) = rt {
                    rt.block_on(async move {
                        let _ = cache.set_user_assignment(&model, &user, &version, ttl).await;
                    });
                }
            }
        };

        (getter, setter)
    }
}

fn extract_model_name_from_key(key: &str) -> Option<String> {
    let stripped = key.strip_prefix(ROUTE_CACHE_PREFIX)?;
    let idx = stripped.find(':')?;
    Some(stripped[..idx].to_string())
}

impl std::fmt::Debug for RouteCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RouteCache")
            .field("redis", &"RedisClient { ... }")
            .field("local_capacity", &self.local_capacity)
            .field("local_ttl_secs", &self.local_ttl_secs)
            .field("cached_routes", &self.local_routes.len())
            .field("cached_configs", &self.local_configs.len())
            .field("cached_assignments", &self.local_assignments.len())
            .field("tracked_models", &self.model_route_index.len())
            .finish()
    }
}

#[derive(Clone)]
pub struct RoutingCache(pub RouteCache);

impl RoutingCache {
    pub fn new(redis: RedisClient) -> Self {
        Self(RouteCache::new(redis))
    }

    pub fn inner(&self) -> &RouteCache {
        &self.0
    }
}

impl std::ops::Deref for RoutingCache {
    type Target = RouteCache;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

impl std::fmt::Debug for RoutingCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.0.fmt(f)
    }
}
