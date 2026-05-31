use async_trait::async_trait;
use serde_json::Value;
use crate::common::error::AppResult;
use crate::domain::{entity::Entity, config::Config, run_instance::RunInstance, resource::Resource};
use crate::common::event::DomainEvent;

#[async_trait]
pub trait RepositoryPort<T>: Send + Sync {
    async fn save(&self, entity: &T) -> AppResult<()>;
    async fn find_by_id(&self, id: &str) -> AppResult<Option<T>>;
    async fn delete(&self, id: &str) -> AppResult<()>;
    async fn list(&self, page: u32, page_size: u32) -> AppResult<(Vec<T>, u64)>;
}

#[async_trait]
pub trait EntityRepositoryPort: RepositoryPort<Entity> {
    async fn find_by_type(&self, entity_type: &str, page: u32, page_size: u32) -> AppResult<(Vec<Entity>, u64)>;
    async fn find_by_label(&self, key: &str, value: &str) -> AppResult<Vec<Entity>>;
    async fn update_attributes(&self, id: &str, attributes: std::collections::HashMap<String, Value>) -> AppResult<()>;
}

#[async_trait]
pub trait ConfigRepositoryPort: RepositoryPort<Config> {
    async fn find_by_namespace(&self, namespace: &str) -> AppResult<Option<Config>>;
    async fn find_latest(&self, namespace: &str) -> AppResult<Option<Config>>;
    async fn list_by_namespace(&self, namespace: &str, page: u32, page_size: u32) -> AppResult<(Vec<Config>, u64)>;
}

#[async_trait]
pub trait RunInstanceRepositoryPort: RepositoryPort<RunInstance> {
    async fn find_by_entity_id(&self, entity_id: &str, page: u32, page_size: u32) -> AppResult<(Vec<RunInstance>, u64)>;
    async fn find_running(&self) -> AppResult<Vec<RunInstance>>;
    async fn update_phase(&self, run_id: &str, phase: crate::domain::run_instance::RunPhase, progress: f32) -> AppResult<()>;
}

#[async_trait]
pub trait ResourceRepositoryPort: RepositoryPort<Resource> {
    async fn find_by_type(&self, resource_type: &str, page: u32, page_size: u32) -> AppResult<(Vec<Resource>, u64)>;
    async fn update_status(&self, id: &str, status: crate::domain::resource::ResourceStatus) -> AppResult<()>;
}

#[async_trait]
pub trait EventPublisherPort: Send + Sync {
    async fn publish(&self, event: DomainEvent) -> AppResult<()>;
    async fn publish_many(&self, events: Vec<DomainEvent>) -> AppResult<()>;
}

#[async_trait]
pub trait MessageQueuePort: Send + Sync {
    async fn send(&self, queue: &str, message: Value) -> AppResult<()>;
    async fn receive(&self, queue: &str) -> AppResult<Option<Value>>;
    async fn acknowledge(&self, message_id: &str) -> AppResult<()>;
}

#[async_trait]
pub trait CachePort: Send + Sync {
    async fn get(&self, key: &str) -> AppResult<Option<String>>;
    async fn set(&self, key: &str, value: &str, ttl_seconds: Option<u64>) -> AppResult<()>;
    async fn delete(&self, key: &str) -> AppResult<()>;
    async fn exists(&self, key: &str) -> AppResult<bool>;
    async fn publish(&self, channel: &str, message: &str) -> AppResult<u64>;
}

#[async_trait]
pub trait NotificationPort: Send + Sync {
    async fn send_alert(&self, level: &str, title: &str, message: &str) -> AppResult<()>;
    async fn send_device_command(&self, device_id: &str, command: Value) -> AppResult<()>;
}

#[async_trait]
pub trait CloudSyncPort: Send + Sync {
    async fn upload_data(&self, data: Value) -> AppResult<()>;
    async fn download_config(&self) -> AppResult<Value>;
    async fn is_online(&self) -> bool;
    async fn check_connectivity(&self) -> AppResult<()>;
}
