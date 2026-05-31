use axum::{
    extract::{Path, State},
    Json,
};

use crate::domain::common::ApiResponse;
use crate::infra::error::AppResult;
use crate::modules::sharding::{CreateShardedKeyRequest, ReconstructKeyRequest, RotateShareRequest};

use super::ApiState;

pub async fn create_sharded_key(
    State(state): State<ApiState>,
    Json(request): Json<CreateShardedKeyRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::sharding::ShardedKeyInfo>>> {
    let auth = super::get_mock_auth_context();
    let info = state.orchestrator.sharding_service.create_sharded_key(request, &auth).await?;
    Ok(Json(ApiResponse::created(info)))
}

pub async fn list_keys(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<Vec<crate::modules::sharding::ShardedKeyInfo>>>> {
    let keys = state.orchestrator.sharding_service.list_keys().await?;
    Ok(Json(ApiResponse::success(keys)))
}

pub async fn get_key(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<crate::modules::sharding::ShardedKeyInfo>>> {
    let key = state.orchestrator.sharding_service.get_key(&id).await?;
    Ok(Json(ApiResponse::success(key)))
}

pub async fn delete_key(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<()>>> {
    state.orchestrator.sharding_service.delete_key(&id).await?;
    Ok(Json(ApiResponse::success(())))
}

pub async fn reconstruct_key(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(request): Json<ReconstructKeyRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::sharding::ReconstructedKeyResponse>>> {
    let auth = super::get_mock_auth_context();
    let response = state.orchestrator.sharding_service.reconstruct_key(&id, request, &auth).await?;
    Ok(Json(ApiResponse::success(response)))
}

pub async fn get_shares(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<Vec<crate::modules::sharding::KeyShareInfo>>>> {
    let shares = state.orchestrator.sharding_service.get_shares(&id).await?;
    Ok(Json(ApiResponse::success(shares)))
}

pub async fn get_share(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<crate::modules::sharding::KeyShareInfo>>> {
    let share = state.orchestrator.sharding_service.get_share(&id).await?;
    Ok(Json(ApiResponse::success(share)))
}

pub async fn rotate_share(
    State(state): State<ApiState>,
    Json(request): Json<RotateShareRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::sharding::KeyShareInfo>>> {
    let share = state.orchestrator.sharding_service.rotate_share(request).await?;
    Ok(Json(ApiResponse::success(share)))
}
