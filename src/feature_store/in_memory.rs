use std::collections::HashMap;
use std::sync::Arc;
use async_trait::async_trait;
use chrono::{DateTime, Duration, Utc};
use dashmap::DashMap;
use parking_lot::RwLock;
use crate::models::{Config, ModelGuardError, Result};
use crate::snapshot::Metrics;
use super::domain::{Feature, FeatureRecord, FeatureValue, OfflineFeaturePoint};
use super::ports::{
    FeatureRepository, OnlineFeatureStore, OfflineFeatureStore, 
    ConsistencyChecker, MetricsRecorder, ConfigurationProvider
};

pub struct InMemoryFeatureRepository {
    features: Arc<DashMap<String, Feature>>,
}

impl InMemoryFeatureRepository {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            features: Arc::new(DashMap::new()),
        })
    }
}

#[async_trait]
impl FeatureRepository for InMemoryFeatureRepository {
    async fn register_feature(&self, feature: Feature) -> Result<Feature> {
        if self.features.contains_key(&feature.name) {
            return Err(ModelGuardError::Conflict(format!(
                "Feature {} already exists",
                feature.name
            )));
        }
        self.features.insert(feature.name.clone(), feature.clone());
        Ok(feature)
    }

    async fn get_feature(&self, name: &str) -> Result<Feature> {
        self.features
            .get(name)
            .map(|f| f.clone())
            .ok_or_else(|| ModelGuardError::NotFound(format!("Feature {} not found", name)))
    }

    async fn list_features(&self) -> Result<Vec<Feature>> {
        Ok(self.features.iter().map(|f| f.clone()).collect())
    }

    async fn feature_exists(&self, name: &str) -> bool {
        self.features.contains_key(name)
    }

    async fn validate_feature_value(&self, feature_name: &str, value: &FeatureValue) -> Result<()> {
        let feature = self
            .features
            .get(feature_name)
            .ok_or_else(|| ModelGuardError::NotFound(format!("Feature {} not found", feature_name)))?;
        
        if matches!(value, FeatureValue::Null) {
            if !feature.schema.nullable {
                return Err(ModelGuardError::ValidationError(format!(
                    "Feature {} is not nullable but received null value",
                    feature_name
                )));
            }
            return Ok(());
        }
        
        if !feature.schema.validate_value(value) {
            return Err(ModelGuardError::ValidationError(format!(
                "Invalid value type for feature {}, expected {:?}",
                feature_name, feature.schema.feature_type
            )));
        }
        Ok(())
    }
}

pub struct InMemoryOnlineStore {
    store: Arc<DashMap<(String, String), (FeatureValue, DateTime<Utc>)>>,
    config_provider: Arc<dyn ConfigurationProvider>,
}

impl InMemoryOnlineStore {
    pub fn new(config_provider: Arc<dyn ConfigurationProvider>) -> Arc<Self> {
        Arc::new(Self {
            store: Arc::new(DashMap::new()),
            config_provider,
        })
    }
}

#[async_trait]
impl OnlineFeatureStore for InMemoryOnlineStore {
    async fn ingest(&self, record: FeatureRecord) -> Result<()> {
        let key = (record.entity_id, record.feature_name);
        let should_update = match self.store.get(&key) {
            Some(existing) => record.timestamp >= existing.1,
            None => true,
        };
        if should_update {
            self.store.insert(key, (record.value, record.timestamp));
        }
        Ok(())
    }

    async fn ingest_batch(&self, records: Vec<FeatureRecord>) -> Result<usize> {
        let mut count = 0;
        for record in records {
            self.ingest(record).await?;
            count += 1;
        }
        Ok(count)
    }

    async fn fetch(&self, entity_id: &str, feature_names: &[String]) -> Result<HashMap<String, FeatureValue>> {
        let mut features = HashMap::new();
        let ttl = self.config_provider.get_ttl_seconds();
        let now = Utc::now();

        for name in feature_names {
            let key = (entity_id.to_string(), name.clone());
            if let Some(entry) = self.store.get(&key) {
                let (value, timestamp) = entry.clone();
                let age = now.signed_duration_since(timestamp);
                if age < Duration::seconds(ttl as i64) {
                    features.insert(name.clone(), value);
                }
            }
        }
        Ok(features)
    }

    async fn evict_expired(&self) -> Result<usize> {
        let ttl = self.config_provider.get_ttl_seconds();
        let now = Utc::now();
        let mut evicted = 0;

        let to_remove: Vec<_> = self
            .store
            .iter()
            .filter(|entry| {
                let (_, (_, timestamp)) = entry.pair();
                now.signed_duration_since(*timestamp) >= Duration::seconds(ttl as i64)
            })
            .map(|entry| entry.key().clone())
            .collect();

        for key in to_remove {
            self.store.remove(&key);
            evicted += 1;
        }
        Ok(evicted)
    }
}

pub struct InMemoryOfflineStore {
    store: Arc<RwLock<Vec<FeatureRecord>>>,
}

impl InMemoryOfflineStore {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            store: Arc::new(RwLock::new(Vec::new())),
        })
    }
}

#[async_trait]
impl OfflineFeatureStore for InMemoryOfflineStore {
    async fn ingest(&self, record: FeatureRecord) -> Result<()> {
        self.store.write().push(record);
        Ok(())
    }

    async fn ingest_batch(&self, records: Vec<FeatureRecord>) -> Result<usize> {
        let mut store = self.store.write();
        let count = records.len();
        store.extend(records);
        Ok(count)
    }

    async fn fetch(
        &self,
        entity_ids: &[String],
        feature_names: &[String],
        start_time: DateTime<Utc>,
        end_time: DateTime<Utc>,
    ) -> Result<Vec<OfflineFeaturePoint>> {
        let store = self.store.read();
        let mut points = Vec::new();

        for record in store.iter() {
            if entity_ids.contains(&record.entity_id)
                && feature_names.contains(&record.feature_name)
                && record.timestamp >= start_time
                && record.timestamp <= end_time
            {
                points.push(OfflineFeaturePoint {
                    entity_id: record.entity_id.clone(),
                    feature_name: record.feature_name.clone(),
                    value: record.value.clone(),
                    timestamp: record.timestamp,
                });
            }
        }

        points.sort_by(|a, b| a.timestamp.cmp(&b.timestamp));
        Ok(points)
    }
}

pub struct DefaultConsistencyChecker {
    online_store: Arc<dyn OnlineFeatureStore>,
    offline_store: Arc<dyn OfflineFeatureStore>,
}

impl DefaultConsistencyChecker {
    pub fn new(
        online_store: Arc<dyn OnlineFeatureStore>,
        offline_store: Arc<dyn OfflineFeatureStore>,
    ) -> Arc<Self> {
        Arc::new(Self {
            online_store,
            offline_store,
        })
    }
}

#[async_trait]
impl ConsistencyChecker for DefaultConsistencyChecker {
    async fn check_consistency(&self, entity_id: &str, feature_name: &str) -> Result<bool> {
        let online_features = self.online_store.fetch(entity_id, &[feature_name.to_string()]).await?;
        let online = online_features.get(feature_name);
        
        let now = Utc::now();
        let five_min_ago = now - Duration::minutes(5);
        let offline = self.offline_store.fetch(
            &[entity_id.to_string()],
            &[feature_name.to_string()],
            five_min_ago,
            now,
        ).await?;
        
        let latest_offline = offline.last();
        
        match (online, latest_offline) {
            (Some(ov), Some(off)) => {
                Ok(format!("{:?}", ov) == format!("{:?}", off.value))
            }
            (None, None) => Ok(false),
            _ => Ok(false),
        }
    }

    async fn bulk_check(
        &self,
        entity_ids: &[String],
        feature_names: &[String],
    ) -> Result<HashMap<String, bool>> {
        let mut results = HashMap::new();
        for entity_id in entity_ids {
            for feature_name in feature_names {
                let key = format!("{}:{}", entity_id, feature_name);
                let consistent = self.check_consistency(entity_id, feature_name).await?;
                results.insert(key, consistent);
            }
        }
        Ok(results)
    }
}

pub struct DefaultMetricsRecorder {
    metrics: Arc<parking_lot::Mutex<Metrics>>,
}

impl DefaultMetricsRecorder {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            metrics: Arc::new(parking_lot::Mutex::new(Metrics::new())),
        })
    }
}

impl MetricsRecorder for DefaultMetricsRecorder {
    fn record_success(&self, latency_ms: f64) {
        self.metrics.lock().record_success(latency_ms);
    }

    fn record_error(&self) {
        self.metrics.lock().record_error();
    }

    fn get_metrics(&self) -> HashMap<String, f64> {
        let m = self.metrics.lock();
        let mut result = HashMap::new();
        result.insert("success_count".into(), m.success_count as f64);
        result.insert("total_count".into(), m.total_count as f64);
        result.insert("avg_latency_ms".into(), m.latency_p50);
        result.insert("p99_latency_ms".into(), m.latency_p99);
        result
    }
}

pub struct ConfigBasedConfigurationProvider {
    config: Arc<RwLock<Config>>,
}

impl ConfigBasedConfigurationProvider {
    pub fn new(config: Config) -> Arc<Self> {
        Arc::new(Self {
            config: Arc::new(RwLock::new(config)),
        })
    }

    pub fn update_config(&self, new_config: Config) {
        let mut config = self.config.write();
        *config = new_config;
    }
}

impl ConfigurationProvider for ConfigBasedConfigurationProvider {
    fn get_ttl_seconds(&self) -> u64 {
        self.config.read().get_param::<u64>("feature_ttl").unwrap_or(86400)
    }

    fn get_retry_count(&self) -> u32 {
        self.config.read().get_param::<u32>("retries").unwrap_or(3)
    }

    fn get_timeout_ms(&self) -> u64 {
        self.config.read().get_param::<u64>("timeout").unwrap_or(30000)
    }
}
