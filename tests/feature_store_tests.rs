use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

use chrono::Utc;
use serde_json::json;
use tokio::sync::Barrier;

use modelguard::feature_store::domain::{
    Feature, FeatureIngestRequest, FeatureOnlineFetchRequest, FeatureRecord,
    FeatureRegistrationRequest, FeatureSchema, FeatureType, FeatureValue,
    OfflineBackfillRequest,
};
use modelguard::feature_store::FeatureStoreService;
use modelguard::models::{Config, ModelGuardError};

fn create_test_config() -> Config {
    Config::new(
        "test",
        json!({
            "feature_ttl": 3600,
            "timeout": 30000,
            "retries": 3,
            "consistency_check_enabled": true,
            "default_ttl_seconds": 86400
        }),
    )
}

fn create_test_service() -> FeatureStoreService {
    FeatureStoreService::with_in_memory_backend(create_test_config())
}

async fn register_test_feature(service: &FeatureStoreService, name: &str, feature_type: FeatureType) -> Feature {
    let schema = FeatureSchema::new(name, feature_type).with_description(format!("Test feature: {}", name));
    let request = FeatureRegistrationRequest {
        name: name.to_string(),
        entity_type: "user".to_string(),
        source: "test_source".to_string(),
        schema,
        ttl_seconds: Some(3600),
    };
    service.register_feature(request).await.unwrap()
}

// ============================================================
// 边界条件测试 - 空值/零值/超长输入
// ============================================================

#[tokio::test]
async fn test_register_feature_empty_name() {
    let service = create_test_service();
    let schema = FeatureSchema::new("", FeatureType::String);
    let request = FeatureRegistrationRequest {
        name: String::new(),
        entity_type: "user".to_string(),
        source: "test".to_string(),
        schema,
        ttl_seconds: None,
    };

    let result = service.register_feature(request).await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::ValidationError(_)));
}

#[tokio::test]
async fn test_register_feature_empty_entity_type() {
    let service = create_test_service();
    let schema = FeatureSchema::new("test", FeatureType::String);
    let request = FeatureRegistrationRequest {
        name: "test_feature".to_string(),
        entity_type: String::new(),
        source: "test".to_string(),
        schema,
        ttl_seconds: None,
    };

    let result = service.register_feature(request).await;
    assert!(result.is_err());
}

#[tokio::test]
async fn test_register_feature_very_long_name() {
    let service = create_test_service();
    let long_name = "a".repeat(1000);
    let schema = FeatureSchema::new(&long_name, FeatureType::String);
    let request = FeatureRegistrationRequest {
        name: long_name.clone(),
        entity_type: "user".to_string(),
        source: "test".to_string(),
        schema,
        ttl_seconds: None,
    };

    let result = service.register_feature(request).await;
    assert!(result.is_ok());
    let feature = result.unwrap();
    assert_eq!(feature.name, long_name);
}

#[tokio::test]
async fn test_register_feature_zero_ttl() {
    let service = create_test_service();
    let schema = FeatureSchema::new("test", FeatureType::Int);
    let request = FeatureRegistrationRequest {
        name: "test_feature".to_string(),
        entity_type: "user".to_string(),
        source: "test".to_string(),
        schema,
        ttl_seconds: Some(0),
    };

    let result = service.register_feature(request).await;
    assert!(result.is_ok());
    assert_eq!(result.unwrap().ttl_seconds, Some(0));
}

#[tokio::test]
async fn test_register_feature_very_long_ttl() {
    let service = create_test_service();
    let schema = FeatureSchema::new("test", FeatureType::Int);
    let request = FeatureRegistrationRequest {
        name: "test_feature".to_string(),
        entity_type: "user".to_string(),
        source: "test".to_string(),
        schema,
        ttl_seconds: Some(u64::MAX),
    };

    let result = service.register_feature(request).await;
    assert!(result.is_ok());
    assert_eq!(result.unwrap().ttl_seconds, Some(u64::MAX));
}

#[tokio::test]
async fn test_ingest_empty_records() {
    let service = create_test_service();
    register_test_feature(&service, "age", FeatureType::Int).await;

    let request = FeatureIngestRequest {
        records: vec![],
    };

    let result = service.ingest_features(request).await;
    assert_eq!(result.unwrap(), 0);
}

#[tokio::test]
async fn test_ingest_null_value_for_nullable_feature() {
    let service = create_test_service();
    let schema = FeatureSchema::new("nullable_field", FeatureType::String).with_nullable(true);
    let request = FeatureRegistrationRequest {
        name: "nullable_field".to_string(),
        entity_type: "user".to_string(),
        source: "test".to_string(),
        schema,
        ttl_seconds: None,
    };
    service.register_feature(request).await.unwrap();

    let ingest_req = FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "user1".to_string(),
            feature_name: "nullable_field".to_string(),
            value: FeatureValue::Null,
            timestamp: Utc::now(),
        }],
    };

    let result = service.ingest_features(ingest_req).await;
    assert!(result.is_ok());
    assert_eq!(result.unwrap(), 1);
}

#[tokio::test]
async fn test_ingest_null_value_for_non_nullable_feature() {
    let service = create_test_service();
    let schema = FeatureSchema::new("non_nullable", FeatureType::String).with_nullable(false);
    let request = FeatureRegistrationRequest {
        name: "non_nullable".to_string(),
        entity_type: "user".to_string(),
        source: "test".to_string(),
        schema,
        ttl_seconds: None,
    };
    service.register_feature(request).await.unwrap();

    let ingest_req = FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "user1".to_string(),
            feature_name: "non_nullable".to_string(),
            value: FeatureValue::Null,
            timestamp: Utc::now(),
        }],
    };

    let result = service.ingest_features(ingest_req).await;
    assert!(result.is_err());
}

#[tokio::test]
async fn test_fetch_empty_feature_names() {
    let service = create_test_service();
    register_test_feature(&service, "age", FeatureType::Int).await;

    let request = FeatureOnlineFetchRequest {
        entity_id: "user1".to_string(),
        feature_names: vec![],
    };

    let result = service.fetch_online_features(request).await;
    assert!(result.is_ok());
    assert!(result.unwrap().features.is_empty());
}

#[tokio::test]
async fn test_fetch_nonexistent_entity() {
    let service = create_test_service();
    register_test_feature(&service, "age", FeatureType::Int).await;

    let request = FeatureOnlineFetchRequest {
        entity_id: "nonexistent".to_string(),
        feature_names: vec!["age".to_string()],
    };

    let result = service.fetch_online_features(request).await;
    assert!(result.is_ok());
    let response = result.unwrap();
    assert!(response.features.is_empty());
}

#[tokio::test]
async fn test_ingest_very_large_embedding() {
    let service = create_test_service();
    let schema = FeatureSchema::new("embedding", FeatureType::Embedding);
    let request = FeatureRegistrationRequest {
        name: "embedding".to_string(),
        entity_type: "document".to_string(),
        source: "ml_pipeline".to_string(),
        schema,
        ttl_seconds: None,
    };
    service.register_feature(request).await.unwrap();

    let large_embedding: Vec<f32> = (0..1536).map(|i| i as f32 * 0.001).collect();
    let ingest_req = FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "doc1".to_string(),
            feature_name: "embedding".to_string(),
            value: FeatureValue::Embedding(large_embedding.clone()),
            timestamp: Utc::now(),
        }],
    };

    let result = service.ingest_features(ingest_req).await;
    assert!(result.is_ok());
    assert_eq!(result.unwrap(), 1);
}

#[tokio::test]
async fn test_offline_fetch_empty_entity_ids() {
    let service = create_test_service();
    register_test_feature(&service, "age", FeatureType::Int).await;

    let now = Utc::now();
    let request = OfflineBackfillRequest {
        feature_names: vec!["age".to_string()],
        entity_ids: vec![],
        start_time: now - chrono::Duration::hours(1),
        end_time: now,
        interval_seconds: None,
    };

    let result = service.fetch_offline_features(request).await;
    assert!(result.is_ok());
    assert!(result.unwrap().is_empty());
}

#[tokio::test]
async fn test_offline_fetch_start_after_end() {
    let service = create_test_service();
    register_test_feature(&service, "age", FeatureType::Int).await;

    let now = Utc::now();
    let request = OfflineBackfillRequest {
        feature_names: vec!["age".to_string()],
        entity_ids: vec!["user1".to_string()],
        start_time: now,
        end_time: now - chrono::Duration::hours(1),
        interval_seconds: None,
    };

    let result = service.fetch_offline_features(request).await;
    assert!(result.is_err());
}

// ============================================================
// 并发场景测试 - 多线程同时操作
// ============================================================

#[tokio::test]
async fn test_concurrent_register_same_feature() {
    let service = Arc::new(create_test_service());
    let mut handles = vec![];

    for i in 0..10 {
        let service = Arc::clone(&service);
        handles.push(tokio::spawn(async move {
            let schema = FeatureSchema::new("concurrent_feature", FeatureType::Int);
            let request = FeatureRegistrationRequest {
                name: format!("concurrent_feature_{}", i % 3),
                entity_type: "user".to_string(),
                source: "test".to_string(),
                schema,
                ttl_seconds: None,
            };
            service.register_feature(request).await
        }));
    }

    let results = futures::future::join_all(handles).await;
    let success_count = results.iter().filter(|r| r.as_ref().unwrap().is_ok()).count();
    
    assert!(success_count > 0);
    let features = service.list_features().await.unwrap();
    assert_eq!(features.len(), 3);
}

#[tokio::test]
async fn test_concurrent_ingest_features() {
    let service = Arc::new(create_test_service());
    register_test_feature(&service, "counter", FeatureType::Int).await;
    
    let mut handles = vec![];

    for i in 0..50 {
        let service = Arc::clone(&service);
        handles.push(tokio::spawn(async move {
            let ingest_req = FeatureIngestRequest {
                records: vec![FeatureRecord {
                    entity_id: format!("user_{}", i % 10),
                    feature_name: "counter".to_string(),
                    value: FeatureValue::Int(i as i64),
                    timestamp: Utc::now(),
                }],
            };
            service.ingest_features(ingest_req).await
        }));
    }

    let results = futures::future::join_all(handles).await;
    let success_count = results.iter().filter(|r| r.as_ref().unwrap().is_ok()).count();
    
    assert_eq!(success_count, 50);
}

#[tokio::test]
async fn test_concurrent_read_write() {
    let service = Arc::new(create_test_service());
    register_test_feature(&service, "balance", FeatureType::Float).await;
    
    let barrier = Arc::new(Barrier::new(20));
    let mut write_handles = vec![];
    let mut read_handles = vec![];

    for i in 0..10 {
        let service = Arc::clone(&service);
        let barrier = Arc::clone(&barrier);
        write_handles.push(tokio::spawn(async move {
            barrier.wait().await;
            let ingest_req = FeatureIngestRequest {
                records: vec![FeatureRecord {
                    entity_id: "user1".to_string(),
                    feature_name: "balance".to_string(),
                    value: FeatureValue::Float(i as f64 * 100.0),
                    timestamp: Utc::now(),
                }],
            };
            service.ingest_features(ingest_req).await
        }));
    }

    for _ in 0..10 {
        let service = Arc::clone(&service);
        let barrier = Arc::clone(&barrier);
        read_handles.push(tokio::spawn(async move {
            barrier.wait().await;
            let fetch_req = FeatureOnlineFetchRequest {
                entity_id: "user1".to_string(),
                feature_names: vec!["balance".to_string()],
            };
            service.fetch_online_features(fetch_req).await
        }));
    }

    let write_results = futures::future::join_all(write_handles).await;
    let read_results = futures::future::join_all(read_handles).await;
    let all_write_ok = write_results.iter().all(|r| r.as_ref().unwrap().is_ok());
    let all_read_ok = read_results.iter().all(|r| r.as_ref().unwrap().is_ok());
    assert!(all_write_ok);
    assert!(all_read_ok);
}

#[tokio::test]
async fn test_concurrent_fetch_high_contention() {
    let service = Arc::new(create_test_service());
    register_test_feature(&service, "hot_feature", FeatureType::String).await;

    service.ingest_features(FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "hot_entity".to_string(),
            feature_name: "hot_feature".to_string(),
            value: FeatureValue::String("hot_value".to_string()),
            timestamp: Utc::now(),
        }],
    }).await.unwrap();

    let mut handles = vec![];
    for _ in 0..100 {
        let service = Arc::clone(&service);
        handles.push(tokio::spawn(async move {
            let fetch_req = FeatureOnlineFetchRequest {
                entity_id: "hot_entity".to_string(),
                feature_names: vec!["hot_feature".to_string()],
            };
            service.fetch_online_features(fetch_req).await.unwrap()
        }));
    }

    let results = futures::future::join_all(handles).await;
    for result in results {
        let response = result.unwrap();
        assert_eq!(
            response.features.get("hot_feature"),
            Some(&FeatureValue::String("hot_value".to_string()))
        );
    }
}

// ============================================================
// 异常路径测试 - 外部依赖故障模拟
// ============================================================

#[tokio::test]
async fn test_ingest_unregistered_feature() {
    let service = create_test_service();

    let ingest_req = FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "user1".to_string(),
            feature_name: "unregistered".to_string(),
            value: FeatureValue::Int(42),
            timestamp: Utc::now(),
        }],
    };

    let result = service.ingest_features(ingest_req).await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::NotFound(_)));
}

#[tokio::test]
async fn test_ingest_type_mismatch_string_to_int() {
    let service = create_test_service();
    register_test_feature(&service, "age", FeatureType::Int).await;

    let ingest_req = FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "user1".to_string(),
            feature_name: "age".to_string(),
            value: FeatureValue::String("twenty five".to_string()),
            timestamp: Utc::now(),
        }],
    };

    let result = service.ingest_features(ingest_req).await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::ValidationError(_)));
}

#[tokio::test]
async fn test_ingest_type_mismatch_int_to_float() {
    let service = create_test_service();
    register_test_feature(&service, "score", FeatureType::Float).await;

    let ingest_req = FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "user1".to_string(),
            feature_name: "score".to_string(),
            value: FeatureValue::Int(100),
            timestamp: Utc::now(),
        }],
    };

    let result = service.ingest_features(ingest_req).await;
    assert!(result.is_ok());
}

#[tokio::test]
async fn test_ingest_type_mismatch_array_to_object() {
    let service = create_test_service();
    register_test_feature(&service, "metadata", FeatureType::Object).await;

    let ingest_req = FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "user1".to_string(),
            feature_name: "metadata".to_string(),
            value: FeatureValue::Array(vec![FeatureValue::Float(1.0), FeatureValue::Float(2.0), FeatureValue::Float(3.0)]),
            timestamp: Utc::now(),
        }],
    };

    let result = service.ingest_features(ingest_req).await;
    assert!(result.is_err());
}

#[tokio::test]
async fn test_fetch_feature_not_registered() {
    let service = create_test_service();

    let fetch_req = FeatureOnlineFetchRequest {
        entity_id: "user1".to_string(),
        feature_names: vec!["nonexistent".to_string()],
    };

    let result = service.fetch_online_features(fetch_req).await;
    assert!(result.is_ok());
    assert!(result.unwrap().features.is_empty());
}

#[tokio::test]
async fn test_get_nonexistent_feature() {
    let service = create_test_service();
    let result = service.get_feature("nonexistent").await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::NotFound(_)));
}

#[tokio::test]
async fn test_consistency_check_no_online_data() {
    let service = create_test_service();
    register_test_feature(&service, "age", FeatureType::Int).await;

    let result = service.check_online_offline_consistency("user1", "age").await;
    assert!(result.is_ok());
    assert!(!result.unwrap());
}

#[tokio::test]
async fn test_multiple_ingest_same_entity_feature() {
    let service = create_test_service();
    register_test_feature(&service, "status", FeatureType::String).await;

    let mut timestamps = vec![];
    for i in 0..5 {
        timestamps.push(Utc::now() - chrono::Duration::minutes(i as i64));
    }

    for (i, ts) in timestamps.iter().enumerate() {
        service.ingest_features(FeatureIngestRequest {
            records: vec![FeatureRecord {
                entity_id: "user1".to_string(),
                feature_name: "status".to_string(),
                value: FeatureValue::String(format!("status_{}", i)),
                timestamp: *ts,
            }],
        }).await.unwrap();
    }

    let fetch_req = FeatureOnlineFetchRequest {
        entity_id: "user1".to_string(),
        feature_names: vec!["status".to_string()],
    };
    let response = service.fetch_online_features(fetch_req).await.unwrap();
    
    assert_eq!(
        response.features.get("status"),
        Some(&FeatureValue::String("status_0".to_string()))
    );
}

#[tokio::test]
async fn test_snapshot_metrics_after_operations() {
    let service = create_test_service();
    register_test_feature(&service, "age", FeatureType::Int).await;

    service.ingest_features(FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "user1".to_string(),
            feature_name: "age".to_string(),
            value: FeatureValue::Int(25),
            timestamp: Utc::now(),
        }],
    }).await.unwrap();

    let dimensions = HashMap::from([
        ("env".to_string(), "test".to_string()),
        ("version".to_string(), "v1".to_string()),
    ]);
    let snapshot = service.snapshot_metrics(dimensions);
    
    assert!(snapshot.snapshot_id.starts_with("snap_"));
    assert_eq!(snapshot.dimensions.get("env").unwrap(), "test");
    assert_eq!(snapshot.dimensions.get("version").unwrap(), "v1");
}

#[tokio::test]
async fn test_list_features_empty() {
    let service = create_test_service();
    let features = service.list_features().await.unwrap();
    assert!(features.is_empty());
}

#[tokio::test]
async fn test_list_features_multiple() {
    let service = create_test_service();
    
    for i in 0..10 {
        register_test_feature(&service, &format!("feature_{}", i), FeatureType::String).await;
    }

    let features = service.list_features().await.unwrap();
    assert_eq!(features.len(), 10);
}

#[tokio::test]
async fn test_feature_exists_true() {
    let service = create_test_service();
    register_test_feature(&service, "existing", FeatureType::Int).await;
    
    assert!(service.feature_exists("existing").await);
}

#[tokio::test]
async fn test_feature_exists_false() {
    let service = create_test_service();
    
    assert!(!service.feature_exists("nonexistent").await);
}

#[tokio::test]
async fn test_batch_ingest_mixed_valid_invalid() {
    let service = create_test_service();
    register_test_feature(&service, "age", FeatureType::Int).await;
    register_test_feature(&service, "name", FeatureType::String).await;

    let ingest_req = FeatureIngestRequest {
        records: vec![
            FeatureRecord {
                entity_id: "user1".to_string(),
                feature_name: "age".to_string(),
                value: FeatureValue::Int(25),
                timestamp: Utc::now(),
            },
            FeatureRecord {
                entity_id: "user1".to_string(),
                feature_name: "name".to_string(),
                value: FeatureValue::String("Alice".to_string()),
                timestamp: Utc::now(),
            },
            FeatureRecord {
                entity_id: "user2".to_string(),
                feature_name: "age".to_string(),
                value: FeatureValue::String("invalid".to_string()),
                timestamp: Utc::now(),
            },
        ],
    };

    let result = service.ingest_features(ingest_req).await;
    assert!(result.is_err());
}

#[tokio::test]
async fn test_get_feature_by_name() {
    let service = create_test_service();
    register_test_feature(&service, "unique_feature", FeatureType::String).await;

    let feature = service.get_feature("unique_feature").await.unwrap();
    assert_eq!(feature.name, "unique_feature");
    assert_eq!(feature.entity_type, "user");
}

#[tokio::test]
async fn test_ingest_boolean_feature() {
    let service = create_test_service();
    register_test_feature(&service, "is_active", FeatureType::Bool).await;

    let ingest_req = FeatureIngestRequest {
        records: vec![
            FeatureRecord {
                entity_id: "user1".to_string(),
                feature_name: "is_active".to_string(),
                value: FeatureValue::Bool(true),
                timestamp: Utc::now(),
            },
            FeatureRecord {
                entity_id: "user2".to_string(),
                feature_name: "is_active".to_string(),
                value: FeatureValue::Bool(false),
                timestamp: Utc::now(),
            },
        ],
    };

    assert_eq!(service.ingest_features(ingest_req).await.unwrap(), 2);

    let fetch_req = FeatureOnlineFetchRequest {
        entity_id: "user1".to_string(),
        feature_names: vec!["is_active".to_string()],
    };
    let response = service.fetch_online_features(fetch_req).await.unwrap();
    assert_eq!(response.features.get("is_active"), Some(&FeatureValue::Bool(true)));
}

#[tokio::test]
async fn test_ingest_array_feature() {
    let service = create_test_service();
    register_test_feature(&service, "scores", FeatureType::Array).await;

    let ingest_req = FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "user1".to_string(),
            feature_name: "scores".to_string(),
            value: FeatureValue::Array(vec![
                FeatureValue::Float(95.5),
                FeatureValue::Float(87.0),
                FeatureValue::Float(91.3),
                FeatureValue::Float(78.9),
            ]),
            timestamp: Utc::now(),
        }],
    };

    assert_eq!(service.ingest_features(ingest_req).await.unwrap(), 1);

    let fetch_req = FeatureOnlineFetchRequest {
        entity_id: "user1".to_string(),
        feature_names: vec!["scores".to_string()],
    };
    let response = service.fetch_online_features(fetch_req).await.unwrap();
    if let Some(FeatureValue::Array(scores)) = response.features.get("scores") {
        assert_eq!(scores.len(), 4);
        assert_eq!(scores[0], FeatureValue::Float(95.5));
    } else {
        panic!("Expected Array value");
    }
}

#[tokio::test]
async fn test_ingest_object_feature() {
    let service = create_test_service();
    register_test_feature(&service, "profile", FeatureType::Object).await;

    let profile = json!({
        "name": "Alice",
        "age": 30,
        "email": "alice@example.com"
    });

    let ingest_req = FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "user1".to_string(),
            feature_name: "profile".to_string(),
            value: FeatureValue::Object(profile.clone()),
            timestamp: Utc::now(),
        }],
    };

    assert_eq!(service.ingest_features(ingest_req).await.unwrap(), 1);

    let fetch_req = FeatureOnlineFetchRequest {
        entity_id: "user1".to_string(),
        feature_names: vec!["profile".to_string()],
    };
    let response = service.fetch_online_features(fetch_req).await.unwrap();
    if let Some(FeatureValue::Object(obj)) = response.features.get("profile") {
        assert_eq!(obj["name"], "Alice");
        assert_eq!(obj["age"], 30);
    } else {
        panic!("Expected Object value");
    }
}

#[tokio::test]
async fn test_bulk_consistency_check() {
    let service = create_test_service();
    register_test_feature(&service, "age", FeatureType::Int).await;

    service.ingest_features(FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "user1".to_string(),
            feature_name: "age".to_string(),
            value: FeatureValue::Int(25),
            timestamp: Utc::now(),
        }],
    }).await.unwrap();

    let entity_ids = vec!["user1".to_string(), "user2".to_string()];
    let feature_names = vec!["age".to_string()];

    let results = service.bulk_consistency_check(&entity_ids, &feature_names).await.unwrap();
    assert!(results.contains_key("user1:age"));
    assert!(results.contains_key("user2:age"));
}

#[tokio::test]
async fn test_get_stats() {
    let service = create_test_service();
    
    let stats = service.get_stats().await.unwrap();
    assert_eq!(stats.get("registered_features"), Some(&0));

    register_test_feature(&service, "age", FeatureType::Int).await;
    register_test_feature(&service, "score", FeatureType::Float).await;

    let stats = service.get_stats().await.unwrap();
    assert_eq!(stats.get("registered_features"), Some(&2));
}

#[tokio::test]
async fn test_evict_expired_no_expired() {
    let service = create_test_service();
    register_test_feature(&service, "age", FeatureType::Int).await;

    service.ingest_features(FeatureIngestRequest {
        records: vec![FeatureRecord {
            entity_id: "user1".to_string(),
            feature_name: "age".to_string(),
            value: FeatureValue::Int(25),
            timestamp: Utc::now(),
        }],
    }).await.unwrap();

    let evicted = service.evict_expired().await.unwrap();
    assert_eq!(evicted, 0);

    let fetch_req = FeatureOnlineFetchRequest {
        entity_id: "user1".to_string(),
        feature_names: vec!["age".to_string()],
    };
    let response = service.fetch_online_features(fetch_req).await.unwrap();
    assert!(response.features.contains_key("age"));
}

#[tokio::test]
async fn test_execute_with_retry_success() {
    let service = create_test_service();
    
    let result: Result<i32, ModelGuardError> = service.execute_with_retry(
        || async { Ok(42) },
        Some(3),
        Some(5000),
    ).await;
    
    assert_eq!(result.unwrap(), 42);
}

#[tokio::test]
async fn test_execute_with_retry_fails_then_succeeds() {
    let service = create_test_service();
    let attempt_count = Arc::new(std::sync::atomic::AtomicU32::new(0));
    let attempt_count_clone = Arc::clone(&attempt_count);
    
    let result: Result<i32, ModelGuardError> = service.execute_with_retry(
        move || {
            let count = Arc::clone(&attempt_count_clone);
            async move {
                let prev = count.fetch_add(1, Ordering::SeqCst);
                if prev < 2 {
                    Err(ModelGuardError::InternalError("transient failure".to_string()))
                } else {
                    Ok(99)
                }
            }
        },
        Some(5),
        Some(5000),
    ).await;
    
    assert_eq!(result.unwrap(), 99);
    assert!(attempt_count.load(Ordering::SeqCst) >= 3);
}

#[tokio::test]
async fn test_execute_with_retry_all_fail() {
    let service = create_test_service();
    
    let result: Result<i32, ModelGuardError> = service.execute_with_retry(
        || async { Err(ModelGuardError::InternalError("permanent failure".to_string())) },
        Some(2),
        Some(100),
    ).await;
    
    assert!(result.is_err());
}

#[tokio::test]
async fn test_execute_with_retry_timeout() {
    let service = create_test_service();
    
    let result: Result<i32, ModelGuardError> = service.execute_with_retry(
        || async {
            tokio::time::sleep(std::time::Duration::from_secs(10)).await;
            Ok(1)
        },
        Some(2),
        Some(1),
    ).await;
    
    assert!(result.is_err());
}
