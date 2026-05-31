use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum DeploymentStrategy {
    Canary,
    BlueGreen,
    Rolling,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CanaryConfig {
    pub percentage: u8,
    pub header_rules: HashMap<String, String>,
    pub cookie_rules: HashMap<String, String>,
    pub gradual_rollout: Option<GradualRollout>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GradualRollout {
    pub start_percentage: u8,
    pub target_percentage: u8,
    pub duration_seconds: u64,
    pub start_time: Option<chrono::DateTime<chrono::Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BlueGreenConfig {
    pub blue_version: String,
    pub green_version: String,
    pub active_environment: String,
    pub auto_switch_enabled: bool,
    pub health_check_threshold: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrafficMirrorConfig {
    pub enabled: bool,
    pub target_endpoints: Vec<String>,
    pub sample_rate: f64,
    pub timeout_ms: u64,
    pub include_headers: Vec<String>,
    pub exclude_paths: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CircuitBreakerConfig {
    pub enabled: bool,
    pub failure_threshold: u32,
    pub success_threshold: u32,
    pub timeout_seconds: u64,
    pub half_open_max_calls: u32,
    pub sliding_window_size: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum CircuitBreakerState {
    Closed,
    Open,
    HalfOpen,
}

#[derive(Debug, Clone)]
pub struct CircuitBreakerMetrics {
    pub state: CircuitBreakerState,
    pub failure_count: u32,
    pub success_count: u32,
    pub last_failure_time: Option<Instant>,
    pub open_time: Option<Instant>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TrafficPolicy {
    pub id: String,
    pub name: String,
    pub strategy: DeploymentStrategy,
    pub canary: Option<CanaryConfig>,
    pub blue_green: Option<BlueGreenConfig>,
    pub mirror: Option<TrafficMirrorConfig>,
    pub circuit_breaker: Option<CircuitBreakerConfig>,
    pub created_at: chrono::DateTime<chrono::Utc>,
    pub updated_at: chrono::DateTime<chrono::Utc>,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone)]
pub struct TrafficController {
    policies: Arc<Mutex<HashMap<String, TrafficPolicy>>>,
    circuit_breakers: Arc<Mutex<HashMap<String, CircuitBreakerMetrics>>>,
}

impl TrafficController {
    pub fn new() -> Self {
        Self {
            policies: Arc::new(Mutex::new(HashMap::new())),
            circuit_breakers: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn create_policy(&self, name: &str, strategy: DeploymentStrategy) -> TrafficPolicy {
        let id = Uuid::new_v4().to_string();
        let now = chrono::Utc::now();
        let policy = TrafficPolicy {
            id: id.clone(),
            name: name.to_string(),
            strategy,
            canary: None,
            blue_green: None,
            mirror: None,
            circuit_breaker: None,
            created_at: now,
            updated_at: now,
            labels: HashMap::new(),
        };
        
        let mut policies = self.policies.lock().unwrap();
        policies.insert(id, policy.clone());
        policy
    }

    pub fn configure_canary(&self, policy_id: &str, config: CanaryConfig) -> Option<TrafficPolicy> {
        let mut policies = self.policies.lock().unwrap();
        if let Some(policy) = policies.get_mut(policy_id) {
            policy.canary = Some(config);
            policy.updated_at = chrono::Utc::now();
            return Some(policy.clone());
        }
        None
    }

    pub fn configure_blue_green(&self, policy_id: &str, config: BlueGreenConfig) -> Option<TrafficPolicy> {
        let mut policies = self.policies.lock().unwrap();
        if let Some(policy) = policies.get_mut(policy_id) {
            policy.blue_green = Some(config);
            policy.updated_at = chrono::Utc::now();
            return Some(policy.clone());
        }
        None
    }

    pub fn configure_mirror(&self, policy_id: &str, config: TrafficMirrorConfig) -> Option<TrafficPolicy> {
        let mut policies = self.policies.lock().unwrap();
        if let Some(policy) = policies.get_mut(policy_id) {
            policy.mirror = Some(config);
            policy.updated_at = chrono::Utc::now();
            return Some(policy.clone());
        }
        None
    }

    pub fn configure_circuit_breaker(&self, policy_id: &str, config: CircuitBreakerConfig) -> Option<TrafficPolicy> {
        let mut policies = self.policies.lock().unwrap();
        if let Some(policy) = policies.get_mut(policy_id) {
            policy.circuit_breaker = Some(config);
            policy.updated_at = chrono::Utc::now();
            return Some(policy.clone());
        }
        None
    }

    pub fn get_policy(&self, policy_id: &str) -> Option<TrafficPolicy> {
        let policies = self.policies.lock().unwrap();
        policies.get(policy_id).cloned()
    }

    pub fn list_policies(&self) -> Vec<TrafficPolicy> {
        let policies = self.policies.lock().unwrap();
        policies.values().cloned().collect()
    }

    pub fn delete_policy(&self, policy_id: &str) -> bool {
        let mut policies = self.policies.lock().unwrap();
        policies.remove(policy_id).is_some()
    }

    pub fn should_route_to_canary(&self, policy_id: &str, headers: &HashMap<String, String>, cookies: &HashMap<String, String>) -> bool {
        let policies = self.policies.lock().unwrap();
        let policy = match policies.get(policy_id) {
            Some(p) => p,
            None => return false,
        };

        let canary = match &policy.canary {
            Some(c) => c,
            None => return false,
        };

        for (key, value) in &canary.header_rules {
            if headers.get(key) != Some(value) {
                return false;
            }
        }

        for (key, value) in &canary.cookie_rules {
            if cookies.get(key) != Some(value) {
                return false;
            }
        }

        let current_percentage = self.calculate_canary_percentage(canary);
        let hash = self.simple_hash(&format!("{:?}{:?}", headers, cookies));
        (hash % 100) < current_percentage as u64
    }

    fn calculate_canary_percentage(&self, canary: &CanaryConfig) -> u8 {
        match &canary.gradual_rollout {
            Some(rollout) => {
                if let Some(start_time) = rollout.start_time {
                    let elapsed = chrono::Utc::now() - start_time;
                    let progress = (elapsed.num_seconds() as f64) / (rollout.duration_seconds as f64);
                    let progress = progress.clamp(0.0, 1.0);
                    let delta = (rollout.target_percentage - rollout.start_percentage) as f64 * progress;
                    rollout.start_percentage + delta as u8
                } else {
                    rollout.start_percentage
                }
            }
            None => canary.percentage,
        }
    }

    fn simple_hash(&self, s: &str) -> u64 {
        let mut hash = 5381u64;
        for c in s.chars() {
            hash = ((hash << 5) + hash) + c as u64;
        }
        hash
    }

    pub fn switch_blue_green(&self, policy_id: &str, target_env: &str) -> Option<TrafficPolicy> {
        let mut policies = self.policies.lock().unwrap();
        if let Some(policy) = policies.get_mut(policy_id) {
            if let Some(bg) = &mut policy.blue_green {
                if target_env == "blue" || target_env == "green" {
                    bg.active_environment = target_env.to_string();
                    policy.updated_at = chrono::Utc::now();
                    return Some(policy.clone());
                }
            }
        }
        None
    }

    pub fn record_failure(&self, policy_id: &str) {
        let policies = self.policies.lock().unwrap();
        let policy = match policies.get(policy_id) {
            Some(p) => p,
            None => return,
        };

        let cb_config = match &policy.circuit_breaker {
            Some(c) if c.enabled => c,
            _ => return,
        };

        let mut cbs = self.circuit_breakers.lock().unwrap();
        let metrics = cbs.entry(policy_id.to_string()).or_insert(CircuitBreakerMetrics {
            state: CircuitBreakerState::Closed,
            failure_count: 0,
            success_count: 0,
            last_failure_time: None,
            open_time: None,
        });

        metrics.failure_count += 1;
        metrics.last_failure_time = Some(Instant::now());

        if metrics.state == CircuitBreakerState::Closed && metrics.failure_count >= cb_config.failure_threshold {
            metrics.state = CircuitBreakerState::Open;
            metrics.open_time = Some(Instant::now());
            metrics.failure_count = 0;
        }
    }

    pub fn record_success(&self, policy_id: &str) {
        let policies = self.policies.lock().unwrap();
        let policy = match policies.get(policy_id) {
            Some(p) => p,
            None => return,
        };

        let cb_config = match &policy.circuit_breaker {
            Some(c) if c.enabled => c,
            _ => return,
        };

        let mut cbs = self.circuit_breakers.lock().unwrap();
        let metrics = cbs.entry(policy_id.to_string()).or_insert(CircuitBreakerMetrics {
            state: CircuitBreakerState::Closed,
            failure_count: 0,
            success_count: 0,
            last_failure_time: None,
            open_time: None,
        });

        if metrics.state == CircuitBreakerState::HalfOpen {
            metrics.success_count += 1;
            if metrics.success_count >= cb_config.success_threshold {
                metrics.state = CircuitBreakerState::Closed;
                metrics.failure_count = 0;
                metrics.success_count = 0;
            }
        } else {
            metrics.failure_count = metrics.failure_count.saturating_sub(1);
        }
    }

    pub fn allow_request(&self, policy_id: &str) -> bool {
        let policies = self.policies.lock().unwrap();
        let policy = match policies.get(policy_id) {
            Some(p) => p,
            None => return true,
        };

        let cb_config = match &policy.circuit_breaker {
            Some(c) if c.enabled => c,
            _ => return true,
        };

        let mut cbs = self.circuit_breakers.lock().unwrap();
        let metrics = cbs.entry(policy_id.to_string()).or_insert(CircuitBreakerMetrics {
            state: CircuitBreakerState::Closed,
            failure_count: 0,
            success_count: 0,
            last_failure_time: None,
            open_time: None,
        });

        if metrics.state == CircuitBreakerState::Open {
            if let Some(open_time) = metrics.open_time {
                if open_time.elapsed() >= Duration::from_secs(cb_config.timeout_seconds) {
                    metrics.state = CircuitBreakerState::HalfOpen;
                    metrics.success_count = 0;
                    return true;
                }
            }
            return false;
        }

        true
    }

    pub fn get_circuit_breaker_state(&self, policy_id: &str) -> Option<CircuitBreakerMetrics> {
        let cbs = self.circuit_breakers.lock().unwrap();
        cbs.get(policy_id).cloned()
    }
}

impl Default for TrafficController {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_policy() {
        let controller = TrafficController::new();
        let policy = controller.create_policy("test-policy", DeploymentStrategy::Canary);
        
        assert_eq!(policy.name, "test-policy");
        assert_eq!(policy.strategy, DeploymentStrategy::Canary);
    }

    #[test]
    fn test_circuit_breaker() {
        let controller = TrafficController::new();
        let policy = controller.create_policy("cb-test", DeploymentStrategy::Rolling);
        
        let cb_config = CircuitBreakerConfig {
            enabled: true,
            failure_threshold: 3,
            success_threshold: 2,
            timeout_seconds: 10,
            half_open_max_calls: 5,
            sliding_window_size: 10,
        };
        
        controller.configure_circuit_breaker(&policy.id, cb_config);
        
        assert!(controller.allow_request(&policy.id));
        
        controller.record_failure(&policy.id);
        controller.record_failure(&policy.id);
        assert!(controller.allow_request(&policy.id));
        
        controller.record_failure(&policy.id);
        assert!(!controller.allow_request(&policy.id));
    }

    #[test]
    fn test_blue_green_switch() {
        let controller = TrafficController::new();
        let policy = controller.create_policy("bg-test", DeploymentStrategy::BlueGreen);
        
        let bg_config = BlueGreenConfig {
            blue_version: "v1".to_string(),
            green_version: "v2".to_string(),
            active_environment: "blue".to_string(),
            auto_switch_enabled: false,
            health_check_threshold: 5,
        };
        
        controller.configure_blue_green(&policy.id, bg_config);
        
        let updated = controller.switch_blue_green(&policy.id, "green");
        assert!(updated.is_some());
        assert_eq!(updated.unwrap().blue_green.unwrap().active_environment, "green");
    }
}
