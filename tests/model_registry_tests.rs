use std::collections::HashMap;
use std::sync::Arc;

use chrono::Utc;
use serde_json::json;
use tokio::sync::Barrier;

use modelguard::model_registry::domain::{
    Model, ModelMetadata, ModelRegistrationRequest, ModelStage, ModelVersion,
    StageTransitionRequest, VersionCreateRequest,
};
use modelguard::model_registry::ModelRegistryService;
use modelguard::models::{Config, ModelGuardError};

fn create_test_config() -> Config {
    Config::new("test", json!({"timeout": 30000}))
}

fn create_test_service() -> ModelRegistryService {
    ModelRegistryService::with_in_memory_backend(create_test_config())
}

async fn register_test_model(service: &ModelRegistryService, name: &str) -> Model {
    let request = ModelRegistrationRequest {
        name: name.to_string(),
        description: Some(format!("Test model: {}", name)),
        metadata: None,
    };
    service.register_model(request).await.unwrap()
}

async fn register_model_with_metadata(
    service: &ModelRegistryService,
    name: &str,
    tags: Vec<&str>,
) -> Model {
    let metadata = ModelMetadata {
        tags: tags.into_iter().map(|s| s.to_string()).collect(),
        framework: Some("pytorch".to_string()),
        ..Default::default()
    };
    let request = ModelRegistrationRequest {
        name: name.to_string(),
        description: None,
        metadata: Some(metadata),
    };
    service.register_model(request).await.unwrap()
}

// ============================================================
// 边界条件测试 - 空值/零值/超长输入
// ============================================================

#[tokio::test]
async fn test_register_model_empty_name() {
    let service = create_test_service();
    let request = ModelRegistrationRequest {
        name: String::new(),
        description: None,
        metadata: None,
    };

    let result = service.register_model(request).await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::ValidationError(_)));
}

#[tokio::test]
async fn test_register_model_whitespace_name() {
    let service = create_test_service();
    let request = ModelRegistrationRequest {
        name: "   ".to_string(),
        description: None,
        metadata: None,
    };

    let result = service.register_model(request).await;
    assert!(result.is_ok());
}

#[tokio::test]
async fn test_register_model_very_long_name() {
    let service = create_test_service();
    let long_name = "x".repeat(1000);
    let request = ModelRegistrationRequest {
        name: long_name.clone(),
        description: None,
        metadata: None,
    };

    let result = service.register_model(request).await;
    assert!(result.is_ok());
    let model = result.unwrap();
    assert_eq!(model.name, long_name);
}

#[tokio::test]
async fn test_register_model_empty_description() {
    let service = create_test_service();
    let request = ModelRegistrationRequest {
        name: "test_model".to_string(),
        description: Some(String::new()),
        metadata: None,
    };

    let result = service.register_model(request).await;
    assert!(result.is_ok());
}

#[tokio::test]
async fn test_register_model_none_description() {
    let service = create_test_service();
    let request = ModelRegistrationRequest {
        name: "test_model".to_string(),
        description: None,
        metadata: None,
    };

    let result = service.register_model(request).await;
    assert!(result.is_ok());
}

#[tokio::test]
async fn test_register_model_empty_metadata_tags() {
    let service = create_test_service();
    let metadata = ModelMetadata {
        tags: vec![],
        ..Default::default()
    };
    let request = ModelRegistrationRequest {
        name: "test_model".to_string(),
        description: None,
        metadata: Some(metadata),
    };

    let result = service.register_model(request).await;
    assert!(result.is_ok());
}

#[tokio::test]
async fn test_register_model_large_metadata() {
    let service = create_test_service();
    let mut tags = Vec::new();
    for i in 0..100 {
        tags.push(format!("tag_{}", i));
    }
    let metadata = ModelMetadata {
        tags,
        framework: Some("tensorflow".to_string()),
        ..Default::default()
    };
    let request = ModelRegistrationRequest {
        name: "large_metadata_model".to_string(),
        description: None,
        metadata: Some(metadata),
    };

    let result = service.register_model(request).await;
    assert!(result.is_ok());
}

#[tokio::test]
async fn test_create_version_zero_model_id() {
    let service = create_test_service();
    let request = VersionCreateRequest {
        model_id: String::new(),
        metadata: None,
    };

    let result = service.create_version(request).await;
    assert!(result.is_err());
}

#[tokio::test]
async fn test_create_version_nonexistent_model() {
    let service = create_test_service();
    let request = VersionCreateRequest {
        model_id: "nonexistent_model".to_string(),
        metadata: None,
    };

    let result = service.create_version(request).await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::NotFound(_)));
}

#[tokio::test]
async fn test_get_version_nonexistent_model() {
    let service = create_test_service();
    let result = service.get_version("nonexistent", 1).await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::NotFound(_)));
}

#[tokio::test]
async fn test_get_version_nonexistent_version() {
    let service = create_test_service();
    let model = register_test_model(&service, "test_model").await;
    
    let result = service.get_version(&model.model_id, 999).await;
    assert!(result.is_err());
}

#[tokio::test]
async fn test_get_production_version_not_set() {
    let service = create_test_service();
    let model = register_test_model(&service, "test_model").await;
    
    let result = service.get_production_version(&model.model_id).await;
    assert!(result.is_err());
}

#[tokio::test]
async fn test_list_versions_empty_model() {
    let service = create_test_service();
    let model = register_test_model(&service, "test_model").await;
    
    let versions = service.list_versions(&model.model_id).await.unwrap();
    assert_eq!(versions.len(), 1);
}

#[tokio::test]
async fn test_search_empty_tags() {
    let service = create_test_service();
    register_model_with_metadata(&service, "model1", vec!["nlp"]).await;
    register_model_with_metadata(&service, "model2", vec!["cv"]).await;

    let results = service.search_models(&[], None);
    assert_eq!(results.len(), 2);
}

#[tokio::test]
async fn test_search_no_matching_tags() {
    let service = create_test_service();
    register_model_with_metadata(&service, "model1", vec!["nlp"]).await;
    register_model_with_metadata(&service, "model2", vec!["cv"]).await;

    let results = service.search_models(&["nonexistent".to_string()], None);
    assert!(results.is_empty());
}

#[tokio::test]
async fn test_delete_nonexistent_model() {
    let service = create_test_service();
    let result = service.delete_model("nonexistent").await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::NotFound(_)));
}

// ============================================================
// 并发场景测试 - 多线程同时操作
// ============================================================

#[tokio::test]
async fn test_concurrent_register_models() {
    let service = Arc::new(create_test_service());
    let mut handles = vec![];

    for i in 0..20 {
        let service = Arc::clone(&service);
        handles.push(tokio::spawn(async move {
            let request = ModelRegistrationRequest {
                name: format!("concurrent_model_{}", i),
                description: None,
                metadata: None,
            };
            service.register_model(request).await
        }));
    }

    let results = futures::future::join_all(handles).await;
    let success_count = results.iter().filter(|r| r.as_ref().unwrap().is_ok()).count();
    
    assert_eq!(success_count, 20);
    let models = service.list_models().await.unwrap();
    assert_eq!(models.len(), 20);
}

#[tokio::test]
async fn test_concurrent_create_versions() {
    let service = Arc::new(create_test_service());
    let model = register_test_model(&service, "concurrent_version_model").await;
    let model_id = model.model_id.clone();
    
    let mut handles = vec![];

    for _ in 0..10 {
        let service = Arc::clone(&service);
        let model_id = model_id.clone();
        handles.push(tokio::spawn(async move {
            let request = VersionCreateRequest {
                model_id,
                metadata: None,
            };
            service.create_version(request).await
        }));
    }

    let results = futures::future::join_all(handles).await;
    let success_count = results.iter().filter(|r| r.as_ref().unwrap().is_ok()).count();
    
    assert_eq!(success_count, 10);
    let versions = service.list_versions(&model_id).await.unwrap();
    assert_eq!(versions.len(), 11);
}

#[tokio::test]
async fn test_concurrent_read_write() {
    let service = Arc::new(create_test_service());
    let model = register_test_model(&service, "concurrent_rw_model").await;
    let model_id = model.model_id.clone();
    
    let barrier = Arc::new(Barrier::new(20));
    let mut version_handles = vec![];
    let mut model_handles = vec![];

    for i in 0..10 {
        let service = Arc::clone(&service);
        let model_id = model_id.clone();
        let barrier = Arc::clone(&barrier);
        version_handles.push(tokio::spawn(async move {
            barrier.wait().await;
            let request = VersionCreateRequest {
                model_id,
                metadata: None,
            };
            service.create_version(request).await
        }));
    }

    for _ in 0..10 {
        let service = Arc::clone(&service);
        let model_id = model_id.clone();
        let barrier = Arc::clone(&barrier);
        model_handles.push(tokio::spawn(async move {
            barrier.wait().await;
            service.get_model(&model_id).await
        }));
    }

    let version_results = futures::future::join_all(version_handles).await;
    let model_results = futures::future::join_all(model_handles).await;
    let all_version_ok = version_results.iter().all(|r| r.as_ref().unwrap().is_ok());
    let all_model_ok = model_results.iter().all(|r| r.as_ref().unwrap().is_ok());
    assert!(all_version_ok);
    assert!(all_model_ok);
}

#[tokio::test]
async fn test_concurrent_stage_transitions() {
    let service = Arc::new(create_test_service());
    let model = register_test_model(&service, "stage_model").await;
    let model_id = model.model_id.clone();
    
    service.create_version(VersionCreateRequest {
        model_id: model_id.clone(),
        metadata: None,
    }).await.unwrap();
    
    let mut handles = vec![];

    for version in 1..=2 {
        let service = Arc::clone(&service);
        let model_id = model_id.clone();
        handles.push(tokio::spawn(async move {
            let request = StageTransitionRequest {
                model_id,
                version,
                target_stage: ModelStage::Production,
            };
            service.transition_stage(request).await
        }));
    }

    let results = futures::future::join_all(handles).await;
    
    let prod_version = service.get_production_version(&model_id).await.unwrap();
    assert!(prod_version.version == 1 || prod_version.version == 2);
}

#[tokio::test]
async fn test_concurrent_search_models() {
    let service = Arc::new(create_test_service());
    
    for i in 0..5 {
        register_model_with_metadata(
            &service,
            &format!("model_{}", i),
            vec![if i % 2 == 0 { "nlp" } else { "cv" }],
        ).await;
    }
    
    let mut handles = vec![];

    for _ in 0..20 {
        let service = Arc::clone(&service);
        handles.push(tokio::spawn(async move {
            service.search_models(&["nlp".to_string()], None)
        }));
    }

    let results = futures::future::join_all(handles).await;
    for result in results {
        let models = result.unwrap();
        assert_eq!(models.len(), 3);
    }
}

// ============================================================
// 异常路径测试 - 外部依赖故障模拟
// ============================================================

#[tokio::test]
async fn test_register_duplicate_model() {
    let service = create_test_service();
    let request = ModelRegistrationRequest {
        name: "duplicate_model".to_string(),
        description: None,
        metadata: None,
    };

    service.register_model(request.clone()).await.unwrap();
    let result = service.register_model(request).await;
    
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::Conflict(_)));
}

#[tokio::test]
async fn test_get_nonexistent_model() {
    let service = create_test_service();
    let result = service.get_model("nonexistent").await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::NotFound(_)));
}

#[tokio::test]
async fn test_get_model_by_name_nonexistent() {
    let service = create_test_service();
    let result = service.get_model_by_name("nonexistent").await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::NotFound(_)));
}

#[tokio::test]
async fn test_invalid_stage_transition_staging_to_deprecated() {
    let service = create_test_service();
    let model = register_test_model(&service, "test_model").await;
    
    let request = StageTransitionRequest {
        model_id: model.model_id.clone(),
        version: 1,
        target_stage: ModelStage::Deprecated,
    };

    let result = service.transition_stage(request).await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::ValidationError(_)));
}

#[tokio::test]
async fn test_invalid_stage_transition_archived_to_production() {
    let service = create_test_service();
    let model = register_test_model(&service, "test_model").await;
    
    service.transition_stage(StageTransitionRequest {
        model_id: model.model_id.clone(),
        version: 1,
        target_stage: ModelStage::Production,
    }).await.unwrap();

    service.transition_stage(StageTransitionRequest {
        model_id: model.model_id.clone(),
        version: 1,
        target_stage: ModelStage::Archived,
    }).await.unwrap();

    let result = service.transition_stage(StageTransitionRequest {
        model_id: model.model_id.clone(),
        version: 1,
        target_stage: ModelStage::Production,
    }).await;

    assert!(result.is_err());
}

#[tokio::test]
async fn test_transition_nonexistent_version() {
    let service = create_test_service();
    let model = register_test_model(&service, "test_model").await;
    
    let request = StageTransitionRequest {
        model_id: model.model_id.clone(),
        version: 999,
        target_stage: ModelStage::Production,
    };

    let result = service.transition_stage(request).await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::NotFound(_)));
}

#[tokio::test]
async fn test_update_metadata_nonexistent_version() {
    let service = create_test_service();
    let model = register_test_model(&service, "test_model").await;
    
    let result = service.update_version_metadata(
        &model.model_id,
        999,
        HashMap::new(),
        HashMap::new(),
    ).await;

    assert!(result.is_err());
}

#[tokio::test]
async fn test_get_latest_version_empty_model() {
    let service = create_test_service();
    let result = service.get_latest_version("nonexistent").await;
    assert!(result.is_err());
}

#[tokio::test]
async fn test_delete_model_then_access() {
    let service = create_test_service();
    let model = register_test_model(&service, "to_delete").await;
    
    service.delete_model(&model.model_id).await.unwrap();
    
    let result = service.get_model(&model.model_id).await;
    assert!(result.is_err());
    assert!(matches!(result.unwrap_err(), ModelGuardError::NotFound(_)));
}

#[tokio::test]
async fn test_list_models_empty() {
    let service = create_test_service();
    let models = service.list_models().await.unwrap();
    assert!(models.is_empty());
}

#[tokio::test]
async fn test_list_models_multiple() {
    let service = create_test_service();
    
    for i in 0..5 {
        register_test_model(&service, &format!("model_{}", i)).await;
    }

    let models = service.list_models().await.unwrap();
    assert_eq!(models.len(), 5);
}

#[tokio::test]
async fn test_get_stats_empty() {
    let service = create_test_service();
    let stats = service.get_stats().await.unwrap();
    
    assert_eq!(stats["total_models"], 0);
    assert_eq!(stats["total_versions"], 0);
    assert_eq!(stats["production_models"], 0);
}

#[tokio::test]
async fn test_get_stats_with_models() {
    let service = create_test_service();
    
    let model1 = register_test_model(&service, "model1").await;
    register_test_model(&service, "model2").await;
    
    service.create_version(VersionCreateRequest {
        model_id: model1.model_id.clone(),
        metadata: None,
    }).await.unwrap();

    service.transition_stage(StageTransitionRequest {
        model_id: model1.model_id.clone(),
        version: 1,
        target_stage: ModelStage::Production,
    }).await.unwrap();

    let stats = service.get_stats().await.unwrap();
    
    assert_eq!(stats["total_models"], 2);
    assert_eq!(stats["total_versions"], 3);
    assert_eq!(stats["production_models"], 1);
}

#[tokio::test]
async fn test_snapshot_metrics() {
    let service = create_test_service();
    let dimensions = HashMap::from([
        ("env".to_string(), "production".to_string()),
        ("region".to_string(), "us-east-1".to_string()),
    ]);
    let snapshot = service.snapshot_metrics(dimensions);
    
    assert!(snapshot.snapshot_id.starts_with("snap_"));
    assert_eq!(snapshot.dimensions.get("env").unwrap(), "production");
    assert_eq!(snapshot.dimensions.get("region").unwrap(), "us-east-1");
}

#[tokio::test]
async fn test_model_version_progression() {
    let service = create_test_service();
    let model = register_test_model(&service, "progression_model").await;
    
    assert_eq!(model.latest_version, 1);
    assert_eq!(model.versions[0].stage, ModelStage::Staging);

    service.transition_stage(StageTransitionRequest {
        model_id: model.model_id.clone(),
        version: 1,
        target_stage: ModelStage::Production,
    }).await.unwrap();

    let v2 = service.create_version(VersionCreateRequest {
        model_id: model.model_id.clone(),
        metadata: None,
    }).await.unwrap();
    
    assert_eq!(v2.version, 2);
    assert_eq!(v2.stage, ModelStage::Staging);

    service.transition_stage(StageTransitionRequest {
        model_id: model.model_id.clone(),
        version: 2,
        target_stage: ModelStage::Production,
    }).await.unwrap();

    let v1 = service.get_version(&model.model_id, 1).await.unwrap();
    assert_eq!(v1.stage, ModelStage::Archived);

    let prod_version = service.get_production_version(&model.model_id).await.unwrap();
    assert_eq!(prod_version.version, 2);
}

#[tokio::test]
async fn test_search_by_stage() {
    let service = create_test_service();
    
    let model1 = register_test_model(&service, "model1").await;
    register_test_model(&service, "model2").await;
    
    service.transition_stage(StageTransitionRequest {
        model_id: model1.model_id.clone(),
        version: 1,
        target_stage: ModelStage::Production,
    }).await.unwrap();

    let prod_models = service.search_models(&[], Some(ModelStage::Production));
    assert_eq!(prod_models.len(), 1);
    assert_eq!(prod_models[0].name, "model1");

    let staging_models = service.search_models(&[], Some(ModelStage::Staging));
    assert_eq!(staging_models.len(), 1);
    assert_eq!(staging_models[0].name, "model2");
}

#[tokio::test]
async fn test_multiple_tags_search() {
    let service = create_test_service();
    
    register_model_with_metadata(&service, "model1", vec!["nlp", "classification"]).await;
    register_model_with_metadata(&service, "model2", vec!["nlp", "generation"]).await;
    register_model_with_metadata(&service, "model3", vec!["cv", "classification"]).await;

    let nlp_models = service.search_models(&["nlp".to_string()], None);
    assert_eq!(nlp_models.len(), 2);

    let class_models = service.search_models(&["classification".to_string()], None);
    assert_eq!(class_models.len(), 2);

    let both_models = service.search_models(
        &["nlp".to_string(), "classification".to_string()],
        None,
    );
    assert_eq!(both_models.len(), 1);
    assert_eq!(both_models[0].name, "model1");
}

#[tokio::test]
async fn test_version_metadata_operations() {
    let service = create_test_service();
    let model = register_test_model(&service, "metadata_model").await;
    
    let mut metrics = HashMap::new();
    metrics.insert("accuracy".to_string(), 0.95);
    metrics.insert("f1_score".to_string(), 0.92);
    metrics.insert("precision".to_string(), 0.94);
    metrics.insert("recall".to_string(), 0.90);
    
    let mut artifacts = HashMap::new();
    artifacts.insert("model_path".to_string(), "s3://bucket/model.pt".to_string());
    artifacts.insert("config_path".to_string(), "s3://bucket/config.json".to_string());

    let version = service.update_version_metadata(
        &model.model_id,
        1,
        metrics.clone(),
        artifacts.clone(),
    ).await.unwrap();

    assert_eq!(version.metadata.metrics.get("accuracy"), Some(&0.95));
    assert_eq!(version.metadata.metrics.get("f1_score"), Some(&0.92));
    assert_eq!(
        version.metadata.artifacts.get("model_path"),
        Some(&"s3://bucket/model.pt".to_string())
    );
}

#[tokio::test]
async fn test_model_id_format() {
    let service = create_test_service();
    let model = register_test_model(&service, "format_test").await;
    
    assert!(model.model_id.starts_with("mod_"));
    assert!(model.model_id.len() > 4);
}

#[tokio::test]
async fn test_version_id_format() {
    let service = create_test_service();
    let model = register_test_model(&service, "version_format_test").await;
    
    for version in &model.versions {
        assert!(version.version_id.starts_with("ver_"));
    }
}

#[tokio::test]
async fn test_created_at_timestamps() {
    let service = create_test_service();
    let before = Utc::now();
    let model = register_test_model(&service, "timestamp_test").await;
    let after = Utc::now();
    
    assert!(model.created_at >= before);
    assert!(model.created_at <= after);
    
    for version in &model.versions {
        assert!(version.created_at >= before);
        assert!(version.created_at <= after);
    }
}
