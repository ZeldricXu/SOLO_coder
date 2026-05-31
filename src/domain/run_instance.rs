use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunInstance {
    pub run_id: String,
    pub entity_id: String,
    pub phase: String,
    pub progress: f64,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_detail: Option<String>,
    pub metadata: std::collections::HashMap<String, String>,
}

impl RunInstance {
    pub fn new(entity_id: impl Into<String>) -> Self {
        Self {
            run_id: format!("run_{}", Uuid::new_v4().simple()),
            entity_id: entity_id.into(),
            phase: "initializing".to_string(),
            progress: 0.0,
            started_at: Utc::now(),
            completed_at: None,
            error_detail: None,
            metadata: std::collections::HashMap::new(),
        }
    }

    pub fn update_phase(&mut self, phase: impl Into<String>, progress: f64) {
        self.phase = phase.into();
        self.progress = progress.clamp(0.0, 1.0);
    }

    pub fn complete(&mut self) {
        self.phase = "completed".to_string();
        self.progress = 1.0;
        self.completed_at = Some(Utc::now());
    }

    pub fn fail(&mut self, error: impl Into<String>) {
        self.phase = "failed".to_string();
        self.error_detail = Some(error.into());
        self.completed_at = Some(Utc::now());
    }

    pub fn is_completed(&self) -> bool {
        self.completed_at.is_some()
    }

    pub fn duration_seconds(&self) -> i64 {
        let end = self.completed_at.unwrap_or_else(Utc::now);
        (end - self.started_at).num_seconds()
    }

    pub fn set_metadata(&mut self, key: impl Into<String>, value: impl Into<String>) {
        self.metadata.insert(key.into(), value.into());
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum InstancePhase {
    Initializing,
    Validating,
    Processing,
    Aggregating,
    Finalizing,
    Completed,
    Failed,
}

impl InstancePhase {
    pub fn as_str(&self) -> &'static str {
        match self {
            InstancePhase::Initializing => "initializing",
            InstancePhase::Validating => "validating",
            InstancePhase::Processing => "processing",
            InstancePhase::Aggregating => "aggregating",
            InstancePhase::Finalizing => "finalizing",
            InstancePhase::Completed => "completed",
            InstancePhase::Failed => "failed",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "initializing" => Some(InstancePhase::Initializing),
            "validating" => Some(InstancePhase::Validating),
            "processing" => Some(InstancePhase::Processing),
            "aggregating" => Some(InstancePhase::Aggregating),
            "finalizing" => Some(InstancePhase::Finalizing),
            "completed" => Some(InstancePhase::Completed),
            "failed" => Some(InstancePhase::Failed),
            _ => None,
        }
    }
}
