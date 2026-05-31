use serde::{Serialize, Deserialize};
use chrono::{DateTime, Utc};
use serde_json::Value;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ComparisonOperator {
    #[serde(rename = ">")]
    GreaterThan,
    #[serde(rename = "<")]
    LessThan,
    #[serde(rename = "==")]
    Equal,
    #[serde(rename = "!=")]
    NotEqual,
    #[serde(rename = ">=")]
    GreaterThanOrEqual,
    #[serde(rename = "<=")]
    LessThanOrEqual,
    #[serde(rename = "contains")]
    Contains,
    #[serde(rename = "matches")]
    Matches,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum LogicalOperator {
    #[serde(rename = "AND")]
    And,
    #[serde(rename = "OR")]
    Or,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuleCondition {
    pub field: String,
    pub operator: ComparisonOperator,
    pub value: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConditionGroup {
    pub logical_op: LogicalOperator,
    pub conditions: Vec<RuleCondition>,
    pub groups: Option<Vec<ConditionGroup>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ActionType {
    #[serde(rename = "send_command")]
    SendCommand,
    #[serde(rename = "alert")]
    Alert,
    #[serde(rename = "trigger_workflow")]
    TriggerWorkflow,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuleAction {
    pub action_type: ActionType,
    pub target: String,
    pub parameters: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum RuleStatus {
    #[serde(rename = "enabled")]
    Enabled,
    #[serde(rename = "disabled")]
    Disabled,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum FailureRecoveryStrategy {
    #[serde(rename = "none")]
    None,
    #[serde(rename = "retry")]
    Retry,
    #[serde(rename = "circuit_breaker")]
    CircuitBreaker,
    #[serde(rename = "fallback_action")]
    FallbackAction,
    #[serde(rename = "retry_with_fallback")]
    RetryWithFallback,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FailureRecoveryConfig {
    pub strategy: FailureRecoveryStrategy,
    pub max_retry_attempts: Option<u32>,
    pub retry_delay_seconds: Option<u64>,
    pub circuit_breaker_threshold: Option<u32>,
    pub circuit_breaker_reset_seconds: Option<u64>,
    pub fallback_action: Option<RuleAction>,
}

impl Default for FailureRecoveryConfig {
    fn default() -> Self {
        Self {
            strategy: FailureRecoveryStrategy::None,
            max_retry_attempts: None,
            retry_delay_seconds: None,
            circuit_breaker_threshold: None,
            circuit_breaker_reset_seconds: None,
            fallback_action: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum CircuitBreakerState {
    #[serde(rename = "closed")]
    Closed,
    #[serde(rename = "open")]
    Open,
    #[serde(rename = "half_open")]
    HalfOpen,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuleFailureInfo {
    pub failure_count: u32,
    pub last_failure_at: Option<DateTime<Utc>>,
    pub last_failure_reason: Option<String>,
    pub consecutive_failures: u32,
    pub circuit_breaker_state: CircuitBreakerState,
    pub circuit_breaker_opened_at: Option<DateTime<Utc>>,
    pub retry_attempts: u32,
    pub pending_recovery: bool,
    pub recovery_started_at: Option<DateTime<Utc>>,
}

impl Default for RuleFailureInfo {
    fn default() -> Self {
        Self {
            failure_count: 0,
            last_failure_at: None,
            last_failure_reason: None,
            consecutive_failures: 0,
            circuit_breaker_state: CircuitBreakerState::Closed,
            circuit_breaker_opened_at: None,
            retry_attempts: 0,
            pending_recovery: false,
            recovery_started_at: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingRecovery {
    pub event_id: String,
    pub rule_id: String,
    pub action: RuleAction,
    pub retry_attempt: u32,
    pub next_retry_at: DateTime<Utc>,
    pub original_error: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RecoveryEvent {
    pub event_id: String,
    pub rule_id: String,
    pub rule_name: String,
    pub recovery_type: String,
    pub original_error: String,
    pub recovery_result: String,
    pub retry_attempt: u32,
    pub recovered_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Rule {
    pub rule_id: String,
    pub name: String,
    pub description: Option<String>,
    pub source: String,
    pub condition: ConditionGroup,
    pub actions: Vec<RuleAction>,
    pub status: RuleStatus,
    pub priority: u8,
    pub trigger_limit: Option<u64>,
    pub trigger_count: u64,
    pub cooldown_seconds: Option<u64>,
    pub last_triggered: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub created_by: String,
    pub recovery_config: FailureRecoveryConfig,
    pub failure_info: RuleFailureInfo,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateRuleRequest {
    pub name: String,
    pub description: Option<String>,
    pub source: String,
    pub condition: ConditionGroup,
    pub actions: Vec<RuleAction>,
    pub priority: Option<u8>,
    pub trigger_limit: Option<u64>,
    pub cooldown_seconds: Option<u64>,
    pub recovery_config: Option<FailureRecoveryConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateRuleRequest {
    pub name: Option<String>,
    pub description: Option<String>,
    pub condition: Option<ConditionGroup>,
    pub actions: Option<Vec<RuleAction>>,
    pub priority: Option<u8>,
    pub trigger_limit: Option<u64>,
    pub cooldown_seconds: Option<u64>,
    pub recovery_config: Option<FailureRecoveryConfig>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataPoint {
    pub source: String,
    pub timestamp: DateTime<Utc>,
    pub data: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TriggerEvent {
    pub event_id: String,
    pub rule_id: String,
    pub rule_name: String,
    pub data_point: DataPoint,
    pub matched_conditions: Vec<String>,
    pub actions: Vec<RuleAction>,
    pub triggered_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ActionExecutionRecord {
    pub record_id: String,
    pub event_id: String,
    pub rule_id: String,
    pub action: RuleAction,
    pub success: bool,
    pub result: Option<Value>,
    pub error_message: Option<String>,
    pub executed_at: DateTime<Utc>,
    pub retry_attempt: u32,
    pub recovery_type: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuleTriggerHistory {
    pub event: TriggerEvent,
    pub action_results: Vec<ActionExecutionRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuleResponse {
    pub rule_id: String,
    pub name: String,
    pub description: Option<String>,
    pub source: String,
    pub condition: ConditionGroup,
    pub actions: Vec<RuleAction>,
    pub status: RuleStatus,
    pub priority: u8,
    pub trigger_limit: Option<u64>,
    pub trigger_count: u64,
    pub cooldown_seconds: Option<u64>,
    pub last_triggered: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub recovery_config: Option<FailureRecoveryConfig>,
    pub failure_info: Option<RuleFailureInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EvaluationResult {
    pub rule_id: String,
    pub rule_name: String,
    pub matched: bool,
    pub matched_conditions: Vec<String>,
    pub skipped_reason: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CircuitBreakerStatus {
    pub rule_id: String,
    pub state: CircuitBreakerState,
    pub failure_count: u32,
    pub consecutive_failures: u32,
    pub threshold: Option<u32>,
    pub opened_at: Option<DateTime<Utc>>,
    pub reset_seconds: Option<u64>,
    pub time_until_reset_seconds: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RecoveryStats {
    pub total_failures: u32,
    pub total_recoveries: u32,
    pub total_retries: u32,
    pub successful_recoveries: u32,
    pub fallback_executions: u32,
    pub circuit_breaker_ops: u32,
    pub avg_recovery_time_ms: Option<f64>,
}

impl Default for RecoveryStats {
    fn default() -> Self {
        Self {
            total_failures: 0,
            total_recoveries: 0,
            total_retries: 0,
            successful_recoveries: 0,
            fallback_executions: 0,
            circuit_breaker_ops: 0,
            avg_recovery_time_ms: None,
        }
    }
}

impl Rule {
    pub fn new(req: CreateRuleRequest, created_by: impl Into<String>) -> Self {
        let now = Utc::now();
        Self {
            rule_id: Uuid::new_v4().to_string(),
            name: req.name,
            description: req.description,
            source: req.source,
            condition: req.condition,
            actions: req.actions,
            status: RuleStatus::Enabled,
            priority: req.priority.unwrap_or(50),
            trigger_limit: req.trigger_limit,
            trigger_count: 0,
            cooldown_seconds: req.cooldown_seconds,
            last_triggered: None,
            created_at: now,
            updated_at: now,
            created_by: created_by.into(),
            recovery_config: req.recovery_config.unwrap_or_default(),
            failure_info: RuleFailureInfo::default(),
        }
    }

    pub fn can_trigger(&self) -> (bool, Option<String>) {
        if self.status != RuleStatus::Enabled {
            return (false, Some("规则已禁用".into()));
        }

        if self.failure_info.circuit_breaker_state == CircuitBreakerState::Open {
            if let (Some(reset_seconds), Some(opened_at)) = (
                self.recovery_config.circuit_breaker_reset_seconds,
                self.failure_info.circuit_breaker_opened_at,
            ) {
                let elapsed = (Utc::now() - opened_at).num_seconds() as u64;
                if elapsed < reset_seconds {
                    return (false, Some(format!("断路器打开中，剩余 {} 秒自动重置", reset_seconds - elapsed)));
                }
            } else {
                return (false, Some("断路器处于打开状态".into()));
            }
        }

        if let Some(limit) = self.trigger_limit {
            if self.trigger_count >= limit {
                return (false, Some(format!("已达到触发上限 {}", limit)));
            }
        }

        if let Some(cooldown) = self.cooldown_seconds {
            if let Some(last_triggered) = self.last_triggered {
                let elapsed = (Utc::now() - last_triggered).num_seconds() as u64;
                if elapsed < cooldown {
                    return (false, Some(format!("冷却中，剩余 {} 秒", cooldown - elapsed)));
                }
            }
        }

        (true, None)
    }

    pub fn mark_triggered(&mut self) {
        self.trigger_count += 1;
        self.last_triggered = Some(Utc::now());
        self.updated_at = Utc::now();
    }

    pub fn record_failure(&mut self, reason: impl Into<String>) {
        let now = Utc::now();
        self.failure_info.failure_count += 1;
        self.failure_info.consecutive_failures += 1;
        self.failure_info.last_failure_at = Some(now);
        self.failure_info.last_failure_reason = Some(reason.into());
        self.updated_at = now;

        if let Some(threshold) = self.recovery_config.circuit_breaker_threshold {
            if self.failure_info.consecutive_failures >= threshold 
                && self.failure_info.circuit_breaker_state == CircuitBreakerState::Closed 
            {
                self.open_circuit_breaker();
            }
        }
    }

    pub fn record_success(&mut self) {
        self.failure_info.consecutive_failures = 0;
        self.failure_info.retry_attempts = 0;
        self.failure_info.pending_recovery = false;
        self.failure_info.recovery_started_at = None;
        self.updated_at = Utc::now();

        if self.failure_info.circuit_breaker_state == CircuitBreakerState::HalfOpen {
            self.failure_info.circuit_breaker_state = CircuitBreakerState::Closed;
            self.failure_info.circuit_breaker_opened_at = None;
        }
    }

    pub fn open_circuit_breaker(&mut self) {
        self.failure_info.circuit_breaker_state = CircuitBreakerState::Open;
        self.failure_info.circuit_breaker_opened_at = Some(Utc::now());
        self.updated_at = Utc::now();
    }

    pub fn try_half_open(&mut self) -> bool {
        if self.failure_info.circuit_breaker_state != CircuitBreakerState::Open {
            return false;
        }

        if let (Some(reset_seconds), Some(opened_at)) = (
            self.recovery_config.circuit_breaker_reset_seconds,
            self.failure_info.circuit_breaker_opened_at,
        ) {
            let elapsed = (Utc::now() - opened_at).num_seconds() as u64;
            if elapsed >= reset_seconds {
                self.failure_info.circuit_breaker_state = CircuitBreakerState::HalfOpen;
                self.failure_info.consecutive_failures = 0;
                self.updated_at = Utc::now();
                return true;
            }
        }
        false
    }

    pub fn get_circuit_breaker_status(&self) -> CircuitBreakerStatus {
        let time_until_reset = match (
            self.recovery_config.circuit_breaker_reset_seconds,
            self.failure_info.circuit_breaker_opened_at,
            &self.failure_info.circuit_breaker_state,
        ) {
            (Some(reset_seconds), Some(opened_at), CircuitBreakerState::Open) => {
                let elapsed = (Utc::now() - opened_at).num_seconds() as u64;
                Some(reset_seconds as i64 - elapsed as i64)
            }
            _ => None,
        };

        CircuitBreakerStatus {
            rule_id: self.rule_id.clone(),
            state: self.failure_info.circuit_breaker_state.clone(),
            failure_count: self.failure_info.failure_count,
            consecutive_failures: self.failure_info.consecutive_failures,
            threshold: self.recovery_config.circuit_breaker_threshold,
            opened_at: self.failure_info.circuit_breaker_opened_at,
            reset_seconds: self.recovery_config.circuit_breaker_reset_seconds,
            time_until_reset_seconds: time_until_reset,
        }
    }
}

impl ConditionGroup {
    pub fn evaluate(&self, data: &Value) -> (bool, Vec<String>) {
        let mut matched_conditions = Vec::new();
        let mut condition_results = Vec::new();

        for condition in &self.conditions {
            let (matched, desc) = condition.evaluate(data);
            condition_results.push(matched);
            if matched {
                matched_conditions.push(desc);
            }
        }

        if let Some(groups) = &self.groups {
            for group in groups {
                let (group_matched, group_matched_conditions) = group.evaluate(data);
                condition_results.push(group_matched);
                matched_conditions.extend(group_matched_conditions);
            }
        }

        let final_result = match self.logical_op {
            LogicalOperator::And => condition_results.iter().all(|&r| r),
            LogicalOperator::Or => condition_results.iter().any(|&r| r),
        };

        if final_result {
            (true, matched_conditions)
        } else {
            (false, Vec::new())
        }
    }
}

impl RuleCondition {
    pub fn evaluate(&self, data: &Value) -> (bool, String) {
        let field_value = data.get(&self.field);
        let desc = format!("{} {} {:?}", self.field, self.operator, self.value);

        let field_value = match field_value {
            Some(v) => v,
            None => return (false, desc),
        };

        let matched = match self.operator {
            ComparisonOperator::GreaterThan => compare_values(field_value, &self.value, Ordering::Greater),
            ComparisonOperator::LessThan => compare_values(field_value, &self.value, Ordering::Less),
            ComparisonOperator::Equal => field_value == &self.value,
            ComparisonOperator::NotEqual => field_value != &self.value,
            ComparisonOperator::GreaterThanOrEqual => compare_values(field_value, &self.value, Ordering::Greater) || field_value == &self.value,
            ComparisonOperator::LessThanOrEqual => compare_values(field_value, &self.value, Ordering::Less) || field_value == &self.value,
            ComparisonOperator::Contains => evaluate_contains(field_value, &self.value),
            ComparisonOperator::Matches => evaluate_matches(field_value, &self.value),
        };

        (matched, desc)
    }
}

#[derive(PartialEq)]
enum Ordering {
    Greater,
    Less,
}

fn compare_values(a: &Value, b: &Value, ordering: Ordering) -> bool {
    match (a, b) {
        (Value::Number(an), Value::Number(bn)) => {
            if let (Some(af), Some(bf)) = (an.as_f64(), bn.as_f64()) {
                match ordering {
                    Ordering::Greater => af > bf,
                    Ordering::Less => af < bf,
                }
            } else if let (Some(ai), Some(bi)) = (an.as_i64(), bn.as_i64()) {
                match ordering {
                    Ordering::Greater => ai > bi,
                    Ordering::Less => ai < bi,
                }
            } else {
                false
            }
        }
        (Value::String(as_), Value::String(bs)) => match ordering {
            Ordering::Greater => as_ > bs,
            Ordering::Less => as_ < bs,
        },
        _ => false,
    }
}

fn evaluate_contains(haystack: &Value, needle: &Value) -> bool {
    match (haystack, needle) {
        (Value::String(h), Value::String(n)) => h.contains(n),
        (Value::Array(arr), _) => arr.iter().any(|v| v == needle),
        _ => false,
    }
}

fn evaluate_matches(value: &Value, pattern: &Value) -> bool {
    match (value, pattern) {
        (Value::String(v), Value::String(p)) => {
            regex::Regex::new(p).map(|re| re.is_match(v)).unwrap_or(false)
        }
        _ => false,
    }
}

impl DataPoint {
    pub fn new(source: impl Into<String>, data: Value) -> Self {
        Self {
            source: source.into(),
            timestamp: Utc::now(),
            data,
        }
    }
}

impl TriggerEvent {
    pub fn new(rule: &Rule, data_point: DataPoint, matched_conditions: Vec<String>) -> Self {
        Self {
            event_id: Uuid::new_v4().to_string(),
            rule_id: rule.rule_id.clone(),
            rule_name: rule.name.clone(),
            data_point,
            matched_conditions,
            actions: rule.actions.clone(),
            triggered_at: Utc::now(),
        }
    }
}

impl ActionExecutionRecord {
    pub fn success(event_id: &str, rule_id: &str, action: RuleAction, result: Option<Value>) -> Self {
        Self {
            record_id: Uuid::new_v4().to_string(),
            event_id: event_id.to_string(),
            rule_id: rule_id.to_string(),
            action,
            success: true,
            result,
            error_message: None,
            executed_at: Utc::now(),
            retry_attempt: 0,
            recovery_type: None,
        }
    }

    pub fn failure(event_id: &str, rule_id: &str, action: RuleAction, error: impl Into<String>) -> Self {
        Self {
            record_id: Uuid::new_v4().to_string(),
            event_id: event_id.to_string(),
            rule_id: rule_id.to_string(),
            action,
            success: false,
            result: None,
            error_message: Some(error.into()),
            executed_at: Utc::now(),
            retry_attempt: 0,
            recovery_type: None,
        }
    }

    pub fn with_recovery(mut self, recovery_type: impl Into<String>, attempt: u32) -> Self {
        self.recovery_type = Some(recovery_type.into());
        self.retry_attempt = attempt;
        self
    }
}

impl From<Rule> for RuleResponse {
    fn from(rule: Rule) -> Self {
        Self {
            rule_id: rule.rule_id,
            name: rule.name,
            description: rule.description,
            source: rule.source,
            condition: rule.condition,
            actions: rule.actions,
            status: rule.status,
            priority: rule.priority,
            trigger_limit: rule.trigger_limit,
            trigger_count: rule.trigger_count,
            cooldown_seconds: rule.cooldown_seconds,
            last_triggered: rule.last_triggered,
            created_at: rule.created_at,
            updated_at: rule.updated_at,
            recovery_config: Some(rule.recovery_config),
            failure_info: Some(rule.failure_info),
        }
    }
}
