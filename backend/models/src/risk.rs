use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::FromRow;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, FromRow)]
pub struct RiskEvent {
    pub id: Uuid,
    pub event_type: String,
    pub user_id: Option<Uuid>,
    pub auction_id: Option<Uuid>,
    pub severity: String,
    pub description: String,
    pub metadata: Option<serde_json::Value>,
    pub reviewed: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RiskAssessmentResult {
    pub passed: bool,
    pub score: f64,
    pub flags: Vec<String>,
    pub recommendations: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SuspiciousActivity {
    pub user_id: Uuid,
    pub activity_type: String,
    pub risk_score: f64,
    pub details: serde_json::Value,
    pub detected_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ImageModerationResult {
    pub safe: bool,
    pub confidence: f32,
    pub categories: Vec<String>,
    pub flagged_content: Option<Vec<String>>,
}
