pub mod common;
pub mod domain;
pub mod ports;
pub mod adapters;
pub mod modules;
pub mod api;

use std::sync::Arc;
use tracing::info;

use crate::common::event::EventBus;
use crate::common::auth::SignatureValidator;
use crate::common::context::AuditLogger;
use crate::common::metrics::HealthChecker;
use crate::adapters::{
    memory_repository::{
        InMemoryEntityRepository, InMemoryConfigRepository,
        InMemoryRunInstanceRepository, InMemoryResourceRepository,
        InMemoryRepository, EntityId,
    },
    in_memory_event::InMemoryEventPublisher,
    in_memory_services::{InMemoryNotification, InMemoryCloudSync},
    in_memory_mq::InMemoryMessageQueue,
    redis_cache::InMemoryCache,
};
use crate::modules::{
    device_shadow::service::DeviceShadowService,
    rule_engine::service::RuleEngineService,
    inference_scheduler::service::InferenceSchedulerService,
    inference_scheduler::model::InferenceResult,
    device_lifecycle::service::DeviceLifecycleService,
    data_aggregation::service::DataAggregationService,
    offline_cache::service::OfflineCacheService,
    ota_upgrade::service::OtaUpgradeService,
    protocol_adapter::service::ProtocolAdapterService,
};

impl EntityId for InferenceResult {
    fn id(&self) -> &str {
        &self.result_id
    }
}

pub struct EdgeSchedulerApp {
    pub event_bus: Arc<EventBus>,
    pub event_publisher: Arc<dyn crate::ports::mod::EventPublisherPort>,
    pub signature_validator: Arc<SignatureValidator>,
    pub audit_logger: Arc<AuditLogger>,
    pub health_checker: Arc<HealthChecker>,

    pub entity_repo: Arc<InMemoryEntityRepository>,
    pub config_repo: Arc<InMemoryConfigRepository>,
    pub run_instance_repo: Arc<InMemoryRunInstanceRepository>,
    pub resource_repo: Arc<InMemoryResourceRepository>,
    pub inference_result_repo: Arc<InMemoryRepository<InferenceResult>>,
    pub cache: Arc<InMemoryCache>,
    pub message_queue: Arc<InMemoryMessageQueue>,
    pub notification: Arc<InMemoryNotification>,
    pub cloud_sync: Arc<InMemoryCloudSync>,

    pub device_shadow_service: Arc<DeviceShadowService>,
    pub rule_engine_service: Arc<RuleEngineService>,
    pub inference_scheduler_service: Arc<InferenceSchedulerService>,
    pub device_lifecycle_service: Arc<DeviceLifecycleService>,
    pub data_aggregation_service: Arc<DataAggregationService>,
    pub offline_cache_service: Arc<OfflineCacheService>,
    pub ota_upgrade_service: Arc<OtaUpgradeService>,
    pub protocol_adapter_service: Arc<ProtocolAdapterService>,
}

impl EdgeSchedulerApp {
    pub async fn new() -> Arc<Self> {
        info!("Initializing EdgeScheduler application...");

        let event_bus = EventBus::new();
        let event_publisher = InMemoryEventPublisher::new(event_bus.clone());
        let signature_validator = Arc::new(SignatureValidator::new("edge-scheduler-secret-key"));
        let audit_logger = AuditLogger::new();
        let health_checker = HealthChecker::new();

        let entity_repo = InMemoryEntityRepository::new();
        let config_repo = InMemoryConfigRepository::new();
        let run_instance_repo = InMemoryRunInstanceRepository::new();
        let resource_repo = InMemoryResourceRepository::new();
        let inference_result_repo = InMemoryRepository::<InferenceResult>::new();
        let cache = InMemoryCache::new();
        let message_queue = InMemoryMessageQueue::new();
        let notification = InMemoryNotification::new();
        let cloud_sync = InMemoryCloudSync::new();

        let device_shadow_service = DeviceShadowService::new(
            event_publisher.clone(),
            signature_validator.clone(),
            audit_logger.clone(),
        );

        let rule_engine_service = RuleEngineService::new(
            event_publisher.clone(),
            notification.clone(),
            audit_logger.clone(),
        );

        let inference_scheduler_service = InferenceSchedulerService::new(
            event_publisher.clone(),
            cache.clone(),
            cloud_sync.clone(),
            inference_result_repo.clone(),
            audit_logger.clone(),
        );

        let device_lifecycle_service = DeviceLifecycleService::new(
            event_publisher.clone(),
            signature_validator.clone(),
            audit_logger.clone(),
        );

        let data_aggregation_service = DataAggregationService::new(
            event_publisher.clone(),
            Some(cloud_sync.clone()),
            audit_logger.clone(),
        );

        let offline_cache_service = OfflineCacheService::new(
            event_publisher.clone(),
            cloud_sync.clone(),
            audit_logger.clone(),
            None,
            Some(100),
            None,
        );

        let ota_upgrade_service = OtaUpgradeService::new(
            event_publisher.clone(),
            signature_validator.clone(),
            audit_logger.clone(),
        ).with_notification(notification.clone());

        let protocol_adapter_service = ProtocolAdapterService::new(
            event_publisher.clone(),
            cloud_sync.clone(),
            message_queue.clone(),
            audit_logger.clone(),
        );
        protocol_adapter_service.clone().start_reconnection_monitor().await;

        health_checker.set_component_health("event_bus", true, None);
        health_checker.set_component_health("database", true, None);
        health_checker.set_component_health("cache", true, None);
        health_checker.set_component_health("message_queue", true, None);
        health_checker.set_component_health("cloud_sync", true, None);

        info!("EdgeScheduler application initialized successfully");

        Arc::new(Self {
            event_bus,
            event_publisher,
            signature_validator,
            audit_logger,
            health_checker,
            entity_repo,
            config_repo,
            run_instance_repo,
            resource_repo,
            inference_result_repo,
            cache,
            message_queue,
            notification,
            cloud_sync,
            device_shadow_service,
            rule_engine_service,
            inference_scheduler_service,
            device_lifecycle_service,
            data_aggregation_service,
            offline_cache_service,
            ota_upgrade_service,
            protocol_adapter_service,
        })
    }

    pub async fn start_background_tasks(&self) {
        info!("Starting background tasks...");
        info!("Background tasks started");
    }
}

pub fn init_tracing() {
    use tracing_subscriber::{fmt, EnvFilter};

    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new("info,edge_scheduler=debug"));

    tracing_subscriber::registry()
        .with(filter)
        .with(fmt::layer().json())
        .init();
}
