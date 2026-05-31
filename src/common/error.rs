use thiserror::Error;
use axum::{
    http::StatusCode,
    response::{IntoResponse, Response},
    Json,
};
use serde_json::json;

#[derive(Error, Debug)]
pub enum AppError {
    #[error("参数验证失败: {0}")]
    Validation(String),

    #[error("资源未找到: {0}")]
    NotFound(String),

    #[error("认证失败: {0}")]
    Unauthorized(String),

    #[error("权限不足: {0}")]
    Forbidden(String),

    #[error("超时错误: {0}")]
    Timeout(String),

    #[error("依赖服务不可用: {0}")]
    ServiceUnavailable(String),

    #[error("冲突错误: {0}")]
    Conflict(String),

    #[error("内部错误: {0}")]
    Internal(String),

    #[error("序列化错误: {0}")]
    Serialization(#[from] serde_json::Error),

    #[error("IO错误: {0}")]
    Io(#[from] std::io::Error),

    #[error("数据库错误: {0}")]
    Database(String),

    #[error("Redis错误: {0}")]
    Redis(String),
}

impl AppError {
    pub fn status_code(&self) -> StatusCode {
        match self {
            AppError::Validation(_) => StatusCode::UNPROCESSABLE_ENTITY,
            AppError::NotFound(_) => StatusCode::NOT_FOUND,
            AppError::Unauthorized(_) => StatusCode::UNAUTHORIZED,
            AppError::Forbidden(_) => StatusCode::FORBIDDEN,
            AppError::Timeout(_) => StatusCode::GATEWAY_TIMEOUT,
            AppError::ServiceUnavailable(_) => StatusCode::SERVICE_UNAVAILABLE,
            AppError::Conflict(_) => StatusCode::CONFLICT,
            AppError::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
            AppError::Serialization(_) => StatusCode::BAD_REQUEST,
            AppError::Io(_) => StatusCode::INTERNAL_SERVER_ERROR,
            AppError::Database(_) => StatusCode::INTERNAL_SERVER_ERROR,
            AppError::Redis(_) => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }

    pub fn error_code(&self) -> i32 {
        match self {
            AppError::Validation(_) => 422,
            AppError::NotFound(_) => 404,
            AppError::Unauthorized(_) => 401,
            AppError::Forbidden(_) => 403,
            AppError::Timeout(_) => 504,
            AppError::ServiceUnavailable(_) => 503,
            AppError::Conflict(_) => 409,
            _ => 500,
        }
    }

    pub fn degraded(&self) -> bool {
        matches!(self, AppError::ServiceUnavailable(_))
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let status = self.status_code();
        let body = json!({
            "code": self.error_code(),
            "message": self.to_string(),
            "degraded": self.degraded(),
            "data": null::<serde_json::Value>
        });
        (status, Json(body)).into_response()
    }
}

pub type AppResult<T> = Result<T, AppError>;

impl From<sqlx::Error> for AppError {
    fn from(err: sqlx::Error) -> Self {
        AppError::Database(err.to_string())
    }
}

impl From<redis::RedisError> for AppError {
    fn from(err: redis::RedisError) -> Self {
        AppError::Redis(err.to_string())
    }
}

impl From<bb8::RunError<redis::RedisError>> for AppError {
    fn from(err: bb8::RunError<redis::RedisError>) -> Self {
        AppError::Redis(err.to_string())
    }
}
