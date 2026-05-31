use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{anyhow, Result};
use chrono::{DateTime, Utc};
use dashmap::DashMap;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use tokio::sync::{mpsc, Semaphore};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScheduledTask {
    pub task_id: String,
    pub name: String,
    pub description: String,
    pub schedule: TaskSchedule,
    pub task_type: TaskType,
    pub payload: serde_json::Value,
    pub status: TaskStatus,
    pub priority: TaskPriority,
    pub max_retries: u32,
    pub retry_count: u32,
    pub timeout_seconds: u64,
    pub last_run_at: Option<DateTime<Utc>>,
    pub next_run_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum TaskSchedule {
    OneTime(DateTime<Utc>),
    Interval(Duration),
    Cron(String),
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum TaskType {
    System,
    Tenant(String),
    Custom,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum TaskStatus {
    Pending,
    Scheduled,
    Running,
    Completed,
    Failed,
    Cancelled,
    Paused,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
pub enum TaskPriority {
    Low,
    Medium,
    High,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskExecution {
    pub execution_id: String,
    pub task_id: String,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub status: TaskStatus,
    pub result: Option<serde_json::Value>,
    pub error: Option<String>,
    pub duration_ms: Option<u64>,
}

#[derive(Debug, Clone)]
pub struct TaskEvent {
    pub event_id: String,
    pub task_id: String,
    pub event_type: TaskEventType,
    pub timestamp: DateTime<Utc>,
    pub details: Option<serde_json::Value>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TaskEventType {
    Created,
    Updated,
    Scheduled,
    Started,
    Completed,
    Failed,
    Cancelled,
    Paused,
    Resumed,
}

type TaskHandler = Arc<dyn Fn(ScheduledTask) -> Result<serde_json::Value> + Send + Sync>;
type TaskEventHandler = Arc<dyn Fn(TaskEvent) -> Result<()> + Send + Sync>;

pub struct TaskScheduler {
    tasks: DashMap<String, ScheduledTask>,
    executions: DashMap<String, Vec<TaskExecution>>,
    handlers: DashMap<String, TaskHandler>,
    event_handlers: RwLock<Vec<TaskEventHandler>>,
    shutdown_tx: Option<mpsc::Sender<()>>,
    semaphore: Arc<Semaphore>,
    max_concurrent_tasks: usize,
}

impl TaskScheduler {
    pub fn new() -> Self {
        Self::with_max_concurrent_tasks(100)
    }

    pub fn with_max_concurrent_tasks(max_concurrent: usize) -> Self {
        Self {
            tasks: DashMap::new(),
            executions: DashMap::new(),
            handlers: DashMap::new(),
            event_handlers: RwLock::new(Vec::new()),
            shutdown_tx: None,
            semaphore: Arc::new(Semaphore::new(max_concurrent)),
            max_concurrent_tasks: max_concurrent,
        }
    }

    pub fn register_event_handler<F>(&self, handler: F)
    where
        F: Fn(TaskEvent) -> Result<()> + Send + Sync + 'static,
    {
        self.event_handlers.write().push(Arc::new(handler));
    }

    fn notify_event_handlers(&self, event: TaskEvent) {
        let handlers = self.event_handlers.read();
        for handler in handlers.iter() {
            let event = event.clone();
            let handler = handler.clone();
            tokio::spawn(async move {
                if let Err(e) = handler(event) {
                    error!(error = %e, "Task event handler failed");
                }
            });
        }
    }

    pub fn register_handler<F>(&self, task_type: &str, handler: F)
    where
        F: Fn(ScheduledTask) -> Result<serde_json::Value> + Send + Sync + 'static,
    {
        self.handlers.insert(task_type.to_string(), Arc::new(handler));
    }

    pub fn create_task(&self, mut task: ScheduledTask) -> Result<ScheduledTask> {
        if task.task_id.is_empty() {
            task.task_id = format!("task_{}", Uuid::new_v4().simple());
        }
        
        let now = Utc::now();
        task.created_at = now;
        task.updated_at = now;
        task.status = TaskStatus::Scheduled;
        task.next_run_at = self.calculate_next_run(&task.schedule, now);
        
        self.tasks.insert(task.task_id.clone(), task.clone());
        self.executions.insert(task.task_id.clone(), Vec::new());
        
        self.notify_event_handlers(TaskEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            task_id: task.task_id.clone(),
            event_type: TaskEventType::Created,
            timestamp: now,
            details: Some(serde_json::json!({ "name": task.name })),
        });
        
        info!("Created task: {} ({})", task.name, task.task_id);
        Ok(task)
    }

    fn calculate_next_run(
        &self,
        schedule: &TaskSchedule,
        from: DateTime<Utc>,
    ) -> Option<DateTime<Utc>> {
        match schedule {
            TaskSchedule::OneTime(datetime) => {
                if *datetime > from {
                    Some(*datetime)
                } else {
                    None
                }
            }
            TaskSchedule::Interval(duration) => {
                Some(from + chrono::Duration::from_std(*duration).unwrap())
            }
            TaskSchedule::Cron(_expr) => {
                Some(from + chrono::Duration::minutes(1))
            }
        }
    }

    pub fn get_task(&self, task_id: &str) -> Option<ScheduledTask> {
        self.tasks.get(task_id).map(|t| t.clone())
    }

    pub fn list_tasks(&self) -> Vec<ScheduledTask> {
        self.tasks.iter().map(|t| t.clone()).collect()
    }

    pub fn list_tasks_by_status(&self, status: TaskStatus) -> Vec<ScheduledTask> {
        self.tasks
            .iter()
            .filter(|t| t.status == status)
            .map(|t| t.clone())
            .collect()
    }

    pub fn cancel_task(&self, task_id: &str) -> Result<()> {
        let mut task = self.tasks.get_mut(task_id)
            .ok_or_else(|| anyhow!("Task not found: {}", task_id))?;
        
        task.status = TaskStatus::Cancelled;
        task.updated_at = Utc::now();
        
        drop(task);
        
        self.notify_event_handlers(TaskEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            task_id: task_id.to_string(),
            event_type: TaskEventType::Cancelled,
            timestamp: Utc::now(),
            details: None,
        });
        
        info!("Cancelled task: {}", task_id);
        Ok(())
    }

    pub fn pause_task(&self, task_id: &str) -> Result<()> {
        let mut task = self.tasks.get_mut(task_id)
            .ok_or_else(|| anyhow!("Task not found: {}", task_id))?;
        
        if task.status == TaskStatus::Running {
            return Err(anyhow!("Cannot pause a running task"));
        }
        
        task.status = TaskStatus::Paused;
        task.updated_at = Utc::now();
        
        drop(task);
        
        self.notify_event_handlers(TaskEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            task_id: task_id.to_string(),
            event_type: TaskEventType::Paused,
            timestamp: Utc::now(),
            details: None,
        });
        
        info!("Paused task: {}", task_id);
        Ok(())
    }

    pub fn resume_task(&self, task_id: &str) -> Result<()> {
        let mut task = self.tasks.get_mut(task_id)
            .ok_or_else(|| anyhow!("Task not found: {}", task_id))?;
        
        if task.status != TaskStatus::Paused {
            return Err(anyhow!("Task is not paused"));
        }
        
        task.status = TaskStatus::Scheduled;
        task.updated_at = Utc::now();
        
        drop(task);
        
        self.notify_event_handlers(TaskEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            task_id: task_id.to_string(),
            event_type: TaskEventType::Resumed,
            timestamp: Utc::now(),
            details: None,
        });
        
        info!("Resumed task: {}", task_id);
        Ok(())
    }

    pub fn delete_task(&self, task_id: &str) -> Result<()> {
        if self.tasks.remove(task_id).is_some() {
            self.executions.remove(task_id);
            info!("Deleted task: {}", task_id);
            Ok(())
        } else {
            Err(anyhow!("Task not found: {}", task_id))
        }
    }

    pub fn get_task_executions(&self, task_id: &str) -> Vec<TaskExecution> {
        self.executions.get(task_id)
            .map(|e| e.clone())
            .unwrap_or_default()
    }

    pub async fn start(&mut self) -> Result<()> {
        let (tx, mut rx) = mpsc::channel::<()>(1);
        self.shutdown_tx = Some(tx);

        let tasks = self.tasks.clone();
        let executions = self.executions.clone();
        let handlers = self.handlers.clone();
        let event_handlers = self.event_handlers.clone();
        let semaphore = self.semaphore.clone();

        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(Duration::from_secs(1));
            
            loop {
                tokio::select! {
                    _ = ticker.tick() => {
                        let now = Utc::now();
                        
                        let mut due_tasks: Vec<ScheduledTask> = Vec::new();
                        for task in tasks.iter() {
                            if task.status == TaskStatus::Scheduled {
                                if let Some(next_run) = task.next_run_at {
                                    if next_run <= now {
                                        due_tasks.push(task.clone());
                                    }
                                }
                            }
                        }
                        
                        due_tasks.sort_by(|a, b| b.priority.cmp(&a.priority));
                        
                        for task in due_tasks {
                            let permit = semaphore.clone().try_acquire_owned();
                            if permit.is_err() {
                                debug!("No available permit for task: {}", task.task_id);
                                continue;
                            }
                            
                            let permit = permit.unwrap();
                            let task_clone = task.clone();
                            let tasks_clone = tasks.clone();
                            let executions_clone = executions.clone();
                            let handlers_clone = handlers.clone();
                            let event_handlers_clone = event_handlers.clone();
                            
                            tokio::spawn(async move {
                                let _permit = permit;
                                Self::execute_task(
                                    task_clone,
                                    tasks_clone,
                                    executions_clone,
                                    handlers_clone,
                                    event_handlers_clone,
                                ).await;
                            });
                        }
                    }
                    _ = rx.recv() => {
                        info!("Task scheduler shutting down");
                        break;
                    }
                }
            }
        });

        info!("Task scheduler started with max {} concurrent tasks", self.max_concurrent_tasks);
        Ok(())
    }

    async fn execute_task(
        task: ScheduledTask,
        tasks: DashMap<String, ScheduledTask>,
        executions: DashMap<String, Vec<TaskExecution>>,
        handlers: DashMap<String, TaskHandler>,
        event_handlers: RwLock<Vec<TaskEventHandler>>,
    ) {
        let task_id = task.task_id.clone();
        debug!("Executing task: {} ({})", task.name, task_id);
        
        if let Some(mut task_mut) = tasks.get_mut(&task_id) {
            task_mut.status = TaskStatus::Running;
            task_mut.last_run_at = Some(Utc::now());
        }
        
        let execution_id = format!("exec_{}", Uuid::new_v4().simple());
        let mut execution = TaskExecution {
            execution_id: execution_id.clone(),
            task_id: task_id.clone(),
            started_at: Utc::now(),
            completed_at: None,
            status: TaskStatus::Running,
            result: None,
            error: None,
            duration_ms: None,
        };
        
        Self::notify_event_handlers_static(&event_handlers, TaskEvent {
            event_id: format!("evt_{}", Uuid::new_v4().simple()),
            task_id: task_id.clone(),
            event_type: TaskEventType::Started,
            timestamp: Utc::now(),
            details: Some(serde_json::json!({ "execution_id": execution_id })),
        });
        
        let result = if let Some(handler) = handlers.get("default") {
            handler(task.clone())
        } else {
            Self::default_handler(&task)
        };
        
        let now = Utc::now();
        execution.completed_at = Some(now);
        execution.duration_ms = Some(
            (now - execution.started_at).num_milliseconds() as u64
        );
        
        match result {
            Ok(output) => {
                execution.status = TaskStatus::Completed;
                execution.result = Some(output);
                info!(
                    "Task completed: {} ({}) in {}ms",
                    task.name, task_id, execution.duration_ms.unwrap_or(0)
                );
                
                if let Some(mut task_mut) = tasks.get_mut(&task_id) {
                    task_mut.status = match task.schedule {
                        TaskSchedule::OneTime(_) => TaskStatus::Completed,
                        _ => TaskStatus::Scheduled,
                    };
                    task_mut.next_run_at = if let TaskSchedule::Interval(dur) = task.schedule {
                        Some(now + chrono::Duration::from_std(dur).unwrap())
                    } else if let TaskSchedule::OneTime(_) = task.schedule {
                        None
                    } else {
                        Some(now + chrono::Duration::minutes(1))
                    };
                    task_mut.retry_count = 0;
                    task_mut.updated_at = now;
                }
                
                Self::notify_event_handlers_static(&event_handlers, TaskEvent {
                    event_id: format!("evt_{}", Uuid::new_v4().simple()),
                    task_id: task_id.clone(),
                    event_type: TaskEventType::Completed,
                    timestamp: now,
                    details: Some(serde_json::json!({ "execution_id": execution_id })),
                });
            }
            Err(e) => {
                execution.status = TaskStatus::Failed;
                execution.error = Some(e.to_string());
                warn!(
                    "Task failed: {} ({}): {}",
                    task.name, task_id, e
                );
                
                if let Some(mut task_mut) = tasks.get_mut(&task_id) {
                    task_mut.retry_count += 1;
                    
                    if task_mut.retry_count < task_mut.max_retries {
                        task_mut.status = TaskStatus::Scheduled;
                        task_mut.next_run_at = Some(
                            now + chrono::Duration::seconds(
                                2u64.pow(task_mut.retry_count).min(60) as i64
                            )
                        );
                        info!(
                            "Scheduling retry {} for task: {}",
                            task_mut.retry_count, task_id
                        );
                    } else {
                        task_mut.status = TaskStatus::Failed;
                        task_mut.next_run_at = None;
                    }
                    
                    task_mut.updated_at = now;
                }
                
                Self::notify_event_handlers_static(&event_handlers, TaskEvent {
                    event_id: format!("evt_{}", Uuid::new_v4().simple()),
                    task_id: task_id.clone(),
                    event_type: TaskEventType::Failed,
                    timestamp: now,
                    details: Some(serde_json::json!({
                        "execution_id": execution_id,
                        "error": e.to_string()
                    })),
                });
            }
        }
        
        if let Some(mut exec_list) = executions.get_mut(&task_id) {
            exec_list.push(execution);
        }
    }

    fn notify_event_handlers_static(
        handlers: &RwLock<Vec<TaskEventHandler>>,
        event: TaskEvent,
    ) {
        let handlers = handlers.read();
        for handler in handlers.iter() {
            let event = event.clone();
            let handler = handler.clone();
            tokio::spawn(async move {
                if let Err(e) = handler(event) {
                    error!(error = %e, "Task event handler failed");
                }
            });
        }
    }

    fn default_handler(task: &ScheduledTask) -> Result<serde_json::Value> {
        debug!("Executing default handler for task: {}", task.name);
        Ok(serde_json::json!({
            "task_id": task.task_id,
            "processed": true,
            "timestamp": Utc::now().to_rfc3339()
        }))
    }

    pub fn stop(&mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            drop(tx);
        }
    }
}

impl Default for TaskScheduler {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for TaskScheduler {
    fn drop(&mut self) {
        self.stop();
    }
}

pub fn create_interval_task(
    name: String,
    description: String,
    interval: Duration,
    payload: serde_json::Value,
) -> ScheduledTask {
    ScheduledTask {
        task_id: String::new(),
        name,
        description,
        schedule: TaskSchedule::Interval(interval),
        task_type: TaskType::System,
        payload,
        status: TaskStatus::Pending,
        priority: TaskPriority::Medium,
        max_retries: 3,
        retry_count: 0,
        timeout_seconds: 300,
        last_run_at: None,
        next_run_at: None,
        created_at: Utc::now(),
        updated_at: Utc::now(),
    }
}

pub fn create_onetime_task(
    name: String,
    description: String,
    run_at: DateTime<Utc>,
    payload: serde_json::Value,
) -> ScheduledTask {
    ScheduledTask {
        task_id: String::new(),
        name,
        description,
        schedule: TaskSchedule::OneTime(run_at),
        task_type: TaskType::System,
        payload,
        status: TaskStatus::Pending,
        priority: TaskPriority::Medium,
        max_retries: 3,
        retry_count: 0,
        timeout_seconds: 300,
        last_run_at: None,
        next_run_at: None,
        created_at: Utc::now(),
        updated_at: Utc::now(),
    }
}
