use anyhow::Result;
use async_trait::async_trait;
use std::collections::HashMap;
use tracing::debug;

use common::alert::{Alert, AlertSeverity, DetectionMethod};
use common::metrics::Labels;

use crate::detectors::{DetectionResult, Detector};

pub struct DetectionChain {
    name: String,
    detectors: Vec<Box<dyn Detector>>,
    labels: Labels,
    min_score: f64,
    strategy: DetectionStrategy,
}

#[derive(Debug, Clone, Copy)]
pub enum DetectionStrategy {
    Any,
    All,
    Majority,
    Weighted,
}

impl DetectionChain {
    pub fn new(name: String, strategy: DetectionStrategy) -> Self {
        Self {
            name,
            detectors: Vec::new(),
            labels: Labels::new(),
            min_score: 0.5,
            strategy,
        }
    }

    pub fn add_detector<D: Detector + 'static>(&mut self, detector: D) {
        self.detectors.push(Box::new(detector));
    }

    pub fn add_boxed_detector(&mut self, detector: Box<dyn Detector>) {
        self.detectors.push(detector);
    }

    pub fn with_labels(mut self, labels: Labels) -> Self {
        self.labels = labels;
        self
    }

    pub fn with_min_score(mut self, min_score: f64) -> Self {
        self.min_score = min_score;
        self
    }

    pub fn name(&self) -> &str {
        &self.name
    }

    fn combine_results(
        &self,
        results: &[(String, DetectionResult, DetectionMethod)],
    ) -> (bool, f64, String, AlertSeverity) {
        let anomaly_results: Vec<_> = results
            .iter()
            .filter(|(_, r, _)| r.is_anomaly)
            .collect();

        if anomaly_results.is_empty() {
            return (false, 0.0, "No anomalies detected".to_string(), AlertSeverity::Info);
        }

        match self.strategy {
            DetectionStrategy::Any => {
                let max_score = anomaly_results
                    .iter()
                    .map(|(_, r, _)| r.score)
                    .fold(0.0, f64::max);
                let details = format!(
                    "Anomaly detected by {} of {} detectors",
                    anomaly_results.len(),
                    results.len()
                );
                let severity = anomaly_results
                    .iter()
                    .map(|(_, r, _)| r.suggested_severity.clone())
                    .max_by_key(|s| s.order())
                    .unwrap_or(AlertSeverity::Warning);
                (true, max_score, details, severity)
            }
            DetectionStrategy::All => {
                if anomaly_results.len() == results.len() {
                    let avg_score: f64 = anomaly_results.iter().map(|(_, r, _)| r.score).sum::<f64>()
                        / anomaly_results.len() as f64;
                    (true, avg_score, "All detectors triggered".to_string(), AlertSeverity::Critical)
                } else {
                    (false, 0.0, "Not all detectors triggered".to_string(), AlertSeverity::Info)
                }
            }
            DetectionStrategy::Majority => {
                if anomaly_results.len() > results.len() / 2 {
                    let avg_score: f64 = anomaly_results.iter().map(|(_, r, _)| r.score).sum::<f64>()
                        / anomaly_results.len() as f64;
                    (
                        true,
                        avg_score,
                        format!("Majority ({}/{}) triggered", anomaly_results.len(), results.len()),
                        AlertSeverity::Error,
                    )
                } else {
                    (false, 0.0, "No majority".to_string(), AlertSeverity::Info)
                }
            }
            DetectionStrategy::Weighted => {
                let total_score: f64 = anomaly_results.iter().map(|(_, r, _)| r.score).sum();
                let avg_score = total_score / results.len() as f64;
                if avg_score >= self.min_score {
                    (
                        true,
                        avg_score,
                        format!("Weighted score: {:.2}", avg_score),
                        AlertSeverity::Warning,
                    )
                } else {
                    (false, avg_score, "Score below threshold".to_string(), AlertSeverity::Info)
                }
            }
        }
    }

    pub async fn run(&mut self, series: &[common::metrics::TimeSeries]) -> Result<Option<Alert>> {
        let mut results = Vec::new();

        for detector in &mut self.detectors {
            let result = detector.detect(series).await?;
            debug!(
                "Detector '{}' result: is_anomaly={}, score={}",
                detector.name(),
                result.is_anomaly,
                result.score
            );
            results.push((detector.name().to_string(), result, detector.detection_method()));
        }

        let (is_anomaly, combined_score, details, severity) = self.combine_results(&results);

        if is_anomaly && combined_score >= self.min_score {
            let mut labels = self.labels.clone();
            if let Some(first_series) = series.first() {
                labels.add("metric_name".to_string(), first_series.metric_name.clone());
            }

            let first_method = results.first().map(|r| r.2.clone()).unwrap_or(DetectionMethod::StaticThreshold);

            let mut alert = Alert::new(
                self.name.clone(),
                severity,
                labels,
                first_method,
                combined_score,
            );

            alert = alert.with_annotation("details".to_string(), details);

            for (name, result, _) in &results {
                if result.is_anomaly {
                    alert = alert.with_annotation(
                        format!("detector_{}", name),
                        format!("score={:.2}, {}", result.score, result.details),
                    );
                }
            }

            Ok(Some(alert))
        } else {
            Ok(None)
        }
    }
}

#[async_trait]
impl Detector for DetectionChain {
    fn name(&self) -> &str {
        &self.name
    }

    async fn detect(&mut self, series_list: &[common::metrics::TimeSeries]) -> Result<DetectionResult> {
        let mut results = Vec::new();
        let mut all_affected = Vec::new();

        for detector in &mut self.detectors {
            let result = detector.detect(series_list).await?;
            debug!(
                "Chain detector '{}' - child '{}' result: is_anomaly={}, score={}",
                self.name,
                detector.name(),
                result.is_anomaly,
                result.score
            );
            all_affected.extend(result.affected_metrics.clone());
            results.push((detector.name().to_string(), result, detector.detection_method()));
        }

        all_affected.dedup();

        let (is_anomaly, combined_score, details, severity) = self.combine_results(&results);

        if is_anomaly && combined_score >= self.min_score {
            Ok(DetectionResult::anomaly(
                combined_score,
                details,
                severity,
            )
            .with_metrics(all_affected))
        } else {
            Ok(DetectionResult::normal())
        }
    }

    fn detection_method(&self) -> DetectionMethod {
        DetectionMethod::ChainComposite
    }
}

pub struct ChainManager {
    chains: HashMap<String, DetectionChain>,
}

impl ChainManager {
    pub fn new() -> Self {
        Self {
            chains: HashMap::new(),
        }
    }

    pub fn add_chain(&mut self, chain: DetectionChain) {
        self.chains.insert(chain.name.clone(), chain);
    }

    pub fn get_chain(&mut self, name: &str) -> Option<&mut DetectionChain> {
        self.chains.get_mut(name)
    }

    pub async fn run_all(
        &mut self,
        series: &[common::metrics::TimeSeries],
    ) -> Result<Vec<Alert>> {
        let mut alerts = Vec::new();

        for chain in self.chains.values_mut() {
            if let Some(alert) = chain.run(series).await? {
                alerts.push(alert);
            }
        }

        Ok(alerts)
    }
}

impl Default for ChainManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::detectors::{StaticThresholdDetector, ThresholdDirection};
    use common::metrics::TimeSeries;

    #[tokio::test]
    async fn test_detection_chain_any() {
        let mut chain = DetectionChain::new("test-chain".to_string(), DetectionStrategy::Any);

        let detector1 = StaticThresholdDetector::new(
            "threshold1".to_string(),
            100.0,
            ThresholdDirection::Above,
            AlertSeverity::Warning,
        );
        chain.add_detector(detector1);

        let mut series = TimeSeries::new("test".to_string(), Labels::new());
        series.add_point(chrono::Utc::now(), 150.0);

        let result = chain.run(&[series]).await.unwrap();
        assert!(result.is_some());
        assert_eq!(result.unwrap().name, "test-chain");
    }

    #[tokio::test]
    async fn test_detection_chain_as_detector() {
        let mut chain = DetectionChain::new("composite-chain".to_string(), DetectionStrategy::Any);

        let detector = StaticThresholdDetector::new(
            "threshold".to_string(),
            100.0,
            ThresholdDirection::Above,
            AlertSeverity::Warning,
        );
        chain.add_detector(detector);

        let mut series = TimeSeries::new("test".to_string(), Labels::new());
        series.add_point(chrono::Utc::now(), 150.0);

        let result = chain.detect(&[series]).await.unwrap();
        assert!(result.is_anomaly);
        assert_eq!(result.score, 1.0);
    }
}
