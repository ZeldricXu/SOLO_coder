use anyhow::{Context, Result};
use chrono::Utc;
use memmap2::Mmap;
use std::collections::HashMap;
use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::mpsc::Sender;
use tokio::sync::RwLock;
use tracing::{debug, error, info, warn};

use common::log::{LogTailConfig, LogEvent};

use crate::multiline::MultilineMerger;
use crate::parser::LogParser;

#[derive(Debug, Clone)]
pub struct FilePosition {
    pub path: PathBuf,
    pub inode: u64,
    pub offset: u64,
    pub last_modified: std::time::SystemTime,
}

pub struct FileWatcher {
    configs: Vec<LogTailConfig>,
    hostname: String,
    positions: Arc<RwLock<HashMap<PathBuf, FilePosition>>>,
    event_sender: Sender<LogEvent>,
    parser: LogParser,
    poll_interval_ms: u64,
}

impl FileWatcher {
    pub fn new(
        configs: Vec<LogTailConfig>,
        hostname: String,
        event_sender: Sender<LogEvent>,
    ) -> Self {
        Self {
            configs,
            hostname,
            positions: Arc::new(RwLock::new(HashMap::new())),
            event_sender,
            parser: LogParser::new(),
            poll_interval_ms: 100,
        }
    }

    pub async fn run(&mut self) -> Result<()> {
        info!("Starting file watcher with {} configs", self.configs.len());
        
        for config in &self.configs {
            self.discover_files(config).await?;
        }

        loop {
            for config in &self.configs {
                if let Err(e) = self.check_and_read_files(config).await {
                    error!("Error processing files for {}: {}", config.service_name, e);
                }
            }
            tokio::time::sleep(tokio::time::Duration::from_millis(self.poll_interval_ms)).await;
        }
    }

    async fn discover_files(&self, config: &LogTailConfig) -> Result<()> {
        let paths = glob::glob(&config.path_pattern)
            .context(format!("Invalid glob pattern: {}", config.path_pattern))?;

        for path in paths {
            match path {
                Ok(path) => {
                    if path.is_file() {
                        self.init_file_position(&path, config.start_from_beginning).await?;
                        debug!("Discovered file: {:?}", path);
                    }
                }
                Err(e) => warn!("Glob error: {}", e),
            }
        }
        Ok(())
    }

    async fn init_file_position(&self, path: &Path, from_beginning: bool) -> Result<()> {
        let metadata = fs::metadata(path)?;
        let inode = get_inode(&metadata);
        let offset = if from_beginning { 0 } else { metadata.len() };

        let mut positions = self.positions.write().await;
        positions.insert(
            path.to_path_buf(),
            FilePosition {
                path: path.to_path_buf(),
                inode,
                offset,
                last_modified: metadata.modified()?,
            },
        );
        Ok(())
    }

    async fn check_and_read_files(&self, config: &LogTailConfig) -> Result<()> {
        self.discover_files(config).await?;

        let paths: Vec<PathBuf> = {
            let positions = self.positions.read().await;
            positions.keys().cloned().collect()
        };

        for path in paths {
            if !path.exists() {
                let mut positions = self.positions.write().await;
                positions.remove(&path);
                debug!("File removed: {:?}", path);
                continue;
            }

            let metadata = fs::metadata(&path)?;
            let current_size = metadata.len();
            let current_inode = get_inode(&metadata);

            let mut need_update = false;
            {
                let positions = self.positions.read().await;
                if let Some(pos) = positions.get(&path) {
                    if pos.inode != current_inode {
                        info!("File rotated: {:?}", path);
                        need_update = true;
                    } else if current_size > pos.offset {
                        need_update = true;
                    }
                }
            }

            if need_update {
                self.read_file(&path, config, current_size, current_inode).await?;
            }
        }
        Ok(())
    }

    async fn read_file(
        &self,
        path: &Path,
        config: &LogTailConfig,
        current_size: u64,
        current_inode: u64,
    ) -> Result<()> {
        let file = File::open(path)?;
        let mmap = unsafe { Mmap::map(&file) }?;

        let mut positions = self.positions.write().await;
        let pos = positions.entry(path.to_path_buf()).or_insert(FilePosition {
            path: path.to_path_buf(),
            inode: current_inode,
            offset: 0,
            last_modified: std::time::SystemTime::now(),
        });

        if pos.inode != current_inode {
            pos.inode = current_inode;
            pos.offset = 0;
        }

        let start = pos.offset as usize;
        let end = current_size as usize;

        if start >= end {
            return Ok(());
        }

        let data = &mmap[start..end];
        let content = String::from_utf8_lossy(data);

        let multiline_pattern = config.multiline_pattern.clone();
        let mut merger = MultilineMerger::new(multiline_pattern.as_deref());
        let mut current_line_start = 0usize;
        let bytes = content.as_bytes();
        let mut lines_with_offsets = Vec::new();

        for (i, &byte) in bytes.iter().enumerate() {
            if byte == b'\n' {
                let line_end = i;
                let line_bytes = &bytes[current_line_start..line_end];
                let line = String::from_utf8_lossy(line_bytes).into_owned();
                let absolute_offset = (start + current_line_start) as u64;
                lines_with_offsets.push((line, absolute_offset));
                current_line_start = i + 1;
            }
        }

        if current_line_start < bytes.len() {
            let line_bytes = &bytes[current_line_start..];
            let line = String::from_utf8_lossy(line_bytes).into_owned();
            let absolute_offset = (start + current_line_start) as u64;
            lines_with_offsets.push((line, absolute_offset));
        }

        for (line, offset) in lines_with_offsets {
            if let Some(full_message) = merger.add_line(&line) {
                if let Some(mut event) = self.parser.parse(&full_message, &config.service_name) {
                    event.hostname = self.hostname.clone();
                    event.source_file = path.to_string_lossy().to_string();
                    event.source_offset = offset;
                    event.timestamp = Utc::now();

                    if self.event_sender.send(event).await.is_err() {
                        warn!("Event channel closed, stopping file reading");
                        return Ok(());
                    }
                }
            }
        }

        if let Some(last_message) = merger.flush() {
            if let Some(mut event) = self.parser.parse(&last_message, &config.service_name) {
                event.hostname = self.hostname.clone();
                event.source_file = path.to_string_lossy().to_string();
                event.source_offset = (start + current_line_start) as u64;
                event.timestamp = Utc::now();

                let _ = self.event_sender.send(event).await;
            }
        }

        pos.offset = current_size;
        pos.last_modified = std::time::SystemTime::now();

        debug!("Read {} bytes from {:?}", end - start, path);
        Ok(())
    }
}

#[cfg(unix)]
fn get_inode(metadata: &std::fs::Metadata) -> u64 {
    use std::os::unix::fs::MetadataExt;
    metadata.ino()
}

#[cfg(not(unix))]
fn get_inode(_metadata: &std::fs::Metadata) -> u64 {
    0
}
