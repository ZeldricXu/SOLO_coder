use serde::{Deserialize, Serialize};
use std::sync::Arc;
use chrono::Utc;

use crate::utils::error::{GatewayError, Result};
use crate::utils::metrics::MetricsCollector;
use crate::models::Config;

use super::registry::{FeatureRegistry, FeatureRegistrationRequest, FeatureDefinition, FeatureStatus};
use super::online::{OnlineFeatureStore, FeatureStoreRequest, FeatureLookupRequest, FeatureLookupResponse, FeatureValue};
use super::offline::{OfflineFeatureStore, HistoricalFeatureValue, PointInTimeLookupRequest, PointInTimeLookupResponse, TrainingDataRequest, TrainingDataResponse, ConsistencyCheckRequest, ConsistencyCheckResult, DriftDetectionResult};
use super::versioning::{FeatureVersionManager, CreateVersionRequest, VersionType, VersionStats, FeatureVersion, FeatureValueVersion, FeatureVersionSnapshot, RollbackResult, VersionDiff};
use super::monitoring::{FeatureStoreMonitor, FeatureStoreMonitorSnapshot, MonitoringConfig, OperationGuard};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureStoreStats {
    pub registered_features: usize,
    pub online_feature_count: usize,
    pub offline_record_count: usize,
    pub total_lookups: u64,
    pub total_inserts: u64,
    pub version_stats: Option<VersionStats>,
    pub monitor_snapshot: Option<FeatureStoreMonitorSnapshot>,
}

pub struct FeatureStoreService {
    registry: Arc<FeatureRegistry>,
    online: Arc<OnlineFeatureStore>,
    offline: Arc<OfflineFeatureStore>,
    version_manager: Arc<FeatureVersionManager>,
    monitor: Arc<FeatureStoreMonitor>,
    metrics: MetricsCollector,
    config: Config,
}

impl FeatureStoreService {
    pub fn new(config: Config, metrics: MetricsCollector) -> Self {
        let monitor = Arc::new(FeatureStoreMonitor::new(MonitoringConfig::default()));
        Self {
            registry: Arc::new(FeatureRegistry::new()),
            online: Arc::new(OnlineFeatureStore::new()),
            offline: Arc::new(OfflineFeatureStore::new()),
            version_manager: Arc::new(FeatureVersionManager::new()),
            monitor,
            metrics,
            config,
        }
    }

    pub fn with_monitoring_config(mut self, monitoring_config: MonitoringConfig) -> Self {
        self.monitor = Arc::new(FeatureStoreMonitor::new(monitoring_config));
        self
    }

    pub fn register_feature(&self, request: FeatureRegistrationRequest) -> Result<FeatureDefinition> {
        let _guard = self.monitor.start_operation("register_feature", None, None);
        let feature = self.registry.register(request)?;
        self.online.register_feature_name(&feature.name, &feature.feature_id);
        self.registry.update_status(&feature.feature_id, FeatureStatus::Active)?;
        Ok(feature)
    }

    pub fn get_feature_definition(&self, feature_id: &str) -> Option<FeatureDefinition> {
        let _guard = self.monitor.start_operation("get_feature_definition", Some(feature_id), None);
        self.monitor.record_feature_access(feature_id);
        self.registry.get(feature_id)
    }

    pub fn get_feature_by_name(&self, name: &str) -> Option<FeatureDefinition> {
        let _guard = self.monitor.start_operation("get_feature_by_name", None, None);
        self.registry.get_by_name(name)
    }

    pub fn list_features(
        &self,
        entity_type: Option<&str>,
        status: Option<FeatureStatus>,
    ) -> Vec<FeatureDefinition> {
        let _guard = self.monitor.start_operation("list_features", None, None);
        self.registry.list(entity_type, status)
    }

    pub fn insert_feature(&self, request: FeatureStoreRequest) -> Result<FeatureValue> {
        let _guard = self.monitor.start_operation("insert_feature", Some(&request.feature_id), Some(&request.entity_id));
        self.metrics.increment_counter("feature_insert");
        let value = self.online.insert(request.clone())?;

        self.version_manager.record_value_version(
            &request, 
            "insert", 
            None
        );

        let historical = HistoricalFeatureValue {
            feature_id: value.feature_id.clone(),
            entity_id: value.entity_id.clone(),
            value: value.value.clone(),
            event_timestamp: value.timestamp,
            ingested_timestamp: Utc::now(),
            version: value.version,
        };
        self.offline.ingest(vec![historical])?;

        Ok(value)
    }

    pub fn insert_features_batch(&self, requests: Vec<FeatureStoreRequest>) -> Result<Vec<FeatureValue>> {
        let _guard = self.monitor.start_operation("insert_features_batch", None, None);
        let mut results = Vec::new();
        for request in requests {
            results.push(self.insert_feature(request)?);
        }
        Ok(results)
    }

    pub fn lookup_features(&self, request: FeatureLookupRequest) -> Result<FeatureLookupResponse> {
        let _guard = self.monitor.start_operation("lookup_features", None, None);
        for fid in &request.feature_ids {
            self.monitor.record_feature_access(fid);
        }
        for eid in &request.entity_ids {
            self.monitor.record_entity_access(eid);
        }
        self.metrics.increment_counter("feature_lookup");
        self.online.lookup(request)
    }

    pub fn get_online_feature(&self, feature_id: &str, entity_id: &str) -> Option<FeatureValue> {
        let _guard = self.monitor.start_operation("get_online_feature", Some(feature_id), Some(entity_id));
        self.monitor.record_feature_access(feature_id);
        self.monitor.record_entity_access(entity_id);
        self.online.get(feature_id, entity_id)
    }

    pub fn point_in_time_lookup(
        &self,
        request: PointInTimeLookupRequest,
    ) -> Result<PointInTimeLookupResponse> {
        let _guard = self.monitor.start_operation("point_in_time_lookup", None, None);
        for fid in &request.feature_ids {
            self.monitor.record_feature_access(fid);
        }
        self.offline.point_in_time_lookup(request)
    }

    pub fn get_training_data(&self, request: TrainingDataRequest) -> Result<TrainingDataResponse> {
        let _guard = self.monitor.start_operation("get_training_data", None, None);
        self.offline.get_training_data(request)
    }

    pub fn check_consistency(
        &self,
        request: ConsistencyCheckRequest,
    ) -> Result<ConsistencyCheckResult> {
        let _guard = self.monitor.start_operation("check_consistency", None, None);
        self.offline.check_consistency(request)
    }

    pub fn detect_drift(
        &self,
        feature_id: &str,
        baseline_days: i64,
        current_days: i64,
        threshold: f64,
    ) -> Result<DriftDetectionResult> {
        let _guard = self.monitor.start_operation("detect_drift", Some(feature_id), None);
        self.monitor.record_feature_access(feature_id);
        let now = Utc::now();
        self.offline.detect_drift(
            feature_id,
            now - chrono::Duration::days(baseline_days * 2),
            now - chrono::Duration::days(baseline_days),
            now - chrono::Duration::days(current_days),
            now,
            threshold,
        )
    }

    pub fn stats(&self) -> FeatureStoreStats {
        let _guard = self.monitor.start_operation("stats", None, None);
        let registered = self.registry.list(None, None).len();
        let online_count = self.online.get_all_features().len();
        let offline_count = self.offline.get_all_values().len();

        FeatureStoreStats {
            registered_features: registered,
            online_feature_count: online_count,
            offline_record_count: offline_count,
            total_lookups: self.metrics.get_counter("feature_lookup"),
            total_inserts: self.metrics.get_counter("feature_insert"),
            version_stats: Some(self.version_manager.get_stats()),
            monitor_snapshot: Some(self.monitor.snapshot()),
        }
    }

    pub fn monitor_snapshot(&self) -> FeatureStoreMonitorSnapshot {
        self.monitor.snapshot()
    }

    pub fn export_prometheus_metrics(&self) -> String {
        self.monitor.export_prometheus_metrics()
    }

    pub fn reset_monitor_stats(&self) {
        self.monitor.reset_stats();
    }

    pub fn create_feature_version(&self, request: CreateVersionRequest) -> Result<FeatureVersion> {
        let feature = self.registry.get(&request.feature_id)
            .ok_or_else(|| GatewayError::NotFound(format!("Feature {} not found", request.feature_id)))?;
        self.version_manager.create_feature_version(request, &feature)
    }

    pub fn get_feature_version(&self, feature_id: &str, version_number: u32) -> Option<FeatureVersion> {
        self.version_manager.get_feature_version(feature_id, version_number)
    }

    pub fn get_latest_version(&self, feature_id: &str) -> Option<FeatureVersion> {
        self.version_manager.get_latest_version(feature_id)
    }

    pub fn list_feature_versions(&self, feature_id: &str) -> Vec<FeatureVersion> {
        self.version_manager.list_feature_versions(feature_id)
    }

    pub fn compare_versions(&self, feature_id: &str, from_version: u32, to_version: u32) -> Result<VersionDiff> {
        self.version_manager.compare_versions(feature_id, from_version, to_version)
    }

    pub fn rollback_feature_version(&self, feature_id: &str, target_version: u32, created_by: &str) -> Result<RollbackResult> {
        self.version_manager.rollback_feature_version(feature_id, target_version, created_by)
    }

    pub fn get_value_versions(&self, feature_id: &str, entity_id: &str) -> Vec<FeatureValueVersion> {
        self.version_manager.get_value_versions(feature_id, entity_id)
    }

    pub fn get_value_at_version(&self, feature_id: &str, entity_id: &str, version_number: u32) -> Option<FeatureValueVersion> {
        self.version_manager.get_value_at_version(feature_id, entity_id, version_number)
    }

    pub fn create_snapshot(&self, name: String, description: String, feature_ids: Vec<String>, created_by: String) -> FeatureVersionSnapshot {
        self.version_manager.create_snapshot(name, description, feature_ids, created_by)
    }

    pub fn get_snapshot(&self, snapshot_id: &str) -> Option<FeatureVersionSnapshot> {
        self.version_manager.get_snapshot(snapshot_id)
    }

    pub fn list_snapshots(&self) -> Vec<FeatureVersionSnapshot> {
        self.version_manager.list_snapshots()
    }

    pub fn delete_snapshot(&self, snapshot_id: &str) -> Result<()> {
        self.version_manager.delete_snapshot(snapshot_id)
    }

    pub fn protect_snapshot(&self, snapshot_id: &str) -> Result<FeatureVersionSnapshot> {
        self.version_manager.protect_snapshot(snapshot_id)
    }

    pub fn registry(&self) -> &FeatureRegistry {
        &self.registry
    }

    pub fn online_store(&self) -> &OnlineFeatureStore {
        &self.online
    }

    pub fn offline_store(&self) -> &OfflineFeatureStore {
        &self.offline
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::feature_store::registry::FeatureType;

    fn create_test_service() -> FeatureStoreService {
        let config = Config::new("test", 1);
        let metrics = MetricsCollector::new();
        FeatureStoreService::new(config, metrics)
    }

    #[test]
    fn test_full_feature_flow() {
        let service = create_test_service();

        let feature = service.register_feature(FeatureRegistrationRequest {
            name: "test_feature".to_string(),
            description: "Test feature".to_string(),
            feature_type: FeatureType::Float,
            entity_type: "user".to_string(),
            dimensions: None,
            ttl_seconds: None,
            tags: vec![],
            owner: "test".to_string(),
            schema: None,
        }).unwrap();

        assert!(service.get_feature_definition(&feature.feature_id).is_some());
        assert!(service.get_feature_by_name("test_feature").is_some());

        let value = service.insert_feature(FeatureStoreRequest {
            feature_id: feature.feature_id.clone(),
            entity_id: "user_1".to_string(),
            value: serde_json::json!(0.95),
            version: None,
        }).unwrap();

        assert_eq!(value.value, serde_json::json!(0.95));

        let lookup_request = FeatureLookupRequest {
            entity_type: "user".to_string(),
            entity_ids: vec!["user_1".to_string()],
            feature_names: vec!["test_feature".to_string()],
        };

        let response = service.lookup_features(lookup_request).unwrap();
        assert_eq!(response.entity_features.len(), 1);
        assert_eq!(
            response.entity_features["user_1"]["test_feature"].value,
            serde_json::json!(0.95)
        );

        let stats = service.stats();
        assert_eq!(stats.registered_features, 1);
        assert_eq!(stats.total_inserts, 1);
        assert_eq!(stats.total_lookups, 1);
    }

    #[test]
    fn test_feature_listing() {
        let service = create_test_service();

        for i in 0..5 {
            service.register_feature(FeatureRegistrationRequest {
                name: format!("feature_{}", i),
                description: format!("Feature {}", i),
                feature_type: FeatureType::Float,
                entity_type: if i < 3 { "user".to_string() } else { "item".to_string() },
                dimensions: None,
                ttl_seconds: None,
                tags: vec![],
                owner: "test".to_string(),
                schema: None,
            }).unwrap();
        }

        let user_features = service.list_features(Some("user"), None);
        assert_eq!(user_features.len(), 3);

        let all_features = service.list_features(None, Some(FeatureStatus::Active));
        assert_eq!(all_features.len(), 5);
    }

    #[test]
    fn test_online_offline_consistency() {
        let service = create_test_service();

        let feature = service.register_feature(FeatureRegistrationRequest {
            name: "consistent_feature".to_string(),
            description: "Test".to_string(),
            feature_type: FeatureType::Float,
            entity_type: "user".to_string(),
            dimensions: None,
            ttl_seconds: None,
            tags: vec![],
            owner: "test".to_string(),
            schema: None,
        }).unwrap();

        service.insert_feature(FeatureStoreRequest {
            feature_id: feature.feature_id.clone(),
            entity_id: "user_1".to_string(),
            value: serde_json::json!(0.75),
            version: None,
        }).unwrap();

        let online = service.get_online_feature(&feature.feature_id, "user_1").unwrap();
        
        let check_request = ConsistencyCheckRequest {
            entity_id: "user_1".to_string(),
            feature_id: feature.feature_id.clone(),
            online_value: online.value.clone(),
            timestamp: chrono::Utc::now(),
        };

        let result = service.check_consistency(check_request).unwrap();
        assert!(result.is_consistent);
    }
}
