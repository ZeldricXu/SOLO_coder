use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct RepoHealthItem {
    pub repo_id: Uuid,
    pub repo_name: String,
    pub coverage_rate: f64,
    pub avg_response_hours: f64,
    pub issue_density: f64,
    pub health_score: f64,
    pub rank: i32,
    pub trend: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct RepoHealthRanking {
    pub total_repos: i64,
    pub items: Vec<RepoHealthItem>,
    pub avg_health_score: f64,
    pub benchmark_date: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ContributorContribution {
    pub user_id: Uuid,
    pub username: String,
    pub avatar_url: Option<String>,
    pub reviews_done: i64,
    pub comments_written: i64,
    pub issues_found: i64,
    pub issues_fixed: i64,
    pub mrs_merged: i64,
    pub contribution_score: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct TeamContributionRanking {
    pub total_members: i64,
    pub items: Vec<ContributorContribution>,
    pub team_avg_score: f64,
    pub benchmark_date: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct IssueTypeTrendPoint {
    pub date: String,
    pub severity: String,
    pub issue_count: i64,
    pub resolved_count: i64,
    pub avg_resolve_hours: f64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct IssueTypeTrendCompare {
    pub periods: Vec<String>,
    pub current: Vec<IssueTypeTrendPoint>,
    pub previous: Vec<IssueTypeTrendPoint>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct OrgStatsOverview {
    pub total_repos: i64,
    pub total_members: i64,
    pub total_mrs_period: i64,
    pub coverage_rate_avg: f64,
    pub avg_response_hours_avg: f64,
    pub total_issues_period: i64,
    pub health_score_avg: f64,
    pub compared_last_period: f64,
}

#[derive(Debug, Deserialize, Clone)]
pub struct OrgStatsQuery {
    pub organization_id: Uuid,
    pub start_date: Option<String>,
    pub end_date: Option<String>,
    pub team_id: Option<Uuid>,
    pub benchmark: String,
}

impl OrgStatsQuery {
    pub fn sanitize(self) -> Self {
        let benchmark = self.benchmark;
        let valid_benchmarks = ["7d", "30d", "90d"];
        let benchmark = if valid_benchmarks.contains(&benchmark.as_str()) {
            benchmark
        } else {
            "30d".to_string()
        };
        Self {
            benchmark,
            ..self
        }
    }
}
