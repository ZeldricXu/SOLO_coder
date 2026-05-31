use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use chrono::{DateTime, Duration, Utc};

use crate::utils::error::{GatewayError, Result};
use crate::models::DriftDetectionResult;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoricalFeatureValue {
    pub feature_id: String,
    pub entity_id: String,
    pub value: serde_json::Value,
    pub event_timestamp: DateTime<Utc>,
    pub ingested_timestamp: DateTime<Utc>,
    pub version: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PointInTimeLookupRequest {
    pub entity_id: String,
    pub feature_ids: Vec<String>,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PointInTimeLookupResponse {
    pub entity_id: String,
    pub features: HashMap<String, HistoricalFeatureValue>,
    pub lookup_timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrainingDataRequest {
    pub entity_ids: Vec<String>,
    pub feature_ids: Vec<String>,
    pub start_time: DateTime<Utc>,
    pub end_time: DateTime<Utc>,
    pub sampling_interval_seconds: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrainingDataResponse {
    pub data: Vec<HashMap<String, serde_json::Value>>,
    pub total_rows: usize,
    pub start_time: DateTime<Utc>,
    pub end_time: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConsistencyCheckRequest {
    pub entity_id: String,
    pub feature_id: String,
    pub online_value: serde_json::Value,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConsistencyCheckResult {
    pub is_consistent: bool,
    pub online_value: serde_json::Value,
    pub offline_value: Option<serde_json::Value>,
    pub difference: Option<f64>,
    pub details: String,
}

pub struct OfflineFeatureStore {
    historical_data: parking_lot::RwLock<Vec<HistoricalFeatureValue>>,
}

impl OfflineFeatureStore {
    pub fn new() -> Self {
        Self {
            historical_data: parking_lot::RwLock::new(Vec::new()),
        }
    }

    pub fn ingest(&self, values: Vec<HistoricalFeatureValue>) -> Result<usize> {
        let mut data = self.historical_data.write();
        let count = values.len();
        data.extend(values);
        Ok(count)
    }

    pub fn point_in_time_lookup(
        &self,
        request: PointInTimeLookupRequest,
    ) -> Result<PointInTimeLookupResponse> {
        let data = self.historical_data.read();
        let mut features = HashMap::new();

        for feature_id in &request.feature_ids {
            let value = data
                .iter()
                .filter(|v| v.entity_id == request.entity_id && v.feature_id == *feature_id)
                .filter(|v| v.event_timestamp <= request.timestamp)
                .max_by_key(|v| v.event_timestamp)
                .cloned();

            if let Some(v) = value {
                features.insert(feature_id.clone(), v);
            }
        }

        Ok(PointInTimeLookupResponse {
            entity_id: request.entity_id,
            features,
            lookup_timestamp: request.timestamp,
        })
    }

    pub fn get_training_data(
        &self,
        request: TrainingDataRequest,
    ) -> Result<TrainingDataResponse> {
        let data = self.historical_data.read();
        let mut rows: Vec<(DateTime<Utc>, HashMap<String, serde_json::Value>)> = Vec::new();

        for entity_id in &request.entity_ids {
            let mut entity_data: HashMap<DateTime<Utc>, HashMap<String, serde_json::Value>> = HashMap::new();

            for feature_id in &request.feature_ids {
                let feature_values: Vec<_> = data
                    .iter()
                    .filter(|v| {
                        v.entity_id == *entity_id
                            && v.feature_id == *feature_id
                            && v.event_timestamp >= request.start_time
                            && v.event_timestamp <= request.end_time
                    })
                    .collect();

                for v in feature_values {
                    let ts = if let Some(interval) = request.sampling_interval_seconds {
                        let ts = v.event_timestamp.timestamp() as u64;
                        let floored = (ts / interval) * interval;
                        DateTime::<Utc>::from_timestamp(floored as i64, 0).unwrap_or(v.event_timestamp)
                    } else {
                        v.event_timestamp
                    };

                    entity_data
                        .entry(ts)
                        .or_insert_with(HashMap::new)
                        .insert(feature_id.clone(), v.value.clone());
                }
            }

            for (ts, mut features) in entity_data {
                features.insert("entity_id".to_string(), serde_json::json!(entity_id));
                features.insert("timestamp".to_string(), serde_json::json!(ts.to_rfc3339()));
                rows.push((ts, features));
            }
        }

        rows.sort_by_key(|(ts, _)| *ts);
        let data_rows: Vec<_> = rows.into_iter().map(|(_, v)| v).collect();
        let total_rows = data_rows.len();

        Ok(TrainingDataResponse {
            data: data_rows,
            total_rows,
            start_time: request.start_time,
            end_time: request.end_time,
        })
    }

    pub fn check_consistency(
        &self,
        request: ConsistencyCheckRequest,
    ) -> Result<ConsistencyCheckResult> {
        let pit_request = PointInTimeLookupRequest {
            entity_id: request.entity_id.clone(),
            feature_ids: vec![request.feature_id.clone()],
            timestamp: request.timestamp,
        };

        let pit_response = self.point_in_time_lookup(pit_request)?;
        let offline_value = pit_response.features.get(&request.feature_id).map(|v| v.value.clone());

        let (is_consistent, difference, details) = match &offline_value {
            Some(offline) => {
                match (request.online_value.as_f64(), offline.as_f64()) {
                    (Some(o), Some(f)) => {
                        let diff = (o - f).abs();
                        let is_consistent = diff < 0.001;
                        (
                            is_consistent,
                            Some(diff),
                            if is_consistent {
                                "Values are consistent".to_string()
                            } else {
                                format!("Values differ by {}", diff)
                            },
                        )
                    }
                    _ => {
                        let is_consistent = request.online_value == *offline;
                        (
                            is_consistent,
                            None,
                            if is_consistent {
                                "Values are consistent".to_string()
                            } else {
                                "Values are different".to_string()
                            },
                        )
                    }
                }
            }
            None => (
                false,
                None,
                "No offline value found for comparison".to_string(),
            ),
        };

        Ok(ConsistencyCheckResult {
            is_consistent,
            online_value: request.online_value,
            offline_value,
            difference,
            details,
        })
    }

    pub fn detect_drift(
        &self,
        feature_id: &str,
        baseline_start: DateTime<Utc>,
        baseline_end: DateTime<Utc>,
        current_start: DateTime<Utc>,
        current_end: DateTime<Utc>,
        threshold: f64,
    ) -> Result<DriftDetectionResult> {
        let data = self.historical_data.read();

        let baseline_values: Vec<f64> = data
            .iter()
            .filter(|v| {
                v.feature_id == feature_id
                    && v.event_timestamp >= baseline_start
                    && v.event_timestamp < baseline_end
            })
            .filter_map(|v| v.value.as_f64())
            .collect();

        let current_values: Vec<f64> = data
            .iter()
            .filter(|v| {
                v.feature_id == feature_id
                    && v.event_timestamp >= current_start
                    && v.event_timestamp < current_end
            })
            .filter_map(|v| v.value.as_f64())
            .collect();

        if baseline_values.is_empty() || current_values.is_empty() {
            return Err(GatewayError::Validation(
                "Insufficient data for drift detection".to_string(),
            ));
        }

        let baseline_mean = baseline_values.iter().sum::<f64>() / baseline_values.len() as f64;
        let current_mean = current_values.iter().sum::<f64>() / current_values.len() as f64;

        let baseline_std = self.calculate_std(&baseline_values, baseline_mean);
        let current_std = self.calculate_std(&current_values, current_mean);

        let drift_score = if baseline_std + current_std > 0.0 {
            (current_mean - baseline_mean).abs() / (baseline_std + current_std)
        } else {
            0.0
        };

        let mut result = DriftDetectionResult::new(
            feature_id.to_string(),
            drift_score,
            threshold,
        );
        result.baseline_mean = baseline_mean;
        result.current_mean = current_mean;

        Ok(result)
    }

    fn calculate_std(&self, values: &[f64], mean: f64) -> f64 {
        if values.len() < 2 {
            return 0.0;
        }
        let variance: f64 = values
            .iter()
            .map(|v| (v - mean).powi(2))
            .sum::<f64>()
            / (values.len() - 1) as f64;
        variance.sqrt()
    }

    pub fn get_all_values(&self) -> Vec<HistoricalFeatureValue> {
        self.historical_data.read().clone()
    }
}

impl Default for OfflineFeatureStore {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_data() -> Vec<HistoricalFeatureValue> {
        let now = Utc::now();
        let mut values = Vec::new();

        for i in 0..10 {
            values.push(HistoricalFeatureValue {
                feature_id: "feat_1".to_string(),
                entity_id: format!("user_{}", i % 3),
                value: serde_json::json!(0.5 + i as f64 * 0.1),
                event_timestamp: now - Duration::hours((10 - i) as i64),
                ingested_timestamp: now,
                version: 1,
            });
        }

        values
    }

    #[test]
    fn test_ingest_and_pit_lookup() {
        let store = OfflineFeatureStore::new();
        let data = create_test_data();
        store.ingest(data).unwrap();

        let now = Utc::now();
        let request = PointInTimeLookupRequest {
            entity_id: "user_0".to_string(),
            feature_ids: vec!["feat_1".to_string()],
            timestamp: now - Duration::hours(5),
        };

        let response = store.point_in_time_lookup(request).unwrap();
        assert_eq!(response.entity_id, "user_0");
        assert!(response.features.contains_key("feat_1"));
    }

    #[test]
    fn test_get_training_data() {
        let store = OfflineFeatureStore::new();
        let data = create_test_data();
        store.ingest(data).unwrap();

        let now = Utc::now();
        let request = TrainingDataRequest {
            entity_ids: vec!["user_0".to_string(), "user_1".to_string()],
            feature_ids: vec!["feat_1".to_string()],
            start_time: now - Duration::hours(24),
            end_time: now,
            sampling_interval_seconds: None,
        };

        let response = store.get_training_data(request).unwrap();
        assert!(response.total_rows > 0);
        assert!(!response.data.is_empty());
        assert!(response.data[0].contains_key("feat_1"));
        assert!(response.data[0].contains_key("entity_id"));
    }

    #[test]
    fn test_consistency_check() {
        let store = OfflineFeatureStore::new();
        let now = Utc::now();

        let value = HistoricalFeatureValue {
            feature_id: "feat_1".to_string(),
            entity_id: "user_1".to_string(),
            value: serde_json::json!(0.85),
            event_timestamp: now - Duration::minutes(5),
            ingested_timestamp: now,
            version: 1,
        };
        store.ingest(vec![value]).unwrap();

        let request = ConsistencyCheckRequest {
            entity_id: "user_1".to_string(),
            feature_id: "feat_1".to_string(),
            online_value: serde_json::json!(0.85),
            timestamp: now,
        };

        let result = store.check_consistency(request).unwrap();
        assert!(result.is_consistent);
    }

    #[test]
    fn test_drift_detection() {
        let store = OfflineFeatureStore::new();
        let now = Utc::now();

        let mut values = Vec::new();
        for i in 0..20 {
            values.push(HistoricalFeatureValue {
                feature_id: "feat_drift".to_string(),
                entity_id: "entity_1".to_string(),
                value: if i < 10 {
                    serde_json::json!(0.1 + i as f64 * 0.01)
                } else {
                    serde_json::json!(0.5 + i as f64 * 0.01)
                },
                event_timestamp: now - Duration::minutes((30 - i) as i64),
                ingested_timestamp: now,
                version: 1,
            });
        }
        store.ingest(values).unwrap();

        let result = store.detect_drift(
            "feat_drift",
            now - Duration::minutes(30),
            now - Duration::minutes(15),
            now - Duration::minutes(15),
            now,
            0.5,
        ).unwrap();

        assert!(result.is_drifted);
        assert!(result.drift_score > 0.5);
    }
}
