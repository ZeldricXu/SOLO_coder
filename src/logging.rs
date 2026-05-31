use crate::types::AppError;
use std::path::Path;
use std::sync::Arc;
use tracing::Level;
use tracing_appender::non_blocking::WorkerGuard;
use tracing_appender::rolling::{RollingFileAppender, Rotation};
use tracing_subscriber::{fmt, layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

pub struct LoggingGuard {
    _file_guard: WorkerGuard,
}

pub fn init_logging() -> Result<LoggingGuard, AppError> {
    let config = crate::types::AppConfig::load()?;
    let log_dir = Path::new(&config.log_dir);
    
    std::fs::create_dir_all(log_dir).map_err(|e| {
        AppError::ConfigError(format!("创建日志目录失败: {}", e))
    })?;

    let file_appender = RollingFileAppender::builder()
        .rotation(Rotation::DAILY)
        .filename_prefix("app")
        .filename_suffix("log")
        .max_log_files(30)
        .build(log_dir)
        .map_err(|e| {
            AppError::ConfigError(format!("初始化日志文件失败: {}", e))
        })?;

    let (non_blocking, file_guard) = tracing_appender::non_blocking(file_appender);

    let log_level = match config.log_level.as_str() {
        "debug" => Level::DEBUG,
        "info" => Level::INFO,
        "warn" => Level::WARN,
        "error" => Level::ERROR,
        "trace" => Level::TRACE,
        _ => Level::INFO,
    };

    let env_filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new(format!("{},tokio=warn,sqlx=warn", log_level)));

    let stdout_layer = fmt::layer()
        .with_target(true)
        .with_level(true)
        .with_thread_ids(true)
        .with_file(true)
        .with_line_number(true)
        .with_timer(fmt::time::UtcTime::rfc_3339());

    let file_layer = fmt::layer()
        .json()
        .with_target(true)
        .with_level(true)
        .with_thread_ids(true)
        .with_file(true)
        .with_line_number(true)
        .with_timer(fmt::time::UtcTime::rfc_3339())
        .with_writer(non_blocking);

    tracing_subscriber::registry()
        .with(env_filter)
        .with(stdout_layer)
        .with(file_layer)
        .try_init()
        .map_err(|e| {
            AppError::ConfigError(format!("初始化日志系统失败: {}", e))
        })?;

    tracing::info!("日志系统初始化完成，日志目录: {}", config.log_dir);
    tracing::info!("日志级别: {}", log_level);

    Ok(LoggingGuard {
        _file_guard: file_guard,
    })
}

pub struct LogArchiveManager {
    log_dir: String,
    archive_dir: String,
    retention_days: u32,
}

impl LogArchiveManager {
    pub fn new(log_dir: String, archive_dir: String, retention_days: u32) -> Self {
        Self {
            log_dir,
            archive_dir,
            retention_days,
        }
    }

    pub async fn run_archive_task(&self) -> Result<(), AppError> {
        tracing::info!("开始执行日志归档任务");

        let archive_path = Path::new(&self.archive_dir);
        std::fs::create_dir_all(archive_path).map_err(|e| {
            AppError::InternalError(format!("创建归档目录失败: {}", e))
        })?;

        let now = chrono::Utc::now();
        let cutoff = now - chrono::Duration::days(self.retention_days as i64);

        let log_dir = Path::new(&self.log_dir);
        let mut archived_count = 0;
        let mut deleted_count = 0;

        let entries = std::fs::read_dir(log_dir).map_err(|e| {
            AppError::InternalError(format!("读取日志目录失败: {}", e))
        })?;

        for entry in entries {
            let entry = entry.map_err(|e| {
                AppError::InternalError(format!("读取目录项失败: {}", e))
            })?;
            
            let path = entry.path();
            if path.is_file() {
                let metadata = entry.metadata().map_err(|e| {
                    AppError::InternalError(format!("获取文件元数据失败: {}", e))
                })?;
                
                let modified = metadata.modified().map_err(|e| {
                    AppError::InternalError(format!("获取文件修改时间失败: {}", e))
                })?;
                
                let modified_time: chrono::DateTime<chrono::Utc> = modified.into();
                
                if modified_time < cutoff {
                    let file_name = path.file_name().unwrap().to_string_lossy().to_string();
                    let archive_file = archive_path.join(format!(
                        "{}.{}.gz",
                        file_name,
                        modified_time.format("%Y%m%d")
                    ));

                    tracing::info!("归档日志文件: {} -> {}", file_name, archive_file.display());
                    
                    let content = std::fs::read(&path).map_err(|e| {
                        AppError::InternalError(format!("读取日志文件失败: {}", e))
                    })?;

                    let mut encoder = flate2::write::GzEncoder::new(
                        Vec::new(),
                        flate2::Compression::default(),
                    );
                    std::io::Write::write_all(&mut encoder, &content).map_err(|e| {
                        AppError::InternalError(format!("压缩日志文件失败: {}", e))
                    })?;
                    let compressed = encoder.finish().map_err(|e| {
                        AppError::InternalError(format!("完成压缩失败: {}", e))
                    })?;

                    std::fs::write(&archive_file, compressed).map_err(|e| {
                        AppError::InternalError(format!("写入归档文件失败: {}", e))
                    })?;

                    std::fs::remove_file(&path).map_err(|e| {
                        AppError::InternalError(format!("删除原始日志文件失败: {}", e))
                    })?;

                    archived_count += 1;
                }

                if modified_time < (cutoff - chrono::Duration::days(30)) {
                    let archive_file = archive_path.join(path.file_name().unwrap());
                    if archive_file.exists() {
                        tracing::info!("删除过期归档: {}", archive_file.display());
                        std::fs::remove_file(&archive_file).map_err(|e| {
                            AppError::InternalError(format!("删除过期归档失败: {}", e))
                        })?;
                        deleted_count += 1;
                    }
                }
            }
        }

        tracing::info!(
            "日志归档任务完成，归档文件: {} 个，删除过期归档: {} 个",
            archived_count,
            deleted_count
        );

        Ok(())
    }
}

mod flate2 {
    pub use flate2::*;
}
