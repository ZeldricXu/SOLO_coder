use axum::{
    extract::{Path, State},
    Json,
};

use crate::domain::common::ApiResponse;
use crate::infra::error::AppResult;
use crate::modules::dp::{DPRequest, CreateBudgetRequest, QueryResult};

use super::ApiState;

pub async fn apply_dp(
    State(state): State<ApiState>,
    Json(request): Json<DPRequest>,
) -> AppResult<Json<ApiResponse<QueryResult>>> {
    let auth = super::get_mock_auth_context();
    let result = state.orchestrator.execute_privacy_preserving_query(request, &auth).await?;
    Ok(Json(ApiResponse::success(result)))
}

pub async fn create_budget(
    State(state): State<ApiState>,
    Json(request): Json<CreateBudgetRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::dp::PrivacyBudget>>> {
    let budget = state.orchestrator.dp_service.create_budget(request).await?;
    Ok(Json(ApiResponse::created(budget)))
}

pub async fn get_budget(
    State(state): State<ApiState>,
    Path(user_id): Path<String>,
) -> AppResult<Json<ApiResponse<crate::modules::dp::PrivacyBudget>>> {
    let budget = state.orchestrator.dp_service.get_budget(&user_id).await?;
    Ok(Json(ApiResponse::success(budget)))
}

pub async fn get_query_history(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<Vec<crate::modules::dp::QueryHistoryEntry>>>> {
    let history = state.orchestrator.dp_service.get_query_history().await?;
    Ok(Json(ApiResponse::success(history)))
}
