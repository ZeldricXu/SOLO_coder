use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Hash, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ModelStage {
    Staging,
    Production,
    Archived,
    Deprecated,
}

impl ModelStage {
    pub fn can_transition_to(&self, new_stage: &ModelStage) -> bool {
        matches!(
            (self, new_stage),
            (ModelStage::Staging, ModelStage::Production)
                | (ModelStage::Staging, ModelStage::Archived)
                | (ModelStage::Production, ModelStage::Staging)
                | (ModelStage::Production, ModelStage::Archived)
                | (ModelStage::Production, ModelStage::Deprecated)
                | (ModelStage::Archived, ModelStage::Staging)
        )
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            ModelStage::Staging => "staging",
            ModelStage::Production => "production",
            ModelStage::Archived => "archived",
            ModelStage::Deprecated => "deprecated",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelMetadata {
    pub description: Option<String>,
    pub author: Option<String>,
    pub tags: Vec<String>,
    pub framework: Option<String>,
    pub algorithm: Option<String>,
    pub metrics: HashMap<String, f64>,
    pub artifacts: HashMap<String, String>,
    pub custom: serde_json::Value,
}

impl Default for ModelMetadata {
    fn default() -> Self {
        Self {
            description: None,
            author: None,
            tags: Vec::new(),
            framework: None,
            algorithm: None,
            metrics: HashMap::new(),
            artifacts: HashMap::new(),
            custom: serde_json::Value::Object(serde_json::Map::new()),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelVersion {
    pub version_id: String,
    pub model_id: String,
    pub version: u32,
    pub stage: ModelStage,
    pub metadata: ModelMetadata,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl ModelVersion {
    pub fn new(model_id: impl Into<String>, version: u32) -> Self {
        let now = Utc::now();
        Self {
            version_id: format!("ver_{}", Uuid::new_v4().simple()),
            model_id: model_id.into(),
            version,
            stage: ModelStage::Staging,
            metadata: ModelMetadata::default(),
            created_at: now,
            updated_at: now,
        }
    }

    pub fn with_metadata(mut self, metadata: ModelMetadata) -> Self {
        self.metadata = metadata;
        self
    }

    pub fn transition_stage(&mut self, new_stage: ModelStage) -> Result<(), String> {
        if self.stage.can_transition_to(&new_stage) {
            self.stage = new_stage;
            self.updated_at = Utc::now();
            Ok(())
        } else {
            Err(format!(
                "Cannot transition from {:?} to {:?}",
                self.stage, new_stage
            ))
        }
    }

    pub fn add_metric(&mut self, name: impl Into<String>, value: f64) {
        self.metadata.metrics.insert(name.into(), value);
        self.updated_at = Utc::now();
    }

    pub fn add_artifact(&mut self, name: impl Into<String>, path: impl Into<String>) {
        self.metadata.artifacts.insert(name.into(), path.into());
        self.updated_at = Utc::now();
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Model {
    pub model_id: String,
    pub name: String,
    pub latest_version: u32,
    pub versions: Vec<ModelVersion>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl Model {
    pub fn new(name: impl Into<String>) -> Self {
        let now = Utc::now();
        Self {
            model_id: format!("mod_{}", Uuid::new_v4().simple()),
            name: name.into(),
            latest_version: 0,
            versions: Vec::new(),
            created_at: now,
            updated_at: now,
        }
    }

    pub fn create_version(&mut self, metadata: Option<ModelMetadata>) -> &ModelVersion {
        let new_version_num = self.latest_version + 1;
        let mut version = ModelVersion::new(self.model_id.clone(), new_version_num);
        
        if let Some(md) = metadata {
            version = version.with_metadata(md);
        }
        
        self.versions.push(version);
        self.latest_version = new_version_num;
        self.updated_at = Utc::now();
        
        self.versions.last().unwrap()
    }

    pub fn get_version(&self, version: u32) -> Option<&ModelVersion> {
        self.versions.iter().find(|v| v.version == version)
    }

    pub fn get_version_mut(&mut self, version: u32) -> Option<&mut ModelVersion> {
        self.versions.iter_mut().find(|v| v.version == version)
    }

    pub fn get_latest_version(&self) -> Option<&ModelVersion> {
        self.versions.iter().max_by_key(|v| v.version)
    }

    pub fn get_version_by_stage(&self, stage: &ModelStage) -> Option<&ModelVersion> {
        self.versions.iter().find(|v| &v.stage == stage)
    }

    pub fn transition_version_stage(
        &mut self,
        version: u32,
        new_stage: ModelStage,
    ) -> Result<(), String> {
        if new_stage == ModelStage::Production {
            for v in self.versions.iter_mut() {
                if v.stage == ModelStage::Production && v.version != version {
                    v.stage = ModelStage::Archived;
                }
            }
        }
        
        let version = self
            .versions
            .iter_mut()
            .find(|v| v.version == version)
            .ok_or_else(|| format!("Version {} not found", version))?;
        
        version.transition_stage(new_stage)?;
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn list_versions(&self) -> Vec<&ModelVersion> {
        let mut versions: Vec<&ModelVersion> = self.versions.iter().collect();
        versions.sort_by(|a, b| b.version.cmp(&a.version));
        versions
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct ModelRegistrationRequest {
    pub name: String,
    pub description: Option<String>,
    pub metadata: Option<ModelMetadata>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct VersionCreateRequest {
    pub model_id: String,
    pub metadata: Option<ModelMetadata>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct StageTransitionRequest {
    pub model_id: String,
    pub version: u32,
    pub target_stage: ModelStage,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_stage_transitions() {
        assert!(ModelStage::Staging.can_transition_to(&ModelStage::Production));
        assert!(ModelStage::Production.can_transition_to(&ModelStage::Archived));
        assert!(!ModelStage::Staging.can_transition_to(&ModelStage::Deprecated));
        assert!(!ModelStage::Deprecated.can_transition_to(&ModelStage::Production));
    }

    #[test]
    fn test_model_creation() {
        let model = Model::new("test_model");
        assert!(model.model_id.starts_with("mod_"));
        assert_eq!(model.name, "test_model");
        assert_eq!(model.latest_version, 0);
        assert_eq!(model.versions.len(), 0);
    }

    #[test]
    fn test_version_creation() {
        let mut model = Model::new("test_model");
        let metadata = ModelMetadata {
            framework: Some("pytorch".to_string()),
            algorithm: Some("transformer".to_string()),
            ..Default::default()
        };
        
        let v1 = model.create_version(Some(metadata));
        assert_eq!(v1.version, 1);
        assert_eq!(v1.stage, ModelStage::Staging);
        assert_eq!(model.latest_version, 1);
        assert_eq!(model.versions.len(), 1);
        
        let v2 = model.create_version(None);
        assert_eq!(v2.version, 2);
        assert_eq!(model.latest_version, 2);
    }

    #[test]
    fn test_version_stage_transition() {
        let mut model = Model::new("test_model");
        model.create_version(None);
        
        let result = model.transition_version_stage(1, ModelStage::Production);
        assert!(result.is_ok());
        
        let v1 = model.get_version(1).unwrap();
        assert_eq!(v1.stage, ModelStage::Production);
        
        let result = model.transition_version_stage(1, ModelStage::Deprecated);
        assert!(result.is_ok());
        
        let result = model.transition_version_stage(1, ModelStage::Production);
        assert!(result.is_err());
    }

    #[test]
    fn test_get_latest_version() {
        let mut model = Model::new("test_model");
        model.create_version(None);
        model.create_version(None);
        model.create_version(None);
        
        let latest = model.get_latest_version().unwrap();
        assert_eq!(latest.version, 3);
    }

    #[test]
    fn test_get_version_by_stage() {
        let mut model = Model::new("test_model");
        model.create_version(None);
        model.create_version(None);
        
        model.transition_version_stage(1, ModelStage::Production).unwrap();
        
        let prod = model.get_version_by_stage(&ModelStage::Production).unwrap();
        assert_eq!(prod.version, 1);
    }

    #[test]
    fn test_version_metadata() {
        let mut version = ModelVersion::new("mod_001", 1);
        version.add_metric("accuracy", 0.95);
        version.add_artifact("model", "s3://model.bin");
        
        assert_eq!(version.metadata.metrics.get("accuracy"), Some(&0.95));
        assert_eq!(version.metadata.artifacts.get("model"), Some(&"s3://model.bin".to_string()));
    }
}
