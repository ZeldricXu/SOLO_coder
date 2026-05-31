use anyhow::Result;
use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use chrono::Utc;
use dashmap::DashMap;
use enterprise_data_hub::*;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::Mutex;
use uuid::Uuid;

#[derive(Clone)]
struct AppState {
    config_manager: Arc<config::ConfigManager>,
    tenant_manager: Arc<tenant::TenantManager>,
    skill_graph_manager: Arc<skill_graph::SkillGraphManager>,
    metrics_collector: Arc<monitoring::MetricsCollector>,
    scheduler: Arc<Mutex<scheduler::TaskScheduler>>,
    billing_manager: Arc<billing::BillingManager>,
    data_access: Arc<data_access::DataAccessLayer>,
    core_processor: Arc<core::CoreProcessor>,
    flow_designer: Arc<flow_designer::FlowDesigner>,
    requests: Arc<DashMap<String, core::Request>>,
}

#[derive(Debug, Serialize, Deserialize)]
struct ApiResponse<T> {
    code: u16,
    message: String,
    data: Option<T>,
}

#[derive(Debug, Deserialize)]
struct CreateResourceRequest {
    resource_type: String,
    config: serde_json::Value,
    labels: std::collections::HashMap<String, String>,
}

#[derive(Debug, Serialize)]
struct CreateResourceResponse {
    id: String,
    status: String,
}

async fn create_resource(
    State(state): State<AppState>,
    Json(req): Json<CreateResourceRequest>,
) -> impl IntoResponse {
    let request_id = Uuid::new_v4().to_string();
    let process_req = core::Request {
        request_id: request_id.clone(),
        trace_id: Uuid::new_v4().to_string(),
        namespace: "default".to_string(),
        tenant_id: "tenant_001".to_string(),
        operation: req.resource_type.clone(),
        payload: req.config,
        parameters: std::collections::HashMap::new(),
        timeout_ms: 30000,
        priority: core::RequestPriority::Medium,
        created_at: Utc::now(),
    };

    state.requests.insert(request_id.clone(), process_req.clone());

    let result = state.core_processor.process(process_req).await;

    match result.status {
        core::ResponseStatus::Success => {
            state
                .metrics_collector
                .increment("resources.created");

            let response = ApiResponse {
                code: 201,
                message: "Resource created successfully".to_string(),
                data: Some(CreateResourceResponse {
                    id: result.request_id,
                    status: format!("{:?}", result.status),
                }),
            };
            (StatusCode::CREATED, Json(response))
        }
        _ => {
            let error_msg = result
                .error
                .map(|e| e.message)
                .unwrap_or_else(|| "Unknown error".to_string());
            let response = ApiResponse::<()> {
                code: 500,
                message: format!("Failed to create resource: {}", error_msg),
                data: None,
            };
            (StatusCode::INTERNAL_SERVER_ERROR, Json(response))
        }
    }
}

async fn get_resource_status(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    if let Some(req) = state.requests.get(&id) {
        let response = ApiResponse {
            code: 200,
            message: "Success".to_string(),
            data: Some(serde_json::json!({
                "id": req.request_id,
                "status": "processing",
                "operation": req.operation,
                "created_at": req.created_at,
            })),
        };
        (StatusCode::OK, Json(response))
    } else {
        let response = ApiResponse::<()> {
            code: 404,
            message: "Resource not found".to_string(),
            data: None,
        };
        (StatusCode::NOT_FOUND, Json(response))
    }
}

#[derive(Debug, Deserialize)]
struct BatchOperation {
    action: String,
    id: String,
}

#[derive(Debug, Deserialize)]
struct BatchRequest {
    operations: Vec<BatchOperation>,
}

#[derive(Debug, Serialize)]
struct BatchResult {
    id: String,
    action: String,
    success: bool,
    message: String,
}

async fn batch_operations(
    State(_state): State<AppState>,
    Json(req): Json<BatchRequest>,
) -> impl IntoResponse {
    let mut results = Vec::new();

    for op in req.operations {
        results.push(BatchResult {
            id: op.id,
            action: op.action.clone(),
            success: true,
            message: format!("Operation {} executed", op.action),
        });
    }

    let response = ApiResponse {
        code: 200,
        message: "Batch operations completed".to_string(),
        data: Some(serde_json::json!({
            "batch_id": Uuid::new_v4().to_string(),
            "results": results,
        })),
    };
    (StatusCode::OK, Json(response))
}

async fn health_check() -> impl IntoResponse {
    let response = ApiResponse {
        code: 200,
        message: "OK".to_string(),
        data: Some(serde_json::json!({
            "status": "healthy",
            "timestamp": Utc::now().to_rfc3339(),
        })),
    };
    Json(response)
}

async fn get_metrics(State(state): State<AppState>) -> impl IntoResponse {
    let snapshot = state.metrics_collector.snapshot();
    let response = ApiResponse {
        code: 200,
        message: "Success".to_string(),
        data: Some(snapshot),
    };
    Json(response)
}

#[derive(Debug, Deserialize)]
struct CreateTenantRequest {
    name: String,
    plan: String,
}

async fn create_tenant(
    State(state): State<AppState>,
    Json(req): Json<CreateTenantRequest>,
) -> impl IntoResponse {
    let tier = match req.plan.as_str() {
        "free" => tenant::TenantTier::Free,
        "standard" => tenant::TenantTier::Standard,
        "premium" => tenant::TenantTier::Premium,
        "enterprise" => tenant::TenantTier::Enterprise,
        _ => {
            let response = ApiResponse::<()> {
                code: 400,
                message: "Invalid plan. Valid options: free, standard, premium, enterprise".to_string(),
                data: None,
            };
            return (StatusCode::BAD_REQUEST, Json(response));
        }
    };

    match state.tenant_manager.create_tenant(req.name, tier) {
        Ok(tenant) => {
            let response = ApiResponse {
                code: 201,
                message: "Tenant created successfully".to_string(),
                data: Some(tenant),
            };
            (StatusCode::CREATED, Json(response))
        }
        Err(e) => {
            let response = ApiResponse::<()> {
                code: 500,
                message: format!("Failed to create tenant: {}", e),
                data: None,
            };
            (StatusCode::INTERNAL_SERVER_ERROR, Json(response))
        }
    }
}

async fn list_tenants(State(state): State<AppState>) -> impl IntoResponse {
    let tenants = state.tenant_manager.list_tenants();
    let response = ApiResponse {
        code: 200,
        message: "Success".to_string(),
        data: Some(tenants),
    };
    Json(response)
}

async fn list_skills(State(state): State<AppState>) -> impl IntoResponse {
    let trees = state.skill_graph_manager.list_skill_trees();
    let response = ApiResponse {
        code: 200,
        message: "Success".to_string(),
        data: Some(trees),
    };
    Json(response)
}

#[derive(Debug, Deserialize)]
struct CreateSkillTreeRequest {
    name: String,
    description: String,
}

async fn create_skill_tree(
    State(state): State<AppState>,
    Json(req): Json<CreateSkillTreeRequest>,
) -> impl IntoResponse {
    let tree = state
        .skill_graph_manager
        .create_skill_tree(req.name, req.description);
    let response = ApiResponse {
        code: 201,
        message: "Skill tree created successfully".to_string(),
        data: Some(tree),
    };
    (StatusCode::CREATED, Json(response))
}

#[derive(Debug, Deserialize)]
struct CreateFlowRequest {
    name: String,
    description: String,
}

async fn create_flow(
    State(state): State<AppState>,
    Json(req): Json<CreateFlowRequest>,
) -> impl IntoResponse {
    let flow = state.flow_designer.create_flow(req.name, req.description);
    let response = ApiResponse {
        code: 201,
        message: "Flow created successfully".to_string(),
        data: Some(flow),
    };
    (StatusCode::CREATED, Json(response))
}

async fn list_flows(State(state): State<AppState>) -> impl IntoResponse {
    let flows = state.flow_designer.list_flows();
    let response = ApiResponse {
        code: 200,
        message: "Success".to_string(),
        data: Some(flows),
    };
    Json(response)
}

async fn get_config(State(state): State<AppState>) -> impl IntoResponse {
    let configs = state.config_manager.get_namespace_configs("default");
    let response = ApiResponse {
        code: 200,
        message: "Success".to_string(),
        data: Some(configs),
    };
    Json(response)
}

async fn get_bills(State(state): State<AppState>) -> impl IntoResponse {
    let plans = state.billing_manager.list_billing_plans();
    let response = ApiResponse {
        code: 200,
        message: "Success".to_string(),
        data: Some(plans),
    };
    Json(response)
}

async fn get_scheduler_tasks(State(state): State<AppState>) -> impl IntoResponse {
    let scheduler = state.scheduler.lock().await;
    let tasks = scheduler.list_tasks();
    drop(scheduler);
    let response = ApiResponse {
        code: 200,
        message: "Success".to_string(),
        data: Some(tasks),
    };
    Json(response)
}

fn build_router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health_check))
        .route("/metrics", get(get_metrics))
        .route("/config", get(get_config))
        .route("/api/v1/resources", post(create_resource))
        .route("/api/v1/resources/:id/status", get(get_resource_status))
        .route("/api/v1/resources/batch", post(batch_operations))
        .route("/api/v1/tenants", post(create_tenant).get(list_tenants))
        .route("/api/v1/skills", post(create_skill_tree).get(list_skills))
        .route("/api/v1/flows", post(create_flow).get(list_flows))
        .route("/api/v1/bills", get(get_bills))
        .route("/api/v1/scheduler/tasks", get(get_scheduler_tasks))
        .with_state(state)
}

#[tokio::main]
async fn main() -> Result<()> {
    let mut log_manager = logging::LogManager::new(logging::LogConfig::default())?;
    log_manager.init_tracing()?;

    tracing::info!("Starting Enterprise Data Hub...");

    let mut config_manager = config::ConfigManager::new();
    config_manager.add_source(config::ConfigSource {
        source_type: config::ConfigSourceType::Environment,
        priority: 10,
        location: "EDH_".to_string(),
    });
    let config_manager = Arc::new(config_manager);
    let tenant_manager = Arc::new(tenant::TenantManager::new());
    let skill_graph_manager = Arc::new(skill_graph::SkillGraphManager::new());
    let metrics_collector = Arc::new(monitoring::MetricsCollector::new());
    let scheduler = Arc::new(Mutex::new(scheduler::TaskScheduler::new()));
    let billing_manager = Arc::new(billing::BillingManager::new());
    let data_access = Arc::new(data_access::DataAccessLayer::new());
    let core_processor = Arc::new(core::CoreProcessor::new(
        metrics_collector.clone(),
    ));
    let flow_designer = Arc::new(flow_designer::FlowDesigner::new());

    metrics_collector.increment_by("resources.created", 0.0);
    metrics_collector.increment_by("requests.total", 0.0);
    metrics_collector.gauge("system.load", 0.0);
    metrics_collector.histogram("request.latency", 0.0);

    let app_state = AppState {
        config_manager: config_manager.clone(),
        tenant_manager: tenant_manager.clone(),
        skill_graph_manager: skill_graph_manager.clone(),
        metrics_collector: metrics_collector.clone(),
        scheduler: scheduler.clone(),
        billing_manager: billing_manager.clone(),
        data_access: data_access.clone(),
        core_processor: core_processor.clone(),
        flow_designer: flow_designer.clone(),
        requests: Arc::new(DashMap::new()),
    };

    {
        let mut scheduler_guard = scheduler.lock().await;

        let metrics_task = scheduler::ScheduledTask {
            task_id: "metrics_cleanup".to_string(),
            name: "Metrics Cleanup".to_string(),
            description: "Clean up old metrics data".to_string(),
            schedule: scheduler::TaskSchedule::Interval(std::time::Duration::from_secs(3600)),
            task_type: scheduler::TaskType::System,
            payload: serde_json::json!({}),
            status: scheduler::TaskStatus::Scheduled,
            priority: scheduler::TaskPriority::Medium,
            max_retries: 3,
            retry_count: 0,
            timeout_seconds: 300,
            last_run_at: None,
            next_run_at: None,
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };
        scheduler_guard.create_task(metrics_task)?;

        let billing_task = scheduler::ScheduledTask {
            task_id: "billing_cycle".to_string(),
            name: "Billing Cycle".to_string(),
            description: "Generate monthly bills for all tenants".to_string(),
            schedule: scheduler::TaskSchedule::Cron("0 0 1 * * *".to_string()),
            task_type: scheduler::TaskType::System,
            payload: serde_json::json!({}),
            status: scheduler::TaskStatus::Scheduled,
            priority: scheduler::TaskPriority::High,
            max_retries: 5,
            retry_count: 0,
            timeout_seconds: 600,
            last_run_at: None,
            next_run_at: None,
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };
        scheduler_guard.create_task(billing_task)?;

        scheduler_guard.start().await?;
    }

    let app = build_router(app_state);
    let listener = tokio::net::TcpListener::bind("0.0.0.0:8080").await?;
    tracing::info!("Server listening on 0.0.0.0:8080");

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;

    tracing::info!("Shutting down scheduler...");
    let mut scheduler_guard = scheduler.lock().await;
    scheduler_guard.stop();
    drop(scheduler_guard);

    tracing::info!("Enterprise Data Hub shutdown complete");
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("Failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("Failed to install signal handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }

    tracing::info!("Shutdown signal received, starting graceful shutdown");
}
