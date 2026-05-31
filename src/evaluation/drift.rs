use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use parking_lot::Mutex;
use std::sync::Arc;

use crate::utils::error::Result;
use crate::utils::metrics::MetricsCollector;

use super::metrics::{MetricType, MetricValue};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DistributionStats {
    pub mean: f64,
    pub variance: f64,
    pub std_dev: f64,
    pub skewness: f64,
    pub kurtosis: f64,
    pub min: f64,
    pub max: f64,
    pub median: f64,
    pub percentiles: HashMap<String, f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DriftDetectionResult {
    pub detection_id: String,
    pub metric_type: MetricType,
    pub model_id: String,
    pub drift_detected: bool,
    pub drift_severity: DriftSeverity,
    pub drift_score: f64,
    pub threshold: f64,
    pub baseline_stats: DistributionStats,
    pub current_stats: DistributionStats,
    pub ks_test_p_value: f64,
    pub wasserstein_distance: f64,
    pub timestamp: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DriftSeverity {
    None,
    Low,
    Medium,
    High,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DriftDetectionConfig {
    pub metric_type: MetricType,
    pub threshold: f64,
    pub window_size_days: i64,
    pub baseline_size_days: i64,
    pub enabled: bool,
}

impl Default for DriftDetectionConfig {
    fn default() -> Self {
        Self {
            metric_type: MetricType::Latency,
            threshold: 0.05,
            window_size_days: 1,
            baseline_size_days: 7,
            enabled: true,
        }
    }
}

pub struct DriftDetectionService {
    metrics: MetricsCollector,
    configs: Arc<Mutex<HashMap<MetricType, DriftDetectionConfig>>>,
    detection_history: Arc<Mutex<Vec<DriftDetectionResult>>>,
    baseline_data: Arc<Mutex<HashMap<String, Vec<f64>>>>,
    current_data: Arc<Mutex<HashMap<String, Vec<f64>>>>,
}

impl DriftDetectionService {
    pub fn new(metrics: MetricsCollector) -> Self {
        Self {
            metrics,
            configs: Arc::new(Mutex::new(HashMap::new())),
            detection_history: Arc::new(Mutex::new(Vec::new())),
            baseline_data: Arc::new(Mutex::new(HashMap::new())),
            current_data: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn set_config(&self, config: DriftDetectionConfig) {
        self.configs.lock().insert(config.metric_type.clone(), config);
    }

    pub fn get_config(&self, metric_type: &MetricType) -> Option<DriftDetectionConfig> {
        self.configs.lock().get(metric_type).cloned()
    }

    pub fn record_value(&self, model_id: &str, metric_type: &MetricType, value: f64) {
        let key = format!("{}:{:?}", model_id, metric_type);
        let mut current_data = self.current_data.lock();
        let entry = current_data.entry(key).or_insert_with(Vec::new);
        entry.push(value);

        if entry.len() > 10000 {
            let excess = entry.len() - 10000;
            entry.drain(0..excess);
        }
    }

    pub fn record_metric_values(&self, values: Vec<MetricValue>) {
        for value in values {
            self.record_value(&value.model_id, &value.metric_type, value.value);
        }
    }

    pub fn set_baseline(&self, model_id: &str, metric_type: &MetricType, values: Vec<f64>) {
        let key = format!("{}:{:?}", model_id, metric_type);
        self.baseline_data.lock().insert(key, values);
    }

    pub fn detect_drift(
        &self,
        model_id: &str,
        metric_type: MetricType,
    ) -> Result<DriftDetectionResult> {
        self.metrics.increment_counter("drift_detection_run");

        let key = format!("{}:{:?}", model_id, metric_type);
        let baseline = self.baseline_data.lock().get(&key).cloned().unwrap_or_default();
        let current = self.current_data.lock().get(&key).cloned().unwrap_or_default();

        if baseline.is_empty() || current.is_empty() {
            return Err(crate::utils::error::AppError::Validation(
                "Not enough data for drift detection".to_string()
            ));
        }

        let config = self.get_config(&metric_type).unwrap_or_else(|| {
            DriftDetectionConfig {
                metric_type: metric_type.clone(),
                ..Default::default()
            }
        });

        let baseline_stats = self.calculate_distribution_stats(&baseline);
        let current_stats = self.calculate_distribution_stats(&current);

        let ks_p_value = self.ks_test(&baseline, &current);
        let wasserstein = self.wasserstein_distance(&baseline, &current);

        let drift_score = self.calculate_drift_score(&baseline_stats, &current_stats);
        let drift_detected = drift_score > config.threshold;

        let severity = if !drift_detected {
            DriftSeverity::None
        } else if drift_score < config.threshold * 1.5 {
            DriftSeverity::Low
        } else if drift_score < config.threshold * 2.0 {
            DriftSeverity::Medium
        } else if drift_score < config.threshold * 3.0 {
            DriftSeverity::High
        } else {
            DriftSeverity::Critical
        };

        if drift_detected {
            self.metrics.increment_counter("drift_detected");
        }

        let result = DriftDetectionResult {
            detection_id: format!("drift_{}", crate::utils::id::generate_id()),
            metric_type,
            model_id: model_id.to_string(),
            drift_detected,
            drift_severity: severity,
            drift_score,
            threshold: config.threshold,
            baseline_stats,
            current_stats,
            ks_test_p_value: ks_p_value,
            wasserstein_distance: wasserstein,
            timestamp: chrono::Utc::now(),
        };

        self.detection_history.lock().push(result.clone());
        Ok(result)
    }

    pub fn detect_all_drifts(&self, model_id: &str) -> Result<Vec<DriftDetectionResult>> {
        let configs = self.configs.lock().values().cloned().collect::<Vec<_>>();
        let mut results = Vec::new();

        for config in configs {
            if config.enabled {
                match self.detect_drift(model_id, config.metric_type.clone()) {
                    Ok(result) => results.push(result),
                    Err(_) => continue,
                }
            }
        }

        Ok(results)
    }

    pub fn get_detection_history(
        &self,
        model_id: Option<&str>,
        metric_type: Option<&MetricType>,
        limit: usize,
    ) -> Vec<DriftDetectionResult> {
        let history = self.detection_history.lock();
        let mut results: Vec<DriftDetectionResult> = history.iter()
            .filter(|r| {
                if let Some(m) = model_id {
                    if r.model_id != m {
                        return false;
                    }
                }
                if let Some(mt) = metric_type {
                    if &r.metric_type != mt {
                        return false;
                    }
                }
                true
            })
            .cloned()
            .collect();

        results.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));
        results.into_iter().take(limit).collect()
    }

    pub fn get_active_drifts(&self, model_id: Option<&str>) -> Vec<DriftDetectionResult> {
        let history = self.get_detection_history(model_id, None, 1000);
        history.into_iter()
            .filter(|r| r.drift_detected && r.drift_severity != DriftSeverity::None)
            .collect()
    }

    fn calculate_distribution_stats(&self, values: &[f64]) -> DistributionStats {
        if values.is_empty() {
            return DistributionStats {
                mean: 0.0,
                variance: 0.0,
                std_dev: 0.0,
                skewness: 0.0,
                kurtosis: 0.0,
                min: 0.0,
                max: 0.0,
                median: 0.0,
                percentiles: HashMap::new(),
            };
        }

        let mut sorted = values.to_vec();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap());

        let n = values.len() as f64;
        let mean = values.iter().sum::<f64>() / n;
        
        let variance = if n > 1.0 {
            values.iter().map(|x| (x - mean).powi(2)).sum::<f64>() / (n - 1.0)
        } else {
            0.0
        };
        let std_dev = variance.sqrt();

        let skewness = if std_dev > 0.0 && n > 2.0 {
            let s3 = std_dev.powi(3);
            values.iter().map(|x| ((x - mean) / std_dev).powi(3)).sum::<f64>() * n / ((n - 1.0) * (n - 2.0))
        } else {
            0.0
        };

        let kurtosis = if std_dev > 0.0 && n > 3.0 {
            let s4 = std_dev.powi(4);
            let term1 = n * (n + 1.0) / ((n - 1.0) * (n - 2.0) * (n - 3.0));
            let term2 = values.iter().map(|x| ((x - mean) / std_dev).powi(4)).sum::<f64>();
            let term3 = 3.0 * (n - 1.0).powi(2) / ((n - 2.0) * (n - 3.0));
            term1 * term2 - term3
        } else {
            0.0
        };

        let min = sorted[0];
        let max = sorted[sorted.len() - 1];
        let median = self.percentile(&sorted, 50.0);

        let mut percentiles = HashMap::new();
        for p in [10.0, 25.0, 75.0, 90.0, 95.0, 99.0].iter() {
            percentiles.insert(format!("p{}", p), self.percentile(&sorted, *p));
        }

        DistributionStats {
            mean,
            variance,
            std_dev,
            skewness,
            kurtosis,
            min,
            max,
            median,
            percentiles,
        }
    }

    fn calculate_drift_score(
        &self,
        baseline: &DistributionStats,
        current: &DistributionStats,
    ) -> f64 {
        let mean_diff = (current.mean - baseline.mean).abs() / (baseline.std_dev.max(0.001));
        let var_diff = (current.variance - baseline.variance).abs() / (baseline.variance.max(0.001));
        let median_diff = (current.median - baseline.median).abs() / (baseline.std_dev.max(0.001));

        (mean_diff + var_diff + median_diff) / 3.0
    }

    fn ks_test(&self, sample1: &[f64], sample2: &[f64]) -> f64 {
        if sample1.is_empty() || sample2.is_empty() {
            return 1.0;
        }

        let mut sorted1 = sample1.to_vec();
        let mut sorted2 = sample2.to_vec();
        sorted1.sort_by(|a, b| a.partial_cmp(b).unwrap());
        sorted2.sort_by(|a, b| a.partial_cmp(b).unwrap());

        let n1 = sorted1.len() as f64;
        let n2 = sorted2.len() as f64;

        let mut d = 0.0;
        let (mut i, mut j) = (0, 0);

        while i < sorted1.len() && j < sorted2.len() {
            let f1 = (i as f64) / n1;
            let f2 = (j as f64) / n2;
            d = d.max((f1 - f2).abs());

            if sorted1[i] < sorted2[j] {
                i += 1;
            } else {
                j += 1;
            }
        }

        let en = (n1 * n2 / (n1 + n2)).sqrt();
        let lambda = (en + 0.12 + 0.11 / en) * d;

        let mut p_value = 0.0;
        for j in 1..101 {
            let term = 2.0 * (-2.0 * (j as f64).powi(2) * lambda.powi(2)).exp() * 
                ((-1.0_f64).powi(j as i32 - 1));
            p_value += term;
            if term.abs() < 1e-10 {
                break;
            }
        }

        p_value.max(0.0).min(1.0)
    }

    fn wasserstein_distance(&self, sample1: &[f64], sample2: &[f64]) -> f64 {
        if sample1.is_empty() || sample2.is_empty() {
            return 0.0;
        }

        let mut sorted1 = sample1.to_vec();
        let mut sorted2 = sample2.to_vec();
        sorted1.sort_by(|a, b| a.partial_cmp(b).unwrap());
        sorted2.sort_by(|a, b| a.partial_cmp(b).unwrap());

        let n = std::cmp::min(sorted1.len(), sorted2.len());
        let mut distance = 0.0;

        for i in 0..n {
            distance += (sorted1[i] - sorted2[i]).abs();
        }

        distance / n as f64
    }

    fn percentile(&self, sorted_values: &[f64], percentile: f64) -> f64 {
        if sorted_values.is_empty() {
            return 0.0;
        }
        let index = (percentile / 100.0 * (sorted_values.len() - 1) as f64).round() as usize;
        sorted_values[index]
    }

    pub fn summarize_drifts(&self, model_id: &str) -> HashMap<DriftSeverity, u64> {
        let mut counts = HashMap::new();
        counts.insert(DriftSeverity::None, 0);
        counts.insert(DriftSeverity::Low, 0);
        counts.insert(DriftSeverity::Medium, 0);
        counts.insert(DriftSeverity::High, 0);
        counts.insert(DriftSeverity::Critical, 0);

        let active = self.get_active_drifts(Some(model_id));
        for drift in active {
            *counts.entry(drift.drift_severity).or_insert(0) += 1;
        }

        counts
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::utils::metrics::MetricsCollector;

    #[test]
    fn test_distribution_stats_calculation() {
        let metrics = MetricsCollector::new();
        let service = DriftDetectionService::new(metrics);

        let values: Vec<f64> = (1..=100).map(|x| x as f64).collect();
        let stats = service.calculate_distribution_stats(&values);

        assert_eq!(stats.mean, 50.5);
        assert_eq!(stats.min, 1.0);
        assert_eq!(stats.max, 100.0);
        assert_eq!(stats.median, 50.0);
        assert!(stats.std_dev > 0.0);
    }

    #[test]
    fn test_drift_detection_no_drift() {
        let metrics = MetricsCollector::new();
        let service = DriftDetectionService::new(metrics);

        service.set_config(DriftDetectionConfig {
            metric_type: MetricType::Latency,
            threshold: 0.5,
            ..Default::default()
        });

        let baseline: Vec<f64> = (1..=100).map(|x| x as f64).collect();
        let current: Vec<f64> = (1..=100).map(|x| x as f64 + 5.0).collect();

        service.set_baseline("model_001", &MetricType::Latency, baseline);
        for &v in &current {
            service.record_value("model_001", &MetricType::Latency, v);
        }

        let result = service.detect_drift("model_001", MetricType::Latency).unwrap();
        assert!(!result.drift_detected);
        assert_eq!(result.drift_severity, DriftSeverity::None);
    }

    #[test]
    fn test_drift_detection_with_drift() {
        let metrics = MetricsCollector::new();
        let service = DriftDetectionService::new(metrics);

        service.set_config(DriftDetectionConfig {
            metric_type: MetricType::Latency,
            threshold: 0.1,
            ..Default::default()
        });

        let baseline: Vec<f64> = (1..=100).map(|x| x as f64).collect();
        let current: Vec<f64> = (1..=100).map(|x| x as f64 * 10.0).collect();

        service.set_baseline("model_001", &MetricType::Latency, baseline);
        for &v in &current {
            service.record_value("model_001", &MetricType::Latency, v);
        }

        let result = service.detect_drift("model_001", MetricType::Latency).unwrap();
        assert!(result.drift_detected);
        assert_ne!(result.drift_severity, DriftSeverity::None);
    }

    #[test]
    fn test_ks_test_same_distribution() {
        let metrics = MetricsCollector::new();
        let service = DriftDetectionService::new(metrics);

        let sample1: Vec<f64> = (1..=100).map(|x| x as f64).collect();
        let sample2: Vec<f64> = (1..=100).map(|x| x as f64).collect();

        let p_value = service.ks_test(&sample1, &sample2);
        assert!(p_value > 0.05);
    }

    #[test]
    fn test_ks_test_different_distribution() {
        let metrics = MetricsCollector::new();
        let service = DriftDetectionService::new(metrics);

        let sample1: Vec<f64> = (1..=100).map(|x| x as f64).collect();
        let sample2: Vec<f64> = (1..=100).map(|x| x as f64 * 10.0).collect();

        let p_value = service.ks_test(&sample1, &sample2);
        assert!(p_value < 0.05);
    }

    #[test]
    fn test_wasserstein_distance() {
        let metrics = MetricsCollector::new();
        let service = DriftDetectionService::new(metrics);

        let sample1: Vec<f64> = (1..=10).map(|x| x as f64).collect();
        let sample2: Vec<f64> = (1..=10).map(|x| x as f64 + 5.0).collect();

        let distance = service.wasserstein_distance(&sample1, &sample2);
        assert!((distance - 5.0).abs() < 0.01);
    }

    #[test]
    fn test_get_active_drifts() {
        let metrics = MetricsCollector::new();
        let service = DriftDetectionService::new(metrics);

        service.set_config(DriftDetectionConfig {
            metric_type: MetricType::Latency,
            threshold: 0.1,
            ..Default::default()
        });

        let baseline: Vec<f64> = (1..=100).map(|x| x as f64).collect();
        let current: Vec<f64> = (1..=100).map(|x| x as f64 * 10.0).collect();

        service.set_baseline("model_001", &MetricType::Latency, baseline);
        for &v in &current {
            service.record_value("model_001", &MetricType::Latency, v);
        }

        service.detect_drift("model_001", MetricType::Latency).unwrap();

        let active = service.get_active_drifts(Some("model_001"));
        assert!(!active.is_empty());
    }

    #[test]
    fn test_detection_history() {
        let metrics = MetricsCollector::new();
        let service = DriftDetectionService::new(metrics);

        service.set_config(DriftDetectionConfig {
            metric_type: MetricType::Latency,
            threshold: 0.5,
            ..Default::default()
        });

        let baseline: Vec<f64> = (1..=100).map(|x| x as f64).collect();
        service.set_baseline("model_001", &MetricType::Latency, baseline);

        for i in 0..5 {
            let current: Vec<f64> = (1..=100).map(|x| x as f64 + i as f64).collect();
            for &v in &current {
                service.record_value("model_001", &MetricType::Latency, v);
            }
            service.detect_drift("model_001", MetricType::Latency).unwrap();
        }

        let history = service.get_detection_history(Some("model_001"), None, 10);
        assert_eq!(history.len(), 5);
    }

    #[test]
    fn test_summarize_drifts() {
        let metrics = MetricsCollector::new();
        let service = DriftDetectionService::new(metrics);

        service.set_config(DriftDetectionConfig {
            metric_type: MetricType::Latency,
            threshold: 0.1,
            ..Default::default()
        });

        let baseline: Vec<f64> = (1..=100).map(|x| x as f64).collect();
        let current: Vec<f64> = (1..=100).map(|x| x as f64 * 10.0).collect();

        service.set_baseline("model_001", &MetricType::Latency, baseline);
        for &v in &current {
            service.record_value("model_001", &MetricType::Latency, v);
        }

        service.detect_drift("model_001", MetricType::Latency).unwrap();

        let summary = service.summarize_drifts("model_001");
        assert!(summary.values().sum::<u64>() > 0);
    }
}
