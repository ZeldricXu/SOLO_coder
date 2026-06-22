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

/// Convert a serde_json::Value to a protobuf Tensor.
/// Serializes the JSON as bytes and wraps it in a STRING-typed tensor.
fn json_to_pb_tensor(name: &str, value: &serde_json::Value) -> inference_runtime::pb::Tensor {
    let data_bytes = serde_json::to_vec(value).unwrap_or_default();
    inference_runtime::pb::Tensor {
        name: name.to_string(),
        dtype: inference_runtime::pb::DataType::String as i32,
        shape: vec![1],
        data_bytes: data_bytes.into(),
    }
}

/// Convert a protobuf Tensor output back to serde_json::Value.
/// Deserializes from the data_bytes field.
fn pb_tensor_to_json(tensor: &inference_runtime::pb::Tensor) -> serde_json::Value {
    serde_json::from_slice(&tensor.data_bytes).unwrap_or(serde_json::Value::Null)
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
    } else if let Some(percent) = state.rollout_manager.route_percent(&request.model_name) {
        let rollout = state.rollout_manager.get_rollout(&request.model_name);
        let use_new = rand::random::<f64>() * 100.0 < percent as f64;

        if let Some(rollout) = rollout {
            let target_id = if use_new { rollout.new_version_id } else { rollout.old_version_id };
            if let Some(v) = model.versions.iter().find(|v| v.id == target_id) {
                return Ok(v.clone());
            }
        }

        model
            .versions
            .first()
            .cloned()
            .ok_or_else(|| AppError::ModelNotOnline(format!("Model {} has no versions", request.model_name)))
    } else {
        model
            .versions
            .first()
            .cloned()
            .ok_or_else(|| AppError::ModelNotOnline(format!("Model {} has no versions", request.model_name)))
    }
}

/// Build an InferRequest from the incoming inference request, including proper input conversion
/// and trace_id propagation.
fn build_infer_request(
    request: &InferenceRequest,
    version: &common::types::ModelVersion,
    trace_id: &str,
    timeout_ms: u64,
) -> inference_runtime::pb::InferRequest {
    let input_tensor = json_to_pb_tensor("input", &request.inputs);

    inference_runtime::pb::InferRequest {
        request_id: request.request_id.clone(),
        model_name: request.model_name.clone(),
        version: version.version.clone(),
        inputs: vec![input_tensor],
        params: std::collections::HashMap::new(),
        trace_id: trace_id.to_string(),
        user_id: request.user_id.clone().unwrap_or_default(),
        priority: 0,
        timeout_ms: timeout_ms as i64,
    }
}

/// Build an InferenceResponse from the runtime's InferResponse, converting
/// proto tensors back to JSON.
fn build_inference_response(
    request_id: &str,
    model_name: &str,
    version: &str,
    infer_resp: &inference_runtime::pb::InferResponse,
    latency_ms: u64,
    gpu_id: usize,
) -> InferenceResponse {
    let outputs = infer_resp
        .outputs
        .first()
        .map(|t| pb_tensor_to_json(t))
        .unwrap_or(serde_json::json!({"output": "empty"}));

    InferenceResponse {
        request_id: request_id.to_string(),
        model_name: model_name.to_string(),
        version: version.to_string(),
        outputs,
        latency_ms,
        gpu_id: Some(gpu_id.to_string()),
        trace_id: if infer_resp.trace_id.is_empty() { None } else { Some(infer_resp.trace_id.clone()) },
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
    let trace_id = Uuid::new_v4().to_string();
    info!("Processing sync inference request: {} (trace: {})", request.request_id, trace_id);

    let masked_inputs = state.data_masker.mask_json(&request.inputs);
    let mut masked_request = request.clone();
    masked_request.inputs = masked_inputs;

    let route_target = state.traffic_router.route_request(&masked_request).await?;
    let version = resolve_version(&state, &request).await?;

    let load_state = state.dynamic_scheduler.get_load_state(version.id);
    if matches!(load_state, scheduler::LoadState::NotLoaded) {
        state.dynamic_scheduler.set_load_state(version.id, scheduler::LoadState::Loading { started_at: Instant::now() });
        let model_name = request.model_name.clone();
        let version_clone = version.clone();
        let runtime = state.inference_runtime.clone();
        let ds = state.dynamic_scheduler.clone();
        tokio::spawn(async move {
            match runtime.load_model(&version_clone, &model_name, None, None, None).await {
                Ok(loaded) => {
                    ds.register_model(
                        version_clone.id,
                        model_name,
                        version_clone.version,
                        version_clone.gpu_memory_mb,
                        loaded.gpu_id.unwrap_or(0) as usize,
                    );
                }
                Err(e) => {
                    warn!("Failed to preload model {}: {}", version_clone.id, e);
                    ds.set_load_state(version_clone.id, scheduler::LoadState::NotLoaded);
                }
            }
        });
    }

    let (gpu_id, _loaded) = state
        .scheduler
        .schedule_inference(version.id, version.gpu_memory_mb)
        .await?;

    let infer_req = build_infer_request(
        &request,
        &version,
        &trace_id,
        state.config.inference.default_timeout_ms as u64,
    );

    let infer_result = state.inference_runtime.infer(infer_req).await;
    let latency_ms = start.elapsed().as_millis() as u64;
    let is_error = infer_result.is_err();

    state.rollout_manager.record_metrics(&request.model_name, version.id, latency_ms, is_error);
    state.dynamic_scheduler.record_access(version.id);

    let infer_resp = infer_result?;

    record_inference_latency(
        &request.model_name,
        &version.version,
        "success",
        latency_ms as f64,
    );
    increment_requests(&request.model_name, &version.version, "success");

    let _ = state.experiment_recorder.record_metric(
        route_target.model_version_id,
        &route_target.model_version_id.to_string(),
        "inference_latency_ms",
        latency_ms as f64,
    ).await;

    let result = build_inference_response(
        &request.request_id,
        &request.model_name,
        &version.version,
        &infer_resp,
        latency_ms,
        gpu_id,
    );

    info!(
        "Sync inference completed: {} (latency: {}ms, gpu: {})",
        request.request_id, latency_ms, gpu_id
    );

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
    let trace_id = Uuid::new_v4().to_string();
    info!("Processing stream inference request: {} (trace: {})", request.request_id, trace_id);

    let _target = state.traffic_router.route_request(&request).await?;
    let version = resolve_version(&state, &request).await?;
    let (_gpu_id, _loaded) = state
        .scheduler
        .schedule_inference(version.id, version.gpu_memory_mb)
        .await?;

    let infer_req = build_infer_request(
        &request,
        &version,
        &trace_id,
        state.config.inference.default_timeout_ms as u64,
    );

    let request_id = request.request_id.clone();

    let stream = async_stream::stream! {
        // For now, submit a single inference and stream the result as one chunk.
        // Full token-streaming requires the runtime's stream_infer gRPC endpoint.
        match state.inference_runtime.infer(infer_req).await {
            Ok(infer_resp) => {
                let outputs = infer_resp.outputs.first()
                    .map(|t| pb_tensor_to_json(t))
                    .unwrap_or(serde_json::Value::Null);
                let chunk = StreamChunk {
                    request_id: request_id.clone(),
                    chunk: outputs,
                    done: true,
                };
                match Event::default().json_data(chunk) {
                    Ok(event) => yield Ok(event),
                    Err(e) => yield Err(AppError::Internal(format!("Failed to serialize event: {}", e))),
                }
            }
            Err(e) => {
                yield Err(e);
            }
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
        let trace_id = Uuid::new_v4().to_string();
        let version = resolve_version(&state, req).await?;
        let (gpu_id, _loaded) = state
            .scheduler
            .schedule_inference(version.id, version.gpu_memory_mb)
            .await?;

        let infer_req = build_infer_request(
            req,
            &version,
            &trace_id,
            state.config.inference.default_timeout_ms as u64,
        );

        match state.inference_runtime.infer(infer_req).await {
            Ok(infer_resp) => {
                let latency_ms = start.elapsed().as_millis() as u64;

                record_inference_latency(
                    &req.model_name,
                    &version.version,
                    "success",
                    latency_ms as f64,
                );
                increment_requests(&req.model_name, &version.version, "success");

                results.push(build_inference_response(
                    &req.request_id,
                    &req.model_name,
                    &version.version,
                    &infer_resp,
                    latency_ms,
                    gpu_id,
                ));
            }
            Err(e) => {
                let latency_ms = start.elapsed().as_millis() as u64;
                increment_requests(&req.model_name, &version.version, "error");
                results.push(InferenceResponse {
                    request_id: req.request_id.clone(),
                    model_name: req.model_name.clone(),
                    version: version.version.clone(),
                    outputs: serde_json::json!({"error": e.to_string()}),
                    latency_ms,
                    gpu_id: None,
                    trace_id: Some(trace_id),
                });
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

#[derive(Debug, Clone, serde::Serialize, utoipa::ToSchema)]
pub struct PipelineExecuteResponse {
    pub request_id: String,
    pub pipeline_name: String,
    pub output: serde_json::Value,
    pub total_latency_ms: u64,
    pub success: bool,
}

#[utoipa::path(
    post,
    path = "/api/v1/pipelines/{pipeline_name}/execute",
    params(
        ("pipeline_name" = String, Path, description = "Pipeline name"),
    ),
    request_body = InferenceRequest,
    responses(
        (status = 200, description = "Pipeline executed successfully", body = PipelineExecuteResponse),
        (status = 400, description = "Invalid request"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Pipeline not found"),
        (status = 500, description = "Internal server error"),
    ),
    tag = "inference",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(pipeline_name = %pipeline_name, request_id = %request.request_id))]
pub async fn execute_pipeline(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Path(pipeline_name): Path<String>,
    Json(request): Json<InferenceRequest>,
) -> Result<Json<PipelineExecuteResponse>, AppError> {
    info!("Executing pipeline: {}", pipeline_name);

    let pipeline = state
        .pipelines
        .get(&pipeline_name)
        .ok_or_else(|| AppError::Validation(format!("Pipeline not found: {}", pipeline_name)))?;

    let result = pipeline.execute(&request).await?;

    info!(
        "Pipeline '{}' executed successfully: {}ms",
        pipeline_name, result.total_latency_ms
    );

    Ok(Json(PipelineExecuteResponse {
        request_id: result.request_id,
        pipeline_name,
        output: result.final_output,
        total_latency_ms: result.total_latency_ms,
        success: result.success,
    }))
}
