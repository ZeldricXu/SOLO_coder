use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use uuid::Uuid;
use std::collections::HashMap;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MetricType {
    Accuracy,
    Precision,
    Recall,
    F1Score,
    AUC,
    MSE,
    MAE,
    RMSE,
    R2Score,
    LatencyP50,
    LatencyP95,
    LatencyP99,
    Throughput,
    ErrorRate,
    SafetyScore,
    HallucinationRate,
    RelevanceScore,
    CoherenceScore,
    Custom(String),
}

impl MetricType {
    pub fn to_str(&self) -> &str {
        match self {
            Self::Accuracy => "accuracy",
            Self::Precision => "precision",
            Self::Recall => "recall",
            Self::F1Score => "f1_score",
            Self::AUC => "auc",
            Self::MSE => "mse",
            Self::MAE => "mae",
            Self::RMSE => "rmse",
            Self::R2Score => "r2_score",
            Self::LatencyP50 => "latency_p50",
            Self::LatencyP95 => "latency_p95",
            Self::LatencyP99 => "latency_p99",
            Self::Throughput => "throughput",
            Self::ErrorRate => "error_rate",
            Self::SafetyScore => "safety_score",
            Self::HallucinationRate => "hallucination_rate",
            Self::RelevanceScore => "relevance_score",
            Self::CoherenceScore => "coherence_score",
            Self::Custom(s) => s,
        }
    }

    pub fn is_higher_better(&self) -> bool {
        matches!(self, 
            Self::Accuracy | Self::Precision | Self::Recall | Self::F1Score | 
            Self::AUC | Self::R2Score | Self::Throughput | Self::SafetyScore |
            Self::RelevanceScore | Self::CoherenceScore
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricValue {
    pub metric_type: MetricType,
    pub value: f64,
    pub confidence_interval: Option<(f64, f64)>,
    pub sample_size: Option<u64>,
}

impl MetricValue {
    pub fn new(metric_type: MetricType, value: f64) -> Self {
        Self {
            metric_type,
            value,
            confidence_interval: None,
            sample_size: None,
        }
    }

    pub fn with_confidence(mut self, lower: f64, upper: f64) -> Self {
        self.confidence_interval = Some((lower, upper));
        self
    }

    pub fn with_sample_size(mut self, size: u64) -> Self {
        self.sample_size = Some(size);
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataPoint {
    pub timestamp: DateTime<Utc>,
    pub value: f64,
    pub labels: HashMap<String, String>,
}

impl DataPoint {
    pub fn new(timestamp: DateTime<Utc>, value: f64) -> Self {
        Self {
            timestamp,
            value,
            labels: HashMap::new(),
        }
    }

    pub fn with_label(mut self, key: &str, value: &str) -> Self {
        self.labels.insert(key.to_string(), value.to_string());
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TimeSeries {
    pub metric: MetricType,
    pub model_id: String,
    pub points: Vec<DataPoint>,
    pub start_time: DateTime<Utc>,
    pub end_time: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OfflineEvaluation {
    pub evaluation_id: String,
    pub model_id: String,
    pub model_version: u32,
    pub dataset_id: String,
    pub metrics: HashMap<String, MetricValue>,
    pub created_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub status: EvaluationStatus,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EvaluationStatus {
    Pending,
    Running,
    Completed,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OnlineMonitoringConfig {
    pub model_id: String,
    pub metrics: Vec<MetricType>,
    pub collection_interval_sec: u64,
    pub window_size_minutes: u64,
    pub alert_thresholds: HashMap<String, AlertThreshold>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AlertThreshold {
    pub metric: MetricType,
    pub warning_threshold: f64,
    pub critical_threshold: f64,
    pub is_relative: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Alert {
    pub alert_id: String,
    pub model_id: String,
    pub metric: MetricType,
    pub severity: AlertSeverity,
    pub current_value: f64,
    pub threshold: f64,
    pub triggered_at: DateTime<Utc>,
    pub resolved_at: Option<DateTime<Utc>>,
    pub description: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AlertSeverity {
    Info,
    Warning,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DriftDetectionResult {
    pub drift_id: String,
    pub model_id: String,
    pub metric: MetricType,
    pub drift_type: DriftType,
    pub drift_score: f64,
    pub p_value: f64,
    pub is_significant: bool,
    pub reference_distribution: DistributionStats,
    pub current_distribution: DistributionStats,
    pub detected_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DriftType {
    DataDrift,
    ConceptDrift,
    PredictionDrift,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DistributionStats {
    pub mean: f64,
    pub median: f64,
    pub std_dev: f64,
    pub min: f64,
    pub max: f64,
    pub percentiles: HashMap<u32, f64>,
}

impl DistributionStats {
    pub fn from_values(values: &[f64]) -> Option<Self> {
        if values.is_empty() {
            return None;
        }
        
        let mut sorted = values.to_vec();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        
        let n = values.len() as f64;
        let mean: f64 = values.iter().sum::<f64>() / n;
        
        let variance: f64 = values.iter()
            .map(|v| (v - mean).powi(2))
            .sum::<f64>() / n;
        let std_dev = variance.sqrt();
        
        let median = if sorted.len() % 2 == 0 {
            let mid = sorted.len() / 2;
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[sorted.len() / 2]
        };
        
        let mut percentiles = HashMap::new();
        for p in [25u32, 50, 75, 90, 95, 99] {
            let idx = ((p as f64 / 100.0) * (sorted.len() as f64 - 1.0)) as usize;
            percentiles.insert(p, sorted[idx]);
        }
        
        Some(Self {
            mean,
            median,
            std_dev,
            min: *sorted.first().unwrap(),
            max: *sorted.last().unwrap(),
            percentiles,
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelComparison {
    pub comparison_id: String,
    pub model_ids: Vec<String>,
    pub base_model_id: String,
    pub metrics: Vec<MetricType>,
    pub results: HashMap<String, HashMap<String, f64>>,
    pub winners: HashMap<String, String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EvaluationSummary {
    pub model_id: String,
    pub latest_offline_evaluation: Option<OfflineEvaluation>,
    pub online_metrics: HashMap<String, f64>,
    pub active_alerts: Vec<Alert>,
    pub recent_drifts: Vec<DriftDetectionResult>,
    pub overall_health_score: f64,
}

pub fn calculate_health_score(
    metrics: &[MetricValue],
    alerts: &[Alert],
    drifts: &[DriftDetectionResult],
) -> f64 {
    let mut score = 100.0;
    
    let critical_alerts = alerts.iter().filter(|a| a.severity == AlertSeverity::Critical).count();
    let warning_alerts = alerts.iter().filter(|a| a.severity == AlertSeverity::Warning).count();
    
    score -= (critical_alerts as f64) * 20.0;
    score -= (warning_alerts as f64) * 10.0;
    
    let significant_drifts = drifts.iter().filter(|d| d.is_significant).count();
    score -= (significant_drifts as f64) * 15.0;
    
    for metric in metrics {
        if metric.metric_type.is_higher_better() {
            if metric.value < 0.5 {
                score -= 10.0;
            }
        } else {
            if metric.value > 0.5 {
                score -= 10.0;
            }
        }
    }
    
    score.max(0.0)
}

pub fn ks_test(reference: &[f64], current: &[f64]) -> (f64, f64) {
    if reference.is_empty() || current.is_empty() {
        return (0.0, 1.0);
    }
    
    let mut ref_sorted = reference.to_vec();
    let mut cur_sorted = current.to_vec();
    ref_sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    cur_sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    
    let n = ref_sorted.len() as f64;
    let m = cur_sorted.len() as f64;
    
    let mut max_diff = 0.0;
    let mut i = 0;
    let mut j = 0;
    
    while i < ref_sorted.len() && j < cur_sorted.len() {
        let ref_cdf = (i as f64 + 1.0) / n;
        let cur_cdf = (j as f64 + 1.0) / m;
        let diff = (ref_cdf - cur_cdf).abs();
        
        if diff > max_diff {
            max_diff = diff;
        }
        
        if ref_sorted[i] <= cur_sorted[j] {
            i += 1;
        } else {
            j += 1;
        }
    }
    
    let en = ((n * m) / (n + m)).sqrt();
    let lambda = (en + 0.12 + 0.11 / en) * max_diff;
    
    let p_value = if lambda <= 0.0 {
        1.0
    } else {
        let mut sum = 0.0;
        for k in 1..100 {
            let term = 2.0 * (-1.0f64).powi(k - 1) * (-2.0 * lambda.powi(2) * k as f64 * k as f64).exp();
            sum += term;
            if term.abs() < 1e-10 {
                break;
            }
        }
        sum
    };
    
    (max_diff, p_value.max(0.0).min(1.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_metric_type_higher_better() {
        assert!(MetricType::Accuracy.is_higher_better());
        assert!(MetricType::F1Score.is_higher_better());
        assert!(!MetricType::MSE.is_higher_better());
        assert!(!MetricType::ErrorRate.is_higher_better());
    }

    #[test]
    fn test_distribution_stats() {
        let values = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let stats = DistributionStats::from_values(&values).unwrap();
        
        assert!((stats.mean - 3.0).abs() < 0.001);
        assert!((stats.median - 3.0).abs() < 0.001);
        assert_eq!(stats.min, 1.0);
        assert_eq!(stats.max, 5.0);
        assert!(stats.std_dev > 0.0);
        assert!(stats.percentiles.contains_key(&25));
        assert!(stats.percentiles.contains_key(&99));
    }

    #[test]
    fn test_distribution_stats_empty() {
        let stats = DistributionStats::from_values(&[]);
        assert!(stats.is_none());
    }

    #[test]
    fn test_ks_test_same_distribution() {
        let reference: Vec<f64> = (0..100).map(|i| i as f64 / 100.0).collect();
        let current: Vec<f64> = (0..100).map(|i| i as f64 / 100.0).collect();
        
        let (stat, p_value) = ks_test(&reference, &current);
        assert!(stat < 0.1);
        assert!(p_value > 0.05);
    }

    #[test]
    fn test_ks_test_different_distribution() {
        let reference: Vec<f64> = (0..100).map(|i| i as f64 / 100.0).collect();
        let current: Vec<f64> = (0..100).map(|i| (i as f64 / 100.0) + 0.5).collect();
        
        let (stat, p_value) = ks_test(&reference, &current);
        assert!(stat > 0.4);
        assert!(p_value < 0.05);
    }

    #[test]
    fn test_calculate_health_score() {
        let metrics = vec![
            MetricValue::new(MetricType::Accuracy, 0.95),
            MetricValue::new(MetricType::ErrorRate, 0.05),
        ];
        
        let alerts = vec![
            Alert {
                alert_id: Uuid::new_v4().to_string(),
                model_id: "model-1".to_string(),
                metric: MetricType::ErrorRate,
                severity: AlertSeverity::Warning,
                current_value: 0.1,
                threshold: 0.05,
                triggered_at: Utc::now(),
                resolved_at: None,
                description: "High error rate".to_string(),
            }
        ];
        
        let drifts = Vec::new();
        
        let score = calculate_health_score(&metrics, &alerts, &drifts);
        assert!(score > 70.0);
        assert!(score < 100.0);
    }

    #[test]
    fn test_metric_value_creation() {
        let metric = MetricValue::new(MetricType::Accuracy, 0.95)
            .with_confidence(0.92, 0.98)
            .with_sample_size(1000);
        
        assert_eq!(metric.value, 0.95);
        assert_eq!(metric.sample_size, Some(1000));
        assert!(metric.confidence_interval.is_some());
    }

    #[test]
    fn test_data_point_creation() {
        let now = Utc::now();
        let point = DataPoint::new(now, 0.95)
            .with_label("model", "gpt-4")
            .with_label("environment", "production");
        
        assert_eq!(point.value, 0.95);
        assert_eq!(point.labels.get("model").unwrap(), "gpt-4");
        assert_eq!(point.labels.get("environment").unwrap(), "production");
    }
}
