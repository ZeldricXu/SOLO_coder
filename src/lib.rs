pub mod config;
pub mod scaffold;
pub mod monitoring;
pub mod feature_flags;
pub mod vulnerability;
pub mod scheduler;
pub mod data_access;
pub mod storage;
pub mod quality_gate;
pub mod api;
pub mod models;
pub mod utils;

use axum::Router;
use serde::{Deserialize, Serialize};

#[derive(Clone)]
pub struct AppState {
    pub config_manager: config::ConfigManager,
    pub scaffold_generator: scaffold::ScaffoldGenerator,
    pub metrics_aggregator: monitoring::MetricsAggregator,
    pub feature_flag_manager: feature_flags::FeatureFlagManager,
    pub vulnerability_manager: vulnerability::manager::VulnerabilityManager,
    pub task_scheduler: scheduler::TaskScheduler,
    pub migration_manager: data_access::MigrationManager,
    pub storage_manager: storage::StorageManager,
    pub quality_gate_manager: quality_gate::QualityGateManager,
}

impl AppState {
    pub fn new() -> Self {
        Self {
            config_manager: config::ConfigManager::new(),
            scaffold_generator: scaffold::ScaffoldGenerator::new().expect("failed to create scaffold generator"),
            metrics_aggregator: monitoring::MetricsAggregator::new(monitoring::MetricCollector::new()),
            feature_flag_manager: feature_flags::FeatureFlagManager::new(),
            vulnerability_manager: vulnerability::manager::VulnerabilityManager::with_sample_data(),
            task_scheduler: scheduler::TaskScheduler::new(),
            migration_manager: data_access::MigrationManager::new(),
            storage_manager: storage::StorageManager::new(),
            quality_gate_manager: quality_gate::QualityGateManager::new(),
        }
    }
}

impl Default for AppState {
    fn default() -> Self {
        Self::new()
    }
}

pub fn build_router(state: AppState) -> Router {
    api::create_router(state)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    pub host: String,
    pub port: u16,
    #[serde(default = "default_log_level")]
    pub log_level: String,
}

fn default_log_level() -> String {
    "info".to_string()
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            host: "0.0.0.0".to_string(),
            port: 8080,
            log_level: "info".to_string(),
        }
    }
}
