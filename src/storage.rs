use crate::config::StorageConfig;
use crate::error::SystemError;
use chrono::{DateTime, Duration, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileMetadata {
    pub id: Uuid,
    pub name: String,
    pub path: PathBuf,
    pub size: u64,
    pub content_type: String,
    pub created_at: DateTime<Utc>,
    pub last_accessed: DateTime<Utc>,
    pub lifecycle_policy: LifecyclePolicy,
    pub archived: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LifecyclePolicy {
    pub hot_duration_days: i64,
    pub cold_duration_days: i64,
    pub delete_after_days: Option<i64>,
}

impl Default for LifecyclePolicy {
    fn default() -> Self {
        Self {
            hot_duration_days: 30,
            cold_duration_days: 90,
            delete_after_days: Some(365),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageStats {
    pub total_files: usize,
    pub total_size: u64,
    pub hot_files: usize,
    pub cold_files: usize,
    pub archived_files: usize,
    pub capacity_used_percent: f64,
}

#[derive(Clone)]
pub struct StorageManager {
    config: StorageConfig,
    root_dir: PathBuf,
    metadata: Arc<DashMap<Uuid, FileMetadata>>,
    total_size: Arc<RwLock<u64>>,
}

impl StorageManager {
    pub fn new(config: &StorageConfig) -> Result<Self, SystemError> {
        let root_dir = config.root_dir.clone();
        fs::create_dir_all(&root_dir)?;

        let hot_dir = root_dir.join("hot");
        let cold_dir = root_dir.join("cold");
        let archive_dir = root_dir.join("archive");

        fs::create_dir_all(&hot_dir)?;
        fs::create_dir_all(&cold_dir)?;
        fs::create_dir_all(&archive_dir)?;

        let manager = Self {
            config: config.clone(),
            root_dir,
            metadata: Arc::new(DashMap::new()),
            total_size: Arc::new(RwLock::new(0)),
        };

        manager.load_existing_metadata()?;

        Ok(manager)
    }

    fn load_existing_metadata(&self) -> Result<(), SystemError> {
        let metadata_path = self.root_dir.join("metadata.json");
        if metadata_path.exists() {
            let content = fs::read_to_string(&metadata_path)?;
            let entries: Vec<FileMetadata> = serde_json::from_str(&content)?;
            for meta in entries {
                self.metadata.insert(meta.id, meta);
            }
        }
        Ok(())
    }

    fn save_metadata(&self) -> Result<(), SystemError> {
        let entries: Vec<FileMetadata> = self.metadata.iter().map(|m| m.clone()).collect();
        let content = serde_json::to_string_pretty(&entries)?;
        let metadata_path = self.root_dir.join("metadata.json");
        fs::write(metadata_path, content)?;
        Ok(())
    }

    pub async fn save_file(
        &self,
        name: String,
        content: &[u8],
        content_type: String,
        lifecycle_policy: Option<LifecyclePolicy>,
    ) -> Result<Uuid, SystemError> {
        let file_id = Uuid::new_v4();
        let hot_dir = self.root_dir.join("hot");
        let file_path = hot_dir.join(file_id.to_string());

        fs::write(&file_path, content)?;

        let size = content.len() as u64;
        let meta = FileMetadata {
            id: file_id,
            name,
            path: file_path.clone(),
            size,
            content_type,
            created_at: Utc::now(),
            last_accessed: Utc::now(),
            lifecycle_policy: lifecycle_policy.unwrap_or_default(),
            archived: false,
        };

        self.metadata.insert(file_id, meta);

        let mut total = self.total_size.write().await;
        *total += size;

        self.save_metadata()?;
        self.check_capacity().await?;

        Ok(file_id)
    }

    pub async fn get_file(&self, file_id: Uuid) -> Result<(Vec<u8>, FileMetadata), SystemError> {
        let mut meta = self
            .metadata
            .get(&file_id)
            .map(|r| r.clone())
            .ok_or_else(|| SystemError::NotFoundError(format!("文件不存在: {}", file_id)))?;

        meta.last_accessed = Utc::now();
        self.metadata.insert(file_id, meta.clone());

        let content = fs::read(&meta.path)?;
        Ok((content, meta))
    }

    pub async fn delete_file(&self, file_id: Uuid) -> Result<(), SystemError> {
        let meta = self
            .metadata
            .remove(&file_id)
            .ok_or_else(|| SystemError::NotFoundError(format!("文件不存在: {}", file_id)))?
            .1;

        if meta.path.exists() {
            fs::remove_file(&meta.path)?;
        }

        let mut total = self.total_size.write().await;
        *total = total.saturating_sub(meta.size);

        self.save_metadata()?;
        Ok(())
    }

    pub async fn get_file_metadata(&self, file_id: Uuid) -> Result<FileMetadata, SystemError> {
        self.metadata
            .get(&file_id)
            .map(|r| r.clone())
            .ok_or_else(|| SystemError::NotFoundError(format!("文件不存在: {}", file_id)))
    }

    pub async fn list_files(&self) -> Result<Vec<FileMetadata>, SystemError> {
        Ok(self.metadata.iter().map(|m| m.clone()).collect())
    }

    pub async fn get_stats(&self) -> Result<StorageStats, SystemError> {
        let total = *self.total_size.read().await;
        let max_capacity_bytes = self.config.max_capacity_gb * 1024 * 1024 * 1024;

        let mut hot_files = 0;
        let mut cold_files = 0;
        let mut archived_files = 0;

        for meta in self.metadata.iter() {
            if meta.archived {
                archived_files += 1;
            } else if meta.path.starts_with(self.root_dir.join("cold")) {
                cold_files += 1;
            } else {
                hot_files += 1;
            }
        }

        Ok(StorageStats {
            total_files: self.metadata.len(),
            total_size: total,
            hot_files,
            cold_files,
            archived_files,
            capacity_used_percent: (total as f64 / max_capacity_bytes as f64) * 100.0,
        })
    }

    async fn check_capacity(&self) -> Result<(), SystemError> {
        let total = *self.total_size.read().await;
        let max_capacity_bytes = self.config.max_capacity_gb * 1024 * 1024 * 1024;

        if total > max_capacity_bytes * 90 / 100 {
            warn!("存储空间使用率超过90%，开始清理旧文件");
            self.cleanup_old_files().await?;
        }

        Ok(())
    }

    pub async fn apply_lifecycle_policies(&self) -> Result<(), SystemError> {
        let now = Utc::now();
        let mut to_move = Vec::new();
        let mut to_delete = Vec::new();

        for entry in self.metadata.iter() {
            let meta = entry.value();
            let age = now - meta.created_at;

            if age > Duration::days(meta.lifecycle_policy.hot_duration_days)
                && !meta.path.starts_with(self.root_dir.join("cold"))
                && !meta.archived
            {
                to_move.push((meta.id, "cold".to_string()));
            }

            if age > Duration::days(
                meta.lifecycle_policy.hot_duration_days + meta.lifecycle_policy.cold_duration_days,
            ) && !meta.archived
            {
                to_move.push((meta.id, "archive".to_string()));
            }

            if let Some(delete_days) = meta.lifecycle_policy.delete_after_days {
                if age > Duration::days(delete_days) {
                    to_delete.push(meta.id);
                }
            }
        }

        for (file_id, target) in to_move {
            self.move_to_tier(file_id, &target).await?;
        }

        for file_id in to_delete {
            self.delete_file(file_id).await?;
        }

        Ok(())
    }

    async fn move_to_tier(&self, file_id: Uuid, tier: &str) -> Result<(), SystemError> {
        let mut meta = self
            .metadata
            .get(&file_id)
            .map(|r| r.clone())
            .ok_or_else(|| SystemError::NotFoundError(format!("文件不存在: {}", file_id)))?;

        let target_dir = self.root_dir.join(tier);
        let target_path = target_dir.join(file_id.to_string());

        if meta.path != target_path {
            if meta.path.exists() {
                fs::rename(&meta.path, &target_path)?;
            }
            meta.path = target_path;
        }

        meta.archived = tier == "archive";
        self.metadata.insert(file_id, meta);
        self.save_metadata()?;

        Ok(())
    }

    pub async fn cleanup_old_files(&self) -> Result<(), SystemError> {
        let mut oldest_files: Vec<FileMetadata> = self
            .metadata
            .iter()
            .map(|m| m.clone())
            .filter(|m| !m.archived)
            .collect();

        oldest_files.sort_by_key(|m| m.last_accessed);

        let total = *self.total_size.read().await;
        let max_capacity_bytes = self.config.max_capacity_gb * 1024 * 1024 * 1024;
        let target_size = max_capacity_bytes * 70 / 100;

        let mut freed = 0u64;
        for meta in oldest_files {
            if total.saturating_sub(freed) <= target_size {
                break;
            }

            if meta.path.exists() {
                fs::remove_file(&meta.path)?;
            }
            self.metadata.remove(&meta.id);
            freed += meta.size;
        }

        let mut total = self.total_size.write().await;
        *total = total.saturating_sub(freed);

        self.save_metadata()?;
        Ok(())
    }

    pub async fn start_cleanup_worker(&self) -> Result<(), SystemError> {
        let interval = self.config.cleanup_interval();
        let manager = self.clone();

        tokio::spawn(async move {
            loop {
                tokio::time::sleep(interval).await;

                if let Err(e) = manager.apply_lifecycle_policies().await {
                    error!("生命周期策略执行失败: {}", e);
                }

                if let Err(e) = manager.check_capacity().await {
                    error!("容量检查失败: {}", e);
                }
            }
        });

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[tokio::test]
    async fn test_storage_operations() {
        let dir = tempdir().unwrap();
        let config = StorageConfig {
            root_dir: dir.path().to_path_buf(),
            max_capacity_gb: 1,
            cleanup_interval_secs: 60,
        };

        let storage = StorageManager::new(&config).unwrap();

        let file_id = storage
            .save_file(
                "test.txt".to_string(),
                b"Hello, World!".to_vec().as_slice(),
                "text/plain".to_string(),
                None,
            )
            .await
            .unwrap();

        let (content, meta) = storage.get_file(file_id).await.unwrap();
        assert_eq!(content, b"Hello, World!");
        assert_eq!(meta.name, "test.txt");

        let stats = storage.get_stats().await.unwrap();
        assert_eq!(stats.total_files, 1);

        storage.delete_file(file_id).await.unwrap();
        let stats = storage.get_stats().await.unwrap();
        assert_eq!(stats.total_files, 0);
    }
}
