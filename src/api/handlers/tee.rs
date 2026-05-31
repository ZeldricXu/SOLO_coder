use axum::{
    extract::{Path, State},
    Json,
};

use crate::domain::common::ApiResponse;
use crate::domain::entity::SignedRequest;
use crate::infra::error::AppResult;
use crate::modules::tee::{AttestationRequest, CreateEnclaveRequest};

use super::ApiState;

pub async fn create_enclave(
    State(state): State<ApiState>,
    Json(request): Json<CreateEnclaveRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::tee::Enclave>>> {
    let auth = super::get_mock_auth_context();
    let enclave = state.orchestrator.create_secure_enclave(request, &auth).await?;
    Ok(Json(ApiResponse::created(enclave)))
}

pub async fn list_enclaves(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<Vec<crate::modules::tee::Enclave>>>> {
    let enclaves = state.orchestrator.tee_service.list_enclaves().await?;
    Ok(Json(ApiResponse::success(enclaves)))
}

pub async fn get_enclave(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<crate::modules::tee::Enclave>>> {
    let enclave = state.orchestrator.tee_service.get_enclave(&id).await?;
    Ok(Json(ApiResponse::success(enclave)))
}

pub async fn start_enclave(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<crate::modules::tee::Enclave>>> {
    let enclave = state.orchestrator.tee_service.start_enclave(&id).await?;
    Ok(Json(ApiResponse::success(enclave)))
}

pub async fn stop_enclave(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<crate::modules::tee::Enclave>>> {
    let enclave = state.orchestrator.tee_service.stop_enclave(&id).await?;
    Ok(Json(ApiResponse::success(enclave)))
}

pub async fn terminate_enclave(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<()>>> {
    state.orchestrator.tee_service.terminate_enclave(&id).await?;
    Ok(Json(ApiResponse::success(())))
}

pub async fn generate_attestation(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(request): Json<AttestationRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::tee::AttestationReport>>> {
    let report = state.orchestrator.tee_service.generate_attestation_report(request).await?;
    Ok(Json(ApiResponse::success(report)))
}

pub async fn execute_secure_function(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(request): Json<SignedRequest>,
) -> AppResult<Json<ApiResponse<crate::domain::entity::BinaryResponse>>> {
    let auth = super::get_mock_auth_context();
    let response = state.orchestrator.execute_secure_operation(&id, request, &auth).await?;
    Ok(Json(ApiResponse::success(response)))
}

pub async fn heartbeat(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<()>>> {
    state.orchestrator.tee_service.heartbeat(&id).await?;
    Ok(Json(ApiResponse::success(())))
}

pub async fn get_cache_status(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<crate::modules::tee::CacheStatusResponse>>> {
    let status = state.orchestrator.tee_service.get_cache_status();
    Ok(Json(ApiResponse::success(status)))
}

pub async fn invalidate_cache(
    State(state): State<ApiState>,
    Json(request): Json<crate::modules::tee::CacheInvalidationRequest>,
) -> AppResult<Json<ApiResponse<usize>>> {
    let count = state.orchestrator.tee_service.invalidate_cache(request).await?;
    Ok(Json(ApiResponse::success(count)))
}

pub async fn warm_up_cache(
    State(state): State<ApiState>,
    Json(request): Json<crate::modules::tee::CacheWarmUpRequest>,
) -> AppResult<Json<ApiResponse<usize>>> {
    let count = state.orchestrator.tee_service.warm_up_cache(request).await?;
    Ok(Json(ApiResponse::success(count)))
}

pub async fn reset_cache_stats(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<()>>> {
    state.orchestrator.tee_service.reset_cache_stats();
    Ok(Json(ApiResponse::success(())))
}
