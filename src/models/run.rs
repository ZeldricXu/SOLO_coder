use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum RunPhase {
    #[serde(rename = "pending")]
    Pending,
    #[serde(rename = "running")]
    Running,
    #[serde(rename = "finalizing")]
    Finalizing,
    #[serde(rename = "completed")]
    Completed,
    #[serde(rename = "failed")]
    Failed,
}

impl std::fmt::Display for RunPhase {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RunPhase::Pending => write!(f, "pending"),
            RunPhase::Running => write!(f, "running"),
            RunPhase::Finalizing => write!(f, "finalizing"),
            RunPhase::Completed => write!(f, "completed"),
            RunPhase::Failed => write!(f, "failed"),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunInstance {
    pub run_id: String,
    pub entity_id: String,
    pub phase: RunPhase,
    pub progress: f64,
    pub started_at: DateTime<Utc>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub completed_at: Option<DateTime<Utc>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_detail: Option<String>,
}

impl RunInstance {
    pub fn new(entity_id: impl Into<String>) -> Self {
        Self {
            run_id: format!("run_{}", uuid::Uuid::new_v4().simple()),
            entity_id: entity_id.into(),
            phase: RunPhase::Pending,
            progress: 0.0,
            started_at: Utc::now(),
            completed_at: None,
            error_detail: None,
        }
    }

    pub fn is_terminal(&self) -> bool {
        matches!(self.phase, RunPhase::Completed | RunPhase::Failed)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateRunRequest {
    pub entity_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateRunRequest {
    pub phase: RunPhase,
    #[serde(default)]
    pub progress: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_detail: Option<String>,
}
