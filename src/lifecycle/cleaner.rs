use crate::models::StreamSQLError;
use crate::lifecycle::policy::{CleanupConfig, DataMetadata};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CleanupResult {
    pub data_id: String,
    pub success: bool,
    pub bytes_freed: u64,
    pub duration_ms: u64,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CleanupStats {
    pub total_cleanups: usize,
    pub successful_cleanups: usize,
    pub failed_cleanups: usize,
    pub total_bytes_freed: u64,
    pub total_duration_ms: u64,
    pub by_table: HashMap<String, usize>,
}

pub struct DataCleaner {
    config: CleanupConfig,
    cleanups: tokio::sync::Mutex<Vec<CleanupResult>>,
}

impl DataCleaner {
    pub fn new(config: CleanupConfig) -> Self {
        Self {
            config,
            cleanups: tokio::sync::Mutex::new(Vec::new()),
        }
    }

    pub async fn clean(&self, metadata: &DataMetadata) -> Result<CleanupResult, StreamSQLError> {
        let start_time = std::time::Instant::now();

        let result = self.perform_cleanup(metadata).await;
        let duration = start_time.elapsed().as_millis() as u64;

        let cleanup_result = match result {
            Ok(bytes_freed) => CleanupResult {
                data_id: metadata.data_id.clone(),
                success: true,
                bytes_freed,
                duration_ms: duration,
                error_message: None,
            },
            Err(e) => CleanupResult {
                data_id: metadata.data_id.clone(),
                success: false,
                bytes_freed: 0,
                duration_ms: duration,
                error_message: Some(e.to_string()),
            },
        };

        let mut cleanups = self.cleanups.lock().await;
        cleanups.push(cleanup_result.clone());

        if cleanup_result.success {
            Ok(cleanup_result)
        } else {
            Err(StreamSQLError::Lifecycle(
                cleanup_result.error_message.unwrap_or_default(),
            ))
        }
    }

    async fn perform_cleanup(&self, metadata: &DataMetadata) -> Result<u64, StreamSQLError> {
        tokio::time::sleep(tokio::time::Duration::from_millis(5)).await;

        Ok(metadata.size_bytes)
    }

    pub async fn clean_batch(
        &self,
        metadata_list: Vec<DataMetadata>,
    ) -> Vec<CleanupResult> {
        let mut results = Vec::with_capacity(metadata_list.len());

        for metadata in metadata_list {
            let result = match self.clean(&metadata).await {
                Ok(r) => r,
                Err(e) => CleanupResult {
                    data_id: metadata.data_id.clone(),
                    success: false,
                    bytes_freed: 0,
                    duration_ms: 0,
                    error_message: Some(e.to_string()),
                },
            };
            results.push(result);
        }

        results
    }

    pub async fn get_stats(&self) -> CleanupStats {
        let cleanups = self.cleanups.lock().await;

        let mut stats = CleanupStats {
            total_cleanups: cleanups.len(),
            successful_cleanups: 0,
            failed_cleanups: 0,
            total_bytes_freed: 0,
            total_duration_ms: 0,
            by_table: HashMap::new(),
        };

        for c in &*cleanups {
            if c.success {
                stats.successful_cleanups += 1;
                stats.total_bytes_freed += c.bytes_freed;
            } else {
                stats.failed_cleanups += 1;
            }
            stats.total_duration_ms += c.duration_ms;

            if let Some((_, table)) = c.data_id.split_once('_') {
                *stats.by_table.entry(table.to_string()).or_insert(0) += 1;
            }
        }

        stats
    }

    pub async fn list_cleanups(&self) -> Vec<CleanupResult> {
        let cleanups = self.cleanups.lock().await;
        cleanups.clone()
    }

    pub async fn clear_history(&self) {
        let mut cleanups = self.cleanups.lock().await;
        cleanups.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_cleaner_creation() {
        let config = CleanupConfig::default();
        let cleaner = DataCleaner::new(config);
        let stats = cleaner.get_stats().await;
        assert_eq!(stats.total_cleanups, 0);
    }

    #[tokio::test]
    async fn test_successful_cleanup() {
        let config = CleanupConfig {
            enabled: true,
            ..Default::default()
        };
        let cleaner = DataCleaner::new(config);

        let metadata = DataMetadata::new("d1", "orders", 1024);
        let result = cleaner.clean(&metadata).await;

        assert!(result.is_ok());
        let result = result.unwrap();
        assert!(result.success);
        assert_eq!(result.bytes_freed, 1024);

        let stats = cleaner.get_stats().await;
        assert_eq!(stats.successful_cleanups, 1);
        assert_eq!(stats.total_bytes_freed, 1024);
    }

    #[tokio::test]
    async fn test_batch_cleanup() {
        let config = CleanupConfig {
            enabled: true,
            ..Default::default()
        };
        let cleaner = DataCleaner::new(config);

        let metadata_list: Vec<DataMetadata> = (0..5)
            .map(|i| DataMetadata::new(format!("d{}", i), "orders", 100 * (i + 1)))
            .collect();

        let results = cleaner.clean_batch(metadata_list).await;

        assert_eq!(results.len(), 5);
        assert!(results.iter().all(|r| r.success));

        let stats = cleaner.get_stats().await;
        assert_eq!(stats.successful_cleanups, 5);
        assert_eq!(stats.total_bytes_freed, 100 + 200 + 300 + 400 + 500);
    }
}
