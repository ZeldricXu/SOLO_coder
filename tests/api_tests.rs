#[cfg(test)]
mod api_tests {
    use axum::{
        body::Body,
        http::{Request, StatusCode},
        Router,
    };
    use enterprise_middleware::api::{create_router, AppState};
    use enterprise_middleware::core::{InMemoryEventEmitter, MetricsRecorder, RequestHandler};
    use enterprise_middleware::notification::NotificationManager;
    use enterprise_middleware::lineage::LineageManager;
    use enterprise_middleware::data_quality::QualityRuleManager;
    use enterprise_middleware::metadata_crawler::MetadataCrawler;
    use enterprise_middleware::types::{AppConfig, StorageConfig, StorageBackend};
    use hyper::body::to_bytes;
    use std::collections::HashMap;
    use std::sync::Arc;
    use dashmap::DashMap;

    fn create_test_app_state() -> AppState {
        let emitter = Arc::new(InMemoryEventEmitter::new());
        let metrics = Arc::new(MetricsRecorder::new());
        let request_handler = Arc::new(RequestHandler::new(10, emitter, metrics));

        let notification_manager = Arc::new(
            NotificationManager::new(enterprise_middleware::notification::NotificationConfig::default()).unwrap(),
        );

        let lineage_manager = Arc::new(LineageManager::new(
            enterprise_middleware::lineage::LineageConfig::default(),
        ));

        let quality_rule_manager = Arc::new(
            QualityRuleManager::new(enterprise_middleware::data_quality::AnomalyStore::new()),
        );

        let metadata_crawler = Arc::new(MetadataCrawler::new(
            enterprise_middleware::metadata_crawler::MetadataCrawlerConfig::default(),
        ));

        let app_config = AppConfig {
            server: enterprise_middleware::types::ServerConfig {
                host: "0.0.0.0".to_string(),
                port: 8080,
                shutdown_timeout: 30,
            },
            database: enterprise_middleware::types::DatabaseConfig {
                url: "postgres://localhost/test".to_string(),
                pool_size: 10,
                connect_timeout: 30,
                idle_timeout: 600,
                max_lifetime: 1800,
                acquire_timeout: 30,
            },
            redis: enterprise_middleware::types::RedisConfig {
                url: "redis://localhost:6379".to_string(),
                pool_size: 10,
                connect_timeout: 5,
                idle_timeout: 300,
                max_lifetime: 1800,
            },
            logging: enterprise_middleware::types::LoggingConfig {
                dir: "./logs".to_string(),
                level: "info".to_string(),
                format: "json".to_string(),
                rotation: "daily".to_string(),
                retention_days: 30,
                compression: false,
                ansi_colors: false,
            },
            storage: StorageConfig {
                backend: StorageBackend::Local,
                local_path: "./data".to_string(),
                s3_bucket: "".to_string(),
                s3_region: "".to_string(),
                s3_access_key: "".to_string(),
                s3_secret_key: "".to_string(),
                s3_endpoint: None,
            },
            cdc: enterprise_middleware::types::CdcConfig {
                enabled: false,
                source_type: "postgres".to_string(),
                connection_string: "".to_string(),
                server_id: 1,
                slot_name: None,
                include_tables: vec![],
                exclude_tables: vec![],
                output_kafka_brokers: None,
                output_topic: None,
                batch_size: 100,
                polling_interval_ms: 500,
            },
            data_quality: enterprise_middleware::types::DataQualityConfig {
                enabled: false,
                schedule_pool_size: 4,
                alert_enabled: false,
                alert_channels: vec![],
                anomaly_storage_enabled: false,
            },
            metadata_crawler: enterprise_middleware::types::MetadataCrawlerConfig {
                enabled: false,
                schedule_pool_size: 4,
                sample_data_count: 100,
                histogram_buckets: 20,
                statistics_enabled: true,
            },
            lineage: enterprise_middleware::types::LineageConfig {
                enabled: false,
                sql_dialect: "postgres".to_string(),
                store_parsed_queries: true,
                build_dag: true,
            },
            notification: enterprise_middleware::types::NotificationConfig {
                enabled: false,
                default_channel: enterprise_middleware::types::NotificationChannel::Email,
                rate_limit_per_minute: 100,
                retry_count: 3,
                retry_interval_ms: 1000,
                smtp_host: "".to_string(),
                smtp_port: 587,
                smtp_username: "".to_string(),
                smtp_password: "".to_string(),
                slack_webhook: None,
                dingtalk_webhook: None,
                wechat_webhook: None,
                webhook_timeout_ms: 5000,
            },
        };

        AppState::new(
            app_config,
            request_handler,
            notification_manager,
            lineage_manager,
            quality_rule_manager,
            metadata_crawler,
        )
    }

    fn create_test_router() -> Router {
        let state = create_test_app_state();
        create_router(state)
    }

    #[tokio::test]
    async fn test_health_check() {
        let app = create_test_router();

        let request = Request::builder()
            .uri("/health")
            .method("GET")
            .body(Body::empty())
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::OK);

        let body = to_bytes(response.into_body()).await.unwrap();
        let body_str = String::from_utf8(body.to_vec()).unwrap();

        assert!(body_str.contains("healthy"));
        assert!(body_str.contains("true"));
    }

    #[tokio::test]
    async fn test_create_resource() {
        let app = create_test_router();

        let request_body = serde_json::json!({
            "type": "workflow",
            "config": {
                "steps": ["extract", "transform", "load"],
                "timeout": 300
            },
            "labels": {
                "env": "test",
                "team": "data"
            }
        });

        let request = Request::builder()
            .uri("/api/v1/resources")
            .method("POST")
            .header("Content-Type", "application/json")
            .body(Body::from(serde_json::to_string(&request_body).unwrap()))
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::CREATED);

        let body = to_bytes(response.into_body()).await.unwrap();
        let response_json: serde_json::Value = serde_json::from_slice(&body).unwrap();

        assert_eq!(response_json["code"], 201);
        assert!(response_json["data"]["id"].as_str().unwrap().starts_with("rsc_"));
        assert_eq!(response_json["data"]["status"], "provisioning");
    }

    #[tokio::test]
    async fn test_list_resources() {
        let app = create_test_router();

        let request = Request::builder()
            .uri("/api/v1/resources")
            .method("GET")
            .body(Body::empty())
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::OK);

        let body = to_bytes(response.into_body()).await.unwrap();
        let response_json: serde_json::Value = serde_json::from_slice(&body).unwrap();

        assert_eq!(response_json["code"], 200);
        assert!(response_json["data"].is_array());
    }

    #[tokio::test]
    async fn test_get_resource_status() {
        let app = create_test_router();

        let create_body = serde_json::json!({
            "type": "workflow",
            "config": {"key": "value"},
            "labels": {}
        });

        let create_request = Request::builder()
            .uri("/api/v1/resources")
            .method("POST")
            .header("Content-Type", "application/json")
            .body(Body::from(serde_json::to_string(&create_body).unwrap()))
            .unwrap();

        let create_response = app.call(create_request).await.unwrap();
        let create_body = to_bytes(create_response.into_body()).await.unwrap();
        let create_json: serde_json::Value = serde_json::from_slice(&create_body).unwrap();
        let resource_id = create_json["data"]["id"].as_str().unwrap();

        let status_request = Request::builder()
            .uri(format!("/api/v1/resources/{}/status", resource_id))
            .method("GET")
            .body(Body::empty())
            .unwrap();

        let status_response = app.call(status_request).await.unwrap();

        assert_eq!(status_response.status(), StatusCode::OK);

        let status_body = to_bytes(status_response.into_body()).await.unwrap();
        let status_json: serde_json::Value = serde_json::from_slice(&status_body).unwrap();

        assert_eq!(status_json["code"], 200);
        assert_eq!(status_json["data"]["id"], resource_id);
        assert_eq!(status_json["data"]["status"], "provisioning");
        assert!(status_json["data"]["progress"].as_f64().unwrap() >= 0.0);
    }

    #[tokio::test]
    async fn test_get_resource_status_not_found() {
        let app = create_test_router();

        let request = Request::builder()
            .uri("/api/v1/resources/nonexistent_id/status")
            .method("GET")
            .body(Body::empty())
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_batch_operations() {
        let app = create_test_router();

        let mut resource_ids = Vec::new();
        for i in 0..3 {
            let body = serde_json::json!({
                "type": "resource",
                "config": {"index": i},
                "labels": {}
            });

            let request = Request::builder()
                .uri("/api/v1/resources")
                .method("POST")
                .header("Content-Type", "application/json")
                .body(Body::from(serde_json::to_string(&body).unwrap()))
                .unwrap();

            let response = app.call(request).await.unwrap();
            let body = to_bytes(response.into_body()).await.unwrap();
            let json: serde_json::Value = serde_json::from_slice(&body).unwrap();
            resource_ids.push(json["data"]["id"].as_str().unwrap().to_string());
        }

        let batch_body = serde_json::json!({
            "operations": [
                {"action": "start", "id": resource_ids[0]},
                {"action": "start", "id": resource_ids[1]},
                {"action": "stop", "id": resource_ids[2]},
                {"action": "invalid_action", "id": resource_ids[0]}
            ]
        });

        let request = Request::builder()
            .uri("/api/v1/resources/batch")
            .method("POST")
            .header("Content-Type", "application/json")
            .body(Body::from(serde_json::to_string(&batch_body).unwrap()))
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::OK);

        let body = to_bytes(response.into_body()).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap();

        assert_eq!(json["code"], 200);
        assert!(json["data"]["batch_id"].as_str().unwrap().starts_with("batch_"));

        let results = json["data"]["results"].as_array().unwrap();
        assert_eq!(results.len(), 4);

        assert!(results[0]["success"].as_bool().unwrap());
        assert!(results[1]["success"].as_bool().unwrap());
        assert!(results[2]["success"].as_bool().unwrap());
        assert!(!results[3]["success"].as_bool().unwrap());
    }

    #[tokio::test]
    async fn test_execute_handler_endpoint() {
        let app = create_test_router();

        let request_body = serde_json::json!({
            "trace_id": "trace_api_test_001",
            "namespace": "production",
            "params": {"validate": true, "timeout": 30},
            "payload": {
                "operation": "data_processing",
                "data": {"records": 1000, "format": "json"}
            }
        });

        let request = Request::builder()
            .uri("/api/v1/execute")
            .method("POST")
            .header("Content-Type", "application/json")
            .header("X-Trace-Id", "trace_api_test_001")
            .body(Body::from(serde_json::to_string(&request_body).unwrap()))
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::OK);

        let body = to_bytes(response.into_body()).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap();

        assert_eq!(json["code"], 200);
        assert_eq!(json["trace_id"], "trace_api_test_001");
        assert!(json["data"].as_object().unwrap().contains_key("id"));
        assert_eq!(json["data"]["status"], "completed");
    }

    #[tokio::test]
    async fn test_invalid_request_payload() {
        let app = create_test_router();

        let invalid_body = "not a valid json";

        let request = Request::builder()
            .uri("/api/v1/resources")
            .method("POST")
            .header("Content-Type", "application/json")
            .body(Body::from(invalid_body))
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }

    #[tokio::test]
    async fn test_cors_headers() {
        let app = create_test_router();

        let request = Request::builder()
            .uri("/health")
            .method("OPTIONS")
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "GET")
            .body(Body::empty())
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        assert!(response.headers().contains_key("access-control-allow-origin"));
        assert!(response.headers().contains_key("access-control-allow-methods"));
    }

    #[tokio::test]
    async fn test_lineage_parse_endpoint() {
        let app = create_test_router();

        let body = serde_json::json!({
            "sql": "SELECT id, name, email FROM users WHERE status = 'active'",
            "source": "adhoc_query_001"
        });

        let request = Request::builder()
            .uri("/api/v1/lineage/parse")
            .method("POST")
            .header("Content-Type", "application/json")
            .body(Body::from(serde_json::to_string(&body).unwrap()))
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::OK);

        let response_body = to_bytes(response.into_body()).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&response_body).unwrap();

        assert_eq!(json["code"], 200);
        assert!(json["data"].as_object().unwrap().contains_key("source_tables"));
    }

    #[tokio::test]
    async fn test_notification_send_endpoint() {
        let app = create_test_router();

        let body = serde_json::json!({
            "channel": "email",
            "recipients": ["user@example.com"],
            "subject": "Test Notification",
            "content": "This is a test notification from API",
            "severity": "info"
        });

        let request = Request::builder()
            .uri("/api/v1/notifications/send")
            .method("POST")
            .header("Content-Type", "application/json")
            .body(Body::from(serde_json::to_string(&body).unwrap()))
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::OK);

        let response_body = to_bytes(response.into_body()).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&response_body).unwrap();

        assert_eq!(json["code"], 200);
    }

    #[tokio::test]
    async fn test_quality_rule_creation() {
        let app = create_test_router();

        let body = serde_json::json!({
            "name": "User Email Completeness",
            "rule_type": "completeness",
            "dataset": {
                "source": "test_db",
                "table": "users",
                "columns": ["email"]
            },
            "severity": "high",
            "parameters": {
                "column": "email",
                "min_complete_rate": 0.95
            }
        });

        let request = Request::builder()
            .uri("/api/v1/quality/rules")
            .method("POST")
            .header("Content-Type", "application/json")
            .body(Body::from(serde_json::to_string(&body).unwrap()))
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::CREATED);

        let response_body = to_bytes(response.into_body()).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&response_body).unwrap();

        assert_eq!(json["code"], 201);
        assert!(json["data"]["rule_id"].as_str().unwrap().starts_with("rule_"));
    }

    #[tokio::test]
    async fn test_metadata_crawl_endpoint() {
        let app = create_test_router();

        let body = serde_json::json!({
            "name": "test_postgres",
            "source_type": "postgres",
            "connection_string": "postgresql://localhost/testdb"
        });

        let request = Request::builder()
            .uri("/api/v1/metadata/sources")
            .method("POST")
            .header("Content-Type", "application/json")
            .body(Body::from(serde_json::to_string(&body).unwrap()))
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::CREATED);

        let response_body = to_bytes(response.into_body()).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&response_body).unwrap();

        assert_eq!(json["code"], 201);
    }

    #[tokio::test]
    async fn test_request_id_header() {
        let app = create_test_router();

        let request = Request::builder()
            .uri("/health")
            .method("GET")
            .header("X-Request-Id", "test-request-id-12345")
            .body(Body::empty())
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::OK);
        assert!(response.headers().contains_key("x-request-id"));
    }

    #[tokio::test]
    async fn test_404_not_found() {
        let app = create_test_router();

        let request = Request::builder()
            .uri("/api/v1/nonexistent/endpoint")
            .method("GET")
            .body(Body::empty())
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn test_method_not_allowed() {
        let app = create_test_router();

        let request = Request::builder()
            .uri("/health")
            .method("DELETE")
            .body(Body::empty())
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::METHOD_NOT_ALLOWED);
    }

    #[tokio::test]
    async fn test_list_templates_endpoint() {
        let app = create_test_router();

        let request = Request::builder()
            .uri("/api/v1/notifications/templates")
            .method("GET")
            .body(Body::empty())
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::OK);

        let body = to_bytes(response.into_body()).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap();

        assert_eq!(json["code"], 200);
        let templates = json["data"].as_array().unwrap();
        assert!(templates.len() >= 3);
    }

    #[tokio::test]
    async fn test_quality_rules_list_endpoint() {
        let app = create_test_router();

        let request = Request::builder()
            .uri("/api/v1/quality/rules")
            .method("GET")
            .body(Body::empty())
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::OK);

        let body = to_bytes(response.into_body()).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap();

        assert_eq!(json["code"], 200);
        assert!(json["data"].is_array());
    }

    #[tokio::test]
    async fn test_metadata_sources_list_endpoint() {
        let app = create_test_router();

        let request = Request::builder()
            .uri("/api/v1/metadata/sources")
            .method("GET")
            .body(Body::empty())
            .unwrap();

        let response = app.call(request).await.unwrap();

        assert_eq!(response.status(), StatusCode::OK);

        let body = to_bytes(response.into_body()).await.unwrap();
        let json: serde_json::Value = serde_json::from_slice(&body).unwrap();

        assert_eq!(json["code"], 200);
        assert!(json["data"].is_array());
    }
}
