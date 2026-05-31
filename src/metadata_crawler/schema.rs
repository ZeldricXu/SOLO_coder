use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TableSchema {
    pub catalog: String,
    pub schema: String,
    pub name: String,
    pub columns: Vec<ColumnSchema>,
    pub primary_keys: Vec<String>,
    pub foreign_keys: Vec<ForeignKey>,
    pub table_type: TableType,
    pub comment: Option<String>,
    pub row_count: Option<u64>,
    pub size_bytes: Option<u64>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub updated_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum TableType {
    Table,
    View,
    MaterializedView,
    Temporary,
    External,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColumnSchema {
    pub name: String,
    pub data_type: String,
    pub native_type: Option<String>,
    pub position: usize,
    pub nullable: bool,
    pub default_value: Option<String>,
    pub comment: Option<String>,
    pub is_primary_key: bool,
    pub is_foreign_key: bool,
    pub is_unique: bool,
    pub is_indexed: bool,
    pub precision: Option<i32>,
    pub scale: Option<i32>,
    pub length: Option<i32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ForeignKey {
    pub name: String,
    pub columns: Vec<String>,
    pub referenced_table: String,
    pub referenced_columns: Vec<String>,
    pub update_rule: Option<String>,
    pub delete_rule: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IndexSchema {
    pub name: String,
    pub columns: Vec<String>,
    pub is_unique: bool,
    pub is_primary: bool,
    pub index_type: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchemaStatistics {
    pub table_name: String,
    pub column_statistics: HashMap<String, ColumnStatistics>,
    pub last_updated: chrono::DateTime<chrono::Utc>,
}

use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColumnStatistics {
    pub null_count: u64,
    pub non_null_count: u64,
    pub distinct_count: u64,
    pub min_value: Option<String>,
    pub max_value: Option<String>,
    pub average_length: Option<f64>,
    pub top_values: Vec<ValueFrequency>,
    pub histogram: Option<Histogram>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValueFrequency {
    pub value: String,
    pub frequency: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Histogram {
    pub buckets: Vec<HistogramBucket>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistogramBucket {
    pub lower_bound: String,
    pub upper_bound: String,
    pub count: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataSample {
    pub sample_id: String,
    pub table_name: String,
    pub rows: Vec<serde_json::Value>,
    pub sample_size: usize,
    pub total_rows: Option<u64>,
    pub sampled_at: chrono::DateTime<chrono::Utc>,
    pub sampling_strategy: SamplingStrategy,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SamplingStrategy {
    Random,
    FirstN,
    LastN,
    Stratified,
    Uniform,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataSourceInfo {
    pub id: String,
    pub name: String,
    pub source_type: DataSourceType,
    pub connection_string: String,
    pub description: Option<String>,
    pub schemas: Vec<SchemaInfo>,
    pub last_scanned_at: Option<chrono::DateTime<chrono::Utc>>,
    pub status: DataSourceStatus,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum DataSourceType {
    Mysql,
    Postgresql,
    Oracle,
    SqlServer,
    Mongodb,
    Cassandra,
    Elasticsearch,
    S3,
    Kafka,
    Generic,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum DataSourceStatus {
    Active,
    Inactive,
    Error,
    Scanning,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchemaInfo {
    pub name: String,
    pub tables: Vec<String>,
    pub views: Vec<String>,
    pub table_count: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CrawlResult {
    pub data_source_id: String,
    pub tables_discovered: usize,
    pub new_tables: usize,
    pub updated_tables: usize,
    pub errors: Vec<String>,
    pub started_at: chrono::DateTime<chrono::Utc>,
    pub completed_at: chrono::DateTime<chrono::Utc>,
    pub duration_ms: u64,
}

impl TableSchema {
    pub fn fully_qualified_name(&self) -> String {
        if self.schema.is_empty() {
            format!("{}.{}", self.catalog, self.name)
        } else {
            format!("{}.{}.{}", self.catalog, self.schema, self.name)
        }
    }

    pub fn get_column(&self, name: &str) -> Option<&ColumnSchema> {
        self.columns.iter().find(|c| c.name == name)
    }

    pub fn get_primary_key_columns(&self) -> Vec<&ColumnSchema> {
        self.columns
            .iter()
            .filter(|c| c.is_primary_key)
            .collect()
    }
}

impl Default for ColumnStatistics {
    fn default() -> Self {
        Self {
            null_count: 0,
            non_null_count: 0,
            distinct_count: 0,
            min_value: None,
            max_value: None,
            average_length: None,
            top_values: Vec::new(),
            histogram: None,
        }
    }
}
