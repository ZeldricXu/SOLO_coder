use crate::types::{AppError, generate_id, now_utc};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tracing;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrafficPolicy {
    pub policy_id: String,
    pub name: String,
    pub policy_type: PolicyType,
    pub target_service: String,
    pub rules: TrafficRules,
    pub status: PolicyStatus,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum PolicyType {
    Canary,
    BlueGreen,
    Mirroring,
    CircuitBreaker,
    RateLimit,
    Retry,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum TrafficRules {
    Canary(CanaryRules),
    BlueGreen(BlueGreenRules),
    Mirroring(MirroringRules),
    CircuitBreaker(CircuitBreakerRules),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CanaryRules {
    pub versions: Vec<ServiceVersion>,
    pub traffic_split: HashMap<String, u32>,
    pub match_rules: Vec<MatchRule>,
    pub rollout_strategy: RolloutStrategy,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServiceVersion {
    pub version: String,
    pub endpoint: String,
    pub weight: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MatchRule {
    pub header: Option<String>,
    pub header_value: Option<String>,
    pub cookie: Option<String>,
    pub query_param: Option<String>,
    pub user_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RolloutStrategy {
    pub initial_percentage: u8,
    pub target_percentage: u8,
    pub step_percentage: u8,
    pub step_interval: Duration,
    pub auto_rollback_on_error: bool,
    pub error_threshold: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BlueGreenRules {
    pub blue_version: String,
    pub blue_endpoint: String,
    pub green_version: String,
    pub green_endpoint: String,
    pub active_environment: Environment,
    pub switch_strategy: SwitchStrategy,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Environment {
    Blue,
    Green,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SwitchStrategy {
    pub switch_type: SwitchType,
    pub validation_endpoint: Option<String>,
    pub rollback_on_failure: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum SwitchType {
    Immediate,
    Gradual,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MirroringRules {
    pub source_service: String,
    pub target_services: Vec<String>,
    pub mirror_percentage: u8,
    pub include_headers: Vec<String>,
    pub exclude_headers: Vec<String>,
    pub timeout: Duration,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CircuitBreakerRules {
    pub failure_threshold: u32,
    pub success_threshold: u32,
    pub timeout_duration: Duration,
    pub half_open_limit: u32,
    pub fallback_service: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum PolicyStatus {
    Draft,
    Active,
    Paused,
    RollingBack,
    Completed,
    Failed,
}

pub struct CircuitBreakerState {
    state: CircuitBreakerStatus,
    failure_count: u32,
    success_count: u32,
    last_state_change: Instant,
    policy_id: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CircuitBreakerStatus {
    Closed,
    Open,
    HalfOpen,
}

pub struct TrafficController {
    policies: Arc<DashMap<String, TrafficPolicy>>,
    circuit_breakers: Arc<DashMap<String, CircuitBreakerState>>,
    canary_progress: Arc<DashMap<String, CanaryProgress>>,
    request_stats: Arc<DashMap<String, RequestStats>>,
}

#[derive(Debug, Clone, Serialize)]
pub struct CanaryProgress {
    pub policy_id: String,
    pub current_percentage: u8,
    pub target_percentage: u8,
    pub last_step: DateTime<Utc>,
    pub error_rate: f64,
}

#[derive(Debug, Clone, Default)]
pub struct RequestStats {
    pub total_requests: u64,
    pub success_requests: u64,
    pub failed_requests: u64,
    pub total_latency_ms: u64,
}

impl TrafficController {
    pub fn new() -> Self {
        let controller = Self {
            policies: Arc::new(DashMap::new()),
            circuit_breakers: Arc::new(DashMap::new()),
            canary_progress: Arc::new(DashMap::new()),
            request_stats: Arc::new(DashMap::new()),
        };

        controller.start_background_tasks();
        controller
    }

    pub fn create_policy(&self, create: TrafficPolicyCreate) -> Result<TrafficPolicy, AppError> {
        let policy_id = generate_id("tfc");
        let now = now_utc();

        let policy = TrafficPolicy {
            policy_id: policy_id.clone(),
            name: create.name,
            policy_type: create.policy_type,
            target_service: create.target_service,
            rules: create.rules,
            status: PolicyStatus::Draft,
            created_at: now,
            updated_at: now,
        };

        self.policies.insert(policy_id.clone(), policy.clone());
        
        if let TrafficRules::CircuitBreaker(_) = policy.rules {
            self.circuit_breakers.insert(
                policy.target_service.clone(),
                CircuitBreakerState {
                    state: CircuitBreakerStatus::Closed,
                    failure_count: 0,
                    success_count: 0,
                    last_state_change: Instant::now(),
                    policy_id: policy_id.clone(),
                },
            );
        }

        tracing::info!(policy_id = %policy_id, "创建流量策略");
        Ok(policy)
    }

    pub fn get_policy(&self, policy_id: &str) -> Option<TrafficPolicy> {
        self.policies.get(policy_id).map(|p| p.clone())
    }

    pub fn list_policies(&self) -> Vec<TrafficPolicy> {
        self.policies.iter().map(|p| p.clone()).collect()
    }

    pub async fn activate_policy(&self, policy_id: &str) -> Result<TrafficPolicy, AppError> {
        let mut policy = self.policies.get_mut(policy_id)
            .ok_or_else(|| AppError::NotFound(format!("流量策略不存在: {}", policy_id)))?;

        if policy.status != PolicyStatus::Draft && policy.status != PolicyStatus::Paused {
            return Err(AppError::Conflict(format!("策略状态不允许激活: {:?}", policy.status)));
        }

        policy.status = PolicyStatus::Active;
        policy.updated_at = now_utc();

        let policy_clone = policy.clone();
        drop(policy);

        if let TrafficRules::Canary(canary) = &policy_clone.rules {
            self.start_canary_rollout(policy_id, canary);
        }

        tracing::info!(policy_id = %policy_id, "激活流量策略");
        Ok(policy_clone)
    }

    pub fn deactivate_policy(&self, policy_id: &str) -> Result<TrafficPolicy, AppError> {
        let mut policy = self.policies.get_mut(policy_id)
            .ok_or_else(|| AppError::NotFound(format!("流量策略不存在: {}", policy_id)))?;

        policy.status = PolicyStatus::Paused;
        policy.updated_at = now_utc();

        Ok(policy.clone())
    }

    pub fn delete_policy(&self, policy_id: &str) -> Result<(), AppError> {
        let policy = self.policies.get(policy_id)
            .ok_or_else(|| AppError::NotFound(format!("流量策略不存在: {}", policy_id)))?;

        if policy.status == PolicyStatus::Active {
            return Err(AppError::Conflict("活动策略不允许删除".to_string()));
        }

        self.policies.remove(policy_id);
        self.circuit_breakers.remove(&policy.target_service);
        self.canary_progress.remove(policy_id);
        Ok(())
    }

    pub fn route_request(
        &self,
        service: &str,
        headers: &HashMap<String, String>,
        cookies: &HashMap<String, String>,
        query_params: &HashMap<String, String>,
    ) -> Result<RouteDecision, AppError> {
        if let Some(cb) = self.circuit_breakers.get(service) {
            if cb.state == CircuitBreakerStatus::Open {
                if let Some(policy) = self.policies.get(&cb.policy_id) {
                    if let TrafficRules::CircuitBreaker(rules) = &policy.rules {
                        if let Some(fallback) = &rules.fallback_service {
                            return Ok(RouteDecision::Fallback(fallback.clone()));
                        }
                    }
                }
                return Err(AppError::InternalError("服务熔断".to_string()));
            }
        }

        for entry in self.policies.iter() {
            let policy = entry.value();
            if policy.target_service != service || policy.status != PolicyStatus::Active {
                continue;
            }

            match &policy.rules {
                TrafficRules::Canary(canary) => {
                    return Ok(self.route_canary(service, canary, headers, cookies, query_params));
                }
                TrafficRules::BlueGreen(blue_green) => {
                    return Ok(self.route_blue_green(blue_green));
                }
                _ => {}
            }
        }

        Ok(RouteDecision::Primary(service.to_string()))
    }

    fn route_canary(
        &self,
        service: &str,
        canary: &CanaryRules,
        headers: &HashMap<String, String>,
        cookies: &HashMap<String, String>,
        query_params: &HashMap<String, String>,
    ) -> RouteDecision {
        for rule in &canary.match_rules {
            if let Some(header_name) = &rule.header {
                if let Some(expected) = &rule.header_value {
                    if headers.get(header_name).map(|v| v.contains(expected)).unwrap_or(false) {
                        return self.select_version_by_weight(&canary.versions);
                    }
                }
            }
            if let Some(cookie_name) = &rule.cookie {
                if cookies.get(cookie_name).is_some() {
                    return self.select_version_by_weight(&canary.versions);
                }
            }
            if let Some(param_name) = &rule.query_param {
                if query_params.get(param_name).is_some() {
                    return self.select_version_by_weight(&canary.versions);
                }
            }
            if let Some(_user_id) = &rule.user_id {
                if headers.get("x-user-id").is_some() {
                    return self.select_version_by_weight(&canary.versions);
                }
            }
        }

        if let Some(progress) = self.canary_progress.get(&service) {
            let roll = rand::random::<u8>() % 100;
            if roll < progress.current_percentage {
                return self.select_version_by_weight(&canary.versions);
            }
        }

        RouteDecision::Primary(service.to_string())
    }

    fn select_version_by_weight(&self, versions: &[ServiceVersion]) -> RouteDecision {
        let total_weight: u32 = versions.iter().map(|v| v.weight).sum();
        let mut roll = rand::random::<u32>() % total_weight;

        for version in versions {
            if roll < version.weight {
                return RouteDecision::Canary {
                    version: version.version.clone(),
                    endpoint: version.endpoint.clone(),
                };
            }
            roll -= version.weight;
        }

        RouteDecision::Canary {
            version: versions[0].version.clone(),
            endpoint: versions[0].endpoint.clone(),
        }
    }

    fn route_blue_green(&self, blue_green: &BlueGreenRules) -> RouteDecision {
        match blue_green.active_environment {
            Environment::Blue => RouteDecision::BlueGreen {
                environment: Environment::Blue,
                endpoint: blue_green.blue_endpoint.clone(),
            },
            Environment::Green => RouteDecision::BlueGreen {
                environment: Environment::Green,
                endpoint: blue_green.green_endpoint.clone(),
            },
        }
    }

    pub async fn switch_blue_green(&self, policy_id: &str) -> Result<TrafficPolicy, AppError> {
        let mut policy = self.policies.get_mut(policy_id)
            .ok_or_else(|| AppError::NotFound(format!("流量策略不存在: {}", policy_id)))?;

        if policy.policy_type != PolicyType::BlueGreen {
            return Err(AppError::ValidationError("策略不是蓝绿部署类型".to_string()));
        }

        if let TrafficRules::BlueGreen(rules) = &mut policy.rules {
            rules.active_environment = match rules.active_environment {
                Environment::Blue => Environment::Green,
                Environment::Green => Environment::Blue,
            };
        }

        policy.updated_at = now_utc();
        Ok(policy.clone())
    }

    pub fn record_request(&self, service: &str, success: bool, latency_ms: u64) {
        let mut stats = self.request_stats
            .entry(service.to_string())
            .or_default();
        stats.total_requests += 1;
        if success {
            stats.success_requests += 1;
        } else {
            stats.failed_requests += 1;
        }
        stats.total_latency_ms += latency_ms;

        if let Some(mut cb) = self.circuit_breakers.get_mut(service) {
            if let Some(policy) = self.policies.get(&cb.policy_id) {
                if let TrafficRules::CircuitBreaker(rules) = &policy.rules {
                    if !success {
                        cb.failure_count += 1;
                        cb.success_count = 0;
                        if cb.failure_count >= rules.failure_threshold && cb.state == CircuitBreakerStatus::Closed {
                            cb.state = CircuitBreakerStatus::Open;
                            cb.last_state_change = Instant::now();
                            tracing::warn!(service = %service, "熔断器打开");
                        }
                    } else if cb.state == CircuitBreakerStatus::HalfOpen {
                        cb.success_count += 1;
                        if cb.success_count >= rules.success_threshold {
                            cb.state = CircuitBreakerStatus::Closed;
                            cb.failure_count = 0;
                            cb.last_state_change = Instant::now();
                            tracing::info!(service = %service, "熔断器关闭");
                        }
                    }
                }
            }
        }
    }

    pub fn get_mirror_targets(&self, service: &str) -> Vec<String> {
        let mut targets = Vec::new();
        for entry in self.policies.iter() {
            let policy = entry.value();
            if policy.status != PolicyStatus::Active {
                continue;
            }
            if let TrafficRules::Mirroring(rules) = &policy.rules {
                if rules.source_service == service {
                    if rand::random::<u8>() % 100 < rules.mirror_percentage {
                        targets.extend(rules.target_services.clone());
                    }
                }
            }
        }
        targets
    }

    pub fn get_circuit_breaker_status(&self, service: &str) -> Option<CircuitBreakerStatus> {
        self.circuit_breakers.get(service).map(|cb| cb.state.clone())
    }

    pub fn get_canary_progress(&self, policy_id: &str) -> Option<CanaryProgress> {
        self.canary_progress.get(policy_id).map(|p| p.clone())
    }

    fn start_canary_rollout(&self, policy_id: &str, canary: &CanaryRules) {
        let policy_id = policy_id.to_string();
        let progress = CanaryProgress {
            policy_id: policy_id.clone(),
            current_percentage: canary.rollout_strategy.initial_percentage,
            target_percentage: canary.rollout_strategy.target_percentage,
            last_step: now_utc(),
            error_rate: 0.0,
        };
        self.canary_progress.insert(policy_id.clone(), progress);
    }

    fn start_background_tasks(&self) {
        let circuit_breakers = self.circuit_breakers.clone();
        let policies = self.policies.clone();
        let request_stats = self.request_stats.clone();
        let canary_progress = self.canary_progress.clone();

        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(10)).await;

                for mut cb in circuit_breakers.iter_mut() {
                    if cb.state == CircuitBreakerStatus::Open {
                        if let Some(policy) = policies.get(&cb.policy_id) {
                            if let TrafficRules::CircuitBreaker(rules) = &policy.rules {
                                if cb.last_state_change.elapsed() >= rules.timeout_duration {
                                    cb.state = CircuitBreakerStatus::HalfOpen;
                                    cb.success_count = 0;
                                    cb.last_state_change = Instant::now();
                                    tracing::info!(service = %cb.policy_id, "熔断器进入半开状态");
                                }
                            }
                        }
                    }
                }

                for mut progress in canary_progress.iter_mut() {
                    if let Some(policy) = policies.get(&progress.policy_id) {
                        if let TrafficRules::Canary(rules) = &policy.rules {
                            let error_rate = request_stats.get(&policy.target_service)
                                .map(|s| {
                                    if s.total_requests > 0 {
                                        s.failed_requests as f64 / s.total_requests as f64
                                    } else {
                                        0.0
                                    }
                                })
                                .unwrap_or(0.0);
                            progress.error_rate = error_rate;

                            if rules.rollout_strategy.auto_rollback_on_error 
                                && error_rate > rules.rollout_strategy.error_threshold 
                                && progress.current_percentage > 0 {
                                progress.current_percentage = 0;
                                tracing::warn!(policy_id = %progress.policy_id, "金丝雀发布自动回滚，错误率: {}", error_rate);
                                continue;
                            }

                            if progress.current_percentage < progress.target_percentage {
                                let elapsed = progress.last_step.elapsed();
                                if elapsed >= rules.rollout_strategy.step_interval {
                                    let next_step = (progress.current_percentage + rules.rollout_strategy.step_percentage)
                                        .min(progress.target_percentage);
                                    progress.current_percentage = next_step;
                                    progress.last_step = now_utc();
                                    tracing::info!(policy_id = %progress.policy_id, "金丝雀发布进度: {}%", next_step);
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    pub fn get_request_stats(&self, service: &str) -> Option<RequestStats> {
        self.request_stats.get(service).map(|s| s.clone())
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", content = "data")]
pub enum RouteDecision {
    Primary(String),
    Canary { version: String, endpoint: String },
    BlueGreen { environment: Environment, endpoint: String },
    Fallback(String),
}

#[derive(Debug, Deserialize)]
pub struct TrafficPolicyCreate {
    pub name: String,
    pub policy_type: PolicyType,
    pub target_service: String,
    pub rules: TrafficRules,
}
