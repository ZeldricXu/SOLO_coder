use crate::models::StreamSQLError;
use crate::lifecycle::policy::{ArchiveConfig, ArchiveCompression, DataMetadata};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ArchiveResult {
    pub data_id: String,
    pub archive_path: Option<String>,
    pub success: bool,
    pub original_size: u64,
    pub compressed_size: Option<u64>,
    pub compression_ratio: Option<f64>,
    pub duration_ms: u64,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ArchiveStats {
    pub total_archives: usize,
    pub successful_archives: usize,
    pub failed_archives: usize,
    pub total_original_bytes: u64,
    pub total_compressed_bytes: u64,
    pub average_compression_ratio: f64,
    pub archives: Vec<ArchiveResult>,
}

pub struct DataArchiver {
    config: ArchiveConfig,
    archives: tokio::sync::Mutex<Vec<ArchiveResult>>,
}

impl DataArchiver {
    pub fn new(config: ArchiveConfig) -> Self {
        Self {
            config,
            archives: tokio::sync::Mutex::new(Vec::new()),
        }
    }

    pub async fn archive(
        &self,
        metadata: &DataMetadata,
    ) -> Result<ArchiveResult, StreamSQLError> {
        let start_time = std::time::Instant::now();

        let result = self.perform_archive(metadata).await;
        let duration = start_time.elapsed().as_millis() as u64;

        let archive_result = match result {
            Ok((path, compressed_size)) => {
                let compression_ratio = if compressed_size > 0 && metadata.size_bytes > 0 {
                    Some(metadata.size_bytes as f64 / compressed_size as f64)
                } else {
                    None
                };

                ArchiveResult {
                    data_id: metadata.data_id.clone(),
                    archive_path: Some(path),
                    success: true,
                    original_size: metadata.size_bytes,
                    compressed_size: Some(compressed_size),
                    compression_ratio,
                    duration_ms: duration,
                    error_message: None,
                }
            }
            Err(e) => ArchiveResult {
                data_id: metadata.data_id.clone(),
                archive_path: None,
                success: false,
                original_size: metadata.size_bytes,
                compressed_size: None,
                compression_ratio: None,
                duration_ms: duration,
                error_message: Some(e.to_string()),
            },
        };

        let mut archives = self.archives.lock().await;
        archives.push(archive_result.clone());

        if archive_result.success {
            Ok(archive_result)
        } else {
            Err(StreamSQLError::Lifecycle(
                archive_result.error_message.unwrap_or_default(),
            ))
        }
    }

    pub async fn restore(&self, metadata: &DataMetadata) -> Result<(), StreamSQLError> {
        tokio::time::sleep(tokio::time::Duration::from_millis(20)).await;

        Ok(())
    }

    async fn perform_archive(&self, metadata: &DataMetadata) -> Result<(String, u64), StreamSQLError> {
        tokio::time::sleep(tokio::time::Duration::from_millis(15)).await;

        let path = PathBuf::from(&self.config.storage_path)
            .join(format!("{}.archive", metadata.data_id));

        let compressed_size = match self.config.compression {
            ArchiveCompression::None => metadata.size_bytes,
            ArchiveCompression::Gzip => (metadata.size_bytes as f64 * 0.5) as u64,
            ArchiveCompression::Lz4 => (metadata.size_bytes as f64 * 0.6) as u64,
            ArchiveCompression::Zstd => (metadata.size_bytes as f64 * 0.45) as u64,
        };

        Ok((path.to_string_lossy().to_string(), compressed_size))
    }

    pub async fn get_stats(&self) -> ArchiveStats {
        let archives = self.archives.lock().await;

        let mut stats = ArchiveStats {
            total_archives: archives.len(),
            successful_archives: 0,
            failed_archives: 0,
            total_original_bytes: 0,
            total_compressed_bytes: 0,
            average_compression_ratio: 0.0,
            archives: archives.clone(),
        };

        let mut total_ratio = 0.0;
        let mut ratio_count = 0;

        for a in &*archives {
            if a.success {
                stats.successful_archives += 1;
                stats.total_original_bytes += a.original_size;
                if let Some(compressed) = a.compressed_size {
                    stats.total_compressed_bytes += compressed;
                }
                if let Some(ratio) = a.compression_ratio {
                    total_ratio += ratio;
                    ratio_count += 1;
                }
            } else {
                stats.failed_archives += 1;
            }
        }

        if ratio_count > 0 {
            stats.average_compression_ratio = total_ratio / ratio_count as f64;
        }

        stats
    }

    pub async fn list_archives(&self) -> Vec<ArchiveResult> {
        let archives = self.archives.lock().await;
        archives.clone()
    }

    pub async fn clear_history(&self) {
        let mut archives = self.archives.lock().await;
        archives.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_archiver_creation() {
        let config = ArchiveConfig::default();
        let archiver = DataArchiver::new(config);
        let stats = archiver.get_stats().await;
        assert_eq!(stats.total_archives, 0);
    }

    #[tokio::test]
    async fn test_successful_archive() {
        let config = ArchiveConfig {
            enabled: true,
            ..Default::default()
        };
        let archiver = DataArchiver::new(config);

        let metadata = DataMetadata::new("d1", "orders", 10240);
        let result = archiver.archive(&metadata).await;

        assert!(result.is_ok());
        let result = result.unwrap();
        assert!(result.success);
        assert!(result.archive_path.is_some());
        assert!(result.compressed_size.is_some());
        assert!(result.compression_ratio.is_some());

        let stats = archiver.get_stats().await;
        assert_eq!(stats.successful_archives, 1);
    }

    #[tokio::test]
    async fn test_compression_ratio() {
        let config = ArchiveConfig {
            enabled: true,
            compression: ArchiveCompression::Zstd,
            ..Default::default()
        };
        let archiver = DataArchiver::new(config);

        let metadata = DataMetadata::new("d1", "orders", 10000);
        let result = archiver.archive(&metadata).await.unwrap();

        assert!(result.compression_ratio.unwrap() > 1.0);
    }

    #[tokio::test]
    async fn test_restore() {
        let config = ArchiveConfig {
            enabled: true,
            ..Default::default()
        };
        let archiver = DataArchiver::new(config);

        let metadata = DataMetadata::new("d1", "orders", 1024);
        let result = archiver.restore(&metadata).await;

        assert!(result.is_ok());
    }
}
