use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{anyhow, Context, Result};
use async_trait::async_trait;
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use tokio::sync::{mpsc, Semaphore};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use crate::config::ConfigDefinition;
use crate::monitoring::{MetricsCollector, TimerGuard};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Request {
    pub request_id: String,
    pub trace_id: String,
    pub tenant_id: String,
    pub namespace: String,
    pub operation: String,
    pub payload: serde_json::Value,
    pub parameters: HashMap<String, String>,
    pub timeout_ms: u64,
    pub priority: RequestPriority,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
pub enum RequestPriority {
    Low,
    Medium,
    High,
    Critical,
}

impl Default for RequestPriority {
    fn default() -> Self {
        RequestPriority::Medium
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Response {
    pub request_id: String,
    pub trace_id: String,
    pub status: ResponseStatus,
    pub data: Option<serde_json::Value>,
    pub error: Option<ErrorDetail>,
    pub metadata: ResponseMetadata,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ResponseStatus {
    Success,
    ValidationError,
    Timeout,
    RateLimited,
    QuotaExceeded,
    InternalError,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorDetail {
    pub code: String,
    pub message: String,
    pub details: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResponseMetadata {
    pub processing_time_ms: u64,
    pub queue_time_ms: u64,
    pub retry_count: u32,
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessingContext {
    pub request_id: String,
    pub trace_id: String,
    pub tenant_id: String,
    pub namespace: String,
    pub config: Option<ConfigDefinition>,
    pub start_time: Instant,
    pub metadata: HashMap<String, String>,
    pub warnings: RwLock<Vec<String>>,
}

impl ProcessingContext {
    pub fn new(request: &Request) -> Self {
        Self {
            request_id: request.request_id.clone(),
            trace_id: request.trace_id.clone(),
            tenant_id: request.tenant_id.clone(),
            namespace: request.namespace.clone(),
            config: None,
            start_time: Instant::now(),
            metadata: HashMap::new(),
            warnings: RwLock::new(Vec::new()),
        }
    }

    pub fn add_warning(&self, warning: &str) {
        self.warnings.write().push(warning.to_string());
    }

    pub fn elapsed_ms(&self) -> u64 {
        self.start_time.elapsed().as_millis() as u64
    }

    pub fn cleanup(self) {
        debug!("Cleaning up context for request: {}", self.request_id);
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CoreEntity {
    pub id: String,
    pub entity_type: String,
    pub status: String,
    pub attributes: serde_json::Value,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunInstance {
    pub run_id: String,
    pub entity_id: String,
    pub phase: String,
    pub progress: f64,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_detail: Option<String>,
}

#[async_trait]
pub trait RequestHandler: Send + Sync {
    fn operation(&self) -> &str;
    async fn handle(&self, ctx: &ProcessingContext, request: &Request) -> Result<serde_json::Value>;
}

pub type HandlerRegistry = Arc<RwLock<HashMap<String, Arc<dyn RequestHandler>>>>;

pub struct CoreProcessor {
    handlers: HandlerRegistry,
    metrics: Arc<MetricsCollector>,
    max_concurrent_requests: usize,
    semaphore: Arc<Semaphore>,
    request_timeout: Duration,
    event_handlers: RwLock<Vec<Arc<dyn Fn(RequestEvent) -> Result<()> + Send + Sync>>>,
    shutdown_tx: Option<mpsc::Sender<()>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RequestEvent {
    pub event_id: String,
    pub request_id: String,
    pub trace_id: String,
    pub event_type: RequestEventType,
    pub timestamp: DateTime<Utc>,
    pub details: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum RequestEventType {
    Received,
    Processing,
    Completed,
    Failed,
    Timeout,
}

impl CoreProcessor {
    pub fn new(metrics: Arc<MetricsCollector>) -> Self {
        Self::with_concurrency(metrics, 1000, Duration::from_secs(30))
    }

    pub fn with_concurrency(
        metrics: Arc<MetricsCollector>,
        max_concurrent: usize,
        request_timeout: Duration,
    ) -> Self {
        Self {
            handlers: Arc::new(RwLock::new(HashMap::new())),
            metrics,
            max_concurrent_requests: max_concurrent,
            semaphore: Arc::new(Semaphore::new(max_concurrent)),
            request_timeout,
            event_handlers: RwLock::new(Vec::new()),
            shutdown_tx: None,
        }
    }

    pub fn register_handler<H>(&self, handler: H)
    where
        H: RequestHandler + 'static,
    {
        let operation = handler.operation().to_string();
        self.handlers.write().insert(operation, Arc::new(handler));
        info!("Registered handler for operation: {}", operation);
    }

    pub fn register_event_handler<F>(&self, handler: F)
    where
        F: Fn(RequestEvent) -> Result<()> + Send + Sync + 'static,
    {
        self.event_handlers.write().push(Arc::new(handler));
    }

    fn notify_event_handlers(&self, event: RequestEvent) {
        let handlers = self.event_handlers.read();
        for handler in handlers.iter() {
            let event = event.clone();
            let handler = handler.clone();
            tokio::spawn(async move {
                if let Err(e) = handler(event) {
                    error!(error = %e, "Request event handler failed");
                }
            });
        }
    }

    pub async fn process(&self, request: Request) -> Response {
        let _timer = TimerGuard::new(&self.metrics, "request_processing_time".to_string());
        let request_received_at = Utc::now();
        
        self.metrics.increment("requests_received");
        
        self.notify_event_handlers(RequestEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            request_id: request.request_id.clone(),
            trace_id: request.trace_id.clone(),
            event_type: RequestEventType::Received,
            timestamp: request_received_at,
            details: Some(serde_json::json!({ "operation": request.operation })),
        });

        let timeout = Duration::from_millis(request.timeout_ms).min(self.request_timeout);
        
        let result = tokio::time::timeout(timeout, async {
            self.process_inner(request.clone()).await
        }).await;

        match result {
            Ok(Ok(response)) => {
                self.metrics.increment("requests_success");
                response
            }
            Ok(Err(e)) => {
                self.metrics.increment("requests_failed");
                self.create_error_response(&request, e, request_received_at)
            }
            Err(_) => {
                self.metrics.increment("requests_timeout");
                self.create_timeout_response(&request, request_received_at)
            }
        }
    }

    async fn process_inner(&self, request: Request) -> Result<Response> {
        let queue_time_ms = (Utc::now() - request.created_at).num_milliseconds() as u64;
        
        let ctx = ProcessingContext::new(&request);
        
        self.notify_event_handlers(RequestEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            request_id: request.request_id.clone(),
            trace_id: request.trace_id.clone(),
            event_type: RequestEventType::Processing,
            timestamp: Utc::now(),
            details: None,
        });

        let _permit = self.semaphore.clone().acquire_owned().await
            .map_err(|e| anyhow!("Failed to acquire semaphore: {}", e))?;

        self.validate_params(&request.parameters)
            .context("Parameter validation failed")?;

        let handler = self.handlers.read()
            .get(&request.operation)
            .cloned()
            .ok_or_else(|| anyhow!("No handler found for operation: {}", request.operation))?;

        let result = handler.handle(&ctx, &request).await;
        
        let processing_time_ms = ctx.elapsed_ms();

        match result {
            Ok(data) => {
                self.notify_event_handlers(RequestEvent {
                    event_id: format!("evt_{}", Uuid::new_v4().simple()),
                    request_id: request.request_id.clone(),
                    trace_id: request.trace_id.clone(),
                    event_type: RequestEventType::Completed,
                    timestamp: Utc::now(),
                    details: Some(serde_json::json!({ "processing_time_ms": processing_time_ms })),
                });

                let warnings = ctx.warnings.read().clone();
                ctx.cleanup();

                Ok(Response {
                    request_id: request.request_id,
                    trace_id: request.trace_id,
                    status: ResponseStatus::Success,
                    data: Some(data),
                    error: None,
                    metadata: ResponseMetadata {
                        processing_time_ms,
                        queue_time_ms,
                        retry_count: 0,
                        warnings,
                    },
                    created_at: Utc::now(),
                })
            }
            Err(e) => {
                self.notify_event_handlers(RequestEvent {
                    event_id: format!("evt_{}", Uuid::new_v4().simple()),
                    request_id: request.request_id.clone(),
                    trace_id: request.trace_id.clone(),
                    event_type: RequestEventType::Failed,
                    timestamp: Utc::now(),
                    details: Some(serde_json::json!({ "error": e.to_string() })),
                });

                let warnings = ctx.warnings.read().clone();
                ctx.cleanup();
                
                Err(e)
            }
        }
    }

    fn validate_params(&self, params: &HashMap<String, String>) -> Result<()> {
        if params.contains_key("invalid") {
            return Err(anyhow!("Invalid parameter detected"));
        }
        Ok(())
    }

    fn create_error_response(&self, request: &Request, error: anyhow::Error, received_at: DateTime<Utc>) -> Response {
        let processing_time_ms = (Utc::now() - received_at).num_milliseconds() as u64;
        
        warn!(request_id = %request.request_id, error = %error, "Request failed");
        
        Response {
            request_id: request.request_id.clone(),
            trace_id: request.trace_id.clone(),
            status: ResponseStatus::InternalError,
            data: None,
            error: Some(ErrorDetail {
                code: "INTERNAL_ERROR".to_string(),
                message: error.to_string(),
                details: None,
            }),
            metadata: ResponseMetadata {
                processing_time_ms,
                queue_time_ms: 0,
                retry_count: 0,
                warnings: Vec::new(),
            },
            created_at: Utc::now(),
        }
    }

    fn create_timeout_response(&self, request: &Request, received_at: DateTime<Utc>) -> Response {
        let processing_time_ms = (Utc::now() - received_at).num_milliseconds() as u64;
        
        warn!(request_id = %request.request_id, "Request timed out");
        
        self.notify_event_handlers(RequestEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            request_id: request.request_id.clone(),
            trace_id: request.trace_id.clone(),
            event_type: RequestEventType::Timeout,
            timestamp: Utc::now(),
            details: None,
        });

        Response {
            request_id: request.request_id.clone(),
            trace_id: request.trace_id.clone(),
            status: ResponseStatus::Timeout,
            data: None,
            error: Some(ErrorDetail {
                code: "TIMEOUT".to_string(),
                message: "上游服务响应超时".to_string(),
                details: None,
            }),
            metadata: ResponseMetadata {
                processing_time_ms,
                queue_time_ms: 0,
                retry_count: 0,
                warnings: Vec::new(),
            },
            created_at: Utc::now(),
        }
    }

    pub async fn execute_with_retry<F, Fut, T>(&self, f: F, max_retries: u32) -> Result<T>
    where
        F: Fn() -> Fut + Send + Sync,
        Fut: std::future::Future<Output = Result<T>> + Send,
        T: Send,
    {
        let mut attempts = 0;
        loop {
            match f().await {
                Ok(result) => return Ok(result),
                Err(e) => {
                    attempts += 1;
                    if attempts >= max_retries {
                        return Err(e);
                    }
                    let delay = Duration::from_millis(2u64.pow(attempts) * 100);
                    tokio::time::sleep(delay).await;
                    warn!("Retry attempt {}/{}", attempts, max_retries);
                }
            }
        }
    }

    pub fn get_metrics(&self) -> Arc<MetricsCollector> {
        self.metrics.clone()
    }

    pub fn start(&mut self) -> Result<()> {
        info!("Core processor started with max {} concurrent requests", self.max_concurrent_requests);
        Ok(())
    }

    pub fn stop(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            drop(tx);
        }
        info!("Core processor stopped");
    }
}

impl Drop for CoreProcessor {
    fn drop(&mut self) {
        self.stop();
    }
}

pub fn build_request(
    tenant_id: String,
    namespace: String,
    operation: String,
    payload: serde_json::Value,
) -> Request {
    Request {
        request_id: format!("req_{}", Uuid::new_v4().simple()),
        trace_id: format!("trace_{}", Uuid::new_v4().simple()),
        tenant_id,
        namespace,
        operation,
        payload,
        parameters: HashMap::new(),
        timeout_ms: 30000,
        priority: RequestPriority::Medium,
        created_at: Utc::now(),
    }
}

pub fn success_response(request: &Request, data: serde_json::Value) -> Response {
    Response {
        request_id: request.request_id.clone(),
        trace_id: request.trace_id.clone(),
        status: ResponseStatus::Success,
        data: Some(data),
        error: None,
        metadata: ResponseMetadata {
            processing_time_ms: 0,
            queue_time_ms: 0,
            retry_count: 0,
            warnings: Vec::new(),
        },
        created_at: Utc::now(),
    }
}

pub fn error_response(request: &Request, code: &str, message: &str) -> Response {
    Response {
        request_id: request.request_id.clone(),
        trace_id: request.trace_id.clone(),
        status: ResponseStatus::InternalError,
        data: None,
        error: Some(ErrorDetail {
            code: code.to_string(),
            message: message.to_string(),
            details: None,
        }),
        metadata: ResponseMetadata {
            processing_time_ms: 0,
            queue_time_ms: 0,
            retry_count: 0,
            warnings: Vec::new(),
        },
        created_at: Utc::now(),
    }
}

#[derive(Debug, Clone)]
pub struct EchoHandler;

#[async_trait]
impl RequestHandler for EchoHandler {
    fn operation(&self) -> &str {
        "echo"
    }

    async fn handle(&self, _ctx: &ProcessingContext, request: &Request) -> Result<serde_json::Value> {
        Ok(serde_json::json!({
            "echo": request.payload,
            "request_id": request.request_id,
            "timestamp": Utc::now().to_rfc3339()
        }))
    }
}

#[derive(Debug, Clone)]
pub struct HealthCheckHandler;

#[async_trait]
impl RequestHandler for HealthCheckHandler {
    fn operation(&self) -> &str {
        "health_check"
    }

    async fn handle(&self, _ctx: &ProcessingContext, _request: &Request) -> Result<serde_json::Value> {
        Ok(serde_json::json!({
            "status": "healthy",
            "timestamp": Utc::now().to_rfc3339()
        }))
    }
}
