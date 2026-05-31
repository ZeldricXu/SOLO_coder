use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use parking_lot::Mutex;
use chrono::{DateTime, Utc};
use crate::utils::error::AppError;
use crate::gpu_scheduler::task::{GpuTask, TaskPriority};
use crate::gpu_scheduler::resource::GpuAllocation;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PreemptionStrategy {
    None,
    LowestPriorityFirst,
    ShortestRemainingTime,
    LowestPreemptionPriority,
    LongestRunning,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PreemptionPolicy {
    pub strategy: PreemptionStrategy,
    pub enabled: bool,
    pub min_running_time_secs: i64,
    pub max_preemptions_per_task: u32,
    pub check_interval_secs: u64,
}

impl Default for PreemptionPolicy {
    fn default() -> Self {
        Self {
            strategy: PreemptionStrategy::LowestPriorityFirst,
            enabled: true,
            min_running_time_secs: 60,
            max_preemptions_per_task: 5,
            check_interval_secs: 30,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PreemptionResult {
    pub success: bool,
    pub preempted_tasks: Vec<String>,
    pub reason: Option<String>,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone)]
pub struct PreemptionCandidate {
    task: GpuTask,
    allocation: GpuAllocation,
    score: f64,
}

pub struct PreemptionManager {
    policy: PreemptionPolicy,
    stats: Arc<Mutex<PreemptionStats>>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct PreemptionStats {
    pub total_preemptions: u64,
    pub total_preempted_tasks: u64,
    pub successful_preemptions: u64,
    pub failed_preemptions: u64,
    pub by_strategy: HashMap<PreemptionStrategy, u64>,
    pub last_preemption_time: Option<DateTime<Utc>>,
}

impl PreemptionManager {
    pub fn new(policy: PreemptionPolicy) -> Self {
        Self {
            policy,
            stats: Arc::new(Mutex::new(PreemptionStats::default())),
        }
    }

    pub fn update_policy(&mut self, policy: PreemptionPolicy) {
        self.policy = policy;
    }

    pub fn get_policy(&self) -> &PreemptionPolicy {
        &self.policy
    }

    pub fn find_preemption_candidates(
        &self,
        running_tasks: &[GpuTask],
        allocations: &HashMap<String, GpuAllocation>,
        required_memory_gb: f64,
        required_priority: TaskPriority,
    ) -> PreemptionResult {
        if !self.policy.enabled {
            return PreemptionResult {
                success: false,
                preempted_tasks: Vec::new(),
                reason: Some("Preemption is disabled".to_string()),
                timestamp: Utc::now(),
            };
        }

        let mut candidates: Vec<PreemptionCandidate> = running_tasks
            .iter()
            .filter(|task| {
                if !task.spec.is_preemptible {
                    return false;
                }
                
                if task.preemptions >= self.policy.max_preemptions_per_task {
                    return false;
                }
                
                if let Some(elapsed) = task.get_execution_time_secs() {
                    if elapsed < self.policy.min_running_time_secs {
                        return false;
                    }
                }
                
                task.spec.priority < required_priority
            })
            .filter_map(|task| {
                allocations.get(&task.task_id).map(|alloc| {
                    let score = self.calculate_preemption_score(task, alloc);
                    PreemptionCandidate {
                        task: task.clone(),
                        allocation: alloc.clone(),
                        score,
                    }
                })
            })
            .collect();

        match self.policy.strategy {
            PreemptionStrategy::LowestPriorityFirst => {
                candidates.sort_by(|a, b| {
                    a.task.spec.priority.cmp(&b.task.spec.priority)
                        .then_with(|| a.score.partial_cmp(&b.score).unwrap_or(std::cmp::Ordering::Equal))
                });
            }
            PreemptionStrategy::ShortestRemainingTime => {
                candidates.sort_by(|a, b| {
                    let a_remaining = a.task.spec.estimated_duration_secs
                        .map(|d| d as i64 - a.task.get_execution_time_secs().unwrap_or(0))
                        .unwrap_or(0);
                    let b_remaining = b.task.spec.estimated_duration_secs
                        .map(|d| d as i64 - b.task.get_execution_time_secs().unwrap_or(0))
                        .unwrap_or(0);
                    a_remaining.cmp(&b_remaining)
                });
            }
            PreemptionStrategy::LowestPreemptionPriority => {
                candidates.sort_by(|a, b| {
                    a.allocation.preemption_priority.cmp(&b.allocation.preemption_priority)
                });
            }
            PreemptionStrategy::LongestRunning => {
                candidates.sort_by(|a, b| {
                    let a_elapsed = a.task.get_execution_time_secs().unwrap_or(0);
                    let b_elapsed = b.task.get_execution_time_secs().unwrap_or(0);
                    b_elapsed.cmp(&a_elapsed)
                });
            }
            PreemptionStrategy::None => {
                return PreemptionResult {
                    success: false,
                    preempted_tasks: Vec::new(),
                    reason: Some("No preemption strategy configured".to_string()),
                    timestamp: Utc::now(),
                };
            }
        }

        let mut freed_memory = 0.0;
        let mut to_preempt = Vec::new();

        for candidate in &candidates {
            if freed_memory >= required_memory_gb {
                break;
            }
            freed_memory += candidate.allocation.memory_gb;
            to_preempt.push(candidate.task.task_id.clone());
        }

        let success = freed_memory >= required_memory_gb;
        let reason = if !success {
            Some(format!(
                "Insufficient preemptible memory. Required: {:.1}GB, Available: {:.1}GB",
                required_memory_gb, freed_memory
            ))
        } else {
            None
        };

        {
            let mut stats = self.stats.lock();
            stats.total_preemptions += 1;
            stats.total_preempted_tasks += to_preempt.len() as u64;
            if success {
                stats.successful_preemptions += 1;
            } else {
                stats.failed_preemptions += 1;
            }
            *stats.by_strategy.entry(self.policy.strategy).or_insert(0) += 1;
            stats.last_preemption_time = Some(Utc::now());
        }

        PreemptionResult {
            success,
            preempted_tasks: to_preempt,
            reason,
            timestamp: Utc::now(),
        }
    }

    fn calculate_preemption_score(&self, task: &GpuTask, allocation: &GpuAllocation) -> f64 {
        let priority_score = task.spec.priority as u8 as f64 / TaskPriority::Realtime as u8 as f64;
        
        let progress_penalty = task.progress * 0.5;
        
        let preemption_count_penalty = task.preemptions as f64 / self.policy.max_preemptions_per_task as f64 * 0.3;
        
        let memory_score = allocation.memory_gb / 64.0;

        priority_score * 0.4 + progress_penalty + preemption_count_penalty + memory_score * 0.2
    }

    pub fn can_preempt(&self, task: &GpuTask) -> bool {
        if !self.policy.enabled {
            return false;
        }
        
        if !task.spec.is_preemptible {
            return false;
        }
        
        if task.preemptions >= self.policy.max_preemptions_per_task {
            return false;
        }
        
        if let Some(elapsed) = task.get_execution_time_secs() {
            if elapsed < self.policy.min_running_time_secs {
                return false;
            }
        }
        
        true
    }

    pub fn get_stats(&self) -> PreemptionStats {
        self.stats.lock().clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gpu_scheduler::resource::{GpuResourceSpec, GpuAllocation};
    use crate::gpu_scheduler::task::{GpuTaskSpec, TaskType, TaskStatus};

    fn create_test_task(
        name: &str,
        priority: TaskPriority,
        preemptible: bool,
        preemption_priority: u8,
        memory_gb: f64,
    ) -> (GpuTask, GpuAllocation) {
        let spec = GpuTaskSpec {
            name: name.to_string(),
            description: "Test task".to_string(),
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
            preemption_priority,
            estimated_duration_secs: Some(3600),
            max_duration_secs: None,
            node_affinity: vec![],
            node_anti_affinity: vec![],
            labels: HashMap::new(),
            metadata: HashMap::new(),
            created_by: "test_user".to_string(),
        };
        
        let mut task = GpuTask::new(spec, 3).unwrap();
        task.enqueue().unwrap();
        task.schedule("gpu_1".to_string(), "alloc_1".to_string()).unwrap();
        task.start().unwrap();
        
        let allocation = GpuAllocation::new(
            task.task_id.clone(),
            "gpu_1".to_string(),
            memory_gb,
            1024,
            preemptible,
            preemption_priority,
        );
        
        (task, allocation)
    }

    #[test]
    fn test_preemption_disabled() {
        let mut policy = PreemptionPolicy::default();
        policy.enabled = false;
        let manager = PreemptionManager::new(policy);
        
        let (task, allocation) = create_test_task(
            "task1", TaskPriority::Low, true, 3, 8.0
        );
        
        let mut allocations = HashMap::new();
        allocations.insert(task.task_id.clone(), allocation);
        
        let result = manager.find_preemption_candidates(
            &[task],
            &allocations,
            16.0,
            TaskPriority::High,
        );
        
        assert!(!result.success);
        assert!(result.reason.is_some());
    }

    #[test]
    fn test_preemption_lowest_priority_first() {
        let policy = PreemptionPolicy {
            strategy: PreemptionStrategy::LowestPriorityFirst,
            ..Default::default()
        };
        let manager = PreemptionManager::new(policy);
        
        let (task1, alloc1) = create_test_task(
            "low_priority", TaskPriority::Low, true, 3, 8.0
        );
        let (task2, alloc2) = create_test_task(
            "medium_priority", TaskPriority::Medium, true, 3, 8.0
        );
        let (task3, alloc3) = create_test_task(
            "high_priority", TaskPriority::High, true, 3, 8.0
        );
        
        let mut allocations = HashMap::new();
        allocations.insert(task1.task_id.clone(), alloc1);
        allocations.insert(task2.task_id.clone(), alloc2);
        allocations.insert(task3.task_id.clone(), alloc3);
        
        let result = manager.find_preemption_candidates(
            &[task1.clone(), task2.clone(), task3.clone()],
            &allocations,
            12.0,
            TaskPriority::Critical,
        );
        
        assert!(result.success);
        assert_eq!(result.preempted_tasks.len(), 2);
        assert_eq!(result.preempted_tasks[0], task1.task_id);
        assert_eq!(result.preempted_tasks[1], task2.task_id);
    }

    #[test]
    fn test_preemption_non_preemptible() {
        let policy = PreemptionPolicy::default();
        let manager = PreemptionManager::new(policy);
        
        let (task1, alloc1) = create_test_task(
            "non_preemptible", TaskPriority::Low, false, 3, 8.0
        );
        
        let mut allocations = HashMap::new();
        allocations.insert(task1.task_id.clone(), alloc1);
        
        let result = manager.find_preemption_candidates(
            &[task1],
            &allocations,
            8.0,
            TaskPriority::High,
        );
        
        assert!(!result.success);
    }

    #[test]
    fn test_preemption_lowest_preemption_priority() {
        let policy = PreemptionPolicy {
            strategy: PreemptionStrategy::LowestPreemptionPriority,
            ..Default::default()
        };
        let manager = PreemptionManager::new(policy);
        
        let (task1, alloc1) = create_test_task(
            "task1", TaskPriority::Medium, true, 1, 8.0
        );
        let (task2, alloc2) = create_test_task(
            "task2", TaskPriority::Medium, true, 5, 8.0
        );
        
        let mut allocations = HashMap::new();
        allocations.insert(task1.task_id.clone(), alloc1);
        allocations.insert(task2.task_id.clone(), alloc2);
        
        let result = manager.find_preemption_candidates(
            &[task1.clone(), task2.clone()],
            &allocations,
            8.0,
            TaskPriority::High,
        );
        
        assert!(result.success);
        assert_eq!(result.preempted_tasks.len(), 1);
        assert_eq!(result.preempted_tasks[0], task1.task_id);
    }

    #[test]
    fn test_preemption_insufficient_memory() {
        let policy = PreemptionPolicy::default();
        let manager = PreemptionManager::new(policy);
        
        let (task1, alloc1) = create_test_task(
            "task1", TaskPriority::Low, true, 3, 4.0
        );
        
        let mut allocations = HashMap::new();
        allocations.insert(task1.task_id.clone(), alloc1);
        
        let result = manager.find_preemption_candidates(
            &[task1],
            &allocations,
            16.0,
            TaskPriority::High,
        );
        
        assert!(!result.success);
        assert!(result.reason.is_some());
    }

    #[test]
    fn test_can_preempt() {
        let policy = PreemptionPolicy::default();
        let manager = PreemptionManager::new(policy);
        
        let (mut task, _) = create_test_task(
            "task1", TaskPriority::Low, true, 3, 8.0
        );
        
        assert!(manager.can_preempt(&task));
        
        task.spec.is_preemptible = false;
        assert!(!manager.can_preempt(&task));
        
        task.spec.is_preemptible = true;
        task.preemptions = 5;
        assert!(!manager.can_preempt(&task));
    }

    #[test]
    fn test_preemption_stats() {
        let policy = PreemptionPolicy::default();
        let manager = PreemptionManager::new(policy);
        
        let (task1, alloc1) = create_test_task(
            "task1", TaskPriority::Low, true, 3, 8.0
        );
        
        let mut allocations = HashMap::new();
        allocations.insert(task1.task_id.clone(), alloc1);
        
        manager.find_preemption_candidates(
            &[task1],
            &allocations,
            4.0,
            TaskPriority::High,
        );
        
        let stats = manager.get_stats();
        assert_eq!(stats.total_preemptions, 1);
        assert_eq!(stats.successful_preemptions, 1);
    }

    #[test]
    fn test_preemption_strategy_none() {
        let policy = PreemptionPolicy {
            strategy: PreemptionStrategy::None,
            ..Default::default()
        };
        let manager = PreemptionManager::new(policy);
        
        let (task1, alloc1) = create_test_task(
            "task1", TaskPriority::Low, true, 3, 8.0
        );
        
        let mut allocations = HashMap::new();
        allocations.insert(task1.task_id.clone(), alloc1);
        
        let result = manager.find_preemption_candidates(
            &[task1],
            &allocations,
            4.0,
            TaskPriority::High,
        );
        
        assert!(!result.success);
    }
}
