use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ReviewStats {
    pub period: String,
    pub total_mrs: i64,
    pub reviewed_mrs: i64,
    pub coverage_rate: f64,
    pub avg_response_time_hours: f64,
    pub total_issues: i64,
    pub issue_density: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct PersonalStats {
    pub user_id: Uuid,
    pub username: String,
    pub avatar_url: Option<String>,
    pub reviews_done: i64,
    pub issues_found: i64,
    pub issues_fixed: i64,
    pub defect_detection_rate: f64,
    pub fix_rate: f64,
    pub avg_review_time_hours: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct HeatmapData {
    pub file_path: String,
    pub issue_count: i64,
    pub review_count: i64,
    pub density_score: f64,
    pub color_hex: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct CoverageTrend {
    pub date: String,
    pub coverage_rate: f64,
    pub total_mrs: i64,
    pub reviewed_mrs: i64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ResponseTimeTrend {
    pub date: String,
    pub avg_response_hours: f64,
    pub median_response_hours: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct DashboardStats {
    pub total_pending_reviews: i64,
    pub my_pending_reviews: i64,
    pub my_open_issues: i64,
    pub issues_assigned_to_me: i64,
    pub team_review_coverage: f64,
    pub avg_response_time_hours: f64,
    pub recent_activity: Vec<ActivityItem>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ActivityItem {
    pub id: Uuid,
    pub type_: String,
    pub title: String,
    pub description: String,
    pub user_id: Uuid,
    pub username: String,
    pub avatar_url: Option<String>,
    pub related_url: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Deserialize, Clone)]
pub struct StatsQuery {
    pub start_date: Option<String>,
    pub end_date: Option<String>,
    pub repo_id: Option<Uuid>,
    pub team_id: Option<Uuid>,
    pub user_id: Option<Uuid>,
    pub period: Option<String>,
}

impl StatsQuery {
    pub fn sanitize(self) -> Self {
        let period = self.period.unwrap_or_else(|| "month".to_string());
        let valid_periods = ["day", "week", "month", "quarter", "year"];
        let period = if valid_periods.contains(&period.as_str()) {
            period
        } else {
            "month".to_string()
        };
        Self {
            period: Some(period),
            ..self
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct TeamRankingItem {
    pub rank: i32,
    pub user_id: Uuid,
    pub username: String,
    pub avatar_url: Option<String>,
    pub reviews_count: i64,
    pub issues_found: i64,
    pub score: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct IssueBySeverity {
    pub severity: String,
    pub count: i64,
    pub percentage: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct IssueByStatus {
    pub status: String,
    pub count: i64,
    pub percentage: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ExportRequest {
    pub format: String,
    pub start_date: String,
    pub end_date: String,
    pub repo_id: Option<Uuid>,
    pub include: Vec<String>,
}
