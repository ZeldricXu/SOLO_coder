use std::sync::Arc;

use ab_test::{ExperimentRecorder, ExperimentService};
use common::config::AppConfig;
use db::{DatabasePool, RedisClient};
use inference_runtime::InferenceRuntime;
use model_registry::{MinioStorage, ModelRegistryService};
use observability::metrics::MetricsRegistry;
use scheduler::ResourceScheduler;
use security::{ApiKeyAuthenticator, DataMasker, RateLimiter};
use traffic_router::TrafficRouter;

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<AppConfig>,
    pub db_pool: DatabasePool,
    pub redis_client: RedisClient,
    pub minio_storage: Arc<MinioStorage>,
    pub model_registry: Arc<ModelRegistryService>,
    pub inference_runtime: Arc<InferenceRuntime>,
    pub traffic_router: Arc<TrafficRouter>,
    pub scheduler: Arc<ResourceScheduler>,
    pub experiment_service: Arc<ExperimentService>,
    pub experiment_recorder: Arc<ExperimentRecorder>,
    pub api_key_authenticator: Arc<ApiKeyAuthenticator>,
    pub rate_limiter: Arc<RateLimiter>,
    pub data_masker: Arc<DataMasker>,
    pub metrics_registry: Arc<MetricsRegistry>,
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
        scheduler: ResourceScheduler,
        experiment_service: ExperimentService,
        experiment_recorder: ExperimentRecorder,
        api_key_authenticator: ApiKeyAuthenticator,
        rate_limiter: RateLimiter,
        data_masker: DataMasker,
        metrics_registry: MetricsRegistry,
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
        }
    }
}
