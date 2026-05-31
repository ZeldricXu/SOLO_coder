use std::sync::Arc;

use crate::infra::cache::Cache;
use crate::infra::config::AppConfig;
use crate::infra::error::AppResult;
use crate::infra::metrics::MetricsRegistry;
use crate::infra::storage::Database;

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<AppConfig>,
    pub database: Arc<Database>,
    pub cache: Arc<Cache>,
    pub metrics: Arc<MetricsRegistry>,
}

impl AppState {
    pub async fn new(config: AppConfig) -> AppResult<Self> {
        let database = Database::new(&config.database).await?;
        let cache = Cache::new(&config.redis).await?;
        let metrics = MetricsRegistry::new();

        Ok(Self {
            config: Arc::new(config),
            database: Arc::new(database),
            cache: Arc::new(cache),
            metrics: Arc::new(metrics),
        })
    }

    pub fn config(&self) -> &AppConfig {
        &self.config
    }

    pub fn database(&self) -> &Database {
        &self.database
    }

    pub fn cache(&self) -> &Cache {
        &self.cache
    }

    pub fn metrics(&self) -> &MetricsRegistry {
        &self.metrics
    }
}
