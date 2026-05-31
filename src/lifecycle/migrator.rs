use crate::models::StreamSQLError;
use crate::lifecycle::policy::{DataMetadata, StorageTier};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MigrationResult {
    pub data_id: String,
    pub from_tier: StorageTier,
    pub to_tier: StorageTier,
    pub success: bool,
    pub bytes_migrated: u64,
    pub duration_ms: u64,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MigrationStats {
    pub total_migrations: usize,
    pub successful_migrations: usize,
    pub failed_migrations: usize,
    pub total_bytes_migrated: u64,
    pub total_duration_ms: u64,
    pub by_tier: HashMap<StorageTier, usize>,
}

pub struct DataMigrator {
    migrations: tokio::sync::Mutex<Vec<MigrationResult>>,
}

impl DataMigrator {
    pub fn new() -> Self {
        Self {
            migrations: tokio::sync::Mutex::new(Vec::new()),
        }
    }

    pub async fn migrate(
        &self,
        metadata: &mut DataMetadata,
        target_tier: StorageTier,
    ) -> Result<MigrationResult, StreamSQLError> {
        let start_time = std::time::Instant::now();
        let from_tier = metadata.current_tier.clone();

        let result = self.perform_migration(metadata, &target_tier).await;

        let duration = start_time.elapsed().as_millis() as u64;

        let migration_result = match result {
            Ok(bytes) => MigrationResult {
                data_id: metadata.data_id.clone(),
                from_tier: from_tier.clone(),
                to_tier: target_tier.clone(),
                success: true,
                bytes_migrated: bytes,
                duration_ms: duration,
                error_message: None,
            },
            Err(e) => MigrationResult {
                data_id: metadata.data_id.clone(),
                from_tier: from_tier.clone(),
                to_tier: target_tier.clone(),
                success: false,
                bytes_migrated: 0,
                duration_ms: duration,
                error_message: Some(e.to_string()),
            },
        };

        let mut migrations = self.migrations.lock().await;
        migrations.push(migration_result.clone());

        if migration_result.success {
            Ok(migration_result)
        } else {
            Err(StreamSQLError::Lifecycle(
                migration_result.error_message.unwrap_or_default(),
            ))
        }
    }

    async fn perform_migration(
        &self,
        metadata: &DataMetadata,
        target_tier: &StorageTier,
    ) -> Result<u64, StreamSQLError> {
        tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;

        let tier_order = [
            StorageTier::Hot,
            StorageTier::Warm,
            StorageTier::Cold,
            StorageTier::Archived,
        ];

        let from_idx = tier_order
            .iter()
            .position(|t| t == &metadata.current_tier)
            .ok_or_else(|| {
                StreamSQLError::Lifecycle(format!(
                    "Invalid source tier: {:?}",
                    metadata.current_tier
                ))
            })?;

        let to_idx = tier_order
            .iter()
            .position(|t| t == target_tier)
            .ok_or_else(|| {
                StreamSQLError::Lifecycle(format!("Invalid target tier: {:?}", target_tier))
            })?;

        if from_idx == to_idx {
            return Ok(0);
        }

        Ok(metadata.size_bytes)
    }

    pub async fn get_stats(&self) -> MigrationStats {
        let migrations = self.migrations.lock().await;

        let mut stats = MigrationStats {
            total_migrations: migrations.len(),
            successful_migrations: 0,
            failed_migrations: 0,
            total_bytes_migrated: 0,
            total_duration_ms: 0,
            by_tier: HashMap::new(),
        };

        for m in &*migrations {
            if m.success {
                stats.successful_migrations += 1;
                stats.total_bytes_migrated += m.bytes_migrated;
            } else {
                stats.failed_migrations += 1;
            }
            stats.total_duration_ms += m.duration_ms;

            *stats.by_tier.entry(m.to_tier.clone()).or_insert(0) += 1;
        }

        stats
    }

    pub async fn clear_history(&self) {
        let mut migrations = self.migrations.lock().await;
        migrations.clear();
    }
}

impl Default for DataMigrator {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_migrator_creation() {
        let migrator = DataMigrator::new();
        let stats = migrator.get_stats().await;
        assert_eq!(stats.total_migrations, 0);
    }

    #[tokio::test]
    async fn test_successful_migration() {
        let migrator = DataMigrator::new();
        let mut metadata = DataMetadata::new("d1", "orders", 1024);

        let result = migrator.migrate(&mut metadata, StorageTier::Warm).await;

        assert!(result.is_ok());
        let result = result.unwrap();
        assert!(result.success);
        assert_eq!(result.from_tier, StorageTier::Hot);
        assert_eq!(result.to_tier, StorageTier::Warm);
        assert_eq!(result.bytes_migrated, 1024);

        let stats = migrator.get_stats().await;
        assert_eq!(stats.successful_migrations, 1);
    }

    #[tokio::test]
    async fn test_same_tier_migration() {
        let migrator = DataMigrator::new();
        let mut metadata = DataMetadata::new("d1", "orders", 1024);

        let result = migrator.migrate(&mut metadata, StorageTier::Hot).await;

        assert!(result.is_ok());
        let result = result.unwrap();
        assert_eq!(result.bytes_migrated, 0);
    }
}
