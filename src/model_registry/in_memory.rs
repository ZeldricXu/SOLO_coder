use std::collections::HashMap;
use std::sync::Arc;
use async_trait::async_trait;
use dashmap::DashMap;
use tracing::warn;
use crate::models::{ModelGuardError, Result};
use crate::snapshot::Metrics;
use super::domain::{Model, ModelStage, ModelVersion};
use super::ports::{
    ModelRepository, VersionRepository, StageTransitionService, MetricsRecorder, SearchService
};

pub struct InMemoryModelRepository {
    models: Arc<DashMap<String, Model>>,
}

impl InMemoryModelRepository {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            models: Arc::new(DashMap::new()),
        })
    }
}

#[async_trait]
impl ModelRepository for InMemoryModelRepository {
    async fn save(&self, model: Model) -> Result<Model> {
        self.models.insert(model.model_id.clone(), model.clone());
        Ok(model)
    }

    async fn get(&self, model_id: &str) -> Result<Model> {
        self.models
            .get(model_id)
            .map(|m| m.clone())
            .ok_or_else(|| ModelGuardError::NotFound(format!("Model {} not found", model_id)))
    }

    async fn get_by_name(&self, name: &str) -> Result<Model> {
        self.models
            .iter()
            .find(|m| m.name == name)
            .map(|m| m.clone())
            .ok_or_else(|| ModelGuardError::NotFound(format!("Model with name '{}' not found", name)))
    }

    async fn list(&self) -> Result<Vec<Model>> {
        Ok(self.models.iter().map(|m| m.clone()).collect())
    }

    async fn delete(&self, model_id: &str) -> Result<()> {
        self.models
            .remove(model_id)
            .map(|_| ())
            .ok_or_else(|| ModelGuardError::NotFound(format!("Model {} not found", model_id)))
    }

    async fn exists(&self, model_id: &str) -> bool {
        self.models.contains_key(model_id)
    }

    async fn name_exists(&self, name: &str) -> bool {
        self.models.iter().any(|m| m.name == name)
    }
}

pub struct InMemoryVersionRepository {
    model_repository: Arc<dyn ModelRepository>,
}

impl InMemoryVersionRepository {
    pub fn new(model_repository: Arc<dyn ModelRepository>) -> Arc<Self> {
        Arc::new(Self {
            model_repository,
        })
    }
}

#[async_trait]
impl VersionRepository for InMemoryVersionRepository {
    async fn save(&self, model_id: &str, version: ModelVersion) -> Result<ModelVersion> {
        let mut model = self.model_repository.get(model_id).await?;
        
        if let Some(existing) = model.versions.iter_mut().find(|v| v.version == version.version) {
            *existing = version.clone();
        } else {
            model.versions.push(version.clone());
        }
        
        model.updated_at = chrono::Utc::now();
        self.model_repository.save(model).await?;
        Ok(version)
    }

    async fn get(&self, model_id: &str, version: u32) -> Result<ModelVersion> {
        let model = self.model_repository.get(model_id).await?;
        model
            .get_version(version)
            .cloned()
            .ok_or_else(|| ModelGuardError::NotFound(format!("Version {} not found", version)))
    }

    async fn get_latest(&self, model_id: &str) -> Result<ModelVersion> {
        let model = self.model_repository.get(model_id).await?;
        model
            .get_latest_version()
            .cloned()
            .ok_or_else(|| ModelGuardError::NotFound("No versions found".to_string()))
    }

    async fn get_by_stage(&self, model_id: &str, stage: &ModelStage) -> Result<ModelVersion> {
        let model = self.model_repository.get(model_id).await?;
        model
            .get_version_by_stage(stage)
            .cloned()
            .ok_or_else(|| ModelGuardError::NotFound("No production version found".to_string()))
    }

    async fn list(&self, model_id: &str) -> Result<Vec<ModelVersion>> {
        let model = self.model_repository.get(model_id).await?;
        Ok(model.list_versions().into_iter().cloned().collect())
    }
}

pub struct DefaultStageTransitionService {
    model_repository: Arc<dyn ModelRepository>,
}

impl DefaultStageTransitionService {
    pub fn new(model_repository: Arc<dyn ModelRepository>) -> Arc<Self> {
        Arc::new(Self {
            model_repository,
        })
    }
}

#[async_trait]
impl StageTransitionService for DefaultStageTransitionService {
    async fn transition(
        &self,
        model_id: &str,
        version: u32,
        target_stage: ModelStage,
    ) -> Result<ModelVersion> {
        let mut model = self.model_repository.get(model_id).await?;
        
        if model.get_version(version).is_none() {
            return Err(ModelGuardError::NotFound(format!("Version {} not found", version)));
        }
        
        if let Err(e) = model.transition_version_stage(version, target_stage.clone()) {
            warn!("Stage transition failed: {}", e);
            return Err(ModelGuardError::ValidationError(e));
        }

        self.model_repository.save(model.clone()).await?;
        
        model
            .get_version(version)
            .cloned()
            .ok_or_else(|| ModelGuardError::NotFound(format!("Version {} not found", version)))
    }

    async fn can_transition(
        &self,
        model_id: &str,
        version: u32,
        target_stage: &ModelStage,
    ) -> bool {
        if let Ok(model) = self.model_repository.get(model_id).await {
            if let Some(v) = model.get_version(version) {
                return v.stage.can_transition_to(target_stage);
            }
        }
        false
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

pub struct InMemorySearchService {
    model_repository: Arc<dyn ModelRepository>,
}

impl InMemorySearchService {
    pub fn new(model_repository: Arc<dyn ModelRepository>) -> Arc<Self> {
        Arc::new(Self {
            model_repository,
        })
    }
}

impl SearchService for InMemorySearchService {
    fn search(&self, tags: &[String], stage: Option<&ModelStage>) -> Vec<Model> {
        let models = self.model_repository.list();
        
        let mut results = Vec::new();
        if let Ok(all_models) = futures::executor::block_on(models) {
            for model in all_models {
                if !tags.is_empty() {
                    if let Some(latest) = model.get_latest_version() {
                        if !tags.iter().all(|t| latest.metadata.tags.contains(t)) {
                            continue;
                        }
                    }
                }
                if let Some(ref s) = stage {
                    if model.get_version_by_stage(s).is_none() {
                        continue;
                    }
                }
                results.push(model);
            }
        }
        results
    }

    fn rebuild_index(&self) -> Result<()> {
        Ok(())
    }
}
