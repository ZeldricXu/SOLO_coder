use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use uuid::Uuid;
use std::sync::{Arc, Mutex};
use chrono::{DateTime, Utc, Duration};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum FaultType {
    Latency,
    Error,
    Abort,
    Corruption,
    ResourceExhaustion,
    NetworkPartition,
    DiskFailure,
    MemoryLeak,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum InjectionScope {
    Global,
    Service,
    Endpoint,
    Instance,
    Region,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum FaultStatus {
    Pending,
    Active,
    Paused,
    Completed,
    Failed,
    RolledBack,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FaultTarget {
    pub scope: InjectionScope,
    pub service_name: Option<String>,
    pub endpoints: Vec<String>,
    pub instances: Vec<String>,
    pub regions: Vec<String>,
    pub selector_labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FaultParameters {
    pub latency_ms: Option<u64>,
    pub error_rate: Option<f64>,
    pub error_codes: Vec<u32>,
    pub error_message: Option<String>,
    pub memory_mb: Option<u32>,
    pub cpu_percent: Option<u8>,
    pub disk_error_rate: Option<f64>,
    pub network_drop_rate: Option<f64>,
    pub corruption_probability: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AutoRollbackConfig {
    pub enabled: bool,
    pub success_rate_threshold: f64,
    pub latency_threshold_ms: u64,
    pub error_rate_threshold: f64,
    pub observation_window_seconds: u64,
    pub check_interval_seconds: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FaultScenario {
    pub id: String,
    pub name: String,
    pub description: String,
    pub fault_type: FaultType,
    pub target: FaultTarget,
    pub parameters: FaultParameters,
    pub duration_seconds: Option<u64>,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub status: FaultStatus,
    pub auto_rollback: AutoRollbackConfig,
    pub created_at: DateTime<Utc>,
    pub created_by: String,
    pub tags: Vec<String>,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InjectionEvent {
    pub id: String,
    pub scenario_id: String,
    pub event_type: String,
    pub message: String,
    pub timestamp: DateTime<Utc>,
    pub details: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthMetrics {
    pub scenario_id: String,
    pub timestamp: DateTime<Utc>,
    pub success_rate: f64,
    pub average_latency_ms: u64,
    pub error_rate: f64,
    pub request_count: u64,
}

pub struct FaultInjector {
    scenarios: Arc<Mutex<HashMap<String, FaultScenario>>>,
    events: Arc<Mutex<Vec<InjectionEvent>>>,
    metrics: Arc<Mutex<HashMap<String, Vec<HealthMetrics>>>>,
    active_scenarios: Arc<Mutex<HashMap<String, tokio::task::JoinHandle<()>>>>,
}

impl std::fmt::Debug for FaultInjector {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("FaultInjector")
            .field("scenarios", &self.scenarios)
            .field("events", &self.events)
            .field("metrics", &self.metrics)
            .field("active_scenarios", &"[JoinHandle omitted]")
            .finish()
    }
}

impl FaultInjector {
    pub fn new() -> Self {
        Self {
            scenarios: Arc::new(Mutex::new(HashMap::new())),
            events: Arc::new(Mutex::new(Vec::new())),
            metrics: Arc::new(Mutex::new(HashMap::new())),
            active_scenarios: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn create_scenario(
        &self,
        name: &str,
        description: &str,
        fault_type: FaultType,
        target: FaultTarget,
        parameters: FaultParameters,
        auto_rollback: AutoRollbackConfig,
        created_by: &str,
    ) -> FaultScenario {
        let id = Uuid::new_v4().to_string();
        let now = Utc::now();

        let scenario = FaultScenario {
            id: id.clone(),
            name: name.to_string(),
            description: description.to_string(),
            fault_type,
            target,
            parameters,
            duration_seconds: None,
            start_time: None,
            end_time: None,
            status: FaultStatus::Pending,
            auto_rollback,
            created_at: now,
            created_by: created_by.to_string(),
            tags: Vec::new(),
            labels: HashMap::new(),
        };

        let mut scenarios = self.scenarios.lock().unwrap();
        scenarios.insert(id, scenario.clone());

        self.add_event(&scenario.id, "created", "Fault scenario created", HashMap::new());

        scenario
    }

    pub fn start_scenario(&self, scenario_id: &str, duration_seconds: Option<u64>) -> Result<FaultScenario, String> {
        let mut scenarios = self.scenarios.lock().unwrap();
        let scenario = scenarios.get_mut(scenario_id)
            .ok_or_else(|| "Scenario not found".to_string())?;

        if scenario.status != FaultStatus::Pending && scenario.status != FaultStatus::Paused {
            return Err("Scenario is not in a startable state".to_string());
        }

        scenario.status = FaultStatus::Active;
        scenario.start_time = Some(Utc::now());
        scenario.duration_seconds = duration_seconds;

        self.add_event(scenario_id, "started", "Fault injection started", HashMap::new());

        Ok(scenario.clone())
    }

    pub fn pause_scenario(&self, scenario_id: &str) -> Result<FaultScenario, String> {
        let mut scenarios = self.scenarios.lock().unwrap();
        let scenario = scenarios.get_mut(scenario_id)
            .ok_or_else(|| "Scenario not found".to_string())?;

        if scenario.status != FaultStatus::Active {
            return Err("Scenario is not active".to_string());
        }

        scenario.status = FaultStatus::Paused;

        self.add_event(scenario_id, "paused", "Fault injection paused", HashMap::new());

        Ok(scenario.clone())
    }

    pub fn stop_scenario(&self, scenario_id: &str) -> Result<FaultScenario, String> {
        let mut scenarios = self.scenarios.lock().unwrap();
        let scenario = scenarios.get_mut(scenario_id)
            .ok_or_else(|| "Scenario not found".to_string())?;

        if scenario.status == FaultStatus::Completed || scenario.status == FaultStatus::RolledBack {
            return Err("Scenario already completed".to_string());
        }

        scenario.status = FaultStatus::Completed;
        scenario.end_time = Some(Utc::now());

        self.add_event(scenario_id, "stopped", "Fault injection stopped", HashMap::new());

        Ok(scenario.clone())
    }

    pub fn rollback_scenario(&self, scenario_id: &str, reason: &str) -> Result<FaultScenario, String> {
        let mut scenarios = self.scenarios.lock().unwrap();
        let scenario = scenarios.get_mut(scenario_id)
            .ok_or_else(|| "Scenario not found".to_string())?;

        scenario.status = FaultStatus::RolledBack;
        scenario.end_time = Some(Utc::now());

        let mut details = HashMap::new();
        details.insert("reason".to_string(), reason.to_string());

        self.add_event(scenario_id, "rolled_back", "Fault injection rolled back", details);

        Ok(scenario.clone())
    }

    pub fn get_scenario(&self, scenario_id: &str) -> Option<FaultScenario> {
        let scenarios = self.scenarios.lock().unwrap();
        scenarios.get(scenario_id).cloned()
    }

    pub fn list_scenarios(&self) -> Vec<FaultScenario> {
        let scenarios = self.scenarios.lock().unwrap();
        scenarios.values().cloned().collect()
    }

    pub fn list_scenarios_by_status(&self, status: FaultStatus) -> Vec<FaultScenario> {
        let scenarios = self.scenarios.lock().unwrap();
        scenarios.values()
            .filter(|s| s.status == status)
            .cloned()
            .collect()
    }

    pub fn delete_scenario(&self, scenario_id: &str) -> bool {
        let mut scenarios = self.scenarios.lock().unwrap();
        if let Some(scenario) = scenarios.get(scenario_id) {
            if scenario.status == FaultStatus::Active {
                return false;
            }
        }
        scenarios.remove(scenario_id).is_some()
    }

    pub fn check_target_inclusion(&self, scenario_id: &str, target: &FaultTarget) -> Result<bool, String> {
        let scenarios = self.scenarios.lock().unwrap();
        let scenario = scenarios.get(scenario_id)
            .ok_or_else(|| "Scenario not found".to_string())?;

        if scenario.status != FaultStatus::Active {
            return Ok(false);
        }

        Ok(self.matches_target(&scenario.target, target))
    }

    fn matches_target(&self, rule: &FaultTarget, target: &FaultTarget) -> bool {
        match rule.scope {
            InjectionScope::Global => true,
            InjectionScope::Service => {
                if let Some(rule_service) = &rule.service_name {
                    if let Some(target_service) = &target.service_name {
                        if rule_service != target_service {
                            return false;
                        }
                    } else {
                        return false;
                    }
                }
                true
            }
            InjectionScope::Endpoint => {
                if !rule.endpoints.is_empty() {
                    return target.endpoints.iter().any(|e| rule.endpoints.contains(e));
                }
                true
            }
            InjectionScope::Instance => {
                if !rule.instances.is_empty() {
                    return target.instances.iter().any(|i| rule.instances.contains(i));
                }
                true
            }
            InjectionScope::Region => {
                if !rule.regions.is_empty() {
                    return target.regions.iter().any(|r| rule.regions.contains(r));
                }
                true
            }
        }
    }

    pub fn apply_fault(&self, scenario_id: &str, mut response: String) -> Result<String, String> {
        let scenarios = self.scenarios.lock().unwrap();
        let scenario = scenarios.get(scenario_id)
            .ok_or_else(|| "Scenario not found".to_string())?;

        if scenario.status != FaultStatus::Active {
            return Ok(response);
        }

        match scenario.fault_type {
            FaultType::Latency => {
                if let Some(ms) = scenario.parameters.latency_ms {
                    std::thread::sleep(std::time::Duration::from_millis(ms));
                }
            }
            FaultType::Error => {
                if let Some(rate) = scenario.parameters.error_rate {
                    use rand::Rng;
                    let mut rng = rand::thread_rng();
                    if rng.gen::<f64>() < rate {
                        return Err(scenario.parameters.error_message.clone()
                            .unwrap_or_else(|| "Injected error".to_string()));
                    }
                }
            }
            FaultType::Abort => {
                return Err("Connection aborted by fault injection".to_string());
            }
            FaultType::Corruption => {
                if let Some(prob) = scenario.parameters.corruption_probability {
                    use rand::Rng;
                    let mut rng = rand::thread_rng();
                    if rng.gen::<f64>() < prob {
                        let bytes: Vec<u8> = response.bytes().map(|b| b.wrapping_add(rng.gen())).collect();
                        response = String::from_utf8_lossy(&bytes).to_string();
                    }
                }
            }
            _ => {}
        }

        Ok(response)
    }

    pub fn record_health_metrics(&self, scenario_id: &str, metrics: HealthMetrics) {
        let mut all_metrics = self.metrics.lock().unwrap();
        let scenario_metrics = all_metrics.entry(scenario_id.to_string()).or_insert_with(Vec::new);
        scenario_metrics.push(metrics);
    }

    pub fn check_auto_rollback(&self, scenario_id: &str) -> Result<bool, String> {
        let scenarios = self.scenarios.lock().unwrap();
        let scenario = scenarios.get(scenario_id)
            .ok_or_else(|| "Scenario not found".to_string())?;

        if !scenario.auto_rollback.enabled || scenario.status != FaultStatus::Active {
            return Ok(false);
        }

        let all_metrics = self.metrics.lock().unwrap();
        let scenario_metrics = match all_metrics.get(scenario_id) {
            Some(m) => m,
            None => return Ok(false),
        };

        let window = scenario.auto_rollback.observation_window_seconds;
        let cutoff = Utc::now() - Duration::seconds(window as i64);

        let recent_metrics: Vec<&HealthMetrics> = scenario_metrics.iter()
            .filter(|m| m.timestamp > cutoff)
            .collect();

        if recent_metrics.is_empty() {
            return Ok(false);
        }

        let avg_success_rate: f64 = recent_metrics.iter().map(|m| m.success_rate).sum::<f64>() / recent_metrics.len() as f64;
        let avg_latency: u64 = recent_metrics.iter().map(|m| m.average_latency_ms).sum::<u64>() / recent_metrics.len() as u64;
        let avg_error_rate: f64 = recent_metrics.iter().map(|m| m.error_rate).sum::<f64>() / recent_metrics.len() as f64;

        let should_rollback = 
            avg_success_rate < scenario.auto_rollback.success_rate_threshold ||
            avg_latency > scenario.auto_rollback.latency_threshold_ms ||
            avg_error_rate > scenario.auto_rollback.error_rate_threshold;

        Ok(should_rollback)
    }

    fn add_event(&self, scenario_id: &str, event_type: &str, message: &str, details: HashMap<String, String>) {
        let event = InjectionEvent {
            id: Uuid::new_v4().to_string(),
            scenario_id: scenario_id.to_string(),
            event_type: event_type.to_string(),
            message: message.to_string(),
            timestamp: Utc::now(),
            details,
        };

        let mut events = self.events.lock().unwrap();
        events.push(event);
    }

    pub fn get_events(&self, scenario_id: &str) -> Vec<InjectionEvent> {
        let events = self.events.lock().unwrap();
        events.iter()
            .filter(|e| e.scenario_id == scenario_id)
            .cloned()
            .collect()
    }

    pub fn get_health_metrics(&self, scenario_id: &str) -> Vec<HealthMetrics> {
        let all_metrics = self.metrics.lock().unwrap();
        all_metrics.get(scenario_id).cloned().unwrap_or_default()
    }

    pub fn update_scenario(
        &self,
        scenario_id: &str,
        parameters: FaultParameters,
    ) -> Result<FaultScenario, String> {
        let mut scenarios = self.scenarios.lock().unwrap();
        let scenario = scenarios.get_mut(scenario_id)
            .ok_or_else(|| "Scenario not found".to_string())?;

        if scenario.status == FaultStatus::Active {
            return Err("Cannot update active scenario".to_string());
        }

        scenario.parameters = parameters;
        Ok(scenario.clone())
    }

    pub fn add_tag(&self, scenario_id: &str, tag: &str) -> Result<(), String> {
        let mut scenarios = self.scenarios.lock().unwrap();
        let scenario = scenarios.get_mut(scenario_id)
            .ok_or_else(|| "Scenario not found".to_string())?;

        if !scenario.tags.contains(&tag.to_string()) {
            scenario.tags.push(tag.to_string());
        }
        Ok(())
    }

    pub fn add_label(&self, scenario_id: &str, key: &str, value: &str) -> Result<(), String> {
        let mut scenarios = self.scenarios.lock().unwrap();
        let scenario = scenarios.get_mut(scenario_id)
            .ok_or_else(|| "Scenario not found".to_string())?;

        scenario.labels.insert(key.to_string(), value.to_string());
        Ok(())
    }
}

impl Default for FaultInjector {
    fn default() -> Self {
        Self::new()
    }
}

pub fn create_latency_fault(target_service: &str, latency_ms: u64) -> (FaultTarget, FaultParameters) {
    let target = FaultTarget {
        scope: InjectionScope::Service,
        service_name: Some(target_service.to_string()),
        endpoints: vec![],
        instances: vec![],
        regions: vec![],
        selector_labels: HashMap::new(),
    };

    let parameters = FaultParameters {
        latency_ms: Some(latency_ms),
        error_rate: None,
        error_codes: vec![],
        error_message: None,
        memory_mb: None,
        cpu_percent: None,
        disk_error_rate: None,
        network_drop_rate: None,
        corruption_probability: None,
    };

    (target, parameters)
}

pub fn create_error_fault(endpoint: &str, error_rate: f64, error_message: &str) -> (FaultTarget, FaultParameters) {
    let target = FaultTarget {
        scope: InjectionScope::Endpoint,
        service_name: None,
        endpoints: vec![endpoint.to_string()],
        instances: vec![],
        regions: vec![],
        selector_labels: HashMap::new(),
    };

    let parameters = FaultParameters {
        latency_ms: None,
        error_rate: Some(error_rate),
        error_codes: vec![500],
        error_message: Some(error_message.to_string()),
        memory_mb: None,
        cpu_percent: None,
        disk_error_rate: None,
        network_drop_rate: None,
        corruption_probability: None,
    };

    (target, parameters)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_create_scenario() {
        let injector = FaultInjector::new();
        let (target, params) = create_latency_fault("api-service", 500);

        let auto_rollback = AutoRollbackConfig {
            enabled: true,
            success_rate_threshold: 0.95,
            latency_threshold_ms: 1000,
            error_rate_threshold: 0.05,
            observation_window_seconds: 60,
            check_interval_seconds: 10,
        };

        let scenario = injector.create_scenario(
            "Latency Test",
            "Test service latency",
            FaultType::Latency,
            target,
            params,
            auto_rollback,
            "test-user",
        );

        assert_eq!(scenario.name, "Latency Test");
        assert_eq!(scenario.fault_type, FaultType::Latency);
        assert_eq!(scenario.status, FaultStatus::Pending);
        assert_eq!(scenario.created_by, "test-user");
    }

    #[test]
    fn test_start_scenario() {
        let injector = FaultInjector::new();
        let (target, params) = create_latency_fault("api-service", 100);

        let auto_rollback = AutoRollbackConfig {
            enabled: false,
            success_rate_threshold: 0.95,
            latency_threshold_ms: 1000,
            error_rate_threshold: 0.05,
            observation_window_seconds: 60,
            check_interval_seconds: 10,
        };

        let scenario = injector.create_scenario(
            "Test",
            "Test",
            FaultType::Latency,
            target,
            params,
            auto_rollback,
            "user",
        );

        let result = injector.start_scenario(&scenario.id, Some(300));
        assert!(result.is_ok());
        
        let started = result.unwrap();
        assert_eq!(started.status, FaultStatus::Active);
        assert!(started.start_time.is_some());
    }

    #[test]
    fn test_pause_scenario() {
        let injector = FaultInjector::new();
        let (target, params) = create_latency_fault("api-service", 100);

        let auto_rollback = AutoRollbackConfig {
            enabled: false,
            success_rate_threshold: 0.95,
            latency_threshold_ms: 1000,
            error_rate_threshold: 0.05,
            observation_window_seconds: 60,
            check_interval_seconds: 10,
        };

        let scenario = injector.create_scenario(
            "Test",
            "Test",
            FaultType::Latency,
            target,
            params,
            auto_rollback,
            "user",
        );

        injector.start_scenario(&scenario.id, None).unwrap();
        let result = injector.pause_scenario(&scenario.id);
        
        assert!(result.is_ok());
        assert_eq!(result.unwrap().status, FaultStatus::Paused);
    }

    #[test]
    fn test_rollback_scenario() {
        let injector = FaultInjector::new();
        let (target, params) = create_latency_fault("api-service", 100);

        let auto_rollback = AutoRollbackConfig {
            enabled: false,
            success_rate_threshold: 0.95,
            latency_threshold_ms: 1000,
            error_rate_threshold: 0.05,
            observation_window_seconds: 60,
            check_interval_seconds: 10,
        };

        let scenario = injector.create_scenario(
            "Test",
            "Test",
            FaultType::Latency,
            target,
            params,
            auto_rollback,
            "user",
        );

        injector.start_scenario(&scenario.id, None).unwrap();
        let result = injector.rollback_scenario(&scenario.id, "Health check failed");
        
        assert!(result.is_ok());
        let rolled_back = result.unwrap();
        assert_eq!(rolled_back.status, FaultStatus::RolledBack);
        assert!(rolled_back.end_time.is_some());
    }

    #[test]
    fn test_apply_fault_latency() {
        let injector = FaultInjector::new();
        let (target, params) = create_latency_fault("api-service", 10);

        let auto_rollback = AutoRollbackConfig {
            enabled: false,
            success_rate_threshold: 0.95,
            latency_threshold_ms: 1000,
            error_rate_threshold: 0.05,
            observation_window_seconds: 60,
            check_interval_seconds: 10,
        };

        let scenario = injector.create_scenario(
            "Latency Test",
            "Test",
            FaultType::Latency,
            target,
            params,
            auto_rollback,
            "user",
        );

        injector.start_scenario(&scenario.id, None).unwrap();
        
        let start = std::time::Instant::now();
        let result = injector.apply_fault(&scenario.id, "response".to_string());
        let elapsed = start.elapsed();
        
        assert!(result.is_ok());
        assert!(elapsed.as_millis() >= 10);
    }

    #[test]
    fn test_get_events() {
        let injector = FaultInjector::new();
        let (target, params) = create_latency_fault("api-service", 100);

        let auto_rollback = AutoRollbackConfig {
            enabled: false,
            success_rate_threshold: 0.95,
            latency_threshold_ms: 1000,
            error_rate_threshold: 0.05,
            observation_window_seconds: 60,
            check_interval_seconds: 10,
        };

        let scenario = injector.create_scenario(
            "Test",
            "Test",
            FaultType::Latency,
            target,
            params,
            auto_rollback,
            "user",
        );

        let events = injector.get_events(&scenario.id);
        assert!(!events.is_empty());
        assert_eq!(events[0].event_type, "created");
    }

    #[test]
    fn test_list_scenarios_by_status() {
        let injector = FaultInjector::new();
        let (target, params) = create_latency_fault("api-service", 100);

        let auto_rollback = AutoRollbackConfig {
            enabled: false,
            success_rate_threshold: 0.95,
            latency_threshold_ms: 1000,
            error_rate_threshold: 0.05,
            observation_window_seconds: 60,
            check_interval_seconds: 10,
        };

        let scenario1 = injector.create_scenario(
            "Test 1",
            "Test",
            FaultType::Latency,
            target.clone(),
            params.clone(),
            auto_rollback.clone(),
            "user",
        );

        let scenario2 = injector.create_scenario(
            "Test 2",
            "Test",
            FaultType::Error,
            target,
            params,
            auto_rollback,
            "user",
        );

        injector.start_scenario(&scenario1.id, None).unwrap();

        let active = injector.list_scenarios_by_status(FaultStatus::Active);
        assert_eq!(active.len(), 1);
        assert_eq!(active[0].id, scenario1.id);

        let pending = injector.list_scenarios_by_status(FaultStatus::Pending);
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].id, scenario2.id);
    }
}
