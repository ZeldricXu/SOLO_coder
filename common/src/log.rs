use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum LogLevel {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
    Fatal,
}

impl LogLevel {
    pub fn from_str(s: &str) -> Option<Self> {
        match s.to_lowercase().as_str() {
            "trace" => Some(LogLevel::Trace),
            "debug" => Some(LogLevel::Debug),
            "info" => Some(LogLevel::Info),
            "warn" | "warning" => Some(LogLevel::Warn),
            "error" => Some(LogLevel::Error),
            "fatal" | "critical" => Some(LogLevel::Fatal),
            _ => None,
        }
    }

    pub fn as_str(&self) -> &str {
        match self {
            LogLevel::Trace => "TRACE",
            LogLevel::Debug => "DEBUG",
            LogLevel::Info => "INFO",
            LogLevel::Warn => "WARN",
            LogLevel::Error => "ERROR",
            LogLevel::Fatal => "FATAL",
        }
    }

    pub fn is_error(&self) -> bool {
        matches!(self, LogLevel::Error | LogLevel::Fatal)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogEvent {
    pub id: Uuid,
    pub timestamp: DateTime<Utc>,
    pub hostname: String,
    pub service: String,
    pub level: LogLevel,
    pub message: String,
    pub fields: HashMap<String, serde_json::Value>,
    pub source_file: String,
    pub source_offset: u64,
    pub raw: Option<String>,
}

impl LogEvent {
    pub fn new(
        hostname: String,
        service: String,
        level: LogLevel,
        message: String,
        source_file: String,
    ) -> Self {
        Self {
            id: Uuid::new_v4(),
            timestamp: Utc::now(),
            hostname,
            service,
            level,
            message,
            fields: HashMap::new(),
            source_file,
            source_offset: 0,
            raw: None,
        }
    }

    pub fn with_offset(mut self, offset: u64) -> Self {
        self.source_offset = offset;
        self
    }

    pub fn with_timestamp(mut self, timestamp: DateTime<Utc>) -> Self {
        self.timestamp = timestamp;
        self
    }

    pub fn with_field<K: Into<String>, V: Into<serde_json::Value>>(
        mut self,
        key: K,
        value: V,
    ) -> Self {
        self.fields.insert(key.into(), value.into());
        self
    }

    pub fn with_raw(mut self, raw: String) -> Self {
        self.raw = Some(raw);
        self
    }

    pub fn add_field<K: Into<String>, V: Into<serde_json::Value>>(&mut self, key: K, value: V) {
        self.fields.insert(key.into(), value.into());
    }

    pub fn get_request_id(&self) -> Option<&str> {
        self.fields
            .get("request_id")
            .and_then(|v| v.as_str())
    }

    pub fn get_duration_ms(&self) -> Option<f64> {
        self.fields
            .get("duration_ms")
            .and_then(|v| v.as_f64())
            .or_else(|| self.fields.get("latency_ms").and_then(|v| v.as_f64()))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LogBatch {
    pub events: Vec<LogEvent>,
    pub batch_id: Uuid,
    pub created_at: DateTime<Utc>,
    pub dropped_events: Option<DroppedEventsMetadata>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DroppedEventsMetadata {
    pub count: u64,
    pub total_size_bytes: u64,
    pub gaps: Vec<FileGap>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileGap {
    pub source_file: String,
    pub start_offset: u64,
    pub end_offset: u64,
    pub timestamp_start: DateTime<Utc>,
    pub timestamp_end: DateTime<Utc>,
}

impl LogBatch {
    pub fn new(events: Vec<LogEvent>) -> Self {
        Self {
            events,
            batch_id: Uuid::new_v4(),
            created_at: Utc::now(),
            dropped_events: None,
        }
    }

    pub fn with_dropped(events: Vec<LogEvent>, dropped: DroppedEventsMetadata) -> Self {
        Self {
            events,
            batch_id: Uuid::new_v4(),
            created_at: Utc::now(),
            dropped_events: Some(dropped),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.events.is_empty()
    }

    pub fn len(&self) -> usize {
        self.events.len()
    }
}

pub use crate::config::LogTailConfig;
