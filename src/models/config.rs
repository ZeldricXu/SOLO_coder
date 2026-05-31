use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub config_id: String,
    pub namespace: String,
    pub version: u32,
    pub parameters: std::collections::HashMap<String, serde_json::Value>,
    pub enabled: bool,
    pub applied_at: DateTime<Utc>,
}

impl Config {
    pub fn new(namespace: impl Into<String>) -> Self {
        Self {
            config_id: crate::models::IdGenerator::generate("cfg"),
            namespace: namespace.into(),
            version: 1,
            parameters: std::collections::HashMap::new(),
            enabled: true,
            applied_at: Utc::now(),
        }
    }

    pub fn with_param(mut self, key: impl Into<String>, value: serde_json::Value) -> Self {
        self.parameters.insert(key.into(), value);
        self
    }

    pub fn increment_version(&mut self) {
        self.version += 1;
        self.applied_at = Utc::now();
    }

    pub fn get_param<T: for<'de> Deserialize<'de>>(&self, key: &str) -> Option<T> {
        self.parameters
            .get(key)
            .and_then(|v| serde_json::from_value(v.clone()).ok())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CDCConfig {
    pub source_type: String,
    pub connection_string: String,
    pub tables: Vec<String>,
    pub output_format: OutputFormat,
    pub batch_size: usize,
    pub poll_interval_ms: u64,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum OutputFormat {
    Json,
    Avro,
    Protobuf,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataQualityConfig {
    pub rules: Vec<QualityRule>,
    pub schedule: ScheduleConfig,
    pub alert_threshold: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QualityRule {
    pub id: String,
    pub name: String,
    pub rule_type: RuleType,
    pub expression: String,
    pub severity: Severity,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RuleType {
    NullCheck,
    RangeCheck,
    RegexMatch,
    Uniqueness,
    ReferentialIntegrity,
    Custom,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Severity {
    Low,
    Medium,
    High,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScheduleConfig {
    pub cron_expression: String,
    pub timezone: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LifecycleConfig {
    pub hot_retention_days: u32,
    pub warm_retention_days: u32,
    pub cold_storage: String,
    pub archive_policy: ArchivePolicy,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ArchivePolicy {
    CompressAndMove,
    Delete,
    MoveToCold,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TimeseriesConfig {
    pub compression_algorithm: CompressionAlgorithm,
    pub downsampling_rules: Vec<DownsamplingRule>,
    pub resolutions: Vec<u64>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum CompressionAlgorithm {
    Lz4,
    Zstd,
    Gorilla,
    Delta,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DownsamplingRule {
    pub resolution_ms: u64,
    pub aggregation: AggregationType,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AggregationType {
    Mean,
    Sum,
    Min,
    Max,
    First,
    Last,
}
