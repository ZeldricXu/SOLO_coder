use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum Stage {
    None,
    Staging,
    Production,
    Archived,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StageTransition {
    pub transition_id: String,
    pub model_id: String,
    pub version_id: String,
    pub from_stage: Stage,
    pub to_stage: Stage,
    pub reason: String,
    pub transitioned_by: String,
    pub transitioned_at: chrono::DateTime<chrono::Utc>,
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StageTransitionRequest {
    pub model_id: String,
    pub version_id: String,
    pub to_stage: Stage,
    pub reason: String,
    pub transitioned_by: String,
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StageInfo {
    pub model_id: String,
    pub version_id: String,
    pub stage: Stage,
    pub assigned_at: chrono::DateTime<chrono::Utc>,
    pub assigned_by: String,
    pub note: Option<String>,
}

pub struct StageManager {
    stages: parking_lot::Mutex<HashMap<(String, Stage), StageInfo>>,
    transitions: parking_lot::Mutex<Vec<StageTransition>>,
    max_transitions_per_model: usize,
}

impl StageManager {
    pub fn new() -> Self {
        Self {
            stages: parking_lot::Mutex::new(HashMap::new()),
            transitions: parking_lot::Mutex::new(Vec::new()),
            max_transitions_per_model: 1000,
        }
    }

    pub fn get_stage(&self, model_id: &str, stage: &Stage) -> Option<StageInfo> {
        self.stages.lock().get(&(model_id.to_string(), stage.clone())).cloned()
    }

    pub fn get_all_stages_for_model(&self, model_id: &str) -> HashMap<Stage, StageInfo> {
        let stages = self.stages.lock();
        let mut result = HashMap::new();
        for ((mid, stage), info) in stages.iter() {
            if mid == model_id {
                result.insert(stage.clone(), info.clone());
            }
        }
        result
    }

    pub fn get_model_version_in_stage(&self, model_id: &str, stage: &Stage) -> Option<String> {
        self.get_stage(model_id, stage).map(|s| s.version_id)
    }

    pub fn transition_stage(
        &self,
        request: StageTransitionRequest,
    ) -> Result<StageTransition, String> {
        let model_id = request.model_id.clone();
        let version_id = request.version_id.clone();
        let to_stage = request.to_stage.clone();

        let from_stage = self.get_current_stage(&model_id).unwrap_or(Stage::None);

        if from_stage == to_stage {
            return Err(format!("Model is already in {:?} stage", to_stage));
        }

        self.validate_transition(&from_stage, &to_stage)?;

        let transition = StageTransition {
            transition_id: format!("trans_{}", crate::utils::id::generate_id()),
            model_id: model_id.clone(),
            version_id: version_id.clone(),
            from_stage: from_stage.clone(),
            to_stage: to_stage.clone(),
            reason: request.reason,
            transitioned_by: request.transitioned_by,
            transitioned_at: chrono::Utc::now(),
            metadata: request.metadata,
        };

        let mut stages = self.stages.lock();
        
        if let Some(current) = stages.remove(&(model_id.clone(), from_stage.clone())) {
            stages.insert(
                (model_id.clone(), Stage::Archived),
                StageInfo {
                    model_id: current.model_id,
                    version_id: current.version_id,
                    stage: Stage::Archived,
                    assigned_at: chrono::Utc::now(),
                    assigned_by: transition.transitioned_by.clone(),
                    note: Some(format!("Archived from {:?}", from_stage)),
                },
            );
        }

        stages.insert(
            (model_id.clone(), to_stage.clone()),
            StageInfo {
                model_id: model_id.clone(),
                version_id: version_id.clone(),
                stage: to_stage.clone(),
                assigned_at: chrono::Utc::now(),
                assigned_by: transition.transitioned_by.clone(),
                note: None,
            },
        );

        let mut transitions = self.transitions.lock();
        transitions.push(transition.clone());

        let model_transitions: Vec<StageTransition> = transitions
            .iter()
            .filter(|t| t.model_id == model_id)
            .cloned()
            .collect();

        if model_transitions.len() > self.max_transitions_per_model {
            let excess = model_transitions.len() - self.max_transitions_per_model;
            transitions.retain(|t| t.model_id != model_id || 
                !model_transitions.iter().take(excess).any(|mt| mt.transition_id == t.transition_id)
            );
        }

        Ok(transition)
    }

    pub fn get_current_stage(&self, model_id: &str) -> Option<Stage> {
        let stages = self.stages.lock();
        for stage in [Stage::Production, Stage::Staging, Stage::Archived, Stage::None].iter() {
            if stages.contains_key(&(model_id.to_string(), stage.clone())) {
                return Some(stage.clone());
            }
        }
        None
    }

    pub fn get_transition_history(
        &self,
        model_id: &str,
        limit: usize,
    ) -> Vec<StageTransition> {
        let transitions = self.transitions.lock();
        let mut history: Vec<StageTransition> = transitions
            .iter()
            .filter(|t| t.model_id == model_id)
            .cloned()
            .collect();

        history.sort_by(|a, b| b.transitioned_at.cmp(&a.transitioned_at));
        history.into_iter().take(limit).collect()
    }

    pub fn list_models_in_stage(&self, stage: &Stage) -> Vec<StageInfo> {
        let stages = self.stages.lock();
        stages
            .iter()
            .filter(|((_, s), _)| s == stage)
            .map(|(_, info)| info.clone())
            .collect()
    }

    pub fn clear_stage(&self, model_id: &str, stage: &Stage) -> Result<(), String> {
        let mut stages = self.stages.lock();
        if stages.remove(&(model_id.to_string(), stage.clone())).is_none() {
            return Err(format!("Model {} not found in stage {:?}", model_id, stage));
        }
        Ok(())
    }

    fn validate_transition(&self, from: &Stage, to: &Stage) -> Result<(), String> {
        match (from, to) {
            (Stage::None, Stage::Staging) => Ok(()),
            (Stage::None, Stage::Production) => Ok(()),
            (Stage::Staging, Stage::Production) => Ok(()),
            (Stage::Staging, Stage::Archived) => Ok(()),
            (Stage::Production, Stage::Staging) => Ok(()),
            (Stage::Production, Stage::Archived) => Ok(()),
            (Stage::Archived, Stage::Staging) => Ok(()),
            (Stage::Archived, Stage::Production) => Ok(()),
            _ => Err(format!("Invalid transition from {:?} to {:?}", from, to)),
        }
    }

    pub fn get_stage_name(stage: &Stage) -> String {
        match stage {
            Stage::None => "None",
            Stage::Staging => "Staging",
            Stage::Production => "Production",
            Stage::Archived => "Archived",
        }.to_string()
    }

    pub fn get_stage_description(stage: &Stage) -> String {
        match stage {
            Stage::None => "No stage assigned",
            Stage::Staging => "In testing and validation",
            Stage::Production => "Live for production traffic",
            Stage::Archived => "Archived, no longer active",
        }.to_string()
    }
}

impl Default for StageManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[test]
    fn test_stage_manager_creation() {
        let manager = StageManager::new();
        assert_eq!(manager.get_current_stage("model_001"), None);
    }

    #[test]
    fn test_stage_transition_none_to_staging() {
        let manager = StageManager::new();

        let request = StageTransitionRequest {
            model_id: "model_001".to_string(),
            version_id: "ver_001".to_string(),
            to_stage: Stage::Staging,
            reason: "Ready for testing".to_string(),
            transitioned_by: "test_user".to_string(),
            metadata: HashMap::new(),
        };

        let transition = manager.transition_stage(request).unwrap();
        assert_eq!(transition.from_stage, Stage::None);
        assert_eq!(transition.to_stage, Stage::Staging);
        assert_eq!(manager.get_current_stage("model_001"), Some(Stage::Staging));
    }

    #[test]
    fn test_stage_transition_staging_to_production() {
        let manager = StageManager::new();

        manager.transition_stage(StageTransitionRequest {
            model_id: "model_001".to_string(),
            version_id: "ver_001".to_string(),
            to_stage: Stage::Staging,
            reason: "".to_string(),
            transitioned_by: "test".to_string(),
            metadata: HashMap::new(),
        }).unwrap();

        let request = StageTransitionRequest {
            model_id: "model_001".to_string(),
            version_id: "ver_001".to_string(),
            to_stage: Stage::Production,
            reason: "Approved for production".to_string(),
            transitioned_by: "admin".to_string(),
            metadata: HashMap::new(),
        };

        let transition = manager.transition_stage(request).unwrap();
        assert_eq!(transition.from_stage, Stage::Staging);
        assert_eq!(transition.to_stage, Stage::Production);
        assert_eq!(manager.get_current_stage("model_001"), Some(Stage::Production));
    }

    #[test]
    fn test_invalid_transition() {
        let manager = StageManager::new();

        let result = manager.transition_stage(StageTransitionRequest {
            model_id: "model_001".to_string(),
            version_id: "ver_001".to_string(),
            to_stage: Stage::None,
            reason: "".to_string(),
            transitioned_by: "test".to_string(),
            metadata: HashMap::new(),
        });

        assert!(result.is_err());
    }

    #[test]
    fn test_get_model_version_in_stage() {
        let manager = StageManager::new();

        manager.transition_stage(StageTransitionRequest {
            model_id: "model_001".to_string(),
            version_id: "ver_001".to_string(),
            to_stage: Stage::Production,
            reason: "".to_string(),
            transitioned_by: "test".to_string(),
            metadata: HashMap::new(),
        }).unwrap();

        let version_id = manager.get_model_version_in_stage("model_001", &Stage::Production);
        assert_eq!(version_id, Some("ver_001".to_string()));
    }

    #[test]
    fn test_list_models_in_stage() {
        let manager = StageManager::new();

        for i in 0..3 {
            manager.transition_stage(StageTransitionRequest {
                model_id: format!("model_{:03}", i),
                version_id: format!("ver_{:03}", i),
                to_stage: Stage::Production,
                reason: "".to_string(),
                transitioned_by: "test".to_string(),
                metadata: HashMap::new(),
            }).unwrap();
        }

        manager.transition_stage(StageTransitionRequest {
            model_id: "model_003".to_string(),
            version_id: "ver_003".to_string(),
            to_stage: Stage::Staging,
            reason: "".to_string(),
            transitioned_by: "test".to_string(),
            metadata: HashMap::new(),
        }).unwrap();

        let production_models = manager.list_models_in_stage(&Stage::Production);
        assert_eq!(production_models.len(), 3);

        let staging_models = manager.list_models_in_stage(&Stage::Staging);
        assert_eq!(staging_models.len(), 1);
    }

    #[test]
    fn test_transition_history() {
        let manager = StageManager::new();

        manager.transition_stage(StageTransitionRequest {
            model_id: "model_001".to_string(),
            version_id: "ver_001".to_string(),
            to_stage: Stage::Staging,
            reason: "First".to_string(),
            transitioned_by: "test".to_string(),
            metadata: HashMap::new(),
        }).unwrap();

        manager.transition_stage(StageTransitionRequest {
            model_id: "model_001".to_string(),
            version_id: "ver_001".to_string(),
            to_stage: Stage::Production,
            reason: "Second".to_string(),
            transitioned_by: "admin".to_string(),
            metadata: HashMap::new(),
        }).unwrap();

        let history = manager.get_transition_history("model_001", 10);
        assert_eq!(history.len(), 2);
        assert_eq!(history[0].to_stage, Stage::Production);
        assert_eq!(history[1].to_stage, Stage::Staging);
    }

    #[test]
    fn test_archive_on_transition() {
        let manager = StageManager::new();

        manager.transition_stage(StageTransitionRequest {
            model_id: "model_001".to_string(),
            version_id: "ver_001".to_string(),
            to_stage: Stage::Production,
            reason: "".to_string(),
            transitioned_by: "test".to_string(),
            metadata: HashMap::new(),
        }).unwrap();

        manager.transition_stage(StageTransitionRequest {
            model_id: "model_001".to_string(),
            version_id: "ver_002".to_string(),
            to_stage: Stage::Production,
            reason: "New version".to_string(),
            transitioned_by: "admin".to_string(),
            metadata: HashMap::new(),
        }).unwrap();

        let archived = manager.list_models_in_stage(&Stage::Archived);
        assert!(!archived.is_empty());
        assert!(archived.iter().any(|a| a.version_id == "ver_001"));
    }

    #[test]
    fn test_stage_names_and_descriptions() {
        assert_eq!(StageManager::get_stage_name(&Stage::Production), "Production");
        assert_eq!(StageManager::get_stage_name(&Stage::Staging), "Staging");
        
        assert!(!StageManager::get_stage_description(&Stage::Production).is_empty());
        assert!(!StageManager::get_stage_description(&Stage::Archived).is_empty());
    }
}
