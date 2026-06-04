use std::sync::Arc;

use clap::Parser;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

use common::config::AppConfig;
use common::db::Database;
use common::redis::RedisClient;
use node_manager::NodeManager;
use scheduler::{TrafficScheduler, GeoLocationResolver};
use cache_engine::CacheEngine;
use monitoring::{MetricsStore, AlertManager, MetricsCollector, AnomalyDetector};
use config_manager::ConfigManager;
use tls_manager::TlsManager;
use smart_preheat::{PreheatExecutor, PreheatPlanner, ExponentialSmoothing, BandwidthThrottler};
use ab_testing::{ExperimentManager, MetricsCollector as AbMetricsCollector};
use api_server::{ApiServer, CdnMetrics};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(short, long)]
    config: Option<String>,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "cdn_edge_system=info,tower_http=debug".into()),
        )
        .with(tracing_subscriber::fmt::layer())
        .init();

    let _args = Args::parse();

    let config = AppConfig::default();

    tracing::info!("Initializing CDN Center Node...");

    let db = Database::new(&config.database).await?;
    tracing::info!("Database connected");

    let redis = RedisClient::new(&config.redis)?;
    tracing::info!("Redis connected");

    let node_manager = NodeManager::new(config.clone(), db.clone(), redis.clone()).await?;
    node_manager.start().await?;
    tracing::info!("Node manager started");

    let geo_resolver = GeoLocationResolver::new();
    let scheduler = TrafficScheduler::new(node_manager.registry.clone(), geo_resolver);
    tracing::info!("Traffic scheduler initialized");

    let cache_engine = CacheEngine::new(1024 * 1024 * 1024);
    tracing::info!("Cache engine initialized");

    let alert_manager = AlertManager::new(db.clone());
    let anomaly_detector = AnomalyDetector::new(alert_manager.clone(), config.monitoring.clone());
    let metrics_store = MetricsStore::new(db.clone(), redis.clone());
    let metrics_collector = MetricsCollector::new(
        node_manager.registry.clone(),
        metrics_store.clone(),
        anomaly_detector,
        10,
    );
    metrics_collector.start().await?;
    tracing::info!("Metrics collector started");

    let config_manager = ConfigManager::new(db.clone(), redis.clone());
    tracing::info!("Config manager initialized");

    let tls_manager = TlsManager::new(db.clone(), config.tls.clone());
    tracing::info!("TLS manager initialized");

    let preheat_predictor = ExponentialSmoothing::new(0.3);
    let preheat_planner = PreheatPlanner::new(preheat_predictor, 100_000_000);
    let preheat_throttler = BandwidthThrottler::new(100_000_000);
    let preheat_executor = PreheatExecutor::new(
        preheat_planner,
        preheat_throttler,
        cache_engine.clone(),
        node_manager.registry.clone(),
        db.clone(),
        redis.clone(),
    );
    tracing::info!("Smart preheat executor initialized");

    let experiment_manager = ExperimentManager::new();
    let ab_metrics_collector = AbMetricsCollector::new();
    tracing::info!("A/B testing framework initialized");

    let cdn_metrics = Arc::new(CdnMetrics::new());

    let api_server = ApiServer::new(
        config,
        node_manager,
        scheduler,
        cache_engine,
        metrics_store,
        alert_manager,
        config_manager,
        tls_manager,
        preheat_executor,
        experiment_manager,
        ab_metrics_collector,
        cdn_metrics,
    );

    tracing::info!("Starting API server...");
    api_server.start().await?;

    Ok(())
}
