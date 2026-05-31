use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ChangeType {
    Insert,
    Update,
    Delete,
    Ddl,
    Heartbeat,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RowData {
    pub before: Option<serde_json::Value>,
    pub after: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SourceInfo {
    pub database: String,
    pub table: String,
    pub binlog_file: Option<String>,
    pub binlog_position: Option<u64>,
    pub timestamp: Option<DateTime<Utc>>,
    pub xid: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChangeEvent {
    pub event_id: String,
    pub change_type: ChangeType,
    pub source: SourceInfo,
    pub data: RowData,
    pub schema: Option<TableSchema>,
    pub timestamp: DateTime<Utc>,
    pub transaction_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TableSchema {
    pub database: String,
    pub table: String,
    pub columns: Vec<ColumnInfo>,
    pub primary_keys: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColumnInfo {
    pub name: String,
    pub data_type: String,
    pub nullable: bool,
    pub position: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EventBatch {
    pub batch_id: String,
    pub events: Vec<ChangeEvent>,
    pub created_at: DateTime<Utc>,
    pub count: usize,
}

impl ChangeEvent {
    pub fn new(change_type: ChangeType, database: impl Into<String>, table: impl Into<String>) -> Self {
        Self {
            event_id: crate::models::IdGenerator::generate("evt"),
            change_type,
            source: SourceInfo {
                database: database.into(),
                table: table.into(),
                binlog_file: None,
                binlog_position: None,
                timestamp: None,
                xid: None,
            },
            data: RowData {
                before: None,
                after: None,
            },
            schema: None,
            timestamp: Utc::now(),
            transaction_id: None,
        }
    }

    pub fn with_before(mut self, data: serde_json::Value) -> Self {
        self.data.before = Some(data);
        self
    }

    pub fn with_after(mut self, data: serde_json::Value) -> Self {
        self.data.after = Some(data);
        self
    }

    pub fn with_schema(mut self, schema: TableSchema) -> Self {
        self.schema = Some(schema);
        self
    }

    pub fn with_transaction(mut self, tx_id: impl Into<String>) -> Self {
        self.transaction_id = Some(tx_id.into());
        self
    }
}

impl EventBatch {
    pub fn new(events: Vec<ChangeEvent>) -> Self {
        let count = events.len();
        Self {
            batch_id: crate::models::IdGenerator::generate("batch"),
            events,
            created_at: Utc::now(),
            count,
        }
    }

    pub fn is_empty(&self) -> bool {
        self.events.is_empty()
    }
}
