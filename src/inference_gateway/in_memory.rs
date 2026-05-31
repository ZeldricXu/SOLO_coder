use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

use async_trait::async_trait;
use chrono::Utc;
use dashmap::DashMap;
use rand::seq::SliceRandom;
use tokio::sync::Mutex;
use uuid::Uuid;

use crate::models::{ModelGuardError, Result};
use super::domain::{
    InferenceRequest, InferenceResponse, LoadBalancingStrategy, ModelProvider,
    ModelProviderConfig, ProviderRegistrationRequest, TokenUsage,
};
use super::ports::{
    FallbackHandler, InferenceExecutor, LoadBalancer, MetricsRecorder, ProviderRepository,
    RequestQueue,
};

#[derive(Clone)]
pub struct InMemoryProviderRepository {
    providers: Arc<DashMap<String, Arc<ModelProvider>>>,
    model_to_providers: Arc<DashMap<String, Vec<String>>>,
}

impl InMemoryProviderRepository {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            providers: Arc::new(DashMap::new()),
            model_to_providers: Arc::new(DashMap::new()),
        })
    }
}

#[async_trait]
impl ProviderRepository for InMemoryProviderRepository {
    async fn register_provider(&self, request: ProviderRegistrationRequest) -> Result<ModelProviderConfig> {
        let provider_id = format!("prov_{}", Uuid::new_v4().simple());
        let config = ModelProviderConfig {
            provider_id: provider_id.clone(),
            name: request.name,
            provider_type: request.provider_type,
            base_url: request.base_url,
            api_key: request.api_key,
            model_id: request.model_id.clone(),
            weight: request.weight.unwrap_or(100),
            timeout_ms: request.timeout_ms.unwrap_or(30000),
            max_concurrent: request.max_concurrent.unwrap_or(10),
            enabled: true,
        };

        let provider = Arc::new(ModelProvider::new(config.clone()));
        self.providers.insert(provider_id.clone(), provider);
        
        self.model_to_providers
            .entry(request.model_id)
            .or_insert_with(Vec::new)
            .push(provider_id);

        Ok(config)
    }

    async fn get_provider(&self, provider_id: &str) -> Result<Arc<ModelProvider>> {
        self.providers
            .get(provider_id)
            .map(|r| r.value().clone())
            .ok_or_else(|| ModelGuardError::NotFound(format!("Provider not found: {}", provider_id)))
    }

    async fn get_providers_for_model(&self, model_id: &str) -> Vec<Arc<ModelProvider>> {
        self.model_to_providers
            .get(model_id)
            .map(|provider_ids| {
                provider_ids
                    .iter()
                    .filter_map(|id| self.providers.get(id).map(|r| r.value().clone()))
                    .filter(|p| p.is_available())
                    .collect()
            })
            .unwrap_or_default()
    }

    async fn list_providers(&self) -> Vec<Arc<ModelProvider>> {
        self.providers.iter().map(|r| r.value().clone()).collect()
    }

    async fn update_provider(&self, provider_id: &str, config: ModelProviderConfig) -> Result<ModelProviderConfig> {
        if !self.providers.contains_key(provider_id) {
            return Err(ModelGuardError::NotFound(format!("Provider not found: {}", provider_id)));
        }
        let provider = Arc::new(ModelProvider::new(config.clone()));
        self.providers.insert(provider_id.to_string(), provider);
        Ok(config)
    }

    async fn remove_provider(&self, provider_id: &str) -> Result<()> {
        if let Some((_, provider)) = self.providers.remove(provider_id) {
            if let Some(mut provider_ids) = self.model_to_providers.get_mut(&provider.config.model_id) {
                provider_ids.retain(|id| id != provider_id);
            }
        }
        Ok(())
    }

    async fn enable_provider(&self, provider_id: &str) -> Result<()> {
        self.providers
            .get_mut(provider_id)
            .map(|mut r| {
                let config = ModelProviderConfig {
                    enabled: true,
                    ..r.config.clone()
                };
                *r = Arc::new(ModelProvider::new(config));
            })
            .ok_or_else(|| ModelGuardError::NotFound(format!("Provider not found: {}", provider_id)))
    }

    async fn disable_provider(&self, provider_id: &str) -> Result<()> {
        self.providers
            .get_mut(provider_id)
            .map(|mut r| {
                let config = ModelProviderConfig {
                    enabled: false,
                    ..r.config.clone()
                };
                *r = Arc::new(ModelProvider::new(config));
            })
            .ok_or_else(|| ModelGuardError::NotFound(format!("Provider not found: {}", provider_id)))
    }
}

pub struct RoundRobinLoadBalancer {
    counter: AtomicU64,
}

impl RoundRobinLoadBalancer {
    pub fn new() -> Self {
        Self {
            counter: AtomicU64::new(0),
        }
    }
}

#[async_trait]
impl LoadBalancer for RoundRobinLoadBalancer {
    fn strategy(&self) -> LoadBalancingStrategy {
        LoadBalancingStrategy::RoundRobin
    }

    fn select_provider<'a>(&self, providers: &'a [Arc<ModelProvider>]) -> Option<&'a Arc<ModelProvider>> {
        if providers.is_empty() {
            return None;
        }
        let idx = (self.counter.fetch_add(1, Ordering::Relaxed) as usize) % providers.len();
        Some(&providers[idx])
    }

    fn record_usage(&self, _provider_id: &str) {}
}

pub struct LeastConnectionsLoadBalancer;

#[async_trait]
impl LoadBalancer for LeastConnectionsLoadBalancer {
    fn strategy(&self) -> LoadBalancingStrategy {
        LoadBalancingStrategy::LeastConnections
    }

    fn select_provider<'a>(&self, providers: &'a [Arc<ModelProvider>]) -> Option<&'a Arc<ModelProvider>> {
        providers
            .iter()
            .min_by_key(|p| p.active_connections.load(Ordering::Relaxed))
    }

    fn record_usage(&self, _provider_id: &str) {}
}

pub struct WeightedRoundRobinLoadBalancer;

#[async_trait]
impl LoadBalancer for WeightedRoundRobinLoadBalancer {
    fn strategy(&self) -> LoadBalancingStrategy {
        LoadBalancingStrategy::WeightedRoundRobin
    }

    fn select_provider<'a>(&self, providers: &'a [Arc<ModelProvider>]) -> Option<&'a Arc<ModelProvider>> {
        if providers.is_empty() {
            return None;
        }
        let total_weight: u32 = providers.iter().map(|p| p.config.weight).sum();
        if total_weight == 0 {
            return Some(&providers[0]);
        }
        
        let mut rng = rand::thread_rng();
        providers
            .choose_weighted(&mut rng, |p| p.config.weight)
            .ok()
    }

    fn record_usage(&self, _provider_id: &str) {}
}

pub struct RandomLoadBalancer;

#[async_trait]
impl LoadBalancer for RandomLoadBalancer {
    fn strategy(&self) -> LoadBalancingStrategy {
        LoadBalancingStrategy::Random
    }

    fn select_provider<'a>(&self, providers: &'a [Arc<ModelProvider>]) -> Option<&'a Arc<ModelProvider>> {
        if providers.is_empty() {
            return None;
        }
        let mut rng = rand::thread_rng();
        providers.choose(&mut rng)
    }

    fn record_usage(&self, _provider_id: &str) {}
}

#[derive(Clone)]
pub struct MockInferenceExecutor;

#[async_trait]
impl InferenceExecutor for MockInferenceExecutor {
    async fn execute(
        &self,
        provider: &ModelProvider,
        request: &InferenceRequest,
    ) -> Result<InferenceResponse> {
        let start = Instant::now();
        
        if !provider.acquire_connection() {
            return Err(ModelGuardError::RateLimitExceeded(
                "Provider capacity exceeded".to_string(),
            ));
        }
        
        provider.total_requests.fetch_add(1, Ordering::Relaxed);
        
        let prompt_tokens = request.prompt.split_whitespace().count() as u32;
        let completion_tokens = 50;
        
        let response = InferenceResponse {
            request_id: request.request_id.clone(),
            provider_id: provider.config.provider_id.clone(),
            model_id: request.model_id.clone(),
            model_version: request.model_version,
            output: serde_json::json!({
                "choices": [
                    {
                        "text": format!("Mock response to: {}", request.prompt),
                        "finish_reason": "stop"
                    }
                ]
            }),
            latency_ms: start.elapsed().as_millis() as u64,
            token_usage: Some(TokenUsage {
                prompt_tokens,
                completion_tokens,
                total_tokens: prompt_tokens + completion_tokens,
            }),
            created_at: Utc::now(),
        };
        
        provider.release_connection();
        provider.circuit_breaker.record_success();
        
        Ok(response)
    }
}

#[derive(Clone)]
pub struct DefaultFallbackHandler {
    default_response: Option<serde_json::Value>,
}

impl DefaultFallbackHandler {
    pub fn new(default_response: Option<serde_json::Value>) -> Self {
        Self { default_response }
    }
}

#[async_trait]
impl FallbackHandler for DefaultFallbackHandler {
    async fn handle_fallback(
        &self,
        request: &InferenceRequest,
        original_error: &crate::models::ModelGuardError,
    ) -> Result<InferenceResponse> {
        if let Some(default_response) = &self.default_response {
            Ok(InferenceResponse {
                request_id: request.request_id.clone(),
                provider_id: "fallback".to_string(),
                model_id: request.model_id.clone(),
                model_version: request.model_version,
                output: default_response.clone(),
                latency_ms: 0,
                token_usage: None,
                created_at: Utc::now(),
            })
        } else {
            Err(ModelGuardError::FallbackFailed(format!(
                "Fallback failed, original error: {}",
                original_error
            )))
        }
    }
}

#[derive(Clone)]
pub struct DefaultMetricsRecorder {
    total_requests: Arc<DashMap<String, u64>>,
    success_requests: Arc<DashMap<String, u64>>,
    failed_requests: Arc<DashMap<String, u64>>,
    total_latency_ms: Arc<DashMap<String, u64>>,
    total_prompt_tokens: Arc<DashMap<String, u64>>,
    total_completion_tokens: Arc<DashMap<String, u64>>,
}

impl DefaultMetricsRecorder {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            total_requests: Arc::new(DashMap::new()),
            success_requests: Arc::new(DashMap::new()),
            failed_requests: Arc::new(DashMap::new()),
            total_latency_ms: Arc::new(DashMap::new()),
            total_prompt_tokens: Arc::new(DashMap::new()),
            total_completion_tokens: Arc::new(DashMap::new()),
        })
    }

    fn key(&self, model_id: &str, provider_id: &str) -> String {
        format!("{}:{}", model_id, provider_id)
    }
}

#[async_trait]
impl MetricsRecorder for DefaultMetricsRecorder {
    fn record_request(&self, model_id: &str, provider_id: &str) {
        let key = self.key(model_id, provider_id);
        *self.total_requests.entry(key).or_insert(0) += 1;
    }

    fn record_success(&self, model_id: &str, provider_id: &str, latency_ms: u64) {
        let key = self.key(model_id, provider_id);
        *self.success_requests.entry(key.clone()).or_insert(0) += 1;
        *self.total_latency_ms.entry(key).or_insert(0) += latency_ms;
    }

    fn record_failure(&self, model_id: &str, provider_id: &str, _error_type: &str) {
        let key = self.key(model_id, provider_id);
        *self.failed_requests.entry(key).or_insert(0) += 1;
    }

    fn record_tokens(&self, model_id: &str, provider_id: &str, prompt_tokens: u32, completion_tokens: u32) {
        let key = self.key(model_id, provider_id);
        *self.total_prompt_tokens.entry(key.clone()).or_insert(0) += prompt_tokens as u64;
        *self.total_completion_tokens.entry(key).or_insert(0) += completion_tokens as u64;
    }

    fn get_metrics(&self) -> HashMap<String, f64> {
        let mut metrics = HashMap::new();
        let total_requests: u64 = self.total_requests.iter().map(|r| *r.value()).sum();
        let success_requests: u64 = self.success_requests.iter().map(|r| *r.value()).sum();
        let total_latency: u64 = self.total_latency_ms.iter().map(|r| *r.value()).sum();
        
        metrics.insert("total_requests".into(), total_requests as f64);
        metrics.insert("success_rate".into(), if total_requests > 0 { success_requests as f64 / total_requests as f64 } else { 1.0 });
        metrics.insert("avg_latency_ms".into(), if success_requests > 0 { total_latency as f64 / success_requests as f64 } else { 0.0 });
        
        metrics
    }
}

#[derive(Clone)]
pub struct InMemoryRequestQueue {
    queue: Arc<Mutex<Vec<InferenceRequest>>>,
}

impl InMemoryRequestQueue {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            queue: Arc::new(Mutex::new(Vec::new())),
        })
    }
}

#[async_trait]
impl RequestQueue for InMemoryRequestQueue {
    async fn enqueue(&self, request: InferenceRequest) -> Result<()> {
        let mut queue = self.queue.lock().await;
        let insert_idx = queue
            .binary_search_by_key(&request.priority, |r| 11 - r.priority)
            .unwrap_or_else(|idx| idx);
        queue.insert(insert_idx, request);
        Ok(())
    }

    async fn dequeue(&self) -> Option<InferenceRequest> {
        let mut queue = self.queue.lock().await;
        if queue.is_empty() {
            None
        } else {
            Some(queue.remove(0))
        }
    }

    fn len(&self) -> usize {
        futures::executor::block_on(async { self.queue.lock().await.len() })
    }

    fn is_empty(&self) -> bool {
        futures::executor::block_on(async { self.queue.lock().await.is_empty() })
    }
}

pub fn create_load_balancer(strategy: LoadBalancingStrategy) -> Arc<dyn LoadBalancer> {
    match strategy {
        LoadBalancingStrategy::RoundRobin => Arc::new(RoundRobinLoadBalancer::new()),
        LoadBalancingStrategy::LeastConnections => Arc::new(LeastConnectionsLoadBalancer),
        LoadBalancingStrategy::WeightedRoundRobin => Arc::new(WeightedRoundRobinLoadBalancer),
        LoadBalancingStrategy::Random => Arc::new(RandomLoadBalancer),
    }
}
