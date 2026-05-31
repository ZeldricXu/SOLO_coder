use crate::types::{AppError, Config, generate_id, now_utc};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tracing;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SidecarInstance {
    pub instance_id: String,
    pub pod_name: String,
    pub namespace: String,
    pub version: String,
    pub status: SidecarStatus,
    pub injection_strategy: InjectionStrategy,
    pub resources: ResourceLimits,
    pub config_version: u32,
    pub config_hash: String,
    pub last_heartbeat: DateTime<Utc>,
    pub started_at: DateTime<Utc>,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SidecarStatus {
    NotInjected,
    Injecting,
    Running,
    Updating,
    Degraded,
    Terminating,
    Terminated,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InjectionStrategy {
    pub strategy_type: StrategyType,
    pub namespaces: Vec<String>,
    pub pod_selector: HashMap<String, String>,
    pub auto_inject: bool,
    pub sidecar_image: String,
    pub init_container_image: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum StrategyType {
    Manual,
    Automatic,
    OptIn,
    OptOut,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceLimits {
    pub cpu_request: String,
    pub cpu_limit: String,
    pub memory_request: String,
    pub memory_limit: String,
    pub ephemeral_storage_request: Option<String>,
    pub ephemeral_storage_limit: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SidecarConfig {
    pub config_id: String,
    pub version: u32,
    pub parameters: HashMap<String, serde_json::Value>,
    pub proxy_config: ProxyConfig,
    pub observability_config: ObservabilityConfig,
    pub security_config: SecurityConfig,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProxyConfig {
    pub log_level: String,
    pub connect_timeout_ms: u32,
    pub request_timeout_ms: u32,
    pub idle_timeout_ms: u32,
    pub max_connections: u32,
    pub max_pending_requests: u32,
    pub retries: u32,
    pub retry_on: Vec<String>,
    pub circuit_breaker_enabled: bool,
    pub circuit_breaker_max_errors: u32,
    pub circuit_breaker_timeout_ms: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ObservabilityConfig {
    pub tracing_enabled: bool,
    pub tracing_sampling_rate: f64,
    pub metrics_enabled: bool,
    pub metrics_port: u16,
    pub access_log_enabled: bool,
    pub access_log_format: String,
    pub distributed_tracing: DistributedTracingConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DistributedTracingConfig {
    pub exporter: String,
    pub endpoint: String,
    pub service_name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SecurityConfig {
    pub mtls_enabled: bool,
    pub certificate_rotation_days: u32,
    pub jwt_validation_enabled: bool,
    pub rbac_enabled: bool,
    pub external_authorization: Option<ExternalAuthConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExternalAuthConfig {
    pub grpc_service: String,
    pub timeout_ms: u32,
    pub include_headers: Vec<String>,
}

pub struct SidecarLifecycleManager {
    instances: Arc<DashMap<String, SidecarInstance>>,
    configs: Arc<DashMap<String, SidecarConfig>>,
    injection_strategies: Arc<DashMap<String, InjectionStrategy>>,
    pending_updates: Arc<DashMap<String, PendingUpdate>>,
    heartbeat_timeout: Duration,
}

#[derive(Debug, Clone)]
struct PendingUpdate {
    instance_id: String,
    target_config_version: u32,
    started_at: DateTime<Utc>,
    max_duration: Duration,
    retry_count: u32,
    max_retries: u32,
}

impl SidecarLifecycleManager {
    pub fn new() -> Self {
        let manager = Self {
            instances: Arc::new(DashMap::new()),
            configs: Arc::new(DashMap::new()),
            injection_strategies: Arc::new(DashMap::new()),
            pending_updates: Arc::new(DashMap::new()),
            heartbeat_timeout: Duration::from_secs(60),
        };

        manager.start_background_tasks();
        manager
    }

    pub fn register_injection_strategy(&self, strategy: InjectionStrategy) -> Result<(), AppError> {
        let strategy_id = generate_id("str");
        self.injection_strategies.insert(strategy_id, strategy);
        Ok(())
    }

    pub fn should_inject(&self, namespace: &str, pod_labels: &HashMap<String, String>) -> Option<InjectionStrategy> {
        for entry in self.injection_strategies.iter() {
            let strategy = entry.value();
            
            if !strategy.namespaces.is_empty() && !strategy.namespaces.contains(&namespace.to_string()) {
                continue;
            }

            let mut label_match = true;
            for (k, v) in &strategy.pod_selector {
                if pod_labels.get(k) != Some(v) {
                    label_match = false;
                    break;
                }
            }
            if !label_match {
                continue;
            }

            match strategy.strategy_type {
                StrategyType::Automatic => return Some(strategy.clone()),
                StrategyType::OptIn => {
                    if pod_labels.get("sidecar-inject").map(|v| v == "true").unwrap_or(false) {
                        return Some(strategy.clone());
                    }
                }
                StrategyType::OptOut => {
                    if pod_labels.get("sidecar-inject").map(|v| v != "false").unwrap_or(true) {
                        return Some(strategy.clone());
                    }
                }
                StrategyType::Manual => continue,
            }
        }
        None
    }

    pub fn inject_sidecar(
        &self,
        pod_name: &str,
        namespace: &str,
        version: &str,
        strategy: InjectionStrategy,
        labels: HashMap<String, String>,
    ) -> Result<SidecarInstance, AppError> {
        let instance_id = generate_id("sid");
        let now = now_utc();

        let instance = SidecarInstance {
            instance_id: instance_id.clone(),
            pod_name: pod_name.to_string(),
            namespace: namespace.to_string(),
            version: version.to_string(),
            status: SidecarStatus::Injecting,
            injection_strategy: strategy,
            resources: ResourceLimits {
                cpu_request: "100m".to_string(),
                cpu_limit: "500m".to_string(),
                memory_request: "128Mi".to_string(),
                memory_limit: "512Mi".to_string(),
                ephemeral_storage_request: None,
                ephemeral_storage_limit: None,
            },
            config_version: 1,
            config_hash: String::new(),
            last_heartbeat: now,
            started_at: now,
            labels,
        };

        self.instances.insert(instance_id.clone(), instance.clone());
        tracing::info!(instance_id = %instance_id, pod = %pod_name, "Sidecar注入中");

        Ok(instance)
    }

    pub fn mark_running(&self, instance_id: &str, config_hash: String) -> Result<SidecarInstance, AppError> {
        let mut instance = self.instances.get_mut(instance_id)
            .ok_or_else(|| AppError::NotFound(format!("Sidecar实例不存在: {}", instance_id)))?;

        instance.status = SidecarStatus::Running;
        instance.config_hash = config_hash;
        instance.last_heartbeat = now_utc();

        tracing::info!(instance_id = %instance_id, "Sidecar运行中");
        Ok(instance.clone())
    }

    pub fn report_heartbeat(&self, instance_id: &str) -> Result<(), AppError> {
        if let Some(mut instance) = self.instances.get_mut(instance_id) {
            instance.last_heartbeat = now_utc();
        }
        Ok(())
    }

    pub fn update_resources(&self, instance_id: &str, resources: ResourceLimits) -> Result<SidecarInstance, AppError> {
        let mut instance = self.instances.get_mut(instance_id)
            .ok_or_else(|| AppError::NotFound(format!("Sidecar实例不存在: {}", instance_id)))?;

        instance.resources = resources;
        Ok(instance.clone())
    }

    pub async fn hot_update_config(
        &self,
        instance_id: &str,
        new_config: SidecarConfig,
    ) -> Result<SidecarConfig, AppError> {
        let mut instance = self.instances.get_mut(instance_id)
            .ok_or_else(|| AppError::NotFound(format!("Sidecar实例不存在: {}", instance_id)))?;

        instance.status = SidecarStatus::Updating;
        let current_version = instance.config_version;
        drop(instance);

        let config_id = new_config.config_id.clone();
        let new_version = current_version + 1;
        let mut new_config = new_config;
        new_config.version = new_version;

        self.configs.insert(config_id.clone(), new_config.clone());

        self.pending_updates.insert(
            instance_id.to_string(),
            PendingUpdate {
                instance_id: instance_id.to_string(),
                target_config_version: new_version,
                started_at: now_utc(),
                max_duration: Duration::from_secs(300),
                retry_count: 0,
                max_retries: 3,
            },
        );

        tracing::info!(instance_id = %instance_id, config_version = new_version, "Sidecar配置热更新中");
        Ok(new_config)
    }

    pub fn confirm_config_update(&self, instance_id: &str, success: bool, error: Option<String>) -> Result<(), AppError> {
        let mut instance = self.instances.get_mut(instance_id)
            .ok_or_else(|| AppError::NotFound(format!("Sidecar实例不存在: {}", instance_id)))?;

        if success {
            if let Some(update) = self.pending_updates.get(instance_id) {
                instance.config_version = update.target_config_version;
            }
            instance.status = SidecarStatus::Running;
            tracing::info!(instance_id = %instance_id, "Sidecar配置热更新成功");
        } else {
            instance.status = SidecarStatus::Degraded;
            tracing::warn!(instance_id = %instance_id, error = ?error, "Sidecar配置热更新失败");

            if let Some(mut update) = self.pending_updates.get_mut(instance_id) {
                update.retry_count += 1;
                if update.retry_count >= update.max_retries {
                    self.pending_updates.remove(instance_id);
                    instance.status = SidecarStatus::Running;
                    tracing::warn!(instance_id = %instance_id, "Sidecar配置热更新重试次数耗尽，回滚到旧配置");
                }
            }
        }

        Ok(())
    }

    pub fn terminate_sidecar(&self, instance_id: &str) -> Result<SidecarInstance, AppError> {
        let mut instance = self.instances.get_mut(instance_id)
            .ok_or_else(|| AppError::NotFound(format!("Sidecar实例不存在: {}", instance_id)))?;

        instance.status = SidecarStatus::Terminating;
        tracing::info!(instance_id = %instance_id, "Sidecar终止中");
        Ok(instance.clone())
    }

    pub fn remove_sidecar(&self, instance_id: &str) -> Result<(), AppError> {
        if self.instances.remove(instance_id).is_some() {
            self.pending_updates.remove(instance_id);
            tracing::info!(instance_id = %instance_id, "Sidecar已移除");
            Ok(())
        } else {
            Err(AppError::NotFound(format!("Sidecar实例不存在: {}", instance_id)))
        }
    }

    pub fn get_instance(&self, instance_id: &str) -> Option<SidecarInstance> {
        self.instances.get(instance_id).map(|i| i.clone())
    }

    pub fn list_instances(&self, namespace: Option<&str>) -> Vec<SidecarInstance> {
        self.instances
            .iter()
            .filter(|entry| {
                namespace.map_or(true, |ns| entry.value().namespace == ns)
            })
            .map(|entry| entry.value().clone())
            .collect()
    }

    pub fn create_default_config(&self, config_id: &str) -> SidecarConfig {
        SidecarConfig {
            config_id: config_id.to_string(),
            version: 1,
            parameters: HashMap::new(),
            proxy_config: ProxyConfig {
                log_level: "info".to_string(),
                connect_timeout_ms: 5000,
                request_timeout_ms: 30000,
                idle_timeout_ms: 3600000,
                max_connections: 1000,
                max_pending_requests: 100,
                retries: 3,
                retry_on: vec!["5xx".to_string(), "connect-failure".to_string()],
                circuit_breaker_enabled: true,
                circuit_breaker_max_errors: 20,
                circuit_breaker_timeout_ms: 30000,
            },
            observability_config: ObservabilityConfig {
                tracing_enabled: true,
                tracing_sampling_rate: 0.1,
                metrics_enabled: true,
                metrics_port: 15090,
                access_log_enabled: true,
                access_log_format: "[%START_TIME%] \"%REQ(:METHOD)% %REQ(X-ENVOY-ORIGINAL-PATH?:PATH)% %PROTOCOL%\" %RESPONSE_CODE% %RESPONSE_FLAGS% %BYTES_RECEIVED% %BYTES_SENT% %DURATION% \"%REQ(X-FORWARDED-FOR)%\" \"%REQ(USER-AGENT)%\" \"%REQ(X-REQUEST-ID)%\"".to_string(),
                distributed_tracing: DistributedTracingConfig {
                    exporter: "otlp".to_string(),
                    endpoint: "http://localhost:4317".to_string(),
                    service_name: "sidecar-proxy".to_string(),
                },
            },
            security_config: SecurityConfig {
                mtls_enabled: true,
                certificate_rotation_days: 30,
                jwt_validation_enabled: true,
                rbac_enabled: true,
                external_authorization: None,
            },
            created_at: now_utc(),
        }
    }

    pub fn get_config(&self, config_id: &str) -> Option<SidecarConfig> {
        self.configs.get(config_id).map(|c| c.clone())
    }

    fn start_background_tasks(&self) {
        let instances = self.instances.clone();
        let pending_updates = self.pending_updates.clone();
        let heartbeat_timeout = self.heartbeat_timeout;

        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(30)).await;

                let now = now_utc();
                for mut instance in instances.iter_mut() {
                    let elapsed = now - instance.last_heartbeat;
                    if elapsed > chrono::Duration::from_std(heartbeat_timeout).unwrap() {
                        if instance.status == SidecarStatus::Running {
                            instance.status = SidecarStatus::Degraded;
                            tracing::warn!(instance_id = %instance.instance_id, "Sidecar心跳超时，标记为降级");
                        }
                    }
                }

                for update in pending_updates.iter() {
                    let elapsed = now - update.started_at;
                    if elapsed > chrono::Duration::from_std(update.max_duration).unwrap() {
                        if let Some(mut instance) = instances.get_mut(&update.instance_id) {
                            instance.status = SidecarStatus::Running;
                            tracing::warn!(instance_id = %update.instance_id, "Sidecar配置更新超时，回滚");
                        }
                        pending_updates.remove(update.key());
                    }
                }
            }
        });
    }

    pub fn get_status_summary(&self) -> SidecarStatusSummary {
        let mut summary = SidecarStatusSummary::default();

        for instance in self.instances.iter() {
            summary.total += 1;
            match instance.status {
                SidecarStatus::Running => summary.running += 1,
                SidecarStatus::Injecting => summary.injecting += 1,
                SidecarStatus::Updating => summary.updating += 1,
                SidecarStatus::Degraded => summary.degraded += 1,
                SidecarStatus::Terminating => summary.terminating += 1,
                SidecarStatus::Failed => summary.failed += 1,
                SidecarStatus::NotInjected => summary.not_injected += 1,
                SidecarStatus::Terminated => summary.terminated += 1,
            }
        }

        summary.pending_updates = self.pending_updates.len() as u32;
        summary
    }
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct SidecarStatusSummary {
    pub total: u32,
    pub running: u32,
    pub injecting: u32,
    pub updating: u32,
    pub degraded: u32,
    pub terminating: u32,
    pub terminated: u32,
    pub failed: u32,
    pub not_injected: u32,
    pub pending_updates: u32,
}
