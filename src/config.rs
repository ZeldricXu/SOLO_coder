use crate::error::SystemError;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::time::Duration;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    pub logger: LoggerConfig,
    pub storage: StorageConfig,
    pub offline_cache: OfflineCacheConfig,
    pub device_shadow: DeviceShadowConfig,
    pub aggregator: EdgeAggregatorConfig,
    pub scheduler: SchedulerConfig,
    pub notifier: NotifierConfig,
    pub core: CoreConfig,
    pub gateway: GatewayConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoggerConfig {
    pub level: String,
    pub file_path: PathBuf,
    pub max_size_mb: u64,
    pub max_files: u32,
    pub json_format: bool,
}

impl Default for LoggerConfig {
    fn default() -> Self {
        Self {
            level: "info".to_string(),
            file_path: PathBuf::from("./logs/app.log"),
            max_size_mb: 100,
            max_files: 10,
            json_format: false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StorageConfig {
    pub root_dir: PathBuf,
    pub max_capacity_gb: u64,
    pub cleanup_interval_secs: u64,
}

impl Default for StorageConfig {
    fn default() -> Self {
        Self {
            root_dir: PathBuf::from("./data/storage"),
            max_capacity_gb: 100,
            cleanup_interval_secs: 3600,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OfflineCacheConfig {
    pub db_path: PathBuf,
    pub max_cache_size_mb: u64,
    pub sync_interval_secs: u64,
    pub cloud_endpoint: String,
}

impl Default for OfflineCacheConfig {
    fn default() -> Self {
        Self {
            db_path: PathBuf::from("./data/offline_cache.db"),
            max_cache_size_mb: 500,
            sync_interval_secs: 30,
            cloud_endpoint: "https://api.example.com/upload".to_string(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceShadowConfig {
    pub sync_interval_secs: u64,
    pub cloud_endpoint: String,
    pub report_interval_secs: u64,
}

impl Default for DeviceShadowConfig {
    fn default() -> Self {
        Self {
            sync_interval_secs: 5,
            cloud_endpoint: "https://api.example.com/shadow".to_string(),
            report_interval_secs: 60,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EdgeAggregatorConfig {
    pub window_size_secs: u64,
    pub max_batch_size: usize,
    pub aggregation_rules: Vec<AggregationRule>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregationRule {
    pub metric: String,
    pub function: AggregationFunction,
    pub output_field: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AggregationFunction {
    Sum,
    Avg,
    Min,
    Max,
    Count,
    StdDev,
}

impl Default for EdgeAggregatorConfig {
    fn default() -> Self {
        Self {
            window_size_secs: 60,
            max_batch_size: 1000,
            aggregation_rules: vec![
                AggregationRule {
                    metric: "temperature".to_string(),
                    function: AggregationFunction::Avg,
                    output_field: "temperature_avg".to_string(),
                },
                AggregationRule {
                    metric: "humidity".to_string(),
                    function: AggregationFunction::Avg,
                    output_field: "humidity_avg".to_string(),
                },
            ],
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchedulerConfig {
    pub max_concurrent_tasks: usize,
    pub task_timeout_secs: u64,
    pub retry_attempts: u32,
    pub retry_delay_secs: u64,
}

impl Default for SchedulerConfig {
    fn default() -> Self {
        Self {
            max_concurrent_tasks: 10,
            task_timeout_secs: 300,
            retry_attempts: 3,
            retry_delay_secs: 5,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotifierConfig {
    pub webhook_endpoints: Vec<String>,
    pub email_smtp_host: Option<String>,
    pub email_smtp_port: Option<u16>,
    pub retry_attempts: u32,
    pub retry_delay_secs: u64,
}

impl Default for NotifierConfig {
    fn default() -> Self {
        Self {
            webhook_endpoints: vec![],
            email_smtp_host: None,
            email_smtp_port: None,
            retry_attempts: 5,
            retry_delay_secs: 10,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CoreConfig {
    pub data_format: String,
    pub validation_enabled: bool,
    pub transformation_rules: Vec<TransformationRule>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransformationRule {
    pub input_field: String,
    pub output_field: String,
    pub transformation: String,
}

impl Default for CoreConfig {
    fn default() -> Self {
        Self {
            data_format: "json".to_string(),
            validation_enabled: true,
            transformation_rules: vec![],
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GatewayConfig {
    pub host: String,
    pub port: u16,
    pub request_timeout_secs: u64,
    pub rate_limit_per_minute: u32,
}

impl Default for GatewayConfig {
    fn default() -> Self {
        Self {
            host: "0.0.0.0".to_string(),
            port: 8080,
            request_timeout_secs: 30,
            rate_limit_per_minute: 1000,
        }
    }
}

pub struct ConfigManager;

impl ConfigManager {
    pub fn load() -> Result<AppConfig, SystemError> {
        let config = Self::load_from_file().unwrap_or_else(|_| {
            println!("使用默认配置");
            AppConfig {
                logger: LoggerConfig::default(),
                storage: StorageConfig::default(),
                offline_cache: OfflineCacheConfig::default(),
                device_shadow: DeviceShadowConfig::default(),
                aggregator: EdgeAggregatorConfig::default(),
                scheduler: SchedulerConfig::default(),
                notifier: NotifierConfig::default(),
                core: CoreConfig::default(),
                gateway: GatewayConfig::default(),
            }
        });
        Ok(config)
    }

    fn load_from_file() -> Result<AppConfig, SystemError> {
        let config_paths = [
            PathBuf::from("./config.yaml"),
            PathBuf::from("./config.json"),
            PathBuf::from("/etc/task-tracker/config.yaml"),
        ];

        for path in config_paths.iter() {
            if path.exists() {
                let content = std::fs::read_to_string(path)
                    .map_err(|e| SystemError::ConfigError(format!("读取配置文件失败: {}", e)))?;

                let config: AppConfig = if path.extension().and_then(|s| s.to_str()) == Some("json") {
                    serde_json::from_str(&content)
                        .map_err(|e| SystemError::ConfigError(format!("JSON解析失败: {}", e)))?
                } else {
                    serde_yaml::from_str(&content)
                        .map_err(|e| SystemError::ConfigError(format!("YAML解析失败: {}", e)))?
                };

                return Ok(config);
            }
        }

        Err(SystemError::ConfigError("未找到配置文件".to_string()))
    }

    pub fn watch_changes<F>(_callback: F) -> Result<(), SystemError>
    where
        F: Fn(AppConfig) + Send + 'static,
    {
        Ok(())
    }

    pub fn get_env_override(key: &str) -> Option<String> {
        std::env::var(key).ok()
    }
}

pub trait ConfigReload {
    fn reload(&self) -> Result<(), SystemError>;
}

impl LoggerConfig {
    pub fn level_filter(&self) -> tracing::Level {
        match self.level.to_lowercase().as_str() {
            "trace" => tracing::Level::TRACE,
            "debug" => tracing::Level::DEBUG,
            "info" => tracing::Level::INFO,
            "warn" => tracing::Level::WARN,
            "error" => tracing::Level::ERROR,
            _ => tracing::Level::INFO,
        }
    }
}

impl StorageConfig {
    pub fn cleanup_interval(&self) -> Duration {
        Duration::from_secs(self.cleanup_interval_secs)
    }
}

impl OfflineCacheConfig {
    pub fn sync_interval(&self) -> Duration {
        Duration::from_secs(self.sync_interval_secs)
    }
}

impl DeviceShadowConfig {
    pub fn sync_interval(&self) -> Duration {
        Duration::from_secs(self.sync_interval_secs)
    }

    pub fn report_interval(&self) -> Duration {
        Duration::from_secs(self.report_interval_secs)
    }
}

impl EdgeAggregatorConfig {
    pub fn window_size(&self) -> Duration {
        Duration::from_secs(self.window_size_secs)
    }
}

impl SchedulerConfig {
    pub fn task_timeout(&self) -> Duration {
        Duration::from_secs(self.task_timeout_secs)
    }

    pub fn retry_delay(&self) -> Duration {
        Duration::from_secs(self.retry_delay_secs)
    }
}

impl NotifierConfig {
    pub fn retry_delay(&self) -> Duration {
        Duration::from_secs(self.retry_delay_secs)
    }
}

impl GatewayConfig {
    pub fn request_timeout(&self) -> Duration {
        Duration::from_secs(self.request_timeout_secs)
    }

    pub fn address(&self) -> String {
        format!("{}:{}", self.host, self.port)
    }
}
