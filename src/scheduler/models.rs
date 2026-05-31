use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TaskStatus {
    #[serde(rename = "pending")]
    Pending,
    #[serde(rename = "ready")]
    Ready,
    #[serde(rename = "running")]
    Running,
    #[serde(rename = "completed")]
    Completed,
    #[serde(rename = "failed")]
    Failed,
    #[serde(rename = "cancelled")]
    Cancelled,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TaskPriority {
    Low,
    Normal,
    High,
    Critical,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskDefinition {
    pub task_id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub dependencies: Vec<String>,
    #[serde(default)]
    pub priority: TaskPriority,
    #[serde(default)]
    pub retries: u32,
    #[serde(default)]
    pub timeout_seconds: u64,
    #[serde(default)]
    pub payload: serde_json::Value,
    #[serde(default)]
    pub metadata: HashMap<String, String>,
}

impl TaskDefinition {
    pub fn new(task_id: impl Into<String>, name: impl Into<String>) -> Self {
        Self {
            task_id: task_id.into(),
            name: name.into(),
            description: String::new(),
            dependencies: vec![],
            priority: TaskPriority::Normal,
            retries: 0,
            timeout_seconds: 300,
            payload: serde_json::json!({}),
            metadata: HashMap::new(),
        }
    }

    pub fn with_dependency(mut self, dep_id: impl Into<String>) -> Self {
        self.dependencies.push(dep_id.into());
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskInstance {
    pub instance_id: String,
    pub task_id: String,
    pub status: TaskStatus,
    pub progress: f64,
    #[serde(default)]
    pub dependencies_completed: Vec<String>,
    pub created_at: DateTime<Utc>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub error_message: Option<String>,
    #[serde(default)]
    pub retry_count: u32,
    #[serde(default)]
    pub result: Option<serde_json::Value>,
}

impl TaskInstance {
    pub fn new(task_id: impl Into<String>) -> Self {
        Self {
            instance_id: format!("inst_{}", uuid::Uuid::new_v4().simple()),
            task_id: task_id.into(),
            status: TaskStatus::Pending,
            progress: 0.0,
            dependencies_completed: vec![],
            created_at: Utc::now(),
            started_at: None,
            completed_at: None,
            error_message: None,
            retry_count: 0,
            result: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkflowDefinition {
    pub workflow_id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    pub tasks: Vec<TaskDefinition>,
    #[serde(default)]
    pub parameters: HashMap<String, serde_json::Value>,
}

impl WorkflowDefinition {
    pub fn new(workflow_id: impl Into<String>, name: impl Into<String>) -> Self {
        Self {
            workflow_id: workflow_id.into(),
            name: name.into(),
            description: String::new(),
            tasks: vec![],
            parameters: HashMap::new(),
        }
    }

    pub fn with_task(mut self, task: TaskDefinition) -> Self {
        self.tasks.push(task);
        self
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkflowExecution {
    pub execution_id: String,
    pub workflow_id: String,
    pub status: TaskStatus,
    pub tasks: HashMap<String, TaskInstance>,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    #[serde(default)]
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScheduleTaskRequest {
    pub workflow: WorkflowDefinition,
    #[serde(default)]
    pub parameters: HashMap<String, serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScheduleResponse {
    pub execution_id: String,
    pub status: TaskStatus,
}
