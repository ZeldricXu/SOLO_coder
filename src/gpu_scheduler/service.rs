use std::collections::HashMap;
use std::sync::Arc;
use dashmap::DashMap;
use parking_lot::RwLock;
use priority_queue::PriorityQueue;
use crate::models::error::ModelGuardError;
use crate::models::Result;
use super::types::*;
use tracing::{info, warn, debug, error};

pub struct GpuSchedulerService {
    devices: DashMap<String, GpuDevice>,
    tasks: DashMap<String, GpuTask>,
    pending_queue: Arc<RwLock<PriorityQueue<String, TaskPriority>>>,
    preemption_strategy: PreemptionStrategy,
    stats: RwLock<SchedulerStats>,
}

impl GpuSchedulerService {
    pub fn new() -> Self {
        Self {
            devices: DashMap::new(),
            tasks: DashMap::new(),
            pending_queue: Arc::new(RwLock::new(PriorityQueue::new())),
            preemption_strategy: PreemptionStrategy::default(),
            stats: RwLock::new(SchedulerStats {
                total_gpus: 0,
                available_gpus: 0,
                total_memory_mb: 0,
                used_memory_mb: 0,
                pending_tasks: 0,
                running_tasks: 0,
                completed_tasks: 0,
                preempted_tasks: 0,
            }),
        }
    }

    pub fn register_device(&self, device: GpuDevice) {
        let device_id = device.device_id.clone();
        let total_memory = device.total_memory_mb;
        let available = device.available;
        
        self.devices.insert(device_id.clone(), device);
        
        let mut stats = self.stats.write();
        stats.total_gpus += 1;
        stats.total_memory_mb += total_memory;
        if available {
            stats.available_gpus += 1;
        }
        
        info!(device_id = %device_id, "GPU device registered");
    }

    pub fn submit_task(&self, task: GpuTask) -> Result<String> {
        let task_id = task.task_id.clone();
        
        if task.resource_request.gpu_count == 0 {
            return Err(ModelGuardError::ValidationError(
                "GPU count must be greater than 0".to_string()
            ));
        }

        if self.tasks.contains_key(&task_id) {
            return Err(ModelGuardError::ConflictError(
                format!("Task {} already exists", task_id)
            ));
        }

        let priority = task.priority;
        self.tasks.insert(task_id.clone(), task);
        
        self.pending_queue.write().push(task_id.clone(), priority);
        
        let mut stats = self.stats.write();
        stats.pending_tasks += 1;
        
        info!(task_id = %task_id, priority = ?priority, "GPU task submitted");
        Ok(task_id)
    }

    pub fn schedule(&self) -> Result<Vec<AllocationResult>> {
        let mut results = Vec::new();
        
        loop {
            let next_task = {
                let mut queue = self.pending_queue.write();
                queue.pop().map(|(id, _)| id)
            };

            match next_task {
                Some(task_id) => {
                    let result = self.try_allocate(&task_id)?;
                    
                    if result.success {
                        results.push(result);
                    } else {
                        let task = self.tasks.get(&task_id).unwrap();
                        self.pending_queue.write().push(task_id.clone(), task.priority);
                        break;
                    }
                }
                None => break,
            }
        }

        self.process_scheduling_queue();
        Ok(results)
    }

    fn try_allocate(&self, task_id: &str) -> Result<AllocationResult> {
        let task = self.tasks.get(task_id).ok_or_else(|| {
            ModelGuardError::NotFound(format!("Task {} not found", task_id))
        })?;

        let request = &task.resource_request;
        
        let mut suitable_devices: Vec<GpuDevice> = self.devices
            .iter()
            .filter(|d| {
                if !d.can_allocate(request.min_memory_mb) {
                    return false;
                }
                for (key, value) in &request.required_labels {
                    if d.labels.get(key) != Some(value) {
                        return false;
                    }
                }
                true
            })
            .map(|d| d.clone())
            .collect();

        suitable_devices.sort_by_key(|d| std::cmp::Reverse(d.available_memory_mb()));

        if suitable_devices.len() >= request.gpu_count as usize {
            let allocated: Vec<GpuDevice> = suitable_devices
                .into_iter()
                .take(request.gpu_count as usize)
                .collect();
            
            return self.complete_allocation(task_id, allocated, Vec::new());
        }

        if self.preemption_strategy.enabled && task.priority > self.preemption_strategy.min_priority_to_preempt {
            let preempt_result = self.try_preempt(task_id, request);
            if preempt_result.success {
                return Ok(preempt_result);
            }
        }

        debug!(task_id = %task_id, "Insufficient GPU resources, task remains pending");
        
        Ok(AllocationResult {
            success: false,
            task_id: task_id.to_string(),
            allocated_devices: Vec::new(),
            preempted_tasks: Vec::new(),
            reason: Some("Insufficient GPU resources".to_string()),
        })
    }

    fn try_preempt(&self, task_id: &str, request: &GpuResourceRequest) -> AllocationResult {
        let mut running_tasks: Vec<(String, TaskPriority, u32, Vec<String>)> = self.tasks
            .iter()
            .filter(|t| t.can_be_preempted() && t.preempt_count < self.preemption_strategy.max_preemptions_per_task)
            .map(|t| (t.task_id.clone(), t.priority, t.preempt_count, t.allocated_devices.clone()))
            .collect();

        running_tasks.sort_by(|a, b| {
            a.1.cmp(&b.1)
                .then_with(|| a.2.cmp(&b.2))
        });

        let mut preempted = Vec::new();
        let mut freed_devices = Vec::new();
        let needed = request.gpu_count as usize;

        for (pt_id, _, _, devices) in running_tasks {
            if freed_devices.len() >= needed {
                break;
            }
            
            for dev_id in devices {
                if freed_devices.len() < needed {
                    if let Some(dev) = self.devices.get(&dev_id) {
                        freed_devices.push(dev.clone());
                    }
                }
            }
            preempted.push(pt_id);
        }

        if freed_devices.len() >= needed {
            let allocated: Vec<GpuDevice> = freed_devices.into_iter().take(needed).collect();
            
            for pt_id in &preempted {
                if let Some(mut pt) = self.tasks.get_mut(pt_id) {
                    pt.status = TaskStatus::Preempted;
                    pt.preempt_count += 1;
                    pt.allocated_devices.clear();
                    
                    for dev_id in pt.allocated_devices.iter() {
                        if let Some(mut dev) = self.devices.get_mut(dev_id) {
                            dev.used_memory_mb = 0;
                        }
                    }
                }
                
                self.pending_queue.write().push(pt_id.clone(), TaskPriority::Low);
                
                let mut stats = self.stats.write();
                stats.preempted_tasks += 1;
                stats.running_tasks -= 1;
                stats.pending_tasks += 1;
                
                warn!(preempted_task = %pt_id, for_task = %task_id, "Task preempted");
            }

            return AllocationResult {
                success: true,
                task_id: task_id.to_string(),
                allocated_devices: allocated.clone(),
                preempted_tasks: preempted,
                reason: None,
            };
        }

        AllocationResult {
            success: false,
            task_id: task_id.to_string(),
            allocated_devices: Vec::new(),
            preempted_tasks: Vec::new(),
            reason: Some("Could not preempt enough resources".to_string()),
        }
    }

    fn complete_allocation(
        &self,
        task_id: &str,
        allocated: Vec<GpuDevice>,
        preempted: Vec<String>,
    ) -> Result<AllocationResult> {
        let mut task = self.tasks.get_mut(task_id).ok_or_else(|| {
            ModelGuardError::NotFound(format!("Task {} not found", task_id))
        })?;

        let memory_per_gpu = task.resource_request.preferred_memory_mb;
        
        for dev in &allocated {
            if let Some(mut device) = self.devices.get_mut(&dev.device_id) {
                device.used_memory_mb += memory_per_gpu;
            }
        }

        task.status = TaskStatus::Scheduled;
        task.scheduled_at = Some(chrono::Utc::now());
        task.allocated_devices = allocated.iter().map(|d| d.device_id.clone()).collect();

        let mut stats = self.stats.write();
        stats.pending_tasks -= 1;
        stats.running_tasks += 1;
        stats.used_memory_mb += memory_per_gpu * allocated.len() as u64;
        stats.available_gpus -= allocated.len();

        info!(
            task_id = %task_id,
            devices = ?allocated.iter().map(|d| d.device_id.clone()).collect::<Vec<_>>(),
            "GPU resources allocated"
        );

        Ok(AllocationResult {
            success: true,
            task_id: task_id.to_string(),
            allocated_devices: allocated,
            preempted_tasks: preempted,
            reason: None,
        })
    }

    pub fn complete_task(&self, task_id: &str, success: bool) -> Result<()> {
        let mut task = self.tasks.get_mut(task_id).ok_or_else(|| {
            ModelGuardError::NotFound(format!("Task {} not found", task_id))
        })?;

        if !task.is_running() && task.status != TaskStatus::Scheduled {
            return Err(ModelGuardError::ValidationError(
                format!("Task {} is not running", task_id)
            ));
        }

        let memory_per_gpu = task.resource_request.preferred_memory_mb;
        let device_count = task.allocated_devices.len() as u64;

        for dev_id in &task.allocated_devices {
            if let Some(mut dev) = self.devices.get_mut(dev_id) {
                dev.used_memory_mb = dev.used_memory_mb.saturating_sub(memory_per_gpu);
            }
        }

        task.status = if success {
            TaskStatus::Completed
        } else {
            TaskStatus::Failed
        };
        task.completed_at = Some(chrono::Utc::now());

        let mut stats = self.stats.write();
        stats.running_tasks -= 1;
        stats.completed_tasks += 1;
        stats.used_memory_mb = stats.used_memory_mb.saturating_sub(memory_per_gpu * device_count);
        stats.available_gpus += task.allocated_devices.len();

        info!(task_id = %task_id, success = success, "GPU task completed");
        Ok(())
    }

    pub fn start_task(&self, task_id: &str) -> Result<()> {
        let mut task = self.tasks.get_mut(task_id).ok_or_else(|| {
            ModelGuardError::NotFound(format!("Task {} not found", task_id))
        })?;

        if task.status != TaskStatus::Scheduled {
            return Err(ModelGuardError::ValidationError(
                format!("Task {} is not scheduled", task_id)
            ));
        }

        task.status = TaskStatus::Running;
        task.started_at = Some(chrono::Utc::now());
        
        debug!(task_id = %task_id, "GPU task started");
        Ok(())
    }

    pub fn cancel_task(&self, task_id: &str) -> Result<()> {
        let mut task = self.tasks.get_mut(task_id).ok_or_else(|| {
            ModelGuardError::NotFound(format!("Task {} not found", task_id))
        })?;

        if matches!(task.status, TaskStatus::Completed | TaskStatus::Failed | TaskStatus::Cancelled) {
            return Err(ModelGuardError::ValidationError(
                format!("Task {} already finished", task_id)
            ));
        }

        if task.is_running() {
            let memory_per_gpu = task.resource_request.preferred_memory_mb;
            let device_count = task.allocated_devices.len() as u64;

            for dev_id in &task.allocated_devices {
                if let Some(mut dev) = self.devices.get_mut(dev_id) {
                    dev.used_memory_mb = dev.used_memory_mb.saturating_sub(memory_per_gpu);
                }
            }

            let mut stats = self.stats.write();
            stats.running_tasks -= 1;
            stats.used_memory_mb = stats.used_memory_mb.saturating_sub(memory_per_gpu * device_count);
            stats.available_gpus += task.allocated_devices.len();
        } else {
            let queue = self.pending_queue.read();
            let mut pending = self.stats.write();
            if queue.get(task_id).is_some() {
                pending.pending_tasks -= 1;
            }
        }

        task.status = TaskStatus::Cancelled;
        task.allocated_devices.clear();

        info!(task_id = %task_id, "GPU task cancelled");
        Ok(())
    }

    fn process_scheduling_queue(&self) {
    }

    pub fn get_task(&self, task_id: &str) -> Result<GpuTask> {
        self.tasks.get(task_id)
            .map(|t| t.clone())
            .ok_or_else(|| ModelGuardError::NotFound(format!("Task {} not found", task_id)))
    }

    pub fn get_stats(&self) -> SchedulerStats {
        self.stats.read().clone()
    }

    pub fn list_tasks(&self, status: Option<TaskStatus>) -> Vec<GpuTask> {
        self.tasks
            .iter()
            .filter(|t| status.map_or(true, |s| t.status == s))
            .map(|t| t.clone())
            .collect()
    }

    pub fn list_devices(&self) -> Vec<GpuDevice> {
        self.devices
            .iter()
            .map(|d| d.clone())
            .collect()
    }

    pub fn set_preemption_strategy(&mut self, strategy: PreemptionStrategy) {
        let old_enabled = self.preemption_strategy.enabled;
        self.preemption_strategy = strategy;
        info!(
            old_enabled,
            new_enabled = self.preemption_strategy.enabled,
            "Preemption strategy updated"
        );
    }
}

impl Default for GpuSchedulerService {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_device_registration() {
        let scheduler = GpuSchedulerService::new();
        let device = GpuDevice::new(0, 8192, "node-1");
        
        scheduler.register_device(device);
        
        let stats = scheduler.get_stats();
        assert_eq!(stats.total_gpus, 1);
        assert_eq!(stats.available_gpus, 1);
        assert_eq!(stats.total_memory_mb, 8192);
    }

    #[tokio::test]
    async fn test_task_submission_and_allocation() {
        let scheduler = GpuSchedulerService::new();
        scheduler.register_device(GpuDevice::new(0, 8192, "node-1"));
        
        let request = GpuResourceRequest::default();
        let task = GpuTask::new("test-task", TaskPriority::High, request);
        let task_id = scheduler.submit_task(task).unwrap();
        
        let results = scheduler.schedule().unwrap();
        assert_eq!(results.len(), 1);
        assert!(results[0].success);
        assert_eq!(results[0].task_id, task_id);
        
        let stats = scheduler.get_stats();
        assert_eq!(stats.running_tasks, 1);
        assert_eq!(stats.pending_tasks, 0);
    }

    #[tokio::test]
    async fn test_task_completion() {
        let scheduler = GpuSchedulerService::new();
        scheduler.register_device(GpuDevice::new(0, 8192, "node-1"));
        
        let request = GpuResourceRequest::default();
        let task = GpuTask::new("test-task", TaskPriority::High, request);
        let task_id = scheduler.submit_task(task).unwrap();
        
        scheduler.schedule().unwrap();
        scheduler.start_task(&task_id).unwrap();
        scheduler.complete_task(&task_id, true).unwrap();
        
        let task = scheduler.get_task(&task_id).unwrap();
        assert_eq!(task.status, TaskStatus::Completed);
        
        let stats = scheduler.get_stats();
        assert_eq!(stats.completed_tasks, 1);
        assert_eq!(stats.running_tasks, 0);
        assert_eq!(stats.available_gpus, 1);
    }

    #[tokio::test]
    async fn test_preemption() {
        let scheduler = GpuSchedulerService::new();
        scheduler.register_device(GpuDevice::new(0, 8192, "node-1"));
        
        let low_request = GpuResourceRequest {
            min_memory_mb: 4096,
            preferred_memory_mb: 4096,
            gpu_count: 1,
            required_labels: HashMap::new(),
            allow_preemption: true,
        };
        let low_task = GpuTask::new("low-priority", TaskPriority::Low, low_request);
        let low_id = scheduler.submit_task(low_task).unwrap();
        scheduler.schedule().unwrap();
        scheduler.start_task(&low_id).unwrap();
        
        let high_request = GpuResourceRequest {
            min_memory_mb: 2048,
            preferred_memory_mb: 2048,
            gpu_count: 1,
            required_labels: HashMap::new(),
            allow_preemption: false,
        };
        let high_task = GpuTask::new("high-priority", TaskPriority::Critical, high_request);
        let high_id = scheduler.submit_task(high_task).unwrap();
        
        let results = scheduler.schedule().unwrap();
        assert_eq!(results.len(), 1);
        assert!(results[0].success);
        assert!(!results[0].preempted_tasks.is_empty());
        
        let low_task = scheduler.get_task(&low_id).unwrap();
        assert_eq!(low_task.status, TaskStatus::Preempted);
        
        let stats = scheduler.get_stats();
        assert_eq!(stats.preempted_tasks, 1);
    }

    #[tokio::test]
    async fn test_insufficient_resources() {
        let scheduler = GpuSchedulerService::new();
        
        let request = GpuResourceRequest::default();
        let task = GpuTask::new("test-task", TaskPriority::High, request);
        let task_id = scheduler.submit_task(task).unwrap();
        
        let results = scheduler.schedule().unwrap();
        assert!(results.is_empty());
        
        let stats = scheduler.get_stats();
        assert_eq!(stats.pending_tasks, 1);
    }

    #[tokio::test]
    async fn test_task_cancellation() {
        let scheduler = GpuSchedulerService::new();
        scheduler.register_device(GpuDevice::new(0, 8192, "node-1"));
        
        let request = GpuResourceRequest::default();
        let task = GpuTask::new("test-task", TaskPriority::High, request);
        let task_id = scheduler.submit_task(task).unwrap();
        
        scheduler.schedule().unwrap();
        scheduler.cancel_task(&task_id).unwrap();
        
        let task = scheduler.get_task(&task_id).unwrap();
        assert_eq!(task.status, TaskStatus::Cancelled);
        
        let stats = scheduler.get_stats();
        assert_eq!(stats.available_gpus, 1);
    }

    #[tokio::test]
    async fn test_invalid_gpu_count() {
        let scheduler = GpuSchedulerService::new();
        
        let request = GpuResourceRequest {
            gpu_count: 0,
            ..Default::default()
        };
        let task = GpuTask::new("test-task", TaskPriority::High, request);
        
        let result = scheduler.submit_task(task);
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_list_tasks_by_status() {
        let scheduler = GpuSchedulerService::new();
        scheduler.register_device(GpuDevice::new(0, 8192, "node-1"));
        scheduler.register_device(GpuDevice::new(1, 8192, "node-1"));
        
        let task1 = GpuTask::new("task1", TaskPriority::High, GpuResourceRequest::default());
        let task2 = GpuTask::new("task2", TaskPriority::Low, GpuResourceRequest::default());
        
        scheduler.submit_task(task1).unwrap();
        scheduler.submit_task(task2).unwrap();
        
        scheduler.schedule().unwrap();
        
        let pending = scheduler.list_tasks(Some(TaskStatus::Pending));
        assert_eq!(pending.len(), 1);
        
        let scheduled = scheduler.list_tasks(Some(TaskStatus::Scheduled));
        assert_eq!(scheduled.len(), 1);
    }
}
