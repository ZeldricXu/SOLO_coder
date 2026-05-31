use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use crate::models::StreamSQLError;
use super::event::{ChangeEvent, EventBatch};

#[async_trait]
pub trait EventOutputAdapter: Send + Sync {
    async fn init(&mut self) -> Result<(), StreamSQLError>;
    async fn send_event(&mut self, event: &ChangeEvent) -> Result<(), StreamSQLError>;
    async fn send_batch(&mut self, batch: &EventBatch) -> Result<(), StreamSQLError>;
    async fn flush(&mut self) -> Result<(), StreamSQLError>;
    async fn close(&mut self) -> Result<(), StreamSQLError>;
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum OutputFormat {
    Json,
    Avro,
    Protobuf,
    Csv,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OutputConfig {
    pub format: OutputFormat,
    pub destination: OutputDestination,
    pub topic: Option<String>,
    pub batch_size: usize,
    pub flush_interval_ms: u64,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum OutputDestination {
    Stdout,
    Kafka,
    Pulsar,
    File,
    Webhook,
}

pub struct StandardOutputAdapter {
    config: OutputConfig,
}

impl StandardOutputAdapter {
    pub fn new(config: OutputConfig) -> Self {
        Self { config }
    }

    fn format_event(&self, event: &ChangeEvent) -> Result<String, StreamSQLError> {
        match self.config.format {
            OutputFormat::Json => Ok(serde_json::to_string(event)?),
            OutputFormat::Csv => self.event_to_csv(event),
            _ => Err(StreamSQLError::Serialization(format!(
                "Format {:?} not implemented for stdout adapter",
                self.config.format
            ))),
        }
    }

    fn event_to_csv(&self, event: &ChangeEvent) -> Result<String, StreamSQLError> {
        Ok(format!(
            "{},{},{},{},{}",
            event.event_id,
            serde_json::to_string(&event.change_type)?,
            event.source.database,
            event.source.table,
            event.timestamp.to_rfc3339()
        ))
    }
}

#[async_trait]
impl EventOutputAdapter for StandardOutputAdapter {
    async fn init(&mut self) -> Result<(), StreamSQLError> {
        tracing::info!("Initializing stdout output adapter");
        Ok(())
    }

    async fn send_event(&mut self, event: &ChangeEvent) -> Result<(), StreamSQLError> {
        let formatted = self.format_event(event)?;
        println!("{}", formatted);
        Ok(())
    }

    async fn send_batch(&mut self, batch: &EventBatch) -> Result<(), StreamSQLError> {
        for event in &batch.events {
            self.send_event(event).await?;
        }
        Ok(())
    }

    async fn flush(&mut self) -> Result<(), StreamSQLError> {
        Ok(())
    }

    async fn close(&mut self) -> Result<(), StreamSQLError> {
        tracing::info!("Closing stdout output adapter");
        Ok(())
    }
}

pub struct InMemoryOutputAdapter {
    events: std::sync::Arc<tokio::sync::Mutex<Vec<ChangeEvent>>>,
}

impl InMemoryOutputAdapter {
    pub fn new() -> Self {
        Self {
            events: std::sync::Arc::new(tokio::sync::Mutex::new(Vec::new())),
        }
    }

    pub async fn get_events(&self) -> Vec<ChangeEvent> {
        self.events.lock().await.clone()
    }

    pub async fn clear(&self) {
        self.events.lock().await.clear();
    }
}

#[async_trait]
impl EventOutputAdapter for InMemoryOutputAdapter {
    async fn init(&mut self) -> Result<(), StreamSQLError> {
        Ok(())
    }

    async fn send_event(&mut self, event: &ChangeEvent) -> Result<(), StreamSQLError> {
        self.events.lock().await.push(event.clone());
        Ok(())
    }

    async fn send_batch(&mut self, batch: &EventBatch) -> Result<(), StreamSQLError> {
        self.events.lock().await.extend(batch.events.clone());
        Ok(())
    }

    async fn flush(&mut self) -> Result<(), StreamSQLError> {
        Ok(())
    }

    async fn close(&mut self) -> Result<(), StreamSQLError> {
        Ok(())
    }
}
