use std::net::SocketAddr;
use axum::{routing::get, Router, middleware};
use tracing::{info, error};
use edge_scheduler::{EdgeSchedulerApp, init_tracing, api};
use crate::api::middleware::{request_tracing, cors, request_timeout};
use crate::api::resources::AppState as ResourceAppState;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    init_tracing();

    info!("Starting EdgeScheduler...");

    let app = EdgeSchedulerApp::new().await;
    app.start_background_tasks().await;

    let resource_state = ResourceAppState::new(
        app.resource_repo.clone(),
        app.event_publisher.clone(),
        app.audit_logger.clone(),
    );

    let v1_routes = Router::new()
        .merge(api::resources::routes(resource_state))
        .merge(crate::modules::device_shadow::handler::routes(app.device_shadow_service.clone()))
        .merge(crate::modules::rule_engine::handler::routes(app.rule_engine_service.clone()))
        .merge(crate::modules::inference_scheduler::handler::routes(app.inference_scheduler_service.clone()))
        .merge(crate::modules::device_lifecycle::handler::routes(app.device_lifecycle_service.clone()))
        .merge(crate::modules::data_aggregation::handler::routes(app.data_aggregation_service.clone()))
        .merge(crate::modules::offline_cache::handler::routes(app.offline_cache_service.clone()))
        .merge(crate::modules::ota_upgrade::handler::routes(app.ota_upgrade_service.clone()))
        .merge(crate::modules::protocol_adapter::handler::routes(app.protocol_adapter_service.clone()))
        .merge(api::health::routes(app.health_checker.clone()));

    let app_router = Router::new()
        .nest("/api/v1", v1_routes)
        .route("/", get(root_handler))
        .layer(middleware::from_fn(request_tracing))
        .layer(middleware::from_fn(cors))
        .layer(middleware::from_fn(request_timeout));

    let addr: SocketAddr = "0.0.0.0:8080".parse()?;
    info!("EdgeScheduler listening on {}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app_router)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .map_err(|e| {
            error!(error = %e, "Server error");
            e
        })?;

    Ok(())
}

async fn root_handler() -> &'static str {
    "EdgeScheduler v0.1.0 - 边缘计算调度平台"
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
        _ = ctrl_c => {
            info!("Ctrl+C received, shutting down gracefully...");
        }
        _ = terminate => {
            info!("SIGTERM received, shutting down gracefully...");
        }
    }
}
