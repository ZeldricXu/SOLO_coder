use crate::types::{AppError, Event, generate_id, now_utc};
use crate::event_store::EventStore;
use chrono::{DateTime, Utc};
use cron::Schedule;
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tracing;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScheduledTask {
    pub task_id: String,
    pub name: String,
    pub description: String,
    pub task_type: TaskType,
    pub schedule: TaskSchedule,
    pub parameters: HashMap<String, serde_json::Value>,
    pub status: TaskStatus,
    pub last_run: Option<DateTime<Utc>>,
    pub next_run: Option<DateTime<Utc>>,
    pub last_error: Option<String>,
    pub retry_policy: RetryPolicy,
    pub timeout: Duration,
    pub enabled: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TaskType {
    LogArchive,
    MetricsExport,
    CacheInvalidation,
    DatabaseBackup,
    DataRetention,
    HealthCheck,
    ConfigurationSync,
    CertificateRotation,
    Custom,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum TaskSchedule {
    Cron { cron_expression: String },
    Interval { interval_seconds: u64 },
    Once { run_at: DateTime<Utc> },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TaskStatus {
    Pending,
    Running,
    Completed,
    Failed,
    Paused,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RetryPolicy {
    pub max_retries: u32,
    pub retry_delay_seconds: u64,
    pub exponential_backoff: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct TaskExecution {
    pub execution_id: String,
    pub task_id: String,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub status: TaskStatus,
    pub result: Option<serde_json::Value>,
    pub error: Option<String>,
    pub retry_count: u32,
}

pub struct TaskScheduler {
    tasks: Arc<DashMap<String, ScheduledTask>>,
    executions: Arc<DashMap<String, TaskExecution>>,
    running_tasks: Arc<DashMap<String, tokio::task::JoinHandle<()>>>,
    event_store: Arc<EventStore>,
    handlers: Arc<DashMap<TaskType, Box<dyn TaskHandler + Send + Sync>>>,
}

#[async_trait::async_trait]
pub trait TaskHandler {
    async fn execute(&self, task: &ScheduledTask) -> Result<serde_json::Value, AppError>;
}

struct DefaultTaskHandler;

#[async_trait::async_trait]
impl TaskHandler for DefaultTaskHandler {
    async fn execute(&self, task: &ScheduledTask) -> Result<serde_json::Value, AppError> {
        match task.task_type {
            TaskType::LogArchive => {
                tracing::info!(task_id = %task.task_id, "执行日志归档任务");
                Ok(serde_json::json!({ "status": "completed", "archived": 0 }))
            }
            TaskType::MetricsExport => {
                tracing::info!(task_id = %task.task_id, "执行指标导出任务");
                Ok(serde_json::json!({ "status": "completed", "exported": 0 }))
            }
            TaskType::CacheInvalidation => {
                tracing::info!(task_id = %task.task_id, "执行缓存失效任务");
                Ok(serde_json::json!({ "status": "completed", "invalidated": 0 }))
            }
            TaskType::DatabaseBackup => {
                tracing::info!(task_id = %task.task_id, "执行数据库备份任务");
                Ok(serde_json::json!({ "status": "completed", "backup_size": "0MB" }))
            }
            TaskType::DataRetention => {
                tracing::info!(task_id = %task.task_id, "执行数据保留任务");
                Ok(serde_json::json!({ "status": "completed", "deleted": 0 }))
            }
            TaskType::HealthCheck => {
                tracing::info!(task_id = %task.task_id, "执行健康检查任务");
                Ok(serde_json::json!({ "status": "healthy" }))
            }
            TaskType::ConfigurationSync => {
                tracing::info!(task_id = %task.task_id, "执行配置同步任务");
                Ok(serde_json::json!({ "status": "completed", "synced": 0 }))
            }
            TaskType::CertificateRotation => {
                tracing::info!(task_id = %task.task_id, "执行证书轮换任务");
                Ok(serde_json::json!({ "status": "completed", "rotated": 0 }))
            }
            TaskType::Custom => {
                tracing::info!(task_id = %task.task_id, "执行自定义任务");
                Ok(serde_json::json!({ "status": "completed" }))
            }
        }
    }
}

impl TaskScheduler {
    pub fn new(event_store: Arc<EventStore>) -> Self {
        let handlers: DashMap<TaskType, Box<dyn TaskHandler + Send + Sync>> = DashMap::new();
        handlers.insert(TaskType::LogArchive, Box::new(DefaultTaskHandler));
        handlers.insert(TaskType::MetricsExport, Box::new(DefaultTaskHandler));
        handlers.insert(TaskType::CacheInvalidation, Box::new(DefaultTaskHandler));
        handlers.insert(TaskType::DatabaseBackup, Box::new(DefaultTaskHandler));
        handlers.insert(TaskType::DataRetention, Box::new(DefaultTaskHandler));
        handlers.insert(TaskType::HealthCheck, Box::new(DefaultTaskHandler));
        handlers.insert(TaskType::ConfigurationSync, Box::new(DefaultTaskHandler));
        handlers.insert(TaskType::CertificateRotation, Box::new(DefaultTaskHandler));
        handlers.insert(TaskType::Custom, Box::new(DefaultTaskHandler));

        let scheduler = Self {
            tasks: Arc::new(DashMap::new()),
            executions: Arc::new(DashMap::new()),
            running_tasks: Arc::new(DashMap::new()),
            event_store,
            handlers: Arc::new(handlers),
        };

        scheduler.start_scheduler_loop();
        scheduler
    }

    pub fn create_task(&self, create: TaskCreate) -> Result<ScheduledTask, AppError> {
        let task_id = generate_id("tsk");
        let now = now_utc();

        let task = ScheduledTask {
            task_id: task_id.clone(),
            name: create.name,
            description: create.description,
            task_type: create.task_type,
            schedule: create.schedule,
            parameters: create.parameters,
            status: TaskStatus::Pending,
            last_run: None,
            next_run: self.calculate_next_run(&create.schedule, None),
            last_error: None,
            retry_policy: create.retry_policy,
            timeout: create.timeout,
            enabled: create.enabled,
            created_at: now,
            updated_at: now,
        };

        self.tasks.insert(task_id.clone(), task.clone());
        
        tracing::info!(task_id = %task_id, "创建定时任务");
        Ok(task)
    }

    pub fn get_task(&self, task_id: &str) -> Option<ScheduledTask> {
        self.tasks.get(task_id).map(|t| t.clone())
    }

    pub fn list_tasks(&self) -> Vec<ScheduledTask> {
        self.tasks.iter().map(|t| t.clone()).collect()
    }

    pub fn update_task(&self, task_id: &str, update: TaskUpdate) -> Result<ScheduledTask, AppError> {
        let mut task = self.tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("定时任务不存在: {}", task_id)))?;

        if let Some(name) = update.name {
            task.name = name;
        }
        if let Some(description) = update.description {
            task.description = description;
        }
        if let Some(schedule) = update.schedule {
            task.schedule = schedule;
            task.next_run = self.calculate_next_run(&task.schedule, None);
        }
        if let Some(parameters) = update.parameters {
            task.parameters = parameters;
        }
        if let Some(retry_policy) = update.retry_policy {
            task.retry_policy = retry_policy;
        }
        if let Some(timeout) = update.timeout {
            task.timeout = timeout;
        }
        if let Some(enabled) = update.enabled {
            task.enabled = enabled;
        }
        task.updated_at = now_utc();

        Ok(task.clone())
    }

    pub fn delete_task(&self, task_id: &str) -> Result<(), AppError> {
        if self.tasks.remove(task_id).is_some() {
            if let Some(handle) = self.running_tasks.remove(task_id) {
                handle.abort();
            }
            tracing::info!(task_id = %task_id, "删除定时任务");
            Ok(())
        } else {
            Err(AppError::NotFound(format!("定时任务不存在: {}", task_id)))
        }
    }

    pub fn pause_task(&self, task_id: &str) -> Result<ScheduledTask, AppError> {
        let mut task = self.tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("定时任务不存在: {}", task_id)))?;

        task.status = TaskStatus::Paused;
        task.enabled = false;
        task.updated_at = now_utc();

        if let Some(handle) = self.running_tasks.remove(task_id) {
            handle.abort();
        }

        tracing::info!(task_id = %task_id, "暂停定时任务");
        Ok(task.clone())
    }

    pub fn resume_task(&self, task_id: &str) -> Result<ScheduledTask, AppError> {
        let mut task = self.tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("定时任务不存在: {}", task_id)))?;

        task.status = TaskStatus::Pending;
        task.enabled = true;
        task.next_run = self.calculate_next_run(&task.schedule, None);
        task.updated_at = now_utc();

        tracing::info!(task_id = %task_id, "恢复定时任务");
        Ok(task.clone())
    }

    pub async fn trigger_task(&self, task_id: &str) -> Result<TaskExecution, AppError> {
        let task = self.tasks.get(task_id)
            .ok_or_else(|| AppError::NotFound(format!("定时任务不存在: {}", task_id)))?
            .clone();

        let execution = self.execute_task(task).await?;
        Ok(execution)
    }

    fn calculate_next_run(&self, schedule: &TaskSchedule, last_run: Option<DateTime<Utc>>) -> Option<DateTime<Utc>> {
        let now = now_utc();
        
        match schedule {
            TaskSchedule::Cron { cron_expression } => {
                if let Ok(schedule) = Schedule::parse(cron_expression) {
                    schedule.upcoming(Utc).next()
                } else {
                    None
                }
            }
            TaskSchedule::Interval { interval_seconds } => {
                let base = last_run.unwrap_or(now);
                Some(base + chrono::Duration::seconds(*interval_seconds as i64))
            }
            TaskSchedule::Once { run_at } => {
                if *run_at > now {
                    Some(*run_at)
                } else {
                    None
                }
            }
        }
    }

    async fn execute_task(&self, task: ScheduledTask) -> Result<TaskExecution, AppError> {
        let execution_id = generate_id("exec");
        let mut execution = TaskExecution {
            execution_id: execution_id.clone(),
            task_id: task.task_id.clone(),
            started_at: now_utc(),
            completed_at: None,
            status: TaskStatus::Running,
            result: None,
            error: None,
            retry_count: 0,
        };

        self.executions.insert(execution_id.clone(), execution.clone());

        self.event_store
            .create_event(
                &format!("task:{}", task.task_id),
                "task.started",
                serde_json::json!({
                    "execution_id": execution_id,
                    "task_id": task.task_id,
                    "task_type": task.task_type,
                }),
                None,
            )
            .await?;

        let handler = self.handlers.get(&task.task_type)
            .map(|h| h.value().clone())
            .ok_or_else(|| AppError::InternalError(format!("未找到任务处理器: {:?}", task.task_type)))?;

        let mut retry_count = 0;
        let mut delay = task.retry_policy.retry_delay_seconds;

        loop {
            let result = tokio::time::timeout(
                task.timeout,
                handler.execute(&task),
            )
            .await;

            match result {
                Ok(Ok(data)) => {
                    execution.status = TaskStatus::Completed;
                    execution.completed_at = Some(now_utc());
                    execution.result = Some(data);
                    execution.retry_count = retry_count;
                    
                    self.event_store
                        .create_event(
                            &format!("task:{}", task.task_id),
                            "task.completed",
                            serde_json::json!({
                                "execution_id": execution_id,
                                "duration_ms": execution.completed_at.unwrap().signed_duration_since(execution.started_at).num_milliseconds(),
                            }),
                            None,
                        )
                        .await?;

                    break;
                }
                Ok(Err(e)) | Err(_) => {
                    let error_msg = match result {
                        Ok(Err(e)) => e.to_string(),
                        Err(_) => "任务执行超时".to_string(),
                        _ => unreachable!(),
                    };

                    retry_count += 1;
                    execution.retry_count = retry_count;

                    if retry_count >= task.retry_policy.max_retries {
                        execution.status = TaskStatus::Failed;
                        execution.completed_at = Some(now_utc());
                        execution.error = Some(error_msg.clone());
                        
                        self.event_store
                            .create_event(
                                &format!("task:{}", task.task_id),
                                "task.failed",
                                serde_json::json!({
                                    "execution_id": execution_id,
                                    "error": error_msg,
                                    "retry_count": retry_count,
                                }),
                                None,
                            )
                            .await?;

                        break;
                    }

                    tracing::warn!(
                        task_id = %task.task_id,
                        execution_id = %execution_id,
                        retry = retry_count,
                        max_retries = task.retry_policy.max_retries,
                        "任务执行失败，准备重试"
                    );

                    tokio::time::sleep(Duration::from_secs(delay)).await;
                    if task.retry_policy.exponential_backoff {
                        delay *= 2;
                    }
                }
            }
        }

        if let Some(mut task) = self.tasks.get_mut(&task.task_id) {
            task.last_run = Some(execution.started_at);
            task.next_run = self.calculate_next_run(&task.schedule, task.last_run);
            task.last_error = execution.error.clone();
            task.status = execution.status.clone();
            task.updated_at = now_utc();
        }

        self.executions.insert(execution_id.clone(), execution.clone());

        Ok(execution)
    }

    fn start_scheduler_loop(&self) {
        let tasks = self.tasks.clone();
        let executions = self.executions.clone();
        let running_tasks = self.running_tasks.clone();
        let event_store = self.event_store.clone();
        let handlers = self.handlers.clone();

        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(1));
            
            loop {
                interval.tick().await;
                
                let now = now_utc();
                let mut tasks_to_run = Vec::new();

                for mut task in tasks.iter_mut() {
                    if !task.enabled || task.status == TaskStatus::Running {
                        continue;
                    }

                    if let Some(next_run) = task.next_run {
                        if next_run <= now {
                            tasks_to_run.push(task.value().clone());
                            task.status = TaskStatus::Running;
                            task.last_run = Some(now);
                        }
                    }

                    if let TaskSchedule::Once { run_at } = &task.schedule {
                        if *run_at <= now && task.last_run.is_none() {
                            tasks_to_run.push(task.value().clone());
                            task.status = TaskStatus::Running;
                            task.last_run = Some(now);
                        }
                    }
                }

                for task in tasks_to_run {
                    let task_id = task.task_id.clone();
                    
                    if running_tasks.contains_key(&task_id) {
                        continue;
                    }

                    let tasks_clone = tasks.clone();
                    let executions_clone = executions.clone();
                    let event_store_clone = event_store.clone();
                    let handlers_clone = handlers.clone();
                    let running_tasks_clone = running_tasks.clone();

                    let handle = tokio::spawn(async move {
                        let execution_id = generate_id("exec");
                        let mut execution = TaskExecution {
                            execution_id: execution_id.clone(),
                            task_id: task.task_id.clone(),
                            started_at: now_utc(),
                            completed_at: None,
                            status: TaskStatus::Running,
                            result: None,
                            error: None,
                            retry_count: 0,
                        };

                        executions_clone.insert(execution_id.clone(), execution.clone());

                        let _ = event_store_clone
                            .create_event(
                                &format!("task:{}", task.task_id),
                                "task.started",
                                serde_json::json!({
                                    "execution_id": execution_id,
                                }),
                                None,
                            )
                            .await;

                        if let Some(handler) = handlers_clone.get(&task.task_type) {
                            let handler_result = tokio::time::timeout(task.timeout, handler.execute(&task)).await;
                            match handler_result {
                                Ok(Ok(data)) => {
                                    execution.status = TaskStatus::Completed;
                                    execution.completed_at = Some(now_utc());
                                    execution.result = Some(data);
                                }
                                Ok(Err(e)) | Err(_) => {
                                    execution.status = TaskStatus::Failed;
                                    execution.completed_at = Some(now_utc());
                                    execution.error = Some(match handler_result {
                                        Ok(Err(e)) => e.to_string(),
                                        Err(_) => "任务执行超时".to_string(),
                                        _ => unreachable!(),
                                    });
                                }
                            }
                        }

                        executions_clone.insert(execution_id.clone(), execution.clone());

                        if let Some(mut task) = tasks_clone.get_mut(&task_id) {
                            task.status = execution.status.clone();
                            task.next_run = match &task.schedule {
                                TaskSchedule::Cron { .. } | TaskSchedule::Interval { .. } => {
                                    Self::_calculate_next_run(&task.schedule, task.last_run)
                                }
                                TaskSchedule::Once { .. } => None,
                            };
                            task.last_error = execution.error.clone();
                            task.updated_at = now_utc();

                            if let TaskSchedule::Once { .. } = task.schedule {
                                task.enabled = false;
                            }
                        }

                        running_tasks_clone.remove(&task_id);
                    });

                    running_tasks.insert(task_id, handle);
                }
            }
        });
    }

    fn _calculate_next_run(schedule: &TaskSchedule, last_run: Option<DateTime<Utc>>) -> Option<DateTime<Utc>> {
        let now = now_utc();
        
        match schedule {
            TaskSchedule::Cron { cron_expression } => {
                if let Ok(schedule) = Schedule::parse(cron_expression) {
                    schedule.upcoming(Utc).next()
                } else {
                    None
                }
            }
            TaskSchedule::Interval { interval_seconds } => {
                let base = last_run.unwrap_or(now);
                Some(base + chrono::Duration::seconds(*interval_seconds as i64))
            }
            TaskSchedule::Once { run_at } => {
                if *run_at > now {
                    Some(*run_at)
                } else {
                    None
                }
            }
        }
    }

    pub fn get_execution(&self, execution_id: &str) -> Option<TaskExecution> {
        self.executions.get(execution_id).map(|e| e.clone())
    }

    pub fn list_executions(&self, task_id: Option<&str>) -> Vec<TaskExecution> {
        self.executions
            .iter()
            .filter(|entry| {
                task_id.map_or(true, |tid| entry.value().task_id == tid)
            })
            .map(|entry| entry.value().clone())
            .collect()
    }

    pub fn register_handler<H: TaskHandler + Send + Sync + 'static>(&self, task_type: TaskType, handler: H) {
        self.handlers.insert(task_type, Box::new(handler));
    }
}

impl Default for RetryPolicy {
    fn default() -> Self {
        Self {
            max_retries: 3,
            retry_delay_seconds: 5,
            exponential_backoff: true,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct TaskCreate {
    pub name: String,
    pub description: String,
    pub task_type: TaskType,
    pub schedule: TaskSchedule,
    #[serde(default)]
    pub parameters: HashMap<String, serde_json::Value>,
    #[serde(default)]
    pub retry_policy: RetryPolicy,
    #[serde(default = "default_timeout")]
    pub timeout: Duration,
    #[serde(default = "default_enabled")]
    pub enabled: bool,
}

#[derive(Debug, Deserialize)]
pub struct TaskUpdate {
    pub name: Option<String>,
    pub description: Option<String>,
    pub schedule: Option<TaskSchedule>,
    pub parameters: Option<HashMap<String, serde_json::Value>>,
    pub retry_policy: Option<RetryPolicy>,
    pub timeout: Option<Duration>,
    pub enabled: Option<bool>,
}

fn default_timeout() -> Duration {
    Duration::from_secs(300)
}

fn default_enabled() -> bool {
    true
}
