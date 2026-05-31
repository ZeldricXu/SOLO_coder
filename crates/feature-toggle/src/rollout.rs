use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};
use crate::models::{FeatureToggle, UserContext, EvaluationResult, ToggleStatus, RolloutStrategy};
use crate::segmentation::UserSegmentation;

pub struct RolloutEngine;

impl RolloutEngine {
    pub fn evaluate(toggle: &FeatureToggle, user_ctx: &UserContext) -> EvaluationResult {
        if toggle.status == ToggleStatus::Off {
            return EvaluationResult {
                enabled: false,
                reason: "Toggle is disabled".to_string(),
            };
        }

        if !UserSegmentation::is_in_segment(user_ctx, toggle) {
            return EvaluationResult {
                enabled: false,
                reason: "User not in target segment".to_string(),
            };
        }

        match toggle.strategy {
            RolloutStrategy::Percentage => {
                if Self::rollout_by_percentage(&user_ctx.user_id, toggle.rollout_percentage) {
                    EvaluationResult {
                        enabled: true,
                        reason: "User in rollout percentage".to_string(),
                    }
                } else {
                    EvaluationResult {
                        enabled: false,
                        reason: "User not in rollout percentage".to_string(),
                    }
                }
            }
            RolloutStrategy::Gradual => {
                if Self::rollout_by_percentage(&user_ctx.user_id, toggle.rollout_percentage) {
                    EvaluationResult {
                        enabled: true,
                        reason: "Gradual rollout enabled".to_string(),
                    }
                } else {
                    EvaluationResult {
                        enabled: false,
                        reason: "Gradual rollout not enabled".to_string(),
                    }
                }
            }
            _ => EvaluationResult {
                enabled: true,
                reason: "Toggle is enabled".to_string(),
            },
        }
    }

    pub fn rollout_by_percentage(user_id: &str, percentage: u8) -> bool {
        let mut hasher = DefaultHasher::new();
        user_id.hash(&mut hasher);
        let hash = hasher.finish();
        (hash % 100) < percentage as u64
    }
}
