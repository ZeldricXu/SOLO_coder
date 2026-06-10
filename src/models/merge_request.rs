use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct MergeRequest {
    pub id: Uuid,
    pub repo_id: Uuid,
    pub provider: String,
    pub provider_id: String,
    pub title: String,
    pub description: Option<String>,
    pub source_branch: String,
    pub target_branch: String,
    pub author_id: Uuid,
    pub status: String,
    pub diff_snapshot_key: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct MergeRequestWithDetails {
    pub id: Uuid,
    pub repo_id: Uuid,
    pub repo_name: String,
    pub provider: String,
    pub provider_id: String,
    pub title: String,
    pub description: Option<String>,
    pub source_branch: String,
    pub target_branch: String,
    pub author_id: Uuid,
    pub author_name: String,
    pub author_avatar: Option<String>,
    pub status: String,
    pub comment_count: i64,
    pub unresolved_comment_count: i64,
    pub issue_count: i64,
    pub diff_snapshot_key: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct MergeRequestQuery {
    pub repo_id: Option<Uuid>,
    pub status: Option<String>,
    pub author_id: Option<Uuid>,
    pub reviewer_id: Option<Uuid>,
    pub page: Option<i32>,
    pub per_page: Option<i32>,
}

#[derive(Debug, Deserialize)]
pub struct CreateMergeRequestRequest {
    pub provider: String,
    pub provider_id: String,
    pub title: String,
    pub description: Option<String>,
    pub source_branch: String,
    pub target_branch: String,
    pub author_provider_id: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum MergeRequestStatus {
    Open,
    Reviewing,
    Approved,
    ChangesRequested,
    Merged,
    Closed,
}

impl MergeRequestStatus {
    pub fn as_str(&self) -> &str {
        match self {
            MergeRequestStatus::Open => "open",
            MergeRequestStatus::Reviewing => "reviewing",
            MergeRequestStatus::Approved => "approved",
            MergeRequestStatus::ChangesRequested => "changes_requested",
            MergeRequestStatus::Merged => "merged",
            MergeRequestStatus::Closed => "closed",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "open" => Some(MergeRequestStatus::Open),
            "reviewing" => Some(MergeRequestStatus::Reviewing),
            "approved" => Some(MergeRequestStatus::Approved),
            "changes_requested" => Some(MergeRequestStatus::ChangesRequested),
            "merged" => Some(MergeRequestStatus::Merged),
            "closed" => Some(MergeRequestStatus::Closed),
            _ => None,
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ReviewerAssignment {
    pub id: Uuid,
    pub merge_request_id: Uuid,
    pub user_id: Uuid,
    pub username: String,
    pub avatar_url: Option<String>,
    pub assigned_at: DateTime<Utc>,
    pub review_status: Option<String>,
    pub reviewed_at: Option<DateTime<Utc>>,
}
