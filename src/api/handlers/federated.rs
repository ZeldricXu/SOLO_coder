use axum::{
    extract::{Path, State},
    Json,
};

use crate::domain::common::ApiResponse;
use crate::infra::error::AppResult;
use crate::modules::federated::{
    AggregationRequest, CreateTaskRequest, GradientSubmission, ParticipantRegistration,
};

use super::ApiState;

pub async fn create_task(
    State(state): State<ApiState>,
    Json(request): Json<CreateTaskRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::federated::FederatedTask>>> {
    let task = state.orchestrator.federated_service.create_task(request).await?;
    Ok(Json(ApiResponse::created(task)))
}

pub async fn list_tasks(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<Vec<crate::modules::federated::FederatedTask>>>> {
    let tasks = state.orchestrator.federated_service.list_tasks().await?;
    Ok(Json(ApiResponse::success(tasks)))
}

pub async fn get_task(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<crate::modules::federated::FederatedTask>>> {
    let task = state.orchestrator.federated_service.get_task(&id).await?;
    Ok(Json(ApiResponse::success(task)))
}

pub async fn register_participant(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(request): Json<ParticipantRegistration>,
) -> AppResult<Json<ApiResponse<crate::modules::federated::ParticipantRegistrationResponse>>> {
    let response = state.orchestrator.federated_service.register_participant(&id, request).await?;
    Ok(Json(ApiResponse::success(response)))
}

pub async fn submit_gradient(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(request): Json<GradientSubmission>,
) -> AppResult<Json<ApiResponse<crate::modules::federated::GradientSubmissionResponse>>> {
    let response = state.orchestrator.federated_service.submit_gradient(&id, request).await?;
    Ok(Json(ApiResponse::success(response)))
}

pub async fn aggregate_gradients(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(request): Json<AggregationRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::federated::GlobalModelUpdate>>> {
    let auth = super::get_mock_auth_context();
    let update = state.orchestrator.federated_service.aggregate_gradients(&id, request, &auth).await?;
    Ok(Json(ApiResponse::success(update)))
}

pub async fn get_task_performance(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<crate::modules::federated::TaskPerformanceMetrics>>> {
    let perf = state.orchestrator.federated_service.get_task_performance(&id)
        .ok_or_else(|| crate::infra::error::AppError::NotFound(format!("Task {} not found", id)))?;
    Ok(Json(ApiResponse::success(perf)))
}

pub async fn get_latency_breakdown(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<crate::modules::federated::LatencyBreakdown>>> {
    let breakdown = state.orchestrator.federated_service.get_latency_breakdown(&id)
        .ok_or_else(|| crate::infra::error::AppError::NotFound(format!("Task {} not found", id)))?;
    Ok(Json(ApiResponse::success(breakdown)))
}

pub async fn get_global_metrics(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<crate::modules::federated::FLMetricsSnapshot>>> {
    let metrics = state.orchestrator.federated_service.get_global_metrics();
    Ok(Json(ApiResponse::success(metrics)))
}

pub async fn export_prometheus_metrics(
    State(state): State<ApiState>,
) -> AppResult<axum::response::Response> {
    let metrics = state.orchestrator.federated_service.export_prometheus_metrics();
    Ok(axum::response::Response::builder()
        .header("Content-Type", "text/plain; version=0.0.4")
        .body(axum::body::Body::from(metrics))
        .unwrap())
}

pub async fn reset_metrics(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<()>>> {
    state.orchestrator.federated_service.reset_metrics();
    Ok(Json(ApiResponse::success(())))
}
