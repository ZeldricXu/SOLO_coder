use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use chrono::{DateTime, Utc};
use crate::utils::error::AppError;
use crate::utils::id::generate_id;
use statrs::distribution::{Normal, ContinuousCDF};
use crate::prompt_experiments::ab_test::{ABTest, Variant};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EvaluationResult {
    pub variant_id: String,
    pub variant_name: String,
    pub metric_name: String,
    pub sample_size: u64,
    pub mean: f64,
    pub std_dev: f64,
    pub standard_error: f64,
    pub confidence_interval: (f64, f64),
    pub observations: Vec<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExperimentEvaluation {
    pub evaluation_id: String,
    pub test_id: String,
    pub timestamp: DateTime<Utc>,
    pub results: HashMap<String, EvaluationResult>,
    pub primary_metric: String,
    pub confidence_level: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VariantComparison {
    pub variant_id: String,
    pub variant_name: String,
    pub is_control: bool,
    pub mean: f64,
    pub difference: f64,
    pub relative_difference: f64,
    pub p_value: f64,
    pub is_statistically_significant: bool,
    pub confidence_interval: (f64, f64),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ComparisonReport {
    pub report_id: String,
    pub test_id: String,
    pub test_name: String,
    pub timestamp: DateTime<Utc>,
    pub primary_metric: String,
    pub control_variant: VariantComparison,
    pub treatment_variants: Vec<VariantComparison>,
    pub winner: Option<String>,
    pub observations: Vec<String>,
    pub recommendations: Vec<String>,
}

impl EvaluationResult {
    pub fn new(
        variant: &Variant,
        metric_name: String,
        observations: Vec<f64>,
        confidence_level: f64,
    ) -> Result<Self, AppError> {
        if observations.is_empty() {
            return Err(AppError::Validation("No observations provided".to_string()));
        }

        let n = observations.len() as f64;
        let sum: f64 = observations.iter().sum();
        let mean = sum / n;

        let variance: f64 = observations
            .iter()
            .map(|x| (x - mean).powi(2))
            .sum::<f64>() / (n - 1.0).max(1.0);
        let std_dev = variance.sqrt();
        let standard_error = std_dev / n.sqrt();

        let z_score = Normal::new(0.0, 1.0)
            .map_err(|e| AppError::Internal(format!("Failed to create normal distribution: {}", e)))?
            .inverse_cdf(1.0 - (1.0 - confidence_level) / 2.0);

        let margin_of_error = z_score * standard_error;
        let confidence_interval = (mean - margin_of_error, mean + margin_of_error);

        Ok(Self {
            variant_id: variant.variant_id.clone(),
            variant_name: variant.name.clone(),
            metric_name,
            sample_size: observations.len() as u64,
            mean,
            std_dev,
            standard_error,
            confidence_interval,
            observations,
        })
    }
}

impl ExperimentEvaluation {
    pub fn new(
        test: &ABTest,
        metric_data: &HashMap<String, HashMap<String, Vec<f64>>>,
    ) -> Result<Self, AppError> {
        let mut results = HashMap::new();

        for variant in &test.variants {
            let variant_metrics = metric_data.get(&variant.variant_id)
                .ok_or_else(|| AppError::NotFound(format!(
                    "No metric data for variant {}",
                    variant.variant_id
                )))?;

            let metric_result = variant_metrics.get(&test.primary_metric)
                .ok_or_else(|| AppError::NotFound(format!(
                    "No data for primary metric {} in variant {}",
                    test.primary_metric, variant.variant_id
                )))?;

            let result = EvaluationResult::new(
                variant,
                test.primary_metric.clone(),
                metric_result.clone(),
                test.confidence_level,
            )?;

            results.insert(variant.variant_id.clone(), result);
        }

        Ok(Self {
            evaluation_id: generate_id("eval"),
            test_id: test.test_id.clone(),
            timestamp: Utc::now(),
            results,
            primary_metric: test.primary_metric.clone(),
            confidence_level: test.confidence_level,
        })
    }

    pub fn get_result(&self, variant_id: &str) -> Option<&EvaluationResult> {
        self.results.get(variant_id)
    }
}

fn perform_t_test(
    control: &EvaluationResult,
    treatment: &EvaluationResult,
) -> Result<(f64, bool), AppError> {
    let n1 = control.sample_size as f64;
    let n2 = treatment.sample_size as f64;
    let mean1 = control.mean;
    let mean2 = treatment.mean;
    let var1 = control.std_dev.powi(2);
    let var2 = treatment.std_dev.powi(2);

    let pooled_se = (var1 / n1 + var2 / n2).sqrt();
    let t_stat = (mean2 - mean1) / pooled_se;

    let df = (var1 / n1 + var2 / n2).powi(2)
        / ((var1 / n1).powi(2) / (n1 - 1.0) + (var2 / n2).powi(2) / (n2 - 1.0));

    let normal = Normal::new(0.0, 1.0)
        .map_err(|e| AppError::Internal(format!("Failed to create normal distribution: {}", e)))?;
    
    let p_value = 2.0 * (1.0 - normal.cdf(t_stat.abs()));

    let alpha = 0.05;
    let is_significant = p_value < alpha;

    Ok((p_value, is_significant))
}

impl ComparisonReport {
    pub fn generate(
        test: &ABTest,
        evaluation: &ExperimentEvaluation,
    ) -> Result<Self, AppError> {
        let control_variant = test.get_control_variant()
            .ok_or_else(|| AppError::NotFound("Control variant not found".to_string()))?;
        
        let control_result = evaluation.get_result(&control_variant.variant_id)
            .ok_or_else(|| AppError::NotFound("Control result not found".to_string()))?;

        let control_comparison = VariantComparison {
            variant_id: control_variant.variant_id.clone(),
            variant_name: control_variant.name.clone(),
            is_control: true,
            mean: control_result.mean,
            difference: 0.0,
            relative_difference: 0.0,
            p_value: 1.0,
            is_statistically_significant: false,
            confidence_interval: control_result.confidence_interval,
        };

        let mut treatment_variants = Vec::new();
        let mut best_variant: Option<(String, f64)> = None;

        for variant in &test.variants {
            if variant.is_control {
                continue;
            }

            let treatment_result = evaluation.get_result(&variant.variant_id)
                .ok_or_else(|| AppError::NotFound(format!(
                    "Result not found for variant {}",
                    variant.variant_id
                )))?;

            let difference = treatment_result.mean - control_result.mean;
            let relative_difference = if control_result.mean.abs() > 1e-10 {
                difference / control_result.mean * 100.0
            } else {
                0.0
            };

            let (p_value, is_significant) = perform_t_test(control_result, treatment_result)?;

            let comparison = VariantComparison {
                variant_id: variant.variant_id.clone(),
                variant_name: variant.name.clone(),
                is_control: false,
                mean: treatment_result.mean,
                difference,
                relative_difference,
                p_value,
                is_statistically_significant: is_significant,
                confidence_interval: treatment_result.confidence_interval,
            };

            if is_significant {
                match &best_variant {
                    None => {
                        best_variant = Some((variant.variant_id.clone(), treatment_result.mean));
                    }
                    Some((_, best_mean)) => {
                        if treatment_result.mean > *best_mean {
                            best_variant = Some((variant.variant_id.clone(), treatment_result.mean));
                        }
                    }
                }
            }

            treatment_variants.push(comparison);
        }

        let winner = best_variant.map(|(id, _)| id);

        let mut observations = Vec::new();
        let mut recommendations = Vec::new();

        if test.sample_count < test.min_sample_size {
            observations.push(format!(
                "Sample size ({}) is below minimum required ({}). Results may not be reliable.",
                test.sample_count, test.min_sample_size
            ));
            recommendations.push("Continue collecting more samples before making decisions.".to_string());
        }

        for tv in &treatment_variants {
            if tv.is_statistically_significant {
                observations.push(format!(
                    "Variant '{}' shows statistically significant improvement of {:.2}% (p={:.4})",
                    tv.variant_name, tv.relative_difference, tv.p_value
                ));
            } else {
                observations.push(format!(
                    "Variant '{}' does not show statistically significant improvement (p={:.4})",
                    tv.variant_name, tv.p_value
                ));
            }
        }

        if let Some(winner_id) = &winner {
            let winner_name = test.get_variant(winner_id).map(|v| v.name.clone())
                .unwrap_or_else(|| winner_id.clone());
            recommendations.push(format!(
                "Consider deploying variant '{}' as it shows the best statistically significant performance.",
                winner_name
            ));
        } else {
            recommendations.push("No variant shows statistically significant improvement. Consider revising the prompt or extending the experiment.".to_string());
        }

        Ok(Self {
            report_id: generate_id("rpt"),
            test_id: test.test_id.clone(),
            test_name: test.name.clone(),
            timestamp: Utc::now(),
            primary_metric: test.primary_metric.clone(),
            control_variant: control_comparison,
            treatment_variants,
            winner,
            observations,
            recommendations,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::prompt_experiments::ab_test::{VariantConfig, ABTestCreationRequest, TrafficAllocation, TrafficAllocationStrategy};
    use std::collections::HashMap;

    fn create_test_ab_test() -> ABTest {
        let variant1 = VariantConfig {
            name: "Control".to_string(),
            prompt_version_id: "ver_1".to_string(),
            description: "Original".to_string(),
            traffic_weight: 0.5,
            is_control: true,
            metadata: HashMap::new(),
        };

        let variant2 = VariantConfig {
            name: "Treatment".to_string(),
            prompt_version_id: "ver_2".to_string(),
            description: "Improved".to_string(),
            traffic_weight: 0.5,
            is_control: false,
            metadata: HashMap::new(),
        };

        let request = ABTestCreationRequest {
            name: "Test".to_string(),
            description: "Test".to_string(),
            prompt_id: "prompt_1".to_string(),
            variants: vec![variant1, variant2],
            traffic_allocation: TrafficAllocation {
                strategy: TrafficAllocationStrategy::Uniform,
                weights: HashMap::new(),
                seed: None,
            },
            primary_metric: "accuracy".to_string(),
            secondary_metrics: vec![],
            start_time: None,
            end_time: None,
            min_sample_size: 100,
            confidence_level: 0.95,
            created_by: "test".to_string(),
        };

        let mut test = ABTest::new(request).unwrap();
        test.start().unwrap();
        for _ in 0..150 {
            test.assign_variant(Some("user"), None).unwrap();
        }
        test
    }

    #[test]
    fn test_evaluation_result_creation() {
        let variant = Variant::from_config(VariantConfig {
            name: "Control".to_string(),
            prompt_version_id: "ver_1".to_string(),
            description: "Test".to_string(),
            traffic_weight: 0.5,
            is_control: true,
            metadata: HashMap::new(),
        });

        let observations: Vec<f64> = (0..100).map(|i| 0.7 + (i as f64) * 0.001).collect();
        let result = EvaluationResult::new(&variant, "accuracy".to_string(), observations, 0.95).unwrap();

        assert_eq!(result.sample_size, 100);
        assert!(result.mean > 0.0 && result.mean < 1.0);
        assert!(result.confidence_interval.0 <= result.mean);
        assert!(result.confidence_interval.1 >= result.mean);
    }

    #[test]
    fn test_experiment_evaluation() {
        let test = create_test_ab_test();
        
        let mut metric_data = HashMap::new();
        for variant in &test.variants {
            let mut metrics = HashMap::new();
            let observations: Vec<f64> = if variant.is_control {
                (0..100).map(|i| 0.7 + (i as f64 % 10) * 0.01).collect()
            } else {
                (0..100).map(|i| 0.85 + (i as f64 % 10) * 0.01).collect()
            };
            metrics.insert("accuracy".to_string(), observations);
            metric_data.insert(variant.variant_id.clone(), metrics);
        }

        let evaluation = ExperimentEvaluation::new(&test, &metric_data).unwrap();
        
        assert!(evaluation.evaluation_id.starts_with("eval_"));
        assert_eq!(evaluation.results.len(), 2);
    }

    #[test]
    fn test_comparison_report_generation() {
        let test = create_test_ab_test();
        
        let mut metric_data = HashMap::new();
        for variant in &test.variants {
            let mut metrics = HashMap::new();
            let observations: Vec<f64> = if variant.is_control {
                (0..100).map(|_| 0.7 + rand::random::<f64>() * 0.1).collect()
            } else {
                (0..100).map(|_| 0.85 + rand::random::<f64>() * 0.05).collect()
            };
            metrics.insert("accuracy".to_string(), observations);
            metric_data.insert(variant.variant_id.clone(), metrics);
        }

        let evaluation = ExperimentEvaluation::new(&test, &metric_data).unwrap();
        let report = ComparisonReport::generate(&test, &evaluation).unwrap();

        assert!(report.report_id.starts_with("rpt_"));
        assert_eq!(report.control_variant.variant_name, "Control");
        assert_eq!(report.treatment_variants.len(), 1);
        assert!(!report.recommendations.is_empty());
    }
}
