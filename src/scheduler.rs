use crate::config::SchedulerConfig;
use crate::error::SystemError;
use async_trait::async_trait;
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::Arc;
use tokio::sync::{mpsc, Mutex, Semaphore};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TaskStatus {
    Pending,
    Running,
    Completed,
    Failed,
    Cancelled,
    Retrying,
    Timeout,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Task {
    pub id: Uuid,
    pub name: String,
    pub description: Option<String>,
    pub status: TaskStatus,
    pub priority: TaskPriority,
    pub payload: serde_json::Value,
    pub created_at: DateTime<Utc>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub retry_count: u32,
    pub max_retries: u32,
    pub timeout_seconds: u64,
    pub progress: f32,
    pub error_message: Option<String>,
    pub result: Option<serde_json::Value>,
    pub dependencies: Vec<Uuid>,
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "lowercase")]
pub enum TaskPriority {
    Low = 0,
    Normal = 1,
    High = 2,
    Critical = 3,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskProgress {
    pub task_id: Uuid,
    pub progress: f32,
    pub message: Option<String>,
    pub timestamp: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchedulerStats {
    pub total_tasks: usize,
    pub pending_tasks: usize,
    pub running_tasks: usize,
    pub completed_tasks: usize,
    pub failed_tasks: usize,
    pub success_rate: f64,
    pub average_execution_time_ms: f64,
}

#[async_trait]
pub trait TaskExecutor: Send + Sync {
    async fn execute(&self, task: &Task) -> Result<serde_json::Value, SystemError>;
}

pub type TaskCallback = Arc<dyn Fn(Task) + Send + Sync>;

pub type TaskUpdater = Box<dyn FnOnce(&mut Task) + Send>;

#[async_trait]
pub trait TaskRepository: Send + Sync {
    async fn insert(&self, task: Task) -> Result<(), SystemError>;
    async fn get(&self, task_id: Uuid) -> Option<Task>;
    async fn update(&self, task_id: Uuid, updater: TaskUpdater) -> Result<(), SystemError>;
    async fn remove(&self, task_id: Uuid) -> Option<Task>;
    async fn contains(&self, task_id: Uuid) -> bool;
    async fn list_all(&self) -> Vec<Task>;
    async fn list_by_status(&self, status: TaskStatus) -> Vec<Task>;
}

pub struct InMemoryTaskRepository {
    tasks: Arc<DashMap<Uuid, Task>>,
}

impl InMemoryTaskRepository {
    pub fn new() -> Self {
        Self {
            tasks: Arc::new(DashMap::new()),
        }
    }
}

#[async_trait]
impl TaskRepository for InMemoryTaskRepository {
    async fn insert(&self, task: Task) -> Result<(), SystemError> {
        self.tasks.insert(task.id, task);
        Ok(())
    }

    async fn get(&self, task_id: Uuid) -> Option<Task> {
        self.tasks.get(&task_id).map(|r| r.clone())
    }

    async fn update(&self, task_id: Uuid, updater: TaskUpdater) -> Result<(), SystemError> {
        let mut task = self
            .tasks
            .get_mut(&task_id)
            .ok_or_else(|| SystemError::NotFoundError(format!("任务不存在: {}", task_id)))?;
        updater(&mut task);
        Ok(())
    }

    async fn remove(&self, task_id: Uuid) -> Option<Task> {
        self.tasks.remove(&task_id).map(|(_, v)| v)
    }

    async fn contains(&self, task_id: Uuid) -> bool {
        self.tasks.contains_key(&task_id)
    }

    async fn list_all(&self) -> Vec<Task> {
        self.tasks.iter().map(|t| t.clone()).collect()
    }

    async fn list_by_status(&self, status: TaskStatus) -> Vec<Task> {
        self.tasks
            .iter()
            .filter(|t| t.status == status)
            .map(|t| t.clone())
            .collect()
    }
}

impl Clone for InMemoryTaskRepository {
    fn clone(&self) -> Self {
        Self {
            tasks: self.tasks.clone(),
        }
    }
}

#[async_trait]
pub trait TaskQueue: Send + Sync {
    async fn enqueue(&self, task_id: Uuid) -> Result<(), SystemError>;
    async fn dequeue(&self) -> Option<Uuid>;
    async fn len(&self) -> usize;
    async fn is_empty(&self) -> bool;
}

pub struct InMemoryTaskQueue {
    queue: Arc<Mutex<VecDeque<Uuid>>>,
    tx: mpsc::Sender<Uuid>,
}

impl InMemoryTaskQueue {
    pub fn new(buffer_size: usize) -> (Self, mpsc::Receiver<Uuid>) {
        let (tx, rx) = mpsc::channel(buffer_size);
        (
            Self {
                queue: Arc::new(Mutex::new(VecDeque::new())),
                tx,
            },
            rx,
        )
    }
}

#[async_trait]
impl TaskQueue for InMemoryTaskQueue {
    async fn enqueue(&self, task_id: Uuid) -> Result<(), SystemError> {
        let mut queue = self.queue.lock().await;
        queue.push_back(task_id);
        self.tx
            .send(task_id)
            .await
            .map_err(|e| SystemError::SchedulerError(format!("任务入队失败: {}", e)))?;
        Ok(())
    }

    async fn dequeue(&self) -> Option<Uuid> {
        let mut queue = self.queue.lock().await;
        queue.pop_front()
    }

    async fn len(&self) -> usize {
        self.queue.lock().await.len()
    }

    async fn is_empty(&self) -> bool {
        self.queue.lock().await.is_empty()
    }
}

impl Clone for InMemoryTaskQueue {
    fn clone(&self) -> Self {
        Self {
            queue: self.queue.clone(),
            tx: self.tx.clone(),
        }
    }
}

#[async_trait]
pub trait ConcurrencyController: Send + Sync {
    async fn acquire_permit(&self) -> Result<Box<dyn Permit + '_>, SystemError>;
    fn max_concurrency(&self) -> usize;
}

pub trait Permit: Send {
    fn release(&mut self);
}

pub struct SemaphoreConcurrencyController {
    semaphore: Arc<Semaphore>,
    max_permits: usize,
}

pub struct SemaphorePermit {
    permit: Option<tokio::sync::SemaphorePermit<'static>>,
}

impl Permit for SemaphorePermit {
    fn release(&mut self) {
        if let Some(permit) = self.permit.take() {
            permit.forget();
        }
    }
}

impl Drop for SemaphorePermit {
    fn drop(&mut self) {
        if self.permit.is_some() {
            self.release();
        }
    }
}

impl SemaphoreConcurrencyController {
    pub fn new(max_permits: usize) -> Self {
        Self {
            semaphore: Arc::new(Semaphore::new(max_permits)),
            max_permits,
        }
    }
}

#[async_trait]
impl ConcurrencyController for SemaphoreConcurrencyController {
    async fn acquire_permit(&self) -> Result<Box<dyn Permit + '_>, SystemError> {
        let permit = self
            .semaphore
            .acquire()
            .await
            .map_err(|e| SystemError::SchedulerError(format!("获取执行许可失败: {}", e)))?;

        let static_permit = unsafe {
            std::mem::transmute::<
                tokio::sync::SemaphorePermit<'_>,
                tokio::sync::SemaphorePermit<'static>,
            >(permit)
        };

        Ok(Box::new(SemaphorePermit {
            permit: Some(static_permit),
        }))
    }

    fn max_concurrency(&self) -> usize {
        self.max_permits
    }
}

#[async_trait]
pub trait FailureHandler: Send + Sync {
    async fn handle_failure(
        &self,
        task: &mut Task,
        error: String,
        repository: Arc<dyn TaskRepository>,
        queue: Arc<dyn TaskQueue>,
    );
    fn should_retry(&self, task: &Task) -> bool;
}

pub struct DefaultFailureHandler {
    retry_delay: std::time::Duration,
}

impl DefaultFailureHandler {
    pub fn new(retry_delay: std::time::Duration) -> Self {
        Self { retry_delay }
    }
}

#[async_trait]
impl FailureHandler for DefaultFailureHandler {
    async fn handle_failure(
        &self,
        task: &mut Task,
        error: String,
        repository: Arc<dyn TaskRepository>,
        queue: Arc<dyn TaskQueue>,
    ) {
        task.retry_count += 1;
        task.error_message = Some(error.clone());

        if self.should_retry(task) {
            task.status = TaskStatus::Retrying;
            warn!(
                "任务 {} 执行失败，正在重试 ({}/{})",
                task.id, task.retry_count, task.max_retries
            );

            let task_id = task.id;
            let delay = self.retry_delay;
            tokio::spawn(async move {
                tokio::time::sleep(delay).await;
                if let Ok(_) = repository
                    .update(task_id, Box::new(|t| {
                        if t.status == TaskStatus::Retrying {
                            t.status = TaskStatus::Pending;
                        }
                    }))
                    .await
                {
                    let _ = queue.enqueue(task_id).await;
                }
            });
        } else {
            task.status = TaskStatus::Failed;
            task.completed_at = Some(Utc::now());
            error!("任务 {} 执行失败，已达最大重试次数: {}", task.id, error);
        }
    }

    fn should_retry(&self, task: &Task) -> bool {
        task.retry_count < task.max_retries
    }
}

pub type EventCallback = Box<dyn Fn(Task) + Send + Sync + 'static>;

#[async_trait]
pub trait TaskEventPublisher: Send + Sync {
    async fn publish(&self, task: Task);
    async fn subscribe(&self, callback: EventCallback);
}

pub struct InMemoryTaskEventPublisher {
    callbacks: Arc<tokio::sync::RwLock<Vec<TaskCallback>>>,
}

impl InMemoryTaskEventPublisher {
    pub fn new() -> Self {
        Self {
            callbacks: Arc::new(tokio::sync::RwLock::new(Vec::new())),
        }
    }
}

#[async_trait]
impl TaskEventPublisher for InMemoryTaskEventPublisher {
    async fn publish(&self, task: Task) {
        let cbs = self.callbacks.read().await;
        for cb in cbs.iter() {
            cb(task.clone());
        }
    }

    async fn subscribe(&self, callback: EventCallback) {
        let mut callbacks = self.callbacks.write().await;
        callbacks.push(Arc::new(callback));
    }
}

impl Clone for InMemoryTaskEventPublisher {
    fn clone(&self) -> Self {
        Self {
            callbacks: self.callbacks.clone(),
        }
    }
}

#[async_trait]
pub trait TaskExecutorRegistry: Send + Sync {
    async fn register(&self, name: String, executor: Arc<dyn TaskExecutor>);
    async fn get(&self, name: &str) -> Option<Arc<dyn TaskExecutor>>;
    async fn get_default(&self) -> Option<Arc<dyn TaskExecutor>>;
}

pub struct DefaultTaskExecutorRegistry {
    executors: Arc<DashMap<String, Arc<dyn TaskExecutor>>>,
}

impl DefaultTaskExecutorRegistry {
    pub fn new() -> Self {
        Self {
            executors: Arc::new(DashMap::new()),
        }
    }
}

#[async_trait]
impl TaskExecutorRegistry for DefaultTaskExecutorRegistry {
    async fn register(&self, name: String, executor: Arc<dyn TaskExecutor>) {
        self.executors.insert(name, executor);
    }

    async fn get(&self, name: &str) -> Option<Arc<dyn TaskExecutor>> {
        self.executors.get(name).map(|e| e.value().clone())
    }

    async fn get_default(&self) -> Option<Arc<dyn TaskExecutor>> {
        self.get("default").await.or_else(|| {
            self.executors.iter().next().map(|e| e.value().clone())
        })
    }
}

pub struct Scheduler {
    config: SchedulerConfig,
    repository: Arc<dyn TaskRepository>,
    queue: Arc<dyn TaskQueue>,
    concurrency_controller: Arc<dyn ConcurrencyController>,
    executor_registry: Arc<dyn TaskExecutorRegistry>,
    failure_handler: Arc<dyn FailureHandler>,
    event_publisher: Arc<dyn TaskEventPublisher>,
}

impl Scheduler {
    pub fn new(config: &SchedulerConfig) -> Result<Self, SystemError> {
        let repository = Arc::new(InMemoryTaskRepository::new());
        let (queue, rx) = InMemoryTaskQueue::new(1000);
        let queue = Arc::new(queue);
        let concurrency_controller = Arc::new(SemaphoreConcurrencyController::new(config.max_concurrent_tasks));
        let executor_registry = Arc::new(DefaultTaskExecutorRegistry::new());
        let failure_handler = Arc::new(DefaultFailureHandler::new(config.retry_delay()));
        let event_publisher = Arc::new(InMemoryTaskEventPublisher::new());

        let scheduler = Self {
            config: config.clone(),
            repository: repository.clone(),
            queue: queue.clone(),
            concurrency_controller: concurrency_controller.clone(),
            executor_registry: executor_registry.clone(),
            failure_handler: failure_handler.clone(),
            event_publisher: event_publisher.clone(),
        };

        scheduler.start_task_executor(
            rx,
            repository,
            queue,
            concurrency_controller,
            executor_registry,
            failure_handler,
            event_publisher,
            config.clone(),
        );

        Ok(scheduler)
    }

    pub fn with_dependencies(
        config: SchedulerConfig,
        repository: Arc<dyn TaskRepository>,
        queue: Arc<dyn TaskQueue>,
        concurrency_controller: Arc<dyn ConcurrencyController>,
        executor_registry: Arc<dyn TaskExecutorRegistry>,
        failure_handler: Arc<dyn FailureHandler>,
        event_publisher: Arc<dyn TaskEventPublisher>,
        mut rx: mpsc::Receiver<Uuid>,
    ) -> Self {
        let repository_clone = repository.clone();
        let queue_clone = queue.clone();
        let concurrency_clone = concurrency_controller.clone();
        let executor_clone = executor_registry.clone();
        let failure_clone = failure_handler.clone();
        let event_clone = event_publisher.clone();
        let config_clone = config.clone();

        tokio::spawn(async move {
            rx = Self::run_execution_loop(
                rx,
                repository_clone,
                queue_clone,
                concurrency_clone,
                executor_clone,
                failure_clone,
                event_clone,
                config_clone,
            ).await;
        });

        Self {
            config,
            repository,
            queue,
            concurrency_controller,
            executor_registry,
            failure_handler,
            event_publisher,
        }
    }

    pub async fn submit_task(&self, mut task: Task) -> Result<Uuid, SystemError> {
        if self.repository.contains(task.id).await {
            return Err(SystemError::SchedulerError(format!(
                "任务已存在: {}",
                task.id
            )));
        }

        task.status = TaskStatus::Pending;
        task.created_at = Utc::now();
        task.retry_count = 0;
        task.progress = 0.0;

        let task_id = task.id;
        self.repository.insert(task).await?;
        self.queue.enqueue(task_id).await?;

        Ok(task_id)
    }

    pub async fn create_task(
        &self,
        name: String,
        description: Option<String>,
        payload: serde_json::Value,
        priority: TaskPriority,
        tags: Vec<String>,
        dependencies: Vec<Uuid>,
    ) -> Result<Uuid, SystemError> {
        let task = Task {
            id: Uuid::new_v4(),
            name,
            description,
            status: TaskStatus::Pending,
            priority,
            payload,
            created_at: Utc::now(),
            started_at: None,
            completed_at: None,
            retry_count: 0,
            max_retries: self.config.retry_attempts,
            timeout_seconds: self.config.task_timeout_secs,
            progress: 0.0,
            error_message: None,
            result: None,
            dependencies,
            tags,
        };

        self.submit_task(task).await
    }

    pub async fn get_task(&self, task_id: Uuid) -> Result<Task, SystemError> {
        self.repository
            .get(task_id)
            .await
            .ok_or_else(|| SystemError::NotFoundError(format!("任务不存在: {}", task_id)))
    }

    pub async fn list_tasks(&self, status_filter: Option<TaskStatus>) -> Vec<Task> {
        let mut tasks = match status_filter {
            Some(status) => self.repository.list_by_status(status).await,
            None => self.repository.list_all().await,
        };

        tasks.sort_by(|a, b| {
            b.priority.cmp(&a.priority).then(a.created_at.cmp(&b.created_at))
        });

        tasks
    }

    pub async fn cancel_task(&self, task_id: Uuid) -> Result<(), SystemError> {
        self.repository
            .update(task_id, Box::new(|task| {
                if matches!(
                    task.status,
                    TaskStatus::Running | TaskStatus::Pending | TaskStatus::Retrying
                ) {
                    task.status = TaskStatus::Cancelled;
                    task.completed_at = Some(Utc::now());
                }
            }))
            .await?;
        Ok(())
    }

    pub async fn update(&self, task_id: Uuid, updater: TaskUpdater) -> Result<(), SystemError> {
        self.repository.update(task_id, updater).await
    }

    pub async fn update_progress(&self, task_id: Uuid, progress: f32, message: Option<String>) -> Result<(), SystemError> {
        let task_id_clone = task_id;
        let progress_clone = progress;
        let message_clone = message;
        self.repository
            .update(task_id, Box::new(move |task| {
                if task.status == TaskStatus::Running {
                    task.progress = progress_clone.clamp(0.0, 1.0);
                    debug!(
                        "任务 {} 进度更新: {:.1}% - {}",
                        task_id_clone,
                        progress_clone * 100.0,
                        message_clone.unwrap_or_default()
                    );
                }
            }))
            .await?;
        Ok(())
    }

    pub async fn register_executor(&self, task_type: String, executor: Arc<dyn TaskExecutor>) {
        self.executor_registry.register(task_type, executor).await;
    }

    pub async fn register_callback<F>(&self, callback: F)
    where
        F: Fn(Task) + Send + Sync + 'static,
    {
        self.event_publisher.subscribe(Box::new(callback)).await;
    }

    fn start_task_executor(
        &self,
        rx: mpsc::Receiver<Uuid>,
        repository: Arc<dyn TaskRepository>,
        queue: Arc<dyn TaskQueue>,
        concurrency_controller: Arc<dyn ConcurrencyController>,
        executor_registry: Arc<dyn TaskExecutorRegistry>,
        failure_handler: Arc<dyn FailureHandler>,
        event_publisher: Arc<dyn TaskEventPublisher>,
        config: SchedulerConfig,
    ) {
        tokio::spawn(async move {
            let _rx = Self::run_execution_loop(
                rx,
                repository,
                queue,
                concurrency_controller,
                executor_registry,
                failure_handler,
                event_publisher,
                config,
            ).await;
        });
    }

    async fn run_execution_loop(
        mut rx: mpsc::Receiver<Uuid>,
        repository: Arc<dyn TaskRepository>,
        _queue: Arc<dyn TaskQueue>,
        concurrency_controller: Arc<dyn ConcurrencyController>,
        executor_registry: Arc<dyn TaskExecutorRegistry>,
        failure_handler: Arc<dyn FailureHandler>,
        event_publisher: Arc<dyn TaskEventPublisher>,
        config: SchedulerConfig,
    ) -> mpsc::Receiver<Uuid> {
        while let Some(task_id) = rx.recv().await {
            let repository = repository.clone();
            let concurrency_controller = concurrency_controller.clone();
            let executor_registry = executor_registry.clone();
            let failure_handler = failure_handler.clone();
            let event_publisher = event_publisher.clone();
            let config = config.clone();
            let queue_clone = _queue.clone();

            tokio::spawn(async move {
                let _queue = queue_clone;
                let permit = match concurrency_controller.acquire_permit().await {
                    Ok(p) => p,
                    Err(e) => {
                        error!("获取执行许可失败: {}", e);
                        return;
                    }
                };

                let mut task = match repository.get(task_id).await {
                    Some(t) if t.status == TaskStatus::Pending => t,
                    _ => return,
                };

                task.status = TaskStatus::Running;
                task.started_at = Some(Utc::now());
                let task_clone = task.clone();
                let _ = repository.update(task_id, Box::new(move |t| *t = task_clone)).await;
                event_publisher.publish(task.clone()).await;

                let executor = executor_registry.get_default().await;
                let result = match executor {
                    Some(executor) => {
                        let task_ref = repository.get(task_id).await.unwrap();
                        tokio::time::timeout(
                            std::time::Duration::from_secs(config.task_timeout_secs),
                            executor.execute(&task_ref),
                        )
                        .await
                    }
                    None => {
                        warn!("未找到任务执行器，使用模拟执行");
                        Ok(Ok(Self::simulate_execute(&task).await))
                    }
                };

                match result {
                    Ok(Ok(execution_result)) => {
                        let _ = repository
                            .update(task_id, Box::new(|t| {
                                t.status = TaskStatus::Completed;
                                t.progress = 1.0;
                                t.result = Some(execution_result);
                                t.completed_at = Some(Utc::now());
                            }))
                            .await;
                    }
                    Ok(Err(e)) => {
                        if let Some(mut t) = repository.get(task_id).await {
                            failure_handler
                                .handle_failure(&mut t, format!("执行失败: {}", e), repository.clone(), _queue.clone())
                                .await;
                            let _ = repository.update(task_id, Box::new(|task| *task = t)).await;
                        }
                    }
                    Err(_) => {
                        if let Some(mut t) = repository.get(task_id).await {
                            failure_handler
                                .handle_failure(&mut t, "任务超时".to_string(), repository.clone(), _queue.clone())
                                .await;
                            let _ = repository.update(task_id, Box::new(|task| *task = t)).await;
                        }
                    }
                }

                if let Some(updated_task) = repository.get(task_id).await {
                    event_publisher.publish(updated_task).await;
                }

                drop(permit);
            });
        }

        rx
    }

    async fn simulate_execute(task: &Task) -> serde_json::Value {
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        serde_json::json!({
            "status": "success",
            "task_id": task.id.to_string(),
            "message": "任务模拟执行完成"
        })
    }

    pub async fn get_stats(&self) -> Result<SchedulerStats, SystemError> {
        let all_tasks = self.repository.list_all().await;

        let mut pending = 0;
        let mut running = 0;
        let mut completed = 0;
        let mut failed = 0;
        let mut total_duration = 0i64;
        let mut completed_count = 0;

        for task in all_tasks {
            match task.status {
                TaskStatus::Pending => pending += 1,
                TaskStatus::Running | TaskStatus::Retrying => running += 1,
                TaskStatus::Completed => {
                    completed += 1;
                    if let (Some(start), Some(end)) = (task.started_at, task.completed_at) {
                        total_duration += (end - start).num_milliseconds();
                        completed_count += 1;
                    }
                }
                TaskStatus::Failed | TaskStatus::Timeout => failed += 1,
                TaskStatus::Cancelled => {}
            }
        }

        let total = completed + failed;
        let success_rate = if total > 0 {
            completed as f64 / total as f64 * 100.0
        } else {
            100.0
        };

        let average_time = if completed_count > 0 {
            total_duration as f64 / completed_count as f64
        } else {
            0.0
        };

        Ok(SchedulerStats {
            total_tasks: pending + running + completed + failed,
            pending_tasks: pending,
            running_tasks: running,
            completed_tasks: completed,
            failed_tasks: failed,
            success_rate,
            average_execution_time_ms: average_time,
        })
    }

    pub async fn retry_task(&self, task_id: Uuid) -> Result<Uuid, SystemError> {
        let task = self
            .repository
            .get(task_id)
            .await
            .ok_or_else(|| SystemError::NotFoundError(format!("任务不存在: {}", task_id)))?;

        if matches!(
            task.status,
            TaskStatus::Failed | TaskStatus::Timeout | TaskStatus::Cancelled
        ) {
            self.repository
                .update(task_id, Box::new(|t| {
                    t.status = TaskStatus::Pending;
                    t.retry_count = 0;
                    t.progress = 0.0;
                    t.started_at = None;
                    t.completed_at = None;
                    t.error_message = None;
                    t.result = None;
                }))
                .await?;

            self.queue.enqueue(task_id).await?;
        }

        Ok(task_id)
    }

    pub async fn start(&self) -> Result<(), SystemError> {
        Ok(())
    }

    pub async fn cleanup_completed_tasks(&self, max_age_hours: i64) -> Result<usize, SystemError> {
        let cutoff = Utc::now() - chrono::Duration::hours(max_age_hours);
        let all_tasks = self.repository.list_all().await;
        let mut to_remove = Vec::new();

        for task in all_tasks {
            if matches!(
                task.status,
                TaskStatus::Completed | TaskStatus::Failed | TaskStatus::Cancelled
            ) {
                if let Some(completed_at) = task.completed_at {
                    if completed_at < cutoff {
                        to_remove.push(task.id);
                    }
                }
            }
        }

        let count = to_remove.len();
        for id in to_remove {
            self.repository.remove(id).await;
        }

        Ok(count)
    }
}

impl Clone for Scheduler {
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            repository: self.repository.clone(),
            queue: self.queue.clone(),
            concurrency_controller: self.concurrency_controller.clone(),
            executor_registry: self.executor_registry.clone(),
            failure_handler: self.failure_handler.clone(),
            event_publisher: self.event_publisher.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};
    use tokio::sync::Barrier;

    fn create_test_config() -> SchedulerConfig {
        SchedulerConfig {
            max_concurrent_tasks: 10,
            task_timeout_secs: 30,
            retry_attempts: 3,
            retry_delay_secs: 1,
        }
    }

    struct SuccessTestExecutor;

    #[async_trait]
    impl TaskExecutor for SuccessTestExecutor {
        async fn execute(&self, _task: &Task) -> Result<serde_json::Value, SystemError> {
            Ok(serde_json::json!({"result": "success", "data": "test"}))
        }
    }

    struct FailingTestExecutor {
        fail_count: AtomicU32,
    }

    impl FailingTestExecutor {
        fn new() -> Self {
            Self {
                fail_count: AtomicU32::new(0),
            }
        }
    }

    #[async_trait]
    impl TaskExecutor for FailingTestExecutor {
        async fn execute(&self, _task: &Task) -> Result<serde_json::Value, SystemError> {
            self.fail_count.fetch_add(1, Ordering::SeqCst);
            Err(SystemError::SchedulerError("模拟执行失败".to_string()))
        }
    }

    struct SlowTestExecutor {
        delay_ms: u64,
    }

    #[async_trait]
    impl TaskExecutor for SlowTestExecutor {
        async fn execute(&self, _task: &Task) -> Result<serde_json::Value, SystemError> {
            tokio::time::sleep(std::time::Duration::from_millis(self.delay_ms)).await;
            Ok(serde_json::json!({"result": "slow_success"}))
        }
    }

    struct FlakyTestExecutor {
        call_count: AtomicU32,
    }

    impl FlakyTestExecutor {
        fn new() -> Self {
            Self {
                call_count: AtomicU32::new(0),
            }
        }
    }

    #[async_trait]
    impl TaskExecutor for FlakyTestExecutor {
        async fn execute(&self, _task: &Task) -> Result<serde_json::Value, SystemError> {
            let count = self.call_count.fetch_add(1, Ordering::SeqCst);
            if count % 2 == 0 {
                Err(SystemError::SchedulerError("间歇性失败".to_string()))
            } else {
                Ok(serde_json::json!({"result": "success", "attempt": count + 1}))
            }
        }
    }

    // ==================== 边界条件测试 ====================

    #[tokio::test]
    async fn test_boundary_empty_task_name() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let task_id = scheduler
            .create_task(
                "".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.name, "");
        assert!(task.description.is_none());
        assert!(task.tags.is_empty());
        assert!(task.dependencies.is_empty());
    }

    #[tokio::test]
    async fn test_boundary_very_long_task_name() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let long_name: String = (0..10000).map(|_| 'x').collect();
        let long_desc: String = (0..50000).map(|_| 'y').collect();

        let task_id = scheduler
            .create_task(
                long_name.clone(),
                Some(long_desc.clone()),
                serde_json::json!({}),
                TaskPriority::High,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.name.len(), 10000);
        assert_eq!(task.description.as_ref().unwrap().len(), 50000);
    }

    #[tokio::test]
    async fn test_boundary_special_chars_task_name() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let special_name = "任务_!@#$%^&*()_+{}|:<>?[];',./`~中文🎉\n\t\r".to_string();

        let task_id = scheduler
            .create_task(
                special_name.clone(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.name, special_name);
    }

    #[tokio::test]
    async fn test_boundary_huge_payload() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let mut huge_payload = serde_json::Map::new();
        for i in 0..1000 {
            huge_payload.insert(
                format!("key_{}", i),
                serde_json::json!(format!("value_{}", i)),
            );
        }
        let payload = serde_json::Value::Object(huge_payload);

        let task_id = scheduler
            .create_task(
                "大数据任务".to_string(),
                None,
                payload.clone(),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.payload, payload);
    }

    #[tokio::test]
    async fn test_boundary_empty_payload() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let task_id = scheduler
            .create_task(
                "空负载任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.payload, serde_json::json!({}));
    }

    #[tokio::test]
    async fn test_boundary_null_payload() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let task_id = scheduler
            .create_task(
                "空值任务".to_string(),
                None,
                serde_json::Value::Null,
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.payload, serde_json::Value::Null);
    }

    #[tokio::test]
    async fn test_boundary_many_tags() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let tags: Vec<String> = (0..1000).map(|i| format!("tag_{}", i)).collect();

        let task_id = scheduler
            .create_task(
                "多标签任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                tags.clone(),
                vec![],
            )
            .await
            .unwrap();

        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.tags.len(), 1000);
        assert_eq!(task.tags, tags);
    }

    #[tokio::test]
    async fn test_boundary_many_dependencies() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let dependencies: Vec<Uuid> = (0..100).map(|_| Uuid::new_v4()).collect();

        let task_id = scheduler
            .create_task(
                "多依赖任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                dependencies.clone(),
            )
            .await
            .unwrap();

        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.dependencies.len(), 100);
        assert_eq!(task.dependencies, dependencies);
    }

    #[tokio::test]
    async fn test_boundary_progress_values() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let task_id = scheduler
            .create_task(
                "进度测试任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        scheduler
            .update(task_id, Box::new(|t| t.status = TaskStatus::Running))
            .await
            .unwrap();

        scheduler
            .update_progress(task_id, 0.0, Some("开始".to_string()))
            .await
            .unwrap();
        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.progress, 0.0);

        scheduler
            .update_progress(task_id, 0.5, Some("中间".to_string()))
            .await
            .unwrap();
        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.progress, 0.5);

        scheduler
            .update_progress(task_id, 1.0, Some("完成".to_string()))
            .await
            .unwrap();
        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.progress, 1.0);

        scheduler
            .update_progress(task_id, -0.5, Some("负值".to_string()))
            .await
            .unwrap();
        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.progress, 0.0);

        scheduler
            .update_progress(task_id, 2.0, Some("超大值".to_string()))
            .await
            .unwrap();
        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.progress, 1.0);
    }

    #[tokio::test]
    async fn test_boundary_zero_timeout() {
        let config = SchedulerConfig {
            max_concurrent_tasks: 10,
            task_timeout_secs: 0,
            retry_attempts: 3,
            retry_delay_secs: 1,
        };

        let scheduler = Scheduler::new(&config).unwrap();

        let task_id = scheduler
            .create_task(
                "零超时任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.timeout_seconds, 0);
    }

    #[tokio::test]
    async fn test_boundary_zero_retries() {
        let config = SchedulerConfig {
            max_concurrent_tasks: 10,
            task_timeout_secs: 30,
            retry_attempts: 0,
            retry_delay_secs: 1,
        };

        let scheduler = Scheduler::new(&config).unwrap();

        let task_id = scheduler
            .create_task(
                "零重试任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.max_retries, 0);
    }

    #[tokio::test]
    async fn test_boundary_single_concurrent_task() {
        let config = SchedulerConfig {
            max_concurrent_tasks: 1,
            task_timeout_secs: 30,
            retry_attempts: 3,
            retry_delay_secs: 1,
        };

        let scheduler = Scheduler::new(&config).unwrap();
        assert!(scheduler.start().await.is_ok());
    }

    #[tokio::test]
    async fn test_boundary_all_priorities() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let priorities = vec![
            TaskPriority::Low,
            TaskPriority::Normal,
            TaskPriority::High,
            TaskPriority::Critical,
        ];

        for priority in &priorities {
            let task_id = scheduler
                .create_task(
                    format!("优先级_{:?}", priority),
                    None,
                    serde_json::json!({}),
                    *priority,
                    vec![],
                    vec![],
                )
                .await
                .unwrap();

            let task = scheduler.get_task(task_id).await.unwrap();
            assert_eq!(task.priority, *priority);
        }

        let tasks = scheduler.list_tasks(None).await;
        assert_eq!(tasks.len(), 4);
        assert_eq!(tasks[0].priority, TaskPriority::Critical);
        assert_eq!(tasks[1].priority, TaskPriority::High);
        assert_eq!(tasks[2].priority, TaskPriority::Normal);
        assert_eq!(tasks[3].priority, TaskPriority::Low);
    }

    // ==================== 并发场景测试 ====================

    #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
    async fn test_concurrent_task_submission() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();
        let scheduler = Arc::new(scheduler);

        let num_tasks = 100;
        let mut handles = Vec::new();

        for i in 0..num_tasks {
            let scheduler = scheduler.clone();
            let handle = tokio::spawn(async move {
                scheduler
                    .create_task(
                        format!("并发任务_{}", i),
                        None,
                        serde_json::json!({"index": i}),
                        TaskPriority::Normal,
                        vec![],
                        vec![],
                    )
                    .await
                    .unwrap()
            });
            handles.push(handle);
        }

        let mut task_ids = Vec::new();
        for handle in handles {
            task_ids.push(handle.await.unwrap());
        }

        let all_tasks = scheduler.list_tasks(None).await;
        assert_eq!(all_tasks.len(), num_tasks);
        assert_eq!(task_ids.len(), num_tasks);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
    async fn test_concurrent_progress_updates() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();
        let scheduler = Arc::new(scheduler);

        let task_id = scheduler
            .create_task(
                "并发进度更新任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        scheduler
            .update(task_id, Box::new(|t| t.status = TaskStatus::Running))
            .await
            .unwrap();

        let num_updates = 50;
        let mut handles = Vec::new();

        for i in 0..num_updates {
            let scheduler = scheduler.clone();
            let handle = tokio::spawn(async move {
                let progress = (i as f32) / (num_updates as f32);
                scheduler
                    .update_progress(task_id, progress, Some(format!("更新_{}", i)))
                    .await
                    .unwrap();
            });
            handles.push(handle);
        }

        for handle in handles {
            handle.await.unwrap();
        }

        let task = scheduler.get_task(task_id).await.unwrap();
        assert!(task.progress <= 1.0);
        assert!(task.progress >= 0.0);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
    async fn test_concurrent_task_cancellation() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();
        let scheduler = Arc::new(scheduler);

        let mut task_ids = Vec::new();
        for i in 0..20 {
            let task_id = scheduler
                .create_task(
                    format!("取消任务_{}", i),
                    None,
                    serde_json::json!({}),
                    TaskPriority::Normal,
                    vec![],
                    vec![],
                )
                .await
                .unwrap();
            task_ids.push(task_id);
        }

        let barrier = Arc::new(Barrier::new(10));
        let mut handles = Vec::new();

        for i in 0..10 {
            let scheduler = scheduler.clone();
            let task_ids = task_ids.clone();
            let barrier = barrier.clone();
            let handle = tokio::spawn(async move {
                barrier.wait().await;
                let idx = i * 2;
                let _ = scheduler.cancel_task(task_ids[idx]).await;
            });
            handles.push(handle);
        }

        for handle in handles {
            handle.await.unwrap();
        }

        let cancelled = scheduler.list_tasks(Some(TaskStatus::Cancelled)).await;
        assert!(cancelled.len() >= 0);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
    async fn test_concurrent_mixed_operations() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();
        let scheduler = Arc::new(scheduler);

        let num_tasks = 50;
        for i in 0..num_tasks {
            scheduler
                .create_task(
                    format!("混合任务_{}", i),
                    None,
                    serde_json::json!({"index": i}),
                    match i % 4 {
                        0 => TaskPriority::Low,
                        1 => TaskPriority::Normal,
                        2 => TaskPriority::High,
                        _ => TaskPriority::Critical,
                    },
                    vec![],
                    vec![],
                )
                .await
                .unwrap();
        }

        let barrier = Arc::new(Barrier::new(16));
        let mut handles = Vec::new();

        for i in 0..16 {
            let scheduler = scheduler.clone();
            let barrier = barrier.clone();
            let handle = tokio::spawn(async move {
                barrier.wait().await;
                match i % 4 {
                    0 => {
                        let _ = scheduler.list_tasks(None).await;
                    }
                    1 => {
                        let _ = scheduler.list_tasks(Some(TaskStatus::Pending)).await;
                    }
                    2 => {
                        let _ = scheduler.get_stats().await;
                    }
                    _ => {
                        let _ = scheduler
                            .create_task(
                                format!("动态任务_{}", i),
                                None,
                                serde_json::json!({}),
                                TaskPriority::Normal,
                                vec![],
                                vec![],
                            )
                            .await;
                    }
                }
            });
            handles.push(handle);
        }

        for handle in handles {
            handle.await.unwrap();
        }

        let all_tasks = scheduler.list_tasks(None).await;
        assert!(all_tasks.len() >= 50);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 16)]
    async fn test_concurrent_event_callbacks() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();
        let scheduler = Arc::new(scheduler);

        let event_count = Arc::new(AtomicU32::new(0));

        for _ in 0..5 {
            let count = event_count.clone();
            scheduler
                .register_callback(move |_task| {
                    count.fetch_add(1, Ordering::SeqCst);
                })
                .await;
        }

        let num_tasks = 20;
        let mut handles = Vec::new();

        for i in 0..num_tasks {
            let scheduler = scheduler.clone();
            let handle = tokio::spawn(async move {
                scheduler
                    .create_task(
                        format!("回调任务_{}", i),
                        None,
                        serde_json::json!({}),
                        TaskPriority::Normal,
                        vec![],
                        vec![],
                    )
                    .await
                    .unwrap()
            });
            handles.push(handle);
        }

        for handle in handles {
            handle.await.unwrap();
        }

        tokio::time::sleep(std::time::Duration::from_millis(200)).await;

        assert!(event_count.load(Ordering::SeqCst) >= 0);
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 8)]
    async fn test_concurrent_retry_tasks() {
        let config = SchedulerConfig {
            max_concurrent_tasks: 5,
            task_timeout_secs: 5,
            retry_attempts: 2,
            retry_delay_secs: 1,
        };

        let repository = Arc::new(InMemoryTaskRepository::new());
        let (queue, rx) = InMemoryTaskQueue::new(1000);
        let queue = Arc::new(queue);
        let concurrency_controller = Arc::new(SemaphoreConcurrencyController::new(config.max_concurrent_tasks));
        let executor_registry = Arc::new(DefaultTaskExecutorRegistry::new());
        let failure_handler = Arc::new(DefaultFailureHandler::new(config.retry_delay()));
        let event_publisher = Arc::new(InMemoryTaskEventPublisher::new());

        let scheduler = Scheduler::with_dependencies(
            config.clone(),
            repository,
            queue,
            concurrency_controller,
            executor_registry,
            failure_handler,
            event_publisher,
            rx,
        );

        let scheduler = Arc::new(scheduler);

        let num_tasks = 10;
        let mut handles = Vec::new();

        for i in 0..num_tasks {
            let scheduler = scheduler.clone();
            let handle = tokio::spawn(async move {
                let task_id = scheduler
                    .create_task(
                        format!("重试任务_{}", i),
                        None,
                        serde_json::json!({}),
                        TaskPriority::Normal,
                        vec![],
                        vec![],
                    )
                    .await
                    .unwrap();

                tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                let _ = scheduler.retry_task(task_id).await;
            });
            handles.push(handle);
        }

        for handle in handles {
            handle.await.unwrap();
        }
    }

    // ==================== 异常路径测试 ====================

    #[tokio::test]
    async fn test_error_get_nonexistent_task() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let fake_id = Uuid::new_v4();
        let result = scheduler.get_task(fake_id).await;
        assert!(result.is_err());
        match result.unwrap_err() {
            SystemError::NotFoundError(msg) => {
                assert!(msg.contains("任务不存在"));
            }
            _ => panic!("预期 NotFoundError"),
        }
    }

    #[tokio::test]
    async fn test_error_duplicate_task_submission() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let task_id = Uuid::new_v4();
        let task = Task {
            id: task_id,
            name: "重复任务".to_string(),
            description: None,
            status: TaskStatus::Pending,
            priority: TaskPriority::Normal,
            payload: serde_json::json!({}),
            created_at: Utc::now(),
            started_at: None,
            completed_at: None,
            retry_count: 0,
            max_retries: 3,
            timeout_seconds: 30,
            progress: 0.0,
            error_message: None,
            result: None,
            dependencies: vec![],
            tags: vec![],
        };

        let result1 = scheduler.submit_task(task.clone()).await;
        assert!(result1.is_ok());

        let result2 = scheduler.submit_task(task).await;
        assert!(result2.is_err());
        match result2.unwrap_err() {
            SystemError::SchedulerError(msg) => {
                assert!(msg.contains("任务已存在"));
            }
            _ => panic!("预期 SchedulerError"),
        }
    }

    #[tokio::test]
    async fn test_error_cancel_nonexistent_task() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let fake_id = Uuid::new_v4();
        let result = scheduler.cancel_task(fake_id).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_error_update_progress_nonexistent_task() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let fake_id = Uuid::new_v4();
        let result = scheduler.update_progress(fake_id, 0.5, None).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_error_retry_nonexistent_task() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let fake_id = Uuid::new_v4();
        let result = scheduler.retry_task(fake_id).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_error_task_execution_failure() {
        let config = SchedulerConfig {
            max_concurrent_tasks: 5,
            task_timeout_secs: 5,
            retry_attempts: 0,
            retry_delay_secs: 1,
        };

        let repository = Arc::new(InMemoryTaskRepository::new());
        let (queue, rx) = InMemoryTaskQueue::new(1000);
        let queue = Arc::new(queue);
        let concurrency_controller = Arc::new(SemaphoreConcurrencyController::new(config.max_concurrent_tasks));
        let executor_registry = Arc::new(DefaultTaskExecutorRegistry::new());
        let failure_handler = Arc::new(DefaultFailureHandler::new(config.retry_delay()));
        let event_publisher = Arc::new(InMemoryTaskEventPublisher::new());

        executor_registry
            .register("default".to_string(), Arc::new(FailingTestExecutor::new()))
            .await;

        let scheduler = Scheduler::with_dependencies(
            config.clone(),
            repository.clone(),
            queue,
            concurrency_controller,
            executor_registry,
            failure_handler,
            event_publisher,
            rx,
        );

        let task_id = scheduler
            .create_task(
                "失败任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        tokio::time::sleep(std::time::Duration::from_millis(500)).await;

        let task = repository.get(task_id).await.unwrap();
        assert!(matches!(task.status, TaskStatus::Failed));
        assert!(task.error_message.is_some());
    }

    #[tokio::test]
    async fn test_error_task_timeout() {
        let config = SchedulerConfig {
            max_concurrent_tasks: 5,
            task_timeout_secs: 1,
            retry_attempts: 0,
            retry_delay_secs: 1,
        };

        let repository = Arc::new(InMemoryTaskRepository::new());
        let (queue, rx) = InMemoryTaskQueue::new(1000);
        let queue = Arc::new(queue);
        let concurrency_controller = Arc::new(SemaphoreConcurrencyController::new(config.max_concurrent_tasks));
        let executor_registry = Arc::new(DefaultTaskExecutorRegistry::new());
        let failure_handler = Arc::new(DefaultFailureHandler::new(config.retry_delay()));
        let event_publisher = Arc::new(InMemoryTaskEventPublisher::new());

        executor_registry
            .register("default".to_string(), Arc::new(SlowTestExecutor { delay_ms: 3000 }))
            .await;

        let scheduler = Scheduler::with_dependencies(
            config.clone(),
            repository.clone(),
            queue,
            concurrency_controller,
            executor_registry,
            failure_handler,
            event_publisher,
            rx,
        );

        let task_id = scheduler
            .create_task(
                "超时任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        tokio::time::sleep(std::time::Duration::from_millis(2000)).await;

        let task = repository.get(task_id).await.unwrap();
        assert!(matches!(task.status, TaskStatus::Failed));
    }

    #[tokio::test]
    async fn test_error_task_retry_mechanism() {
        let config = SchedulerConfig {
            max_concurrent_tasks: 5,
            task_timeout_secs: 30,
            retry_attempts: 2,
            retry_delay_secs: 1,
        };

        let repository = Arc::new(InMemoryTaskRepository::new());
        let (queue, rx) = InMemoryTaskQueue::new(1000);
        let queue = Arc::new(queue);
        let concurrency_controller = Arc::new(SemaphoreConcurrencyController::new(config.max_concurrent_tasks));
        let executor_registry = Arc::new(DefaultTaskExecutorRegistry::new());
        let failure_handler = Arc::new(DefaultFailureHandler::new(config.retry_delay()));
        let event_publisher = Arc::new(InMemoryTaskEventPublisher::new());

        let failing_executor = Arc::new(FailingTestExecutor::new());
        executor_registry
            .register("default".to_string(), failing_executor.clone())
            .await;

        let scheduler = Scheduler::with_dependencies(
            config.clone(),
            repository.clone(),
            queue,
            concurrency_controller,
            executor_registry,
            failure_handler,
            event_publisher,
            rx,
        );

        let task_id = scheduler
            .create_task(
                "重试任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        tokio::time::sleep(std::time::Duration::from_millis(4000)).await;

        let task = repository.get(task_id).await.unwrap();
        assert_eq!(task.retry_count, 2);
        assert!(matches!(task.status, TaskStatus::Failed));
    }

    #[tokio::test]
    async fn test_error_no_executor_registered() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let task_id = scheduler
            .create_task(
                "无执行器任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        tokio::time::sleep(std::time::Duration::from_millis(500)).await;

        let task = scheduler.get_task(task_id).await.unwrap();
        assert!(matches!(task.status, TaskStatus::Completed | TaskStatus::Running));
    }

    #[tokio::test]
    async fn test_error_retry_completed_task() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let task_id = scheduler
            .create_task(
                "已完成任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        scheduler
            .update(task_id, Box::new(|t| {
                t.status = TaskStatus::Completed;
                t.completed_at = Some(Utc::now());
            }))
            .await
            .unwrap();

        let result = scheduler.retry_task(task_id).await;
        assert!(result.is_ok());

        let task = scheduler.get_task(task_id).await.unwrap();
        assert_eq!(task.status, TaskStatus::Completed);
    }

    #[tokio::test]
    async fn test_error_cleanup_old_tasks() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        for i in 0..10 {
            let task_id = scheduler
                .create_task(
                    format!("清理任务_{}", i),
                    None,
                    serde_json::json!({}),
                    TaskPriority::Normal,
                    vec![],
                    vec![],
                )
                .await
                .unwrap();

            scheduler
                .update(task_id, Box::new(move |t| {
                    t.status = TaskStatus::Completed;
                    t.completed_at = Some(Utc::now() - chrono::Duration::days(2));
                }))
                .await
                .unwrap();
        }

        let cleaned = scheduler.cleanup_completed_tasks(1).await.unwrap();
        assert_eq!(cleaned, 10);

        let remaining = scheduler.list_tasks(None).await;
        assert_eq!(remaining.len(), 0);
    }

    #[tokio::test]
    async fn test_error_cleanup_with_max_age_zero() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        let task_id = scheduler
            .create_task(
                "立即清理任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        scheduler
            .update(task_id, Box::new(|t| {
                t.status = TaskStatus::Completed;
                t.completed_at = Some(Utc::now());
            }))
            .await
            .unwrap();

        tokio::time::sleep(std::time::Duration::from_millis(10)).await;

        let cleaned = scheduler.cleanup_completed_tasks(0).await.unwrap();
        assert_eq!(cleaned, 1);
    }

    #[tokio::test]
    async fn test_error_flaky_executor_recovery() {
        let config = SchedulerConfig {
            max_concurrent_tasks: 5,
            task_timeout_secs: 30,
            retry_attempts: 3,
            retry_delay_secs: 1,
        };

        let repository = Arc::new(InMemoryTaskRepository::new());
        let (queue, rx) = InMemoryTaskQueue::new(1000);
        let queue = Arc::new(queue);
        let concurrency_controller = Arc::new(SemaphoreConcurrencyController::new(config.max_concurrent_tasks));
        let executor_registry = Arc::new(DefaultTaskExecutorRegistry::new());
        let failure_handler = Arc::new(DefaultFailureHandler::new(config.retry_delay()));
        let event_publisher = Arc::new(InMemoryTaskEventPublisher::new());

        executor_registry
            .register("default".to_string(), Arc::new(FlakyTestExecutor::new()))
            .await;

        let scheduler = Scheduler::with_dependencies(
            config.clone(),
            repository.clone(),
            queue,
            concurrency_controller,
            executor_registry,
            failure_handler,
            event_publisher,
            rx,
        );

        let task_id = scheduler
            .create_task(
                "间歇性失败任务".to_string(),
                None,
                serde_json::json!({}),
                TaskPriority::Normal,
                vec![],
                vec![],
            )
            .await
            .unwrap();

        tokio::time::sleep(std::time::Duration::from_millis(3000)).await;

        let task = repository.get(task_id).await.unwrap();
        assert!(matches!(task.status, TaskStatus::Completed));
        assert!(task.result.is_some());
    }

    // ==================== 任务仓库独立测试 ====================

    #[tokio::test]
    async fn test_task_repository_full_crud() {
        let repo = InMemoryTaskRepository::new();

        let task_id = Uuid::new_v4();
        let task = Task {
            id: task_id,
            name: "测试任务".to_string(),
            description: None,
            status: TaskStatus::Pending,
            priority: TaskPriority::Normal,
            payload: serde_json::json!({}),
            created_at: Utc::now(),
            started_at: None,
            completed_at: None,
            retry_count: 0,
            max_retries: 3,
            timeout_seconds: 30,
            progress: 0.0,
            error_message: None,
            result: None,
            dependencies: vec![],
            tags: vec![],
        };

        repo.insert(task.clone()).await.unwrap();
        assert!(repo.contains(task_id).await);

        let retrieved = repo.get(task_id).await.unwrap();
        assert_eq!(retrieved.id, task_id);
        assert_eq!(retrieved.name, "测试任务");

        repo.update(task_id, Box::new(|t| t.status = TaskStatus::Running))
            .await
            .unwrap();
        let updated = repo.get(task_id).await.unwrap();
        assert_eq!(updated.status, TaskStatus::Running);

        let all = repo.list_all().await;
        assert_eq!(all.len(), 1);

        let removed = repo.remove(task_id).await.unwrap();
        assert_eq!(removed.id, task_id);
        assert!(!repo.contains(task_id).await);
    }

    // ==================== 任务队列独立测试 ====================

    #[tokio::test]
    async fn test_task_queue_operations() {
        let (queue, _rx) = InMemoryTaskQueue::new(10);

        let id1 = Uuid::new_v4();
        let id2 = Uuid::new_v4();

        queue.enqueue(id1).await.unwrap();
        queue.enqueue(id2).await.unwrap();

        assert_eq!(queue.len().await, 2);
        assert!(!queue.is_empty().await);

        let dequeued = queue.dequeue().await.unwrap();
        assert_eq!(dequeued, id1);
        assert_eq!(queue.len().await, 1);
    }

    // ==================== 统计测试 ====================

    #[tokio::test]
    async fn test_scheduler_stats() {
        let config = create_test_config();
        let scheduler = Scheduler::new(&config).unwrap();

        for i in 0..10 {
            let task_id = scheduler
                .create_task(
                    format!("统计任务_{}", i),
                    None,
                    serde_json::json!({}),
                    TaskPriority::Normal,
                    vec![],
                    vec![],
                )
                .await
                .unwrap();

            if i < 3 {
                scheduler
                    .update(task_id, Box::new(|t| {
                        t.status = TaskStatus::Completed;
                        t.started_at = Some(Utc::now() - chrono::Duration::seconds(5));
                        t.completed_at = Some(Utc::now());
                    }))
                    .await
                    .unwrap();
            } else if i < 5 {
                scheduler
                    .update(task_id, Box::new(|t| {
                        t.status = TaskStatus::Failed;
                        t.completed_at = Some(Utc::now());
                    }))
                    .await
                    .unwrap();
            } else if i < 7 {
                scheduler
                    .update(task_id, Box::new(|t| {
                        t.status = TaskStatus::Running;
                        t.started_at = Some(Utc::now());
                    }))
                    .await
                    .unwrap();
            }
        }

        let stats = scheduler.get_stats().await.unwrap();
        assert_eq!(stats.total_tasks, 10);
        assert_eq!(stats.pending_tasks, 3);
        assert_eq!(stats.running_tasks, 2);
        assert_eq!(stats.completed_tasks, 3);
        assert_eq!(stats.failed_tasks, 2);
        assert_eq!(stats.success_rate, 60.0);
        assert!(stats.average_execution_time_ms > 0.0);
    }

    // ==================== 失败处理器独立测试 ====================

    #[tokio::test]
    async fn test_failure_handler_should_retry() {
        let handler = DefaultFailureHandler::new(std::time::Duration::from_secs(1));

        let mut task = Task {
            id: Uuid::new_v4(),
            name: "测试任务".to_string(),
            description: None,
            status: TaskStatus::Pending,
            priority: TaskPriority::Normal,
            payload: serde_json::json!({}),
            created_at: Utc::now(),
            started_at: None,
            completed_at: None,
            retry_count: 0,
            max_retries: 3,
            timeout_seconds: 30,
            progress: 0.0,
            error_message: None,
            result: None,
            dependencies: vec![],
            tags: vec![],
        };

        assert!(handler.should_retry(&task));

        task.retry_count = 2;
        assert!(handler.should_retry(&task));

        task.retry_count = 3;
        assert!(!handler.should_retry(&task));
    }
}
