use std::collections::HashMap;
use async_trait::async_trait;
use chrono::{DateTime, Utc};
use crate::models::Result;
use super::domain::{Feature, FeatureRecord, FeatureValue, OfflineFeaturePoint};

#[async_trait]
pub trait FeatureRepository: Send + Sync {
    async fn register_feature(&self, feature: Feature) -> Result<Feature>;
    async fn get_feature(&self, name: &str) -> Result<Feature>;
    async fn list_features(&self) -> Result<Vec<Feature>>;
    async fn feature_exists(&self, name: &str) -> bool;
    async fn validate_feature_value(&self, feature_name: &str, value: &FeatureValue) -> Result<()>;
}

#[async_trait]
pub trait OnlineFeatureStore: Send + Sync {
    async fn ingest(&self, record: FeatureRecord) -> Result<()>;
    async fn ingest_batch(&self, records: Vec<FeatureRecord>) -> Result<usize>;
    async fn fetch(&self, entity_id: &str, feature_names: &[String]) -> Result<HashMap<String, FeatureValue>>;
    async fn evict_expired(&self) -> Result<usize>;
}

#[async_trait]
pub trait OfflineFeatureStore: Send + Sync {
    async fn ingest(&self, record: FeatureRecord) -> Result<()>;
    async fn ingest_batch(&self, records: Vec<FeatureRecord>) -> Result<usize>;
    async fn fetch(
        &self,
        entity_ids: &[String],
        feature_names: &[String],
        start_time: DateTime<Utc>,
        end_time: DateTime<Utc>,
    ) -> Result<Vec<OfflineFeaturePoint>>;
}

#[async_trait]
pub trait ConsistencyChecker: Send + Sync {
    async fn check_consistency(
        &self,
        entity_id: &str,
        feature_name: &str,
    ) -> Result<bool>;
    
    async fn bulk_check(
        &self,
        entity_ids: &[String],
        feature_names: &[String],
    ) -> Result<HashMap<String, bool>>;
}

pub trait MetricsRecorder: Send + Sync {
    fn record_success(&self, latency_ms: f64);
    fn record_error(&self);
    fn get_metrics(&self) -> HashMap<String, f64>;
}

pub trait ConfigurationProvider: Send + Sync {
    fn get_ttl_seconds(&self) -> u64;
    fn get_retry_count(&self) -> u32;
    fn get_timeout_ms(&self) -> u64;
}
