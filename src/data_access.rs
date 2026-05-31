use crate::types::{AppError, Entity, Config};
use bb8_redis::{
    bb8::{self, Pool},
    redis::{self, AsyncCommands},
    RedisConnectionManager,
};
use dashmap::DashMap;
use serde::{de::DeserializeOwned, Serialize};
use sqlx::{postgres::PgPoolOptions, PgPool};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::RwLock;
use uuid::Uuid;

#[derive(Clone)]
pub struct DataAccessLayer {
    pub db_pool: PgPool,
    pub redis_pool: Pool<RedisConnectionManager>,
    pub local_cache: Arc<LocalCache>,
    pub cache_config: CacheConfig,
}

#[derive(Clone, Debug)]
pub struct CacheConfig {
    pub default_ttl: Duration,
    pub max_local_entries: usize,
    pub cache_key_prefix: String,
}

impl Default for CacheConfig {
    fn default() -> Self {
        Self {
            default_ttl: Duration::from_secs(300),
            max_local_entries: 10000,
            cache_key_prefix: "data:".to_string(),
        }
    }
}

pub struct LocalCache {
    entries: DashMap<String, CacheEntry>,
    max_entries: usize,
    hits: std::sync::atomic::AtomicU64,
    misses: std::sync::atomic::AtomicU64,
}

struct CacheEntry {
    value: serde_json::Value,
    expires_at: Instant,
    version: u64,
}

impl LocalCache {
    pub fn new(max_entries: usize) -> Self {
        Self {
            entries: DashMap::new(),
            max_entries,
            hits: std::sync::atomic::AtomicU64::new(0),
            misses: std::sync::atomic::AtomicU64::new(0),
        }
    }

    pub fn get<T: DeserializeOwned>(&self, key: &str) -> Option<T> {
        let entry = self.entries.get(key)?;
        if entry.expires_at <= Instant::now() {
            drop(entry);
            self.entries.remove(key);
            self.misses.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            return None;
        }
        self.hits.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        serde_json::from_value(entry.value.clone()).ok()
    }

    pub fn set<T: Serialize>(&self, key: &str, value: T, ttl: Duration) -> Result<(), AppError> {
        let value = serde_json::to_value(value)
            .map_err(|e| AppError::InternalError(format!("序列化缓存值失败: {}", e)))?;

        if self.entries.len() >= self.max_entries {
            self.evict_lru();
        }

        self.entries.insert(
            key.to_string(),
            CacheEntry {
                value,
                expires_at: Instant::now() + ttl,
                version: 0,
            },
        );
        Ok(())
    }

    pub fn invalidate(&self, key: &str) {
        self.entries.remove(key);
    }

    pub fn invalidate_pattern(&self, pattern: &str) -> usize {
        let keys_to_remove: Vec<String> = self.entries
            .iter()
            .filter(|entry| entry.key().contains(pattern))
            .map(|entry| entry.key().clone())
            .collect();
        
        let count = keys_to_remove.len();
        for key in keys_to_remove {
            self.entries.remove(&key);
        }
        count
    }

    fn evict_lru(&self) {
        let mut oldest_key: Option<String> = None;
        let mut oldest_time = Instant::now() + Duration::from_secs(86400);

        for entry in self.entries.iter() {
            if entry.expires_at < oldest_time {
                oldest_time = entry.expires_at;
                oldest_key = Some(entry.key().clone());
            }
        }

        if let Some(key) = oldest_key {
            self.entries.remove(&key);
        }
    }

    pub fn stats(&self) -> CacheStats {
        CacheStats {
            hits: self.hits.load(std::sync::atomic::Ordering::Relaxed),
            misses: self.misses.load(std::sync::atomic::Ordering::Relaxed),
            size: self.entries.len(),
            max_size: self.max_entries,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct CacheStats {
    pub hits: u64,
    pub misses: u64,
    pub size: usize,
    pub max_size: usize,
}

impl DataAccessLayer {
    pub async fn new(database_url: &str, redis_url: &str) -> Result<Self, AppError> {
        let db_pool = PgPoolOptions::new()
            .max_connections(20)
            .acquire_timeout(Duration::from_secs(5))
            .connect(database_url)
            .await
            .map_err(|e| AppError::ConfigError(format!("连接数据库失败: {}", e)))?;

        let manager = RedisConnectionManager::new(redis_url)
            .map_err(|e| AppError::ConfigError(format!("创建Redis连接管理器失败: {}", e)))?;
        
        let redis_pool = bb8::Pool::builder()
            .max_size(20)
            .build(manager)
            .await
            .map_err(|e| AppError::ConfigError(format!("创建Redis连接池失败: {}", e)))?;

        let cache_config = CacheConfig::default();
        let local_cache = Arc::new(LocalCache::new(cache_config.max_local_entries));

        Ok(Self {
            db_pool,
            redis_pool,
            local_cache,
            cache_config,
        })
    }

    fn build_cache_key(&self, namespace: &str, key: &str) -> String {
        format!("{}{}:{}", self.cache_config.cache_key_prefix, namespace, key)
    }

    pub async fn cache_get<T: DeserializeOwned + Clone>(
        &self,
        namespace: &str,
        key: &str,
    ) -> Result<Option<T>, AppError> {
        let cache_key = self.build_cache_key(namespace, key);

        if let Some(value) = self.local_cache.get::<T>(&cache_key) {
            tracing::debug!("本地缓存命中: {}", cache_key);
            return Ok(Some(value));
        }

        let mut conn = self.redis_pool.get().await
            .map_err(|e| AppError::InternalError(format!("获取Redis连接失败: {}", e)))?;
        
        let redis_value: Option<String> = conn.get(&cache_key).await
            .map_err(|e| AppError::InternalError(format!("Redis读取失败: {}", e)))?;

        if let Some(redis_str) = redis_value {
            let value: T = serde_json::from_str(&redis_str)
                .map_err(|e| AppError::InternalError(format!("反序列化Redis值失败: {}", e)))?;
            
            self.local_cache.set(&cache_key, value.clone(), self.cache_config.default_ttl)?;
            tracing::debug!("Redis缓存命中: {}", cache_key);
            return Ok(Some(value));
        }

        tracing::debug!("缓存未命中: {}", cache_key);
        Ok(None)
    }

    pub async fn cache_set<T: Serialize>(
        &self,
        namespace: &str,
        key: &str,
        value: &T,
        ttl: Option<Duration>,
    ) -> Result<(), AppError> {
        let cache_key = self.build_cache_key(namespace, key);
        let ttl = ttl.unwrap_or(self.cache_config.default_ttl);
        let ttl_secs = ttl.as_secs() as usize;

        let serialized = serde_json::to_string(value)
            .map_err(|e| AppError::InternalError(format!("序列化缓存值失败: {}", e)))?;

        self.local_cache.set(&cache_key, value, ttl)?;

        let mut conn = self.redis_pool.get().await
            .map_err(|e| AppError::InternalError(format!("获取Redis连接失败: {}", e)))?;
        
        let _: () = conn.set_ex(&cache_key, &serialized, ttl_secs).await
            .map_err(|e| AppError::InternalError(format!("Redis写入失败: {}", e)))?;

        Ok(())
    }

    pub async fn cache_invalidate(
        &self,
        namespace: &str,
        key: &str,
    ) -> Result<(), AppError> {
        let cache_key = self.build_cache_key(namespace, key);
        
        self.local_cache.invalidate(&cache_key);

        let mut conn = self.redis_pool.get().await
            .map_err(|e| AppError::InternalError(format!("获取Redis连接失败: {}", e)))?;
        
        let _: () = conn.del(&cache_key).await
            .map_err(|e| AppError::InternalError(format!("Redis删除失败: {}", e)))?;

        Ok(())
    }

    pub async fn cache_invalidate_pattern(
        &self,
        pattern: &str,
    ) -> Result<usize, AppError> {
        let local_count = self.local_cache.invalidate_pattern(pattern);

        let mut conn = self.redis_pool.get().await
            .map_err(|e| AppError::InternalError(format!("获取Redis连接失败: {}", e)))?;
        
        let redis_pattern = format!("*{}*", pattern);
        let keys: Vec<String> = redis::cmd("KEYS")
            .arg(&redis_pattern)
            .query_async(&mut *conn)
            .await
            .map_err(|e| AppError::InternalError(format!("Redis KEYS查询失败: {}", e)))?;
        
        let redis_count = keys.len();
        if !keys.is_empty() {
            let _: () = conn.del(keys).await
                .map_err(|e| AppError::InternalError(format!("Redis批量删除失败: {}", e)))?;
        }

        Ok(local_count + redis_count)
    }

    pub async fn cache_get_or_load<T, F>(
        &self,
        namespace: &str,
        key: &str,
        loader: F,
        ttl: Option<Duration>,
    ) -> Result<T, AppError>
    where
        T: DeserializeOwned + Serialize + Clone,
        F: std::future::Future<Output = Result<T, AppError>>,
    {
        if let Some(cached) = self.cache_get::<T>(namespace, key).await? {
            return Ok(cached);
        }

        let value = loader.await?;
        self.cache_set(namespace, key, &value, ttl).await?;
        
        Ok(value)
    }

    pub async fn create_entity(&self, entity: &Entity) -> Result<Entity, AppError> {
        let result = sqlx::query!(
            r#"
            INSERT INTO entities (id, type, status, attributes, created_at, updated_at)
            VALUES ($1, $2, $3, $4, $5, $6)
            RETURNING id, type, status, attributes, created_at, updated_at
            "#,
            entity.id,
            entity.r#type,
            serde_json::to_value(&entity.status).unwrap(),
            serde_json::to_value(&entity.attributes).unwrap(),
            entity.created_at,
            entity.updated_at,
        )
        .fetch_one(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("创建实体失败: {}", e)))?;

        let entity = Entity {
            id: result.id,
            r#type: result.r#type,
            status: serde_json::from_value(result.status).unwrap(),
            attributes: serde_json::from_value(result.attributes).unwrap(),
            created_at: result.created_at,
            updated_at: result.updated_at,
        };

        self.cache_invalidate("entities", &entity.id).await?;
        Ok(entity)
    }

    pub async fn get_entity(&self, id: &str) -> Result<Option<Entity>, AppError> {
        self.cache_get_or_load(
            "entities",
            id,
            async move {
                let result = sqlx::query!(
                    r#"
                    SELECT id, type, status, attributes, created_at, updated_at
                    FROM entities WHERE id = $1
                    "#,
                    id
                )
                .fetch_optional(&self.db_pool)
                .await
                .map_err(|e| AppError::InternalError(format!("查询实体失败: {}", e)))?;

                match result {
                    Some(row) => Ok(Entity {
                        id: row.id,
                        r#type: row.r#type,
                        status: serde_json::from_value(row.status).unwrap(),
                        attributes: serde_json::from_value(row.attributes).unwrap(),
                        created_at: row.created_at,
                        updated_at: row.updated_at,
                    }),
                    None => Err(AppError::NotFound(format!("实体不存在: {}", id))),
                }
            },
            None,
        )
        .await
        .map(Some)
    }

    pub async fn update_entity(&self, entity: &Entity) -> Result<Entity, AppError> {
        let result = sqlx::query!(
            r#"
            UPDATE entities 
            SET status = $1, attributes = $2, updated_at = $3, version = version + 1
            WHERE id = $4
            RETURNING id, type, status, attributes, created_at, updated_at
            "#,
            serde_json::to_value(&entity.status).unwrap(),
            serde_json::to_value(&entity.attributes).unwrap(),
            entity.updated_at,
            entity.id,
        )
        .fetch_optional(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("更新实体失败: {}", e)))?;

        match result {
            Some(row) => {
                let entity = Entity {
                    id: row.id,
                    r#type: row.r#type,
                    status: serde_json::from_value(row.status).unwrap(),
                    attributes: serde_json::from_value(row.attributes).unwrap(),
                    created_at: row.created_at,
                    updated_at: row.updated_at,
                };
                self.cache_invalidate("entities", &entity.id).await?;
                Ok(entity)
            }
            None => Err(AppError::Conflict(format!("实体版本冲突: {}", entity.id))),
        }
    }

    pub async fn save_config(&self, config: &Config) -> Result<Config, AppError> {
        let result = sqlx::query!(
            r#"
            INSERT INTO configs (config_id, namespace, version, parameters, enabled, applied_at)
            VALUES ($1, $2, $3, $4, $5, $6)
            ON CONFLICT (config_id) DO UPDATE SET
                version = EXCLUDED.version,
                parameters = EXCLUDED.parameters,
                enabled = EXCLUDED.enabled,
                applied_at = EXCLUDED.applied_at
            RETURNING config_id, namespace, version, parameters, enabled, applied_at
            "#,
            config.config_id,
            config.namespace,
            config.version as i32,
            serde_json::to_value(&config.parameters).unwrap(),
            config.enabled,
            config.applied_at,
        )
        .fetch_one(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("保存配置失败: {}", e)))?;

        let config = Config {
            config_id: result.config_id,
            namespace: result.namespace,
            version: result.version as u32,
            parameters: serde_json::from_value(result.parameters).unwrap(),
            enabled: result.enabled,
            applied_at: result.applied_at,
        };

        self.cache_invalidate("configs", &config.config_id).await?;
        Ok(config)
    }

    pub async fn get_config(&self, namespace: &str, config_id: &str) -> Result<Option<Config>, AppError> {
        let key = format!("{}:{}", namespace, config_id);
        self.cache_get_or_load(
            "configs",
            &key,
            async move {
                let result = sqlx::query!(
                    r#"
                    SELECT config_id, namespace, version, parameters, enabled, applied_at
                    FROM configs WHERE config_id = $1 AND namespace = $2
                    ORDER BY version DESC
                    LIMIT 1
                    "#,
                    config_id,
                    namespace
                )
                .fetch_optional(&self.db_pool)
                .await
                .map_err(|e| AppError::InternalError(format!("查询配置失败: {}", e)))?;

                match result {
                    Some(row) => Ok(Config {
                        config_id: row.config_id,
                        namespace: row.namespace,
                        version: row.version as u32,
                        parameters: serde_json::from_value(row.parameters).unwrap(),
                        enabled: row.enabled,
                        applied_at: row.applied_at,
                    }),
                    None => Err(AppError::NotFound(format!("配置不存在: {}", config_id))),
                }
            },
            Some(Duration::from_secs(60)),
        )
        .await
        .map(Some)
    }

    pub fn cache_stats(&self) -> CacheStats {
        self.local_cache.stats()
    }

    pub async fn init_database(&self) -> Result<(), AppError> {
        sqlx::query!(
            r#"
            CREATE TABLE IF NOT EXISTS entities (
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                status JSONB NOT NULL,
                attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
                version INTEGER NOT NULL DEFAULT 1,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_entities_type ON entities(type);
            CREATE INDEX IF NOT EXISTS idx_entities_status ON entities(status);
            "#
        )
        .execute(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("创建实体表失败: {}", e)))?;

        sqlx::query!(
            r#"
            CREATE TABLE IF NOT EXISTS configs (
                config_id TEXT NOT NULL,
                namespace TEXT NOT NULL,
                version INTEGER NOT NULL,
                parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
                enabled BOOLEAN NOT NULL DEFAULT true,
                applied_at TIMESTAMPTZ NOT NULL,
                PRIMARY KEY (config_id, version)
            );
            CREATE INDEX IF NOT EXISTS idx_configs_namespace ON configs(namespace);
            "#
        )
        .execute(&self.db_pool)
        .await
        .map_err(|e| AppError::InternalError(format!("创建配置表失败: {}", e)))?;

        Ok(())
    }
}
