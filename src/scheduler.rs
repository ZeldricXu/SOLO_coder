use std::collections::BinaryHeap;
use std::future::Future;
use std::pin::Pin;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::task::{Context, Poll};
use std::time::Duration;

use tokio::sync::{mpsc, watch, Mutex};
use tokio::time::{sleep, Instant};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum TaskPriority {
    Low = 0,
    Normal = 1,
    High = 2,
    Critical = 3,
}

impl Default for TaskPriority {
    fn default() -> Self {
        TaskPriority::Normal
    }
}

pub type TaskId = u64;

#[derive(Debug, Clone)]
pub struct TaskMetadata {
    pub id: TaskId,
    pub name: String,
    pub priority: TaskPriority,
    pub created_at: Instant,
}

pub trait SchedulableTask: Send + 'static {
    type Output: Send + 'static;
    type Error: Send + 'static;

    fn metadata(&self) -> &TaskMetadata;
    fn run(self) -> Pin<Box<dyn Future<Output = Result<Self::Output, Self::Error>> + Send>>;
}

struct QueuedTask<O, E> {
    metadata: TaskMetadata,
    task: Pin<Box<dyn Future<Output = Result<O, E>> + Send>>,
}

impl<O, E> PartialEq for QueuedTask<O, E> {
    fn eq(&self, other: &Self) -> bool {
        self.metadata.id == other.metadata.id
    }
}

impl<O, E> Eq for QueuedTask<O, E> {}

impl<O, E> PartialOrd for QueuedTask<O, E> {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

impl<O, E> Ord for QueuedTask<O, E> {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        match other.metadata.priority.cmp(&self.metadata.priority) {
            std::cmp::Ordering::Equal => self.metadata.created_at.cmp(&other.metadata.created_at),
            ordering => ordering,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SchedulerStatus {
    Idle,
    Running,
    Paused,
    Stopped,
}

pub struct SchedulerConfig {
    pub max_concurrent: usize,
    pub max_queue_size: usize,
    pub token_refill_interval: Duration,
    pub tokens_per_refill: u32,
    pub initial_tokens: u32,
    pub max_tokens: u32,
}

impl Default for SchedulerConfig {
    fn default() -> Self {
        let cpu_count = num_cpus::get();
        SchedulerConfig {
            max_concurrent: cpu_count,
            max_queue_size: 1000,
            token_refill_interval: Duration::from_millis(100),
            tokens_per_refill: 1,
            initial_tokens: cpu_count as u32,
            max_tokens: (cpu_count * 2) as u32,
        }
    }
}

struct TokenBucket {
    tokens: AtomicUsize,
    max_tokens: usize,
}

impl TokenBucket {
    fn new(initial_tokens: u32, max_tokens: u32) -> Self {
        TokenBucket {
            tokens: AtomicUsize::new(initial_tokens as usize),
            max_tokens: max_tokens as usize,
        }
    }

    fn try_acquire(&self) -> bool {
        let mut current = self.tokens.load(Ordering::Acquire);
        loop {
            if current == 0 {
                return false;
            }
            let new_current = current - 1;
            match self.tokens.compare_exchange_weak(
                current,
                new_current,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => return true,
                Err(actual) => current = actual,
            }
        }
    }

    fn refill(&self, amount: u32) {
        let amount = amount as usize;
        let mut current = self.tokens.load(Ordering::Acquire);
        loop {
            let new_current = std::cmp::min(current + amount, self.max_tokens);
            if new_current == current {
                return;
            }
            match self.tokens.compare_exchange_weak(
                current,
                new_current,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => return,
                Err(actual) => current = actual,
            }
        }
    }

    fn available(&self) -> usize {
        self.tokens.load(Ordering::Acquire)
    }
}

#[derive(Debug, Clone)]
pub struct SchedulerStats {
    pub queued_tasks: usize,
    pub running_tasks: usize,
    pub completed_tasks: usize,
    pub failed_tasks: usize,
    pub available_tokens: usize,
    pub status: SchedulerStatus,
}

pub struct Scheduler<O, E> {
    config: SchedulerConfig,
    queue: Arc<Mutex<BinaryHeap<QueuedTask<O, E>>>>,
    token_bucket: Arc<TokenBucket>,
    status: watch::Sender<SchedulerStatus>,
    task_counter: AtomicUsize,
    running_count: Arc<AtomicUsize>,
    completed_count: Arc<AtomicUsize>,
    failed_count: Arc<AtomicUsize>,
    result_tx: mpsc::UnboundedSender<(TaskId, Result<O, E>)>,
}

impl<O: Send + 'static, E: Send + 'static> Scheduler<O, E> {
    pub fn new(config: SchedulerConfig) -> (Self, mpsc::UnboundedReceiver<(TaskId, Result<O, E>)>) {
        let (result_tx, result_rx) = mpsc::unbounded_channel();
        let (status_tx, _) = watch::channel(SchedulerStatus::Idle);

        let scheduler = Scheduler {
            config,
            queue: Arc::new(Mutex::new(BinaryHeap::new())),
            token_bucket: Arc::new(TokenBucket::new(
                config.initial_tokens,
                config.max_tokens,
            )),
            status: status_tx,
            task_counter: AtomicUsize::new(0),
            running_count: Arc::new(AtomicUsize::new(0)),
            completed_count: Arc::new(AtomicUsize::new(0)),
            failed_count: Arc::new(AtomicUsize::new(0)),
            result_tx,
        };

        (scheduler, result_rx)
    }

    pub fn with_default_config() -> (Self, mpsc::UnboundedReceiver<(TaskId, Result<O, E>)>) {
        Self::new(SchedulerConfig::default())
    }

    pub fn next_task_id(&self) -> TaskId {
        self.task_counter.fetch_add(1, Ordering::Relaxed) as TaskId
    }

    pub async fn schedule<F>(
        &self,
        name: String,
        priority: TaskPriority,
        future: F,
    ) -> Result<TaskId, SchedulerError>
    where
        F: Future<Output = Result<O, E>> + Send + 'static,
    {
        let task_id = self.next_task_id();
        let metadata = TaskMetadata {
            id: task_id,
            name,
            priority,
            created_at: Instant::now(),
        };

        let queued_task = QueuedTask {
            metadata,
            task: Box::pin(future),
        };

        let mut queue = self.queue.lock().await;
        if queue.len() >= self.config.max_queue_size {
            return Err(SchedulerError::QueueFull);
        }
        queue.push(queued_task);

        Ok(task_id)
    }

    pub async fn run(&self) {
        self.status.send_modify(|s| *s = SchedulerStatus::Running);

        let token_bucket = self.token_bucket.clone();
        let refill_interval = self.config.token_refill_interval;
        let tokens_per_refill = self.config.tokens_per_refill;

        let _token_refill_task = tokio::spawn(async move {
            loop {
                sleep(refill_interval).await;
                token_bucket.refill(tokens_per_refill);
            }
        });

        loop {
            if *self.status.borrow() == SchedulerStatus::Stopped {
                break;
            }

            if *self.status.borrow() == SchedulerStatus::Paused {
                sleep(Duration::from_millis(100)).await;
                continue;
            }

            let running = self.running_count.load(Ordering::Acquire);
            if running >= self.config.max_concurrent {
                sleep(Duration::from_millis(10)).await;
                continue;
            }

            if !self.token_bucket.try_acquire() {
                sleep(Duration::from_millis(10)).await;
                continue;
            }

            let task = {
                let mut queue = self.queue.lock().await;
                queue.pop()
            };

            if let Some(task) = task {
                self.running_count.fetch_add(1, Ordering::AcqRel);
                let result_tx = self.result_tx.clone();
                let running_count = self.running_count.clone();
                let completed_count = self.completed_count.clone();
                let failed_count = self.failed_count.clone();
                let task_id = task.metadata.id;

                tokio::spawn(async move {
                    let result = task.task.await;

                    match &result {
                        Ok(_) => {
                            completed_count.fetch_add(1, Ordering::AcqRel);
                        }
                        Err(_) => {
                            failed_count.fetch_add(1, Ordering::AcqRel);
                        }
                    }

                    let _ = result_tx.send((task_id, result));
                    running_count.fetch_sub(1, Ordering::AcqRel);
                });
            } else {
                sleep(Duration::from_millis(50)).await;
            }
        }

        self.status.send_modify(|s| *s = SchedulerStatus::Idle);
    }

    pub fn pause(&self) {
        self.status.send_modify(|s| *s = SchedulerStatus::Paused);
    }

    pub fn resume(&self) {
        self.status.send_modify(|s| *s = SchedulerStatus::Running);
    }

    pub fn stop(&self) {
        self.status.send_modify(|s| *s = SchedulerStatus::Stopped);
    }

    pub async fn stats(&self) -> SchedulerStats {
        let queue = self.queue.lock().await;
        SchedulerStats {
            queued_tasks: queue.len(),
            running_tasks: self.running_count.load(Ordering::Acquire),
            completed_tasks: self.completed_count.load(Ordering::Acquire),
            failed_tasks: self.failed_count.load(Ordering::Acquire),
            available_tokens: self.token_bucket.available(),
            status: *self.status.borrow(),
        }
    }

    pub async fn queue_len(&self) -> usize {
        let queue = self.queue.lock().await;
        queue.len()
    }

    pub fn status(&self) -> SchedulerStatus {
        *self.status.borrow()
    }

    pub fn update_concurrency(&mut self, max_concurrent: usize) {
        self.config.max_concurrent = max_concurrent;
    }

    pub fn config(&self) -> &SchedulerConfig {
        &self.config
    }
}

#[derive(Error, Debug, Clone)]
pub enum SchedulerError {
    #[error("任务队列已满")]
    QueueFull,

    #[error("调度器已停止")]
    SchedulerStopped,

    #[error("任务不存在: {0}")]
    TaskNotFound(TaskId),

    #[error("任务执行超时")]
    TaskTimeout,

    #[error("调度器配置错误: {0}")]
    InvalidConfig(String),
}

impl std::error::Error for SchedulerError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_scheduler_basic() {
        let (scheduler, mut result_rx) = Scheduler::<u32, ()>::with_default_config();

        let task_id = scheduler
            .schedule(
                "test".to_string(),
                TaskPriority::Normal,
                async { Ok(42) },
            )
            .await
            .unwrap();

        tokio::spawn(async move {
            scheduler.run().await;
        });

        let (received_id, result) = result_rx.recv().await.unwrap();
        assert_eq!(received_id, task_id);
        assert_eq!(result.unwrap(), 42);
    }

    #[tokio::test]
    async fn test_task_priority() {
        let (scheduler, _result_rx) = Scheduler::<u32, ()>::with_default_config();

        let _low_id = scheduler
            .schedule("low".to_string(), TaskPriority::Low, async { Ok(1) })
            .await
            .unwrap();

        let _high_id = scheduler
            .schedule("high".to_string(), TaskPriority::High, async { Ok(2) })
            .await
            .unwrap();

        let _normal_id = scheduler
            .schedule(
                "normal".to_string(),
                TaskPriority::Normal,
                async { Ok(3) },
            )
            .await
            .unwrap();

        let stats = scheduler.stats().await;
        assert_eq!(stats.queued_tasks, 3);
    }

    #[test]
    fn test_token_bucket() {
        let bucket = TokenBucket::new(3, 5);

        assert!(bucket.try_acquire());
        assert!(bucket.try_acquire());
        assert!(bucket.try_acquire());
        assert!(!bucket.try_acquire());

        bucket.refill(2);
        assert_eq!(bucket.available(), 2);

        bucket.refill(10);
        assert_eq!(bucket.available(), 5);
    }
}
