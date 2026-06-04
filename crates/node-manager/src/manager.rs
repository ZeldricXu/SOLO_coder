use std::sync::Arc;
use tokio::sync::RwLock;

use common::db::Database;
use common::redis::RedisClient;
use common::config::AppConfig;
use common::error::CdnResult;

use crate::registry::NodeRegistry;
use crate::health_check::HealthChecker;

pub struct NodeManager {
    pub registry: NodeRegistry,
    pub health_checker: HealthChecker,
    config: Arc<RwLock<AppConfig>>,
}

impl NodeManager {
    pub async fn new(config: AppConfig, db: Database, redis: RedisClient) -> CdnResult<Self> {
        let registry = NodeRegistry::new(
            db.clone(),
            redis.clone(),
            config.center.max_heartbeat_failures,
        );

        registry.load_from_database().await?;

        let health_checker = HealthChecker::new(
            registry.clone(),
            redis.clone(),
            config.center.heartbeat_interval_seconds,
            config.center.max_heartbeat_failures,
        );

        Ok(NodeManager {
            registry,
            health_checker,
            config: Arc::new(RwLock::new(config)),
        })
    }

    pub async fn start(&self) -> CdnResult<()> {
        self.health_checker.start().await?;
        tracing::info!("Node manager started");
        Ok(())
    }

    pub async fn stop(&self) -> CdnResult<()> {
        self.health_checker.stop().await?;
        tracing::info!("Node manager stopped");
        Ok(())
    }

    pub async fn update_config(&self, config: AppConfig) -> CdnResult<()> {
        let mut current_config = self.config.write().await;
        *current_config = config;
        Ok(())
    }

    pub async fn get_config(&self) -> CdnResult<AppConfig> {
        let config = self.config.read().await;
        Ok(config.clone())
    }
}

impl Clone for NodeManager {
    fn clone(&self) -> Self {
        NodeManager {
            registry: self.registry.clone(),
            health_checker: self.health_checker.clone(),
            config: self.config.clone(),
        }
    }
}
