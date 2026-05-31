use crate::types::{AppConfig, AppError, AppResult};
use std::collections::HashMap;
use std::sync::Arc;
use dashmap::DashMap;
use once_cell::sync::Lazy;

static CONFIG_CACHE: Lazy<DashMap<String, AppConfig>> = Lazy::new(DashMap::new);

pub struct ConfigManager {
    environment: String,
    config_dir: String,
    defaults: HashMap<String, serde_json::Value>,
}

impl ConfigManager {
    pub fn new() -> Self {
        let environment = std::env::var("RUST_ENV")
            .unwrap_or_else(|_| "development".to_string())
            .to_lowercase();

        let config_dir = std::env::var("CONFIG_DIR")
            .unwrap_or_else(|_| "./config".to_string());

        Self {
            environment,
            config_dir,
            defaults: Self::build_defaults(),
        }
    }

    fn build_defaults() -> HashMap<String, serde_json::Value> {
        let mut defaults = HashMap::new();

        defaults.insert("server.host".to_string(), serde_json::json!("127.0.0.1"));
        defaults.insert("server.port".to_string(), serde_json::json!(8080));
        defaults.insert("server.shutdown_timeout".to_string(), serde_json::json!(30));

        defaults.insert("database.url".to_string(), serde_json::json!("postgres://localhost:5432/enterprise"));
        defaults.insert("database.pool_size".to_string(), serde_json::json!(10));
        defaults.insert("database.connect_timeout".to_string(), serde_json::json!(10));
        defaults.insert("database.idle_timeout".to_string(), serde_json::json!(300));
        defaults.insert("database.max_lifetime".to_string(), serde_json::json!(1800));
        defaults.insert("database.acquire_timeout".to_string(), serde_json::json!(30));

        defaults.insert("redis.url".to_string(), serde_json::json!("redis://localhost:6379/0"));
        defaults.insert("redis.pool_size".to_string(), serde_json::json!(10));
        defaults.insert("redis.connect_timeout".to_string(), serde_json::json!(10));
        defaults.insert("redis.idle_timeout".to_string(), serde_json::json!(300));
        defaults.insert("redis.max_lifetime".to_string(), serde_json::json!(1800));

        defaults.insert("logging.dir".to_string(), serde_json::json!("./logs"));
        defaults.insert("logging.level".to_string(), serde_json::json!("info"));
        defaults.insert("logging.format".to_string(), serde_json::json!("json"));
        defaults.insert("logging.rotation".to_string(), serde_json::json!("daily"));
        defaults.insert("logging.retention_days".to_string(), serde_json::json!(30));
        defaults.insert("logging.compression".to_string(), serde_json::json!(false));
        defaults.insert("logging.ansi_colors".to_string(), serde_json::json!(true));

        defaults.insert("storage.backend".to_string(), serde_json::json!("local"));
        defaults.insert("storage.local_path".to_string(), serde_json::json!("./data/storage"));
        defaults.insert("storage.s3_bucket".to_string(), serde_json::json!(""));
        defaults.insert("storage.s3_region".to_string(), serde_json::json!("us-east-1"));
        defaults.insert("storage.s3_access_key".to_string(), serde_json::json!(""));
        defaults.insert("storage.s3_secret_key".to_string(), serde_json::json!(""));
        defaults.insert("storage.s3_endpoint".to_string(), serde_json::json!(""));

        defaults.insert("cdc.enabled".to_string(), serde_json::json!(false));
        defaults.insert("cdc.source_type".to_string(), serde_json::json!("postgres_wal"));
        defaults.insert("cdc.connection_string".to_string(), serde_json::json!(""));
        defaults.insert("cdc.server_id".to_string(), serde_json::json!(1));
        defaults.insert("cdc.slot_name".to_string(), serde_json::json!("enterprise_slot"));
        defaults.insert("cdc.include_tables".to_string(), serde_json::json!([] as [String; 0]));
        defaults.insert("cdc.exclude_tables".to_string(), serde_json::json!([] as [String; 0]));
        defaults.insert("cdc.output_kafka_brokers".to_string(), serde_json::json!(""));
        defaults.insert("cdc.output_topic".to_string(), serde_json::json!("cdc_events"));
        defaults.insert("cdc.batch_size".to_string(), serde_json::json!(100));
        defaults.insert("cdc.polling_interval_ms".to_string(), serde_json::json!(100));

        defaults.insert("data_quality.enabled".to_string(), serde_json::json!(true));
        defaults.insert("data_quality.schedule_pool_size".to_string(), serde_json::json!(4));
        defaults.insert("data_quality.alert_enabled".to_string(), serde_json::json!(true));
        defaults.insert("data_quality.alert_channels".to_string(), serde_json::json!(["email", "slack"]));
        defaults.insert("data_quality.anomaly_storage_enabled".to_string(), serde_json::json!(true));

        defaults.insert("metadata_crawler.enabled".to_string(), serde_json::json!(true));
        defaults.insert("metadata_crawler.schedule_pool_size".to_string(), serde_json::json!(4));
        defaults.insert("metadata_crawler.sample_data_count".to_string(), serde_json::json!(100));
        defaults.insert("metadata_crawler.histogram_buckets".to_string(), serde_json::json!(10));
        defaults.insert("metadata_crawler.statistics_enabled".to_string(), serde_json::json!(true));

        defaults.insert("lineage.enabled".to_string(), serde_json::json!(true));
        defaults.insert("lineage.sql_dialect".to_string(), serde_json::json!("postgres"));
        defaults.insert("lineage.store_parsed_queries".to_string(), serde_json::json!(true));
        defaults.insert("lineage.build_dag".to_string(), serde_json::json!(true));

        defaults.insert("notification.enabled".to_string(), serde_json::json!(true));
        defaults.insert("notification.default_channel".to_string(), serde_json::json!("email"));
        defaults.insert("notification.rate_limit_per_minute".to_string(), serde_json::json!(60));
        defaults.insert("notification.retry_count".to_string(), serde_json::json!(3));
        defaults.insert("notification.retry_interval_ms".to_string(), serde_json::json!(1000));
        defaults.insert("notification.smtp_host".to_string(), serde_json::json!(""));
        defaults.insert("notification.smtp_port".to_string(), serde_json::json!(587));
        defaults.insert("notification.smtp_username".to_string(), serde_json::json!(""));
        defaults.insert("notification.smtp_password".to_string(), serde_json::json!(""));
        defaults.insert("notification.slack_webhook".to_string(), serde_json::json!(""));
        defaults.insert("notification.dingtalk_webhook".to_string(), serde_json::json!(""));
        defaults.insert("notification.wechat_webhook".to_string(), serde_json::json!(""));
        defaults.insert("notification.webhook_timeout_ms".to_string(), serde_json::json!(5000));

        defaults
    }

    pub fn load(&self) -> AppResult<AppConfig> {
        let cache_key = format!("{}:{}", self.environment, self.config_dir);

        if let Some(cached) = CONFIG_CACHE.get(&cache_key) {
            return Ok(cached.clone());
        }

        let _ = dotenvy::dotenv();

        let mut builder = config::Config::builder()
            .add_source(config::File::from(format!("{}/default.toml", self.config_dir)).required(false))
            .add_source(config::File::from(format!("{}/{}.toml", self.config_dir, self.environment)).required(false));

        if let Ok(local_config) = std::env::var("LOCAL_CONFIG") {
            builder = builder.add_source(config::File::from(local_config));
        }

        builder = builder
            .add_source(config::Environment::with_prefix("APP").separator("__"))
            .add_source(config::Environment::with_prefix("ENTERPRISE_MW").separator("__"));

        let config = builder
            .build()
            .map_err(|e| AppError::ConfigError(format!("加载配置失败: {}", e)))?;

        let app_config: AppConfig = config
            .try_deserialize()
            .map_err(|e| AppError::ConfigError(format!("解析配置失败: {}", e)))?;

        self.validate(&app_config)?;

        CONFIG_CACHE.insert(cache_key, app_config.clone());

        Ok(app_config)
    }

    pub fn validate(&self, config: &AppConfig) -> AppResult<()> {
        let mut errors = Vec::new();

        if config.server.port < 1024 {
            errors.push("server.port 不能小于1024".to_string());
        }

        if config.server.port > 65535 {
            errors.push("server.port 不能大于65535".to_string());
        }

        if config.database.pool_size < 1 {
            errors.push("database.pool_size 不能小于1".to_string());
        }

        if config.database.pool_size > 100 {
            errors.push("database.pool_size 不能大于100".to_string());
        }

        if config.cdc.enabled {
            if config.cdc.connection_string.is_empty() {
                errors.push("cdc.enabled 为 true 时，cdc.connection_string 不能为空".to_string());
            }

            if config.cdc.source_type.is_empty() {
                errors.push("cdc.enabled 为 true 时，cdc.source_type 不能为空".to_string());
            }

            if config.cdc.batch_size < 1 {
                errors.push("cdc.batch_size 不能小于1".to_string());
            }

            if config.cdc.batch_size > 10000 {
                errors.push("cdc.batch_size 不能大于10000".to_string());
            }
        }

        if config.data_quality.enabled && config.data_quality.schedule_pool_size < 1 {
            errors.push("data_quality.schedule_pool_size 不能小于1".to_string());
        }

        if config.metadata_crawler.enabled {
            if config.metadata_crawler.schedule_pool_size < 1 {
                errors.push("metadata_crawler.schedule_pool_size 不能小于1".to_string());
            }

            if config.metadata_crawler.sample_data_count < 1 {
                errors.push("metadata_crawler.sample_data_count 不能小于1".to_string());
            }

            if config.metadata_crawler.histogram_buckets < 2 {
                errors.push("metadata_crawler.histogram_buckets 不能小于2".to_string());
            }
        }

        if config.lineage.enabled && config.lineage.sql_dialect.is_empty() {
            errors.push("lineage.sql_dialect 不能为空".to_string());
        }

        if config.notification.enabled && config.notification.rate_limit_per_minute < 1 {
            errors.push("notification.rate_limit_per_minute 不能小于1".to_string());
        }

        if !errors.is_empty() {
            return Err(AppError::ConfigError(format!(
                "配置验证失败: {}",
                errors.join(", ")
            )));
        }

        Ok(())
    }

    pub fn get_default(&self, key: &str) -> Option<&serde_json::Value> {
        self.defaults.get(key)
    }

    pub fn get_all_defaults(&self) -> &HashMap<String, serde_json::Value> {
        &self.defaults
    }

    pub fn environment(&self) -> &str {
        &self.environment
    }

    pub fn is_development(&self) -> bool {
        self.environment == "development"
    }

    pub fn is_staging(&self) -> bool {
        self.environment == "staging"
    }

    pub fn is_production(&self) -> bool {
        self.environment == "production"
    }

    pub fn diff_environments(&self, env1: &str, env2: &str) -> AppResult<Vec<String>> {
        let config1 = self.load_for_env(env1)?;
        let config2 = self.load_for_env(env2)?;

        let json1 = serde_json::to_value(&config1).map_err(|e| AppError::ConfigError(format!("序列化失败: {}", e)))?;
        let json2 = serde_json::to_value(&config2).map_err(|e| AppError::ConfigError(format!("序列化失败: {}", e)))?;

        let mut diffs = Vec::new();
        self.compare_json("", &json1, &json2, &mut diffs);

        Ok(diffs)
    }

    fn load_for_env(&self, env: &str) -> AppResult<AppConfig> {
        let mut builder = config::Config::builder()
            .add_source(config::File::from(format!("{}/default.toml", self.config_dir)).required(false))
            .add_source(config::File::from(format!("{}/{}.toml", self.config_dir, env)).required(false));

        let config = builder
            .build()
            .map_err(|e| AppError::ConfigError(format!("加载环境 {} 配置失败: {}", env, e)))?;

        let app_config: AppConfig = config
            .try_deserialize()
            .map_err(|e| AppError::ConfigError(format!("解析环境 {} 配置失败: {}", env, e)))?;

        Ok(app_config)
    }

    fn compare_json(&self, path: &str, v1: &serde_json::Value, v2: &serde_json::Value, diffs: &mut Vec<String>) {
        match (v1, v2) {
            (serde_json::Value::Object(m1), serde_json::Value::Object(m2)) => {
                let keys: std::collections::HashSet<_> = m1.keys().chain(m2.keys()).collect();
                for key in keys {
                    let new_path = if path.is_empty() {
                        key.clone()
                    } else {
                        format!("{}.{}", path, key)
                    };
                    match (m1.get(key), m2.get(key)) {
                        (Some(val1), Some(val2)) => self.compare_json(&new_path, val1, val2, diffs),
                        (Some(_), None) => diffs.push(format!("{}: 环境1有值，环境2无值", new_path)),
                        (None, Some(_)) => diffs.push(format!("{}: 环境1无值，环境2有值", new_path)),
                    }
                }
            }
            _ => {
                if v1 != v2 {
                    diffs.push(format!("{}: {} != {}", path, v1, v2));
                }
            }
        }
    }

    pub fn clear_cache(&self) {
        CONFIG_CACHE.clear();
    }
}

impl Default for ConfigManager {
    fn default() -> Self {
        Self::new()
    }
}

pub fn load_config() -> AppResult<AppConfig> {
    let manager = ConfigManager::new();
    manager.load()
}

pub async fn create_config_version(
    namespace: &str,
    parameters: HashMap<String, serde_json::Value>,
) -> AppResult<crate::types::ConfigDefinition> {
    use crate::types::{generate_id, now_utc};

    Ok(crate::types::ConfigDefinition {
        config_id: generate_id("cfg"),
        namespace: namespace.to_string(),
        version: 1,
        parameters,
        enabled: true,
        applied_at: now_utc(),
    })
}
