use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{anyhow, Context, Result};
use async_trait::async_trait;
use bb8_redis::{bb8, RedisConnectionManager};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use parking_lot::RwLock;
use redis::AsyncCommands;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use sqlx::{postgres::PgPoolOptions, PgPool};
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DatabaseConfig {
    pub host: String,
    pub port: u16,
    pub database: String,
    pub username: String,
    pub password: String,
    pub max_connections: u32,
    pub min_connections: u32,
    pub connect_timeout: Duration,
    pub idle_timeout: Duration,
}

impl Default for DatabaseConfig {
    fn default() -> Self {
        Self {
            host: "localhost".to_string(),
            port: 5432,
            database: "app".to_string(),
            username: "postgres".to_string(),
            password: "postgres".to_string(),
            max_connections: 100,
            min_connections: 5,
            connect_timeout: Duration::from_secs(30),
            idle_timeout: Duration::from_secs(300),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RedisConfig {
    pub host: String,
    pub port: u16,
    pub password: Option<String>,
    pub database: i64,
    pub max_connections: u32,
    pub min_connections: u32,
    pub connect_timeout: Duration,
}

impl Default for RedisConfig {
    fn default() -> Self {
        Self {
            host: "localhost".to_string(),
            port: 6379,
            password: None,
            database: 0,
            max_connections: 50,
            min_connections: 2,
            connect_timeout: Duration::from_secs(10),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueryPlan {
    pub query_id: String,
    pub sql: String,
    pub estimated_cost: f64,
    pub actual_cost: Option<f64>,
    pub execution_time_ms: Option<u64>,
    pub cache_hit: bool,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone)]
pub struct CacheEntry {
    pub value: Vec<u8>,
    pub expires_at: Option<Instant>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectionPoolStats {
    pub total_connections: u32,
    pub idle_connections: u32,
    pub active_connections: u32,
    pub waiting_tasks: u32,
    pub total_queries_executed: u64,
    pub total_cache_hits: u64,
    pub total_cache_misses: u64,
}

type QueryCache = DashMap<String, CacheEntry>;

pub struct DataAccessLayer {
    pg_pool: Option<PgPool>,
    redis_pool: Option<bb8::Pool<RedisConnectionManager>>,
    cache: QueryCache,
    query_plans: DashMap<String, Vec<QueryPlan>>,
    stats: Arc<RwLock<ConnectionPoolStats>>,
    default_ttl: Duration,
    shutdown_tx: Option<mpsc::Sender<()>>,
}

impl DataAccessLayer {
    pub fn new() -> Self {
        Self {
            pg_pool: None,
            redis_pool: None,
            cache: DashMap::new(),
            query_plans: DashMap::new(),
            stats: Arc::new(RwLock::new(ConnectionPoolStats {
                total_connections: 0,
                idle_connections: 0,
                active_connections: 0,
                waiting_tasks: 0,
                total_queries_executed: 0,
                total_cache_hits: 0,
                total_cache_misses: 0,
            })),
            default_ttl: Duration::from_secs(300),
            shutdown_tx: None,
        }
    }

    pub fn with_default_ttl(mut self, ttl: Duration) -> Self {
        self.default_ttl = ttl;
        self
    }

    pub async fn connect_postgres(&mut self, config: DatabaseConfig) -> Result<()> {
        let database_url = format!(
            "postgres://{}:{}@{}:{}/{}",
            config.username, config.password, config.host, config.port, config.database
        );

        let pool = PgPoolOptions::new()
            .max_connections(config.max_connections)
            .min_connections(config.min_connections)
            .acquire_timeout(config.connect_timeout)
            .idle_timeout(config.idle_timeout)
            .connect(&database_url)
            .await
            .with_context(|| "Failed to connect to PostgreSQL")?;

        self.pg_pool = Some(pool);
        info!("Connected to PostgreSQL at {}:{}", config.host, config.port);
        Ok(())
    }

    pub async fn connect_redis(&mut self, config: RedisConfig) -> Result<()> {
        let redis_url = if let Some(password) = &config.password {
            format!("redis://:{}@{}:{}/{}", password, config.host, config.port, config.database)
        } else {
            format!("redis://{}:{}/{}", config.host, config.port, config.database)
        };

        let manager = RedisConnectionManager::new(redis_url)?;
        let pool = bb8::Pool::builder()
            .max_size(config.max_connections)
            .min_idle(Some(config.min_connections))
            .connection_timeout(config.connect_timeout)
            .build(manager)
            .await
            .with_context(|| "Failed to connect to Redis")?;

        self.redis_pool = Some(pool);
        info!("Connected to Redis at {}:{}", config.host, config.port);
        Ok(())
    }

    pub fn get_postgres_pool(&self) -> Result<&PgPool> {
        self.pg_pool.as_ref().ok_or_else(|| anyhow!("PostgreSQL pool not initialized"))
    }

    pub fn get_redis_pool(&self) -> Result<&bb8::Pool<RedisConnectionManager>> {
        self.redis_pool.as_ref().ok_or_else(|| anyhow!("Redis pool not initialized"))
    }

    pub async fn query_one<T>(&self, sql: &str, params: &[&(dyn sqlx::Encode<'_, sqlx::Postgres> + Send + Sync)]) -> Result<T>
    where
        T: for<'r> sqlx::FromRow<'r, sqlx::postgres::PgRow> + Send + Unpin,
    {
        let pool = self.get_postgres_pool()?;
        
        let start = Instant::now();
        let result = sqlx::query_as::<_, T>(sql)
            .build()
            .fetch_one(pool)
            .await;
        
        let execution_time = start.elapsed().as_millis() as u64;
        
        self.record_query(sql, execution_time, false);
        
        result.map_err(|e| anyhow!("Query failed: {}", e))
    }

    pub async fn query_all<T>(&self, sql: &str) -> Result<Vec<T>>
    where
        T: for<'r> sqlx::FromRow<'r, sqlx::postgres::PgRow> + Send + Unpin,
    {
        let pool = self.get_postgres_pool()?;
        
        let start = Instant::now();
        let result = sqlx::query_as::<_, T>(sql)
            .fetch_all(pool)
            .await;
        
        let execution_time = start.elapsed().as_millis() as u64;
        
        self.record_query(sql, execution_time, false);
        
        result.map_err(|e| anyhow!("Query failed: {}", e))
    }

    pub async fn execute(&self, sql: &str) -> Result<u64> {
        let pool = self.get_postgres_pool()?;
        
        let start = Instant::now();
        let result = sqlx::query(sql)
            .execute(pool)
            .await;
        
        let execution_time = start.elapsed().as_millis() as u64;
        
        self.record_query(sql, execution_time, false);
        
        result
            .map(|r| r.rows_affected())
            .map_err(|e| anyhow!("Execute failed: {}", e))
    }

    pub async fn execute_transaction<F, T>(&self, f: F) -> Result<T>
    where
        F: for<'a> FnOnce(&'a mut sqlx::Transaction<'_, sqlx::Postgres>) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<T>> + Send + 'a>> + Send,
        T: Send,
    {
        let pool = self.get_postgres_pool()?;
        let mut tx = pool.begin().await?;
        
        match f(&mut tx).await {
            Ok(result) => {
                tx.commit().await?;
                Ok(result)
            }
            Err(e) => {
                tx.rollback().await?;
                Err(e)
            }
        }
    }

    fn record_query(&self, sql: &str, execution_time_ms: u64, cache_hit: bool) {
        let query_plan = QueryPlan {
            query_id: format!("q_{}", Uuid::new_v4().simple()),
            sql: sql.to_string(),
            estimated_cost: 0.0,
            actual_cost: Some(execution_time_ms as f64),
            execution_time_ms: Some(execution_time_ms),
            cache_hit,
            timestamp: Utc::now(),
        };

        let sql_hash = self.hash_sql(sql);
        let mut plans = self.query_plans
            .entry(sql_hash)
            .or_insert_with(Vec::new);
        
        plans.push(query_plan);
        
        if plans.len() > 1000 {
            plans = plans.split_off(plans.len() - 500).into();
        }
        
        drop(plans);

        let mut stats = self.stats.write();
        stats.total_queries_executed = stats.total_queries_executed.saturating_add(1);
        if cache_hit {
            stats.total_cache_hits = stats.total_cache_hits.saturating_add(1);
        } else {
            stats.total_cache_misses = stats.total_cache_misses.saturating_add(1);
        }
    }

    fn hash_sql(&self, sql: &str) -> String {
        use std::collections::hash_map::DefaultHasher;
        use std::hash::{Hash, Hasher};
        
        let mut hasher = DefaultHasher::new();
        sql.hash(&mut hasher);
        format!("{:x}", hasher.finish())
    }

    pub fn cache_set<T: Serialize>(&self, key: &str, value: &T, ttl: Option<Duration>) -> Result<()> {
        let serialized = serde_json::to_vec(value)?;
        let entry = CacheEntry {
            value: serialized,
            expires_at: ttl.map(|d| Instant::now() + d),
        };
        self.cache.insert(key.to_string(), entry);
        Ok(())
    }

    pub fn cache_get<T: DeserializeOwned>(&self, key: &str) -> Option<T> {
        let entry = self.cache.get(key)?;
        
        if let Some(expires_at) = entry.expires_at {
            if Instant::now() > expires_at {
                drop(entry);
                self.cache.remove(key);
                return None;
            }
        }
        
        serde_json::from_slice(&entry.value).ok()
    }

    pub fn cache_delete(&self, key: &str) {
        self.cache.remove(key);
    }

    pub fn cache_clear(&self) {
        self.cache.clear();
    }

    pub async fn redis_set<T: Serialize>(&self, key: &str, value: &T, ttl: Option<Duration>) -> Result<()> {
        let pool = self.get_redis_pool()?;
        let mut conn = pool.get().await?;
        
        let serialized = serde_json::to_string(value)?;
        
        if let Some(ttl) = ttl {
            conn.set_ex::<_, _, ()>(key, serialized, ttl.as_secs() as usize).await?;
        } else {
            conn.set::<_, _, ()>(key, serialized).await?;
        }
        
        Ok(())
    }

    pub async fn redis_get<T: DeserializeOwned>(&self, key: &str) -> Result<Option<T>> {
        let pool = self.get_redis_pool()?;
        let mut conn = pool.get().await?;
        
        let value: Option<String> = conn.get(key).await?;
        
        match value {
            Some(s) => Ok(Some(serde_json::from_str(&s)?)),
            None => Ok(None),
        }
    }

    pub async fn redis_delete(&self, key: &str) -> Result<()> {
        let pool = self.get_redis_pool()?;
        let mut conn = pool.get().await?;
        conn.del::<_, ()>(key).await?;
        Ok(())
    }

    pub async fn redis_ping(&self) -> Result<()> {
        let pool = self.get_redis_pool()?;
        let mut conn = pool.get().await?;
        let _: String = redis::cmd("PING").query_async(&mut *conn).await?;
        Ok(())
    }

    pub fn get_stats(&self) -> ConnectionPoolStats {
        self.stats.read().clone()
    }

    pub fn get_query_plans(&self, sql: &str) -> Vec<QueryPlan> {
        let hash = self.hash_sql(sql);
        self.query_plans.get(&hash).map(|p| p.clone()).unwrap_or_default()
    }

    pub fn optimize_query(&self, sql: &str) -> String {
        let mut optimized = sql.to_string();
        
        optimized = optimized.replace("SELECT *", "SELECT id, created_at, updated_at");
        
        if !optimized.to_uppercase().contains("LIMIT") {
            optimized.push_str(" LIMIT 100");
        }
        
        optimized
    }

    pub async fn start_health_check(&mut self) -> Result<()> {
        let (tx, mut rx) = mpsc::channel::<()>(1);
        self.shutdown_tx = Some(tx);
        
        let pg_pool = self.pg_pool.clone();
        let redis_pool = self.redis_pool.clone();
        let stats = self.stats.clone();
        
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(60));
            
            loop {
                tokio::select! {
                    _ = interval.tick() => {
                        if let Some(pool) = &pg_pool {
                            let _ = sqlx::query("SELECT 1").execute(pool).await;
                        }
                        
                        if let Some(pool) = &redis_pool {
                            if let Ok(mut conn) = pool.get().await {
                                let _: Result<String, _> = redis::cmd("PING").query_async(&mut *conn).await;
                            }
                        }
                        
                        debug!("Health check completed");
                    }
                    _ = rx.recv() => {
                        info!("Health check shutting down");
                        break;
                    }
                }
            }
            
            let _ = stats;
        });
        
        Ok(())
    }

    pub fn stop(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            drop(tx);
        }
    }

    pub async fn close(&mut self) {
        self.stop();
        
        if let Some(pool) = self.pg_pool.take() {
            pool.close().await;
            info!("PostgreSQL connection pool closed");
        }
        
        self.redis_pool = None;
        info!("Redis connection pool closed");
    }
}

impl Default for DataAccessLayer {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for DataAccessLayer {
    fn drop(&mut self) {
        self.stop();
    }
}

#[async_trait]
pub trait Repository<T> {
    async fn find_by_id(&self, id: &str) -> Result<Option<T>>;
    async fn find_all(&self) -> Result<Vec<T>>;
    async fn create(&self, entity: &T) -> Result<T>;
    async fn update(&self, entity: &T) -> Result<T>;
    async fn delete(&self, id: &str) -> Result<()>;
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BaseEntity {
    pub id: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl Default for BaseEntity {
    fn default() -> Self {
        let now = Utc::now();
        Self {
            id: format!("ent_{}", Uuid::new_v4().simple()),
            created_at: now,
            updated_at: now,
        }
    }
}

pub fn build_query(table: &str, columns: &[&str], conditions: &[(&str, &str)]) -> String {
    let columns_str = if columns.is_empty() {
        "*".to_string()
    } else {
        columns.join(", ")
    };
    
    let mut query = format!("SELECT {} FROM {}", columns_str, table);
    
    if !conditions.is_empty() {
        let where_clause: Vec<String> = conditions
            .iter()
            .map(|(k, v)| format!("{} = '{}'", k, v))
            .collect();
        query.push_str(" WHERE ");
        query.push_str(&where_clause.join(" AND "));
    }
    
    query
}

pub fn paginate_query(sql: &str, page: u32, page_size: u32) -> String {
    let offset = (page - 1) * page_size;
    format!("{} LIMIT {} OFFSET {}", sql, page_size, offset)
}
