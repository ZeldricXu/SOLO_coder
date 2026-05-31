use enterprise_middleware::api::{create_router, AppState};
use enterprise_middleware::config::ConfigManager;
use enterprise_middleware::core::{
    InMemoryEventEmitter, MetricsRecorder, RequestHandler,
};
use enterprise_middleware::data_quality::QualityRuleManager;
use enterprise_middleware::lineage::{create_lineage_manager};
use enterprise_middleware::logging::StructuredLogger;
use enterprise_middleware::metadata_crawler::MetadataCrawler;
use enterprise_middleware::notification::create_notification_manager;
use enterprise_middleware::storage::StorageManager;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::signal;
use tower_http::cors::{Any, CorsLayer};
use tower_http::trace::TraceLayer;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let _ = dotenvy::dotenv();

    let config_manager = ConfigManager::new();
    let app_config = config_manager.load()?;

    let logger = StructuredLogger::new(app_config.logging.clone());
    logger.init()?;

    tracing::info!(
        target: "startup",
        "启动 {} 版本 {}",
        enterprise_middleware::name(),
        enterprise_middleware::version()
    );

    let storage_manager = StorageManager::new(&app_config.storage)?;
    let storage_manager = Arc::new(storage_manager);

    let emitter = Arc::new(InMemoryEventEmitter::new());
    let metrics = Arc::new(MetricsRecorder::new());
    let request_handler = RequestHandler::new(10, emitter, metrics)
        .with_timeout(30000)
        .with_retries(3);

    let notification_manager = create_notification_manager(app_config.notification.clone())?;

    let lineage_manager = create_lineage_manager(app_config.lineage.clone());

    let quality_rule_manager = QualityRuleManager::new(app_config.data_quality.clone());

    let metadata_crawler = MetadataCrawler::new(app_config.metadata_crawler.clone());

    let app_state = AppState::new(
        app_config.clone(),
        request_handler,
        notification_manager,
        lineage_manager,
        quality_rule_manager,
        metadata_crawler,
    );

    let router = create_router(app_state)
        .layer(
            CorsLayer::new()
                .allow_origin(Any)
                .allow_methods(Any)
                .allow_headers(Any),
        )
        .layer(TraceLayer::new_for_http());

    let addr = SocketAddr::from((
        app_config.server.host.parse().unwrap_or(std::net::IpAddr::V4(std::net::Ipv4Addr::new(0, 0, 0, 0))),
        app_config.server.port,
    ));

    tracing::info!(
        target: "startup",
        "监听地址: http://{}",
        addr
    );

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, router)
        .with_graceful_shutdown(shutdown_signal(app_config.server.shutdown_timeout))
        .await?;

    tracing::info!(target: "shutdown", "服务已正常关闭");

    Ok(())
}

async fn shutdown_signal(timeout_seconds: u64) {
    let ctrl_c = async {
        signal::ctrl_c()
            .await
            .expect("Failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        signal::unix::signal(signal::unix::SignalKind::terminate())
            .expect("Failed to install signal handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {
            tracing::info!(target: "shutdown", "收到 Ctrl+C 信号，开始优雅关闭");
        }
        _ = terminate => {
            tracing::info!(target: "shutdown", "收到 SIGTERM 信号，开始优雅关闭");
        }
    }

    tokio::time::sleep(std::time::Duration::from_secs(timeout_seconds)).await;
}
