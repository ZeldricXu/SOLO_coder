use serde::{Serialize, Deserialize};
use chrono::{DateTime, Utc};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum RunPhase {
    Initializing,
    Pending,
    Running,
    Completed,
    Failed,
    Cancelled,
    RollingBack,
    RolledBack,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunInstance {
    pub run_id: String,
    pub entity_id: String,
    pub config_id: String,
    pub phase: RunPhase,
    pub progress: f32,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_detail: Option<String>,
    pub metadata: serde_json::Value,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

impl RunInstance {
    pub fn new(entity_id: impl Into<String>, config_id: impl Into<String>) -> Self {
        let now = Utc::now();
        Self {
            run_id: format!("run_{}", Uuid::new_v4().simple()),
            entity_id: entity_id.into(),
            config_id: config_id.into(),
            phase: RunPhase::Initializing,
            progress: 0.0,
            started_at: None,
            completed_at: None,
            error_detail: None,
            metadata: serde_json::json!({}),
            created_at: now,
            updated_at: now,
        }
    }

    pub fn start(&mut self) {
        self.phase = RunPhase::Running;
        self.started_at = Some(Utc::now());
        self.progress = 0.0;
        self.updated_at = Utc::now();
    }

    pub fn set_progress(&mut self, progress: f32) {
        self.progress = progress.clamp(0.0, 1.0);
        self.updated_at = Utc::now();
    }

    pub fn complete(&mut self) {
        self.phase = RunPhase::Completed;
        self.progress = 1.0;
        self.completed_at = Some(Utc::now());
        self.updated_at = Utc::now();
    }

    pub fn fail(&mut self, error: impl Into<String>) {
        self.phase = RunPhase::Failed;
        self.error_detail = Some(error.into());
        self.completed_at = Some(Utc::now());
        self.updated_at = Utc::now();
    }

    pub fn cancel(&mut self) {
        self.phase = RunPhase::Cancelled;
        self.completed_at = Some(Utc::now());
        self.updated_at = Utc::now();
    }

    pub fn start_rollback(&mut self) {
        self.phase = RunPhase::RollingBack;
        self.updated_at = Utc::now();
    }

    pub fn finish_rollback(&mut self) {
        self.phase = RunPhase::RolledBack;
        self.completed_at = Some(Utc::now());
        self.updated_at = Utc::now();
    }

    pub fn is_finished(&self) -> bool {
        matches!(self.phase, RunPhase::Completed | RunPhase::Failed | RunPhase::Cancelled | RunPhase::RolledBack)
    }

    pub fn is_running(&self) -> bool {
        matches!(self.phase, RunPhase::Running | RunPhase::Pending)
    }

    pub fn duration_ms(&self) -> Option<i64> {
        match (self.started_at, self.completed_at) {
            (Some(start), Some(end)) => Some((end - start).num_milliseconds()),
            (Some(start), None) => Some((Utc::now() - start).num_milliseconds()),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunInstanceQuery {
    pub entity_id: Option<String>,
    pub config_id: Option<String>,
    pub phase: Option<RunPhase>,
    pub page: u32,
    pub page_size: u32,
}

impl Default for RunInstanceQuery {
    fn default() -> Self {
        Self {
            entity_id: None,
            config_id: None,
            phase: None,
            page: 1,
            page_size: 20,
        }
    }
}
