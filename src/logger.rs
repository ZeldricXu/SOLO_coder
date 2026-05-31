use crate::config::LoggerConfig;
use crate::error::SystemError;
use std::fs::{self, File};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use tracing::Level;
use tracing_appender::non_blocking::{NonBlocking, WorkerGuard};
use tracing_subscriber::fmt::format::FmtSpan;
use tracing_subscriber::{fmt, EnvFilter};

pub struct Logger;

impl Logger {
    pub fn init(config: &LoggerConfig) -> Result<(), SystemError> {
        if let Some(parent) = config.file_path.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| SystemError::IoError(e))?;
        }

        let file_appender = RotatingFileAppender::new(
            config.file_path.clone(),
            config.max_size_mb * 1024 * 1024,
            config.max_files,
        )?;

        let (non_blocking, _guard) = NonBlocking::new(file_appender);

        let filter = EnvFilter::try_from_default_env()
            .unwrap_or_else(|_| EnvFilter::new(format!("task_tracker={}", config.level)));

        let builder = fmt()
            .with_env_filter(filter)
            .with_span_events(FmtSpan::FULL)
            .with_target(true)
            .with_thread_ids(true)
            .with_line_number(true);

        if config.json_format {
            builder
                .json()
                .with_writer(non_blocking)
                .init();
        } else {
            builder
                .with_writer(non_blocking)
                .init();
        }

        Ok(())
    }

    pub fn init_simple(level: Level) {
        tracing_subscriber::fmt()
            .with_env_filter(EnvFilter::new(format!("task_tracker={}", level)))
            .with_target(true)
            .with_line_number(true)
            .init();
    }
}

pub struct RotatingFileAppender {
    path: PathBuf,
    max_size: u64,
    max_files: u32,
    current_size: u64,
    file: Arc<Mutex<File>>,
}

impl RotatingFileAppender {
    pub fn new(path: PathBuf, max_size: u64, max_files: u32) -> Result<Self, SystemError> {
        let current_size = if path.exists() {
            fs::metadata(&path)
                .map(|m| m.len())
                .unwrap_or(0)
        } else {
            0
        };

        let file = Self::open_file(&path)?;

        Ok(Self {
            path,
            max_size,
            max_files,
            current_size,
            file: Arc::new(Mutex::new(file)),
        })
    }

    fn open_file(path: &Path) -> Result<File, SystemError> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        File::options()
            .create(true)
            .append(true)
            .open(path)
            .map_err(SystemError::IoError)
    }

    fn rotate(&self) -> Result<(), SystemError> {
        for i in (1..self.max_files).rev() {
            let old_path = self.rotated_path(i);
            let new_path = self.rotated_path(i + 1);
            if old_path.exists() {
                fs::rename(&old_path, &new_path)?;
            }
        }

        let rotated_path = self.rotated_path(1);
        fs::rename(&self.path, &rotated_path)?;

        let mut file = self.file.lock().unwrap();
        *file = Self::open_file(&self.path)?;
        Ok(())
    }

    fn rotated_path(&self, index: u32) -> PathBuf {
        let file_name = self.path.file_name().unwrap().to_string_lossy().to_string();
        let ext = self.path.extension().map(|e| e.to_string_lossy().to_string());
        let base = match &ext {
            Some(e) => file_name.trim_end_matches(&format!(".{}", e)).to_string(),
            None => file_name,
        };
        match ext {
            Some(e) => self.path.with_file_name(format!("{}.{}.{}", base, index, e)),
            None => self.path.with_file_name(format!("{}.{}", base, index)),
        }
    }

    pub fn archive_logs(&self, archive_path: &Path) -> Result<(), SystemError> {
        let mut tar_file = File::create(archive_path)?;
        let mut tar_builder = tar::Builder::new(tar_file);

        for i in 1..=self.max_files {
            let log_path = self.rotated_path(i);
            if log_path.exists() {
                let file_name = log_path.file_name().unwrap().to_string_lossy().to_string();
                let mut file = File::open(&log_path)?;
                tar_builder.append_file(&file_name, &mut file)?;
            }
        }

        tar_builder.finish()?;
        Ok(())
    }

    pub fn cleanup_old_logs(&self) -> Result<(), SystemError> {
        for i in self.max_files.. {
            let log_path = self.rotated_path(i);
            if log_path.exists() {
                fs::remove_file(&log_path)?;
            } else {
                break;
            }
        }
        Ok(())
    }
}

impl io::Write for RotatingFileAppender {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let bytes_written = {
            let mut file = self.file.lock().unwrap();
            file.write(buf)?
        };
        self.current_size += bytes_written as u64;

        if self.current_size >= self.max_size {
            if let Err(e) = self.rotate() {
                eprintln!("日志轮转失败: {}", e);
            } else {
                self.current_size = 0;
            }
        }

        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        let mut file = self.file.lock().unwrap();
        file.flush()
    }
}

pub struct LogWorkerGuard {
    _inner: Option<WorkerGuard>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_rotating_appender() {
        let dir = tempdir().unwrap();
        let log_path = dir.path().join("test.log");

        let mut appender = RotatingFileAppender::new(
            log_path.clone(),
            100,
            3,
        ).unwrap();

        for i in 0..100 {
            writeln!(appender, "Log line {}", i).unwrap();
        }

        assert!(log_path.exists());
        assert!(appender.rotated_path(1).exists());
    }
}
