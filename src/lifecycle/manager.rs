use crate::models::StreamSQLError;
use crate::lifecycle::policy::{DataMetadata, LifecyclePolicy, StorageTier};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LifecycleManagerConfig {
    pub scan_interval_secs: u64,
    pub batch_size: usize,
    pub max_concurrent_migrations: usize,
    pub enable_auto_tiering: bool,
    pub enable_auto_archive: bool,
    pub enable_auto_cleanup: bool,
}

impl Default for LifecycleManagerConfig {
    fn default() -> Self {
        Self {
            scan_interval_secs: 3600,
            batch_size: 100,
            max_concurrent_migrations: 4,
            enable_auto_tiering: true,
            enable_auto_archive: true,
            enable_auto_cleanup: true,
        }
    }
}

pub struct LifecycleManager {
    config: LifecycleManagerConfig,
    policies: Arc<RwLock<HashMap<String, LifecyclePolicy>>>,
    metadata_store: Arc<RwLock<HashMap<String, DataMetadata>>>,
    migrator: Arc<crate::lifecycle::migrator::DataMigrator>,
    archiver: Arc<crate::lifecycle::archiver::DataArchiver>,
    cleaner: Arc<crate::lifecycle::cleaner::DataCleaner>,
}

impl LifecycleManager {
    pub fn new(config: LifecycleManagerConfig) -> Self {
        Self {
            config: config.clone(),
            policies: Arc::new(RwLock::new(HashMap::new())),
            metadata_store: Arc::new(RwLock::new(HashMap::new())),
            migrator: Arc::new(crate::lifecycle::migrator::DataMigrator::new()),
            archiver: Arc::new(crate::lifecycle::archiver::DataArchiver::new(
                crate::lifecycle::policy::ArchiveConfig::default(),
            )),
            cleaner: Arc::new(crate::lifecycle::cleaner::DataCleaner::new(
                crate::lifecycle::policy::CleanupConfig::default(),
            )),
        }
    }

    pub async fn register_policy(&self, policy: LifecyclePolicy) {
        let mut policies = self.policies.write().await;
        policies.insert(policy.policy_id.clone(), policy);
    }

    pub async fn get_policy(&self, policy_id: &str) -> Option<LifecyclePolicy> {
        let policies = self.policies.read().await;
        policies.get(policy_id).cloned()
    }

    pub async fn list_policies(&self) -> Vec<LifecyclePolicy> {
        let policies = self.policies.read().await;
        policies.values().cloned().collect()
    }

    pub async fn remove_policy(&self, policy_id: &str) -> Option<LifecyclePolicy> {
        let mut policies = self.policies.write().await;
        policies.remove(policy_id)
    }

    pub async fn register_data(&self, metadata: DataMetadata) {
        let mut store = self.metadata_store.write().await;
        store.insert(metadata.data_id.clone(), metadata);
    }

    pub async fn get_metadata(&self, data_id: &str) -> Option<DataMetadata> {
        let store = self.metadata_store.read().await;
        store.get(data_id).cloned()
    }

    pub async fn list_metadata(&self) -> Vec<DataMetadata> {
        let store = self.metadata_store.read().await;
        store.values().cloned().collect()
    }

    pub async fn access_data(&self, data_id: &str) -> Option<DataMetadata> {
        let mut store = self.metadata_store.write().await;
        if let Some(metadata) = store.get_mut(data_id) {
            metadata.access();
            Some(metadata.clone())
        } else {
            None
        }
    }

    pub async fn run_lifecycle_cycle(&self) -> LifecycleCycleResult {
        let mut result = LifecycleCycleResult::default();
        result.started_at = chrono::Utc::now();

        let metadata_list = self.list_metadata().await;
        let policies = self.list_policies().await;

        for metadata in metadata_list {
            for policy in &policies {
                if !policy.enabled {
                    continue;
                }

                if policy.namespace != "*" && policy.namespace != metadata.table_name {
                    continue;
                }

                if self.config.enable_auto_tiering {
                    if let Some(rule) = policy.get_applicable_rule(&metadata) {
                        match self.migrate_data(&metadata.data_id, rule.to_tier.clone()).await {
                            Ok(_) => result.tiered_count += 1,
                            Err(_) => result.failed_count += 1,
                        }
                    }
                }

                if self.config.enable_auto_archive && policy.should_archive(&metadata) {. 
                    match self.archive_data(&metadata.data_id).await {
                        Ok(_) => result.archived_count += 1,
                        Err(_) => result.failed_count += 1,
                    }
                }

                if self.config.enable_auto_cleanup && policy.should_delete(&metadata) {
                    match self.clean_data(&metadata.data_id).await {
                        Ok(_) => result.deleted_count += 1,
                        Err(_) => result.failed_count += 1,
                    }
                }
            }
        }

        result.completed_at = Some(chrono::Utc::now());
        result
    }

    pub async fn migrate_data(
        &self,
        data_id: &str,
        target_tier: StorageTier,
    ) -> Result<(), StreamSQLError> {
        let mut store = self.metadata_store.write().await;
        let metadata = store
            .get_mut(data_id)
            .ok_or_else(|| StreamSQLError::Lifecycle(format!("Data not found: {}", data_id)))?;

        if metadata.current_tier == target_tier {
            return Ok(());
        }

        self.migrator.migrate(metadata, target_tier.clone()).await?;
        metadata.transition_tier(target_tier, "lifecycle policy");

        Ok(())
    }

    pub async fn archive_data(&self, data_id: &str) -> Result<(), StreamSQLError> {
        let mut store = self.metadata_store.write().await;
        let metadata = store
            .get_mut(data_id)
            .ok_or_else(|| StreamSQLError::Lifecycle(format!("Data not found: {}", data_id)))?;

        if metadata.current_tier == StorageTier::Archived {
            return Ok(());
        }

        self.archiver.archive(metadata).await?;
        metadata.transition_tier(StorageTier::Archived, "archive policy");

        Ok(())
    }

    pub async fn restore_data(&self, data_id: &str) -> Result<(), StreamSQLError> {
        let mut store = self.metadata_store.write().await;
        let metadata = store
            .get_mut(data_id)
            .ok_or_else(|| StreamSQLError::Lifecycle(format!("Data not found: {}", data_id)))?;

        if metadata.current_tier != StorageTier::Archived {
            return Err(StreamSQLError::Lifecycle(format!(
                "Data not archived: {}",
                data_id
            )));
        }

        self.archiver.restore(metadata).await?;
        metadata.transition_tier(StorageTier::Hot, "manual restore");

        Ok(())
    }

    pub async fn clean_data(&self, data_id: &str) -> Result<(), StreamSQLError> {
        let mut store = self.metadata_store.write().await;
        let metadata = store
            .get(data_id)
            .cloned()
            .ok_or_else(|| StreamSQLError::Lifecycle(format!("Data not found: {}", data_id)))?;

        self.cleaner.clean(&metadata).await?;
        store.remove(data_id);

        Ok(())
    }

    pub async fn get_stats(&self) -> LifecycleStats {
        let store = self.metadata_store.read().await;

        let mut stats = LifecycleStats::default();
        stats.total_data = store.len();

        for metadata in store.values() {
            match metadata.current_tier {
                StorageTier::Hot => stats.hot_count += 1,
                StorageTier::Warm => stats.warm_count += 1,
                StorageTier::Cold => stats.cold_count += 1,
                StorageTier::Archived => stats.archived_count += 1,
            }
            stats.total_bytes += metadata.size_bytes;
        }

        stats
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct LifecycleCycleResult {
    pub tiered_count: usize,
    pub archived_count: usize,
    pub deleted_count: usize,
    pub failed_count: usize,
    pub started_at: chrono::DateTime<chrono::Utc>,
    pub completed_at: Option<chrono::DateTime<chrono::Utc>>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct LifecycleStats {
    pub total_data: usize,
    pub hot_count: usize,
    pub warm_count: usize,
    pub cold_count: usize,
    pub archived_count: usize,
    pub total_bytes: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_manager_creation() {
        let config = LifecycleManagerConfig::default();
        let manager = LifecycleManager::new(config);

        let policies = manager.list_policies().await;
        assert!(policies.is_empty());

        let stats = manager.get_stats().await;
        assert_eq!(stats.total_data, 0);
    }

    #[tokio::test]
    async fn test_policy_registration() {
        let config = LifecycleManagerConfig::default();
        let manager = LifecycleManager::new(config);

        let policy = LifecyclePolicy::new("test", "default");
        let policy_id = policy.policy_id.clone();

        manager.register_policy(policy).await;

        let retrieved = manager.get_policy(&policy_id).await;
        assert!(retrieved.is_some());
        assert_eq!(retrieved.unwrap().name, "test");
    }

    #[tokio::test]
    async fn test_data_registration() {
        let config = LifecycleManagerConfig::default();
        let manager = LifecycleManager::new(config);

        let metadata = DataMetadata::new("d1", "orders", 1024);
        manager.register_data(metadata).await;

        let stats = manager.get_stats().await;
        assert_eq!(stats.total_data, 1);
        assert_eq!(stats.hot_count, 1);
    }

    #[tokio::test]
    async fn test_access_data() {
        let config = LifecycleManagerConfig::default();
        let manager = LifecycleManager::new(config);

        let metadata = DataMetadata::new("d1", "orders", 1024);
        manager.register_data(metadata).await;

        let accessed = manager.access_data("d1").await;
        assert!(accessed.is_some());
        assert!(accessed.unwrap().last_accessed_at.is_some());
    }
}
