use axum::{
    extract::{Path, Query, State},
    Json,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

use crate::domain::common::ApiResponse;
use crate::infra::error::AppResult;
use crate::modules::audit::{AuditLogEntry, SealBlockRequest};

use super::ApiState;

#[derive(Debug, Deserialize)]
pub struct LogQuery {
    pub action: Option<String>,
    pub user: Option<String>,
    pub from: Option<chrono::DateTime<chrono::Utc>>,
    pub to: Option<chrono::DateTime<chrono::Utc>>,
    pub limit: Option<usize>,
}

pub async fn log_event(
    State(state): State<ApiState>,
    Json(request): Json<AuditLogEntry>,
) -> AppResult<Json<ApiResponse<crate::modules::audit::AuditLogEntry>>> {
    let entry = state.orchestrator.audit_service.log_event(request).await?;
    Ok(Json(ApiResponse::created(entry)))
}

pub async fn query_logs(
    State(state): State<ApiState>,
    Query(query): Query<LogQuery>,
) -> AppResult<Json<ApiResponse<Vec<AuditLogEntry>>>> {
    let logs = state.orchestrator.audit_service.query_logs(
        query.action,
        query.user,
        query.from,
        query.to,
        query.limit,
    ).await?;
    Ok(Json(ApiResponse::success(logs)))
}

pub async fn get_log(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<AuditLogEntry>>> {
    let log = state.orchestrator.audit_service.get_log(&id).await?;
    Ok(Json(ApiResponse::success(log)))
}

pub async fn list_blocks(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<Vec<crate::modules::audit::AuditBlock>>>> {
    let blocks = state.orchestrator.audit_service.list_blocks().await?;
    Ok(Json(ApiResponse::success(blocks)))
}

pub async fn get_block(
    State(state): State<ApiState>,
    Path(height): Path<u64>,
) -> AppResult<Json<ApiResponse<crate::modules::audit::AuditBlock>>> {
    let block = state.orchestrator.audit_service.get_block(height).await?;
    Ok(Json(ApiResponse::success(block)))
}

pub async fn seal_block(
    State(state): State<ApiState>,
    Json(request): Json<SealBlockRequest>,
) -> AppResult<Json<ApiResponse<crate::modules::audit::AuditBlock>>> {
    let block = state.orchestrator.audit_service.seal_block(request).await?;
    Ok(Json(ApiResponse::success(block)))
}

pub async fn verify_integrity(
    State(state): State<ApiState>,
) -> AppResult<Json<ApiResponse<crate::modules::audit::IntegrityReport>>> {
    let report = state.orchestrator.audit_service.verify_integrity().await?;
    Ok(Json(ApiResponse::success(report)))
}
