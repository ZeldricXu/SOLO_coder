use actix_web::{App, HttpServer, web};
use std::sync::Arc;
use tracing::{info, error};
use crate::api::{AppState, configure_routes};
use crate::utils::metrics::MetricsCollector;
use crate::document_pipeline::DocumentPipeline;
use crate::inference_gateway::InferenceGateway;
use crate::feature_store::FeatureStore;
use crate::adversarial::AdversarialGenerator;
use crate::evaluation::ModelDashboard;
use crate::model_registry::ModelRegistry;
use crate::prompt_experiments::PromptExperimentManager;
use crate::gpu_scheduler::{GpuScheduler, SchedulerConfig};

#[derive(Debug, Clone)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
    pub workers: usize,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            host: "127.0.0.1".to_string(),
            port: 8080,
            workers: num_cpus::get(),
        }
    }
}

pub async fn run_server(config: ServerConfig) -> std::io::Result<()> {
    info!("Starting LLMGateway server on {}:{}", config.host, config.port);
    
    let metrics = Arc::new(MetricsCollector::new());
    let document_pipeline = Arc::new(DocumentPipeline::new(metrics.clone()));
    let inference_gateway = Arc::new(InferenceGateway::new(metrics.clone()));
    let feature_store = Arc::new(FeatureStore::new(metrics.clone()));
    let adversarial_generator = Arc::new(AdversarialGenerator::new(metrics.clone()));
    let model_dashboard = Arc::new(ModelDashboard::new(metrics.clone()));
    let model_registry = Arc::new(ModelRegistry::new(metrics.clone()));
    let prompt_manager = Arc::new(PromptExperimentManager::new(metrics.clone()));
    let gpu_scheduler = Arc::new(GpuScheduler::new(
        SchedulerConfig::default(),
        metrics.clone(),
    ));

    let app_state = AppState::new(
        metrics.clone(),
        document_pipeline,
        inference_gateway,
        feature_store,
        adversarial_generator,
        model_dashboard,
        model_registry,
        prompt_manager,
        gpu_scheduler,
    );

    let data = web::Data::new(app_state.clone());

    let server = HttpServer::new(move || {
        App::new()
            .app_data(data.clone())
            .configure(configure_routes)
    })
    .workers(config.workers)
    .bind((config.host.as_str(), config.port))?;

    info!("Server starting with {} workers", config.workers);
    
    server.run().await.map_err(|e| {
        error!("Server error: {}", e);
        e
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_server_config_default() {
        let config = ServerConfig::default();
        assert_eq!(config.host, "127.0.0.1");
        assert_eq!(config.port, 8080);
        assert!(config.workers > 0);
    }
}
