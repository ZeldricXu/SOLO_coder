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
        )
        .route(
            "/{model_name}/rollout",
            post(models::start_rollout)
                .delete(models::cancel_rollout)
                .get(models::get_rollout_status),
        )
        .route(
            "/{model_name}/rollout/pause",
            post(models::pause_rollout),
        )
        .route(
            "/{model_name}/rollout/resume",
            post(models::resume_rollout),
        );

    let pipeline_routes = Router::new()
        .route("/", post(models::create_pipeline).get(models::list_pipelines))
        .route("/{name}", delete(models::delete_pipeline))
        .route(
            "/{pipeline_name}/execute",
            post(inference::execute_pipeline),
        );

    let scheduler_routes = Router::new()
        .route("/heat", get(models::get_all_heat_scores))
        .route("/heat/{version_id}", get(models::get_model_heat_score));

    Router::new()
        .nest("/inference", inference_routes)
        .nest("/models", model_routes)
        .nest("/pipelines", pipeline_routes)
        .nest("/scheduler", scheduler_routes)
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
