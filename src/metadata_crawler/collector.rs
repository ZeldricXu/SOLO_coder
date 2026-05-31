use std::collections::HashMap;
use serde::{Deserialize, Serialize};
use crate::models::StreamSQLError;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetadataSearchQuery {
    pub query: String,
    pub search_tables: bool,
    pub search_columns: bool,
    pub search_comments: bool,
    pub filters: Option<SearchFilters>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchFilters {
    pub data_source: Option<String>,
    pub schema: Option<String>,
    pub table_type: Option<String>,
    pub column_types: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchResult {
    pub results: Vec<SearchResultItem>,
    pub total_count: usize,
    pub elapsed_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchResultItem {
    pub item_type: String,
    pub name: String,
    pub fully_qualified_name: String,
    pub description: Option<String>,
    pub data_source: String,
    pub schema: String,
    pub score: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LineageImpact {
    pub table_name: String,
    pub downstream_tables: Vec<String>,
    pub downstream_columns: Vec<(String, String)>,
    pub affected_pipelines: Vec<String>,
}

pub struct MetadataCollector {
    crawler: super::MetadataCrawler,
}

impl MetadataCollector {
    pub fn new() -> Self {
        Self {
            crawler: super::MetadataCrawler::new(),
        }
    }

    pub fn with_crawler(crawler: super::MetadataCrawler) -> Self {
        Self { crawler }
    }

    pub fn crawler(&self) -> &super::MetadataCrawler {
        &self.crawler
    }

    pub async fn search(&self, query: &MetadataSearchQuery) -> Result<SearchResult, StreamSQLError> {
        let start = std::time::Instant::now();
        let mut results = Vec::new();

        let lower_query = query.query.to_lowercase();

        if query.search_tables {
            let tables = self.crawler.search_tables(&lower_query).await;
            for table in tables {
                results.push(SearchResultItem {
                    item_type: "table".to_string(),
                    name: table.name.clone(),
                    fully_qualified_name: table.fully_qualified_name(),
                    description: table.comment.clone(),
                    data_source: table.catalog.clone(),
                    schema: table.schema.clone(),
                    score: calculate_score(&lower_query, &table.name, &table.comment),
                });
            }
        }

        if query.search_columns {
            let columns = self.crawler.search_columns(&lower_query).await;
            for (table_key, column) in columns {
                let parts: Vec<&str> = table_key.split('.').collect();
                let schema = if parts.len() >= 2 { parts[0] } else { "" };
                
                results.push(SearchResultItem {
                    item_type: "column".to_string(),
                    name: column.name.clone(),
                    fully_qualified_name: format!("{}.{}", table_key, column.name),
                    description: column.comment.clone(),
                    data_source: "default".to_string(),
                    schema: schema.to_string(),
                    score: calculate_score(&lower_query, &column.name, &column.comment),
                });
            }
        }

        results.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
        let total_count = results.len();

        Ok(SearchResult {
            results,
            total_count,
            elapsed_ms: start.elapsed().as_millis() as u64,
        })
    }

    pub async fn get_table_summary(&self, table_key: &str) -> Result<TableSummary, StreamSQLError> {
        let schema = self
            .crawler
            .get_table_schema(table_key)
            .await
            .ok_or_else(|| StreamSQLError::Metadata(format!("Table {} not found", table_key)))?;

        let stats = self.crawler.get_statistics(table_key).await;
        let sample = self.crawler.get_sample(table_key).await;

        Ok(TableSummary {
            table: schema,
            statistics: stats,
            sample: sample.map(|s| s.rows),
        })
    }

    pub async fn get_column_lineage(
        &self,
        table_key: &str,
        column_name: &str,
    ) -> Result<ColumnLineageInfo, StreamSQLError> {
        Ok(ColumnLineageInfo {
            table: table_key.to_string(),
            column: column_name.to_string(),
            upstream: Vec::new(),
            downstream: Vec::new(),
            transformations: Vec::new(),
        })
    }

    pub async fn compare_schemas(
        &self,
        table_key1: &str,
        table_key2: &str,
    ) -> Result<SchemaDiff, StreamSQLError> {
        let schema1 = self
            .crawler
            .get_table_schema(table_key1)
            .await
            .ok_or_else(|| StreamSQLError::Metadata(format!("Table {} not found", table_key1)))?;

        let schema2 = self
            .crawler
            .get_table_schema(table_key2)
            .await
            .ok_or_else(|| StreamSQLError::Metadata(format!("Table {} not found", table_key2)))?;

        let mut added = Vec::new();
        let mut removed = Vec::new();
        let mut modified = Vec::new();

        let columns1: HashMap<String, _> = schema1
            .columns
            .iter()
            .map(|c| (c.name.clone(), c.clone()))
            .collect();

        let columns2: HashMap<String, _> = schema2
            .columns
            .iter()
            .map(|c| (c.name.clone(), c.clone()))
            .collect();

        for (name, _) in &columns1 {
            if !columns2.contains_key(name) {
                removed.push(name.clone());
            }
        }

        for (name, _) in &columns2 {
            if !columns1.contains_key(name) {
                added.push(name.clone());
            }
        }

        for (name, col1) in &columns1 {
            if let Some(col2) = columns2.get(name) {
                if col1.data_type != col2.data_type
                    || col1.nullable != col2.nullable
                {
                    modified.push(ColumnDiff {
                        column: name.clone(),
                        old_type: col1.data_type.clone(),
                        new_type: col2.data_type.clone(),
                        old_nullable: col1.nullable,
                        new_nullable: col2.nullable,
                    });
                }
            }
        }

        Ok(SchemaDiff {
            table1: table_key1.to_string(),
            table2: table_key2.to_string(),
            added_columns: added,
            removed_columns: removed,
            modified_columns: modified,
        })
    }
}

fn calculate_score(query: &str, name: &str, comment: &Option<String>) -> f64 {
    let mut score = 0.0;
    let lower_name = name.to_lowercase();

    if lower_name == query {
        score += 100.0;
    } else if lower_name.starts_with(query) {
        score += 80.0;
    } else if lower_name.contains(query) {
        score += 50.0;
    }

    if let Some(comment) = comment {
        let lower_comment = comment.to_lowercase();
        if lower_comment.contains(query) {
            score += 30.0;
        }
    }

    score
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TableSummary {
    pub table: super::schema::TableSchema,
    pub statistics: Option<super::schema::SchemaStatistics>,
    pub sample: Option<Vec<serde_json::Value>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColumnLineageInfo {
    pub table: String,
    pub column: String,
    pub upstream: Vec<String>,
    pub downstream: Vec<String>,
    pub transformations: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchemaDiff {
    pub table1: String,
    pub table2: String,
    pub added_columns: Vec<String>,
    pub removed_columns: Vec<String>,
    pub modified_columns: Vec<ColumnDiff>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ColumnDiff {
    pub column: String,
    pub old_type: String,
    pub new_type: String,
    pub old_nullable: bool,
    pub new_nullable: bool,
}

impl Default for MetadataCollector {
    fn default() -> Self {
        Self::new()
    }
}
