use std::sync::Arc;

use ab_test::{ExperimentRecorder, ExperimentService};
use common::config::AppConfig;
use dashmap::DashMap;
use db::{DatabasePool, RedisClient};
use inference_runtime::InferencePipeline;
use inference_runtime::InferenceRuntime;
use model_registry::{MinioStorage, ModelRegistryService};
use observability::metrics::MetricsRegistry;
use scheduler::{DynamicModelScheduler, SchedulerService};
use security::{ApiKeyAuthenticator, DataMasker, RateLimiter};
use traffic_router::{RolloutManager, TrafficRouter};

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<AppConfig>,
    pub db_pool: DatabasePool,
    pub redis_client: RedisClient,
    pub minio_storage: Arc<MinioStorage>,
    pub model_registry: Arc<ModelRegistryService>,
    pub inference_runtime: Arc<InferenceRuntime>,
    pub traffic_router: Arc<TrafficRouter>,
    pub scheduler: Arc<SchedulerService>,
    pub experiment_service: Arc<ExperimentService>,
    pub experiment_recorder: Arc<ExperimentRecorder>,
    pub api_key_authenticator: Arc<ApiKeyAuthenticator>,
    pub rate_limiter: Arc<RateLimiter>,
    pub data_masker: Arc<DataMasker>,
    pub metrics_registry: Arc<MetricsRegistry>,
    pub rollout_manager: RolloutManager,
    pub dynamic_scheduler: DynamicModelScheduler,
    pub pipelines: Arc<DashMap<String, InferencePipeline>>,
}

impl AppState {
    pub fn new(
        config: AppConfig,
        db_pool: DatabasePool,
        redis_client: RedisClient,
        minio_storage: MinioStorage,
        model_registry: ModelRegistryService,
        inference_runtime: InferenceRuntime,
        traffic_router: TrafficRouter,
        scheduler: SchedulerService,
        experiment_service: ExperimentService,
        experiment_recorder: ExperimentRecorder,
        api_key_authenticator: ApiKeyAuthenticator,
        rate_limiter: RateLimiter,
        data_masker: DataMasker,
        metrics_registry: MetricsRegistry,
        rollout_manager: RolloutManager,
        dynamic_scheduler: DynamicModelScheduler,
        pipelines: Arc<DashMap<String, InferencePipeline>>,
    ) -> Self {
        Self {
            config: Arc::new(config),
            db_pool,
            redis_client,
            minio_storage: Arc::new(minio_storage),
            model_registry: Arc::new(model_registry),
            inference_runtime: Arc::new(inference_runtime),
            traffic_router: Arc::new(traffic_router),
            scheduler: Arc::new(scheduler),
            experiment_service: Arc::new(experiment_service),
            experiment_recorder: Arc::new(experiment_recorder),
            api_key_authenticator: Arc::new(api_key_authenticator),
            rate_limiter: Arc::new(rate_limiter),
            data_masker: Arc::new(data_masker),
            metrics_registry: Arc::new(metrics_registry),
            rollout_manager,
            dynamic_scheduler,
            pipelines,
        }
    }
}
