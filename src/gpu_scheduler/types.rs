use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use uuid::Uuid;
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskPriority {
    Low = 0,
    Medium = 1,
    High = 2,
    Critical = 3,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskStatus {
    Pending,
    Scheduled,
    Running,
    Preempted,
    Completed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpuDevice {
    pub device_id: String,
    pub gpu_index: u32,
    pub total_memory_mb: u64,
    pub used_memory_mb: u64,
    pub compute_capability: String,
    pub node_id: String,
    pub available: bool,
    pub labels: HashMap<String, String>,
}

impl GpuDevice {
    pub fn new(gpu_index: u32, total_memory_mb: u64, node_id: &str) -> Self {
        Self {
            device_id: Uuid::new_v4().to_string(),
            gpu_index,
            total_memory_mb,
            used_memory_mb: 0,
            compute_capability: "8.0".to_string(),
            node_id: node_id.to_string(),
            available: true,
            labels: HashMap::new(),
        }
    }

    pub fn available_memory_mb(&self) -> u64 {
        self.total_memory_mb - self.used_memory_mb
    }

    pub fn can_allocate(&self, memory_mb: u64) -> bool {
        self.available && self.available_memory_mb() >= memory_mb
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpuResourceRequest {
    pub min_memory_mb: u64,
    pub preferred_memory_mb: u64,
    pub gpu_count: u32,
    pub required_labels: HashMap<String, String>,
    pub allow_preemption: bool,
}

impl Default for GpuResourceRequest {
    fn default() -> Self {
        Self {
            min_memory_mb: 1024,
            preferred_memory_mb: 2048,
            gpu_count: 1,
            required_labels: HashMap::new(),
            allow_preemption: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpuTask {
    pub task_id: String,
    pub name: String,
    pub priority: TaskPriority,
    pub resource_request: GpuResourceRequest,
    pub status: TaskStatus,
    pub allocated_devices: Vec<String>,
    pub preempt_count: u32,
    pub created_at: DateTime<Utc>,
    pub scheduled_at: Option<DateTime<Utc>>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub metadata: serde_json::Value,
}

impl GpuTask {
    pub fn new(name: &str, priority: TaskPriority, resource_request: GpuResourceRequest) -> Self {
        Self {
            task_id: Uuid::new_v4().to_string(),
            name: name.to_string(),
            priority,
            resource_request,
            status: TaskStatus::Pending,
            allocated_devices: Vec::new(),
            preempt_count: 0,
            created_at: Utc::now(),
            scheduled_at: None,
            started_at: None,
            completed_at: None,
            metadata: serde_json::json!({}),
        }
    }

    pub fn is_running(&self) -> bool {
        matches!(self.status, TaskStatus::Running | TaskStatus::Scheduled)
    }

    pub fn can_be_preempted(&self) -> bool {
        self.resource_request.allow_preemption 
            && self.status == TaskStatus::Running
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AllocationResult {
    pub success: bool,
    pub task_id: String,
    pub allocated_devices: Vec<GpuDevice>,
    pub preempted_tasks: Vec<String>,
    pub reason: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchedulerStats {
    pub total_gpus: usize,
    pub available_gpus: usize,
    pub total_memory_mb: u64,
    pub used_memory_mb: u64,
    pub pending_tasks: usize,
    pub running_tasks: usize,
    pub completed_tasks: usize,
    pub preempted_tasks: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PreemptionStrategy {
    pub enabled: bool,
    pub min_priority_to_preempt: TaskPriority,
    pub max_preemptions_per_task: u32,
}

impl Default for PreemptionStrategy {
    fn default() -> Self {
        Self {
            enabled: true,
            min_priority_to_preempt: TaskPriority::Low,
            max_preemptions_per_task: 3,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_gpu_device_allocation() {
        let device = GpuDevice::new(0, 8192, "node-1");
        assert_eq!(device.available_memory_mb(), 8192);
        assert!(device.can_allocate(4096));
        assert!(!device.can_allocate(16384));
    }

    #[test]
    fn test_task_priority_ordering() {
        assert!(TaskPriority::Critical > TaskPriority::High);
        assert!(TaskPriority::High > TaskPriority::Medium);
        assert!(TaskPriority::Medium > TaskPriority::Low);
    }

    #[test]
    fn test_task_creation() {
        let request = GpuResourceRequest::default();
        let task = GpuTask::new("test-task", TaskPriority::High, request);
        assert_eq!(task.status, TaskStatus::Pending);
        assert!(task.allocated_devices.is_empty());
        assert_eq!(task.preempt_count, 0);
    }

    #[test]
    fn test_can_be_preempted() {
        let mut request = GpuResourceRequest::default();
        request.allow_preemption = true;
        let mut task = GpuTask::new("test", TaskPriority::Low, request);
        task.status = TaskStatus::Running;
        assert!(task.can_be_preempted());

        task.status = TaskStatus::Pending;
        assert!(!task.can_be_preempted());

        task.resource_request.allow_preemption = false;
        task.status = TaskStatus::Running;
        assert!(!task.can_be_preempted());
    }
}
