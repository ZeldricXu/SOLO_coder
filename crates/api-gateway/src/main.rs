use std::sync::Arc;

use api_gateway::AppState;
use common::config::AppConfig;
use common::types::RoutingStrategy;
use dashmap::DashMap;
use inference_runtime::{InferencePipeline, InferenceRuntime};
use model_registry::{MinioStorage, ModelRegistryService};
use observability::metrics::MetricsRegistry;
use scheduler::{DynamicModelScheduler, SchedulerConfig, SchedulerService};
use security::{ApiKeyAuthenticator, DataMasker, RateLimiter};
use traffic_router::{RolloutManager, TrafficRouter};
use ab_test::{ExperimentRecorder, ExperimentService};
use db::{init_database, init_redis};

use clap::Parser;
use tracing::info;

#[derive(Parser, Debug)]
#[command(name = "model-serving-gateway", about = "Unified Model Inference Gateway")]
struct Cli {
    #[arg(short, long, default_value = "config")]
    config_path: String,

    #[arg(short, long, default_value = "development")]
    env: String,
}

async fn init_app(config: &AppConfig) -> anyhow::Result<AppState> {
    let db_config = db::DatabaseConfig {
        url: config.database.url.clone(),
        max_connections: config.database.max_connections,
        min_connections: config.database.min_connections,
        connect_timeout_secs: config.database.acquire_timeout_secs,
        idle_timeout_secs: config.database.idle_timeout_secs,
    };
    let db_pool = init_database(&db_config).await?;

    let redis_config = db::RedisConfig {
        url: config.redis.url.clone(),
        max_connections: config.redis.pool_size as usize,
        connect_timeout_secs: config.redis.retry_delay_ms / 1000 + 1,
        response_timeout_secs: 10,
    };
    let redis_client = init_redis(&redis_config).await?;

    let minio_config = model_registry::MinioConfig {
        endpoint: config.storage.s3_endpoint.clone().unwrap_or_else(|| "http://localhost:9000".to_string()),
        access_key: std::env::var("MINIO_ACCESS_KEY").unwrap_or_else(|_| "minioadmin".to_string()),
        secret_key: std::env::var("MINIO_SECRET_KEY").unwrap_or_else(|_| "minioadmin".to_string()),
        bucket: config.storage.s3_bucket.clone().unwrap_or_else(|| "models".to_string()),
        region: config.storage.s3_region.clone().unwrap_or_else(|| "us-east-1".to_string()),
        use_ssl: false,
    };
    let minio_storage = MinioStorage::new(
        &minio_config.endpoint,
        &minio_config.access_key,
        &minio_config.secret_key,
        &minio_config.region,
        &minio_config.bucket,
        minio_config.use_ssl,
    )?;

    let model_registry = ModelRegistryService::new(
        db_pool.clone(),
        redis_client.clone(),
        minio_storage.clone(),
    );

    let runtime_config = inference_runtime::RuntimeConfig::default();
    let inference_runtime = InferenceRuntime::new(runtime_config);

    let strategy = match config.routing.default_strategy.as_str() {
        "user_hash" => RoutingStrategy::UserHash,
        "region" => RoutingStrategy::Region,
        "round_robin" => RoutingStrategy::RoundRobin,
        "experiment" => RoutingStrategy::Experiment,
        _ => RoutingStrategy::Random,
    };
    let traffic_router = TrafficRouter::new(
        db_pool.clone(),
        redis_client.clone(),
        strategy,
    );

    let runtime_client = Arc::new(traffic_router::RuntimeClient::new());
    let model_registry_arc = Arc::new(model_registry.clone());
    let scheduler = SchedulerService::new(
        SchedulerConfig::default(),
        runtime_client,
        model_registry_arc,
    );

    let experiment_recorder = ExperimentRecorder::new(redis_client.clone());
    let experiment_service = ExperimentService::new(
        redis_client.clone(),
        experiment_recorder.clone(),
    );

    let api_key_authenticator = ApiKeyAuthenticator::new(
        db_pool.clone(),
        redis_client.clone(),
    );

    let rate_limiter = RateLimiter::new(
        redis_client.clone(),
        security::RateLimitConfig::default(),
    );

    let data_masker = DataMasker::new();

    let metrics_registry = MetricsRegistry::new()?;

    let rollout_manager = RolloutManager::new(redis_client.clone());
    let dynamic_scheduler = DynamicModelScheduler::new(
        scheduler::DynamicSchedulerConfig::default(),
    );
    let pipelines = Arc::new(DashMap::<String, InferencePipeline>::new());

    Ok(AppState::new(
        config.clone(),
        db_pool,
        redis_client,
        minio_storage,
        model_registry,
        inference_runtime,
        traffic_router,
        scheduler,
        experiment_service,
        experiment_recorder,
        api_key_authenticator,
        rate_limiter,
        data_masker,
        metrics_registry,
        rollout_manager,
        dynamic_scheduler,
        pipelines,
    ))
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();

    std::env::set_var("APP_CONFIG_PATH", &cli.config_path);
    std::env::set_var("APP_ENV", &cli.env);

    let config = AppConfig::load().unwrap_or_default();

    let tracing_config = observability::TracingConfig {
        service_name: "model-serving-gateway".to_string(),
        endpoint: config.observability.otlp_endpoint.clone(),
        level: config.observability.log_level.clone(),
        json_format: config.observability.log_format == "json",
        otlp_enabled: config.observability.otlp_endpoint.is_some(),
    };
    let _ = observability::init_tracing(&tracing_config);
    observability::init_panic_hook();

    info!("Starting Model Serving Gateway...");
    info!("Environment: {}", config.environment);
    info!("Server: {}:{}", config.server.host, config.server.port);

    let state = init_app(&config).await?;

    state.inference_runtime.start().await?;
    state.scheduler.start();

    let rm = state.rollout_manager.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(5));
        loop {
            interval.tick().await;
            rm.tick();
        }
    });

    let app = api_gateway::build_app(state.clone());

    let addr = config.server_addr();
    let listener = tokio::net::TcpListener::bind(&addr).await?;
    info!("Gateway listening on {}", addr);

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;

    info!("Gateway shutdown complete");
    observability::shutdown_tracing();
    Ok(())
}

async fn shutdown_signal() {
    tokio::signal::ctrl_c()
        .await
        .expect("Failed to install CTRL+C handler");
    info!("Received shutdown signal");
}
