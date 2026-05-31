use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use parking_lot::Mutex;
use std::sync::Arc;

use crate::utils::error::Result;
use crate::utils::metrics::MetricsCollector;

use super::metrics::{MetricType, MetricValue, MetricComparison, MetricThreshold};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EvaluationDataset {
    pub dataset_id: String,
    pub name: String,
    pub description: String,
    pub sample_count: usize,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub tags: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OfflineEvaluationRequest {
    pub model_id: String,
    pub dataset_id: String,
    pub metrics: Vec<MetricType>,
    pub name: String,
    pub description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OfflineEvaluationResult {
    pub evaluation_id: String,
    pub model_id: String,
    pub dataset_id: String,
    pub name: String,
    pub description: String,
    pub metrics: HashMap<MetricType, MetricValue>,
    pub status: EvaluationStatus,
    pub started_at: chrono::DateTime<chrono::Utc>,
    pub completed_at: Option<chrono::DateTime<chrono::Utc>>,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum EvaluationStatus {
    Pending,
    Running,
    Completed,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelComparison {
    pub comparison_id: String,
    pub baseline_model_id: String,
    pub candidate_model_id: String,
    pub dataset_id: String,
    pub metric_comparisons: HashMap<MetricType, MetricComparison>,
    pub overall_winner: String,
    pub confidence_score: f64,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

pub struct OfflineEvaluationService {
    metrics: MetricsCollector,
    evaluations: Arc<Mutex<HashMap<String, OfflineEvaluationResult>>>,
    datasets: Arc<Mutex<HashMap<String, EvaluationDataset>>>,
    thresholds: Arc<Mutex<HashMap<MetricType, MetricThreshold>>>,
}

impl OfflineEvaluationService {
    pub fn new(metrics: MetricsCollector) -> Self {
        Self {
            metrics,
            evaluations: Arc::new(Mutex::new(HashMap::new())),
            datasets: Arc::new(Mutex::new(HashMap::new())),
            thresholds: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn register_dataset(&self, dataset: EvaluationDataset) -> Result<EvaluationDataset> {
        self.datasets.lock().insert(dataset.dataset_id.clone(), dataset.clone());
        Ok(dataset)
    }

    pub fn get_dataset(&self, dataset_id: &str) -> Option<EvaluationDataset> {
        self.datasets.lock().get(dataset_id).cloned()
    }

    pub fn list_datasets(&self) -> Vec<EvaluationDataset> {
        self.datasets.lock().values().cloned().collect()
    }

    pub fn set_threshold(&self, threshold: MetricThreshold) {
        self.thresholds.lock().insert(threshold.metric_type.clone(), threshold);
    }

    pub fn get_threshold(&self, metric_type: &MetricType) -> Option<MetricThreshold> {
        self.thresholds.lock().get(metric_type).cloned()
    }

    pub fn start_evaluation(
        &self,
        request: OfflineEvaluationRequest,
    ) -> Result<OfflineEvaluationResult> {
        self.metrics.increment_counter("offline_evaluation_started");

        let evaluation_id = format!("eval_{}", crate::utils::id::generate_id());
        let now = chrono::Utc::now();

        let result = OfflineEvaluationResult {
            evaluation_id: evaluation_id.clone(),
            model_id: request.model_id.clone(),
            dataset_id: request.dataset_id.clone(),
            name: request.name,
            description: request.description,
            metrics: HashMap::new(),
            status: EvaluationStatus::Running,
            started_at: now,
            completed_at: None,
            error_message: None,
        };

        self.evaluations.lock().insert(evaluation_id, result.clone());
        Ok(result)
    }

    pub fn complete_evaluation(
        &self,
        evaluation_id: &str,
        metrics: Vec<MetricValue>,
    ) -> Result<OfflineEvaluationResult> {
        let mut evaluations = self.evaluations.lock();
        let evaluation = evaluations.get_mut(evaluation_id)
            .ok_or_else(|| crate::utils::error::AppError::NotFound(format!(
                "Evaluation {} not found", evaluation_id
            )))?;

        let mut metric_map = HashMap::new();
        for metric in metrics {
            metric_map.insert(metric.metric_type.clone(), metric);
        }

        evaluation.metrics = metric_map;
        evaluation.status = EvaluationStatus::Completed;
        evaluation.completed_at = Some(chrono::Utc::now());

        self.metrics.increment_counter("offline_evaluation_completed");
        Ok(evaluation.clone())
    }

    pub fn fail_evaluation(
        &self,
        evaluation_id: &str,
        error_message: String,
    ) -> Result<OfflineEvaluationResult> {
        let mut evaluations = self.evaluations.lock();
        let evaluation = evaluations.get_mut(evaluation_id)
            .ok_or_else(|| crate::utils::error::AppError::NotFound(format!(
                "Evaluation {} not found", evaluation_id
            )))?;

        evaluation.status = EvaluationStatus::Failed;
        evaluation.completed_at = Some(chrono::Utc::now());
        evaluation.error_message = Some(error_message);

        self.metrics.increment_counter("offline_evaluation_failed");
        Ok(evaluation.clone())
    }

    pub fn get_evaluation(&self, evaluation_id: &str) -> Option<OfflineEvaluationResult> {
        self.evaluations.lock().get(evaluation_id).cloned()
    }

    pub fn list_evaluations(&self, model_id: Option<&str>) -> Vec<OfflineEvaluationResult> {
        let evaluations = self.evaluations.lock();
        let mut results: Vec<OfflineEvaluationResult> = if let Some(model_id) = model_id {
            evaluations.values()
                .filter(|e| e.model_id == model_id)
                .cloned()
                .collect()
        } else {
            evaluations.values().cloned().collect()
        };
        
        results.sort_by(|a, b| b.started_at.cmp(&a.started_at));
        results
    }

    pub fn compare_models(
        &self,
        baseline_model_id: String,
        candidate_model_id: String,
        dataset_id: String,
        significance_threshold: f64,
    ) -> Result<ModelComparison> {
        let evaluations = self.evaluations.lock();
        
        let baseline_eval = evaluations.values()
            .find(|e| e.model_id == baseline_model_id && e.dataset_id == dataset_id && e.status == EvaluationStatus::Completed)
            .ok_or_else(|| crate::utils::error::AppError::NotFound(format!(
                "No completed evaluation found for baseline model {} on dataset {}",
                baseline_model_id, dataset_id
            )))?;

        let candidate_eval = evaluations.values()
            .find(|e| e.model_id == candidate_model_id && e.dataset_id == dataset_id && e.status == EvaluationStatus::Completed)
            .ok_or_else(|| crate::utils::error::AppError::NotFound(format!(
                "No completed evaluation found for candidate model {} on dataset {}",
                candidate_model_id, dataset_id
            )))?;

        let mut metric_comparisons = HashMap::new();
        let mut baseline_wins = 0;
        let mut candidate_wins = 0;
        let mut ties = 0;

        for (metric_type, baseline_metric) in &baseline_eval.metrics {
            if let Some(candidate_metric) = candidate_eval.metrics.get(metric_type) {
                let comparison = MetricComparison::calculate(
                    metric_type.clone(),
                    baseline_metric.value,
                    candidate_metric.value,
                    significance_threshold,
                );

                let threshold = self.thresholds.lock().get(metric_type).cloned();
                let is_higher_better = threshold.map(|t| t.is_higher_better).unwrap_or(true);

                if comparison.is_significant {
                    if is_higher_better {
                        if comparison.delta > 0.0 {
                            candidate_wins += 1;
                        } else {
                            baseline_wins += 1;
                        }
                    } else {
                        if comparison.delta < 0.0 {
                            candidate_wins += 1;
                        } else {
                            baseline_wins += 1;
                        }
                    }
                } else {
                    ties += 1;
                }

                metric_comparisons.insert(metric_type.clone(), comparison);
            }
        }

        let total = baseline_wins + candidate_wins + ties;
        let overall_winner = if candidate_wins > baseline_wins {
            candidate_model_id.clone()
        } else if baseline_wins > candidate_wins {
            baseline_model_id.clone()
        } else {
            "tie".to_string()
        };

        let confidence_score = if total > 0 {
            std::cmp::max(baseline_wins, candidate_wins) as f64 / total as f64
        } else {
            0.5
        };

        Ok(ModelComparison {
            comparison_id: format!("cmp_{}", crate::utils::id::generate_id()),
            baseline_model_id,
            candidate_model_id,
            dataset_id,
            metric_comparisons,
            overall_winner,
            confidence_score,
            created_at: chrono::Utc::now(),
        })
    }

    pub fn check_threshold_violations(
        &self,
        evaluation_id: &str,
    ) -> Result<Vec<(MetricType, String)>> {
        let evaluation = self.get_evaluation(evaluation_id)
            .ok_or_else(|| crate::utils::error::AppError::NotFound(format!(
                "Evaluation {} not found", evaluation_id
            )))?;

        let mut violations = Vec::new();
        let thresholds = self.thresholds.lock();

        for (metric_type, metric_value) in &evaluation.metrics {
            if let Some(threshold) = thresholds.get(metric_type) {
                let value = metric_value.value;
                if threshold.is_higher_better {
                    if value < threshold.critical_threshold {
                        violations.push((metric_type.clone(), format!(
                            "CRITICAL: Value {:.4} below critical threshold {:.4}",
                            value, threshold.critical_threshold
                        )));
                    } else if value < threshold.warning_threshold {
                        violations.push((metric_type.clone(), format!(
                            "WARNING: Value {:.4} below warning threshold {:.4}",
                            value, threshold.warning_threshold
                        )));
                    }
                } else {
                    if value > threshold.critical_threshold {
                        violations.push((metric_type.clone(), format!(
                            "CRITICAL: Value {:.4} above critical threshold {:.4}",
                            value, threshold.critical_threshold
                        )));
                    } else if value > threshold.warning_threshold {
                        violations.push((metric_type.clone(), format!(
                            "WARNING: Value {:.4} above warning threshold {:.4}",
                            value, threshold.warning_threshold
                        )));
                    }
                }
            }
        }

        Ok(violations)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::utils::metrics::MetricsCollector;

    #[test]
    fn test_dataset_registration() {
        let metrics = MetricsCollector::new();
        let service = OfflineEvaluationService::new(metrics);
        
        let dataset = EvaluationDataset {
            dataset_id: "ds_001".to_string(),
            name: "Test Dataset".to_string(),
            description: "Test dataset for evaluation".to_string(),
            sample_count: 1000,
            created_at: chrono::Utc::now(),
            tags: HashMap::new(),
        };

        let result = service.register_dataset(dataset.clone()).unwrap();
        assert_eq!(result.dataset_id, "ds_001");
        
        let retrieved = service.get_dataset("ds_001").unwrap();
        assert_eq!(retrieved.name, "Test Dataset");
    }

    #[test]
    fn test_evaluation_lifecycle() {
        let metrics = MetricsCollector::new();
        let service = OfflineEvaluationService::new(metrics);

        let request = OfflineEvaluationRequest {
            model_id: "model_001".to_string(),
            dataset_id: "ds_001".to_string(),
            metrics: vec![MetricType::Accuracy, MetricType::Latency],
            name: "Test Evaluation".to_string(),
            description: "Testing evaluation flow".to_string(),
        };

        let eval = service.start_evaluation(request).unwrap();
        assert_eq!(eval.status, EvaluationStatus::Running);

        let metrics = vec![
            MetricValue::new(MetricType::Accuracy, 0.95, "model_001".to_string()),
            MetricValue::new(MetricType::Latency, 150.0, "model_001".to_string()),
        ];

        let completed = service.complete_evaluation(&eval.evaluation_id, metrics).unwrap();
        assert_eq!(completed.status, EvaluationStatus::Completed);
        assert_eq!(completed.metrics.len(), 2);
        assert!(completed.completed_at.is_some());
    }

    #[test]
    fn test_evaluation_failure() {
        let metrics = MetricsCollector::new();
        let service = OfflineEvaluationService::new(metrics);

        let request = OfflineEvaluationRequest {
            model_id: "model_001".to_string(),
            dataset_id: "ds_001".to_string(),
            metrics: vec![],
            name: "Failing Evaluation".to_string(),
            description: "This will fail".to_string(),
        };

        let eval = service.start_evaluation(request).unwrap();
        let failed = service.fail_evaluation(&eval.evaluation_id, "Timeout error".to_string()).unwrap();
        
        assert_eq!(failed.status, EvaluationStatus::Failed);
        assert_eq!(failed.error_message, Some("Timeout error".to_string()));
    }

    #[test]
    fn test_model_comparison() {
        let metrics = MetricsCollector::new();
        let service = OfflineEvaluationService::new(metrics);

        service.set_threshold(MetricThreshold {
            metric_type: MetricType::Accuracy,
            warning_threshold: 0.85,
            critical_threshold: 0.80,
            is_higher_better: true,
        });

        let eval1 = service.start_evaluation(OfflineEvaluationRequest {
            model_id: "baseline".to_string(),
            dataset_id: "ds_001".to_string(),
            metrics: vec![MetricType::Accuracy],
            name: "Baseline Eval".to_string(),
            description: "".to_string(),
        }).unwrap();

        service.complete_evaluation(&eval1.evaluation_id, vec![
            MetricValue::new(MetricType::Accuracy, 0.90, "baseline".to_string()),
        ]).unwrap();

        let eval2 = service.start_evaluation(OfflineEvaluationRequest {
            model_id: "candidate".to_string(),
            dataset_id: "ds_001".to_string(),
            metrics: vec![MetricType::Accuracy],
            name: "Candidate Eval".to_string(),
            description: "".to_string(),
        }).unwrap();

        service.complete_evaluation(&eval2.evaluation_id, vec![
            MetricValue::new(MetricType::Accuracy, 0.95, "candidate".to_string()),
        ]).unwrap();

        let comparison = service.compare_models(
            "baseline".to_string(),
            "candidate".to_string(),
            "ds_001".to_string(),
            5.0,
        ).unwrap();

        assert_eq!(comparison.overall_winner, "candidate");
        assert!(comparison.confidence_score > 0.5);
        assert!(comparison.metric_comparisons.contains_key(&MetricType::Accuracy));
    }

    #[test]
    fn test_threshold_violations() {
        let metrics = MetricsCollector::new();
        let service = OfflineEvaluationService::new(metrics);

        service.set_threshold(MetricThreshold {
            metric_type: MetricType::Accuracy,
            warning_threshold: 0.85,
            critical_threshold: 0.80,
            is_higher_better: true,
        });

        service.set_threshold(MetricThreshold {
            metric_type: MetricType::ErrorRate,
            warning_threshold: 0.05,
            critical_threshold: 0.10,
            is_higher_better: false,
        });

        let eval = service.start_evaluation(OfflineEvaluationRequest {
            model_id: "model_001".to_string(),
            dataset_id: "ds_001".to_string(),
            metrics: vec![MetricType::Accuracy, MetricType::ErrorRate],
            name: "Threshold Test".to_string(),
            description: "".to_string(),
        }).unwrap();

        service.complete_evaluation(&eval.evaluation_id, vec![
            MetricValue::new(MetricType::Accuracy, 0.78, "model_001".to_string()),
            MetricValue::new(MetricType::ErrorRate, 0.12, "model_001".to_string()),
        ]).unwrap();

        let violations = service.check_threshold_violations(&eval.evaluation_id).unwrap();
        assert_eq!(violations.len(), 2);
    }
}
