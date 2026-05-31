use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, AtomicBool, Ordering};
use std::sync::Arc;
use uuid::Uuid;
use chrono::{DateTime, Utc};

use crate::utils::error::{GatewayError, Result};
use crate::utils::metrics::MetricsCollector;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum ProviderType {
    OpenAi,
    Anthropic,
    AzureOpenAi,
    HuggingFace,
    LocalModel,
    Custom,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderConfig {
    pub provider_id: String,
    pub provider_type: ProviderType,
    pub name: String,
    pub base_url: String,
    pub api_key: String,
    pub models: Vec<String>,
    pub enabled: bool,
    pub weight: u32,
    pub priority: u32,
    pub timeout_ms: u64,
    pub max_retries: u32,
    pub rate_limit_per_minute: Option<u32>,
    pub max_concurrent_requests: Option<u32>,
}

impl ProviderConfig {
    pub fn new(provider_type: ProviderType, name: String, base_url: String, api_key: String) -> Self {
        Self {
            provider_id: format!("prov_{}", Uuid::new_v4().simple()),
            provider_type,
            name,
            base_url,
            api_key,
            models: Vec::new(),
            enabled: true,
            weight: 100,
            priority: 50,
            timeout_ms: 30000,
            max_retries: 3,
            rate_limit_per_minute: None,
            max_concurrent_requests: None,
        }
    }

    pub fn with_model(mut self, model: String) -> Self {
        self.models.push(model);
        self
    }

    pub fn with_weight(mut self, weight: u32) -> Self {
        self.weight = weight;
        self
    }

    pub fn with_priority(mut self, priority: u32) -> Self {
        self.priority = priority;
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceRequest {
    pub model: String,
    pub prompt: String,
    pub max_tokens: u32,
    pub temperature: f32,
    pub top_p: f32,
    pub frequency_penalty: f32,
    pub presence_penalty: f32,
    pub stop_sequences: Vec<String>,
    pub stream: bool,
    pub metadata: HashMap<String, String>,
}

impl Default for InferenceRequest {
    fn default() -> Self {
        Self {
            model: "gpt-3.5-turbo".to_string(),
            prompt: String::new(),
            max_tokens: 1024,
            temperature: 0.7,
            top_p: 1.0,
            frequency_penalty: 0.0,
            presence_penalty: 0.0,
            stop_sequences: Vec::new(),
            stream: false,
            metadata: HashMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceResponse {
    pub response_id: String,
    pub provider_id: String,
    pub model: String,
    pub content: String,
    pub prompt_tokens: u32,
    pub completion_tokens: u32,
    pub total_tokens: u32,
    pub latency_ms: u64,
    pub created_at: DateTime<Utc>,
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum HealthStatus {
    Healthy,
    Degraded,
    Unhealthy,
    Unknown,
    Recovering,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthCheckResult {
    pub provider_id: String,
    pub status: HealthStatus,
    pub latency_ms: u64,
    pub error_message: Option<String>,
    pub checked_at: DateTime<Utc>,
    pub consecutive_successes: u32,
    pub consecutive_failures: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderStats {
    pub provider_id: String,
    pub total_requests: u64,
    pub success_requests: u64,
    pub failed_requests: u64,
    pub total_tokens: u64,
    pub avg_latency_ms: f64,
    pub p99_latency_ms: f64,
    pub current_load: u64,
    pub last_used: Option<DateTime<Utc>>,
    pub circuit_breaker_open: bool,
    pub health_status: HealthStatus,
    pub last_health_check: Option<DateTime<Utc>>,
    pub consecutive_successes: u32,
    pub consecutive_failures: u32,
}

#[async_trait]
pub trait LLMProvider: Send + Sync {
    async fn complete(&self, request: InferenceRequest) -> Result<InferenceResponse>;
    
    async fn health_check(&self) -> HealthCheckResult;
    
    fn config(&self) -> &ProviderConfig;
    
    fn stats(&self) -> ProviderStats;
    
    fn is_available(&self) -> bool;
    
    fn supports_model(&self, model: &str) -> bool;
    
    fn record_success(&self, latency_ms: u64, tokens: u32);
    
    fn record_failure(&self);
    
    fn update_health_status(&self, status: HealthStatus);
    
    fn get_health_status(&self) -> HealthStatus;
}

pub struct BaseProvider {
    config: ProviderConfig,
    stats: Arc<ProviderStatsInner>,
    metrics: MetricsCollector,
}

struct ProviderStatsInner {
    total_requests: AtomicU64,
    success_requests: AtomicU64,
    failed_requests: AtomicU64,
    total_tokens: AtomicU64,
    current_load: AtomicU64,
    circuit_breaker_open: parking_lot::Mutex<bool>,
    latencies: parking_lot::Mutex<Vec<u64>>,
    health_status: parking_lot::Mutex<HealthStatus>,
    last_health_check: parking_lot::Mutex<Option<DateTime<Utc>>>,
    consecutive_successes: AtomicU64,
    consecutive_failures: AtomicU64,
    last_used: parking_lot::Mutex<Option<DateTime<Utc>>>,
}

impl BaseProvider {
    pub fn new(config: ProviderConfig, metrics: MetricsCollector) -> Self {
        Self {
            config,
            stats: Arc::new(ProviderStatsInner {
                total_requests: AtomicU64::new(0),
                success_requests: AtomicU64::new(0),
                failed_requests: AtomicU64::new(0),
                total_tokens: AtomicU64::new(0),
                current_load: AtomicU64::new(0),
                circuit_breaker_open: parking_lot::Mutex::new(false),
                latencies: parking_lot::Mutex::new(Vec::new()),
                health_status: parking_lot::Mutex::new(HealthStatus::Unknown),
                last_health_check: parking_lot::Mutex::new(None),
                consecutive_successes: AtomicU64::new(0),
                consecutive_failures: AtomicU64::new(0),
                last_used: parking_lot::Mutex::new(None),
            }),
            metrics,
        }
    }

    pub fn config(&self) -> &ProviderConfig {
        &self.config
    }

    pub fn is_available(&self) -> bool {
        let health_ok = matches!(*self.stats.health_status.lock(), 
            HealthStatus::Healthy | HealthStatus::Degraded | HealthStatus::Recovering);
        self.config.enabled && !*self.stats.circuit_breaker_open.lock() && health_ok
    }

    pub fn update_health_status(&self, status: HealthStatus) {
        *self.stats.health_status.lock() = status;
    }

    pub fn get_health_status(&self) -> HealthStatus {
        self.stats.health_status.lock().clone()
    }

    pub fn supports_model(&self, model: &str) -> bool {
        self.config.models.contains(&model.to_string()) || self.config.models.is_empty()
    }

    pub fn record_success(&self, latency_ms: u64, tokens: u32) {
        self.stats.total_requests.fetch_add(1, Ordering::Relaxed);
        self.stats.success_requests.fetch_add(1, Ordering::Relaxed);
        self.stats.total_tokens.fetch_add(tokens as u64, Ordering::Relaxed);
        self.stats.current_load.fetch_sub(1, Ordering::Relaxed);
        self.stats.consecutive_successes.fetch_add(1, Ordering::Relaxed);
        self.stats.consecutive_failures.store(0, Ordering::Relaxed);
        *self.stats.last_used.lock() = Some(Utc::now());
        
        let mut latencies = self.stats.latencies.lock();
        latencies.push(latency_ms);
        if latencies.len() > 1000 {
            latencies.drain(0..latencies.len() - 1000);
        }
    }

    pub fn record_failure(&self) {
        self.stats.total_requests.fetch_add(1, Ordering::Relaxed);
        self.stats.failed_requests.fetch_add(1, Ordering::Relaxed);
        self.stats.current_load.fetch_sub(1, Ordering::Relaxed);
        self.stats.consecutive_failures.fetch_add(1, Ordering::Relaxed);
        self.stats.consecutive_successes.store(0, Ordering::Relaxed);
        *self.stats.last_used.lock() = Some(Utc::now());
    }

    pub fn stats(&self) -> ProviderStats {
        let latencies = self.stats.latencies.lock();
        let avg_latency_ms = if !latencies.is_empty() {
            latencies.iter().sum::<u64>() as f64 / latencies.len() as f64
        } else {
            0.0
        };

        let p99_latency_ms = if latencies.len() >= 100 {
            let mut sorted = latencies.clone();
            sorted.sort();
            let idx = (sorted.len() as f64 * 0.99) as usize;
            sorted[idx] as f64
        } else {
            avg_latency_ms
        };

        ProviderStats {
            provider_id: self.config.provider_id.clone(),
            total_requests: self.stats.total_requests.load(Ordering::Relaxed),
            success_requests: self.stats.success_requests.load(Ordering::Relaxed),
            failed_requests: self.stats.failed_requests.load(Ordering::Relaxed),
            total_tokens: self.stats.total_tokens.load(Ordering::Relaxed),
            avg_latency_ms,
            p99_latency_ms,
            current_load: self.stats.current_load.load(Ordering::Relaxed),
            last_used: self.stats.last_used.lock().clone(),
            circuit_breaker_open: *self.stats.circuit_breaker_open.lock(),
            health_status: self.stats.health_status.lock().clone(),
            last_health_check: self.stats.last_health_check.lock().clone(),
            consecutive_successes: self.stats.consecutive_successes.load(Ordering::Relaxed) as u32,
            consecutive_failures: self.stats.consecutive_failures.load(Ordering::Relaxed) as u32,
        }
    }

    pub fn record_health_check(&self, result: &HealthCheckResult) {
        *self.stats.last_health_check.lock() = Some(result.checked_at);
    }

    pub fn begin_request(&self) {
        self.stats.current_load.fetch_add(1, Ordering::Relaxed);
    }

    pub fn check_rate_limit(&self) -> bool {
        true
    }
}

pub struct MockProvider {
    base: BaseProvider,
    response_delay_ms: u64,
    error_rate: f32,
}

impl MockProvider {
    pub fn new(config: ProviderConfig, metrics: MetricsCollector) -> Self {
        Self {
            base: BaseProvider::new(config, metrics),
            response_delay_ms: 100,
            error_rate: 0.0,
        }
    }

    pub fn with_response_delay(mut self, delay_ms: u64) -> Self {
        self.response_delay_ms = delay_ms;
        self
    }

    pub fn with_error_rate(mut self, error_rate: f32) -> Self {
        self.error_rate = error_rate;
        self
    }

    fn generate_mock_response(&self, prompt: &str) -> String {
        if prompt.contains("hello") || prompt.contains("你好") {
            "Hello! How can I assist you today?".to_string()
        } else if prompt.contains("?") {
            "That's an interesting question. Let me think about it carefully and provide a thoughtful response.".to_string()
        } else {
            format!("Thank you for your message about \"{}\". I understand and am processing your request.", 
                prompt.chars().take(50).collect::<String>())
        }
    }
}

#[async_trait]
impl LLMProvider for MockProvider {
    async fn complete(&self, request: InferenceRequest) -> Result<InferenceResponse> {
        self.base.begin_request();
        let start = std::time::Instant::now();

        if self.response_delay_ms > 0 {
            tokio::time::sleep(tokio::time::Duration::from_millis(self.response_delay_ms)).await;
        }

        use rand::Rng;
        let mut rng = rand::thread_rng();
        if rng.gen::<f32>() < self.error_rate {
            self.base.record_failure();
            return Err(GatewayError::Provider("Mock provider error".to_string()));
        }

        let content = self.generate_mock_response(&request.prompt);
        let prompt_tokens = (request.prompt.len() / 4) as u32;
        let completion_tokens = (content.len() / 4) as u32;
        let total_tokens = prompt_tokens + completion_tokens;
        let latency_ms = start.elapsed().as_millis() as u64;

        self.base.record_success(latency_ms, total_tokens);

        Ok(InferenceResponse {
            response_id: format!("resp_{}", Uuid::new_v4().simple()),
            provider_id: self.base.config().provider_id.clone(),
            model: request.model,
            content,
            prompt_tokens,
            completion_tokens,
            total_tokens,
            latency_ms,
            created_at: Utc::now(),
            metadata: HashMap::new(),
        })
    }

    async fn health_check(&self) -> HealthCheckResult {
        let start = std::time::Instant::now();
        let mut status = HealthStatus::Healthy;
        let mut error_message = None;

        if self.error_rate > 0.5 {
            status = HealthStatus::Unhealthy;
            error_message = Some(format!("High error rate: {}", self.error_rate));
        } else if self.error_rate > 0.1 {
            status = HealthStatus::Degraded;
            error_message = Some(format!("Elevated error rate: {}", self.error_rate));
        }

        let latency_ms = start.elapsed().as_millis() as u64;
        let stats = self.base.stats();

        let result = HealthCheckResult {
            provider_id: self.base.config().provider_id.clone(),
            status: status.clone(),
            latency_ms,
            error_message,
            checked_at: Utc::now(),
            consecutive_successes: stats.consecutive_successes,
            consecutive_failures: stats.consecutive_failures,
        };

        self.base.update_health_status(status);
        self.base.record_health_check(&result);
        result
    }

    fn config(&self) -> &ProviderConfig {
        self.base.config()
    }

    fn stats(&self) -> ProviderStats {
        self.base.stats()
    }

    fn is_available(&self) -> bool {
        self.base.is_available()
    }

    fn supports_model(&self, model: &str) -> bool {
        self.base.supports_model(model)
    }

    fn record_success(&self, latency_ms: u64, tokens: u32) {
        self.base.record_success(latency_ms, tokens)
    }

    fn record_failure(&self) {
        self.base.record_failure()
    }

    fn update_health_status(&self, status: HealthStatus) {
        self.base.update_health_status(status);
    }

    fn get_health_status(&self) -> HealthStatus {
        self.base.get_health_status()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn create_test_provider() -> MockProvider {
        let config = ProviderConfig::new(
            ProviderType::OpenAi,
            "test-openai".to_string(),
            "https://api.openai.com/v1".to_string(),
            "sk-test".to_string(),
        ).with_model("gpt-3.5-turbo".to_string());
        
        MockProvider::new(config, MetricsCollector::new())
    }

    #[tokio::test]
    async fn test_provider_completion() {
        let provider = create_test_provider();
        
        let request = InferenceRequest {
            prompt: "Hello, world!".to_string(),
            ..Default::default()
        };

        let response = provider.complete(request).await.unwrap();
        
        assert!(response.response_id.starts_with("resp_"));
        assert!(!response.content.is_empty());
        assert!(response.total_tokens > 0);
        assert!(response.latency_ms > 0);
    }

    #[tokio::test]
    async fn test_provider_error_rate() {
        let config = ProviderConfig::new(
            ProviderType::OpenAi,
            "failing".to_string(),
            "https://api.test.com".to_string(),
            "sk-test".to_string(),
        );
        let provider = MockProvider::new(config, MetricsCollector::new())
            .with_error_rate(1.0);

        let request = InferenceRequest {
            prompt: "test".to_string(),
            ..Default::default()
        };

        let result = provider.complete(request).await;
        assert!(result.is_err());

        let stats = provider.stats();
        assert_eq!(stats.failed_requests, 1);
        assert_eq!(stats.success_requests, 0);
    }

    #[test]
    fn test_provider_config() {
        let provider = create_test_provider();
        assert_eq!(provider.config().provider_type, ProviderType::OpenAi);
        assert_eq!(provider.config().name, "test-openai");
        assert!(provider.supports_model("gpt-3.5-turbo"));
        assert!(!provider.supports_model("gpt-4"));
    }

    #[tokio::test]
    async fn test_provider_stats() {
        let provider = create_test_provider();
        
        for i in 0..10 {
            let request = InferenceRequest {
                prompt: format!("Request {}", i),
                ..Default::default()
            };
            provider.complete(request).await.unwrap();
        }

        let stats = provider.stats();
        assert_eq!(stats.total_requests, 10);
        assert_eq!(stats.success_requests, 10);
        assert_eq!(stats.failed_requests, 0);
        assert!(stats.total_tokens > 0);
        assert!(stats.avg_latency_ms > 0.0);
    }
}
