use anyhow::{Context, Result};
use anyhow::Result as AnyhowResult;
use chrono::{DateTime, Duration, Utc};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration as StdDuration;
use tokio::fs;
use tokio::sync::Semaphore;
use tracing::{debug, error, info, warn};

const SCAN_INTERVAL_HOURS: u64 = 6;
const MAX_CONCURRENT_CHECKS: usize = 10;

pub struct CorruptionScanner {
    base_path: PathBuf,
    semaphore: Arc<Semaphore>,
}

impl CorruptionScanner {
    pub fn new(base_path: PathBuf) -> Self {
        Self {
            base_path,
            semaphore: Arc::new(Semaphore::new(MAX_CONCURRENT_CHECKS)),
        }
    }

    pub async fn run_periodic_scan(&self) {
        let mut interval = tokio::time::interval(StdDuration::from_secs(SCAN_INTERVAL_HOURS * 3600));
        loop {
            interval.tick().await;
            if let Err(e) = self.scan_and_repair().await {
                error!("Corruption scan failed: {}", e);
            }
        }
    }

    pub async fn scan_and_repair(&self) -> Result<Vec<PathBuf>> {
        info!("Starting parquet corruption scan in {}", self.base_path.display());

        let mut repaired_files = Vec::new();
        let mut parquet_files = Vec::new();

        self.collect_parquet_files(&self.base_path, &mut parquet_files).await?;

        debug!("Found {} parquet files to check", parquet_files.len());

        let mut handles = Vec::new();
        for file_path in parquet_files {
            let sem_clone = self.semaphore.clone();
            let handle = tokio::spawn(async move {
                let _permit = sem_clone.acquire().await.expect("Semaphore closed");
                check_and_repair_file(&file_path).await
            });
            handles.push(handle);
        }

        for handle in handles {
            match handle.await {
                Ok(Ok(Some(repaired))) => {
                    repaired_files.push(repaired);
                }
                Ok(Ok(None)) => {}
                Ok(Err(e)) => {
                    error!("File check failed: {}", e);
                }
                Err(e) => {
                    error!("File check task panicked: {}", e);
                }
            }
        }

        info!(
            "Corruption scan complete. Repaired/removed {} corrupt files",
            repaired_files.len()
        );

        Ok(repaired_files)
    }

    async fn collect_parquet_files(
        &self,
        dir: &Path,
        files: &mut Vec<PathBuf>,
    ) -> Result<()> {
        let mut entries = fs::read_dir(dir)
            .await
            .with_context(|| format!("Failed to read directory: {}", dir.display()))?;

        while let Some(entry) = entries.next_entry().await? {
            let path = entry.path();
            let file_type = entry.file_type().await?;

            if file_type.is_dir() {
                Box::pin(self.collect_parquet_files(&path, files)).await?;
            } else if file_type.is_file() {
                if let Some(ext) = path.extension() {
                    if ext == "parquet" {
                        files.push(path);
                    }
                }
            }
        }

        Ok(())
    }
}

async fn check_and_repair_file(path: &Path) -> Result<Option<PathBuf>> {
    if is_file_corrupt(path).await? {
        warn!("Found corrupt parquet file: {}", path.display());

        let backup_path = path.with_extension("parquet.corrupt");
        fs::rename(path, &backup_path)
            .await
            .with_context(|| format!(
                "Failed to rename corrupt file {} to {}",
                path.display(),
                backup_path.display()
            ))?;

        metrics::counter!("timeseries_store_repaired_files_total",
            "path" => path.to_string_lossy().to_string()
        ).increment(1);

        info!(
            "Moved corrupt file {} to backup {}",
            path.display(),
            backup_path.display()
        );

        Ok(Some(path.to_path_buf()))
    } else {
        Ok(None)
    }
}

async fn is_file_corrupt(path: &Path) -> Result<bool> {
    use datafusion::prelude::*;

    let path_str = path.to_string_lossy().to_string();

    let result = tokio::task::spawn_blocking(move || -> std::result::Result<bool, String> {
        let rt = match tokio::runtime::Runtime::new() {
            Ok(rt) => rt,
            Err(e) => return Err(format!("Failed to create runtime: {}", e)),
        };

        rt.block_on(async {
            let ctx = SessionContext::new();
            match ctx.read_parquet(&path_str, ParquetReadOptions::default()).await {
                Ok(df) => {
                    match df.limit(0, Some(1)) {
                        Ok(limited) => {
                            match limited.collect().await {
                                Ok(_) => Ok(false),
                                Err(e) => {
                                    debug!("Parquet read collect failed for {}: {}", path_str, e);
                                    Ok(true)
                                }
                            }
                        }
                        Err(e) => {
                            debug!("Parquet limit failed for {}: {}", path_str, e);
                            Ok(true)
                        }
                    }
                }
                Err(e) => {
                    debug!("Parquet read failed for {}: {}", path_str, e);
                    Ok(true)
                }
            }
        })
    }).await;

    match result {
        Ok(Ok(is_corrupt)) => Ok(is_corrupt),
        Ok(Err(e)) => {
            warn!("File check error for {}: {}", path.display(), e);
            Ok(true)
        }
        Err(e) => {
            warn!("File check task panicked for {}: {}", path.display(), e);
            Ok(true)
        }
    }
}

pub fn check_stale_temp_files(base_path: &Path) -> Result<Vec<PathBuf>> {
    let cutoff = Utc::now() - Duration::hours(24);
    let mut stale_files = Vec::new();

    fn walk(dir: &Path, cutoff: DateTime<Utc>, stale: &mut Vec<PathBuf>) -> Result<()> {
        let entries = std::fs::read_dir(dir)
            .with_context(|| format!("Failed to read directory: {}", dir.display()))?;

        for entry in entries {
            let entry = entry?;
            let path = entry.path();
            let file_type = entry.file_type()?;

            if file_type.is_dir() {
                walk(&path, cutoff, stale)?;
            } else if file_type.is_file() {
                if let Some(ext) = path.extension() {
                    if ext == "tmp" {
                        if let Ok(metadata) = std::fs::metadata(&path) {
                            if let Ok(modified) = metadata.modified() {
                                let modified_time: DateTime<Utc> = modified.into();
                                if modified_time < cutoff {
                                    stale.push(path);
                                }
                            }
                        }
                    }
                }
            }
        }
        Ok(())
    }

    walk(base_path, cutoff, &mut stale_files)?;

    if !stale_files.is_empty() {
        warn!(
            "Found {} stale temp files older than 24 hours",
            stale_files.len()
        );
        for file in &stale_files {
            debug!("Stale temp file: {}", file.display());
        }
    }

    Ok(stale_files)
}
