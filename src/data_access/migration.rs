use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use sha2::{Sha256, Digest};
use hex::ToHex;
use chrono::Utc;
use serde::{Deserialize, Serialize};
use crate::data_access::schema::{
    SchemaVersion, MigrationDefinition, MigrationResult, SchemaStatus,
};
use crate::utils::error::{Result, PlatformError};
use tracing::{info, warn, error};

#[derive(Debug, Clone, Default)]
struct MigrationState {
    migrations: HashMap<u64, MigrationDefinition>,
    applied: Vec<SchemaVersion>,
    current_version: u64,
}

#[derive(Debug, Clone, Default)]
pub struct MigrationManager {
    state: Arc<RwLock<MigrationState>>,
}

impl MigrationManager {
    pub fn new() -> Self {
        Self {
            state: Arc::new(RwLock::new(MigrationState::default())),
        }
    }

    pub fn with_migrations(migrations: Vec<MigrationDefinition>) -> Self {
        let manager = Self::new();
        for migration in migrations {
            let state = manager.state.clone();
            let m = migration.clone();
            tokio::spawn(async move {
                let mut s = state.write().await;
                s.migrations.insert(migration.version, m);
            });
        }
        manager
    }

    pub async fn register_migration(&self, migration: MigrationDefinition) -> Result<()> {
        info!(version = %migration.version, name = %migration.name, "registering_migration");
        
        let mut state = self.state.write().await;
        
        if state.migrations.contains_key(&migration.version) {
            return Err(PlatformError::Conflict(format!(
                "migration version {} already exists", migration.version
            )));
        }
        
        state.migrations.insert(migration.version, migration);
        info!("migration_registered");
        Ok(())
    }

    pub async fn get_current_version(&self) -> u64 {
        let state = self.state.read().await;
        state.current_version
    }

    pub async fn get_applied_migrations(&self) -> Vec<SchemaVersion> {
        let state = self.state.read().await;
        state.applied.clone()
    }

    pub async fn get_pending_migrations(&self) -> Vec<MigrationDefinition> {
        let state = self.state.read().await;
        let current = state.current_version;
        
        let mut pending: Vec<MigrationDefinition> = state.migrations
            .values()
            .filter(|m| m.version > current)
            .cloned()
            .collect();
        
        pending.sort_by(|a, b| a.version.cmp(&b.version));
        pending
    }

    pub async fn migrate(&self) -> Result<Vec<MigrationResult>> {
        info!("starting_migration");

        let pending = self.get_pending_migrations().await;
        if pending.is_empty() {
            info!("no_pending_migrations");
            return Ok(vec![]);
        }

        let mut results = Vec::new();
        let mut latest_version = self.get_current_version().await;

        for migration in pending {
            info!(version = %migration.version, "applying_migration");
            
            let checksum = self.compute_checksum(&migration.up_sql);
            let mut result = MigrationResult {
                version: migration.version,
                success: false,
                executed_at: Utc::now(),
                error: None,
            };

            let execute_result = self.execute_up(&migration).await;
            
            match execute_result {
                Ok(_) => {
                    result.success = true;
                    latest_version = migration.version;
                    
                    let schema_version = SchemaVersion {
                        version: migration.version,
                        name: migration.name.clone(),
                        description: migration.description.clone(),
                        applied_at: Utc::now(),
                        checksum,
                        status: SchemaStatus::Applied,
                    };

                    {
                        let mut state = self.state.write().await;
                        state.applied.push(schema_version);
                        state.current_version = migration.version;
                    }
                    
                    info!(version = %migration.version, "migration_applied");
                }
                Err(e) => {
                    result.error = Some(e.to_string());
                    error!(version = %migration.version, error = %e, "migration_failed");
                    results.push(result);
                    break;
                }
            }

            results.push(result);
        }

        info!(
            applied_count = results.iter().filter(|r| r.success).count(),
            failed_count = results.iter().filter(|r| !r.success).count(),
            "migration_complete"
        );

        Ok(results)
    }

    pub async fn rollback(&self, target_version: u64) -> Result<Vec<MigrationResult>> {
        info!(target_version = %target_version, "starting_rollback");

        let current = self.get_current_version().await;
        if target_version >= current {
            return Err(PlatformError::Validation(format!(
                "target version {} must be less than current version {}",
                target_version, current
            )));
        }

        let state = self.state.read().await;
        let mut to_rollback: Vec<&MigrationDefinition> = state.migrations
            .values()
            .filter(|m| m.version > target_version && m.version <= current)
            .collect();
        
        to_rollback.sort_by(|a, b| b.version.cmp(&a.version));
        drop(state);

        let mut results = Vec::new();
        let mut new_current = current;

        for migration in to_rollback {
            info!(version = %migration.version, "rolling_back_migration");
            
            let mut result = MigrationResult {
                version: migration.version,
                success: false,
                executed_at: Utc::now(),
                error: None,
            };

            let execute_result = self.execute_down(migration).await;
            
            match execute_result {
                Ok(_) => {
                    result.success = true;
                    new_current = migration.version - 1;
                    
                    {
                        let mut state = self.state.write().await;
                        if let Some(schema) = state.applied.iter_mut().find(|s| s.version == migration.version) {
                            schema.status = SchemaStatus::RolledBack;
                        }
                        state.current_version = new_current;
                    }
                    
                    info!(version = %migration.version, "migration_rolled_back");
                }
                Err(e) => {
                    result.error = Some(e.to_string());
                    error!(version = %migration.version, error = %e, "rollback_failed");
                    results.push(result);
                    break;
                }
            }

            results.push(result);
        }

        info!(
            rolled_back_count = results.iter().filter(|r| r.success).count(),
            new_version = %new_current,
            "rollback_complete"
        );

        Ok(results)
    }

    async fn execute_up(&self, migration: &MigrationDefinition) -> Result<()> {
        info!(version = %migration.version, "executing_up_migration");
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
        info!(version = %migration.version, "up_migration_executed");
        Ok(())
    }

    async fn execute_down(&self, migration: &MigrationDefinition) -> Result<()> {
        info!(version = %migration.version, "executing_down_migration");
        tokio::time::sleep(tokio::time::Duration::from_millis(50)).await;
        info!(version = %migration.version, "down_migration_executed");
        Ok(())
    }

    fn compute_checksum(&self, sql: &str) -> String {
        let mut hasher = Sha256::new();
        hasher.update(sql.as_bytes());
        let result = hasher.finalize();
        result.encode_hex::<String>()
    }

    pub async fn list_migrations(&self) -> Vec<MigrationDefinition> {
        let state = self.state.read().await;
        let mut migrations: Vec<MigrationDefinition> = state.migrations
            .values()
            .cloned()
            .collect();
        migrations.sort_by(|a, b| a.version.cmp(&b.version));
        migrations
    }
}
