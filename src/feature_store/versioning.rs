use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use uuid::Uuid;

use crate::utils::error::{GatewayError, Result};
use super::registry::{FeatureDefinition, FeatureStatus, FeatureRegistrationRequest};
use super::online::{FeatureValue, FeatureStoreRequest};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum VersionType {
    Major,
    Minor,
    Patch,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureVersion {
    pub version_id: String,
    pub feature_id: String,
    pub version_number: u32,
    pub version_type: VersionType,
    pub definition: FeatureDefinition,
    pub created_at: DateTime<Utc>,
    pub created_by: String,
    pub changelog: String,
    pub is_active: bool,
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureValueVersion {
    pub version_id: String,
    pub feature_id: String,
    pub entity_id: String,
    pub value: serde_json::Value,
    pub version_number: u32,
    pub timestamp: DateTime<Utc>,
    pub created_by: Option<String>,
    pub operation: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionDiff {
    pub feature_id: String,
    pub from_version: u32,
    pub to_version: u32,
    pub changes: Vec<FieldChange>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FieldChange {
    pub field: String,
    pub old_value: Option<serde_json::Value>,
    pub new_value: Option<serde_json::Value>,
    pub change_type: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateVersionRequest {
    pub feature_id: String,
    pub version_type: VersionType,
    pub changelog: String,
    pub created_by: String,
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureVersionSnapshot {
    pub snapshot_id: String,
    pub name: String,
    pub description: String,
    pub feature_versions: HashMap<String, u32>,
    pub created_at: DateTime<Utc>,
    pub created_by: String,
    pub is_protected: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RollbackResult {
    pub feature_id: String,
    pub from_version: u32,
    pub to_version: u32,
    pub rolled_back_at: DateTime<Utc>,
    pub success: bool,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionStats {
    pub total_versions: u64,
    pub active_versions: u64,
    pub total_value_versions: u64,
    pub snapshots_count: u64,
    pub last_version_created: Option<DateTime<Utc>>,
}

pub struct FeatureVersionManager {
    feature_versions: Arc<DashMap<String, Vec<FeatureVersion>>>,
    value_versions: Arc<DashMap<String, Vec<FeatureValueVersion>>>,
    snapshots: Arc<DashMap<String, FeatureVersionSnapshot>>,
    max_versions_per_feature: usize,
    max_value_versions: usize,
}

impl FeatureVersionManager {
    pub fn new() -> Self {
        Self {
            feature_versions: Arc::new(DashMap::new()),
            value_versions: Arc::new(DashMap::new()),
            snapshots: Arc::new(DashMap::new()),
            max_versions_per_feature: 100,
            max_value_versions: 1000,
        }
    }

    pub fn with_limits(max_feature_versions: usize, max_value_versions: usize) -> Self {
        Self {
            feature_versions: Arc::new(DashMap::new()),
            value_versions: Arc::new(DashMap::new()),
            snapshots: Arc::new(DashMap::new()),
            max_versions_per_feature: max_feature_versions,
            max_value_versions,
        }
    }

    pub fn create_feature_version(
        &self,
        request: CreateVersionRequest,
        current_definition: &FeatureDefinition,
    ) -> Result<FeatureVersion> {
        let version_id = format!("ver_{}", Uuid::new_v4().simple());
        let now = Utc::now();

        let mut new_version = current_definition.clone();
        
        let versions = self.feature_versions
            .entry(request.feature_id.clone())
            .or_insert_with(Vec::new);
        
        let next_version_number = versions.len() as u32 + 1;
        
        let version = FeatureVersion {
            version_id: version_id.clone(),
            feature_id: request.feature_id.clone(),
            version_number: next_version_number,
            version_type: request.version_type,
            definition: new_version,
            created_at: now,
            created_by: request.created_by,
            changelog: request.changelog,
            is_active: true,
            tags: request.tags,
        };

        versions.push(version.clone());

        if versions.len() > self.max_versions_per_feature {
            let to_remove = versions.len() - self.max_versions_per_feature;
            versions.drain(0..to_remove);
        }

        Ok(version)
    }

    pub fn get_feature_version(&self, feature_id: &str, version_number: u32) -> Option<FeatureVersion> {
        self.feature_versions
            .get(feature_id)
            .and_then(|versions| {
                versions.iter()
                    .find(|v| v.version_number == version_number)
                    .cloned()
            })
    }

    pub fn get_latest_version(&self, feature_id: &str) -> Option<FeatureVersion> {
        self.feature_versions
            .get(feature_id)
            .and_then(|versions| versions.last().cloned())
    }

    pub fn list_feature_versions(&self, feature_id: &str) -> Vec<FeatureVersion> {
        self.feature_versions
            .get(feature_id)
            .map(|versions| versions.clone())
            .unwrap_or_default()
    }

    pub fn compare_versions(
        &self,
        feature_id: &str,
        from_version: u32,
        to_version: u32,
    ) -> Result<VersionDiff> {
        let versions = self.feature_versions
            .get(feature_id)
            .ok_or_else(|| GatewayError::NotFound(format!("Feature {} not found", feature_id)))?;

        let from = versions.iter()
            .find(|v| v.version_number == from_version)
            .ok_or_else(|| GatewayError::NotFound(format!("Version {} not found", from_version)))?;

        let to = versions.iter()
            .find(|v| v.version_number == to_version)
            .ok_or_else(|| GatewayError::NotFound(format!("Version {} not found", to_version)))?;

        let mut changes = Vec::new();

        if from.definition.name != to.definition.name {
            changes.push(FieldChange {
                field: "name".to_string(),
                old_value: Some(serde_json::json!(from.definition.name)),
                new_value: Some(serde_json::json!(to.definition.name)),
                change_type: "modified".to_string(),
            });
        }

        if from.definition.description != to.definition.description {
            changes.push(FieldChange {
                field: "description".to_string(),
                old_value: Some(serde_json::json!(from.definition.description)),
                new_value: Some(serde_json::json!(to.definition.description)),
                change_type: "modified".to_string(),
            });
        }

        if from.definition.status != to.definition.status {
            changes.push(FieldChange {
                field: "status".to_string(),
                old_value: Some(serde_json::json!(format!("{:?}", from.definition.status))),
                new_value: Some(serde_json::json!(format!("{:?}", to.definition.status))),
                change_type: "modified".to_string(),
            });
        }

        Ok(VersionDiff {
            feature_id: feature_id.to_string(),
            from_version,
            to_version,
            changes,
        })
    }

    pub fn rollback_feature_version(
        &self,
        feature_id: &str,
        target_version: u32,
        created_by: &str,
    ) -> Result<RollbackResult> {
        let target = self.get_feature_version(feature_id, target_version)
            .ok_or_else(|| GatewayError::NotFound(format!("Version {} not found", target_version)))?;

        let current_version = self.get_latest_version(feature_id)
            .ok_or_else(|| GatewayError::NotFound(format!("Feature {} has no versions", feature_id)))?;

        let rollback_version = self.create_feature_version(
            CreateVersionRequest {
                feature_id: feature_id.to_string(),
                version_type: VersionType::Patch,
                changelog: format!("Rollback to version {}", target_version),
                created_by: created_by.to_string(),
                tags: vec!["rollback".to_string()],
            },
            &target.definition,
        )?;

        Ok(RollbackResult {
            feature_id: feature_id.to_string(),
            from_version: current_version.version_number,
            to_version: rollback_version.version_number,
            rolled_back_at: Utc::now(),
            success: true,
            message: format!("Successfully rolled back to version {}", target_version),
        })
    }

    pub fn record_value_version(
        &self,
        request: &FeatureStoreRequest,
        operation: &str,
        created_by: Option<String>,
    ) -> FeatureValueVersion {
        let version_id = format!("vval_{}", Uuid::new_v4().simple());
        let now = Utc::now();

        let key = format!("{}:{}", request.feature_id, request.entity_id);
        let versions = self.value_versions
            .entry(key)
            .or_insert_with(Vec::new);

        let next_version = versions.len() as u32 + 1;

        let value_version = FeatureValueVersion {
            version_id,
            feature_id: request.feature_id.clone(),
            entity_id: request.entity_id.clone(),
            value: request.value.clone(),
            version_number: next_version,
            timestamp: now,
            created_by,
            operation: operation.to_string(),
        };

        versions.push(value_version.clone());

        if versions.len() > self.max_value_versions {
            let to_remove = versions.len() - self.max_value_versions;
            versions.drain(0..to_remove);
        }

        value_version
    }

    pub fn get_value_versions(
        &self,
        feature_id: &str,
        entity_id: &str,
    ) -> Vec<FeatureValueVersion> {
        let key = format!("{}:{}", feature_id, entity_id);
        self.value_versions
            .get(&key)
            .map(|versions| versions.clone())
            .unwrap_or_default()
    }

    pub fn get_value_at_version(
        &self,
        feature_id: &str,
        entity_id: &str,
        version_number: u32,
    ) -> Option<FeatureValueVersion> {
        let key = format!("{}:{}", feature_id, entity_id);
        self.value_versions
            .get(&key)
            .and_then(|versions| {
                versions.iter()
                    .find(|v| v.version_number == version_number)
                    .cloned()
            })
    }

    pub fn create_snapshot(
        &self,
        name: String,
        description: String,
        feature_ids: Vec<String>,
        created_by: String,
    ) -> FeatureVersionSnapshot {
        let snapshot_id = format!("snap_{}", Uuid::new_v4().simple());
        let mut feature_versions = HashMap::new();

        for feature_id in &feature_ids {
            if let Some(latest) = self.get_latest_version(feature_id) {
                feature_versions.insert(feature_id.clone(), latest.version_number);
            }
        }

        let snapshot = FeatureVersionSnapshot {
            snapshot_id: snapshot_id.clone(),
            name,
            description,
            feature_versions,
            created_at: Utc::now(),
            created_by,
            is_protected: false,
        };

        self.snapshots.insert(snapshot_id, snapshot.clone());
        snapshot
    }

    pub fn get_snapshot(&self, snapshot_id: &str) -> Option<FeatureVersionSnapshot> {
        self.snapshots.get(snapshot_id).map(|s| s.clone())
    }

    pub fn list_snapshots(&self) -> Vec<FeatureVersionSnapshot> {
        self.snapshots.iter().map(|s| s.clone()).collect()
    }

    pub fn delete_snapshot(&self, snapshot_id: &str) -> Result<()> {
        let snapshot = self.snapshots.get(snapshot_id)
            .ok_or_else(|| GatewayError::NotFound(format!("Snapshot {} not found", snapshot_id)))?;

        if snapshot.is_protected {
            return Err(GatewayError::Validation("Cannot delete protected snapshot".to_string()));
        }

        self.snapshots.remove(snapshot_id);
        Ok(())
    }

    pub fn protect_snapshot(&self, snapshot_id: &str) -> Result<FeatureVersionSnapshot> {
        let mut snapshot = self.snapshots.get_mut(snapshot_id)
            .ok_or_else(|| GatewayError::NotFound(format!("Snapshot {} not found", snapshot_id)))?;

        snapshot.is_protected = true;
        Ok(snapshot.clone())
    }

    pub fn get_stats(&self) -> VersionStats {
        let mut total_feature_versions = 0u64;
        let mut active_feature_versions = 0u64;
        let mut last_created: Option<DateTime<Utc>> = None;

        for entry in self.feature_versions.iter() {
            let versions = entry.value();
            total_feature_versions += versions.len() as u64;
            active_feature_versions += versions.iter().filter(|v| v.is_active).count() as u64;
            
            if let Some(latest) = versions.last() {
                if last_created.map_or(true, |dt| latest.created_at > dt) {
                    last_created = Some(latest.created_at);
                }
            }
        }

        let total_value_versions = self.value_versions.iter()
            .map(|entry| entry.value().len() as u64)
            .sum();

        VersionStats {
            total_versions: total_feature_versions,
            active_versions: active_feature_versions,
            total_value_versions,
            snapshots_count: self.snapshots.len() as u64,
            last_version_created: last_created,
        }
    }
}

impl Default for FeatureVersionManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::feature_store::registry::{FeatureType, FeatureStatus};

    fn create_test_feature_definition() -> FeatureDefinition {
        FeatureDefinition {
            feature_id: "feat_test".to_string(),
            name: "test_feature".to_string(),
            description: "Test feature".to_string(),
            feature_type: FeatureType::Float,
            entity_type: "user".to_string(),
            status: FeatureStatus::Active,
            version: 1,
            dimensions: None,
            ttl_seconds: None,
            tags: vec![],
            owner: "test".to_string(),
            created_at: Utc::now(),
            updated_at: Utc::now(),
            schema: None,
            data_sources: vec![],
            transformation: None,
        }
    }

    #[test]
    fn test_create_feature_version() {
        let manager = FeatureVersionManager::new();
        let feature = create_test_feature_definition();

        let version = manager.create_feature_version(
            CreateVersionRequest {
                feature_id: feature.feature_id.clone(),
                version_type: VersionType::Major,
                changelog: "Initial version".to_string(),
                created_by: "test_user".to_string(),
                tags: vec!["initial".to_string()],
            },
            &feature,
        ).unwrap();

        assert_eq!(version.version_number, 1);
        assert_eq!(version.version_type, VersionType::Major);
        assert_eq!(version.created_by, "test_user");
        assert!(version.is_active);
    }

    #[test]
    fn test_get_latest_version() {
        let manager = FeatureVersionManager::new();
        let feature = create_test_feature_definition();

        manager.create_feature_version(
            CreateVersionRequest {
                feature_id: feature.feature_id.clone(),
                version_type: VersionType::Major,
                changelog: "v1".to_string(),
                created_by: "test".to_string(),
                tags: vec![],
            },
            &feature,
        ).unwrap();

        let mut v2 = manager.create_feature_version(
            CreateVersionRequest {
                feature_id: feature.feature_id.clone(),
                version_type: VersionType::Minor,
                changelog: "v2".to_string(),
                created_by: "test".to_string(),
                tags: vec![],
            },
            &feature,
        ).unwrap();

        let latest = manager.get_latest_version(&feature.feature_id).unwrap();
        assert_eq!(latest.version_number, 2);
    }

    #[test]
    fn test_list_feature_versions() {
        let manager = FeatureVersionManager::new();
        let feature = create_test_feature_definition();

        for i in 0..3 {
            manager.create_feature_version(
                CreateVersionRequest {
                    feature_id: feature.feature_id.clone(),
                    version_type: VersionType::Minor,
                    changelog: format!("Version {}", i + 1),
                    created_by: "test".to_string(),
                    tags: vec![],
                },
                &feature,
            ).unwrap();
        }

        let versions = manager.list_feature_versions(&feature.feature_id);
        assert_eq!(versions.len(), 3);
    }

    #[test]
    fn test_value_versioning() {
        let manager = FeatureVersionManager::new();
        
        let request = FeatureStoreRequest {
            feature_id: "feat_1".to_string(),
            entity_id: "user_1".to_string(),
            value: serde_json::json!(0.5),
            version: None,
        };

        let v1 = manager.record_value_version(&request, "create", Some("user_a".to_string()));
        assert_eq!(v1.version_number, 1);

        let request2 = FeatureStoreRequest {
            feature_id: "feat_1".to_string(),
            entity_id: "user_1".to_string(),
            value: serde_json::json!(0.75),
            version: None,
        };

        let v2 = manager.record_value_version(&request2, "update", Some("user_b".to_string()));
        assert_eq!(v2.version_number, 2);

        let versions = manager.get_value_versions("feat_1", "user_1");
        assert_eq!(versions.len(), 2);
    }

    #[test]
    fn test_snapshot_creation() {
        let manager = FeatureVersionManager::new();
        let feature = create_test_feature_definition();

        manager.create_feature_version(
            CreateVersionRequest {
                feature_id: feature.feature_id.clone(),
                version_type: VersionType::Major,
                changelog: "v1".to_string(),
                created_by: "test".to_string(),
                tags: vec![],
            },
            &feature,
        ).unwrap();

        let snapshot = manager.create_snapshot(
            "release-1.0".to_string(),
            "Production release".to_string(),
            vec![feature.feature_id.clone()],
            "release_manager".to_string(),
        );

        assert_eq!(snapshot.name, "release-1.0");
        assert_eq!(snapshot.feature_versions.len(), 1);
        assert!(!snapshot.is_protected);
    }

    #[test]
    fn test_rollback() {
        let manager = FeatureVersionManager::new();
        let mut feature = create_test_feature_definition();

        manager.create_feature_version(
            CreateVersionRequest {
                feature_id: feature.feature_id.clone(),
                version_type: VersionType::Major,
                changelog: "v1".to_string(),
                created_by: "test".to_string(),
                tags: vec![],
            },
            &feature,
        ).unwrap();

        feature.description = "Updated description".to_string();
        manager.create_feature_version(
            CreateVersionRequest {
                feature_id: feature.feature_id.clone(),
                version_type: VersionType::Minor,
                changelog: "v2".to_string(),
                created_by: "test".to_string(),
                tags: vec![],
            },
            &feature,
        ).unwrap();

        let result = manager.rollback_feature_version(
            &feature.feature_id,
            1,
            "rollback_user",
        ).unwrap();

        assert!(result.success);
        assert_eq!(result.from_version, 2);
        assert_eq!(result.to_version, 3);
    }

    #[test]
    fn test_version_stats() {
        let manager = FeatureVersionManager::new();
        let feature = create_test_feature_definition();

        for i in 0..5 {
            manager.create_feature_version(
                CreateVersionRequest {
                    feature_id: feature.feature_id.clone(),
                    version_type: VersionType::Minor,
                    changelog: format!("v{}", i + 1),
                    created_by: "test".to_string(),
                    tags: vec![],
                },
                &feature,
            ).unwrap();
        }

        let stats = manager.get_stats();
        assert_eq!(stats.total_versions, 5);
        assert_eq!(stats.active_versions, 5);
        assert_eq!(stats.snapshots_count, 0);
    }

    #[test]
    fn test_protect_snapshot() {
        let manager = FeatureVersionManager::new();

        let snapshot = manager.create_snapshot(
            "prod".to_string(),
            "Production".to_string(),
            vec![],
            "admin".to_string(),
        );

        let protected = manager.protect_snapshot(&snapshot.snapshot_id).unwrap();
        assert!(protected.is_protected);

        let result = manager.delete_snapshot(&snapshot.snapshot_id);
        assert!(result.is_err());
    }
}
