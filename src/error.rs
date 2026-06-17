use thiserror::Error;

use crate::crdt::CrdtError;
use crate::auth::AuthError;
use crate::storage::StorageError;

#[derive(Error, Debug)]
pub enum AppError {
    #[error("CRDT error: {0}")]
    Crdt(#[from] CrdtError),

    #[error("Authentication error: {0}")]
    Auth(#[from] AuthError),

    #[error("Storage error: {0}")]
    Storage(#[from] StorageError),

    #[error("WebSocket error: {0}")]
    WebSocket(String),

    #[error("Rate limit exceeded: retry after {0}s")]
    RateLimit(u64),

    #[error("Document not found: {0}")]
    DocumentNotFound(String),

    #[error("Session not found: {0}")]
    SessionNotFound(String),

    #[error("Service unavailable: {0}")]
    ServiceUnavailable(String),

    #[error("Invalid input: {0}")]
    InvalidInput(String),

    #[error("Internal error: {0}")]
    Internal(String),
}

pub type AppResult<T> = Result<T, AppError>;

impl axum::response::IntoResponse for AppError {
    fn into_response(self) -> axum::response::Response {
        use axum::http::StatusCode;
        use serde_json::json;

        let (status, code, message) = match &self {
            AppError::Auth(_) => (StatusCode::UNAUTHORIZED, "AUTH_ERROR", self.to_string()),
            AppError::DocumentNotFound(_) => (StatusCode::NOT_FOUND, "NOT_FOUND", self.to_string()),
            AppError::InvalidInput(_) => (StatusCode::BAD_REQUEST, "BAD_REQUEST", self.to_string()),
            AppError::RateLimit(retry) => (StatusCode::TOO_MANY_REQUESTS, "RATE_LIMITED", format!("{}; retry-after={}s", self.to_string(), retry)),
            AppError::ServiceUnavailable(_) => (StatusCode::SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", self.to_string()),
            AppError::SessionNotFound(_) => (StatusCode::GONE, "SESSION_GONE", self.to_string()),
            _ => (StatusCode::INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", self.to_string()),
        };

        let body = json!({
            "error": {
                "code": code,
                "message": message,
                "timestamp": chrono::Utc::now().to_rfc3339()
            }
        });

        let mut response = axum::Json(body).into_response();
        *response.status_mut() = status;

        if let AppError::RateLimit(retry) = self {
            response.headers_mut().insert(
                axum::http::header::RETRY_AFTER,
                retry.to_string().parse().unwrap(),
            );
        }

        if let AppError::ServiceUnavailable(retry) = &self {
            response.headers_mut().insert(
                axum::http::header::RETRY_AFTER,
                "30".parse().unwrap(),
            );
        }

        response
    }
}
