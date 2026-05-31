use serde::{Serialize, Deserialize};
use chrono::{DateTime, Utc};
use serde_json::Value;
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceShadow {
    pub device_id: String,
    pub desired: HashMap<String, Value>,
    pub reported: HashMap<String, Value>,
    pub delta: Option<HashMap<String, Value>>,
    pub version: u64,
    pub last_updated: DateTime<Utc>,
    pub sync_status: SyncStatus,
    pub monitoring: ShadowMonitoring,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum SyncStatus {
    InSync,
    Syncing,
    OutOfSync,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ShadowMonitoring {
    pub enabled: bool,
    pub update_count: u64,
    pub desired_update_count: u64,
    pub reported_update_count: u64,
    pub sync_count: u64,
    pub sync_failure_count: u64,
    pub last_sync_duration_ms: Option<u64>,
    pub avg_sync_duration_ms: Option<f64>,
    pub out_of_sync_duration_seconds: Option<u64>,
    pub out_of_sync_started_at: Option<DateTime<Utc>>,
    pub delta_detection_count: u64,
    pub metrics_points: Vec<MetricsPoint>,
    pub max_metrics_points: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricsPoint {
    pub timestamp: DateTime<Utc>,
    pub metric_type: MetricsType,
    pub value: f64,
    pub tags: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum MetricsType {
    UpdateLatencyMs,
    SyncDurationMs,
    DeltaSize,
    DesiredStateSize,
    ReportedStateSize,
    SyncStatusChange,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MonitorPoint {
    pub point_id: String,
    pub name: String,
    pub path: String,
    pub data_type: MonitorDataType,
    pub threshold_high: Option<f64>,
    pub threshold_low: Option<f64>,
    pub unit: Option<String>,
    pub last_value: Option<Value>,
    pub last_updated: Option<DateTime<Utc>>,
    pub alert_enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum MonitorDataType {
    Number,
    Boolean,
    String,
    Object,
    Array,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MonitoringConfig {
    pub enabled: bool,
    pub collection_interval_seconds: u64,
    pub report_interval_seconds: u64,
    pub max_metrics_history: usize,
    pub monitor_points: Vec<MonitorPoint>,
    pub alert_webhook_url: Option<String>,
}

impl Default for MonitoringConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            collection_interval_seconds: 10,
            report_interval_seconds: 60,
            max_metrics_history: 1000,
            monitor_points: Vec::new(),
            alert_webhook_url: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MonitoringAlert {
    pub alert_id: String,
    pub device_id: String,
    pub point_id: String,
    pub alert_type: AlertType,
    pub message: String,
    pub current_value: Value,
    pub threshold: Option<f64>,
    pub timestamp: DateTime<Utc>,
    pub acknowledged: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum AlertType {
    ThresholdHigh,
    ThresholdLow,
    ValueChanged,
    SyncTimeout,
    OutOfSync,
    MonitorPointOffline,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MonitoringReport {
    pub report_id: String,
    pub device_id: String,
    pub start_time: DateTime<Utc>,
    pub end_time: DateTime<Utc>,
    pub metrics: Vec<MetricsPoint>,
    pub alerts: Vec<MonitoringAlert>,
    pub summary: MonitoringSummary,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct MonitoringSummary {
    pub total_updates: u64,
    pub successful_syncs: u64,
    pub failed_syncs: u64,
    pub avg_sync_duration_ms: Option<f64>,
    pub max_sync_duration_ms: Option<u64>,
    pub out_of_sync_count: u64,
    pub total_out_of_sync_seconds: u64,
    pub alert_count: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShadowUpdateRequest {
    pub device_id: String,
    pub signature: String,
    pub timestamp: i64,
    pub state: ShadowState,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ShadowState {
    Desired(HashMap<String, Value>),
    Reported(HashMap<String, Value>),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShadowResponse {
    pub device_id: String,
    pub status: String,
    pub version: u64,
    pub state: HashMap<String, Value>,
    pub delta: Option<HashMap<String, Value>>,
    pub monitoring: Option<ShadowMonitoring>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShadowDeltaEvent {
    pub device_id: String,
    pub delta: HashMap<String, Value>,
    pub version: u64,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MonitorPointCreateRequest {
    pub name: String,
    pub path: String,
    pub data_type: MonitorDataType,
    pub threshold_high: Option<f64>,
    pub threshold_low: Option<f64>,
    pub unit: Option<String>,
    pub alert_enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MonitorPointUpdateRequest {
    pub name: Option<String>,
    pub threshold_high: Option<f64>,
    pub threshold_low: Option<f64>,
    pub alert_enabled: Option<bool>,
}

impl DeviceShadow {
    pub fn new(device_id: impl Into<String>) -> Self {
        Self {
            device_id: device_id.into(),
            desired: HashMap::new(),
            reported: HashMap::new(),
            delta: None,
            version: 1,
            last_updated: Utc::now(),
            sync_status: SyncStatus::InSync,
            monitoring: ShadowMonitoring {
                enabled: true,
                max_metrics_points: 1000,
                ..Default::default()
            },
        }
    }

    pub fn with_monitoring_config(mut self, config: &MonitoringConfig) -> Self {
        self.monitoring.enabled = config.enabled;
        self.monitoring.max_metrics_points = config.max_metrics_history;
        self
    }

    pub fn update_desired(&mut self, desired: HashMap<String, Value>) {
        self.desired = desired;
        self.version += 1;
        self.last_updated = Utc::now();
        self.monitoring.update_count += 1;
        self.monitoring.desired_update_count += 1;
        self.record_metric(MetricsType::DesiredStateSize, self.desired.len() as f64);
        self.compute_delta();
    }

    pub fn update_reported(&mut self, reported: HashMap<String, Value>) {
        self.reported = reported;
        self.version += 1;
        self.last_updated = Utc::now();
        self.monitoring.update_count += 1;
        self.monitoring.reported_update_count += 1;
        self.record_metric(MetricsType::ReportedStateSize, self.reported.len() as f64);
        self.compute_delta();
    }

    pub fn compute_delta(&mut self) {
        let mut delta = HashMap::new();
        for (key, desired_value) in &self.desired {
            match self.reported.get(key) {
                Some(reported_value) if reported_value != desired_value => {
                    delta.insert(key.clone(), desired_value.clone());
                }
                None => {
                    delta.insert(key.clone(), desired_value.clone());
                }
                _ => {}
            }
        }
        if delta.is_empty() {
            self.delta = None;
            if self.sync_status == SyncStatus::OutOfSync {
                if let Some(start) = self.monitoring.out_of_sync_started_at {
                    let duration = (Utc::now() - start).num_seconds() as u64;
                    self.monitoring.out_of_sync_duration_seconds = Some(
                        self.monitoring.out_of_sync_duration_seconds.unwrap_or(0) + duration
                    );
                }
                self.monitoring.out_of_sync_started_at = None;
            }
            self.sync_status = SyncStatus::InSync;
        } else {
            if self.sync_status != SyncStatus::OutOfSync {
                self.monitoring.out_of_sync_started_at = Some(Utc::now());
            }
            self.delta = Some(delta);
            self.monitoring.delta_detection_count += 1;
            self.sync_status = SyncStatus::OutOfSync;
            if let Some(d) = &self.delta {
                self.record_metric(MetricsType::DeltaSize, d.len() as f64);
            }
        }
    }

    pub fn record_metric(&mut self, metric_type: MetricsType, value: f64) {
        if !self.monitoring.enabled {
            return;
        }
        let point = MetricsPoint {
            timestamp: Utc::now(),
            metric_type,
            value,
            tags: HashMap::new(),
        };
        self.monitoring.metrics_points.push(point);
        if self.monitoring.metrics_points.len() > self.monitoring.max_metrics_points {
            let remove_count = self.monitoring.metrics_points.len() - self.monitoring.max_metrics_points;
            self.monitoring.metrics_points.drain(0..remove_count);
        }
    }

    pub fn record_sync_duration(&mut self, duration_ms: u64) {
        self.monitoring.last_sync_duration_ms = Some(duration_ms);
        self.record_metric(MetricsType::SyncDurationMs, duration_ms as f64);
        let total = self.monitoring.sync_count as f64;
        let current_avg = self.monitoring.avg_sync_duration_ms.unwrap_or(0.0);
        let new_avg = ((current_avg * (total - 1.0)) + duration_ms as f64) / total;
        self.monitoring.avg_sync_duration_ms = Some(new_avg);
    }

    pub fn record_sync_success(&mut self, duration_ms: u64) {
        self.monitoring.sync_count += 1;
        self.record_sync_duration(duration_ms);
        self.record_metric(MetricsType::SyncStatusChange, 1.0);
    }

    pub fn record_sync_failure(&mut self) {
        self.monitoring.sync_failure_count += 1;
        self.record_metric(MetricsType::SyncStatusChange, 0.0);
    }

    pub fn is_in_sync(&self) -> bool {
        matches!(self.sync_status, SyncStatus::InSync)
    }

    pub fn generate_monitoring_report(&self, duration_seconds: u64) -> MonitoringReport {
        let end_time = Utc::now();
        let start_time = end_time - chrono::Duration::seconds(duration_seconds as i64);

        let metrics: Vec<MetricsPoint> = self.monitoring.metrics_points
            .iter()
            .filter(|m| m.timestamp >= start_time && m.timestamp <= end_time)
            .cloned()
            .collect();

        let mut summary = MonitoringSummary::default();
        summary.total_updates = metrics.iter()
            .filter(|m| matches!(m.metric_type, MetricsType::DesiredStateSize | MetricsType::ReportedStateSize))
            .count() as u64;
        summary.successful_syncs = metrics.iter()
            .filter(|m| matches!(m.metric_type, MetricsType::SyncStatusChange) && m.value > 0.5)
            .count() as u64;
        summary.failed_syncs = metrics.iter()
            .filter(|m| matches!(m.metric_type, MetricsType::SyncStatusChange) && m.value <= 0.5)
            .count() as u64;

        let sync_durations: Vec<f64> = metrics.iter()
            .filter(|m| matches!(m.metric_type, MetricsType::SyncDurationMs))
            .map(|m| m.value)
            .collect();
        if !sync_durations.is_empty() {
            summary.avg_sync_duration_ms = Some(sync_durations.iter().sum::<f64>() / sync_durations.len() as f64);
            summary.max_sync_duration_ms = Some(sync_durations.iter().fold(f64::NAN, |a, &b| a.max(b)) as u64);
        }

        MonitoringReport {
            report_id: Uuid::new_v4().to_string(),
            device_id: self.device_id.clone(),
            start_time,
            end_time,
            metrics,
            alerts: Vec::new(),
            summary,
        }
    }
}

impl MonitorPoint {
    pub fn new(req: MonitorPointCreateRequest) -> Self {
        Self {
            point_id: Uuid::new_v4().to_string(),
            name: req.name,
            path: req.path,
            data_type: req.data_type,
            threshold_high: req.threshold_high,
            threshold_low: req.threshold_low,
            unit: req.unit,
            last_value: None,
            last_updated: None,
            alert_enabled: req.alert_enabled,
        }
    }

    pub fn check_alert(&self, value: &Value) -> Option<AlertType> {
        if !self.alert_enabled {
            return None;
        }
        match value.as_f64() {
            Some(num_value) => {
                if let Some(high) = self.threshold_high {
                    if num_value > high {
                        return Some(AlertType::ThresholdHigh);
                    }
                }
                if let Some(low) = self.threshold_low {
                    if num_value < low {
                        return Some(AlertType::ThresholdLow);
                    }
                }
            }
            None
        }
    }
}
