use std::sync::Arc;
use std::collections::HashMap;
use tokio::sync::RwLock;

use common::error::{CdnResult};
use common::models::{NodeMetrics, AlertType, AlertSeverity};
use common::config::MonitoringConfig;

use crate::alerts::AlertManager;

pub struct AnomalyDetector {
    alert_manager: AlertManager,
    config: MonitoringConfig,
    baselines: Arc<RwLock<HashMap<uuid::Uuid, MetricsBaseline>>>,
}

#[derive(Debug, Clone)]
struct MetricsBaseline {
    avg_qps: f64,
    avg_bandwidth: f64,
    sample_count: u64,
}

impl AnomalyDetector {
    pub fn new(alert_manager: AlertManager, config: MonitoringConfig) -> Self {
        AnomalyDetector {
            alert_manager,
            config,
            baselines: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn analyze(&self, metrics: NodeMetrics) -> CdnResult<()> {
        let mut baselines = self.baselines.write().await;
        let baseline = baselines.entry(metrics.node_id)
            .or_insert(MetricsBaseline {
                avg_qps: 0.0,
                avg_bandwidth: 0.0,
                sample_count: 0,
            });

        baseline.sample_count += 1;
        baseline.avg_qps += (metrics.qps as f64 - baseline.avg_qps) / baseline.sample_count as f64;
        baseline.avg_bandwidth += (metrics.bandwidth_usage - baseline.avg_bandwidth) / baseline.sample_count as f64;

        drop(baselines);

        self.detect_traffic_spike(&metrics).await?;
        self.detect_traffic_drop(&metrics).await?;
        self.detect_high_error_rate(&metrics).await?;

        Ok(())
    }

    async fn detect_traffic_spike(&self, metrics: &NodeMetrics) -> CdnResult<()> {
        let baselines = self.baselines.read().await;
        if let Some(baseline) = baselines.get(&metrics.node_id) {
            if baseline.avg_qps > 0.0 {
                let ratio = metrics.qps as f64 / baseline.avg_qps;
                if ratio >= self.config.traffic_spike_threshold {
                    let mut metadata = HashMap::new();
                    metadata.insert("current_qps".to_string(), metrics.qps.to_string());
                    metadata.insert("baseline_qps".to_string(), baseline.avg_qps.to_string());
                    metadata.insert("ratio".to_string(), ratio.to_string());

                    self.alert_manager.create_alert(
                        AlertType::TrafficSpike,
                        AlertSeverity::Warning,
                        format!("Traffic spike detected: {:.2}x baseline", ratio),
                        Some(metrics.node_id),
                        metadata,
                    ).await?;
                }
            }
        }
        Ok(())
    }

    async fn detect_traffic_drop(&self, metrics: &NodeMetrics) -> CdnResult<()> {
        let baselines = self.baselines.read().await;
        if let Some(baseline) = baselines.get(&metrics.node_id) {
            if baseline.avg_qps > 0.0 {
                let ratio = metrics.qps as f64 / baseline.avg_qps;
                if ratio <= self.config.traffic_drop_threshold {
                    let mut metadata = HashMap::new();
                    metadata.insert("current_qps".to_string(), metrics.qps.to_string());
                    metadata.insert("baseline_qps".to_string(), baseline.avg_qps.to_string());
                    metadata.insert("ratio".to_string(), ratio.to_string());

                    self.alert_manager.create_alert(
                        AlertType::TrafficDrop,
                        AlertSeverity::Warning,
                        format!("Traffic drop detected: {:.2}x baseline", ratio),
                        Some(metrics.node_id),
                        metadata,
                    ).await?;
                }
            }
        }
        Ok(())
    }

    async fn detect_high_error_rate(&self, metrics: &NodeMetrics) -> CdnResult<()> {
        let total_error_rate = metrics.error_rate_4xx + metrics.error_rate_5xx;
        if total_error_rate >= self.config.error_rate_threshold {
            let mut metadata = HashMap::new();
            metadata.insert("error_rate_4xx".to_string(), metrics.error_rate_4xx.to_string());
            metadata.insert("error_rate_5xx".to_string(), metrics.error_rate_5xx.to_string());
            metadata.insert("total_error_rate".to_string(), total_error_rate.to_string());

            let severity = if metrics.error_rate_5xx > 0.0 {
                AlertSeverity::Critical
            } else {
                AlertSeverity::Warning
            };

            self.alert_manager.create_alert(
                AlertType::HighErrorRate,
                severity,
                format!("High error rate detected: {:.2}%", total_error_rate * 100.0),
                Some(metrics.node_id),
                metadata,
            ).await?;
        }
        Ok(())
    }

    pub async fn check_traffic_imbalance(&self, all_metrics: &HashMap<uuid::Uuid, NodeMetrics>) -> CdnResult<()> {
        if all_metrics.len() < 2 {
            return Ok(());
        }

        let qps_values: Vec<f64> = all_metrics.values().map(|m| m.qps as f64).collect();
        let avg_qps = qps_values.iter().sum::<f64>() / qps_values.len() as f64;
        
        if avg_qps == 0.0 {
            return Ok(());
        }

        let max_qps = qps_values.iter().cloned().fold(f64::NAN, f64::max);
        let imbalance_ratio = max_qps / avg_qps;

        if imbalance_ratio > (1.0 + self.config.imbalance_threshold) {
            let mut metadata = HashMap::new();
            metadata.insert("avg_qps".to_string(), avg_qps.to_string());
            metadata.insert("max_qps".to_string(), max_qps.to_string());
            metadata.insert("imbalance_ratio".to_string(), imbalance_ratio.to_string());

            self.alert_manager.create_alert(
                AlertType::ImbalancedTraffic,
                AlertSeverity::Warning,
                format!("Traffic imbalance detected: ratio {:.2}x", imbalance_ratio),
                None,
                metadata,
            ).await?;
        }

        Ok(())
    }
}

impl Clone for AnomalyDetector {
    fn clone(&self) -> Self {
        AnomalyDetector {
            alert_manager: self.alert_manager.clone(),
            config: self.config.clone(),
            baselines: self.baselines.clone(),
        }
    }
}
