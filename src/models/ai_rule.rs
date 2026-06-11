use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct AiRule {
    pub id: Uuid,
    pub organization_id: Uuid,
    pub repo_id: Option<Uuid>,
    pub name: String,
    pub description: Option<String>,
    pub scope: String,
    pub severity_level: String,
    pub custom_prompt: String,
    pub enabled_categories: sqlx::types::Json<Vec<String>>,
    pub min_changed_lines: Option<i32>,
    pub context_lines: Option<i32>,
    pub is_active: bool,
    pub is_default: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreateAiRuleRequest {
    pub organization_id: Uuid,
    pub repo_id: Option<Uuid>,
    pub name: String,
    pub description: Option<String>,
    pub scope: String,
    pub severity_level: String,
    pub custom_prompt: String,
    pub enabled_categories: Vec<String>,
    pub min_changed_lines: Option<i32>,
    pub context_lines: Option<i32>,
    pub is_active: Option<bool>,
    pub is_default: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateAiRuleRequest {
    pub name: Option<String>,
    pub description: Option<String>,
    pub severity_level: Option<String>,
    pub custom_prompt: Option<String>,
    pub enabled_categories: Option<Vec<String>>,
    pub min_changed_lines: Option<i32>,
    pub context_lines: Option<i32>,
    pub is_active: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct AiRuleQuery {
    pub organization_id: Uuid,
    pub repo_id: Option<Uuid>,
    pub scope: Option<String>,
    pub is_active: Option<bool>,
    pub is_default: Option<bool>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum AiRuleSeverity {
    Strict,
    Normal,
    Loose,
}

impl AiRuleSeverity {
    pub fn as_str(&self) -> &str {
        match self {
            AiRuleSeverity::Strict => "strict",
            AiRuleSeverity::Normal => "normal",
            AiRuleSeverity::Loose => "loose",
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum AiRuleScope {
    Organization,
    Repository,
}

impl AiRuleScope {
    pub fn as_str(&self) -> &str {
        match self {
            AiRuleScope::Organization => "organization",
            AiRuleScope::Repository => "repository",
        }
    }
}
