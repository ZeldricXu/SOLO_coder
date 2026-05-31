use anyhow::Result;
use uuid::Uuid;
use crate::models::{FeatureToggle, UserContext, EvaluationResult};
use crate::toggle::ToggleManager;
use crate::rollout::RolloutEngine;

pub fn create_toggle(manager: &ToggleManager, toggle: FeatureToggle) -> Result<()> {
    manager.create_toggle(&toggle)
}

pub fn update_toggle(manager: &ToggleManager, id: Uuid, toggle: FeatureToggle) -> Result<()> {
    manager.update_toggle(id, &toggle)
}

pub fn delete_toggle(manager: &ToggleManager, id: Uuid) -> Result<()> {
    manager.delete_toggle(id)
}

pub fn get_toggle(manager: &ToggleManager, id: Uuid) -> Result<Option<FeatureToggle>> {
    manager.get_toggle(id)
}

pub fn list_toggles(manager: &ToggleManager) -> Result<Vec<FeatureToggle>> {
    manager.list_toggles()
}

pub fn evaluate_toggle(toggle: &FeatureToggle, user_ctx: &UserContext) -> EvaluationResult {
    RolloutEngine::evaluate(toggle, user_ctx)
}

pub fn batch_evaluate(toggles: &[FeatureToggle], user_ctx: &UserContext) -> Vec<(Uuid, EvaluationResult)> {
    toggles
        .iter()
        .map(|toggle| (toggle.id, RolloutEngine::evaluate(toggle, user_ctx)))
        .collect()
}
