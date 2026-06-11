use chrono::{DateTime, Duration, Utc};
use uuid::Uuid;

use crate::models::org_stats::{
    ContributorContribution, IssueTypeTrendCompare, IssueTypeTrendPoint, OrgStatsOverview,
    OrgStatsQuery, RepoHealthItem, RepoHealthRanking, TeamContributionRanking,
};
use crate::repositories::StatsRepository;
use crate::services::PermissionService;
use crate::utils::{AppError, AppResult};

#[derive(Clone)]
pub struct OrgStatsService {
    stats_repo: StatsRepository,
    permission_service: PermissionService,
}

impl OrgStatsService {
    pub fn new(stats_repo: StatsRepository, permission_service: PermissionService) -> Self {
        Self {
            stats_repo,
            permission_service,
        }
    }

    pub async fn get_org_overview(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
        query: &OrgStatsQuery,
    ) -> AppResult<OrgStatsOverview> {
        self.require_reviewer_role(user_id, organization_id).await?;

        let query = query.clone().sanitize();
        let (start, end, compare_start, compare_end) =
            Self::calculate_date_ranges(&query.benchmark, query.start_date.as_deref(), query.end_date.as_deref());

        let repo_overview = self
            .stats_repo
            .get_org_stats_overview(organization_id, start, end, compare_start, compare_end)
            .await?;

        let total_members = self.stats_repo.get_org_total_members(organization_id).await?;

        let compared_last_period = if repo_overview.avg_health_score_previous > 0.0 {
            (repo_overview.avg_health_score - repo_overview.avg_health_score_previous)
                / repo_overview.avg_health_score_previous
                * 100.0
        } else {
            0.0
        };

        Ok(OrgStatsOverview {
            total_repos: repo_overview.total_repos,
            total_members,
            total_mrs_period: repo_overview.total_mrs,
            coverage_rate_avg: repo_overview.coverage_rate,
            avg_response_hours_avg: repo_overview.avg_response_time_hours,
            total_issues_period: repo_overview.total_issues,
            health_score_avg: repo_overview.avg_health_score,
            compared_last_period: (compared_last_period * 100.0).round() / 100.0,
        })
    }

    pub async fn get_repo_health_ranking(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
        query: &OrgStatsQuery,
    ) -> AppResult<RepoHealthRanking> {
        self.require_reviewer_role(user_id, organization_id).await?;

        let query = query.clone().sanitize();
        let (start, end, compare_start, _compare_end) =
            Self::calculate_date_ranges(&query.benchmark, query.start_date.as_deref(), query.end_date.as_deref());

        let benchmark_date = end;
        let compare_date = compare_start;

        let repo_ranking = self
            .stats_repo
            .get_repo_health_ranking(organization_id, benchmark_date, compare_date)
            .await?;

        let total_repos = repo_ranking.items.len() as i64;
        let avg_health_score = if total_repos > 0 {
            repo_ranking.items.iter().map(|i| i.health_score).sum::<f64>() / total_repos as f64
        } else {
            0.0
        };

        let items: Vec<RepoHealthItem> = repo_ranking
            .items
            .into_iter()
            .enumerate()
            .map(|(idx, item)| {
                let trend = if item.trend > 0.05 {
                    "up".to_string()
                } else if item.trend < -0.05 {
                    "down".to_string()
                } else {
                    "flat".to_string()
                };

                RepoHealthItem {
                    repo_id: item.repo_id,
                    repo_name: item.repo_name,
                    coverage_rate: item.coverage_rate,
                    avg_response_hours: item.avg_response_time_hours,
                    issue_density: item.issue_density,
                    health_score: item.health_score,
                    rank: (idx as i32) + 1,
                    trend,
                }
            })
            .take(20)
            .collect();

        Ok(RepoHealthRanking {
            total_repos,
            items,
            avg_health_score: (avg_health_score * 100.0).round() / 100.0,
            benchmark_date: repo_ranking.benchmark_date,
        })
    }

    pub async fn get_contributor_ranking(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
        query: &OrgStatsQuery,
        team_id: Option<Uuid>,
        top_n: Option<i32>,
    ) -> AppResult<TeamContributionRanking> {
        self.require_reviewer_role(user_id, organization_id).await?;

        let query = query.clone().sanitize();
        let top_n = top_n.unwrap_or(20);
        let (start, _end, _compare_start, _compare_end) =
            Self::calculate_date_ranges(&query.benchmark, query.start_date.as_deref(), query.end_date.as_deref());

        let benchmark_date = start;

        let contributor_ranking = self
            .stats_repo
            .get_contributor_ranking(organization_id, benchmark_date, team_id, top_n)
            .await?;

        let total_members = contributor_ranking.items.len() as i64;
        let team_avg_score = if total_members > 0 {
            contributor_ranking.items.iter().map(|i| i.score).sum::<f64>() / total_members as f64
        } else {
            0.0
        };

        let items: Vec<ContributorContribution> = contributor_ranking
            .items
            .into_iter()
            .map(|item| ContributorContribution {
                user_id: item.user_id,
                username: item.username,
                avatar_url: item.avatar_url,
                reviews_done: item.reviews_done,
                comments_written: item.comments_count,
                issues_found: item.issues_found,
                issues_fixed: item.issues_fixed,
                mrs_merged: 0,
                contribution_score: item.score,
            })
            .collect();

        Ok(TeamContributionRanking {
            total_members,
            items,
            team_avg_score: (team_avg_score * 100.0).round() / 100.0,
            benchmark_date: contributor_ranking.benchmark_date,
        })
    }

    pub async fn get_issue_type_trend(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
        query: &OrgStatsQuery,
    ) -> AppResult<IssueTypeTrendCompare> {
        self.require_reviewer_role(user_id, organization_id).await?;

        let query = query.clone().sanitize();
        let (start, end, compare_start, compare_end) =
            Self::calculate_date_ranges(&query.benchmark, query.start_date.as_deref(), query.end_date.as_deref());

        let trend_compare = self
            .stats_repo
            .get_issue_type_trend(organization_id, start, end, compare_start, compare_end)
            .await?;

        let periods = vec![
            trend_compare.compare_start_date.clone(),
            trend_compare.compare_end_date.clone(),
            trend_compare.start_date.clone(),
            trend_compare.end_date.clone(),
        ];

        let current: Vec<IssueTypeTrendPoint> = trend_compare
            .current
            .into_iter()
            .map(|item| IssueTypeTrendPoint {
                date: trend_compare.end_date.clone(),
                severity: item.issue_type,
                issue_count: item.count,
                resolved_count: 0,
                avg_resolve_hours: 0.0,
            })
            .collect();

        let previous: Vec<IssueTypeTrendPoint> = trend_compare
            .previous
            .into_iter()
            .map(|item| IssueTypeTrendPoint {
                date: trend_compare.compare_end_date.clone(),
                severity: item.issue_type,
                issue_count: item.count,
                resolved_count: 0,
                avg_resolve_hours: 0.0,
            })
            .collect();

        Ok(IssueTypeTrendCompare {
            periods,
            current,
            previous,
        })
    }

    pub async fn refresh_materialized_views(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
    ) -> AppResult<()> {
        self.require_maintainer_role(user_id, organization_id).await?;
        self.stats_repo.refresh_materialized_views().await
    }

    pub fn calculate_date_ranges(
        benchmark: &str,
        custom_start: Option<&str>,
        custom_end: Option<&str>,
    ) -> (DateTime<Utc>, DateTime<Utc>, DateTime<Utc>, DateTime<Utc>) {
        let now = Utc::now();

        if let (Some(start_str), Some(end_str)) = (custom_start, custom_end) {
            if let (Ok(start), Ok(end)) = (
                chrono::NaiveDate::parse_from_str(start_str, "%Y-%m-%d"),
                chrono::NaiveDate::parse_from_str(end_str, "%Y-%m-%d"),
            ) {
                let start_dt = start.and_hms_opt(0, 0, 0).unwrap().and_utc();
                let end_dt = end.and_hms_opt(23, 59, 59).unwrap().and_utc();
                let duration = end_dt - start_dt;
                let compare_end_dt = start_dt - Duration::seconds(1);
                let compare_start_dt = compare_end_dt - duration;
                return (start_dt, end_dt, compare_start_dt, compare_end_dt);
            }
        }

        let days = match benchmark {
            "7d" => 7,
            "30d" => 30,
            "90d" => 90,
            _ => 30,
        };

        let end = now;
        let start = end - Duration::days(days);
        let compare_end = start - Duration::seconds(1);
        let compare_start = compare_end - Duration::days(days);

        (start, end, compare_start, compare_end)
    }

    async fn require_reviewer_role(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
    ) -> AppResult<()> {
        let has_permission = self
            .permission_service
            .is_reviewer(user_id, organization_id)
            .await?;

        if !has_permission {
            return Err(AppError::Authorization(
                "User requires Owner, Maintainer, or Reviewer role to view organization statistics"
                    .to_string(),
            ));
        }

        Ok(())
    }

    async fn require_maintainer_role(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
    ) -> AppResult<()> {
        let has_permission = self
            .permission_service
            .is_maintainer(user_id, organization_id)
            .await?;

        if !has_permission {
            return Err(AppError::Authorization(
                "User requires Owner or Maintainer role to refresh materialized views".to_string(),
            ));
        }

        Ok(())
    }
}
