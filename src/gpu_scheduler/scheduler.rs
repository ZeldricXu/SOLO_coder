use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use parking_lot::Mutex;
use chrono::{DateTime, Utc};
use crate::utils::error::AppError;
use crate::utils::metrics::MetricsCollector;
use crate::utils::id::generate_id;
use crate::gpu_scheduler::resource::{GpuDevice, GpuResourceSpec, GpuAllocation, GpuStatus};
use crate::gpu_scheduler::task::{GpuTask, GpuTaskSpec, TaskPriority, TaskStatus};
use crate::gpu_scheduler::queue::{PriorityQueue, QueueConfig, QueueStats};
use crate::gpu_scheduler::preemption::{PreemptionManager, PreemptionPolicy, PreemptionStats};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SchedulingStrategy {
    FirstFit,
    BestFit,
    WorstFit,
    GangScheduling,
    BinPacking,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchedulerConfig {
    pub scheduling_strategy: SchedulingStrategy,
    pub queue_config: QueueConfig,
    pub preemption_policy: PreemptionPolicy,
    pub heartbeat_timeout_secs: i64,
    pub max_concurrent_tasks: usize,
    pub auto_preempt: bool,
}

impl Default for SchedulerConfig {
    fn default() -> Self {
        Self {
            scheduling_strategy: SchedulingStrategy::BestFit,
            queue_config: QueueConfig::default(),
            preemption_policy: PreemptionPolicy::default(),
            heartbeat_timeout_secs: 60,
            max_concurrent_tasks: 100,
            auto_preempt: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchedulerStats {
    pub total_devices: usize,
    pub available_devices: usize,
    pub total_memory_gb: f64,
    pub available_memory_gb: f64,
    pub running_tasks: usize,
    pub queued_tasks: usize,
    pub queue_stats: QueueStats,
    pub preemption_stats: PreemptionStats,
    pub total_tasks_submitted: u64,
    pub total_tasks_completed: u64,
    pub total_tasks_failed: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchedulingResult {
    pub task_id: String,
    pub device_id: Option<String>,
    pub allocation_id: Option<String>,
    pub scheduled: bool,
    pub reason: Option<String>,
    pub timestamp: DateTime<Utc>,
}

pub struct GpuScheduler {
    config: Mutex<SchedulerConfig>,
    devices: Arc<Mutex<HashMap<String, GpuDevice>>>,
    device_resources: Arc<Mutex<HashMap<String, GpuResourceSpec>>>,
    allocations: Arc<Mutex<HashMap<String, GpuAllocation>>>,
    tasks: Arc<Mutex<HashMap<String, GpuTask>>>,
    running_tasks: Arc<Mutex<HashSet<String>>>,
    queue: Arc<PriorityQueue>,
    preemption_manager: Mutex<PreemptionManager>,
    metrics: Arc<MetricsCollector>,
    stats: Arc<Mutex<SchedulerStatsInternal>>,
}

#[derive(Debug, Default)]
struct SchedulerStatsInternal {
    total_tasks_submitted: u64,
    total_tasks_completed: u64,
    total_tasks_failed: u64,
}

impl GpuScheduler {
    pub fn new(config: SchedulerConfig, metrics: Arc<MetricsCollector>) -> Self {
        let preemption_manager = PreemptionManager::new(config.preemption_policy.clone());
        
        Self {
            config: Mutex::new(config),
            devices: Arc::new(Mutex::new(HashMap::new())),
            device_resources: Arc::new(Mutex::new(HashMap::new())),
            allocations: Arc::new(Mutex::new(HashMap::new())),
            tasks: Arc::new(Mutex::new(HashMap::new())),
            running_tasks: Arc::new(Mutex::new(HashSet::new())),
            queue: Arc::new(PriorityQueue::new(config.queue_config.clone())),
            preemption_manager: Mutex::new(preemption_manager),
            metrics,
            stats: Arc::new(Mutex::new(SchedulerStatsInternal::default())),
        }
    }

    pub fn register_device(&self, device: GpuDevice) -> Result<GpuDevice, AppError> {
        let mut devices = self.devices.lock();
        let mut resources = self.device_resources.lock();
        
        if devices.contains_key(&device.device_id) {
            return Err(AppError::Validation(format!(
                "Device {} already registered",
                device.device_id
            )));
        }

        resources.insert(device.device_id.clone(), device.spec.clone());
        devices.insert(device.device_id.clone(), device.clone());
        
        self.metrics.increment_counter("gpu_device_registered");
        Ok(device)
    }

    pub fn unregister_device(&self, device_id: &str) -> Result<(), AppError> {
        let mut devices = self.devices.lock();
        let mut resources = self.device_resources.lock();
        
        if !devices.contains_key(device_id) {
            return Err(AppError::NotFound(format!("Device {} not found", device_id)));
        }

        let allocations = self.allocations.lock();
        if allocations.values().any(|a| a.device_id == device_id) {
            return Err(AppError::Validation(
                "Cannot unregister device with active allocations".to_string()
            ));
        }

        devices.remove(device_id);
        resources.remove(device_id);
        
        self.metrics.increment_counter("gpu_device_unregistered");
        Ok(())
    }

    pub fn get_device(&self, device_id: &str) -> Result<GpuDevice, AppError> {
        self.devices.lock()
            .get(device_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Device {} not found", device_id)))
    }

    pub fn list_devices(&self) -> Vec<GpuDevice> {
        self.devices.lock().values().cloned().collect()
    }

    pub fn update_device_heartbeat(&self, device_id: &str) -> Result<(), AppError> {
        let mut devices = self.devices.lock();
        let device = devices.get_mut(device_id)
            .ok_or_else(|| AppError::NotFound(format!("Device {} not found", device_id)))?;
        
        device.update_heartbeat();
        Ok(())
    }

    pub fn submit_task(&self, spec: GpuTaskSpec, max_retries: u32) -> Result<GpuTask, AppError> {
        let task = GpuTask::new(spec, max_retries)?;
        
        self.tasks.lock().insert(task.task_id.clone(), task.clone());
        
        let enqueued = self.queue.enqueue(task.clone())?;
        
        self.stats.lock().total_tasks_submitted += 1;
        self.metrics.increment_counter("gpu_task_submitted");
        
        Ok(enqueued)
    }

    pub fn schedule_next(&self) -> Result<Option<SchedulingResult>, AppError> {
        let config = self.config.lock();
        
        if self.running_tasks.lock().len() >= config.max_concurrent_tasks {
            return Ok(Some(SchedulingResult {
                task_id: String::new(),
                device_id: None,
                allocation_id: None,
                scheduled: false,
                reason: Some("Max concurrent tasks reached".to_string()),
                timestamp: Utc::now(),
            }));
        }

        let task = match self.queue.dequeue() {
            Some(t) => t,
            None => return Ok(None),
        };

        let result = self.try_schedule_task(task, &config)?;
        Ok(Some(result))
    }

    fn try_schedule_task(&self, mut task: GpuTask, config: &SchedulerConfig) -> Result<SchedulingResult, AppError> {
        let required = &task.spec.resource_requirements;
        
        let (device_id, allocation) = match self.find_available_device(required, config.scheduling_strategy) {
            Some((id, alloc)) => (id, alloc),
            None => {
                if config.auto_preempt {
                    match self.try_preempt_for_task(required, task.spec.priority) {
                        Some(preempt_result) => {
                            self.metrics.increment_counter("gpu_task_preempted");
                            preempt_result
                        }
                        None => {
                            self.queue.enqueue(task)?;
                            return Ok(SchedulingResult {
                                task_id: task.task_id,
                                device_id: None,
                                allocation_id: None,
                                scheduled: false,
                                reason: Some("No available resources, queued for retry".to_string()),
                                timestamp: Utc::now(),
                            });
                        }
                    }
                } else {
                    self.queue.enqueue(task)?;
                    return Ok(SchedulingResult {
                        task_id: task.task_id,
                        device_id: None,
                        allocation_id: None,
                        scheduled: false,
                        reason: Some("No GPU resources available".to_string()),
                        timestamp: Utc::now(),
                    });
                }
            }
        };

        task.schedule(device_id.clone(), allocation.allocation_id.clone())?;
        task.start()?;

        self.allocations.lock().insert(allocation.allocation_id.clone(), allocation.clone());
        self.tasks.lock().insert(task.task_id.clone(), task.clone());
        self.running_tasks.lock().insert(task.task_id.clone());

        self.metrics.increment_counter("gpu_task_scheduled");
        
        Ok(SchedulingResult {
            task_id: task.task_id,
            device_id: Some(device_id),
            allocation_id: Some(allocation.allocation_id),
            scheduled: true,
            reason: None,
            timestamp: Utc::now(),
        })
    }

    fn find_available_device(
        &self,
        required: &GpuResourceSpec,
        strategy: SchedulingStrategy,
    ) -> Option<(String, GpuAllocation)> {
        let devices = self.devices.lock();
        let resources = self.device_resources.lock();
        let allocations = self.allocations.lock();
        let config = self.config.lock();

        let mut available: Vec<(String, GpuResourceSpec, f64)> = Vec::new();

        for (device_id, device) in devices.iter() {
            if !device.is_healthy(config.heartbeat_timeout_secs) {
                continue;
            }
            if device.status != GpuStatus::Available {
                continue;
            }

            let total = resources.get(device_id).unwrap();
            let used_memory: f64 = allocations.values()
                .filter(|a| a.device_id == *device_id && a.end_time.is_none())
                .map(|a| a.memory_gb)
                .sum();
            let used_cores: u32 = allocations.values()
                .filter(|a| a.device_id == *device_id && a.end_time.is_none())
                .map(|a| a.cores)
                .sum();

            let available_memory = total.gpu_memory_gb - used_memory;
            let available_cores = total.gpu_cores - used_cores;
            let available_cu = total.compute_units - (used_cores * total.compute_units / total.gpu_cores);

            let available_spec = GpuResourceSpec {
                gpu_memory_gb: available_memory,
                gpu_cores: available_cores,
                compute_units: available_cu,
                memory_bandwidth_gbps: 0.0,
                tensor_cores: None,
                rt_cores: None,
            };

            if available_spec.can_allocate(required) {
                let score = match strategy {
                    SchedulingStrategy::BestFit => {
                        total.get_resource_score(required)
                    }
                    SchedulingStrategy::WorstFit => {
                        -total.get_resource_score(required)
                    }
                    SchedulingStrategy::FirstFit |
                    SchedulingStrategy::GangScheduling |
                    SchedulingStrategy::BinPacking => {
                        available_memory
                    }
                };
                available.push((device_id.clone(), available_spec, score));
            }
        }

        if available.is_empty() {
            return None;
        }

        match strategy {
            SchedulingStrategy::BestFit | SchedulingStrategy::WorstFit => {
                available.sort_by(|a, b| b.2.partial_cmp(&a.2).unwrap_or(std::cmp::Ordering::Equal));
            }
            _ => {}
        }

        let (device_id, available_spec, _) = available.first()?;

        let allocation = GpuAllocation::new(
            "pending".to_string(),
            device_id.clone(),
            required.gpu_memory_gb,
            required.gpu_cores,
            false,
            5,
        );

        Some((device_id.clone(), allocation))
    }

    fn try_preempt_for_task(
        &self,
        required: &GpuResourceSpec,
        priority: TaskPriority,
    ) -> Option<(String, GpuAllocation)> {
        let running_tasks: Vec<GpuTask> = self.running_tasks.lock()
            .iter()
            .filter_map(|id| self.tasks.lock().get(id).cloned())
            .collect();

        let allocations = self.allocations.lock();
        let alloc_map: HashMap<String, GpuAllocation> = allocations.values()
            .filter(|a| a.end_time.is_none())
            .map(|a| (a.task_id.clone(), a.clone()))
            .collect();

        let preemption_result = self.preemption_manager.lock().find_preemption_candidates(
            &running_tasks,
            &alloc_map,
            required.gpu_memory_gb,
            priority,
        );

        if !preemption_result.success {
            return None;
        }

        let mut device_to_free: Option<String> = None;
        let mut total_freed = 0.0;

        for task_id in &preemption_result.preempted_tasks {
            if let Some(mut task) = self.tasks.lock().get_mut(task_id) {
                if let Ok(()) = task.preempt() {
                    self.running_tasks.lock().remove(task_id);
                    
                    if let Some(alloc) = allocations.get(task_id) {
                        if device_to_free.is_none() {
                            device_to_free = Some(alloc.device_id.clone());
                        }
                        total_freed += alloc.memory_gb;
                    }
                    
                    if let Ok(()) = self.queue.enqueue(task.clone()) {
                        self.metrics.increment_counter("gpu_task_preempted_queued");
                    }
                }
            }
        }

        device_to_free.map(|device_id| {
            let allocation = GpuAllocation::new(
                "pending".to_string(),
                device_id.clone(),
                required.gpu_memory_gb,
                required.gpu_cores,
                false,
                5,
            );
            (device_id, allocation)
        })
    }

    pub fn complete_task(&self, task_id: &str) -> Result<GpuTask, AppError> {
        let mut tasks = self.tasks.lock();
        let mut task = tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("Task {} not found", task_id)))?;
        
        task.complete()?;
        
        if let Some(alloc_id) = &task.allocation_id {
            if let Some(mut alloc) = self.allocations.lock().get_mut(alloc_id) {
                alloc.complete();
            }
        }
        
        self.running_tasks.lock().remove(task_id);
        self.stats.lock().total_tasks_completed += 1;
        self.metrics.increment_counter("gpu_task_completed");
        
        Ok(task.clone())
    }

    pub fn fail_task(&self, task_id: &str, error: String) -> Result<GpuTask, AppError> {
        let mut tasks = self.tasks.lock();
        let mut task = tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("Task {} not found", task_id)))?;
        
        let was_running = matches!(task.status, TaskStatus::Running);
        
        task.fail(error)?;
        
        if was_running {
            if let Some(alloc_id) = &task.allocation_id {
                if let Some(mut alloc) = self.allocations.lock().get_mut(alloc_id) {
                    alloc.complete();
                }
            }
            self.running_tasks.lock().remove(task_id);
        }

        if matches!(task.status, TaskStatus::Failed) {
            self.stats.lock().total_tasks_failed += 1;
            self.metrics.increment_counter("gpu_task_failed");
        } else {
            self.queue.enqueue(task.clone())?;
            self.metrics.increment_counter("gpu_task_requeued");
        }
        
        Ok(task.clone())
    }

    pub fn cancel_task(&self, task_id: &str) -> Result<GpuTask, AppError> {
        if self.queue.contains(task_id) {
            self.queue.remove(task_id);
        }

        let mut tasks = self.tasks.lock();
        let mut task = tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("Task {} not found", task_id)))?;
        
        let was_running = matches!(task.status, TaskStatus::Running);
        
        task.cancel()?;
        
        if was_running {
            if let Some(alloc_id) = &task.allocation_id {
                if let Some(mut alloc) = self.allocations.lock().get_mut(alloc_id) {
                    alloc.complete();
                }
            }
            self.running_tasks.lock().remove(task_id);
        }
        
        self.metrics.increment_counter("gpu_task_cancelled");
        Ok(task.clone())
    }

    pub fn get_task(&self, task_id: &str) -> Result<GpuTask, AppError> {
        self.tasks.lock()
            .get(task_id)
            .cloned()
            .ok_or_else(|| AppError::NotFound(format!("Task {} not found", task_id)))
    }

    pub fn list_tasks(&self, status: Option<TaskStatus>) -> Vec<GpuTask> {
        let tasks = self.tasks.lock();
        match status {
            Some(s) => tasks.values().filter(|t| t.status == s).cloned().collect(),
            None => tasks.values().cloned().collect(),
        }
    }

    pub fn update_task_progress(&self, task_id: &str, progress: f64) -> Result<(), AppError> {
        let mut tasks = self.tasks.lock();
        let task = tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("Task {} not found", task_id)))?;
        
        task.update_progress(progress)
    }

    pub fn update_queue_priorities(&self) -> usize {
        let boosted = self.queue.update_priorities();
        if boosted > 0 {
            self.metrics.increment_counter_with_value("gpu_queue_priority_boost", boosted as u64);
        }
        boosted
    }

    pub fn check_device_health(&self) -> usize {
        let mut unhealthy = 0;
        let config = self.config.lock();
        let mut devices = self.devices.lock();
        
        for device in devices.values_mut() {
            if !device.is_healthy(config.heartbeat_timeout_secs) {
                if device.status != GpuStatus::Unhealthy {
                    device.set_status(GpuStatus::Unhealthy);
                    unhealthy += 1;
                }
            }
        }
        
        if unhealthy > 0 {
            self.metrics.increment_counter_with_value("gpu_device_unhealthy", unhealthy as u64);
        }
        
        unhealthy
    }

    pub fn get_stats(&self) -> SchedulerStats {
        let devices = self.devices.lock();
        let resources = self.device_resources.lock();
        let allocations = self.allocations.lock();
        let running = self.running_tasks.lock();
        let stats = self.stats.lock();
        let preemption_stats = self.preemption_manager.lock().get_stats();
        let queue_stats = self.queue.get_stats();

        let total_memory_gb: f64 = resources.values().map(|r| r.gpu_memory_gb).sum();
        
        let used_memory_gb: f64 = allocations.values()
            .filter(|a| a.end_time.is_none())
            .map(|a| a.memory_gb)
            .sum();
        
        let available_memory_gb = total_memory_gb - used_memory_gb;
        
        let available_devices = devices.values()
            .filter(|d| d.status == GpuStatus::Available)
            .count();

        SchedulerStats {
            total_devices: devices.len(),
            available_devices,
            total_memory_gb,
            available_memory_gb,
            running_tasks: running.len(),
            queued_tasks: self.queue.size(),
            queue_stats,
            preemption_stats,
            total_tasks_submitted: stats.total_tasks_submitted,
            total_tasks_completed: stats.total_tasks_completed,
            total_tasks_failed: stats.total_tasks_failed,
        }
    }

    pub fn get_running_tasks(&self) -> Vec<GpuTask> {
        let running = self.running_tasks.lock();
        let tasks = self.tasks.lock();
        
        running.iter()
            .filter_map(|id| tasks.get(id).cloned())
            .collect()
    }

    pub fn update_config(&self, config: SchedulerConfig) {
        *self.config.lock() = config.clone();
        self.preemption_manager.lock().update_policy(config.preemption_policy);
    }

    pub fn get_config(&self) -> SchedulerConfig {
        self.config.lock().clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::utils::metrics::MetricsCollector;
    use crate::gpu_scheduler::resource::{GpuDevice, GpuResourceSpec, GpuVendor, GpuStatus};
    use crate::gpu_scheduler::task::{GpuTaskSpec, TaskType, TaskPriority, TaskStatus};
    use std::collections::HashMap;

    fn create_test_scheduler() -> GpuScheduler {
        let metrics = Arc::new(MetricsCollector::new());
        GpuScheduler::new(SchedulerConfig::default(), metrics)
    }

    fn create_test_device(scheduler: &GpuScheduler, memory_gb: f64) -> GpuDevice {
        let spec = GpuResourceSpec {
            gpu_memory_gb: memory_gb,
            gpu_cores: 3584,
            compute_units: 56,
            memory_bandwidth_gbps: 448.0,
            tensor_cores: Some(224),
            rt_cores: Some(56),
        };

        let device = GpuDevice::new(
            "node-1".to_string(),
            GpuVendor::Nvidia,
            "RTX 3080".to_string(),
            format!("GPU-{}", generate_id("test")),
            0,
            spec,
            HashMap::new(),
        ).unwrap();

        scheduler.register_device(device).unwrap()
    }

    fn create_test_task_spec(priority: TaskPriority, memory_gb: f64, preemptible: bool) -> GpuTaskSpec {
        GpuTaskSpec {
            name: "Test Task".to_string(),
            description: "A test GPU task".to_string(),
            task_type: TaskType::Training,
            priority,
            resource_requirements: GpuResourceSpec {
                gpu_memory_gb: memory_gb,
                gpu_cores: 1024,
                compute_units: 28,
                memory_bandwidth_gbps: 0.0,
                tensor_cores: None,
                rt_cores: None,
            },
            is_preemptible: preemptible,
            preemption_priority: 5,
            estimated_duration_secs: Some(3600),
            max_duration_secs: None,
            node_affinity: vec![],
            node_anti_affinity: vec![],
            labels: HashMap::new(),
            metadata: HashMap::new(),
            created_by: "test_user".to_string(),
        }
    }

    #[test]
    fn test_device_registration() {
        let scheduler = create_test_scheduler();
        let device = create_test_device(&scheduler, 16.0);
        
        assert!(device.device_id.starts_with("gpu_"));
        assert_eq!(scheduler.list_devices().len(), 1);
    }

    #[test]
    fn test_task_submission_and_scheduling() {
        let scheduler = create_test_scheduler();
        create_test_device(&scheduler, 16.0);
        
        let spec = create_test_task_spec(TaskPriority::Medium, 8.0, true);
        let task = scheduler.submit_task(spec, 3).unwrap();
        
        assert_eq!(task.status, TaskStatus::Queued);
        assert_eq!(scheduler.get_stats().queued_tasks, 1);
        
        let result = scheduler.schedule_next().unwrap().unwrap();
        assert!(result.scheduled);
        assert!(result.device_id.is_some());
        assert_eq!(scheduler.get_stats().running_tasks, 1);
    }

    #[test]
    fn test_task_completion() {
        let scheduler = create_test_scheduler();
        create_test_device(&scheduler, 16.0);
        
        let spec = create_test_task_spec(TaskPriority::Medium, 8.0, true);
        let task = scheduler.submit_task(spec, 3).unwrap();
        
        scheduler.schedule_next().unwrap().unwrap();
        
        let completed = scheduler.complete_task(&task.task_id).unwrap();
        assert_eq!(completed.status, TaskStatus::Completed);
        assert_eq!(scheduler.get_stats().running_tasks, 0);
        assert_eq!(scheduler.get_stats().total_tasks_completed, 1);
    }

    #[test]
    fn test_preemption() {
        let scheduler = create_test_scheduler();
        create_test_device(&scheduler, 12.0);
        
        let spec1 = create_test_task_spec(TaskPriority::Low, 8.0, true);
        scheduler.submit_task(spec1, 3).unwrap();
        scheduler.schedule_next().unwrap().unwrap();
        
        let spec2 = create_test_task_spec(TaskPriority::High, 8.0, false);
        scheduler.submit_task(spec2, 3).unwrap();
        
        std::thread::sleep(std::time::Duration::from_secs(2));
        
        let result = scheduler.schedule_next().unwrap().unwrap();
        assert!(result.scheduled);
        
        let stats = scheduler.get_stats();
        assert_eq!(stats.running_tasks, 1);
    }

    #[test]
    fn test_task_cancellation() {
        let scheduler = create_test_scheduler();
        create_test_device(&scheduler, 16.0);
        
        let spec = create_test_task_spec(TaskPriority::Medium, 8.0, true);
        let task = scheduler.submit_task(spec, 3).unwrap();
        
        let cancelled = scheduler.cancel_task(&task.task_id).unwrap();
        assert_eq!(cancelled.status, TaskStatus::Cancelled);
        assert_eq!(scheduler.get_stats().queued_tasks, 0);
    }

    #[test]
    fn test_task_progress_update() {
        let scheduler = create_test_scheduler();
        create_test_device(&scheduler, 16.0);
        
        let spec = create_test_task_spec(TaskPriority::Medium, 8.0, true);
        let task = scheduler.submit_task(spec, 3).unwrap();
        
        scheduler.schedule_next().unwrap().unwrap();
        
        scheduler.update_task_progress(&task.task_id, 0.5).unwrap();
        
        let updated = scheduler.get_task(&task.task_id).unwrap();
        assert_eq!(updated.progress, 0.5);
    }

    #[test]
    fn test_scheduler_stats() {
        let scheduler = create_test_scheduler();
        create_test_device(&scheduler, 16.0);
        create_test_device(&scheduler, 16.0);
        
        let stats = scheduler.get_stats();
        assert_eq!(stats.total_devices, 2);
        assert_eq!(stats.total_memory_gb, 32.0);
    }

    #[test]
    fn test_device_health_check() {
        let scheduler = create_test_scheduler();
        let mut device = create_test_device(&scheduler, 16.0);
        
        let unhealthy = scheduler.check_device_health();
        assert_eq!(unhealthy, 0);
        
        device.last_heartbeat = Utc::now() - chrono::Duration::seconds(120);
        scheduler.devices.lock().insert(device.device_id.clone(), device);
        
        let unhealthy = scheduler.check_device_health();
        assert_eq!(unhealthy, 1);
        
        let updated = scheduler.get_device(&device.device_id).unwrap();
        assert_eq!(updated.status, GpuStatus::Unhealthy);
    }

    #[test]
    fn test_best_fit_scheduling() {
        let mut config = SchedulerConfig::default();
        config.scheduling_strategy = SchedulingStrategy::BestFit;
        let metrics = Arc::new(MetricsCollector::new());
        let scheduler = GpuScheduler::new(config, metrics);
        
        create_test_device(&scheduler, 8.0);
        create_test_device(&scheduler, 16.0);
        
        let spec = create_test_task_spec(TaskPriority::Medium, 6.0, true);
        scheduler.submit_task(spec, 3).unwrap();
        
        let result = scheduler.schedule_next().unwrap().unwrap();
        assert!(result.scheduled);
    }
}
