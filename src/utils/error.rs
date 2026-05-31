use thiserror::Error;

#[derive(Error, Debug)]
pub enum GatewayError {
    #[error("Validation error: {0}")]
    Validation(String),

    #[error("Timeout error: {0}")]
    Timeout(String),

    #[error("Resource not found: {0}")]
    NotFound(String),

    #[error("Configuration error: {0}")]
    Config(String),

    #[error("Provider error: {0}")]
    Provider(String),

    #[error("Database error: {0}")]
    Database(#[from] sqlx::Error),

    #[error("Cache error: {0}")]
    Cache(String),

    #[error("Pipeline error: {0}")]
    Pipeline(String),

    #[error("Scheduling error: {0}")]
    Scheduling(String),

    #[error("Rollback error: {0}")]
    Rollback(String),

    #[error("Internal error: {0}")]
    Internal(String),

    #[error("External service error: {0}")]
    ExternalService(String),
}

pub type Result<T> = std::result::Result<T, GatewayError>;

impl GatewayError {
    pub fn status_code(&self) -> u16 {
        match self {
            GatewayError::Validation(_) => 422,
            GatewayError::Timeout(_) => 504,
            GatewayError::NotFound(_) => 404,
            GatewayError::Config(_) => 400,
            GatewayError::Provider(_) => 502,
            GatewayError::Database(_) => 500,
            GatewayError::Cache(_) => 500,
            GatewayError::Pipeline(_) => 500,
            GatewayError::Scheduling(_) => 503,
            GatewayError::Rollback(_) => 500,
            GatewayError::Internal(_) => 500,
            GatewayError::ExternalService(_) => 502,
        }
    }

    pub fn code(&self) -> &'static str {
        match self {
            GatewayError::Validation(_) => "VALIDATION_ERROR",
            GatewayError::Timeout(_) => "TIMEOUT_ERROR",
            GatewayError::NotFound(_) => "NOT_FOUND",
            GatewayError::Config(_) => "CONFIG_ERROR",
            GatewayError::Provider(_) => "PROVIDER_ERROR",
            GatewayError::Database(_) => "DATABASE_ERROR",
            GatewayError::Cache(_) => "CACHE_ERROR",
            GatewayError::Pipeline(_) => "PIPELINE_ERROR",
            GatewayError::Scheduling(_) => "SCHEDULING_ERROR",
            GatewayError::Rollback(_) => "ROLLBACK_ERROR",
            GatewayError::Internal(_) => "INTERNAL_ERROR",
            GatewayError::ExternalService(_) => "EXTERNAL_SERVICE_ERROR",
        }
    }
}

impl From<anyhow::Error> for GatewayError {
    fn from(e: anyhow::Error) -> Self {
        GatewayError::Internal(e.to_string())
    }
}

impl From<redis::RedisError> for GatewayError {
    fn from(e: redis::RedisError) -> Self {
        GatewayError::Cache(e.to_string())
    }
}

impl From<reqwest::Error> for GatewayError {
    fn from(e: reqwest::Error) -> Self {
        GatewayError::ExternalService(e.to_string())
    }
}
