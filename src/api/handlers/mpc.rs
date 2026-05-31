use axum::{
    extract::{Path, State},
    Json,
};

use crate::domain::common::ApiResponse;
use crate::infra::error::AppResult;
use crate::modules::mpc::{
    CreateSessionRequest, CreateGarbledCircuitRequest, CreateObliviousTransferRequest,
    InputSubmission, ComputationRequest,
};

use super::ApiState;

pub async fn create_session(
    State(state): State<ApiState>,
    Json(request): Json<CreateSessionRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::mpc::MPCSession>>> {
    let auth = super::get_mock_auth_context();
    let session = state.orchestrator.mpc_service.create_session(request, &auth).await?;
    Ok(Json(ApiResponse::created(session)))
}

pub async fn list_sessions(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<Vec<crate::modules::mpc::MPCSession>>>> {
    let sessions = state.orchestrator.mpc_service.list_sessions().await?;
    Ok(Json(ApiResponse::success(sessions)))
}

pub async fn get_session(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<crate::modules::mpc::MPCSession>>> {
    let session = state.orchestrator.mpc_service.get_session(&id).await?;
    Ok(Json(ApiResponse::success(session)))
}

pub async fn submit_input(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(request): Json<InputSubmission>,
) -> AppResult<Json<ApiResponse<()>>> {
    state.orchestrator.mpc_service.submit_input(&id, request).await?;
    Ok(Json(ApiResponse::success(())))
}

pub async fn execute_computation(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(request): Json<ComputationRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::mpc::ComputationResult>>> {
    let result = state.orchestrator.mpc_service.execute_computation(&id, request).await?;
    Ok(Json(ApiResponse::success(result)))
}

pub async fn create_garbled_circuit(
    State(state): State<ApiState>,
    Json(request): Json<CreateGarbledCircuitRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::mpc::GarbledCircuit>>> {
    let circuit = state.orchestrator.mpc_service.create_garbled_circuit(request).await?;
    Ok(Json(ApiResponse::created(circuit)))
}

pub async fn create_oblivious_transfer(
    State(state): State<ApiState>,
    Json(request): Json<CreateObliviousTransferRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::mpc::ObliviousTransfer>>> {
    let ot = state.orchestrator.mpc_service.create_oblivious_transfer(request).await?;
    Ok(Json(ApiResponse::created(ot)))
}
