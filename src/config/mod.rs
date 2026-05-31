pub mod domain;
pub mod repository;
pub mod service;

pub use domain::ConfigVersion as DomainConfigVersion;
pub use service::ConfigService;
pub use repository::{ConfigRepository, InMemoryConfigRepository};

use crate::models::config::{
    ConfigVersion, CreateConfigRequest, UpdateConfigRequest, RollbackRequest, ConfigHistoryEntry,
};
use crate::utils::error::Result;

impl From<DomainConfigVersion> for ConfigVersion {
    fn from(d: DomainConfigVersion) -> Self {
        ConfigVersion {
            config_id: d.config_id.to_string(),
            namespace: d.namespace,
            version: d.version,
            parameters: d.parameters,
            enabled: d.enabled,
            applied_at: d.applied_at,
            description: d.description,
        }
    }
}

impl From<domain::ConfigHistoryEntry> for ConfigHistoryEntry {
    fn from(d: domain::ConfigHistoryEntry) -> Self {
        ConfigHistoryEntry {
            config_id: d.config_id,
            version: d.version,
            applied_at: d.applied_at,
            description: d.description,
        }
    }
}

impl From<CreateConfigRequest> for domain::CreateConfigCommand {
    fn from(r: CreateConfigRequest) -> Self {
        domain::CreateConfigCommand {
            config_id: r.config_id,
            namespace: r.namespace,
            parameters: r.parameters,
            enabled: r.enabled,
            description: r.description,
        }
    }
}

impl From<UpdateConfigRequest> for domain::UpdateConfigCommand {
    fn from(r: UpdateConfigRequest) -> Self {
        domain::UpdateConfigCommand {
            parameters: r.parameters,
            enabled: r.enabled,
            description: r.description,
        }
    }
}

impl From<RollbackRequest> for domain::RollbackCommand {
    fn from(r: RollbackRequest) -> Self {
        domain::RollbackCommand {
            target_version: r.target_version,
        }
    }
}

pub struct ConfigManager {
    inner: ConfigService,
}

impl ConfigManager {
    pub fn new() -> Self {
        Self { inner: ConfigService::new() }
    }

    pub async fn create(&self, req: CreateConfigRequest) -> Result<ConfigVersion> {
        let cmd: domain::CreateConfigCommand = req.into();
        self.inner.create(cmd).await.map(Into::into)
    }

    pub async fn get(&self, config_id: &str) -> Result<ConfigVersion> {
        self.inner.get(config_id).await.map(Into::into)
    }

    pub async fn list(&self, namespace: Option<&str>) -> Result<Vec<ConfigVersion>> {
        self.inner.list(namespace).await
            .map(|v| v.into_iter().map(Into::into).collect())
    }

    pub async fn update(&self, config_id: &str, req: UpdateConfigRequest) -> Result<ConfigVersion> {
        let cmd: domain::UpdateConfigCommand = req.into();
        self.inner.update(config_id, cmd).await.map(Into::into)
    }

    pub async fn rollback(&self, config_id: &str, req: RollbackRequest) -> Result<ConfigVersion> {
        let cmd: domain::RollbackCommand = req.into();
        self.inner.rollback(config_id, cmd).await.map(Into::into)
    }

    pub async fn history(&self, config_id: &str) -> Result<Vec<ConfigHistoryEntry>> {
        self.inner.history(config_id).await
            .map(|v| v.into_iter().map(Into::into).collect())
    }

    pub async fn delete(&self, config_id: &str) -> Result<()> {
        self.inner.delete(config_id).await
    }
}

impl Clone for ConfigManager {
    fn clone(&self) -> Self {
        Self { inner: self.inner.clone() }
    }
}

impl Default for ConfigManager {
    fn default() -> Self {
        Self::new()
    }
}
