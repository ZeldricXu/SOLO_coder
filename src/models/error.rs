use thiserror::Error;
use serde::{Deserialize, Serialize};

#[derive(Debug, Error)]
pub enum StreamSQLError {
    #[error("Validation error: {0}")]
    Validation(String),

    #[error("Configuration error: {0}")]
    Config(String),

    #[error("Timeout error: {0}")]
    Timeout(String),

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    #[error("Serialization error: {0}")]
    Serialization(String),

    #[error("Parse error: {0}")]
    Parse(String),

    #[error("CDC error: {0}")]
    Cdc(String),

    #[error("Lineage error: {0}")]
    Lineage(String),

    #[error("Quality check error: {0}")]
    Quality(String),

    #[error("Metadata error: {0}")]
    Metadata(String),

    #[error("SQL error: {0}")]
    Sql(String),

    #[error("Vector index error: {0}")]
    Vector(String),

    #[error("Lifecycle error: {0}")]
    Lifecycle(String),

    #[error("Compression error: {0}")]
    Compression(String),

    #[error("Internal error: {0}")]
    Internal(String),

    #[error("Not found: {0}")]
    NotFound(String),
}

impl From<serde_json::Error> for StreamSQLError {
    fn from(err: serde_json::Error) -> Self {
        StreamSQLError::Serialization(err.to_string())
    }
}

impl From<chrono::ParseError> for StreamSQLError {
    fn from(err: chrono::ParseError) -> Self {
        StreamSQLError::Parse(err.to_string())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorDetail {
    pub field: String,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ValidationResponse {
    pub code: u16,
    pub message: String,
    pub errors: Vec<ErrorDetail>,
}

impl ValidationResponse {
    pub fn new(message: impl Into<String>, errors: Vec<ErrorDetail>) -> Self {
        Self {
            code: 422,
            message: message.into(),
            errors,
        }
    }

    pub fn single(field: impl Into<String>, message: impl Into<String>) -> Self {
        Self::new(
            "Validation failed",
            vec![ErrorDetail {
                field: field.into(),
                message: message.into(),
            }],
        )
    }
}

pub type Result<T> = std::result::Result<T, StreamSQLError>;
