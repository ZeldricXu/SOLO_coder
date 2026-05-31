use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use tokio::sync::{RwLock, mpsc};
use futures::future;
use crate::scheduler::models::{
    TaskDefinition, TaskInstance, TaskStatus, WorkflowDefinition, 
    WorkflowExecution, ScheduleTaskRequest, ScheduleResponse,
};
use crate::utils::error::{Result, PlatformError};
use crate::utils::id::generate_id;
use chrono::Utc;
use tracing::{info, warn, error};

#[async_trait::async_trait]
pub trait TaskExecutor: Send + Sync {
    async fn execute(&self, task: &TaskDefinition, instance: &TaskInstance) -> Result<serde_json::Value>;
}

pub struct DefaultExecutor;

#[async_trait::async_trait]
impl TaskExecutor for DefaultExecutor {
    async fn execute(&self, _task: &TaskDefinition, _instance: &TaskInstance) -> Result<serde_json::Value> {
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
        Ok(serde_json::json!({ "status": "completed" }))
    }
}

#[derive(Debug, Clone, Default)]
struct SchedulerState {
    executions: HashMap<String, WorkflowExecution>,
    task_definitions: HashMap<String, TaskDefinition>,
    pending_queue: VecDeque<String>,
}

#[derive(Clone)]
pub struct TaskScheduler {
    state: Arc<RwLock<SchedulerState>>,
    executor: Arc<dyn TaskExecutor>,
}

impl TaskScheduler {
    pub fn new() -> Self {
        Self::with_executor(Arc::new(DefaultExecutor))
    }

    pub fn with_executor(executor: Arc<dyn TaskExecutor>) -> Self {
        Self {
            state: Arc::new(RwLock::new(SchedulerState::default())),
            executor,
        }
    }

    pub async fn schedule_workflow(&self, req: ScheduleTaskRequest) -> Result<ScheduleResponse> {
        info!(workflow_id = %req.workflow.workflow_id, "scheduling_workflow");

        self.validate_workflow(&req.workflow)?;

        let execution_id = format!("exec_{}", uuid::Uuid::new_v4().simple());
        let mut tasks = HashMap::new();

        for task_def in &req.workflow.tasks {
            let instance = TaskInstance::new(&task_def.task_id);
            tasks.insert(task_def.task_id.clone(), instance);
        }

        let execution = WorkflowExecution {
            execution_id: execution_id.clone(),
            workflow_id: req.workflow.workflow_id.clone(),
            status: TaskStatus::Pending,
            tasks,
            started_at: Utc::now(),
            completed_at: None,
            error_message: None,
        };

        {
            let mut state = self.state.write().await;
            
            for task_def in &req.workflow.tasks {
                state.task_definitions.insert(task_def.task_id.clone(), task_def.clone());
            }
            
            state.executions.insert(execution_id.clone(), execution);
            state.pending_queue.push_back(execution_id.clone());
        }

        info!(execution_id = %execution_id, "workflow_scheduled");

        Ok(ScheduleResponse {
            execution_id,
            status: TaskStatus::Pending,
        })
    }

    fn validate_workflow(&self, workflow: &WorkflowDefinition) -> Result<()> {
        let task_ids: std::collections::HashSet<&String> = workflow.tasks
            .iter()
            .map(|t| &t.task_id)
            .collect();

        for task in &workflow.tasks {
            for dep in &task.dependencies {
                if !task_ids.contains(dep) {
                    return Err(PlatformError::Validation(format!(
                        "task {} depends on non-existent task {}",
                        task.task_id, dep
                    )));
                }
            }
        }

        if self.has_cyclic_dependencies(workflow) {
            return Err(PlatformError::Validation(
                "workflow contains cyclic dependencies".to_string()
            ));
        }

        Ok(())
    }

    fn has_cyclic_dependencies(&self, workflow: &WorkflowDefinition) -> bool {
        let mut visited = std::collections::HashSet::new();
        let mut rec_stack = std::collections::HashSet::new();

        let task_map: HashMap<String, &TaskDefinition> = workflow.tasks
            .iter()
            .map(|t| (t.task_id.clone(), t))
            .collect();

        for task in &workflow.tasks {
            if self.dfs_cycle_check(&task.task_id, &task_map, &mut visited, &mut rec_stack) {
                return true;
            }
        }
        false
    }

    fn dfs_cycle_check(
        &self,
        task_id: &str,
        task_map: &HashMap<String, &TaskDefinition>,
        visited: &mut std::collections::HashSet<String>,
        rec_stack: &mut std::collections::HashSet<String>,
    ) -> bool {
        if rec_stack.contains(task_id) {
            return true;
        }
        if visited.contains(task_id) {
            return false;
        }

        visited.insert(task_id.to_string());
        rec_stack.insert(task_id.to_string());

        if let Some(task) = task_map.get(task_id) {
            for dep in &task.dependencies {
                if self.dfs_cycle_check(dep, task_map, visited, rec_stack) {
                    return true;
                }
            }
        }

        rec_stack.remove(task_id);
        false
    }

    pub async fn execute_workflow(&self, execution_id: &str) -> Result<WorkflowExecution> {
        info!(execution_id = %execution_id, "executing_workflow");

        let workflow = {
            let state = self.state.read().await;
            state.executions.get(execution_id)
                .cloned()
                .ok_or_else(|| PlatformError::NotFound(format!(
                    "execution {} not found", execution_id
                )))?
        };

        let task_defs = {
            let state = self.state.read().await;
            state.task_definitions.clone()
        };

        let mut execution = workflow;
        execution.status = TaskStatus::Running;
        {
            let mut state = self.state.write().await;
            state.executions.insert(execution_id.to_string(), execution.clone());
        }

        let ordered_tasks = self.topological_sort(&execution, &task_defs);
        info!(task_count = %ordered_tasks.len(), "topologically_sorted_tasks");

        for task_id in ordered_tasks {
            let result = self.execute_task(&task_id, &task_defs, &mut execution).await;
            
            {
                let mut state = self.state.write().await;
                state.executions.insert(execution_id.to_string(), execution.clone());
            }

            if result.is_err() {
                execution.status = TaskStatus::Failed;
                execution.error_message = Some(result.err().unwrap().to_string());
                {
                    let mut state = self.state.write().await;
                    state.executions.insert(execution_id.to_string(), execution.clone());
                }
                return Err(PlatformError::Internal(format!(
                    "task {} failed", task_id
                )));
            }
        }

        execution.status = TaskStatus::Completed;
        execution.completed_at = Some(Utc::now());
        
        {
            let mut state = self.state.write().await;
            state.executions.insert(execution_id.to_string(), execution.clone());
        }

        info!(execution_id = %execution_id, "workflow_completed");
        Ok(execution)
    }

    fn topological_sort(
        &self,
        execution: &WorkflowExecution,
        task_defs: &HashMap<String, TaskDefinition>,
    ) -> Vec<String> {
        let mut in_degree: HashMap<String, usize> = HashMap::new();
        let mut adjacency: HashMap<String, Vec<String>> = HashMap::new();

        for (task_id, _) in &execution.tasks {
            in_degree.insert(task_id.clone(), 0);
            adjacency.insert(task_id.clone(), vec![]);
        }

        for (task_id, _) in &execution.tasks {
            if let Some(def) = task_defs.get(task_id) {
                for dep in &def.dependencies {
                    in_degree.entry(task_id.clone()).and_modify(|d| *d += 1);
                    adjacency.entry(dep.clone()).or_default().push(task_id.clone());
                }
            }
        }

        let mut queue: VecDeque<String> = in_degree
            .iter()
            .filter(|(_, &d)| d == 0)
            .map(|(k, _)| k.clone())
            .collect();

        let mut result = Vec::new();
        while let Some(node) = queue.pop_front() {
            result.push(node.clone());
            if let Some(neighbors) = adjacency.get(&node) {
                for neighbor in neighbors {
                    if let Some(d) = in_degree.get_mut(neighbor) {
                        *d -= 1;
                        if *d == 0 {
                            queue.push_back(neighbor.clone());
                        }
                    }
                }
            }
        }

        result
    }

    async fn execute_task(
        &self,
        task_id: &str,
        task_defs: &HashMap<String, TaskDefinition>,
        execution: &mut WorkflowExecution,
    ) -> Result<()> {
        info!(task_id = %task_id, "executing_task");

        let task_def = task_defs.get(task_id)
            .ok_or_else(|| PlatformError::NotFound(format!(
                "task definition {} not found", task_id
            )))?;

        let instance = execution.tasks.get_mut(task_id)
            .ok_or_else(|| PlatformError::NotFound(format!(
                "task instance {} not found", task_id
            )))?;

        instance.status = TaskStatus::Running;
        instance.started_at = Some(Utc::now());
        instance.progress = 0.5;

        let result = self.executor.execute(task_def, instance).await;
        
        match result {
            Ok(value) => {
                instance.status = TaskStatus::Completed;
                instance.progress = 1.0;
                instance.completed_at = Some(Utc::now());
                instance.result = Some(value);
                info!(task_id = %task_id, "task_completed");
                Ok(())
            }
            Err(e) => {
                instance.status = TaskStatus::Failed;
                instance.error_message = Some(e.to_string());
                instance.completed_at = Some(Utc::now());
                error!(task_id = %task_id, error = %e, "task_failed");
                Err(e)
            }
        }
    }

    pub async fn get_execution(&self, execution_id: &str) -> Result<WorkflowExecution> {
        let state = self.state.read().await;
        state.executions.get(execution_id)
            .cloned()
            .ok_or_else(|| PlatformError::NotFound(format!(
                "execution {} not found", execution_id
            )))
    }

    pub async fn list_executions(&self) -> Result<Vec<WorkflowExecution>> {
        let state = self.state.read().await;
        Ok(state.executions.values().cloned().collect())
    }

    pub async fn cancel_execution(&self, execution_id: &str) -> Result<()> {
        info!(execution_id = %execution_id, "cancelling_execution");
        
        let mut state = self.state.write().await;
        if let Some(execution) = state.executions.get_mut(execution_id) {
            if matches!(execution.status, TaskStatus::Pending | TaskStatus::Running) {
                execution.status = TaskStatus::Cancelled;
                info!(execution_id = %execution_id, "execution_cancelled");
                Ok(())
            } else {
                Err(PlatformError::Validation(format!(
                    "cannot cancel execution with status {:?}", execution.status
                )))
            }
        } else {
            Err(PlatformError::NotFound(format!(
                "execution {} not found", execution_id
            )))
        }
    }
}
