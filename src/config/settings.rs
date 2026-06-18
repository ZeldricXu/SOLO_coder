use std::time::Duration;

use config::{Config, ConfigError, Environment, File};
use serde::Deserialize;

#[derive(Debug, Deserialize, Clone)]
pub struct Settings {
    pub server: ServerConfig,
    pub database: DatabaseConfig,
    pub redis: RedisConfig,
    pub minio: MinioConfig,
    pub oauth: OAuthConfig,
    pub llm: LLMConfig,
    pub email: EmailConfig,
    pub slack: SlackConfig,
    pub dingtalk: DingtalkConfig,
    pub session: SessionConfig,
    pub app: AppConfig,
}

#[derive(Debug, Deserialize, Clone)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
    pub base_url: String,
}

#[derive(Debug, Deserialize, Clone)]
pub struct DatabaseConfig {
    pub url: String,
    #[serde(default)]
    pub host: Option<String>,
    #[serde(default)]
    pub port: Option<u16>,
    #[serde(default)]
    pub user: Option<String>,
    #[serde(default)]
    pub password: Option<String>,
    #[serde(default)]
    pub name: Option<String>,
    pub max_connections: u32,
    pub min_connections: u32,
    pub acquire_timeout_secs: u64,
    pub idle_timeout_secs: u64,
}

#[derive(Debug, Deserialize, Clone)]
pub struct RedisConfig {
    pub url: String,
    pub max_connections: u32,
    pub min_connections: u32,
}

#[derive(Debug, Deserialize, Clone)]
pub struct MinioConfig {
    pub endpoint: String,
    pub access_key: String,
    pub secret_key: String,
    pub bucket_name: String,
    pub region: String,
    pub use_ssl: bool,
}

#[derive(Debug, Deserialize, Clone)]
pub struct OAuthConfig {
    pub github: OAuthProviderConfig,
    pub gitlab: OAuthProviderConfig,
    pub gitee: OAuthProviderConfig,
}

#[derive(Debug, Deserialize, Clone)]
pub struct OAuthProviderConfig {
    pub client_id: String,
    pub client_secret: String,
    pub auth_url: String,
    pub token_url: String,
    pub api_base_url: String,
    pub redirect_url: String,
}

#[derive(Debug, Deserialize, Clone)]
pub struct LLMConfig {
    pub provider: String,
    pub api_key: String,
    pub api_base_url: String,
    pub model: String,
    pub max_tokens: u32,
    pub temperature: f32,
    pub timeout_secs: u64,
}

#[derive(Debug, Deserialize, Clone)]
pub struct EmailConfig {
    pub smtp_host: String,
    pub smtp_port: u16,
    pub smtp_username: String,
    pub smtp_password: String,
    pub from_address: String,
    pub from_name: String,
    pub use_tls: bool,
}

#[derive(Debug, Deserialize, Clone)]
pub struct SessionConfig {
    pub secret_key: String,
    pub ttl_secs: u64,
    pub cookie_name: String,
    pub cookie_secure: bool,
    pub cookie_http_only: bool,
}

#[derive(Debug, Deserialize, Clone)]
pub struct AppConfig {
    pub name: String,
    pub log_level: String,
    pub environment: String,
    pub webhook_signature_header: String,
    pub daily_digest_cron: String,
}

impl Settings {
    pub fn new() -> Result<Self, ConfigError> {
        let run_mode = std::env::var("APP_ENV")
            .or_else(|_| std::env::var("RUN_MODE"))
            .unwrap_or_else(|_| "development".into());

        let mut builder = Config::builder()
            .add_source(File::with_name("config/default").required(false))
            .add_source(File::with_name(&format!("config/{}", run_mode)).required(false))
            .add_source(File::with_name("config/local").required(false))
            .add_source(Environment::with_prefix("APP").separator("__"));

        if let Ok(db_url) = std::env::var("DATABASE_URL") {
            builder = builder.set_override("database.url", db_url)?;
        }
        if let Ok(redis_url) = std::env::var("REDIS_URL") {
            builder = builder.set_override("redis.url", redis_url)?;
        }
        if let Ok(minio_endpoint) = std::env::var("MINIO_ENDPOINT") {
            builder = builder.set_override("minio.endpoint", minio_endpoint)?;
        }
        if let Ok(minio_access) = std::env::var("MINIO_ACCESS_KEY") {
            builder = builder.set_override("minio.access_key", minio_access)?;
        }
        if let Ok(minio_secret) = std::env::var("MINIO_SECRET_KEY") {
            builder = builder.set_override("minio.secret_key", minio_secret)?;
        }
        if let Ok(minio_bucket) = std::env::var("MINIO_BUCKET") {
            builder = builder.set_override("minio.bucket_name", minio_bucket)?;
        }
        if let Ok(llm_key) = std::env::var("LLM_API_KEY") {
            builder = builder.set_override("llm.api_key", llm_key)?;
        }

        let config = builder.build()?;
        let mut settings: Settings = config.try_deserialize()?;

        if let Some(github_id) = std::env::var("GITHUB_CLIENT_ID").ok().filter(|v| !v.is_empty()) {
            settings.oauth.github.client_id = github_id;
        }
        if let Some(github_secret) = std::env::var("GITHUB_CLIENT_SECRET").ok().filter(|v| !v.is_empty()) {
            settings.oauth.github.client_secret = github_secret;
        }
        if let Some(gitlab_id) = std::env::var("GITLAB_CLIENT_ID").ok().filter(|v| !v.is_empty()) {
            settings.oauth.gitlab.client_id = gitlab_id;
        }
        if let Some(gitlab_secret) = std::env::var("GITLAB_CLIENT_SECRET").ok().filter(|v| !v.is_empty()) {
            settings.oauth.gitlab.client_secret = gitlab_secret;
        }
        if let Some(gitee_id) = std::env::var("GITEE_CLIENT_ID").ok().filter(|v| !v.is_empty()) {
            settings.oauth.gitee.client_id = gitee_id;
        }
        if let Some(gitee_secret) = std::env::var("GITEE_CLIENT_SECRET").ok().filter(|v| !v.is_empty()) {
            settings.oauth.gitee.client_secret = gitee_secret;
        }

        Ok(settings)
    }

    pub fn database_url(&self) -> &str {
        &self.database.url
    }

    pub fn redis_url(&self) -> &str {
        &self.redis.url
    }

    pub fn server_addr(&self) -> String {
        format!("{}:{}", self.server.host, self.server.port)
    }

    pub fn base_url(&self) -> &str {
        &self.server.base_url
    }

    pub fn session_ttl(&self) -> Duration {
        Duration::from_secs(self.session.ttl_secs)
    }

    pub fn is_development(&self) -> bool {
        self.app.environment == "development" || self.app.environment == "dev"
    }

    pub fn is_production(&self) -> bool {
        self.app.environment == "production" || self.app.environment == "prod"
    }

    pub fn is_staging(&self) -> bool {
        self.app.environment == "staging" || self.app.environment == "stage"
    }

    pub fn is_testing(&self) -> bool {
        self.app.environment == "testing" || self.app.environment == "test"
    }

    pub fn environment(&self) -> &str {
        &self.app.environment
    }
}

#[derive(Debug, Deserialize, Clone)]
pub struct SlackConfig {
    pub webhook_url: String,
}

#[derive(Debug, Deserialize, Clone)]
pub struct DingtalkConfig {
    pub webhook_url: String,
    pub secret: String,
}

pub mod config {
    pub use super::Settings;
}
