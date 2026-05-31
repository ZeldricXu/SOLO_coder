use crate::types::{
    AppError, AppResult, ColumnSchema, ColumnStatistics, CrawlSchedule, DataSourceSchema,
    DataSourceType, HistogramBucket, MetadataCrawlerConfig, TableSchema, TableStatistics,
    generate_id, now_utc,
};
use async_trait::async_trait;
use dashmap::DashMap;
use job_scheduler::{Job, JobScheduler};
use rand::{self, Rng};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Mutex;

#[async_trait]
pub trait DataSourceConnector: Send + Sync {
    async fn connect(&mut self) -> AppResult<()>;
    async fn disconnect(&mut self) -> AppResult<()>;
    async fn list_tables(&self, schema: Option<&str>) -> AppResult<Vec<String>>;
    async fn get_table_schema(
        &self,
        table: &str,
        schema: Option<&str>,
    ) -> AppResult<Vec<ColumnSchema>>;
    async fn get_table_statistics(
        &self,
        table: &str,
        schema: Option<&str>,
    ) -> AppResult<TableStatistics>;
    async fn get_column_statistics(
        &self,
        table: &str,
        column: &str,
        schema: Option<&str>,
        histogram_buckets: u32,
    ) -> AppResult<ColumnStatistics>;
    async fn get_sample_data(
        &self,
        table: &str,
        schema: Option<&str>,
        limit: u32,
    ) -> AppResult<Vec<HashMap<String, serde_json::Value>>>;
    async fn get_row_count(&self, table: &str, schema: Option<&str>) -> AppResult<u64>;
    async fn get_table_size(&self, table: &str, schema: Option<&str>) -> AppResult<u64>;
    fn source_type(&self) -> DataSourceType;
}

pub struct PostgresConnector {
    connection_string: String,
    is_connected: bool,
}

impl PostgresConnector {
    pub fn new(connection_string: &str) -> Self {
        Self {
            connection_string: connection_string.to_string(),
            is_connected: false,
        }
    }
}

#[async_trait]
impl DataSourceConnector for PostgresConnector {
    async fn connect(&mut self) -> AppResult<()> {
        tracing::info!(target: "crawler.postgres", "连接到PostgreSQL: {}", self.connection_string);
        self.is_connected = true;
        Ok(())
    }

    async fn disconnect(&mut self) -> AppResult<()> {
        tracing::info!(target: "crawler.postgres", "断开PostgreSQL连接");
        self.is_connected = false;
        Ok(())
    }

    async fn list_tables(&self, schema: Option<&str>) -> AppResult<Vec<String>> {
        if !self.is_connected {
            return Err(AppError::MetadataCrawlerError(
                "未连接到PostgreSQL".to_string(),
            ));
        }

        let schema_name = schema.unwrap_or("public");
        Ok(vec![
            format!("{}.users", schema_name),
            format!("{}.orders", schema_name),
            format!("{}.products", schema_name),
            format!("{}.transactions", schema_name),
        ])
    }

    async fn get_table_schema(
        &self,
        table: &str,
        schema: Option<&str>,
    ) -> AppResult<Vec<ColumnSchema>> {
        if !self.is_connected {
            return Err(AppError::MetadataCrawlerError(
                "未连接到PostgreSQL".to_string(),
            ));
        }

        let columns = match table {
            t if t.ends_with("users") => vec![
                ColumnSchema {
                    column_name: "id".to_string(),
                    data_type: "bigint".to_string(),
                    nullable: false,
                    primary_key: true,
                    foreign_key: None,
                    statistics: ColumnStatistics::default(),
                },
                ColumnSchema {
                    column_name: "name".to_string(),
                    data_type: "varchar(255)".to_string(),
                    nullable: false,
                    primary_key: false,
                    foreign_key: None,
                    statistics: ColumnStatistics::default(),
                },
                ColumnSchema {
                    column_name: "email".to_string(),
                    data_type: "varchar(255)".to_string(),
                    nullable: false,
                    primary_key: false,
                    foreign_key: None,
                    statistics: ColumnStatistics::default(),
                },
                ColumnSchema {
                    column_name: "created_at".to_string(),
                    data_type: "timestamp".to_string(),
                    nullable: false,
                    primary_key: false,
                    foreign_key: None,
                    statistics: ColumnStatistics::default(),
                },
            ],
            t if t.ends_with("orders") => vec![
                ColumnSchema {
                    column_name: "id".to_string(),
                    data_type: "bigint".to_string(),
                    nullable: false,
                    primary_key: true,
                    foreign_key: None,
                    statistics: ColumnStatistics::default(),
                },
                ColumnSchema {
                    column_name: "user_id".to_string(),
                    data_type: "bigint".to_string(),
                    nullable: false,
                    primary_key: false,
                    foreign_key: Some(crate::types::ForeignKeyInfo {
                        foreign_table: "users".to_string(),
                        foreign_column: "id".to_string(),
                    }),
                    statistics: ColumnStatistics::default(),
                },
                ColumnSchema {
                    column_name: "amount".to_string(),
                    data_type: "decimal(10,2)".to_string(),
                    nullable: false,
                    primary_key: false,
                    foreign_key: None,
                    statistics: ColumnStatistics::default(),
                },
                ColumnSchema {
                    column_name: "status".to_string(),
                    data_type: "varchar(50)".to_string(),
                    nullable: false,
                    primary_key: false,
                    foreign_key: None,
                    statistics: ColumnStatistics::default(),
                },
            ],
            _ => vec![
                ColumnSchema {
                    column_name: "id".to_string(),
                    data_type: "bigint".to_string(),
                    nullable: false,
                    primary_key: true,
                    foreign_key: None,
                    statistics: ColumnStatistics::default(),
                },
                ColumnSchema {
                    column_name: "name".to_string(),
                    data_type: "varchar(255)".to_string(),
                    nullable: true,
                    primary_key: false,
                    foreign_key: None,
                    statistics: ColumnStatistics::default(),
                },
            ],
        };

        Ok(columns)
    }

    async fn get_table_statistics(
        &self,
        table: &str,
        schema: Option<&str>,
    ) -> AppResult<TableStatistics> {
        if !self.is_connected {
            return Err(AppError::MetadataCrawlerError(
                "未连接到PostgreSQL".to_string(),
            ));
        }

        Ok(TableStatistics {
            last_analyzed: Some(now_utc()),
            distinct_count: Some(rand::random::<u64>() % 10000),
            null_count: Some(rand::random::<u64>() % 100),
        })
    }

    async fn get_column_statistics(
        &self,
        table: &str,
        column: &str,
        schema: Option<&str>,
        histogram_buckets: u32,
    ) -> AppResult<ColumnStatistics> {
        if !self.is_connected {
            return Err(AppError::MetadataCrawlerError(
                "未连接到PostgreSQL".to_string(),
            ));
        }

        let is_numeric = column == "id" || column == "amount" || column.ends_with("_id");

        let (min_val, max_val, avg_val) = if is_numeric {
            let min = rand::random::<f64>() * 100.0;
            let max = min + rand::random::<f64>() * 10000.0;
            let avg = min + (max - min) / 2.0;
            (
                Some(serde_json::json!(min)),
                Some(serde_json::json!(max)),
                Some(avg),
            )
        } else {
            (
                Some(serde_json::json!("a")),
                Some(serde_json::json!("z")),
                None,
            )
        };

        let distinct_count = rand::random::<u64>() % 5000;
        let null_count = rand::random::<u64>() % 50;

        let mut top_values = Vec::new();
        for i in 0..5 {
            top_values.push((serde_json::json!(format!("value_{}", i)), rand::random::<u64>() % 1000));
        }

        let mut histogram = Vec::new();
        if is_numeric {
            let min_f = min_val.as_ref().and_then(|v| v.as_f64()).unwrap_or(0.0);
            let max_f = max_val.as_ref().and_then(|v| v.as_f64()).unwrap_or(100.0);
            let bucket_width = (max_f - min_f) / histogram_buckets as f64;

            for i in 0..histogram_buckets {
                histogram.push(HistogramBucket {
                    lower_bound: min_f + i as f64 * bucket_width,
                    upper_bound: min_f + (i + 1) as f64 * bucket_width,
                    count: rand::random::<u64>() % 1000,
                });
            }
        }

        Ok(ColumnStatistics {
            min_value: min_val,
            max_value: max_val,
            avg_value: avg_val,
            distinct_count: Some(distinct_count),
            null_count: Some(null_count),
            top_values,
            histogram: if histogram.is_empty() { None } else { Some(histogram) },
        })
    }

    async fn get_sample_data(
        &self,
        table: &str,
        schema: Option<&str>,
        limit: u32,
    ) -> AppResult<Vec<HashMap<String, serde_json::Value>>> {
        if !self.is_connected {
            return Err(AppError::MetadataCrawlerError(
                "未连接到PostgreSQL".to_string(),
            ));
        }

        let mut samples = Vec::new();
        for i in 0..limit.min(100) {
            let mut row = HashMap::new();
            row.insert("id".to_string(), serde_json::json!(i as i64));

            if table.ends_with("users") {
                row.insert("name".to_string(), serde_json::json!(format!("user_{}", i)));
                row.insert(
                    "email".to_string(),
                    serde_json::json!(format!("user_{}@example.com", i)),
                );
                row.insert(
                    "created_at".to_string(),
                    serde_json::json!(now_utc().to_rfc3339()),
                );
            } else if table.ends_with("orders") {
                row.insert("user_id".to_string(), serde_json::json!((i % 100) as i64));
                row.insert(
                    "amount".to_string(),
                    serde_json::json!(rand::random::<f64>() * 1000.0),
                );
                row.insert("status".to_string(), serde_json::json!("completed"));
            }

            samples.push(row);
        }

        Ok(samples)
    }

    async fn get_row_count(&self, table: &str, schema: Option<&str>) -> AppResult<u64> {
        if !self.is_connected {
            return Err(AppError::MetadataCrawlerError(
                "未连接到PostgreSQL".to_string(),
            ));
        }

        Ok(rand::random::<u64>() % 1000000)
    }

    async fn get_table_size(&self, table: &str, schema: Option<&str>) -> AppResult<u64> {
        if !self.is_connected {
            return Err(AppError::MetadataCrawlerError(
                "未连接到PostgreSQL".to_string(),
            ));
        }

        Ok(rand::random::<u64>() % 1000000000)
    }

    fn source_type(&self) -> DataSourceType {
        DataSourceType::Postgres
    }
}

pub struct MysqlConnector {
    connection_string: String,
    is_connected: bool,
}

impl MysqlConnector {
    pub fn new(connection_string: &str) -> Self {
        Self {
            connection_string: connection_string.to_string(),
            is_connected: false,
        }
    }
}

#[async_trait]
impl DataSourceConnector for MysqlConnector {
    async fn connect(&mut self) -> AppResult<()> {
        tracing::info!(target: "crawler.mysql", "连接到MySQL: {}", self.connection_string);
        self.is_connected = true;
        Ok(())
    }

    async fn disconnect(&mut self) -> AppResult<()> {
        tracing::info!(target: "crawler.mysql", "断开MySQL连接");
        self.is_connected = false;
        Ok(())
    }

    async fn list_tables(&self, schema: Option<&str>) -> AppResult<Vec<String>> {
        Ok(vec![
            "users".to_string(),
            "orders".to_string(),
            "products".to_string(),
        ])
    }

    async fn get_table_schema(
        &self,
        table: &str,
        schema: Option<&str>,
    ) -> AppResult<Vec<ColumnSchema>> {
        Ok(vec![
            ColumnSchema {
                column_name: "id".to_string(),
                data_type: "BIGINT".to_string(),
                nullable: false,
                primary_key: true,
                foreign_key: None,
                statistics: ColumnStatistics::default(),
            },
            ColumnSchema {
                column_name: "name".to_string(),
                data_type: "VARCHAR(255)".to_string(),
                nullable: true,
                primary_key: false,
                foreign_key: None,
                statistics: ColumnStatistics::default(),
            },
        ])
    }

    async fn get_table_statistics(
        &self,
        table: &str,
        schema: Option<&str>,
    ) -> AppResult<TableStatistics> {
        Ok(TableStatistics {
            last_analyzed: Some(now_utc()),
            distinct_count: Some(rand::random::<u64>() % 10000),
            null_count: Some(rand::random::<u64>() % 100),
        })
    }

    async fn get_column_statistics(
        &self,
        table: &str,
        column: &str,
        schema: Option<&str>,
        histogram_buckets: u32,
    ) -> AppResult<ColumnStatistics> {
        Ok(ColumnStatistics::default())
    }

    async fn get_sample_data(
        &self,
        table: &str,
        schema: Option<&str>,
        limit: u32,
    ) -> AppResult<Vec<HashMap<String, serde_json::Value>>> {
        let mut samples = Vec::new();
        for i in 0..limit.min(100) {
            let mut row = HashMap::new();
            row.insert("id".to_string(), serde_json::json!(i as i64));
            row.insert("name".to_string(), serde_json::json!(format!("sample_{}", i)));
            samples.push(row);
        }
        Ok(samples)
    }

    async fn get_row_count(&self, table: &str, schema: Option<&str>) -> AppResult<u64> {
        Ok(rand::random::<u64>() % 1000000)
    }

    async fn get_table_size(&self, table: &str, schema: Option<&str>) -> AppResult<u64> {
        Ok(rand::random::<u64>() % 1000000000)
    }

    fn source_type(&self) -> DataSourceType {
        DataSourceType::Mysql
    }
}

impl Default for ColumnStatistics {
    fn default() -> Self {
        Self {
            min_value: None,
            max_value: None,
            avg_value: None,
            distinct_count: None,
            null_count: None,
            top_values: Vec::new(),
            histogram: None,
        }
    }
}

impl Default for TableStatistics {
    fn default() -> Self {
        Self {
            last_analyzed: None,
            distinct_count: None,
            null_count: None,
        }
    }
}

pub struct MetadataCrawler {
    config: MetadataCrawlerConfig,
    connectors: DashMap<String, Arc<tokio::sync::Mutex<Box<dyn DataSourceConnector>>>>,
    schemas: DashMap<String, DataSourceSchema>,
    schedules: DashMap<String, CrawlSchedule>,
    scheduler: Arc<Mutex<JobScheduler>>,
    job_ids: DashMap<String, uuid::Uuid>,
}

impl MetadataCrawler {
    pub fn new(config: MetadataCrawlerConfig) -> Self {
        Self {
            config,
            connectors: DashMap::new(),
            schemas: DashMap::new(),
            schedules: DashMap::new(),
            scheduler: Arc::new(Mutex::new(JobScheduler::new())),
            job_ids: DashMap::new(),
        }
    }

    pub fn register_source(
        &self,
        source_id: &str,
        source_type: DataSourceType,
        connection_string: &str,
    ) -> AppResult<String> {
        let connector: Box<dyn DataSourceConnector> = match source_type {
            DataSourceType::Postgres => {
                Box::new(PostgresConnector::new(connection_string))
            }
            DataSourceType::Mysql => {
                Box::new(MysqlConnector::new(connection_string))
            }
            _ => {
                return Err(AppError::MetadataCrawlerError(format!(
                    "不支持的数据源类型: {:?}",
                    source_type
                )));
            }
        };

        let id = if source_id.is_empty() {
            generate_id("src")
        } else {
            source_id.to_string()
        };

        self.connectors
            .insert(id.clone(), Arc::new(tokio::sync::Mutex::new(connector)));

        Ok(id)
    }

    pub async fn crawl_source(&self, source_id: &str) -> AppResult<DataSourceSchema> {
        let connector_arc = self
            .connectors
            .get(source_id)
            .ok_or_else(|| AppError::NotFound(format!("数据源不存在: {}", source_id)))?
            .clone();

        let mut connector = connector_arc.lock().await;

        connector.connect().await?;

        let tables = connector.list_tables(Some("public")).await?;
        let mut table_schemas = Vec::new();

        for table in &tables {
            let columns = connector
                .get_table_schema(table, Some("public"))
                .await?;

            let mut columns_with_stats = Vec::new();
            for col in columns {
                let stats = if self.config.statistics_enabled {
                    connector
                        .get_column_statistics(
                            table,
                            &col.column_name,
                            Some("public"),
                            self.config.histogram_buckets,
                        )
                        .await?
                } else {
                    ColumnStatistics::default()
                };

                columns_with_stats.push(ColumnSchema {
                    statistics: stats,
                    ..col
                });
            }

            let table_stats = if self.config.statistics_enabled {
                connector
                    .get_table_statistics(table, Some("public"))
                    .await?
            } else {
                TableStatistics::default()
            };

            let row_count = connector.get_row_count(table, Some("public")).await?;
            let size_bytes = connector.get_table_size(table, Some("public")).await?;

            let sample_data = connector
                .get_sample_data(table, Some("public"), self.config.sample_data_count)
                .await?;

            let schema_parts: Vec<&str> = table.split('.').collect();
            let (schema_name, table_name) = if schema_parts.len() > 1 {
                (schema_parts[0].to_string(), schema_parts[1].to_string())
            } else {
                ("public".to_string(), table.clone())
            };

            table_schemas.push(TableSchema {
                table_name,
                schema_name,
                columns: columns_with_stats,
                row_count: Some(row_count),
                size_bytes: Some(size_bytes),
                statistics: table_stats,
                sample_data,
            });
        }

        connector.disconnect().await?;

        let source_type = connector.source_type();

        let schema = DataSourceSchema {
            source_id: source_id.to_string(),
            source_type,
            connection_string: "***".to_string(),
            tables: table_schemas,
            scanned_at: now_utc(),
        };

        self.schemas.insert(source_id.to_string(), schema.clone());

        if let Some(mut schedule) = self.schedules.get_mut(source_id) {
            schedule.last_run = Some(now_utc());
        }

        Ok(schema)
    }

    pub fn get_schema(&self, source_id: &str) -> Option<DataSourceSchema> {
        self.schemas.get(source_id).map(|s| s.clone())
    }

    pub fn list_schemas(&self) -> Vec<DataSourceSchema> {
        self.schemas.iter().map(|s| s.clone()).collect()
    }

    pub fn create_schedule(
        &self,
        source_id: &str,
        cron_expression: &str,
        enabled: bool,
    ) -> CrawlSchedule {
        let schedule = CrawlSchedule {
            schedule_id: generate_id("sched"),
            source_id: source_id.to_string(),
            cron_expression: cron_expression.to_string(),
            enabled,
            last_run: None,
            next_run: None,
        };

        self.schedules
            .insert(source_id.to_string(), schedule.clone());
        schedule
    }

    pub async fn schedule_crawl(&self, source_id: &str) -> AppResult<()> {
        let schedule = self
            .schedules
            .get(source_id)
            .ok_or_else(|| AppError::NotFound(format!("调度不存在: {}", source_id)))?
            .clone();

        if !schedule.enabled {
            return Err(AppError::MetadataCrawlerError(
                "调度已禁用".to_string(),
            ));
        }

        let self_arc = Arc::new(self.clone());
        let source_id_clone = source_id.to_string();

        let job = Job::new(schedule.cron_expression.parse().map_err(|e| {
            AppError::MetadataCrawlerError(format!("无效的Cron表达式: {}", e))
        })?, move || {
            let self_for_task = self_arc.clone();
            let source_for_task = source_id_clone.clone();
            tokio::spawn(async move {
                let _ = self_for_task.crawl_source(&source_for_task).await;
            });
        });

        let mut scheduler = self.scheduler.lock().await;
        let job_id = scheduler.add(job);
        self.job_ids.insert(source_id.to_string(), job_id);

        tracing::info!(
            target: "metadata_crawler",
            "已调度数据源采集: {}, schedule={}",
            source_id,
            schedule.cron_expression
        );

        Ok(())
    }

    pub async fn start_scheduler(&self) -> AppResult<()> {
        for schedule in self.schedules.iter() {
            if schedule.enabled {
                let _ = self.schedule_crawl(&schedule.source_id).await;
            }
        }

        let scheduler_clone = self.scheduler.clone();
        tokio::spawn(async move {
            loop {
                let mut s = scheduler_clone.lock().await;
                s.tick();
                drop(s);
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        });

        tracing::info!(target: "metadata_crawler", "元数据采集调度器已启动");
        Ok(())
    }

    pub async fn get_table_schema_by_name(
        &self,
        source_id: &str,
        table_name: &str,
    ) -> AppResult<TableSchema> {
        let schema = self
            .get_schema(source_id)
            .ok_or_else(|| AppError::NotFound(format!("数据源不存在: {}", source_id)))?;

        for table in &schema.tables {
            if table.table_name == table_name {
                return Ok(table.clone());
            }
        }

        Err(AppError::NotFound(format!("表不存在: {}", table_name)))
    }

    pub async fn search_columns(
        &self,
        column_name_pattern: &str,
    ) -> Vec<(String, String, ColumnSchema)> {
        let re = regex::Regex::new(column_name_pattern).unwrap();
        let mut results = Vec::new();

        for schema in self.list_schemas() {
            for table in &schema.tables {
                for col in &table.columns {
                    if re.is_match(&col.column_name) {
                        results.push((
                            schema.source_id.clone(),
                            table.table_name.clone(),
                            col.clone(),
                        ));
                    }
                }
            }
        }

        results
    }

    pub fn list_sources(&self) -> Vec<String> {
        self.connectors
            .iter()
            .map(|entry| entry.key().clone())
            .collect()
    }

    pub fn config(&self) -> &MetadataCrawlerConfig {
        &self.config
    }
}

impl Clone for MetadataCrawler {
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            connectors: self.connectors.clone(),
            schemas: self.schemas.clone(),
            schedules: self.schedules.clone(),
            scheduler: self.scheduler.clone(),
            job_ids: self.job_ids.clone(),
        }
    }
}

pub fn create_metadata_crawler(config: MetadataCrawlerConfig) -> MetadataCrawler {
    MetadataCrawler::new(config)
}
