use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Run {
    pub run_id: String,
    pub entity_id: String,
    pub phase: String,
    pub progress: f64,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_detail: Option<String>,
}

impl Run {
    pub fn new(entity_id: impl Into<String>) -> Self {
        Self {
            run_id: crate::models::IdGenerator::generate("run"),
            entity_id: entity_id.into(),
            phase: "pending".to_string(),
            progress: 0.0,
            started_at: Utc::now(),
            completed_at: None,
            error_detail: None,
        }
    }

    pub fn set_phase(&mut self, phase: impl Into<String>) {
        self.phase = phase.into();
    }

    pub fn set_progress(&mut self, progress: f64) {
        self.progress = progress.clamp(0.0, 1.0);
    }

    pub fn complete(&mut self, success: bool, error: Option<String>) {
        self.phase = if success { "completed".to_string() } else { "failed".to_string() };
        self.progress = if success { 1.0 } else { self.progress };
        self.completed_at = Some(Utc::now());
        self.error_detail = error;
    }

    pub fn is_finished(&self) -> bool {
        self.phase == "completed" || self.phase == "failed"
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunStatus {
    pub id: String,
    pub status: String,
    pub progress: f64,
    pub phase: String,
}

impl From<&Run> for RunStatus {
    fn from(run: &Run) -> Self {
        Self {
            id: run.run_id.clone(),
            status: if run.is_finished() {
                run.phase.clone()
            } else {
                "running".to_string()
            },
            progress: run.progress,
            phase: run.phase.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_run_lifecycle() {
        let mut run = Run::new("ent_123");
        assert_eq!(run.phase, "pending");
        assert_eq!(run.progress, 0.0);
        
        run.set_phase("executing");
        run.set_progress(0.5);
        assert_eq!(run.phase, "executing");
        assert_eq!(run.progress, 0.5);
        
        run.complete(true, None);
        assert!(run.is_finished());
        assert_eq!(run.phase, "completed");
        assert_eq!(run.progress, 1.0);
    }
}
