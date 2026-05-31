use std::collections::HashMap;
use std::sync::Arc;
use chrono::{DateTime, Utc};
use tracing::{info, instrument, warn};
use crate::models::{Config, Result, RunInstance};
use crate::run::RunPhase;
use crate::snapshot::{Metrics, Snapshot};
use super::domain::{
    Feature, FeatureIngestRequest, FeatureOnlineFetchRequest, FeatureRegistrationRequest,
    OfflineBackfillRequest, OfflineFeaturePoint, OnlineFeatureResponse,
};
use super::ports::{
    ConfigurationProvider, ConsistencyChecker, FeatureRepository, MetricsRecorder,
    OfflineFeatureStore, OnlineFeatureStore,
};

#[derive(Clone)]
pub struct FeatureStoreService {
    feature_repository: Arc<dyn FeatureRepository>,
    online_store: Arc<dyn OnlineFeatureStore>,
    offline_store: Arc<dyn OfflineFeatureStore>,
    consistency_checker: Arc<dyn ConsistencyChecker>,
    metrics_recorder: Arc<dyn MetricsRecorder>,
    config_provider: Arc<dyn ConfigurationProvider>,
}

impl FeatureStoreService {
    pub fn new(
        feature_repository: Arc<dyn FeatureRepository>,
        online_store: Arc<dyn OnlineFeatureStore>,
        offline_store: Arc<dyn OfflineFeatureStore>,
        consistency_checker: Arc<dyn ConsistencyChecker>,
        metrics_recorder: Arc<dyn MetricsRecorder>,
        config_provider: Arc<dyn ConfigurationProvider>,
    ) -> Self {
        Self {
            feature_repository,
            online_store,
            offline_store,
            consistency_checker,
            metrics_recorder,
            config_provider,
        }
    }

    #[instrument(skip(self), fields(feature_name = %request.name))]
    pub async fn register_feature(&self, request: FeatureRegistrationRequest) -> Result<Feature> {
        info!("Registering feature: {}", request.name);

        if request.schema.name.is_empty() {
            return Err(crate::models::ModelGuardError::ValidationError(
                "Feature schema name cannot be empty".to_string(),
            ));
        }

        if request.entity_type.is_empty() {
            return Err(crate::models::ModelGuardError::ValidationError(
                "Entity type cannot be empty".to_string(),
            ));
        }

        if request.source.is_empty() {
            return Err(crate::models::ModelGuardError::ValidationError(
                "Source cannot be empty".to_string(),
            ));
        }

        let mut feature = Feature::new(
            request.name,
            request.schema,
            request.entity_type,
            request.source,
        );

        if let Some(ttl) = request.ttl_seconds {
            feature = feature.with_ttl(ttl);
        }

        let result = self.feature_repository.register_feature(feature).await?;
        self.metrics_recorder.record_success(0.0);
        info!("Feature registered successfully: {}", result.feature_id);
        Ok(result)
    }

    pub async fn get_feature(&self, name: &str) -> Result<Feature> {
        self.feature_repository.get_feature(name).await
    }

    pub async fn feature_exists(&self, name: &str) -> bool {
        self.feature_repository.feature_exists(name).await
    }

    pub async fn list_features(&self) -> Result<Vec<Feature>> {
        self.feature_repository.list_features().await
    }

    #[instrument(skip(self), fields(record_count = %request.records.len()))]
    pub async fn ingest_features(&self, request: FeatureIngestRequest) -> Result<usize> {
        let start = std::time::Instant::now();
        
        if request.records.is_empty() {
            return Ok(0);
        }

        for record in &request.records {
            self.feature_repository
                .validate_feature_value(&record.feature_name, &record.value)
                .await?;
        }

        let record_count = request.records.len();
        let online_count = self.online_store.ingest_batch(request.records.clone()).await?;
        let offline_count = self.offline_store.ingest_batch(request.records).await?;
        
        let latency = start.elapsed().as_millis() as f64;
        self.metrics_recorder.record_success(latency);
        
        info!("Ingested {} feature records (online: {}, offline: {})", 
              record_count, online_count, offline_count);
        Ok(online_count)
    }

    #[instrument(skip(self), fields(entity_id = %request.entity_id))]
    pub async fn fetch_online_features(
        &self,
        request: FeatureOnlineFetchRequest,
    ) -> Result<OnlineFeatureResponse> {
        let start = std::time::Instant::now();

        let features = if request.feature_names.is_empty() {
            HashMap::new()
        } else {
            self.online_store
                .fetch(&request.entity_id, &request.feature_names)
                .await?
        };
        
        let latency = start.elapsed().as_millis() as f64;
        self.metrics_recorder.record_success(latency);

        Ok(OnlineFeatureResponse {
            entity_id: request.entity_id,
            features,
            timestamp: Utc::now(),
            source: "online_store".to_string(),
        })
    }

    #[instrument(skip(self), fields(
        entity_count = %request.entity_ids.len(),
        feature_count = %request.feature_names.len()
    ))]
    pub async fn fetch_offline_features(
        &self,
        request: OfflineBackfillRequest,
    ) -> Result<Vec<OfflineFeaturePoint>> {
        let start = std::time::Instant::now();

        if request.start_time >= request.end_time {
            return Err(crate::models::ModelGuardError::ValidationError(
                "start_time must be before end_time".to_string(),
            ));
        }

        let mut run = RunInstance::new("feature_backfill");
        run.update_phase(RunPhase::Processing, 0.0);

        let points = self.offline_store.fetch(
            &request.entity_ids,
            &request.feature_names,
            request.start_time,
            request.end_time,
        ).await?;

        run.update_phase(RunPhase::Completed, 1.0);
        let latency = start.elapsed().as_millis() as f64;
        self.metrics_recorder.record_success(latency);

        info!(
            "Fetched {} offline feature points in {:?}",
            points.len(),
            run.duration()
        );
        Ok(points)
    }

    pub async fn check_online_offline_consistency(
        &self,
        entity_id: &str,
        feature_name: &str,
    ) -> Result<bool> {
        self.consistency_checker
            .check_consistency(entity_id, feature_name)
            .await
    }

    pub async fn bulk_consistency_check(
        &self,
        entity_ids: &[String],
        feature_names: &[String],
    ) -> Result<HashMap<String, bool>> {
        self.consistency_checker
            .bulk_check(entity_ids, feature_names)
            .await
    }

    pub fn snapshot_metrics(&self, dimensions: HashMap<String, String>) -> Snapshot {
        let metrics_data = self.metrics_recorder.get_metrics();
        let mut metrics = Metrics::new();
        
        for (k, v) in metrics_data {
            match k.as_str() {
                "success_count" => metrics.success_count = v as u64,
                "total_count" => metrics.total_count = v as u64,
                "avg_latency_ms" => metrics.latency_p50 = v,
                "p99_latency_ms" => metrics.latency_p99 = v,
                _ => {}
            }
        }
        
        Snapshot::new(metrics).with_dimensions(dimensions)
    }

    pub async fn get_stats(&self) -> Result<HashMap<String, usize>> {
        let mut stats = HashMap::new();
        stats.insert("registered_features".into(), self.feature_repository.list_features().await?.len());
        Ok(stats)
    }

    pub async fn evict_expired(&self) -> Result<usize> {
        let evicted = self.online_store.evict_expired().await?;
        if evicted > 0 {
            info!("Evicted {} expired feature entries", evicted);
        }
        Ok(evicted)
    }

    pub async fn execute_with_retry<F, Fut, T>(
        &self,
        operation: F,
        max_retries: Option<u32>,
        timeout_ms: Option<u64>,
    ) -> Result<T>
    where
        F: Fn() -> Fut,
        Fut: std::future::Future<Output = Result<T>>,
    {
        let max_retries = max_retries.unwrap_or_else(|| self.config_provider.get_retry_count());
        let timeout_ms = timeout_ms.unwrap_or_else(|| self.config_provider.get_timeout_ms());
        let mut last_error = None;
        
        for attempt in 0..max_retries {
            match tokio::time::timeout(
                std::time::Duration::from_millis(timeout_ms),
                operation(),
            )
            .await
            {
                Ok(Ok(result)) => return Ok(result),
                Ok(Err(e)) => {
                    warn!("Attempt {} failed: {}", attempt + 1, e);
                    last_error = Some(e);
                }
                Err(_) => {
                    warn!("Attempt {} timed out", attempt + 1);
                    last_error = Some(crate::models::ModelGuardError::TimeoutError(format!(
                        "Operation timed out after {}ms",
                        timeout_ms
                    )));
                }
            }
            
            if attempt < max_retries - 1 {
                let backoff = std::time::Duration::from_millis(100 * 2u64.pow(attempt));
                tokio::time::sleep(backoff).await;
            }
        }
        
        Err(last_error.unwrap_or_else(|| {
            crate::models::ModelGuardError::InternalError("All retry attempts failed".to_string())
        }))
    }
}

impl FeatureStoreService {
    pub fn with_in_memory_backend(config: Config) -> Self {
        use super::in_memory::{
            ConfigBasedConfigurationProvider, DefaultConsistencyChecker, DefaultMetricsRecorder,
            InMemoryFeatureRepository, InMemoryOfflineStore, InMemoryOnlineStore,
        };

        let config_provider = ConfigBasedConfigurationProvider::new(config);
        let feature_repository = InMemoryFeatureRepository::new();
        let online_store = InMemoryOnlineStore::new(config_provider.clone());
        let offline_store = InMemoryOfflineStore::new();
        let consistency_checker =
            DefaultConsistencyChecker::new(online_store.clone(), offline_store.clone());
        let metrics_recorder = DefaultMetricsRecorder::new();

        Self::new(
            feature_repository,
            online_store,
            offline_store,
            consistency_checker,
            metrics_recorder,
            config_provider,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::feature_store::domain::{FeatureSchema, FeatureType, FeatureValue, FeatureRecord};
    use serde_json::json;

    fn create_test_config() -> Config {
        Config::new("test", json!({"feature_ttl": 3600, "timeout": 30000, "retries": 3}))
    }

    #[tokio::test]
    async fn test_register_feature() {
        let service = FeatureStoreService::with_in_memory_backend(create_test_config());
        let schema = FeatureSchema::new("age", FeatureType::Int)
            .with_description("User age in years");
        
        let request = FeatureRegistrationRequest {
            name: "age".to_string(),
            entity_type: "user".to_string(),
            source: "mysql.users".to_string(),
            schema,
            ttl_seconds: Some(86400),
        };

        let feature = service.register_feature(request).await.unwrap();
        assert_eq!(feature.name, "age");
        assert_eq!(feature.entity_type, "user");
        assert_eq!(feature.ttl_seconds, Some(86400));
    }

    #[tokio::test]
    async fn test_ingest_and_fetch() {
        let service = FeatureStoreService::with_in_memory_backend(create_test_config());
        
        let schema = FeatureSchema::new("age", FeatureType::Int);
        let request = FeatureRegistrationRequest {
            name: "age".to_string(),
            entity_type: "user".to_string(),
            source: "test".to_string(),
            schema,
            ttl_seconds: None,
        };
        service.register_feature(request).await.unwrap();

        let ingest_req = FeatureIngestRequest {
            records: vec![FeatureRecord {
                entity_id: "user1".to_string(),
                feature_name: "age".to_string(),
                value: FeatureValue::Int(25),
                timestamp: Utc::now(),
            }],
        };

        let count = service.ingest_features(ingest_req).await.unwrap();
        assert_eq!(count, 1);

        let fetch_req = FeatureOnlineFetchRequest {
            entity_id: "user1".to_string(),
            feature_names: vec!["age".to_string()],
        };

        let response = service.fetch_online_features(fetch_req).await.unwrap();
        assert_eq!(response.entity_id, "user1");
        assert!(matches!(
            response.features.get("age"),
            Some(FeatureValue::Int(25))
        ));
    }

    #[tokio::test]
    async fn test_invalid_feature_value() {
        let service = FeatureStoreService::with_in_memory_backend(create_test_config());
        
        let schema = FeatureSchema::new("age", FeatureType::Int);
        let request = FeatureRegistrationRequest {
            name: "age".to_string(),
            entity_type: "user".to_string(),
            source: "test".to_string(),
            schema,
            ttl_seconds: None,
        };
        service.register_feature(request).await.unwrap();

        let ingest_req = FeatureIngestRequest {
            records: vec![FeatureRecord {
                entity_id: "user1".to_string(),
                feature_name: "age".to_string(),
                value: FeatureValue::String("invalid".to_string()),
                timestamp: Utc::now(),
            }],
        };

        let result = service.ingest_features(ingest_req).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_offline_fetch() {
        let service = FeatureStoreService::with_in_memory_backend(create_test_config());
        
        let schema = FeatureSchema::new("age", FeatureType::Int);
        let request = FeatureRegistrationRequest {
            name: "age".to_string(),
            entity_type: "user".to_string(),
            source: "test".to_string(),
            schema,
            ttl_seconds: None,
        };
        service.register_feature(request).await.unwrap();

        let now = Utc::now();
        let mut records = Vec::new();
        for i in 0..3 {
            records.push(FeatureRecord {
                entity_id: "user1".to_string(),
                feature_name: "age".to_string(),
                value: FeatureValue::Int(20 + i),
                timestamp: now - chrono::Duration::minutes(i as i64),
            });
        }

        service
            .ingest_features(FeatureIngestRequest { records })
            .await
            .unwrap();

        let start = now - chrono::Duration::minutes(10);
        let end = now;
        
        let backfill_req = OfflineBackfillRequest {
            feature_names: vec!["age".to_string()],
            entity_ids: vec!["user1".to_string()],
            start_time: start,
            end_time: end,
            interval_seconds: None,
        };

        let points = service.fetch_offline_features(backfill_req).await.unwrap();
        assert_eq!(points.len(), 3);
    }

    #[tokio::test]
    async fn test_snapshot_metrics() {
        let service = FeatureStoreService::with_in_memory_backend(create_test_config());
        let dimensions = HashMap::from([("host".to_string(), "node-1".to_string())]);
        let snapshot = service.snapshot_metrics(dimensions);
        
        assert!(snapshot.snapshot_id.starts_with("snap_"));
        assert_eq!(snapshot.dimensions.get("host").unwrap(), "node-1");
    }

    #[tokio::test]
    async fn test_consistency_check() {
        let service = FeatureStoreService::with_in_memory_backend(create_test_config());
        
        let schema = FeatureSchema::new("age", FeatureType::Int);
        let request = FeatureRegistrationRequest {
            name: "age".to_string(),
            entity_type: "user".to_string(),
            source: "test".to_string(),
            schema,
            ttl_seconds: None,
        };
        service.register_feature(request).await.unwrap();

        let ingest_req = FeatureIngestRequest {
            records: vec![FeatureRecord {
                entity_id: "user1".to_string(),
                feature_name: "age".to_string(),
                value: FeatureValue::Int(25),
                timestamp: Utc::now(),
            }],
        };
        service.ingest_features(ingest_req).await.unwrap();

        let consistent = service.check_online_offline_consistency("user1", "age").await.unwrap();
        assert!(consistent);
    }

    #[tokio::test]
    async fn test_list_features() {
        let service = FeatureStoreService::with_in_memory_backend(create_test_config());
        
        let schema = FeatureSchema::new("age", FeatureType::Int);
        let request = FeatureRegistrationRequest {
            name: "age".to_string(),
            entity_type: "user".to_string(),
            source: "test".to_string(),
            schema,
            ttl_seconds: None,
        };
        service.register_feature(request).await.unwrap();

        let features = service.list_features().await.unwrap();
        assert_eq!(features.len(), 1);
    }
}
