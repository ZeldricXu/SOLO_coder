use std::collections::HashMap;
use std::sync::Arc;
use dashmap::DashMap;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::{DateTime, Utc, Duration};
use tracing::{info, debug, warn, error};
use crate::models::error::ModelGuardError;
use crate::models::Result;
use super::types::*;

pub struct EvaluationDashboardService {
    offline_evaluations: DashMap<String, OfflineEvaluation>,
    online_metrics: DashMap<String, DashMap<String, Vec<DataPoint>>>,
    monitoring_configs: DashMap<String, OnlineMonitoringConfig>,
    alerts: DashMap<String, Alert>,
    drift_results: DashMap<String, DriftDetectionResult>,
    reference_datasets: DashMap<String, Vec<f64>>,
    model_comparisons: DashMap<String, ModelComparison>,
    stats: RwLock<DashboardStats>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct DashboardStats {
    pub total_evaluations: u64,
    pub total_models_monitored: u64,
    pub active_alerts: u64,
    pub drift_detections: u64,
    pub significant_drifts: u64,
}

impl EvaluationDashboardService {
    pub fn new() -> Self {
        Self {
            offline_evaluations: DashMap::new(),
            online_metrics: DashMap::new(),
            monitoring_configs: DashMap::new(),
            alerts: DashMap::new(),
            drift_results: DashMap::new(),
            reference_datasets: DashMap::new(),
            model_comparisons: DashMap::new(),
            stats: RwLock::new(DashboardStats::default()),
        }
    }

    pub fn create_offline_evaluation(
        &self,
        model_id: &str,
        model_version: u32,
        dataset_id: &str,
        metrics: HashMap<String, MetricValue>,
    ) -> String {
        let evaluation = OfflineEvaluation {
            evaluation_id: Uuid::new_v4().to_string(),
            model_id: model_id.to_string(),
            model_version,
            dataset_id: dataset_id.to_string(),
            metrics,
            created_at: Utc::now(),
            completed_at: None,
            status: EvaluationStatus::Pending,
            error_message: None,
        };

        let id = evaluation.evaluation_id.clone();
        self.offline_evaluations.insert(id.clone(), evaluation);
        
        let mut stats = self.stats.write();
        stats.total_evaluations += 1;
        
        info!(evaluation_id = %id, model_id = %model_id, "Offline evaluation created");
        id
    }

    pub fn start_evaluation(&self, evaluation_id: &str) -> Result<()> {
        let mut eval = self.offline_evaluations.get_mut(evaluation_id).ok_or_else(|| {
            ModelGuardError::NotFound(format!("Evaluation {} not found", evaluation_id))
        })?;

        if eval.status != EvaluationStatus::Pending {
            return Err(ModelGuardError::ValidationError(
                format!("Evaluation {} is not in pending state", evaluation_id)
            ));
        }

        eval.status = EvaluationStatus::Running;
        debug!(evaluation_id = %evaluation_id, "Offline evaluation started");
        Ok(())
    }

    pub fn complete_evaluation(
        &self,
        evaluation_id: &str,
        metrics: HashMap<String, MetricValue>,
    ) -> Result<()> {
        let mut eval = self.offline_evaluations.get_mut(evaluation_id).ok_or_else(|| {
            ModelGuardError::NotFound(format!("Evaluation {} not found", evaluation_id))
        })?;

        if eval.status != EvaluationStatus::Running {
            return Err(ModelGuardError::ValidationError(
                format!("Evaluation {} is not running", evaluation_id)
            ));
        }

        eval.metrics = metrics;
        eval.status = EvaluationStatus::Completed;
        eval.completed_at = Some(Utc::now());
        
        info!(evaluation_id = %evaluation_id, "Offline evaluation completed");
        Ok(())
    }

    pub fn fail_evaluation(&self, evaluation_id: &str, error: &str) -> Result<()> {
        let mut eval = self.offline_evaluations.get_mut(evaluation_id).ok_or_else(|| {
            ModelGuardError::NotFound(format!("Evaluation {} not found", evaluation_id))
        })?;

        eval.status = EvaluationStatus::Failed;
        eval.error_message = Some(error.to_string());
        eval.completed_at = Some(Utc::now());
        
        warn!(evaluation_id = %evaluation_id, error = %error, "Offline evaluation failed");
        Ok(())
    }

    pub fn get_offline_evaluation(&self, evaluation_id: &str) -> Result<OfflineEvaluation> {
        self.offline_evaluations.get(evaluation_id)
            .map(|e| e.clone())
            .ok_or_else(|| ModelGuardError::NotFound(format!("Evaluation {} not found", evaluation_id)))
    }

    pub fn list_model_evaluations(&self, model_id: &str) -> Vec<OfflineEvaluation> {
        self.offline_evaluations
            .iter()
            .filter(|e| e.model_id == model_id)
            .map(|e| e.clone())
            .collect()
    }

    pub fn configure_online_monitoring(&self, config: OnlineMonitoringConfig) {
        let model_id = config.model_id.clone();
        self.monitoring_configs.insert(model_id.clone(), config);
        
        let mut stats = self.stats.write();
        stats.total_models_monitored += 1;
        
        info!(model_id = %model_id, "Online monitoring configured");
    }

    pub fn record_online_metric(
        &self,
        model_id: &str,
        metric: MetricType,
        value: f64,
        timestamp: Option<DateTime<Utc>>,
    ) {
        let model_metrics = self.online_metrics
            .entry(model_id.to_string())
            .or_insert_with(DashMap::new);
        
        let mut metric_points = model_metrics
            .entry(metric.to_str().to_string())
            .or_insert_with(Vec::new);
        
        let point = DataPoint::new(timestamp.unwrap_or_else(Utc::now), value);
        metric_points.push(point);
        
        if metric_points.len() > 10000 {
            let new_len = metric_points.len() / 2;
            metric_points.drain(0..new_len);
        }
        
        self.check_alerts(model_id, metric, value);
    }

    fn check_alerts(&self, model_id: &str, metric: MetricType, value: f64) {
        let config = match self.monitoring_configs.get(model_id) {
            Some(c) => c,
            None => return,
        };

        let threshold = match config.alert_thresholds.get(metric.to_str()) {
            Some(t) => t,
            None => return,
        };

        let is_breach = if metric.is_higher_better() {
            value < threshold.critical_threshold
        } else {
            value > threshold.critical_threshold
        };

        if is_breach {
            let existing_alerts: Vec<Alert> = self.alerts
                .iter()
                .filter(|a| a.model_id == model_id 
                    && a.metric == metric 
                    && a.resolved_at.is_none())
                .map(|a| a.clone())
                .collect();

            if existing_alerts.is_empty() {
                let severity = if (if metric.is_higher_better() {
                    value < threshold.critical_threshold
                } else {
                    value > threshold.critical_threshold
                }) {
                    AlertSeverity::Critical
                } else {
                    AlertSeverity::Warning
                };

                let alert = Alert {
                    alert_id: Uuid::new_v4().to_string(),
                    model_id: model_id.to_string(),
                    metric: metric.clone(),
                    severity,
                    current_value: value,
                    threshold: threshold.critical_threshold,
                    triggered_at: Utc::now(),
                    resolved_at: None,
                    description: format!(
                        "Metric {:?} breached threshold: value={}, threshold={}",
                        metric, value, threshold.critical_threshold
                    ),
                };

                self.alerts.insert(alert.alert_id.clone(), alert);
                
                let mut stats = self.stats.write();
                stats.active_alerts += 1;
                
                warn!(
                    model_id = %model_id,
                    metric = ?metric,
                    value = value,
                    threshold = threshold.critical_threshold,
                    "Alert triggered"
                );
            }
        }
    }

    pub fn get_online_metrics(
        &self,
        model_id: &str,
        metric: Option<MetricType>,
        start_time: Option<DateTime<Utc>>,
        end_time: Option<DateTime<Utc>>,
    ) -> Result<Vec<TimeSeries>> {
        let model_metrics = self.online_metrics.get(model_id).ok_or_else(|| {
            ModelGuardError::NotFound(format!("No metrics for model {}", model_id))
        })?;

        let mut results = Vec::new();
        let end = end_time.unwrap_or_else(Utc::now);
        let start = start_time.unwrap_or_else(|| end - Duration::hours(24));

        for entry in model_metrics.iter() {
            let metric_name = entry.key();
            
            if let Some(ref filter) = metric {
                if filter.to_str() != metric_name {
                    continue;
                }
            }

            let points: Vec<DataPoint> = entry.value()
                .iter()
                .filter(|p| p.timestamp >= start && p.timestamp <= end)
                .cloned()
                .collect();

            if !points.is_empty() {
                let metric_type = match metric_name.as_str() {
                    "accuracy" => MetricType::Accuracy,
                    "precision" => MetricType::Precision,
                    "recall" => MetricType::Recall,
                    "f1_score" => MetricType::F1Score,
                    "auc" => MetricType::AUC,
                    "mse" => MetricType::MSE,
                    "mae" => MetricType::MAE,
                    "rmse" => MetricType::RMSE,
                    "r2_score" => MetricType::R2Score,
                    "latency_p50" => MetricType::LatencyP50,
                    "latency_p95" => MetricType::LatencyP95,
                    "latency_p99" => MetricType::LatencyP99,
                    "throughput" => MetricType::Throughput,
                    "error_rate" => MetricType::ErrorRate,
                    "safety_score" => MetricType::SafetyScore,
                    "hallucination_rate" => MetricType::HallucinationRate,
                    "relevance_score" => MetricType::RelevanceScore,
                    "coherence_score" => MetricType::CoherenceScore,
                    other => MetricType::Custom(other.to_string()),
                };

                results.push(TimeSeries {
                    metric: metric_type,
                    model_id: model_id.to_string(),
                    points,
                    start_time: start,
                    end_time: end,
                });
            }
        }

        Ok(results)
    }

    pub fn get_latest_online_metrics(&self, model_id: &str) -> Result<HashMap<String, f64>> {
        let series = self.get_online_metrics(model_id, None, None, None)?;
        let mut latest = HashMap::new();

        for s in series {
            if let Some(last) = s.points.last() {
                latest.insert(s.metric.to_str().to_string(), last.value);
            }
        }

        Ok(latest)
    }

    pub fn set_reference_dataset(&self, dataset_id: &str, values: Vec<f64>) {
        self.reference_datasets.insert(dataset_id.to_string(), values);
        info!(dataset_id = %dataset_id, "Reference dataset set");
    }

    pub fn detect_drift(
        &self,
        model_id: &str,
        metric: MetricType,
        drift_type: DriftType,
        reference_dataset_id: &str,
        current_values: &[f64],
        significance_level: f64,
    ) -> Result<DriftDetectionResult> {
        let reference = self.reference_datasets.get(reference_dataset_id).ok_or_else(|| {
            ModelGuardError::NotFound(format!("Reference dataset {} not found", reference_dataset_id))
        })?;

        let (drift_score, p_value) = ks_test(&reference, current_values);
        
        let is_significant = p_value < significance_level;
        
        let ref_stats = DistributionStats::from_values(&reference).ok_or_else(|| {
            ModelGuardError::ValidationError("Invalid reference dataset".to_string())
        })?;
        
        let cur_stats = DistributionStats::from_values(current_values).ok_or_else(|| {
            ModelGuardError::ValidationError("Invalid current values".to_string())
        })?;

        let result = DriftDetectionResult {
            drift_id: Uuid::new_v4().to_string(),
            model_id: model_id.to_string(),
            metric: metric.clone(),
            drift_type,
            drift_score,
            p_value,
            is_significant,
            reference_distribution: ref_stats,
            current_distribution: cur_stats,
            detected_at: Utc::now(),
        };

        self.drift_results.insert(result.drift_id.clone(), result.clone());
        
        let mut stats = self.stats.write();
        stats.drift_detections += 1;
        if is_significant {
            stats.significant_drifts += 1;
        }
        
        if is_significant {
            warn!(
                drift_id = %result.drift_id,
                model_id = %model_id,
                metric = ?metric,
                drift_score = drift_score,
                p_value = p_value,
                "Significant drift detected"
            );
        } else {
            debug!(
                drift_id = %result.drift_id,
                model_id = %model_id,
                metric = ?metric,
                "No significant drift detected"
            );
        }

        Ok(result)
    }

    pub fn get_drift_results(&self, model_id: &str, limit: usize) -> Vec<DriftDetectionResult> {
        let mut results: Vec<DriftDetectionResult> = self.drift_results
            .iter()
            .filter(|d| d.model_id == model_id)
            .map(|d| d.clone())
            .collect();
        
        results.sort_by(|a, b| b.detected_at.cmp(&a.detected_at));
        results.truncate(limit);
        results
    }

    pub fn compare_models(
        &self,
        model_ids: Vec<String>,
        base_model_id: String,
        metrics: Vec<MetricType>,
    ) -> Result<ModelComparison> {
        if model_ids.len() < 2 {
            return Err(ModelGuardError::ValidationError(
                "At least 2 models required for comparison".to_string()
            ));
        }

        if !model_ids.contains(&base_model_id) {
            return Err(ModelGuardError::ValidationError(
                "Base model must be in the model list".to_string()
            ));
        }

        let mut results: HashMap<String, HashMap<String, f64>> = HashMap::new();
        let mut winners: HashMap<String, String> = HashMap::new();

        for metric in &metrics {
            let mut best_model = String::new();
            let mut best_value = if metric.is_higher_better() { f64::NEG_INFINITY } else { f64::INFINITY };
            
            for model_id in &model_ids {
                let evals = self.list_model_evaluations(model_id);
                let completed: Vec<_> = evals.iter()
                    .filter(|e| e.status == EvaluationStatus::Completed)
                    .collect();
                
                let value = completed.last()
                    .and_then(|e| e.metrics.get(metric.to_str()))
                    .map(|m| m.value)
                    .unwrap_or(0.0);
                
                results
                    .entry(model_id.clone())
                    .or_insert_with(HashMap::new)
                    .insert(metric.to_str().to_string(), value);
                
                let is_better = if metric.is_higher_better() {
                    value > best_value
                } else {
                    value < best_value
                };
                
                if is_better {
                    best_value = value;
                    best_model = model_id.clone();
                }
            }
            
            winners.insert(metric.to_str().to_string(), best_model);
        }

        let comparison = ModelComparison {
            comparison_id: Uuid::new_v4().to_string(),
            model_ids,
            base_model_id,
            metrics,
            results,
            winners,
            created_at: Utc::now(),
        };

        self.model_comparisons.insert(comparison.comparison_id.clone(), comparison.clone());
        
        info!(comparison_id = %comparison.comparison_id, "Model comparison created");
        Ok(comparison)
    }

    pub fn get_model_summary(&self, model_id: &str) -> Result<EvaluationSummary> {
        let evaluations = self.list_model_evaluations(model_id);
        let latest_offline = evaluations.into_iter()
            .filter(|e| e.status == EvaluationStatus::Completed)
            .max_by_key(|e| e.created_at);

        let online_metrics = self.get_latest_online_metrics(model_id).unwrap_or_default();

        let active_alerts: Vec<Alert> = self.alerts
            .iter()
            .filter(|a| a.model_id == model_id && a.resolved_at.is_none())
            .map(|a| a.clone())
            .collect();

        let recent_drifts = self.get_drift_results(model_id, 10);

        let metric_values: Vec<MetricValue> = online_metrics
            .iter()
            .map(|(k, v)| {
                let metric_type = match k.as_str() {
                    "accuracy" => MetricType::Accuracy,
                    "error_rate" => MetricType::ErrorRate,
                    "latency_p99" => MetricType::LatencyP99,
                    _ => MetricType::Custom(k.clone()),
                };
                MetricValue::new(metric_type, *v)
            })
            .collect();

        let health_score = calculate_health_score(&metric_values, &active_alerts, &recent_drifts);

        Ok(EvaluationSummary {
            model_id: model_id.to_string(),
            latest_offline_evaluation: latest_offline,
            online_metrics,
            active_alerts,
            recent_drifts,
            overall_health_score: health_score,
        })
    }

    pub fn resolve_alert(&self, alert_id: &str) -> Result<()> {
        let mut alert = self.alerts.get_mut(alert_id).ok_or_else(|| {
            ModelGuardError::NotFound(format!("Alert {} not found", alert_id))
        })?;

        if alert.resolved_at.is_some() {
            return Err(ModelGuardError::ValidationError(
                format!("Alert {} already resolved", alert_id)
            ));
        }

        alert.resolved_at = Some(Utc::now());
        
        let mut stats = self.stats.write();
        stats.active_alerts = stats.active_alerts.saturating_sub(1);
        
        info!(alert_id = %alert_id, "Alert resolved");
        Ok(())
    }

    pub fn get_active_alerts(&self, model_id: Option<&str>) -> Vec<Alert> {
        self.alerts
            .iter()
            .filter(|a| a.resolved_at.is_none() 
                && model_id.map_or(true, |m| a.model_id == m))
            .map(|a| a.clone())
            .collect()
    }

    pub fn get_stats(&self) -> DashboardStats {
        self.stats.read().clone()
    }
}

impl Default for EvaluationDashboardService {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_offline_evaluation_workflow() {
        let service = EvaluationDashboardService::new();
        
        let mut metrics = HashMap::new();
        metrics.insert("accuracy".to_string(), MetricValue::new(MetricType::Accuracy, 0.95));
        metrics.insert("f1_score".to_string(), MetricValue::new(MetricType::F1Score, 0.92));
        
        let eval_id = service.create_offline_evaluation("model-1", 1, "dataset-1", metrics);
        
        service.start_evaluation(&eval_id).unwrap();
        
        let mut result_metrics = HashMap::new();
        result_metrics.insert("accuracy".to_string(), MetricValue::new(MetricType::Accuracy, 0.96).with_sample_size(10000));
        result_metrics.insert("f1_score".to_string(), MetricValue::new(MetricType::F1Score, 0.93).with_sample_size(10000));
        
        service.complete_evaluation(&eval_id, result_metrics).unwrap();
        
        let eval = service.get_offline_evaluation(&eval_id).unwrap();
        assert_eq!(eval.status, EvaluationStatus::Completed);
        assert!(eval.completed_at.is_some());
        assert_eq!(eval.metrics.get("accuracy").unwrap().value, 0.96);
    }

    #[tokio::test]
    async fn test_online_metrics_and_alerts() {
        let service = EvaluationDashboardService::new();
        
        let mut thresholds = HashMap::new();
        thresholds.insert("error_rate".to_string(), AlertThreshold {
            metric: MetricType::ErrorRate,
            warning_threshold: 0.05,
            critical_threshold: 0.1,
            is_relative: false,
        });
        
        let config = OnlineMonitoringConfig {
            model_id: "model-1".to_string(),
            metrics: vec![MetricType::ErrorRate, MetricType::LatencyP99],
            collection_interval_sec: 60,
            window_size_minutes: 60,
            alert_thresholds: thresholds,
        };
        
        service.configure_online_monitoring(config);
        
        service.record_online_metric("model-1", MetricType::ErrorRate, 0.03, None);
        service.record_online_metric("model-1", MetricType::ErrorRate, 0.15, None);
        
        let alerts = service.get_active_alerts(Some("model-1"));
        assert!(!alerts.is_empty());
        assert_eq!(alerts[0].severity, AlertSeverity::Critical);
        
        let latest = service.get_latest_online_metrics("model-1").unwrap();
        assert_eq!(latest.get("error_rate").unwrap(), &0.15);
    }

    #[tokio::test]
    async fn test_drift_detection() {
        let service = EvaluationDashboardService::new();
        
        let reference: Vec<f64> = (0..100).map(|i| i as f64 / 100.0).collect();
        service.set_reference_dataset("ref-1", reference);
        
        let current_normal: Vec<f64> = (0..100).map(|i| i as f64 / 100.0 + (rand::random::<f64>() - 0.5) * 0.1).collect();
        
        let result1 = service.detect_drift(
            "model-1",
            MetricType::Accuracy,
            DriftType::DataDrift,
            "ref-1",
            &current_normal,
            0.05,
        ).unwrap();
        
        assert!(!result1.is_significant);
        
        let current_drifted: Vec<f64> = (0..100).map(|i| (i as f64 / 100.0) + 0.5).collect();
        
        let result2 = service.detect_drift(
            "model-1",
            MetricType::Accuracy,
            DriftType::DataDrift,
            "ref-1",
            &current_drifted,
            0.05,
        ).unwrap();
        
        assert!(result2.is_significant);
        assert!(result2.drift_score > 0.3);
        assert!(result2.p_value < 0.05);
    }

    #[tokio::test]
    async fn test_model_comparison() {
        let service = EvaluationDashboardService::new();
        
        let mut metrics1 = HashMap::new();
        metrics1.insert("accuracy".to_string(), MetricValue::new(MetricType::Accuracy, 0.90));
        let eval1 = service.create_offline_evaluation("model-a", 1, "dataset-1", metrics1);
        service.start_evaluation(&eval1).unwrap();
        let mut results1 = HashMap::new();
        results1.insert("accuracy".to_string(), MetricValue::new(MetricType::Accuracy, 0.90));
        service.complete_evaluation(&eval1, results1).unwrap();
        
        let mut metrics2 = HashMap::new();
        metrics2.insert("accuracy".to_string(), MetricValue::new(MetricType::Accuracy, 0.95));
        let eval2 = service.create_offline_evaluation("model-b", 1, "dataset-1", metrics2);
        service.start_evaluation(&eval2).unwrap();
        let mut results2 = HashMap::new();
        results2.insert("accuracy".to_string(), MetricValue::new(MetricType::Accuracy, 0.95));
        service.complete_evaluation(&eval2, results2).unwrap();
        
        let comparison = service.compare_models(
            vec!["model-a".to_string(), "model-b".to_string()],
            "model-a".to_string(),
            vec![MetricType::Accuracy],
        ).unwrap();
        
        assert_eq!(comparison.winners.get("accuracy").unwrap(), "model-b");
        assert_eq!(comparison.results.get("model-b").unwrap().get("accuracy").unwrap(), &0.95);
    }

    #[tokio::test]
    async fn test_model_summary() {
        let service = EvaluationDashboardService::new();
        
        let mut metrics = HashMap::new();
        metrics.insert("accuracy".to_string(), MetricValue::new(MetricType::Accuracy, 0.95));
        let eval_id = service.create_offline_evaluation("model-1", 1, "dataset-1", metrics);
        service.start_evaluation(&eval_id).unwrap();
        let mut results = HashMap::new();
        results.insert("accuracy".to_string(), MetricValue::new(MetricType::Accuracy, 0.95));
        service.complete_evaluation(&eval_id, results).unwrap();
        
        service.record_online_metric("model-1", MetricType::Accuracy, 0.94, None);
        service.record_online_metric("model-1", MetricType::ErrorRate, 0.02, None);
        
        let summary = service.get_model_summary("model-1").unwrap();
        
        assert!(summary.latest_offline_evaluation.is_some());
        assert_eq!(summary.online_metrics.get("accuracy").unwrap(), &0.94);
        assert!(summary.overall_health_score > 50.0);
    }

    #[tokio::test]
    async fn test_alert_resolution() {
        let service = EvaluationDashboardService::new();
        
        let mut thresholds = HashMap::new();
        thresholds.insert("error_rate".to_string(), AlertThreshold {
            metric: MetricType::ErrorRate,
            warning_threshold: 0.05,
            critical_threshold: 0.1,
            is_relative: false,
        });
        
        service.configure_online_monitoring(OnlineMonitoringConfig {
            model_id: "model-1".to_string(),
            metrics: vec![MetricType::ErrorRate],
            collection_interval_sec: 60,
            window_size_minutes: 60,
            alert_thresholds: thresholds,
        });
        
        service.record_online_metric("model-1", MetricType::ErrorRate, 0.15, None);
        
        let alerts = service.get_active_alerts(Some("model-1"));
        assert_eq!(alerts.len(), 1);
        
        service.resolve_alert(&alerts[0].alert_id).unwrap();
        
        let alerts = service.get_active_alerts(Some("model-1"));
        assert_eq!(alerts.len(), 0);
    }

    #[tokio::test]
    async fn test_evaluation_failure() {
        let service = EvaluationDashboardService::new();
        
        let metrics = HashMap::new();
        let eval_id = service.create_offline_evaluation("model-1", 1, "dataset-1", metrics);
        
        service.start_evaluation(&eval_id).unwrap();
        service.fail_evaluation(&eval_id, "Out of memory error").unwrap();
        
        let eval = service.get_offline_evaluation(&eval_id).unwrap();
        assert_eq!(eval.status, EvaluationStatus::Failed);
        assert_eq!(eval.error_message.as_deref(), Some("Out of memory error"));
    }
}

fn rand() -> impl FnMut() -> f64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    let mut seed = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos() as u64;
    move || {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        (seed as f64) / (u64::MAX as f64)
    }
}
