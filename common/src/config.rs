use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::path::Path;

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct LogTailConfig {
    pub path: String,
    pub path_pattern: String,
    pub service_name: String,
    #[serde(default = "default_multiline")]
    pub multiline: bool,
    #[serde(default)]
    pub multiline_pattern: Option<String>,
    #[serde(default = "default_encoding")]
    pub encoding: String,
    #[serde(default)]
    pub start_from_beginning: bool,
    #[serde(default)]
    pub tags: Vec<String>,
}

fn default_multiline() -> bool {
    false
}

fn default_encoding() -> String {
    "utf-8".to_string()
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct KafkaConfig {
    pub brokers: Vec<String>,
    pub topic: String,
    #[serde(default = "default_kafka_group_id")]
    pub group_id: String,
    #[serde(default = "default_kafka_partitions")]
    pub partitions: i32,
}

fn default_kafka_group_id() -> String {
    "log-pipeline".to_string()
}

fn default_kafka_partitions() -> i32 {
    12
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ElasticsearchConfig {
    pub urls: Vec<String>,
    #[serde(default)]
    pub username: Option<String>,
    #[serde(default)]
    pub password: Option<String>,
    #[serde(default = "default_es_index")]
    pub index_prefix: String,
    #[serde(default = "default_es_batch_size")]
    pub batch_size: usize,
    #[serde(default = "default_es_flush_interval")]
    pub flush_interval_ms: u64,
}

fn default_es_index() -> String {
    "logs".to_string()
}

fn default_es_batch_size() -> usize {
    1000
}

fn default_es_flush_interval() -> u64 {
    5000
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct LLMConfig {
    pub api_url: String,
    pub api_key: Option<String>,
    #[serde(default = "default_llm_model")]
    pub model: String,
    #[serde(default = "default_llm_timeout")]
    pub timeout_secs: u64,
}

fn default_llm_model() -> String {
    "gpt-4".to_string()
}

fn default_llm_timeout() -> u64 {
    30
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AlertingConfig {
    #[serde(default)]
    pub dingtalk_webhook: Option<String>,
    #[serde(default = "default_alert_dedup_window")]
    pub dedup_window_secs: u64,
    pub llm_root_cause: Option<LLMConfig>,
}

fn default_alert_dedup_window() -> u64 {
    300
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct MetricsConfig {
    #[serde(default = "default_metrics_host")]
    pub host: String,
    #[serde(default = "default_metrics_port")]
    pub port: u16,
    #[serde(default = "default_metrics_path")]
    pub path: String,
}

fn default_metrics_host() -> String {
    "0.0.0.0".to_string()
}

fn default_metrics_port() -> u16 {
    9090
}

fn default_metrics_path() -> String {
    "/metrics".to_string()
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AgentKubernetesConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_node_name")]
    pub node_name: String,
    #[serde(default = "default_pod_log_dir")]
    pub pod_log_dir: String,
    #[serde(default = "default_k8s_api_enabled")]
    pub api_enrichment: bool,
}

fn default_node_name() -> String {
    std::env::var("NODE_NAME").unwrap_or_else(|_| "unknown".to_string())
}

fn default_pod_log_dir() -> String {
    "/var/log/pods".to_string()
}

fn default_k8s_api_enabled() -> bool {
    true
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct AgentConfig {
    #[serde(default)]
    pub hostname: Option<String>,
    #[serde(default = "default_agent_downstream")]
    pub downstream_url: String,
    #[serde(default = "default_agent_batch_size")]
    pub batch_size: usize,
    #[serde(default = "default_agent_flush_interval")]
    pub flush_interval_ms: u64,
    #[serde(default = "default_agent_buffer_size")]
    pub channel_buffer_size: usize,
    #[serde(default)]
    pub kafka: Option<KafkaConfig>,
    #[serde(default)]
    pub files: Vec<LogTailConfig>,
    #[serde(default)]
    pub kubernetes: Option<AgentKubernetesConfig>,
    pub metrics: MetricsConfig,
}

fn default_agent_downstream() -> String {
    "http://localhost:8080/api/v1/logs".to_string()
}

fn default_agent_batch_size() -> usize {
    100
}

fn default_agent_flush_interval() -> u64 {
    1000
}

fn default_agent_buffer_size() -> usize {
    10000
}

impl AgentConfig {
    pub fn get_hostname(&self) -> String {
        self.hostname
            .clone()
            .or_else(|| hostname::get().ok().and_then(|h| h.into_string().ok()))
            .unwrap_or_else(|| "unknown".to_string())
    }

    pub fn is_kubernetes_enabled(&self) -> bool {
        self.kubernetes.as_ref().map_or(false, |k| k.enabled)
    }
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ServerConfig {
    #[serde(default = "default_server_host")]
    pub host: String,
    #[serde(default = "default_server_port")]
    pub port: u16,
    #[serde(default = "default_server_channel_capacity")]
    pub channel_capacity: usize,
    pub kafka: KafkaConfig,
    pub elasticsearch: ElasticsearchConfig,
    pub alerting: AlertingConfig,
    pub metrics: MetricsConfig,
    #[serde(default = "default_sqlite_path")]
    pub sqlite_path: String,
    #[serde(default = "default_compaction_enabled")]
    pub compaction_enabled: bool,
}

fn default_server_host() -> String {
    "0.0.0.0".to_string()
}

fn default_server_port() -> u16 {
    8080
}

fn default_server_channel_capacity() -> usize {
    1000
}

fn default_sqlite_path() -> String {
    "./data/pipeline.db".to_string()
}

fn default_compaction_enabled() -> bool {
    true
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct PipelineConfig {
    pub agent: AgentConfig,
    pub server: ServerConfig,
}

impl PipelineConfig {
    pub fn load(config_path: &str, env: &str) -> Result<Self> {
        use figment::{
            providers::{Env, Format, Toml},
            Figment,
        };

        let base_path = Path::new(config_path);
        let env_file = base_path.with_file_name(format!("{}.toml", env));
        let default_file = base_path.with_file_name("default.toml");

        let mut figment = Figment::new();

        if default_file.exists() {
            figment = figment.merge(Toml::file(default_file));
        }

        if env_file.exists() {
            figment = figment.merge(Toml::file(env_file));
        }

        figment = figment.merge(
            Env::prefixed("PIPELINE_").split("_").lowercase(false));

        let config: PipelineConfig = figment.extract()
            .with_context(|| format!("Failed to load configuration for environment: {}", env))?;

        Ok(config)
    }

    pub fn load_agent(config_path: &str, env: &str) -> Result<AgentConfig> {
        let full_config = Self::load(config_path, env)?;
        Ok(full_config.agent)
    }

    pub fn load_server(config_path: &str, env: &str) -> Result<ServerConfig> {
        let full_config = Self::load(config_path, env)?;
        Ok(full_config.server)
    }
}
