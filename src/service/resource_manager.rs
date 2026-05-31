use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use uuid::Uuid;

use crate::domain::entity::Entity;
use crate::domain::run_instance::RunInstance;
use crate::infra::app_state::AppState;
use crate::infra::error::{AppError, AppResult};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Resource {
    pub id: String,
    pub resource_type: String,
    pub status: String,
    pub config: HashMap<String, serde_json::Value>,
    pub labels: HashMap<String, String>,
    pub entity_id: String,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub updated_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateResourceRequest {
    pub resource_type: String,
    #[serde(default)]
    pub config: HashMap<String, serde_json::Value>,
    #[serde(default)]
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceStatusResponse {
    pub id: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub progress: Option<f64>,
}

pub struct ResourceManager {
    state: AppState,
    resources: std::sync::Arc<parking_lot::Mutex<HashMap<String, Resource>>>,
    instances: std::sync::Arc<parking_lot::Mutex<HashMap<String, RunInstance>>>,
}

impl ResourceManager {
    pub fn new(state: AppState) -> Self {
        Self {
            state,
            resources: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
            instances: std::sync::Arc::new(parking_lot::Mutex::new(HashMap::new())),
        }
    }

    pub async fn create_resource(&self, request: CreateResourceRequest) -> AppResult<Resource> {
        let entity = Entity::new(request.resource_type.clone(), HashMap::new());

        let resource = Resource {
            id: format!("rsc_{}", Uuid::new_v4().simple()),
            resource_type: request.resource_type,
            status: "provisioning".to_string(),
            config: request.config,
            labels: request.labels,
            entity_id: entity.id.clone(),
            created_at: chrono::Utc::now(),
            updated_at: chrono::Utc::now(),
        };

        self.resources
            .lock()
            .insert(resource.id.clone(), resource.clone());

        Ok(resource)
    }

    pub async fn get_resource(&self, id: &str) -> AppResult<Resource> {
        let resources = self.resources.lock();
        resources
            .get(id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Resource {} not found", id)))
    }

    pub async fn get_resource_status(&self, id: &str) -> AppResult<ResourceStatusResponse> {
        let resources = self.resources.lock();
        let resource = resources
            .get(id)
            .ok_or_else(|| AppError::NotFound(format!("Resource {} not found", id)))?;

        let instances = self.instances.lock();
        let progress = instances
            .values()
            .find(|inst| inst.entity_id == id)
            .map(|inst| inst.progress);

        Ok(ResourceStatusResponse {
            id: id.to_string(),
            status: resource.status.clone(),
            progress,
        })
    }

    pub async fn update_resource_status(&self, id: &str, status: &str) -> AppResult<Resource> {
        let mut resources = self.resources.lock();
        let resource = resources
            .get_mut(id)
            .ok_or_else(|| AppError::NotFound(format!("Resource {} not found", id)))?;

        resource.status = status.to_string();
        resource.updated_at = chrono::Utc::now();

        Ok(resource.clone())
    }

    pub async fn list_resources(
        &self,
        resource_type: Option<&str>,
        status: Option<&str>,
    ) -> AppResult<Vec<Resource>> {
        let resources = self.resources.lock();
        Ok(resources
            .values()
            .filter(|r| {
                resource_type.map(|t| r.resource_type == t).unwrap_or(true)
                    && status.map(|s| r.status == s).unwrap_or(true)
            })
            .cloned()
            .collect())
    }

    pub async fn delete_resource(&self, id: &str) -> AppResult<()> {
        let mut resources = self.resources.lock();
        if resources.remove(id).is_none() {
            return Err(AppError::NotFound(format!("Resource {} not found", id)));
        }
        Ok(())
    }

    pub async fn create_instance(&self, resource_id: &str) -> AppResult<RunInstance> {
        let resources = self.resources.lock();
        if !resources.contains_key(resource_id) {
            return Err(AppError::NotFound(format!("Resource {} not found", resource_id)));
        }
        drop(resources);

        let instance = RunInstance::new(resource_id.to_string());
        self.instances
            .lock()
            .insert(instance.run_id.clone(), instance.clone());

        Ok(instance)
    }

    pub async fn update_instance_progress(
        &self,
        run_id: &str,
        phase: &str,
        progress: f64,
    ) -> AppResult<RunInstance> {
        let mut instances = self.instances.lock();
        let instance = instances
            .get_mut(run_id)
            .ok_or_else(|| AppError::NotFound(format!("Instance {} not found", run_id)))?;

        instance.update_phase(phase, progress);

        Ok(instance.clone())
    }

    pub async fn complete_instance(&self, run_id: &str) -> AppResult<RunInstance> {
        let mut instances = self.instances.lock();
        let instance = instances
            .get_mut(run_id)
            .ok_or_else(|| AppError::NotFound(format!("Instance {} not found", run_id)))?;

        instance.complete();

        Ok(instance.clone())
    }

    pub async fn fail_instance(&self, run_id: &str, error: &str) -> AppResult<RunInstance> {
        let mut instances = self.instances.lock();
        let instance = instances
            .get_mut(run_id)
            .ok_or_else(|| AppError::NotFound(format!("Instance {} not found", run_id)))?;

        instance.fail(error);

        Ok(instance.clone())
    }

    pub async fn get_instance(&self, run_id: &str) -> AppResult<RunInstance> {
        let instances = self.instances.lock();
        instances
            .get(run_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Instance {} not found", run_id)))
    }

    pub async fn list_instances(&self, resource_id: Option<&str>) -> AppResult<Vec<RunInstance>> {
        let instances = self.instances.lock();
        Ok(instances
            .values()
            .filter(|inst| resource_id.map(|id| inst.entity_id == id).unwrap_or(true))
            .cloned()
            .collect())
    }
}
