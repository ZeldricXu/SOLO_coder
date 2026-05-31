use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use parking_lot::Mutex;
use std::time::Duration;

use crate::utils::error::Result;
use crate::utils::metrics::MetricsCollector;

use super::metrics::{MetricType, MetricThreshold};
use super::offline::{OfflineEvaluationService, OfflineEvaluationResult, ModelComparison};
use super::online::{OnlineMonitoringService, WindowedMetrics, Alert};
use super::drift::{DriftDetectionService, DriftDetectionResult, DriftSeverity};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelDashboard {
    pub model_id: String,
    pub model_name: String,
    pub current_stage: String,
    pub overview: OverviewMetrics,
    pub offline_evaluations: Vec<OfflineEvaluationResult>,
    pub latest_comparison: Option<ModelComparison>,
    pub online_metrics: HashMap<MetricType, WindowedMetrics>,
    pub active_alerts: Vec<Alert>,
    pub active_drifts: Vec<DriftDetectionResult>,
    pub drift_summary: HashMap<DriftSeverity, u64>,
    pub last_updated: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OverviewMetrics {
    pub total_requests: u64,
    pub success_rate: f64,
    pub average_latency_ms: f64,
    pub p95_latency_ms: f64,
    pub p99_latency_ms: f64,
    pub throughput: f64,
    pub error_rate: f64,
    pub latest_accuracy: Option<f64>,
    pub security_score: Option<f64>,
    pub uptime_percentage: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DashboardConfig {
    pub window_size: Duration,
    pub max_evaluations: usize,
    pub max_alerts: usize,
    pub max_drifts: usize,
}

impl Default for DashboardConfig {
    fn default() -> Self {
        Self {
            window_size: Duration::from_secs(300),
            max_evaluations: 10,
            max_alerts: 50,
            max_drifts: 20,
        }
    }
}

pub struct EvaluationDashboardService {
    metrics: MetricsCollector,
    config: DashboardConfig,
    offline_service: Arc<OfflineEvaluationService>,
    online_service: Arc<OnlineMonitoringService>,
    drift_service: Arc<DriftDetectionService>,
    model_metadata: Arc<Mutex<HashMap<String, (String, String)>>>,
}

impl EvaluationDashboardService {
    pub fn new(
        metrics: MetricsCollector,
        config: DashboardConfig,
    ) -> Self {
        let offline_service = Arc::new(OfflineEvaluationService::new(metrics.clone()));
        let online_service = Arc::new(OnlineMonitoringService::new(
            metrics.clone(),
            config.window_size,
            10000,
        ));
        let drift_service = Arc::new(DriftDetectionService::new(metrics.clone()));

        Self {
            metrics,
            config,
            offline_service,
            online_service,
            drift_service,
            model_metadata: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn register_model(&self, model_id: String, model_name: String, stage: String) {
        self.model_metadata.lock().insert(model_id, (model_name, stage));
    }

    pub fn update_model_stage(&self, model_id: &str, stage: String) -> Result<()> {
        let mut metadata = self.model_metadata.lock();
        let entry = metadata.get_mut(model_id)
            .ok_or_else(|| crate::utils::error::AppError::NotFound(format!(
                "Model {} not registered", model_id
            )))?;
        entry.1 = stage;
        Ok(())
    }

    pub fn get_dashboard(&self, model_id: &str) -> Result<ModelDashboard> {
        let metadata = self.model_metadata.lock();
        let (model_name, stage) = metadata.get(model_id)
            .ok_or_else(|| crate::utils::error::AppError::NotFound(format!(
                "Model {} not registered", model_id
            )))?;

        let overview = self.get_overview_metrics(model_id);
        let offline_evaluations = self.offline_service.list_evaluations(Some(model_id))
            .into_iter()
            .take(self.config.max_evaluations)
            .collect();
        
        let latest_comparison = self.get_latest_comparison(model_id);
        let online_metrics = self.online_service.get_current_metrics(model_id);
        let active_alerts = self.online_service.get_alerts(
            Some(model_id),
            None,
            true,
        ).into_iter().take(self.config.max_alerts).collect();
        
        let active_drifts = self.drift_service.get_active_drifts(Some(model_id))
            .into_iter().take(self.config.max_drifts).collect();
        let drift_summary = self.drift_service.summarize_drifts(model_id);

        Ok(ModelDashboard {
            model_id: model_id.to_string(),
            model_name: model_name.clone(),
            current_stage: stage.clone(),
            overview,
            offline_evaluations,
            latest_comparison,
            online_metrics,
            active_alerts,
            active_drifts,
            drift_summary,
            last_updated: chrono::Utc::now(),
        })
    }

    fn get_overview_metrics(&self, model_id: &str) -> OverviewMetrics {
        let online_metrics = self.online_service.get_current_metrics(model_id);
        
        let latency_metrics = online_metrics.get(&MetricType::Latency);
        let throughput = online_metrics.get(&MetricType::Throughput)
            .map(|m| m.avg).unwrap_or(0.0);
        let error_rate = self.online_service.get_error_rate(model_id);

        let total_requests = latency_metrics
            .map(|m| m.success_count + m.error_count)
            .unwrap_or(0);
        let success_rate = if total_requests > 0 {
            latency_metrics.map(|m| m.success_count as f64 / total_requests as f64)
                .unwrap_or(1.0)
        } else {
            1.0
        };

        let avg_latency = latency_metrics.map(|m| m.avg).unwrap_or(0.0);
        let p95_latency = latency_metrics.map(|m| m.p95).unwrap_or(0.0);
        let p99_latency = latency_metrics.map(|m| m.p99).unwrap_or(0.0);

        let latest_accuracy = self.offline_service.list_evaluations(Some(model_id))
            .into_iter()
            .find(|e| e.status == super::offline::EvaluationStatus::Completed)
            .and_then(|e| e.metrics.get(&MetricType::Accuracy).map(|m| m.value));

        let uptime_percentage = if error_rate < 1.0 {
            (1.0 - error_rate) * 100.0
        } else {
            99.9
        };

        OverviewMetrics {
            total_requests,
            success_rate,
            average_latency_ms: avg_latency,
            p95_latency_ms: p95_latency,
            p99_latency_ms: p99_latency,
            throughput,
            error_rate,
            latest_accuracy,
            security_score: None,
            uptime_percentage,
        }
    }

    fn get_latest_comparison(&self, model_id: &str) -> Option<ModelComparison> {
        let evaluations = self.offline_service.list_evaluations(Some(model_id));
        if evaluations.len() < 2 {
            return None;
        }

        let baseline = evaluations.get(1)?;
        let candidate = evaluations.get(0)?;

        if baseline.dataset_id != candidate.dataset_id {
            return None;
        }

        self.offline_service.compare_models(
            baseline.model_id.clone(),
            candidate.model_id.clone(),
            baseline.dataset_id.clone(),
            5.0,
        ).ok()
    }

    pub fn set_threshold(&self, threshold: MetricThreshold) {
        self.online_service.set_threshold(threshold.clone());
        self.offline_service.set_threshold(threshold);
    }

    pub fn run_drift_detection(&self, model_id: &str) -> Result<Vec<DriftDetectionResult>> {
        self.drift_service.detect_all_drifts(model_id)
    }

    pub fn record_online_metric(
        &self,
        model_id: String,
        request_id: String,
        latency_ms: u64,
        success: bool,
        token_count: u64,
    ) -> Result<()> {
        self.online_service.record_request(model_id, request_id, latency_ms, success, token_count)
    }

    pub fn resolve_alert(&self, alert_id: &str) -> Result<()> {
        self.online_service.resolve_alert(alert_id)
    }

    pub fn offline_service(&self) -> &OfflineEvaluationService {
        &self.offline_service
    }

    pub fn online_service(&self) -> &OnlineMonitoringService {
        &self.online_service
    }

    pub fn drift_service(&self) -> &DriftDetectionService {
        &self.drift_service
    }

    pub fn get_all_model_ids(&self) -> Vec<String> {
        self.model_metadata.lock().keys().cloned().collect()
    }

    pub fn get_health_status(&self, model_id: &str) -> HealthStatus {
        let alerts = self.online_service.get_alerts(Some(model_id), None, true);
        let drifts = self.drift_service.get_active_drifts(Some(model_id));

        let has_critical_alert = alerts.iter().any(|a| 
            a.severity == super::online::AlertSeverity::Critical
        );
        let has_critical_drift = drifts.iter().any(|d| 
            d.drift_severity == DriftSeverity::Critical
        );
        let has_high_drift = drifts.iter().any(|d| 
            d.drift_severity == DriftSeverity::High
        );
        let has_warning_alert = alerts.iter().any(|a| 
            a.severity == super::online::AlertSeverity::Warning
        );

        if has_critical_alert || has_critical_drift {
            HealthStatus::Critical
        } else if has_high_drift || has_warning_alert {
            HealthStatus::Warning
        } else if alerts.is_empty() && drifts.is_empty() {
            HealthStatus::Healthy
        } else {
            HealthStatus::Degraded
        }
    }

    pub fn get_system_health(&self) -> SystemHealth {
        let model_ids = self.get_all_model_ids();
        let mut status_counts = HashMap::new();
        
        for model_id in &model_ids {
            let status = self.get_health_status(model_id);
            *status_counts.entry(status).or_insert(0) += 1;
        }

        SystemHealth {
            total_models: model_ids.len() as u64,
            healthy_count: *status_counts.get(&HealthStatus::Healthy).unwrap_or(&0),
            degraded_count: *status_counts.get(&HealthStatus::Degraded).unwrap_or(&0),
            warning_count: *status_counts.get(&HealthStatus::Warning).unwrap_or(&0),
            critical_count: *status_counts.get(&HealthStatus::Critical).unwrap_or(&0),
            timestamp: chrono::Utc::now(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum HealthStatus {
    Healthy,
    Degraded,
    Warning,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SystemHealth {
    pub total_models: u64,
    pub healthy_count: u64,
    pub degraded_count: u64,
    pub warning_count: u64,
    pub critical_count: u64,
    pub timestamp: chrono::DateTime<chrono::Utc>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::utils::metrics::MetricsCollector;
    use std::time::Duration;

    #[test]
    fn test_dashboard_service_creation() {
        let metrics = MetricsCollector::new();
        let config = DashboardConfig::default();
        let service = EvaluationDashboardService::new(metrics, config);
        
        assert!(service.get_all_model_ids().is_empty());
    }

    #[test]
    fn test_model_registration() {
        let metrics = MetricsCollector::new();
        let config = DashboardConfig::default();
        let service = EvaluationDashboardService::new(metrics, config);
        
        service.register_model(
            "model_001".to_string(),
            "Test Model".to_string(),
            "production".to_string(),
        );
        
        assert_eq!(service.get_all_model_ids(), vec!["model_001"]);
    }

    #[test]
    fn test_update_model_stage() {
        let metrics = MetricsCollector::new();
        let config = DashboardConfig::default();
        let service = EvaluationDashboardService::new(metrics, config);
        
        service.register_model(
            "model_001".to_string(),
            "Test Model".to_string(),
            "staging".to_string(),
        );
        
        service.update_model_stage("model_001", "production".to_string()).unwrap();
        
        let dashboard = service.get_dashboard("model_001").unwrap();
        assert_eq!(dashboard.current_stage, "production");
    }

    #[test]
    fn test_get_dashboard() {
        let metrics = MetricsCollector::new();
        let config = DashboardConfig {
            window_size: Duration::from_secs(60),
            ..Default::default()
        };
        let service = EvaluationDashboardService::new(metrics, config);
        
        service.register_model(
            "model_001".to_string(),
            "Test Model".to_string(),
            "production".to_string(),
        );

        for i in 0..10 {
            service.record_online_metric(
                "model_001".to_string(),
                format!("req_{}", i),
                150 + i * 10,
                i < 8,
                256,
            ).unwrap();
        }

        let dashboard = service.get_dashboard("model_001").unwrap();
        assert_eq!(dashboard.model_id, "model_001");
        assert_eq!(dashboard.model_name, "Test Model");
        assert_eq!(dashboard.overview.total_requests, 10);
        assert!(dashboard.overview.success_rate < 1.0);
    }

    #[test]
    fn test_health_status_healthy() {
        let metrics = MetricsCollector::new();
        let config = DashboardConfig::default();
        let service = EvaluationDashboardService::new(metrics, config);
        
        service.register_model(
            "model_001".to_string(),
            "Test Model".to_string(),
            "production".to_string(),
        );
        
        assert_eq!(service.get_health_status("model_001"), HealthStatus::Healthy);
    }

    #[test]
    fn test_health_status_with_alerts() {
        let metrics = MetricsCollector::new();
        let config = DashboardConfig {
            window_size: Duration::from_secs(60),
            ..Default::default()
        };
        let service = EvaluationDashboardService::new(metrics, config);
        
        service.register_model(
            "model_001".to_string(),
            "Test Model".to_string(),
            "production".to_string(),
        );

        service.set_threshold(MetricThreshold {
            metric_type: MetricType::Latency,
            warning_threshold: 500.0,
            critical_threshold: 1000.0,
            is_higher_better: false,
        });

        service.record_online_metric(
            "model_001".to_string(),
            "req_001".to_string(),
            600,
            true,
            256,
        ).unwrap();

        let status = service.get_health_status("model_001");
        assert_ne!(status, HealthStatus::Healthy);
    }

    #[test]
    fn test_system_health() {
        let metrics = MetricsCollector::new();
        let config = DashboardConfig::default();
        let service = EvaluationDashboardService::new(metrics, config);
        
        for i in 0..3 {
            service.register_model(
                format!("model_{:03}", i),
                format!("Model {}", i),
                "production".to_string(),
            );
        }

        let health = service.get_system_health();
        assert_eq!(health.total_models, 3);
        assert_eq!(health.healthy_count, 3);
        assert_eq!(health.critical_count, 0);
    }

    #[test]
    fn test_resolve_alert() {
        let metrics = MetricsCollector::new();
        let config = DashboardConfig {
            window_size: Duration::from_secs(60),
            ..Default::default()
        };
        let service = EvaluationDashboardService::new(metrics, config);
        
        service.register_model(
            "model_001".to_string(),
            "Test Model".to_string(),
            "production".to_string(),
        );

        service.set_threshold(MetricThreshold {
            metric_type: MetricType::ErrorRate,
            warning_threshold: 0.05,
            critical_threshold: 0.10,
            is_higher_better: false,
        });

        service.record_online_metric(
            "model_001".to_string(),
            "req_001".to_string(),
            150,
            false,
            256,
        ).unwrap();

        let alerts = service.online_service().get_alerts(Some("model_001"), None, true);
        assert!(!alerts.is_empty());
        
        let alert_id = alerts[0].alert_id.clone();
        service.resolve_alert(&alert_id).unwrap();
        
        let active_alerts = service.online_service().get_alerts(Some("model_001"), None, true);
        assert!(active_alerts.is_empty());
    }

    #[test]
    fn test_overview_metrics_calculation() {
        let metrics = MetricsCollector::new();
        let config = DashboardConfig {
            window_size: Duration::from_secs(60),
            ..Default::default()
        };
        let service = EvaluationDashboardService::new(metrics, config);
        
        service.register_model(
            "model_001".to_string(),
            "Test Model".to_string(),
            "production".to_string(),
        );

        for i in 0..100 {
            service.record_online_metric(
                "model_001".to_string(),
                format!("req_{}", i),
                100 + (i % 10) * 20,
                i % 20 != 0,
                256,
            ).unwrap();
        }

        let dashboard = service.get_dashboard("model_001").unwrap();
        assert_eq!(dashboard.overview.total_requests, 100);
        assert!(dashboard.overview.success_rate > 0.9);
        assert!(dashboard.overview.average_latency_ms > 0.0);
        assert!(dashboard.overview.p99_latency_ms >= dashboard.overview.p95_latency_ms);
    }
}
