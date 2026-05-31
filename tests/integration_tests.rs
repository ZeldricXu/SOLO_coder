#[cfg(test)]
mod storage_tests {
    use enterprise_middleware::storage::{LocalStorage, MetadataIndex, ObjectStorage, StorageManager, StorageConfig};
    use enterprise_middleware::types::StorageBackend;
    use bytes::Bytes;
    use std::collections::HashMap;
    use tempfile::tempdir;

    #[tokio::test]
    async fn test_local_storage_put_and_get() {
        let temp_dir = tempdir().unwrap();
        let config = StorageConfig {
            backend: StorageBackend::Local,
            local_path: temp_dir.path().to_string_lossy().to_string(),
            s3_bucket: "".to_string(),
            s3_region: "".to_string(),
            s3_access_key: "".to_string(),
            s3_secret_key: "".to_string(),
            s3_endpoint: None,
        };

        let storage = LocalStorage::new(&config).unwrap();

        let data = Bytes::from_static(b"test data content");
        let mut metadata = HashMap::new();
        metadata.insert("content-type".to_string(), "text/plain".to_string());
        metadata.insert("author".to_string(), "test".to_string());

        let stored = storage
            .put_object("test_bucket", "test_key", data.clone(), "text/plain", metadata.clone())
            .await
            .unwrap();

        assert_eq!(stored.bucket, "test_bucket");
        assert_eq!(stored.key, "test_key");
        assert_eq!(stored.size, data.len() as u64);
        assert_eq!(stored.content_type, "text/plain");
        assert_eq!(stored.metadata, metadata);

        let retrieved = storage.get_object("test_bucket", "test_key").await.unwrap();
        assert_eq!(retrieved, data);
    }

    #[tokio::test]
    async fn test_storage_list_and_delete() {
        let temp_dir = tempdir().unwrap();
        let config = StorageConfig {
            backend: StorageBackend::Local,
            local_path: temp_dir.path().to_string_lossy().to_string(),
            s3_bucket: "".to_string(),
            s3_region: "".to_string(),
            s3_access_key: "".to_string(),
            s3_secret_key: "".to_string(),
            s3_endpoint: None,
        };

        let storage = LocalStorage::new(&config).unwrap();

        for i in 0..5 {
            let data = Bytes::from(format!("data_{}", i));
            storage
                .put_object(
                    "bucket",
                    &format!("key_{}", i),
                    data,
                    "text/plain",
                    HashMap::new(),
                )
                .await
                .unwrap();
        }

        let objects = storage.list_objects("bucket", None, 100).await.unwrap();
        assert_eq!(objects.len(), 5);

        storage.delete_object("bucket", "key_0").await.unwrap();

        let objects_after = storage.list_objects("bucket", None, 100).await.unwrap();
        assert_eq!(objects_after.len(), 4);
    }

    #[tokio::test]
    async fn test_metadata_index_search() {
        let index = MetadataIndex::new();

        for i in 0..10 {
            let mut tags = HashMap::new();
            tags.insert("type".to_string(), if i % 2 == 0 { "even".to_string() } else { "odd".to_string() });
            tags.insert("category".to_string(), format!("cat_{}", i % 3));

            let mut custom = HashMap::new();
            custom.insert("value".to_string(), i.to_string());

            let obj = enterprise_middleware::types::ObjectMetadataIndex {
                object_id: format!("obj_{}", i),
                bucket: "test_bucket".to_string(),
                key: format!("path/to/obj_{}", i),
                content_type: "application/octet-stream".to_string(),
                size: (i * 1000) as u64,
                tags,
                custom_fields: custom,
                created_at: enterprise_middleware::types::now_utc(),
                last_accessed_at: enterprise_middleware::types::now_utc(),
            };

            index.insert(obj);
        }

        let even_objs = index.search_by_tag("type", "even");
        assert_eq!(even_objs.len(), 5);

        let cat0_objs = index.search_by_tag("category", "cat_0");
        assert_eq!(cat0_objs.len(), 4);

        let prefix_objs = index.search_by_prefix("path/to/");
        assert_eq!(prefix_objs.len(), 10);

        let size_filtered = index.search_by_size(3000, 7000);
        assert_eq!(size_filtered.len(), 4);

        let custom = index.search_by_custom_field("value", "5");
        assert_eq!(custom.len(), 1);
    }

    #[tokio::test]
    async fn test_storage_manager_boundary_values() {
        let temp_dir = tempdir().unwrap();
        let config = StorageConfig {
            backend: StorageBackend::Local,
            local_path: temp_dir.path().to_string_lossy().to_string(),
            s3_bucket: "".to_string(),
            s3_region: "".to_string(),
            s3_access_key: "".to_string(),
            s3_secret_key: "".to_string(),
            s3_endpoint: None,
        };

        let manager = StorageManager::new(&config).unwrap();

        let large_data = Bytes::from(vec![0u8; 1_000_000]);
        let stored = manager
            .put_object("bucket", "large_file", large_data.clone(), "application/octet-stream", HashMap::new())
            .await
            .unwrap();
        assert_eq!(stored.size, 1_000_000);

        let empty_data = Bytes::new();
        let stored_empty = manager
            .put_object("bucket", "empty_file", empty_data, "text/plain", HashMap::new())
            .await
            .unwrap();
        assert_eq!(stored_empty.size, 0);

        let special_key = "path/with/special/chars/@#$%^&*()";
        let special_data = Bytes::from("special content");
        manager
            .put_object("bucket", special_key, special_data, "text/plain", HashMap::new())
            .await
            .unwrap();

        let retrieved = manager.get_object("bucket", special_key).await.unwrap();
        assert_eq!(retrieved, Bytes::from("special content"));
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 4)]
    async fn test_storage_concurrent_access() {
        let temp_dir = tempdir().unwrap();
        let config = StorageConfig {
            backend: StorageBackend::Local,
            local_path: temp_dir.path().to_string_lossy().to_string(),
            s3_bucket: "".to_string(),
            s3_region: "".to_string(),
            s3_access_key: "".to_string(),
            s3_secret_key: "".to_string(),
            s3_endpoint: None,
        };

        let manager = std::sync::Arc::new(StorageManager::new(&config).unwrap());
        let mut handles = Vec::new();

        for i in 0..100 {
            let manager_clone = manager.clone();
            handles.push(tokio::spawn(async move {
                let key = format!("concurrent/key_{}", i);
                let data = Bytes::from(format!("data_{}", i));
                manager_clone
                    .put_object("bucket", &key, data, "text/plain", HashMap::new())
                    .await
            }));
        }

        let results = futures::future::join_all(handles).await;
        let success_count = results.iter().filter(|r| r.as_ref().map(|x| x.is_ok()).unwrap_or(false)).count();
        assert_eq!(success_count, 100);

        let list = manager.list_objects("bucket", Some("concurrent/"), 200).await.unwrap();
        assert_eq!(list.len(), 100);
    }

    #[tokio::test]
    async fn test_storage_error_handling() {
        let temp_dir = tempdir().unwrap();
        let config = StorageConfig {
            backend: StorageBackend::Local,
            local_path: temp_dir.path().to_string_lossy().to_string(),
            s3_bucket: "".to_string(),
            s3_region: "".to_string(),
            s3_access_key: "".to_string(),
            s3_secret_key: "".to_string(),
            s3_endpoint: None,
        };

        let manager = StorageManager::new(&config).unwrap();

        let result = manager.get_object("bucket", "nonexistent_key").await;
        assert!(result.is_err());

        let result = manager.delete_object("bucket", "nonexistent_key").await;
        assert!(result.is_err());

        let result = manager.get_metadata("nonexistent_bucket", "any_key").await;
        assert!(result.is_err());
    }
}

#[cfg(test)]
mod config_tests {
    use enterprise_middleware::config::ConfigManager;
    use std::collections::HashMap;

    #[test]
    fn test_default_config_loading() {
        let manager = ConfigManager::new();

        let defaults = manager.defaults();
        assert!(defaults.contains_key("server.port"));
        assert!(defaults.contains_key("server.host"));
        assert!(defaults.contains_key("logging.level"));

        assert_eq!(manager.environment(), "development");
    }

    #[test]
    fn test_config_validation() {
        let manager = ConfigManager::new();

        let valid_config = serde_json::json!({
            "server": {
                "host": "0.0.0.0",
                "port": 8080,
                "shutdown_timeout": 30
            },
            "logging": {
                "dir": "./logs",
                "level": "info"
            },
            "database": {
                "url": "postgresql://localhost:5432/test",
                "pool_size": 10
            },
            "storage": {
                "backend": "local",
                "local_path": "./data"
            }
        });

        let result = manager.validate(&valid_config);
        assert!(result.is_ok(), "验证失败: {:?}", result.err());

        let invalid_config = serde_json::json!({
            "server": {
                "host": "0.0.0.0",
                "port": 99999
            }
        });

        let result = manager.validate(&invalid_config);
        assert!(result.is_err());
    }

    #[test]
    fn test_config_override_and_merge() {
        let manager = ConfigManager::new();

        let base = serde_json::json!({
            "a": 1,
            "b": {
                "c": 2,
                "d": 3
            }
        });

        let overrides = serde_json::json!({
            "b": {
                "c": 100,
                "e": 200
            },
            "f": 300
        });

        let merged = manager.merge_configs(&base, &overrides);

        assert_eq!(merged["a"], 1);
        assert_eq!(merged["b"]["c"], 100);
        assert_eq!(merged["b"]["d"], 3);
        assert_eq!(merged["b"]["e"], 200);
        assert_eq!(merged["f"], 300);
    }

    #[test]
    fn test_config_environment_diff() {
        let manager = ConfigManager::new();

        let dev_config = serde_json::json!({
            "server": {"port": 8080, "debug": true},
            "logging": {"level": "debug"},
            "database": {"url": "postgresql://localhost:5432/dev"}
        });

        let prod_config = serde_json::json!({
            "server": {"port": 80, "debug": false},
            "logging": {"level": "warn"},
            "database": {"url": "postgresql://prod-db:5432/prod"},
            "cache": {"enabled": true}
        });

        let diff = manager.compare_configs(&dev_config, &prod_config);

        assert!(diff.changes.len() >= 4);
        assert!(diff.additions.contains_key("cache"));
    }

    #[test]
    fn test_config_caching() {
        let manager = ConfigManager::new();

        let config: HashMap<String, serde_json::Value> = vec![
            ("key1".to_string(), serde_json::json!("value1")),
        ]
        .into_iter()
        .collect();

        manager.cache_config("test_ns", config.clone());
        assert!(manager.is_cached("test_ns"));

        let cached = manager.get_cached("test_ns").unwrap();
        assert_eq!(cached, &config);

        manager.invalidate_cache("test_ns");
        assert!(!manager.is_cached("test_ns"));

        manager.cache_config("ns1", config.clone());
        manager.cache_config("ns2", config);
        manager.invalidate_all();
        assert!(!manager.is_cached("ns1"));
        assert!(!manager.is_cached("ns2"));
    }

    #[test]
    fn test_config_boundary_values() {
        let manager = ConfigManager::new();

        let edge_cases = serde_json::json!({
            "server": {
                "port": 1,
                "shutdown_timeout": 0
            },
            "database": {
                "pool_size": 1,
                "connect_timeout": 1
            },
            "logging": {
                "retention_days": 1
            }
        });

        let result = manager.validate(&edge_cases);
        assert!(result.is_ok(), "边界值配置应通过验证: {:?}", result.err());

        let invalid_edges = serde_json::json!({
            "server": {
                "port": 0
            },
            "database": {
                "pool_size": 0
            }
        });

        let result = manager.validate(&invalid_edges);
        assert!(result.is_err());
    }

    #[test]
    fn test_config_versioning() {
        let manager = ConfigManager::new();

        let v1 = serde_json::json!({"version": 1, "data": "v1"});
        let v2 = serde_json::json!({"version": 2, "data": "v2"});

        manager.store_version("test", 1, v1.clone());
        manager.store_version("test", 2, v2.clone());

        let versions = manager.list_versions("test");
        assert_eq!(versions.len(), 2);
        assert_eq!(versions[0], 2);
        assert_eq!(versions[1], 1);

        let loaded_v1 = manager.get_version("test", 1).unwrap();
        assert_eq!(loaded_v1, &v1);

        let loaded_latest = manager.get_latest_version("test").unwrap();
        assert_eq!(loaded_latest, &v2);
    }
}

#[cfg(test)]
mod logging_tests {
    use enterprise_middleware::logging::{create_log_record, LoggingConfig, StructuredLogger};
    use std::collections::HashMap;

    #[test]
    fn test_log_record_creation() {
        let mut fields = HashMap::new();
        fields.insert("user_id".to_string(), serde_json::json!("user_123"));
        fields.insert("request_id".to_string(), serde_json::json!("req_456"));
        fields.insert("duration_ms".to_string(), serde_json::json!(150));

        let record = create_log_record(
            "info",
            "test::module",
            "测试消息内容",
            Some("trace_001".to_string()),
            fields,
        );

        assert_eq!(record.level, "INFO");
        assert_eq!(record.target, "test::module");
        assert_eq!(record.message, "测试消息内容");
        assert_eq!(record.trace_id, Some("trace_001".to_string()));
        assert!(record.timestamp.len() > 0);
        assert_eq!(record.fields["user_id"], serde_json::json!("user_123"));
    }

    #[test]
    fn test_logger_config_validation() {
        let valid_config = LoggingConfig {
            dir: "./logs".to_string(),
            level: "info".to_string(),
            format: "json".to_string(),
            rotation: "daily".to_string(),
            retention_days: 30,
            compression: false,
            ansi_colors: false,
        };

        assert!(StructuredLogger::validate_config(&valid_config).is_ok());

        let invalid_config = LoggingConfig {
            level: "invalid_level".to_string(),
            ..valid_config.clone()
        };

        assert!(StructuredLogger::validate_config(&invalid_config).is_err());

        let invalid_format = LoggingConfig {
            format: "xml".to_string(),
            ..valid_config.clone()
        };

        assert!(StructuredLogger::validate_config(&invalid_format).is_err());

        let invalid_rotation = LoggingConfig {
            rotation: "yearly".to_string(),
            ..valid_config
        };

        assert!(StructuredLogger::validate_config(&invalid_rotation).is_err());
    }

    #[test]
    fn test_structured_log_record_serialization() {
        let mut fields = HashMap::new();
        fields.insert("key".to_string(), serde_json::json!("value"));

        let record = create_log_record(
            "error",
            "app::error",
            "发生错误",
            Some("trace_err".to_string()),
            fields,
        );

        let json = serde_json::to_string(&record).unwrap();
        assert!(json.contains("\"level\":\"ERROR\""));
        assert!(json.contains("\"message\":\"发生错误\""));
        assert!(json.contains("\"trace_id\":\"trace_err\""));

        let deserialized: enterprise_middleware::types::StructuredLogRecord =
            serde_json::from_str(&json).unwrap();
        assert_eq!(deserialized.level, "ERROR");
        assert_eq!(deserialized.message, "发生错误");
    }

    #[test]
    fn test_log_level_conversion() {
        let test_cases = vec![
            ("trace", "TRACE"),
            ("debug", "DEBUG"),
            ("info", "INFO"),
            ("warn", "WARN"),
            ("error", "ERROR"),
            ("TRACE", "TRACE"),
            ("Info", "INFO"),
        ];

        for (input, expected) in test_cases {
            let record = create_log_record(input, "target", "msg", None, HashMap::new());
            assert_eq!(record.level, expected, "输入 '{}' 应转为 '{}'", input, expected);
        }
    }

    #[test]
    fn test_logger_builds_correct_config() {
        let config = LoggingConfig {
            dir: "./test_logs".to_string(),
            level: "debug".to_string(),
            format: "text".to_string(),
            rotation: "hourly".to_string(),
            retention_days: 7,
            compression: true,
            ansi_colors: true,
        };

        let logger = StructuredLogger::new(config);
        assert_eq!(logger.config().level, "debug");
        assert_eq!(logger.config().rotation, "hourly");
    }
}

#[cfg(test)]
mod cdc_tests {
    use enterprise_middleware::cdc::{CdcConfig, JsonEventSerializer, MysqlBinlogParser, CdcOperation};
    use enterprise_middleware::types::{CdcEvent, CdcSource};

    #[tokio::test]
    async fn test_event_serialization_roundtrip() {
        let serializer = JsonEventSerializer::new();

        let event = CdcEvent {
            event_id: "cdc_test_001".to_string(),
            source: CdcSource::MysqlBinlog,
            operation: CdcOperation::Insert,
            database: "test_db".to_string(),
            table: "users".to_string(),
            primary_key: serde_json::json!(123),
            before: None,
            after: Some(serde_json::json!({
                "id": 123,
                "name": "测试用户",
                "email": "test@example.com",
                "created_at": "2026-05-21T10:00:00Z"
            })),
            timestamp: enterprise_middleware::types::now_utc(),
            binlog_position: Some("mysql-bin.000001:12345".to_string()),
            lsn: None,
            transaction_id: Some("tx_001".to_string()),
        };

        let serialized = serializer.serialize(&event).await.unwrap();
        assert!(!serialized.is_empty());

        let deserialized = serializer.deserialize(&serialized).await.unwrap();
        assert_eq!(deserialized.event_id, event.event_id);
        assert_eq!(deserialized.operation, event.operation);
        assert_eq!(deserialized.table, event.table);
        assert_eq!(deserialized.primary_key, event.primary_key);
        assert_eq!(deserialized.after, event.after);
    }

    #[test]
    fn test_cdc_config_validation() {
        let valid_config = CdcConfig {
            enabled: true,
            source_type: "mysql".to_string(),
            connection_string: "mysql://root:password@localhost:3306/test".to_string(),
            server_id: 1,
            slot_name: None,
            include_tables: vec!["users".to_string(), "orders".to_string()],
            exclude_tables: vec![],
            output_kafka_brokers: None,
            output_topic: None,
            batch_size: 100,
            polling_interval_ms: 500,
        };

        assert!(valid_config.validate().is_ok());

        let invalid_source = CdcConfig {
            source_type: "oracle".to_string(),
            ..valid_config.clone()
        };
        assert!(invalid_source.validate().is_err());

        let invalid_batch = CdcConfig {
            batch_size: 0,
            ..valid_config.clone()
        };
        assert!(invalid_batch.validate().is_err());

        let invalid_interval = CdcConfig {
            polling_interval_ms: 0,
            ..valid_config
        };
        assert!(invalid_interval.validate().is_err());
    }

    #[tokio::test]
    async fn test_mysql_parser_filtering() {
        let config = CdcConfig {
            enabled: true,
            source_type: "mysql".to_string(),
            connection_string: "mysql://root:password@localhost:3306/test".to_string(),
            server_id: 1,
            slot_name: None,
            include_tables: vec![
                "users".to_string(),
                "orders".to_string(),
                "schema.*".to_string(),
            ],
            exclude_tables: vec!["logs".to_string(), "temp_*".to_string()],
            output_kafka_brokers: None,
            output_topic: None,
            batch_size: 100,
            polling_interval_ms: 500,
        };

        let parser = MysqlBinlogParser::new(config);

        assert!(parser.should_include_table("users"));
        assert!(parser.should_include_table("orders"));
        assert!(parser.should_include_table("schema_info"));
        assert!(!parser.should_include_table("logs"));
        assert!(!parser.should_include_table("temp_data"));
        assert!(!parser.should_include_table("unknown_table"));
    }

    #[test]
    fn test_cdc_event_operations() {
        let ops = vec![
            (CdcOperation::Insert, "INSERT"),
            (CdcOperation::Update, "UPDATE"),
            (CdcOperation::Delete, "DELETE"),
            (CdcOperation::Truncate, "TRUNCATE"),
            (CdcOperation::CreateTable, "CREATE_TABLE"),
            (CdcOperation::AlterTable, "ALTER_TABLE"),
            (CdcOperation::DropTable, "DROP_TABLE"),
        ];

        for (op, expected_str) in ops {
            let event = CdcEvent {
                event_id: "test".to_string(),
                source: CdcSource::MysqlBinlog,
                operation: op.clone(),
                database: "db".to_string(),
                table: "t".to_string(),
                primary_key: serde_json::json!(1),
                before: None,
                after: None,
                timestamp: enterprise_middleware::types::now_utc(),
                binlog_position: None,
                lsn: None,
                transaction_id: None,
            };

            let op_str = match event.operation {
                CdcOperation::Insert => "INSERT",
                CdcOperation::Update => "UPDATE",
                CdcOperation::Delete => "DELETE",
                CdcOperation::Truncate => "TRUNCATE",
                CdcOperation::CreateTable => "CREATE_TABLE",
                CdcOperation::AlterTable => "ALTER_TABLE",
                CdcOperation::DropTable => "DROP_TABLE",
            };

            assert_eq!(op_str, expected_str);
        }
    }

    #[tokio::test]
    async fn test_event_batch_processing() {
        use enterprise_middleware::cdc::{CdcEventBatch, EventOutput, InMemoryOutput};

        let output = InMemoryOutput::new(1000);

        let mut events = Vec::new();
        for i in 0..100 {
            events.push(CdcEvent {
                event_id: format!("evt_{}", i),
                source: CdcSource::PostgresWal,
                operation: CdcOperation::Insert,
                database: "test".to_string(),
                table: format!("table_{}", i % 5),
                primary_key: serde_json::json!(i),
                before: None,
                after: Some(serde_json::json!({"id": i})),
                timestamp: enterprise_middleware::types::now_utc(),
                binlog_position: None,
                lsn: Some(format!("0/{}", i * 1000)),
                transaction_id: Some(format!("tx_{}", i / 10)),
            });
        }

        let batch = CdcEventBatch::new(events.clone());
        assert_eq!(batch.size(), 100);
        assert_eq!(batch.transactions().len(), 10);

        output
            .send_batch(&batch)
            .await
            .unwrap();

        assert_eq!(output.event_count(), 100);
        assert_eq!(output.batch_count(), 1);
    }
}

#[cfg(test)]
mod data_quality_tests {
    use enterprise_middleware::data_quality::{
        CompletenessEvaluator, EvaluatorRegistry, QualityRuleManager, UniquenessEvaluator,
        AccuracyEvaluator, ConsistencyEvaluator, TimelinessEvaluator, ValidityEvaluator,
        CustomEvaluator, AnomalyStore,
    };
    use enterprise_middleware::types::{QualityRule, QualityRuleType, QualitySeverity, DatasetRef, DataSample};
    use std::collections::HashMap;

    #[test]
    fn test_evaluator_registry() {
        let registry = EvaluatorRegistry::new();

        assert!(registry.get(&QualityRuleType::Completeness).is_ok());
        assert!(registry.get(&QualityRuleType::Uniqueness).is_ok());
        assert!(registry.get(&QualityRuleType::Accuracy).is_ok());
        assert!(registry.get(&QualityRuleType::Consistency).is_ok());
        assert!(registry.get(&QualityRuleType::Timeliness).is_ok());
        assert!(registry.get(&QualityRuleType::Validity).is_ok());
        assert!(registry.get(&QualityRuleType::Custom).is_ok());

        let types = registry.list_types();
        assert_eq!(types.len(), 7);
    }

    #[tokio::test]
    async fn test_completeness_evaluator() {
        let evaluator = CompletenessEvaluator::new();

        let rule = QualityRule {
            rule_id: "test_completeness".to_string(),
            name: "测试完整性".to_string(),
            rule_type: QualityRuleType::Completeness,
            dataset: DatasetRef {
                source: "test".to_string(),
                table: "users".to_string(),
                columns: vec!["email".to_string()],
            },
            severity: QualitySeverity::High,
            enabled: true,
            schedule: None,
            parameters: serde_json::json!({
                "column": "email",
                "min_complete_rate": 0.95
            }),
            created_at: enterprise_middleware::types::now_utc(),
            updated_at: enterprise_middleware::types::now_utc(),
        };

        let good_data = DatasetRef {
            source: "test".to_string(),
            table: "users".to_string(),
            columns: vec!["email".to_string()],
        };

        let result = evaluator
            .evaluate(&rule, &good_data)
            .await
            .unwrap();

        assert!(result.passed);
        assert!(result.completeness_rate >= 0.95);
    }

    #[tokio::test]
    async fn test_uniqueness_evaluator() {
        let evaluator = UniquenessEvaluator::new();

        let rule = QualityRule {
            rule_id: "test_uniqueness".to_string(),
            name: "测试唯一性".to_string(),
            rule_type: QualityRuleType::Uniqueness,
            dataset: DatasetRef {
                source: "test".to_string(),
                table: "users".to_string(),
                columns: vec!["user_id".to_string()],
            },
            severity: QualitySeverity::High,
            enabled: true,
            schedule: None,
            parameters: serde_json::json!({
                "columns": ["user_id"],
                "max_duplicate_rate": 0.01
            }),
            created_at: enterprise_middleware::types::now_utc(),
            updated_at: enterprise_middleware::types::now_utc(),
        };

        let data = DatasetRef {
            source: "test".to_string(),
            table: "users".to_string(),
            columns: vec!["user_id".to_string()],
        };

        let result = evaluator.evaluate(&rule, &data).await.unwrap();
        assert!(result.passed);
    }

    #[test]
    fn test_rule_validation() {
        let valid_rule = QualityRule {
            rule_id: "valid_rule".to_string(),
            name: "有效规则".to_string(),
            rule_type: QualityRuleType::Completeness,
            dataset: DatasetRef {
                source: "db".to_string(),
                table: "t".to_string(),
                columns: vec!["col".to_string()],
            },
            severity: QualitySeverity::Medium,
            enabled: true,
            schedule: Some("0 0 * * * *".to_string()),
            parameters: serde_json::json!({"column": "col"}),
            created_at: enterprise_middleware::types::now_utc(),
            updated_at: enterprise_middleware::types::now_utc(),
        };

        assert!(valid_rule.validate().is_ok());

        let invalid_rule = QualityRule {
            rule_id: "".to_string(),
            name: "".to_string(),
            rule_type: QualityRuleType::Completeness,
            dataset: DatasetRef {
                source: "".to_string(),
                table: "".to_string(),
                columns: vec![],
            },
            severity: QualitySeverity::High,
            enabled: true,
            schedule: None,
            parameters: serde_json::json!({}),
            created_at: enterprise_middleware::types::now_utc(),
            updated_at: enterprise_middleware::types::now_utc(),
        };

        let result = invalid_rule.validate();
        assert!(result.is_err());
        assert_eq!(result.unwrap_err().len(), 4);
    }

    #[tokio::test]
    async fn test_quality_rule_manager_lifecycle() {
        let anomaly_store = AnomalyStore::new();
        let manager = QualityRuleManager::new(anomaly_store);

        let rule = QualityRule {
            rule_id: "manager_test".to_string(),
            name: "管理测试".to_string(),
            rule_type: QualityRuleType::Completeness,
            dataset: DatasetRef {
                source: "test".to_string(),
                table: "t".to_string(),
                columns: vec!["c".to_string()],
            },
            severity: QualitySeverity::Low,
            enabled: true,
            schedule: None,
            parameters: serde_json::json!({"column": "c"}),
            created_at: enterprise_middleware::types::now_utc(),
            updated_at: enterprise_middleware::types::now_utc(),
        };

        manager.add_rule(rule.clone()).unwrap();
        assert!(manager.has_rule("manager_test"));

        let retrieved = manager.get_rule("manager_test").unwrap();
        assert_eq!(retrieved.name, "管理测试");

        let rules = manager.list_rules();
        assert_eq!(rules.len(), 1);

        let result = manager.execute_rule("manager_test").await;
        assert!(result.is_ok());

        manager.remove_rule("manager_test").unwrap();
        assert!(!manager.has_rule("manager_test"));
    }

    #[test]
    fn test_accuracy_evaluator_with_thresholds() {
        let evaluator = AccuracyEvaluator::new();
        assert_eq!(evaluator.rule_type(), QualityRuleType::Accuracy);
    }

    #[test]
    fn test_custom_evaluator() {
        let evaluator = CustomEvaluator::new();
        assert_eq!(evaluator.rule_type(), QualityRuleType::Custom);
    }
}
