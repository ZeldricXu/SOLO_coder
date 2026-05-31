use axum::{
    extract::{Path, State, Query},
    Json,
};
use serde::Deserialize;
use std::sync::Arc;

use crate::common::error::AppResult;
use crate::common::context::RequestContext;
use crate::common::response::{ApiResponse, PaginationInfo};
use super::model::{ShadowUpdateRequest, MonitorPointCreateRequest, MonitorPointUpdateRequest};
use super::service::DeviceShadowService;

#[derive(Debug, Deserialize)]
pub struct PaginationQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
    #[serde(default)]
    pub include_monitoring: bool,
}

#[derive(Debug, Deserialize)]
pub struct MonitoringQuery {
    #[serde(default = "default_duration")]
    pub duration_seconds: u64,
}

#[derive(Debug, Deserialize)]
pub struct AlertQuery {
    #[serde(default)]
    pub device_id: Option<String>,
    #[serde(default = "default_limit")]
    pub limit: usize,
}

fn default_page() -> u32 { 1 }
fn default_page_size() -> u32 { 20 }
fn default_duration() -> u64 { 3600 }
fn default_limit() -> usize { 100 }

pub struct DeviceShadowHandler {
    pub service: Arc<DeviceShadowService>,
}

impl DeviceShadowHandler {
    pub fn new(service: Arc<DeviceShadowService>) -> Arc<Self> {
        Arc::new(Self { service })
    }

    pub async fn update_shadow(
        State(_self): State<Arc<Self>>,
        Json(req): Json<ShadowUpdateRequest>,
    ) -> AppResult<Json<ApiResponse<super::model::ShadowResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.update_shadow(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_shadow(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
        Query(query): Query<PaginationQuery>,
    ) -> AppResult<Json<ApiResponse<super::model::ShadowResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.get_shadow(&ctx, &device_id, query.include_monitoring).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn sync_shadow(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<super::model::ShadowResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.sync_device(&ctx, &device_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_shadows(
        State(_self): State<Arc<Self>>,
        Query(query): Query<PaginationQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<super::model::ShadowResponse>>>> {
        let (items, total) = _self.service.list_shadows(query.page, query.page_size, query.include_monitoring).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn delete_shadow(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.delete_shadow(&ctx, &device_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted" }))))
    }

    pub async fn create_monitor_point(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
        Json(req): Json<MonitorPointCreateRequest>,
    ) -> AppResult<Json<ApiResponse<super::model::MonitorPoint>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.create_monitor_point(&ctx, &device_id, req).await?;
        Ok(Json(ApiResponse::created(result)))
    }

    pub async fn update_monitor_point(
        State(_self): State<Arc<Self>>,
        Path((device_id, point_id)): Path<(String, String)>,
        Json(req): Json<MonitorPointUpdateRequest>,
    ) -> AppResult<Json<ApiResponse<super::model::MonitorPoint>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.update_monitor_point(&ctx, &device_id, &point_id, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn delete_monitor_point(
        State(_self): State<Arc<Self>>,
        Path((device_id, point_id)): Path<(String, String)>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.delete_monitor_point(&ctx, &device_id, &point_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted" }))))
    }

    pub async fn list_monitor_points(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<Vec<super::model::MonitorPoint>>>> {
        let result = _self.service.list_monitor_points(&device_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_monitoring(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<super::model::ShadowMonitoring>>> {
        let result = _self.service.get_monitoring(&device_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_monitoring_report(
        State(_self): State<Arc<Self>>,
        Path(device_id): Path<String>,
        Query(query): Query<MonitoringQuery>,
    ) -> AppResult<Json<ApiResponse<super::model::MonitoringReport>>> {
        let result = _self.service.get_monitoring_report(&device_id, query.duration_seconds).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_alerts(
        State(_self): State<Arc<Self>>,
        Query(query): Query<AlertQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<super::model::MonitoringAlert>>>> {
        let result = _self.service.get_alerts(query.device_id.as_deref(), query.limit).await;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn acknowledge_alert(
        State(_self): State<Arc<Self>>,
        Path(alert_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        _self.service.acknowledge_alert(&alert_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "acknowledged" }))))
    }

    pub async fn get_metrics(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<crate::common::metrics::StatsSnapshot>>> {
        let result = _self.service.get_metrics();
        Ok(Json(ApiResponse::success(result)))
    }
}

pub fn routes(service: Arc<DeviceShadowService>) -> axum::Router {
    let handler = DeviceShadowHandler::new(service);
    axum::Router::new()
        .route("/shadows", axum::routing::post(DeviceShadowHandler::update_shadow).get(DeviceShadowHandler::list_shadows))
        .route("/shadows/:device_id", axum::routing::get(DeviceShadowHandler::get_shadow).delete(DeviceShadowHandler::delete_shadow))
        .route("/shadows/:device_id/sync", axum::routing::post(DeviceShadowHandler::sync_shadow))
        .route("/shadows/:device_id/monitoring", axum::routing::get(DeviceShadowHandler::get_monitoring))
        .route("/shadows/:device_id/monitoring/report", axum::routing::get(DeviceShadowHandler::get_monitoring_report))
        .route("/shadows/:device_id/monitor-points", axum::routing::post(DeviceShadowHandler::create_monitor_point).get(DeviceShadowHandler::list_monitor_points))
        .route("/shadows/:device_id/monitor-points/:point_id", axum::routing::put(DeviceShadowHandler::update_monitor_point).delete(DeviceShadowHandler::delete_monitor_point))
        .route("/monitoring/alerts", axum::routing::get(DeviceShadowHandler::get_alerts))
        .route("/monitoring/alerts/:alert_id/acknowledge", axum::routing::post(DeviceShadowHandler::acknowledge_alert))
        .route("/metrics", axum::routing::get(DeviceShadowHandler::get_metrics))
        .with_state(handler)
}
