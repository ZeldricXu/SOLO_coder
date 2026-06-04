use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

use crate::metrics::Labels;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum AlertSeverity {
    Critical,
    Error,
    Warning,
    Info,
}

impl AlertSeverity {
    pub fn from_str(s: &str) -> Option<Self> {
        match s.to_lowercase().as_str() {
            "critical" => Some(AlertSeverity::Critical),
            "error" => Some(AlertSeverity::Error),
            "warning" | "warn" => Some(AlertSeverity::Warning),
            "info" | "information" => Some(AlertSeverity::Info),
            _ => None,
        }
    }

    pub fn as_str(&self) -> &str {
        match self {
            AlertSeverity::Critical => "critical",
            AlertSeverity::Error => "error",
            AlertSeverity::Warning => "warning",
            AlertSeverity::Info => "info",
        }
    }

    pub fn order(&self) -> u8 {
        match self {
            AlertSeverity::Critical => 4,
            AlertSeverity::Error => 3,
            AlertSeverity::Warning => 2,
            AlertSeverity::Info => 1,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum AlertStatus {
    Firing,
    Resolved,
    Silenced,
    Inhibited,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum DetectionMethod {
    StaticThreshold,
    MovingAverage,
    Dbscan,
    SeasonalComparison,
    PatternChange,
    Correlation,
    ChainComposite,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Alert {
    pub id: Uuid,
    pub name: String,
    pub severity: AlertSeverity,
    pub status: AlertStatus,
    pub labels: Labels,
    pub annotations: HashMap<String, String>,
    pub starts_at: DateTime<Utc>,
    pub ends_at: Option<DateTime<Utc>>,
    pub detection_method: DetectionMethod,
    pub value: f64,
    pub threshold: Option<f64>,
    pub generator_url: Option<String>,
}

impl Alert {
    pub fn new(
        name: String,
        severity: AlertSeverity,
        labels: Labels,
        detection_method: DetectionMethod,
        value: f64,
    ) -> Self {
        Self {
            id: Uuid::new_v4(),
            name,
            severity,
            status: AlertStatus::Firing,
            labels,
            annotations: HashMap::new(),
            starts_at: Utc::now(),
            ends_at: None,
            detection_method,
            value,
            threshold: None,
            generator_url: None,
        }
    }

    pub fn with_annotation<K: Into<String>, V: Into<String>>(mut self, key: K, value: V) -> Self {
        self.annotations.insert(key.into(), value.into());
        self
    }

    pub fn with_threshold(mut self, threshold: f64) -> Self {
        self.threshold = Some(threshold);
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Incident {
    pub id: Uuid,
    pub title: String,
    pub severity: AlertSeverity,
    pub alerts: Vec<Alert>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub status: IncidentStatus,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum IncidentStatus {
    Open,
    Acknowledged,
    Resolved,
    Closed,
}

impl Incident {
    pub fn new(title: String, severity: AlertSeverity, alert: Alert) -> Self {
        Self {
            id: Uuid::new_v4(),
            title,
            severity,
            alerts: vec![alert],
            created_at: Utc::now(),
            updated_at: Utc::now(),
            status: IncidentStatus::Open,
        }
    }

    pub fn add_alert(&mut self, alert: Alert) {
        self.alerts.push(alert);
        self.updated_at = Utc::now();
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AlertRule {
    pub id: Uuid,
    pub name: String,
    pub expr: String,
    pub for_duration: Option<i64>,
    pub labels: Labels,
    pub annotations: HashMap<String, String>,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Silence {
    pub id: Uuid,
    pub starts_at: DateTime<Utc>,
    pub ends_at: DateTime<Utc>,
    pub matchers: Vec<LabelMatcher>,
    pub created_by: String,
    pub comment: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LabelMatcher {
    pub name: String,
    pub value: String,
    pub is_regex: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InhibitRule {
    pub id: Uuid,
    pub name: String,
    pub source_match: Vec<LabelMatcher>,
    pub target_match: Vec<LabelMatcher>,
    pub equal: Vec<String>,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum NotificationChannelType {
    DingTalk,
    FeiShu,
    Email,
    PagerDuty,
    Webhook,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NotificationChannel {
    pub id: Uuid,
    pub name: String,
    pub channel_type: NotificationChannelType,
    pub config: HashMap<String, String>,
    pub enabled: bool,
}
