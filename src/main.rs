use std::sync::Arc;
use std::net::SocketAddr;
use tokio;
use tracing::{info, warn, error};
use tracing_subscriber::{fmt, EnvFilter};
use modelguard::*;
use serde_json::json;

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| {
            EnvFilter::new("modelguard=info,tower_http=debug,axum=info")
        }))
        .json()
        .init();

    info!("Starting ModelGuard...");

    let config = Config::new("default", json!({
        "feature_store": {
            "consistency_check_enabled": true,
            "default_ttl_seconds": 86400
        },
        "gateway": {
            "default_strategy": "round_robin",
            "fallback": {
                "enabled": true,
                "timeout_ms": 30000,
                "max_retries": 2
            },
            "circuit_breaker_threshold": 5,
            "circuit_breaker_reset_ms": 60000
        }
    }));

    let feature_store = Arc::new(FeatureStoreService::with_in_memory_backend(config.clone()));
    let model_registry = Arc::new(ModelRegistryService::with_in_memory_backend(config.clone()));
    let inference_gateway = Arc::new(InferenceGatewayService::with_in_memory_backend(config.clone()));
    let adversarial_service = Arc::new(AdversarialService::new(config.clone()));
    let prompt_service = Arc::new(PromptExperimentService::new(config.clone()));
    let gpu_scheduler = Arc::new(GpuSchedulerService::new());
    let document_pipeline = Arc::new(DocumentPipelineService::new());
    let evaluation_dashboard = Arc::new(EvaluationDashboardService::new());

    gpu_scheduler.register_device(GpuDevice::new(0, 24576, "node-0"));
    gpu_scheduler.register_device(GpuDevice::new(1, 24576, "node-0"));
    gpu_scheduler.register_device(GpuDevice::new(2, 24576, "node-1"));
    gpu_scheduler.register_device(GpuDevice::new(3, 24576, "node-1"));

    info!("GPU scheduler initialized with 4 devices");

    inference_gateway.register_provider(ProviderRegistrationRequest {
        name: "OpenAI GPT-4".to_string(),
        provider_type: "openai".to_string(),
        base_url: "https://api.openai.com/v1/chat/completions".to_string(),
        api_key: "placeholder".to_string(),
        model_id: "openai-gpt-4".to_string(),
        weight: Some(100),
        timeout_ms: Some(30000),
        max_concurrent: Some(100),
    }).await?;

    inference_gateway.register_provider(ProviderRegistrationRequest {
        name: "Anthropic Claude".to_string(),
        provider_type: "anthropic".to_string(),
        base_url: "https://api.anthropic.com/v1/messages".to_string(),
        api_key: "placeholder".to_string(),
        model_id: "anthropic-claude".to_string(),
        weight: Some(100),
        timeout_ms: Some(30000),
        max_concurrent: Some(100),
    }).await?;

    info!("Inference gateway initialized with 2 providers");

    adversarial_service.register_strategy(AttackStrategy::PromptInjection, Arc::new(PromptInjectionAttack));
    adversarial_service.register_strategy(AttackStrategy::Jailbreak, Arc::new(JailbreakAttack));
    adversarial_service.register_strategy(AttackStrategy::AdversarialSuffix, Arc::new(AdversarialSuffixAttack));
    adversarial_service.register_strategy(AttackStrategy::RolePlay, Arc::new(RolePlayAttack));
    adversarial_service.register_strategy(AttackStrategy::Combination, Arc::new(CombinationAttack::new()));

    info!("Adversarial service initialized with 5 attack strategies");

    let app_state = api::handlers::AppState {
        feature_store,
        model_registry,
        inference_gateway,
        adversarial_service,
        prompt_service,
        gpu_scheduler,
        document_pipeline,
        evaluation_dashboard,
    };

    let app = api::create_router(app_state);

    let addr: SocketAddr = "0.0.0.0:8080".parse().map_err(|e| ModelGuardError::InternalError(format!("Address parse error: {}", e)))?;
    info!("ModelGuard server listening on {}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app)
        .await
        .map_err(|e| ModelGuardError::InternalError(format!("Server error: {}", e)))?;

    Ok(())
}
