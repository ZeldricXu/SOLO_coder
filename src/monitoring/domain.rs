use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fmt;
use chrono::{DateTime, Utc};
use std::sync::LazyLock;
use regex::Regex;

pub const MAX_METRIC_ID_LENGTH: usize = 256;
pub const MAX_LABEL_KEY_LENGTH: usize = 128;
pub const MAX_LABEL_VALUE_LENGTH: usize = 1024;
pub const MAX_LABEL_COUNT: usize = 32;
pub const MAX_HISTOGRAM_SAMPLES: usize = 10000;

pub static METRIC_ID_REGEX: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^[a-zA-Z_][a-zA-Z0-9_]*$").expect("valid regex pattern")
});

pub static LABEL_KEY_REGEX: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^[a-zA-Z_][a-zA-Z0-9_]*$").expect("valid regex pattern")
});

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct MetricId(pub String);

impl MetricId {
    pub fn new(id: impl Into<String>) -> Result<Self, MetricValidationError> {
        let s: String = id.into();
        Self::validate(&s)?;
        Ok(Self(s))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }

    pub fn validate(s: &str) -> Result<(), MetricValidationError> {
        if s.is_empty() {
            return Err(MetricValidationError::Empty);
        }
        if s.len() > MAX_METRIC_ID_LENGTH {
            return Err(MetricValidationError::TooLong {
                max: MAX_METRIC_ID_LENGTH,
                actual: s.len(),
            });
        }
        if !METRIC_ID_REGEX.is_match(s) {
            return Err(MetricValidationError::InvalidFormat {
                value: s.to_string(),
            });
        }
        Ok(())
    }
}

impl fmt::Display for MetricId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl TryFrom<&str> for MetricId {
    type Error = MetricValidationError;

    fn try_from(s: &str) -> Result<Self, Self::Error> {
        Self::new(s)
    }
}

#[derive(Debug, thiserror::Error)]
pub enum MetricValidationError {
    #[error("metric identifier cannot be empty")]
    Empty,
    #[error("metric identifier too long: max {max}, actual {actual}")]
    TooLong { max: usize, actual: usize },
    #[error("invalid format: {value}")]
    InvalidFormat { value: String },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MetricType {
    Counter,
    Gauge,
    Histogram,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub enum MetricValue {
    Counter(u64),
    Gauge(f64),
    HistogramSample(f64),
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct Label {
    pub key: String,
    pub value: String,
}

impl Label {
    pub fn new(key: impl Into<String>, value: impl Into<String>) -> Result<Self, LabelValidationError> {
        let key: String = key.into();
        let value: String = value.into();
        Self::validate_key(&key)?;
        Self::validate_value(&value)?;
        Ok(Self { key, value })
    }

    pub fn validate_key(key: &str) -> Result<(), LabelValidationError> {
        if key.is_empty() {
            return Err(LabelValidationError::KeyEmpty);
        }
        if key.len() > MAX_LABEL_KEY_LENGTH {
            return Err(LabelValidationError::KeyTooLong {
                max: MAX_LABEL_KEY_LENGTH,
                actual: key.len(),
            });
        }
        if !LABEL_KEY_REGEX.is_match(key) {
            return Err(LabelValidationError::KeyInvalidFormat {
                key: key.to_string(),
            });
        }
        Ok(())
    }

    pub fn validate_value(value: &str) -> Result<(), LabelValidationError> {
        if value.len() > MAX_LABEL_VALUE_LENGTH {
            return Err(LabelValidationError::ValueTooLong {
                max: MAX_LABEL_VALUE_LENGTH,
                actual: value.len(),
            });
        }
        Ok(())
    }
}

#[derive(Debug, thiserror::Error)]
pub enum LabelValidationError {
    #[error("label key cannot be empty")]
    KeyEmpty,
    #[error("label key too long: max {max}, actual {actual}")]
    KeyTooLong { max: usize, actual: usize },
    #[error("invalid label key format: {key}")]
    KeyInvalidFormat { key: String },
    #[error("label value too long: max {max}, actual {actual}")]
    ValueTooLong { max: usize, actual: usize },
    #[error("too many labels: max {max}, actual {actual}")]
    TooManyLabels { max: usize, actual: usize },
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
pub struct Labels(pub BTreeMap<String, String>);

impl Labels {
    pub fn new() -> Self {
        Self(BTreeMap::new())
    }

    pub fn try_with(
        mut self,
        key: impl Into<String>,
        value: impl Into<String>,
    ) -> Result<Self, LabelValidationError> {
        if self.0.len() >= MAX_LABEL_COUNT {
            return Err(LabelValidationError::TooManyLabels {
                max: MAX_LABEL_COUNT,
                actual: self.0.len() + 1,
            });
        }
        let label = Label::new(key, value)?;
        self.0.insert(label.key, label.value);
        Ok(self)
    }

    pub fn with(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        match self.try_with(key, value) {
            Ok(labels) => labels,
            Err(e) => {
                tracing::warn!(error = %e, "skipping invalid label");
                self
            }
        }
    }

    pub fn get(&self, key: &str) -> Option<&str> {
        self.0.get(key).map(|s| s.as_str())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricRecord {
    pub id: MetricId,
    pub metric_type: MetricType,
    pub value: MetricValue,
    pub labels: Labels,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricsSnapshot {
    pub timestamp: DateTime<Utc>,
    pub records: Vec<MetricRecord>,
}

pub fn validate_histogram_value(value: f64) -> Result<f64, HistogramValidationError> {
    if value.is_nan() {
        return Err(HistogramValidationError::NaN);
    }
    if value.is_infinite() {
        return Err(HistogramValidationError::Infinite);
    }
    Ok(value)
}

#[derive(Debug, thiserror::Error)]
pub enum HistogramValidationError {
    #[error("histogram value cannot be NaN")]
    NaN,
    #[error("histogram value cannot be infinite")]
    Infinite,
}
