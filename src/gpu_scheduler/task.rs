use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use chrono::{DateTime, Utc};
use crate::utils::error::AppError;
use crate::utils::id::generate_id;
use crate::gpu_scheduler::resource::GpuResourceSpec;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskPriority {
    Low = 0,
    Medium = 1,
    High = 2,
    Critical = 3,
    Realtime = 4,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskStatus {
    Pending,
    Queued,
    Scheduled,
    Running,
    Suspended,
    Completed,
    Failed,
    Cancelled,
    Preempted,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskType {
    Training,
    Inference,
    FineTuning,
    BatchProcessing,
    Vectorization,
    ModelEvaluation,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpuTaskSpec {
    pub name: String,
    pub description: String,
    pub task_type: TaskType,
    pub priority: TaskPriority,
    pub resource_requirements: GpuResourceSpec,
    pub is_preemptible: bool,
    pub preemption_priority: u8,
    pub estimated_duration_secs: Option<u64>,
    pub max_duration_secs: Option<u64>,
    pub node_affinity: Vec<String>,
    pub node_anti_affinity: Vec<String>,
    pub labels: HashMap<String, String>,
    pub metadata: HashMap<String, String>,
    pub created_by: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GpuTask {
    pub task_id: String,
    pub spec: GpuTaskSpec,
    pub status: TaskStatus,
    pub queue_time: Option<DateTime<Utc>>,
    pub schedule_time: Option<DateTime<Utc>>,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
    pub allocated_device_id: Option<String>,
    pub allocation_id: Option<String>,
    pub progress: f64,
    pub retries: u32,
    pub max_retries: u32,
    pub error_message: Option<String>,
    pub preemptions: u32,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl TaskPriority {
    pub fn from_u8(value: u8) -> Result<Self, AppError> {
        match value {
            0 => Ok(TaskPriority::Low),
            1 => Ok(TaskPriority::Medium),
            2 => Ok(TaskPriority::High),
            3 => Ok(TaskPriority::Critical),
            4 => Ok(TaskPriority::Realtime),
            _ => Err(AppError::Validation(format!("Invalid priority value: {}", value))),
        }
    }
}

impl GpuTask {
    pub fn new(spec: GpuTaskSpec, max_retries: u32) -> Result<Self, AppError> {
        if spec.name.is_empty() {
            return Err(AppError::Validation("Task name cannot be empty".to_string()));
        }
        spec.resource_requirements.validate()?;

        if spec.preemption_priority > 10 {
            return Err(AppError::Validation(
                "Preemption priority must be between 0 and 10".to_string()
            ));
        }

        let now = Utc::now();
        Ok(Self {
            task_id: generate_id("task"),
            spec,
            status: TaskStatus::Pending,
            queue_time: None,
            schedule_time: None,
            start_time: None,
            end_time: None,
            allocated_device_id: None,
            allocation_id: None,
            progress: 0.0,
            retries: 0,
            max_retries,
            error_message: None,
            preemptions: 0,
            created_at: now,
            updated_at: now,
        })
    }

    pub fn enqueue(&mut self) -> Result<(), AppError> {
        if self.status != TaskStatus::Pending {
            return Err(AppError::Validation(format!(
                "Cannot enqueue task from status: {:?}",
                self.status
            )));
        }
        self.status = TaskStatus::Queued;
        self.queue_time = Some(Utc::now());
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn schedule(&mut self, device_id: String, allocation_id: String) -> Result<(), AppError> {
        if self.status != TaskStatus::Queued {
            return Err(AppError::Validation(format!(
                "Cannot schedule task from status: {:?}",
                self.status
            )));
        }
        self.status = TaskStatus::Scheduled;
        self.schedule_time = Some(Utc::now());
        self.allocated_device_id = Some(device_id);
        self.allocation_id = Some(allocation_id);
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn start(&mut self) -> Result<(), AppError> {
        if self.status != TaskStatus::Scheduled {
            return Err(AppError::Validation(format!(
                "Cannot start task from status: {:?}",
                self.status
            )));
        }
        self.status = TaskStatus::Running;
        self.start_time = Some(Utc::now());
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn update_progress(&mut self, progress: f64) -> Result<(), AppError> {
        if self.status != TaskStatus::Running {
            return Err(AppError::Validation(format!(
                "Cannot update progress for task in status: {:?}",
                self.status
            )));
        }
        if !(0.0..=1.0).contains(&progress) {
            return Err(AppError::Validation(format!(
                "Progress must be between 0 and 1, got {}",
                progress
            )));
        }
        self.progress = progress;
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn complete(&mut self) -> Result<(), AppError> {
        if self.status != TaskStatus::Running {
            return Err(AppError::Validation(format!(
                "Cannot complete task from status: {:?}",
                self.status
            )));
        }
        self.status = TaskStatus::Completed;
        self.end_time = Some(Utc::now());
        self.progress = 1.0;
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn fail(&mut self, error: String) -> Result<(), AppError> {
        if !matches!(self.status, TaskStatus::Running | TaskStatus::Scheduled) {
            return Err(AppError::Validation(format!(
                "Cannot fail task from status: {:?}",
                self.status
            )));
        }
        self.retries += 1;
        self.error_message = Some(error);
        self.updated_at = Utc::now();

        if self.retries < self.max_retries {
            self.status = TaskStatus::Queued;
            self.allocated_device_id = None;
            self.allocation_id = None;
        } else {
            self.status = TaskStatus::Failed;
            self.end_time = Some(Utc::now());
        }
        Ok(())
    }

    pub fn cancel(&mut self) -> Result<(), AppError> {
        if matches!(self.status, TaskStatus::Completed | TaskStatus::Failed | TaskStatus::Cancelled) {
            return Err(AppError::Validation(format!(
                "Cannot cancel task from status: {:?}",
                self.status
            )));
        }
        self.status = TaskStatus::Cancelled;
        self.end_time = Some(Utc::now());
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn preempt(&mut self) -> Result<(), AppError> {
        if self.status != TaskStatus::Running {
            return Err(AppError::Validation(format!(
                "Cannot preempt task from status: {:?}",
                self.status
            )));
        }
        if !self.spec.is_preemptible {
            return Err(AppError::Validation("Task is not preemptible".to_string()));
        }
        self.status = TaskStatus::Preempted;
        self.preemptions += 1;
        self.end_time = Some(Utc::now());
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn resume(&mut self) -> Result<(), AppError> {
        if self.status != TaskStatus::Preempted && self.status != TaskStatus::Suspended {
            return Err(AppError::Validation(format!(
                "Cannot resume task from status: {:?}",
                self.status
            )));
        }
        self.status = TaskStatus::Queued;
        self.allocated_device_id = None;
        self.allocation_id = None;
        self.end_time = None;
        self.updated_at = Utc::now();
        Ok(())
    }

    pub fn get_wait_time_secs(&self) -> Option<i64> {
        match (self.queue_time, self.start_time) {
            (Some(qt), Some(st)) => Some((st - qt).num_seconds()),
            _ => None,
        }
    }

    pub fn get_execution_time_secs(&self) -> Option<i64> {
        match (self.start_time, self.end_time) {
            (Some(st), Some(et)) => Some((et - st).num_seconds()),
            (Some(st), None) => Some((Utc::now() - st).num_seconds()),
            _ => None,
        }
    }

    pub fn is_timed_out(&self) -> bool {
        if let Some(max_duration) = self.spec.max_duration_secs {
            if let Some(elapsed) = self.get_execution_time_secs() {
                return elapsed > max_duration as i64;
            }
        }
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gpu_scheduler::resource::GpuResourceSpec;

    fn create_test_spec() -> GpuTaskSpec {
        GpuTaskSpec {
            name: "Test Task".to_string(),
            description: "A test GPU task".to_string(),
            task_type: TaskType::Training,
            priority: TaskPriority::Medium,
            resource_requirements: GpuResourceSpec {
                gpu_memory_gb: 8.0,
                gpu_cores: 1024,
                compute_units: 28,
                memory_bandwidth_gbps: 0.0,
                tensor_cores: None,
                rt_cores: None,
            },
            is_preemptible: true,
            preemption_priority: 5,
            estimated_duration_secs: Some(3600),
            max_duration_secs: Some(7200),
            node_affinity: vec![],
            node_anti_affinity: vec![],
            labels: HashMap::new(),
            metadata: HashMap::new(),
            created_by: "test_user".to_string(),
        }
    }

    #[test]
    fn test_task_creation() {
        let spec = create_test_spec();
        let task = GpuTask::new(spec, 3).unwrap();
        
        assert!(task.task_id.starts_with("task_"));
        assert_eq!(task.status, TaskStatus::Pending);
        assert_eq!(task.progress, 0.0);
        assert_eq!(task.retries, 0);
    }

    #[test]
    fn test_task_creation_validation() {
        let mut spec = create_test_spec();
        spec.name = "".to_string();
        
        let result = GpuTask::new(spec, 3);
        assert!(result.is_err());
    }

    #[test]
    fn test_task_lifecycle() {
        let spec = create_test_spec();
        let mut task = GpuTask::new(spec, 3).unwrap();
        
        assert!(task.enqueue().is_ok());
        assert_eq!(task.status, TaskStatus::Queued);
        
        assert!(task.schedule("gpu_123".to_string(), "alloc_456".to_string()).is_ok());
        assert_eq!(task.status, TaskStatus::Scheduled);
        assert_eq!(task.allocated_device_id, Some("gpu_123".to_string()));
        
        assert!(task.start().is_ok());
        assert_eq!(task.status, TaskStatus::Running);
        
        assert!(task.update_progress(0.5).is_ok());
        assert_eq!(task.progress, 0.5);
        
        assert!(task.complete().is_ok());
        assert_eq!(task.status, TaskStatus::Completed);
        assert_eq!(task.progress, 1.0);
    }

    #[test]
    fn test_task_preemption() {
        let spec = create_test_spec();
        let mut task = GpuTask::new(spec, 3).unwrap();
        
        task.enqueue().unwrap();
        task.schedule("gpu_123".to_string(), "alloc_456".to_string()).unwrap();
        task.start().unwrap();
        
        assert!(task.preempt().is_ok());
        assert_eq!(task.status, TaskStatus::Preempted);
        assert_eq!(task.preemptions, 1);
        
        assert!(task.resume().is_ok());
        assert_eq!(task.status, TaskStatus::Queued);
    }

    #[test]
    fn test_task_failure_with_retry() {
        let spec = create_test_spec();
        let mut task = GpuTask::new(spec, 2).unwrap();
        
        task.enqueue().unwrap();
        task.schedule("gpu_123".to_string(), "alloc_456".to_string()).unwrap();
        task.start().unwrap();
        
        assert!(task.fail("GPU out of memory".to_string()).is_ok());
        assert_eq!(task.retries, 1);
        assert_eq!(task.status, TaskStatus::Queued);
        
        task.schedule("gpu_789".to_string(), "alloc_012".to_string()).unwrap();
        task.start().unwrap();
        
        assert!(task.fail("Another error".to_string()).is_ok());
        assert_eq!(task.retries, 2);
        assert_eq!(task.status, TaskStatus::Failed);
    }

    #[test]
    fn test_task_cancellation() {
        let spec = create_test_spec();
        let mut task = GpuTask::new(spec, 3).unwrap();
        
        task.enqueue().unwrap();
        assert!(task.cancel().is_ok());
        assert_eq!(task.status, TaskStatus::Cancelled);
    }

    #[test]
    fn test_task_timing() {
        let spec = create_test_spec();
        let mut task = GpuTask::new(spec, 3).unwrap();
        
        task.enqueue().unwrap();
        task.schedule("gpu_123".to_string(), "alloc_456".to_string()).unwrap();
        task.start().unwrap();
        
        assert!(task.get_wait_time_secs().is_some());
        assert!(task.get_execution_time_secs().is_some());
    }

    #[test]
    fn test_task_priority_ordering() {
        assert!(TaskPriority::Low < TaskPriority::Medium);
        assert!(TaskPriority::Medium < TaskPriority::High);
        assert!(TaskPriority::High < TaskPriority::Critical);
        assert!(TaskPriority::Critical < TaskPriority::Realtime);
    }
}
