use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct ChecklistTemplate {
    pub id: Uuid,
    pub name: String,
    pub description: Option<String>,
    pub scope: String,
    pub scope_id: Option<Uuid>,
    pub parent_id: Option<Uuid>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct ChecklistItemTemplate {
    pub id: Uuid,
    pub template_id: Uuid,
    pub group_name: String,
    pub title: String,
    pub description: Option<String>,
    pub order_index: i32,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ChecklistTemplateWithItems {
    pub id: Uuid,
    pub name: String,
    pub description: Option<String>,
    pub scope: String,
    pub scope_id: Option<Uuid>,
    pub parent_id: Option<Uuid>,
    pub parent_name: Option<String>,
    pub items: Vec<ChecklistItemTemplate>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreateChecklistTemplateRequest {
    pub name: String,
    pub description: Option<String>,
    pub scope: String,
    pub scope_id: Option<Uuid>,
    pub parent_id: Option<Uuid>,
    pub items: Vec<ChecklistItemRequest>,
}

#[derive(Debug, Deserialize, Clone)]
pub struct ChecklistItemRequest {
    pub group_name: String,
    pub title: String,
    pub description: Option<String>,
    pub order_index: i32,
}

#[derive(Debug, Deserialize)]
pub struct UpdateChecklistTemplateRequest {
    pub name: Option<String>,
    pub description: Option<String>,
    pub parent_id: Option<Uuid>,
    pub items: Option<Vec<ChecklistItemRequest>>,
}

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct ReviewChecklist {
    pub id: Uuid,
    pub merge_request_id: Uuid,
    pub template_id: Uuid,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct ReviewChecklistItem {
    pub id: Uuid,
    pub review_checklist_id: Uuid,
    pub item_template_id: Uuid,
    pub checked: bool,
    pub checked_by: Option<Uuid>,
    pub checked_at: Option<DateTime<Utc>>,
    pub comment: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ReviewChecklistWithDetails {
    pub id: Uuid,
    pub merge_request_id: Uuid,
    pub template_id: Uuid,
    pub template_name: String,
    pub items: Vec<ReviewChecklistItemWithDetails>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ReviewChecklistItemWithDetails {
    pub id: Uuid,
    pub item_template_id: Uuid,
    pub group_name: String,
    pub title: String,
    pub description: Option<String>,
    pub checked: bool,
    pub checked_by: Option<Uuid>,
    pub checked_by_name: Option<String>,
    pub checked_at: Option<DateTime<Utc>>,
    pub comment: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct CheckItemRequest {
    pub checked: bool,
    pub comment: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum ChecklistScope {
    Organization,
    Team,
    Repository,
}

impl ChecklistScope {
    pub fn as_str(&self) -> &str {
        match self {
            ChecklistScope::Organization => "organization",
            ChecklistScope::Team => "team",
            ChecklistScope::Repository => "repository",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "organization" => Some(ChecklistScope::Organization),
            "team" => Some(ChecklistScope::Team),
            "repository" => Some(ChecklistScope::Repository),
            _ => None,
        }
    }
}
