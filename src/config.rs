use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{anyhow, Context, Result};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigDefinition {
    #[serde(default = "default_config_id")]
    pub config_id: String,
    pub namespace: String,
    pub version: u64,
    pub parameters: serde_json::Value,
    pub enabled: bool,
    #[serde(default = "default_now")]
    pub applied_at: DateTime<Utc>,
}

fn default_config_id() -> String {
    format!("cfg_{}", Uuid::new_v4().simple())
}

fn default_now() -> DateTime<Utc> {
    Utc::now()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigSource {
    pub source_type: ConfigSourceType,
    pub priority: u32,
    pub location: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ConfigSourceType {
    File,
    Environment,
    Remote,
    Memory,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigChangeEvent {
    pub event_id: String,
    pub config_id: String,
    pub namespace: String,
    pub old_version: u64,
    pub new_version: u64,
    pub change_type: ConfigChangeType,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ConfigChangeType {
    Created,
    Updated,
    Deleted,
}

type ConfigChangeListener = Arc<dyn Fn(ConfigChangeEvent) -> Result<()> + Send + Sync>;

pub struct ConfigManager {
    sources: Vec<ConfigSource>,
    configs: DashMap<String, ConfigDefinition>,
    listeners: RwLock<Vec<ConfigChangeListener>>,
    watch_interval: Duration,
    shutdown_tx: Option<mpsc::Sender<()>>,
}

impl ConfigManager {
    pub fn new() -> Self {
        Self {
            sources: Vec::new(),
            configs: DashMap::new(),
            listeners: RwLock::new(Vec::new()),
            watch_interval: Duration::from_secs(30),
            shutdown_tx: None,
        }
    }

    pub fn with_watch_interval(mut self, interval: Duration) -> Self {
        self.watch_interval = interval;
        self
    }

    pub fn add_source(&mut self, source: ConfigSource) {
        self.sources.push(source);
        self.sources.sort_by_key(|s| s.priority);
    }

    pub fn register_listener<F>(&self, listener: F)
    where
        F: Fn(ConfigChangeEvent) -> Result<()> + Send + Sync + 'static,
    {
        self.listeners.write().push(Arc::new(listener));
    }

    pub async fn load_all(&mut self) -> Result<()> {
        info!("Loading configuration from all sources");
        
        let mut merged_configs: HashMap<String, ConfigDefinition> = HashMap::new();

        for source in &self.sources {
            match self.load_from_source(source).await {
                Ok(configs) => {
                    for config in configs {
                        let key = format!("{}:{}", config.namespace, config.config_id);
                        if let Some(existing) = merged_configs.get(&key) {
                            if config.version > existing.version {
                                merged_configs.insert(key, config);
                            }
                        } else {
                            merged_configs.insert(key, config);
                        }
                    }
                }
                Err(e) => {
                    warn!(?source, error = %e, "Failed to load config from source");
                }
            }
        }

        for (_, config) in merged_configs {
            let key = config.config_id.clone();
            let change_type = if self.configs.contains_key(&key) {
                ConfigChangeType::Updated
            } else {
                ConfigChangeType::Created
            };
            
            let old_version = self.configs.get(&key).map(|c| c.version).unwrap_or(0);
            self.configs.insert(key.clone(), config.clone());
            
            if old_version != config.version {
                self.notify_listeners(ConfigChangeEvent {
                    event_id: format!("evt_{}", Uuid::new_v4().simple()),
                    config_id: key,
                    namespace: config.namespace,
                    old_version,
                    new_version: config.version,
                    change_type,
                    timestamp: Utc::now(),
                });
            }
        }

        info!("Loaded {} configurations", self.configs.len());
        Ok(())
    }

    async fn load_from_source(&self, source: &ConfigSource) -> Result<Vec<ConfigDefinition>> {
        debug!(?source, "Loading config from source");
        
        match source.source_type {
            ConfigSourceType::File => self.load_from_file(&source.location).await,
            ConfigSourceType::Environment => self.load_from_env(),
            ConfigSourceType::Remote => self.load_from_remote(&source.location).await,
            ConfigSourceType::Memory => Ok(Vec::new()),
        }
    }

    async fn load_from_file(&self, path: &str) -> Result<Vec<ConfigDefinition>> {
        let path_buf = PathBuf::from(path);
        let content = tokio::fs::read_to_string(&path_buf)
            .await
            .with_context(|| format!("Failed to read config file: {}", path))?;

        let configs: Vec<ConfigDefinition> = if path.ends_with(".json") {
            serde_json::from_str(&content)?
        } else if path.ends_with(".yaml") || path.ends_with(".yml") {
            serde_yaml::from_str(&content)?
        } else if path.ends_with(".toml") {
            toml::from_str(&content)?
        } else {
            return Err(anyhow!("Unsupported config file format: {}", path));
        };

        Ok(configs)
    }

    fn load_from_env(&self) -> Result<Vec<ConfigDefinition>> {
        let mut configs = Vec::new();
        
        for (key, value) in std::env::vars() {
            if key.starts_with("CFG_") {
                let parts: Vec<&str> = key.splitn(3, '_').collect();
                if parts.len() >= 3 {
                    let namespace = parts[1].to_string();
                    let param_name = parts[2].to_string();
                    
                    let mut parameters = serde_json::Map::new();
                    parameters.insert(param_name, serde_json::Value::String(value));
                    
                    configs.push(ConfigDefinition {
                        config_id: format!("env_{}", namespace),
                        namespace,
                        version: 1,
                        parameters: serde_json::Value::Object(parameters),
                        enabled: true,
                        applied_at: Utc::now(),
                    });
                }
            }
        }
        
        Ok(configs)
    }

    async fn load_from_remote(&self, url: &str) -> Result<Vec<ConfigDefinition>> {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(10))
            .build()?;
            
        let response = client.get(url).send().await?;
        let configs: Vec<ConfigDefinition> = response.json().await?;
        Ok(configs)
    }

    pub fn get_config(&self, namespace: &str, config_id: &str) -> Option<ConfigDefinition> {
        self.configs.get(config_id).and_then(|c| {
            if c.namespace == namespace {
                Some(c.clone())
            } else {
                None
            }
        })
    }

    pub fn get_namespace_configs(&self, namespace: &str) -> Vec<ConfigDefinition> {
        self.configs
            .iter()
            .filter(|c| c.namespace == namespace)
            .map(|c| c.clone())
            .collect()
    }

    pub fn upsert_config(&self, config: ConfigDefinition) -> Result<()> {
        let key = config.config_id.clone();
        let change_type = if self.configs.contains_key(&key) {
            ConfigChangeType::Updated
        } else {
            ConfigChangeType::Created
        };
        
        let old_version = self.configs.get(&key).map(|c| c.version).unwrap_or(0);
        
        if config.version <= old_version {
            warn!(config_id = %key, "Config version is not newer, skipping update");
            return Ok(());
        }
        
        self.configs.insert(key.clone(), config.clone());
        
        self.notify_listeners(ConfigChangeEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            config_id: key,
            namespace: config.namespace,
            old_version,
            new_version: config.version,
            change_type,
            timestamp: Utc::now(),
        });
        
        Ok(())
    }

    pub fn delete_config(&self, config_id: &str) -> Result<()> {
        if let Some((_, config)) = self.configs.remove(config_id) {
            self.notify_listeners(ConfigChangeEvent {
                event_id: format!("evt_{}", Uuid::new_v4().simple()),
                config_id: config_id.to_string(),
                namespace: config.namespace,
                old_version: config.version,
                new_version: 0,
                change_type: ConfigChangeType::Deleted,
                timestamp: Utc::now(),
            });
        }
        Ok(())
    }

    fn notify_listeners(&self, event: ConfigChangeEvent) {
        let listeners = self.listeners.read();
        for listener in listeners.iter() {
            let event = event.clone();
            let listener = listener.clone();
            tokio::spawn(async move {
                if let Err(e) = listener(event) {
                    error!(error = %e, "Config change listener failed");
                }
            });
        }
    }

    pub async fn start_watcher(&mut self) -> Result<()> {
        let (tx, mut rx) = mpsc::channel::<()>(1);
        self.shutdown_tx = Some(tx);

        let interval = self.watch_interval;
        let configs_clone = self.configs.clone();
        let sources_clone = self.sources.clone();
        let listeners_clone = self.listeners.clone();

        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(interval);
            loop {
                tokio::select! {
                    _ = ticker.tick() => {
                        debug!("Checking for config updates");
                        for source in &sources_clone {
                            match load_from_source_internal(source).await {
                                Ok(new_configs) => {
                                    for new_config in new_configs {
                                        let key = new_config.config_id.clone();
                                        let should_notify = configs_clone.get(&key)
                                            .map(|existing| existing.version < new_config.version)
                                            .unwrap_or(true);
                                        
                                        if should_notify {
                                            let old_version = configs_clone.get(&key).map(|c| c.version).unwrap_or(0);
                                            let change_type = if old_version == 0 {
                                                ConfigChangeType::Created
                                            } else {
                                                ConfigChangeType::Updated
                                            };
                                            configs_clone.insert(key.clone(), new_config.clone());
                                            
                                            notify_listeners_internal(&listeners_clone, ConfigChangeEvent {
                                                event_id: format!("evt_{}", Uuid::new_v4().simple()),
                                                config_id: key,
                                                namespace: new_config.namespace,
                                                old_version,
                                                new_version: new_config.version,
                                                change_type,
                                                timestamp: Utc::now(),
                                            });
                                        }
                                    }
                                }
                                Err(e) => {
                                    warn!(error = %e, "Failed to refresh config from source");
                                }
                            }
                        }
                    }
                    _ = rx.recv() => {
                        info!("Config watcher shutting down");
                        break;
                    }
                }
            }
        });

        Ok(())
    }

    pub fn stop_watcher(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            drop(tx);
        }
    }
}

async fn load_from_source_internal(source: &ConfigSource) -> Result<Vec<ConfigDefinition>> {
    match source.source_type {
        ConfigSourceType::File => {
            let content = tokio::fs::read_to_string(&source.location).await?;
            if source.location.ends_with(".json") {
                Ok(serde_json::from_str(&content)?)
            } else if source.location.ends_with(".yaml") || source.location.ends_with(".yml") {
                Ok(serde_yaml::from_str(&content)?)
            } else {
                Err(anyhow!("Unsupported format"))
            }
        }
        ConfigSourceType::Environment => {
            let mut configs = Vec::new();
            for (key, value) in std::env::vars() {
                if key.starts_with("CFG_") {
                    let parts: Vec<&str> = key.splitn(3, '_').collect();
                    if parts.len() >= 3 {
                        let mut params = serde_json::Map::new();
                        params.insert(parts[2].to_string(), serde_json::Value::String(value));
                        configs.push(ConfigDefinition {
                            config_id: format!("env_{}", parts[1]),
                            namespace: parts[1].to_string(),
                            version: 1,
                            parameters: serde_json::Value::Object(params),
                            enabled: true,
                            applied_at: Utc::now(),
                        });
                    }
                }
            }
            Ok(configs)
        }
        ConfigSourceType::Remote => {
            let client = reqwest::Client::builder()
                .timeout(Duration::from_secs(10))
                .build()?;
            Ok(client.get(&source.location).send().await?.json().await?)
        }
        ConfigSourceType::Memory => Ok(Vec::new()),
    }
}

fn notify_listeners_internal(
    listeners: &RwLock<Vec<ConfigChangeListener>>,
    event: ConfigChangeEvent,
) {
    let listeners = listeners.read();
    for listener in listeners.iter() {
        let event = event.clone();
        let listener = listener.clone();
        tokio::spawn(async move {
            if let Err(e) = listener(event) {
                error!(error = %e, "Config change listener failed");
            }
        });
    }
}

impl Default for ConfigManager {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for ConfigManager {
    fn drop(&mut self) {
        self.stop_watcher();
    }
}
