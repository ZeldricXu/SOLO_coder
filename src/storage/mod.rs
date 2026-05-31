use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;
use std::sync::{Arc, Mutex};
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum StorageType {
    FileSystem,
    S3,
    GCS,
    AzureBlob,
    LocalDisk,
    NetworkStorage,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum BackupStatus {
    Pending,
    InProgress,
    Completed,
    Failed,
    Partial,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum RestoreStatus {
    Pending,
    InProgress,
    Completed,
    Failed,
    Validating,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageConfig {
    pub id: String,
    pub name: String,
    pub storage_type: StorageType,
    pub base_path: String,
    pub credentials: HashMap<String, String>,
    pub max_backups: u32,
    pub retention_days: u32,
    pub compression_enabled: bool,
    pub encryption_enabled: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BackupItem {
    pub id: String,
    pub config_id: String,
    pub name: String,
    pub source_path: String,
    pub target_path: String,
    pub size_bytes: u64,
    pub checksum: String,
    pub created_at: DateTime<Utc>,
    pub expires_at: Option<DateTime<Utc>>,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BackupOperation {
    pub id: String,
    pub config_id: String,
    pub items: Vec<BackupItem>,
    pub status: BackupStatus,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub total_size_bytes: u64,
    pub error_message: Option<String>,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RestoreOperation {
    pub id: String,
    pub backup_id: String,
    pub config_id: String,
    pub items: Vec<String>,
    pub target_path: String,
    pub status: RestoreStatus,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub error_message: Option<String>,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageStats {
    pub total_backups: u32,
    pub total_size_bytes: u64,
    pub successful_backups: u32,
    pub failed_backups: u32,
    pub last_backup_time: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone)]
pub struct StorageManager {
    configs: Arc<Mutex<HashMap<String, StorageConfig>>>,
    backups: Arc<Mutex<HashMap<String, BackupOperation>>>,
    restores: Arc<Mutex<HashMap<String, RestoreOperation>>>,
}

impl StorageManager {
    pub fn new() -> Self {
        Self {
            configs: Arc::new(Mutex::new(HashMap::new())),
            backups: Arc::new(Mutex::new(HashMap::new())),
            restores: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn create_config(
        &self,
        name: &str,
        storage_type: StorageType,
        base_path: &str,
        max_backups: u32,
        retention_days: u32,
    ) -> StorageConfig {
        let id = Uuid::new_v4().to_string();
        let now = Utc::now();

        let config = StorageConfig {
            id: id.clone(),
            name: name.to_string(),
            storage_type,
            base_path: base_path.to_string(),
            credentials: HashMap::new(),
            max_backups,
            retention_days,
            compression_enabled: true,
            encryption_enabled: false,
            created_at: now,
            updated_at: now,
        };

        let mut configs = self.configs.lock().unwrap();
        configs.insert(id, config.clone());
        config
    }

    pub fn set_credentials(&self, config_id: &str, credentials: HashMap<String, String>) -> Option<StorageConfig> {
        let mut configs = self.configs.lock().unwrap();
        let config = configs.get_mut(config_id)?;
        config.credentials = credentials;
        config.updated_at = Utc::now();
        Some(config.clone())
    }

    pub fn get_config(&self, config_id: &str) -> Option<StorageConfig> {
        let configs = self.configs.lock().unwrap();
        configs.get(config_id).cloned()
    }

    pub fn list_configs(&self) -> Vec<StorageConfig> {
        let configs = self.configs.lock().unwrap();
        configs.values().cloned().collect()
    }

    pub fn delete_config(&self, config_id: &str) -> bool {
        let mut configs = self.configs.lock().unwrap();
        configs.remove(config_id).is_some()
    }

    pub fn create_backup(
        &self,
        config_id: &str,
        name: &str,
        items: Vec<(String, String)>,
        created_by: &str,
    ) -> Result<BackupOperation, String> {
        let configs = self.configs.lock().unwrap();
        configs.get(config_id)
            .ok_or_else(|| "Storage config not found".to_string())?;

        let id = Uuid::new_v4().to_string();
        let now = Utc::now();

        let backup_items: Vec<BackupItem> = items.into_iter().map(|(source, target)| BackupItem {
            id: Uuid::new_v4().to_string(),
            config_id: config_id.to_string(),
            name: name.to_string(),
            source_path: source,
            target_path: target,
            size_bytes: 0,
            checksum: String::new(),
            created_at: now,
            expires_at: None,
            labels: HashMap::new(),
        }).collect();

        let backup = BackupOperation {
            id: id.clone(),
            config_id: config_id.to_string(),
            items: backup_items,
            status: BackupStatus::Pending,
            start_time: None,
            end_time: None,
            total_size_bytes: 0,
            error_message: None,
            created_by: created_by.to_string(),
            created_at: now,
        };

        let mut backups = self.backups.lock().unwrap();
        backups.insert(id, backup.clone());
        Ok(backup)
    }

    pub fn start_backup(&self, backup_id: &str) -> Result<BackupOperation, String> {
        let mut backups = self.backups.lock().unwrap();
        let backup = backups.get_mut(backup_id)
            .ok_or_else(|| "Backup not found".to_string())?;

        if backup.status != BackupStatus::Pending {
            return Err("Backup already started".to_string());
        }

        backup.status = BackupStatus::InProgress;
        backup.start_time = Some(Utc::now());
        Ok(backup.clone())
    }

    pub fn complete_backup(&self, backup_id: &str, total_size: u64, checksums: Vec<(String, String)>) -> Result<BackupOperation, String> {
        let mut backups = self.backups.lock().unwrap();
        let backup = backups.get_mut(backup_id)
            .ok_or_else(|| "Backup not found".to_string())?;

        if backup.status != BackupStatus::InProgress {
            return Err("Backup not in progress".to_string());
        }

        backup.status = BackupStatus::Completed;
        backup.end_time = Some(Utc::now());
        backup.total_size_bytes = total_size;

        for (item_id, checksum) in checksums {
            if let Some(item) = backup.items.iter_mut().find(|i| i.id == item_id) {
                item.checksum = checksum;
            }
        }

        Ok(backup.clone())
    }

    pub fn fail_backup(&self, backup_id: &str, error: &str) -> Result<BackupOperation, String> {
        let mut backups = self.backups.lock().unwrap();
        let backup = backups.get_mut(backup_id)
            .ok_or_else(|| "Backup not found".to_string())?;

        backup.status = BackupStatus::Failed;
        backup.end_time = Some(Utc::now());
        backup.error_message = Some(error.to_string());
        Ok(backup.clone())
    }

    pub fn get_backup(&self, backup_id: &str) -> Option<BackupOperation> {
        let backups = self.backups.lock().unwrap();
        backups.get(backup_id).cloned()
    }

    pub fn list_backups(&self, config_id: Option<&str>) -> Vec<BackupOperation> {
        let backups = self.backups.lock().unwrap();
        backups.values()
            .filter(|b| config_id.map_or(true, |cid| b.config_id == cid))
            .cloned()
            .collect()
    }

    pub fn delete_backup(&self, backup_id: &str) -> bool {
        let mut backups = self.backups.lock().unwrap();
        backups.remove(backup_id).is_some()
    }

    pub fn create_restore(
        &self,
        backup_id: &str,
        config_id: &str,
        items: Vec<String>,
        target_path: &str,
        created_by: &str,
    ) -> Result<RestoreOperation, String> {
        let backups = self.backups.lock().unwrap();
        backups.get(backup_id)
            .ok_or_else(|| "Backup not found".to_string())?;

        let id = Uuid::new_v4().to_string();
        let now = Utc::now();

        let restore = RestoreOperation {
            id: id.clone(),
            backup_id: backup_id.to_string(),
            config_id: config_id.to_string(),
            items,
            target_path: target_path.to_string(),
            status: RestoreStatus::Pending,
            start_time: None,
            end_time: None,
            error_message: None,
            created_by: created_by.to_string(),
            created_at: now,
        };

        let mut restores = self.restores.lock().unwrap();
        restores.insert(id, restore.clone());
        Ok(restore)
    }

    pub fn start_restore(&self, restore_id: &str) -> Result<RestoreOperation, String> {
        let mut restores = self.restores.lock().unwrap();
        let restore = restores.get_mut(restore_id)
            .ok_or_else(|| "Restore not found".to_string())?;

        if restore.status != RestoreStatus::Pending {
            return Err("Restore already started".to_string());
        }

        restore.status = RestoreStatus::InProgress;
        restore.start_time = Some(Utc::now());
        Ok(restore.clone())
    }

    pub fn complete_restore(&self, restore_id: &str) -> Result<RestoreOperation, String> {
        let mut restores = self.restores.lock().unwrap();
        let restore = restores.get_mut(restore_id)
            .ok_or_else(|| "Restore not found".to_string())?;

        if restore.status != RestoreStatus::InProgress {
            return Err("Restore not in progress".to_string());
        }

        restore.status = RestoreStatus::Completed;
        restore.end_time = Some(Utc::now());
        Ok(restore.clone())
    }

    pub fn fail_restore(&self, restore_id: &str, error: &str) -> Result<RestoreOperation, String> {
        let mut restores = self.restores.lock().unwrap();
        let restore = restores.get_mut(restore_id)
            .ok_or_else(|| "Restore not found".to_string())?;

        restore.status = RestoreStatus::Failed;
        restore.end_time = Some(Utc::now());
        restore.error_message = Some(error.to_string());
        Ok(restore.clone())
    }

    pub fn get_restore(&self, restore_id: &str) -> Option<RestoreOperation> {
        let restores = self.restores.lock().unwrap();
        restores.get(restore_id).cloned()
    }

    pub fn list_restores(&self, backup_id: Option<&str>) -> Vec<RestoreOperation> {
        let restores = self.restores.lock().unwrap();
        restores.values()
            .filter(|r| backup_id.map_or(true, |bid| r.backup_id == bid))
            .cloned()
            .collect()
    }

    pub fn get_stats(&self, config_id: Option<&str>) -> StorageStats {
        let backups = self.backups.lock().unwrap();
        let filtered: Vec<&BackupOperation> = backups.values()
            .filter(|b| config_id.map_or(true, |cid| b.config_id == cid))
            .collect();

        let total_backups = filtered.len() as u32;
        let total_size_bytes: u64 = filtered.iter().map(|b| b.total_size_bytes).sum();
        let successful_backups = filtered.iter().filter(|b| b.status == BackupStatus::Completed).count() as u32;
        let failed_backups = filtered.iter().filter(|b| b.status == BackupStatus::Failed).count() as u32;
        let last_backup_time = filtered.iter()
            .filter(|b| b.status == BackupStatus::Completed)
            .filter_map(|b| b.end_time)
            .max();

        StorageStats {
            total_backups,
            total_size_bytes,
            successful_backups,
            failed_backups,
            last_backup_time,
        }
    }

    pub fn cleanup_expired_backups(&self) -> Vec<String> {
        let mut backups = self.backups.lock().unwrap();
        let configs = self.configs.lock().unwrap();
        let now = Utc::now();
        let mut removed = Vec::new();

        let expired_ids: Vec<String> = backups.values()
            .filter(|b| {
                if let Some(config) = configs.get(&b.config_id) {
                    if let Some(end_time) = b.end_time {
                        let age = now - end_time;
                        age.num_days() > config.retention_days as i64
                    } else {
                        false
                    }
                } else {
                    false
                }
            })
            .map(|b| b.id.clone())
            .collect();

        for id in expired_ids {
            backups.remove(&id);
            removed.push(id);
        }

        removed
    }

    pub fn validate_backup(&self, backup_id: &str) -> Result<bool, String> {
        let backups = self.backups.lock().unwrap();
        let backup = backups.get(backup_id)
            .ok_or_else(|| "Backup not found".to_string())?;

        if backup.status != BackupStatus::Completed {
            return Ok(false);
        }

        for item in &backup.items {
            if item.checksum.is_empty() {
                return Ok(false);
            }
        }

        Ok(true)
    }
}

impl Default for StorageManager {
    fn default() -> Self {
        Self::new()
    }
}

pub fn format_size(bytes: u64) -> String {
    const KB: u64 = 1024;
    const MB: u64 = KB * 1024;
    const GB: u64 = MB * 1024;
    const TB: u64 = GB * 1024;

    if bytes >= TB {
        format!("{:.2} TB", bytes as f64 / TB as f64)
    } else if bytes >= GB {
        format!("{:.2} GB", bytes as f64 / GB as f64)
    } else if bytes >= MB {
        format!("{:.2} MB", bytes as f64 / MB as f64)
    } else if bytes >= KB {
        format!("{:.2} KB", bytes as f64 / KB as f64)
    } else {
        format!("{} B", bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_config() {
        let manager = StorageManager::new();
        let config = manager.create_config(
            "main-storage",
            StorageType::S3,
            "s3://bucket/backups",
            100,
            30,
        );

        assert_eq!(config.name, "main-storage");
        assert_eq!(config.storage_type, StorageType::S3);
        assert_eq!(config.max_backups, 100);
        assert_eq!(config.retention_days, 30);
    }

    #[test]
    fn test_create_backup() {
        let manager = StorageManager::new();
        let config = manager.create_config(
            "test-storage",
            StorageType::LocalDisk,
            "/backups",
            10,
            7,
        );

        let items = vec![
            ("/data/file1.txt".to_string(), "file1.txt".to_string()),
            ("/data/file2.txt".to_string(), "file2.txt".to_string()),
        ];

        let backup = manager.create_backup(&config.id, "daily-backup", items, "admin");
        assert!(backup.is_ok());

        let backup = backup.unwrap();
        assert_eq!(backup.items.len(), 2);
        assert_eq!(backup.status, BackupStatus::Pending);
    }

    #[test]
    fn test_start_and_complete_backup() {
        let manager = StorageManager::new();
        let config = manager.create_config(
            "test-storage",
            StorageType::LocalDisk,
            "/backups",
            10,
            7,
        );

        let items = vec![("/data/file.txt".to_string(), "file.txt".to_string())];
        let backup = manager.create_backup(&config.id, "test", items, "admin").unwrap();

        let started = manager.start_backup(&backup.id);
        assert!(started.is_ok());
        assert_eq!(started.unwrap().status, BackupStatus::InProgress);

        let checksums = vec![(backup.items[0].id.clone(), "abc123".to_string())];
        let completed = manager.complete_backup(&backup.id, 1024, checksums);
        assert!(completed.is_ok());
        
        let completed = completed.unwrap();
        assert_eq!(completed.status, BackupStatus::Completed);
        assert_eq!(completed.total_size_bytes, 1024);
    }

    #[test]
    fn test_fail_backup() {
        let manager = StorageManager::new();
        let config = manager.create_config(
            "test-storage",
            StorageType::LocalDisk,
            "/backups",
            10,
            7,
        );

        let items = vec![("/data/file.txt".to_string(), "file.txt".to_string())];
        let backup = manager.create_backup(&config.id, "test", items, "admin").unwrap();

        manager.start_backup(&backup.id).unwrap();
        let failed = manager.fail_backup(&backup.id, "Network error");
        
        assert!(failed.is_ok());
        let failed = failed.unwrap();
        assert_eq!(failed.status, BackupStatus::Failed);
        assert_eq!(failed.error_message, Some("Network error".to_string()));
    }

    #[test]
    fn test_restore_operation() {
        let manager = StorageManager::new();
        let config = manager.create_config(
            "test-storage",
            StorageType::LocalDisk,
            "/backups",
            10,
            7,
        );

        let items = vec![("/data/file.txt".to_string(), "file.txt".to_string())];
        let backup = manager.create_backup(&config.id, "test", items, "admin").unwrap();
        manager.start_backup(&backup.id).unwrap();
        manager.complete_backup(&backup.id, 1024, vec![]).unwrap();

        let restore_items = vec!["file.txt".to_string()];
        let restore = manager.create_restore(
            &backup.id,
            &config.id,
            restore_items,
            "/restore",
            "admin",
        );

        assert!(restore.is_ok());
        let restore = restore.unwrap();
        assert_eq!(restore.status, RestoreStatus::Pending);

        let started = manager.start_restore(&restore.id);
        assert!(started.is_ok());

        let completed = manager.complete_restore(&restore.id);
        assert!(completed.is_ok());
        assert_eq!(completed.unwrap().status, RestoreStatus::Completed);
    }

    #[test]
    fn test_get_stats() {
        let manager = StorageManager::new();
        let config = manager.create_config(
            "test-storage",
            StorageType::LocalDisk,
            "/backups",
            10,
            7,
        );

        let items = vec![("/data/file.txt".to_string(), "file.txt".to_string())];
        
        let backup1 = manager.create_backup(&config.id, "backup1", items.clone(), "admin").unwrap();
        manager.start_backup(&backup1.id).unwrap();
        manager.complete_backup(&backup1.id, 1024, vec![]).unwrap();

        let backup2 = manager.create_backup(&config.id, "backup2", items.clone(), "admin").unwrap();
        manager.start_backup(&backup2.id).unwrap();
        manager.fail_backup(&backup2.id, "error").unwrap();

        let stats = manager.get_stats(Some(&config.id));
        assert_eq!(stats.total_backups, 2);
        assert_eq!(stats.successful_backups, 1);
        assert_eq!(stats.failed_backups, 1);
        assert_eq!(stats.total_size_bytes, 1024);
    }

    #[test]
    fn test_format_size() {
        assert_eq!(format_size(512), "512 B");
        assert_eq!(format_size(2048), "2.00 KB");
        assert_eq!(format_size(2 * 1024 * 1024), "2.00 MB");
        assert_eq!(format_size(2 * 1024 * 1024 * 1024), "2.00 GB");
    }

    #[test]
    fn test_validate_backup() {
        let manager = StorageManager::new();
        let config = manager.create_config(
            "test-storage",
            StorageType::LocalDisk,
            "/backups",
            10,
            7,
        );

        let items = vec![("/data/file.txt".to_string(), "file.txt".to_string())];
        let backup = manager.create_backup(&config.id, "test", items, "admin").unwrap();

        let validation = manager.validate_backup(&backup.id);
        assert!(validation.is_ok());
        assert!(!validation.unwrap());

        manager.start_backup(&backup.id).unwrap();
        let checksums = vec![(backup.items[0].id.clone(), "checksum".to_string())];
        manager.complete_backup(&backup.id, 1024, checksums).unwrap();

        let validation = manager.validate_backup(&backup.id);
        assert!(validation.is_ok());
        assert!(validation.unwrap());
    }
}
