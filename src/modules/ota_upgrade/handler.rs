use axum::{
    extract::{Path, State, Query},
    Json,
};
use serde::Deserialize;
use std::sync::Arc;

use crate::common::error::AppResult;
use crate::common::context::RequestContext;
use crate::common::response::{ApiResponse, PaginationInfo};
use super::model::{
    UploadFirmwareRequest, CreateUpgradeTaskRequest, ApproveUpgradeRequest,
    DeviceStatusUpdateRequest, GenerateDeltaRequest, RollbackRequest,
    FirmwareResponse, UpgradeTaskResponse, DeviceStatusResponse, DeltaResponse,
    UpgradePhase,
};
use super::service::OtaUpgradeService;

#[derive(Debug, Deserialize)]
pub struct PaginationQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
}

fn default_page() -> u32 { 1 }
fn default_page_size() -> u32 { 20 }

#[derive(Debug, Deserialize)]
pub struct FirmwareListQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
    pub device_model: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct TaskListQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
    pub status: Option<String>,
}

pub struct OtaUpgradeHandler {
    pub service: Arc<OtaUpgradeService>,
}

impl OtaUpgradeHandler {
    pub fn new(service: Arc<OtaUpgradeService>) -> Arc<Self> {
        Arc::new(Self { service })
    }

    pub async fn upload_firmware(
        State(_self): State<Arc<Self>>,
        Json(req): Json<UploadFirmwareRequest>,
    ) -> AppResult<Json<ApiResponse<FirmwareResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.upload_firmware(&ctx, req).await?;
        Ok(Json(ApiResponse::created(result)))
    }

    pub async fn get_firmware(
        State(_self): State<Arc<Self>>,
        Path(package_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<FirmwareResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.get_firmware(&ctx, &package_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_firmware(
        State(_self): State<Arc<Self>>,
        Query(query): Query<FirmwareListQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<FirmwareResponse>>>> {
        let (items, total) = _self.service.list_firmware(
            query.device_model.as_deref(),
            query.page,
            query.page_size,
        ).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn delete_firmware(
        State(_self): State<Arc<Self>>,
        Path(package_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.delete_firmware(&ctx, &package_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted" }))))
    }

    pub async fn generate_delta(
        State(_self): State<Arc<Self>>,
        Json(req): Json<GenerateDeltaRequest>,
    ) -> AppResult<Json<ApiResponse<DeltaResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.generate_delta(&ctx, req).await?;
        Ok(Json(ApiResponse::created(result)))
    }

    pub async fn create_upgrade_task(
        State(_self): State<Arc<Self>>,
        Json(req): Json<CreateUpgradeTaskRequest>,
    ) -> AppResult<Json<ApiResponse<UpgradeTaskResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.create_upgrade_task(&ctx, req).await?;
        Ok(Json(ApiResponse::created(result)))
    }

    pub async fn get_upgrade_task(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<UpgradeTaskResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.get_upgrade_task(&ctx, &task_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_upgrade_tasks(
        State(_self): State<Arc<Self>>,
        Query(query): Query<TaskListQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<UpgradeTaskResponse>>>> {
        let status = query.status.as_ref().and_then(|s| parse_upgrade_phase(s));
        let (items, total) = _self.service.list_upgrade_tasks(
            status,
            query.page,
            query.page_size,
        ).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn approve_upgrade(
        State(_self): State<Arc<Self>>,
        Json(req): Json<ApproveUpgradeRequest>,
    ) -> AppResult<Json<ApiResponse<UpgradeTaskResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = (&_self.service).approve_upgrade(
            &ctx,
            &req.task_id,
            req.approved,
            req.comment,
        ).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn update_device_status(
        State(_self): State<Arc<Self>>,
        Json(req): Json<DeviceStatusUpdateRequest>,
    ) -> AppResult<Json<ApiResponse<DeviceStatusResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = (&_self.service).update_device_status(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_device_status(
        State(_self): State<Arc<Self>>,
        Path((task_id, device_id)): Path<(String, String)>,
    ) -> AppResult<Json<ApiResponse<DeviceStatusResponse>>> {
        let result = _self.service.get_device_upgrade_status(&task_id, &device_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_device_statuses(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
        Query(query): Query<PaginationQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<DeviceStatusResponse>>>> {
        let (items, total) = _self.service.list_device_statuses(
            &task_id,
            query.page,
            query.page_size,
        ).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn trigger_rollback(
        State(_self): State<Arc<Self>>,
        Json(req): Json<RollbackRequest>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.trigger_rollback(&ctx, req).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "rollback_triggered" }))))
    }

    pub async fn pause_upgrade(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.pause_upgrade(&ctx, &task_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "paused" }))))
    }

    pub async fn resume_upgrade(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        (&_self.service).resume_upgrade(&ctx, &task_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "resumed" }))))
    }

    pub async fn cancel_upgrade(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.cancel_upgrade(&ctx, &task_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "cancelled" }))))
    }

    pub async fn get_metrics(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<crate::common::metrics::StatsSnapshot>>> {
        let metrics = _self.service.get_metrics();
        Ok(Json(ApiResponse::success(metrics)))
    }
}

fn parse_upgrade_phase(s: &str) -> Option<UpgradePhase> {
    match s.to_lowercase().as_str() {
        "pending" => Some(UpgradePhase::Pending),
        "approved" => Some(UpgradePhase::Approved),
        "downloading" => Some(UpgradePhase::Downloading),
        "installing" => Some(UpgradePhase::Installing),
        "verifying" => Some(UpgradePhase::Verifying),
        "success" => Some(UpgradePhase::Success),
        "failed" => Some(UpgradePhase::Failed),
        "rolling_back" | "rollingback" => Some(UpgradePhase::RollingBack),
        "rolled_back" | "rolledback" => Some(UpgradePhase::RolledBack),
        _ => None,
    }
}

pub fn routes(service: Arc<OtaUpgradeService>) -> axum::Router {
    let handler = OtaUpgradeHandler::new(service);
    axum::Router::new()
        .route("/firmware",
            axum::routing::post(OtaUpgradeHandler::upload_firmware)
                .get(OtaUpgradeHandler::list_firmware)
        )
        .route("/firmware/:package_id",
            axum::routing::get(OtaUpgradeHandler::get_firmware)
                .delete(OtaUpgradeHandler::delete_firmware)
        )
        .route("/firmware/delta",
            axum::routing::post(OtaUpgradeHandler::generate_delta)
        )
        .route("/upgrade-tasks",
            axum::routing::post(OtaUpgradeHandler::create_upgrade_task)
                .get(OtaUpgradeHandler::list_upgrade_tasks)
        )
        .route("/upgrade-tasks/:task_id",
            axum::routing::get(OtaUpgradeHandler::get_upgrade_task)
        )
        .route("/upgrade-tasks/:task_id/approve",
            axum::routing::post(OtaUpgradeHandler::approve_upgrade)
        )
        .route("/upgrade-tasks/:task_id/pause",
            axum::routing::post(OtaUpgradeHandler::pause_upgrade)
        )
        .route("/upgrade-tasks/:task_id/resume",
            axum::routing::post(OtaUpgradeHandler::resume_upgrade)
        )
        .route("/upgrade-tasks/:task_id/cancel",
            axum::routing::post(OtaUpgradeHandler::cancel_upgrade)
        )
        .route("/upgrade-tasks/:task_id/rollback",
            axum::routing::post(OtaUpgradeHandler::trigger_rollback)
        )
        .route("/upgrade-tasks/:task_id/devices",
            axum::routing::get(OtaUpgradeHandler::list_device_statuses)
        )
        .route("/upgrade-tasks/:task_id/devices/:device_id",
            axum::routing::get(OtaUpgradeHandler::get_device_status)
        )
        .route("/device/status",
            axum::routing::post(OtaUpgradeHandler::update_device_status)
        )
        .route("/rollback",
            axum::routing::post(OtaUpgradeHandler::trigger_rollback)
        )
        .route("/metrics",
            axum::routing::get(OtaUpgradeHandler::get_metrics)
        )
        .with_state(handler)
}
