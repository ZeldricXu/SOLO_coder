use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;
use chrono::{DateTime, Utc};
use parking_lot::Mutex;
use tracing::{info, debug};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum MetricType {
    Counter,
    Gauge,
    Histogram,
    Summary,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PrometheusMetric {
    pub name: String,
    pub metric_type: MetricType,
    pub help: String,
    pub labels: HashMap<String, String>,
    pub value: f64,
    pub timestamp: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureStoreOperation {
    pub operation: String,
    pub feature_id: Option<String>,
    pub entity_id: Option<String>,
    pub start_time: Instant,
    pub duration_ms: Option<u64>,
    pub success: bool,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OperationLatencyStats {
    pub operation: String,
    pub count: u64,
    pub avg_latency_ms: f64,
    pub p50_latency_ms: f64,
    pub p95_latency_ms: f64,
    pub p99_latency_ms: f64,
    pub min_latency_ms: u64,
    pub max_latency_ms: u64,
    pub total_latency_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureStoreMonitorSnapshot {
    pub timestamp: DateTime<Utc>,
    pub operations_total: u64,
    pub operations_success: u64,
    pub operations_failed: u64,
    pub success_rate: f64,
    pub operations_in_progress: u64,
    pub latency_stats: HashMap<String, OperationLatencyStats>,
    pub feature_access_counts: HashMap<String, u64>,
    pub entity_access_counts: HashMap<String, u64>,
    pub last_10_operations: Vec<FeatureStoreOperation>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MonitoringConfig {
    pub enable_prometheus: bool,
    pub prometheus_port: u16,
    pub enable_operation_tracing: bool,
    pub max_operation_history: usize,
    pub latency_percentiles: Vec<f64>,
    pub slow_operation_threshold_ms: u64,
}

impl Default for MonitoringConfig {
    fn default() -> Self {
        Self {
            enable_prometheus: true,
            prometheus_port: 9090,
            enable_operation_tracing: true,
            max_operation_history: 1000,
            latency_percentiles: vec![50.0, 95.0, 99.0],
            slow_operation_threshold_ms: 1000,
        }
    }
}

struct OperationTrackerInner {
    operations: Vec<FeatureStoreOperation>,
    in_progress: HashMap<String, FeatureStoreOperation>,
    latency_samples: HashMap<String, Vec<u64>>,
    feature_access_counts: HashMap<String, u64>,
    entity_access_counts: HashMap<String, u64>,
    total_operations: u64,
    total_success: u64,
    total_failed: u64,
}

pub struct OperationGuard {
    operation_id: String,
    tracker: Arc<FeatureStoreMonitor>,
}

impl Drop for OperationGuard {
    fn drop(&mut self) {
        self.tracker.end_operation(&self.operation_id, true, None);
    }
}

impl OperationGuard {
    pub fn record_failure(&self, error_message: &str) {
        self.tracker.end_operation(&self.operation_id, false, Some(error_message.to_string()));
    }
}

pub struct FeatureStoreMonitor {
    inner: Mutex<OperationTrackerInner>,
    config: MonitoringConfig,
    metrics: Mutex<Vec<PrometheusMetric>>,
}

impl FeatureStoreMonitor {
    pub fn new(config: MonitoringConfig) -> Self {
        Self {
            inner: Mutex::new(OperationTrackerInner {
                operations: Vec::new(),
                in_progress: HashMap::new(),
                latency_samples: HashMap::new(),
                feature_access_counts: HashMap::new(),
                entity_access_counts: HashMap::new(),
                total_operations: 0,
                total_success: 0,
                total_failed: 0,
            }),
            config,
            metrics: Mutex::new(Vec::new()),
        }
    }

    pub fn start_operation(
        self: &Arc<Self>,
        operation: &str,
        feature_id: Option<&str>,
        entity_id: Option<&str>,
    ) -> OperationGuard {
        use uuid::Uuid;
        let operation_id = format!("op_{}", Uuid::new_v4().simple());
        
        let op = FeatureStoreOperation {
            operation: operation.to_string(),
            feature_id: feature_id.map(|s| s.to_string()),
            entity_id: entity_id.map(|s| s.to_string()),
            start_time: Instant::now(),
            duration_ms: None,
            success: false,
            error_message: None,
        };
        
        {
            let mut inner = self.inner.lock();
            inner.in_progress.insert(operation_id.clone(), op);
            inner.total_operations += 1;
        }
        
        OperationGuard {
            operation_id,
            tracker: Arc::clone(self),
        }
    }

    fn end_operation(&self, operation_id: &str, success: bool, error_message: Option<String>) {
        let mut inner = self.inner.lock();
        
        if let Some(mut op) = inner.in_progress.remove(operation_id) {
            let duration = op.start_time.elapsed().as_millis() as u64;
            op.duration_ms = Some(duration);
            op.success = success;
            op.error_message = error_message.clone();
            
            if success {
                inner.total_success += 1;
            } else {
                inner.total_failed += 1;
            }
            
            inner.latency_samples
                .entry(op.operation.clone())
                .or_insert_with(Vec::new)
                .push(duration);
            
            if let Some(feature_id) = &op.feature_id {
                *inner.feature_access_counts.entry(feature_id.clone()).or_insert(0) += 1;
            }
            
            if let Some(entity_id) = &op.entity_id {
                *inner.entity_access_counts.entry(entity_id.clone()).or_insert(0) += 1;
            }
            
            inner.operations.push(op.clone());
            
            while inner.operations.len() > self.config.max_operation_history {
                inner.operations.remove(0);
            }
            
            if duration >= self.config.slow_operation_threshold_ms {
                debug!(
                    operation = %op.operation,
                    duration_ms = duration,
                    feature_id = ?op.feature_id,
                    "Slow operation detected"
                );
            }
        }
    }

    pub fn record_feature_access(&self, feature_id: &str) {
        let mut inner = self.inner.lock();
        *inner.feature_access_counts.entry(feature_id.to_string()).or_insert(0) += 1;
    }

    pub fn record_entity_access(&self, entity_id: &str) {
        let mut inner = self.inner.lock();
        *inner.entity_access_counts.entry(entity_id.to_string()).or_insert(0) += 1;
    }

    pub fn register_prometheus_metric(&self, metric: PrometheusMetric) {
        let mut metrics = self.metrics.lock();
        metrics.push(metric);
    }

    pub fn snapshot(&self) -> FeatureStoreMonitorSnapshot {
        let inner = self.inner.lock();
        
        let mut latency_stats = HashMap::new();
        for (op, samples) in &inner.latency_samples {
            if !samples.is_empty() {
                latency_stats.insert(op.clone(), self.calculate_latency_stats(op, samples));
            }
        }
        
        let success_rate = if inner.total_operations > 0 {
            inner.total_success as f64 / inner.total_operations as f64
        } else {
            1.0
        };
        
        let recent_ops = inner.operations
            .iter()
            .rev()
            .take(10)
            .cloned()
            .collect();
        
        FeatureStoreMonitorSnapshot {
            timestamp: Utc::now(),
            operations_total: inner.total_operations,
            operations_success: inner.total_success,
            operations_failed: inner.total_failed,
            success_rate,
            operations_in_progress: inner.in_progress.len() as u64,
            latency_stats,
            feature_access_counts: inner.feature_access_counts.clone(),
            entity_access_counts: inner.entity_access_counts.clone(),
            last_10_operations: recent_ops,
        }
    }

    pub fn export_prometheus_metrics(&self) -> String {
        let mut output = String::new();
        let snapshot = self.snapshot();
        
        output.push_str("# HELP feature_store_operations_total Total number of feature store operations\n");
        output.push_str("# TYPE feature_store_operations_total counter\n");
        output.push_str(&format!("feature_store_operations_total {}\n", snapshot.operations_total));
        
        output.push_str("# HELP feature_store_operations_success Total number of successful operations\n");
        output.push_str("# TYPE feature_store_operations_success counter\n");
        output.push_str(&format!("feature_store_operations_success {}\n", snapshot.operations_success));
        
        output.push_str("# HELP feature_store_operations_failed Total number of failed operations\n");
        output.push_str("# TYPE feature_store_operations_failed counter\n");
        output.push_str(&format!("feature_store_operations_failed {}\n", snapshot.operations_failed));
        
        output.push_str("# HELP feature_store_success_rate Operation success rate\n");
        output.push_str("# TYPE feature_store_success_rate gauge\n");
        output.push_str(&format!("feature_store_success_rate {}\n", snapshot.success_rate));
        
        output.push_str("# HELP feature_store_operations_in_progress Number of operations in progress\n");
        output.push_str("# TYPE feature_store_operations_in_progress gauge\n");
        output.push_str(&format!("feature_store_operations_in_progress {}\n", snapshot.operations_in_progress));
        
        for (op, stats) in &snapshot.latency_stats {
            output.push_str(&format!("# HELP feature_store_{}_latency_ms Operation latency in ms\n", op));
            output.push_str(&format!("# TYPE feature_store_{}_latency_ms summary\n", op));
            output.push_str(&format!("feature_store_{}_latency_ms{{quantile=\"0.5\"}} {}\n", op, stats.p50_latency_ms));
            output.push_str(&format!("feature_store_{}_latency_ms{{quantile=\"0.95\"}} {}\n", op, stats.p95_latency_ms));
            output.push_str(&format!("feature_store_{}_latency_ms{{quantile=\"0.99\"}} {}\n", op, stats.p99_latency_ms));
            output.push_str(&format!("feature_store_{}_latency_ms_sum {}\n", op, stats.total_latency_ms));
            output.push_str(&format!("feature_store_{}_latency_ms_count {}\n", op, stats.count));
        }
        
        for (feature_id, count) in &snapshot.feature_access_counts {
            output.push_str("# HELP feature_store_feature_access_count Feature access count\n");
            output.push_str("# TYPE feature_store_feature_access_count counter\n");
            output.push_str(&format!("feature_store_feature_access_count{{feature_id=\"{}\"}} {}\n", feature_id, count));
        }
        
        let custom_metrics = self.metrics.lock();
        for metric in custom_metrics.iter() {
            let labels_str: Vec<String> = metric.labels
                .iter()
                .map(|(k, v)| format!("{}=\"{}\"", k, v))
                .collect();
            let labels = if labels_str.is_empty() {
                String::new()
            } else {
                format!("{{{}}}", labels_str.join(","))
            };
            
            output.push_str(&format!("# HELP {} {}\n", metric.name, metric.help));
            output.push_str(&format!("# TYPE {} {:?}\n", metric.name, metric.metric_type).to_lowercase());
            output.push_str(&format!("{}{} {}\n", metric.name, labels, metric.value));
        }
        
        output
    }

    fn calculate_latency_stats(&self, operation: &str, samples: &[u64]) -> OperationLatencyStats {
        let count = samples.len() as u64;
        let total = samples.iter().sum::<u64>();
        let avg = if count > 0 { total as f64 / count as f64 } else { 0.0 };
        let min = *samples.iter().min().unwrap_or(&0);
        let max = *samples.iter().max().unwrap_or(&0);
        
        let mut sorted = samples.to_vec();
        sorted.sort_unstable();
        
        let p50 = self.percentile(&sorted, 50.0);
        let p95 = self.percentile(&sorted, 95.0);
        let p99 = self.percentile(&sorted, 99.0);
        
        OperationLatencyStats {
            operation: operation.to_string(),
            count,
            avg_latency_ms: avg,
            p50_latency_ms: p50,
            p95_latency_ms: p95,
            p99_latency_ms: p99,
            min_latency_ms: min,
            max_latency_ms: max,
            total_latency_ms: total,
        }
    }

    fn percentile(&self, sorted: &[u64], percentile: f64) -> f64 {
        if sorted.is_empty() {
            return 0.0;
        }
        
        let idx = (percentile / 100.0 * (sorted.len() - 1) as f64).round() as usize;
        sorted[idx] as f64
    }

    pub fn config(&self) -> &MonitoringConfig {
        &self.config
    }

    pub fn reset_stats(&self) {
        let mut inner = self.inner.lock();
        inner.total_operations = 0;
        inner.total_success = 0;
        inner.total_failed = 0;
        inner.latency_samples.clear();
        inner.feature_access_counts.clear();
        inner.entity_access_counts.clear();
        inner.operations.clear();
        info!("Feature store monitoring stats reset");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_monitoring_config_default() {
        let config = MonitoringConfig::default();
        assert!(config.enable_prometheus);
        assert_eq!(config.prometheus_port, 9090);
        assert!(config.enable_operation_tracing);
    }

    #[test]
    fn test_monitor_creation() {
        let config = MonitoringConfig::default();
        let monitor = FeatureStoreMonitor::new(config);
        let snapshot = monitor.snapshot();
        
        assert_eq!(snapshot.operations_total, 0);
        assert_eq!(snapshot.success_rate, 1.0);
    }

    #[tokio::test]
    async fn test_operation_tracking() {
        let config = MonitoringConfig::default();
        let monitor = Arc::new(FeatureStoreMonitor::new(config));
        
        let _guard = monitor.start_operation("insert", Some("feature_1"), Some("entity_1"));
        drop(_guard);
        
        let snapshot = monitor.snapshot();
        assert_eq!(snapshot.operations_total, 1);
        assert_eq!(snapshot.operations_success, 1);
        assert_eq!(snapshot.feature_access_counts.get("feature_1"), Some(&1));
    }

    #[tokio::test]
    async fn test_operation_failure() {
        let config = MonitoringConfig::default();
        let monitor = Arc::new(FeatureStoreMonitor::new(config));
        
        let guard = monitor.start_operation("lookup", Some("feature_1"), None);
        guard.record_failure("Test error");
        drop(guard);
        
        let snapshot = monitor.snapshot();
        assert_eq!(snapshot.operations_total, 1);
        assert_eq!(snapshot.operations_failed, 1);
        assert_eq!(snapshot.success_rate, 0.0);
    }

    #[test]
    fn test_latency_stats_calculation() {
        let config = MonitoringConfig::default();
        let monitor = FeatureStoreMonitor::new(config);
        let samples = vec![10, 20, 30, 40, 50, 60, 70, 80, 90, 100];
        
        let stats = monitor.calculate_latency_stats("test_op", &samples);
        
        assert_eq!(stats.count, 10);
        assert_eq!(stats.avg_latency_ms, 55.0);
        assert_eq!(stats.min_latency_ms, 10);
        assert_eq!(stats.max_latency_ms, 100);
        assert_eq!(stats.p50_latency_ms, 50.0);
        assert_eq!(stats.p95_latency_ms, 95.0);
        assert_eq!(stats.p99_latency_ms, 100.0);
    }

    #[test]
    fn test_percentile_calculation() {
        let config = MonitoringConfig::default();
        let monitor = FeatureStoreMonitor::new(config);
        let mut sorted: Vec<u64> = (1..=100).collect();
        
        assert_eq!(monitor.percentile(&sorted, 50.0), 50.0);
        assert_eq!(monitor.percentile(&sorted, 95.0), 95.0);
        assert_eq!(monitor.percentile(&sorted, 99.0), 99.0);
    }

    #[test]
    fn test_prometheus_export() {
        let config = MonitoringConfig::default();
        let monitor = FeatureStoreMonitor::new(config);
        
        let metric = PrometheusMetric {
            name: "test_metric".to_string(),
            metric_type: MetricType::Gauge,
            help: "Test metric".to_string(),
            labels: HashMap::new(),
            value: 42.0,
            timestamp: None,
        };
        
        monitor.register_prometheus_metric(metric);
        
        let output = monitor.export_prometheus_metrics();
        
        assert!(output.contains("feature_store_operations_total"));
        assert!(output.contains("feature_store_success_rate"));
        assert!(output.contains("test_metric"));
        assert!(output.contains("42"));
    }

    #[tokio::test]
    async fn test_reset_stats() {
        let config = MonitoringConfig::default();
        let monitor = Arc::new(FeatureStoreMonitor::new(config));
        
        let guard = monitor.start_operation("test", None, None);
        drop(guard);
        
        let snapshot = monitor.snapshot();
        assert_eq!(snapshot.operations_total, 1);
        
        monitor.reset_stats();
        
        let snapshot = monitor.snapshot();
        assert_eq!(snapshot.operations_total, 0);
        assert_eq!(snapshot.feature_access_counts.len(), 0);
    }

    #[tokio::test]
    async fn test_last_10_operations() {
        let config = MonitoringConfig {
            max_operation_history: 100,
            ..Default::default()
        };
        let monitor = Arc::new(FeatureStoreMonitor::new(config));
        
        for i in 0..15 {
            let guard = monitor.start_operation(&format!("op_{}", i), None, None);
            drop(guard);
        }
        
        let snapshot = monitor.snapshot();
        assert_eq!(snapshot.last_10_operations.len(), 10);
        assert_eq!(snapshot.operations_total, 15);
    }

    #[test]
    fn test_metric_type_serialization() {
        assert_eq!(serde_json::to_string(&MetricType::Counter).unwrap(), "\"counter\"");
        assert_eq!(serde_json::to_string(&MetricType::Gauge).unwrap(), "\"gauge\"");
    }

    #[test]
    fn test_entity_access_tracking() {
        let config = MonitoringConfig::default();
        let monitor = FeatureStoreMonitor::new(config);
        monitor.record_entity_access("entity_1");
        monitor.record_entity_access("entity_1");
        monitor.record_entity_access("entity_2");
        
        let snapshot = monitor.snapshot();
        assert_eq!(snapshot.entity_access_counts.get("entity_1"), Some(&2));
        assert_eq!(snapshot.entity_access_counts.get("entity_2"), Some(&1));
    }
}
