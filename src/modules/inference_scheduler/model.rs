use serde::{Serialize, Deserialize};
use chrono::{DateTime, Utc};
use serde_json::Value;
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ModelDeploymentStatus {
    Pending,
    Deploying,
    Deployed,
    Failed,
    Undeployed,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum TaskPriority {
    Low,
    Medium,
    High,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum TaskStatus {
    Queued,
    Scheduled,
    Running,
    Completed,
    Failed,
    Timeout,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum SyncStatus {
    Pending,
    Syncing,
    Synced,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum VersionCompatibility {
    #[serde(rename = "compatible")]
    Compatible,
    #[serde(rename = "requires_upgrade")]
    RequiresUpgrade,
    #[serde(rename = "requires_downgrade")]
    RequiresDowngrade,
    #[serde(rename = "incompatible")]
    Incompatible,
    #[serde(rename = "unknown")]
    Unknown,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum VersionStatus {
    #[serde(rename = "draft")]
    Draft,
    #[serde(rename = "testing")]
    Testing,
    #[serde(rename = "stable")]
    Stable,
    #[serde(rename = "deprecated")]
    Deprecated,
    #[serde(rename = "archived")]
    Archived,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SemanticVersion {
    pub major: u32,
    pub minor: u32,
    pub patch: u32,
    pub prerelease: Option<String>,
    pub build: Option<String>,
}

impl SemanticVersion {
    pub fn parse(version_str: &str) -> Option<Self> {
        let parts: Vec<&str> = version_str.split('.').collect();
        if parts.len() < 3 {
            return None;
        }

        let major = parts[0].parse::<u32>().ok()?;
        let minor = parts[1].parse::<u32>().ok()?;
        
        let patch_part = parts[2];
        let (patch_str, prerelease, build) = if let Some(plus_pos) = patch_part.find('+') {
            let (main, build) = patch_part.split_at(plus_pos);
            let (patch_str, prerelease) = if let Some(dash_pos) = main.find('-') {
                let (p, pre) = main.split_at(dash_pos);
                (p, Some(pre[1..].to_string()))
            } else {
                (main, None)
            };
            (patch_str, prerelease, Some(build[1..].to_string()))
        } else if let Some(dash_pos) = patch_part.find('-') {
            let (p, pre) = patch_part.split_at(dash_pos);
            (p, Some(pre[1..].to_string()), None)
        } else {
            (patch_part, None, None)
        };

        let patch = patch_str.parse::<u32>().ok()?;

        Some(Self {
            major,
            minor,
            patch,
            prerelease,
            build,
        })
    }

    pub fn to_string(&self) -> String {
        let mut base = format!("{}.{}.{}", self.major, self.minor, self.patch);
        if let Some(pre) = &self.prerelease {
            base.push_str(&format!("-{}", pre));
        }
        if let Some(build) = &self.build {
            base.push_str(&format!("+{}", build));
        }
        base
    }

    pub fn compare(&self, other: &SemanticVersion) -> std::cmp::Ordering {
        use std::cmp::Ordering::*;
        
        match self.major.cmp(&other.major) {
            Equal => {},
            ord => return ord,
        }
        match self.minor.cmp(&other.minor) {
            Equal => {},
            ord => return ord,
        }
        match self.patch.cmp(&other.patch) {
            Equal => {},
            ord => return ord,
        }
        
        match (&self.prerelease, &other.prerelease) {
            (None, None) => Equal,
            (None, Some(_)) => Greater,
            (Some(_), None) => Less,
            (Some(a), Some(b)) => a.cmp(b),
        }
    }

    pub fn is_compatible_with(&self, other: &SemanticVersion) -> bool {
        self.major == other.major && self.minor >= other.minor
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelVersion {
    pub version_id: String,
    pub model_id: String,
    pub version: String,
    pub semantic_version: SemanticVersion,
    pub status: VersionStatus,
    pub description: Option<String>,
    pub file_path: String,
    pub file_size_bytes: u64,
    pub checksum: String,
    pub checksum_algorithm: String,
    pub compatible_runtimes: Vec<String>,
    pub minimum_runtime_version: Option<String>,
    pub dependencies: HashMap<String, String>,
    pub release_notes: Option<String>,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
    pub deployed_at: Option<DateTime<Utc>>,
    pub deployment_status: ModelDeploymentStatus,
    pub deployed_device_id: Option<String>,
    pub performance_metrics: HashMap<String, f64>,
    pub tags: Vec<String>,
    pub is_latest: bool,
    pub is_default: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionMigration {
    pub migration_id: String,
    pub from_version_id: String,
    pub to_version_id: String,
    pub model_id: String,
    pub migration_type: String,
    pub status: String,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_message: Option<String>,
    pub migration_script: Option<String>,
    pub rollback_script: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelVersionDiff {
    pub model_id: String,
    pub from_version: String,
    pub to_version: String,
    pub changed_fields: Vec<String>,
    pub compatibility: VersionCompatibility,
    pub breaking_changes: Vec<String>,
    pub performance_impact: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionCompatibilityCheck {
    pub model_id: String,
    pub version: String,
    pub device_id: String,
    pub compatible: bool,
    pub issues: Vec<String>,
    pub required_upgrades: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelResourceRequirements {
    pub cpu_cores: f64,
    pub memory_mb: u64,
    pub gpu_required: bool,
    pub gpu_memory_mb: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AiModel {
    pub model_id: String,
    pub name: String,
    pub version: String,
    pub description: Option<String>,
    pub model_type: String,
    pub framework: String,
    pub file_path: String,
    pub file_size_bytes: u64,
    pub resource_requirements: ModelResourceRequirements,
    pub deployment_status: ModelDeploymentStatus,
    pub deployed_device_id: Option<String>,
    pub deployed_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub metadata: HashMap<String, Value>,
    pub versions: Vec<ModelVersion>,
    pub current_version_id: Option<String>,
    pub default_version_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceTask {
    pub task_id: String,
    pub model_id: String,
    pub model_version: String,
    pub version_id: Option<String>,
    pub input_data: Value,
    pub priority: TaskPriority,
    pub status: TaskStatus,
    pub device_id: Option<String>,
    pub timeout_seconds: u64,
    pub created_at: DateTime<Utc>,
    pub scheduled_at: Option<DateTime<Utc>>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub retry_count: u32,
    pub max_retries: u32,
    pub metadata: HashMap<String, Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceResult {
    pub result_id: String,
    pub task_id: String,
    pub model_id: String,
    pub model_version: String,
    pub version_id: Option<String>,
    pub output_data: Value,
    pub latency_ms: u64,
    pub success: bool,
    pub error_message: Option<String>,
    pub created_at: DateTime<Utc>,
    pub sync_status: SyncStatus,
    pub synced_at: Option<DateTime<Utc>>,
    pub retry_count: u32,
    pub metadata: HashMap<String, Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceStatistics {
    pub model_id: String,
    pub version_id: Option<String>,
    pub time_window_seconds: u64,
    pub total_requests: u64,
    pub successful_requests: u64,
    pub failed_requests: u64,
    pub throughput: f64,
    pub avg_latency_ms: f64,
    pub p50_latency_ms: u64,
    pub p99_latency_ms: u64,
    pub success_rate: f64,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RegisterModelRequest {
    pub name: String,
    pub version: String,
    pub description: Option<String>,
    pub model_type: String,
    pub framework: String,
    pub file_path: String,
    pub file_size_bytes: u64,
    pub resource_requirements: ModelResourceRequirements,
    pub metadata: Option<HashMap<String, Value>>,
    pub version_status: Option<VersionStatus>,
    pub release_notes: Option<String>,
    pub tags: Option<Vec<String>>,
    pub checksum: Option<String>,
    pub checksum_algorithm: Option<String>,
    pub compatible_runtimes: Option<Vec<String>>,
    pub dependencies: Option<HashMap<String, String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeployModelRequest {
    pub model_id: String,
    pub device_id: String,
    pub version_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceRequest {
    pub model_id: String,
    pub model_version: Option<String>,
    pub version_id: Option<String>,
    pub input_data: Value,
    pub priority: Option<TaskPriority>,
    pub timeout_seconds: Option<u64>,
    pub max_retries: Option<u32>,
    pub metadata: Option<HashMap<String, Value>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchInferenceRequest {
    pub requests: Vec<InferenceRequest>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateVersionRequest {
    pub model_id: String,
    pub version: String,
    pub description: Option<String>,
    pub file_path: String,
    pub file_size_bytes: u64,
    pub status: Option<VersionStatus>,
    pub release_notes: Option<String>,
    pub tags: Option<Vec<String>>,
    pub checksum: String,
    pub checksum_algorithm: Option<String>,
    pub compatible_runtimes: Option<Vec<String>>,
    pub minimum_runtime_version: Option<String>,
    pub dependencies: Option<HashMap<String, String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateVersionRequest {
    pub status: Option<VersionStatus>,
    pub description: Option<String>,
    pub release_notes: Option<String>,
    pub tags: Option<Vec<String>>,
    pub is_default: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VersionMigrationRequest {
    pub model_id: String,
    pub from_version_id: String,
    pub to_version_id: String,
    pub migration_type: String,
    pub migration_script: Option<String>,
    pub rollback_script: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelResponse {
    pub model_id: String,
    pub name: String,
    pub version: String,
    pub description: Option<String>,
    pub model_type: String,
    pub framework: String,
    pub deployment_status: ModelDeploymentStatus,
    pub deployed_device_id: Option<String>,
    pub deployed_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub version_count: usize,
    pub current_version: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelVersionResponse {
    pub version_id: String,
    pub model_id: String,
    pub version: String,
    pub status: VersionStatus,
    pub description: Option<String>,
    pub file_size_bytes: u64,
    pub created_at: DateTime<Utc>,
    pub deployed_at: Option<DateTime<Utc>>,
    pub deployment_status: ModelDeploymentStatus,
    pub deployed_device_id: Option<String>,
    pub tags: Vec<String>,
    pub is_latest: bool,
    pub is_default: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskResponse {
    pub task_id: String,
    pub model_id: String,
    pub model_version: String,
    pub version_id: Option<String>,
    pub status: TaskStatus,
    pub priority: TaskPriority,
    pub created_at: DateTime<Utc>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InferenceResultResponse {
    pub result_id: String,
    pub task_id: String,
    pub model_id: String,
    pub model_version: String,
    pub version_id: Option<String>,
    pub output_data: Value,
    pub latency_ms: u64,
    pub success: bool,
    pub error_message: Option<String>,
    pub created_at: DateTime<Utc>,
    pub sync_status: SyncStatus,
}

impl AiModel {
    pub fn new(req: RegisterModelRequest) -> Self {
        let now = Utc::now();
        let semantic_version = SemanticVersion::parse(&req.version)
            .unwrap_or(SemanticVersion { major: 0, minor: 1, patch: 0, prerelease: None, build: None });
        
        let initial_version = ModelVersion {
            version_id: Uuid::new_v4().to_string(),
            model_id: String::new(),
            version: req.version.clone(),
            semantic_version,
            status: req.version_status.clone().unwrap_or(VersionStatus::Stable),
            description: req.description.clone(),
            file_path: req.file_path.clone(),
            file_size_bytes: req.file_size_bytes,
            checksum: req.checksum.clone().unwrap_or_default(),
            checksum_algorithm: req.checksum_algorithm.clone().unwrap_or_else(|| "sha256".to_string()),
            compatible_runtimes: req.compatible_runtimes.clone().unwrap_or_default(),
            minimum_runtime_version: None,
            dependencies: req.dependencies.clone().unwrap_or_default(),
            release_notes: req.release_notes.clone(),
            created_by: "system".to_string(),
            created_at: now,
            deployed_at: None,
            deployment_status: ModelDeploymentStatus::Pending,
            deployed_device_id: None,
            performance_metrics: HashMap::new(),
            tags: req.tags.clone().unwrap_or_default(),
            is_latest: true,
            is_default: true,
        };

        let model_id = Uuid::new_v4().to_string();
        let mut version_with_model_id = initial_version;
        version_with_model_id.model_id = model_id.clone();
        let version_id = version_with_model_id.version_id.clone();

        Self {
            model_id,
            name: req.name,
            version: req.version,
            description: req.description,
            model_type: req.model_type,
            framework: req.framework,
            file_path: req.file_path,
            file_size_bytes: req.file_size_bytes,
            resource_requirements: req.resource_requirements,
            deployment_status: ModelDeploymentStatus::Pending,
            deployed_device_id: None,
            deployed_at: None,
            created_at: now,
            updated_at: now,
            metadata: req.metadata.unwrap_or_default(),
            versions: vec![version_with_model_id],
            current_version_id: Some(version_id.clone()),
            default_version_id: Some(version_id),
        }
    }

    pub fn deploy(&mut self, device_id: String, version_id: Option<String>) {
        self.deployment_status = ModelDeploymentStatus::Deploying;
        self.deployed_device_id = Some(device_id.clone());
        self.updated_at = Utc::now();

        if let Some(vid) = version_id {
            if let Some(version) = self.versions.iter_mut().find(|v| v.version_id == vid) {
                version.deployment_status = ModelDeploymentStatus::Deploying;
                version.deployed_device_id = Some(device_id);
            }
        }
    }

    pub fn mark_deployed(&mut self, version_id: Option<String>) {
        self.deployment_status = ModelDeploymentStatus::Deployed;
        self.deployed_at = Some(Utc::now());
        self.updated_at = Utc::now();

        let vid = version_id.or(self.current_version_id.clone());
        if let Some(vid) = vid {
            if let Some(version) = self.versions.iter_mut().find(|v| v.version_id == vid) {
                version.deployment_status = ModelDeploymentStatus::Deployed;
                version.deployed_at = Some(Utc::now());
            }
        }
    }

    pub fn mark_deployment_failed(&mut self, version_id: Option<String>) {
        self.deployment_status = ModelDeploymentStatus::Failed;
        self.updated_at = Utc::now();

        let vid = version_id.or(self.current_version_id.clone());
        if let Some(vid) = vid {
            if let Some(version) = self.versions.iter_mut().find(|v| v.version_id == vid) {
                version.deployment_status = ModelDeploymentStatus::Failed;
            }
        }
    }

    pub fn is_deployed(&self) -> bool {
        matches!(self.deployment_status, ModelDeploymentStatus::Deployed)
    }

    pub fn add_version(&mut self, version: ModelVersion) {
        for v in self.versions.iter_mut() {
            v.is_latest = false;
        }
        self.versions.push(version);
        self.updated_at = Utc::now();
    }

    pub fn get_version(&self, version_id: &str) -> Option<&ModelVersion> {
        self.versions.iter().find(|v| v.version_id == version_id)
    }

    pub fn get_latest_version(&self) -> Option<&ModelVersion> {
        self.versions.iter().find(|v| v.is_latest)
    }

    pub fn get_default_version(&self) -> Option<&ModelVersion> {
        self.versions.iter().find(|v| v.is_default)
    }

    pub fn set_default_version(&mut self, version_id: &str) -> bool {
        let mut found = false;
        for v in self.versions.iter_mut() {
            if v.version_id == version_id {
                v.is_default = true;
                found = true;
            } else {
                v.is_default = false;
            }
        }
        if found {
            self.default_version_id = Some(version_id.to_string());
            self.updated_at = Utc::now();
        }
        found
    }

    pub fn check_version_compatibility(&self, device_runtime_version: &str) -> VersionCompatibilityCheck {
        let default_version = self.get_default_version();
        let version_str = default_version.map(|v| v.version.clone()).unwrap_or_default();
        
        VersionCompatibilityCheck {
            model_id: self.model_id.clone(),
            version: version_str,
            device_id: String::new(),
            compatible: true,
            issues: Vec::new(),
            required_upgrades: Vec::new(),
        }
    }
}

impl InferenceTask {
    pub fn new(req: InferenceRequest, model_version: String, version_id: Option<String>) -> Self {
        let now = Utc::now();
        Self {
            task_id: Uuid::new_v4().to_string(),
            model_id: req.model_id,
            model_version,
            version_id,
            input_data: req.input_data,
            priority: req.priority.unwrap_or(TaskPriority::Medium),
            status: TaskStatus::Queued,
            device_id: None,
            timeout_seconds: req.timeout_seconds.unwrap_or(30),
            created_at: now,
            scheduled_at: None,
            started_at: None,
            completed_at: None,
            retry_count: 0,
            max_retries: req.max_retries.unwrap_or(3),
            metadata: req.metadata.unwrap_or_default(),
        }
    }

    pub fn schedule(&mut self, device_id: String) {
        self.status = TaskStatus::Scheduled;
        self.device_id = Some(device_id);
        self.scheduled_at = Some(Utc::now());
    }

    pub fn start(&mut self) {
        self.status = TaskStatus::Running;
        self.started_at = Some(Utc::now());
    }

    pub fn complete(&mut self) {
        self.status = TaskStatus::Completed;
        self.completed_at = Some(Utc::now());
    }

    pub fn fail(&mut self) {
        self.status = TaskStatus::Failed;
        self.completed_at = Some(Utc::now());
    }

    pub fn timeout(&mut self) {
        self.status = TaskStatus::Timeout;
        self.completed_at = Some(Utc::now());
    }

    pub fn should_retry(&self) -> bool {
        self.retry_count < self.max_retries
    }

    pub fn increment_retry(&mut self) {
        self.retry_count += 1;
        self.status = TaskStatus::Queued;
        self.scheduled_at = None;
        self.started_at = None;
        self.completed_at = None;
    }

    pub fn priority_value(&self) -> u8 {
        match self.priority {
            TaskPriority::Low => 0,
            TaskPriority::Medium => 1,
            TaskPriority::High => 2,
            TaskPriority::Critical => 3,
        }
    }
}

impl InferenceResult {
    pub fn success(task: &InferenceTask, output_data: Value, latency_ms: u64) -> Self {
        Self {
            result_id: Uuid::new_v4().to_string(),
            task_id: task.task_id.clone(),
            model_id: task.model_id.clone(),
            model_version: task.model_version.clone(),
            version_id: task.version_id.clone(),
            output_data,
            latency_ms,
            success: true,
            error_message: None,
            created_at: Utc::now(),
            sync_status: SyncStatus::Pending,
            synced_at: None,
            retry_count: task.retry_count,
            metadata: task.metadata.clone(),
        }
    }

    pub fn failure(task: &InferenceTask, error_message: String, latency_ms: u64) -> Self {
        Self {
            result_id: Uuid::new_v4().to_string(),
            task_id: task.task_id.clone(),
            model_id: task.model_id.clone(),
            model_version: task.model_version.clone(),
            version_id: task.version_id.clone(),
            output_data: Value::Null,
            latency_ms,
            success: false,
            error_message: Some(error_message),
            created_at: Utc::now(),
            sync_status: SyncStatus::Pending,
            synced_at: None,
            retry_count: task.retry_count,
            metadata: task.metadata.clone(),
        }
    }

    pub fn mark_synced(&mut self) {
        self.sync_status = SyncStatus::Synced;
        self.synced_at = Some(Utc::now());
    }

    pub fn mark_sync_failed(&mut self) {
        self.sync_status = SyncStatus::Failed;
    }

    pub fn needs_sync(&self) -> bool {
        matches!(self.sync_status, SyncStatus::Pending) || matches!(self.sync_status, SyncStatus::Failed)
    }
}

impl TaskPriority {
    pub fn from_str(s: &str) -> Option<Self> {
        match s.to_lowercase().as_str() {
            "low" => Some(TaskPriority::Low),
            "medium" => Some(TaskPriority::Medium),
            "high" => Some(TaskPriority::High),
            "critical" => Some(TaskPriority::Critical),
            _ => None,
        }
    }
}

impl Default for ModelResourceRequirements {
    fn default() -> Self {
        Self {
            cpu_cores: 1.0,
            memory_mb: 512,
            gpu_required: false,
            gpu_memory_mb: None,
        }
    }
}
