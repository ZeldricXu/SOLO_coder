use serde::{Serialize, Deserialize};
use axum::{Json, http::StatusCode, response::IntoResponse};
use serde_json::Value;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ApiResponse<T> {
    pub code: u32,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<T>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pagination: Option<PaginationInfo>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub degraded: Option<bool>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct PaginationInfo {
    pub page: u32,
    pub page_size: u32,
    pub total: u64,
    pub total_pages: u32,
}

impl<T> ApiResponse<T> {
    pub fn success(data: T) -> Self {
        Self {
            code: 200,
            message: "success".into(),
            data: Some(data),
            pagination: None,
            degraded: None,
        }
    }

    pub fn success_with_pagination(data: T, pagination: PaginationInfo) -> Self {
        Self {
            code: 200,
            message: "success".into(),
            data: Some(data),
            pagination: Some(pagination),
            degraded: None,
        }
    }

    pub fn created(data: T) -> Self {
        Self {
            code: 201,
            message: "created".into(),
            data: Some(data),
            pagination: None,
            degraded: None,
        }
    }

    pub fn error(code: u32, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            data: None,
            pagination: None,
            degraded: None,
        }
    }

    pub fn degraded(code: u32, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            data: None,
            pagination: None,
            degraded: Some(true),
        }
    }
}

impl<T: Serialize> IntoResponse for ApiResponse<T> {
    fn into_response(self) -> axum::response::Response {
        let status = match self.code {
            200 => StatusCode::OK,
            201 => StatusCode::CREATED,
            204 => StatusCode::NO_CONTENT,
            400 => StatusCode::BAD_REQUEST,
            401 => StatusCode::UNAUTHORIZED,
            403 => StatusCode::FORBIDDEN,
            404 => StatusCode::NOT_FOUND,
            409 => StatusCode::CONFLICT,
            422 => StatusCode::UNPROCESSABLE_ENTITY,
            503 => StatusCode::SERVICE_UNAVAILABLE,
            504 => StatusCode::GATEWAY_TIMEOUT,
            _ => StatusCode::INTERNAL_SERVER_ERROR,
        };
        (status, Json(self)).into_response()
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct BatchResponse<T> {
    pub batch_id: String,
    pub results: Vec<BatchResult<T>>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct BatchResult<T> {
    pub id: String,
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<T>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

impl<T: Serialize> BatchResult<T> {
    pub fn success(id: impl Into<String>, data: T) -> Self {
        Self {
            id: id.into(),
            success: true,
            data: Some(data),
            error: None,
        }
    }

    pub fn failure(id: impl Into<String>, error: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            success: false,
            data: None,
            error: Some(error.into()),
        }
    }
}

impl PaginationInfo {
    pub fn new(page: u32, page_size: u32, total: u64) -> Self {
        let total_pages = ((total as f64) / (page_size as f64)).ceil() as u32;
        Self {
            page,
            page_size,
            total,
            total_pages,
        }
    }
}

pub fn not_found(message: impl Into<String>) -> ApiResponse<Value> {
    ApiResponse::error(404, message)
}

pub fn validation_error(message: impl Into<String>) -> ApiResponse<Value> {
    ApiResponse::error(422, message)
}

pub fn internal_error(message: impl Into<String>) -> ApiResponse<Value> {
    ApiResponse::error(500, message)
}
