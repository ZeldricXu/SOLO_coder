use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::time::Instant;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpuLoad {
    pub gpu_id: usize,
    pub total_mb: u64,
    pub free_mb: u64,
    pub util_percent: f64,
    pub model_count: u32,
    pub score: f64,
    pub node_address: Option<String>,
    pub is_healthy: bool,
}

impl GpuLoad {
    pub fn new(gpu_id: usize, total_mb: u64) -> Self {
        Self {
            gpu_id,
            total_mb,
            free_mb: total_mb,
            util_percent: 0.0,
            model_count: 0,
            score: 0.0,
            node_address: None,
            is_healthy: true,
        }
    }

    pub fn can_fit(&self, required_mb: u64) -> bool {
        self.is_healthy && self.free_mb >= required_mb
    }

    pub fn used_mb(&self) -> u64 {
        self.total_mb.saturating_sub(self.free_mb)
    }

    pub fn memory_usage_percent(&self) -> f64 {
        if self.total_mb > 0 {
            (self.used_mb() as f64 / self.total_mb as f64) * 100.0
        } else {
            0.0
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum GpuSelectionStrategy {
    LeastLoaded,
    LeastMemory,
    RoundRobin,
    Affinity,
    BinPacking,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchedulerConfig {
    pub scheduling_interval_secs: u64,
    pub heartbeat_interval_secs: u64,
    pub heartbeat_timeout_secs: u64,
    pub max_unhealthy_heartbeats: u32,
    pub model_ttl_seconds: u64,
    pub cold_threshold_rps: f64,
    pub hot_threshold_rps: f64,
    pub min_loaded_models_per_gpu: u32,
    pub max_replicas_per_model: u32,
    pub max_models_per_gpu: u32,
    pub safety_margin_mb: u64,
    pub warmup_batch_size: u32,
    pub warmup_iterations: u32,
    pub auto_scale_enabled: bool,
    pub overload_util_threshold: f64,
    pub overload_memory_threshold: f64,
    pub underload_util_threshold: f64,
    pub underload_memory_threshold: f64,
    pub rebalance_imbalance_threshold: f64,
    pub heat_score_hot_threshold: f32,
    pub heat_score_cold_threshold: f32,
}

impl Default for SchedulerConfig {
    fn default() -> Self {
        Self {
            scheduling_interval_secs: 30,
            heartbeat_interval_secs: 5,
            heartbeat_timeout_secs: 30,
            max_unhealthy_heartbeats: 3,
            model_ttl_seconds: 3600,
            cold_threshold_rps: 0.1,
            hot_threshold_rps: 10.0,
            min_loaded_models_per_gpu: 1,
            max_replicas_per_model: 4,
            max_models_per_gpu: 50,
            safety_margin_mb: 1024,
            warmup_batch_size: 4,
            warmup_iterations: 10,
            auto_scale_enabled: true,
            overload_util_threshold: 80.0,
            overload_memory_threshold: 85.0,
            underload_util_threshold: 20.0,
            underload_memory_threshold: 30.0,
            rebalance_imbalance_threshold: 30.0,
            heat_score_hot_threshold: 50.0,
            heat_score_cold_threshold: 10.0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum SchedulerEvent {
    ModelLoaded {
        version_id: Uuid,
        gpu_id: usize,
    },
    ModelUnloaded {
        version_id: Uuid,
        gpu_id: usize,
    },
    ModelMigrated {
        version_id: Uuid,
        from_gpu: usize,
        to_gpu: usize,
    },
    ModelStatusChanged {
        version_id: Uuid,
        old_status: common::types::ModelStatus,
        new_status: common::types::ModelStatus,
    },
    GpuAdded {
        gpu_id: usize,
        total_mb: u64,
        node_address: String,
    },
    GpuRemoved {
        gpu_id: usize,
    },
    GpuOverloaded {
        gpu_id: usize,
        util_percent: f64,
        memory_percent: f64,
    },
    GpuRecovered {
        gpu_id: usize,
    },
    NodeDead {
        node_id: String,
        address: String,
    },
    Heartbeat {
        node_address: String,
        gpu_id: usize,
        healthy: bool,
        qps: f64,
        avg_latency_ms: f64,
        error_rate: f64,
    },
    TrafficChanged {
        version_id: Uuid,
        rps: f64,
    },
    InferenceRequested {
        version_id: Uuid,
    },
    SchedulingCycleCompleted {
        cycle_id: u64,
        decisions: u32,
        duration_ms: u64,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HeartbeatStats {
    pub qps: f64,
    pub avg_latency_ms: f64,
    pub error_rate: f64,
    pub gpu_util_percent: f64,
    pub gpu_memory_used_mb: u64,
    pub active_models: Vec<Uuid>,
}

impl Default for HeartbeatStats {
    fn default() -> Self {
        Self {
            qps: 0.0,
            avg_latency_ms: 0.0,
            error_rate: 0.0,
            gpu_util_percent: 0.0,
            gpu_memory_used_mb: 0,
            active_models: vec![],
        }
    }
}

#[derive(Debug, Clone)]
pub struct HeartbeatInfo {
    pub node_address: String,
    pub gpu_id: usize,
    pub last_heartbeat: Instant,
    pub consecutive_failures: u32,
    pub is_healthy: bool,
    pub qps: f64,
    pub avg_latency_ms: f64,
    pub error_rate: f64,
    pub gpu_util_percent: f64,
    pub gpu_memory_used_mb: u64,
}

impl HeartbeatInfo {
    pub fn new(node_address: String, gpu_id: usize) -> Self {
        Self {
            node_address,
            gpu_id,
            last_heartbeat: Instant::now(),
            consecutive_failures: 0,
            is_healthy: true,
            qps: 0.0,
            avg_latency_ms: 0.0,
            error_rate: 0.0,
            gpu_util_percent: 0.0,
            gpu_memory_used_mb: 0,
        }
    }
}

#[derive(Debug, Clone)]
pub struct NodeInfo {
    pub node_id: String,
    pub address: String,
    pub gpu_ids: Vec<usize>,
    pub state: GpuNodeState,
    pub registered_at: Instant,
    pub last_heartbeat: Instant,
    pub total_models: usize,
    pub qps: f64,
}

#[derive(Debug, Clone)]
pub struct DeadNode {
    pub node_id: String,
    pub address: String,
    pub gpu_ids: Vec<usize>,
    pub last_heartbeat: Instant,
    pub missed_heartbeats: u32,
    pub models_hosted: Vec<Uuid>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum GpuNodeState {
    Healthy,
    Overloaded,
    Offline,
}

impl GpuNodeState {
    pub fn as_str(&self) -> &'static str {
        match self {
            GpuNodeState::Healthy => "healthy",
            GpuNodeState::Overloaded => "overloaded",
            GpuNodeState::Offline => "offline",
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoadedModelInfo {
    pub version_id: Uuid,
    pub model_name: String,
    pub version: String,
    pub gpu_id: usize,
    pub size_mb: u64,
    pub ref_count: u32,
    pub access_count: u64,
    pub loaded_at: Instant,
    pub last_accessed_at: Instant,
    pub is_warmed_up: bool,
    pub priority: u32,
}

impl LoadedModelInfo {
    pub fn new(
        version_id: Uuid,
        model_name: String,
        version: String,
        gpu_id: usize,
        size_mb: u64,
        priority: u32,
    ) -> Self {
        let now = Instant::now();
        Self {
            version_id,
            model_name,
            version,
            gpu_id,
            size_mb,
            ref_count: 0,
            access_count: 0,
            loaded_at: now,
            last_accessed_at: now,
            is_warmed_up: false,
            priority,
        }
    }

    pub fn age_seconds(&self) -> u64 {
        self.loaded_at.elapsed().as_secs()
    }

    pub fn idle_seconds(&self) -> u64 {
        self.last_accessed_at.elapsed().as_secs()
    }

    pub fn heat_score(&self) -> f32 {
        let age = self.age_seconds().max(1) as f32;
        let access = self.access_count as f32;
        let rps = access / age;
        let idle_factor = if self.idle_seconds() > 300 {
            0.5
        } else {
            1.0
        };

        (rps * 10.0 + (self.priority as f32) * 0.1) * idle_factor
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeploymentInfo {
    pub version_id: Uuid,
    pub model_name: String,
    pub version: String,
    pub deployed_gpus: Vec<usize>,
    pub primary_gpu: Option<usize>,
    pub size_mb: u64,
    pub status: DeploymentStatus,
    pub heat_score: f32,
    pub access_count: u64,
    pub last_accessed_at: Instant,
    pub is_warmed_up: bool,
    pub node_addresses: std::collections::HashMap<usize, String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum DeploymentStatus {
    Deploying,
    Deployed,
    Failed,
    Unloading,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ModelHeatScore {
    pub version_id: Uuid,
    pub score: f32,
}

impl ModelHeatScore {
    pub fn calculate(
        version_id: Uuid,
        access_count: u64,
        idle_seconds: u64,
        priority: u32,
        age_seconds: u64,
    ) -> Self {
        let age = age_seconds.max(1) as f32;
        let access = access_count as f32;
        let rps = access / age;
        let idle_factor = if idle_seconds > 300 { 0.5 } else { 1.0 };

        let score = (rps * 10.0 + (priority as f32) * 0.1) * idle_factor;

        Self { version_id, score }
    }

    pub fn is_hot(&self, threshold: f32) -> bool {
        self.score >= threshold
    }

    pub fn is_cold(&self, threshold: f32) -> bool {
        self.score < threshold
    }
}

impl std::fmt::Debug for ModelHeatScore {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ModelHeatScore")
            .field("version_id", &self.version_id)
            .field("score", &format_args!("{:.1}", self.score))
            .finish()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WarmupReport {
    pub version_id: Uuid,
    pub gpu_id: usize,
    pub iterations: u32,
    pub cold_start_latency_ms: f64,
    pub stable_latency_p50_ms: f64,
    pub stable_latency_p95_ms: f64,
    pub memory_peak_mb: u64,
    pub success: bool,
    pub error_message: Option<String>,
    pub per_iteration_latency_ms: Vec<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WarmupProgress {
    pub version_id: Uuid,
    pub gpu_id: usize,
    pub total_iterations: u32,
    pub completed_iterations: u32,
    pub failed_iterations: u32,
    pub current_latency_p50_ms: f64,
    pub current_latency_p95_ms: f64,
    pub is_complete: bool,
    pub is_success: bool,
    pub last_updated_at: Instant,
}

impl WarmupProgress {
    pub fn new(version_id: Uuid, gpu_id: usize, total_iterations: u32) -> Self {
        Self {
            version_id,
            gpu_id,
            total_iterations,
            completed_iterations: 0,
            failed_iterations: 0,
            current_latency_p50_ms: 0.0,
            current_latency_p95_ms: 0.0,
            is_complete: false,
            is_success: false,
            last_updated_at: Instant::now(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SampleRequest {
    pub sample_id: String,
    pub model_name: String,
    pub version: String,
    pub inputs: serde_json::Value,
    pub parameters: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BinPackingItem {
    pub version_id: Uuid,
    pub size_mb: u64,
    pub priority: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BinPackingPlacement {
    pub version_id: Uuid,
    pub gpu_id: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BinPackingResult {
    pub placements: Vec<BinPackingPlacement>,
    pub unplaced: Vec<BinPackingItem>,
    pub gpu_utilization: HashMap<usize, f64>,
}

#[derive(Debug, Clone, Default)]
pub struct SchedulingDecision {
    pub loaded_models: Vec<(Uuid, usize)>,
    pub unloaded_models: Vec<(Uuid, usize)>,
    pub migrated_models: Vec<(Uuid, usize, usize)>,
    pub warmed_models: Vec<(Uuid, usize)>,
    pub details: Vec<SchedulingDecisionDetail>,
}

impl SchedulingDecision {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn is_empty(&self) -> bool {
        self.loaded_models.is_empty()
            && self.unloaded_models.is_empty()
            && self.migrated_models.is_empty()
            && self.warmed_models.is_empty()
    }

    pub fn total_actions(&self) -> usize {
        self.loaded_models.len()
            + self.unloaded_models.len()
            + self.migrated_models.len()
            + self.warmed_models.len()
    }

    pub fn merge(&mut self, other: SchedulingDecision) {
        self.loaded_models.extend(other.loaded_models);
        self.unloaded_models.extend(other.unloaded_models);
        self.migrated_models.extend(other.migrated_models);
        self.warmed_models.extend(other.warmed_models);
        self.details.extend(other.details);
    }
}

#[derive(Debug, Clone)]
pub struct SchedulingDecisionDetail {
    pub decision_type: SchedulingDecisionType,
    pub version_id: Uuid,
    pub from_gpu: Option<usize>,
    pub to_gpu: Option<usize>,
    pub reason: String,
    pub priority: u32,
    pub created_at: Instant,
}

impl SchedulingDecisionDetail {
    pub fn load(version_id: Uuid, gpu_id: usize, reason: String) -> Self {
        Self {
            decision_type: SchedulingDecisionType::Load,
            version_id,
            from_gpu: None,
            to_gpu: Some(gpu_id),
            reason,
            priority: 50,
            created_at: Instant::now(),
        }
    }

    pub fn unload(version_id: Uuid, gpu_id: usize, reason: String) -> Self {
        Self {
            decision_type: SchedulingDecisionType::Unload,
            version_id,
            from_gpu: Some(gpu_id),
            to_gpu: None,
            reason,
            priority: 50,
            created_at: Instant::now(),
        }
    }

    pub fn migrate(version_id: Uuid, from_gpu: usize, to_gpu: usize, reason: String) -> Self {
        Self {
            decision_type: SchedulingDecisionType::Migrate,
            version_id,
            from_gpu: Some(from_gpu),
            to_gpu: Some(to_gpu),
            reason,
            priority: 70,
            created_at: Instant::now(),
        }
    }

    pub fn warmup(version_id: Uuid, gpu_id: usize, reason: String) -> Self {
        Self {
            decision_type: SchedulingDecisionType::Warmup,
            version_id,
            from_gpu: None,
            to_gpu: Some(gpu_id),
            reason,
            priority: 30,
            created_at: Instant::now(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SchedulingDecisionType {
    Load,
    Unload,
    Migrate,
    Warmup,
}
