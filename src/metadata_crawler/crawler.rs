use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock, Semaphore};
use crate::models::StreamSQLError;
use super::schema::*;

#[async_trait]
pub trait MetadataProvider: Send + Sync {
    async fn get_data_source_info(&self) -> Result<DataSourceInfo, StreamSQLError>;
    async fn list_schemas(&self) -> Result<Vec<String>, StreamSQLError>;
    async fn list_tables(&self, schema: &str) -> Result<Vec<String>, StreamSQLError>;
    async fn get_table_schema(
        &self,
        schema: &str,
        table: &str,
    ) -> Result<TableSchema, StreamSQLError>;
    async fn get_table_statistics(
        &self,
        schema: &str,
        table: &str,
    ) -> Result<SchemaStatistics, StreamSQLError>;
    async fn get_sample_data(
        &self,
        schema: &str,
        table: &str,
        limit: usize,
    ) -> Result<DataSample, StreamSQLError>;
    async fn test_connection(&self) -> Result<(), StreamSQLError>;
}

pub struct MetadataCrawler {
    providers: Arc<RwLock<HashMap<String, Box<dyn MetadataProvider>>>>,
    schemas: Arc<RwLock<HashMap<String, TableSchema>>>,
    statistics: Arc<RwLock<HashMap<String, SchemaStatistics>>>,
    samples: Arc<RwLock<HashMap<String, DataSample>>>,
    concurrency_limit: Arc<Semaphore>,
    crawling: Arc<Mutex<bool>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CrawlerConfig {
    pub data_source_id: String,
    pub crawl_schemas: Option<Vec<String>>,
    pub crawl_tables: Option<Vec<String>>,
    pub sample_size: usize,
    pub collect_statistics: bool,
    pub collect_samples: bool,
    pub max_concurrent_tables: usize,
    pub refresh_interval_ms: Option<u64>,
}

impl Default for CrawlerConfig {
    fn default() -> Self {
        Self {
            data_source_id: "default".to_string(),
            crawl_schemas: None,
            crawl_tables: None,
            sample_size: 100,
            collect_statistics: true,
            collect_samples: true,
            max_concurrent_tables: 10,
            refresh_interval_ms: None,
        }
    }
}

impl MetadataCrawler {
    pub fn new() -> Self {
        Self::with_concurrency(10)
    }

    pub fn with_concurrency(max_concurrent: usize) -> Self {
        Self {
            providers: Arc::new(RwLock::new(HashMap::new())),
            schemas: Arc::new(RwLock::new(HashMap::new())),
            statistics: Arc::new(RwLock::new(HashMap::new())),
            samples: Arc::new(RwLock::new(HashMap::new())),
            concurrency_limit: Arc::new(Semaphore::new(max_concurrent)),
            crawling: Arc::new(Mutex::new(false)),
        }
    }

    pub async fn register_provider(
        &self,
        id: impl Into<String>,
        provider: Box<dyn MetadataProvider>,
    ) {
        self.providers
            .write()
            .await
            .insert(id.into(), provider);
    }

    pub async fn list_providers(&self) -> Vec<String> {
        self.providers.read().await.keys().cloned().collect()
    }

    pub async fn crawl(&self, config: CrawlerConfig) -> Result<CrawlResult, StreamSQLError> {
        let start = std::time::Instant::now();
        let started_at = chrono::Utc::now();

        *self.crawling.lock().await = true;

        let provider = self
            .providers
            .read()
            .await
            .get(&config.data_source_id)
            .ok_or_else(|| {
                StreamSQLError::Metadata(format!(
                    "Data source {} not found",
                    config.data_source_id
                ))
            })?
            .as_ref();

        provider.test_connection().await?;

        let schemas = match &config.crawl_schemas {
            Some(s) => s.clone(),
            None => provider.list_schemas().await?,
        };

        let mut tables_discovered = 0;
        let mut new_tables = 0;
        let mut updated_tables = 0;
        let mut errors = Vec::new();

        for schema in &schemas {
            let tables = match &config.crawl_tables {
                Some(t) => t.clone(),
                None => match provider.list_tables(schema).await {
                    Ok(t) => t,
                    Err(e) => {
                        errors.push(format!("Error listing tables in schema {}: {}", schema, e));
                        continue;
                    }
                },
            };

            tables_discovered += tables.len();

            let mut handles = Vec::new();
            let semaphore = self.concurrency_limit.clone();

            for table in tables {
                let permit = semaphore.clone().acquire_owned().await.unwrap();
                let provider_clone = unsafe {
                    std::mem::transmute::<
                        &dyn MetadataProvider,
                        &'static dyn MetadataProvider,
                    >(provider)
                };
                let config_clone = config.clone();
                let schema_clone = schema.clone();
                let table_clone = table.clone();
                let schemas_arc = self.schemas.clone();
                let stats_arc = self.statistics.clone();
                let samples_arc = self.samples.clone();

                let handle = tokio::spawn(async move {
                    let _permit = permit;
                    let key = format!("{}.{}", schema_clone, table_clone);
                    let mut result = (false, false);

                    match provider_clone.get_table_schema(&schema_clone, &table_clone).await {
                        Ok(schema) => {
                            let is_new = !schemas_arc.read().await.contains_key(&key);
                            schemas_arc.write().await.insert(key.clone(), schema);
                            
                            if is_new {
                                result.0 = true;
                            } else {
                                result.1 = true;
                            }
                        }
                        Err(e) => {
                            tracing::error!("Error fetching schema for {}.{}: {}", schema_clone, table_clone, e);
                        }
                    }

                    if config_clone.collect_statistics {
                        if let Ok(stats) = provider_clone
                            .get_table_statistics(&schema_clone, &table_clone)
                            .await
                        {
                            stats_arc.write().await.insert(key.clone(), stats);
                        }
                    }

                    if config_clone.collect_samples {
                        if let Ok(sample) = provider_clone
                            .get_sample_data(&schema_clone, &table_clone, config_clone.sample_size)
                            .await
                        {
                            samples_arc.write().await.insert(key.clone(), sample);
                        }
                    }

                    result
                });

                handles.push(handle);
            }

            for handle in handles {
                match handle.await {
                    Ok((is_new, is_updated)) => {
                        if is_new {
                            new_tables += 1;
                        }
                        if is_updated {
                            updated_tables += 1;
                        }
                    }
                    Err(e) => {
                        errors.push(format!("Task error: {}", e));
                    }
                }
            }
        }

        *self.crawling.lock().await = false;

        Ok(CrawlResult {
            data_source_id: config.data_source_id,
            tables_discovered,
            new_tables,
            updated_tables,
            errors,
            started_at,
            completed_at: chrono::Utc::now(),
            duration_ms: start.elapsed().as_millis() as u64,
        })
    }

    pub async fn get_table_schema(&self, key: &str) -> Option<TableSchema> {
        self.schemas.read().await.get(key).cloned()
    }

    pub async fn get_statistics(&self, key: &str) -> Option<SchemaStatistics> {
        self.statistics.read().await.get(key).cloned()
    }

    pub async fn get_sample(&self, key: &str) -> Option<DataSample> {
        self.samples.read().await.get(key).cloned()
    }

    pub async fn list_cached_tables(&self) -> Vec<String> {
        self.schemas.read().await.keys().cloned().collect()
    }

    pub async fn is_crawling(&self) -> bool {
        *self.crawling.lock().await
    }

    pub async fn search_tables(&self, query: &str) -> Vec<TableSchema> {
        let lower_query = query.to_lowercase();
        self.schemas
            .read()
            .await
            .values()
            .filter(|s| {
                s.name.to_lowercase().contains(&lower_query)
                    || s.comment
                        .as_ref()
                        .map(|c| c.to_lowercase().contains(&lower_query))
                        .unwrap_or(false)
            })
            .cloned()
            .collect()
    }

    pub async fn search_columns(&self, query: &str) -> Vec<(String, ColumnSchema)> {
        let lower_query = query.to_lowercase();
        let mut results = Vec::new();

        for (table_key, schema) in self.schemas.read().await.iter() {
            for col in &schema.columns {
                if col.name.to_lowercase().contains(&lower_query)
                    || col.comment
                        .as_ref()
                        .map(|c| c.to_lowercase().contains(&lower_query))
                        .unwrap_or(false)
                {
                    results.push((table_key.clone(), col.clone()));
                }
            }
        }

        results
    }
}

impl Default for MetadataCrawler {
    fn default() -> Self {
        Self::new()
    }
}
