use std::collections::HashMap;
use std::sync::Arc;
use tracing::{info, instrument, warn};
use crate::models::{Config, Result};
use crate::snapshot::{Metrics, Snapshot};
use super::domain::{
    Model, ModelMetadata, ModelRegistrationRequest, ModelStage, ModelVersion,
    StageTransitionRequest, VersionCreateRequest,
};
use super::ports::{
    MetricsRecorder, ModelRepository, SearchService, StageTransitionService, VersionRepository,
};

#[derive(Clone)]
pub struct ModelRegistryService {
    model_repository: Arc<dyn ModelRepository>,
    version_repository: Arc<dyn VersionRepository>,
    stage_transition_service: Arc<dyn StageTransitionService>,
    metrics_recorder: Arc<dyn MetricsRecorder>,
    search_service: Arc<dyn SearchService>,
}

impl ModelRegistryService {
    pub fn new(
        model_repository: Arc<dyn ModelRepository>,
        version_repository: Arc<dyn VersionRepository>,
        stage_transition_service: Arc<dyn StageTransitionService>,
        metrics_recorder: Arc<dyn MetricsRecorder>,
        search_service: Arc<dyn SearchService>,
    ) -> Self {
        Self {
            model_repository,
            version_repository,
            stage_transition_service,
            metrics_recorder,
            search_service,
        }
    }

    #[instrument(skip(self), fields(model_name = %request.name))]
    pub async fn register_model(&self, request: ModelRegistrationRequest) -> Result<Model> {
        info!("Registering model: {}", request.name);

        if request.name.is_empty() {
            return Err(crate::models::ModelGuardError::ValidationError(
                "Model name cannot be empty".to_string(),
            ));
        }

        if self.model_repository.name_exists(&request.name).await {
            return Err(crate::models::ModelGuardError::Conflict(format!(
                "Model with name '{}' already exists",
                request.name
            )));
        }

        let mut model = Model::new(request.name);
        model.create_version(request.metadata);

        let result = self.model_repository.save(model).await?;
        self.metrics_recorder.record_success(0.0);
        
        info!("Model registered successfully: {}", result.model_id);
        Ok(result)
    }

    pub async fn get_model(&self, model_id: &str) -> Result<Model> {
        self.model_repository.get(model_id).await
    }

    pub async fn get_model_by_name(&self, name: &str) -> Result<Model> {
        self.model_repository.get_by_name(name).await
    }

    pub async fn list_models(&self) -> Result<Vec<Model>> {
        self.model_repository.list().await
    }

    #[instrument(skip(self), fields(model_id = %request.model_id))]
    pub async fn create_version(&self, request: VersionCreateRequest) -> Result<ModelVersion> {
        info!("Creating version for model: {}", request.model_id);

        let mut model = self.model_repository.get(&request.model_id).await?;
        let version = model.create_version(request.metadata).clone();
        
        self.model_repository.save(model).await?;
        self.metrics_recorder.record_success(0.0);
        
        info!("Version {} created for model {}", version.version, request.model_id);
        Ok(version)
    }

    pub async fn get_version(&self, model_id: &str, version: u32) -> Result<ModelVersion> {
        self.version_repository.get(model_id, version).await
    }

    pub async fn get_latest_version(&self, model_id: &str) -> Result<ModelVersion> {
        self.version_repository.get_latest(model_id).await
    }

    pub async fn get_production_version(&self, model_id: &str) -> Result<ModelVersion> {
        self.version_repository.get_by_stage(model_id, &ModelStage::Production).await
    }

    pub async fn list_versions(&self, model_id: &str) -> Result<Vec<ModelVersion>> {
        self.version_repository.list(model_id).await
    }

    #[instrument(skip(self), fields(
        model_id = %request.model_id,
        version = %request.version,
        target_stage = ?request.target_stage
    ))]
    pub async fn transition_stage(&self, request: StageTransitionRequest) -> Result<ModelVersion> {
        info!(
            "Transitioning model {} v{} to {:?}",
            request.model_id, request.version, request.target_stage
        );

        let model_id = request.model_id.clone();
        let version = request.version;
        let target_stage = request.target_stage.clone();
        
        let result = self
            .stage_transition_service
            .transition(&model_id, version, target_stage.clone())
            .await?;

        self.metrics_recorder.record_success(0.0);
        
        info!(
            "Successfully transitioned model {} v{} to {:?}",
            model_id, version, target_stage
        );
        Ok(result)
    }

    pub fn search_models(&self, tags: &[String], stage: Option<ModelStage>) -> Vec<Model> {
        self.search_service.search(tags, stage.as_ref())
    }

    pub async fn update_version_metadata(
        &self,
        model_id: &str,
        version: u32,
        metrics: HashMap<String, f64>,
        artifacts: HashMap<String, String>,
    ) -> Result<ModelVersion> {
        let mut model_version = self.version_repository.get(model_id, version).await?;

        for (k, v) in metrics {
            model_version.add_metric(k, v);
        }
        for (k, v) in artifacts {
            model_version.add_artifact(k, v);
        }

        self.version_repository.save(model_id, model_version.clone()).await
    }

    pub async fn delete_model(&self, model_id: &str) -> Result<()> {
        self.model_repository.delete(model_id).await
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
        let models = self.model_repository.list().await?;
        
        stats.insert("total_models".into(), models.len());
        
        let mut total_versions = 0;
        let mut prod_versions = 0;
        
        for model in &models {
            total_versions += model.versions.len();
            if model.get_version_by_stage(&ModelStage::Production).is_some() {
                prod_versions += 1;
            }
        }
        
        stats.insert("total_versions".into(), total_versions);
        stats.insert("production_models".into(), prod_versions);
        Ok(stats)
    }
}

impl ModelRegistryService {
    pub fn with_in_memory_backend(_config: Config) -> Self {
        use super::in_memory::{
            DefaultMetricsRecorder, DefaultStageTransitionService, InMemoryModelRepository,
            InMemorySearchService, InMemoryVersionRepository,
        };

        let model_repository = InMemoryModelRepository::new();
        let version_repository = InMemoryVersionRepository::new(model_repository.clone());
        let stage_transition_service = DefaultStageTransitionService::new(model_repository.clone());
        let metrics_recorder = DefaultMetricsRecorder::new();
        let search_service = InMemorySearchService::new(model_repository.clone());

        Self::new(
            model_repository,
            version_repository,
            stage_transition_service,
            metrics_recorder,
            search_service,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model_registry::domain::ModelMetadata;
    use serde_json::json;

    fn create_test_config() -> Config {
        Config::new("test", json!({"timeout": 30000}))
    }

    #[tokio::test]
    async fn test_register_model() {
        let service = ModelRegistryService::with_in_memory_backend(create_test_config());
        let request = ModelRegistrationRequest {
            name: "test_model".to_string(),
            description: Some("A test model".to_string()),
            metadata: None,
        };

        let model = service.register_model(request).await.unwrap();
        assert!(model.model_id.starts_with("mod_"));
        assert_eq!(model.name, "test_model");
        assert_eq!(model.latest_version, 1);
        assert_eq!(model.versions.len(), 1);
    }

    #[tokio::test]
    async fn test_register_duplicate_model() {
        let service = ModelRegistryService::with_in_memory_backend(create_test_config());
        let request = ModelRegistrationRequest {
            name: "test_model".to_string(),
            description: None,
            metadata: None,
        };

        service.register_model(request.clone()).await.unwrap();
        let result = service.register_model(request).await;
        
        assert!(result.is_err());
        assert!(matches!(result.unwrap_err(), crate::models::ModelGuardError::Conflict(_)));
    }

    #[tokio::test]
    async fn test_create_version() {
        let service = ModelRegistryService::with_in_memory_backend(create_test_config());
        let model = service
            .register_model(ModelRegistrationRequest {
                name: "test_model".to_string(),
                description: None,
                metadata: None,
            })
            .await
            .unwrap();

        let metadata = ModelMetadata {
            framework: Some("pytorch".to_string()),
            ..Default::default()
        };
        
        let version = service
            .create_version(VersionCreateRequest {
                model_id: model.model_id.clone(),
                metadata: Some(metadata),
            })
            .await
            .unwrap();

        assert_eq!(version.version, 2);
        assert_eq!(version.stage, ModelStage::Staging);
        
        let updated = service.get_model(&model.model_id).await.unwrap();
        assert_eq!(updated.latest_version, 2);
    }

    #[tokio::test]
    async fn test_stage_transition() {
        let service = ModelRegistryService::with_in_memory_backend(create_test_config());
        let model = service
            .register_model(ModelRegistrationRequest {
                name: "test_model".to_string(),
                description: None,
                metadata: None,
            })
            .await
            .unwrap();

        let result = service
            .transition_stage(StageTransitionRequest {
                model_id: model.model_id.clone(),
                version: 1,
                target_stage: ModelStage::Production,
            })
            .await;

        assert!(result.is_ok());
        let version = result.unwrap();
        assert_eq!(version.stage, ModelStage::Production);

        let prod_version = service.get_production_version(&model.model_id).await.unwrap();
        assert_eq!(prod_version.version, 1);
    }

    #[tokio::test]
    async fn test_invalid_stage_transition() {
        let service = ModelRegistryService::with_in_memory_backend(create_test_config());
        let model = service
            .register_model(ModelRegistrationRequest {
                name: "test_model".to_string(),
                description: None,
                metadata: None,
            })
            .await
            .unwrap();

        let result = service
            .transition_stage(StageTransitionRequest {
                model_id: model.model_id.clone(),
                version: 1,
                target_stage: ModelStage::Deprecated,
            })
            .await;

        assert!(result.is_err());
        assert!(matches!(result.unwrap_err(), crate::models::ModelGuardError::ValidationError(_)));
    }

    #[tokio::test]
    async fn test_search_models() {
        let service = ModelRegistryService::with_in_memory_backend(create_test_config());
        
        let metadata = ModelMetadata {
            tags: vec!["nlp".to_string(), "classification".to_string()],
            ..Default::default()
        };
        
        service
            .register_model(ModelRegistrationRequest {
                name: "model1".to_string(),
                description: None,
                metadata: Some(metadata),
            })
            .await
            .unwrap();

        service
            .register_model(ModelRegistrationRequest {
                name: "model2".to_string(),
                description: None,
                metadata: None,
            })
            .await
            .unwrap();

        let results = service.search_models(&["nlp".to_string()], None);
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].name, "model1");

        let results = service.search_models(&[], None);
        assert_eq!(results.len(), 2);
    }

    #[tokio::test]
    async fn test_update_metadata() {
        let service = ModelRegistryService::with_in_memory_backend(create_test_config());
        let model = service
            .register_model(ModelRegistrationRequest {
                name: "test_model".to_string(),
                description: None,
                metadata: None,
            })
            .await
            .unwrap();

        let mut metrics = HashMap::new();
        metrics.insert("accuracy".to_string(), 0.95);
        
        let mut artifacts = HashMap::new();
        artifacts.insert("model_path".to_string(), "s3://model.pt".to_string());

        let version = service
            .update_version_metadata(&model.model_id, 1, metrics, artifacts)
            .await
            .unwrap();

        assert_eq!(version.metadata.metrics.get("accuracy"), Some(&0.95));
        assert_eq!(
            version.metadata.artifacts.get("model_path"),
            Some(&"s3://model.pt".to_string())
        );
    }

    #[tokio::test]
    async fn test_delete_model() {
        let service = ModelRegistryService::with_in_memory_backend(create_test_config());
        let model = service
            .register_model(ModelRegistrationRequest {
                name: "test_model".to_string(),
                description: None,
                metadata: None,
            })
            .await
            .unwrap();

        assert!(service.delete_model(&model.model_id).await.is_ok());
        assert!(service.get_model(&model.model_id).await.is_err());
    }

    #[tokio::test]
    async fn test_get_stats() {
        let service = ModelRegistryService::with_in_memory_backend(create_test_config());
        let stats = service.get_stats().await.unwrap();
        
        assert_eq!(stats["total_models"], 0);
        assert_eq!(stats["total_versions"], 0);
    }
}
