use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct AiReview {
    pub id: Uuid,
    pub merge_request_id: Uuid,
    pub status: String,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct AiSuggestion {
    pub id: Uuid,
    pub ai_review_id: Uuid,
    pub file_path: String,
    pub line_no: i32,
    pub category: String,
    pub severity: String,
    pub title: String,
    pub description: String,
    pub suggestion: String,
    pub status: String,
    pub acted_by: Option<Uuid>,
    pub acted_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct AiReviewWithSuggestions {
    pub id: Uuid,
    pub merge_request_id: Uuid,
    pub status: String,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub suggestions: Vec<AiSuggestionWithDetails>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct AiSuggestionWithDetails {
    pub id: Uuid,
    pub ai_review_id: Uuid,
    pub file_path: String,
    pub line_no: i32,
    pub category: String,
    pub severity: String,
    pub title: String,
    pub description: String,
    pub suggestion: String,
    pub status: String,
    pub acted_by: Option<Uuid>,
    pub acted_by_name: Option<String>,
    pub acted_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize)]
pub struct TriggerAiScanRequest {
    pub file_paths: Option<Vec<String>>,
    pub scan_types: Option<Vec<String>>,
}

#[derive(Debug, Deserialize)]
pub struct ActOnSuggestionRequest {
    pub action: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum AiReviewStatus {
    Pending,
    Running,
    Completed,
    Failed,
}

impl AiReviewStatus {
    pub fn as_str(&self) -> &str {
        match self {
            AiReviewStatus::Pending => "pending",
            AiReviewStatus::Running => "running",
            AiReviewStatus::Completed => "completed",
            AiReviewStatus::Failed => "failed",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "pending" => Some(AiReviewStatus::Pending),
            "running" => Some(AiReviewStatus::Running),
            "completed" => Some(AiReviewStatus::Completed),
            "failed" => Some(AiReviewStatus::Failed),
            _ => None,
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum AiSuggestionStatus {
    Pending,
    Accepted,
    Ignored,
}

impl AiSuggestionStatus {
    pub fn as_str(&self) -> &str {
        match self {
            AiSuggestionStatus::Pending => "pending",
            AiSuggestionStatus::Accepted => "accepted",
            AiSuggestionStatus::Ignored => "ignored",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "pending" => Some(AiSuggestionStatus::Pending),
            "accepted" => Some(AiSuggestionStatus::Accepted),
            "ignored" => Some(AiSuggestionStatus::Ignored),
            _ => None,
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub enum AiScanCategory {
    CodeStyle,
    BugPattern,
    Security,
    Performance,
    BestPractice,
    Maintainability,
}

impl AiScanCategory {
    pub fn as_str(&self) -> &str {
        match self {
            AiScanCategory::CodeStyle => "code_style",
            AiScanCategory::BugPattern => "bug_pattern",
            AiScanCategory::Security => "security",
            AiScanCategory::Performance => "performance",
            AiScanCategory::BestPractice => "best_practice",
            AiScanCategory::Maintainability => "maintainability",
        }
    }

    pub fn all() -> Vec<Self> {
        vec![
            AiScanCategory::CodeStyle,
            AiScanCategory::BugPattern,
            AiScanCategory::Security,
            AiScanCategory::Performance,
            AiScanCategory::BestPractice,
            AiScanCategory::Maintainability,
        ]
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct LlmMessage {
    pub role: String,
    pub content: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct LlmRequest {
    pub model: String,
    pub messages: Vec<LlmMessage>,
    pub max_tokens: u32,
    pub temperature: f32,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct LlmResponse {
    pub choices: Vec<LlmChoice>,
    pub usage: Option<LlmUsage>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct LlmChoice {
    pub message: LlmMessage,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct LlmUsage {
    pub prompt_tokens: u32,
    pub completion_tokens: u32,
    pub total_tokens: u32,
}
