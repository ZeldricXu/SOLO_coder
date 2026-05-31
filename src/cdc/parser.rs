use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use crate::models::StreamSQLError;
use super::event::{ChangeEvent, ChangeType, TableSchema};

#[async_trait]
pub trait BinlogParser: Send + Sync {
    async fn connect(&mut self) -> Result<(), StreamSQLError>;
    async fn disconnect(&mut self) -> Result<(), StreamSQLError>;
    async fn poll(&mut self, timeout_ms: u64) -> Result<Vec<ChangeEvent>, StreamSQLError>;
    async fn get_schema(&self, database: &str, table: &str) -> Result<Option<TableSchema>, StreamSQLError>;
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BinlogPosition {
    pub file: String,
    pub position: u64,
    pub timestamp: Option<i64>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SourceType {
    Mysql,
    Postgresql,
    Mongodb,
    Oracle,
    SqlServer,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ParserConfig {
    pub source_type: SourceType,
    pub connection_string: String,
    pub tables: Vec<String>,
    pub start_position: Option<BinlogPosition>,
    pub server_id: Option<u32>,
}

pub struct MockBinlogParser {
    config: ParserConfig,
    connected: bool,
    event_queue: std::collections::VecDeque<ChangeEvent>,
}

impl MockBinlogParser {
    pub fn new(config: ParserConfig) -> Self {
        Self {
            config,
            connected: false,
            event_queue: std::collections::VecDeque::new(),
        }
    }

    pub fn push_event(&mut self, event: ChangeEvent) {
        self.event_queue.push_back(event);
    }

    pub fn generate_mock_events(&mut self, count: usize) {
        for i in 0..count {
            let change_type = match i % 3 {
                0 => ChangeType::Insert,
                1 => ChangeType::Update,
                _ => ChangeType::Delete,
            };
            
            let table = self.config.tables[i % self.config.tables.len()].clone();
            let mut event = ChangeEvent::new(
                change_type,
                "test_db",
                &table,
            );

            let data = serde_json::json!({
                "id": i,
                "name": format!("record_{}", i),
                "value": i * 100,
                "created_at": chrono::Utc::now().to_rfc3339(),
            });

            event = match change_type {
                ChangeType::Insert => event.with_after(data),
                ChangeType::Update => event.with_before(
                    serde_json::json!({
                        "id": i,
                        "name": format!("old_record_{}", i),
                        "value": (i - 1) * 100,
                    })
                ).with_after(data),
                ChangeType::Delete => event.with_before(data),
                _ => event,
            };

            event.source.binlog_file = Some(format!("binlog.00000{}", (i / 1000) + 1));
            event.source.binlog_position = Some((i as u64) * 128);
            event.source.xid = Some(i as u64 + 1000);

            self.event_queue.push_back(event);
        }
    }
}

#[async_trait]
impl BinlogParser for MockBinlogParser {
    async fn connect(&mut self) -> Result<(), StreamSQLError> {
        tracing::info!("Connecting to mock binlog parser");
        self.connected = true;
        Ok(())
    }

    async fn disconnect(&mut self) -> Result<(), StreamSQLError> {
        tracing::info!("Disconnecting mock binlog parser");
        self.connected = false;
        Ok(())
    }

    async fn poll(&mut self, _timeout_ms: u64) -> Result<Vec<ChangeEvent>, StreamSQLError> {
        if !self.connected {
            return Err(StreamSQLError::Cdc("Not connected".into()));
        }

        let events: Vec<ChangeEvent> = self.event_queue.drain(..).collect();
        Ok(events)
    }

    async fn get_schema(&self, database: &str, table: &str) -> Result<Option<TableSchema>, StreamSQLError> {
        Ok(Some(TableSchema {
            database: database.to_string(),
            table: table.to_string(),
            columns: vec![
                super::event::ColumnInfo {
                    name: "id".to_string(),
                    data_type: "bigint".to_string(),
                    nullable: false,
                    position: 0,
                },
                super::event::ColumnInfo {
                    name: "name".to_string(),
                    data_type: "varchar".to_string(),
                    nullable: true,
                    position: 1,
                },
                super::event::ColumnInfo {
                    name: "value".to_string(),
                    data_type: "int".to_string(),
                    nullable: true,
                    position: 2,
                },
                super::event::ColumnInfo {
                    name: "created_at".to_string(),
                    data_type: "timestamp".to_string(),
                    nullable: true,
                    position: 3,
                },
            ],
            primary_keys: vec!["id".to_string()],
        }))
    }
}
