use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use chrono::{DateTime, Utc};
use uuid::Uuid;

use crate::utils::error::{GatewayError, Result};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FeatureType {
    Float,
    Integer,
    String,
    Boolean,
    Vector,
    Json,
    Timestamp,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FeatureStatus {
    Draft,
    Active,
    Deprecated,
    Archived,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureDefinition {
    pub feature_id: String,
    pub name: String,
    pub description: String,
    pub feature_type: FeatureType,
    pub entity_type: String,
    pub status: FeatureStatus,
    pub version: u32,
    pub dimensions: Option<usize>,
    pub ttl_seconds: Option<u64>,
    pub tags: Vec<String>,
    pub owner: String,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub schema: Option<serde_json::Value>,
    pub lineage: Vec<FeatureLineage>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureLineage {
    pub parent_feature_id: String,
    pub transformation: String,
    pub applied_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureRegistrationRequest {
    pub name: String,
    pub description: String,
    pub feature_type: FeatureType,
    pub entity_type: String,
    pub dimensions: Option<usize>,
    pub ttl_seconds: Option<u64>,
    pub tags: Vec<String>,
    pub owner: String,
    pub schema: Option<serde_json::Value>,
}

pub struct FeatureRegistry {
    features: parking_lot::RwLock<HashMap<String, FeatureDefinition>>,
    name_index: parking_lot::RwLock<HashMap<String, String>>,
}

impl FeatureRegistry {
    pub fn new() -> Self {
        Self {
            features: parking_lot::RwLock::new(HashMap::new()),
            name_index: parking_lot::RwLock::new(HashMap::new()),
        }
    }

    pub fn register(&self, request: FeatureRegistrationRequest) -> Result<FeatureDefinition> {
        let mut name_index = self.name_index.write();
        if name_index.contains_key(&request.name) {
            return Err(GatewayError::Validation(format!(
                "Feature name '{}' already exists",
                request.name
            )));
        }

        let now = Utc::now();
        let feature_id = format!("feat_{}", Uuid::new_v4().simple());
        
        let feature = FeatureDefinition {
            feature_id: feature_id.clone(),
            name: request.name.clone(),
            description: request.description,
            feature_type: request.feature_type,
            entity_type: request.entity_type,
            status: FeatureStatus::Draft,
            version: 1,
            dimensions: request.dimensions,
            ttl_seconds: request.ttl_seconds,
            tags: request.tags,
            owner: request.owner,
            created_at: now,
            updated_at: now,
            schema: request.schema,
            lineage: Vec::new(),
        };

        self.features.write().insert(feature_id.clone(), feature.clone());
        name_index.insert(request.name, feature_id.clone());

        Ok(feature)
    }

    pub fn get(&self, feature_id: &str) -> Option<FeatureDefinition> {
        self.features.read().get(feature_id).cloned()
    }

    pub fn get_by_name(&self, name: &str) -> Option<FeatureDefinition> {
        self.name_index.read()
            .get(name)
            .and_then(|id| self.features.read().get(id).cloned())
    }

    pub fn list(&self, entity_type: Option<&str>, status: Option<FeatureStatus>) -> Vec<FeatureDefinition> {
        self.features.read()
            .values()
            .filter(|f| {
                entity_type.map_or(true, |et| f.entity_type == et)
                    && status.as_ref().map_or(true, |s| f.status == *s)
            })
            .cloned()
            .collect()
    }

    pub fn update_status(&self, feature_id: &str, status: FeatureStatus) -> Result<FeatureDefinition> {
        let mut features = self.features.write();
        let feature = features.get_mut(feature_id)
            .ok_or_else(|| GatewayError::NotFound(format!("Feature {} not found", feature_id)))?;
        
        feature.status = status;
        feature.updated_at = Utc::now();
        Ok(feature.clone())
    }

    pub fn add_lineage(&self, feature_id: &str, parent_id: &str, transformation: &str) -> Result<FeatureDefinition> {
        let mut features = self.features.write();
        let feature = features.get_mut(feature_id)
            .ok_or_else(|| GatewayError::NotFound(format!("Feature {} not found", feature_id)))?;

        feature.lineage.push(FeatureLineage {
            parent_feature_id: parent_id.to_string(),
            transformation: transformation.to_string(),
            applied_at: Utc::now(),
        });
        feature.updated_at = Utc::now();
        Ok(feature.clone())
    }

    pub fn new_version(&self, feature_id: &str) -> Result<FeatureDefinition> {
        let mut features = self.features.write();
        let feature = features.get_mut(feature_id)
            .ok_or_else(|| GatewayError::NotFound(format!("Feature {} not found", feature_id)))?;

        feature.version += 1;
        feature.updated_at = Utc::now();
        Ok(feature.clone())
    }
}

impl Default for FeatureRegistry {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_feature_registration() {
        let registry = FeatureRegistry::new();

        let request = FeatureRegistrationRequest {
            name: "user_embedding".to_string(),
            description: "User preference embedding".to_string(),
            feature_type: FeatureType::Vector,
            entity_type: "user".to_string(),
            dimensions: Some(1536),
            ttl_seconds: Some(86400),
            tags: vec!["embedding".to_string(), "user".to_string()],
            owner: "data-team".to_string(),
            schema: None,
        };

        let feature = registry.register(request).unwrap();
        assert!(feature.feature_id.starts_with("feat_"));
        assert_eq!(feature.name, "user_embedding");
        assert_eq!(feature.status, FeatureStatus::Draft);
        assert_eq!(feature.version, 1);
        assert_eq!(feature.dimensions, Some(1536));

        let retrieved = registry.get(&feature.feature_id).unwrap();
        assert_eq!(retrieved.name, "user_embedding");

        let by_name = registry.get_by_name("user_embedding").unwrap();
        assert_eq!(by_name.feature_id, feature.feature_id);
    }

    #[test]
    fn test_duplicate_feature_name() {
        let registry = FeatureRegistry::new();

        let request = FeatureRegistrationRequest {
            name: "test_feature".to_string(),
            description: "Test".to_string(),
            feature_type: FeatureType::Float,
            entity_type: "test".to_string(),
            dimensions: None,
            ttl_seconds: None,
            tags: vec![],
            owner: "test".to_string(),
            schema: None,
        };

        registry.register(request.clone()).unwrap();
        let result = registry.register(request);
        assert!(result.is_err());
    }

    #[test]
    fn test_feature_status_update() {
        let registry = FeatureRegistry::new();

        let request = FeatureRegistrationRequest {
            name: "test_feature".to_string(),
            description: "Test".to_string(),
            feature_type: FeatureType::Float,
            entity_type: "test".to_string(),
            dimensions: None,
            ttl_seconds: None,
            tags: vec![],
            owner: "test".to_string(),
            schema: None,
        };

        let feature = registry.register(request).unwrap();
        let updated = registry.update_status(&feature.feature_id, FeatureStatus::Active).unwrap();
        
        assert_eq!(updated.status, FeatureStatus::Active);
        assert!(updated.updated_at > updated.created_at);
    }

    #[test]
    fn test_list_features() {
        let registry = FeatureRegistry::new();

        for i in 0..5 {
            let request = FeatureRegistrationRequest {
                name: format!("feature_{}", i),
                description: format!("Feature {}", i),
                feature_type: FeatureType::Float,
                entity_type: if i < 3 { "user".to_string() } else { "item".to_string() },
                dimensions: None,
                ttl_seconds: None,
                tags: vec![],
                owner: "test".to_string(),
                schema: None,
            };
            registry.register(request).unwrap();
        }

        let user_features = registry.list(Some("user"), None);
        assert_eq!(user_features.len(), 3);

        let all_features = registry.list(None, None);
        assert_eq!(all_features.len(), 5);
    }
}
