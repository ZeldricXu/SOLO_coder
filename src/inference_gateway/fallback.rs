use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use chrono::{DateTime, Utc};

use crate::inference_gateway::provider::{InferenceRequest, InferenceResponse, LLMProvider, ProviderStats};
use crate::utils::error::{GatewayError, Result};
use crate::utils::metrics::MetricsCollector;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FallbackStrategy {
    None,
    Failover,
    RetryThenFailover,
    DegradedResponse,
}

impl Default for FallbackStrategy {
    fn default() -> Self {
        FallbackStrategy::RetryThenFailover
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CircuitBreakerConfig {
    pub failure_threshold: u32,
    pub success_threshold: u32,
    pub timeout_seconds: u64,
    pub half_open_max_calls: u32,
}

impl Default for CircuitBreakerConfig {
    fn default() -> Self {
        Self {
            failure_threshold: 5,
            success_threshold: 3,
            timeout_seconds: 30,
            half_open_max_calls: 5,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum CircuitBreakerState {
    Closed,
    Open,
    HalfOpen,
}

pub struct CircuitBreaker {
    config: CircuitBreakerConfig,
    state: parking_lot::RwLock<CircuitBreakerState>,
    failure_count: AtomicU64,
    success_count: AtomicU64,
    last_failure_time: parking_lot::RwLock<Option<DateTime<Utc>>>,
    half_open_calls: AtomicU64,
}

impl CircuitBreaker {
    pub fn new(config: CircuitBreakerConfig) -> Self {
        Self {
            config,
            state: parking_lot::RwLock::new(CircuitBreakerState::Closed),
            failure_count: AtomicU64::new(0),
            success_count: AtomicU64::new(0),
            last_failure_time: parking_lot::RwLock::new(None),
            half_open_calls: AtomicU64::new(0),
        }
    }

    pub fn allow_request(&self) -> bool {
        let state = self.state.read().clone();
        match state {
            CircuitBreakerState::Closed => true,
            CircuitBreakerState::Open => {
                if let Some(last_failure) = *self.last_failure_time.read() {
                    if Utc::now() - last_failure > chrono::Duration::seconds(self.config.timeout_seconds as i64) {
                        *self.state.write() = CircuitBreakerState::HalfOpen;
                        self.half_open_calls.store(0, Ordering::Relaxed);
                        return true;
                    }
                }
                false
            }
            CircuitBreakerState::HalfOpen => {
                self.half_open_calls.load(Ordering::Relaxed) < self.config.half_open_max_calls as u64
            }
        }
    }

    pub fn record_success(&self) {
        let state = self.state.read().clone();
        match state {
            CircuitBreakerState::Closed => {
                self.failure_count.store(0, Ordering::Relaxed);
            }
            CircuitBreakerState::HalfOpen => {
                let count = self.success_count.fetch_add(1, Ordering::Relaxed) + 1;
                if count >= self.config.success_threshold as u64 {
                    *self.state.write() = CircuitBreakerState::Closed;
                    self.failure_count.store(0, Ordering::Relaxed);
                    self.success_count.store(0, Ordering::Relaxed);
                }
            }
            _ => {}
        }
    }

    pub fn record_failure(&self) {
        let state = self.state.read().clone();
        match state {
            CircuitBreakerState::Closed => {
                let count = self.failure_count.fetch_add(1, Ordering::Relaxed) + 1;
                if count >= self.config.failure_threshold as u64 {
                    *self.state.write() = CircuitBreakerState::Open;
                    *self.last_failure_time.write() = Some(Utc::now());
                }
            }
            CircuitBreakerState::HalfOpen => {
                *self.state.write() = CircuitBreakerState::Open;
                *self.last_failure_time.write() = Some(Utc::now());
                self.success_count.store(0, Ordering::Relaxed);
            }
            _ => {}
        }
    }

    pub fn state(&self) -> CircuitBreakerState {
        self.state.read().clone()
    }

    pub fn reset(&self) {
        *self.state.write() = CircuitBreakerState::Closed;
        self.failure_count.store(0, Ordering::Relaxed);
        self.success_count.store(0, Ordering::Relaxed);
        *self.last_failure_time.write() = None;
        self.half_open_calls.store(0, Ordering::Relaxed);
    }
}

pub struct FallbackManager {
    strategy: FallbackStrategy,
    max_retries: u32,
    retry_delay_ms: u64,
    circuit_breakers: HashMap<String, CircuitBreaker>,
    metrics: MetricsCollector,
}

impl FallbackManager {
    pub fn new(strategy: FallbackStrategy, metrics: MetricsCollector) -> Self {
        Self {
            strategy,
            max_retries: 3,
            retry_delay_ms: 100,
            circuit_breakers: HashMap::new(),
            metrics,
        }
    }

    pub fn with_max_retries(mut self, max_retries: u32) -> Self {
        self.max_retries = max_retries;
        self
    }

    pub fn with_retry_delay(mut self, delay_ms: u64) -> Self {
        self.retry_delay_ms = delay_ms;
        self
    }

    pub fn register_provider(&mut self, provider_id: String, config: CircuitBreakerConfig) {
        self.circuit_breakers.insert(provider_id, CircuitBreaker::new(config));
    }

    pub fn get_circuit_breaker(&self, provider_id: &str) -> Option<&CircuitBreaker> {
        self.circuit_breakers.get(provider_id)
    }

    pub async fn execute_with_fallback(
        &self,
        providers: &[Arc<dyn LLMProvider>],
        request: InferenceRequest,
        load_balancer_select: impl Fn(&str) -> Option<Arc<dyn LLMProvider>>,
    ) -> Result<InferenceResponse> {
        let mut attempted_providers = std::collections::HashSet::new();
        let mut last_error: Option<GatewayError> = None;

        loop {
            let provider = match self.strategy {
                FallbackStrategy::None => {
                    load_balancer_select(&request.model)
                }
                FallbackStrategy::Failover => {
                    self.select_failover_provider(providers, &request.model, &attempted_providers)
                }
                FallbackStrategy::RetryThenFailover => {
                    load_balancer_select(&request.model)
                }
                FallbackStrategy::DegradedResponse => {
                    load_balancer_select(&request.model)
                }
            };

            let provider = match provider {
                Some(p) => p,
                None => {
                    return match self.strategy {
                        FallbackStrategy::DegradedResponse => {
                            Ok(self.get_degraded_response(&request))
                        }
                        _ => Err(last_error.unwrap_or_else(|| {
                            GatewayError::Provider("No available providers".to_string())
                        })),
                    };
                }
            };

            let provider_id = provider.config().provider_id.clone();
            attempted_providers.insert(provider_id.clone());

            if let Some(cb) = self.circuit_breakers.get(&provider_id) {
                if !cb.allow_request() {
                    tracing::warn!(provider_id = %provider_id, "Circuit breaker is open, skipping");
                    continue;
                }
            }

            let result = self.try_with_retry(provider.clone(), request.clone()).await;

            match result {
                Ok(response) => {
                    if let Some(cb) = self.circuit_breakers.get(&provider_id) {
                        cb.record_success();
                    }
                    self.metrics.increment_counter("fallback_success");
                    return Ok(response);
                }
                Err(e) => {
                    if let Some(cb) = self.circuit_breakers.get(&provider_id) {
                        cb.record_failure();
                    }
                    last_error = Some(e);
                    self.metrics.increment_counter("fallback_failure");

                    match self.strategy {
                        FallbackStrategy::None | FallbackStrategy::DegradedResponse => {
                            if attempted_providers.len() >= providers.len() {
                                return match self.strategy {
                                    FallbackStrategy::DegradedResponse => {
                                        Ok(self.get_degraded_response(&request))
                                    }
                                    _ => Err(last_error.unwrap()),
                                };
                            }
                        }
                        FallbackStrategy::Failover | FallbackStrategy::RetryThenFailover => {
                            if attempted_providers.len() >= providers.len() {
                                return Err(last_error.unwrap());
                            }
                        }
                    }
                }
            }
        }
    }

    async fn try_with_retry(
        &self,
        provider: Arc<dyn LLMProvider>,
        request: InferenceRequest,
    ) -> Result<InferenceResponse> {
        let mut retries = 0;
        loop {
            match provider.complete(request.clone()).await {
                Ok(response) => return Ok(response),
                Err(e) => {
                    retries += 1;
                    if retries > self.max_retries {
                        return Err(e);
                    }
                    tracing::warn!(
                        provider_id = %provider.config().provider_id,
                        retry = retries,
                        error = %e,
                        "Retrying request"
                    );
                    tokio::time::sleep(Duration::from_millis(self.retry_delay_ms * retries as u64)).await;
                }
            }
        }
    }

    fn select_failover_provider(
        &self,
        providers: &[Arc<dyn LLMProvider>],
        model: &str,
        attempted: &std::collections::HashSet<String>,
    ) -> Option<Arc<dyn LLMProvider>> {
        let mut sorted: Vec<_> = providers
            .iter()
            .filter(|p| !attempted.contains(&p.config().provider_id))
            .filter(|p| p.is_available() && p.supports_model(model))
            .collect();

        sorted.sort_by_key(|p| std::cmp::Reverse(p.config().priority));

        sorted.first().map(|p| (*p).clone())
    }

    fn get_degraded_response(&self, request: &InferenceRequest) -> InferenceResponse {
        InferenceResponse {
            response_id: format!("degraded_{}", uuid::Uuid::new_v4().simple()),
            provider_id: "fallback".to_string(),
            model: request.model.clone(),
            content: "I'm currently experiencing high load. Please try again later.".to_string(),
            prompt_tokens: 0,
            completion_tokens: 0,
            total_tokens: 0,
            latency_ms: 0,
            created_at: Utc::now(),
            metadata: HashMap::from([("degraded".to_string(), "true".to_string())]),
        }
    }

    pub fn get_circuit_breaker_states(&self) -> HashMap<String, CircuitBreakerState> {
        self.circuit_breakers
            .iter()
            .map(|(id, cb)| (id.clone(), cb.state()))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::inference_gateway::provider::{MockProvider, ProviderConfig, ProviderType};

    fn create_test_provider(name: &str, error_rate: f32) -> Arc<dyn LLMProvider> {
        let config = ProviderConfig::new(
            ProviderType::OpenAi,
            name.to_string(),
            "https://api.test.com".to_string(),
            "sk-test".to_string(),
        );
        Arc::new(MockProvider::new(config, MetricsCollector::new())
            .with_error_rate(error_rate)
            .with_response_delay(10)) as Arc<dyn LLMProvider>
    }

    #[tokio::test]
    async fn test_circuit_breaker() {
        let config = CircuitBreakerConfig {
            failure_threshold: 3,
            success_threshold: 2,
            timeout_seconds: 1,
            half_open_max_calls: 2,
        };
        let cb = CircuitBreaker::new(config);

        assert_eq!(cb.state(), CircuitBreakerState::Closed);
        assert!(cb.allow_request());

        cb.record_failure();
        cb.record_failure();
        cb.record_failure();

        assert_eq!(cb.state(), CircuitBreakerState::Open);
        assert!(!cb.allow_request());

        tokio::time::sleep(Duration::from_millis(1100)).await;
        assert!(cb.allow_request());
        assert_eq!(cb.state(), CircuitBreakerState::HalfOpen);

        cb.record_success();
        cb.record_success();
        assert_eq!(cb.state(), CircuitBreakerState::Closed);
    }

    #[tokio::test]
    async fn test_fallback_failover() {
        let providers = vec![
            create_test_provider("p1", 1.0),
            create_test_provider("p2", 0.0),
        ];

        let mut fallback = FallbackManager::new(FallbackStrategy::Failover, MetricsCollector::new());
        fallback.register_provider(providers[0].config().provider_id.clone(), CircuitBreakerConfig::default());
        fallback.register_provider(providers[1].config().provider_id.clone(), CircuitBreakerConfig::default());

        let request = InferenceRequest {
            prompt: "test".to_string(),
            ..Default::default()
        };

        let lb = crate::inference_gateway::load_balancer::LoadBalancer::new(
            crate::inference_gateway::load_balancer::LoadBalanceStrategy::RoundRobin
        ).with_providers(providers.clone());

        let select_fn = |model: &str| lb.select_provider(model);

        let response = fallback.execute_with_fallback(&providers, request, select_fn).await.unwrap();
        assert_eq!(response.provider_id, providers[1].config().provider_id);
    }

    #[tokio::test]
    async fn test_degraded_response() {
        let providers = vec![create_test_provider("p1", 1.0)];

        let mut fallback = FallbackManager::new(FallbackStrategy::DegradedResponse, MetricsCollector::new());
        fallback.register_provider(providers[0].config().provider_id.clone(), CircuitBreakerConfig::default());

        let request = InferenceRequest {
            prompt: "test".to_string(),
            ..Default::default()
        };

        let lb = crate::inference_gateway::load_balancer::LoadBalancer::new(
            crate::inference_gateway::load_balancer::LoadBalanceStrategy::RoundRobin
        ).with_providers(providers.clone());

        let select_fn = |model: &str| lb.select_provider(model);

        let response = fallback.execute_with_fallback(&providers, request, select_fn).await.unwrap();
        assert_eq!(response.provider_id, "fallback");
        assert!(response.metadata.contains_key("degraded"));
    }

    #[test]
    fn test_circuit_breaker_reset() {
        let cb = CircuitBreaker::new(CircuitBreakerConfig::default());
        cb.record_failure();
        cb.record_failure();
        cb.record_failure();
        assert_eq!(cb.state(), CircuitBreakerState::Open);

        cb.reset();
        assert_eq!(cb.state(), CircuitBreakerState::Closed);
        assert!(cb.allow_request());
    }
}
