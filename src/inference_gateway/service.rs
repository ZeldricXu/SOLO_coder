use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;

use tracing::{info, instrument, warn};

use crate::models::{Config, Result};
use crate::snapshot::{Metrics, Snapshot};
use super::domain::{
    GatewayConfig, InferenceRequest, InferenceResponse, LoadBalancingStrategy,
    ModelProviderConfig, ProviderRegistrationRequest,
};
use super::ports::{
    FallbackHandler, InferenceExecutor, LoadBalancer, MetricsRecorder, ProviderRepository,
    RequestQueue,
};

#[derive(Clone)]
pub struct InferenceGatewayService {
    provider_repository: Arc<dyn ProviderRepository>,
    load_balancer: Arc<dyn LoadBalancer>,
    inference_executor: Arc<dyn InferenceExecutor>,
    fallback_handler: Arc<dyn FallbackHandler>,
    metrics_recorder: Arc<dyn MetricsRecorder>,
    request_queue: Arc<dyn RequestQueue>,
    config: GatewayConfig,
}

impl InferenceGatewayService {
    pub fn new(
        provider_repository: Arc<dyn ProviderRepository>,
        load_balancer: Arc<dyn LoadBalancer>,
        inference_executor: Arc<dyn InferenceExecutor>,
        fallback_handler: Arc<dyn FallbackHandler>,
        metrics_recorder: Arc<dyn MetricsRecorder>,
        request_queue: Arc<dyn RequestQueue>,
        config: GatewayConfig,
    ) -> Self {
        Self {
            provider_repository,
            load_balancer,
            inference_executor,
            fallback_handler,
            metrics_recorder,
            request_queue,
            config,
        }
    }

    #[instrument(skip(self), fields(
        model_id = %request.model_id,
        request_id = %request.request_id
    ))]
    pub async fn infer(&self, request: InferenceRequest) -> Result<InferenceResponse> {
        info!("Processing inference request for model: {}", request.model_id);
        
        let providers = self
            .provider_repository
            .get_providers_for_model(&request.model_id)
            .await;

        if providers.is_empty() {
            warn!("No providers available for model: {}", request.model_id);
            return self
                .fallback_handler
                .handle_fallback(
                    &request,
                    &crate::models::ModelGuardError::NotFound(format!(
                        "No providers available for model: {}",
                        request.model_id
                    )),
                )
                .await;
        }

        let provider = self
            .load_balancer
            .select_provider(&providers)
            .ok_or_else(|| {
                crate::models::ModelGuardError::InternalError("Failed to select provider".into())
            })?;

        self.metrics_recorder
            .record_request(&request.model_id, &provider.config.provider_id);

        let start = Instant::now();
        let mut attempts = 0;
        let max_attempts = self.config.fallback.max_retries + 1;

        loop {
            match self.inference_executor.execute(provider, &request).await {
                Ok(response) => {
                    let latency = start.elapsed().as_millis() as u64;
                    self.metrics_recorder.record_success(
                        &request.model_id,
                        &provider.config.provider_id,
                        latency,
                    );

                    if let Some(usage) = &response.token_usage {
                        self.metrics_recorder.record_tokens(
                            &request.model_id,
                            &provider.config.provider_id,
                            usage.prompt_tokens,
                            usage.completion_tokens,
                        );
                    }

                    self.load_balancer.record_usage(&provider.config.provider_id);
                    
                    info!(
                        "Inference completed for {} in {}ms",
                        request.model_id, latency
                    );
                    return Ok(response);
                }
                Err(e) => {
                    attempts += 1;
                    provider.circuit_breaker.record_failure();
                    self.metrics_recorder.record_failure(
                        &request.model_id,
                        &provider.config.provider_id,
                        &e.to_string(),
                    );

                    if attempts >= max_attempts {
                        warn!(
                            "All {} attempts failed for {}, using fallback",
                            max_attempts, request.model_id
                        );
                        return self.fallback_handler.handle_fallback(&request, &e).await;
                    }

                    warn!(
                        "Attempt {}/{} failed: {}, retrying...",
                        attempts, max_attempts, e
                    );
                }
            }
        }
    }

    pub async fn register_provider(&self, request: ProviderRegistrationRequest) -> Result<ModelProviderConfig> {
        info!("Registering provider: {}", request.name);
        let config = self.provider_repository.register_provider(request).await?;
        info!("Provider registered: {}", config.provider_id);
        Ok(config)
    }

    pub async fn get_provider(&self, provider_id: &str) -> Result<ModelProviderConfig> {
        let provider = self.provider_repository.get_provider(provider_id).await?;
        Ok(provider.config.clone())
    }

    pub async fn list_providers(&self) -> Vec<ModelProviderConfig> {
        let providers = self.provider_repository.list_providers().await;
        providers
            .into_iter()
            .map(|p| p.config.clone())
            .collect()
    }

    pub async fn update_provider(&self, provider_id: &str, config: ModelProviderConfig) -> Result<ModelProviderConfig> {
        self.provider_repository.update_provider(provider_id, config).await
    }

    pub async fn remove_provider(&self, provider_id: &str) -> Result<()> {
        self.provider_repository.remove_provider(provider_id).await
    }

    pub async fn enable_provider(&self, provider_id: &str) -> Result<()> {
        self.provider_repository.enable_provider(provider_id).await
    }

    pub async fn disable_provider(&self, provider_id: &str) -> Result<()> {
        self.provider_repository.disable_provider(provider_id).await
    }

    pub async fn enqueue_request(&self, request: InferenceRequest) -> Result<()> {
        self.request_queue.enqueue(request).await
    }

    pub async fn process_queue(&self) -> Option<InferenceResponse> {
        if let Some(request) = self.request_queue.dequeue().await {
            Some(self.infer(request).await.unwrap_or_else(|e| {
                warn!("Queue processing failed: {}", e);
                InferenceResponse {
                    request_id: "error".to_string(),
                    provider_id: "error".to_string(),
                    model_id: "error".to_string(),
                    model_version: None,
                    output: serde_json::json!({"error": e.to_string()}),
                    latency_ms: 0,
                    token_usage: None,
                    created_at: chrono::Utc::now(),
                }
            }))
        } else {
            None
        }
    }

    pub fn queue_len(&self) -> usize {
        self.request_queue.len()
    }

    pub fn get_load_balancing_strategy(&self) -> LoadBalancingStrategy {
        self.load_balancer.strategy()
    }

    pub fn get_metrics(&self) -> HashMap<String, f64> {
        self.metrics_recorder.get_metrics()
    }

    pub fn snapshot_metrics(&self, dimensions: HashMap<String, String>) -> Snapshot {
        let metrics_data = self.metrics_recorder.get_metrics();
        let mut metrics = Metrics::new();
        
        for (k, v) in metrics_data {
            match k.as_str() {
                "total_requests" => metrics.total_count = v as u64,
                "success_count" => metrics.success_count = v as u64,
                "avg_latency_ms" => metrics.latency_p50 = v,
                _ => {}
            }
        }
        
        Snapshot::new(metrics).with_dimensions(dimensions)
    }

    pub async fn get_stats(&self) -> Result<HashMap<String, serde_json::Value>> {
        let mut stats = HashMap::new();
        let providers = self.provider_repository.list_providers().await;
        
        stats.insert("total_providers".into(), serde_json::json!(providers.len()));
        
        let active_providers = providers.iter().filter(|p| p.is_available()).count();
        stats.insert("active_providers".into(), serde_json::json!(active_providers));
        
        let total_requests: u64 = providers
            .iter()
            .map(|p| p.total_requests.load(std::sync::atomic::Ordering::Relaxed))
            .sum();
        stats.insert("total_requests".into(), serde_json::json!(total_requests));
        
        let queue_size = self.request_queue.len();
        stats.insert("queue_size".into(), serde_json::json!(queue_size));
        
        Ok(stats)
    }
}

impl InferenceGatewayService {
    pub fn with_in_memory_backend(config: Config) -> Self {
        use super::in_memory::{
            create_load_balancer, DefaultFallbackHandler, DefaultMetricsRecorder,
            InMemoryProviderRepository, InMemoryRequestQueue, MockInferenceExecutor,
        };

        let gateway_config: GatewayConfig = config
            .get("gateway")
            .and_then(|v| serde_json::from_value(v.clone()).ok())
            .unwrap_or_default();

        let provider_repository = InMemoryProviderRepository::new();
        let load_balancer = create_load_balancer(gateway_config.default_strategy.clone());
        let inference_executor = Arc::new(MockInferenceExecutor);
        let fallback_handler = Arc::new(DefaultFallbackHandler::new(
            gateway_config.fallback.default_response.clone(),
        ));
        let metrics_recorder = DefaultMetricsRecorder::new();
        let request_queue = InMemoryRequestQueue::new();

        Self::new(
            provider_repository,
            load_balancer,
            inference_executor,
            fallback_handler,
            metrics_recorder,
            request_queue,
            gateway_config,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn create_test_config() -> Config {
        Config::new(
            "test",
            json!({
                "gateway": {
                    "default_strategy": "round_robin",
                    "fallback": {
                        "enabled": true,
                        "timeout_ms": 30000,
                        "max_retries": 2
                    },
                    "circuit_breaker_threshold": 5,
                    "circuit_breaker_reset_ms": 60000
                }
            }),
        )
    }

    #[tokio::test]
    async fn test_register_provider() {
        let service = InferenceGatewayService::with_in_memory_backend(create_test_config());
        let request = ProviderRegistrationRequest {
            name: "Test Provider".to_string(),
            provider_type: "openai".to_string(),
            base_url: "http://localhost".to_string(),
            api_key: "test-key".to_string(),
            model_id: "gpt-4".to_string(),
            weight: Some(100),
            timeout_ms: Some(30000),
            max_concurrent: Some(10),
        };

        let config = service.register_provider(request).await.unwrap();
        assert!(config.provider_id.starts_with("prov_"));
        assert_eq!(config.name, "Test Provider");
    }

    #[tokio::test]
    async fn test_list_providers() {
        let service = InferenceGatewayService::with_in_memory_backend(create_test_config());
        
        let providers = service.list_providers().await;
        assert_eq!(providers.len(), 0);

        service
            .register_provider(ProviderRegistrationRequest {
                name: "Provider 1".to_string(),
                provider_type: "openai".to_string(),
                base_url: "http://localhost".to_string(),
                api_key: "key".to_string(),
                model_id: "gpt-4".to_string(),
                weight: None,
                timeout_ms: None,
                max_concurrent: None,
            })
            .await
            .unwrap();

        let providers = service.list_providers().await;
        assert_eq!(providers.len(), 1);
    }

    #[tokio::test]
    async fn test_infer_no_providers_with_fallback() {
        let mut config = create_test_config();
        let fallback_response = json!({"choices": [{"text": "Fallback response"}]});
        config = Config::new(
            "test",
            json!({
                "gateway": {
                    "default_strategy": "round_robin",
                    "fallback": {
                        "enabled": true,
                        "timeout_ms": 30000,
                        "max_retries": 0,
                        "default_response": fallback_response
                    },
                    "circuit_breaker_threshold": 5,
                    "circuit_breaker_reset_ms": 60000
                }
            }),
        );

        let service = InferenceGatewayService::with_in_memory_backend(config);
        let request = InferenceRequest::new("nonexistent-model", "test prompt");
        
        let result = service.infer(request).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_infer_with_provider() {
        let service = InferenceGatewayService::with_in_memory_backend(create_test_config());
        
        service
            .register_provider(ProviderRegistrationRequest {
                name: "Test Provider".to_string(),
                provider_type: "openai".to_string(),
                base_url: "http://localhost".to_string(),
                api_key: "test-key".to_string(),
                model_id: "test-model".to_string(),
                weight: Some(100),
                timeout_ms: Some(30000),
                max_concurrent: Some(10),
            })
            .await
            .unwrap();

        let request = InferenceRequest::new("test-model", "Hello, world!");
        let response = service.infer(request).await.unwrap();

        assert_eq!(response.model_id, "test-model");
        assert!(response.provider_id.starts_with("prov_"));
        assert!(response.output["choices"][0]["text"]
            .as_str()
            .unwrap()
            .contains("Mock response"));
    }

    #[tokio::test]
    async fn test_enable_disable_provider() {
        let service = InferenceGatewayService::with_in_memory_backend(create_test_config());
        
        let config = service
            .register_provider(ProviderRegistrationRequest {
                name: "Test Provider".to_string(),
                provider_type: "openai".to_string(),
                base_url: "http://localhost".to_string(),
                api_key: "key".to_string(),
                model_id: "test-model".to_string(),
                weight: None,
                timeout_ms: None,
                max_concurrent: None,
            })
            .await
            .unwrap();

        assert!(service.disable_provider(&config.provider_id).await.is_ok());
        
        let providers = service.list_providers().await;
        assert!(!providers[0].enabled);
        
        assert!(service.enable_provider(&config.provider_id).await.is_ok());
        
        let providers = service.list_providers().await;
        assert!(providers[0].enabled);
    }

    #[tokio::test]
    async fn test_request_queue() {
        let service = InferenceGatewayService::with_in_memory_backend(create_test_config());
        
        assert_eq!(service.queue_len(), 0);
        
        let request = InferenceRequest::new("test-model", "test prompt");
        service.enqueue_request(request).await.unwrap();
        
        assert_eq!(service.queue_len(), 1);
    }

    #[tokio::test]
    async fn test_get_stats() {
        let service = InferenceGatewayService::with_in_memory_backend(create_test_config());
        
        let stats = service.get_stats().await.unwrap();
        
        assert_eq!(stats["total_providers"], 0);
        assert_eq!(stats["active_providers"], 0);
        assert_eq!(stats["queue_size"], 0);
    }

    #[test]
    fn test_load_balancing_strategy() {
        let service = InferenceGatewayService::with_in_memory_backend(create_test_config());
        assert_eq!(
            service.get_load_balancing_strategy(),
            LoadBalancingStrategy::RoundRobin
        );
    }
}
