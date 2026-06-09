use crate::config::ConfigManager;
use crate::git::GitContext;
use crate::errors::Result;
use std::sync::Arc;
use reqwest::Client;

#[derive(Clone)]
pub struct AppContext {
    pub git: Arc<GitContext>,
    pub config: Arc<ConfigManager>,
    pub http_client: Client,
}

impl AppContext {
    pub async fn new(config_path: Option<&str>) -> Result<Self> {
        let config_manager = if let Some(path) = config_path {
            ConfigManager::with_custom_path(path)?
        } else {
            ConfigManager::new()?
        };

        let git_context = GitContext::open(None)?;

        Ok(Self {
            git: Arc::new(git_context),
            config: Arc::new(config_manager),
            http_client: Client::builder()
                .user_agent("gitflow-cli")
                .timeout(std::time::Duration::from_secs(30))
                .build()?,
        })
    }

    pub async fn reload_config(&self) -> Result<()> {
        self.config.reload().await
    }
}
