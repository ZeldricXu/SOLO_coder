use std::sync::Arc;

use axum::{Router, routing::{get, post}};
use common::error::{CdnResult};
use common::config::AppConfig;
use node_manager::NodeManager;
use scheduler::TrafficScheduler;
use cache_engine::CacheEngine;
use monitoring::{MetricsStore, AlertManager};
use config_manager::ConfigManager;
use tls_manager::TlsManager;
use smart_preheat::PreheatExecutor;
use ab_testing::{ExperimentManager, MetricsCollector as AbMetricsCollector};

use crate::handlers::*;
use crate::metrics::{CdnMetrics, metrics_handler};

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<AppConfig>,
    pub node_manager: NodeManager,
    pub scheduler: TrafficScheduler,
    pub cache_engine: CacheEngine,
    pub metrics_store: MetricsStore,
    pub alert_manager: AlertManager,
    pub config_manager: ConfigManager,
    pub tls_manager: TlsManager,
    pub preheat_executor: PreheatExecutor,
    pub experiment_manager: ExperimentManager,
    pub ab_metrics_collector: AbMetricsCollector,
    pub metrics: Arc<CdnMetrics>,
}

pub struct ApiServer {
    config: Arc<AppConfig>,
    state: AppState,
}

impl ApiServer {
    pub fn new(
        config: AppConfig,
        node_manager: NodeManager,
        scheduler: TrafficScheduler,
        cache_engine: CacheEngine,
        metrics_store: MetricsStore,
        alert_manager: AlertManager,
        config_manager: ConfigManager,
        tls_manager: TlsManager,
        preheat_executor: PreheatExecutor,
        experiment_manager: ExperimentManager,
        ab_metrics_collector: AbMetricsCollector,
        metrics: Arc<CdnMetrics>,
    ) -> Self {
        let config = Arc::new(config);
        let state = AppState {
            config: config.clone(),
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
            metrics,
        };

        ApiServer { config, state }
    }

    pub async fn start(&self) -> CdnResult<()> {
        let app = self.build_router();
        let addr = self.config.center.listen_addr;

        tracing::info!("API server listening on {}", addr);

        axum::Server::bind(&addr)
            .serve(app.into_make_service())
            .await
            .map_err(|e| common::error::CdnError::InternalError(e.to_string()))?;

        Ok(())
    }

    fn build_router(&self) -> Router {
        Router::new()
            .route("/health", get(health_check_handler))
            .route("/api/v1/nodes", get(list_nodes_handler).post(register_node_handler))
            .route("/api/v1/nodes/:id", get(get_node_handler).delete(delete_node_handler))
            .route("/api/v1/nodes/:id/heartbeat", post(heartbeat_handler))
            .route("/api/v1/schedule", post(schedule_handler))
            .route("/api/v1/schedule/content-aware", post(schedule_content_aware_handler))
            .route("/api/v1/metrics", get(get_metrics_handler))
            .route("/api/v1/metrics/:node_id", get(get_node_metrics_handler))
            .route("/api/v1/alerts", get(list_alerts_handler))
            .route("/api/v1/domains", get(list_domains_handler))
            .route("/api/v1/certificates", get(list_certificates_handler))
            .route("/api/v1/certificates/:domain", get(get_certificate_handler))
            .route("/api/v1/preheat/plan", post(generate_preheat_plan_handler))
            .route("/api/v1/preheat/execute", post(execute_preheat_plan_handler))
            .route("/api/v1/preheat/running", get(get_running_preheat_plans_handler))
            .route("/api/v1/experiments", post(create_experiment_handler).get(list_experiments_handler))
            .route("/api/v1/experiments/:id", get(get_experiment_handler).post(control_experiment_handler))
            .route("/api/v1/experiments/:id/metrics", post(record_experiment_metric_handler).get(get_experiment_metrics_handler))
            .route("/api/v1/experiments/:id/analyze", post(analyze_experiment_handler))
            .route("/metrics", get(metrics_handler))
            .with_state(self.state.clone())
    }
}
