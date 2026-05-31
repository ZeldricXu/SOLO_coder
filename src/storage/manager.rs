use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use sha2::{Sha256, Digest};
use hex::ToHex;
use chrono::{DateTime, Utc, Duration};
use serde::{Deserialize, Serialize};
use crate::storage::models::{
    BackupRecord, BackupStatus, RestoreRequest, RestoreResult, 
    DataRecord, FrequencyCheckResult,
};
use crate::utils::error::{Result, PlatformError};
use tracing::{info, warn, error};

#[derive(Debug, Clone, Default)]
struct StorageState {
    data: HashMap<String, DataRecord>,
    backups: Vec<BackupRecord>,
    rate_limits: HashMap<String, RateLimitState>,
}

#[derive(Debug, Clone)]
struct RateLimitState {
    requests: Vec<DateTime<Utc>>,
    limit: u32,
    window_seconds: i64,
}

#[derive(Debug, Clone, Default)]
pub struct StorageManager {
    state: Arc<RwLock<StorageState>>,
}

impl StorageManager {
    pub fn new() -> Self {
        Self {
            state: Arc::new(RwLock::new(StorageState::default())),
        }
    }

    pub async fn check_frequency(&self, key: &str) -> FrequencyCheckResult {
        let mut state = self.state.write().await;
        let now = Utc::now();

        let rate_limit = state.rate_limits.entry(key.to_string())
            .or_insert_with(|| RateLimitState {
                requests: vec![],
                limit: 100,
                window_seconds: 60,
            });

        let window_start = now - Duration::seconds(rate_limit.window_seconds);
        rate_limit.requests.retain(|t| *t >= window_start);
        
        let remaining = rate_limit.limit.saturating_sub(rate_limit.requests.len() as u32);
        let reset_at = if rate_limit.requests.is_empty() {
            now
        } else {
            rate_limit.requests.first()
                .map(|t| *t + Duration::seconds(rate_limit.window_seconds))
                .unwrap_or(now)
        };

        if remaining > 0 {
            rate_limit.requests.push(now);
        }

        FrequencyCheckResult {
            allowed: remaining > 0,
            remaining: remaining.saturating_sub(1),
            reset_at,
        }
    }

    pub async fn get(&self, key: &str) -> Result<DataRecord> {
        let state = self.state.read().await;
        state.data.get(key)
            .cloned()
            .ok_or_else(|| PlatformError::NotFound(format!("key {} not found", key)))
    }

    pub async fn put(&self, key: &str, value: serde_json::Value) -> Result<DataRecord> {
        let mut state = self.state.write().await;
        
        let record = match state.data.get_mut(key) {
            Some(existing) => {
                existing.value = value;
                existing.updated_at = Utc::now();
                existing.clone()
            }
            None => {
                let new_record = DataRecord::new(key.to_string(), value);
                state.data.insert(key.to_string(), new_record.clone());
                new_record
            }
        };

        info!(key = %key, "data_stored");
        Ok(record)
    }

    pub async fn delete(&self, key: &str) -> Result<()> {
        let mut state = self.state.write().await;
        
        if state.data.remove(key).is_none() {
            return Err(PlatformError::NotFound(format!("key {} not found", key)));
        }

        info!(key = %key, "data_deleted");
        Ok(())
    }

    pub async fn transform(&self, key: &str, transform: &str) -> Result<DataRecord> {
        info!(key = %key, transform = %transform, "transforming_data");
        
        let mut state = self.state.write().await;
        
        let record = state.data.get_mut(key)
            .ok_or_else(|| PlatformError::NotFound(format!("key {} not found", key)))?;

        match transform {
            "uppercase" => {
                if let Some(s) = record.value.as_str() {
                    record.value = serde_json::Value::String(s.to_uppercase());
                }
            }
            "lowercase" => {
                if let Some(s) = record.value.as_str() {
                    record.value = serde_json::Value::String(s.to_lowercase());
                }
            }
            "trim" => {
                if let Some(s) = record.value.as_str() {
                    record.value = serde_json::Value::String(s.trim().to_string());
                }
            }
            _ => {
                return Err(PlatformError::Validation(format!(
                    "unknown transform: {}", transform
                )));
            }
        }

        record.updated_at = Utc::now();
        
        info!(key = %key, "data_transformed");
        Ok(record.clone())
    }

    pub async fn create_backup(&self, source: &str, destination: &str) -> Result<BackupRecord> {
        info!(source = %source, destination = %destination, "creating_backup");

        let mut state = self.state.write().await;
        
        let mut backup = BackupRecord {
            backup_id: format!("backup_{}", uuid::Uuid::new_v4().simple()),
            source: source.to_string(),
            destination: destination.to_string(),
            status: BackupStatus::InProgress,
            size_bytes: 0,
            created_at: Utc::now(),
            completed_at: None,
            error: None,
            checksum: String::new(),
        };

        state.backups.push(backup.clone());
        drop(state);

        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;

        let data_size = {
            let state = self.state.read().await;
            let data_json = serde_json::to_string(&state.data).unwrap_or_default();
            data_json.len() as u64
        };

        let checksum = self.compute_checksum(&format!("{}:{}", source, Utc::now()));

        let mut state = self.state.write().await;
        if let Some(b) = state.backups.iter_mut().find(|b| b.backup_id == backup.backup_id) {
            b.status = BackupStatus::Completed;
            b.size_bytes = data_size;
            b.completed_at = Some(Utc::now());
            b.checksum = checksum;
            backup = b.clone();
        }

        info!(backup_id = %backup.backup_id, size = %backup.size_bytes, "backup_completed");
        Ok(backup)
    }

    pub async fn list_backups(&self) -> Result<Vec<BackupRecord>> {
        let state = self.state.read().await;
        Ok(state.backups.clone())
    }

    pub async fn get_backup(&self, backup_id: &str) -> Result<BackupRecord> {
        let state = self.state.read().await;
        state.backups
            .iter()
            .find(|b| b.backup_id == backup_id)
            .cloned()
            .ok_or_else(|| PlatformError::NotFound(format!(
                "backup {} not found", backup_id
            )))
    }

    pub async fn restore(&self, request: RestoreRequest) -> Result<RestoreResult> {
        info!(backup_id = %request.backup_id, destination = %request.destination, "starting_restore");

        let backup = self.get_backup(&request.backup_id).await?;
        
        if backup.status != BackupStatus::Completed {
            return Err(PlatformError::Validation(format!(
                "backup {} is not completed (status: {:?})",
                request.backup_id, backup.status
            )));
        }

        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;

        let result = RestoreResult {
            backup_id: request.backup_id,
            success: true,
            restored_at: Utc::now(),
            error: None,
        };

        info!(backup_id = %result.backup_id, "restore_completed");
        Ok(result)
    }

    pub async fn list_keys(&self) -> Result<Vec<String>> {
        let state = self.state.read().await;
        Ok(state.data.keys().cloned().collect())
    }

    fn compute_checksum(&self, data: &str) -> String {
        let mut hasher = Sha256::new();
        hasher.update(data.as_bytes());
        let result = hasher.finalize();
        result.encode_hex::<String>()
    }
}
