use anyhow::{Context, Result};
use chrono::{DateTime, Duration, Timelike, Utc};
use std::path::{Path, PathBuf};
use tokio::task;
use tracing::{debug, info, warn};

use crate::partition::PartitionManager;

pub struct CompactionManager {
    base_path: PathBuf,
    partition_manager: PartitionManager,
    compaction_hours: i64,
    retention_hours: i64,
}

impl CompactionManager {
    pub fn new(base_path: PathBuf) -> Self {
        Self {
            base_path: base_path.clone(),
            partition_manager: PartitionManager::new(base_path),
            compaction_hours: 24,
            retention_hours: 24,
        }
    }

    pub fn with_config(base_path: PathBuf, compaction_hours: i64, retention_hours: i64) -> Self {
        Self {
            base_path: base_path.clone(),
            partition_manager: PartitionManager::new(base_path),
            compaction_hours,
            retention_hours,
        }
    }

    pub async fn run_periodic_compaction(&self) {
        info!("Compaction manager started (compaction_hours={}, retention_hours={})",
              self.compaction_hours, self.retention_hours);

        let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(3600));

        loop {
            interval.tick().await;

            if let Err(e) = self.run_compaction().await {
                warn!("Compaction failed: {}", e);
            }
        }
    }

    pub async fn run_compaction(&self) -> Result<()> {
        info!("Starting daily compaction...");

        let now = Utc::now();
        let compaction_cutoff = now - Duration::hours(self.compaction_hours);

        let days_to_compact = self.get_days_before(compaction_cutoff);

        for day_key in days_to_compact {
            if let Err(e) = self.compact_day(&day_key).await {
                warn!("Failed to compact day {}: {}", day_key, e);
            }
        }

        self.cleanup_old_hourly_files(compaction_cutoff).await?;

        info!("Daily compaction completed");
        Ok(())
    }

    fn get_days_before(&self, cutoff: DateTime<Utc>) -> Vec<String> {
        let mut days = Vec::new();
        let mut current = cutoff.with_hour(0).unwrap().with_minute(0).unwrap().with_second(0).unwrap().with_nanosecond(0).unwrap();

        for _ in 0..7 {
            let day_key = current.format("dt=20%Y-%m-%d").to_string();
            days.push(day_key);
            current = current - Duration::days(1);
        }

        days
    }

    async fn compact_day(&self, day_key: &str) -> Result<()> {
        debug!("Compacting day: {}", day_key);

        let hourly_files = self.collect_hourly_files_for_day(day_key)?;

        if hourly_files.is_empty() {
            debug!("No files to compact for day: {}", day_key);
            return Ok(());
        }

        debug!("Found {} hourly files for day {}", hourly_files.len(), day_key);

        let output_file = self.base_path.join(day_key).join("compact_daily.parquet");

        self.merge_parquet_files(&hourly_files, &output_file).await?;

        info!("Compacted {} files into {}", hourly_files.len(), output_file.display());

        Ok(())
    }

    fn collect_hourly_files_for_day(&self, day_key: &str) -> Result<Vec<PathBuf>> {
        let day_path = self.base_path.join(day_key);

        if !day_path.exists() {
            return Ok(Vec::new());
        }

        let mut files = Vec::new();

        for entry in std::fs::read_dir(&day_path)? {
            let entry = entry?;
            let path = entry.path();

            if path.is_dir() {
                for file_entry in std::fs::read_dir(&path)? {
                    let file_entry = file_entry?;
                    let file_path = file_entry.path();
                    if file_path.is_file()
                        && file_path.extension().and_then(|e| e.to_str()) == Some("parquet")
                        && file_path.file_name().and_then(|n| n.to_str()).map(|n| !n.contains("compact")).unwrap_or(false)
                    {
                        files.push(file_path);
                    }
                }
            } else if path.is_file()
                && path.extension().and_then(|e| e.to_str()) == Some("parquet")
                && path.file_name().and_then(|n| n.to_str()).map(|n| !n.contains("compact")).unwrap_or(false)
            {
                files.push(path);
            }
        }

        files.sort();
        Ok(files)
    }

    async fn merge_parquet_files(&self, input_files: &[PathBuf], output_file: &Path) -> Result<()> {
        if input_files.is_empty() {
            return Ok(());
        }

        let input_files_clone: Vec<PathBuf> = input_files.to_vec();
        let output_file_clone = output_file.to_path_buf();

        task::spawn_blocking(move || -> Result<()> {
            use arrow::record_batch::RecordBatch;
            use datafusion::prelude::*;
            use parquet::arrow::ArrowWriter;
            use parquet::basic::{Compression, Encoding};
            use parquet::file::properties::WriterProperties;

            let rt = tokio::runtime::Runtime::new()?;

            let merged = rt.block_on(async move {
                let ctx = SessionContext::new();

                let file_paths: Vec<String> = input_files_clone
                    .iter()
                    .map(|p| p.to_string_lossy().to_string())
                    .collect();

                let df = ctx
                    .read_parquet(file_paths, ParquetReadOptions::default())
                    .await?;

                let batches = df.collect().await?;
                Ok::<Vec<RecordBatch>, anyhow::Error>(batches)
            })?;

            if merged.is_empty() {
                return Ok(());
            }

            let schema = merged[0].schema();

            if let Some(parent) = output_file_clone.parent() {
                std::fs::create_dir_all(parent)?;
            }

            let props = WriterProperties::builder()
                .set_compression(Compression::SNAPPY)
                .set_encoding(Encoding::PLAIN)
                .build();

            let file = std::fs::File::create(&output_file_clone)
                .with_context(|| format!("Failed to create compacted file: {}", output_file_clone.display()))?;

            let mut writer = ArrowWriter::try_new(file, schema.clone(), Some(props))?;

            for batch in merged {
                writer.write(&batch)?;
            }

            writer.close()?;

            Ok(())
        }).await
            .with_context(|| "Compaction task panicked")??;

        Ok(())
    }

    async fn cleanup_old_hourly_files(&self, cutoff: DateTime<Utc>) -> Result<()> {
        let partitions = self.partition_manager.list_partitions()?;

        let mut deleted_count = 0;

        for partition in partitions {
            if let Some(dt) = self.parse_partition_datetime(&partition) {
                if dt < cutoff {
                    let files = self.partition_manager.list_files_in_partition(&partition)?;
                    for file in files {
                        if let Some(filename) = file.file_name().and_then(|n| n.to_str()) {
                            if filename.starts_with("metrics_") && !filename.contains("compact") {
                                std::fs::remove_file(&file).ok();
                                deleted_count += 1;
                            }
                        }
                    }
                }
            }
        }

        if deleted_count > 0 {
            info!("Cleaned up {} old hourly files", deleted_count);
        }

        Ok(())
    }

    fn parse_partition_datetime(&self, partition: &str) -> Option<DateTime<Utc>> {
        let parts: Vec<&str> = partition.split('/').collect();
        if parts.len() >= 2 {
            let dt_str = parts[0].trim_start_matches("dt=");
            let hour = parts[1].parse::<u32>().ok()?;
            let dt = chrono::NaiveDate::parse_from_str(dt_str, "%Y-%m-%d").ok()?;
            let naive = dt.and_hms_opt(hour, 0, 0)?;
            Some(DateTime::from_naive_utc_and_offset(naive, Utc))
        } else if parts.len() == 1 {
            let dt_str = parts[0].trim_start_matches("dt=");
            let dt = chrono::NaiveDate::parse_from_str(dt_str, "%Y-%m-%d").ok()?;
            let naive = dt.and_hms_opt(0, 0, 0)?;
            Some(DateTime::from_naive_utc_and_offset(naive, Utc))
        } else {
            None
        }
    }

    pub async fn run_manual_compaction(&self, days: i64) -> Result<()> {
        info!("Running manual compaction for {} days", days);

        let now = Utc::now();
        let cutoff = now - Duration::days(days);

        let mut current = cutoff.with_hour(0).unwrap().with_minute(0).unwrap().with_second(0).unwrap().with_nanosecond(0).unwrap();

        for _ in 0..days {
            let day_key = current.format("dt=20%Y-%m-%d").to_string();
            self.compact_day(&day_key).await?;
            current = current - Duration::days(1);
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[tokio::test]
    async fn test_compaction_manager_create() {
        let dir = tempdir().unwrap();
        let manager = CompactionManager::new(dir.path().to_path_buf());
        assert_eq!(manager.compaction_hours, 24);
        assert_eq!(manager.retention_hours, 24);
    }
}
