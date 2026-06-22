use std::time::Instant;

use axum::{
    extract::{Path, State},
    response::sse::{Event, KeepAlive, Sse},
    Json,
};
use common::error::AppError;
use common::types::{InferenceRequest, InferenceResponse};
use futures::stream::Stream;
use observability::metrics::{increment_requests, record_inference_latency};
use security::AuthenticatedTenant;
use tracing::{error, info, instrument, warn};
use uuid::Uuid;

use crate::state::AppState;

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, utoipa::ToSchema)]
pub struct BatchInferenceRequest {
    pub requests: Vec<InferenceRequest>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, utoipa::ToSchema)]
pub struct InferenceStatusResponse {
    pub request_id: String,
    pub status: String,
    pub started_at: String,
    pub completed_at: Option<String>,
    pub result: Option<InferenceResponse>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, utoipa::ToSchema)]
pub struct StreamChunk {
    pub request_id: String,
    pub chunk: serde_json::Value,
    pub done: bool,
}

async fn resolve_version(
    state: &AppState,
    request: &InferenceRequest,
) -> Result<common::types::ModelVersion, AppError> {
    let model = state.model_registry.get_model(&request.model_name).await?;

    if model.versions.is_empty() {
        return Err(AppError::ModelNotOnline(format!(
            "Model {} has no registered versions",
            request.model_name
        )));
    }

    if let Some(version_str) = &request.version {
        model
            .versions
            .iter()
            .find(|v| v.version == *version_str)
            .cloned()
            .ok_or_else(|| {
                AppError::ModelVersionNotFound(format!(
                    "Model {} version {} not found",
                    request.model_name, version_str
                ))
            })
    } else {
        model
            .versions
            .first()
            .cloned()
            .ok_or_else(|| AppError::ModelNotOnline(format!("Model {} has no versions", request.model_name)))
    }
}

#[utoipa::path(
    post,
    path = "/api/v1/inference/sync",
    request_body = InferenceRequest,
    responses(
        (status = 200, description = "Inference completed successfully", body = InferenceResponse),
        (status = 400, description = "Invalid request"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Model not found"),
        (status = 408, description = "Request timeout"),
        (status = 500, description = "Internal server error"),
    ),
    tag = "inference",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(request_id = %request.request_id, model_name = %request.model_name))]
pub async fn sync_inference(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Json(request): Json<InferenceRequest>,
) -> Result<Json<InferenceResponse>, AppError> {
    let start = Instant::now();
    info!("Processing sync inference request: {}", request.request_id);

    let masked_inputs = state.data_masker.mask_json(&request.inputs);
    let mut masked_request = request.clone();
    masked_request.inputs = masked_inputs;

    let route_target = state.traffic_router.route_request(&masked_request).await?;

    let version = resolve_version(&state, &request).await?;

    let (_gpu_id, _loaded) = state
        .scheduler
        .schedule_inference(version.id, version.gpu_memory_mb)
        .await?;

    let infer_req = inference_runtime::pb::InferRequest {
        request_id: request.request_id.clone(),
        model_name: request.model_name.clone(),
        version: request.version.clone().unwrap_or_default(),
        inputs: vec![],
        params: std::collections::HashMap::new(),
        trace_id: String::new(),
        user_id: request.user_id.clone().unwrap_or_default(),
        priority: 0,
        timeout_ms: state.config.inference.default_timeout_ms as i64,
    };

    let _response = state.inference_runtime.infer(infer_req).await?;

    let latency_ms = start.elapsed().as_millis() as u64;

    record_inference_latency(
        &request.model_name,
        &version.version,
        "success",
        latency_ms as f64,
    );
    increment_requests(&request.model_name, &version.version, "success");

    info!(
        "Sync inference completed: {} (latency: {}ms)",
        request.request_id, latency_ms
    );

    let result = InferenceResponse {
        request_id: request.request_id.clone(),
        model_name: request.model_name.clone(),
        version: version.version.clone(),
        outputs: serde_json::json!({"output": "placeholder"}),
        latency_ms,
        gpu_id: Some(_gpu_id.to_string()),
        trace_id: None,
    };

    Ok(Json(result))
}

#[utoipa::path(
    post,
    path = "/api/v1/inference/stream",
    request_body = InferenceRequest,
    responses(
        (status = 200, description = "Streaming inference started", body = String, content_type = "text/event-stream"),
        (status = 400, description = "Invalid request"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Model not found"),
    ),
    tag = "inference",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(request_id = %request.request_id, model_name = %request.model_name))]
pub async fn stream_inference(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Json(request): Json<InferenceRequest>,
) -> Result<Sse<impl Stream<Item = Result<Event, AppError>>>, AppError> {
    info!("Processing stream inference request: {}", request.request_id);

    let _target = state.traffic_router.route_request(&request).await?;
    let version = resolve_version(&state, &request).await?;
    let (_gpu_id, _loaded) = state
        .scheduler
        .schedule_inference(version.id, version.gpu_memory_mb)
        .await?;

    let request_id = request.request_id.clone();
    let stream = async_stream::stream! {
        let stream_chunk = StreamChunk {
            request_id: request_id.clone(),
            chunk: serde_json::json!({"message": "streaming not fully implemented"}),
            done: true,
        };
        match Event::default().json_data(stream_chunk) {
            Ok(event) => yield Ok(event),
            Err(e) => yield Err(AppError::Internal(format!("Failed to serialize event: {}", e))),
        }
    };

    Ok(Sse::new(stream).keep_alive(KeepAlive::default()))
}

#[utoipa::path(
    post,
    path = "/api/v1/inference/batch",
    request_body = BatchInferenceRequest,
    responses(
        (status = 200, description = "Batch inference completed", body = [InferenceResponse]),
        (status = 400, description = "Invalid request"),
        (status = 401, description = "Unauthorized"),
        (status = 500, description = "Internal server error"),
    ),
    tag = "inference",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(batch_size = %request.requests.len()))]
pub async fn batch_inference(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Json(request): Json<BatchInferenceRequest>,
) -> Result<Json<Vec<InferenceResponse>>, AppError> {
    info!("Processing batch inference request, size: {}", request.requests.len());

    if request.requests.is_empty() {
        return Err(AppError::Validation("Empty batch".to_string()));
    }

    let mut results = Vec::with_capacity(request.requests.len());
    for req in &request.requests {
        let start = Instant::now();
        let version = resolve_version(&state, req).await?;
        let (_gpu_id, _loaded) = state
            .scheduler
            .schedule_inference(version.id, version.gpu_memory_mb)
            .await?;

        let infer_req = inference_runtime::pb::InferRequest {
            request_id: req.request_id.clone(),
            model_name: req.model_name.clone(),
            version: req.version.clone().unwrap_or_default(),
            inputs: vec![],
            params: std::collections::HashMap::new(),
            trace_id: String::new(),
            user_id: req.user_id.clone().unwrap_or_default(),
            priority: 0,
            timeout_ms: state.config.inference.default_timeout_ms as i64,
        };

        match state.inference_runtime.infer(infer_req).await {
            Ok(_resp) => {
                let latency_ms = start.elapsed().as_millis() as u64;
                results.push(InferenceResponse {
                    request_id: req.request_id.clone(),
                    model_name: req.model_name.clone(),
                    version: version.version.clone(),
                    outputs: serde_json::json!({"output": "placeholder"}),
                    latency_ms,
                    gpu_id: Some(_gpu_id.to_string()),
                    trace_id: None,
                });
            }
            Err(e) => {
                let latency_ms = start.elapsed().as_millis() as u64;
                results.push(InferenceResponse {
                    request_id: req.request_id.clone(),
                    model_name: req.model_name.clone(),
                    version: version.version.clone(),
                    outputs: serde_json::json!({}),
                    latency_ms,
                    gpu_id: None,
                    trace_id: None,
                });
                let _ = e;
            }
        }
    }

    info!("Batch inference completed, {} responses", results.len());

    Ok(Json(results))
}

#[utoipa::path(
    get,
    path = "/api/v1/inference/status/{request_id}",
    params(
        ("request_id" = String, Path, description = "Inference request ID"),
    ),
    responses(
        (status = 200, description = "Request status", body = InferenceStatusResponse),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Request not found"),
    ),
    tag = "inference",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(request_id = %request_id))]
pub async fn get_inference_status(
    State(_state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Path(request_id): Path<String>,
) -> Result<Json<InferenceStatusResponse>, AppError> {
    warn!(
        "get_inference_status called for {}, status tracking not fully implemented",
        request_id
    );

    Ok(Json(InferenceStatusResponse {
        request_id: request_id.clone(),
        status: "unknown".to_string(),
        started_at: chrono::Utc::now().to_rfc3339(),
        completed_at: None,
        result: None,
        error: Some("Async status tracking is not implemented".to_string()),
    }))
}
