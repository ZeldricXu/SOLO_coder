use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Serialize;
use thiserror::Error;
use utoipa::ToSchema;

#[derive(Debug, Serialize, ToSchema)]
pub struct ErrorResponse {
    pub code: u16,
    pub message: String,
    pub details: Option<serde_json::Value>,
}

#[derive(Debug, Error)]
pub enum AppError {
    #[error("Model not found: {0}")]
    ModelNotFound(String),

    #[error("Model version not found: {0}")]
    ModelVersionNotFound(String),

    #[error("Model is not online: {0}")]
    ModelNotOnline(String),

    #[error("Invalid model configuration: {0}")]
    InvalidModelConfig(String),

    #[error("Schema validation failed: {0}")]
    SchemaValidation(String),

    #[error("Inference failed: {0}")]
    InferenceError(String),

    #[error("Inference timeout: {0}ms")]
    InferenceTimeout(u64),

    #[error("Insufficient GPU memory: required={0}MB, available={1}MB")]
    InsufficientGpuMemory(u64, u64),

    #[error("GPU device not found: {0}")]
    GpuNotFound(String),

    #[error("Tenant not found: {0}")]
    TenantNotFound(String),

    #[error("Invalid API key")]
    InvalidApiKey,

    #[error("Rate limit exceeded")]
    RateLimitExceeded,

    #[error("QPS limit exceeded")]
    QpsLimitExceeded,

    #[error("Experiment not found: {0}")]
    ExperimentNotFound(String),

    #[error("Invalid experiment configuration: {0}")]
    InvalidExperimentConfig(String),

    #[error("Routing error: {0}")]
    RoutingError(String),

    #[error("Database error: {0}")]
    Database(String),

    #[error("Cache error: {0}")]
    Cache(String),

    #[error("Serialization error: {0}")]
    Serialization(String),

    #[error("Configuration error: {0}")]
    Config(String),

    #[error("Authentication error: {0}")]
    Authentication(String),

    #[error("Authorization error: {0}")]
    Authorization(String),

    #[error("Validation error: {0}")]
    Validation(String),

    #[error("Internal server error: {0}")]
    Internal(String),

    #[error("{0}")]
    External(String),

    #[error("Not implemented: {0}")]
    NotImplemented(String),

    #[error("Service unavailable: {0}")]
    ServiceUnavailable(String),
}

impl AppError {
    pub fn status_code(&self) -> StatusCode {
        match self {
            AppError::ModelNotFound(_)
            | AppError::ModelVersionNotFound(_)
            | AppError::TenantNotFound(_)
            | AppError::ExperimentNotFound(_) => StatusCode::NOT_FOUND,

            AppError::InvalidApiKey
            | AppError::Authentication(_) => StatusCode::UNAUTHORIZED,

            AppError::Authorization(_) => StatusCode::FORBIDDEN,

            AppError::RateLimitExceeded
            | AppError::QpsLimitExceeded => StatusCode::TOO_MANY_REQUESTS,

            AppError::ModelNotOnline(_)
            | AppError::InsufficientGpuMemory(_, _)
            | AppError::GpuNotFound(_)
            | AppError::RoutingError(_)
            | AppError::InvalidModelConfig(_)
            | AppError::InvalidExperimentConfig(_)
            | AppError::SchemaValidation(_)
            | AppError::Validation(_)
            | AppError::Serialization(_) => StatusCode::BAD_REQUEST,

            AppError::InferenceTimeout(_) => StatusCode::REQUEST_TIMEOUT,

            AppError::NotImplemented(_) => StatusCode::NOT_IMPLEMENTED,

            AppError::ServiceUnavailable(_) => StatusCode::SERVICE_UNAVAILABLE,

            AppError::InferenceError(_)
            | AppError::Database(_)
            | AppError::Cache(_)
            | AppError::Config(_)
            | AppError::Internal(_)
            | AppError::External(_) => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }

    pub fn details(&self) -> Option<serde_json::Value> {
        None
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let status = self.status_code();
        let error_response = ErrorResponse {
            code: status.as_u16(),
            message: self.to_string(),
            details: self.details(),
        };
        (status, Json(error_response)).into_response()
    }
}

impl From<serde_json::Error> for AppError {
    fn from(err: serde_json::Error) -> Self {
        AppError::Serialization(err.to_string())
    }
}

impl From<sqlx::Error> for AppError {
    fn from(err: sqlx::Error) -> Self {
        match err {
            sqlx::Error::RowNotFound => AppError::ModelNotFound("database row not found".to_string()),
            _ => AppError::Database(err.to_string()),
        }
    }
}

impl From<redis::RedisError> for AppError {
    fn from(err: redis::RedisError) -> Self {
        AppError::Cache(err.to_string())
    }
}

impl From<config::ConfigError> for AppError {
    fn from(err: config::ConfigError) -> Self {
        AppError::Config(err.to_string())
    }
}

impl From<anyhow::Error> for AppError {
    fn from(err: anyhow::Error) -> Self {
        AppError::Internal(err.to_string())
    }
}
