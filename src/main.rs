use std::sync::Arc;
use tokio::sync::RwLock;
use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::Utc;

use streamsql::models::*;
use streamsql::utils::*;

#[derive(Debug, Clone)]
struct AppState {
    resources: Arc<RwLock<std::collections::HashMap<String, Resource>>>,
    runs: Arc<RwLock<std::collections::HashMap<String, RunInstance>>>,
}

#[derive(Debug, Deserialize)]
struct CreateResourceRequest {
    r#type: String,
    config: serde_json::Value,
    labels: Option<std::collections::HashMap<String, String>>,
}

#[derive(Debug, Serialize)]
struct ApiResponse<T: Serialize> {
    code: u16,
    data: T,
}

#[derive(Debug, Serialize)]
struct ErrorResponse {
    code: u16,
    error: String,
    details: Option<String>,
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
    message: Option<String>,
}

#[derive(Debug, Serialize)]
struct BatchResponseData {
    batch_id: String,
    results: Vec<BatchResult>,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let state = AppState {
        resources: Arc::new(RwLock::new(std::collections::HashMap::new())),
        runs: Arc::new(RwLock::new(std::collections::HashMap::new())),
    };

    let app = Router::new()
        .route("/health", get(health_check))
        .route("/api/v1/resources", post(create_resource))
        .route("/api/v1/resources/:id/status", get(get_resource_status))
        .route("/api/v1/resources/batch", post(batch_operation))
        .route("/api/v1/version", get(get_version))
        .with_state(state);

    let addr = std::net::SocketAddr::from(([127, 0, 0, 1], 8080));
    tracing::info!("StreamSQL 服务启动中，监听地址: {}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}

async fn health_check() -> impl IntoResponse {
    Json(ApiResponse {
        code: 200,
        data: serde_json::json!({
            "status": "healthy",
            "timestamp": Utc::now().to_rfc3339()
        }),
    })
}

async fn get_version() -> impl IntoResponse {
    Json(ApiResponse {
        code: 200,
        data: serde_json::json!({
            "name": streamsql::NAME,
            "version": streamsql::VERSION,
            "description": "流式SQL计算执行引擎"
        }),
    })
}

async fn create_resource(
    State(state): State<AppState>,
    Json(payload): Json<CreateResourceRequest>,
) -> impl IntoResponse {
    let resource_id = format!("rsc_{}", Uuid::new_v4().simple().to_string()[..8].to_string());

    let resource = Resource {
        id: resource_id.clone(),
        r#type: payload.r#type,
        status: "provisioning".to_string(),
        attributes: if let Some(labels) = payload.labels {
            labels
        } else {
            std::collections::HashMap::new()
        },
        created_at: Utc::now(),
        updated_at: Utc::now(),
    };

    let mut resources = state.resources.write().await;
    resources.insert(resource_id.clone(), resource.clone());

    let run_id = format!("run_{}", Uuid::new_v4().simple().to_string()[..8].to_string());
    let run = RunInstance {
        run_id: run_id.clone(),
        entity_id: resource_id.clone(),
        phase: "executing".to_string(),
        progress: 0.0,
        started_at: Some(Utc::now()),
        completed_at: None,
        error_detail: None,
    };

    let mut runs = state.runs.write().await;
    runs.insert(run_id, run);

    (
        StatusCode::CREATED,
        Json(ApiResponse {
            code: 201,
            data: serde_json::json!({
                "id": resource_id,
                "status": "provisioning"
            }),
        }),
    )
}

async fn get_resource_status(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    let resources = state.resources.read().await;

    match resources.get(&id) {
        Some(resource) => {
            let runs = state.runs.read().await;
            let progress = runs
                .values()
                .filter(|r| r.entity_id == id)
                .map(|r| r.progress)
                .fold(0.0, |a, b| a.max(b));

            Json(ApiResponse {
                code: 200,
                data: serde_json::json!({
                    "id": id,
                    "status": resource.status,
                    "progress": progress
                }),
            })
            .into_response()
        }
        None => (
            StatusCode::NOT_FOUND,
            Json(ErrorResponse {
                code: 404,
                error: "Resource not found".to_string(),
                details: Some(format!("资源 {} 不存在", id)),
            }),
        )
            .into_response(),
    }
}

async fn batch_operation(
    State(state): State<AppState>,
    Json(payload): Json<BatchRequest>,
) -> impl IntoResponse {
    let mut results = Vec::new();
    let mut resources = state.resources.write().await;

    for op in payload.operations {
        let success = resources.contains_key(&op.id);
        let message = if success {
            if op.action == "restart" {
                if let Some(res) = resources.get_mut(&op.id) {
                    res.status = "restarting".to_string();
                    res.updated_at = Utc::now();
                }
                Some("重启操作已提交".to_string())
            } else {
                Some(format!("操作 {} 已执行", op.action))
            }
        } else {
            Some(format!("资源 {} 不存在", op.id))
        };

        results.push(BatchResult {
            id: op.id.clone(),
            action: op.action.clone(),
            success,
            message,
        });
    }

    let batch_id = format!("batch_{}", Uuid::new_v4().simple().to_string()[..8].to_string());

    Json(ApiResponse {
        code: 200,
        data: BatchResponseData {
            batch_id,
            results,
        },
    })
}
