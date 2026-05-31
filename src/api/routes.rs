use axum::{
    routing::{get, post},
    Router,
};
use super::handlers::*;

pub fn create_router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health_check))
        .route("/api/v1/stats", get(get_stats))
        .route("/api/v1/resources", post(create_resource))
        .route("/api/v1/resources/:id/status", get(get_resource_status))
        .route("/api/v1/resources/batch", post(batch_operations))
        .with_state(state)
}
