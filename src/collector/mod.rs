pub mod ring_buffer;
pub mod file_tailer;
pub mod syslog;

use crate::config::{AppConfig, ConfigHandle, FileSourceConfig, SyslogSourceConfig};
use crate::collector::file_tailer::FileTailer;
use crate::collector::ring_buffer::RingBuffer;
use crate::collector::syslog::SyslogListener;
use std::sync::Arc;
use tracing::{error, info};

pub use ring_buffer::{RingBufferHandle, BufferedEntry};

pub struct CollectorManager {
    pub ring_buffer: Arc<RingBuffer>,
}

impl CollectorManager {
    pub fn new(config: &AppConfig) -> Self {
        let ring_buffer = Arc::new(RingBuffer::new(
            config.pipeline.ring_buffer_size,
            config.pipeline.ring_buffer_seconds,
        ));
        Self { ring_buffer }
    }

    pub fn buffer_handle(&self) -> RingBufferHandle {
        self.ring_buffer.handle()
    }

    pub async fn start_all(
        &self,
        config_handle: ConfigHandle,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let config = config_handle.read().await.clone();
        let handle = self.buffer_handle();

        for fs in &config.source.file_sources {
            info!("Starting file tailer: {} ({})", fs.name, fs.glob_pattern);
            let tailer = FileTailer::new(fs.clone(), handle.clone());
            match tailer.start() {
                Ok(_jh) => {
                    info!("File tailer started: {}", fs.name);
                }
                Err(e) => {
                    error!("Failed to start file tailer {}: {}", fs.name, e);
                }
            }
        }

        for ss in &config.source.syslog_sources {
            info!("Starting syslog listener: {} ({}:{})", ss.name, ss.host, ss.port);
            let listener = SyslogListener::new(ss.clone(), handle.clone());
            match listener.start() {
                Ok(_jh) => {
                    info!("Syslog listener started: {}", ss.name);
                }
                Err(e) => {
                    error!("Failed to start syslog listener {}: {}", ss.name, e);
                }
            }
        }

        Ok(())
    }
}
