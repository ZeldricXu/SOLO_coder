use crate::types::{AppError, Event, generate_id, now_utc};
use crate::event_store::EventStore;
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Mutex;
use tracing;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FaultScenario {
    pub scenario_id: String,
    pub name: String,
    pub description: String,
    pub fault_type: FaultType,
    pub scope: InjectionScope,
    pub parameters: HashMap<String, serde_json::Value>,
    pub duration: Duration,
    pub auto_rollback: bool,
    pub status: ScenarioStatus,
    pub created_at: DateTime<Utc>,
    pub started_at: Option<DateTime<Utc>>,
    pub expires_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FaultType {
    Latency,
    Error,
    PacketLoss,
    ResourceExhaustion,
    DataCorruption,
    NetworkPartition,
    ServiceDown,
    SlowQuery,
    MemoryLeak,
    CpuSpike,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InjectionScope {
    pub namespaces: HashSet<String>,
    pub services: HashSet<String>,
    pub endpoints: HashSet<String>,
    pub labels: HashMap<String, String>,
    pub percentage: u8,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ScenarioStatus {
    Draft,
    Scheduled,
    Active,
    Paused,
    Completed,
    RolledBack,
    Failed,
}

#[derive(Debug, Clone, Serialize)]
pub struct ActiveInjection {
    pub scenario_id: String,
    pub fault_type: FaultType,
    pub started_at: DateTime<Utc>,
    pub expires_at: DateTime<Utc>,
    pub affected_requests: u64,
}

pub struct FaultInjectionOrchestrator {
    event_store: Arc<EventStore>,
    scenarios: Arc<DashMap<String, FaultScenario>>,
    active_injections: Arc<DashMap<String, ActiveInjection>>,
    rollback_handlers: Arc<DashMap<String, Vec<Box<dyn Fn() + Send + Sync>>>>,
}

impl FaultInjectionOrchestrator {
    pub fn new(event_store: Arc<EventStore>) -> Self {
        Self {
            event_store,
            scenarios: Arc::new(DashMap::new()),
            active_injections: Arc::new(DashMap::new()),
            rollback_handlers: Arc::new(DashMap::new()),
        }
    }

    pub fn create_scenario(&self, scenario: FaultScenarioCreate) -> Result<FaultScenario, AppError> {
        let scenario_id = generate_id("flt");
        let now = now_utc();

        let scenario = FaultScenario {
            scenario_id: scenario_id.clone(),
            name: scenario.name,
            description: scenario.description,
            fault_type: scenario.fault_type,
            scope: scenario.scope,
            parameters: scenario.parameters,
            duration: scenario.duration,
            auto_rollback: scenario.auto_rollback,
            status: ScenarioStatus::Draft,
            created_at: now,
            started_at: None,
            expires_at: None,
        };

        self.scenarios.insert(scenario_id.clone(), scenario.clone());
        tracing::info!(scenario_id = %scenario_id, "创建故障场景");
        Ok(scenario)
    }

    pub fn get_scenario(&self, scenario_id: &str) -> Option<FaultScenario> {
        self.scenarios.get(scenario_id).map(|s| s.clone())
    }

    pub fn list_scenarios(&self) -> Vec<FaultScenario> {
        self.scenarios.iter().map(|s| s.clone()).collect()
    }

    pub async fn start_scenario(&self, scenario_id: &str) -> Result<FaultScenario, AppError> {
        let mut scenario = self.scenarios.get_mut(scenario_id)
            .ok_or_else(|| AppError::NotFound(format!("故障场景不存在: {}", scenario_id)))?;

        if scenario.status != ScenarioStatus::Draft && scenario.status != ScenarioStatus::Scheduled {
            return Err(AppError::Conflict(format!("场景状态不允许启动: {:?}", scenario.status)));
        }

        let now = now_utc();
        scenario.status = ScenarioStatus::Active;
        scenario.started_at = Some(now);
        scenario.expires_at = Some(now + scenario.duration);

        let active_injection = ActiveInjection {
            scenario_id: scenario_id.to_string(),
            fault_type: scenario.fault_type.clone(),
            started_at: now,
            expires_at: scenario.expires_at.unwrap(),
            affected_requests: 0,
        };

        self.active_injections.insert(scenario_id.to_string(), active_injection);

        let scenario_clone = scenario.clone();
        drop(scenario);

        self.event_store
            .create_event(
                &format!("scenario:{}", scenario_id),
                "fault.started",
                serde_json::json!({
                    "scenario_id": scenario_id,
                    "fault_type": scenario_clone.fault_type,
                    "duration_secs": scenario_clone.duration.as_secs(),
                }),
                None,
            )
            .await?;

        if scenario_clone.auto_rollback {
            self.schedule_auto_rollback(scenario_id, scenario_clone.duration);
        }

        Ok(scenario_clone)
    }

    pub async fn stop_scenario(&self, scenario_id: &str) -> Result<FaultScenario, AppError> {
        let mut scenario = self.scenarios.get_mut(scenario_id)
            .ok_or_else(|| AppError::NotFound(format!("故障场景不存在: {}", scenario_id)))?;

        if scenario.status != ScenarioStatus::Active && scenario.status != ScenarioStatus::Paused {
            return Err(AppError::Conflict(format!("场景状态不允许停止: {:?}", scenario.status)));
        }

        scenario.status = ScenarioStatus::Completed;
        self.active_injections.remove(scenario_id);

        let scenario_clone = scenario.clone();
        drop(scenario);

        self.event_store
            .create_event(
                &format!("scenario:{}", scenario_id),
                "fault.stopped",
                serde_json::json!({
                    "scenario_id": scenario_id,
                }),
                None,
            )
            .await?;

        self.execute_rollback(scenario_id).await?;

        Ok(scenario_clone)
    }

    pub async fn rollback_scenario(&self, scenario_id: &str) -> Result<FaultScenario, AppError> {
        let mut scenario = self.scenarios.get_mut(scenario_id)
            .ok_or_else(|| AppError::NotFound(format!("故障场景不存在: {}", scenario_id)))?;

        scenario.status = ScenarioStatus::RolledBack;
        self.active_injections.remove(scenario_id);

        let scenario_clone = scenario.clone();
        drop(scenario);

        self.event_store
            .create_event(
                &format!("scenario:{}", scenario_id),
                "fault.rolled_back",
                serde_json::json!({
                    "scenario_id": scenario_id,
                }),
                None,
            )
            .await?;

        self.execute_rollback(scenario_id).await?;

        Ok(scenario_clone)
    }

    async fn execute_rollback(&self, scenario_id: &str) -> Result<(), AppError> {
        tracing::info!(scenario_id = %scenario_id, "执行故障回滚");

        if let Some(handlers) = self.rollback_handlers.get(scenario_id) {
            for handler in handlers.iter() {
                handler();
            }
        }

        Ok(())
    }

    fn schedule_auto_rollback(&self, scenario_id: &str, duration: Duration) {
        let scenario_id = scenario_id.to_string();
        let active_injections = self.active_injections.clone();
        let scenarios = self.scenarios.clone();
        let event_store = self.event_store.clone();
        let rollback_handlers = self.rollback_handlers.clone();

        tokio::spawn(async move {
            tokio::time::sleep(duration).await;

            if active_injections.contains_key(&scenario_id) {
                tracing::info!(scenario_id = %scenario_id, "自动回滚触发");
                
                if let Some(mut scenario) = scenarios.get_mut(&scenario_id) {
                    scenario.status = ScenarioStatus::RolledBack;
                }
                active_injections.remove(&scenario_id);

                let _ = event_store
                    .create_event(
                        &format!("scenario:{}", scenario_id),
                        "fault.auto_rolled_back",
                        serde_json::json!({
                            "scenario_id": scenario_id,
                        }),
                        None,
                    )
                    .await;

                if let Some(handlers) = rollback_handlers.get(&scenario_id) {
                    for handler in handlers.iter() {
                        handler();
                    }
                }
            }
        });
    }

    pub fn check_injection(&self, namespace: &str, service: &str, endpoint: &str, labels: &HashMap<String, String>) -> Option<ActiveInjection> {
        for entry in self.active_injections.iter() {
            let injection = entry.value();
            let scenario = self.scenarios.get(entry.key())?;

            if self.matches_scope(&scenario.scope, namespace, service, endpoint, labels) {
                let mut injection = injection.clone();
                injection.affected_requests += 1;
                
                if injection.affected_requests % 100 == 0 {
                    if let Some(mut active) = self.active_injections.get_mut(entry.key()) {
                        active.affected_requests = injection.affected_requests;
                    }
                }

                return Some(injection);
            }
        }
        None
    }

    fn matches_scope(&self, scope: &InjectionScope, namespace: &str, service: &str, endpoint: &str, labels: &HashMap<String, String>) -> bool {
        if !scope.namespaces.is_empty() && !scope.namespaces.contains(namespace) {
            return false;
        }
        if !scope.services.is_empty() && !scope.services.contains(service) {
            return false;
        }
        if !scope.endpoints.is_empty() && !scope.endpoints.contains(endpoint) {
            return false;
        }
        for (k, v) in &scope.labels {
            if labels.get(k) != Some(v) {
                return false;
            }
        }

        if scope.percentage < 100 {
            let check = rand::random::<u8>() % 100;
            return check < scope.percentage;
        }

        true
    }

    pub async fn apply_fault(&self, injection: &ActiveInjection) -> Result<(), AppError> {
        let scenario = self.scenarios.get(&injection.scenario_id)
            .ok_or_else(|| AppError::NotFound(format!("故障场景不存在: {}", injection.scenario_id)))?;

        match scenario.fault_type {
            FaultType::Latency => {
                let ms = scenario.parameters.get("latency_ms")
                    .and_then(|v| v.as_u64())
                    .unwrap_or(1000);
                tokio::time::sleep(Duration::from_millis(ms)).await;
            }
            FaultType::Error => {
                let error_rate = scenario.parameters.get("error_rate")
                    .and_then(|v| v.as_f64())
                    .unwrap_or(0.5);
                if rand::random::<f64>() < error_rate {
                    let error_msg = scenario.parameters.get("error_message")
                        .and_then(|v| v.as_str())
                        .unwrap_or("故障注入测试错误");
                    return Err(AppError::InternalError(error_msg.to_string()));
                }
            }
            FaultType::PacketLoss => {
                let loss_rate = scenario.parameters.get("loss_rate")
                    .and_then(|v| v.as_f64())
                    .unwrap_or(0.3);
                if rand::random::<f64>() < loss_rate {
                    return Err(AppError::TimeoutError);
                }
            }
            FaultType::ResourceExhaustion => {
                let _vec = vec![0u8; 1024 * 1024];
            }
            _ => {}
        }

        Ok(())
    }

    pub fn register_rollback_handler<F: Fn() + Send + Sync + 'static>(&self, scenario_id: &str, handler: F) {
        self.rollback_handlers
            .entry(scenario_id.to_string())
            .or_default()
            .push(Box::new(handler));
    }

    pub fn list_active_injections(&self) -> Vec<ActiveInjection> {
        self.active_injections.iter().map(|e| e.value().clone()).collect()
    }

    pub fn update_scenario(&self, scenario_id: &str, update: FaultScenarioUpdate) -> Result<FaultScenario, AppError> {
        let mut scenario = self.scenarios.get_mut(scenario_id)
            .ok_or_else(|| AppError::NotFound(format!("故障场景不存在: {}", scenario_id)))?;

        if scenario.status == ScenarioStatus::Active {
            return Err(AppError::Conflict("活动场景不允许修改".to_string()));
        }

        if let Some(name) = update.name {
            scenario.name = name;
        }
        if let Some(description) = update.description {
            scenario.description = description;
        }
        if let Some(parameters) = update.parameters {
            scenario.parameters = parameters;
        }
        if let Some(duration) = update.duration {
            scenario.duration = duration;
        }
        if let Some(auto_rollback) = update.auto_rollback {
            scenario.auto_rollback = auto_rollback;
        }
        if let Some(scope) = update.scope {
            scenario.scope = scope;
        }

        Ok(scenario.clone())
    }

    pub fn delete_scenario(&self, scenario_id: &str) -> Result<(), AppError> {
        let scenario = self.scenarios.get(scenario_id)
            .ok_or_else(|| AppError::NotFound(format!("故障场景不存在: {}", scenario_id)))?;

        if scenario.status == ScenarioStatus::Active {
            return Err(AppError::Conflict("活动场景不允许删除".to_string()));
        }

        self.scenarios.remove(scenario_id);
        self.rollback_handlers.remove(scenario_id);
        Ok(())
    }
}

#[derive(Debug, Deserialize)]
pub struct FaultScenarioCreate {
    pub name: String,
    pub description: String,
    pub fault_type: FaultType,
    pub scope: InjectionScope,
    pub parameters: HashMap<String, serde_json::Value>,
    pub duration: Duration,
    pub auto_rollback: bool,
}

#[derive(Debug, Deserialize)]
pub struct FaultScenarioUpdate {
    pub name: Option<String>,
    pub description: Option<String>,
    pub parameters: Option<HashMap<String, serde_json::Value>>,
    pub duration: Option<Duration>,
    pub auto_rollback: Option<bool>,
    pub scope: Option<InjectionScope>,
}

impl Default for InjectionScope {
    fn default() -> Self {
        Self {
            namespaces: HashSet::new(),
            services: HashSet::new(),
            endpoints: HashSet::new(),
            labels: HashMap::new(),
            percentage: 100,
        }
    }
}
