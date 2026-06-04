use std::sync::Arc;
use tokio::sync::RwLock;
use tokio::time::interval;
use std::time::Duration;

use common::error::{CdnResult};
use common::models::NodeMetrics;
use node_manager::NodeRegistry;

use crate::metrics::MetricsStore;
use crate::anomaly::AnomalyDetector;

pub struct MetricsCollector {
    registry: NodeRegistry,
    metrics_store: MetricsStore,
    anomaly_detector: AnomalyDetector,
    running: Arc<RwLock<bool>>,
    collection_interval: Duration,
}

impl MetricsCollector {
    pub fn new(
        registry: NodeRegistry,
        metrics_store: MetricsStore,
        anomaly_detector: AnomalyDetector,
        collection_interval_seconds: u64,
    ) -> Self {
        MetricsCollector {
            registry,
            metrics_store,
            anomaly_detector,
            running: Arc::new(RwLock::new(false)),
            collection_interval: Duration::from_secs(collection_interval_seconds),
        }
    }

    pub async fn start(&self) -> CdnResult<()> {
        let mut running = self.running.write().await;
        if *running {
            return Ok(());
        }
        *running = true;
        drop(running);

        let collector = self.clone();
        tokio::spawn(async move {
            if let Err(e) = collector.run_collection().await {
                tracing::error!("Metrics collector failed: {}", e);
            }
        });

        tracing::info!("Metrics collector started");
        Ok(())
    }

    pub async fn stop(&self) -> CdnResult<()> {
        let mut running = self.running.write().await;
        *running = false;
        tracing::info!("Metrics collector stopped");
        Ok(())
    }

    async fn run_collection(&self) -> CdnResult<()> {
        let mut ticker = interval(self.collection_interval);

        loop {
            ticker.tick().await;

            let running = self.running.read().await;
            if !*running {
                break;
            }
            drop(running);

            self.collect_metrics().await?;
        }

        Ok(())
    }

    async fn collect_metrics(&self) -> CdnResult<()> {
        let nodes = self.registry.list_nodes().await?;

        for node in nodes {
            let bandwidth_utilization = if node.bandwidth_capacity > 0 {
                node.bandwidth_usage / node.bandwidth_capacity as f64
            } else {
                0.0
            };
            
            let metrics = NodeMetrics {
                id: common::utils::generate_id(),
                node_id: node.id,
                timestamp: chrono::Utc::now(),
                qps: 0.0,
                bandwidth_usage: node.bandwidth_usage,
                cache_hit_rate: 0.0,
                origin_fetch_rate: 0.0,
                error_rate_4xx: 0.0,
                error_rate_5xx: 0.0,
                active_connections: 0,
                memory_usage: node.current_load,
                cpu_usage: node.current_load,
            };

            self.metrics_store.record_metrics(metrics.clone()).await?;
            self.anomaly_detector.analyze(metrics).await?;
        }

        Ok(())
    }
}

impl Clone for MetricsCollector {
    fn clone(&self) -> Self {
        MetricsCollector {
            registry: self.registry.clone(),
            metrics_store: self.metrics_store.clone(),
            anomaly_detector: self.anomaly_detector.clone(),
            running: self.running.clone(),
            collection_interval: self.collection_interval,
        }
    }
}
