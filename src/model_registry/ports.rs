use std::collections::HashMap;
use async_trait::async_trait;
use crate::models::Result;
use super::domain::{Model, ModelStage, ModelVersion};

#[async_trait]
pub trait ModelRepository: Send + Sync {
    async fn save(&self, model: Model) -> Result<Model>;
    async fn get(&self, model_id: &str) -> Result<Model>;
    async fn get_by_name(&self, name: &str) -> Result<Model>;
    async fn list(&self) -> Result<Vec<Model>>;
    async fn delete(&self, model_id: &str) -> Result<()>;
    async fn exists(&self, model_id: &str) -> bool;
    async fn name_exists(&self, name: &str) -> bool;
}

#[async_trait]
pub trait VersionRepository: Send + Sync {
    async fn save(&self, model_id: &str, version: ModelVersion) -> Result<ModelVersion>;
    async fn get(&self, model_id: &str, version: u32) -> Result<ModelVersion>;
    async fn get_latest(&self, model_id: &str) -> Result<ModelVersion>;
    async fn get_by_stage(&self, model_id: &str, stage: &ModelStage) -> Result<ModelVersion>;
    async fn list(&self, model_id: &str) -> Result<Vec<ModelVersion>>;
}

#[async_trait]
pub trait StageTransitionService: Send + Sync {
    async fn transition(
        &self,
        model_id: &str,
        version: u32,
        target_stage: ModelStage,
    ) -> Result<ModelVersion>;
    
    async fn can_transition(
        &self,
        model_id: &str,
        version: u32,
        target_stage: &ModelStage,
    ) -> bool;
}

pub trait MetricsRecorder: Send + Sync {
    fn record_success(&self, latency_ms: f64);
    fn record_error(&self);
    fn get_metrics(&self) -> HashMap<String, f64>;
}

pub trait SearchService: Send + Sync {
    fn search(&self, tags: &[String], stage: Option<&ModelStage>) -> Vec<Model>;
    fn rebuild_index(&self) -> Result<()>;
}
