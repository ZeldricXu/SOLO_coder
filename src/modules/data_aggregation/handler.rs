use axum::{
    extract::{Path, State, Query},
    Json,
};
use serde::Deserialize;
use std::sync::Arc;
use chrono::DateTime;

use crate::common::error::AppResult;
use crate::common::context::RequestContext;
use crate::common::response::{ApiResponse, PaginationInfo};
use super::model::{CreateTaskRequest, UpdateTaskRequest, IngestDataRequest, IngestBatchRequest, TaskResponse, AggregationResult};
use super::service::DataAggregationService;

#[derive(Debug, Deserialize)]
pub struct PaginationQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
}

#[derive(Debug, Deserialize)]
pub struct ResultQueryParams {
    pub start_time: Option<DateTime<chrono::Utc>>,
    pub end_time: Option<DateTime<chrono::Utc>>,
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
}

fn default_page() -> u32 { 1 }
fn default_page_size() -> u32 { 20 }

pub struct DataAggregationHandler {
    pub service: Arc<DataAggregationService>,
}

impl DataAggregationHandler {
    pub fn new(service: Arc<DataAggregationService>) -> Arc<Self> {
        Arc::new(Self { service })
    }

    pub async fn create_task(
        State(_self): State<Arc<Self>>,
        Json(req): Json<CreateTaskRequest>,
    ) -> AppResult<Json<ApiResponse<TaskResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.create_task(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_task(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<TaskResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.get_task(&ctx, &task_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn list_tasks(
        State(_self): State<Arc<Self>>,
        Query(query): Query<PaginationQuery>,
    ) -> AppResult<Json<ApiResponse<Vec<TaskResponse>>>> {
        let (items, total) = _self.service.list_tasks(query.page, query.page_size).await?;
        let pagination = PaginationInfo::new(query.page, query.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn update_task(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
        Json(req): Json<UpdateTaskRequest>,
    ) -> AppResult<Json<ApiResponse<TaskResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.update_task(&ctx, &task_id, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn start_task(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<TaskResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.start_task(&ctx, &task_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn stop_task(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<TaskResponse>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.stop_task(&ctx, &task_id).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn delete_task(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        _self.service.delete_task(&ctx, &task_id).await?;
        Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted", "task_id": task_id }))))
    }

    pub async fn ingest_data(
        State(_self): State<Arc<Self>>,
        Json(req): Json<IngestDataRequest>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.ingest_data(&ctx, req).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn ingest_batch(
        State(_self): State<Arc<Self>>,
        Json(req): Json<IngestBatchRequest>,
    ) -> AppResult<Json<ApiResponse<serde_json::Value>>> {
        let ctx = RequestContext::new_with_random();
        let result = _self.service.ingest_batch(&ctx, req.task_id, req.points).await?;
        Ok(Json(ApiResponse::success(result)))
    }

    pub async fn get_results(
        State(_self): State<Arc<Self>>,
        Path(task_id): Path<String>,
        Query(params): Query<ResultQueryParams>,
    ) -> AppResult<Json<ApiResponse<Vec<AggregationResult>>>> {
        let ctx = RequestContext::new_with_random();
        let (items, total) = _self.service.get_results(
            &ctx,
            &task_id,
            params.start_time,
            params.end_time,
            params.page,
            params.page_size,
        ).await?;
        let pagination = PaginationInfo::new(params.page, params.page_size, total);
        Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
    }

    pub async fn get_metrics(
        State(_self): State<Arc<Self>>,
    ) -> AppResult<Json<ApiResponse<crate::common::metrics::StatsSnapshot>>> {
        let result = _self.service.get_metrics();
        Ok(Json(ApiResponse::success(result)))
    }
}

pub fn routes(service: Arc<DataAggregationService>) -> axum::Router {
    let handler = DataAggregationHandler::new(service);
    axum::Router::new()
        .route("/aggregation/tasks", axum::routing::post(DataAggregationHandler::create_task).get(DataAggregationHandler::list_tasks))
        .route("/aggregation/tasks/:task_id", axum::routing::get(DataAggregationHandler::get_task).put(DataAggregationHandler::update_task).delete(DataAggregationHandler::delete_task))
        .route("/aggregation/tasks/:task_id/start", axum::routing::post(DataAggregationHandler::start_task))
        .route("/aggregation/tasks/:task_id/stop", axum::routing::post(DataAggregationHandler::stop_task))
        .route("/aggregation/tasks/:task_id/results", axum::routing::get(DataAggregationHandler::get_results))
        .route("/aggregation/ingest", axum::routing::post(DataAggregationHandler::ingest_data))
        .route("/aggregation/ingest/batch", axum::routing::post(DataAggregationHandler::ingest_batch))
        .route("/aggregation/metrics", axum::routing::get(DataAggregationHandler::get_metrics))
        .with_state(handler)
}
