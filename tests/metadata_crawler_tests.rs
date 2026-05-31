#[cfg(test)]
mod metadata_crawler_tests {
    use enterprise_middleware::metadata_crawler::{
        CrawlSchedule, DataSourceConnector, MetadataCrawler, MetadataCrawlerConfig,
        PostgresConnector, MysqlConnector, SampleDataCollector, StatisticsCollector,
    };
    use enterprise_middleware::types::{
        ColumnSchema, ColumnStatistics, DataSourceSchema, DataSourceType, TableSchema, TableStatistics,
    };
    use std::collections::HashMap;

    #[test]
    fn test_crawler_config_validation() {
        let valid_config = MetadataCrawlerConfig {
            enabled: true,
            schedule_pool_size: 4,
            sample_data_count: 100,
            histogram_buckets: 20,
            statistics_enabled: true,
        };

        assert!(valid_config.validate().is_ok());

        let invalid_config = MetadataCrawlerConfig {
            schedule_pool_size: 0,
            ..valid_config.clone()
        };
        assert!(invalid_config.validate().is_err());

        let invalid_sample = MetadataCrawlerConfig {
            sample_data_count: 0,
            ..valid_config.clone()
        };
        assert!(invalid_sample.validate().is_err());

        let invalid_buckets = MetadataCrawlerConfig {
            histogram_buckets: 2,
            ..valid_config
        };
        assert!(invalid_buckets.validate().is_err());
    }

    #[test]
    fn test_crawl_schedule() {
        let schedule = CrawlSchedule {
            source_id: "source_1".to_string(),
            cron_expression: "0 0 * * * *".to_string(),
            enabled: true,
            last_run_at: None,
            next_run_at: None,
        };

        assert!(schedule.is_due_now());
        assert!(schedule.validate_cron().is_ok());

        let invalid_schedule = CrawlSchedule {
            cron_expression: "invalid cron".to_string(),
            ..schedule
        };
        assert!(invalid_schedule.validate_cron().is_err());
    }

    #[test]
    fn test_column_schema_validation() {
        let valid_column = ColumnSchema {
            name: "user_id".to_string(),
            data_type: "integer".to_string(),
            is_nullable: false,
            default_value: Some("nextval('users_id_seq')".to_string()),
            character_maximum_length: None,
            numeric_precision: Some(32),
            numeric_scale: Some(0),
            is_primary_key: true,
            is_foreign_key: false,
            comment: Some("用户唯一标识".to_string()),
            statistics: None,
        };

        assert!(valid_column.validate().is_ok());

        let invalid_column = ColumnSchema {
            name: "".to_string(),
            data_type: "".to_string(),
            is_nullable: false,
            default_value: None,
            character_maximum_length: None,
            numeric_precision: None,
            numeric_scale: None,
            is_primary_key: false,
            is_foreign_key: false,
            comment: None,
            statistics: None,
        };

        let errors = invalid_column.validate().unwrap_err();
        assert!(!errors.is_empty());
    }

    #[test]
    fn test_table_schema() {
        let columns = vec![
            ColumnSchema {
                name: "id".to_string(),
                data_type: "integer".to_string(),
                is_nullable: false,
                default_value: None,
                character_maximum_length: None,
                numeric_precision: Some(32),
                numeric_scale: Some(0),
                is_primary_key: true,
                is_foreign_key: false,
                comment: None,
                statistics: None,
            },
            ColumnSchema {
                name: "name".to_string(),
                data_type: "varchar".to_string(),
                is_nullable: false,
                default_value: None,
                character_maximum_length: Some(100),
                numeric_precision: None,
                numeric_scale: None,
                is_primary_key: false,
                is_foreign_key: false,
                comment: None,
                statistics: None,
            },
        ];

        let table = TableSchema {
            name: "users".to_string(),
            schema: "public".to_string(),
            columns: columns.clone(),
            primary_key: vec!["id".to_string()],
            foreign_keys: vec![],
            row_count: Some(10000),
            size_bytes: Some(1024 * 1024),
            comment: Some("用户表".to_string()),
            statistics: None,
            sample_data: None,
        };

        assert!(table.validate().is_ok());
        assert_eq!(table.column_count(), 2);
        assert!(table.has_column("id"));
        assert!(table.has_column("name"));
        assert!(!table.has_column("email"));

        let pk_cols = table.primary_key_columns();
        assert_eq!(pk_cols.len(), 1);
        assert_eq!(pk_cols[0].name, "id");
    }

    #[test]
    fn test_data_source_schema() {
        let columns = vec![ColumnSchema {
            name: "id".to_string(),
            data_type: "integer".to_string(),
            is_nullable: false,
            default_value: None,
            character_maximum_length: None,
            numeric_precision: Some(32),
            numeric_scale: Some(0),
            is_primary_key: true,
            is_foreign_key: false,
            comment: None,
            statistics: None,
        }];

        let tables = vec![TableSchema {
            name: "test_table".to_string(),
            schema: "public".to_string(),
            columns: columns.clone(),
            primary_key: vec!["id".to_string()],
            foreign_keys: vec![],
            row_count: Some(100),
            size_bytes: Some(10240),
            comment: None,
            statistics: None,
            sample_data: None,
        }];

        let schema = DataSourceSchema {
            schema_id: "schema_001".to_string(),
            source_name: "test_db".to_string(),
            source_type: DataSourceType::PostgreSQL,
            connection_string: "postgresql://localhost/test".to_string(),
            tables: tables.clone(),
            total_tables: 1,
            total_rows: Some(100),
            total_size_bytes: Some(10240),
            last_crawled_at: None,
            statistics: None,
        };

        assert!(schema.validate().is_ok());
        assert_eq!(schema.find_table("test_table").unwrap().name, "test_table");
        assert!(schema.find_table("nonexistent").is_none());
    }

    #[test]
    fn test_statistics_collection() {
        let collector = StatisticsCollector::new(20);

        let values = vec![
            serde_json::json!(10),
            serde_json::json!(20),
            serde_json::json!(30),
            serde_json::json!(40),
            serde_json::json!(50),
            serde_json::json!(60),
            serde_json::json!(70),
            serde_json::json!(80),
            serde_json::json!(90),
            serde_json::json!(100),
        ];

        let stats = collector.collect_numeric_stats(&values).unwrap();

        assert_eq!(stats.min_value, Some(serde_json::json!(10)));
        assert_eq!(stats.max_value, Some(serde_json::json!(100)));
        assert!((stats.avg_value.unwrap() - 55.0).abs() < 0.01);
        assert_eq!(stats.distinct_count, Some(10));
        assert_eq!(stats.null_count, Some(0));

        let histogram = stats.histogram.unwrap();
        assert_eq!(histogram.len(), 20);
        assert!(histogram.iter().any(|b| b.count > 0));
    }

    #[test]
    fn test_sample_data_collector() {
        let collector = SampleDataCollector::new(100);

        let rows: Vec<HashMap<String, serde_json::Value>> = (0..150)
            .map(|i| {
                let mut m = HashMap::new();
                m.insert("id".to_string(), serde_json::json!(i));
                m.insert(
                    "name".to_string(),
                    serde_json::json!(format!("user_{}", i)),
                );
                if i % 10 == 0 {
                    m.insert("optional".to_string(), serde_json::Value::Null);
                } else {
                    m.insert(
                        "optional".to_string(),
                        serde_json::json!(format!("data_{}", i)),
                    );
                }
                m
            })
            .collect();

        let sample = collector.collect_samples(&rows, 50).unwrap();
        assert_eq!(sample.len(), 50);

        let null_count = sample.iter().filter(|r| r["optional"].is_null()).count();
        assert!(null_count > 0);
    }

    #[tokio::test]
    async fn test_postgres_connector_connection() {
        let config = MetadataCrawlerConfig::default();
        let connector = PostgresConnector::new(
            "postgresql://localhost:5432/testdb",
            config.clone(),
        );

        assert_eq!(connector.source_type(), DataSourceType::PostgreSQL);
    }

    #[tokio::test]
    async fn test_mysql_connector_connection() {
        let config = MetadataCrawlerConfig::default();
        let connector = MysqlConnector::new(
            "mysql://user:password@localhost:3306/testdb",
            config.clone(),
        );

        assert_eq!(connector.source_type(), DataSourceType::MySQL);
    }

    #[test]
    fn test_metadata_crawler_operations() {
        let config = MetadataCrawlerConfig {
            enabled: true,
            schedule_pool_size: 2,
            sample_data_count: 10,
            histogram_buckets: 10,
            statistics_enabled: true,
        };

        let crawler = MetadataCrawler::new(config);

        assert_eq!(crawler.list_sources().len(), 0);

        let sources = vec![
            ("pg_db", DataSourceType::PostgreSQL, "postgresql://localhost/pg"),
            ("mysql_db", DataSourceType::MySQL, "mysql://localhost/mysql"),
        ];

        for (name, source_type, conn_str) in sources {
            crawler
                .add_source(name, source_type, conn_str)
                .unwrap();
        }

        assert_eq!(crawler.list_sources().len(), 2);
        assert!(crawler.has_source("pg_db"));
        assert!(crawler.has_source("mysql_db"));
        assert!(!crawler.has_source("unknown_db"));

        let schedule = CrawlSchedule {
            source_id: "pg_db".to_string(),
            cron_expression: "0 * * * * *".to_string(),
            enabled: true,
            last_run_at: None,
            next_run_at: None,
        };

        crawler
            .add_schedule(schedule)
            .unwrap();

        let schedules = crawler.list_schedules();
        assert_eq!(schedules.len(), 1);
    }

    #[test]
    fn test_table_statistics_default() {
        let stats = TableStatistics::default();
        assert!(stats.last_analyzed.is_none());
        assert!(stats.distinct_count.is_none());
        assert!(stats.null_count.is_none());
    }

    #[test]
    fn test_column_statistics_default() {
        let stats = ColumnStatistics::default();
        assert!(stats.min_value.is_none());
        assert!(stats.max_value.is_none());
        assert!(stats.avg_value.is_none());
        assert!(stats.distinct_count.is_none());
        assert!(stats.null_count.is_none());
        assert!(stats.top_values.is_empty());
        assert!(stats.histogram.is_none());
    }

    #[tokio::test]
    async fn test_crawler_concurrent_crawl() {
        let config = MetadataCrawlerConfig {
            enabled: true,
            schedule_pool_size: 4,
            sample_data_count: 5,
            histogram_buckets: 10,
            statistics_enabled: true,
        };

        let crawler = std::sync::Arc::new(MetadataCrawler::new(config));

        let sources = vec!["source_1", "source_2", "source_3", "source_4"];

        for name in sources {
            crawler
                .add_source(
                    name,
                    DataSourceType::PostgreSQL,
                    &format!("postgresql://localhost/{}", name),
                )
                .unwrap();
        }

        let mut handles = Vec::new();
        for source_name in sources {
            let crawler_clone = crawler.clone();
            let source_id = source_name.to_string();
            handles.push(tokio::spawn(async move {
                crawler_clone.crawl_source(&source_id).await
            }));
        }

        let results = futures::future::join_all(handles).await;

        // 由于没有实际数据库连接，应该返回错误
        let error_count = results
            .iter()
            .filter(|r| r.as_ref().map(|x| x.is_err()).unwrap_or(false))
            .count();

        assert_eq!(error_count, 4);
    }

    #[test]
    fn test_data_source_type_conversion() {
        let types = vec![
            (DataSourceType::PostgreSQL, "postgresql"),
            (DataSourceType::MySQL, "mysql"),
            (DataSourceType::Oracle, "oracle"),
            (DataSourceType::SQLServer, "sqlserver"),
            (DataSourceType::MongoDB, "mongodb"),
            (DataSourceType::S3, "s3"),
            (DataSourceType::HDFS, "hdfs"),
        ];

        for (t, expected) in types {
            let t_str = match t {
                DataSourceType::PostgreSQL => "postgresql",
                DataSourceType::MySQL => "mysql",
                DataSourceType::Oracle => "oracle",
                DataSourceType::SQLServer => "sqlserver",
                DataSourceType::MongoDB => "mongodb",
                DataSourceType::S3 => "s3",
                DataSourceType::HDFS => "hdfs",
            };
            assert_eq!(t_str, expected);
        }
    }

    #[test]
    fn test_histogram_construction() {
        let collector = StatisticsCollector::new(10);

        let values: Vec<serde_json::Value> = (0..1000)
            .map(|i| serde_json::json!(i as f64))
            .collect();

        let stats = collector.collect_numeric_stats(&values).unwrap();
        let histogram = stats.histogram.unwrap();

        assert_eq!(histogram.len(), 10);

        let total_count: u64 = histogram.iter().map(|b| b.count).sum();
        assert_eq!(total_count, 1000);

        // 每个桶应该有大致相同数量的值
        for bucket in &histogram {
            assert!(bucket.count >= 50);
            assert!(bucket.count <= 150);
        }

        // 桶边界应该有序
        for i in 1..histogram.len() {
            assert!(histogram[i].lower_bound >= histogram[i - 1].upper_bound);
        }
    }

    #[test]
    fn test_top_values_collection() {
        let collector = StatisticsCollector::new(10);

        let values: Vec<serde_json::Value> = vec![
            serde_json::json!("A"),
            serde_json::json!("A"),
            serde_json::json!("A"),
            serde_json::json!("A"),
            serde_json::json!("B"),
            serde_json::json!("B"),
            serde_json::json!("B"),
            serde_json::json!("C"),
            serde_json::json!("C"),
            serde_json::json!("D"),
        ];

        let stats = collector.collect_string_stats(&values).unwrap();

        assert_eq!(stats.top_values.len(), 4);
        assert_eq!(stats.top_values[0].0, serde_json::json!("A"));
        assert_eq!(stats.top_values[0].1, 4);
        assert_eq!(stats.top_values[1].0, serde_json::json!("B"));
        assert_eq!(stats.top_values[1].1, 3);
        assert_eq!(stats.null_count, Some(0));
        assert_eq!(stats.distinct_count, Some(4));
    }
}
