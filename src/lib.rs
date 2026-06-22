pub mod config;
pub mod collector;
pub mod parser;
pub mod aggregator;
pub mod detector;
pub mod alerter;
pub mod output;
pub mod observability;
pub mod interner;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum LogLevel {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
    Fatal,
    Unknown,
}

impl From<&str> for LogLevel {
    fn from(s: &str) -> Self {
        match s.to_uppercase().as_str() {
            "TRACE" => LogLevel::Trace,
            "DEBUG" => LogLevel::Debug,
            "INFO" | "INFORMATION" => LogLevel::Info,
            "WARN" | "WARNING" => LogLevel::Warn,
            "ERROR" | "ERR" => LogLevel::Error,
            "FATAL" | "CRITICAL" | "CRIT" => LogLevel::Fatal,
            _ => LogLevel::Unknown,
        }
    }
}

impl std::fmt::Display for LogLevel {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            LogLevel::Trace => "TRACE",
            LogLevel::Debug => "DEBUG",
            LogLevel::Info => "INFO",
            LogLevel::Warn => "WARN",
            LogLevel::Error => "ERROR",
            LogLevel::Fatal => "FATAL",
            LogLevel::Unknown => "UNKNOWN",
        };
        write!(f, "{}", s)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogRecord {
    pub id: Uuid,
    pub timestamp: DateTime<Utc>,
    pub level: LogLevel,
    pub service: String,
    pub trace_id: Option<String>,
    pub spend_ms: Option<f64>,
    pub message: String,
    pub fields: BTreeMap<String, String>,
    pub source: String,
    pub raw: String,
}

impl LogRecord {
    pub fn new() -> Self {
        Self {
            id: Uuid::new_v4(),
            timestamp: Utc::now(),
            level: LogLevel::Unknown,
            service: String::new(),
            trace_id: None,
            spend_ms: None,
            message: String::new(),
            fields: BTreeMap::new(),
            source: String::new(),
            raw: String::new(),
        }
    }
}

impl Default for LogRecord {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum LogFormat {
    Json,
    Csv,
    NginxAccess,
    ApacheCommon,
    Envoy,
    Custom(String),
    Unknown,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregationKey {
    pub service: String,
    pub level: LogLevel,
}

impl std::fmt::Display for AggregationKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}:{}", self.service, self.level)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WindowStats {
    pub window_start: DateTime<Utc>,
    pub window_end: DateTime<Utc>,
    pub key: AggregationKey,
    pub count: u64,
    pub sum_spend: f64,
    pub avg_spend: f64,
    pub p50_spend: f64,
    pub p95_spend: f64,
    pub p99_spend: f64,
    pub min_spend: f64,
    pub max_spend: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AlertEvent {
    pub id: Uuid,
    pub timestamp: DateTime<Utc>,
    pub rule_id: String,
    pub rule_name: String,
    pub severity: AlertSeverity,
    pub message: String,
    pub service: String,
    pub window_stats: Option<WindowStats>,
    pub raw_logs: Vec<String>,
    pub occurrences: u64,
    pub duration_secs: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum AlertSeverity {
    Info,
    Warning,
    Critical,
}

impl std::fmt::Display for AlertSeverity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            AlertSeverity::Info => "INFO",
            AlertSeverity::Warning => "WARNING",
            AlertSeverity::Critical => "CRITICAL",
        };
        write!(f, "{}", s)
    }
}
