use crate::config::OfflineCacheConfig;
use crate::error::SystemError;
use chrono::{DateTime, Duration, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CachedData {
    pub id: Uuid,
    pub data_type: String,
    pub payload: serde_json::Value,
    pub created_at: DateTime<Utc>,
    pub synced: bool,
    pub sync_attempts: u32,
    pub last_sync_attempt: Option<DateTime<Utc>>,
    pub priority: CachePriority,
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum CachePriority {
    Low,
    Normal,
    High,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheStats {
    pub total_entries: usize,
    pub synced_entries: usize,
    pub unsynced_entries: usize,
    pub total_size_bytes: u64,
    pub oldest_entry: Option<DateTime<Utc>>,
    pub newest_entry: Option<DateTime<Utc>>,
    pub sync_backlog: usize,
}

pub struct OfflineCache {
    config: OfflineCacheConfig,
    db_path: PathBuf,
    conn: Arc<Mutex<rusqlite::Connection>>,
    network_status: Arc<RwLock<bool>>,
    cloud_client: reqwest::Client,
}

impl OfflineCache {
    pub fn new(config: &OfflineCacheConfig) -> Result<Self, SystemError> {
        let db_path = config.db_path.clone();

        if let Some(parent) = db_path.parent() {
            std::fs::create_dir_all(parent)?;
        }

        let conn = rusqlite::Connection::open(&db_path).map_err(|e| {
            SystemError::OfflineCacheError(format!("数据库打开失败: {}", e))
        })?;

        Self::init_schema(&conn)?;

        let cache = Self {
            config: config.clone(),
            db_path,
            conn: Arc::new(Mutex::new(conn)),
            network_status: Arc::new(RwLock::new(true)),
            cloud_client: reqwest::Client::builder()
                .timeout(std::time::Duration::from_secs(30))
                .build()
                .map_err(SystemError::NetworkError)?,
        };

        Ok(cache)
    }

    fn init_schema(conn: &rusqlite::Connection) -> Result<(), SystemError> {
        conn.execute(
            "CREATE TABLE IF NOT EXISTS cached_data (
                id TEXT PRIMARY KEY,
                data_type TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at TEXT NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0,
                sync_attempts INTEGER NOT NULL DEFAULT 0,
                last_sync_attempt TEXT,
                priority INTEGER NOT NULL DEFAULT 1,
                tags TEXT
            )",
            [],
        ).map_err(|e| SystemError::OfflineCacheError(format!("创建表失败: {}", e)))?;

        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_synced ON cached_data(synced)",
            [],
        ).map_err(|e| SystemError::OfflineCacheError(format!("创建索引失败: {}", e)))?;

        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_created_at ON cached_data(created_at)",
            [],
        ).map_err(|e| SystemError::OfflineCacheError(format!("创建索引失败: {}", e)))?;

        Ok(())
    }

    pub async fn cache_data(
        &self,
        data_type: String,
        payload: serde_json::Value,
        priority: CachePriority,
        tags: Vec<String>,
    ) -> Result<Uuid, SystemError> {
        let id = Uuid::new_v4();
        let created_at = Utc::now();

        let payload_str = serde_json::to_string(&payload)?;
        let tags_str = serde_json::to_string(&tags)?;
        let priority_int = match priority {
            CachePriority::Low => 0,
            CachePriority::Normal => 1,
            CachePriority::High => 2,
            CachePriority::Critical => 3,
        };

        let conn = self.conn.lock().await;
        conn.execute(
            "INSERT INTO cached_data (
                id, data_type, payload, created_at,
                synced, sync_attempts, last_sync_attempt, priority, tags
            ) VALUES (?, ?, ?, ?, 0, 0, NULL, ?, ?)",
            [
                id.to_string(),
                data_type,
                payload_str,
                created_at.to_rfc3339(),
                priority_int.to_string(),
                tags_str,
            ],
        ).map_err(|e| SystemError::OfflineCacheError(format!("插入缓存失败: {}", e)))?;

        self.check_capacity().await?;

        Ok(id)
    }

    pub async fn get_cached_data(&self, id: Uuid) -> Result<Option<CachedData>, SystemError> {
        let conn = self.conn.lock().await;
        let mut stmt = conn
            .prepare(
                "SELECT id, data_type, payload, created_at, synced, sync_attempts, last_sync_attempt, priority, tags FROM cached_data WHERE id = ?"
            )
            .map_err(|e| SystemError::OfflineCacheError(format!("查询失败: {}", e)))?;

        let result = stmt.query_row([id.to_string()], |row| {
            Ok(CachedData {
                id: Uuid::parse_str(&row.get::<_, String>(0)?).unwrap(),
                data_type: row.get(1)?,
                payload: serde_json::from_str(&row.get::<_, String>(2)?).unwrap(),
                created_at: DateTime::parse_from_rfc3339(&row.get::<_, String>(3)?)
                    .unwrap()
                    .with_timezone(&Utc),
                synced: row.get::<_, i64>(4)? != 0,
                sync_attempts: row.get(5)?,
                last_sync_attempt: row
                    .get::<_, Option<String>>(6)?
                    .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                    .map(|dt| dt.with_timezone(&Utc)),
                priority: match row.get::<_, i64>(7)? {
                    0 => CachePriority::Low,
                    1 => CachePriority::Normal,
                    2 => CachePriority::High,
                    _ => CachePriority::Critical,
                },
                tags: serde_json::from_str(&row.get::<_, String>(8)?).unwrap_or_default(),
            })
        });

        match result {
            Ok(data) => Ok(Some(data)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(SystemError::OfflineCacheError(format!("查询失败: {}", e))),
        }
    }

    pub async fn get_unsynced_data(&self, limit: usize) -> Result<Vec<CachedData>, SystemError> {
        let conn = self.conn.lock().await;
        let mut stmt = conn
            .prepare(
                "SELECT id, data_type, payload, created_at, synced, sync_attempts, last_sync_attempt, priority, tags
                 FROM cached_data WHERE synced = 0 ORDER BY priority DESC, created_at ASC LIMIT ?"
            )
            .map_err(|e| SystemError::OfflineCacheError(format!("查询失败: {}", e)))?;

        let rows = stmt
            .query_map([limit as i64], |row| {
                Ok(CachedData {
                    id: Uuid::parse_str(&row.get::<_, String>(0)?).unwrap(),
                    data_type: row.get(1)?,
                    payload: serde_json::from_str(&row.get::<_, String>(2)?).unwrap(),
                    created_at: DateTime::parse_from_rfc3339(&row.get::<_, String>(3)?)
                        .unwrap()
                        .with_timezone(&Utc),
                    synced: row.get::<_, i64>(4)? != 0,
                    sync_attempts: row.get(5)?,
                    last_sync_attempt: row
                        .get::<_, Option<String>>(6)?
                        .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                        .map(|dt| dt.with_timezone(&Utc)),
                    priority: match row.get::<_, i64>(7)? {
                        0 => CachePriority::Low,
                        1 => CachePriority::Normal,
                        2 => CachePriority::High,
                        _ => CachePriority::Critical,
                    },
                    tags: serde_json::from_str(&row.get::<_, String>(8)?).unwrap_or_default(),
                })
            })
            .map_err(|e| SystemError::OfflineCacheError(format!("查询失败: {}", e)))?;

        let mut result = Vec::new();
        for row in rows {
            result.push(row.map_err(|e| SystemError::OfflineCacheError(format!("获取行失败: {}", e)))?);
        }

        Ok(result)
    }

    pub async fn mark_synced(&self, id: Uuid) -> Result<(), SystemError> {
        let conn = self.conn.lock().await;
        conn.execute(
            "UPDATE cached_data SET synced = 1, last_sync_attempt = ? WHERE id = ?",
            [Utc::now().to_rfc3339(), id.to_string()],
        ).map_err(|e| SystemError::OfflineCacheError(format!("更新失败: {}", e)))?;
        Ok(())
    }

    pub async fn mark_sync_failed(&self, id: Uuid) -> Result<(), SystemError> {
        let conn = self.conn.lock().await;
        conn.execute(
            "UPDATE cached_data SET sync_attempts = sync_attempts + 1, last_sync_attempt = ? WHERE id = ?",
            [Utc::now().to_rfc3339(), id.to_string()],
        ).map_err(|e| SystemError::OfflineCacheError(format!("更新失败: {}", e)))?;
        Ok(())
    }

    pub async fn get_stats(&self) -> Result<CacheStats, SystemError> {
        let conn = self.conn.lock().await;

        let total: i64 = conn
            .query_row("SELECT COUNT(*) FROM cached_data", [], |row| row.get(0))
            .map_err(|e| SystemError::OfflineCacheError(format!("统计失败: {}", e)))?;

        let synced: i64 = conn
            .query_row("SELECT COUNT(*) FROM cached_data WHERE synced = 1", [], |row| row.get(0))
            .map_err(|e| SystemError::OfflineCacheError(format!("统计失败: {}", e)))?;

        let total_size: i64 = conn
            .query_row(
                "SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM cached_data",
                [],
                |row| row.get(0),
            )
            .map_err(|e| SystemError::OfflineCacheError(format!("统计失败: {}", e)))?;

        let oldest: Option<String> = conn
            .query_row("SELECT MIN(created_at) FROM cached_data", [], |row| row.get(0))
            .map_err(|e| SystemError::OfflineCacheError(format!("统计失败: {}", e)))?;

        let newest: Option<String> = conn
            .query_row("SELECT MAX(created_at) FROM cached_data", [], |row| row.get(0))
            .map_err(|e| SystemError::OfflineCacheError(format!("统计失败: {}", e)))?;

        Ok(CacheStats {
            total_entries: total as usize,
            synced_entries: synced as usize,
            unsynced_entries: (total - synced) as usize,
            total_size_bytes: total_size as u64,
            oldest_entry: oldest.and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                .map(|dt| dt.with_timezone(&Utc)),
            newest_entry: newest.and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                .map(|dt| dt.with_timezone(&Utc)),
            sync_backlog: (total - synced) as usize,
        })
    }

    async fn check_capacity(&self) -> Result<(), SystemError> {
        let stats = self.get_stats().await?;
        let max_size_bytes = self.config.max_cache_size_mb * 1024 * 1024;

        if stats.total_size_bytes > max_size_bytes {
            warn!(
                "离线缓存容量超限: 当前 {} MB / {} MB",
                stats.total_size_bytes / (1024 * 1024),
                self.config.max_cache_size_mb
            );
            self.cleanup_old_entries().await?;
        }

        Ok(())
    }

    pub async fn cleanup_old_entries(&self) -> Result<(), SystemError> {
        let conn = self.conn.lock().await;

        conn.execute(
            "DELETE FROM cached_data WHERE synced = 1 AND created_at < ?",
            [(Utc::now() - Duration::days(7)).to_rfc3339()],
        ).map_err(|e| SystemError::OfflineCacheError(format!("清理失败: {}", e)))?;

        Ok(())
    }

    pub async fn set_network_status(&self, online: bool) {
        let mut status = self.network_status.write().await;
        *status = online;
    }

    pub async fn is_online(&self) -> bool {
        *self.network_status.read().await
    }

    pub async fn sync_to_cloud(&self) -> Result<usize, SystemError> {
        if !self.is_online().await {
            return Err(SystemError::OfflineCacheError("网络不可用".to_string()));
        }

        let unsynced = self.get_unsynced_data(100).await?;
        let mut synced_count = 0;

        for data in unsynced {
            match self.upload_data(&data).await {
                Ok(_) => {
                    self.mark_synced(data.id).await?;
                    synced_count += 1;
                }
                Err(e) => {
                    warn!("上传数据失败 ({}): {}", data.id, e);
                    self.mark_sync_failed(data.id).await?;
                }
            }
        }

        Ok(synced_count)
    }

    async fn upload_data(&self, data: &CachedData) -> Result<(), SystemError> {
        let payload = serde_json::json!({
            "id": data.id.to_string(),
            "data_type": data.data_type,
            "payload": data.payload,
            "timestamp": data.created_at,
            "tags": data.tags,
        });

        let response = self
            .cloud_client
            .post(&self.config.cloud_endpoint)
            .json(&payload)
            .send()
            .await
            .map_err(SystemError::NetworkError)?;

        if response.status().is_success() {
            Ok(())
        } else {
            Err(SystemError::OfflineCacheError(format!(
                "上传失败: HTTP {}",
                response.status()
            )))
        }
    }

    pub async fn start_sync_worker(&self) -> Result<(), SystemError> {
        let interval = self.config.sync_interval();
        let cache = self.clone();

        tokio::spawn(async move {
            loop {
                tokio::time::sleep(interval).await;

                if cache.is_online().await {
                    match cache.sync_to_cloud().await {
                        Ok(count) if count > 0 => {
                            debug!("同步了 {} 条数据到云端", count);
                        }
                        Err(e) => {
                            warn!("同步失败: {}", e);
                        }
                        _ => {}
                    }
                }
            }
        });

        Ok(())
    }

    pub async fn delete_synced_data(&self, days: i64) -> Result<usize, SystemError> {
        let conn = self.conn.lock().await;
        let count = conn
            .execute(
                "DELETE FROM cached_data WHERE synced = 1 AND created_at < ?",
                [(Utc::now() - Duration::days(days)).to_rfc3339()],
            )
            .map_err(|e| SystemError::OfflineCacheError(format!("删除失败: {}", e)))?;
        Ok(count)
    }

    pub async fn query_by_tags(&self, tags: Vec<String>) -> Result<Vec<CachedData>, SystemError> {
        let conn = self.conn.lock().await;
        let mut results = Vec::new();

        let mut stmt = conn
            .prepare(
                "SELECT id, data_type, payload, created_at, synced, sync_attempts, last_sync_attempt, priority, tags
                 FROM cached_data"
            )
            .map_err(|e| SystemError::OfflineCacheError(format!("查询失败: {}", e)))?;

        let rows = stmt
            .query_map([], |row| {
                Ok(CachedData {
                    id: Uuid::parse_str(&row.get::<_, String>(0)?).unwrap(),
                    data_type: row.get(1)?,
                    payload: serde_json::from_str(&row.get::<_, String>(2)?).unwrap(),
                    created_at: DateTime::parse_from_rfc3339(&row.get::<_, String>(3)?)
                        .unwrap()
                        .with_timezone(&Utc),
                    synced: row.get::<_, i64>(4)? != 0,
                    sync_attempts: row.get(5)?,
                    last_sync_attempt: row
                        .get::<_, Option<String>>(6)?
                        .and_then(|s| DateTime::parse_from_rfc3339(&s).ok())
                        .map(|dt| dt.with_timezone(&Utc)),
                    priority: match row.get::<_, i64>(7)? {
                            0 => CachePriority::Low,
                            1 => CachePriority::Normal,
                            2 => CachePriority::High,
                            _ => CachePriority::Critical,
                        },
                    tags: serde_json::from_str(&row.get::<_, String>(8)?).unwrap_or_default(),
                })
            })
            .map_err(|e| SystemError::OfflineCacheError(format!("查询失败: {}", e)))?;

        for row in rows {
            let data = row.map_err(|e| SystemError::OfflineCacheError(format!("获取行失败: {}", e)))?;
            if tags.iter().any(|t| data.tags.contains(t)) {
                results.push(data);
            }
        }

        Ok(results)
    }
}

impl Clone for OfflineCache {
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            db_path: self.db_path.clone(),
            conn: self.conn.clone(),
            network_status: self.network_status.clone(),
            cloud_client: self.cloud_client.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[tokio::test]
    async fn test_offline_cache() {
        let dir = tempdir().unwrap();
        let config = OfflineCacheConfig {
            db_path: dir.path().join("test.db"),
            max_cache_size_mb: 100,
            sync_interval_secs: 60,
            cloud_endpoint: "http://localhost:8080".to_string(),
        };

        let cache = OfflineCache::new(&config).unwrap();

        let payload = serde_json::json!({"temperature": 25.5, "humidity": 60});
        let id = cache
            .cache_data(
                "sensor".to_string(),
                payload,
                CachePriority::Normal,
                vec!["sensor".to_string()],
            )
            .await
            .unwrap();

        let stats = cache.get_stats().await.unwrap();
        assert_eq!(stats.total_entries, 1);
        assert_eq!(stats.unsynced_entries, 1);

        let data = cache.get_cached_data(id).await.unwrap().unwrap();
        assert_eq!(data.data_type, "sensor");
    }
}
