use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum MetricType {
    Accuracy,
    Precision,
    Recall,
    F1Score,
    Bleu,
    Rouge,
    Perplexity,
    Latency,
    Throughput,
    ErrorRate,
    TokenCount,
    Cost,
    Custom(String),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricValue {
    pub metric_type: MetricType,
    pub value: f64,
    pub timestamp: chrono::DateTime<chrono::Utc>,
    pub model_id: String,
    pub tags: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricThreshold {
    pub metric_type: MetricType,
    pub warning_threshold: f64,
    pub critical_threshold: f64,
    pub is_higher_better: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricComparison {
    pub metric_type: MetricType,
    pub baseline_value: f64,
    pub current_value: f64,
    pub delta: f64,
    pub percentage_change: f64,
    pub is_significant: bool,
}

impl MetricValue {
    pub fn new(metric_type: MetricType, value: f64, model_id: String) -> Self {
        Self {
            metric_type,
            value,
            timestamp: chrono::Utc::now(),
            model_id,
            tags: HashMap::new(),
        }
    }

    pub fn with_tag(mut self, key: String, value: String) -> Self {
        self.tags.insert(key, value);
        self
    }
}

impl MetricComparison {
    pub fn calculate(
        metric_type: MetricType,
        baseline: f64,
        current: f64,
        significance_threshold: f64,
    ) -> Self {
        let delta = current - baseline;
        let percentage_change = if baseline != 0.0 {
            (delta / baseline) * 100.0
        } else {
            0.0
        };
        let is_significant = percentage_change.abs() >= significance_threshold;

        Self {
            metric_type,
            baseline_value: baseline,
            current_value: current,
            delta,
            percentage_change,
            is_significant,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_metric_value_creation() {
        let metric = MetricValue::new(MetricType::Accuracy, 0.95, "model-001".to_string());
        assert_eq!(metric.value, 0.95);
        assert_eq!(metric.model_id, "model-001");
    }

    #[test]
    fn test_metric_value_with_tags() {
        let metric = MetricValue::new(MetricType::Latency, 150.0, "model-001".to_string())
            .with_tag("environment".to_string(), "production".to_string())
            .with_tag("region".to_string(), "cn-east".to_string());
        
        assert_eq!(metric.tags.len(), 2);
        assert_eq!(metric.tags.get("environment"), Some(&"production".to_string()));
    }

    #[test]
    fn test_metric_comparison_calculation() {
        let comparison = MetricComparison::calculate(
            MetricType::Accuracy,
            0.90,
            0.95,
            5.0,
        );

        assert_eq!(comparison.baseline_value, 0.90);
        assert_eq!(comparison.current_value, 0.95);
        assert_eq!(comparison.delta, 0.05);
        assert!((comparison.percentage_change - 5.555).abs() < 0.01);
        assert!(comparison.is_significant);
    }

    #[test]
    fn test_metric_comparison_not_significant() {
        let comparison = MetricComparison::calculate(
            MetricType::Accuracy,
            0.90,
            0.91,
            5.0,
        );

        assert!(!comparison.is_significant);
    }

    #[test]
    fn test_metric_comparison_zero_baseline() {
        let comparison = MetricComparison::calculate(
            MetricType::ErrorRate,
            0.0,
            0.01,
            1.0,
        );

        assert_eq!(comparison.percentage_change, 0.0);
    }
}
