use async_trait::async_trait;
use std::collections::HashMap;
use std::sync::Arc;

use crate::models::Result;
use super::domain::{
    InferenceRequest, InferenceResponse, LoadBalancingStrategy, ModelProvider,
    ModelProviderConfig, ProviderRegistrationRequest,
};

#[async_trait]
pub trait ProviderRepository: Send + Sync {
    async fn register_provider(&self, request: ProviderRegistrationRequest) -> Result<ModelProviderConfig>;
    async fn get_provider(&self, provider_id: &str) -> Result<Arc<ModelProvider>>;
    async fn get_providers_for_model(&self, model_id: &str) -> Vec<Arc<ModelProvider>>;
    async fn list_providers(&self) -> Vec<Arc<ModelProvider>>;
    async fn update_provider(&self, provider_id: &str, config: ModelProviderConfig) -> Result<ModelProviderConfig>;
    async fn remove_provider(&self, provider_id: &str) -> Result<()>;
    async fn enable_provider(&self, provider_id: &str) -> Result<()>;
    async fn disable_provider(&self, provider_id: &str) -> Result<()>;
}

#[async_trait]
pub trait LoadBalancer: Send + Sync {
    fn strategy(&self) -> LoadBalancingStrategy;
    fn select_provider<'a>(&self, providers: &'a [Arc<ModelProvider>]) -> Option<&'a Arc<ModelProvider>>;
    fn record_usage(&self, provider_id: &str);
}

#[async_trait]
pub trait InferenceExecutor: Send + Sync {
    async fn execute(
        &self,
        provider: &ModelProvider,
        request: &InferenceRequest,
    ) -> Result<InferenceResponse>;
}

#[async_trait]
pub trait FallbackHandler: Send + Sync {
    async fn handle_fallback(
        &self,
        request: &InferenceRequest,
        original_error: &crate::models::ModelGuardError,
    ) -> Result<InferenceResponse>;
}

#[async_trait]
pub trait MetricsRecorder: Send + Sync {
    fn record_request(&self, model_id: &str, provider_id: &str);
    fn record_success(&self, model_id: &str, provider_id: &str, latency_ms: u64);
    fn record_failure(&self, model_id: &str, provider_id: &str, error_type: &str);
    fn record_tokens(&self, model_id: &str, provider_id: &str, prompt_tokens: u32, completion_tokens: u32);
    fn get_metrics(&self) -> HashMap<String, f64>;
}

#[async_trait]
pub trait RequestQueue: Send + Sync {
    async fn enqueue(&self, request: InferenceRequest) -> Result<()>;
    async fn dequeue(&self) -> Option<InferenceRequest>;
    fn len(&self) -> usize;
    fn is_empty(&self) -> bool;
}
