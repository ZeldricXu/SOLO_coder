use std::sync::Arc;
use axum::{
    extract::{Path, State, Query},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde::{Deserialize, Serialize};
use tracing::debug;
use crate::models::*;
use crate::feature_store::*;
use crate::model_registry::*;
use crate::inference_gateway::*;
use crate::adversarial::*;
use crate::prompt_experiment::*;
use crate::gpu_scheduler::*;
use crate::document_pipeline::*;
use crate::evaluation_dashboard::*;

#[derive(Clone)]
pub struct AppState {
    pub feature_store: Arc<FeatureStoreService>,
    pub model_registry: Arc<ModelRegistryService>,
    pub inference_gateway: Arc<InferenceGatewayService>,
    pub adversarial_service: Arc<AdversarialService>,
    pub prompt_service: Arc<PromptExperimentService>,
    pub gpu_scheduler: Arc<GpuSchedulerService>,
    pub document_pipeline: Arc<DocumentPipelineService>,
    pub evaluation_dashboard: Arc<EvaluationDashboardService>,
}

#[derive(Debug, Deserialize)]
pub struct CreateResourceRequest {
    pub r#type: String,
    pub config: serde_json::Value,
    pub labels: Option<serde_json::Value>,
}

#[derive(Debug, Deserialize)]
pub struct BatchOperationRequest {
    pub operations: Vec<BatchOperation>,
}

#[derive(Debug, Deserialize)]
pub struct BatchOperation {
    pub action: String,
    pub id: String,
}

async fn handle_error<T>(err: ModelGuardError) -> (StatusCode, Json<ApiResponse<T>>) {
    let status_code = err.status_code();
    let status = StatusCode::from_u16(status_code).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);
    let response = ApiResponse {
        code: status.as_u16(),
        message: Some(err.to_string()),
        data: None,
    };
    (status, Json(response))
}

pub async fn create_resource(
    State(state): State<AppState>,
    Json(req): Json<CreateResourceRequest>,
) -> impl IntoResponse {
    let trace_id = uuid::Uuid::new_v4().to_string();
    debug!(trace_id = %trace_id, resource_type = %req.r#type, "Creating resource");

    let result = match req.r#type.as_str() {
        "gpu_task" => {
            let task: Result<GpuTask> = serde_json::from_value(req.config)
                .map_err(|e| ModelGuardError::ValidationError(e.to_string()));
            match task {
                Ok(t) => state.gpu_scheduler.submit_task(t)
                    .map(|id| serde_json::json!({"id": id, "status": "provisioning"})),
                Err(e) => Err(e),
            }
        }
        "model" => {
            let name = match req.config.get("name")
                .and_then(|v| v.as_str())
                .ok_or_else(|| ModelGuardError::ValidationError("name required".to_string())) {
                Ok(name) => name,
                Err(e) => return handle_error(e).await,
            };
            let request = ModelRegistrationRequest {
                name: name.to_string(),
                description: None,
                metadata: None,
            };
            state.model_registry.register_model(request).await
                .map(|m| serde_json::json!({"id": m.model_id, "status": "created"}))
        }
        "document" => {
            let filename = match req.config.get("filename")
                .and_then(|v| v.as_str())
                .ok_or_else(|| ModelGuardError::ValidationError("filename required".to_string())) {
                Ok(filename) => filename,
                Err(e) => return handle_error(e).await,
            };
            let content = match req.config.get("content")
                .and_then(|v| v.as_str())
                .ok_or_else(|| ModelGuardError::ValidationError("content required".to_string())) {
                Ok(content) => content,
                Err(e) => return handle_error(e).await,
            };
            let config = req.config.get("pipeline_config")
                .and_then(|v| serde_json::from_value(v.clone()).ok());
            state.document_pipeline.parse_document(filename, content.as_bytes(), config).await
                .map(|r| serde_json::json!({"id": r.document.document_id, "status": "processed"}))
        }
        _ => Err(ModelGuardError::ValidationError(format!("Unknown resource type: {}", req.r#type))),
    };

    match result {
        Ok(data) => {
            let response = ApiResponse {
                code: 201,
                message: None,
                data: Some(ResourceResponse {
                    id: data.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    status: data.get("status").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    resource_type: None,
                }),
            };
            (StatusCode::CREATED, Json(response))
        }
        Err(e) => {
            handle_error::<ResourceResponse>(e).await
        }
    }
}

pub async fn get_resource_status(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Query(params): Query<std::collections::HashMap<String, String>>,
) -> impl IntoResponse {
    let resource_type = params.get("type").cloned().unwrap_or_default();
    
    let result = match resource_type.as_str() {
        "gpu_task" => {
            state.gpu_scheduler.get_task(&id)
                .map(|t| StatusResponse {
                    id: t.task_id,
                    status: format!("{:?}", t.status).to_lowercase(),
                    progress: 0.0,
                    phase: None,
                    error_detail: None,
                })
        }
        "model" => {
            state.model_registry.get_model(&id).await
                .map(|m| StatusResponse {
                    id: m.model_id,
                    status: "active".to_string(),
                    progress: 1.0,
                    phase: None,
                    error_detail: None,
                })
        }
        "document" => {
            state.document_pipeline.get_pipeline_result(&id)
                .map(|r| StatusResponse {
                    id: r.document.document_id,
                    status: "completed".to_string(),
                    progress: 1.0,
                    phase: None,
                    error_detail: None,
                })
        }
        _ => Err(ModelGuardError::ValidationError(format!("Unknown resource type: {}", resource_type))),
    };

    match result {
        Ok(data) => (
            StatusCode::OK,
            Json(ApiResponse {
                code: 200,
                message: None,
                data: Some(data),
            }),
        ),
        Err(e) => handle_error(e).await,
    }
}

pub async fn batch_operations(
    State(state): State<AppState>,
    Json(req): Json<BatchOperationRequest>,
) -> impl IntoResponse {
    let batch_id = uuid::Uuid::new_v4().to_string();
    let mut results = Vec::new();

    for op in req.operations {
        let result = match op.action.as_str() {
            "stop_gpu_task" => {
                state.gpu_scheduler.cancel_task(&op.id)
                    .map(|_| BatchResult::success(&op.id))
            }
            _ => Err(ModelGuardError::ValidationError(format!("Unknown action: {}", op.action))),
        };
        
        results.push(match result {
            Ok(r) => r,
            Err(e) => BatchResult::failure(&op.id, e.to_string()),
        });
    }

    (
        StatusCode::OK,
        Json(ApiResponse {
            code: 200,
            message: None,
            data: Some(BatchResponse {
                batch_id,
                results,
            }),
        }),
    )
}

pub async fn health_check() -> impl IntoResponse {
    Json(ApiResponse {
        code: 200,
        message: Some("OK".to_string()),
        data: Some(serde_json::json!({"status": "healthy"})),
    })
}

pub async fn get_stats(
    State(state): State<AppState>,
) -> impl IntoResponse {
    let gpu_stats = state.gpu_scheduler.get_stats();
    let pipeline_stats = state.document_pipeline.get_stats();
    let dashboard_stats = state.evaluation_dashboard.get_stats();

    Json(ApiResponse {
        code: 200,
        message: None,
        data: Some(serde_json::json!({
            "gpu_scheduler": gpu_stats,
            "document_pipeline": pipeline_stats,
            "evaluation_dashboard": dashboard_stats,
        })),
    })
}
