use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum RunPhase {
    Initializing,
    Validating,
    Processing,
    Finalizing,
    Completed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RunInstance {
    pub run_id: String,
    pub entity_id: String,
    pub phase: RunPhase,
    pub progress: f64,
    pub started_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_detail: Option<String>,
}

impl RunInstance {
    pub fn new(entity_id: impl Into<String>) -> Self {
        Self {
            run_id: format!("run_{}", Uuid::new_v4().simple()),
            entity_id: entity_id.into(),
            phase: RunPhase::Initializing,
            progress: 0.0,
            started_at: Utc::now(),
            completed_at: None,
            error_detail: None,
        }
    }

    pub fn update_phase(&mut self, phase: RunPhase, progress: f64) {
        let is_final = matches!(phase, RunPhase::Completed | RunPhase::Failed | RunPhase::Cancelled);
        self.phase = phase;
        self.progress = progress.clamp(0.0, 1.0);
        
        if is_final {
            self.completed_at = Some(Utc::now());
        }
    }

    pub fn fail(&mut self, error: impl Into<String>) {
        self.phase = RunPhase::Failed;
        self.error_detail = Some(error.into());
        self.completed_at = Some(Utc::now());
    }

    pub fn is_finished(&self) -> bool {
        matches!(
            self.phase,
            RunPhase::Completed | RunPhase::Failed | RunPhase::Cancelled
        )
    }

    pub fn duration(&self) -> Option<chrono::Duration> {
        self.completed_at
            .map(|end| end.signed_duration_since(self.started_at))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_run_instance_creation() {
        let run = RunInstance::new("ent_001");
        
        assert!(run.run_id.starts_with("run_"));
        assert_eq!(run.entity_id, "ent_001");
        assert_eq!(run.phase, RunPhase::Initializing);
        assert_eq!(run.progress, 0.0);
        assert!(run.completed_at.is_none());
        assert!(run.error_detail.is_none());
        assert!(!run.is_finished());
    }

    #[test]
    fn test_phase_update() {
        let mut run = RunInstance::new("ent_001");
        
        run.update_phase(RunPhase::Processing, 0.5);
        assert_eq!(run.phase, RunPhase::Processing);
        assert_eq!(run.progress, 0.5);
        assert!(!run.is_finished());

        run.update_phase(RunPhase::Completed, 1.0);
        assert_eq!(run.phase, RunPhase::Completed);
        assert_eq!(run.progress, 1.0);
        assert!(run.is_finished());
        assert!(run.completed_at.is_some());
        assert!(run.duration().is_some());
    }

    #[test]
    fn test_progress_clamping() {
        let mut run = RunInstance::new("ent_001");
        
        run.update_phase(RunPhase::Processing, 1.5);
        assert_eq!(run.progress, 1.0);

        run.update_phase(RunPhase::Processing, -0.5);
        assert_eq!(run.progress, 0.0);
    }

    #[test]
    fn test_fail() {
        let mut run = RunInstance::new("ent_001");
        run.fail("timeout error");
        
        assert_eq!(run.phase, RunPhase::Failed);
        assert_eq!(run.error_detail, Some("timeout error".to_string()));
        assert!(run.is_finished());
        assert!(run.completed_at.is_some());
    }
}
