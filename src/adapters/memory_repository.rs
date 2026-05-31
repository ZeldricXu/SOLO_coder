use std::sync::Arc;
use dashmap::DashMap;
use async_trait::async_trait;
use serde_json::Value;
use std::collections::HashMap;

use crate::common::error::AppResult;
use crate::domain::entity::{Entity, EntityStatus};
use crate::domain::config::Config;
use crate::domain::run_instance::{RunInstance, RunPhase};
use crate::domain::resource::{Resource, ResourceStatus};
use crate::ports::mod::{
    EntityRepositoryPort, ConfigRepositoryPort, RunInstanceRepositoryPort,
    ResourceRepositoryPort, RepositoryPort,
};

pub trait EntityId {
    fn id(&self) -> &str;
}

pub struct InMemoryRepository<T: Clone + Send + Sync + EntityId + 'static> {
    data: Arc<DashMap<String, T>>,
}

impl<T: Clone + Send + Sync + EntityId + 'static> InMemoryRepository<T> {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            data: Arc::new(DashMap::new()),
        })
    }
}

#[async_trait]
impl<T: Clone + Send + Sync + EntityId + 'static> RepositoryPort<T> for InMemoryRepository<T> {
    async fn save(&self, entity: &T) -> AppResult<()> {
        self.data.insert(entity.id().to_string(), entity.clone());
        Ok(())
    }

    async fn find_by_id(&self, id: &str) -> AppResult<Option<T>> {
        Ok(self.data.get(id).map(|e| e.clone()))
    }

    async fn delete(&self, id: &str) -> AppResult<()> {
        self.data.remove(id);
        Ok(())
    }

    async fn list(&self, page: u32, page_size: u32) -> AppResult<(Vec<T>, u64)> {
        let items: Vec<T> = self.data.iter().map(|e| e.clone()).collect();
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }
}

pub struct InMemoryEntityRepository {
    entities: Arc<DashMap<String, Entity>>,
}

impl InMemoryEntityRepository {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            entities: Arc::new(DashMap::new()),
        })
    }
}

#[async_trait]
impl RepositoryPort<Entity> for InMemoryEntityRepository {
    async fn save(&self, entity: &Entity) -> AppResult<()> {
        self.entities.insert(entity.id.clone(), entity.clone());
        Ok(())
    }

    async fn find_by_id(&self, id: &str) -> AppResult<Option<Entity>> {
        Ok(self.entities.get(id).map(|e| e.clone()))
    }

    async fn delete(&self, id: &str) -> AppResult<()> {
        self.entities.remove(id);
        Ok(())
    }

    async fn list(&self, page: u32, page_size: u32) -> AppResult<(Vec<Entity>, u64)> {
        let items: Vec<Entity> = self.entities.iter().map(|e| e.clone()).collect();
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }
}

#[async_trait]
impl EntityRepositoryPort for InMemoryEntityRepository {
    async fn find_by_type(&self, entity_type: &str, page: u32, page_size: u32) -> AppResult<(Vec<Entity>, u64)> {
        let items: Vec<Entity> = self.entities.iter()
            .filter(|e| e.entity_type == entity_type)
            .map(|e| e.clone())
            .collect();
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }

    async fn find_by_label(&self, key: &str, value: &str) -> AppResult<Vec<Entity>> {
        Ok(self.entities.iter()
            .filter(|e| e.labels.get(key).map(|v| v == value).unwrap_or(false))
            .map(|e| e.clone())
            .collect())
    }

    async fn update_attributes(&self, id: &str, attributes: HashMap<String, Value>) -> AppResult<()> {
        if let Some(mut entity) = self.entities.get_mut(id) {
            for (k, v) in attributes {
                entity.attributes.insert(k, v);
            }
            entity.touch();
        }
        Ok(())
    }
}

pub struct InMemoryConfigRepository {
    configs: Arc<DashMap<String, Config>>,
}

impl InMemoryConfigRepository {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            configs: Arc::new(DashMap::new()),
        })
    }
}

#[async_trait]
impl RepositoryPort<Config> for InMemoryConfigRepository {
    async fn save(&self, config: &Config) -> AppResult<()> {
        self.configs.insert(config.config_id.clone(), config.clone());
        Ok(())
    }

    async fn find_by_id(&self, id: &str) -> AppResult<Option<Config>> {
        Ok(self.configs.get(id).map(|c| c.clone()))
    }

    async fn delete(&self, id: &str) -> AppResult<()> {
        self.configs.remove(id);
        Ok(())
    }

    async fn list(&self, page: u32, page_size: u32) -> AppResult<(Vec<Config>, u64)> {
        let mut items: Vec<Config> = self.configs.iter().map(|c| c.clone()).collect();
        items.sort_by(|a, b| b.version.cmp(&a.version));
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }
}

#[async_trait]
impl ConfigRepositoryPort for InMemoryConfigRepository {
    async fn find_by_namespace(&self, namespace: &str) -> AppResult<Option<Config>> {
        Ok(self.configs.iter()
            .filter(|c| c.namespace == namespace)
            .max_by_key(|c| c.version)
            .map(|c| c.clone()))
    }

    async fn find_latest(&self, namespace: &str) -> AppResult<Option<Config>> {
        self.find_by_namespace(namespace).await
    }

    async fn list_by_namespace(&self, namespace: &str, page: u32, page_size: u32) -> AppResult<(Vec<Config>, u64)> {
        let mut items: Vec<Config> = self.configs.iter()
            .filter(|c| c.namespace == namespace)
            .map(|c| c.clone())
            .collect();
        items.sort_by(|a, b| b.version.cmp(&a.version));
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }
}

pub struct InMemoryRunInstanceRepository {
    instances: Arc<DashMap<String, RunInstance>>,
}

impl InMemoryRunInstanceRepository {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            instances: Arc::new(DashMap::new()),
        })
    }
}

#[async_trait]
impl RepositoryPort<RunInstance> for InMemoryRunInstanceRepository {
    async fn save(&self, instance: &RunInstance) -> AppResult<()> {
        self.instances.insert(instance.run_id.clone(), instance.clone());
        Ok(())
    }

    async fn find_by_id(&self, id: &str) -> AppResult<Option<RunInstance>> {
        Ok(self.instances.get(id).map(|r| r.clone()))
    }

    async fn delete(&self, id: &str) -> AppResult<()> {
        self.instances.remove(id);
        Ok(())
    }

    async fn list(&self, page: u32, page_size: u32) -> AppResult<(Vec<RunInstance>, u64)> {
        let mut items: Vec<RunInstance> = self.instances.iter().map(|r| r.clone()).collect();
        items.sort_by(|a, b| b.created_at.cmp(&a.created_at));
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }
}

#[async_trait]
impl RunInstanceRepositoryPort for InMemoryRunInstanceRepository {
    async fn find_by_entity_id(&self, entity_id: &str, page: u32, page_size: u32) -> AppResult<(Vec<RunInstance>, u64)> {
        let mut items: Vec<RunInstance> = self.instances.iter()
            .filter(|r| r.entity_id == entity_id)
            .map(|r| r.clone())
            .collect();
        items.sort_by(|a, b| b.created_at.cmp(&a.created_at));
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }

    async fn find_running(&self) -> AppResult<Vec<RunInstance>> {
        Ok(self.instances.iter()
            .filter(|r| r.is_running())
            .map(|r| r.clone())
            .collect())
    }

    async fn update_phase(&self, run_id: &str, phase: RunPhase, progress: f32) -> AppResult<()> {
        if let Some(mut instance) = self.instances.get_mut(run_id) {
            instance.phase = phase;
            instance.progress = progress;
            instance.updated_at = chrono::Utc::now();
        }
        Ok(())
    }
}

pub struct InMemoryResourceRepository {
    resources: Arc<DashMap<String, Resource>>,
}

impl InMemoryResourceRepository {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            resources: Arc::new(DashMap::new()),
        })
    }
}

#[async_trait]
impl RepositoryPort<Resource> for InMemoryResourceRepository {
    async fn save(&self, resource: &Resource) -> AppResult<()> {
        self.resources.insert(resource.id.clone(), resource.clone());
        Ok(())
    }

    async fn find_by_id(&self, id: &str) -> AppResult<Option<Resource>> {
        Ok(self.resources.get(id).map(|r| r.clone()))
    }

    async fn delete(&self, id: &str) -> AppResult<()> {
        self.resources.remove(id);
        Ok(())
    }

    async fn list(&self, page: u32, page_size: u32) -> AppResult<(Vec<Resource>, u64)> {
        let items: Vec<Resource> = self.resources.iter().map(|r| r.clone()).collect();
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }
}

#[async_trait]
impl ResourceRepositoryPort for InMemoryResourceRepository {
    async fn find_by_type(&self, resource_type: &str, page: u32, page_size: u32) -> AppResult<(Vec<Resource>, u64)> {
        let items: Vec<Resource> = self.resources.iter()
            .filter(|r| r.resource_type == resource_type)
            .map(|r| r.clone())
            .collect();
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }

    async fn update_status(&self, id: &str, status: ResourceStatus) -> AppResult<()> {
        if let Some(mut resource) = self.resources.get_mut(id) {
            resource.set_status(status);
        }
        Ok(())
    }
}
