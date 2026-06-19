pub mod repository;

pub use repository::model_repo::{
    Model, ModelVersion, ModelDeployment,
    CreateModelParams, CreateModelVersionParams, UpdateModelParams, UpdateModelVersionParams,
    ModelRepository,
};
pub use repository::tenant_repo::{
    Tenant, CreateTenantParams, UpdateTenantParams, TenantRepository,
};
pub use repository::experiment_repo::{
    Experiment, ExperimentGroup, ExperimentMetric, ExperimentResult,
    CreateExperimentParams, UpdateExperimentParams,
    CreateExperimentGroupParams, CreateExperimentMetricParams, CreateExperimentResultParams,
    ExperimentRepository,
};
pub use repository::routing_repo::{
    RoutingRule, CreateRoutingRuleParams, UpdateRoutingRuleParams, RoutingRepository,
};
pub use repository::gpu_repo::{
    GpuDevice, CreateGpuDeviceParams, UpdateGpuDeviceParams, GpuRepository,
};

pub use error::{DbError, DbResult};

use std::time::Duration;

use anyhow::{Context, Result};
use redis::aio::ConnectionManager;
use serde::{Deserialize, Serialize};
use sqlx::postgres::{PgPool, PgPoolOptions};
use tracing::info;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DatabaseConfig {
    pub url: String,
    pub max_connections: u32,
    pub min_connections: u32,
    pub connect_timeout_secs: u64,
    pub idle_timeout_secs: u64,
}

impl Default for DatabaseConfig {
    fn default() -> Self {
        Self {
            url: "postgres://postgres:postgres@localhost:5432/model_serving".to_string(),
            max_connections: 20,
            min_connections: 5,
            connect_timeout_secs: 10,
            idle_timeout_secs: 300,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RedisConfig {
    pub url: String,
    pub max_connections: usize,
    pub connect_timeout_secs: u64,
    pub response_timeout_secs: u64,
}

impl Default for RedisConfig {
    fn default() -> Self {
        Self {
            url: "redis://localhost:6379".to_string(),
            max_connections: 50,
            connect_timeout_secs: 10,
            response_timeout_secs: 10,
        }
    }
}

#[derive(Clone)]
pub struct DatabasePool {
    pub pool: PgPool,
}

impl DatabasePool {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub fn inner(&self) -> &PgPool {
        &self.pool
    }

    pub async fn ping(&self) -> Result<()> {
        sqlx::query("SELECT 1")
            .execute(&self.pool)
            .await
            .context("Failed to ping database")?;
        Ok(())
    }

    pub fn model_repo(&self) -> crate::repository::PgModelRepository {
        crate::repository::PgModelRepository::new(self.pool.clone())
    }

    pub fn tenant_repo(&self) -> crate::repository::PgTenantRepository {
        crate::repository::PgTenantRepository::new(self.pool.clone())
    }

    pub fn experiment_repo(&self) -> crate::repository::PgExperimentRepository {
        crate::repository::PgExperimentRepository::new(self.pool.clone())
    }

    pub fn routing_repo(&self) -> crate::repository::PgRoutingRepository {
        crate::repository::PgRoutingRepository::new(self.pool.clone())
    }

    pub fn gpu_repo(&self) -> crate::repository::PgGpuRepository {
        crate::repository::PgGpuRepository::new(self.pool.clone())
    }

    pub async fn get_tenant_by_api_key_hash(&self, api_key_hash: &str) -> DbResult<Option<Tenant>> {
        self.tenant_repo().get_tenant_by_api_key_hash(api_key_hash).await
    }

    pub async fn get_tenant_by_id(&self, id: Uuid) -> DbResult<Option<Tenant>> {
        self.tenant_repo().get_tenant_by_id(id).await
    }

    pub async fn rotate_api_key(&self, id: Uuid, new_api_key: &str, new_api_key_hash: &str) -> DbResult<Tenant> {
        self.tenant_repo().rotate_api_key(id, new_api_key, new_api_key_hash).await
    }

    pub fn mock() -> Self {
        unimplemented!("mock DatabasePool not available without test database")
    }
}

impl std::ops::Deref for DatabasePool {
    type Target = PgPool;

    fn deref(&self) -> &Self::Target {
        &self.pool
    }
}

#[derive(Clone)]
pub struct RedisClient {
    pub manager: ConnectionManager,
}

impl RedisClient {
    pub fn new(manager: ConnectionManager) -> Self {
        Self { manager }
    }

    pub fn inner(&self) -> &ConnectionManager {
        &self.manager
    }

    pub fn mock() -> Self {
        unimplemented!("mock RedisClient not available")
    }
}

impl std::ops::Deref for RedisClient {
    type Target = ConnectionManager;

    fn deref(&self) -> &Self::Target {
        &self.manager
    }
}

pub async fn init_database(config: &DatabaseConfig) -> Result<DatabasePool> {
    info!("Initializing database connection pool to {}", mask_url(&config.url));

    let pool = PgPoolOptions::new()
        .max_connections(config.max_connections)
        .min_connections(config.min_connections)
        .acquire_timeout(Duration::from_secs(config.connect_timeout_secs))
        .idle_timeout(Duration::from_secs(config.idle_timeout_secs))
        .connect(&config.url)
        .await
        .context("Failed to create database connection pool")?;

    let db = DatabasePool::new(pool);
    db.ping().await.context("Failed to verify database connection")?;

    info!("Database connection pool initialized successfully");
    Ok(db)
}

pub async fn init_redis(config: &RedisConfig) -> Result<RedisClient> {
    info!("Initializing Redis client to {}", mask_url(&config.url));

    let client = redis::Client::open(config.url.clone())
        .context("Failed to create Redis client")?;

    let manager = ConnectionManager::new(client)
        .await
        .context("Failed to create Redis connection manager")?;

    let redis_client = RedisClient::new(manager);
    info!("Redis client initialized successfully");

    Ok(redis_client)
}

fn mask_url(url: &str) -> String {
    if let Ok(parsed) = url::Url::parse(url) {
        let scheme = parsed.scheme();
        let host = parsed.host_str().unwrap_or("unknown");
        let port = parsed.port().map(|p| format!(":{}", p)).unwrap_or_default();
        format!("{}://***:***@{}{}", scheme, host, port)
    } else {
        "***".to_string()
    }
}

pub mod error {
    use thiserror::Error;

    #[derive(Error, Debug)]
    pub enum DbError {
        #[error("Database error: {0}")]
        Database(#[from] sqlx::Error),

        #[error("Redis error: {0}")]
        Redis(#[from] redis::RedisError),

        #[error("Not found: {0}")]
        NotFound(String),

        #[error("Conflict: {0}")]
        Conflict(String),

        #[error("Validation error: {0}")]
        Validation(String),
    }

    pub type DbResult<T> = Result<T, DbError>;
}
