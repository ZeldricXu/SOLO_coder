use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct Issue {
    pub id: Uuid,
    pub merge_request_id: Option<Uuid>,
    pub file_path: Option<String>,
    pub line_no: Option<i32>,
    pub title: String,
    pub description: String,
    pub severity: String,
    pub status: String,
    pub reporter_id: Uuid,
    pub assignee_id: Option<Uuid>,
    pub code_snippet: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct IssueWithDetails {
    pub id: Uuid,
    pub merge_request_id: Option<Uuid>,
    pub merge_request_title: Option<String>,
    pub repo_name: Option<String>,
    pub file_path: Option<String>,
    pub line_no: Option<i32>,
    pub title: String,
    pub description: String,
    pub severity: String,
    pub status: String,
    pub reporter_id: Uuid,
    pub reporter_name: String,
    pub reporter_avatar: Option<String>,
    pub assignee_id: Option<Uuid>,
    pub assignee_name: Option<String>,
    pub assignee_avatar: Option<String>,
    pub code_snippet: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreateIssueRequest {
    pub merge_request_id: Option<Uuid>,
    pub file_path: Option<String>,
    pub line_no: Option<i32>,
    pub title: String,
    pub description: String,
    pub severity: String,
    pub assignee_id: Option<Uuid>,
    pub code_snippet: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateIssueRequest {
    pub title: Option<String>,
    pub description: Option<String>,
    pub severity: Option<String>,
    pub assignee_id: Option<Uuid>,
    pub code_snippet: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateIssueStatusRequest {
    pub status: String,
}

#[derive(Debug, Deserialize)]
pub struct AssignIssueRequest {
    pub assignee_id: Uuid,
}

#[derive(Debug, Deserialize)]
pub struct IssueQuery {
    pub merge_request_id: Option<Uuid>,
    pub repo_id: Option<Uuid>,
    pub severity: Option<String>,
    pub status: Option<String>,
    pub reporter_id: Option<Uuid>,
    pub assignee_id: Option<Uuid>,
    pub page: Option<i32>,
    pub per_page: Option<i32>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum IssueSeverity {
    Critical,
    Major,
    Minor,
    Info,
}

impl IssueSeverity {
    pub fn as_str(&self) -> &str {
        match self {
            IssueSeverity::Critical => "critical",
            IssueSeverity::Major => "major",
            IssueSeverity::Minor => "minor",
            IssueSeverity::Info => "info",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "critical" => Some(IssueSeverity::Critical),
            "major" => Some(IssueSeverity::Major),
            "minor" => Some(IssueSeverity::Minor),
            "info" => Some(IssueSeverity::Info),
            _ => None,
        }
    }

    pub fn color(&self) -> &str {
        match self {
            IssueSeverity::Critical => "#EF4444",
            IssueSeverity::Major => "#F59E0B",
            IssueSeverity::Minor => "#3B82F6",
            IssueSeverity::Info => "#8B5CF6",
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum IssueStatus {
    Open,
    InProgress,
    PendingReview,
    Resolved,
    Closed,
}

impl IssueStatus {
    pub fn as_str(&self) -> &str {
        match self {
            IssueStatus::Open => "open",
            IssueStatus::InProgress => "in_progress",
            IssueStatus::PendingReview => "pending_review",
            IssueStatus::Resolved => "resolved",
            IssueStatus::Closed => "closed",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "open" => Some(IssueStatus::Open),
            "in_progress" => Some(IssueStatus::InProgress),
            "pending_review" => Some(IssueStatus::PendingReview),
            "resolved" => Some(IssueStatus::Resolved),
            "closed" => Some(IssueStatus::Closed),
            _ => None,
        }
    }

    pub fn can_transition_to(&self, next: &IssueStatus) -> bool {
        match (self, next) {
            (IssueStatus::Open, IssueStatus::InProgress) => true,
            (IssueStatus::Open, IssueStatus::Closed) => true,
            (IssueStatus::InProgress, IssueStatus::PendingReview) => true,
            (IssueStatus::InProgress, IssueStatus::Closed) => true,
            (IssueStatus::PendingReview, IssueStatus::Resolved) => true,
            (IssueStatus::PendingReview, IssueStatus::InProgress) => true,
            (IssueStatus::Resolved, IssueStatus::Closed) => true,
            (IssueStatus::Resolved, IssueStatus::Open) => true,
            _ => false,
        }
    }
}
