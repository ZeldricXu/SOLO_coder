use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use chrono::{DateTime, Utc};
use dashmap::DashMap;

use crate::utils::error::{GatewayError, Result};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureValue {
    pub feature_id: String,
    pub entity_id: String,
    pub value: serde_json::Value,
    pub timestamp: DateTime<Utc>,
    pub version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureLookupRequest {
    pub entity_type: String,
    pub entity_ids: Vec<String>,
    pub feature_names: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureLookupResponse {
    pub entity_features: HashMap<String, HashMap<String, FeatureValue>>,
    pub missing_features: Vec<String>,
    pub lookup_time_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureStoreRequest {
    pub feature_id: String,
    pub entity_id: String,
    pub value: serde_json::Value,
    pub version: Option<u32>,
}

pub struct OnlineFeatureStore {
    features: Arc<DashMap<String, Arc<DashMap<String, FeatureValue>>>>,
    feature_name_map: Arc<DashMap<String, String>>,
}

impl OnlineFeatureStore {
    pub fn new() -> Self {
        Self {
            features: Arc::new(DashMap::new()),
            feature_name_map: Arc::new(DashMap::new()),
        }
    }

    pub fn register_feature_name(&self, feature_name: &str, feature_id: &str) {
        self.feature_name_map.insert(feature_name.to_string(), feature_id.to_string());
    }

    pub fn insert(&self, request: FeatureStoreRequest) -> Result<FeatureValue> {
        let feature_value = FeatureValue {
            feature_id: request.feature_id.clone(),
            entity_id: request.entity_id.clone(),
            value: request.value,
            timestamp: Utc::now(),
            version: request.version.unwrap_or(1),
        };

        self.features
            .entry(request.feature_id)
            .or_insert_with(|| Arc::new(DashMap::new()))
            .insert(request.entity_id, feature_value.clone());

        Ok(feature_value)
    }

    pub fn insert_batch(&self, requests: Vec<FeatureStoreRequest>) -> Result<Vec<FeatureValue>> {
        let mut results = Vec::new();
        for request in requests {
            results.push(self.insert(request)?);
        }
        Ok(results)
    }

    pub fn get(&self, feature_id: &str, entity_id: &str) -> Option<FeatureValue> {
        self.features
            .get(feature_id)
            .and_then(|entities| entities.get(entity_id).map(|v| v.clone()))
    }

    pub fn lookup(&self, request: FeatureLookupRequest) -> Result<FeatureLookupResponse> {
        let start = std::time::Instant::now();
        let mut results = HashMap::new();
        let mut missing = Vec::new();

        let feature_ids: Vec<String> = request.feature_names
            .iter()
            .map(|name| {
                self.feature_name_map
                    .get(name)
                    .map(|id| id.clone())
                    .unwrap_or_else(|| name.clone())
            })
            .collect();

        for entity_id in &request.entity_ids {
            let mut entity_features = HashMap::new();
            
            for (name, feature_id) in request.feature_names.iter().zip(feature_ids.iter()) {
                match self.get(feature_id, entity_id) {
                    Some(value) => {
                        entity_features.insert(name.clone(), value);
                    }
                    None => {
                        if !missing.contains(name) {
                            missing.push(name.clone());
                        }
                    }
                }
            }
            
            results.insert(entity_id.clone(), entity_features);
        }

        Ok(FeatureLookupResponse {
            entity_features: results,
            missing_features: missing,
            lookup_time_ms: start.elapsed().as_millis() as u64,
        })
    }

    pub fn delete(&self, feature_id: &str, entity_id: &str) -> bool {
        self.features
            .get(feature_id)
            .map(|entities| entities.remove(entity_id).is_some())
            .unwrap_or(false)
    }

    pub fn get_feature_entities(&self, feature_id: &str) -> Vec<String> {
        self.features
            .get(feature_id)
            .map(|entities| entities.iter().map(|e| e.key().clone()).collect())
            .unwrap_or_default()
    }

    pub fn get_all_features(&self) -> Vec<String> {
        self.features.iter().map(|f| f.key().clone()).collect()
    }
}

impl Default for OnlineFeatureStore {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_insert_and_get() {
        let store = OnlineFeatureStore::new();

        let request = FeatureStoreRequest {
            feature_id: "feat_1".to_string(),
            entity_id: "user_1".to_string(),
            value: serde_json::json!(0.85),
            version: None,
        };

        let inserted = store.insert(request).unwrap();
        assert_eq!(inserted.feature_id, "feat_1");
        assert_eq!(inserted.entity_id, "user_1");
        assert_eq!(inserted.value, serde_json::json!(0.85));
        assert_eq!(inserted.version, 1);

        let retrieved = store.get("feat_1", "user_1").unwrap();
        assert_eq!(retrieved.value, serde_json::json!(0.85));
    }

    #[test]
    fn test_lookup() {
        let store = OnlineFeatureStore::new();
        store.register_feature_name("click_rate", "feat_click");
        store.register_feature_name("purchase_rate", "feat_purchase");

        for i in 0..3 {
            store.insert(FeatureStoreRequest {
                feature_id: "feat_click".to_string(),
                entity_id: format!("user_{}", i),
                value: serde_json::json!(0.1 + i as f64 * 0.1),
                version: None,
            }).unwrap();

            store.insert(FeatureStoreRequest {
                feature_id: "feat_purchase".to_string(),
                entity_id: format!("user_{}", i),
                value: serde_json::json!(0.05 + i as f64 * 0.05),
                version: None,
            }).unwrap();
        }

        let lookup_request = FeatureLookupRequest {
            entity_type: "user".to_string(),
            entity_ids: vec!["user_0".to_string(), "user_1".to_string()],
            feature_names: vec!["click_rate".to_string(), "purchase_rate".to_string()],
        };

        let response = store.lookup(lookup_request).unwrap();
        assert_eq!(response.entity_features.len(), 2);
        assert_eq!(response.entity_features.get("user_0").unwrap().len(), 2);
        assert!(response.missing_features.is_empty());
        assert!(response.lookup_time_ms >= 0);
    }

    #[test]
    fn test_delete() {
        let store = OnlineFeatureStore::new();

        store.insert(FeatureStoreRequest {
            feature_id: "feat_1".to_string(),
            entity_id: "user_1".to_string(),
            value: serde_json::json!(42),
            version: None,
        }).unwrap();

        assert!(store.get("feat_1", "user_1").is_some());
        assert!(store.delete("feat_1", "user_1"));
        assert!(store.get("feat_1", "user_1").is_none());
    }

    #[test]
    fn test_batch_insert() {
        let store = OnlineFeatureStore::new();
        let mut requests = Vec::new();

        for i in 0..10 {
            requests.push(FeatureStoreRequest {
                feature_id: "feat_1".to_string(),
                entity_id: format!("user_{}", i),
                value: serde_json::json!(i),
                version: None,
            });
        }

        let results = store.insert_batch(requests).unwrap();
        assert_eq!(results.len(), 10);

        let entities = store.get_feature_entities("feat_1");
        assert_eq!(entities.len(), 10);
    }

    #[test]
    fn test_missing_features_in_lookup() {
        let store = OnlineFeatureStore::new();
        
        let lookup_request = FeatureLookupRequest {
            entity_type: "user".to_string(),
            entity_ids: vec!["user_1".to_string()],
            feature_names: vec!["missing_feature".to_string()],
        };

        let response = store.lookup(lookup_request).unwrap();
        assert_eq!(response.missing_features.len(), 1);
        assert_eq!(response.missing_features[0], "missing_feature");
    }
}
