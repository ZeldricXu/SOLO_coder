use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{info, warn, error};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    #[serde(default)]
    pub pipeline: PipelineConfig,

    #[serde(default)]
    pub source: SourceConfig,

    #[serde(default)]
    pub sink: SinkConfig,

    #[serde(default)]
    pub rules: RulesConfig,

    #[serde(default)]
    pub observability: ObservabilityConfig,

    #[serde(default)]
    pub parser: ParserConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ParserConfig {
    #[serde(default)]
    pub custom_formats: Vec<CustomFormatConfig>,

    #[serde(default)]
    pub format_match_order: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CustomFormatConfig {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default = "default_priority")]
    pub priority: u32,
    #[serde(default)]
    pub line_prefix: Option<String>,
    pub delimiter: String,
    #[serde(default)]
    pub trim_whitespace: bool,
    pub fields: Vec<CustomFieldConfig>,
    #[serde(default)]
    pub time_format: Option<String>,
}

fn default_priority() -> u32 { 100 }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CustomFieldConfig {
    pub name: String,
    #[serde(rename = "type", default = "default_field_type")]
    pub field_type: String,
    #[serde(default)]
    pub target_field: Option<String>,
    #[serde(default)]
    pub optional: bool,
    #[serde(default)]
    pub default_value: Option<String>,
}

fn default_field_type() -> String { "string".to_string() }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BenchmarkConfig {
    pub log_file: String,
    #[serde(default = "default_sample_size")]
    pub sample_size: usize,
}

fn default_sample_size() -> usize { 10000 }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PipelineConfig {
    #[serde(default = "default_workers")]
    pub worker_threads: usize,

    #[serde(default = "default_buffer_size")]
    pub ring_buffer_size: usize,

    #[serde(default = "default_buffer_seconds")]
    pub ring_buffer_seconds: u64,

    #[serde(default = "default_fine_window_secs")]
    pub fine_grained_window_secs: u64,

    #[serde(default = "default_coarse_window_secs")]
    pub coarse_grained_window_secs: u64,

    #[serde(default = "default_tdigest_compression")]
    pub tdigest_compression: f64,
}

fn default_workers() -> usize { num_cpus::get() }
fn default_buffer_size() -> usize { 100_000 }
fn default_buffer_seconds() -> u64 { 5 }
fn default_fine_window_secs() -> u64 { 10 }
fn default_coarse_window_secs() -> u64 { 300 }
fn default_tdigest_compression() -> f64 { 100.0 }

impl Default for PipelineConfig {
    fn default() -> Self {
        Self {
            worker_threads: default_workers(),
            ring_buffer_size: default_buffer_size(),
            ring_buffer_seconds: default_buffer_seconds(),
            fine_grained_window_secs: default_fine_window_secs(),
            coarse_grained_window_secs: default_coarse_window_secs(),
            tdigest_compression: default_tdigest_compression(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SourceConfig {
    #[serde(default)]
    pub file_sources: Vec<FileSourceConfig>,

    #[serde(default)]
    pub syslog_sources: Vec<SyslogSourceConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileSourceConfig {
    pub name: String,
    pub glob_pattern: String,
    pub service: String,
    #[serde(default = "default_from_beginning")]
    pub from_beginning: bool,
    #[serde(default = "default_line_buffer_size")]
    pub line_buffer_size: usize,
}

fn default_from_beginning() -> bool { false }
fn default_line_buffer_size() -> usize { 8192 }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyslogSourceConfig {
    pub name: String,
    #[serde(default = "default_syslog_host")]
    pub host: String,
    #[serde(default = "default_syslog_port")]
    pub port: u16,
    pub service: String,
}

fn default_syslog_host() -> String { "0.0.0.0".to_string() }
fn default_syslog_port() -> u16 { 514 }

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SinkConfig {
    #[serde(default)]
    pub kafka: Option<KafkaSinkConfig>,

    #[serde(default)]
    pub minio: Option<MinIOSinkConfig>,

    #[serde(default)]
    pub clickhouse: Option<ClickHouseSinkConfig>,

    #[serde(default)]
    pub alert_channels: AlertChannelsConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClickHouseSinkConfig {
    pub url: String,
    pub user: String,
    #[serde(default)]
    pub password: String,
    pub database: String,
    pub table: String,
    #[serde(default = "default_clickhouse_batch")]
    pub batch_size: usize,
    #[serde(default = "default_clickhouse_flush")]
    pub flush_interval_secs: u64,
    #[serde(default = "default_clickhouse_retries")]
    pub max_retries: u32,
    #[serde(default)]
    pub local_cache_dir: Option<String>,
    #[serde(default = "default_clickhouse_retry_backoff")]
    pub retry_backoff_ms: u64,
}

fn default_clickhouse_batch() -> usize { 1000 }
fn default_clickhouse_flush() -> u64 { 10 }
fn default_clickhouse_retries() -> u32 { 3 }
fn default_clickhouse_retry_backoff() -> u64 { 1000 }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KafkaSinkConfig {
    pub brokers: String,
    pub anomaly_topic: String,
    pub stats_topic: String,
    #[serde(default = "default_kafka_producer_linger")]
    pub producer_linger_ms: u64,
    #[serde(default = "default_kafka_batch_size")]
    pub batch_size: usize,
    #[serde(default)]
    pub compression: String,
}

fn default_kafka_producer_linger() -> u64 { 5 }
fn default_kafka_batch_size() -> usize { 16384 }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MinIOSinkConfig {
    pub endpoint: String,
    pub access_key: String,
    pub secret_key: String,
    pub bucket: String,
    #[serde(default = "default_minio_prefix")]
    pub key_prefix: String,
    #[serde(default = "default_minio_flush_interval")]
    pub flush_interval_secs: u64,
}

fn default_minio_prefix() -> String { "log-stats/".to_string() }
fn default_minio_flush_interval() -> u64 { 60 }

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct AlertChannelsConfig {
    #[serde(default)]
    pub dingtalk: Vec<DingTalkChannelConfig>,

    #[serde(default)]
    pub feishu: Vec<FeishuChannelConfig>,

    #[serde(default)]
    pub email: Vec<EmailChannelConfig>,

    #[serde(default)]
    pub pagerduty: Vec<PagerDutyChannelConfig>,

    #[serde(default)]
    pub sns: Vec<SnsChannelConfig>,

    #[serde(default)]
    pub tencent_sms: Vec<TencentSmsChannelConfig>,

    #[serde(default = "default_dedup_window")]
    pub dedup_window_secs: u64,

    #[serde(default = "default_escalation_minutes")]
    pub escalation_minutes: u64,

    #[serde(default)]
    pub escalation_api_url: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SnsChannelConfig {
    pub name: String,
    pub region: String,
    pub topic_arn: String,
    #[serde(default)]
    pub access_key_id: Option<String>,
    #[serde(default)]
    pub secret_access_key: Option<String>,
    #[serde(default = "default_sns_timeout")]
    pub timeout_ms: u64,
    #[serde(default)]
    pub phone_numbers: Vec<String>,
}

fn default_sns_timeout() -> u64 { 5000 }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TencentSmsChannelConfig {
    pub name: String,
    pub region: String,
    pub secret_id: String,
    pub secret_key: String,
    pub app_id: String,
    pub template_id: String,
    pub sign_name: Option<String>,
    pub phone_numbers: Vec<String>,
    #[serde(default = "default_sms_timeout")]
    pub timeout_ms: u64,
}

fn default_sms_timeout() -> u64 { 10000 }

fn default_dedup_window() -> u64 { 10 }
fn default_escalation_minutes() -> u64 { 5 }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DingTalkChannelConfig {
    pub name: String,
    pub webhook_url: String,
    pub secret: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeishuChannelConfig {
    pub name: String,
    pub webhook_url: String,
    pub secret: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmailChannelConfig {
    pub name: String,
    pub smtp_host: String,
    pub smtp_port: u16,
    pub username: String,
    pub password: String,
    pub from: String,
    pub to: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PagerDutyChannelConfig {
    pub name: String,
    pub routing_key: String,
    pub api_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct RulesConfig {
    #[serde(default)]
    pub threshold_rules: Vec<ThresholdRuleConfig>,

    #[serde(default)]
    pub trend_rules: Vec<TrendRuleConfig>,

    #[serde(default)]
    pub pattern_rules: Vec<PatternRuleConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ThresholdRuleConfig {
    pub id: String,
    pub name: String,
    pub service: Option<String>,
    #[serde(default)]
    pub level: Option<String>,
    pub window_minutes: u64,
    pub threshold_count: u64,
    pub severity: String,
    #[serde(default)]
    pub alert_channels: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrendRuleConfig {
    pub id: String,
    pub name: String,
    pub service: Option<String>,
    pub quantile: String,
    pub growth_percent: f64,
    pub consecutive_windows: u32,
    pub severity: String,
    #[serde(default)]
    pub alert_channels: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PatternRuleConfig {
    pub id: String,
    pub name: String,
    pub service: Option<String>,
    pub patterns: Vec<String>,
    pub case_sensitive: bool,
    pub severity: String,
    #[serde(default)]
    pub alert_channels: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ObservabilityConfig {
    #[serde(default = "default_metrics_host")]
    pub metrics_host: String,
    #[serde(default = "default_metrics_port")]
    pub metrics_port: u16,
    #[serde(default = "default_enable_dashboard")]
    pub enable_dashboard: bool,
}

fn default_metrics_host() -> String { "0.0.0.0".to_string() }
fn default_metrics_port() -> u16 { 9090 }
fn default_enable_dashboard() -> bool { true }

impl Default for ObservabilityConfig {
    fn default() -> Self {
        Self {
            metrics_host: default_metrics_host(),
            metrics_port: default_metrics_port(),
            enable_dashboard: default_enable_dashboard(),
        }
    }
}

pub type ConfigHandle = Arc<RwLock<AppConfig>>;

impl AppConfig {
    pub fn load<P: AsRef<Path>>(path: P) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let content = std::fs::read_to_string(path.as_ref())?;
        let config: AppConfig = toml::from_str(&content)?;
        Ok(config)
    }

    pub fn handle(self) -> ConfigHandle {
        Arc::new(RwLock::new(self))
    }
}

pub struct ConfigManager {
    config_path: PathBuf,
    handle: ConfigHandle,
}

impl ConfigManager {
    pub fn new<P: AsRef<Path>>(path: P) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let config = AppConfig::load(path.as_ref())?;
        Ok(Self {
            config_path: path.as_ref().to_path_buf(),
            handle: config.handle(),
        })
    }

    pub fn handle(&self) -> ConfigHandle {
        self.handle.clone()
    }

    pub async fn reload(&self) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        info!("Reloading configuration from {:?}", self.config_path);
        match AppConfig::load(&self.config_path) {
            Ok(new_config) => {
                let mut w = self.handle.write().await;
                *w = new_config;
                info!("Configuration reloaded successfully");
                Ok(())
            }
            Err(e) => {
                error!("Failed to reload configuration: {}", e);
                warn!("Keeping previous configuration");
                Err(e)
            }
        }
    }
}

pub fn default_config_toml() -> String {
    r#"[pipeline]
worker_threads = 8
ring_buffer_size = 100000
ring_buffer_seconds = 5
fine_grained_window_secs = 10
coarse_grained_window_secs = 300
tdigest_compression = 100.0

[[source.file_sources]]
name = "app-logs"
glob_pattern = "/var/log/app/*.log"
service = "my-service"
from_beginning = false
line_buffer_size = 8192

[[source.syslog_sources]]
name = "syslog-udp"
host = "0.0.0.0"
port = 5514
service = "syslog"

[sink.kafka]
brokers = "localhost:9092"
anomaly_topic = "log-anomalies"
stats_topic = "log-stats-aggregated"
producer_linger_ms = 5
batch_size = 16384
compression = "lz4"

[sink.minio]
endpoint = "http://localhost:9000"
access_key = "minioadmin"
secret_key = "minioadmin"
bucket = "logs"
key_prefix = "log-stats/"
flush_interval_secs = 60

[sink.alert_channels]
dedup_window_secs = 10
escalation_minutes = 5
escalation_api_url = "https://api.example.com/call-alert"

[[sink.alert_channels.dingtalk]]
name = "ops-dingtalk"
webhook_url = "https://oapi.dingtalk.com/robot/send?access_token=xxx"
secret = "SECxxx"

[[sink.alert_channels.feishu]]
name = "ops-feishu"
webhook_url = "https://open.feishu.cn/open-apis/bot/v2/hook/xxx"

[[sink.alert_channels.pagerduty]]
name = "oncall-pd"
routing_key = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
api_url = "https://events.pagerduty.com/v2/enqueue"

[[rules.threshold_rules]]
id = "threshold-error-50"
name = "High error rate"
service = "my-service"
level = "ERROR"
window_minutes = 5
threshold_count = 50
severity = "WARNING"
alert_channels = ["ops-dingtalk", "ops-feishu"]

[[rules.trend_rules]]
id = "trend-latency-p99"
name = "P99 latency surge"
service = "my-service"
quantile = "P99"
growth_percent = 30.0
consecutive_windows = 3
severity = "WARNING"
alert_channels = ["ops-dingtalk"]

[[rules.pattern_rules]]
id = "pattern-oom"
name = "OOM detected"
patterns = ["OutOfMemoryError", "Java heap space"]
case_sensitive = true
severity = "CRITICAL"
alert_channels = ["oncall-pd", "ops-dingtalk"]

[[rules.pattern_rules]]
id = "pattern-connection-reset"
name = "Connection reset storm"
patterns = ["connection reset", "Connection reset by peer"]
case_sensitive = false
severity = "WARNING"
alert_channels = ["ops-feishu"]

[observability]
metrics_host = "0.0.0.0"
metrics_port = 9090
enable_dashboard = true
"#.to_string()
}
