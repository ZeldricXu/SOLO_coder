use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct Attachment {
    pub id: Uuid,
    pub attachment_type: String,
    pub target_id: Uuid,
    pub uploader_id: Uuid,
    pub file_name: String,
    pub storage_key: String,
    pub content_type: String,
    pub file_size_bytes: i64,
    pub width: Option<i32>,
    pub height: Option<i32>,
    pub thumbnail_key: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct AttachmentWithDetails {
    pub id: Uuid,
    pub attachment_type: String,
    pub target_id: Uuid,
    pub uploader_id: Uuid,
    pub uploader_name: String,
    pub uploader_avatar: Option<String>,
    pub file_name: String,
    pub storage_key: String,
    pub file_url: String,
    pub thumbnail_url: Option<String>,
    pub content_type: String,
    pub file_size_bytes: i64,
    pub width: Option<i32>,
    pub height: Option<i32>,
    pub thumbnail_key: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct CreateAttachmentRequest {
    pub attachment_type: String,
    pub target_id: Uuid,
    pub file_name: String,
    pub storage_key: String,
    pub content_type: String,
    pub file_size_bytes: i64,
    pub width: Option<i32>,
    pub height: Option<i32>,
    pub thumbnail_key: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct AttachmentQuery {
    pub attachment_type: Option<String>,
    pub target_id: Option<Uuid>,
    pub uploader_id: Option<Uuid>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum AttachmentType {
    Comment,
    Issue,
    AiSuggestion,
}

impl AttachmentType {
    pub fn as_str(&self) -> &str {
        match self {
            AttachmentType::Comment => "comment",
            AttachmentType::Issue => "issue",
            AttachmentType::AiSuggestion => "ai_suggestion",
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct UploadAttachmentResponse {
    pub id: Uuid,
    pub file_name: String,
    pub file_url: String,
    pub thumbnail_url: Option<String>,
    pub file_size_bytes: i64,
    pub content_type: String,
}
