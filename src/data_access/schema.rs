use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchemaVersion {
    pub version: u64,
    pub name: String,
    pub description: String,
    pub applied_at: DateTime<Utc>,
    pub checksum: String,
    pub status: SchemaStatus,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SchemaStatus {
    Pending,
    Applied,
    Failed,
    RolledBack,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MigrationDefinition {
    pub version: u64,
    pub name: String,
    pub description: String,
    pub up_sql: String,
    pub down_sql: String,
}

impl MigrationDefinition {
    pub fn new(
        version: u64,
        name: impl Into<String>,
        description: impl Into<String>,
        up_sql: impl Into<String>,
        down_sql: impl Into<String>,
    ) -> Self {
        Self {
            version,
            name: name.into(),
            description: description.into(),
            up_sql: up_sql.into(),
            down_sql: down_sql.into(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MigrationResult {
    pub version: u64,
    pub success: bool,
    pub executed_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}
