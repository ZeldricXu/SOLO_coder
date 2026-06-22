use crate::error::AppError;
use config::{Config, Environment, File};
use serde::Deserialize;
use std::path::Path;

#[derive(Debug, Clone, Deserialize)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
    pub workers: usize,
    pub request_timeout_secs: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DatabaseConfig {
    pub url: String,
    pub max_connections: u32,
    pub min_connections: u32,
    pub acquire_timeout_secs: u64,
    pub idle_timeout_secs: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct RedisConfig {
    pub url: String,
    pub pool_size: u32,
    pub retry_times: u32,
    pub retry_delay_ms: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct InferenceConfig {
    pub default_timeout_ms: u64,
    pub max_batch_size: u32,
    pub max_queue_size: u32,
    pub gpu_devices: Vec<String>,
    pub warmup_enabled: bool,
    pub model_cache_dir: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SecurityConfig {
    pub jwt_secret: String,
    pub jwt_expiration_secs: u64,
    pub api_key_header: String,
    pub rate_limit_enabled: bool,
    pub encryption_key: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ObservabilityConfig {
    pub tracing_enabled: bool,
    pub metrics_enabled: bool,
    pub otlp_endpoint: Option<String>,
    pub log_level: String,
    pub log_format: String,
    pub metrics_port: u16,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SchedulerConfig {
    pub enabled: bool,
    pub heartbeat_interval_secs: u64,
    pub model_load_timeout_secs: u64,
    pub auto_scale_enabled: bool,
    pub scale_up_threshold: f32,
    pub scale_down_threshold: f32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct RoutingConfig {
    pub default_strategy: String,
    pub sticky_session_enabled: bool,
    pub fallback_enabled: bool,
    pub health_check_interval_secs: u64,
    pub circuit_breaker_enabled: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ExperimentConfig {
    pub enabled: bool,
    pub min_traffic_percent: u8,
    pub max_groups: u8,
    pub assignment_cache_ttl_secs: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct StorageConfig {
    pub backend: String,
    pub s3_bucket: Option<String>,
    pub s3_region: Option<String>,
    pub s3_endpoint: Option<String>,
    pub local_path: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct RolloutConfig {
    pub enabled: bool,
    pub initial_percent: u8,
    pub step_percent: u8,
    pub window_secs: u64,
    pub max_error_rate_ratio: f64,
    pub max_p99_latency_ratio: f64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DynamicSchedulerConfig {
    pub enabled: bool,
    pub high_watermark_percent: f64,
    pub low_watermark_percent: f64,
    pub protection_period_secs: u64,
    pub warmup_iterations: u32,
    pub check_interval_secs: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AppConfig {
    pub environment: String,
    pub server: ServerConfig,
    pub database: DatabaseConfig,
    pub redis: RedisConfig,
    pub inference: InferenceConfig,
    pub security: SecurityConfig,
    pub observability: ObservabilityConfig,
    pub scheduler: SchedulerConfig,
    pub routing: RoutingConfig,
    pub experiment: ExperimentConfig,
    pub storage: StorageConfig,
    pub rollout: RolloutConfig,
    pub dynamic_scheduler: DynamicSchedulerConfig,
}

impl AppConfig {
    pub fn load() -> Result<Self, AppError> {
        let config_path = std::env::var("APP_CONFIG_PATH").unwrap_or_else(|_| "config".to_string());
        let env = std::env::var("APP_ENV").unwrap_or_else(|_| "development".to_string());

        let mut builder = Config::builder()
            .add_source(File::from(Path::new(&config_path).join("default")).required(false))
            .add_source(File::from(Path::new(&config_path).join(&env)).required(false))
            .add_source(File::from(Path::new(&config_path).join("local")).required(false))
            .add_source(
                Environment::with_prefix("APP")
                    .try_parsing(true)
                    .separator("__")
                    .list_separator(",")
                    .with_list_parse_key("inference.gpu_devices"),
            );

        if let Ok(extra_path) = std::env::var("APP_EXTRA_CONFIG") {
            builder = builder.add_source(File::from(Path::new(&extra_path)).required(false));
        }

        let config = builder.build().map_err(AppError::from)?;
        let app_config: AppConfig = config.try_deserialize().map_err(AppError::from)?;

        Ok(app_config)
    }

    pub fn is_development(&self) -> bool {
        self.environment == "development" || self.environment == "dev"
    }

    pub fn is_production(&self) -> bool {
        self.environment == "production" || self.environment == "prod"
    }

    pub fn is_staging(&self) -> bool {
        self.environment == "staging" || self.environment == "stage"
    }

    pub fn server_addr(&self) -> String {
        format!("{}:{}", self.server.host, self.server.port)
    }
}

impl Default for AppConfig {
    fn default() -> Self {
        AppConfig {
            environment: "development".to_string(),
            server: ServerConfig {
                host: "127.0.0.1".to_string(),
                port: 8080,
                workers: 4,
                request_timeout_secs: 60,
            },
            database: DatabaseConfig {
                url: "postgres://postgres:postgres@localhost:5432/df1".to_string(),
                max_connections: 20,
                min_connections: 5,
                acquire_timeout_secs: 30,
                idle_timeout_secs: 300,
            },
            redis: RedisConfig {
                url: "redis://localhost:6379".to_string(),
                pool_size: 16,
                retry_times: 3,
                retry_delay_ms: 100,
            },
            inference: InferenceConfig {
                default_timeout_ms: 30000,
                max_batch_size: 32,
                max_queue_size: 1024,
                gpu_devices: vec!["0".to_string()],
                warmup_enabled: true,
                model_cache_dir: "./data/models".to_string(),
            },
            security: SecurityConfig {
                jwt_secret: "development-secret-change-in-production".to_string(),
                jwt_expiration_secs: 3600,
                api_key_header: "X-API-Key".to_string(),
                rate_limit_enabled: true,
                encryption_key: "0123456789abcdef0123456789abcdef".to_string(),
            },
            observability: ObservabilityConfig {
                tracing_enabled: true,
                metrics_enabled: true,
                otlp_endpoint: None,
                log_level: "info".to_string(),
                log_format: "json".to_string(),
                metrics_port: 9090,
            },
            scheduler: SchedulerConfig {
                enabled: true,
                heartbeat_interval_secs: 5,
                model_load_timeout_secs: 300,
                auto_scale_enabled: true,
                scale_up_threshold: 0.8,
                scale_down_threshold: 0.2,
            },
            routing: RoutingConfig {
                default_strategy: "round_robin".to_string(),
                sticky_session_enabled: false,
                fallback_enabled: true,
                health_check_interval_secs: 10,
                circuit_breaker_enabled: true,
            },
            experiment: ExperimentConfig {
                enabled: true,
                min_traffic_percent: 1,
                max_groups: 10,
                assignment_cache_ttl_secs: 3600,
            },
            storage: StorageConfig {
                backend: "local".to_string(),
                s3_bucket: None,
                s3_region: None,
                s3_endpoint: None,
                local_path: Some("./data/storage".to_string()),
            },
            rollout: RolloutConfig {
                enabled: true,
                initial_percent: 5,
                step_percent: 10,
                window_secs: 3600,
                max_error_rate_ratio: 1.2,
                max_p99_latency_ratio: 1.5,
            },
            dynamic_scheduler: DynamicSchedulerConfig {
                enabled: true,
                high_watermark_percent: 90.0,
                low_watermark_percent: 70.0,
                protection_period_secs: 600,
                warmup_iterations: 5,
                check_interval_secs: 15,
            },
        }
    }
}
