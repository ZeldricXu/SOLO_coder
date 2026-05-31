use super::{TeeConfig, MpcConfig, MaskingConfig};
use crate::models::AppError;
use crate::utils::{current_datetime, generate_id};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::sync::{Arc, RwLock};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum DeploymentScene {
    Production,
    Staging,
    Development,
    Testing,
    DisasterRecovery,
    Custom,
}

impl DeploymentScene {
    pub fn to_str(&self) -> &'static str {
        match self {
            DeploymentScene::Production => "production",
            DeploymentScene::Staging => "staging",
            DeploymentScene::Development => "development",
            DeploymentScene::Testing => "testing",
            DeploymentScene::DisasterRecovery => "disaster_recovery",
            DeploymentScene::Custom => "custom",
        }
    }

    pub fn from_str(s: &str) -> Result<Self, AppError> {
        match s.to_lowercase().as_str() {
            "production" => Ok(DeploymentScene::Production),
            "staging" => Ok(DeploymentScene::Staging),
            "development" => Ok(DeploymentScene::Development),
            "testing" => Ok(DeploymentScene::Testing),
            "disaster_recovery" => Ok(DeploymentScene::DisasterRecovery),
            "custom" => Ok(DeploymentScene::Custom),
            _ => Err(AppError::Validation(format!("Unknown deployment scene: {}", s))),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SceneTeeConfig {
    pub scene: DeploymentScene,
    pub base_config: TeeConfig,
    pub overrides: Option<serde_json::Value>,
    pub priority: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigVersion {
    pub version_id: String,
    pub version: u32,
    pub scene: DeploymentScene,
    pub created_at: DateTime<Utc>,
    pub created_by: Option<String>,
    pub changelog: Option<String>,
    pub is_active: bool,
}

pub trait ConfigurationListener: Send + Sync {
    fn on_config_changed(&self, module: &str, old_version: u32, new_version: u32);
    fn on_config_rolled_back(&self, module: &str, from_version: u32, to_version: u32);
}

type ListenerBox = Arc<dyn ConfigurationListener>;

pub struct DynamicConfigManager {
    active_scene: RwLock<DeploymentScene>,
    tee_configs: DashMap<DeploymentScene, SceneTeeConfig>,
    tee_versions: DashMap<DeploymentScene, Vec<ConfigVersion>>,
    mpc_strategy_config: RwLock<MpcStrategyConfig>,
    masking_async_config: RwLock<MaskingAsyncConfig>,
    listeners: DashMap<String, Vec<ListenerBox>>,
    current_tee_version: RwLock<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpcStrategyConfig {
    pub active_strategy: String,
    pub available_strategies: Vec<String>,
    pub strategy_params: serde_json::Value,
}

impl Default for MpcStrategyConfig {
    fn default() -> Self {
        Self {
            active_strategy: "default".to_string(),
            available_strategies: vec!["default".to_string(), "secure".to_string(), "fast".to_string()],
            strategy_params: serde_json::json!({}),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingAsyncConfig {
    pub enabled: bool,
    pub max_queue_size: usize,
    pub worker_threads: usize,
    pub callback_timeout_ms: u64,
    pub retry_attempts: u32,
}

impl Default for MaskingAsyncConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            max_queue_size: 10000,
            worker_threads: 4,
            callback_timeout_ms: 30000,
            retry_attempts: 3,
        }
    }
}

impl DynamicConfigManager {
    pub fn new() -> Self {
        let manager = Self {
            active_scene: RwLock::new(DeploymentScene::Production),
            tee_configs: DashMap::new(),
            tee_versions: DashMap::new(),
            mpc_strategy_config: RwLock::new(MpcStrategyConfig::default()),
            masking_async_config: RwLock::new(MaskingAsyncConfig::default()),
            listeners: DashMap::new(),
            current_tee_version: RwLock::new(1),
        };

        manager.init_default_scenes();
        manager
    }

    fn init_default_scenes(&self) {
        let base_prod = TeeConfig {
            enabled: true,
            max_enclaves: 64,
            attestation_timeout_ms: 30000,
            supported_techs: vec!["SGX".to_string(), "SEV".to_string(), "TrustZone".to_string()],
        };

        let prod_scene = SceneTeeConfig {
            scene: DeploymentScene::Production,
            base_config: base_prod,
            overrides: None,
            priority: 100,
        };

        let staging = TeeConfig {
            enabled: true,
            max_enclaves: 32,
            attestation_timeout_ms: 60000,
            supported_techs: vec!["SGX".to_string(), "SEV".to_string(), "TrustZone".to_string(), "Generic".to_string()],
        };

        let staging_scene = SceneTeeConfig {
            scene: DeploymentScene::Staging,
            base_config: staging,
            overrides: Some(serde_json::json!({"debug_mode": true})),
            priority: 50,
        };

        let dev = TeeConfig {
            enabled: true,
            max_enclaves: 16,
            attestation_timeout_ms: 120000,
            supported_techs: vec!["Generic".to_string()],
        };

        let dev_scene = SceneTeeConfig {
            scene: DeploymentScene::Development,
            base_config: dev,
            overrides: Some(serde_json::json!({"debug_mode": true, "mock_attestation": true})),
            priority: 10,
        };

        self.tee_configs.insert(DeploymentScene::Production, prod_scene);
        self.tee_configs.insert(DeploymentScene::Staging, staging_scene);
        self.tee_configs.insert(DeploymentScene::Development, dev_scene);

        for scene in &[DeploymentScene::Production, DeploymentScene::Staging, DeploymentScene::Development] {
            self.tee_versions.insert(*scene, vec![ConfigVersion {
                version_id: generate_id("v"),
                version: 1,
                scene: *scene,
                created_at: current_datetime(),
                created_by: Some("system".to_string()),
                changelog: Some("Initial configuration".to_string()),
                is_active: true,
            }]);
        }
    }

    pub fn get_active_scene(&self) -> DeploymentScene {
        *self.active_scene.read().unwrap()
    }

    pub fn set_active_scene(&self, scene: DeploymentScene) -> Result<(), AppError> {
        if !self.tee_configs.contains_key(&scene) {
            return Err(AppError::Validation(format!("Scene not configured: {:?}", scene)));
        }

        let old_scene = self.get_active_scene();
        if old_scene == scene {
            return Ok(());
        }

        let old_version = *self.current_tee_version.read().unwrap();
        *self.active_scene.write().unwrap() = scene;
        
        let new_version = old_version + 1;
        *self.current_tee_version.write().unwrap() = new_version;

        self.notify_listeners("tee", old_version, new_version);
        Ok(())
    }

    pub fn get_tee_config(&self) -> Result<TeeConfig, AppError> {
        let scene = self.get_active_scene();
        self.get_tee_config_for_scene(scene)
    }

    pub fn get_tee_config_for_scene(&self, scene: DeploymentScene) -> Result<TeeConfig, AppError> {
        self.tee_configs
            .get(&scene)
            .map(|entry| entry.base_config.clone())
            .ok_or_else(|| AppError::NotFound(format!("TEE config not found for scene: {:?}", scene)))
    }

    pub fn update_tee_config(
        &self,
        scene: DeploymentScene,
        new_config: TeeConfig,
        changelog: Option<String>,
        created_by: Option<String>,
    ) -> Result<u32, AppError> {
        let versions = self.tee_versions
            .get(&scene)
            .ok_or_else(|| AppError::NotFound(format!("No versions found for scene: {:?}", scene)))?;

        let current_version = versions.last()
            .map(|v| v.version)
            .unwrap_or(0);

        let new_version_num = current_version + 1;

        if let Some(mut config_entry) = self.tee_configs.get_mut(&scene) {
            config_entry.base_config = new_config;
        }

        let new_version = ConfigVersion {
            version_id: generate_id("v"),
            version: new_version_num,
            scene,
            created_at: current_datetime(),
            created_by,
            changelog,
            is_active: true,
        };

        if let Some(mut versions_entry) = self.tee_versions.get_mut(&scene) {
            for v in versions_entry.iter_mut() {
                v.is_active = false;
            }
            versions_entry.push(new_version);
        }

        let active_scene = self.get_active_scene();
        if active_scene == scene {
            let old_version = *self.current_tee_version.read().unwrap();
            *self.current_tee_version.write().unwrap() = new_version_num;
            self.notify_listeners("tee", old_version, new_version_num);
        }

        Ok(new_version_num)
    }

    pub fn rollback_tee_config(&self, scene: DeploymentScene, target_version: u32) -> Result<(), AppError> {
        let versions = self.tee_versions
            .get(&scene)
            .ok_or_else(|| AppError::NotFound(format!("No versions found for scene: {:?}", scene)))?;

        let target = versions.iter().find(|v| v.version == target_version)
            .ok_or_else(|| AppError::NotFound(format!("Version {} not found", target_version)))?;

        if !target.is_active {
            let current_version = versions.last().map(|v| v.version).unwrap_or(0);
            
            let active_scene = self.get_active_scene();
            if active_scene == scene {
                self.notify_rollback_listeners("tee", current_version, target_version);
            }

            if let Some(mut versions_entry) = self.tee_versions.get_mut(&scene) {
                for v in versions_entry.iter_mut() {
                    v.is_active = v.version == target_version;
                }
            }
        }

        Ok(())
    }

    pub fn get_tee_config_versions(&self, scene: DeploymentScene) -> Vec<ConfigVersion> {
        self.tee_versions
            .get(&scene)
            .map(|entry| entry.clone())
            .unwrap_or_default()
    }

    pub fn list_scenes(&self) -> Vec<DeploymentScene> {
        self.tee_configs.iter().map(|entry| *entry.key()).collect()
    }

    pub fn get_mpc_strategy_config(&self) -> MpcStrategyConfig {
        self.mpc_strategy_config.read().unwrap().clone()
    }

    pub fn set_active_mpc_strategy(&self, strategy: &str) -> Result<(), AppError> {
        let mut config = self.mpc_strategy_config.write().unwrap();
        
        if !config.available_strategies.iter().any(|s| s == strategy) {
            return Err(AppError::Validation(format!("Unknown MPC strategy: {}", strategy)));
        }

        config.active_strategy = strategy.to_string();
        drop(config);

        self.notify_listeners("mpc", 0, 1);
        Ok(())
    }

    pub fn register_mpc_strategy(&self, strategy: &str) {
        let mut config = self.mpc_strategy_config.write().unwrap();
        if !config.available_strategies.iter().any(|s| s == strategy) {
            config.available_strategies.push(strategy.to_string());
        }
    }

    pub fn get_masking_async_config(&self) -> MaskingAsyncConfig {
        self.masking_async_config.read().unwrap().clone()
    }

    pub fn update_masking_async_config(&self, new_config: MaskingAsyncConfig) {
        *self.masking_async_config.write().unwrap() = new_config;
        self.notify_listeners("masking", 0, 1);
    }

    pub fn add_listener(&self, module: &str, listener: ListenerBox) {
        self.listeners
            .entry(module.to_string())
            .or_default()
            .push(listener);
    }

    fn notify_listeners(&self, module: &str, old_version: u32, new_version: u32) {
        if let Some(listeners) = self.listeners.get(module) {
            for listener in listeners.iter() {
                listener.on_config_changed(module, old_version, new_version);
            }
        }
    }

    fn notify_rollback_listeners(&self, module: &str, from_version: u32, to_version: u32) {
        if let Some(listeners) = self.listeners.get(module) {
            for listener in listeners.iter() {
                listener.on_config_rolled_back(module, from_version, to_version);
            }
        }
    }
}

impl Default for DynamicConfigManager {
    fn default() -> Self {
        Self::new()
    }
}
