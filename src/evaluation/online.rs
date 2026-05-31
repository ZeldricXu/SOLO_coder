use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use parking_lot::Mutex;
use std::sync::Arc;
use std::time::{Instant, Duration};

use crate::utils::error::Result;
use crate::utils::metrics::MetricsCollector;

use super::metrics::{MetricType, MetricValue, MetricThreshold};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OnlineMetricRecord {
    pub metric_type: MetricType,
    pub value: f64,
    pub timestamp: chrono::DateTime<chrono::Utc>,
    pub model_id: String,
    pub request_id: Option<String>,
    pub latency_ms: Option<u64>,
    pub success: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WindowedMetrics {
    pub metric_type: MetricType,
    pub window_size: Duration,
    pub count: u64,
    pub sum: f64,
    pub avg: f64,
    pub min: f64,
    pub max: f64,
    pub p50: f64,
    pub p95: f64,
    pub p99: f64,
    pub error_count: u64,
    pub success_count: u64,
    pub window_start: chrono::DateTime<chrono::Utc>,
    pub window_end: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Alert {
    pub alert_id: String,
    pub metric_type: MetricType,
    pub model_id: String,
    pub severity: AlertSeverity,
    pub message: String,
    pub current_value: f64,
    pub threshold: f64,
    pub triggered_at: chrono::DateTime<chrono::Utc>,
    pub resolved: bool,
    pub resolved_at: Option<chrono::DateTime<chrono::Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AlertSeverity {
    Info,
    Warning,
    Critical,
}

pub struct OnlineMonitoringService {
    metrics: MetricsCollector,
    records: Arc<Mutex<HashMap<String, Vec<OnlineMetricRecord>>>>,
    thresholds: Arc<Mutex<HashMap<MetricType, MetricThreshold>>>,
    alerts: Arc<Mutex<Vec<Alert>>>,
    window_size: Duration,
    max_records_per_metric: usize,
}

impl OnlineMonitoringService {
    pub fn new(metrics: MetricsCollector, window_size: Duration, max_records: usize) -> Self {
        Self {
            metrics,
            records: Arc::new(Mutex::new(HashMap::new())),
            thresholds: Arc::new(Mutex::new(HashMap::new())),
            alerts: Arc::new(Mutex::new(Vec::new())),
            window_size,
            max_records_per_metric: max_records,
        }
    }

    pub fn set_threshold(&self, threshold: MetricThreshold) {
        self.thresholds.lock().insert(threshold.metric_type.clone(), threshold);
    }

    pub fn record_metric(&self, record: OnlineMetricRecord) -> Result<()> {
        self.metrics.increment_counter("online_metric_recorded");

        let key = format!("{}:{}", record.model_id, self.metric_type_to_str(&record.metric_type));
        let mut records = self.records.lock();
        let entry = records.entry(key).or_insert_with(Vec::new);
        
        entry.push(record.clone());
        
        if entry.len() > self.max_records_per_metric {
            let excess = entry.len() - self.max_records_per_metric;
            entry.drain(0..excess);
        }

        self.check_and_trigger_alerts(&record);
        Ok(())
    }

    pub fn record_request(
        &self,
        model_id: String,
        request_id: String,
        latency_ms: u64,
        success: bool,
        token_count: u64,
    ) -> Result<()> {
        let now = chrono::Utc::now();

        self.record_metric(OnlineMetricRecord {
            metric_type: MetricType::Latency,
            value: latency_ms as f64,
            timestamp: now,
            model_id: model_id.clone(),
            request_id: Some(request_id.clone()),
            latency_ms: Some(latency_ms),
            success,
        })?;

        self.record_metric(OnlineMetricRecord {
            metric_type: MetricType::TokenCount,
            value: token_count as f64,
            timestamp: now,
            model_id: model_id.clone(),
            request_id: Some(request_id.clone()),
            latency_ms: Some(latency_ms),
            success,
        })?;

        if !success {
            self.metrics.increment_counter("online_request_failed");
            self.record_metric(OnlineMetricRecord {
                metric_type: MetricType::ErrorRate,
                value: 1.0,
                timestamp: now,
                model_id,
                request_id: Some(request_id),
                latency_ms: Some(latency_ms),
                success: false,
            })?;
        }

        self.metrics.increment_counter("online_request_succeeded");
        Ok(())
    }

    pub fn get_windowed_metrics(
        &self,
        model_id: &str,
        metric_type: MetricType,
    ) -> Option<WindowedMetrics> {
        let key = format!("{}:{}", model_id, self.metric_type_to_str(&metric_type));
        let records = self.records.lock();
        let entries = records.get(&key)?;

        let now = chrono::Utc::now();
        let window_start = now - chrono::Duration::from_std(self.window_size).unwrap();

        let windowed_records: Vec<&OnlineMetricRecord> = entries
            .iter()
            .filter(|r| r.timestamp >= window_start)
            .collect();

        if windowed_records.is_empty() {
            return None;
        }

        let mut values: Vec<f64> = windowed_records.iter().map(|r| r.value).collect();
        values.sort_by(|a, b| a.partial_cmp(b).unwrap());

        let count = values.len() as u64;
        let sum: f64 = values.iter().sum();
        let avg = sum / count as f64;
        let min = values[0];
        let max = values[values.len() - 1];
        let p50 = self.percentile(&values, 50.0);
        let p95 = self.percentile(&values, 95.0);
        let p99 = self.percentile(&values, 99.0);

        let error_count = windowed_records.iter().filter(|r| !r.success).count() as u64;
        let success_count = windowed_records.iter().filter(|r| r.success).count() as u64;

        Some(WindowedMetrics {
            metric_type,
            window_size: self.window_size,
            count,
            sum,
            avg,
            min,
            max,
            p50,
            p95,
            p99,
            error_count,
            success_count,
            window_start,
            window_end: now,
        })
    }

    pub fn get_throughput(&self, model_id: &str) -> f64 {
        let key = format!("{}:{}", model_id, self.metric_type_to_str(&MetricType::Latency));
        let records = self.records.lock();
        let entries = match records.get(&key) {
            Some(e) => e,
            None => return 0.0,
        };

        let now = chrono::Utc::now();
        let window_start = now - chrono::Duration::from_std(self.window_size).unwrap();
        
        let count = entries.iter().filter(|r| r.timestamp >= window_start).count() as f64;
        let window_secs = self.window_size.as_secs_f64();
        
        if window_secs > 0.0 {
            count / window_secs
        } else {
            0.0
        }
    }

    pub fn get_error_rate(&self, model_id: &str) -> f64 {
        let latency_metrics = self.get_windowed_metrics(model_id, MetricType::Latency);
        
        if let Some(metrics) = latency_metrics {
            if metrics.count > 0 {
                metrics.error_count as f64 / metrics.count as f64
            } else {
                0.0
            }
        } else {
            0.0
        }
    }

    pub fn get_current_metrics(&self, model_id: &str) -> HashMap<MetricType, WindowedMetrics> {
        let metric_types = vec![
            MetricType::Latency,
            MetricType::TokenCount,
            MetricType::ErrorRate,
            MetricType::Throughput,
        ];

        let mut result = HashMap::new();
        for metric_type in metric_types {
            if let Some(metrics) = self.get_windowed_metrics(model_id, metric_type.clone()) {
                result.insert(metric_type, metrics);
            }
        }

        let throughput = self.get_throughput(model_id);
        let now = chrono::Utc::now();
        let window_start = now - chrono::Duration::from_std(self.window_size).unwrap();
        
        result.insert(MetricType::Throughput, WindowedMetrics {
            metric_type: MetricType::Throughput,
            window_size: self.window_size,
            count: 1,
            sum: throughput,
            avg: throughput,
            min: throughput,
            max: throughput,
            p50: throughput,
            p95: throughput,
            p99: throughput,
            error_count: 0,
            success_count: 0,
            window_start,
            window_end: now,
        });

        result
    }

    pub fn get_alerts(
        &self,
        model_id: Option<&str>,
        severity: Option<AlertSeverity>,
        active_only: bool,
    ) -> Vec<Alert> {
        let alerts = self.alerts.lock();
        let mut result: Vec<Alert> = alerts.iter()
            .filter(|a| {
                if let Some(model) = model_id {
                    if a.model_id != model {
                        return false;
                    }
                }
                if let Some(sev) = &severity {
                    if &a.severity != sev {
                        return false;
                    }
                }
                if active_only && a.resolved {
                    return false;
                }
                true
            })
            .cloned()
            .collect();

        result.sort_by(|a, b| b.triggered_at.cmp(&a.triggered_at));
        result
    }

    pub fn resolve_alert(&self, alert_id: &str) -> Result<()> {
        let mut alerts = self.alerts.lock();
        for alert in alerts.iter_mut() {
            if alert.alert_id == alert_id {
                alert.resolved = true;
                alert.resolved_at = Some(chrono::Utc::now());
                return Ok(());
            }
        }
        Err(crate::utils::error::AppError::NotFound(format!(
            "Alert {} not found", alert_id
        )))
    }

    fn check_and_trigger_alerts(&self, record: &OnlineMetricRecord) {
        let thresholds = self.thresholds.lock();
        if let Some(threshold) = thresholds.get(&record.metric_type) {
            let should_alert = if threshold.is_higher_better {
                record.value < threshold.warning_threshold
            } else {
                record.value > threshold.warning_threshold
            };

            if should_alert {
                let severity = if threshold.is_higher_better {
                    if record.value < threshold.critical_threshold {
                        AlertSeverity::Critical
                    } else {
                        AlertSeverity::Warning
                    }
                } else {
                    if record.value > threshold.critical_threshold {
                        AlertSeverity::Critical
                    } else {
                        AlertSeverity::Warning
                    }
                };

                let message = format!(
                    "Metric {:?} for model {}: value {:.4} {} threshold {:.4}",
                    record.metric_type,
                    record.model_id,
                    record.value,
                    if threshold.is_higher_better { "below" } else { "above" },
                    threshold.warning_threshold
                );

                let alert = Alert {
                    alert_id: format!("alert_{}", crate::utils::id::generate_id()),
                    metric_type: record.metric_type.clone(),
                    model_id: record.model_id.clone(),
                    severity,
                    message,
                    current_value: record.value,
                    threshold: threshold.warning_threshold,
                    triggered_at: chrono::Utc::now(),
                    resolved: false,
                    resolved_at: None,
                };

                self.metrics.increment_counter("online_alert_triggered");
                self.alerts.lock().push(alert);
            }
        }
    }

    fn percentile(&self, sorted_values: &[f64], percentile: f64) -> f64 {
        if sorted_values.is_empty() {
            return 0.0;
        }
        let index = (percentile / 100.0 * (sorted_values.len() - 1) as f64).round() as usize;
        sorted_values[index]
    }

    fn metric_type_to_str(&self, metric_type: &MetricType) -> String {
        match metric_type {
            MetricType::Accuracy => "accuracy",
            MetricType::Precision => "precision",
            MetricType::Recall => "recall",
            MetricType::F1Score => "f1_score",
            MetricType::Bleu => "bleu",
            MetricType::Rouge => "rouge",
            MetricType::Perplexity => "perplexity",
            MetricType::Latency => "latency",
            MetricType::Throughput => "throughput",
            MetricType::ErrorRate => "error_rate",
            MetricType::TokenCount => "token_count",
            MetricType::Cost => "cost",
            MetricType::Custom(s) => s.as_str(),
        }.to_string()
    }

    pub fn clear_old_records(&self, older_than: Duration) {
        let cutoff = chrono::Utc::now() - chrono::Duration::from_std(older_than).unwrap();
        let mut records = self.records.lock();
        
        for entry in records.values_mut() {
            entry.retain(|r| r.timestamp >= cutoff);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::utils::metrics::MetricsCollector;
    use std::time::Duration;

    #[test]
    fn test_record_metric() {
        let metrics = MetricsCollector::new();
        let service = OnlineMonitoringService::new(metrics, Duration::from_secs(60), 1000);

        let record = OnlineMetricRecord {
            metric_type: MetricType::Latency,
            value: 150.0,
            timestamp: chrono::Utc::now(),
            model_id: "model_001".to_string(),
            request_id: Some("req_001".to_string()),
            latency_ms: Some(150),
            success: true,
        };

        service.record_metric(record).unwrap();
        
        let windowed = service.get_windowed_metrics("model_001", MetricType::Latency).unwrap();
        assert_eq!(windowed.count, 1);
        assert_eq!(windowed.avg, 150.0);
    }

    #[test]
    fn test_record_request() {
        let metrics = MetricsCollector::new();
        let service = OnlineMonitoringService::new(metrics, Duration::from_secs(60), 1000);

        service.record_request(
            "model_001".to_string(),
            "req_001".to_string(),
            150,
            true,
            256,
        ).unwrap();

        let latency = service.get_windowed_metrics("model_001", MetricType::Latency).unwrap();
        assert_eq!(latency.avg, 150.0);

        let tokens = service.get_windowed_metrics("model_001", MetricType::TokenCount).unwrap();
        assert_eq!(tokens.avg, 256.0);
    }

    #[test]
    fn test_windowed_metrics_calculation() {
        let metrics = MetricsCollector::new();
        let service = OnlineMonitoringService::new(metrics, Duration::from_secs(60), 1000);

        for i in 0..10 {
            service.record_metric(OnlineMetricRecord {
                metric_type: MetricType::Latency,
                value: (i * 10 + 100) as f64,
                timestamp: chrono::Utc::now(),
                model_id: "model_001".to_string(),
                request_id: None,
                latency_ms: None,
                success: true,
            }).unwrap();
        }

        let windowed = service.get_windowed_metrics("model_001", MetricType::Latency).unwrap();
        assert_eq!(windowed.count, 10);
        assert_eq!(windowed.min, 100.0);
        assert_eq!(windowed.max, 190.0);
        assert_eq!(windowed.p50, 140.0);
        assert_eq!(windowed.p95, 180.0);
        assert_eq!(windowed.p99, 190.0);
    }

    #[test]
    fn test_throughput_calculation() {
        let metrics = MetricsCollector::new();
        let service = OnlineMonitoringService::new(metrics, Duration::from_secs(10), 1000);

        for i in 0..5 {
            service.record_metric(OnlineMetricRecord {
                metric_type: MetricType::Latency,
                value: 100.0,
                timestamp: chrono::Utc::now(),
                model_id: "model_001".to_string(),
                request_id: None,
                latency_ms: None,
                success: true,
            }).unwrap();
        }

        let throughput = service.get_throughput("model_001");
        assert!(throughput > 0.0);
    }

    #[test]
    fn test_alert_triggering() {
        let metrics = MetricsCollector::new();
        let service = OnlineMonitoringService::new(metrics, Duration::from_secs(60), 1000);

        service.set_threshold(MetricThreshold {
            metric_type: MetricType::Latency,
            warning_threshold: 500.0,
            critical_threshold: 1000.0,
            is_higher_better: false,
        });

        service.record_metric(OnlineMetricRecord {
            metric_type: MetricType::Latency,
            value: 600.0,
            timestamp: chrono::Utc::now(),
            model_id: "model_001".to_string(),
            request_id: None,
            latency_ms: None,
            success: true,
        }).unwrap();

        let alerts = service.get_alerts(Some("model_001"), None, true);
        assert_eq!(alerts.len(), 1);
        assert_eq!(alerts[0].severity, AlertSeverity::Warning);
    }

    #[test]
    fn test_critical_alert() {
        let metrics = MetricsCollector::new();
        let service = OnlineMonitoringService::new(metrics, Duration::from_secs(60), 1000);

        service.set_threshold(MetricThreshold {
            metric_type: MetricType::ErrorRate,
            warning_threshold: 0.05,
            critical_threshold: 0.10,
            is_higher_better: false,
        });

        service.record_metric(OnlineMetricRecord {
            metric_type: MetricType::ErrorRate,
            value: 0.15,
            timestamp: chrono::Utc::now(),
            model_id: "model_001".to_string(),
            request_id: None,
            latency_ms: None,
            success: false,
        }).unwrap();

        let alerts = service.get_alerts(Some("model_001"), Some(AlertSeverity::Critical), true);
        assert_eq!(alerts.len(), 1);
    }

    #[test]
    fn test_alert_resolution() {
        let metrics = MetricsCollector::new();
        let service = OnlineMonitoringService::new(metrics, Duration::from_secs(60), 1000);

        service.set_threshold(MetricThreshold {
            metric_type: MetricType::Latency,
            warning_threshold: 500.0,
            critical_threshold: 1000.0,
            is_higher_better: false,
        });

        service.record_metric(OnlineMetricRecord {
            metric_type: MetricType::Latency,
            value: 600.0,
            timestamp: chrono::Utc::now(),
            model_id: "model_001".to_string(),
            request_id: None,
            latency_ms: None,
            success: true,
        }).unwrap();

        let alerts = service.get_alerts(Some("model_001"), None, true);
        let alert_id = alerts[0].alert_id.clone();

        service.resolve_alert(&alert_id).unwrap();

        let active_alerts = service.get_alerts(Some("model_001"), None, true);
        assert_eq!(active_alerts.len(), 0);
    }

    #[test]
    fn test_get_current_metrics() {
        let metrics = MetricsCollector::new();
        let service = OnlineMonitoringService::new(metrics, Duration::from_secs(60), 1000);

        service.record_request(
            "model_001".to_string(),
            "req_001".to_string(),
            150,
            true,
            256,
        ).unwrap();

        let current = service.get_current_metrics("model_001");
        assert!(current.contains_key(&MetricType::Latency));
        assert!(current.contains_key(&MetricType::TokenCount));
        assert!(current.contains_key(&MetricType::Throughput));
    }

    #[test]
    fn test_error_rate_calculation() {
        let metrics = MetricsCollector::new();
        let service = OnlineMonitoringService::new(metrics, Duration::from_secs(60), 1000);

        for i in 0..10 {
            service.record_request(
                "model_001".to_string(),
                format!("req_{}", i),
                150,
                i >= 8,
                256,
            ).unwrap();
        }

        let error_rate = service.get_error_rate("model_001");
        assert!((error_rate - 0.2).abs() < 0.01);
    }
}
