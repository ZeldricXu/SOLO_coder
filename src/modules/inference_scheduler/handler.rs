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
    RegisterModelRequest, DeployModelRequest, InferenceRequest, BatchInferenceRequest,
    TaskStatus, CreateVersionRequest, UpdateVersionRequest,
};
use super::service::InferenceSchedulerService;

#[derive(Debug, Deserialize)]
pub struct PaginationQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
}

#[derive(Debug, Deserialize)]
pub struct TaskListQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
    pub status: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct StatisticsQuery {
    #[serde(default = "default_time_window")]
    pub time_window_seconds: u64,
}

fn default_page() -> u32 { 1 }
fn default_page_size() -> u32 { 20 }
fn default_time_window() -> u64 { 3600 }

pub struct InferenceSchedulerHandler {
    pub service: Arc<InferenceSchedulerService>,
}

impl InferenceSchedulerHandler {
    pub fn new(service: Arc<InferenceSchedulerService>) -> Arc<Self> {
        Arc::new(Self { service })
    }

    pub async fn register_model(
        State(_self): State<Arc<Self>>,
        Json(req): Json<RegisterModelRequest>,
    ) -> AppResult<Json<ApiResponse<super::model::ModelResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.register_model(&ctx, req).await?;
        Ok(Json(ApiResponse::created(result)))
    }

    pub async fn deploy_model(
        State(_self): State<Arc<Self>>,
        Json(req): Json<DeployModelRequest>,
    ) -> AppResult<Json<ApiResponse<super::model::ModelResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.deploy_model(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_model(
        State(_self): State<Arc<Self>>,
        Path(model_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<super::model::ModelResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.get_model(&ctx, &model_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_models(
        State(_self): State<Arc<Self>>,
        Query(query): Query<PaginationQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<super::model::ModelResponse>>>> {
        let (items, total) = _self.service.list_models(query.page, query.page_size).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn undeploy_model(
        State(_self): State<Arc<Self>>,
        Path(model_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.undeploy_model(&ctx, &model_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "undeployed" }))))
    }

    pub async fn delete_model(
        State(_self): State<Arc<Self>>,
        Path(model_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.delete_model(&ctx, &model_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted" }))))
    }

    pub async fn submit_inference(
        State(_self): State<Arc<Self>>,
        Json(req): Json<InferenceRequest>,
    ) -> AppResult<Json<ApiResponse<super::model::TaskResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.submit_inference(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn batch_inference(
        State(_self): State<Arc<Self>>,
        Json(req): Json<BatchInferenceRequest>,
    ) -> AppResult<Json<ApiResponse<Vec<super::model::TaskResponse>>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.batch_inference(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_task(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<super::model::TaskResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.get_task(&ctx, &task_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_result(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<super::model::InferenceResultResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.get_result(&ctx, &task_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_tasks(
        State(_self): State<Arc<Self>>,
        Query(query): Query<TaskListQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<super::model::TaskResponse>>>> {
        let status = query.status.as_ref().and_then(|s| {
            match s.to_lowercase().as_str() {
                "queued" => Some(TaskStatus::Queued),
                "scheduled" => Some(TaskStatus::Scheduled),
                "running" => Some(TaskStatus::Running),
                "completed" => Some(TaskStatus::Completed),
                "failed" => Some(TaskStatus::Failed),
                "timeout" => Some(TaskStatus::Timeout),
                "cancelled" => Some(TaskStatus::Cancelled),
                _ => None,
            }
        });
        let (items, total) = _self.service.list_tasks(query.page, query.page_size, status).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn cancel_task(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.cancel_task(&ctx, &task_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "cancelled" }))))
    }

    pub async fn get_statistics(
        State(_self): State<Arc<Self>>,
        Path(model_id): Path<String>,
        Query(query): Query<StatisticsQuery>,
    ) -> AppResult<Json<ApiResponse<super::model::InferenceStatistics>>> {
        let result = _self.service.get_statistics(&model_id, query.time_window_seconds).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_metrics(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<crate::common::metrics::StatsSnapshot>>> {
        let result = _self.service.get_metrics();
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn create_version(
        State(_self): State<Arc<Self>>,
        Json(req): Json<CreateVersionRequest>,
    ) -> AppResult<Json<ApiResponse<super::model::ModelVersionResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.create_version(&ctx, req).await?;
        Ok(Json(ApiResponse::created(result)))
    }

    pub async fn list_versions(
        State(_self): State<Arc<Self>>,
        Path(model_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<Vec<super::model::ModelVersionResponse>>>> {
        let result = _self.service.list_versions(&model_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_version(
        State(_self): State<Arc<Self>>,
        Path((model_id, version_id)): Path<(String, String)>,
    ) -> AppResult<Json<ApiResponse<super::model::ModelVersionResponse>>> {
        let result = _self.service.get_version(&model_id, &version_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn update_version(
        State(_self): State<Arc<Self>>,
        Path((model_id, version_id)): Path<(String, String)>,
        Json(req): Json<UpdateVersionRequest>,
    ) -> AppResult<Json<ApiResponse<super::model::ModelVersionResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.update_version(&ctx, &model_id, &version_id, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn delete_version(
        State(_self): State<Arc<Self>>,
        Path((model_id, version_id)): Path<(String, String)>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.delete_version(&ctx, &model_id, &version_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted" }))))
    }

    pub async fn set_default_version(
        State(_self): State<Arc<Self>>,
        Path((model_id, version_id)): Path<(String, String)>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.set_default_version(&ctx, &model_id, &version_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "default_version_set" }))))
    }

    pub async fn compare_versions(
        State(_self): State<Arc<Self>>,
        Path((model_id, from_version_id, to_version_id)): Path<(String, String, String)>,
    ) -> AppResult<Json<ApiResponse<super::model::ModelVersionDiff>>> {
        let result = _self.service.compare_versions(&model_id, &from_version_id, &to_version_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn check_version_compatibility(
        State(_self): State<Arc<Self>>,
        Path((model_id, version_id, device_id)): Path<(String, String, String)>,
    ) -> AppResult<Json<ApiResponse<super::model::VersionCompatibilityCheck>>> {
        let result = _self.service.check_version_compatibility(&model_id, &version_id, &device_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }
}

pub fn routes(service: Arc<InferenceSchedulerService>) -> axum::Router {
    let handler = InferenceSchedulerHandler::new(service);
    axum::Router::new()
        .route(
            "/models",
            axum::routing::post(InferenceSchedulerHandler::register_model)
                .get(InferenceSchedulerHandler::list_models),
        )
        .route(
            "/models/:model_id",
            axum::routing::get(InferenceSchedulerHandler::get_model)
                .delete(InferenceSchedulerHandler::delete_model),
        )
        .route(
            "/models/:model_id/deploy",
            axum::routing::post(InferenceSchedulerHandler::deploy_model),
        )
        .route(
            "/models/:model_id/undeploy",
            axum::routing::post(InferenceSchedulerHandler::undeploy_model),
        )
        .route(
            "/models/:model_id/statistics",
            axum::routing::get(InferenceSchedulerHandler::get_statistics),
        )
        .route(
            "/inference",
            axum::routing::post(InferenceSchedulerHandler::submit_inference),
        )
        .route(
            "/inference/batch",
            axum::routing::post(InferenceSchedulerHandler::batch_inference),
        )
        .route(
            "/inference-tasks",
            axum::routing::get(InferenceSchedulerHandler::list_tasks),
        )
        .route(
            "/inference-tasks/:task_id",
            axum::routing::get(InferenceSchedulerHandler::get_task),
        )
        .route(
            "/inference-tasks/:task_id/cancel",
            axum::routing::post(InferenceSchedulerHandler::cancel_task),
        )
        .route(
            "/results/:task_id",
            axum::routing::get(InferenceSchedulerHandler::get_result),
        )
        .route(
            "/metrics",
            axum::routing::get(InferenceSchedulerHandler::get_metrics),
        )
        .route(
            "/versions",
            axum::routing::post(InferenceSchedulerHandler::create_version),
        )
        .route(
            "/models/:model_id/versions",
            axum::routing::get(InferenceSchedulerHandler::list_versions),
        )
        .route(
            "/models/:model_id/versions/:version_id",
            axum::routing::get(InferenceSchedulerHandler::get_version)
                .put(InferenceSchedulerHandler::update_version)
                .delete(InferenceSchedulerHandler::delete_version),
        )
        .route(
            "/models/:model_id/versions/:version_id/default",
            axum::routing::post(InferenceSchedulerHandler::set_default_version),
        )
        .route(
            "/models/:model_id/versions/compare/:from_version_id/:to_version_id",
            axum::routing::get(InferenceSchedulerHandler::compare_versions),
        )
        .route(
            "/models/:model_id/versions/:version_id/compatibility/:device_id",
            axum::routing::get(InferenceSchedulerHandler::check_version_compatibility),
        )
        .with_state(handler)
}
