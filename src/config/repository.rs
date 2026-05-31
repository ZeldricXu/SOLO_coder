use crate::config::domain::{ConfigId, ConfigVersion, ConfigStatus};
use crate::utils::error::{Result, PlatformError};
use std::sync::Arc;
use tokio::sync::RwLock;
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SaveMode {
    CreateOnly,
    UpdateOnly { expected_version: u64 },
    Upsert,
}

pub trait ConfigRepository: Send + Sync + 'static {
    async fn get_current(&self, config_id: &ConfigId) -> Result<ConfigVersion>;
    async fn get_by_version(&self, config_id: &ConfigId, version: u64) -> Result<ConfigVersion>;
    async fn list(&self, namespace: Option<&str>) -> Result<Vec<ConfigVersion>>;
    async fn save(&self, config: ConfigVersion, mode: SaveMode) -> Result<ConfigVersion>;
    async fn list_history(&self, config_id: &ConfigId) -> Result<Vec<ConfigVersion>>;
    async fn delete(&self, config_id: &ConfigId) -> Result<()>;
    async fn atomically<F, T>(&self, config_id: &ConfigId, f: F) -> Result<T>
    where
        F: FnOnce(Option<&ConfigVersion>) -> Result<T> + Send;
    async fn compare_and_swap(
        &self,
        config_id: &ConfigId,
        expected_version: u64,
        new_config: ConfigVersion,
    ) -> Result<ConfigVersion>;
}

#[derive(Default, Clone)]
pub struct InMemoryConfigRepository {
    inner: Arc<RwLock<RepositoryState>>,
}

#[derive(Default)]
struct RepositoryState {
    current_versions: HashMap<ConfigId, ConfigVersion>,
    history: HashMap<ConfigId, Vec<ConfigVersion>>,
}

impl InMemoryConfigRepository {
    pub fn new() -> Self {
        Self::default()
    }

    fn validate_save(state: &RepositoryState, config: &ConfigVersion, mode: SaveMode) -> Result<()> {
        let exists = state.current_versions.contains_key(&config.config_id);
        
        match mode {
            SaveMode::CreateOnly => {
                if exists {
                    return Err(PlatformError::Conflict(
                        format!("config {} already exists", config.config_id)
                    ));
                }
            }
            SaveMode::UpdateOnly { expected_version } => {
                let current = state.current_versions
                    .get(&config.config_id)
                    .ok_or_else(|| {
                        PlatformError::NotFound(format!("config not found: {}", config.config_id))
                    })?;
                
                if current.version != expected_version {
                    return Err(PlatformError::Conflict(format!(
                        "version mismatch: expected {}, current {}",
                        expected_version, current.version
                    )));
                }
                
                if config.version != expected_version + 1 {
                    return Err(PlatformError::Validation(format!(
                        "new version {} must be exactly one more than expected version {}",
                        config.version, expected_version
                    )));
                }
            }
            SaveMode::Upsert => {}
        }
        Ok(())
    }
}

impl ConfigRepository for InMemoryConfigRepository {
    async fn get_current(&self, config_id: &ConfigId) -> Result<ConfigVersion> {
        let state = self.inner.read().await;
        state.current_versions
            .get(config_id)
            .cloned()
            .ok_or_else(|| {
                PlatformError::NotFound(format!("config not found: {}", config_id))
            })
    }

    async fn get_by_version(&self, config_id: &ConfigId, version: u64) -> Result<ConfigVersion> {
        let state = self.inner.read().await;
        let history = state.history.get(config_id)
            .ok_or_else(|| PlatformError::NotFound(format!("config history not found: {}", config_id)))?;
        
        history
            .iter()
            .find(|c| c.version == version)
            .cloned()
            .ok_or_else(|| {
                PlatformError::NotFound(format!(
                    "version {} not found for config {}",
                    version, config_id
                ))
            })
    }

    async fn list(&self, namespace: Option<&str>) -> Result<Vec<ConfigVersion>> {
        let state = self.inner.read().await;
        let configs: Vec<ConfigVersion> = state.current_versions
            .values()
            .filter(|c| {
                namespace.map_or(true, |ns| c.namespace == ns)
            })
            .cloned()
            .collect();
        Ok(configs)
    }

    async fn save(&self, config: ConfigVersion, mode: SaveMode) -> Result<ConfigVersion> {
        let mut state = self.inner.write().await;
        Self::validate_save(&state, &config, mode)?;
        
        let config_id = config.config_id.clone();
        
        state.history
            .entry(config_id.clone())
            .or_default()
            .push(config.clone());
        
        state.current_versions.insert(config_id, config.clone());
        Ok(config)
    }

    async fn list_history(&self, config_id: &ConfigId) -> Result<Vec<ConfigVersion>> {
        let state = self.inner.read().await;
        let history = state.history
            .get(config_id)
            .cloned()
            .unwrap_or_default();
        Ok(history)
    }

    async fn delete(&self, config_id: &ConfigId) -> Result<()> {
        let mut state = self.inner.write().await;
        state.current_versions.remove(config_id);
        state.history.remove(config_id);
        Ok(())
    }

    async fn atomically<F, T>(&self, config_id: &ConfigId, f: F) -> Result<T>
    where
        F: FnOnce(Option<&ConfigVersion>) -> Result<T> + Send,
    {
        let state = self.inner.read().await;
        let current = state.current_versions.get(config_id);
        f(current)
    }

    async fn compare_and_swap(
        &self,
        config_id: &ConfigId,
        expected_version: u64,
        new_config: ConfigVersion,
    ) -> Result<ConfigVersion> {
        self.save(new_config, SaveMode::UpdateOnly { expected_version }).await
    }
}
