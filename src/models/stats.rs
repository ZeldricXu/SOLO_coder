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

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct RepoHealthItem {
    pub repo_id: Uuid,
    pub repo_name: String,
    pub health_score: f64,
    pub coverage_rate: f64,
    pub issue_density: f64,
    pub avg_response_time_hours: f64,
    pub active_mrs: i64,
    pub trend: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct RepoHealthRanking {
    pub benchmark_date: String,
    pub compare_date: String,
    pub items: Vec<RepoHealthItem>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ContributorItem {
    pub rank: i32,
    pub user_id: Uuid,
    pub username: String,
    pub avatar_url: Option<String>,
    pub reviews_done: i64,
    pub issues_found: i64,
    pub issues_fixed: i64,
    pub comments_count: i64,
    pub lines_changed: i64,
    pub score: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct TeamContributionRanking {
    pub benchmark_date: String,
    pub team_id: Option<Uuid>,
    pub top_n: i32,
    pub items: Vec<ContributorItem>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct IssueTypeData {
    pub issue_type: String,
    pub count: i64,
    pub percentage: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct IssueTypeTrendCompare {
    pub start_date: String,
    pub end_date: String,
    pub compare_start_date: String,
    pub compare_end_date: String,
    pub current: Vec<IssueTypeData>,
    pub previous: Vec<IssueTypeData>,
    pub change_rate: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct OrgStatsOverview {
    pub start_date: String,
    pub end_date: String,
    pub compare_start_date: String,
    pub compare_end_date: String,
    pub total_repos: i64,
    pub total_mrs: i64,
    pub total_mrs_previous: i64,
    pub mrs_change_rate: f64,
    pub reviewed_mrs: i64,
    pub reviewed_mrs_previous: i64,
    pub coverage_rate: f64,
    pub coverage_rate_previous: f64,
    pub total_issues: i64,
    pub total_issues_previous: i64,
    pub issues_change_rate: f64,
    pub resolved_issues: i64,
    pub resolved_issues_previous: i64,
    pub fix_rate: f64,
    pub fix_rate_previous: f64,
    pub avg_response_time_hours: f64,
    pub avg_response_time_hours_previous: f64,
    pub active_contributors: i64,
    pub active_contributors_previous: i64,
    pub avg_health_score: f64,
    pub avg_health_score_previous: f64,
}
