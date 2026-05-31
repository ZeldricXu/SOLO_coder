use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use crate::models::StreamSQLError;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataMarker {
    pub marker_id: String,
    pub table_name: String,
    pub row_identifier: String,
    pub rule_id: String,
    pub rule_name: String,
    pub severity: crate::data_quality::rules::Severity,
    pub reason: String,
    pub marked_at: chrono::DateTime<chrono::Utc>,
    pub resolved: bool,
    pub resolved_at: Option<chrono::DateTime<chrono::Utc>>,
    pub resolution_note: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MarkerStats {
    pub total_markers: usize,
    pub resolved: usize,
    pub unresolved: usize,
    pub by_severity: HashMap<String, usize>,
    pub by_table: HashMap<String, usize>,
}

pub struct DataMarkerManager {
    markers: Arc<RwLock<HashMap<String, DataMarker>>>,
}

impl Default for DataMarkerManager {
    fn default() -> Self {
        Self::new()
    }
}

impl DataMarkerManager {
    pub fn new() -> Self {
        Self {
            markers: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn mark_invalid(
        &self,
        table_name: &str,
        row_identifier: &str,
        rule_id: &str,
        rule_name: &str,
        severity: crate::data_quality::rules::Severity,
        reason: &str,
    ) -> DataMarker {
        let marker = DataMarker {
            marker_id: crate::models::IdGenerator::generate("marker"),
            table_name: table_name.to_string(),
            row_identifier: row_identifier.to_string(),
            rule_id: rule_id.to_string(),
            rule_name: rule_name.to_string(),
            severity,
            reason: reason.to_string(),
            marked_at: chrono::Utc::now(),
            resolved: false,
            resolved_at: None,
            resolution_note: None,
        };

        self.markers
            .write()
            .await
            .insert(marker.marker_id.clone(), marker.clone());

        marker
    }

    pub async fn resolve_marker(
        &self,
        marker_id: &str,
        resolution_note: Option<String>,
    ) -> Result<DataMarker, StreamSQLError> {
        let mut markers = self.markers.write().await;
        
        if let Some(marker) = markers.get_mut(marker_id) {
            marker.resolved = true;
            marker.resolved_at = Some(chrono::Utc::now());
            marker.resolution_note = resolution_note;
            Ok(marker.clone())
        } else {
            Err(StreamSQLError::Quality(format!("Marker {} not found", marker_id)))
        }
    }

    pub async fn get_marker(&self, marker_id: &str) -> Option<DataMarker> {
        self.markers.read().await.get(marker_id).cloned()
    }

    pub async fn get_markers_for_table(&self, table_name: &str) -> Vec<DataMarker> {
        self.markers
            .read()
            .await
            .values()
            .filter(|m| m.table_name == table_name)
            .cloned()
            .collect()
    }

    pub async fn get_unresolved_markers(&self) -> Vec<DataMarker> {
        self.markers
            .read()
            .await
            .values()
            .filter(|m| !m.resolved)
            .cloned()
            .collect()
    }

    pub async fn get_markers_by_severity(
        &self,
        severity: crate::data_quality::rules::Severity,
    ) -> Vec<DataMarker> {
        self.markers
            .read()
            .await
            .values()
            .filter(|m| m.severity == severity && !m.resolved)
            .cloned()
            .collect()
    }

    pub async fn get_stats(&self) -> MarkerStats {
        let markers = self.markers.read().await;
        let mut stats = MarkerStats {
            total_markers: markers.len(),
            resolved: 0,
            unresolved: 0,
            by_severity: HashMap::new(),
            by_table: HashMap::new(),
        };

        for marker in markers.values() {
            if marker.resolved {
                stats.resolved += 1;
            } else {
                stats.unresolved += 1;
            }

            let severity_key = format!("{:?}", marker.severity).to_lowercase();
            *stats.by_severity.entry(severity_key).or_insert(0) += 1;

            *stats.by_table.entry(marker.table_name.clone()).or_insert(0) += 1;
        }

        stats
    }

    pub async fn clear_resolved(&self) {
        self.markers
            .write()
            .await
            .retain(|_, m| !m.resolved);
    }

    pub async fn mark_rows_from_result(
        &self,
        rule: &crate::data_quality::rules::QualityRule,
        result: &crate::data_quality::rules::RuleResult,
    ) {
        for (i, row) in result.invalid_rows.iter().enumerate() {
            let row_id = row
                .get("id")
                .and_then(|v| v.as_str())
                .or_else(|| row.get("id").and_then(|v| v.as_u64().map(|n| n.to_string())))
                .unwrap_or_else(|| format!("row_{}", i));

            self.mark_invalid(
                &rule.table_name,
                row_id,
                &rule.id,
                &rule.name,
                rule.severity,
                result.message.as_deref().unwrap_or("Quality check failed"),
            )
            .await;
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnomalyEvent {
    pub event_id: String,
    pub table_name: String,
    pub metric: String,
    pub expected_value: f64,
    pub actual_value: f64,
    pub deviation: f64,
    pub timestamp: chrono::DateTime<chrono::Utc>,
    pub severity: crate::data_quality::rules::Severity,
}

pub struct AnomalyDetector {
    thresholds: Arc<RwLock<HashMap<String, (f64, f64)>>>,
    history: Arc<RwLock<HashMap<String, Vec<f64>>>>,
}

impl Default for AnomalyDetector {
    fn default() -> Self {
        Self::new()
    }
}

impl AnomalyDetector {
    pub fn new() -> Self {
        Self {
            thresholds: Arc::new(RwLock::new(HashMap::new())),
            history: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn set_threshold(
        &self,
        metric: &str,
        min: f64,
        max: f64,
    ) {
        self.thresholds
            .write()
            .await
            .insert(metric.to_string(), (min, max));
    }

    pub async fn check_value(
        &self,
        table_name: &str,
        metric: &str,
        value: f64,
    ) -> Option<AnomalyEvent> {
        let thresholds = self.thresholds.read().await;
        
        if let Some(&(min, max)) = thresholds.get(metric) {
            if value < min || value > max {
                let expected = (min + max) / 2.0;
                let deviation = if value != 0.0 {
                    ((value - expected) / value).abs() * 100.0
                } else {
                    100.0
                };

                let severity = if deviation > 50.0 {
                    crate::data_quality::rules::Severity::Critical
                } else if deviation > 20.0 {
                    crate::data_quality::rules::Severity::High
                } else {
                    crate::data_quality::rules::Severity::Medium
                };

                return Some(AnomalyEvent {
                    event_id: crate::models::IdGenerator::generate("anomaly"),
                    table_name: table_name.to_string(),
                    metric: metric.to_string(),
                    expected_value: expected,
                    actual_value: value,
                    deviation,
                    timestamp: chrono::Utc::now(),
                    severity,
                });
            }
        }

        None
    }

    pub async fn record_value(
        &self,
        metric: &str,
        value: f64,
        window_size: usize,
    ) {
        let mut history = self.history.write().await;
        let values = history.entry(metric.to_string()).or_insert_with(Vec::new);
        values.push(value);
        
        while values.len() > window_size {
            values.remove(0);
        }

        if values.len() >= 3 {
            let mean = values.iter().sum::<f64>() / values.len() as f64;
            let std_dev = (values
                .iter()
                .map(|v| (v - mean).powi(2))
                .sum::<f64>()
                / values.len() as f64)
                .sqrt();

            let min = mean - 3.0 * std_dev;
            let max = mean + 3.0 * std_dev;

            self.set_threshold(metric, min, max).await;
        }
    }
}
