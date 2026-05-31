use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use uuid::Uuid;
use chrono::{DateTime, Utc};

use crate::utils::context::{RequestContext, TransactionContext};
use crate::utils::error::{GatewayError, Result};
use crate::utils::metrics::MetricsCollector;
use crate::models::Config;

use super::provider::{InferenceRequest, InferenceResponse, LLMProvider, MockProvider, ProviderConfig, ProviderStats, ProviderType};
use super::load_balancer::{LoadBalancer, LoadBalanceStrategy};
use super::fallback::{CircuitBreakerConfig, FallbackManager, FallbackStrategy};
use super::health_check::{HealthCheckConfig, HealthCheckManager, HealthMonitorSnapshot};
use super::batch::{BatchProcessor, BatchConfig, BatchInferenceRequest, BatchInferenceResponse, BatchStats};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GatewayConfig {
    pub default_strategy: LoadBalanceStrategy,
    pub fallback_strategy: FallbackStrategy,
    pub max_retries: u32,
    pub request_timeout_ms: u64,
    pub circuit_breaker: CircuitBreakerConfig,
    pub health_check: HealthCheckConfig,
    pub enable_auto_recovery: bool,
    pub batch: BatchConfig,
}

impl Default for GatewayConfig {
    fn default() -> Self {
        Self {
            default_strategy: LoadBalanceStrategy::RoundRobin,
            fallback_strategy: FallbackStrategy::RetryThenFailover,
            max_retries: 3,
            request_timeout_ms: 30000,
            circuit_breaker: CircuitBreakerConfig::default(),
            health_check: HealthCheckConfig::default(),
            enable_auto_recovery: true,
            batch: BatchConfig::default(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GatewayRequest {
    pub model: String,
    pub messages: Vec<ChatMessage>,
    pub max_tokens: Option<u32>,
    pub temperature: Option<f32>,
    pub stream: Option<bool>,
    pub metadata: Option<HashMap<String, String>>,
    pub trace_id: Option<String>,
    pub user_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GatewayResponse {
    pub response_id: String,
    pub model: String,
    pub content: String,
    pub usage: UsageInfo,
    pub latency_ms: u64,
    pub provider_id: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UsageInfo {
    pub prompt_tokens: u32,
    pub completion_tokens: u32,
    pub total_tokens: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GatewayStats {
    pub total_requests: u64,
    pub successful_requests: u64,
    pub failed_requests: u64,
    pub avg_latency_ms: f64,
    pub p99_latency_ms: f64,
    pub total_tokens: u64,
    pub provider_stats: Vec<ProviderStats>,
    pub circuit_breaker_states: HashMap<String, String>,
    pub health_monitor: Option<HealthMonitorSnapshot>,
    pub batch_stats: Option<BatchStats>,
}

pub struct InferenceGateway {
    config: GatewayConfig,
    load_balancer: LoadBalancer,
    fallback_manager: FallbackManager,
    health_check_manager: HealthCheckManager,
    batch_processor: BatchProcessor,
    metrics: MetricsCollector,
    app_config: Config,
    auto_recovery_enabled: bool,
}

impl InferenceGateway {
    pub fn new(app_config: Config, gateway_config: GatewayConfig, metrics: MetricsCollector) -> Self {
        let load_balancer = LoadBalancer::new(gateway_config.default_strategy.clone());
        let fallback_manager = FallbackManager::new(
            gateway_config.fallback_strategy.clone(),
            metrics.clone(),
        ).with_max_retries(gateway_config.max_retries);

        let health_check_manager = HealthCheckManager::new(
            gateway_config.health_check.clone(),
            metrics.clone(),
        );

        let batch_processor = BatchProcessor::new(
            gateway_config.batch.clone(),
            metrics.clone(),
        );

        Self {
            config: gateway_config,
            load_balancer,
            fallback_manager,
            health_check_manager,
            batch_processor,
            metrics,
            app_config,
            auto_recovery_enabled: true,
        }
    }

    pub async fn register_provider(&mut self, provider: Arc<dyn LLMProvider>) {
        let provider_id = provider.config().provider_id.clone();
        self.fallback_manager.register_provider(provider_id.clone(), self.config.circuit_breaker.clone());
        self.health_check_manager.register_provider(provider.clone()).await;
        self.load_balancer.add_provider(provider);
    }

    pub async fn start_health_monitoring(&self) {
        if self.config.enable_auto_recovery {
            self.health_check_manager.start().await;
            info!("Health monitoring and auto-recovery enabled");
        }
    }

    pub async fn stop_health_monitoring(&self) {
        self.health_check_manager.stop().await;
    }

    pub async fn start_batch_processing(&self) {
        self.batch_processor.start_background_processor().await;
        info!("Batch processing started with max_batch_size={}, max_wait={}ms",
              self.config.batch.max_batch_size, self.config.batch.max_wait_ms);
    }

    pub fn stop_batch_processing(&self) {
        self.batch_processor.stop_background_processor();
        info!("Batch processing stopped");
    }

    pub async fn trigger_provider_recovery(&self, provider_id: &str) -> Result<bool, String> {
        self.health_check_manager.trigger_manual_recovery(provider_id).await
    }

    pub async fn register_mock_provider(&mut self, name: &str, provider_type: ProviderType, models: Vec<String>) -> String {
        let mut config = ProviderConfig::new(
            provider_type,
            name.to_string(),
            format!("https://{}.api.com/v1", name),
            format!("sk-mock-{}", Uuid::new_v4().simple()),
        );

        for model in models {
            config = config.with_model(model);
        }

        let provider = Arc::new(MockProvider::new(config, self.metrics.clone()));
        let provider_id = provider.config().provider_id.clone();
        self.register_provider(provider).await;
        provider_id
    }

    pub async fn chat(&self, request: GatewayRequest) -> Result<GatewayResponse> {
        let ctx = RequestContext::new(
            request.trace_id.clone(),
            self.app_config.namespace.clone(),
        ).with_timeout(self.config.request_timeout_ms / 1000);

        if let Some(user_id) = &request.user_id {
            ctx.set("user_id", user_id.clone());
        }

        let mut tx = TransactionContext::new();
        let start = std::time::Instant::now();

        tracing::info!(
            trace_id = %ctx.trace_id,
            model = %request.model,
            "Processing inference request"
        );

        self.metrics.increment_counter("gateway_requests");

        let prompt = self.format_prompt(&request.messages);
        let inference_request = InferenceRequest {
            model: request.model.clone(),
            prompt,
            max_tokens: request.max_tokens.unwrap_or(1024),
            temperature: request.temperature.unwrap_or(0.7),
            top_p: 1.0,
            frequency_penalty: 0.0,
            presence_penalty: 0.0,
            stop_sequences: Vec::new(),
            stream: request.stream.unwrap_or(false),
            metadata: request.metadata.unwrap_or_default(),
        };

        let providers = self.load_balancer.providers().to_vec();
        let lb = &self.load_balancer;
        let select_fn = |model: &str| lb.select_provider(model);

        let result = self.fallback_manager.execute_with_fallback(
            &providers,
            inference_request,
            select_fn,
        ).await;

        match result {
            Ok(response) => {
                let latency_ms = start.elapsed().as_millis() as u64;
                self.metrics.increment_counter("gateway_success");
                self.metrics.record_histogram("gateway_latency", latency_ms as f64);

                tx.commit();
                ctx.cleanup();

                Ok(GatewayResponse {
                    response_id: response.response_id,
                    model: response.model,
                    content: response.content,
                    usage: UsageInfo {
                        prompt_tokens: response.prompt_tokens,
                        completion_tokens: response.completion_tokens,
                        total_tokens: response.total_tokens,
                    },
                    latency_ms,
                    provider_id: response.provider_id,
                    created_at: response.created_at,
                })
            }
            Err(e) => {
                self.metrics.increment_counter("gateway_errors");
                tracing::error!(
                    trace_id = %ctx.trace_id,
                    error = %e,
                    "Inference request failed"
                );
                tx.rollback();
                ctx.cleanup();
                Err(e)
            }
        }
    }

    fn format_prompt(&self, messages: &[ChatMessage]) -> String {
        messages
            .iter()
            .map(|m| format!("{}: {}", m.role, m.content))
            .collect::<Vec<_>>()
            .join("\n")
    }

    pub async fn batch_chat(&self, batch_request: BatchInferenceRequest) -> Result<BatchInferenceResponse> {
        self.metrics.increment_counter("gateway_batch_requests");
        info!("Received batch request with {} requests", batch_request.requests.len());

        let mut results = Vec::new();
        
        for (idx, request) in batch_request.requests.into_iter().enumerate() {
            let item_start = std::time::Instant::now();
            
            let result = if self.config.batch.enable_auto_batching && batch_request.max_wait_ms.is_some() {
                self.batch_processor.submit_single(request).await
            } else {
                self.chat(request).await
            };
            
            let latency_ms = item_start.elapsed().as_millis() as u64;
            
            match result {
                Ok(response) => {
                    results.push(super::batch::BatchItemResult {
                        request_index: idx,
                        response: Some(response),
                        error: None,
                        latency_ms,
                    });
                }
                Err(e) => {
                    results.push(super::batch::BatchItemResult {
                        request_index: idx,
                        response: None,
                        error: Some(e.to_string()),
                        latency_ms,
                    });
                }
            }
        }
        
        let successful = results.iter().filter(|r| r.response.is_some()).count();
        let failed = results.len() - successful;
        
        Ok(BatchInferenceResponse {
            batch_id: batch_request.batch_id.clone().unwrap_or_else(|| format!("batch_{}", Uuid::new_v4().simple())),
            responses: results,
            total_requests: successful + failed,
            successful_requests: successful,
            failed_requests: failed,
            processing_time_ms: 0,
            created_at: Utc::now(),
        })
    }

    pub async fn batch_stats(&self) -> BatchStats {
        self.batch_processor.stats().await
    }

    pub async fn stats(&self) -> GatewayStats {
        let metrics_snapshot = self.metrics.snapshot();
        let cb_states = self.fallback_manager.get_circuit_breaker_states();
        let health_monitor = self.health_check_manager.get_snapshot().await;
        let batch_stats = self.batch_processor.stats().await;

        GatewayStats {
            total_requests: metrics_snapshot.throughput.unwrap_or(0.0) as u64,
            successful_requests: metrics_snapshot.success_count.unwrap_or(0),
            failed_requests: metrics_snapshot.failure_count.unwrap_or(0),
            avg_latency_ms: metrics_snapshot.latency_p50.unwrap_or(0.0),
            p99_latency_ms: metrics_snapshot.latency_p99.unwrap_or(0.0),
            total_tokens: self.load_balancer.all_stats().iter().map(|s| s.total_tokens).sum(),
            provider_stats: self.load_balancer.all_stats(),
            circuit_breaker_states: cb_states
                .into_iter()
                .map(|(k, v)| (k, format!("{:?}", v)))
                .collect(),
            health_monitor: Some(health_monitor),
            batch_stats: Some(batch_stats),
        }
    }

    pub fn list_providers(&self) -> Vec<ProviderConfig> {
        self.load_balancer
            .providers()
            .iter()
            .map(|p| p.config().clone())
            .collect()
    }

    pub fn remove_provider(&mut self, provider_id: &str) {
        self.load_balancer.remove_provider(provider_id);
    }

    pub fn set_load_balance_strategy(&mut self, strategy: LoadBalanceStrategy) {
        self.load_balancer = LoadBalancer::new(strategy);
        let providers: Vec<_> = self.load_balancer.providers().to_vec();
        for p in providers {
            self.load_balancer.add_provider(p);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::inference_gateway::provider::ProviderType;

    async fn create_test_gateway() -> InferenceGateway {
        let app_config = Config::new("test", 1);
        let gateway_config = GatewayConfig::default();
        let metrics = MetricsCollector::new();
        InferenceGateway::new(app_config, gateway_config, metrics)
    }

    #[tokio::test]
    async fn test_gateway_chat() {
        let mut gateway = create_test_gateway().await;
        gateway.register_mock_provider(
            "test-openai",
            ProviderType::OpenAi,
            vec!["gpt-3.5-turbo".to_string()],
        ).await;

        let request = GatewayRequest {
            model: "gpt-3.5-turbo".to_string(),
            messages: vec![
                ChatMessage {
                    role: "user".to_string(),
                    content: "Hello, how are you?".to_string(),
                }
            ],
            max_tokens: Some(100),
            temperature: Some(0.7),
            stream: Some(false),
            metadata: None,
            trace_id: Some("trace_123".to_string()),
            user_id: Some("user_456".to_string()),
        };

        let response = gateway.chat(request).await.unwrap();

        assert!(!response.response_id.is_empty());
        assert!(!response.content.is_empty());
        assert_eq!(response.model, "gpt-3.5-turbo");
        assert!(response.latency_ms > 0);
        assert!(response.usage.total_tokens > 0);
    }

    #[tokio::test]
    async fn test_gateway_no_providers() {
        let gateway = create_test_gateway().await;

        let request = GatewayRequest {
            model: "gpt-3.5-turbo".to_string(),
            messages: vec![
                ChatMessage {
                    role: "user".to_string(),
                    content: "Hello".to_string(),
                }
            ],
            max_tokens: None,
            temperature: None,
            stream: None,
            metadata: None,
            trace_id: None,
            user_id: None,
        };

        let result = gateway.chat(request).await;
        assert!(result.is_err());
        assert!(matches!(result.unwrap_err(), GatewayError::Provider(_)));
    }

    #[tokio::test]
    async fn test_gateway_stats() {
        let mut gateway = create_test_gateway().await;
        let provider_id = gateway.register_mock_provider(
            "test",
            ProviderType::OpenAi,
            vec!["gpt-3.5-turbo".to_string()],
        ).await;

        for _ in 0..5 {
            let request = GatewayRequest {
                model: "gpt-3.5-turbo".to_string(),
                messages: vec![
                    ChatMessage {
                        role: "user".to_string(),
                        content: "Hello".to_string(),
                    }
                ],
                max_tokens: None,
                temperature: None,
                stream: None,
                metadata: None,
                trace_id: None,
                user_id: None,
            };
            gateway.chat(request).await.unwrap();
        }

        let stats = gateway.stats().await;
        assert_eq!(stats.successful_requests, 5);
        assert_eq!(stats.provider_stats.len(), 1);
        assert!(stats.circuit_breaker_states.contains_key(&provider_id));
        assert!(stats.health_monitor.is_some());
    }

    #[tokio::test]
    async fn test_provider_management() {
        let mut gateway = create_test_gateway().await;
        let id = gateway.register_mock_provider(
            "test",
            ProviderType::OpenAi,
            vec!["gpt-3.5-turbo".to_string()],
        ).await;

        assert_eq!(gateway.list_providers().len(), 1);

        gateway.remove_provider(&id);
        assert_eq!(gateway.list_providers().len(), 0);
    }

    #[tokio::test]
    async fn test_format_prompt() {
        let gateway = create_test_gateway().await;
        let messages = vec![
            ChatMessage { role: "system".to_string(), content: "You are helpful".to_string() },
            ChatMessage { role: "user".to_string(), content: "Hello".to_string() },
        ];
        let formatted = gateway.format_prompt(&messages);
        assert!(formatted.contains("system: You are helpful"));
        assert!(formatted.contains("user: Hello"));
    }

    #[tokio::test]
    async fn test_health_monitoring_integration() {
        let gateway = create_test_gateway().await;
        gateway.start_health_monitoring().await;
        
        let snapshot = gateway.stats().await;
        assert!(snapshot.health_monitor.is_some());
        assert_eq!(snapshot.health_monitor.unwrap().total_providers, 0);
        
        gateway.stop_health_monitoring().await;
    }

    #[tokio::test]
    async fn test_manual_recovery() {
        let mut gateway = create_test_gateway().await;
        let provider_id = gateway.register_mock_provider(
            "recovery_test",
            ProviderType::OpenAi,
            vec!["gpt-3.5-turbo".to_string()],
        ).await;

        let result = gateway.trigger_provider_recovery(&provider_id).await;
        assert!(result.is_ok());
    }
}
