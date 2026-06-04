use actix_web::{http::StatusCode, HttpResponse, ResponseError};
use serde::Serialize;
use thiserror::Error;
use uuid::Uuid;

#[derive(Debug, Error)]
pub enum AppError {
    #[error("Database error: {0}")]
    Database(#[from] sqlx::Error),

    #[error("Redis error: {0}")]
    Redis(#[from] redis::RedisError),

    #[error("MongoDB error: {0}")]
    MongoDB(#[from] mongodb::error::Error),

    #[error("Serialization error: {0}")]
    Serialization(#[from] serde_json::Error),

    #[error("Authentication error: {0}")]
    Authentication(String),

    #[error("Authorization error: {0}")]
    Authorization(String),

    #[error("Validation error: {0}")]
    Validation(String),

    #[error("Not found: {0}")]
    NotFound(String),

    #[error("Auction error: {0}")]
    Auction(String),

    #[error("Bid error: {0}")]
    Bid(String),

    #[error("Account error: {0}")]
    Account(String),

    #[error("Payment error: {0}")]
    Payment(String),

    #[error("Risk control error: {0}")]
    RiskControl(String),

    #[error("Lock acquisition failed: {0}")]
    LockFailed(String),

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    #[error("Configuration error: {0}")]
    Config(#[from] config::ConfigError),

    #[error("External service error: {0}")]
    ExternalService(String),

    #[error("Internal error: {0}")]
    Internal(String),

    #[error("Password hash error: {0}")]
    PasswordHash(String),

    #[error("JWT error: {0}")]
    Jwt(#[from] jsonwebtoken::errors::Error),

    #[error("HTTP client error: {0}")]
    HttpClient(#[from] reqwest::Error),

    #[error("Base64 decode error: {0}")]
    Base64Decode(#[from] base64::DecodeError),
}

impl From<argon2::password_hash::Error> for AppError {
    fn from(e: argon2::password_hash::Error) -> Self {
        AppError::PasswordHash(e.to_string())
    }
}

pub type AppResult<T> = Result<T, AppError>;

#[derive(Serialize)]
struct ErrorResponse {
    success: bool,
    error: String,
    code: u16,
    trace_id: String,
}

impl ResponseError for AppError {
    fn status_code(&self) -> StatusCode {
        match self {
            AppError::Authentication(_) => StatusCode::UNAUTHORIZED,
            AppError::Authorization(_) => StatusCode::FORBIDDEN,
            AppError::NotFound(_) => StatusCode::NOT_FOUND,
            AppError::Validation(_) => StatusCode::BAD_REQUEST,
            AppError::Bid(_) => StatusCode::CONFLICT,
            AppError::Auction(_) => StatusCode::BAD_REQUEST,
            AppError::Account(_) => StatusCode::BAD_REQUEST,
            AppError::LockFailed(_) => StatusCode::CONFLICT,
            _ => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }

    fn error_response(&self) -> HttpResponse {
        let status = self.status_code();
        let trace_id = Uuid::new_v4().to_string();

        tracing::error!(error = %self, trace_id = %trace_id, "Request error");

        HttpResponse::build(status).json(ErrorResponse {
            success: false,
            error: self.to_string(),
            code: status.as_u16(),
            trace_id,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_authentication_error_maps_to_401() {
        let err = AppError::Authentication("invalid token".to_string());
        assert_eq!(err.status_code(), StatusCode::UNAUTHORIZED);
    }

    #[test]
    fn test_authorization_error_maps_to_403() {
        let err = AppError::Authorization("not allowed".to_string());
        assert_eq!(err.status_code(), StatusCode::FORBIDDEN);
    }

    #[test]
    fn test_not_found_error_maps_to_404() {
        let err = AppError::NotFound("auction not found".to_string());
        assert_eq!(err.status_code(), StatusCode::NOT_FOUND);
    }

    #[test]
    fn test_validation_error_maps_to_400() {
        let err = AppError::Validation("invalid input".to_string());
        assert_eq!(err.status_code(), StatusCode::BAD_REQUEST);
    }

    #[test]
    fn test_bid_error_maps_to_409() {
        let err = AppError::Bid("concurrent bid conflict".to_string());
        assert_eq!(err.status_code(), StatusCode::CONFLICT);
    }

    #[test]
    fn test_auction_error_maps_to_400() {
        let err = AppError::Auction("auction not active".to_string());
        assert_eq!(err.status_code(), StatusCode::BAD_REQUEST);
    }

    #[test]
    fn test_lock_failed_maps_to_409() {
        let err = AppError::LockFailed("lock timeout".to_string());
        assert_eq!(err.status_code(), StatusCode::CONFLICT);
    }

    #[test]
    fn test_database_error_maps_to_500() {
        let err = AppError::Database(sqlx::Error::RowNotFound);
        assert_eq!(err.status_code(), StatusCode::INTERNAL_SERVER_ERROR);
    }

    #[test]
    fn test_redis_error_maps_to_500() {
        let err = AppError::Redis(redis::RedisError::from((
            redis::ErrorKind::IoError,
            "connection refused",
        )));
        assert_eq!(err.status_code(), StatusCode::INTERNAL_SERVER_ERROR);
    }

    #[test]
    fn test_error_response_json_structure() {
        let err = AppError::NotFound("test resource".to_string());
        let resp = err.error_response();
        assert_eq!(resp.status(), StatusCode::NOT_FOUND);
    }

    #[test]
    fn test_error_display_message() {
        let err = AppError::Authentication("bad token".to_string());
        assert_eq!(err.to_string(), "Authentication error: bad token");

        let err = AppError::Validation("empty field".to_string());
        assert_eq!(err.to_string(), "Validation error: empty field");

        let err = AppError::NotFound("id 123".to_string());
        assert_eq!(err.to_string(), "Not found: id 123");
    }

    #[test]
    fn test_app_result_ok() {
        let result: AppResult<i32> = Ok(42);
        assert_eq!(result.unwrap(), 42);
    }

    #[test]
    fn test_app_result_err() {
        let result: AppResult<i32> = Err(AppError::Validation("test".to_string()));
        assert!(result.is_err());
    }

    #[test]
    fn test_password_hash_conversion() {
        let hash_err = argon2::password_hash::Error::Algorithm;
        let app_err: AppError = hash_err.into();
        assert!(matches!(app_err, AppError::PasswordHash(_)));
    }
}
