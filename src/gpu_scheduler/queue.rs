use serde::{Deserialize, Serialize};
use std::collections::{BinaryHeap, HashMap, HashSet};
use std::cmp::Reverse;
use std::sync::Arc;
use parking_lot::Mutex;
use chrono::{DateTime, Utc};
use crate::utils::error::AppError;
use crate::gpu_scheduler::task::{GpuTask, TaskPriority, TaskStatus};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueueConfig {
    pub max_queue_size: usize,
    pub priority_boost_timeout_secs: Option<i64>,
    pub fair_scheduling: bool,
    pub max_tasks_per_user: Option<usize>,
}

impl Default for QueueConfig {
    fn default() -> Self {
        Self {
            max_queue_size: 1000,
            priority_boost_timeout_secs: Some(300),
            fair_scheduling: true,
            max_tasks_per_user: Some(50),
        }
    }
}

#[derive(Debug, Clone)]
struct QueuedTask {
    task: GpuTask,
    effective_priority: TaskPriority,
    enqueue_time: DateTime<Utc>,
    priority_boost_count: u32,
}

impl PartialEq for QueuedTask {
    fn eq(&self, other: &Self) -> bool {
        self.effective_priority == other.effective_priority
            && self.enqueue_time == other.enqueue_time
    }
}

impl Eq for QueuedTask {}

impl PartialOrd for QueuedTask {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for QueuedTask {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        self.effective_priority.cmp(&other.effective_priority)
            .then_with(|| other.enqueue_time.cmp(&self.enqueue_time))
    }
}

pub struct PriorityQueue {
    config: QueueConfig,
    heap: Mutex<BinaryHeap<QueuedTask>>,
    task_ids: Mutex<HashSet<String>>,
    user_task_counts: Mutex<HashMap<String, usize>>,
}

impl PriorityQueue {
    pub fn new(config: QueueConfig) -> Self {
        Self {
            config,
            heap: Mutex::new(BinaryHeap::new()),
            task_ids: Mutex::new(HashSet::new()),
            user_task_counts: Mutex::new(HashMap::new()),
        }
    }

    pub fn enqueue(&self, mut task: GpuTask) -> Result<GpuTask, AppError> {
        if self.size() >= self.config.max_queue_size {
            return Err(AppError::ResourceExhausted(
                "Task queue is full".to_string()
            ));
        }

        if self.task_ids.lock().contains(&task.task_id) {
            return Err(AppError::Validation(format!(
                "Task {} is already in queue",
                task.task_id
            )));
        }

        if let Some(max_per_user) = self.config.max_tasks_per_user {
            let mut counts = self.user_task_counts.lock();
            let count = counts.get(&task.spec.created_by).copied().unwrap_or(0);
            if count >= max_per_user {
                return Err(AppError::ResourceExhausted(format!(
                    "User {} has exceeded maximum queued tasks ({})",
                    task.spec.created_by, max_per_user
                )));
            }
            *counts.entry(task.spec.created_by.clone()).or_insert(0) += 1;
        }

        task.enqueue()?;
        
        let queued = QueuedTask {
            task: task.clone(),
            effective_priority: task.spec.priority,
            enqueue_time: task.queue_time.unwrap(),
            priority_boost_count: 0,
        };

        self.heap.lock().push(queued);
        self.task_ids.lock().insert(task.task_id.clone());

        Ok(task)
    }

    pub fn dequeue(&self) -> Option<GpuTask> {
        let mut heap = self.heap.lock();
        let mut task_ids = self.task_ids.lock();
        let mut user_counts = self.user_task_counts.lock();

        while let Some(queued) = heap.pop() {
            if task_ids.remove(&queued.task.task_id) {
                if let Some(count) = user_counts.get_mut(&queued.task.spec.created_by) {
                    *count = count.saturating_sub(1);
                }
                return Some(queued.task);
            }
        }

        None
    }

    pub fn dequeue_with_filter<F>(&self, filter: F) -> Option<GpuTask>
    where
        F: Fn(&GpuTask) -> bool,
    {
        let mut heap = self.heap.lock();
        let mut task_ids = self.task_ids.lock();
        let mut user_counts = self.user_task_counts.lock();
        
        let mut temp = Vec::new();
        let mut result = None;

        while let Some(queued) = heap.pop() {
            if task_ids.contains(&queued.task.task_id) {
                if filter(&queued.task) {
                    task_ids.remove(&queued.task.task_id);
                    if let Some(count) = user_counts.get_mut(&queued.task.spec.created_by) {
                        *count = count.saturating_sub(1);
                    }
                    result = Some(queued.task);
                    break;
                } else {
                    temp.push(queued);
                }
            }
        }

        for item in temp {
            heap.push(item);
        }

        result
    }

    pub fn remove(&self, task_id: &str) -> Option<GpuTask> {
        let mut heap = self.heap.lock();
        let mut task_ids = self.task_ids.lock();
        let mut user_counts = self.user_task_counts.lock();

        if !task_ids.remove(task_id) {
            return None;
        }

        let mut temp = Vec::new();
        let mut result = None;

        while let Some(queued) = heap.pop() {
            if queued.task.task_id == task_id {
                if let Some(count) = user_counts.get_mut(&queued.task.spec.created_by) {
                    *count = count.saturating_sub(1);
                }
                result = Some(queued.task);
            } else {
                temp.push(queued);
            }
        }

        for item in temp {
            heap.push(item);
        }

        result
    }

    pub fn size(&self) -> usize {
        self.task_ids.lock().len()
    }

    pub fn is_empty(&self) -> bool {
        self.task_ids.lock().is_empty()
    }

    pub fn contains(&self, task_id: &str) -> bool {
        self.task_ids.lock().contains(task_id)
    }

    pub fn update_priorities(&self) -> usize {
        let Some(boost_timeout) = self.config.priority_boost_timeout_secs else {
            return 0;
        };

        let mut heap = self.heap.lock();
        let mut temp = Vec::new();
        let now = Utc::now();
        let mut boosted = 0;

        while let Some(mut queued) = heap.pop() {
            let elapsed = (now - queued.enqueue_time).num_seconds();
            let boost_level = (elapsed / boost_timeout) as u32;

            if boost_level > queued.priority_boost_count {
                if queued.effective_priority < TaskPriority::Critical {
                    let priority_value = queued.effective_priority as u8;
                    if let Ok(new_priority) = TaskPriority::from_u8(priority_value + 1) {
                        queued.effective_priority = new_priority;
                        queued.priority_boost_count = boost_level;
                        boosted += 1;
                    }
                }
            }

            temp.push(queued);
        }

        for item in temp {
            heap.push(item);
        }

        boosted
    }

    pub fn get_queued_tasks(&self, limit: Option<usize>) -> Vec<GpuTask> {
        let heap = self.heap.lock();
        let task_ids = self.task_ids.lock();
        
        let mut tasks: Vec<GpuTask> = heap
            .iter()
            .filter(|q| task_ids.contains(&q.task.task_id))
            .map(|q| q.task.clone())
            .collect();
        
        if let Some(l) = limit {
            tasks.truncate(l);
        }
        
        tasks
    }

    pub fn get_stats(&self) -> QueueStats {
        let tasks = self.get_queued_tasks(None);
        
        let mut by_priority: HashMap<TaskPriority, usize> = HashMap::new();
        for task in &tasks {
            *by_priority.entry(task.spec.priority).or_insert(0) += 1;
        }

        let total_wait_time: i64 = tasks
            .iter()
            .filter_map(|t| t.queue_time.map(|qt| (Utc::now() - qt).num_seconds()))
            .sum();
        
        let avg_wait_time = if !tasks.is_empty() {
            total_wait_time as f64 / tasks.len() as f64
        } else {
            0.0
        };

        QueueStats {
            total_tasks: tasks.len(),
            by_priority,
            avg_wait_time_seconds: avg_wait_time,
            max_wait_time_seconds: tasks
                .iter()
                .filter_map(|t| t.queue_time.map(|qt| (Utc::now() - qt).num_seconds()))
                .max()
                .unwrap_or(0),
        }
    }

    pub fn clear(&self) {
        self.heap.lock().clear();
        self.task_ids.lock().clear();
        self.user_task_counts.lock().clear();
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueueStats {
    pub total_tasks: usize,
    pub by_priority: HashMap<TaskPriority, usize>,
    pub avg_wait_time_seconds: f64,
    pub max_wait_time_seconds: i64,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::gpu_scheduler::resource::GpuResourceSpec;
    use crate::gpu_scheduler::task::{GpuTaskSpec, TaskType};

    fn create_test_task(name: &str, priority: TaskPriority, user: &str) -> GpuTask {
        let spec = GpuTaskSpec {
            name: name.to_string(),
            description: "Test task".to_string(),
            task_type: TaskType::Training,
            priority,
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
            estimated_duration_secs: None,
            max_duration_secs: None,
            node_affinity: vec![],
            node_anti_affinity: vec![],
            labels: HashMap::new(),
            metadata: HashMap::new(),
            created_by: user.to_string(),
        };
        GpuTask::new(spec, 3).unwrap()
    }

    #[test]
    fn test_queue_basic_operations() {
        let queue = PriorityQueue::new(QueueConfig::default());
        
        let task = create_test_task("task1", TaskPriority::Medium, "user1");
        let task_id = task.task_id.clone();
        
        let enqueued = queue.enqueue(task).unwrap();
        assert_eq!(enqueued.status, TaskStatus::Queued);
        assert_eq!(queue.size(), 1);
        assert!(queue.contains(&task_id));
        
        let dequeued = queue.dequeue().unwrap();
        assert_eq!(dequeued.task_id, task_id);
        assert!(queue.is_empty());
    }

    #[test]
    fn test_queue_priority_ordering() {
        let queue = PriorityQueue::new(QueueConfig::default());
        
        let task1 = create_test_task("low", TaskPriority::Low, "user1");
        let task2 = create_test_task("high", TaskPriority::High, "user1");
        let task3 = create_test_task("medium", TaskPriority::Medium, "user1");
        
        queue.enqueue(task1).unwrap();
        queue.enqueue(task2).unwrap();
        queue.enqueue(task3).unwrap();
        
        let first = queue.dequeue().unwrap();
        assert_eq!(first.spec.name, "high");
        
        let second = queue.dequeue().unwrap();
        assert_eq!(second.spec.name, "medium");
        
        let third = queue.dequeue().unwrap();
        assert_eq!(third.spec.name, "low");
    }

    #[test]
    fn test_queue_fifo_same_priority() {
        let queue = PriorityQueue::new(QueueConfig::default());
        
        let task1 = create_test_task("first", TaskPriority::Medium, "user1");
        std::thread::sleep(std::time::Duration::from_millis(10));
        let task2 = create_test_task("second", TaskPriority::Medium, "user1");
        std::thread::sleep(std::time::Duration::from_millis(10));
        let task3 = create_test_task("third", TaskPriority::Medium, "user1");
        
        queue.enqueue(task1).unwrap();
        queue.enqueue(task2).unwrap();
        queue.enqueue(task3).unwrap();
        
        assert_eq!(queue.dequeue().unwrap().spec.name, "first");
        assert_eq!(queue.dequeue().unwrap().spec.name, "second");
        assert_eq!(queue.dequeue().unwrap().spec.name, "third");
    }

    #[test]
    fn test_queue_remove() {
        let queue = PriorityQueue::new(QueueConfig::default());
        
        let task1 = create_test_task("task1", TaskPriority::Medium, "user1");
        let task2 = create_test_task("task2", TaskPriority::High, "user1");
        let task3 = create_test_task("task3", TaskPriority::Low, "user1");
        
        let id1 = queue.enqueue(task1).unwrap().task_id;
        queue.enqueue(task2).unwrap();
        queue.enqueue(task3).unwrap();
        
        assert_eq!(queue.size(), 3);
        
        let removed = queue.remove(&id1).unwrap();
        assert_eq!(removed.task_id, id1);
        assert_eq!(queue.size(), 2);
        
        assert!(queue.remove(&id1).is_none());
    }

    #[test]
    fn test_queue_max_size() {
        let mut config = QueueConfig::default();
        config.max_queue_size = 2;
        let queue = PriorityQueue::new(config);
        
        queue.enqueue(create_test_task("task1", TaskPriority::Medium, "user1")).unwrap();
        queue.enqueue(create_test_task("task2", TaskPriority::Medium, "user1")).unwrap();
        
        let result = queue.enqueue(create_test_task("task3", TaskPriority::Medium, "user1"));
        assert!(result.is_err());
    }

    #[test]
    fn test_queue_max_per_user() {
        let mut config = QueueConfig::default();
        config.max_tasks_per_user = Some(2);
        let queue = PriorityQueue::new(config);
        
        queue.enqueue(create_test_task("task1", TaskPriority::Medium, "user1")).unwrap();
        queue.enqueue(create_test_task("task2", TaskPriority::Medium, "user1")).unwrap();
        
        let result = queue.enqueue(create_test_task("task3", TaskPriority::Medium, "user1"));
        assert!(result.is_err());
        
        let result = queue.enqueue(create_test_task("task4", TaskPriority::Medium, "user2"));
        assert!(result.is_ok());
    }

    #[test]
    fn test_queue_dequeue_with_filter() {
        let queue = PriorityQueue::new(QueueConfig::default());
        
        queue.enqueue(create_test_task("task1", TaskPriority::High, "user1")).unwrap();
        queue.enqueue(create_test_task("task2", TaskPriority::Medium, "user2")).unwrap();
        queue.enqueue(create_test_task("task3", TaskPriority::Low, "user1")).unwrap();
        
        let filtered = queue.dequeue_with_filter(|t| t.spec.created_by == "user2").unwrap();
        assert_eq!(filtered.spec.name, "task2");
        assert_eq!(queue.size(), 2);
    }

    #[test]
    fn test_queue_stats() {
        let queue = PriorityQueue::new(QueueConfig::default());
        
        queue.enqueue(create_test_task("task1", TaskPriority::High, "user1")).unwrap();
        queue.enqueue(create_test_task("task2", TaskPriority::Medium, "user1")).unwrap();
        queue.enqueue(create_test_task("task3", TaskPriority::Medium, "user2")).unwrap();
        
        let stats = queue.get_stats();
        assert_eq!(stats.total_tasks, 3);
        assert_eq!(stats.by_priority.get(&TaskPriority::High), Some(&1));
        assert_eq!(stats.by_priority.get(&TaskPriority::Medium), Some(&2));
    }

    #[test]
    fn test_queue_priority_boost() {
        let mut config = QueueConfig::default();
        config.priority_boost_timeout_secs = Some(1);
        let queue = PriorityQueue::new(config);
        
        let task = create_test_task("task1", TaskPriority::Low, "user1");
        queue.enqueue(task).unwrap();
        
        std::thread::sleep(std::time::Duration::from_secs(2));
        
        let boosted = queue.update_priorities();
        assert!(boosted > 0);
        
        let dequeued = queue.dequeue().unwrap();
        assert!(dequeued.spec.priority < TaskPriority::Medium);
    }

    #[test]
    fn test_queue_clear() {
        let queue = PriorityQueue::new(QueueConfig::default());
        
        queue.enqueue(create_test_task("task1", TaskPriority::High, "user1")).unwrap();
        queue.enqueue(create_test_task("task2", TaskPriority::Medium, "user1")).unwrap();
        
        assert_eq!(queue.size(), 2);
        queue.clear();
        assert!(queue.is_empty());
    }
}
