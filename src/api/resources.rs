use axum::{
    extract::{Path, State, Query},
    Json,
};
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use uuid::Uuid;
use serde_json::Value;
use std::collections::HashMap;

use crate::common::error::AppResult;
use crate::common::context::RequestContext;
use crate::common::response::{ApiResponse, PaginationInfo, BatchResponse, BatchResult};
use crate::domain::resource::{
    Resource, ResourceCreateRequest, ResourceStatus, ResourceStatusResponse,
    BatchRequest, BatchResponseData, BatchResultItem,
};
use crate::common::event::DomainEvent;
use crate::ports::mod::{EventPublisherPort, ResourceRepositoryPort};
use crate::common::context::AuditLogger;
use crate::common::metrics::MetricsCollector;

#[derive(Debug, Deserialize)]
pub struct PaginationQuery {
    #[serde(default = "default_page")]
    pub page: u32,
    #[serde(default = "default_page_size")]
    pub page_size: u32,
    #[serde(default)]
    pub resource_type: Option<String>,
}

fn default_page() -> u32 { 1 }
fn default_page_size() -> u32 { 20 }

pub struct AppState {
    pub resource_repo: Arc<dyn ResourceRepositoryPort>,
    pub event_publisher: Arc<dyn EventPublisherPort>,
    pub audit_logger: Arc<AuditLogger>,
    pub metrics: MetricsCollector,
}

impl AppState {
    pub fn new(
        resource_repo: Arc<dyn ResourceRepositoryPort>,
        event_publisher: Arc<dyn EventPublisherPort>,
        audit_logger: Arc<AuditLogger>,
    ) -> Arc<Self> {
        Arc::new(Self {
            resource_repo,
            event_publisher,
            audit_logger,
            metrics: MetricsCollector::new().with_dimension("module", "api"),
        })
    }
}

pub async fn create_resource(
    State(state): State<Arc<AppState>>,
    Json(req): Json<ResourceCreateRequest>,
) -> AppResult<Json<ApiResponse<ResourceStatusResponse>>> {
    let ctx = RequestContext::new_with_random();
    let start = std::time::Instant::now();

    if req.resource_type.is_empty() {
        return Err(crate::common::error::AppError::Validation("资源类型不能为空".into()));
    }

    let mut resource = Resource::new(req.resource_type, req.config);
    for (k, v) in req.labels {
        resource = resource.with_label(k, v);
    }

    state.resource_repo.save(&resource).await?;

    let event = DomainEvent::new(
        "resource.created",
        &resource.id,
        serde_json::json!({
            "id": resource.id,
            "type": resource.resource_type,
            "status": format!("{:?}", resource.status),
        }),
        &ctx.trace_id,
    );
    state.event_publisher.publish(event).await?;

    state.audit_logger.log_operation(
        &ctx,
        "resource.create",
        "resource",
        &resource.id,
        true,
        serde_json::json!({ "type": resource.resource_type }),
    );

    state.metrics.record_success(start.elapsed().as_millis() as u64);

    Ok(Json(ApiResponse::created(ResourceStatusResponse {
        id: resource.id,
        status: format!("{:?}", resource.status).to_lowercase(),
        progress: None,
    })))
}

pub async fn get_resource_status(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<ResourceStatusResponse>>> {
    let ctx = RequestContext::new_with_random();
    let start = std::time::Instant::now();

    let resource = state.resource_repo.find_by_id(&id).await?
        .ok_or_else(|| crate::common::error::AppError::NotFound(format!("资源不存在: {}", id)))?;

    state.metrics.record_success(start.elapsed().as_millis() as u64);

    Ok(Json(ApiResponse::success(ResourceStatusResponse {
        id: resource.id,
        status: format!("{:?}", resource.status).to_lowercase(),
        progress: None,
    })))
}

pub async fn list_resources(
    State(state): State<Arc<AppState>>,
    Query(query): Query<PaginationQuery>,
) -> AppResult<Json<ApiResponse<Vec<Resource>>>> {
    let start = std::time::Instant::now();

    let (items, total) = if let Some(resource_type) = &query.resource_type {
        state.resource_repo.find_by_type(resource_type, query.page, query.page_size).await?
    } else {
        state.resource_repo.list(query.page, query.page_size).await?
    };

    let pagination = PaginationInfo::new(query.page, query.page_size, total);
    state.metrics.record_success(start.elapsed().as_millis() as u64);

    Ok(Json(ApiResponse::success_with_pagination(items, pagination)))
}

pub async fn delete_resource(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> AppResult<Json<ApiResponse<Value>>> {
    let ctx = RequestContext::new_with_random();

    state.resource_repo.find_by_id(&id).await?
        .ok_or_else(|| crate::common::error::AppError::NotFound(format!("资源不存在: {}", id)))?;

    state.resource_repo.delete(&id).await?;

    let event = DomainEvent::new(
        "resource.deleted",
        &id,
        serde_json::json!({ "id": id }),
        &ctx.trace_id,
    );
    state.event_publisher.publish(event).await?;

    state.audit_logger.log_operation(
        &ctx,
        "resource.delete",
        "resource",
        &id,
        true,
        serde_json::json!({}),
    );

    Ok(Json(ApiResponse::success(serde_json::json!({ "status": "deleted" }))))
}

pub async fn batch_operations(
    State(state): State<Arc<AppState>>,
    Json(req): Json<BatchRequest>,
) -> AppResult<Json<ApiResponse<BatchResponseData>>> {
    let ctx = RequestContext::new_with_random();
    let start = std::time::Instant::now();

    let batch_id = format!("batch_{}", Uuid::new_v4().simple());
    let mut results = Vec::new();

    for op in req.operations {
        let result = match op.action.as_str() {
            "restart" => {
                handle_restart(&state, &op.id).await
            }
            "stop" => {
                handle_stop(&state, &op.id).await
            }
            "start" => {
                handle_start(&state, &op.id).await
            }
            "delete" => {
                handle_batch_delete(&state, &op.id).await
            }
            _ => {
                BatchResultItem {
                    id: op.id.clone(),
                    success: false,
                    status: None,
                    error: Some(format!("不支持的操作: {}", op.action)),
                }
            }
        };
        results.push(result);
    }

    let event = DomainEvent::new(
        "batch.operation.completed",
        &batch_id,
        serde_json::json!({
            "batch_id": batch_id,
            "operations_count": results.len(),
            "success_count": results.iter().filter(|r| r.success).count(),
            "failure_count": results.iter().filter(|r| !r.success).count(),
        }),
        &ctx.trace_id,
    );
    state.event_publisher.publish(event).await?;

    state.audit_logger.log_operation(
        &ctx,
        "resource.batch",
        "resource",
        &batch_id,
        true,
        serde_json::json!({ "operations": results.len() }),
    );

    state.metrics.record_success(start.elapsed().as_millis() as u64);

    Ok(Json(ApiResponse::success(BatchResponseData {
        batch_id,
        results,
    })))
}

async fn handle_restart(state: &Arc<AppState>, id: &str) -> BatchResultItem {
    match state.resource_repo.find_by_id(id).await {
        Ok(Some(_)) => {
            match state.resource_repo.update_status(id, ResourceStatus::Provisioning).await {
                Ok(_) => {
                    let _ = state.event_publisher.publish(DomainEvent::new(
                        "resource.restarted",
                        id,
                        serde_json::json!({ "id": id }),
                        "batch",
                    )).await;
                    BatchResultItem {
                        id: id.to_string(),
                        success: true,
                        status: Some("restarting".into()),
                        error: None,
                    }
                }
                Err(e) => BatchResultItem {
                    id: id.to_string(),
                    success: false,
                    status: None,
                    error: Some(e.to_string()),
                }
            }
        }
        Ok(None) => BatchResultItem {
            id: id.to_string(),
            success: false,
            status: None,
            error: Some("资源不存在".into()),
        },
        Err(e) => BatchResultItem {
            id: id.to_string(),
            success: false,
            status: None,
            error: Some(e.to_string()),
        },
    }
}

async fn handle_stop(state: &Arc<AppState>, id: &str) -> BatchResultItem {
    match state.resource_repo.find_by_id(id).await {
        Ok(Some(_)) => {
            match state.resource_repo.update_status(id, ResourceStatus::Stopped).await {
                Ok(_) => {
                    let _ = state.event_publisher.publish(DomainEvent::new(
                        "resource.stopped",
                        id,
                        serde_json::json!({ "id": id }),
                        "batch",
                    )).await;
                    BatchResultItem {
                        id: id.to_string(),
                        success: true,
                        status: Some("stopped".into()),
                        error: None,
                    }
                }
                Err(e) => BatchResultItem {
                    id: id.to_string(),
                    success: false,
                    status: None,
                    error: Some(e.to_string()),
                }
            }
        }
        Ok(None) => BatchResultItem {
            id: id.to_string(),
            success: false,
            status: None,
            error: Some("资源不存在".into()),
        },
        Err(e) => BatchResultItem {
            id: id.to_string(),
            success: false,
            status: None,
            error: Some(e.to_string()),
        },
    }
}

async fn handle_start(state: &Arc<AppState>, id: &str) -> BatchResultItem {
    match state.resource_repo.find_by_id(id).await {
        Ok(Some(_)) => {
            match state.resource_repo.update_status(id, ResourceStatus::Running).await {
                Ok(_) => {
                    let _ = state.event_publisher.publish(DomainEvent::new(
                        "resource.started",
                        id,
                        serde_json::json!({ "id": id }),
                        "batch",
                    )).await;
                    BatchResultItem {
                        id: id.to_string(),
                        success: true,
                        status: Some("running".into()),
                        error: None,
                    }
                }
                Err(e) => BatchResultItem {
                    id: id.to_string(),
                    success: false,
                    status: None,
                    error: Some(e.to_string()),
                }
            }
        }
        Ok(None) => BatchResultItem {
            id: id.to_string(),
            success: false,
            status: None,
            error: Some("资源不存在".into()),
        },
        Err(e) => BatchResultItem {
            id: id.to_string(),
            success: false,
            status: None,
            error: Some(e.to_string()),
        },
    }
}

async fn handle_batch_delete(state: &Arc<AppState>, id: &str) -> BatchResultItem {
    match state.resource_repo.find_by_id(id).await {
        Ok(Some(_)) => {
            match state.resource_repo.delete(id).await {
                Ok(_) => {
                    let _ = state.event_publisher.publish(DomainEvent::new(
                        "resource.deleted",
                        id,
                        serde_json::json!({ "id": id }),
                        "batch",
                    )).await;
                    BatchResultItem {
                        id: id.to_string(),
                        success: true,
                        status: Some("deleted".into()),
                        error: None,
                    }
                }
                Err(e) => BatchResultItem {
                    id: id.to_string(),
                    success: false,
                    status: None,
                    error: Some(e.to_string()),
                }
            }
        }
        Ok(None) => BatchResultItem {
            id: id.to_string(),
            success: false,
            status: None,
            error: Some("资源不存在".into()),
        },
        Err(e) => BatchResultItem {
            id: id.to_string(),
            success: false,
            status: None,
            error: Some(e.to_string()),
        },
    }
}

pub fn routes(state: Arc<AppState>) -> axum::Router {
    axum::Router::new()
        .route("/resources", axum::routing::post(create_resource).get(list_resources))
        .route("/resources/:id/status", axum::routing::get(get_resource_status))
        .route("/resources/:id", axum::routing::delete(delete_resource))
        .route("/resources/batch", axum::routing::post(batch_operations))
        .with_state(state)
}
