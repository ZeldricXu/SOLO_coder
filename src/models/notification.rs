use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct Notification {
    pub id: Uuid,
    pub user_id: Uuid,
    pub type_: String,
    pub title: String,
    pub content: String,
    pub related_url: Option<String>,
    pub read: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum NotificationType {
    NewReview,
    NewComment,
    Mention,
    IssueAssigned,
    IssueStatusChanged,
    MrStatusChanged,
    ChecklistCompleted,
    DailyDigest,
    System,
}

impl NotificationType {
    pub fn as_str(&self) -> &str {
        match self {
            NotificationType::NewReview => "new_review",
            NotificationType::NewComment => "new_comment",
            NotificationType::Mention => "mention",
            NotificationType::IssueAssigned => "issue_assigned",
            NotificationType::IssueStatusChanged => "issue_status_changed",
            NotificationType::MrStatusChanged => "mr_status_changed",
            NotificationType::ChecklistCompleted => "checklist_completed",
            NotificationType::DailyDigest => "daily_digest",
            NotificationType::System => "system",
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct NotificationSettings {
    pub id: Uuid,
    pub user_id: Uuid,
    pub email_enabled: bool,
    pub slack_enabled: bool,
    pub dingtalk_enabled: bool,
    pub slack_webhook_url: Option<String>,
    pub dingtalk_webhook_url: Option<String>,
    pub on_new_review: bool,
    pub on_comment: bool,
    pub on_mention: bool,
    pub on_issue_assigned: bool,
    pub daily_digest: bool,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateNotificationSettingsRequest {
    pub email_enabled: Option<bool>,
    pub slack_enabled: Option<bool>,
    pub dingtalk_enabled: Option<bool>,
    pub slack_webhook_url: Option<String>,
    pub dingtalk_webhook_url: Option<String>,
    pub on_new_review: Option<bool>,
    pub on_comment: Option<bool>,
    pub on_mention: Option<bool>,
    pub on_issue_assigned: Option<bool>,
    pub daily_digest: Option<bool>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct NotificationQuery {
    pub type_: Option<String>,
    pub read: Option<bool>,
    pub page: Option<i32>,
    pub per_page: Option<i32>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct MarkReadRequest {
    pub notification_ids: Vec<Uuid>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ImWebhookPayload {
    pub text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub markdown: Option<ImMarkdownContent>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub at: Option<ImAtMention>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ImMarkdownContent {
    pub title: String,
    pub text: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ImAtMention {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub at_mobiles: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub at_user_ids: Option<Vec<String>>,
    pub is_at_all: bool,
}
