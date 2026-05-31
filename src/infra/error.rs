use thiserror::Error;

#[derive(Error, Debug)]
pub enum AppError {
    #[error("Validation failed: {0}")]
    ValidationError(String),

    #[error("Authentication failed: {0}")]
    AuthError(String),

    #[error("Permission denied: {0}")]
    PermissionDenied(String),

    #[error("Not found: {0}")]
    NotFound(String),

    #[error("Timeout: {0}")]
    TimeoutError(String),

    #[error("Configuration error: {0}")]
    ConfigError(String),

    #[error("Cryptographic error: {0}")]
    CryptoError(String),

    #[error("Database error: {0}")]
    DatabaseError(String),

    #[error("Cache error: {0}")]
    CacheError(String),

    #[error("Resource exhausted: {0}")]
    ResourceExhausted(String),

    #[error("Integrity check failed: {0}")]
    IntegrityError(String),

    #[error("MPC protocol error: {0}")]
    MPCError(String),

    #[error("Privacy budget exhausted: {0}")]
    PrivacyBudgetExhausted(String),

    #[error("Internal error: {0}")]
    InternalError(String),
}

impl AppError {
    pub fn status_code(&self) -> u16 {
        match self {
            AppError::ValidationError(_) => 422,
            AppError::AuthError(_) => 401,
            AppError::PermissionDenied(_) => 403,
            AppError::NotFound(_) => 404,
            AppError::TimeoutError(_) => 504,
            AppError::ConfigError(_) => 500,
            AppError::CryptoError(_) => 500,
            AppError::DatabaseError(_) => 500,
            AppError::CacheError(_) => 500,
            AppError::ResourceExhausted(_) => 429,
            AppError::IntegrityError(_) => 500,
            AppError::MPCError(_) => 500,
            AppError::PrivacyBudgetExhausted(_) => 429,
            AppError::InternalError(_) => 500,
        }
    }

    pub fn error_code(&self) -> &'static str {
        match self {
            AppError::ValidationError(_) => "VALIDATION_ERROR",
            AppError::AuthError(_) => "AUTH_ERROR",
            AppError::PermissionDenied(_) => "PERMISSION_DENIED",
            AppError::NotFound(_) => "NOT_FOUND",
            AppError::TimeoutError(_) => "TIMEOUT",
            AppError::ConfigError(_) => "CONFIG_ERROR",
            AppError::CryptoError(_) => "CRYPTO_ERROR",
            AppError::DatabaseError(_) => "DATABASE_ERROR",
            AppError::CacheError(_) => "CACHE_ERROR",
            AppError::ResourceExhausted(_) => "RESOURCE_EXHAUSTED",
            AppError::IntegrityError(_) => "INTEGRITY_ERROR",
            AppError::MPCError(_) => "MPC_ERROR",
            AppError::PrivacyBudgetExhausted(_) => "PRIVACY_BUDGET_EXHAUSTED",
            AppError::InternalError(_) => "INTERNAL_ERROR",
        }
    }
}

impl axum::response::IntoResponse for AppError {
    fn into_response(self) -> axum::response::Response {
        let body = serde_json::json!({
            "code": self.status_code(),
            "error_code": self.error_code(),
            "message": self.to_string(),
        });

        (
            axum::http::StatusCode::from_u16(self.status_code()).unwrap_or(axum::http::StatusCode::INTERNAL_SERVER_ERROR),
            axum::Json(body),
        )
            .into_response()
    }
}

pub type AppResult<T> = Result<T, AppError>;

impl From<serde_json::Error> for AppError {
    fn from(err: serde_json::Error) -> Self {
        AppError::ValidationError(format!("JSON error: {}", err))
    }
}

impl From<sqlx::Error> for AppError {
    fn from(err: sqlx::Error) -> Self {
        AppError::DatabaseError(err.to_string())
    }
}

impl From<redis::RedisError> for AppError {
    fn from(err: redis::RedisError) -> Self {
        AppError::CacheError(err.to_string())
    }
}

impl From<chrono::ParseError> for AppError {
    fn from(err: chrono::ParseError) -> Self {
        AppError::ValidationError(format!("Date parse error: {}", err))
    }
}
