use serde::Deserialize;
use std::collections::HashMap;

use common::alert::AlertSeverity;

use crate::chain::{DetectionChain, DetectionStrategy};
use crate::detectors::{
    DbscanDetector, Detector, MovingAverageDetector, SeasonalDetector, StaticThresholdDetector,
    ThresholdDirection,
};

#[derive(Debug, Deserialize)]
pub struct DetectorConfig {
    pub name: String,
    pub r#type: String,
    pub params: HashMap<String, String>,
    pub severity: String,
}

#[derive(Debug, Deserialize)]
pub struct ChainConfig {
    pub name: String,
    pub strategy: String,
    pub detectors: Vec<String>,
    pub min_score: Option<f64>,
}

#[derive(Debug, Deserialize)]
pub struct AnomalyConfig {
    pub detectors: Vec<DetectorConfig>,
    pub chains: Vec<ChainConfig>,
}

impl AnomalyConfig {
    pub fn from_yaml(content: &str) -> Result<Self, serde_yaml::Error> {
        serde_yaml::from_str(content)
    }

    pub fn build_chains(&self) -> HashMap<String, DetectionChain> {
        let mut chains = HashMap::new();

        for chain_config in &self.chains {
            let strategy = match chain_config.strategy.to_lowercase().as_str() {
                "any" => DetectionStrategy::Any,
                "all" => DetectionStrategy::All,
                "majority" => DetectionStrategy::Majority,
                "weighted" => DetectionStrategy::Weighted,
                _ => DetectionStrategy::Any,
            };

            let mut chain = DetectionChain::new(chain_config.name.clone(), strategy);
            if let Some(min_score) = chain_config.min_score {
                chain = chain.with_min_score(min_score);
            }

            for detector_name in &chain_config.detectors {
                if let Some(detector_config) = self.detectors.iter().find(|d| &d.name == detector_name) {
                    if let Some(detector) = build_detector(detector_config) {
                        chain.add_boxed_detector(detector);
                    }
                }
            }

            chains.insert(chain_config.name.clone(), chain);
        }

        chains
    }
}

fn build_detector(config: &DetectorConfig) -> Option<Box<dyn Detector>> {
    let severity = AlertSeverity::from_str(&config.severity).unwrap_or(AlertSeverity::Warning);

    match config.r#type.to_lowercase().as_str() {
        "static_threshold" => {
            let threshold: f64 = config.params.get("threshold")?.parse().ok()?;
            let direction = match config.params.get("direction").map(|s| s.as_str()) {
                Some("below") => ThresholdDirection::Below,
                Some("either") => ThresholdDirection::Either,
                _ => ThresholdDirection::Above,
            };
            Some(Box::new(StaticThresholdDetector::new(
                config.name.clone(),
                threshold,
                direction,
                severity,
            )))
        }
        "moving_average" => {
            let window_size: usize = config
                .params
                .get("window_size")
                .and_then(|s| s.parse().ok())
                .unwrap_or(10);
            let std_dev_multiplier: f64 = config
                .params
                .get("std_dev_multiplier")
                .and_then(|s| s.parse().ok())
                .unwrap_or(2.0);
            Some(Box::new(MovingAverageDetector::new(
                config.name.clone(),
                window_size,
                std_dev_multiplier,
                severity,
            )))
        }
        "dbscan" => {
            let eps: f64 = config
                .params
                .get("eps")
                .and_then(|s| s.parse().ok())
                .unwrap_or(10.0);
            let min_points: usize = config
                .params
                .get("min_points")
                .and_then(|s| s.parse().ok())
                .unwrap_or(5);
            Some(Box::new(DbscanDetector::new(
                config.name.clone(),
                eps,
                min_points,
                severity,
            )))
        }
        "seasonal" => {
            let period_hours: i64 = config
                .params
                .get("period_hours")
                .and_then(|s| s.parse().ok())
                .unwrap_or(24);
            let threshold_multiplier: f64 = config
                .params
                .get("threshold_multiplier")
                .and_then(|s| s.parse().ok())
                .unwrap_or(2.0);
            Some(Box::new(SeasonalDetector::new(
                config.name.clone(),
                period_hours,
                threshold_multiplier,
                severity,
            )))
        }
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_config() {
        let config_yaml = r#"
detectors:
  - name: "high_error_rate"
    type: "static_threshold"
    params:
      threshold: "0.1"
      direction: "above"
    severity: "warning"
  - name: "error_spike"
    type: "moving_average"
    params:
      window_size: "10"
      std_dev_multiplier: "2.5"
    severity: "error"
chains:
  - name: "error_anomaly"
    strategy: "any"
    detectors: ["high_error_rate", "error_spike"]
    min_score: 0.5
"#;
        let config = AnomalyConfig::from_yaml(config_yaml).unwrap();
        assert_eq!(config.detectors.len(), 2);
        assert_eq!(config.chains.len(), 1);

        let chains = config.build_chains();
        assert_eq!(chains.len(), 1);
        assert!(chains.contains_key("error_anomaly"));
    }
}
