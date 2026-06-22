pub mod types;
pub mod balancer;
pub mod model_manager;
pub mod warmup;
pub mod heartbeat;
pub mod scheduler;
pub mod dynamic_scheduler;

pub use types::*;
pub use balancer::GpuLoadBalancer;
pub use model_manager::ModelLifecycleManager;
pub use warmup::ModelWarmer;
pub use heartbeat::HeartbeatManager;
pub use scheduler::ResourceScheduler;
pub use dynamic_scheduler::*;

use common::error::AppError;
use common::types::ModelVersion;
use dashmap::DashMap;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::broadcast;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use model_registry::ModelRegistryService;
use traffic_router::RuntimeClient;

pub struct SchedulerService {
    pub scheduler: Arc<ResourceScheduler>,
    runtime_client: Arc<RuntimeClient>,
    model_registry: Arc<ModelRegistryService>,
    config: SchedulerConfig,
    event_handlers: DashMap<String, Vec<Box<dyn Fn(SchedulerEvent) + Send + Sync>>>,
}

impl SchedulerService {
    pub fn new(
        config: SchedulerConfig,
        runtime_client: Arc<RuntimeClient>,
        model_registry: Arc<ModelRegistryService>,
    ) -> Self {
        let scheduler = Arc::new(ResourceScheduler::new(
            config.clone(),
            runtime_client.clone(),
            model_registry.clone(),
        ));

        Self {
            scheduler,
            runtime_client,
            model_registry,
            config,
            event_handlers: DashMap::new(),
        }
    }

    pub fn start(&self) {
        info!("Starting SchedulerService...");
        self.scheduler.start();
        info!("SchedulerService started successfully");
    }

    pub fn with_scheduler(scheduler: Arc<ResourceScheduler>) -> Self {
        Self {
            config: scheduler.config.clone(),
            runtime_client: scheduler.runtime_client.clone(),
            model_registry: scheduler.model_registry.clone(),
            scheduler,
            event_handlers: DashMap::new(),
        }
    }

    pub fn add_event_handler<F>(&self, name: String, handler: F)
    where
        F: Fn(SchedulerEvent) + Send + Sync + 'static,
    {
        self.event_handlers
            .entry(name)
            .or_default()
            .push(Box::new(handler));
        debug!("Registered event handler");
    }

    pub fn remove_event_handler(&self, name: &str) {
        self.event_handlers.remove(name);
        debug!("Removed event handler: {}", name);
    }

    pub async fn schedule_inference(
        &self,
        version_id: Uuid,
        required_mb: u64,
    ) -> Result<(usize, bool), AppError> {
        self.scheduler
            .schedule_for_inference(version_id, required_mb)
            .await
    }

    pub fn release_inference_slot(&self, version_id: Uuid) {
        self.scheduler.release_inference_slot(version_id);
    }

    pub async fn register_runtime_node(
        &self,
        node_id: Option<String>,
        address: String,
        gpu_ids: Vec<usize>,
        gpu_total_mb: Vec<u64>,
    ) -> Result<String, AppError> {
        if gpu_ids.len() != gpu_total_mb.len() {
            return Err(AppError::InvalidModelConfig(format!(
                "GPU IDs count ({}) must match total memory count ({})",
                gpu_ids.len(),
                gpu_total_mb.len()
            )));
        }

        let assigned_id = match node_id {
            Some(id) => id.clone(),
            None => format!(
                "node-{}",
                &address.replace(':', "-").replace('.', "-")
            ),
        };

        for (i, &gpu_id) in gpu_ids.iter().enumerate() {
            self.scheduler
                .add_gpu(gpu_id, gpu_total_mb[i], Some(address.clone()));
        }

        self.scheduler
            .heartbeat
            .register_node_with_id(assigned_id.clone(), address.clone(), &gpu_ids);

        info!(
            "Registered runtime node {} (addr: {}) with {} GPUs",
            assigned_id,
            address,
            gpu_ids.len()
        );

        Ok(assigned_id)
    }

    pub fn deregister_runtime_node(&self, node_id: &str) -> Result<(), AppError> {
        let node_addr = self
            .scheduler
            .heartbeat
            .get_node_address(node_id)
            .ok_or_else(|| AppError::GpuNotFound(format!("Node {} not found", node_id)))?;

        self.scheduler.heartbeat.deregister_node(node_id);

        let gpu_ids = self
            .scheduler
            .heartbeat
            .get_registered_gpu_ids(&node_addr);

        for gpu_id in gpu_ids {
            self.scheduler.remove_gpu(gpu_id);
        }

        info!("Deregistered runtime node {}", node_id);
        Ok(())
    }

    pub async fn report_runtime_heartbeat(
        &self,
        node_id: &str,
        gpu_id: usize,
        stats: HeartbeatStats,
    ) -> Result<(), AppError> {
        self.scheduler
            .heartbeat
            .report_heartbeat(node_id, gpu_id, stats)
    }

    pub fn check_dead_nodes(&self) -> Vec<DeadNode> {
        self.scheduler.heartbeat.check_heartbeats()
    }

    pub fn list_alive_nodes(&self) -> Vec<NodeInfo> {
        self.scheduler.heartbeat.get_alive_nodes()
    }

    pub async fn load_model(
        &self,
        version_id: Uuid,
        gpu_id: Option<usize>,
    ) -> Result<LoadedModelInfo, AppError> {
        let info = match self.scheduler.model_registry.list_versions(version_id).await
        {
            Ok(versions) => versions
                .into_iter()
                .find(|v| v.id == version_id)
                .unwrap_or_else(|| ModelVersion {
                    id: version_id,
                    model_id: Uuid::nil(),
                    version: "1".to_string(),
                    framework: common::types::ModelFramework::Onnx,
                    status: common::types::ModelStatus::Online,
                    input_schema: vec![],
                    output_schema: vec![],
                    gpu_memory_mb: 2048,
                    created_at: chrono::Utc::now(),
                }),
            _ => ModelVersion {
                id: version_id,
                model_id: Uuid::nil(),
                version: "1".to_string(),
                framework: common::types::ModelFramework::Onnx,
                status: common::types::ModelStatus::Online,
                input_schema: vec![],
                output_schema: vec![],
                gpu_memory_mb: 2048,
                created_at: chrono::Utc::now(),
            },
        };

        let target_gpu = match gpu_id {
            Some(gid) => gid,
            None => self.scheduler.balancer.select_gpu(
                info.gpu_memory_mb,
                GpuSelectionStrategy::LeastLoaded,
                Some(version_id),
            )?,
        };

        self.scheduler
            .model_manager
            .load_model(version_id, target_gpu)
            .await
    }

    pub async fn unload_model(
        &self,
        version_id: Uuid,
        gpu_id: usize,
        force: bool,
    ) -> Result<(), AppError> {
        self.scheduler
            .model_manager
            .unload_model(version_id, gpu_id, force)
            .await
    }

    pub fn list_loaded_models(&self) -> Vec<LoadedModelInfo> {
        self.scheduler.model_manager.list_loaded_models()
    }

    pub fn get_model_deployment(&self, version_id: Uuid) -> Option<DeploymentInfo> {
        self.scheduler.model_manager.get_model_deployment(version_id)
    }

    pub fn list_all_deployments(&self) -> Vec<DeploymentInfo> {
        self.scheduler.model_manager.get_all_deployments()
    }

    pub fn calculate_model_heat(&self, version_id: Uuid) -> f32 {
        self.scheduler.model_manager.calculate_heat_score(version_id)
    }

    pub fn get_all_heat_scores(&self) -> Vec<ModelHeatScore> {
        self.scheduler.model_manager.get_heat_scores()
    }

    pub async fn warmup_model(
        &self,
        version_id: Uuid,
        gpu_id: usize,
        batch_size: Option<u32>,
        iterations: Option<u32>,
    ) -> Result<WarmupReport, AppError> {
        self.scheduler
            .warmer
            .warmup_model(version_id, gpu_id, batch_size, iterations)
            .await
    }

    pub fn get_warmup_progress(&self, version_id: Uuid, gpu_id: usize) -> Option<WarmupProgress> {
        self.scheduler
            .warmer
            .get_warmup_progress(version_id, gpu_id)
    }

    pub fn list_warmup_progress(&self) -> Vec<WarmupProgress> {
        self.scheduler.warmer.get_all_warmup_progress()
    }

    pub async fn canary_warmup_new_version(
        &self,
        model_name: &str,
        old_version_id: Uuid,
        new_version_id: Uuid,
    ) -> Result<Vec<WarmupReport>, AppError> {
        self.scheduler
            .warmer
            .warmup_new_version(model_name, old_version_id, new_version_id)
            .await
    }

    pub fn select_least_loaded_gpu(
        &self,
        required_mb: u64,
    ) -> Option<common::types::GpuDevice> {
        self.scheduler.balancer.least_loaded_gpu(required_mb)
    }

    pub fn detect_overloaded_gpus(&self) -> Vec<common::types::GpuDevice> {
        self.scheduler
            .balancer
            .detect_overloaded_gpus(&self.config)
    }

    pub fn detect_underloaded_gpus(&self) -> Vec<common::types::GpuDevice> {
        self.scheduler
            .balancer
            .detect_underloaded_gpus(&self.config)
    }

    pub fn run_bin_packing(
        &self,
        models: Vec<BinPackingItem>,
    ) -> BinPackingResult {
        self.scheduler.balancer.bin_packing(models, None)
    }

    pub fn compute_gpu_imbalance(&self) -> f64 {
        self.scheduler.balancer.compute_imbalance_score()
    }

    pub async fn trigger_scheduling(&self) -> Result<SchedulingDecision, AppError> {
        self.scheduler.trigger_scheduling_event().await
    }

    pub async fn trigger_rebalance(&self) -> Vec<SchedulingDecision> {
        self.scheduler
            .balancer
            .rebalance(Some(self.config.rebalance_imbalance_threshold))
    }

    pub async fn get_cluster_snapshot(&self) -> HashMap<String, serde_json::Value> {
        self.scheduler.collect_cluster_snapshot().await
    }

    pub fn get_status(&self) -> HashMap<String, serde_json::Value> {
        self.scheduler.get_status()
    }

    pub async fn reload_models_from_registry(&self) -> Result<usize, AppError> {
        self.scheduler.reload_models_from_registry().await
    }

    pub fn event_receiver(&self) -> broadcast::Receiver<SchedulerEvent> {
        self.scheduler.event_sender.subscribe()
    }

    pub fn publish_event(&self, event: SchedulerEvent) {
        let _ = self.scheduler.event_sender.send(event);
    }

    pub fn list_gpus(&self) -> Vec<GpuLoad> {
        self.scheduler.balancer.list_gpus()
    }

    pub fn get_gpu(&self, gpu_id: usize) -> Option<GpuLoad> {
        self.scheduler.balancer.get_gpu(gpu_id)
    }

    pub fn get_cluster_health_score(&self) -> f64 {
        self.scheduler.heartbeat.get_cluster_health_score()
    }

    pub fn get_scheduler_config(&self) -> SchedulerConfig {
        self.config.clone()
    }

    pub fn set_scheduler_config(&mut self, config: SchedulerConfig) {
        self.config = config.clone();
        let scheduler_inner = Arc::get_mut(&mut self.scheduler);
        if let Some(s) = scheduler_inner {
            s.config = config;
        }
        info!("Scheduler configuration updated");
    }
}

impl Clone for SchedulerService {
    fn clone(&self) -> Self {
        Self {
            scheduler: self.scheduler.clone(),
            runtime_client: self.runtime_client.clone(),
            model_registry: self.model_registry.clone(),
            config: self.config.clone(),
            event_handlers: DashMap::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_service() -> Arc<SchedulerService> {
        let runtime = Arc::new(RuntimeClient::new());
        let registry = Arc::new(ModelRegistryService::new(
            db::DatabasePool::mock(),
            db::RedisClient::mock(),
            model_registry::MinioStorage::mock(),
        ));

        Arc::new(SchedulerService::new(
            SchedulerConfig::default(),
            runtime,
            registry,
        ))
    }

    #[test]
    fn test_create_service() {
        let service = create_test_service();
        assert_eq!(service.scheduler.balancer.gpu_count(), 0);
    }
}
