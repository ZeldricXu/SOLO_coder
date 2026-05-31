use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum RunPhase {
    Pending,
    Validating,
    Executing,
    Finalizing,
    Completed,
    Failed,
    Rollback,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunInstance {
    pub run_id: String,
    pub entity_id: String,
    pub phase: RunPhase,
    pub progress: f32,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_detail: Option<ErrorDetail>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorDetail {
    pub code: String,
    pub message: String,
    pub stack_trace: Option<String>,
}

impl RunInstance {
    pub fn new(entity_id: String) -> Self {
        Self {
            run_id: format!("run_{}", Uuid::new_v4().simple()),
            entity_id,
            phase: RunPhase::Pending,
            progress: 0.0,
            started_at: Utc::now(),
            completed_at: None,
            error_detail: None,
        }
    }

    pub fn update_phase(&mut self, phase: RunPhase) {
        self.phase = phase;
        if matches!(phase, RunPhase::Completed | RunPhase::Failed) {
            self.completed_at = Some(Utc::now());
        }
    }

    pub fn set_progress(&mut self, progress: f32) {
        self.progress = progress.clamp(0.0, 1.0);
    }

    pub fn mark_failed(&mut self, code: String, message: String) {
        self.phase = RunPhase::Failed;
        self.progress = 0.0;
        self.completed_at = Some(Utc::now());
        self.error_detail = Some(ErrorDetail {
            code,
            message,
            stack_trace: None,
        });
    }

    pub fn mark_completed(&mut self) {
        self.phase = RunPhase::Completed;
        self.progress = 1.0;
        self.completed_at = Some(Utc::now());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_run_instance_lifecycle() {
        let entity_id = "ent_test123".to_string();
        let mut run = RunInstance::new(entity_id.clone());

        assert!(run.run_id.starts_with("run_"));
        assert_eq!(run.entity_id, entity_id);
        assert_eq!(run.phase, RunPhase::Pending);
        assert_eq!(run.progress, 0.0);

        run.update_phase(RunPhase::Executing);
        run.set_progress(0.5);
        assert_eq!(run.phase, RunPhase::Executing);
        assert_eq!(run.progress, 0.5);

        run.mark_completed();
        assert_eq!(run.phase, RunPhase::Completed);
        assert_eq!(run.progress, 1.0);
        assert!(run.completed_at.is_some());
    }

    #[test]
    fn test_run_instance_failure() {
        let mut run = RunInstance::new("ent_test".to_string());
        run.mark_failed("TIMEOUT".to_string(), "Operation timed out".to_string());

        assert_eq!(run.phase, RunPhase::Failed);
        assert!(run.error_detail.is_some());
        let err = run.error_detail.unwrap();
        assert_eq!(err.code, "TIMEOUT");
        assert_eq!(err.message, "Operation timed out");
    }

    #[test]
    fn test_progress_clamping() {
        let mut run = RunInstance::new("ent_test".to_string());
        run.set_progress(1.5);
        assert_eq!(run.progress, 1.0);
        run.set_progress(-0.5);
        assert_eq!(run.progress, 0.0);
    }
}
