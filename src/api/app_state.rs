use std::sync::Arc;
use crate::utils::metrics::MetricsCollector;
use crate::document_pipeline::DocumentPipeline;
use crate::inference_gateway::InferenceGateway;
use crate::feature_store::FeatureStoreService;
use crate::adversarial::AdversarialGenerator;
use crate::evaluation::ModelDashboard;
use crate::model_registry::ModelRegistry;
use crate::prompt_experiments::PromptExperimentManager;
use crate::gpu_scheduler::GpuScheduler;

#[derive(Clone)]
pub struct AppState {
    pub metrics: Arc<MetricsCollector>,
    pub document_pipeline: Arc<DocumentPipeline>,
    pub inference_gateway: Arc<InferenceGateway>,
    pub feature_store: Arc<FeatureStoreService>,
    pub adversarial_generator: Arc<AdversarialGenerator>,
    pub model_dashboard: Arc<ModelDashboard>,
    pub model_registry: Arc<ModelRegistry>,
    pub prompt_manager: Arc<PromptExperimentManager>,
    pub gpu_scheduler: Arc<GpuScheduler>,
}

impl AppState {
    pub fn new(
        metrics: Arc<MetricsCollector>,
        document_pipeline: Arc<DocumentPipeline>,
        inference_gateway: Arc<InferenceGateway>,
        feature_store: Arc<FeatureStoreService>,
        adversarial_generator: Arc<AdversarialGenerator>,
        model_dashboard: Arc<ModelDashboard>,
        model_registry: Arc<ModelRegistry>,
        prompt_manager: Arc<PromptExperimentManager>,
        gpu_scheduler: Arc<GpuScheduler>,
    ) -> Self {
        Self {
            metrics,
            document_pipeline,
            inference_gateway,
            feature_store,
            adversarial_generator,
            model_dashboard,
            model_registry,
            prompt_manager,
            gpu_scheduler,
        }
    }
}
