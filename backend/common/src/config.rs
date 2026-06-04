use crate::error::AppResult;
use serde::Deserialize;

#[derive(Debug, Deserialize, Clone)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub database: DatabaseConfig,
    pub redis: RedisConfig,
    pub mongodb: MongodbConfig,
    pub auth: AuthConfig,
    pub media: MediaConfig,
}

#[derive(Debug, Deserialize, Clone)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
}

#[derive(Debug, Deserialize, Clone)]
pub struct DatabaseConfig {
    pub url: String,
    pub max_connections: u32,
}

#[derive(Debug, Deserialize, Clone)]
pub struct RedisConfig {
    pub url: String,
    pub max_connections: u32,
}

#[derive(Debug, Deserialize, Clone)]
pub struct MongodbConfig {
    pub uri: String,
    pub database: String,
}

#[derive(Debug, Deserialize, Clone)]
pub struct AuthConfig {
    pub jwt_secret: String,
    pub jwt_expiry_hours: i64,
}

#[derive(Debug, Deserialize, Clone)]
pub struct MediaConfig {
    pub storage_path: String,
    pub max_file_size: u64,
    pub allowed_image_types: Vec<String>,
    pub allowed_video_types: Vec<String>,
}

impl AppConfig {
    pub fn load() -> AppResult<Self> {
        let run_mode = std::env::var("RUN_MODE").unwrap_or_else(|_| "development".to_string());

        let config = config::Config::builder()
            .add_source(config::File::with_name(&format!("config/{}", run_mode)))
            .add_source(config::Environment::default().separator("__"))
            .build()?;

        let app_config: AppConfig = config.try_deserialize()?;
        Ok(app_config)
    }
}
