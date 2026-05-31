use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use crate::models::StreamSQLError;
use super::event::{ChangeEvent, EventBatch};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineConfig {
    pub name: String,
    pub source_tables: Vec<String>,
    pub batch_size: usize,
    pub poll_interval_ms: u64,
    pub enabled: bool,
}

impl Default for PipelineConfig {
    fn default() -> Self {
        Self {
            name: "default".to_string(),
            source_tables: Vec::new(),
            batch_size: 100,
            poll_interval_ms: 1000,
            enabled: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineStats {
    pub pipeline_name: String,
    pub events_processed: u64,
    pub batches_sent: u64,
    pub errors: u64,
    pub last_event_timestamp: Option<chrono::DateTime<chrono::Utc>>,
    pub running: bool,
}

impl Default for PipelineStats {
    fn default() -> Self {
        Self {
            pipeline_name: "default".to_string(),
            events_processed: 0,
            batches_sent: 0,
            errors: 0,
            last_event_timestamp: None,
            running: false,
        }
    }
}

#[async_trait]
pub trait EventProcessor: Send + Sync {
    async fn process(&mut self, event: &ChangeEvent) -> Result<ChangeEvent, StreamSQLError>;
    async fn process_batch(&mut self, events: &[ChangeEvent]) -> Result<Vec<ChangeEvent>, StreamSQLError> {
        let mut processed = Vec::with_capacity(events.len());
        for event in events {
            processed.push(self.process(event).await?);
        }
        Ok(processed)
    }
}

pub struct SchemaValidationProcessor {
    strict: bool,
}

impl SchemaValidationProcessor {
    pub fn new(strict: bool) -> Self {
        Self { strict }
    }
}

#[async_trait]
impl EventProcessor for SchemaValidationProcessor {
    async fn process(&mut self, event: &ChangeEvent) -> Result<ChangeEvent, StreamSQLError> {
        if self.strict {
            if let (None, None) = (&event.data.before, &event.data.after) {
                return Err(StreamSQLError::Validation(
                    "Event must have either before or after data".into(),
                ));
            }
        }
        Ok(event.clone())
    }
}

pub struct FilterProcessor {
    filter: Box<dyn Fn(&ChangeEvent) -> bool + Send + Sync>,
}

impl FilterProcessor {
    pub fn new<F>(filter: F) -> Self
    where
        F: Fn(&ChangeEvent) -> bool + Send + Sync + 'static,
    {
        Self {
            filter: Box::new(filter),
        }
    }
}

#[async_trait]
impl EventProcessor for FilterProcessor {
    async fn process(&mut self, event: &ChangeEvent) -> Result<ChangeEvent, StreamSQLError> {
        if (self.filter)(event) {
            Ok(event.clone())
        } else {
            Err(StreamSQLError::Cdc("Event filtered out".into()))
        }
    }

    async fn process_batch(&mut self, events: &[ChangeEvent]) -> Result<Vec<ChangeEvent>, StreamSQLError> {
        Ok(events
            .iter()
            .filter(|e| (self.filter)(e))
            .cloned()
            .collect())
    }
}

pub struct TableFilterProcessor {
    tables: std::collections::HashSet<String>,
}

impl TableFilterProcessor {
    pub fn new(tables: Vec<String>) -> Self {
        Self {
            tables: tables.into_iter().collect(),
        }
    }
}

#[async_trait]
impl EventProcessor for TableFilterProcessor {
    async fn process(&mut self, event: &ChangeEvent) -> Result<ChangeEvent, StreamSQLError> {
        if self.tables.contains(&event.source.table) {
            Ok(event.clone())
        } else {
            Err(StreamSQLError::Cdc(format!(
                "Table {} not in whitelist",
                event.source.table
            )))
        }
    }

    async fn process_batch(&mut self, events: &[ChangeEvent]) -> Result<Vec<ChangeEvent>, StreamSQLError> {
        Ok(events
            .iter()
            .filter(|e| self.tables.contains(&e.source.table))
            .cloned()
            .collect())
    }
}
