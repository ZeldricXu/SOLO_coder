use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use parking_lot::Mutex;

use crate::utils::error::Result;
use crate::utils::metrics::MetricsCollector;

use super::model::{Model, ModelRegistrationRequest, ModelUpdateRequest};
use super::version::{ModelVersion, VersionRegistrationRequest, VersionBumpRequest};
use super::stage::{StageManager, Stage, StageTransitionRequest, StageTransition};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelWithLatestVersion {
    pub model: Model,
    pub latest_version: Option<ModelVersion>,
    pub current_stage: Option<Stage>,
}

pub struct ModelRegistryService {
    metrics: MetricsCollector,
    models: Arc<Mutex<HashMap<String, Model>>>,
    versions: Arc<Mutex<HashMap<String, Vec<ModelVersion>>>>,
    stage_manager: StageManager,
}

impl ModelRegistryService {
    pub fn new(metrics: MetricsCollector) -> Self {
        Self {
            metrics,
            models: Arc::new(Mutex::new(HashMap::new())),
            versions: Arc::new(Mutex::new(HashMap::new())),
            stage_manager: StageManager::new(),
        }
    }

    pub fn register_model(&self, request: ModelRegistrationRequest) -> Result<Model> {
        self.metrics.increment_counter("model_registration");
        
        let model = Model::new(request);
        self.models.lock().insert(model.model_id.clone(), model.clone());
        self.versions.lock().insert(model.model_id.clone(), Vec::new());
        
        Ok(model)
    }

    pub fn get_model(&self, model_id: &str) -> Option<Model> {
        self.models.lock().get(model_id).cloned()
    }

    pub fn get_model_with_latest_version(&self, model_id: &str) -> Option<ModelWithLatestVersion> {
        let model = self.get_model(model_id)?;
        let latest_version = self.get_latest_version(model_id);
        let current_stage = self.stage_manager.get_current_stage(model_id);
        
        Some(ModelWithLatestVersion {
            model,
            latest_version,
            current_stage,
        })
    }

    pub fn list_models(&self, include_inactive: bool) -> Vec<Model> {
        self.models.lock()
            .values()
            .filter(|m| include_inactive || m.is_active)
            .cloned()
            .collect()
    }

    pub fn update_model(&self, model_id: &str, updates: ModelUpdateRequest) -> Result<Model> {
        let mut models = self.models.lock();
        let model = models.get_mut(model_id)
            .ok_or_else(|| crate::utils::error::AppError::NotFound(format!(
                "Model {} not found", model_id
            )))?;
        
        model.update(updates);
        self.metrics.increment_counter("model_updated");
        Ok(model.clone())
    }

    pub fn delete_model(&self, model_id: &str) -> Result<()> {
        let mut models = self.models.lock();
        if models.remove(model_id).is_none() {
            return Err(crate::utils::error::AppError::NotFound(format!(
                "Model {} not found", model_id
            )));
        }
        self.versions.lock().remove(model_id);
        self.metrics.increment_counter("model_deleted");
        Ok(())
    }

    pub fn register_version(&self, request: VersionRegistrationRequest) -> Result<ModelVersion> {
        if !self.models.lock().contains_key(&request.model_id) {
            return Err(crate::utils::error::AppError::NotFound(format!(
                "Model {} not found", request.model_id
            )));
        }

        self.metrics.increment_counter("version_registration");
        
        let mut versions = self.versions.lock();
        let model_versions = versions.entry(request.model_id.clone()).or_insert_with(Vec::new);
        
        for v in model_versions.iter_mut() {
            v.is_latest = false;
        }
        
        let version = ModelVersion::new(request).map_err(|e| 
            crate::utils::error::AppError::Validation(e)
        )?;
        
        model_versions.push(version.clone());
        model_versions.sort_by(|a, b| b.semantic_version.cmp(&a.semantic_version));
        
        Ok(version)
    }

    pub fn get_versions(&self, model_id: &str) -> Vec<ModelVersion> {
        self.versions.lock()
            .get(model_id)
            .cloned()
            .unwrap_or_default()
    }

    pub fn get_version(&self, model_id: &str, version_id: &str) -> Option<ModelVersion> {
        self.get_versions(model_id)
            .into_iter()
            .find(|v| v.version_id == version_id)
    }

    pub fn get_latest_version(&self, model_id: &str) -> Option<ModelVersion> {
        self.get_versions(model_id)
            .into_iter()
            .find(|v| v.is_latest)
    }

    pub fn get_version_by_number(&self, model_id: &str, version: &str) -> Option<ModelVersion> {
        self.get_versions(model_id)
            .into_iter()
            .find(|v| v.version == version)
    }

    pub fn bump_version(&self, request: VersionBumpRequest) -> Result<ModelVersion> {
        let current = self.get_latest_version(&request.model_id)
            .ok_or_else(|| crate::utils::error::AppError::NotFound(format!(
                "No versions found for model {}", request.model_id
            )))?;

        let mut versions = self.versions.lock();
        let model_versions = versions.get_mut(&request.model_id).unwrap();
        
        for v in model_versions.iter_mut() {
            v.is_latest = false;
        }

        let new_version = ModelVersion::bump_version(&current, request)
            .map_err(|e| crate::utils::error::AppError::Validation(e))?;
        
        model_versions.push(new_version.clone());
        model_versions.sort_by(|a, b| b.semantic_version.cmp(&a.semantic_version));
        
        self.metrics.increment_counter("version_bumped");
        Ok(new_version)
    }

    pub fn deprecate_version(&self, model_id: &str, version_id: &str, reason: String, deprecated_by: String) -> Result<ModelVersion> {
        let mut versions = self.versions.lock();
        let model_versions = versions.get_mut(model_id)
            .ok_or_else(|| crate::utils::error::AppError::NotFound(format!(
                "Model {} not found", model_id
            )))?;

        for v in model_versions.iter_mut() {
            if v.version_id == version_id {
                v.deprecate(reason, deprecated_by);
                self.metrics.increment_counter("version_deprecated");
                return Ok(v.clone());
            }
        }

        Err(crate::utils::error::AppError::NotFound(format!(
            "Version {} not found for model {}", version_id, model_id
        )))
    }

    pub fn transition_stage(&self, request: StageTransitionRequest) -> Result<StageTransition> {
        if !self.models.lock().contains_key(&request.model_id) {
            return Err(crate::utils::error::AppError::NotFound(format!(
                "Model {} not found", request.model_id
            )));
        }

        if self.get_version(&request.model_id, &request.version_id).is_none() {
            return Err(crate::utils::error::AppError::NotFound(format!(
                "Version {} not found for model {}", request.version_id, request.model_id
            )));
        }

        self.stage_manager.transition_stage(request)
            .map_err(|e| crate::utils::error::AppError::Validation(e))
    }

    pub fn get_model_stage(&self, model_id: &str, stage: &Stage) -> Option<super::stage::StageInfo> {
        self.stage_manager.get_stage(model_id, stage)
    }

    pub fn get_model_current_stage(&self, model_id: &str) -> Option<Stage> {
        self.stage_manager.get_current_stage(model_id)
    }

    pub fn get_stage_transitions(&self, model_id: &str, limit: usize) -> Vec<StageTransition> {
        self.stage_manager.get_transition_history(model_id, limit)
    }

    pub fn list_models_in_stage(&self, stage: &Stage) -> Vec<super::stage::StageInfo> {
        self.stage_manager.list_models_in_stage(stage)
    }

    pub fn get_production_version(&self, model_id: &str) -> Option<ModelVersion> {
        let version_id = self.stage_manager.get_model_version_in_stage(model_id, &Stage::Production)?;
        self.get_version(model_id, &version_id)
    }

    pub fn get_staging_version(&self, model_id: &str) -> Option<ModelVersion> {
        let version_id = self.stage_manager.get_model_version_in_stage(model_id, &Stage::Staging)?;
        self.get_version(model_id, &version_id)
    }

    pub fn search_models(&self, query: &str) -> Vec<Model> {
        let query_lower = query.to_lowercase();
        self.models.lock()
            .values()
            .filter(|m| {
                m.name.to_lowercase().contains(&query_lower)
                    || m.description.to_lowercase().contains(&query_lower)
                    || m.provider.to_lowercase().contains(&query_lower)
                    || m.tags.values().any(|v| v.to_lowercase().contains(&query_lower))
            })
            .cloned()
            .collect()
    }

    pub fn get_stats(&self) -> RegistryStats {
        let models = self.models.lock();
        let versions = self.versions.lock();

        let total_models = models.len();
        let active_models = models.values().filter(|m| m.is_active).count();
        let total_versions: usize = versions.values().map(|v| v.len()).sum();
        let deprecated_versions: usize = versions.values()
            .map(|v| v.iter().filter(|ver| ver.is_deprecated).count())
            .sum();

        let production_models = self.stage_manager.list_models_in_stage(&Stage::Production).len();
        let staging_models = self.stage_manager.list_models_in_stage(&Stage::Staging).len();

        RegistryStats {
            total_models,
            active_models,
            total_versions,
            deprecated_versions,
            production_models,
            staging_models,
        }
    }

    pub fn stage_manager(&self) -> &StageManager {
        &self.stage_manager
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RegistryStats {
    pub total_models: usize,
    pub active_models: usize,
    pub total_versions: usize,
    pub deprecated_versions: usize,
    pub production_models: usize,
    pub staging_models: usize,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::utils::metrics::MetricsCollector;
    use std::collections::HashMap;

    #[test]
    fn test_model_registration() {
        let metrics = MetricsCollector::new();
        let service = ModelRegistryService::new(metrics);

        let request = ModelRegistrationRequest {
            name: "GPT-4".to_string(),
            description: "Large language model".to_string(),
            provider: "openai".to_string(),
            model_type: super::model::ModelType::Llm,
            capabilities: vec!["chat".to_string()],
            max_tokens: 8192,
            supported_languages: vec!["en".to_string()],
            tags: HashMap::new(),
            metadata: HashMap::new(),
            created_by: "admin".to_string(),
            labels: HashMap::new(),
        };

        let model = service.register_model(request).unwrap();
        assert!(model.model_id.starts_with("model_"));
        assert_eq!(model.name, "GPT-4");
    }

    #[test]
    fn test_version_registration() {
        let metrics = MetricsCollector::new();
        let service = ModelRegistryService::new(metrics);

        let model = service.register_model(ModelRegistrationRequest {
            name: "Test Model".to_string(),
            description: "".to_string(),
            provider: "test".to_string(),
            model_type: super::model::ModelType::Llm,
            capabilities: vec![],
            max_tokens: 4096,
            supported_languages: vec![],
            tags: HashMap::new(),
            metadata: HashMap::new(),
            created_by: "test".to_string(),
            labels: HashMap::new(),
        }).unwrap();

        let version = service.register_version(VersionRegistrationRequest {
            model_id: model.model_id.clone(),
            version: "1.0.0".to_string(),
            description: "Initial".to_string(),
            artifact_uri: "s3://test".to_string(),
            checksum: "abc".to_string(),
            size_bytes: 1024,
            tags: HashMap::new(),
            metadata: HashMap::new(),
            training_metrics: HashMap::new(),
            validation_metrics: HashMap::new(),
            created_by: "test".to_string(),
        }).unwrap();

        assert!(version.is_latest);
        assert_eq!(service.get_versions(&model.model_id).len(), 1);
    }

    #[test]
    fn test_version_bump() {
        let metrics = MetricsCollector::new();
        let service = ModelRegistryService::new(metrics);

        let model = service.register_model(ModelRegistrationRequest {
            name: "Test Model".to_string(),
            description: "".to_string(),
            provider: "test".to_string(),
            model_type: super::model::ModelType::Llm,
            capabilities: vec![],
            max_tokens: 4096,
            supported_languages: vec![],
            tags: HashMap::new(),
            metadata: HashMap::new(),
            created_by: "test".to_string(),
            labels: HashMap::new(),
        }).unwrap();

        service.register_version(VersionRegistrationRequest {
            model_id: model.model_id.clone(),
            version: "1.0.0".to_string(),
            description: "".to_string(),
            artifact_uri: "".to_string(),
            checksum: "".to_string(),
            size_bytes: 0,
            tags: HashMap::new(),
            metadata: HashMap::new(),
            training_metrics: HashMap::new(),
            validation_metrics: HashMap::new(),
            created_by: "test".to_string(),
        }).unwrap();

        let new_version = service.bump_version(VersionBumpRequest {
            model_id: model.model_id.clone(),
            bump_type: super::version::VersionBumpType::Minor,
            description: "Minor update".to_string(),
            artifact_uri: "".to_string(),
            checksum: "".to_string(),
            size_bytes: 0,
            created_by: "test".to_string(),
            tags: HashMap::new(),
            metadata: HashMap::new(),
            training_metrics: HashMap::new(),
            validation_metrics: HashMap::new(),
        }).unwrap();

        assert_eq!(new_version.version, "1.1.0");
        assert!(new_version.is_latest);
        
        let versions = service.get_versions(&model.model_id);
        assert_eq!(versions.len(), 2);
        assert_eq!(versions.iter().filter(|v| v.is_latest).count(), 1);
    }

    #[test]
    fn test_stage_transition() {
        let metrics = MetricsCollector::new();
        let service = ModelRegistryService::new(metrics);

        let model = service.register_model(ModelRegistrationRequest {
            name: "Test Model".to_string(),
            description: "".to_string(),
            provider: "test".to_string(),
            model_type: super::model::ModelType::Llm,
            capabilities: vec![],
            max_tokens: 4096,
            supported_languages: vec![],
            tags: HashMap::new(),
            metadata: HashMap::new(),
            created_by: "test".to_string(),
            labels: HashMap::new(),
        }).unwrap();

        let version = service.register_version(VersionRegistrationRequest {
            model_id: model.model_id.clone(),
            version: "1.0.0".to_string(),
            description: "".to_string(),
            artifact_uri: "".to_string(),
            checksum: "".to_string(),
            size_bytes: 0,
            tags: HashMap::new(),
            metadata: HashMap::new(),
            training_metrics: HashMap::new(),
            validation_metrics: HashMap::new(),
            created_by: "test".to_string(),
        }).unwrap();

        let transition = service.transition_stage(StageTransitionRequest {
            model_id: model.model_id.clone(),
            version_id: version.version_id.clone(),
            to_stage: Stage::Production,
            reason: "Ready for production".to_string(),
            transitioned_by: "admin".to_string(),
            metadata: HashMap::new(),
        }).unwrap();

        assert_eq!(transition.to_stage, Stage::Production);
        assert_eq!(service.get_model_current_stage(&model.model_id), Some(Stage::Production));
        
        let prod_version = service.get_production_version(&model.model_id).unwrap();
        assert_eq!(prod_version.version_id, version.version_id);
    }

    #[test]
    fn test_model_search() {
        let metrics = MetricsCollector::new();
        let service = ModelRegistryService::new(metrics);

        for i in 0..3 {
            service.register_model(ModelRegistrationRequest {
                name: format!("Model {}", i),
                description: if i == 1 { "GPT model".to_string() } else { format!("Description {}", i) },
                provider: if i == 0 { "openai".to_string() } else { format!("provider{}", i) },
                model_type: super::model::ModelType::Llm,
                capabilities: vec![],
                max_tokens: 4096,
                supported_languages: vec![],
                tags: HashMap::new(),
                metadata: HashMap::new(),
                created_by: "test".to_string(),
                labels: HashMap::new(),
            }).unwrap();
        }

        let results = service.search_models("gpt");
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].name, "Model 1");

        let results = service.search_models("openai");
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].name, "Model 0");
    }

    #[test]
    fn test_registry_stats() {
        let metrics = MetricsCollector::new();
        let service = ModelRegistryService::new(metrics);

        for i in 0..5 {
            let model = service.register_model(ModelRegistrationRequest {
                name: format!("Model {}", i),
                description: "".to_string(),
                provider: "test".to_string(),
                model_type: super::model::ModelType::Llm,
                capabilities: vec![],
                max_tokens: 4096,
                supported_languages: vec![],
                tags: HashMap::new(),
                metadata: HashMap::new(),
                created_by: "test".to_string(),
                labels: HashMap::new(),
            }).unwrap();

            let version = service.register_version(VersionRegistrationRequest {
                model_id: model.model_id.clone(),
                version: "1.0.0".to_string(),
                description: "".to_string(),
                artifact_uri: "".to_string(),
                checksum: "".to_string(),
                size_bytes: 0,
                tags: HashMap::new(),
                metadata: HashMap::new(),
                training_metrics: HashMap::new(),
                validation_metrics: HashMap::new(),
                created_by: "test".to_string(),
            }).unwrap();

            if i < 2 {
                service.transition_stage(StageTransitionRequest {
                    model_id: model.model_id.clone(),
                    version_id: version.version_id.clone(),
                    to_stage: Stage::Production,
                    reason: "".to_string(),
                    transitioned_by: "admin".to_string(),
                    metadata: HashMap::new(),
                }).unwrap();
            }

            if i == 4 {
                let mut update = ModelUpdateRequest::default();
                update.is_active = Some(false);
                service.update_model(&model.model_id, update).unwrap();
            }
        }

        let stats = service.get_stats();
        assert_eq!(stats.total_models, 5);
        assert_eq!(stats.active_models, 4);
        assert_eq!(stats.total_versions, 5);
        assert_eq!(stats.production_models, 2);
    }

    #[test]
    fn test_version_deprecation() {
        let metrics = MetricsCollector::new();
        let service = ModelRegistryService::new(metrics);

        let model = service.register_model(ModelRegistrationRequest {
            name: "Test Model".to_string(),
            description: "".to_string(),
            provider: "test".to_string(),
            model_type: super::model::ModelType::Llm,
            capabilities: vec![],
            max_tokens: 4096,
            supported_languages: vec![],
            tags: HashMap::new(),
            metadata: HashMap::new(),
            created_by: "test".to_string(),
            labels: HashMap::new(),
        }).unwrap();

        let version = service.register_version(VersionRegistrationRequest {
            model_id: model.model_id.clone(),
            version: "1.0.0".to_string(),
            description: "".to_string(),
            artifact_uri: "".to_string(),
            checksum: "".to_string(),
            size_bytes: 0,
            tags: HashMap::new(),
            metadata: HashMap::new(),
            training_metrics: HashMap::new(),
            validation_metrics: HashMap::new(),
            created_by: "test".to_string(),
        }).unwrap();

        let deprecated = service.deprecate_version(
            &model.model_id,
            &version.version_id,
            "Old version".to_string(),
            "admin".to_string(),
        ).unwrap();

        assert!(deprecated.is_deprecated);
        assert_eq!(deprecated.deprecation_reason, Some("Old version".to_string()));
    }
}
