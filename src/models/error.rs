use thiserror::Error;

#[derive(Debug, Error)]
pub enum ModelGuardError {
    #[error("Validation error: {0}")]
    ValidationError(String),
    
    #[error("Not found: {0}")]
    NotFound(String),
    
    #[error("Timeout: {0}")]
    TimeoutError(String),
    
    #[error("Internal error: {0}")]
    InternalError(String),
    
    #[error("Configuration error: {0}")]
    ConfigError(String),
    
    #[error("Resource unavailable: {0}")]
    ResourceUnavailable(String),
    
    #[error("Permission denied: {0}")]
    PermissionDenied(String),
    
    #[error("Conflict: {0}")]
    Conflict(String),
    
    #[error("Circuit breaker open: {0}")]
    CircuitBreakerOpen(String),
    
    #[error("Serialization error: {0}")]
    SerializationError(String),
    
    #[error("Parse error: {0}")]
    ParseError(String),
    
    #[error("Database error: {0}")]
    DatabaseError(String),
    
    #[error("Cache error: {0}")]
    CacheError(String),

    #[error("Rate limit exceeded: {0}")]
    RateLimitExceeded(String),

    #[error("Fallback failed: {0}")]
    FallbackFailed(String),

    #[error("Parsing error: {0}")]
    ParsingError(String),

    #[error("Conflict error: {0}")]
    ConflictError(String),
}

impl ModelGuardError {
    pub fn status_code(&self) -> u16 {
        match self {
            ModelGuardError::ValidationError(_) => 422,
            ModelGuardError::NotFound(_) => 404,
            ModelGuardError::PermissionDenied(_) => 403,
            ModelGuardError::Conflict(_) => 409,
            ModelGuardError::TimeoutError(_) => 504,
            ModelGuardError::CircuitBreakerOpen(_) => 503,
            ModelGuardError::ResourceUnavailable(_) => 503,
            _ => 500,
        }
    }

    pub fn to_json(&self) -> serde_json::Value {
        serde_json::json!({
            "code": self.status_code(),
            "error": self.to_string(),
            "type": format!("{:?}", self),
        })
    }
}

impl From<serde_json::Error> for ModelGuardError {
    fn from(err: serde_json::Error) -> Self {
        ModelGuardError::SerializationError(err.to_string())
    }
}

impl From<std::io::Error> for ModelGuardError {
    fn from(err: std::io::Error) -> Self {
        ModelGuardError::InternalError(format!("IO error: {}", err))
    }
}

impl From<uuid::Error> for ModelGuardError {
    fn from(err: uuid::Error) -> Self {
        ModelGuardError::ParseError(format!("UUID error: {}", err))
    }
}

impl From<chrono::ParseError> for ModelGuardError {
    fn from(err: chrono::ParseError) -> Self {
        ModelGuardError::ParseError(format!("Date parse error: {}", err))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_error_status_codes() {
        assert_eq!(
            ModelGuardError::ValidationError("bad input".to_string()).status_code(),
            422
        );
        assert_eq!(
            ModelGuardError::NotFound("resource".to_string()).status_code(),
            404
        );
        assert_eq!(
            ModelGuardError::TimeoutError("upstream".to_string()).status_code(),
            504
        );
        assert_eq!(
            ModelGuardError::InternalError("oops".to_string()).status_code(),
            500
        );
    }

    #[test]
    fn test_error_conversion() {
        let json_err = serde_json::from_str::<serde_json::Value>("invalid").unwrap_err();
        let mg_err: ModelGuardError = json_err.into();
        
        assert!(matches!(mg_err, ModelGuardError::SerializationError(_)));
    }
}
