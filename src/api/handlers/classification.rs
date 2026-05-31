use axum::{
    extract::{Path, State},
    Json,
};

use crate::domain::common::ApiResponse;
use crate::infra::error::AppResult;
use crate::modules::classification::{ClassificationRequest, CreatePolicyRequest};

use super::ApiState;

pub async fn classify_data(
    State(state): State<ApiState>,
    Json(request): Json<ClassificationRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::classification::ClassificationResponse>>> {
    let auth = super::get_mock_auth_context();
    let response = state.orchestrator.classify_and_mask_data(request, &auth).await?;
    Ok(Json(ApiResponse::success(response)))
}

pub async fn list_reports(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<Vec<crate::modules::classification::ClassificationReport>>>> {
    let reports = state.orchestrator.classification_service.list_reports().await?;
    Ok(Json(ApiResponse::success(reports)))
}

pub async fn get_report(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<crate::modules::classification::ClassificationReport>>> {
    let report = state.orchestrator.classification_service.get_report(&id).await?;
    Ok(Json(ApiResponse::success(report)))
}

pub async fn list_patterns(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<Vec<crate::modules::classification::DataPattern>>>> {
    let patterns = state.orchestrator.classification_service.list_patterns();
    Ok(Json(ApiResponse::success(patterns)))
}

pub async fn create_policy(
    State(state): State<ApiState>,
    Json(request): Json<CreatePolicyRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::classification::ClassificationPolicy>>> {
    let policy = state.orchestrator.classification_service.create_policy(request).await?;
    Ok(Json(ApiResponse::created(policy)))
}
