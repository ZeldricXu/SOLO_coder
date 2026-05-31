use crate::config::domain::{
    ConfigId, ConfigVersion, CreateConfigCommand, UpdateConfigCommand, 
    RollbackCommand, ConfigHistoryEntry, ConfigStatus
};
use crate::config::repository::{ConfigRepository, InMemoryConfigRepository, SaveMode};
use crate::utils::error::{Result, PlatformError};
use tracing::warn;

pub const MAX_RETRY_ATTEMPTS: u32 = 3;

pub trait ConfigService: Send + Sync + 'static {
    async fn create(&self, command: CreateConfigCommand) -> Result<ConfigVersion>;
    async fn get(&self, config_id: &str) -> Result<ConfigVersion>;
    async fn list(&self, namespace: Option<&str>) -> Result<Vec<ConfigVersion>>;
    async fn update(&self, config_id: &str, command: UpdateConfigCommand) -> Result<ConfigVersion>;
    async fn rollback(&self, config_id: &str, command: RollbackCommand) -> Result<ConfigVersion>;
    async fn history(&self, config_id: &str) -> Result<Vec<ConfigHistoryEntry>>;
    async fn delete(&self, config_id: &str) -> Result<()>;
}

#[derive(Clone)]
pub struct ConfigServiceImpl<R: ConfigRepository> {
    repository: R,
}

impl<R: ConfigRepository> ConfigServiceImpl<R> {
    pub fn new(repository: R) -> Self {
        Self { repository }
    }

    async fn retry_on_conflict<F, Fut, T>(&self, mut operation: F) -> Result<T>
    where
        F: FnMut() -> Fut,
        Fut: std::future::Future<Output = Result<T>>,
    {
        let mut last_error = None;
        for attempt in 0..MAX_RETRY_ATTEMPTS {
            match operation().await {
                Ok(result) => return Ok(result),
                Err(PlatformError::Conflict(msg)) => {
                    last_error = Some(PlatformError::Conflict(msg.clone()));
                    if attempt < MAX_RETRY_ATTEMPTS - 1 {
                        warn!(
                            attempt = attempt + 1,
                            "conflict detected, retrying operation: {}",
                            msg
                        );
                        tokio::time::sleep(tokio::time::Duration::from_millis(10 * (attempt + 1))).await;
                    }
                }
                Err(e) => return Err(e),
            }
        }
        Err(last_error.unwrap_or_else(|| PlatformError::Internal("max retry attempts exceeded".to_string())))
    }
}

pub type ConfigService = ConfigServiceImpl<InMemoryConfigRepository>;

impl ConfigService {
    pub fn new() -> Self {
        Self::new(InMemoryConfigRepository::new())
    }
}

impl Default for ConfigService {
    fn default() -> Self {
        Self::new()
    }
}

impl<R: ConfigRepository> ConfigService for ConfigServiceImpl<R> {
    async fn create(&self, command: CreateConfigCommand) -> Result<ConfigVersion> {
        let config_id = ConfigId::new(command.config_id);

        let mut config = ConfigVersion::new(
            config_id,
            command.namespace,
            command.parameters,
        );
        config.enabled = command.enabled;
        config.description = command.description;
        config.validate()?;
        
        self.repository.save(config, SaveMode::CreateOnly).await
    }

    async fn get(&self, config_id: &str) -> Result<ConfigVersion> {
        let id = ConfigId::new(config_id);
        self.repository.get_current(&id).await
    }

    async fn list(&self, namespace: Option<&str>) -> Result<Vec<ConfigVersion>> {
        self.repository.list(namespace).await
    }

    async fn update(&self, config_id: &str, command: UpdateConfigCommand) -> Result<ConfigVersion> {
        let id = ConfigId::new(config_id);
        let repo = self.repository.clone();
        
        self.retry_on_conflict(|| async {
            let current = repo.get_current(&id).await?;
            let expected_version = current.version;
            
            let mut new_version = current.next_version();
            new_version.parameters = command.parameters.clone();
            if let Some(enabled) = command.enabled {
                new_version.enabled = enabled;
            }
            if let Some(description) = command.description.clone() {
                new_version.description = Some(description);
            }
            
            new_version.validate()?;
            repo.compare_and_swap(&id, expected_version, new_version).await
        }).await
    }

    async fn rollback(&self, config_id: &str, command: RollbackCommand) -> Result<ConfigVersion> {
        let id = ConfigId::new(config_id);
        let repo = self.repository.clone();
        
        self.retry_on_conflict(|| async {
            let target = repo.get_by_version(&id, command.target_version).await?;
            let current = repo.get_current(&id).await?;
            let expected_version = current.version;
            
            let mut new_version = current.next_version();
            new_version.parameters = target.parameters.clone();
            new_version.enabled = target.enabled;
            new_version.description = Some(format!(
                "rollback from version {} to version {}",
                new_version.version, target.version
            ));
            
            new_version.validate()?;
            repo.compare_and_swap(&id, expected_version, new_version).await
        }).await
    }

    async fn history(&self, config_id: &str) -> Result<Vec<ConfigHistoryEntry>> {
        let id = ConfigId::new(config_id);
        let history = self.repository.list_history(&id).await?;
        
        Ok(history.into_iter().map(|c| ConfigHistoryEntry {
            config_id: c.config_id.to_string(),
            version: c.version,
            applied_at: c.applied_at,
            description: c.description,
        }).collect())
    }

    async fn delete(&self, config_id: &str) -> Result<()> {
        let id = ConfigId::new(config_id);
        let repo = self.repository.clone();
        
        self.retry_on_conflict(|| async {
            let current = repo.get_current(&id).await?;
            let expected_version = current.version;
            
            let mut archived = current.clone();
            archived.status = ConfigStatus::Archived;
            
            repo.compare_and_swap(&id, expected_version, archived).await?;
            repo.delete(&id).await?;
            Ok(())
        }).await
    }
}
