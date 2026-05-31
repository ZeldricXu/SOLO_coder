use axum::{
    routing::{get, post, delete, put},
    Router,
    middleware,
};
use std::sync::Arc;
use tower_http::cors::{CorsLayer, Any};

use crate::infra::app_state::AppState;
use crate::service::orchestrator::ServiceOrchestrator;
use crate::service::resource_manager::ResourceManager;

use super::handlers as h;
use super::middleware as m;

#[derive(Clone)]
pub struct ApiState {
    pub app_state: AppState,
    pub orchestrator: Arc<ServiceOrchestrator>,
    pub resource_manager: Arc<ResourceManager>,
}

impl ApiState {
    pub fn new(app_state: AppState) -> Self {
        let orchestrator = Arc::new(ServiceOrchestrator::new(app_state.clone()));
        let resource_manager = Arc::new(ResourceManager::new(app_state.clone()));

        Self {
            app_state,
            orchestrator,
            resource_manager,
        }
    }
}

pub fn create_router(app_state: AppState) -> Router {
    let api_state = ApiState::new(app_state);

    Router::new()
        .route("/health", get(h::health_check))
        .nest("/api/v1", api_v1_routes())
        .with_state(api_state)
        .layer(CorsLayer::new()
            .allow_origin(Any)
            .allow_methods(Any)
            .allow_headers(Any)
        )
        .layer(middleware::from_fn(m::request_logger))
        .layer(middleware::from_fn(m::request_id))
}

fn api_v1_routes() -> Router<ApiState> {
    Router::new()
        .route("/resources", post(h::create_resource))
        .route("/resources/:id/status", get(h::get_resource_status))
        .route("/resources/batch", post(h::batch_operation))
        .nest("/tee", tee_routes())
        .nest("/masking", masking_routes())
        .nest("/federated", federated_routes())
        .nest("/mpc", mpc_routes())
        .nest("/classification", classification_routes())
        .nest("/dp", dp_routes())
        .nest("/audit", audit_routes())
        .nest("/sharding", sharding_routes())
}

fn tee_routes() -> Router<ApiState> {
    Router::new()
        .route("/enclaves", post(h::tee::create_enclave))
        .route("/enclaves", get(h::tee::list_enclaves))
        .route("/enclaves/:id", get(h::tee::get_enclave))
        .route("/enclaves/:id/start", put(h::tee::start_enclave))
        .route("/enclaves/:id/stop", put(h::tee::stop_enclave))
        .route("/enclaves/:id/terminate", delete(h::tee::terminate_enclave))
        .route("/enclaves/:id/attest", post(h::tee::generate_attestation))
        .route("/enclaves/:id/execute", post(h::tee::execute_secure_function))
        .route("/enclaves/:id/heartbeat", post(h::tee::heartbeat))
        .route("/cache/status", get(h::tee::get_cache_status))
        .route("/cache/invalidate", post(h::tee::invalidate_cache))
        .route("/cache/warmup", post(h::tee::warm_up_cache))
        .route("/cache/reset", post(h::tee::reset_cache_stats))
}

fn masking_routes() -> Router<ApiState> {
    Router::new()
        .route("/mask", post(h::masking::mask_data))
        .route("/rules", get(h::masking::list_rules))
        .route("/batch", post(h::masking::batch_mask))
        .route("/batch/fields", post(h::masking::mask_field_batch))
        .route("/batch/json", post(h::masking::mask_json_batch))
        .route("/batch/metrics", get(h::masking::get_batch_metrics))
        .route("/batch/config", get(h::masking::get_batch_config))
        .route("/batch/config", put(h::masking::update_batch_config))
}

fn federated_routes() -> Router<ApiState> {
    Router::new()
        .route("/tasks", post(h::federated::create_task))
        .route("/tasks", get(h::federated::list_tasks))
        .route("/tasks/:id", get(h::federated::get_task))
        .route("/tasks/:id/register", post(h::federated::register_participant))
        .route("/tasks/:id/gradient", post(h::federated::submit_gradient))
        .route("/tasks/:id/aggregate", post(h::federated::aggregate_gradients))
        .route("/tasks/:id/performance", get(h::federated::get_task_performance))
        .route("/tasks/:id/latency", get(h::federated::get_latency_breakdown))
        .route("/metrics", get(h::federated::get_global_metrics))
        .route("/metrics/prometheus", get(h::federated::export_prometheus_metrics))
        .route("/metrics/reset", post(h::federated::reset_metrics))
}

fn mpc_routes() -> Router<ApiState> {
    Router::new()
        .route("/sessions", post(h::mpc::create_session))
        .route("/sessions", get(h::mpc::list_sessions))
        .route("/sessions/:id", get(h::mpc::get_session))
        .route("/sessions/:id/input", post(h::mpc::submit_input))
        .route("/sessions/:id/compute", post(h::mpc::execute_computation))
        .route("/circuits", post(h::mpc::create_garbled_circuit))
        .route("/ot", post(h::mpc::create_oblivious_transfer))
}

fn classification_routes() -> Router<ApiState> {
    Router::new()
        .route("/classify", post(h::classification::classify_data))
        .route("/reports", get(h::classification::list_reports))
        .route("/reports/:id", get(h::classification::get_report))
        .route("/patterns", get(h::classification::list_patterns))
        .route("/policies", post(h::classification::create_policy))
}

fn dp_routes() -> Router<ApiState> {
    Router::new()
        .route("/apply", post(h::dp::apply_dp))
        .route("/budgets", post(h::dp::create_budget))
        .route("/budgets/:user_id", get(h::dp::get_budget))
        .route("/history", get(h::dp::get_query_history))
}

fn audit_routes() -> Router<ApiState> {
    Router::new()
        .route("/logs", post(h::audit::log_event))
        .route("/logs", get(h::audit::query_logs))
        .route("/logs/:id", get(h::audit::get_log))
        .route("/blocks", get(h::audit::list_blocks))
        .route("/blocks/:height", get(h::audit::get_block))
        .route("/blocks/seal", post(h::audit::seal_block))
        .route("/integrity", get(h::audit::verify_integrity))
}

fn sharding_routes() -> Router<ApiState> {
    Router::new()
        .route("/keys", post(h::sharding::create_sharded_key))
        .route("/keys", get(h::sharding::list_keys))
        .route("/keys/:id", get(h::sharding::get_key))
        .route("/keys/:id", delete(h::sharding::delete_key))
        .route("/keys/:id/reconstruct", post(h::sharding::reconstruct_key))
        .route("/keys/:id/shares", get(h::sharding::get_shares))
        .route("/shares/:id", get(h::sharding::get_share))
        .route("/shares/rotate", post(h::sharding::rotate_share))
}
