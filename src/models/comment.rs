use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct Comment {
    pub id: Uuid,
    pub merge_request_id: Uuid,
    pub author_id: Uuid,
    pub file_path: Option<String>,
    pub line_no: Option<i32>,
    pub line_type: Option<String>,
    pub content: String,
    pub parent_id: Option<Uuid>,
    pub resolved: bool,
    pub resolved_by: Option<Uuid>,
    pub resolved_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct CommentWithDetails {
    pub id: Uuid,
    pub merge_request_id: Uuid,
    pub author_id: Uuid,
    pub author_name: String,
    pub author_avatar: Option<String>,
    pub file_path: Option<String>,
    pub line_no: Option<i32>,
    pub line_type: Option<String>,
    pub content: String,
    pub parent_id: Option<Uuid>,
    pub resolved: bool,
    pub resolved_by: Option<Uuid>,
    pub resolved_by_name: Option<String>,
    pub resolved_at: Option<DateTime<Utc>>,
    pub replies: Vec<CommentWithDetails>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreateCommentRequest {
    pub file_path: Option<String>,
    pub line_no: Option<i32>,
    pub line_type: Option<String>,
    pub content: String,
    pub parent_id: Option<Uuid>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateCommentRequest {
    pub content: String,
}

#[derive(Debug, Deserialize)]
pub struct ResolveCommentRequest {
    pub resolved: bool,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum CommentType {
    FileLevel,
    LineLevel,
    General,
}

impl CommentType {
    pub fn from_parts(file_path: &Option<String>, line_no: &Option<i32>) -> Self {
        match (file_path, line_no) {
            (Some(_), Some(_)) => CommentType::LineLevel,
            (Some(_), None) => CommentType::FileLevel,
            _ => CommentType::General,
        }
    }
}
