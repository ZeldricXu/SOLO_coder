use crate::error::PlatformError;
use crate::core_processing::TaskScheduler;
use crate::monitoring::MonitoringService;
use crate::types::{
    ApiResponse, BatchOperation, BatchRequest, BatchResponse, BatchResult,
    CreateResourceRequest, CreateResourceResponse, ResourceStatus,
};
use crate::utils::{validate_timestamp, verify_hmac_sha256};
use async_trait::async_trait;
use axum::{
    body::Bytes,
    extract::{Path, Query, State},
    http::{HeaderMap, StatusCode},
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use chrono::{DateTime, Utc};
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;
use tracing::{info, warn, error, debug};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RequestContext {
    pub trace_id: String,
    pub timestamp: DateTime<Utc>,
    pub signature: Option<String>,
    pub user_id: Option<String>,
    pub protocol: String,
}

#[async_trait]
pub trait ProtocolAdapter: Send + Sync {
    async fn adapt_request(&self, headers: &HeaderMap, body: &Bytes) -> Result<serde_json::Value, PlatformError>;
    async fn adapt_response(&self, response: &ApiResponse<serde_json::Value>) -> Result<Bytes, PlatformError>;
    fn protocol_name(&self) -> &str;
}

pub struct RestAdapter;

#[async_trait]
impl ProtocolAdapter for RestAdapter {
    async fn adapt_request(&self, _headers: &HeaderMap, body: &Bytes) -> Result<serde_json::Value, PlatformError> {
        if body.is_empty() {
            return Ok(serde_json::Value::Null);
        }
        
        serde_json::from_slice(body)
            .map_err(|e| PlatformError::Validation(format!("Invalid JSON body: {}", e)))
    }

    async fn adapt_response(&self, response: &ApiResponse<serde_json::Value>) -> Result<Bytes, PlatformError> {
        let json = serde_json::to_vec(response)
            .map_err(|e| PlatformError::Internal(format!("Serialization error: {}", e)))?;
        Ok(Bytes::from(json))
    }

    fn protocol_name(&self) -> &str {
        "rest"
    }
}

pub struct BinaryAdapter;

#[async_trait]
impl ProtocolAdapter for BinaryAdapter {
    async fn adapt_request(&self, headers: &HeaderMap, body: &Bytes) -> Result<serde_json::Value, PlatformError> {
        let extracted = crate::utils::verify_and_extract_prefix(body)
            .map_err(|e| PlatformError::Validation(format!("Binary format error: {}", e)))?;
        
        Ok(serde_json::json!({
            "binary_data": extracted,
            "original_headers": headers.iter()
                .map(|(k, v)| (k.to_string(), v.to_str().unwrap_or("").to_string()))
                .collect::<HashMap<String, String>>(),
        }))
    }

    async fn adapt_response(&self, response: &ApiResponse<serde_json::Value>) -> Result<Bytes, PlatformError> {
        let json_bytes = serde_json::to_vec(response)
            .map_err(|e| PlatformError::Internal(format!("Serialization error: {}", e)))?;
        
        Ok(crate::utils::checksum_with_prefix(&json_bytes).into())
    }

    fn protocol_name(&self) -> &str {
        "binary"
    }
}

#[derive(Debug, Clone)]
pub struct RouteConfig {
    pub path: String,
    pub method: String,
    pub handler: String,
    pub required_permissions: Vec<String>,
    pub rate_limit: u64,
    pub timeout_ms: u64,
}

struct GatewayState {
    routes: HashMap<String, RouteConfig>,
    adapters: HashMap<String, Arc<dyn ProtocolAdapter>>,
    api_keys: HashMap<String, Vec<u8>>,
    rate_limit_counters: HashMap<String, (u64, Instant)>,
    active_requests: u64,
    max_concurrent: u64,
}

pub struct ApiGateway {
    state: Arc<RwLock<GatewayState>>,
    scheduler: Arc<TaskScheduler>,
    monitoring: Arc<MonitoringService>,
}

impl ApiGateway {
    pub fn new(scheduler: Arc<TaskScheduler>, monitoring: Arc<MonitoringService>) -> Self {
        let mut adapters: HashMap<String, Arc<dyn ProtocolAdapter>> = HashMap::new();
        adapters.insert("rest".to_string(), Arc::new(RestAdapter));
        adapters.insert("binary".to_string(), Arc::new(BinaryAdapter));
        
        let mut routes = HashMap::new();
        
        routes.insert(
            "POST:/api/v1/resources".to_string(),
            RouteConfig {
                path: "/api/v1/resources".to_string(),
                method: "POST".to_string(),
                handler: "create_resource".to_string(),
                required_permissions: vec!["resources:write".to_string()],
                rate_limit: 100,
                timeout_ms: 30000,
            },
        );
        
        routes.insert(
            "GET:/api/v1/resources/:id/status".to_string(),
            RouteConfig {
                path: "/api/v1/resources/:id/status".to_string(),
                method: "GET".to_string(),
                handler: "get_resource_status".to_string(),
                required_permissions: vec!["resources:read".to_string()],
                rate_limit: 1000,
                timeout_ms: 5000,
            },
        );
        
        routes.insert(
            "POST:/api/v1/resources/batch".to_string(),
            RouteConfig {
                path: "/api/v1/resources/batch".to_string(),
                method: "POST".to_string(),
                handler: "batch_operation".to_string(),
                required_permissions: vec!["resources:write".to_string()],
                rate_limit: 50,
                timeout_ms: 60000,
            },
        );

        ApiGateway {
            state: Arc::new(RwLock::new(GatewayState {
                routes,
                adapters,
                api_keys: HashMap::new(),
                rate_limit_counters: HashMap::new(),
                active_requests: 0,
                max_concurrent: 1000,
            })),
            scheduler,
            monitoring,
        }
    }

    pub fn register_api_key(&self, key_id: &str, secret: &[u8]) {
        let mut state = self.state.write();
        state.api_keys.insert(key_id.to_string(), secret.to_vec());
    }

    pub fn register_route(&self, route: RouteConfig) {
        let key = format!("{}:{}", route.method.to_uppercase(), route.path);
        let mut state = self.state.write();
        state.routes.insert(key, route);
    }

    pub fn register_protocol_adapter(&self, name: &str, adapter: Arc<dyn ProtocolAdapter>) {
        let mut state = self.state.write();
        state.adapters.insert(name.to_string(), adapter);
    }

    pub fn set_max_concurrent(&self, max: u64) {
        let mut state = self.state.write();
        state.max_concurrent = max;
    }

    pub fn create_context(&self, headers: &HeaderMap) -> Result<RequestContext, PlatformError> {
        let trace_id = headers
            .get("x-trace-id")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string())
            .unwrap_or_else(|| format!("trace_{}", Uuid::new_v4().simple()));
        
        let timestamp_str = headers
            .get("x-timestamp")
            .and_then(|v| v.to_str().ok());
        
        let timestamp = match timestamp_str {
            Some(ts) => DateTime::parse_from_rfc3339(ts)
                .map_err(|e| PlatformError::Validation(format!("Invalid timestamp: {}", e)))?
                .with_timezone(&Utc),
            None => Utc::now(),
        };
        
        if let Some(ts_str) = timestamp_str {
            if !validate_timestamp(timestamp, 300) {
                return Err(PlatformError::Authentication("Timestamp is too old or in the future".to_string()));
            }
        }
        
        let signature = headers
            .get("x-signature")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());
        
        let user_id = headers
            .get("x-user-id")
            .and_then(|v| v.to_str().ok())
            .map(|s| s.to_string());
        
        let protocol = headers
            .get("x-protocol")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("rest")
            .to_string();
        
        Ok(RequestContext {
            trace_id,
            timestamp,
            signature,
            user_id,
            protocol,
        })
    }

    pub async fn validate_request(
        &self,
        ctx: &RequestContext,
        method: &str,
        path: &str,
        body: &Bytes,
        headers: &HeaderMap,
    ) -> Result<(), PlatformError> {
        if let (Some(signature), Some(user_id)) = (&ctx.signature, &ctx.user_id) {
            let state = self.state.read();
            if let Some(secret) = state.api_keys.get(user_id) {
                let mut signature_data = Vec::new();
                signature_data.extend_from_slice(method.as_bytes());
                signature_data.push(b'|');
                signature_data.extend_from_slice(path.as_bytes());
                signature_data.push(b'|');
                signature_data.extend_from_slice(ctx.timestamp.to_rfc3339().as_bytes());
                signature_data.push(b'|');
                signature_data.extend_from_slice(body);
                
                let signature_bytes = hex::decode(signature)
                    .map_err(|_| PlatformError::Authentication("Invalid signature format".to_string()))?;
                
                if !verify_hmac_sha256(secret, &signature_data, &signature_bytes) {
                    return Err(PlatformError::Authentication("Invalid signature".to_string()));
                }
            }
        }
        
        let client_key = ctx.user_id.clone().unwrap_or_else(|| "anonymous".to_string());
        let route_key = format!("{}:{}", method.to_uppercase(), path);
        
        let route_config = {
            let state = self.state.read();
            state.routes.get(&route_key).cloned()
        };
        
        if let Some(route) = route_config {
            let mut state = self.state.write();
            let now = Instant::now();
            
            if let Some((count, window_start)) = state.rate_limit_counters.get_mut(&client_key) {
                if now.duration_since(*window_start).as_secs() < 60 {
                    if *count >= route.rate_limit {
                        return Err(PlatformError::Validation("Rate limit exceeded".to_string()));
                    }
                    *count += 1;
                } else {
                    *count = 1;
                    *window_start = now;
                }
            } else {
                state.rate_limit_counters.insert(client_key, (1, now));
            }
            
            if state.active_requests >= state.max_concurrent {
                return Err(PlatformError::Internal("Too many concurrent requests".to_string()));
            }
            
            state.active_requests += 1;
        }
        
        Ok(())
    }

    pub async fn handle_create_resource(
        &self,
        ctx: RequestContext,
        request: CreateResourceRequest,
    ) -> Result<ApiResponse<CreateResourceResponse>, PlatformError> {
        info!(
            trace_id = %ctx.trace_id,
            resource_type = %request.resource_type,
            "Processing create resource request"
        );
        
        let entity = self.scheduler.create_task(
            &request.resource_type,
            request.config.clone(),
        ).await?;
        
        let response = CreateResourceResponse {
            id: entity.id.clone(),
            status: "provisioning".to_string(),
        };
        
        Ok(ApiResponse::created(response))
    }

    pub async fn handle_get_resource_status(
        &self,
        ctx: RequestContext,
        id: &str,
    ) -> Result<ApiResponse<ResourceStatus>, PlatformError> {
        info!(
            trace_id = %ctx.trace_id,
            resource_id = %id,
            "Processing status query"
        );
        
        let status = self.scheduler.get_task_status(id).await?;
        
        Ok(ApiResponse::success(status))
    }

    pub async fn handle_batch_operation(
        &self,
        ctx: RequestContext,
        request: BatchRequest,
    ) -> Result<ApiResponse<BatchResponse>, PlatformError> {
        info!(
            trace_id = %ctx.trace_id,
            operation_count = request.operations.len(),
            "Processing batch operation"
        );
        
        let mut results = Vec::with_capacity(request.operations.len());
        
        for op in &request.operations {
            let result = self.scheduler.execute_batch_operation(op).await;
            
            match result {
                Ok(_) => results.push(BatchResult {
                    id: op.id.clone(),
                    success: true,
                    message: None,
                }),
                Err(e) => results.push(BatchResult {
                    id: op.id.clone(),
                    success: false,
                    message: Some(e.to_string()),
                }),
            }
        }
        
        let response = BatchResponse {
            batch_id: format!("batch_{}", Uuid::new_v4().simple()),
            results,
        };
        
        Ok(ApiResponse::success(response))
    }

    pub async fn start(&self, addr: &str) -> Result<(), PlatformError> {
        info!(addr = %addr, "Starting API Gateway");
        
        let app = self.build_router();
        
        let listener = tokio::net::TcpListener::bind(addr).await
            .map_err(|e| PlatformError::Network(format!("Failed to bind address: {}", e)))?;
        
        info!("API Gateway listening on {}", addr);
        
        axum::serve(listener, app).await
            .map_err(|e| PlatformError::Network(format!("Server error: {}", e)))?;
        
        Ok(())
    }

    fn build_router(&self) -> Router {
        let state = self.state.clone();
        let scheduler = self.scheduler.clone();
        let monitoring = self.monitoring.clone();
        
        let gateway_state = AppState {
            scheduler,
            monitoring,
            gateway_state: state,
        };
        
        Router::new()
            .route("/api/v1/resources", post(create_resource_handler))
            .route("/api/v1/resources/:id/status", get(get_status_handler))
            .route("/api/v1/resources/batch", post(batch_operation_handler))
            .route("/health", get(health_check_handler))
            .with_state(Arc::new(gateway_state))
    }
}

#[derive(Clone)]
struct AppState {
    scheduler: Arc<TaskScheduler>,
    monitoring: Arc<MonitoringService>,
    gateway_state: Arc<RwLock<GatewayState>>,
}

async fn create_resource_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Bytes,
) -> impl IntoResponse {
    let start = Instant::now();
    
    let gateway = ApiGateway {
        state: state.gateway_state.clone(),
        scheduler: state.scheduler.clone(),
        monitoring: state.monitoring.clone(),
    };
    
    let ctx = match gateway.create_context(&headers) {
        Ok(ctx) => ctx,
        Err(e) => {
            return (
                StatusCode::from_u16(e.http_status_code()).unwrap_or(StatusCode::BAD_REQUEST),
                Json(ApiResponse::<serde_json::Value>::error(e.http_status_code(), &e.error_message())),
            ).into_response();
        }
    };
    
    if let Err(e) = gateway.validate_request(&ctx, "POST", "/api/v1/resources", &body, &headers).await {
        {
            let mut gs = state.gateway_state.write();
            gs.active_requests = gs.active_requests.saturating_sub(1);
        }
        
        state.monitoring.record_error("create_resource");
        
        return (
            StatusCode::from_u16(e.http_status_code()).unwrap_or(StatusCode::BAD_REQUEST),
            Json(ApiResponse::<serde_json::Value>::error(e.http_status_code(), &e.error_message())),
        ).into_response();
    }
    
    let request: Result<CreateResourceRequest, _> = serde_json::from_slice(&body);
    
    let response = match request {
        Ok(req) => gateway.handle_create_resource(ctx, req).await,
        Err(e) => Err(PlatformError::Validation(format!("Invalid request: {}", e))),
    };
    
    {
        let mut gs = state.gateway_state.write();
        gs.active_requests = gs.active_requests.saturating_sub(1);
    }
    
    let duration = start.elapsed().as_millis() as u64;
    state.monitoring.record_latency("create_resource", duration);
    
    match response {
        Ok(resp) => {
            state.monitoring.increment_counter("requests_create_resource", 1.0);
            (StatusCode::CREATED, Json(resp)).into_response()
        }
        Err(e) => {
            state.monitoring.record_error("create_resource");
            (
                StatusCode::from_u16(e.http_status_code()).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
                Json(ApiResponse::<serde_json::Value>::error(e.http_status_code(), &e.error_message())),
            ).into_response()
        }
    }
}

async fn get_status_handler(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    headers: HeaderMap,
) -> impl IntoResponse {
    let start = Instant::now();
    
    let gateway = ApiGateway {
        state: state.gateway_state.clone(),
        scheduler: state.scheduler.clone(),
        monitoring: state.monitoring.clone(),
    };
    
    let ctx = match gateway.create_context(&headers) {
        Ok(ctx) => ctx,
        Err(e) => {
            return (
                StatusCode::from_u16(e.http_status_code()).unwrap_or(StatusCode::BAD_REQUEST),
                Json(ApiResponse::<serde_json::Value>::error(e.http_status_code(), &e.error_message())),
            ).into_response();
        }
    };
    
    if let Err(e) = gateway.validate_request(&ctx, "GET", "/api/v1/resources/:id/status", &Bytes::new(), &headers).await {
        {
            let mut gs = state.gateway_state.write();
            gs.active_requests = gs.active_requests.saturating_sub(1);
        }
        state.monitoring.record_error("get_status");
        
        return (
            StatusCode::from_u16(e.http_status_code()).unwrap_or(StatusCode::BAD_REQUEST),
            Json(ApiResponse::<serde_json::Value>::error(e.http_status_code(), &e.error_message())),
        ).into_response();
    }
    
    let response = gateway.handle_get_resource_status(ctx, &id).await;
    
    {
        let mut gs = state.gateway_state.write();
        gs.active_requests = gs.active_requests.saturating_sub(1);
    }
    
    let duration = start.elapsed().as_millis() as u64;
    state.monitoring.record_latency("get_status", duration);
    
    match response {
        Ok(resp) => {
            state.monitoring.increment_counter("requests_get_status", 1.0);
            (StatusCode::OK, Json(resp)).into_response()
        }
        Err(e) => {
            state.monitoring.record_error("get_status");
            (
                StatusCode::from_u16(e.http_status_code()).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
                Json(ApiResponse::<serde_json::Value>::error(e.http_status_code(), &e.error_message())),
            ).into_response()
        }
    }
}

async fn batch_operation_handler(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Bytes,
) -> impl IntoResponse {
    let start = Instant::now();
    
    let gateway = ApiGateway {
        state: state.gateway_state.clone(),
        scheduler: state.scheduler.clone(),
        monitoring: state.monitoring.clone(),
    };
    
    let ctx = match gateway.create_context(&headers) {
        Ok(ctx) => ctx,
        Err(e) => {
            return (
                StatusCode::from_u16(e.http_status_code()).unwrap_or(StatusCode::BAD_REQUEST),
                Json(ApiResponse::<serde_json::Value>::error(e.http_status_code(), &e.error_message())),
            ).into_response();
        }
    };
    
    if let Err(e) = gateway.validate_request(&ctx, "POST", "/api/v1/resources/batch", &body, &headers).await {
        {
            let mut gs = state.gateway_state.write();
            gs.active_requests = gs.active_requests.saturating_sub(1);
        }
        state.monitoring.record_error("batch_operation");
        
        return (
            StatusCode::from_u16(e.http_status_code()).unwrap_or(StatusCode::BAD_REQUEST),
            Json(ApiResponse::<serde_json::Value>::error(e.http_status_code(), &e.error_message())),
        ).into_response();
    }
    
    let request: Result<BatchRequest, _> = serde_json::from_slice(&body);
    
    let response = match request {
        Ok(req) => gateway.handle_batch_operation(ctx, req).await,
        Err(e) => Err(PlatformError::Validation(format!("Invalid request: {}", e))),
    };
    
    {
        let mut gs = state.gateway_state.write();
        gs.active_requests = gs.active_requests.saturating_sub(1);
    }
    
    let duration = start.elapsed().as_millis() as u64;
    state.monitoring.record_latency("batch_operation", duration);
    
    match response {
        Ok(resp) => {
            state.monitoring.increment_counter("requests_batch", 1.0);
            (StatusCode::OK, Json(resp)).into_response()
        }
        Err(e) => {
            state.monitoring.record_error("batch_operation");
            (
                StatusCode::from_u16(e.http_status_code()).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
                Json(ApiResponse::<serde_json::Value>::error(e.http_status_code(), &e.error_message())),
            ).into_response()
        }
    }
}

async fn health_check_handler() -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "status": "healthy",
            "timestamp": Utc::now().to_rfc3339(),
        })),
    )
}
