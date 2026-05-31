use axum::{Json, State};
use std::sync::Arc;
use crate::common::metrics::{HealthChecker, HealthCheck};
use crate::common::response::ApiResponse;

pub async fn health_check(
    State(health_checker): State<Arc<HealthChecker>>,
) -> Json<ApiResponse<HealthCheck>> {
    let health = health_checker.check();
    Json(ApiResponse::success(health))
}

pub async fn ready_check() -> Json<ApiResponse<&'static str>> {
    Json(ApiResponse::success("ready"))
}

pub async fn metrics() -> Json<ApiResponse<&'static str>> {
    Json(ApiResponse::success("metrics endpoint"))
}

pub fn routes(health_checker: Arc<HealthChecker>) -> axum::Router {
    axum::Router::new()
        .route("/health", axum::routing::get(health_check))
        .route("/ready", axum::routing::get(ready_check))
        .route("/metrics", axum::routing::get(metrics))
        .with_state(health_checker)
}
