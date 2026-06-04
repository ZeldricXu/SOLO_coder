use std::sync::Arc;
use tokio::sync::RwLock;
use std::collections::HashMap;

use common::error::{CdnResult};
use common::models::{ConfigVersion, DomainConfig, CacheRule};
use common::db::Database;
use common::redis::RedisClient;
use common::utils::generate_id;

pub struct ConfigManager {
    db: Database,
    redis: RedisClient,
    versions: Arc<RwLock<HashMap<String, Vec<ConfigVersion>>>>,
}

impl ConfigManager {
    pub fn new(db: Database, redis: RedisClient) -> Self {
        ConfigManager {
            db,
            redis,
            versions: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn create_version(
        &self,
        config_type: String,
        data: serde_json::Value,
        created_by: String,
    ) -> CdnResult<ConfigVersion> {
        let now = chrono::Utc::now();
        
        let version = ConfigVersion {
            id: generate_id(),
            config_type: config_type.clone(),
            version: 1,
            data,
            created_by: Some(created_by),
            description: None,
            created_at: now,
        };

        self.db.create_config_version(&version).await?;

        let mut versions = self.versions.write().await;
        versions.entry(config_type).or_insert_with(Vec::new).push(version.clone());

        Ok(version)
    }

    pub async fn get_latest_version(&self, config_type: &str) -> Option<ConfigVersion> {
        let versions = self.versions.read().await;
        versions.get(config_type).and_then(|v| v.last().cloned())
    }

    pub async fn get_all_versions(&self, config_type: &str) -> Vec<ConfigVersion> {
        let versions = self.versions.read().await;
        versions.get(config_type).cloned().unwrap_or_default()
    }

    pub async fn create_domain_config(&self, config: DomainConfig) -> CdnResult<()> {
        self.db.create_domain_config(&config).await
    }

    pub async fn get_domain_config(&self, domain: &str) -> CdnResult<Option<DomainConfig>> {
        self.db.get_domain_config(domain).await
    }

    pub async fn create_cache_rule(&self, rule: CacheRule) -> CdnResult<()> {
        self.db.create_cache_rule(&rule).await
    }

    pub async fn get_cache_rules(&self, domain: &str) -> CdnResult<Vec<CacheRule>> {
        self.db.get_cache_rules_by_domain_name(domain).await
    }

    pub async fn get_all_domains(&self) -> CdnResult<Vec<DomainConfig>> {
        self.db.get_all_domains().await
    }
}

impl Clone for ConfigManager {
    fn clone(&self) -> Self {
        ConfigManager {
            db: self.db.clone(),
            redis: self.redis.clone(),
            versions: self.versions.clone(),
        }
    }
}
