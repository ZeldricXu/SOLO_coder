use serde::{Serialize, Deserialize};
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum UpgradePhase {
    Pending,
    Approved,
    Downloading,
    Installing,
    Verifying,
    Success,
    Failed,
    RollingBack,
    RolledBack,
}

impl UpgradePhase {
    pub fn as_str(&self) -> &'static str {
        match self {
            UpgradePhase::Pending => "pending",
            UpgradePhase::Approved => "approved",
            UpgradePhase::Downloading => "downloading",
            UpgradePhase::Installing => "installing",
            UpgradePhase::Verifying => "verifying",
            UpgradePhase::Success => "success",
            UpgradePhase::Failed => "failed",
            UpgradePhase::RollingBack => "rolling_back",
            UpgradePhase::RolledBack => "rolled_back",
        }
    }

    pub fn is_terminal(&self) -> bool {
        matches!(self, UpgradePhase::Success | UpgradePhase::Failed | UpgradePhase::RolledBack)
    }

    pub fn can_transition_to(&self, next: &UpgradePhase) -> bool {
        matches!(
            (self, next),
            (UpgradePhase::Pending, UpgradePhase::Approved)
                | (UpgradePhase::Pending, UpgradePhase::Failed)
                | (UpgradePhase::Approved, UpgradePhase::Downloading)
                | (UpgradePhase::Approved, UpgradePhase::Failed)
                | (UpgradePhase::Downloading, UpgradePhase::Installing)
                | (UpgradePhase::Downloading, UpgradePhase::Failed)
                | (UpgradePhase::Downloading, UpgradePhase::RollingBack)
                | (UpgradePhase::Installing, UpgradePhase::Verifying)
                | (UpgradePhase::Installing, UpgradePhase::Failed)
                | (UpgradePhase::Installing, UpgradePhase::RollingBack)
                | (UpgradePhase::Verifying, UpgradePhase::Success)
                | (UpgradePhase::Verifying, UpgradePhase::Failed)
                | (UpgradePhase::Verifying, UpgradePhase::RollingBack)
                | (UpgradePhase::Failed, UpgradePhase::RollingBack)
                | (UpgradePhase::RollingBack, UpgradePhase::RolledBack)
                | (UpgradePhase::RollingBack, UpgradePhase::Failed)
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum GrayStrategyType {
    ByTags,
    ByDeviceGroup,
    ByPercentage,
    ByDeviceList,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GrayStrategy {
    pub strategy_type: GrayStrategyType,
    pub tags: Option<Vec<String>>,
    pub device_group_id: Option<String>,
    pub percentage: Option<u32>,
    pub device_ids: Option<Vec<String>>,
    pub batch_count: u32,
    pub batch_interval_seconds: u64,
    pub success_threshold: f64,
    pub pause_on_failure: bool,
}

impl Default for GrayStrategy {
    fn default() -> Self {
        Self {
            strategy_type: GrayStrategyType::ByPercentage,
            tags: None,
            device_group_id: None,
            percentage: Some(100),
            device_ids: None,
            batch_count: 1,
            batch_interval_seconds: 0,
            success_threshold: 0.95,
            pause_on_failure: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum RollbackTrigger {
    OnFailure,
    OnDeviceOffline,
    OnVerificationFailed,
    Manual,
    AutoThreshold,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RollbackPolicy {
    pub enabled: bool,
    pub triggers: Vec<RollbackTrigger>,
    pub target_version: Option<String>,
    pub timeout_seconds: u64,
    pub max_retries: u32,
    pub failure_threshold: f64,
}

impl Default for RollbackPolicy {
    fn default() -> Self {
        Self {
            enabled: true,
            triggers: vec![
                RollbackTrigger::OnFailure,
                RollbackTrigger::OnVerificationFailed,
            ],
            target_version: None,
            timeout_seconds: 1800,
            max_retries: 3,
            failure_threshold: 0.2,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FirmwarePackage {
    pub package_id: String,
    pub name: String,
    pub version: String,
    pub previous_version: Option<String>,
    pub device_model: String,
    pub firmware_type: String,
    pub size_bytes: u64,
    pub checksum: String,
    pub checksum_algorithm: String,
    pub download_url: String,
    pub delta_from: Option<String>,
    pub delta_size_bytes: Option<u64>,
    pub delta_checksum: Option<String>,
    pub metadata: HashMap<String, String>,
    pub release_notes: String,
    pub created_by: String,
    pub created_at: DateTime<Utc>,
    pub is_active: bool,
}

impl FirmwarePackage {
    pub fn new(
        name: impl Into<String>,
        version: impl Into<String>,
        device_model: impl Into<String>,
        firmware_type: impl Into<String>,
        size_bytes: u64,
        checksum: impl Into<String>,
        download_url: impl Into<String>,
        created_by: impl Into<String>,
    ) -> Self {
        Self {
            package_id: Uuid::new_v4().to_string(),
            name: name.into(),
            version: version.into(),
            previous_version: None,
            device_model: device_model.into(),
            firmware_type: firmware_type.into(),
            size_bytes,
            checksum: checksum.into(),
            checksum_algorithm: "SHA256".into(),
            download_url: download_url.into(),
            delta_from: None,
            delta_size_bytes: None,
            delta_checksum: None,
            metadata: HashMap::new(),
            release_notes: String::new(),
            created_by: created_by.into(),
            created_at: Utc::now(),
            is_active: true,
        }
    }

    pub fn with_delta(&mut self, from_version: impl Into<String>, delta_size: u64, delta_checksum: impl Into<String>) {
        self.delta_from = Some(from_version.into());
        self.delta_size_bytes = Some(delta_size);
        self.delta_checksum = Some(delta_checksum.into());
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceUpgradeStatus {
    pub device_id: String,
    pub task_id: String,
    pub phase: UpgradePhase,
    pub progress: f32,
    pub error_message: Option<String>,
    pub download_speed_bps: Option<u64>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub rollback_count: u32,
    pub last_heartbeat: Option<DateTime<Utc>>,
}

impl DeviceUpgradeStatus {
    pub fn new(device_id: impl Into<String>, task_id: impl Into<String>) -> Self {
        Self {
            device_id: device_id.into(),
            task_id: task_id.into(),
            phase: UpgradePhase::Pending,
            progress: 0.0,
            error_message: None,
            download_speed_bps: None,
            started_at: None,
            completed_at: None,
            rollback_count: 0,
            last_heartbeat: None,
        }
    }

    pub fn update_phase(&mut self, phase: UpgradePhase) -> Result<(), String> {
        if !self.phase.can_transition_to(&phase) {
            return Err(format!(
                "Invalid phase transition from {:?} to {:?}",
                self.phase, phase
            ));
        }
        self.phase = phase.clone();
        if phase == UpgradePhase::Downloading && self.started_at.is_none() {
            self.started_at = Some(Utc::now());
        }
        if phase.is_terminal() {
            self.completed_at = Some(Utc::now());
        }
        self.last_heartbeat = Some(Utc::now());
        Ok(())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpgradeTask {
    pub task_id: String,
    pub name: String,
    pub description: Option<String>,
    pub firmware_package_id: String,
    pub target_devices_count: u32,
    pub gray_strategy: GrayStrategy,
    pub rollback_policy: RollbackPolicy,
    pub schedule_time: Option<DateTime<Utc>>,
    pub deadline_time: Option<DateTime<Utc>>,
    pub concurrency_limit: u32,
    pub timeout_per_device_seconds: u64,
    pub created_by: String,
    pub approver: Option<String>,
    pub approved_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub status: UpgradePhase,
    pub statistics: UpgradeStatistics,
    pub is_paused: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct UpgradeStatistics {
    pub total_devices: u32,
    pub pending_devices: u32,
    pub downloading_devices: u32,
    pub installing_devices: u32,
    pub verifying_devices: u32,
    pub success_devices: u32,
    pub failed_devices: u32,
    pub rolling_back_devices: u32,
    pub rolled_back_devices: u32,
    pub success_rate: f64,
    pub average_duration_seconds: f64,
}

impl UpgradeTask {
    pub fn new(
        name: impl Into<String>,
        firmware_package_id: impl Into<String>,
        gray_strategy: GrayStrategy,
        rollback_policy: RollbackPolicy,
        created_by: impl Into<String>,
    ) -> Self {
        Self {
            task_id: Uuid::new_v4().to_string(),
            name: name.into(),
            description: None,
            firmware_package_id: firmware_package_id.into(),
            target_devices_count: 0,
            gray_strategy,
            rollback_policy,
            schedule_time: None,
            deadline_time: None,
            concurrency_limit: 100,
            timeout_per_device_seconds: 3600,
            created_by: created_by.into(),
            approver: None,
            approved_at: None,
            created_at: Utc::now(),
            started_at: None,
            completed_at: None,
            status: UpgradePhase::Pending,
            statistics: UpgradeStatistics::default(),
            is_paused: false,
        }
    }

    pub fn approve(&mut self, approver: impl Into<String>) -> Result<(), String> {
        if self.status != UpgradePhase::Pending {
            return Err("Only pending tasks can be approved".into());
        }
        self.status = UpgradePhase::Approved;
        self.approver = Some(approver.into());
        self.approved_at = Some(Utc::now());
        Ok(())
    }

    pub fn update_statistics(&mut self, statuses: &[DeviceUpgradeStatus]) {
        let mut stats = UpgradeStatistics::default();
        stats.total_devices = statuses.len() as u32;

        for s in statuses {
            match s.phase {
                UpgradePhase::Pending | UpgradePhase::Approved => stats.pending_devices += 1,
                UpgradePhase::Downloading => stats.downloading_devices += 1,
                UpgradePhase::Installing => stats.installing_devices += 1,
                UpgradePhase::Verifying => stats.verifying_devices += 1,
                UpgradePhase::Success => stats.success_devices += 1,
                UpgradePhase::Failed => stats.failed_devices += 1,
                UpgradePhase::RollingBack => stats.rolling_back_devices += 1,
                UpgradePhase::RolledBack => stats.rolled_back_devices += 1,
            }
        }

        let completed = stats.success_devices + stats.failed_devices + stats.rolled_back_devices;
        if completed > 0 {
            stats.success_rate = stats.success_devices as f64 / completed as f64;
        }

        self.statistics = stats;
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeltaPackage {
    pub delta_id: String,
    pub from_version: String,
    pub to_version: String,
    pub device_model: String,
    pub size_bytes: u64,
    pub checksum: String,
    pub download_url: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UploadFirmwareRequest {
    pub name: String,
    pub version: String,
    pub previous_version: Option<String>,
    pub device_model: String,
    pub firmware_type: String,
    pub size_bytes: u64,
    pub checksum: String,
    pub checksum_algorithm: Option<String>,
    pub download_url: String,
    pub release_notes: Option<String>,
    pub metadata: Option<HashMap<String, String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateUpgradeTaskRequest {
    pub name: String,
    pub description: Option<String>,
    pub firmware_package_id: String,
    pub gray_strategy: GrayStrategy,
    pub rollback_policy: Option<RollbackPolicy>,
    pub schedule_time: Option<DateTime<Utc>>,
    pub deadline_time: Option<DateTime<Utc>>,
    pub concurrency_limit: Option<u32>,
    pub timeout_per_device_seconds: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApproveUpgradeRequest {
    pub task_id: String,
    pub approved: bool,
    pub comment: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceStatusUpdateRequest {
    pub device_id: String,
    pub task_id: String,
    pub phase: UpgradePhase,
    pub progress: Option<f32>,
    pub error_message: Option<String>,
    pub download_speed_bps: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FirmwareResponse {
    pub package_id: String,
    pub name: String,
    pub version: String,
    pub device_model: String,
    pub firmware_type: String,
    pub size_bytes: u64,
    pub checksum: String,
    pub download_url: String,
    pub delta_size_bytes: Option<u64>,
    pub release_notes: String,
    pub created_at: DateTime<Utc>,
    pub is_active: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpgradeTaskResponse {
    pub task_id: String,
    pub name: String,
    pub firmware_package_id: String,
    pub firmware_version: String,
    pub status: String,
    pub statistics: UpgradeStatistics,
    pub created_by: String,
    pub approver: Option<String>,
    pub created_at: DateTime<Utc>,
    pub approved_at: Option<DateTime<Utc>>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceStatusResponse {
    pub device_id: String,
    pub task_id: String,
    pub phase: String,
    pub progress: f32,
    pub error_message: Option<String>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GenerateDeltaRequest {
    pub from_package_id: String,
    pub to_package_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeltaResponse {
    pub delta_id: String,
    pub from_version: String,
    pub to_version: String,
    pub size_bytes: u64,
    pub original_size_bytes: u64,
    pub compression_ratio: f64,
    pub download_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RollbackRequest {
    pub task_id: String,
    pub device_ids: Option<Vec<String>>,
    pub reason: String,
}
