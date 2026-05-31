use std::sync::Arc;
use data_transformer::*;
use tracing::info;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let config = AppConfig::load()?;
    
    logging::init_logging_with_config(&config.logging)?;
    
    info!(
        "初始化数据转换与标准化中间件服务 - 环境: {}",
        AppConfig::environment()
    );
    
    let data_access = Arc::new(DataAccessLayer::with_config(&config.database, &config.redis, &config.cache).await?);
    let event_store = Arc::new(EventStore::new(data_access.clone(), &config.event_store).await?);
    let core_processor = Arc::new(CoreProcessor::new());
    let fault_injector = Arc::new(FaultInjectionOrchestrator::new(event_store.clone(), &config.fault_injection));
    let traffic_controller = Arc::new(TrafficController::new(event_store.clone(), &config.traffic_control, &config.circuit_breaker));
    let metrics = Arc::new(MetricsCollector::with_config(&config.metrics));
    let sidecar_manager = Arc::new(SidecarManager::new(event_store.clone(), &config.sidecar));
    let scheduler = Arc::new(TaskScheduler::new(event_store.clone(), &config.scheduler));
    
    let gateway_rate_limit = gateway::RateLimitConfig {
        requests_per_minute: config.rate_limit.per_minute,
        requests_per_hour: config.rate_limit.per_hour,
        requests_per_day: config.rate_limit.per_day,
        burst_size: config.rate_limit.burst_size,
    };
    
    let gateway = Arc::new(ApiGateway::new(
        config.jwt.secret.clone(),
        gateway_rate_limit,
    ));
    
    if config.is_development() {
        gateway.register_user(
            "admin",
            "admin123",
            "admin@example.com",
            vec!["admin".to_string()],
        )?;
        info!("开发环境默认管理员账号已创建: admin / admin123");
    }
    
    let app_state = api::AppState {
        data_access,
        event_store,
        core_processor,
        fault_injector,
        traffic_controller,
        metrics,
        sidecar_manager,
        scheduler,
        gateway,
    };
    
    let router = api::create_router(app_state);
    let listener = tokio::net::TcpListener::bind(format!("{}:{}", config.server.host, config.server.port)).await?;
    
    info!(
        "服务器启动成功 - 地址: {}:{}, 版本: {}, 环境: {}",
        config.server.host,
        config.server.port,
        env!("CARGO_PKG_VERSION"),
        AppConfig::environment()
    );
    
    axum::serve(listener, router)
        .with_graceful_shutdown(shutdown_signal(config.server.shutdown_timeout))
        .await?;
    
    info!("服务器优雅关闭完成");
    Ok(())
}

async fn shutdown_signal(timeout_seconds: u64) {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install signal handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {
            tracing::info!("收到Ctrl+C信号，开始优雅关闭...");
        }
        _ = terminate => {
            tracing::info!("收到SIGTERM信号，开始优雅关闭...");
        }
    }

    tracing::info!("等待 {} 秒完成正在处理的请求...", timeout_seconds);
    tokio::time::sleep(std::time::Duration::from_secs(timeout_seconds)).await;
}

