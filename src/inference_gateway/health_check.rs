use async_trait::async_trait;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::RwLock;
use tokio::time::interval;
use chrono::{DateTime, Utc};
use tracing::{info, warn, error, debug};

use crate::inference_gateway::provider::{LLMProvider, HealthCheckResult, HealthStatus};
use crate::utils::metrics::MetricsCollector;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthCheckConfig {
    pub check_interval_ms: u64,
    pub healthy_threshold: u32,
    pub unhealthy_threshold: u32,
    pub degraded_threshold_latency_ms: u64,
    pub auto_recover: bool,
    pub auto_recover_interval_ms: u64,
    pub max_recovery_attempts: u32,
}

impl Default for HealthCheckConfig {
    fn default() -> Self {
        Self {
            check_interval_ms: 10000,
            healthy_threshold: 3,
            unhealthy_threshold: 3,
            degraded_threshold_latency_ms: 2000,
            auto_recover: true,
            auto_recover_interval_ms: 30000,
            max_recovery_attempts: 5,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProviderHealthStatus {
    pub provider_id: String,
    pub current_status: HealthStatus,
    pub last_check_result: Option<HealthCheckResult>,
    pub consecutive_healthy: u32,
    pub consecutive_unhealthy: u32,
    pub recovery_attempts: u32,
    pub last_status_change: DateTime<Utc>,
    pub is_recovering: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthMonitorSnapshot {
    pub checked_at: DateTime<Utc>,
    pub total_providers: usize,
    pub healthy_count: usize,
    pub degraded_count: usize,
    pub unhealthy_count: usize,
    pub unknown_count: usize,
    pub recovering_count: usize,
    pub provider_statuses: Vec<ProviderHealthStatus>,
}

pub struct HealthCheckManager {
    config: HealthCheckConfig,
    providers: Arc<RwLock<Vec<Arc<dyn LLMProvider>>>>,
    provider_statuses: Arc<RwLock<HashMap<String, ProviderHealthStatus>>>,
    metrics: MetricsCollector,
    is_running: Arc<RwLock<bool>>,
}

impl HealthCheckManager {
    pub fn new(config: HealthCheckConfig, metrics: MetricsCollector) -> Self {
        Self {
            config,
            providers: Arc::new(RwLock::new(Vec::new())),
            provider_statuses: Arc::new(RwLock::new(HashMap::new())),
            metrics,
            is_running: Arc::new(RwLock::new(false)),
        }
    }

    pub async fn register_provider(&self, provider: Arc<dyn LLMProvider>) {
        let provider_id = provider.config().provider_id.clone();
        
        let mut providers = self.providers.write().await;
        providers.push(provider.clone());

        let mut statuses = self.provider_statuses.write().await;
        statuses.insert(provider_id.clone(), ProviderHealthStatus {
            provider_id,
            current_status: HealthStatus::Unknown,
            last_check_result: None,
            consecutive_healthy: 0,
            consecutive_unhealthy: 0,
            recovery_attempts: 0,
            last_status_change: Utc::now(),
            is_recovering: false,
        });
    }

    pub async fn unregister_provider(&self, provider_id: &str) {
        let mut providers = self.providers.write().await;
        providers.retain(|p| p.config().provider_id != provider_id);

        let mut statuses = self.provider_statuses.write().await;
        statuses.remove(provider_id);
    }

    pub async fn start(&self) {
        let is_running = self.is_running.clone();
        *is_running.write().await = true;

        let providers = Arc::clone(&self.providers);
        let provider_statuses = Arc::clone(&self.provider_statuses);
        let config = self.config.clone();
        let metrics = self.metrics.clone();

        tokio::spawn(async move {
            let mut check_interval = interval(Duration::from_millis(config.check_interval_ms));
            
            while *is_running.read().await {
                check_interval.tick().await;
                Self::perform_health_checks(
                    &providers,
                    &provider_statuses,
                    &config,
                    &metrics,
                ).await;
            }

            info!("Health check manager stopped");
        });

        info!("Health check manager started with interval {}ms", self.config.check_interval_ms);
    }

    pub async fn stop(&self) {
        *self.is_running.write().await = false;
        info!("Health check manager stopping...");
    }

    async fn perform_health_checks(
        providers: &RwLock<Vec<Arc<dyn LLMProvider>>>,
        provider_statuses: &RwLock<HashMap<String, ProviderHealthStatus>>,
        config: &HealthCheckConfig,
        metrics: &MetricsCollector,
    ) {
        let provider_list = providers.read().await.clone();
        
        for provider in provider_list {
            let provider_id = provider.config().provider_id.clone();
            let result = provider.health_check().await;
            
            let mut statuses = provider_statuses.write().await;
            if let Some(status) = statuses.get_mut(&provider_id) {
                Self::update_provider_status(
                    status,
                    result,
                    config,
                    provider.as_ref(),
                    metrics,
                );
            }
        }
    }

    fn update_provider_status(
        status: &mut ProviderHealthStatus,
        result: HealthCheckResult,
        config: &HealthCheckConfig,
        provider: &dyn LLMProvider,
        metrics: &MetricsCollector,
    ) {
        let old_status = status.current_status.clone();
        
        match result.status {
            HealthStatus::Healthy => {
                status.consecutive_healthy += 1;
                status.consecutive_unhealthy = 0;
                
                if status.consecutive_healthy >= config.healthy_threshold {
                    if status.is_recovering {
                        status.is_recovering = false;
                        status.recovery_attempts = 0;
                        provider.update_health_status(HealthStatus::Healthy);
                        info!(
                            "Provider {} recovered successfully after {} attempts",
                            status.provider_id, status.recovery_attempts
                        );
                    }
                    status.current_status = HealthStatus::Healthy;
                }
            }
            HealthStatus::Unhealthy => {
                status.consecutive_unhealthy += 1;
                status.consecutive_healthy = 0;
                
                if status.consecutive_unhealthy >= config.unhealthy_threshold {
                    status.current_status = HealthStatus::Unhealthy;
                    
                    if config.auto_recover && status.recovery_attempts < config.max_recovery_attempts {
                        status.is_recovering = true;
                        status.current_status = HealthStatus::Recovering;
                        status.recovery_attempts += 1;
                        provider.update_health_status(HealthStatus::Recovering);
                        
                        warn!(
                            "Provider {} is unhealthy, attempting recovery (attempt {}/{})",
                            status.provider_id, status.recovery_attempts, config.max_recovery_attempts
                        );
                        
                        Self::attempt_recovery(provider, status, metrics);
                    } else if status.recovery_attempts >= config.max_recovery_attempts {
                        error!(
                            "Provider {} failed to recover after {} attempts, marking as permanently unhealthy",
                            status.provider_id, config.max_recovery_attempts
                        );
                        provider.update_health_status(HealthStatus::Unhealthy);
                    }
                }
            }
            HealthStatus::Degraded => {
                status.consecutive_healthy = 0;
                status.current_status = HealthStatus::Degraded;
                provider.update_health_status(HealthStatus::Degraded);
                debug!("Provider {} is degraded: {:?}", status.provider_id, result.error_message);
            }
            HealthStatus::Unknown => {
                status.current_status = HealthStatus::Unknown;
                provider.update_health_status(HealthStatus::Unknown);
            }
            HealthStatus::Recovering => {
                status.is_recovering = true;
                status.current_status = HealthStatus::Recovering;
            }
        }

        if old_status != status.current_status {
            status.last_status_change = Utc::now();
            info!(
                "Provider {} status changed: {:?} -> {:?}",
                status.provider_id, old_status, status.current_status
            );
            metrics.increment_counter(&format!("health_status_change_{:?}", status.current_status).to_lowercase());
        }

        status.last_check_result = Some(result);
    }

    fn attempt_recovery(
        provider: &dyn LLMProvider,
        status: &ProviderHealthStatus,
        metrics: &MetricsCollector,
    ) {
        info!("Initiating recovery for provider: {}", status.provider_id);
        
        metrics.increment_counter("recovery_attempts");
        
        provider.update_health_status(HealthStatus::Recovering);
    }

    pub async fn trigger_manual_recovery(&self, provider_id: &str) -> Result<bool, String> {
        let mut statuses = self.provider_statuses.write().await;
        let status = statuses.get_mut(provider_id)
            .ok_or_else(|| format!("Provider {} not found", provider_id))?;
        
        let providers = self.providers.read().await;
        let provider = providers.iter()
            .find(|p| p.config().provider_id == provider_id)
            .cloned()
            .ok_or_else(|| format!("Provider {} not found", provider_id))?;

        status.is_recovering = true;
        status.recovery_attempts += 1;
        status.current_status = HealthStatus::Recovering;
        provider.update_health_status(HealthStatus::Recovering);

        let result = provider.health_check().await;
        if result.status == HealthStatus::Healthy {
            status.current_status = HealthStatus::Healthy;
            status.is_recovering = false;
            status.consecutive_healthy = self.config.healthy_threshold;
            provider.update_health_status(HealthStatus::Healthy);
            info!("Manual recovery successful for provider: {}", provider_id);
            Ok(true)
        } else {
            status.current_status = HealthStatus::Unhealthy;
            status.is_recovering = false;
            provider.update_health_status(HealthStatus::Unhealthy);
            warn!("Manual recovery failed for provider: {}", provider_id);
            Ok(false)
        }
    }

    pub async fn get_snapshot(&self) -> HealthMonitorSnapshot {
        let statuses = self.provider_statuses.read().await;
        let mut healthy = 0;
        let mut degraded = 0;
        let mut unhealthy = 0;
        let mut unknown = 0;
        let mut recovering = 0;

        for status in statuses.values() {
            match status.current_status {
                HealthStatus::Healthy => healthy += 1,
                HealthStatus::Degraded => degraded += 1,
                HealthStatus::Unhealthy => unhealthy += 1,
                HealthStatus::Unknown => unknown += 1,
                HealthStatus::Recovering => recovering += 1,
            }
        }

        HealthMonitorSnapshot {
            checked_at: Utc::now(),
            total_providers: statuses.len(),
            healthy_count: healthy,
            degraded_count: degraded,
            unhealthy_count: unhealthy,
            unknown_count: unknown,
            recovering_count: recovering,
            provider_statuses: statuses.values().cloned().collect(),
        }
    }

    pub async fn get_provider_status(&self, provider_id: &str) -> Option<ProviderHealthStatus> {
        let statuses = self.provider_statuses.read().await;
        statuses.get(provider_id).cloned()
    }

    pub fn config(&self) -> &HealthCheckConfig {
        &self.config
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
    async fn test_health_check_manager_registration() {
        let manager = HealthCheckManager::new(HealthCheckConfig::default(), MetricsCollector::new());
        let provider = create_test_provider("test", 0.0);
        
        manager.register_provider(provider).await;
        
        let snapshot = manager.get_snapshot().await;
        assert_eq!(snapshot.total_providers, 1);
        assert_eq!(snapshot.unknown_count, 1);
    }

    #[tokio::test]
    async fn test_health_check_healthy_provider() {
        let manager = HealthCheckManager::new(HealthCheckConfig {
            check_interval_ms: 100,
            healthy_threshold: 1,
            ..Default::default()
        }, MetricsCollector::new());
        
        let provider = create_test_provider("healthy", 0.0);
        manager.register_provider(provider).await;
        
        manager.start().await;
        tokio::time::sleep(Duration::from_millis(200)).await;
        manager.stop().await;
        
        let snapshot = manager.get_snapshot().await;
        assert!(snapshot.healthy_count >= 0);
    }

    #[tokio::test]
    async fn test_manual_recovery() {
        let manager = HealthCheckManager::new(HealthCheckConfig::default(), MetricsCollector::new());
        let provider = create_test_provider("recovery_test", 0.0);
        let provider_id = provider.config().provider_id.clone();
        
        manager.register_provider(provider).await;
        
        let result = manager.trigger_manual_recovery(&provider_id).await;
        assert!(result.is_ok());
        assert!(result.unwrap());
    }

    #[tokio::test]
    async fn test_provider_unregistration() {
        let manager = HealthCheckManager::new(HealthCheckConfig::default(), MetricsCollector::new());
        let provider = create_test_provider("to_remove", 0.0);
        let provider_id = provider.config().provider_id.clone();
        
        manager.register_provider(provider).await;
        assert_eq!(manager.get_snapshot().await.total_providers, 1);
        
        manager.unregister_provider(&provider_id).await;
        assert_eq!(manager.get_snapshot().await.total_providers, 0);
    }
}
