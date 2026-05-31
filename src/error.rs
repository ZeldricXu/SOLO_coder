use thiserror::Error;
use std::io;
use serde_json;

#[derive(Debug, Error)]
pub enum PlatformError {
    #[error("Validation error: {0}")]
    Validation(String),
    
    #[error("Configuration error: {0}")]
    Config(String),
    
    #[error("Resource not found: {0}")]
    NotFound(String),
    
    #[error("Concurrency conflict: resource_id={0}")]
    Conflict(String),
    
    #[error("Timeout error: {0}")]
    Timeout(String),
    
    #[error("Authentication error: {0}")]
    Authentication(String),
    
    #[error("Authorization error: {0}")]
    Authorization(String),
    
    #[error("Cryptographic error: {0}")]
    Crypto(String),
    
    #[error("Database error: {0}")]
    Database(String),
    
    #[error("Migration error: {0}")]
    Migration(String),
    
    #[error("Network error: {0}")]
    Network(String),
    
    #[error("Enclave error: {0}")]
    Enclave(String),
    
    #[error("Audit log tampered: {0}")]
    AuditTampered(String),
    
    #[error("Internal error: {0}")]
    Internal(String),
}

impl From<io::Error> for PlatformError {
    fn from(err: io::Error) -> Self {
        PlatformError::Internal(format!("IO error: {}", err))
    }
}

impl From<serde_json::Error> for PlatformError {
    fn from(err: serde_json::Error) -> Self {
        PlatformError::Internal(format!("JSON error: {}", err))
    }
}

impl PlatformError {
    pub fn http_status_code(&self) -> u16 {
        match self {
            PlatformError::Validation(_) => 422,
            PlatformError::Config(_) => 400,
            PlatformError::NotFound(_) => 404,
            PlatformError::Conflict(_) => 409,
            PlatformError::Timeout(_) => 504,
            PlatformError::Authentication(_) => 401,
            PlatformError::Authorization(_) => 403,
            PlatformError::Crypto(_) => 500,
            PlatformError::Database(_) => 500,
            PlatformError::Migration(_) => 500,
            PlatformError::Network(_) => 502,
            PlatformError::Enclave(_) => 500,
            PlatformError::AuditTampered(_) => 500,
            PlatformError::Internal(_) => 500,
        }
    }

    pub fn error_message(&self) -> String {
        match self {
            PlatformError::Conflict(resource_id) => {
                format!("Concurrency conflict detected for resource: {}", resource_id)
            }
            _ => self.to_string(),
        }
    }
}
