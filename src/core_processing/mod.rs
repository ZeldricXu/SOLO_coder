use crate::error::PlatformError;
use crate::monitoring::MonitoringService;
use crate::audit_log::AuditLogChain;
use crate::types::{
    BatchAction, BatchOperation, Entity, EntityStatus, RunInstance, RunPhase,
    ResourceStatus, Config,
};
use async_trait::async_trait;
use chrono::Utc;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, Semaphore};
use tokio::time::timeout;
use tracing::{info, warn, error, debug};
use uuid::Uuid;

#[async_trait]
pub trait TaskExecutor: Send + Sync {
    async fn execute(&self, task: &TaskContext) -> Result<TaskResult, PlatformError>;
    fn task_type(&self) -> &str;
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskContext {
    pub task_id: String,
    pub task_type: String,
    pub parameters: HashMap<String, serde_json::Value>,
    pub trace_id: String,
    pub timeout_ms: u64,
    pub priority: u8,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskResult {
    pub task_id: String,
    pub success: bool,
    pub output: Option<serde_json::Value>,
    pub error_message: Option<String>,
    pub duration_ms: u64,
}

struct ScheduledTask {
    entity: Entity,
    run_instance: RunInstance,
    config: Config,
    submitted_at: Instant,
    priority: u8,
}

struct SchedulerState {
    entities: HashMap<String, Entity>,
    run_instances: HashMap<String, RunInstance>,
    configs: HashMap<String, Config>,
    pending_queue: VecDeque<ScheduledTask>,
    running_tasks: HashMap<String, ScheduledTask>,
    completed_tasks: Vec<ScheduledTask>,
    executors: HashMap<String, Arc<dyn TaskExecutor>>,
    task_count: u64,
}

pub struct TaskScheduler {
    state: Arc<RwLock<SchedulerState>>,
    monitoring: Arc<MonitoringService>,
    audit_log: Arc<AuditLogChain>,
    concurrency_semaphore: Arc<Semaphore>,
    task_tx: Option<mpsc::UnboundedSender<ScheduledTask>>,
}

impl TaskScheduler {
    pub fn new(
        monitoring: Arc<MonitoringService>,
        audit_log: Arc<AuditLogChain>,
    ) -> Self {
        TaskScheduler {
            state: Arc::new(RwLock::new(SchedulerState {
                entities: HashMap::new(),
                run_instances: HashMap::new(),
                configs: HashMap::new(),
                pending_queue: VecDeque::new(),
                running_tasks: HashMap::new(),
                completed_tasks: Vec::new(),
                executors: HashMap::new(),
                task_count: 0,
            })),
            monitoring,
            audit_log,
            concurrency_semaphore: Arc::new(Semaphore::new(100)),
            task_tx: None,
        }
    }

    pub async fn start(&self) -> Result<(), PlatformError> {
        info!("Starting TaskScheduler");
        
        let (tx, mut rx) = mpsc::unbounded_channel::<ScheduledTask>();
        
        {
            let mut state = self.state.write();
            state.executors.insert("default".to_string(), Arc::new(DefaultExecutor));
        }
        
        let state_clone = self.state.clone();
        let monitoring_clone = self.monitoring.clone();
        let audit_log_clone = self.audit_log.clone();
        let semaphore_clone = self.concurrency_semaphore.clone();
        
        tokio::spawn(async move {
            while let Some(mut task) = rx.recv().await {
                let permit = semaphore_clone.clone().acquire_owned().await
                    .map_err(|e| PlatformError::Internal(format!("Semaphore error: {}", e)));
                
                if let Err(e) = permit {
                    error!(error = %e, "Failed to acquire concurrency permit");
                    continue;
                }
                
                let _permit = permit.unwrap();
                
                let executor = {
                    let state = state_clone.read();
                    state.executors
                        .get(&task.entity.entity_type)
                        .cloned()
                        .or_else(|| state.executors.get("default").cloned())
                };
                
                if let Some(executor) = executor {
                    let context = TaskContext {
                        task_id: task.run_instance.run_id.clone(),
                        task_type: task.entity.entity_type.clone(),
                        parameters: task.entity.attributes.clone(),
                        trace_id: format!("trace_{}", Uuid::new_v4().simple()),
                        timeout_ms: 30000,
                        priority: task.priority,
                    };
                    
                    {
                        let mut state = state_clone.write();
                        task.run_instance.phase = RunPhase::Processing;
                        task.entity.status = EntityStatus::Running;
                        task.entity.updated_at = Utc::now();
                        
                        state.running_tasks.insert(task.run_instance.run_id.clone(), task.clone());
                        state.run_instances.insert(task.run_instance.run_id.clone(), task.run_instance.clone());
                        state.entities.insert(task.entity.id.clone(), task.entity.clone());
                    }
                    
                    let start = Instant::now();
                    let run_id = task.run_instance.run_id.clone();
                    
                    let result = timeout(
                        Duration::from_millis(context.timeout_ms),
                        executor.execute(&context),
                    ).await;
                    
                    let duration_ms = start.elapsed().as_millis() as u64;
                    
                    let final_result = match result {
                        Ok(Ok(r)) => r,
                        Ok(Err(e)) => TaskResult {
                            task_id: run_id.clone(),
                            success: false,
                            output: None,
                            error_message: Some(e.to_string()),
                            duration_ms,
                        },
                        Err(_) => TaskResult {
                            task_id: run_id.clone(),
                            success: false,
                            output: None,
                            error_message: Some("Task execution timed out".to_string()),
                            duration_ms,
                        },
                    };
                    
                    {
                        let mut state = state_clone.write();
                        state.running_tasks.remove(&run_id);
                        
                        if let Some(mut instance) = state.run_instances.get_mut(&run_id) {
                            if final_result.success {
                                instance.phase = RunPhase::Completed;
                                instance.progress = 1.0;
                                instance.completed_at = Some(Utc::now());
                                
                                if let Some(entity) = state.entities.get_mut(&task.entity.id) {
                                    entity.status = EntityStatus::Completed;
                                    entity.updated_at = Utc::now();
                                }
                            } else {
                                instance.phase = RunPhase::Failed;
                                instance.error_detail = final_result.error_message.clone();
                                instance.completed_at = Some(Utc::now());
                                
                                if let Some(entity) = state.entities.get_mut(&task.entity.id) {
                                    entity.status = EntityStatus::Failed;
                                    entity.updated_at = Utc::now();
                                }
                            }
                        }
                        
                        state.completed_tasks.push(task);
                        
                        if state.completed_tasks.len() > 10000 {
                            state.completed_tasks.drain(0..1000);
                        }
                    }
                    
                    monitoring_clone.record_latency("task_execution", duration_ms);
                    monitoring_clone.increment_counter("tasks_executed", 1.0);
                    
                    if !final_result.success {
                        monitoring_clone.record_error("task_execution");
                    }
                    
                    if let Err(e) = audit_log_clone.append(
                        "system",
                        if final_result.success { "task_completed" } else { "task_failed" },
                        &run_id,
                        serde_json::json!({
                            "task_type": &task.entity.entity_type,
                            "duration_ms": duration_ms,
                            "success": final_result.success,
                            "error": final_result.error_message,
                        })
                    ).await {
                        error!(error = %e, "Failed to log task completion");
                    }
                }
            }
        });
        
        {
            let mut state = self.state.write();
            self.task_tx = Some(tx);
        }
        
        info!("TaskScheduler started successfully");
        Ok(())
    }

    pub fn register_executor(&self, task_type: &str, executor: Arc<dyn TaskExecutor>) {
        let mut state = self.state.write();
        state.executors.insert(task_type.to_string(), executor);
        info!(task_type = %task_type, "Task executor registered");
    }

    pub async fn create_task(
        &self,
        task_type: &str,
        parameters: HashMap<String, serde_json::Value>,
    ) -> Result<Entity, PlatformError> {
        if task_type.is_empty() {
            return Err(PlatformError::Validation("Task type cannot be empty".to_string()));
        }
        
        let tx = self.task_tx.clone()
            .ok_or_else(|| PlatformError::Internal("Scheduler not started".to_string()))?;
        
        let mut entity = Entity::new(task_type);
        entity.attributes = parameters.clone();
        
        let mut run_instance = RunInstance::new(&entity.id);
        let config = Config::new("default");
        
        let scheduled_task = ScheduledTask {
            entity: entity.clone(),
            run_instance: run_instance.clone(),
            config: config.clone(),
            submitted_at: Instant::now(),
            priority: 50,
        };
        
        {
            let mut state = self.state.write();
            state.entities.insert(entity.id.clone(), entity.clone());
            state.run_instances.insert(run_instance.run_id.clone(), run_instance.clone());
            state.configs.insert(config.config_id.clone(), config.clone());
            state.pending_queue.push_back(scheduled_task.clone());
            state.task_count += 1;
        }
        
        tx.send(scheduled_task)
            .map_err(|e| PlatformError::Internal(format!("Failed to queue task: {}", e)))?;
        
        self.audit_log.append(
            "system",
            "task_created",
            &entity.id,
            serde_json::json!({
                "task_type": task_type,
                "parameters_count": parameters.len(),
            })
        ).await?;
        
        self.monitoring.increment_counter("tasks_created", 1.0);
        
        info!(
            task_id = %entity.id,
            task_type = %task_type,
            "Task created and queued"
        );
        
        Ok(entity)
    }

    pub async fn get_task_status(&self, task_id: &str) -> Result<ResourceStatus, PlatformError> {
        let state = self.state.read();
        
        let entity = state.entities.get(task_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Task {} not found", task_id)))?;
        
        let run_instance = state.run_instances.values()
            .find(|r| r.entity_id == task_id);
        
        let status_str = match entity.status {
            EntityStatus::Pending => "pending",
            EntityStatus::Running => "running",
            EntityStatus::Completed => "completed",
            EntityStatus::Failed => "failed",
            EntityStatus::Cancelled => "cancelled",
        };
        
        let progress = run_instance.map(|r| r.progress).unwrap_or(0.0);
        
        Ok(ResourceStatus {
            id: entity.id.clone(),
            status: status_str.to_string(),
            progress,
        })
    }

    pub async fn execute_batch_operation(&self, operation: &BatchOperation) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        
        let entity = state.entities.get_mut(&operation.id)
            .ok_or_else(|| PlatformError::NotFound(format!("Resource {} not found", operation.id)))?;
        
        match operation.action {
            BatchAction::Start => {
                if entity.status == EntityStatus::Running {
                    return Err(PlatformError::Conflict(format!(
                        "Resource {} is already running",
                        operation.id
                    )));
                }
                entity.status = EntityStatus::Running;
                entity.updated_at = Utc::now();
            }
            BatchAction::Stop => {
                entity.status = EntityStatus::Cancelled;
                entity.updated_at = Utc::now();
            }
            BatchAction::Cancel => {
                entity.status = EntityStatus::Cancelled;
                entity.updated_at = Utc::now();
            }
            BatchAction::Restart => {
                entity.status = EntityStatus::Pending;
                entity.updated_at = Utc::now();
                
                if let Some(run_instance) = state.run_instances.values_mut()
                    .find(|r| r.entity_id == operation.id)
                {
                    *run_instance = RunInstance::new(&operation.id);
                }
            }
        }
        
        let action_str = match operation.action {
            BatchAction::Start => "start",
            BatchAction::Stop => "stop",
            BatchAction::Cancel => "cancel",
            BatchAction::Restart => "restart",
        };
        
        self.audit_log.append(
            "system",
            &format!("batch_{}", action_str),
            &operation.id,
            serde_json::json!({ "action": action_str }),
        ).await?;
        
        info!(
            resource_id = %operation.id,
            action = action_str,
            "Batch operation executed"
        );
        
        Ok(())
    }

    pub fn get_entity(&self, id: &str) -> Option<Entity> {
        let state = self.state.read();
        state.entities.get(id).cloned()
    }

    pub fn get_run_instance(&self, run_id: &str) -> Option<RunInstance> {
        let state = self.state.read();
        state.run_instances.get(run_id).cloned()
    }

    pub fn list_entities(&self, status: Option<EntityStatus>) -> Vec<Entity> {
        let state = self.state.read();
        state.entities.values()
            .filter(|e| status.map(|s| e.status == s).unwrap_or(true))
            .cloned()
            .collect()
    }

    pub fn get_pending_count(&self) -> usize {
        let state = self.state.read();
        state.pending_queue.len()
    }

    pub fn get_running_count(&self) -> usize {
        let state = self.state.read();
        state.running_tasks.len()
    }

    pub fn get_completed_count(&self) -> usize {
        let state = self.state.read();
        state.completed_tasks.len()
    }

    pub fn get_total_count(&self) -> u64 {
        let state = self.state.read();
        state.task_count
    }

    pub fn update_task_progress(&self, run_id: &str, progress: f64) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        
        let progress = progress.clamp(0.0, 1.0);
        
        let run_instance = state.run_instances.get_mut(run_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Run instance {} not found", run_id)))?;
        
        run_instance.progress = progress;
        
        if progress >= 1.0 {
            run_instance.phase = RunPhase::Finalizing;
        } else if progress > 0.0 {
            run_instance.phase = RunPhase::Processing;
        }
        
        Ok(())
    }

    pub async fn cancel_task(&self, task_id: &str) -> Result<(), PlatformError> {
        let mut state = self.state.write();
        
        let entity = state.entities.get_mut(task_id)
            .ok_or_else(|| PlatformError::NotFound(format!("Task {} not found", task_id)))?;
        
        if entity.status == EntityStatus::Completed || entity.status == EntityStatus::Failed {
            return Err(PlatformError::Validation(
                "Cannot cancel a completed or failed task".to_string()
            ));
        }
        
        entity.status = EntityStatus::Cancelled;
        entity.updated_at = Utc::now();
        
        self.audit_log.append(
            "system",
            "task_cancelled",
            task_id,
            serde_json::json!({ "cancelled_at": Utc::now().to_rfc3339() }),
        ).await?;
        
        warn!(task_id = %task_id, "Task cancelled");
        
        Ok(())
    }

    pub fn get_statistics(&self) -> HashMap<String, u64> {
        let state = self.state.read();
        let mut stats = HashMap::new();
        
        stats.insert("total".to_string(), state.task_count);
        stats.insert("pending".to_string(), state.pending_queue.len() as u64);
        stats.insert("running".to_string(), state.running_tasks.len() as u64);
        stats.insert("completed".to_string(), state.completed_tasks.len() as u64);
        
        stats
    }
}

pub struct DefaultExecutor;

#[async_trait]
impl TaskExecutor for DefaultExecutor {
    async fn execute(&self, context: &TaskContext) -> Result<TaskResult, PlatformError> {
        let start = Instant::now();
        
        debug!(task_id = %context.task_id, task_type = %context.task_type, "Executing default task");
        
        tokio::time::sleep(Duration::from_millis(100)).await;
        
        Ok(TaskResult {
            task_id: context.task_id.clone(),
            success: true,
            output: Some(serde_json::json!({
                "message": "Task completed successfully",
                "processed_params": context.parameters.keys().count(),
            })),
            error_message: None,
            duration_ms: start.elapsed().as_millis() as u64,
        })
    }

    fn task_type(&self) -> &str {
        "default"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::monitoring::MonitoringService;
    use crate::audit_log::AuditLogChain;

    struct TestExecutor;

    #[async_trait]
    impl TaskExecutor for TestExecutor {
        async fn execute(&self, context: &TaskContext) -> Result<TaskResult, PlatformError> {
            Ok(TaskResult {
                task_id: context.task_id.clone(),
                success: true,
                output: Some(serde_json::json!({"status": "ok"})),
                error_message: None,
                duration_ms: 100,
            })
        }

        fn task_type(&self) -> &str {
            "test"
        }
    }

    #[tokio::test]
    async fn test_create_task() {
        let audit_log = Arc::new(AuditLogChain::new());
        let monitoring = Arc::new(MonitoringService::new(audit_log.clone()));
        let scheduler = TaskScheduler::new(monitoring.clone(), audit_log.clone());
        
        scheduler.start().await.unwrap();
        scheduler.register_executor("test", Arc::new(TestExecutor));
        
        let params = HashMap::new();
        let entity = scheduler.create_task("test", params).await.unwrap();
        
        assert_eq!(entity.entity_type, "test");
        assert_eq!(entity.status, EntityStatus::Pending);
    }

    #[tokio::test]
    async fn test_batch_operation() {
        let audit_log = Arc::new(AuditLogChain::new());
        let monitoring = Arc::new(MonitoringService::new(audit_log.clone()));
        let scheduler = TaskScheduler::new(monitoring.clone(), audit_log.clone());
        
        scheduler.start().await.unwrap();
        
        let params = HashMap::new();
        let entity = scheduler.create_task("default", params).await.unwrap();
        
        let operation = BatchOperation {
            action: BatchAction::Cancel,
            id: entity.id.clone(),
        };
        
        scheduler.execute_batch_operation(&operation).await.unwrap();
        
        let status = scheduler.get_task_status(&entity.id).await.unwrap();
        assert_eq!(status.status, "cancelled");
    }
}
