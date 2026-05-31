use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum LoadBalancingStrategy {
    RoundRobin,
    LeastConnections,
    WeightedRoundRobin,
    Random,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FallbackConfig {
    pub enabled: bool,
    pub timeout_ms: u64,
    pub max_retries: u32,
    pub fallback_model_id: Option<String>,
    pub default_response: Option<serde_json::Value>,
}

impl Default for FallbackConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            timeout_ms: 30000,
            max_retries: 2,
            fallback_model_id: None,
            default_response: None,
        }
    }
}

#[derive(Debug)]
pub struct CircuitBreaker {
    pub name: String,
    pub failure_threshold: u32,
    pub reset_timeout_ms: u64,
    pub failures: AtomicU64,
    pub state: AtomicBool,
    pub last_failure_time: parking_lot::Mutex<Option<DateTime<Utc>>>,
}

impl CircuitBreaker {
    pub fn new(name: impl Into<String>, failure_threshold: u32, reset_timeout_ms: u64) -> Self {
        Self {
            name: name.into(),
            failure_threshold,
            reset_timeout_ms,
            failures: AtomicU64::new(0),
            state: AtomicBool::new(false),
            last_failure_time: parking_lot::Mutex::new(None),
        }
    }

    pub fn is_open(&self) -> bool {
        if self.state.load(Ordering::Relaxed) {
            if let Some(last_failure) = *self.last_failure_time.lock() {
                let elapsed = Utc::now()
                    .signed_duration_since(last_failure)
                    .num_milliseconds() as u64;
                if elapsed >= self.reset_timeout_ms {
                    self.reset();
                    return false;
                }
            }
            true
        } else {
            false
        }
    }

    pub fn record_failure(&self) {
        let failures = self.failures.fetch_add(1, Ordering::Relaxed) + 1;
        *self.last_failure_time.lock() = Some(Utc::now());
        if failures >= self.failure_threshold as u64 {
            self.state.store(true, Ordering::Relaxed);
        }
    }

    pub fn record_success(&self) {
        self.failures.store(0, Ordering::Relaxed);
        self.state.store(false, Ordering::Relaxed);
    }

    pub fn reset(&self) {
        self.failures.store(0, Ordering::Relaxed);
        self.state.store(false, Ordering::Relaxed);
        *self.last_failure_time.lock() = None;
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelProviderConfig {
    pub provider_id: String,
    pub name: String,
    pub provider_type: String,
    pub base_url: String,
    pub api_key: String,
    pub model_id: String,
    pub weight: u32,
    pub timeout_ms: u64,
    pub max_concurrent: u32,
    pub enabled: bool,
}

#[derive(Debug)]
pub struct ModelProvider {
    pub config: ModelProviderConfig,
    pub circuit_breaker: CircuitBreaker,
    pub active_connections: AtomicU64,
    pub total_requests: AtomicU64,
    pub failed_requests: AtomicU64,
}

impl ModelProvider {
    pub fn new(config: ModelProviderConfig) -> Self {
        let cb = CircuitBreaker::new(
            format!("cb_{}", config.provider_id),
            5,
            60000,
        );
        Self {
            config,
            circuit_breaker: cb,
            active_connections: AtomicU64::new(0),
            total_requests: AtomicU64::new(0),
            failed_requests: AtomicU64::new(0),
        }
    }

    pub fn acquire_connection(&self) -> bool {
        let current = self.active_connections.load(Ordering::Relaxed);
        if current >= self.config.max_concurrent as u64 {
            return false;
        }
        self.active_connections.fetch_add(1, Ordering::Relaxed);
        true
    }

    pub fn release_connection(&self) {
        self.active_connections.fetch_sub(1, Ordering::Relaxed);
    }

    pub fn is_available(&self) -> bool {
        self.config.enabled
            && !self.circuit_breaker.is_open()
            && self.active_connections.load(Ordering::Relaxed) < self.config.max_concurrent as u64
    }

    pub fn success_rate(&self) -> f64 {
        let total = self.total_requests.load(Ordering::Relaxed);
        if total == 0 {
            return 1.0;
        }
        let failed = self.failed_requests.load(Ordering::Relaxed);
        (total - failed) as f64 / total as f64
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceRequest {
    pub request_id: String,
    pub model_id: String,
    pub model_version: Option<u32>,
    pub prompt: String,
    pub parameters: serde_json::Value,
    pub priority: u8,
    pub trace_id: Option<String>,
}

impl InferenceRequest {
    pub fn new(model_id: impl Into<String>, prompt: impl Into<String>) -> Self {
        Self {
            request_id: format!("req_{}", Uuid::new_v4().simple()),
            model_id: model_id.into(),
            model_version: None,
            prompt: prompt.into(),
            parameters: serde_json::json!({}),
            priority: 5,
            trace_id: None,
        }
    }

    pub fn with_version(mut self, version: u32) -> Self {
        self.model_version = Some(version);
        self
    }

    pub fn with_params(mut self, params: serde_json::Value) -> Self {
        self.parameters = params;
        self
    }

    pub fn with_priority(mut self, priority: u8) -> Self {
        self.priority = priority.clamp(1, 10);
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceResponse {
    pub request_id: String,
    pub provider_id: String,
    pub model_id: String,
    pub model_version: Option<u32>,
    pub output: serde_json::Value,
    pub latency_ms: u64,
    pub token_usage: Option<TokenUsage>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TokenUsage {
    pub prompt_tokens: u32,
    pub completion_tokens: u32,
    pub total_tokens: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderRegistrationRequest {
    pub name: String,
    pub provider_type: String,
    pub base_url: String,
    pub api_key: String,
    pub model_id: String,
    pub weight: Option<u32>,
    pub timeout_ms: Option<u64>,
    pub max_concurrent: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GatewayConfig {
    pub default_strategy: LoadBalancingStrategy,
    pub fallback: FallbackConfig,
    pub circuit_breaker_threshold: u32,
    pub circuit_breaker_reset_ms: u64,
}

impl Default for GatewayConfig {
    fn default() -> Self {
        Self {
            default_strategy: LoadBalancingStrategy::RoundRobin,
            fallback: FallbackConfig::default(),
            circuit_breaker_threshold: 5,
            circuit_breaker_reset_ms: 60000,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_circuit_breaker() {
        let cb = CircuitBreaker::new("test", 3, 1000);
        assert!(!cb.is_open());
        
        cb.record_failure();
        cb.record_failure();
        assert!(!cb.is_open());
        
        cb.record_failure();
        assert!(cb.is_open());
        
        cb.record_success();
        assert!(!cb.is_open());
    }

    #[test]
    fn test_model_provider_availability() {
        let config = ModelProviderConfig {
            provider_id: "test_provider".to_string(),
            name: "Test".to_string(),
            provider_type: "openai".to_string(),
            base_url: "http://localhost".to_string(),
            api_key: "key".to_string(),
            model_id: "gpt-4".to_string(),
            weight: 100,
            timeout_ms: 30000,
            max_concurrent: 2,
            enabled: true,
        };

        let provider = ModelProvider::new(config);
        assert!(provider.is_available());
        
        assert!(provider.acquire_connection());
        assert!(provider.acquire_connection());
        assert!(!provider.acquire_connection());
        
        provider.release_connection();
        assert!(provider.acquire_connection());
    }

    #[test]
    fn test_inference_request_creation() {
        let req = InferenceRequest::new("model1", "Hello, world!")
            .with_version(2)
            .with_priority(10)
            .with_params(serde_json::json!({"temperature": 0.7}));
        
        assert!(req.request_id.starts_with("req_"));
        assert_eq!(req.model_id, "model1");
        assert_eq!(req.model_version, Some(2));
        assert_eq!(req.priority, 10);
    }

    #[test]
    fn test_priority_clamping() {
        let req = InferenceRequest::new("model1", "test").with_priority(100);
        assert_eq!(req.priority, 10);
        
        let req = InferenceRequest::new("model1", "test").with_priority(0);
        assert_eq!(req.priority, 1);
    }

    #[test]
    fn test_success_rate() {
        let config = ModelProviderConfig {
            provider_id: "test".to_string(),
            name: "Test".to_string(),
            provider_type: "test".to_string(),
            base_url: "http://localhost".to_string(),
            api_key: "key".to_string(),
            model_id: "test".to_string(),
            weight: 100,
            timeout_ms: 30000,
            max_concurrent: 10,
            enabled: true,
        };
        
        let provider = ModelProvider::new(config);
        assert_eq!(provider.success_rate(), 1.0);
        
        provider.total_requests.store(10, Ordering::Relaxed);
        provider.failed_requests.store(2, Ordering::Relaxed);
        assert_eq!(provider.success_rate(), 0.8);
    }
}
