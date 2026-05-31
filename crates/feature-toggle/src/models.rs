use serde::{Serialize, Deserialize};
use uuid::Uuid;
use chrono::DateTime;
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum ToggleStatus {
    On,
    Off,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub enum RolloutStrategy {
    Percentage,
    UserList,
    TeamList,
    AttributeMatch,
    Gradual,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct UserAttribute {
    pub key: String,
    pub values: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeatureToggle {
    pub id: Uuid,
    pub name: String,
    pub description: String,
    pub status: ToggleStatus,
    pub strategy: RolloutStrategy,
    pub rollout_percentage: u8,
    pub whitelist_users: Vec<String>,
    pub whitelist_teams: Vec<String>,
    pub attributes: Vec<UserAttribute>,
    pub created_at: DateTime<chrono::Utc>,
    pub updated_at: DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UserContext {
    pub user_id: String,
    pub team: String,
    pub attributes: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EvaluationResult {
    pub enabled: bool,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToggleHistory {
    pub id: Uuid,
    pub toggle_id: Uuid,
    pub event_type: String,
    pub description: String,
    pub timestamp: DateTime<chrono::Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EvaluationRequest {
    pub toggle_id: Uuid,
    pub user_ctx: UserContext,
}
