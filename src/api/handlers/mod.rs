use axum::{
    extract::{Path, Query, State},
    Json,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

use crate::domain::common::{ApiResponse, BatchRequest, BatchResponse, CreateResourceRequest, ResourceStatusResponse};
use crate::domain::user::{AuthContext, User, PermissionLevel};
use crate::infra::error::AppResult;

use super::routes::ApiState;

pub mod tee;
pub mod masking;
pub mod federated;
pub mod mpc;
pub mod classification;
pub mod dp;
pub mod audit;
pub mod sharding;

#[derive(Debug, Serialize, Deserialize)]
pub struct HealthResponse {
    pub status: String,
    pub version: String,
    pub timestamp: chrono::DateTime<chrono::Utc>,
}

pub async fn health_check() -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "healthy".to_string(),
        version: env!("CARGO_PKG_VERSION").to_string(),
        timestamp: chrono::Utc::now(),
    })
}

pub async fn create_resource(
    State(state): State<ApiState>,
    Json(request): Json<CreateResourceRequest>,
) -> AppResult<Json<ApiResponse<crate::service::resource_manager::Resource>>> {
    let resource = state.resource_manager.create_resource(request).await?;
    Ok(Json(ApiResponse::created(resource)))
}

pub async fn get_resource_status(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<ResourceStatusResponse>>> {
    let status = state.resource_manager.get_resource_status(&id).await?;
    Ok(Json(ApiResponse::success(status)))
}

pub async fn batch_operation(
    State(state): State<ApiState>,
    Json(request): Json<BatchRequest>,
) -> AppResult<Json<ApiResponse<BatchResponse>>> {
    let auth = get_mock_auth_context();
    let response = state
        .orchestrator
        .execute_batch_operation(request.operations, &auth)
        .await?;
    Ok(Json(ApiResponse::success(response)))
}

fn get_mock_auth_context() -> AuthContext {
    let user = User::new("test_user".to_string(), "test".to_string(), PermissionLevel::Restricted);
    AuthContext::new(user, 3600)
}
