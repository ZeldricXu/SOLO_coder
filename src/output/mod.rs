pub mod kafka_sink;
pub mod minio_sink;
pub mod dashboard;
pub mod clickhouse_sink;

use crate::config::ConfigHandle;
use crate::output::kafka_sink::KafkaSink;
use crate::output::minio_sink::MinIOSink;
use crate::output::clickhouse_sink::ClickHouseSink;
use crate::aggregator::AggregationEngine;
use crate::output::dashboard::Dashboard;
use std::sync::Arc;
use tracing::info;

pub struct OutputManager {
    pub kafka: KafkaSink,
    pub minio: MinIOSink,
    pub clickhouse: Option<ClickHouseSink>,
}

impl OutputManager {
    pub async fn new(config: ConfigHandle) -> Self {
        let kafka = KafkaSink::new(config.clone());
        kafka.init_from_config().await;

        let (minio_cfg, clickhouse_cfg) = {
            let cfg = config.read().await;
            (cfg.sink.minio.clone(), cfg.sink.clickhouse.clone())
        };
        let mut minio = MinIOSink::new(minio_cfg);
        minio.init().await;

        let clickhouse = match clickhouse_cfg {
            Some(cfg) => {
                info!("Initializing ClickHouse sink at {}", cfg.url);
                match ClickHouseSink::new(cfg).await {
                    Ok(ch) => Some(ch),
                    Err(e) => {
                        tracing::warn!("Failed to initialize ClickHouse sink: {}", e);
                        None
                    }
                }
            }
            None => {
                info!("No ClickHouse sink configured - skipping");
                None
            }
        };

        Self { kafka, minio, clickhouse }
    }

    pub fn maybe_start_dashboard(config: ConfigHandle, agg: Arc<AggregationEngine>) -> Option<tokio::task::JoinHandle<()>> {
        let rt = tokio::runtime::Handle::try_current().ok();
        if rt.is_none() {
            return None;
        }
        let enable = {
            let cfg = if let Ok(rt) = std::panic::catch_unwind(|| {
                tokio::runtime::Handle::try_current()
            }) {
                rt.ok()
            } else {
                None
            };
            if cfg.is_none() {
                return None;
            }
            let _ = cfg;
            true
        };
        if !enable {
            return None;
        }
        let enable_dashboard: bool = {
            // Don't block; just default to true since blocking here is risky
            true
        };
        if !enable_dashboard {
            return None;
        }
        let dash = Dashboard::new(agg);
        let handle = tokio::spawn(async move {
            let _ = dash.run().await;
        });
        Some(handle)
    }
}
