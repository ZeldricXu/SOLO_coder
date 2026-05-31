use thiserror::Error;
use std::io;

#[derive(Error, Debug)]
pub enum PlatformError {
    #[error("validation error: {0}")]
    Validation(String),

    #[error("not found: {0}")]
    NotFound(String),

    #[error("conflict: {0}")]
    Conflict(String),

    #[error("timeout: {0}")]
    Timeout(String),

    #[error("external service error: {0}")]
    ExternalService(String),

    #[error("database error: {0}")]
    Database(String),

    #[error("configuration error: {0}")]
    Configuration(String),

    #[error("IO error: {0}")]
    Io(#[from] io::Error),

    #[error("serialization error: {0}")]
    Serialization(#[from] serde_json::Error),

    #[error("internal error: {0}")]
    Internal(String),
}

impl PlatformError {
    pub fn code(&self) -> u16 {
        match self {
            PlatformError::Validation(_) => 400,
            PlatformError::NotFound(_) => 404,
            PlatformError::Conflict(_) => 409,
            PlatformError::Timeout(_) => 504,
            PlatformError::ExternalService(_) => 502,
            PlatformError::Database(_) => 500,
            PlatformError::Configuration(_) => 400,
            PlatformError::Io(_) => 500,
            PlatformError::Serialization(_) => 400,
            PlatformError::Internal(_) => 500,
        }
    }

    pub fn is_retryable(&self) -> bool {
        match self {
            PlatformError::Timeout(_) |
            PlatformError::ExternalService(_) |
            PlatformError::Database(_) => true,
            _ => false,
        }
    }
}

impl From<sqlx::Error> for PlatformError {
    fn from(err: sqlx::Error) -> Self {
        match err {
            sqlx::Error::RowNotFound => PlatformError::NotFound("record not found".to_string()),
            _ => PlatformError::Database(err.to_string()),
        }
    }
}

impl From<redis::RedisError> for PlatformError {
    fn from(err: redis::RedisError) -> Self {
        PlatformError::Database(err.to_string())
    }
}

pub type Result<T> = std::result::Result<T, PlatformError>;
