use std::collections::HashMap;
use crate::models::{FeatureToggle, UserContext, UserAttribute};

pub struct UserSegmentation;

impl UserSegmentation {
    pub fn match_by_team(team: &str, toggle: &FeatureToggle) -> bool {
        toggle.whitelist_teams.contains(&team.to_string())
    }

    pub fn match_by_user(user_id: &str, toggle: &FeatureToggle) -> bool {
        toggle.whitelist_users.contains(&user_id.to_string())
    }

    pub fn match_by_attributes(user_attrs: &HashMap<String, String>, toggle_attrs: &[UserAttribute]) -> bool {
        if toggle_attrs.is_empty() {
            return true;
        }
        for attr in toggle_attrs {
            if let Some(user_val) = user_attrs.get(&attr.key) {
                if !attr.values.contains(user_val) {
                    return false;
                }
            } else {
                return false;
            }
        }
        true
    }

    pub fn is_in_segment(user_ctx: &UserContext, toggle: &FeatureToggle) -> bool {
        match toggle.strategy {
            crate::models::RolloutStrategy::UserList => {
                Self::match_by_user(&user_ctx.user_id, toggle)
            }
            crate::models::RolloutStrategy::TeamList => {
                Self::match_by_team(&user_ctx.team, toggle)
            }
            crate::models::RolloutStrategy::AttributeMatch => {
                Self::match_by_attributes(&user_ctx.attributes, &toggle.attributes)
            }
            crate::models::RolloutStrategy::Gradual => {
                Self::match_by_user(&user_ctx.user_id, toggle)
                    || Self::match_by_team(&user_ctx.team, toggle)
                    || Self::match_by_attributes(&user_ctx.attributes, &toggle.attributes)
            }
            _ => true,
        }
    }
}
