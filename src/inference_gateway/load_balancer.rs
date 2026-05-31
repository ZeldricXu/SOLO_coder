use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

use crate::inference_gateway::provider::{LLMProvider, ProviderStats};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum LoadBalanceStrategy {
    RoundRobin,
    WeightedRoundRobin,
    LeastConnections,
    LeastLatency,
    Random,
    PriorityBased,
}

impl Default for LoadBalanceStrategy {
    fn default() -> Self {
        LoadBalanceStrategy::RoundRobin
    }
}

pub struct LoadBalancer {
    strategy: LoadBalanceStrategy,
    providers: Vec<Arc<dyn LLMProvider>>,
    round_robin_counter: AtomicUsize,
}

impl LoadBalancer {
    pub fn new(strategy: LoadBalanceStrategy) -> Self {
        Self {
            strategy,
            providers: Vec::new(),
            round_robin_counter: AtomicUsize::new(0),
        }
    }

    pub fn with_providers(mut self, providers: Vec<Arc<dyn LLMProvider>>) -> Self {
        self.providers = providers;
        self
    }

    pub fn add_provider(&mut self, provider: Arc<dyn LLMProvider>) {
        self.providers.push(provider);
    }

    pub fn remove_provider(&mut self, provider_id: &str) {
        self.providers.retain(|p| p.config().provider_id != provider_id);
    }

    pub fn get_available_providers(&self, model: &str) -> Vec<Arc<dyn LLMProvider>> {
        self.providers
            .iter()
            .filter(|p| p.is_available() && p.supports_model(model))
            .cloned()
            .collect()
    }

    pub fn select_provider(&self, model: &str) -> Option<Arc<dyn LLMProvider>> {
        let available = self.get_available_providers(model);
        if available.is_empty() {
            return None;
        }

        match self.strategy {
            LoadBalanceStrategy::RoundRobin => self.round_robin(&available),
            LoadBalanceStrategy::WeightedRoundRobin => self.weighted_round_robin(&available),
            LoadBalanceStrategy::LeastConnections => self.least_connections(&available),
            LoadBalanceStrategy::LeastLatency => self.least_latency(&available),
            LoadBalanceStrategy::Random => self.random(&available),
            LoadBalanceStrategy::PriorityBased => self.priority_based(&available),
        }
    }

    fn round_robin(&self, providers: &[Arc<dyn LLMProvider>]) -> Option<Arc<dyn LLMProvider>> {
        if providers.is_empty() {
            return None;
        }
        let idx = self.round_robin_counter.fetch_add(1, Ordering::Relaxed) % providers.len();
        Some(providers[idx].clone())
    }

    fn weighted_round_robin(&self, providers: &[Arc<dyn LLMProvider>]) -> Option<Arc<dyn LLMProvider>> {
        if providers.is_empty() {
            return None;
        }

        let total_weight: u32 = providers.iter().map(|p| p.config().weight).sum();
        if total_weight == 0 {
            return self.round_robin(providers);
        }

        use rand::Rng;
        let mut rng = rand::thread_rng();
        let mut r = rng.gen_range(0..total_weight);

        for provider in providers {
            r = r.saturating_sub(provider.config().weight);
            if r == 0 || r < provider.config().weight {
                return Some(provider.clone());
            }
        }

        Some(providers[0].clone())
    }

    fn least_connections(&self, providers: &[Arc<dyn LLMProvider>]) -> Option<Arc<dyn LLMProvider>> {
        providers
            .iter()
            .min_by_key(|p| p.stats().current_load)
            .cloned()
    }

    fn least_latency(&self, providers: &[Arc<dyn LLMProvider>]) -> Option<Arc<dyn LLMProvider>> {
        providers
            .iter()
            .min_by(|a, b| {
                let a_latency = a.stats().avg_latency_ms;
                let b_latency = b.stats().avg_latency_ms;
                a_latency.partial_cmp(&b_latency).unwrap_or(std::cmp::Ordering::Equal)
            })
            .cloned()
    }

    fn random(&self, providers: &[Arc<dyn LLMProvider>]) -> Option<Arc<dyn LLMProvider>> {
        if providers.is_empty() {
            return None;
        }
        use rand::Rng;
        let mut rng = rand::thread_rng();
        let idx = rng.gen_range(0..providers.len());
        Some(providers[idx].clone())
    }

    fn priority_based(&self, providers: &[Arc<dyn LLMProvider>]) -> Option<Arc<dyn LLMProvider>> {
        let mut sorted: Vec<_> = providers.iter().collect();
        sorted.sort_by_key(|p| std::cmp::Reverse(p.config().priority));
        
        let max_priority = sorted.first()?.config().priority;
        let top_providers: Vec<_> = sorted
            .into_iter()
            .filter(|p| p.config().priority == max_priority)
            .cloned()
            .collect();

        self.round_robin(&top_providers)
    }

    pub fn providers(&self) -> &[Arc<dyn LLMProvider>] {
        &self.providers
    }

    pub fn all_stats(&self) -> Vec<ProviderStats> {
        self.providers.iter().map(|p| p.stats()).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::inference_gateway::provider::{MockProvider, ProviderConfig, ProviderType, InferenceRequest};
    use crate::utils::metrics::MetricsCollector;

    fn create_test_providers() -> Vec<Arc<dyn LLMProvider>> {
        let config1 = ProviderConfig::new(
            ProviderType::OpenAi,
            "provider-1".to_string(),
            "https://api1.com".to_string(),
            "sk-1".to_string(),
        ).with_weight(50).with_priority(10);

        let config2 = ProviderConfig::new(
            ProviderType::Anthropic,
            "provider-2".to_string(),
            "https://api2.com".to_string(),
            "sk-2".to_string(),
        ).with_weight(30).with_priority(20);

        let config3 = ProviderConfig::new(
            ProviderType::AzureOpenAi,
            "provider-3".to_string(),
            "https://api3.com".to_string(),
            "sk-3".to_string(),
        ).with_weight(20).with_priority(10);

        vec![
            Arc::new(MockProvider::new(config1, MetricsCollector::new())) as Arc<dyn LLMProvider>,
            Arc::new(MockProvider::new(config2, MetricsCollector::new())) as Arc<dyn LLMProvider>,
            Arc::new(MockProvider::new(config3, MetricsCollector::new())) as Arc<dyn LLMProvider>,
        ]
    }

    #[test]
    fn test_round_robin() {
        let providers = create_test_providers();
        let lb = LoadBalancer::new(LoadBalanceStrategy::RoundRobin)
            .with_providers(providers);

        let p1 = lb.select_provider("gpt-3.5-turbo").unwrap();
        let p2 = lb.select_provider("gpt-3.5-turbo").unwrap();
        let p3 = lb.select_provider("gpt-3.5-turbo").unwrap();
        let p4 = lb.select_provider("gpt-3.5-turbo").unwrap();

        assert_eq!(p1.config().name, "provider-1");
        assert_eq!(p2.config().name, "provider-2");
        assert_eq!(p3.config().name, "provider-3");
        assert_eq!(p4.config().name, "provider-1");
    }

    #[test]
    fn test_weighted_round_robin() {
        let providers = create_test_providers();
        let lb = LoadBalancer::new(LoadBalanceStrategy::WeightedRoundRobin)
            .with_providers(providers);

        let mut counts = std::collections::HashMap::new();
        for _ in 0..1000 {
            let p = lb.select_provider("gpt-3.5-turbo").unwrap();
            *counts.entry(p.config().name.clone()).or_insert(0) += 1;
        }

        assert!(counts.get("provider-1").unwrap() > counts.get("provider-2").unwrap());
        assert!(counts.get("provider-2").unwrap() > counts.get("provider-3").unwrap());
    }

    #[tokio::test]
    async fn test_least_connections() {
        let providers = create_test_providers();
        let lb = LoadBalancer::new(LoadBalanceStrategy::LeastConnections)
            .with_providers(providers.clone());

        let req = InferenceRequest {
            prompt: "test".to_string(),
            ..Default::default()
        };

        for _ in 0..5 {
            providers[0].complete(req.clone()).await.unwrap();
        }

        let selected = lb.select_provider("gpt-3.5-turbo").unwrap();
        assert_eq!(selected.config().name, "provider-2");
    }

    #[test]
    fn test_priority_based() {
        let providers = create_test_providers();
        let lb = LoadBalancer::new(LoadBalanceStrategy::PriorityBased)
            .with_providers(providers);

        let p = lb.select_provider("gpt-3.5-turbo").unwrap();
        assert_eq!(p.config().name, "provider-2");
        assert_eq!(p.config().priority, 20);
    }

    #[test]
    fn test_no_available_providers() {
        let lb = LoadBalancer::new(LoadBalanceStrategy::RoundRobin);
        let result = lb.select_provider("gpt-3.5-turbo");
        assert!(result.is_none());
    }

    #[test]
    fn test_provider_management() {
        let mut lb = LoadBalancer::new(LoadBalanceStrategy::RoundRobin);
        let providers = create_test_providers();
        let id = providers[0].config().provider_id.clone();
        
        lb.add_provider(providers[0].clone());
        lb.add_provider(providers[1].clone());
        assert_eq!(lb.providers().len(), 2);

        lb.remove_provider(&id);
        assert_eq!(lb.providers().len(), 1);
    }
}
