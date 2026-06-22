use axum::{
    Router,
    routing::{get, post, patch, delete},
    extract::State,
    http::StatusCode,
    response::IntoResponse,
    Json,
};

use crate::handlers::{inference, models};
use crate::state::AppState;

fn api_routes() -> Router<AppState> {
    let inference_routes = Router::new()
        .route("/sync", post(inference::sync_inference))
        .route("/stream", post(inference::stream_inference))
        .route("/batch", post(inference::batch_inference))
        .route("/status/{request_id}", get(inference::get_inference_status));

    let model_routes = Router::new()
        .route("/", post(models::register_model).get(models::list_models))
        .route("/{id_or_name}", get(models::get_model).delete(models::delete_model))
        .route(
            "/{model_id}/versions",
            post(models::upload_model_version).get(models::list_versions),
        )
        .route(
            "/{model_id}/versions/{version}",
            get(models::get_version),
        )
        .route(
            "/versions/{version_id}/status",
            patch(models::update_version_status),
        )
        .route(
            "/versions/{version_id}",
            delete(models::delete_version),
        );

    Router::new()
        .nest("/inference", inference_routes)
        .nest("/models", model_routes)
}

pub fn build_app(state: AppState) -> Router {
    let api = api_routes();

    let security_layer = security::SecurityLayer::simple(
        state.db_pool.clone(),
        state.redis_client.clone(),
    );

    Router::new()
        .nest("/api/v1", api)
        .route("/health", get(health_check))
        .route("/ready", get(readiness_check))
        .layer(security_layer)
        .with_state(state)
}

async fn health_check() -> &'static str {
    "OK"
}

async fn readiness_check(State(state): State<AppState>) -> impl IntoResponse {
    let db_ok = state.db_pool.ping().await.is_ok();
    let mut conn = state.redis_client.manager.clone();
    let redis_ok: bool = redis::cmd("PING")
        .query_async::<redis::aio::ConnectionManager, String>(&mut conn)
        .await
        .is_ok();

    if db_ok && redis_ok {
        (StatusCode::OK, Json(serde_json::json!({"status": "ready"})))
    } else {
        let mut details = Vec::new();
        if !db_ok { details.push("database"); }
        if !redis_ok { details.push("redis"); }
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(serde_json::json!({
                "status": "not_ready",
                "details": details
            })),
        )
    }
}
