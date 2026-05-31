use axum::{
    extract::{Path, State, Query, Json},
    http::HeaderMap,
};
use serde::Deserialize;
use std::sync::Arc;

use crate::common::error::AppResult;
use crate::common::context::RequestContext;
use crate::common::response::{ApiResponse, PaginationInfo};
use super::model::{
    DeviceRegisterRequest, DeviceActivateRequest, DeviceAuthRequest,
    HeartbeatRequest, DeviceStatusUpdateRequest, DeviceLabelUpdateRequest,
    DeviceTagUpdateRequest, DeviceResponse, DeviceQueryParams,
};
use super::service::DeviceLifecycleService;

#[derive(Debug, Deserialize)]
pub struct PaginationQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
}

#[derive(Debug, Deserialize)]
pub struct DeviceListQuery {
    #[serde(flatten)]
    pub pagination: PaginationQuery,
    pub status: Option<String>,
    pub device_type: Option<String>,
    pub tenant_id: Option<String>,
    pub label_key: Option<String>,
    pub label_value: Option<String>,
    pub tag: Option<String>,
    pub last_heartbeat_before: Option<chrono::DateTime<chrono::Utc>>,
    pub last_heartbeat_after: Option<chrono::DateTime<chrono::Utc>>,
}

fn default_page() -> u32 { 1 }
fn default_page_size() -> u32 { 20 }

pub struct DeviceLifecycleHandler {
    pub service: Arc<DeviceLifecycleService>,
}

impl DeviceLifecycleHandler {
    pub fn new(service: Arc<DeviceLifecycleService>) -> Arc<Self> {
        Arc::new(Self { service })
    }

    pub async fn register_device(
        State(_self): State<Arc<Self>>,
        Json(req): Json<DeviceRegisterRequest>,
    ) -> AppResult<Json<ApiResponse<super::model::DeviceRegisterResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.register_device(&ctx, req).await?;
        Ok(Json(ApiResponse::created(result)))
    }

    pub async fn activate_device(
        State(_self): State<Arc<Self>>,
        Json(req): Json<DeviceActivateRequest>,
    ) -> AppResult<Json<ApiResponse<super::model::DeviceActivateResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.activate_device(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn authenticate_device(
        State(_self): State<Arc<Self>>,
        Json(req): Json<DeviceAuthRequest>,
    ) -> AppResult<Json<ApiResponse<super::model::DeviceAuthResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.authenticate_device(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn heartbeat(
        State(_self): State<Arc<Self>>,
        Json(req): Json<HeartbeatRequest>,
    ) -> AppResult<Json<ApiResponse<super::model::HeartbeatResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.heartbeat(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_device(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<DeviceResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.get_device(&ctx, &device_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_devices(
        State(_self): State<Arc<Self>>,
        Query(query): Query<DeviceListQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<DeviceResponse>>>> {
        let ctx = RequestContext::new_with_random();
        let params = DeviceQueryParams {
            status: query.status,
            device_type: query.device_type,
            tenant_id: query.tenant_id,
            label_key: query.label_key,
            label_value: query.label_value,
            tag: query.tag,
            last_heartbeat_before: query.last_heartbeat_before,
            last_heartbeat_after: query.last_heartbeat_after,
        };
        let (items, total) = _self.service.list_devices(&ctx, params, query.pagination.page, query.pagination.page_size).await?;
        let pagination = PaginationInfo::new(query.pagination.page, query.pagination.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn update_device_status(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
        Json(req): Json<DeviceStatusUpdateRequest>,
    ) -> AppResult<Json<ApiResponse<DeviceResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.update_device_status(&ctx, &device_id, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn update_labels(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
        Json(req): Json<DeviceLabelUpdateRequest>,
    ) -> AppResult<Json<ApiResponse<DeviceResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.update_labels(&ctx, &device_id, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn update_tags(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
        Json(req): Json<DeviceTagUpdateRequest>,
    ) -> AppResult<Json<ApiResponse<DeviceResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.update_tags(&ctx, &device_id, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn enable_device(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<DeviceResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.enable_device(&ctx, &device_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn disable_device(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<DeviceResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.disable_device(&ctx, &device_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn delete_device(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.delete_device(&ctx, &device_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted" }))))
    }

    pub async fn rotate_credentials(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<super::model::DeviceRegisterResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.rotate_credentials(&ctx, &device_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn check_session(
        State(_self): State<Arc<Self>>,
        headers: HeaderMap,
    ) -> AppResult<Json<ApiResponse<super::model::DeviceSession>>> {
        let ctx = RequestContext::new_with_random();
        let token = extract_token(&headers)?;
        let result = _self.service.check_session(&ctx, &token).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn logout_device(
        State(_self): State<Arc<Self>>,
        headers: HeaderMap,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        let token = extract_token(&headers)?;
        _self.service.logout_device(&ctx, &token).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "logged_out" }))))
    }

    pub async fn get_metrics(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<crate::common::metrics::StatsSnapshot>>> {
        let result = _self.service.get_metrics();
        Ok(Json(ApiResponse::success(result)))
    }
}

fn extract_token(headers: &HeaderMap) -> AppResult<String> {
    let auth_header = headers.get("Authorization")
        .ok_or_else(|| crate::common::error::AppError::Unauthorized("缺少Authorization头".into()))?;

    let auth_str = auth_header.to_str()
        .map_err(|_| crate::common::error::AppError::Unauthorized("Authorization头格式无效".into()))?;

    if !auth_str.starts_with("Bearer ") {
        return Err(crate::common::error::AppError::Unauthorized("Authorization必须使用Bearer方案".into()));
    }

    Ok(auth_str[7..].to_string())
}

pub fn routes(service: Arc<DeviceLifecycleService>) -> axum::Router {
    let handler = DeviceLifecycleHandler::new(service);
    axum::Router::new()
        .route("/devices",
            axum::routing::post(DeviceLifecycleHandler::register_device)
                .get(DeviceLifecycleHandler::list_devices)
        )
        .route("/devices/activate",
            axum::routing::post(DeviceLifecycleHandler::activate_device)
        )
        .route("/devices/authenticate",
            axum::routing::post(DeviceLifecycleHandler::authenticate_device)
        )
        .route("/devices/heartbeat",
            axum::routing::post(DeviceLifecycleHandler::heartbeat)
        )
        .route("/devices/session",
            axum::routing::get(DeviceLifecycleHandler::check_session)
                .delete(DeviceLifecycleHandler::logout_device)
        )
        .route("/devices/metrics",
            axum::routing::get(DeviceLifecycleHandler::get_metrics)
        )
        .route("/devices/:device_id",
            axum::routing::get(DeviceLifecycleHandler::get_device)
                .delete(DeviceLifecycleHandler::delete_device)
        )
        .route("/devices/:device_id/status",
            axum::routing::put(DeviceLifecycleHandler::update_device_status)
        )
        .route("/devices/:device_id/labels",
            axum::routing::put(DeviceLifecycleHandler::update_labels)
        )
        .route("/devices/:device_id/tags",
            axum::routing::put(DeviceLifecycleHandler::update_tags)
        )
        .route("/devices/:device_id/enable",
            axum::routing::post(DeviceLifecycleHandler::enable_device)
        )
        .route("/devices/:device_id/disable",
            axum::routing::post(DeviceLifecycleHandler::disable_device)
        )
        .route("/devices/:device_id/credentials/rotate",
            axum::routing::post(DeviceLifecycleHandler::rotate_credentials)
        )
        .with_state(handler)
}
