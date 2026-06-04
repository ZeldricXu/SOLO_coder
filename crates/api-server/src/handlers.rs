use axum::{
    extract::{Path, State},
    Json,
    response::IntoResponse,
    http::StatusCode,
};
use serde_json::json;
use uuid::Uuid;
use serde::Deserialize;

use common::models::{Heartbeat, SchedulingRequest, NodeRegistration, SchedulingStrategy, ContentType};
use common::utils::content_type_from_url;
use crate::server::AppState;

pub async fn health_check_handler() -> impl IntoResponse {
    Json(json!({ "status": "ok", "timestamp": chrono::Utc::now().to_rfc3339() }))
}

pub async fn list_nodes_handler(State(state): State<AppState>) -> impl IntoResponse {
    match state.node_manager.registry.list_nodes().await {
        Ok(nodes) => (StatusCode::OK, Json(json!({ "nodes": nodes, "count": nodes.len() }))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn register_node_handler(
    State(state): State<AppState>,
    Json(reg): Json<NodeRegistration>,
) -> impl IntoResponse {
    match state.node_manager.registry.register_node(reg).await {
        Ok(registered) => (StatusCode::CREATED, Json(json!({ "node": registered }))),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn get_node_handler(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    match Uuid::parse_str(&id) {
        Ok(uuid) => {
            match state.node_manager.registry.get_node(uuid).await {
                Ok(Some(node)) => (StatusCode::OK, Json(json!({ "node": node }))),
                Ok(None) => (StatusCode::NOT_FOUND, Json(json!({ "error": "Node not found" }))),
                Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
            }
        }
        Err(_) => (StatusCode::BAD_REQUEST, Json(json!({ "error": "Invalid node ID" }))),
    }
}

pub async fn delete_node_handler(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    match Uuid::parse_str(&id) {
        Ok(uuid) => {
            match state.node_manager.registry.deregister_node(uuid).await {
                Ok(_) => (StatusCode::OK, Json(json!({ "message": "Node deleted" }))),
                Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
            }
        }
        Err(_) => (StatusCode::BAD_REQUEST, Json(json!({ "error": "Invalid node ID" }))),
    }
}

pub async fn heartbeat_handler(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(heartbeat): Json<Heartbeat>,
) -> impl IntoResponse {
    match Uuid::parse_str(&id) {
        Ok(uuid) => {
            let _ = uuid;
            match state.node_manager.registry.process_heartbeat(heartbeat).await {
                Ok(_) => (StatusCode::OK, Json(json!({ "status": "received" }))),
                Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e.to_string() }))),
            }
        }
        Err(_) => (StatusCode::BAD_REQUEST, Json(json!({ "error": "Invalid node ID" }))),
    }
}

pub async fn schedule_handler(
    State(state): State<AppState>,
    Json(request): Json<SchedulingRequest>,
) -> impl IntoResponse {
    let client_ip = request.client_ip.as_deref().unwrap_or("");
    match state.scheduler.schedule(request.strategy, client_ip, &request.domain, &request.path).await {
        Ok(decision) => (StatusCode::OK, Json(json!({ "node": decision }))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

#[derive(Deserialize)]
pub struct ContentAwareScheduleRequest {
    pub client_ip: Option<String>,
    pub domain: String,
    pub path: String,
    pub content_type: Option<ContentType>,
}

pub async fn schedule_content_aware_handler(
    State(state): State<AppState>,
    Json(request): Json<ContentAwareScheduleRequest>,
) -> impl IntoResponse {
    let client_ip = request.client_ip.as_deref().unwrap_or("");
    let ct = request.content_type.unwrap_or_else(|| content_type_from_url(&request.path));
    match state.scheduler.schedule_by_content_type(client_ip, &request.domain, &request.path, Some(ct.clone())).await {
        Ok(decision) => (StatusCode::OK, Json(json!({ "node": decision, "content_type": format!("{:?}", ct) }))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn get_metrics_handler(State(state): State<AppState>) -> impl IntoResponse {
    let metrics = state.metrics_store.get_all_latest_metrics().await;
    (StatusCode::OK, Json(json!({ "metrics": metrics })))
}

pub async fn get_node_metrics_handler(
    State(state): State<AppState>,
    Path(node_id): Path<String>,
) -> impl IntoResponse {
    match Uuid::parse_str(&node_id) {
        Ok(uuid) => {
            match state.metrics_store.get_latest_metrics(uuid).await {
                Some(metrics) => (StatusCode::OK, Json(json!({ "metrics": metrics }))),
                None => (StatusCode::NOT_FOUND, Json(json!({ "error": "Metrics not found" }))),
            }
        }
        Err(_) => (StatusCode::BAD_REQUEST, Json(json!({ "error": "Invalid node ID" }))),
    }
}

pub async fn list_alerts_handler(State(state): State<AppState>) -> impl IntoResponse {
    let alerts = state.alert_manager.get_active_alerts().await;
    (StatusCode::OK, Json(json!({ "alerts": alerts })))
}

pub async fn list_domains_handler(State(state): State<AppState>) -> impl IntoResponse {
    match state.config_manager.get_all_domains().await {
        Ok(domains) => (StatusCode::OK, Json(json!({ "domains": domains }))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn list_certificates_handler() -> impl IntoResponse {
    (StatusCode::OK, Json(json!({ "certificates": [] })))
}

pub async fn get_certificate_handler(
    State(state): State<AppState>,
    Path(domain): Path<String>,
) -> impl IntoResponse {
    match state.tls_manager.get_certificate(&domain).await {
        Some(cert) => (StatusCode::OK, Json(json!({ "certificate": cert }))),
        None => (StatusCode::NOT_FOUND, Json(json!({ "error": "Certificate not found" }))),
    }
}

pub async fn generate_preheat_plan_handler(State(_state): State<AppState>) -> impl IntoResponse {
    let planner = smart_preheat::PreheatPlanner::new(
        smart_preheat::ExponentialSmoothing::new(0.3),
        100_000_000,
    );
    let plan = planner.generate_plan().await;
    (StatusCode::OK, Json(json!({ "plan": plan })))
}

pub async fn execute_preheat_plan_handler(State(state): State<AppState>) -> impl IntoResponse {
    match state.preheat_executor.generate_and_execute().await {
        Ok(plan) => (StatusCode::OK, Json(json!({ "plan": plan }))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e }))),
    }
}

pub async fn get_running_preheat_plans_handler(State(state): State<AppState>) -> impl IntoResponse {
    let plans = state.preheat_executor.get_running_plans().await;
    (StatusCode::OK, Json(json!({ "plans": plans })))
}

#[derive(Deserialize)]
pub struct CreateExperimentRequest {
    pub name: String,
    pub description: String,
    pub control_strategy: SchedulingStrategy,
    pub treatment_strategy: SchedulingStrategy,
    pub traffic_percentage: u32,
    pub target_nodes: Vec<Uuid>,
}

pub async fn create_experiment_handler(
    State(state): State<AppState>,
    Json(req): Json<CreateExperimentRequest>,
) -> impl IntoResponse {
    let id = state.experiment_manager.create_experiment(
        req.name,
        req.description,
        req.control_strategy,
        req.treatment_strategy,
        req.traffic_percentage,
        req.target_nodes,
    ).await;
    (StatusCode::CREATED, Json(json!({ "experiment_id": id })))
}

pub async fn list_experiments_handler(State(state): State<AppState>) -> impl IntoResponse {
    let experiments = state.experiment_manager.list_experiments().await;
    (StatusCode::OK, Json(json!({ "experiments": experiments })))
}

pub async fn get_experiment_handler(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    match Uuid::parse_str(&id) {
        Ok(uuid) => {
            match state.experiment_manager.get_experiment(uuid).await {
                Some(experiment) => (StatusCode::OK, Json(json!({ "experiment": experiment }))),
                None => (StatusCode::NOT_FOUND, Json(json!({ "error": "Experiment not found" }))),
            }
        }
        Err(_) => (StatusCode::BAD_REQUEST, Json(json!({ "error": "Invalid experiment ID" }))),
    }
}

#[derive(Deserialize)]
pub struct ControlExperimentRequest {
    pub action: String,
}

pub async fn control_experiment_handler(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(req): Json<ControlExperimentRequest>,
) -> impl IntoResponse {
    match Uuid::parse_str(&id) {
        Ok(uuid) => {
            let result = match req.action.as_str() {
                "start" => state.experiment_manager.start_experiment(uuid).await,
                "pause" => state.experiment_manager.pause_experiment(uuid).await,
                "complete" => state.experiment_manager.complete_experiment(uuid).await,
                _ => Err(format!("Unknown action: {}", req.action)),
            };
            match result {
                Ok(_) => (StatusCode::OK, Json(json!({ "message": format!("Experiment {} {}", uuid, req.action) }))),
                Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))),
            }
        }
        Err(_) => (StatusCode::BAD_REQUEST, Json(json!({ "error": "Invalid experiment ID" }))),
    }
}

#[derive(Deserialize)]
pub struct RecordExperimentMetricRequest {
    pub group: ab_testing::ExperimentGroup,
    pub cache_hit_rate: f64,
    pub avg_latency_ms: f64,
    pub origin_fetch_rate: f64,
    pub user_qoe_score: f64,
}

pub async fn record_experiment_metric_handler(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(req): Json<RecordExperimentMetricRequest>,
) -> impl IntoResponse {
    match Uuid::parse_str(&id) {
        Ok(uuid) => {
            state.ab_metrics_collector.record_metric(
                uuid,
                req.group,
                req.cache_hit_rate,
                req.avg_latency_ms,
                req.origin_fetch_rate,
                req.user_qoe_score,
            ).await;
            (StatusCode::OK, Json(json!({ "status": "recorded" })))
        }
        Err(_) => (StatusCode::BAD_REQUEST, Json(json!({ "error": "Invalid experiment ID" }))),
    }
}

pub async fn get_experiment_metrics_handler(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> impl IntoResponse {
    match Uuid::parse_str(&id) {
        Ok(uuid) => {
            let metrics = state.ab_metrics_collector.get_metrics(uuid).await;
            (StatusCode::OK, Json(json!({ "metrics": metrics })))
        }
        Err(_) => (StatusCode::BAD_REQUEST, Json(json!({ "error": "Invalid experiment ID" }))),
    }
}

#[derive(Deserialize)]
pub struct AnalyzeExperimentRequest {
    pub metric_name: String,
}

pub async fn analyze_experiment_handler(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(req): Json<AnalyzeExperimentRequest>,
) -> impl IntoResponse {
    match Uuid::parse_str(&id) {
        Ok(uuid) => {
            match state.experiment_manager.get_experiment(uuid).await {
                Some(experiment) => {
                    let result = ab_testing::analyze_experiment(&experiment, &req.metric_name);
                    (StatusCode::OK, Json(json!({ "analysis": result })))
                }
                None => (StatusCode::NOT_FOUND, Json(json!({ "error": "Experiment not found" }))),
            }
        }
        Err(_) => (StatusCode::BAD_REQUEST, Json(json!({ "error": "Invalid experiment ID" }))),
    }
}
