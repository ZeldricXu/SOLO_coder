use std::time::Instant;

use axum::{
    extract::{Path, State},
    response::sse::{Event, KeepAlive, Sse},
    Json,
};
use common::error::AppError;
use common::types::{InferenceRequest, InferenceResponse, ModelVersion, Tenant};
use futures::stream::Stream;
use observability::metrics::{increment_requests, record_inference_latency};
use tokio_stream::StreamExt;
use tracing::{error, info, instrument, warn};
use utoipa::ToSchema;
use uuid::Uuid;

use crate::state::AppState;

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, ToSchema)]
pub struct BatchInferenceRequest {
    pub requests: Vec<InferenceRequest>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, ToSchema)]
pub struct InferenceStatusResponse {
    pub request_id: String,
    pub status: String,
    pub started_at: String,
    pub completed_at: Option<String>,
    pub result: Option<InferenceResponse>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize, ToSchema)]
pub struct StreamChunk {
    pub request_id: String,
    pub chunk: serde_json::Value,
    pub done: bool,
}

async fn resolve_version(
    state: &AppState,
    request: &InferenceRequest,
) -> Result<ModelVersion, AppError> {
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
    _tenant: Tenant,
    Json(request): Json<InferenceRequest>,
) -> Result<Json<InferenceResponse>, AppError> {
    let start = Instant::now();
    info!("Processing sync inference request: {}", request.request_id);

    let masked_inputs = state.data_masker.mask_json(&request.inputs);
    let mut masked_request = request.clone();
    masked_requests.inputs = masked_inputs;

    let (experiment_info, route_target) = if let Some(user_id) = &request.user_id {
        if let Some((exp_id, target)) = state
            .experiment_service
            .assign_group(&request.model_name, user_id)
            .await
        {
            let group_name = state
                .experiment_service
                .get_assigned_group(exp_id, user_id)
                .unwrap_or_else(|| "unknown".to_string());
            (Some((exp_id, group_name)), Some(target))
        } else {
            (None, None)
        }
    } else {
        (None, None)
    };

    let target = match route_target {
        Some(t) => t,
        None => state.traffic_router.route_request(&masked_request).await?,
    };

    let version = resolve_version(&state, &request).await?;

    let gpu_id = state.scheduler.schedule_for_inference(&version).await?;

    let timeout_ms = state.config.inference.default_timeout_ms;

    let response = state
        .inference_runtime
        .infer(masked_request, target.model_version_id, timeout_ms)
        .await?;

    let masked_outputs = state.data_masker.mask_json(&response.outputs);
    let mut final_response = response.clone();
    final_response.outputs = masked_outputs;

    let elapsed = start.elapsed();
    let latency_ms = elapsed.as_millis() as u64;
    final_response.latency_ms = latency_ms;

    if let Some((exp_id, group_name)) = experiment_info {
        if let Some(user_id) = &request.user_id {
            state
                .experiment_service
                .record_observation(
                    exp_id,
                    group_name,
                    user_id.clone(),
                    request.request_id.clone(),
                    target.model_version_id,
                    latency_ms,
                    true,
                )
                .await;
        }
    }

    record_inference_latency(
        &request.model_name,
        &final_response.version,
        "success",
        latency_ms as f64,
    );
    increment_requests(&request.model_name, &final_response.version, "success");

    info!(
        "Sync inference completed: {} (latency: {}ms, gpu: {:?})",
        request.request_id, latency_ms, final_response.gpu_id
    );

    Ok(Json(final_response))
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
    _tenant: Tenant,
    Json(request): Json<InferenceRequest>,
) -> Result<Sse<impl Stream<Item = Result<Event, AppError>>>, AppError> {
    info!("Processing stream inference request: {}", request.request_id);

    let target = state.traffic_router.route_request(&request).await?;
    let version = resolve_version(&state, &request).await?;
    let _gpu_id = state.scheduler.schedule_for_inference(&version).await?;
    let timeout_ms = state.config.inference.default_timeout_ms;

    let mut rx = state
        .inference_runtime
        .infer_stream(request.clone(), target.model_version_id, timeout_ms)
        .await?;

    let request_id = request.request_id.clone();
    let stream = async_stream::stream! {
        let mut index = 0;
        loop {
            match rx.recv().await {
                Some(Ok(chunk)) => {
                    let done = chunk.get("done").and_then(|v| v.as_bool()).unwrap_or(false);
                    let stream_chunk = StreamChunk {
                        request_id: request_id.clone(),
                        chunk,
                        done,
                    };
                    match Event::default().json_data(stream_chunk) {
                        Ok(event) => yield Ok(event),
                        Err(e) => yield Err(AppError::Internal(format!("Failed to serialize event: {}", e))),
                    }
                    if done {
                        break;
                    }
                    index += 1;
                }
                Some(Err(e)) => {
                    yield Err(e);
                    break;
                }
                None => {
                    let stream_chunk = StreamChunk {
                        request_id: request_id.clone(),
                        chunk: serde_json::json!({"done": true}),
                        done: true,
                    };
                    if let Ok(event) = Event::default().json_data(stream_chunk) {
                        yield Ok(event);
                    }
                    break;
                }
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
    _tenant: Tenant,
    Json(request): Json<BatchInferenceRequest>,
) -> Result<Json<Vec<InferenceResponse>>, AppError> {
    info!("Processing batch inference request, size: {}", request.requests.len());

    if request.requests.is_empty() {
        return Err(AppError::Validation("Empty batch".to_string()));
    }

    let model_name = &request.requests[0].model_name;
    let first_req = &request.requests[0];
    let target = state.traffic_router.route_request(first_req).await?;
    let version = resolve_version(&state, first_req).await?;
    let _gpu_id = state.scheduler.schedule_for_inference(&version).await?;
    let timeout_ms = state.config.inference.default_timeout_ms;

    let results = state
        .inference_runtime
        .infer_batch(request.requests.clone(), target.model_version_id, timeout_ms)
        .await?;

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
    _tenant: Tenant,
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
