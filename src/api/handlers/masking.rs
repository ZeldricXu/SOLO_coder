use axum::{
    extract::State,
    Json,
};
use serde::Deserialize;

use crate::domain::common::ApiResponse;
use crate::infra::error::AppResult;
use crate::modules::masking::{
    BatchMaskingRequest, BatchProcessorConfig, MaskingRequest, MaskingRule,
};

use super::ApiState;

pub async fn mask_data(
    State(state): State<ApiState>,
    Json(request): Json<MaskingRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::masking::MaskingResponse>>> {
    let auth = super::get_mock_auth_context();
    let response = state.orchestrator.masking_service.mask_data(request, &auth).await?;
    Ok(Json(ApiResponse::success(response)))
}

pub async fn list_rules(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<Vec<MaskingRule>>>> {
    let rules = state.orchestrator.masking_service.list_rules();
    Ok(Json(ApiResponse::success(rules.iter().cloned().collect())))
}

pub async fn batch_mask(
    State(state): State<ApiState>,
    Json(request): Json<BatchMaskingRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::masking::BatchMaskingResponse>>> {
    let auth = super::get_mock_auth_context();
    let response = state.orchestrator.masking_service.batch_mask(request, &auth).await?;
    Ok(Json(ApiResponse::success(response)))
}

#[derive(Debug, Deserialize)]
pub struct MaskFieldBatchRequest {
    pub records: Vec<(String, String)>,
}

pub async fn mask_field_batch(
    State(state): State<ApiState>,
    Json(request): Json<MaskFieldBatchRequest>,
) -> AppResult<Json<ApiResponse<Vec<(String, String)>>>> {
    let auth = super::get_mock_auth_context();
    let response = state.orchestrator.masking_service.mask_field_batch(request.records, &auth).await?;
    Ok(Json(ApiResponse::success(response)))
}

#[derive(Debug, Deserialize)]
pub struct MaskJsonBatchRequest {
    pub records: Vec<serde_json::Value>,
}

pub async fn mask_json_batch(
    State(state): State<ApiState>,
    Json(request): Json<MaskJsonBatchRequest>,
) -> AppResult<Json<ApiResponse<Vec<serde_json::Value>>>> {
    let auth = super::get_mock_auth_context();
    let response = state.orchestrator.masking_service.mask_json_batch(request.records, &auth).await?;
    Ok(Json(ApiResponse::success(response)))
}

pub async fn get_batch_metrics(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<crate::modules::masking::BatchProcessorMetrics>>> {
    let metrics = state.orchestrator.masking_service.get_batch_metrics();
    Ok(Json(ApiResponse::success(metrics)))
}

pub async fn get_batch_config(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<BatchProcessorConfig>>> {
    let config = state.orchestrator.masking_service.get_batch_config().clone();
    Ok(Json(ApiResponse::success(config)))
}

pub async fn update_batch_config(
    State(state): State<ApiState>,
    Json(request): Json<BatchProcessorConfig>,
) -> AppResult<Json<ApiResponse<BatchProcessorConfig>>> {
    state.orchestrator.masking_service.update_batch_config(request);
    let config = state.orchestrator.masking_service.get_batch_config().clone();
    Ok(Json(ApiResponse::success(config)))
}
