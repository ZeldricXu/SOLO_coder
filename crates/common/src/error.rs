use thiserror::Error;
use serde::{Deserialize, Serialize};

#[derive(Error, Debug)]
pub enum CdnError {
    #[error("Database error: {0}")]
    DatabaseError(#[from] sqlx::Error),

    #[error("Redis error: {0}")]
    RedisError(#[from] redis::RedisError),

    #[error("Node not found: {0}")]
    NodeNotFound(String),

    #[error("Node registration failed: {0}")]
    RegistrationFailed(String),

    #[error("Heartbeat failed: {0}")]
    HeartbeatFailed(String),

    #[error("No available nodes")]
    NoAvailableNodes,

    #[error("Invalid configuration: {0}")]
    InvalidConfig(String),

    #[error("Cache error: {0}")]
    CacheError(String),

    #[error("Certificate error: {0}")]
    CertificateError(String),

    #[error("ACME error: {0}")]
    AcmeError(String),

    #[error("HTTP error: {0}")]
    HttpError(#[from] reqwest::Error),

    #[error("Serialization error: {0}")]
    SerializationError(#[from] serde_json::Error),

    #[error("IO error: {0}")]
    IoError(#[from] std::io::Error),

    #[error("Address parse error: {0}")]
    AddrParseError(#[from] std::net::AddrParseError),

    #[error("Encryption error: {0}")]
    EncryptionError(String),

    #[error("GeoIP error: {0}")]
    GeoIpError(String),

    #[error("Operation timeout")]
    Timeout,

    #[error("Internal error: {0}")]
    InternalError(String),
}

pub type CdnResult<T> = Result<T, CdnError>;

#[derive(Debug, Serialize, Deserialize)]
pub struct ErrorResponse {
    pub error: String,
    pub message: String,
}

impl From<CdnError> for ErrorResponse {
    fn from(err: CdnError) -> Self {
        ErrorResponse {
            error: format!("{:?}", err),
            message: err.to_string(),
        }
    }
}
