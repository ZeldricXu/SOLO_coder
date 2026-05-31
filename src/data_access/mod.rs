use crate::error::PlatformError;
use crate::types::SchemaMigration;
use crate::utils::{current_timestamp, hash_string};
use parking_lot::RwLock;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::Semaphore;
use tracing::{info, warn, error};

#[derive(Debug, Clone)]
struct MigrationState {
    applied_versions: Vec<u64>,
    migrations: HashMap<u64, SchemaMigration>,
    current_version: u64,
    is_locked: bool,
    lock_holder: Option<String>,
}

pub struct MigrationManager {
    state: Arc<RwLock<MigrationState>>,
    concurrency_semaphore: Arc<Semaphore>,
}

impl MigrationManager {
    pub fn new() -> Self {
        MigrationManager {
            state: Arc::new(RwLock::new(MigrationState {
                applied_versions: Vec::new(),
                migrations: HashMap::new(),
                current_version: 0,
                is_locked: false,
                lock_holder: None,
            })),
            concurrency_semaphore: Arc::new(Semaphore::new(1)),
        }
    }

    pub async fn initialize(&self) -> Result<(), PlatformError> {
        info!("Initializing migration manager");
        
        let boot_migration = SchemaMigration {
            version: 1,
            name: "initial_schema".to_string(),
            applied_at: None,
            up_sql: "CREATE TABLE IF NOT EXISTS schema_migrations (version BIGINT PRIMARY KEY, name VARCHAR(255), applied_at TIMESTAMP)".to_string(),
            down_sql: "DROP TABLE IF EXISTS schema_migrations".to_string(),
        };
        
        self.register_migration(boot_migration).await?;
        self.apply_up_to(u64::MAX).await?;
        
        Ok(())
    }

    pub async fn register_migration(&self, migration: SchemaMigration) -> Result<(), PlatformError> {
        if migration.version == 0 {
            return Err(PlatformError::Validation("Migration version must be greater than 0".to_string()));
        }
        
        if migration.name.is_empty() {
            return Err(PlatformError::Validation("Migration name cannot be empty".to_string()));
        }
        
        let mut state = self.state.write();
        
        if state.migrations.contains_key(&migration.version) {
            return Err(PlatformError::Conflict(format!(
                "Migration with version {} already registered",
                migration.version
            )));
        }
        
        info!(version = migration.version, name = %migration.name, "Registering migration");
        state.migrations.insert(migration.version, migration);
        
        Ok(())
    }

    pub async fn apply_up_to(&self, target_version: u64) -> Result<Vec<u64>, PlatformError> {
        let _permit = self.concurrency_semaphore.acquire().await
            .map_err(|e| PlatformError::Internal(format!("Failed to acquire migration lock: {}", e)))?;
        
        let mut applied = Vec::new();
        
        loop {
            let current_version = {
                let state = self.state.read();
                state.current_version
            };
            
            if current_version >= target_version {
                break;
            }
            
            let next_version = {
                let state = self.state.read();
                let mut versions: Vec<u64> = state.migrations.keys().copied().collect();
                versions.sort();
                
                versions.into_iter()
                    .find(|&v| v > current_version && v <= target_version)
            };
            
            match next_version {
                Some(version) => {
                    self.apply_single_migration(version).await?;
                    applied.push(version);
                }
                None => break,
            }
        }
        
        if !applied.is_empty() {
            info!(applied_count = applied.len(), "Migrations applied successfully");
        }
        
        Ok(applied)
    }

    pub async fn apply_single_migration(&self, version: u64) -> Result<(), PlatformError> {
        let migration = {
            let state = self.state.read();
            state.migrations.get(&version).cloned()
        };
        
        match migration {
            Some(migration) => {
                info!(version = version, name = %migration.name, "Applying migration");
                
                self.execute_sql(&migration.up_sql).await?;
                
                let mut state = self.state.write();
                state.applied_versions.push(version);
                state.applied_versions.sort();
                state.current_version = version;
                
                if let Some(m) = state.migrations.get_mut(&version) {
                    m.applied_at = Some(current_timestamp());
                }
                
                Ok(())
            }
            None => Err(PlatformError::NotFound(format!(
                "Migration with version {} not found",
                version
            ))),
        }
    }

    pub async fn rollback_to(&self, target_version: u64) -> Result<Vec<u64>, PlatformError> {
        let _permit = self.concurrency_semaphore.acquire().await
            .map_err(|e| PlatformError::Internal(format!("Failed to acquire migration lock: {}", e)))?;
        
        let mut rolled_back = Vec::new();
        
        loop {
            let current_version = {
                let state = self.state.read();
                state.current_version
            };
            
            if current_version <= target_version {
                break;
            }
            
            let prev_version = {
                let state = self.state.read();
                let mut versions: Vec<u64> = state.applied_versions.clone();
                versions.sort();
                versions.reverse();
                
                versions.into_iter()
                    .find(|&v| v > target_version)
            };
            
            match prev_version {
                Some(version) => {
                    self.rollback_single_migration(version).await?;
                    rolled_back.push(version);
                }
                None => break,
            }
        }
        
        if !rolled_back.is_empty() {
            info!(rolled_back_count = rolled_back.len(), "Migrations rolled back successfully");
        }
        
        Ok(rolled_back)
    }

    pub async fn rollback_single_migration(&self, version: u64) -> Result<(), PlatformError> {
        let migration = {
            let state = self.state.read();
            state.migrations.get(&version).cloned()
        };
        
        match migration {
            Some(migration) => {
                warn!(version = version, name = %migration.name, "Rolling back migration");
                
                self.execute_sql(&migration.down_sql).await?;
                
                let mut state = self.state.write();
                state.applied_versions.retain(|&v| v != version);
                
                state.current_version = state.applied_versions
                    .iter()
                    .copied()
                    .max()
                    .unwrap_or(0);
                
                if let Some(m) = state.migrations.get_mut(&version) {
                    m.applied_at = None;
                }
                
                Ok(())
            }
            None => Err(PlatformError::NotFound(format!(
                "Migration with version {} not found",
                version
            ))),
        }
    }

    async fn execute_sql(&self, sql: &str) -> Result<(), PlatformError> {
        info!(sql = %sql, "Executing SQL migration");
        Ok(())
    }

    pub fn get_current_version(&self) -> u64 {
        let state = self.state.read();
        state.current_version
    }

    pub fn get_applied_versions(&self) -> Vec<u64> {
        let state = self.state.read();
        state.applied_versions.clone()
    }

    pub fn get_pending_migrations(&self) -> Vec<SchemaMigration> {
        let state = self.state.read();
        state.migrations
            .values()
            .filter(|m| !state.applied_versions.contains(&m.version))
            .cloned()
            .collect()
    }

    pub fn get_all_migrations(&self) -> Vec<SchemaMigration> {
        let state = self.state.read();
        let mut migrations: Vec<SchemaMigration> = state.migrations.values().cloned().collect();
        migrations.sort_by_key(|m| m.version);
        migrations
    }

    pub fn is_migration_applied(&self, version: u64) -> bool {
        let state = self.state.read();
        state.applied_versions.contains(&version)
    }

    pub fn validate_schema_integrity(&self) -> Result<(), PlatformError> {
        let state = self.state.read();
        
        let mut sorted_applied = state.applied_versions.clone();
        sorted_applied.sort();
        
        for (i, &version) in sorted_applied.iter().enumerate() {
            if i > 0 && version <= sorted_applied[i - 1] {
                return Err(PlatformError::Migration(
                    "Applied migrations are not in sequential order".to_string()
                ));
            }
        }
        
        for &version in &sorted_applied {
            if !state.migrations.contains_key(&version) {
                return Err(PlatformError::Migration(format!(
                    "Applied migration version {} has no corresponding definition",
                    version
                )));
            }
        }
        
        let expected_current = sorted_applied.last().copied().unwrap_or(0);
        if state.current_version != expected_current {
            return Err(PlatformError::Migration(format!(
                "Current version mismatch: expected {}, found {}",
                expected_current, state.current_version
            )));
        }
        
        info!("Schema integrity validated successfully");
        Ok(())
    }

    pub async fn lock_migrations(&self, holder: &str) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        
        if state.is_locked {
            return Err(PlatformError::Conflict(format!(
                "Migrations are already locked by: {:?}",
                state.lock_holder
            )));
        }
        
        state.is_locked = true;
        state.lock_holder = Some(holder.to_string());
        
        info!(holder = %holder, "Migrations locked");
        Ok(())
    }

    pub async fn unlock_migrations(&self) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        
        if !state.is_locked {
            return Err(PlatformError::Internal("Migrations are not locked".to_string()));
        }
        
        state.is_locked = false;
        state.lock_holder = None;
        
        info!("Migrations unlocked");
        Ok(())
    }
}

pub struct DataRepository<T> {
    data: Arc<RwLock<HashMap<String, T>>>,
    version: Arc<RwLock<u64>>,
}

impl<T: Clone> DataRepository<T> {
    pub fn new() -> Self {
        DataRepository {
            data: Arc::new(RwLock::new(HashMap::new())),
            version: Arc::new(RwLock::new(0)),
        }
    }

    pub fn insert(&self, key: &str, value: T) -> Result<(), PlatformError> {
        let mut data = self.data.write();
        let mut version = self.version.write();
        
        if data.contains_key(key) {
            return Err(PlatformError::Conflict(format!("Key {} already exists", key)));
        }
        
        data.insert(key.to_string(), value);
        *version += 1;
        
        Ok(())
    }

    pub fn get(&self, key: &str) -> Option<T> {
        let data = self.data.read();
        data.get(key).cloned()
    }

    pub fn update(&self, key: &str, value: T) -> Result<(), PlatformError> {
        let mut data = self.data.write();
        let mut version = self.version.write();
        
        if !data.contains_key(key) {
            return Err(PlatformError::NotFound(format!("Key {} not found", key)));
        }
        
        data.insert(key.to_string(), value);
        *version += 1;
        
        Ok(())
    }

    pub fn delete(&self, key: &str) -> Result<(), PlatformError> {
        let mut data = self.data.write();
        let mut version = self.version.write();
        
        if data.remove(key).is_none() {
            return Err(PlatformError::NotFound(format!("Key {} not found", key)));
        }
        
        *version += 1;
        
        Ok(())
    }

    pub fn get_version(&self) -> u64 {
        *self.version.read()
    }

    pub fn list_keys(&self) -> Vec<String> {
        let data = self.data.read();
        data.keys().cloned().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_migration_lifecycle() {
        let manager = MigrationManager::new();
        manager.initialize().await.unwrap();
        
        assert_eq!(manager.get_current_version(), 1);
        
        let migration = SchemaMigration {
            version: 2,
            name: "test_migration".to_string(),
            applied_at: None,
            up_sql: "CREATE TABLE test (id INT)".to_string(),
            down_sql: "DROP TABLE test".to_string(),
        };
        
        manager.register_migration(migration).await.unwrap();
        manager.apply_up_to(2).await.unwrap();
        
        assert_eq!(manager.get_current_version(), 2);
        assert!(manager.is_migration_applied(2));
    }

    #[tokio::test]
    async fn test_migration_rollback() {
        let manager = MigrationManager::new();
        manager.initialize().await.unwrap();
        
        let migration = SchemaMigration {
            version: 2,
            name: "test_migration".to_string(),
            applied_at: None,
            up_sql: "CREATE TABLE test (id INT)".to_string(),
            down_sql: "DROP TABLE test".to_string(),
        };
        
        manager.register_migration(migration).await.unwrap();
        manager.apply_up_to(2).await.unwrap();
        
        manager.rollback_to(1).await.unwrap();
        
        assert_eq!(manager.get_current_version(), 1);
        assert!(!manager.is_migration_applied(2));
    }
}
