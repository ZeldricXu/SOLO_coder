use crate::core::CoreProcessor;
use crate::data_access::DataAccessLayer;
use crate::event_store::EventStore;
use crate::fault_injection::FaultInjectionOrchestrator;
use crate::gateway::{ApiGateway, AuthContext, AuthCredentials, RateLimitConfig};
use crate::metrics::MetricsCollector;
use crate::scheduler::TaskScheduler;
use crate::sidecar::SidecarManager;
use crate::traffic_control::TrafficController;
use crate::types::{
    AppError, Config, Entity, MetricsSnapshot, ResourceCreateRequest, ResourceStatusResponse,
    RunInstance, StandardizedData, TransformRequest, TransformResponse,
};
use axum::{
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Json, Response},
    routing::{get, post},
    Router,
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashMap;
use std::sync::Arc;
use uuid::Uuid;

#[derive(Clone)]
pub struct AppState {
    pub data_access: Arc<DataAccessLayer>,
    pub event_store: Arc<EventStore>,
    pub core_processor: Arc<CoreProcessor>,
    pub fault_injector: Arc<FaultInjectionOrchestrator>,
    pub traffic_controller: Arc<TrafficController>,
    pub metrics: Arc<MetricsCollector>,
    pub sidecar_manager: Arc<SidecarManager>,
    pub scheduler: Arc<TaskScheduler>,
    pub gateway: Arc<ApiGateway>,
}

#[derive(Debug, Deserialize)]
pub struct PaginationParams {
    pub limit: Option<u64>,
    pub offset: Option<u64>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ApiResponse<T> {
    pub code: u16,
    pub data: Option<T>,
    pub message: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub trace_id: Option<String>,
}

impl<T> ApiResponse<T> {
    pub fn success(data: T) -> Self {
        Self {
            code: 200,
            data: Some(data),
            message: None,
            trace_id: Some(Uuid::new_v4().to_string()),
        }
    }

    pub fn created(data: T) -> Self {
        Self {
            code: 201,
            data: Some(data),
            message: None,
            trace_id: Some(Uuid::new_v4().to_string()),
        }
    }

    pub fn error(code: u16, message: &str) -> Self {
        Self {
            code,
            data: None,
            message: Some(message.to_string()),
            trace_id: Some(Uuid::new_v4().to_string()),
        }
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            AppError::NotFound(msg) => (StatusCode::NOT_FOUND, msg),
            AppError::Conflict(msg) => (StatusCode::CONFLICT, msg),
            AppError::ValidationError(msg) => (StatusCode::BAD_REQUEST, msg),
            AppError::Unauthorized(msg) => (StatusCode::UNAUTHORIZED, msg),
            AppError::Forbidden(msg) => (StatusCode::FORBIDDEN, msg),
            AppError::TimeoutError => (StatusCode::GATEWAY_TIMEOUT, "上游服务响应超时".to_string()),
            AppError::RateLimited => (StatusCode::TOO_MANY_REQUESTS, "请求过于频繁".to_string()),
            AppError::InternalError(msg) => (StatusCode::INTERNAL_SERVER_ERROR, msg),
            _ => (StatusCode::INTERNAL_SERVER_ERROR, "未知错误".to_string()),
        };

        let body = ApiResponse::<()>::error(status.as_u16(), &message);
        (status, Json(body)).into_response()
    }
}

async fn extract_auth(
    headers: &HeaderMap,
    gateway: &ApiGateway,
) -> Result<AuthContext, AppError> {
    let auth_header = headers
        .get("Authorization")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");

    let api_key_header = headers
        .get("X-API-Key")
        .and_then(|v| v.to_str().ok());

    let client_id = headers
        .get("X-Client-ID")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("anonymous");

    gateway.check_rate_limit(client_id)?;
    gateway.authenticate(auth_header, api_key_header)
}

pub async fn create_resource(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<ResourceCreateRequest>,
) -> Result<Response, AppError> {
    let auth_ctx = extract_auth(&headers, &state.gateway).await?;
    state.gateway.authorize(&auth_ctx, "resource:create")?;

    let entity = state
        .data_access
        .create_entity(&request.r#type, request.config, request.labels)
        .await?;

    let event_data = serde_json::to_value(&entity)?;
    state
        .event_store
        .append_event(
            &entity.id,
            "resource.created",
            event_data,
            Some(auth_ctx.user_id.unwrap_or_default()),
        )
        .await?;

    state.metrics.increment_counter("resources_created", 1);

    let response = json!({
        "id": entity.id,
        "status": entity.status
    });

    Ok((StatusCode::CREATED, Json(ApiResponse::created(response))).into_response())
}

pub async fn get_resource_status(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<ApiResponse<ResourceStatusResponse>>, AppError> {
    let auth_ctx = extract_auth(&headers, &state.gateway).await?;
    state.gateway.authorize(&auth_ctx, "resource:read")?;

    let entity = state.data_access.get_entity(&id).await?;
    let runs = state.data_access.get_runs_by_entity(&id, 10, 0).await?;

    let progress = runs.first().map(|r| r.progress).unwrap_or(0.0);

    let response = ResourceStatusResponse {
        id: entity.id,
        status: entity.status,
        progress,
        updated_at: entity.updated_at,
    };

    Ok(Json(ApiResponse::success(response)))
}

pub async fn batch_operations(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<BatchOperationRequest>,
) -> Result<Json<ApiResponse<BatchOperationResponse>>, AppError> {
    let auth_ctx = extract_auth(&headers, &state.gateway).await?;
    state.gateway.authorize(&auth_ctx, "resource:update")?;

    let batch_id = crate::types::generate_id("batch");
    let mut results = Vec::new();

    for op in &request.operations {
        let result = match op.action.as_str() {
            "restart" => {
                match state.data_access.update_entity_status(&op.id, "restarting").await {
                    Ok(_) => {
                        let event_data = json!({"action": "restart", "id": op.id});
                        let _ = state
                            .event_store
                            .append_event(
                                &op.id,
                                "resource.batch_action",
                                event_data,
                                Some(auth_ctx.user_id.clone().unwrap_or_default()),
                            )
                            .await;
                        BatchResult {
                            id: op.id.clone(),
                            success: true,
                            message: Some("重启成功".to_string()),
                        }
                    }
                    Err(e) => BatchResult {
                        id: op.id.clone(),
                        success: false,
                        message: Some(e.to_string()),
                    },
                }
            }
            "delete" => match state.data_access.delete_entity(&op.id).await {
                Ok(_) => BatchResult {
                    id: op.id.clone(),
                    success: true,
                    message: Some("删除成功".to_string()),
                },
                Err(e) => BatchResult {
                    id: op.id.clone(),
                    success: false,
                    message: Some(e.to_string()),
                },
            },
            _ => BatchResult {
                id: op.id.clone(),
                success: false,
                message: Some(format!("未知操作: {}", op.action)),
            },
        };
        results.push(result);
    }

    let response = BatchOperationResponse {
        batch_id,
        results,
    };

    Ok(Json(ApiResponse::success(response)))
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

#[derive(Debug, Serialize)]
pub struct BatchOperationResponse {
    pub batch_id: String,
    pub results: Vec<BatchResult>,
}

#[derive(Debug, Serialize)]
pub struct BatchResult {
    pub id: String,
    pub success: bool,
    pub message: Option<String>,
}

pub async fn transform_data(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<TransformRequest>,
) -> Result<Json<ApiResponse<TransformResponse>>, AppError> {
    let auth_ctx = extract_auth(&headers, &state.gateway).await?;
    state.gateway.authorize(&auth_ctx, "resource:create")?;

    let trace_id = Uuid::new_v4().to_string();

    let traffic_decision = state
        .traffic_controller
        .route_request(
            &trace_id,
            request.source.as_deref().unwrap_or("default"),
            &request.data,
        )
        .await;

    if traffic_decision.should_mirror {
        let mirror_data = request.data.clone();
        let mirror_store = state.event_store.clone();
        let mirror_trace_id = trace_id.clone();
        tokio::spawn(async move {
            let event_data = json!({"data": mirror_data, "source": "mirror"});
            let _ = mirror_store
                .append_event(&mirror_trace_id, "traffic.mirrored", event_data, None)
                .await;
        });
    }

    let processed = state
        .traffic_controller
        .process_with_circuit_breaker(
            &trace_id,
            async {
                state
                    .core_processor
                    .process_request(request.data, request.config.unwrap_or_default())
                    .await
            },
        )
        .await?;

    state
        .event_store
        .append_event(
            &trace_id,
            "data.transformed",
            serde_json::to_value(&processed)?,
            auth_ctx.user_id,
        )
        .await?;

    state
        .metrics
        .record_histogram("transform_latency_ms", processed.processing_time_ms as f64);
    state
        .metrics
        .increment_counter("transformations_total", 1);

    let response = TransformResponse {
        success: true,
        data: processed.data,
        metadata: processed.metadata,
        processing_time_ms: processed.processing_time_ms,
        transformations_applied: processed.transformations_applied,
    };

    Ok(Json(ApiResponse::success(response)))
}

pub async fn get_metrics(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(params): Query<PaginationParams>,
) -> Result<Json<ApiResponse<MetricsQueryResponse>>, AppError> {
    let auth_ctx = extract_auth(&headers, &state.gateway).await?;
    state.gateway.authorize_role(&auth_ctx, "admin")?;

    let counters = state.metrics.get_all_counters();
    let gauges = state.metrics.get_all_gauges();
    let histograms = state.metrics.get_all_histograms();

    let response = MetricsQueryResponse {
        total_counters: counters.len(),
        total_gauges: gauges.len(),
        total_histograms: histograms.len(),
        counters,
        gauges,
        histograms,
        prometheus_export: state.metrics.export_prometheus(),
    };

    Ok(Json(ApiResponse::success(response)))
}

#[derive(Debug, Serialize)]
pub struct MetricsQueryResponse {
    pub total_counters: usize,
    pub total_gauges: usize,
    pub total_histograms: usize,
    pub counters: HashMap<String, u64>,
    pub gauges: HashMap<String, f64>,
    pub histograms: HashMap<String, Vec<f64>>,
    pub prometheus_export: String,
}

pub async fn get_prometheus_metrics(
    State(state): State<AppState>,
) -> Response {
    let metrics = state.metrics.export_prometheus();
    (
        StatusCode::OK,
        [("Content-Type", "text/plain; version=0.0.4; charset=utf-8")],
        metrics,
    )
        .into_response()
}

pub async fn list_resources(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(params): Query<PaginationParams>,
) -> Result<Json<ApiResponse<ListResponse<Entity>>>, AppError> {
    let auth_ctx = extract_auth(&headers, &state.gateway).await?;
    state.gateway.authorize(&auth_ctx, "resource:read")?;

    let limit = params.limit.unwrap_or(50);
    let offset = params.offset.unwrap_or(0);

    let entities = state.data_access.list_entities(limit, offset).await?;
    let total = state.data_access.count_entities().await?;

    let response = ListResponse {
        items: entities,
        total,
        limit,
        offset,
    };

    Ok(Json(ApiResponse::success(response)))
}

pub async fn list_runs(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(entity_id): Path<String>,
    Query(params): Query<PaginationParams>,
) -> Result<Json<ApiResponse<ListResponse<RunInstance>>>, AppError> {
    let auth_ctx = extract_auth(&headers, &state.gateway).await?;
    state.gateway.authorize(&auth_ctx, "resource:read")?;

    let limit = params.limit.unwrap_or(50);
    let offset = params.offset.unwrap_or(0);

    let runs = state
        .data_access
        .get_runs_by_entity(&entity_id, limit, offset)
        .await?;
    let total = state.data_access.count_runs_by_entity(&entity_id).await?;

    let response = ListResponse {
        items: runs,
        total,
        limit,
        offset,
    };

    Ok(Json(ApiResponse::success(response)))
}

#[derive(Debug, Serialize)]
pub struct ListResponse<T> {
    pub items: Vec<T>,
    pub total: u64,
    pub limit: u64,
    pub offset: u64,
}

pub async fn login_handler(
    State(state): State<AppState>,
    Json(credentials): Json<AuthCredentials>,
) -> Result<Json<ApiResponse<crate::gateway::TokenResponse>>, AppError> {
    let tokens = state.gateway.login(credentials).await?;
    Ok(Json(ApiResponse::success(tokens)))
}

pub async fn register_handler(
    State(state): State<AppState>,
    Json(request): Json<RegisterRequest>,
) -> Result<Json<ApiResponse<crate::gateway::User>>, AppError> {
    let user = state
        .gateway
        .register_user(&request.username, &request.password, &request.email, request.roles)?;
    Ok(Json(ApiResponse::success(user)))
}

#[derive(Debug, Deserialize)]
pub struct RegisterRequest {
    pub username: String,
    pub password: String,
    pub email: String,
    pub roles: Vec<String>,
}

pub async fn refresh_token_handler(
    State(state): State<AppState>,
    Json(request): Json<RefreshTokenRequest>,
) -> Result<Json<ApiResponse<crate::gateway::TokenResponse>>, AppError> {
    let tokens = state.gateway.refresh_token(&request.refresh_token)?;
    Ok(Json(ApiResponse::success(tokens)))
}

#[derive(Debug, Deserialize)]
pub struct RefreshTokenRequest {
    pub refresh_token: String,
}

pub async fn health_check(
    State(state): State<AppState>,
) -> Json<ApiResponse<HealthCheckResponse>> {
    let response = HealthCheckResponse {
        status: "healthy".to_string(),
        version: env!("CARGO_PKG_VERSION").to_string(),
        uptime_seconds: state.metrics.get_uptime_seconds(),
    };
    Json(ApiResponse::success(response))
}

#[derive(Debug, Serialize)]
pub struct HealthCheckResponse {
    pub status: String,
    pub version: String,
    pub uptime_seconds: u64,
}

pub fn create_router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health_check))
        .route("/metrics", get(get_prometheus_metrics))
        .route("/api/v1/auth/login", post(login_handler))
        .route("/api/v1/auth/register", post(register_handler))
        .route("/api/v1/auth/refresh", post(refresh_token_handler))
        .route("/api/v1/resources", post(create_resource).get(list_resources))
        .route(
            "/api/v1/resources/:id/status",
            get(get_resource_status),
        )
        .route("/api/v1/resources/batch", post(batch_operations))
        .route("/api/v1/resources/:id/runs", get(list_runs))
        .route("/api/v1/transform", post(transform_data))
        .route("/api/v1/metrics", get(get_metrics))
        .with_state(state)
}
