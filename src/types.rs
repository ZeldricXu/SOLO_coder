use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Entity {
    pub id: String,
    pub entity_type: String,
    pub status: EntityStatus,
    pub attributes: HashMap<String, serde_json::Value>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EntityStatus {
    Pending,
    Running,
    Completed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub config_id: String,
    pub namespace: String,
    pub version: u64,
    pub parameters: HashMap<String, serde_json::Value>,
    pub enabled: bool,
    pub applied_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunInstance {
    pub run_id: String,
    pub entity_id: String,
    pub phase: RunPhase,
    pub progress: f64,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_detail: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RunPhase {
    Initializing,
    Processing,
    Finalizing,
    Completed,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StatsSnapshot {
    pub snapshot_id: String,
    pub timestamp: DateTime<Utc>,
    pub metrics: Metrics,
    pub dimensions: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Metrics {
    pub throughput: u64,
    pub latency_p99: u64,
    pub error_rate: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApiResponse<T> {
    pub code: u16,
    pub data: Option<T>,
    pub message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateResourceRequest {
    pub resource_type: String,
    pub config: HashMap<String, serde_json::Value>,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateResourceResponse {
    pub id: String,
    pub status: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourceStatus {
    pub id: String,
    pub status: String,
    pub progress: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchOperation {
    pub action: BatchAction,
    pub id: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BatchAction {
    Start,
    Stop,
    Cancel,
    Restart,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchRequest {
    pub operations: Vec<BatchOperation>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchResponse {
    pub batch_id: String,
    pub results: Vec<BatchResult>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchResult {
    pub id: String,
    pub success: bool,
    pub message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AlertRule {
    pub rule_id: String,
    pub name: String,
    pub condition: AlertCondition,
    pub threshold: f64,
    pub severity: AlertSeverity,
    pub enabled: bool,
    pub notification_channels: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AlertCondition {
    GreaterThan,
    LessThan,
    GreaterThanOrEqual,
    LessThanOrEqual,
    Equal,
    NotEqual,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AlertSeverity {
    Info,
    Warning,
    Critical,
    Fatal,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Alert {
    pub alert_id: String,
    pub rule_id: String,
    pub severity: AlertSeverity,
    pub message: String,
    pub timestamp: DateTime<Utc>,
    pub resolved: bool,
    pub resolved_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShamirShare {
    pub share_id: u8,
    pub data: Vec<u8>,
    pub x_coordinate: u8,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchemaMigration {
    pub version: u64,
    pub name: String,
    pub applied_at: Option<DateTime<Utc>>,
    pub up_sql: String,
    pub down_sql: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditLogEntry {
    pub entry_id: String,
    pub sequence: u64,
    pub previous_hash: String,
    pub hash: String,
    pub timestamp: DateTime<Utc>,
    pub operation: String,
    pub actor: String,
    pub resource: String,
    pub details: serde_json::Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FederatedTrainingTask {
    pub task_id: String,
    pub model_id: String,
    pub round: u32,
    pub status: TrainingStatus,
    pub participants: Vec<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TrainingStatus {
    Pending,
    Distributing,
    Training,
    Aggregating,
    Completed,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GradientUpdate {
    pub participant_id: String,
    pub task_id: String,
    pub round: u32,
    pub encrypted_gradient: Vec<u8>,
    pub signature: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EnclaveInstance {
    pub enclave_id: String,
    pub status: EnclaveStatus,
    pub attestation_report: Option<Vec<u8>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EnclaveStatus {
    Created,
    Attested,
    Running,
    Terminated,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SensitiveField {
    pub field_name: String,
    pub field_type: SensitiveType,
    pub mask_pattern: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SensitiveType {
    Email,
    Phone,
    IdCard,
    CreditCard,
    Address,
    Custom,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserPermission {
    pub user_id: String,
    pub roles: Vec<String>,
    pub allowed_fields: Vec<String>,
}

impl Entity {
    pub fn new(entity_type: &str) -> Self {
        let now = Utc::now();
        Entity {
            id: format!("ent_{}", Uuid::new_v4().simple()),
            entity_type: entity_type.to_string(),
            status: EntityStatus::Pending,
            attributes: HashMap::new(),
            created_at: now,
            updated_at: now,
        }
    }
}

impl Config {
    pub fn new(namespace: &str) -> Self {
        Config {
            config_id: format!("cfg_{}", Uuid::new_v4().simple()),
            namespace: namespace.to_string(),
            version: 1,
            parameters: HashMap::new(),
            enabled: true,
            applied_at: Utc::now(),
        }
    }
}

impl RunInstance {
    pub fn new(entity_id: &str) -> Self {
        RunInstance {
            run_id: format!("run_{}", Uuid::new_v4().simple()),
            entity_id: entity_id.to_string(),
            phase: RunPhase::Initializing,
            progress: 0.0,
            started_at: Utc::now(),
            completed_at: None,
            error_detail: None,
        }
    }
}

impl StatsSnapshot {
    pub fn new() -> Self {
        StatsSnapshot {
            snapshot_id: format!("snap_{}", Uuid::new_v4().simple()),
            timestamp: Utc::now(),
            metrics: Metrics {
                throughput: 0,
                latency_p99: 0,
                error_rate: 0.0,
            },
            dimensions: HashMap::new(),
        }
    }
}

impl<T> ApiResponse<T> {
    pub fn success(data: T) -> Self {
        ApiResponse {
            code: 200,
            data: Some(data),
            message: None,
        }
    }

    pub fn created(data: T) -> Self {
        ApiResponse {
            code: 201,
            data: Some(data),
            message: None,
        }
    }

    pub fn error(code: u16, message: &str) -> Self {
        ApiResponse {
            code,
            data: None,
            message: Some(message.to_string()),
        }
    }
}

impl AlertRule {
    pub fn new(name: &str, condition: AlertCondition, threshold: f64, severity: AlertSeverity) -> Self {
        AlertRule {
            rule_id: format!("rule_{}", Uuid::new_v4().simple()),
            name: name.to_string(),
            condition,
            threshold,
            severity,
            enabled: true,
            notification_channels: Vec::new(),
        }
    }

    pub fn evaluate(&self, value: f64) -> bool {
        if !self.enabled {
            return false;
        }
        match self.condition {
            AlertCondition::GreaterThan => value > self.threshold,
            AlertCondition::LessThan => value < self.threshold,
            AlertCondition::GreaterThanOrEqual => value >= self.threshold,
            AlertCondition::LessThanOrEqual => value <= self.threshold,
            AlertCondition::Equal => (value - self.threshold).abs() < f64::EPSILON,
            AlertCondition::NotEqual => (value - self.threshold).abs() >= f64::EPSILON,
        }
    }
}

impl Alert {
    pub fn new(rule_id: &str, severity: AlertSeverity, message: &str) -> Self {
        Alert {
            alert_id: format!("alert_{}", Uuid::new_v4().simple()),
            rule_id: rule_id.to_string(),
            severity,
            message: message.to_string(),
            timestamp: Utc::now(),
            resolved: false,
            resolved_at: None,
        }
    }

    pub fn resolve(&mut self) {
        self.resolved = true;
        self.resolved_at = Some(Utc::now());
    }
}
