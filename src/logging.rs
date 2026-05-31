use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use chrono::{DateTime, Datelike, Local, Timelike, Utc};
use parking_lot::Mutex;
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};
use tracing_appender::non_blocking::WorkerGuard;
use tracing_subscriber::fmt::time::FormatTime;
use tracing_subscriber::{fmt, EnvFilter};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogConfig {
    pub log_dir: String,
    pub log_file_prefix: String,
    pub rotation_strategy: RotationStrategy,
    pub max_file_size_mb: u64,
    pub max_files: usize,
    pub archive_dir: String,
    pub compression_enabled: bool,
    pub level: String,
    pub json_format: bool,
}

impl Default for LogConfig {
    fn default() -> Self {
        Self {
            log_dir: "./logs".to_string(),
            log_file_prefix: "app".to_string(),
            rotation_strategy: RotationStrategy::Daily,
            max_file_size_mb: 100,
            max_files: 30,
            archive_dir: "./logs/archive".to_string(),
            compression_enabled: true,
            level: "info".to_string(),
            json_format: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum RotationStrategy {
    Minutely,
    Hourly,
    Daily,
    SizeBased,
}

#[derive(Debug, Clone)]
pub struct LogFileInfo {
    pub path: PathBuf,
    pub size: u64,
    pub created_at: DateTime<Utc>,
}

pub struct LogManager {
    config: LogConfig,
    current_file: Arc<Mutex<Option<PathBuf>>>,
    current_size: Arc<Mutex<u64>>,
    rotation_tx: Option<mpsc::Sender<()>>,
    _guard: Option<WorkerGuard>,
}

impl LogManager {
    pub fn new(config: LogConfig) -> Result<Self> {
        std::fs::create_dir_all(&config.log_dir)
            .with_context(|| format!("Failed to create log directory: {}", config.log_dir))?;
        std::fs::create_dir_all(&config.archive_dir)
            .with_context(|| format!("Failed to create archive directory: {}", config.archive_dir))?;

        Ok(Self {
            config,
            current_file: Arc::new(Mutex::new(None)),
            current_size: Arc::new(Mutex::new(0)),
            rotation_tx: None,
            _guard: None,
        })
    }

    pub fn init_tracing(&mut self) -> Result<()> {
        let log_file = self.get_current_log_file()?;
        *self.current_file.lock() = Some(log_file.clone());
        *self.current_size.lock() = std::fs::metadata(&log_file)
            .map(|m| m.len())
            .unwrap_or(0);

        let file_appender = tracing_appender::rolling::never(
            &self.config.log_dir,
            log_file.file_name().unwrap().to_str().unwrap(),
        );

        let (non_blocking, guard) = tracing_appender::non_blocking(file_appender);
        self._guard = Some(guard);

        let builder = tracing_subscriber::fmt()
            .with_env_filter(EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| EnvFilter::new(&self.config.level)))
            .with_target(true)
            .with_thread_ids(true)
            .with_file(true)
            .with_line_number(true);

        if self.config.json_format {
            builder
                .json()
                .with_writer(non_blocking)
                .init();
        } else {
            builder
                .with_writer(non_blocking)
                .with_timer(LocalTimer)
                .init();
        }

        info!("Logging initialized with file: {:?}", log_file);
        Ok(())
    }

    fn get_current_log_file(&self) -> Result<PathBuf> {
        let now = Local::now();
        let filename = match self.config.rotation_strategy {
            RotationStrategy::Minutely => format!(
                "{}_{:04}{:02}{:02}_{:02}{:02}.log",
                self.config.log_file_prefix,
                now.year(),
                now.month(),
                now.day(),
                now.hour(),
                now.minute()
            ),
            RotationStrategy::Hourly => format!(
                "{}_{:04}{:02}{:02}_{:02}.log",
                self.config.log_file_prefix,
                now.year(),
                now.month(),
                now.day(),
                now.hour()
            ),
            RotationStrategy::Daily | RotationStrategy::SizeBased => format!(
                "{}_{:04}{:02}{:02}.log",
                self.config.log_file_prefix,
                now.year(),
                now.month(),
                now.day()
            ),
        };

        Ok(Path::new(&self.config.log_dir).join(filename))
    }

    pub async fn start_rotation_watcher(&mut self) -> Result<()> {
        let (tx, mut rx) = mpsc::channel::<()>(1);
        self.rotation_tx = Some(tx);

        let config = self.config.clone();
        let current_file = self.current_file.clone();
        let current_size = self.current_size.clone();

        tokio::spawn(async move {
            let mut interval = match config.rotation_strategy {
                RotationStrategy::Minutely => tokio::time::interval(Duration::from_secs(60)),
                RotationStrategy::Hourly => tokio::time::interval(Duration::from_secs(3600)),
                RotationStrategy::Daily => tokio::time::interval(Duration::from_secs(86400)),
                RotationStrategy::SizeBased => tokio::time::interval(Duration::from_secs(60)),
            };

            loop {
                tokio::select! {
                    _ = interval.tick() => {
                        if let Err(e) = check_and_rotate(
                            &config,
                            &current_file,
                            &current_size,
                        ).await {
                            error!(error = %e, "Log rotation check failed");
                        }
                    }
                    _ = rx.recv() => {
                        info!("Log rotation watcher shutting down");
                        break;
                    }
                }
            }
        });

        Ok(())
    }

    pub fn list_log_files(&self) -> Result<Vec<LogFileInfo>> {
        let mut files = Vec::new();
        
        for entry in std::fs::read_dir(&self.config.log_dir)? {
            let entry = entry?;
            let path = entry.path();
            
            if path.extension().and_then(|e| e.to_str()) == Some("log") {
                let metadata = entry.metadata()?;
                files.push(LogFileInfo {
                    path: path.clone(),
                    size: metadata.len(),
                    created_at: metadata.created()?.into(),
                });
            }
        }
        
        files.sort_by_key(|f| std::cmp::Reverse(f.created_at));
        Ok(files)
    }

    pub fn archive_old_logs(&self) -> Result<usize> {
        let mut archived_count = 0;
        let log_files = self.list_log_files()?;
        
        if log_files.len() <= self.config.max_files {
            return Ok(0);
        }

        let files_to_archive = &log_files[self.config.max_files..];
        
        for file_info in files_to_archive {
            let dest_path = Path::new(&self.config.archive_dir)
                .join(file_info.path.file_name().unwrap());
            
            if self.config.compression_enabled {
                let compressed_path = dest_path.with_extension("log.gz");
                compress_file(&file_info.path, &compressed_path)?;
                std::fs::remove_file(&file_info.path)?;
            } else {
                std::fs::rename(&file_info.path, &dest_path)?;
            }
            
            archived_count += 1;
            debug!("Archived log file: {:?}", file_info.path);
        }
        
        if archived_count > 0 {
            info!("Archived {} log files", archived_count);
        }
        
        Ok(archived_count)
    }

    pub fn cleanup_old_archives(&self, retention_days: u32) -> Result<usize> {
        let mut deleted_count = 0;
        let cutoff = Utc::now() - chrono::Duration::days(retention_days as i64);
        
        for entry in std::fs::read_dir(&self.config.archive_dir)? {
            let entry = entry?;
            let metadata = entry.metadata()?;
            let created: DateTime<Utc> = metadata.created()?.into();
            
            if created < cutoff {
                std::fs::remove_file(entry.path())?;
                deleted_count += 1;
            }
        }
        
        if deleted_count > 0 {
            info!("Cleaned up {} old archive files", deleted_count);
        }
        
        Ok(deleted_count)
    }

    pub fn stop(&mut self) {
        if let Some(tx) = self.rotation_tx.take() {
            drop(tx);
        }
    }
}

async fn check_and_rotate(
    config: &LogConfig,
    current_file: &Arc<Mutex<Option<PathBuf>>>,
    current_size: &Arc<Mutex<u64>>,
) -> Result<()> {
    let now = Local::now();
    let expected_file = match config.rotation_strategy {
        RotationStrategy::Minutely => format!(
            "{}_{:04}{:02}{:02}_{:02}{:02}.log",
            config.log_file_prefix,
            now.year(),
            now.month(),
            now.day(),
            now.hour(),
            now.minute()
        ),
        RotationStrategy::Hourly => format!(
            "{}_{:04}{:02}{:02}_{:02}.log",
            config.log_file_prefix,
            now.year(),
            now.month(),
            now.day(),
            now.hour()
        ),
        RotationStrategy::Daily | RotationStrategy::SizeBased => format!(
            "{}_{:04}{:02}{:02}.log",
            config.log_file_prefix,
            now.year(),
            now.month(),
            now.day()
        ),
    };

    let expected_path = Path::new(&config.log_dir).join(&expected_file);
    let current = current_file.lock().clone();
    
    let needs_rotation = match &current {
        Some(path) => {
            if path.file_name().unwrap() != expected_path.file_name().unwrap() {
                true
            } else if config.rotation_strategy == RotationStrategy::SizeBased {
                let size = *current_size.lock();
                size >= config.max_file_size_mb * 1024 * 1024
            } else {
                false
            }
        }
        None => true,
    };

    if needs_rotation {
        info!("Rotating log file to: {:?}", expected_path);
        *current_file.lock() = Some(expected_path);
        *current_size.lock() = 0;
    }

    Ok(())
}

fn compress_file(src: &Path, dest: &Path) -> Result<()> {
    use std::io::prelude::*;
    use flate2::write::GzEncoder;
    use flate2::Compression;

    let src_file = std::fs::File::open(src)?;
    let dest_file = std::fs::File::create(dest)?;
    
    let mut reader = std::io::BufReader::new(src_file);
    let mut encoder = GzEncoder::new(dest_file, Compression::default());
    
    std::io::copy(&mut reader, &mut encoder)?;
    encoder.finish()?;
    
    Ok(())
}

#[derive(Clone)]
struct LocalTimer;

impl FormatTime for LocalTimer {
    fn format_time(&self, w: &mut dyn std::fmt::Write) -> std::fmt::Result {
        let now = Local::now();
        write!(
            w,
            "{:04}-{:02}-{:02} {:02}:{:02}:{:02}.{:03}",
            now.year(),
            now.month(),
            now.day(),
            now.hour(),
            now.minute(),
            now.second(),
            now.timestamp_subsec_millis()
        )
    }
}

impl Drop for LogManager {
    fn drop(&mut self) {
        self.stop();
    }
}
