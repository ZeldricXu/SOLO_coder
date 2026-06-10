use chrono::{DateTime, Duration, Utc};
use sqlx::{Postgres, Pool};
use uuid::Uuid;

use crate::error::AppResult;
use crate::models::stats::{
    ReviewStats, PersonalStats, HeatmapData, CoverageTrend, ResponseTimeTrend,
    DashboardStats, ActivityItem, TeamRankingItem, IssueBySeverity, IssueByStatus,
};

#[derive(Clone)]
pub struct StatsRepository {
    pool: Pool<Postgres>,
}

impl StatsRepository {
    pub fn new(pool: Pool<Postgres>) -> Self {
        Self { pool }
    }

    pub async fn get_review_stats(
        &self,
        start_date: Option<DateTime<Utc>>,
        end_date: Option<DateTime<Utc>>,
        repo_id: Option<Uuid>,
        team_id: Option<Uuid>,
        organization_id: Uuid,
    ) -> AppResult<ReviewStats> {
        let stats = sqlx::query_as!(
            ReviewStats,
            r#"
            SELECT
                'custom' as period,
                COUNT(DISTINCT mr.id) as total_mrs,
                COUNT(DISTINCT CASE WHEN mr.status IN ('approved', 'merged') THEN mr.id END) as reviewed_mrs,
                CASE 
                    WHEN COUNT(DISTINCT mr.id) > 0 
                    THEN ROUND(COUNT(DISTINCT CASE WHEN mr.status IN ('approved', 'merged') THEN mr.id END)::numeric / COUNT(DISTINCT mr.id)::numeric * 100, 2)::float8
                    ELSE 0 
                END as coverage_rate,
                COALESCE(AVG(EXTRACT(EPOCH FROM (mr.updated_at - mr.created_at)) / 3600)::float8, 0) as avg_response_time_hours,
                COUNT(DISTINCT i.id) as total_issues,
                CASE 
                    WHEN COUNT(DISTINCT mr.id) > 0 
                    THEN ROUND(COUNT(DISTINCT i.id)::numeric / COUNT(DISTINCT mr.id)::numeric, 2)::float8
                    ELSE 0 
                END as issue_density
            FROM merge_requests mr
            JOIN repositories r ON mr.repo_id = r.id
            LEFT JOIN issues i ON mr.id = i.merge_request_id
            LEFT JOIN teams t ON r.team_id = t.id
            WHERE r.organization_id = $1
                AND ($2::timestamp IS NULL OR mr.created_at >= $2)
                AND ($3::timestamp IS NULL OR mr.created_at <= $3)
                AND ($4::uuid IS NULL OR mr.repo_id = $4)
                AND ($5::uuid IS NULL OR r.team_id = $5)
            "#,
            organization_id,
            start_date,
            end_date,
            repo_id,
            team_id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(stats)
    }

    pub async fn get_personal_stats(
        &self,
        user_id: Uuid,
        start_date: Option<DateTime<Utc>>,
        end_date: Option<DateTime<Utc>>,
    ) -> AppResult<PersonalStats> {
        let user = sqlx::query!("SELECT username, avatar_url FROM users WHERE id = $1", user_id)
            .fetch_one(&self.pool)
            .await?;

        let stats = sqlx::query_as!(
            PersonalStats,
            r#"
            SELECT
                $1 as user_id,
                $2 as username,
                $3 as avatar_url,
                COUNT(DISTINCT CASE WHEN c.author_id = $1 THEN mr.id END) as reviews_done,
                COUNT(DISTINCT CASE WHEN i.reporter_id = $1 THEN i.id END) as issues_found,
                COUNT(DISTINCT CASE WHEN i.assignee_id = $1 AND i.status = 'resolved' THEN i.id END) as issues_fixed,
                CASE 
                    WHEN COUNT(DISTINCT CASE WHEN c.author_id = $1 THEN mr.id END) > 0
                    THEN ROUND(COUNT(DISTINCT CASE WHEN i.reporter_id = $1 THEN i.id END)::numeric / COUNT(DISTINCT CASE WHEN c.author_id = $1 THEN mr.id END)::numeric, 2)::float8
                    ELSE 0
                END as defect_detection_rate,
                CASE
                    WHEN COUNT(DISTINCT CASE WHEN i.assignee_id = $1 THEN i.id END) > 0
                    THEN ROUND(COUNT(DISTINCT CASE WHEN i.assignee_id = $1 AND i.status = 'resolved' THEN i.id END)::numeric / COUNT(DISTINCT CASE WHEN i.assignee_id = $1 THEN i.id END)::numeric, 2)::float8
                    ELSE 0
                END as fix_rate,
                COALESCE(AVG(EXTRACT(EPOCH FROM (c.created_at - mr.created_at)) / 3600)::float8, 0) as avg_review_time_hours
            FROM merge_requests mr
            LEFT JOIN comments c ON mr.id = c.merge_request_id
            LEFT JOIN issues i ON mr.id = i.merge_request_id
            WHERE ($4::timestamp IS NULL OR mr.created_at >= $4)
                AND ($5::timestamp IS NULL OR mr.created_at <= $5)
            "#,
            user_id,
            user.username,
            user.avatar_url,
            start_date,
            end_date,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(stats)
    }

    pub async fn get_heatmap_data(
        &self,
        organization_id: Uuid,
        repo_id: Option<Uuid>,
        start_date: Option<DateTime<Utc>>,
        end_date: Option<DateTime<Utc>>,
    ) -> AppResult<Vec<HeatmapData>> {
        let data = sqlx::query!(
            r#"
            SELECT
                COALESCE(i.file_path, c.file_path) as file_path,
                COUNT(DISTINCT i.id) as issue_count,
                COUNT(DISTINCT c.id) as review_count,
                CASE
                    WHEN COUNT(DISTINCT mr.id) > 0
                    THEN ROUND((COUNT(DISTINCT i.id) * 2 + COUNT(DISTINCT c.id))::numeric / COUNT(DISTINCT mr.id)::numeric, 2)::float8
                    ELSE 0
                END as density_score
            FROM merge_requests mr
            JOIN repositories r ON mr.repo_id = r.id
            LEFT JOIN issues i ON mr.id = i.merge_request_id
            LEFT JOIN comments c ON mr.id = c.merge_request_id
            WHERE r.organization_id = $1
                AND ($2::uuid IS NULL OR mr.repo_id = $2)
                AND ($3::timestamp IS NULL OR mr.created_at >= $3)
                AND ($4::timestamp IS NULL OR mr.created_at <= $4)
                AND (i.file_path IS NOT NULL OR c.file_path IS NOT NULL)
            GROUP BY COALESCE(i.file_path, c.file_path)
            ORDER BY density_score DESC
            LIMIT 100
            "#,
            organization_id,
            repo_id,
            start_date,
            end_date,
        )
        .fetch_all(&self.pool)
        .await?;

        let max_density = data.iter()
            .map(|d| d.density_score.unwrap_or(0.0))
            .fold(0.0, |a, b| a.max(b));

        let result = data.into_iter().map(|row| {
            let density = row.density_score.unwrap_or(0.0);
            let color = if max_density > 0.0 {
                let ratio = density / max_density;
                if ratio > 0.75 {
                    "#EF4444".to_string()
                } else if ratio > 0.5 {
                    "#F59E0B".to_string()
                } else if ratio > 0.25 {
                    "#EAB308".to_string()
                } else {
                    "#22C55E".to_string()
                }
            } else {
                "#E5E7EB".to_string()
            };

            HeatmapData {
                file_path: row.file_path.unwrap_or_default(),
                issue_count: row.issue_count.unwrap_or(0),
                review_count: row.review_count.unwrap_or(0),
                density_score: density,
                color_hex: color,
            }
        }).collect();

        Ok(result)
    }

    pub async fn get_coverage_trend(
        &self,
        organization_id: Uuid,
        days: i32,
        repo_id: Option<Uuid>,
    ) -> AppResult<Vec<CoverageTrend>> {
        let end_date = Utc::now();
        let start_date = end_date - Duration::days(days as i64);

        let trends = sqlx::query_as!(
            CoverageTrend,
            r#"
            SELECT
                DATE(mr.created_at)::varchar as date,
                COUNT(DISTINCT mr.id) as total_mrs,
                COUNT(DISTINCT CASE WHEN mr.status IN ('approved', 'merged') THEN mr.id END) as reviewed_mrs,
                CASE
                    WHEN COUNT(DISTINCT mr.id) > 0
                    THEN ROUND(COUNT(DISTINCT CASE WHEN mr.status IN ('approved', 'merged') THEN mr.id END)::numeric / COUNT(DISTINCT mr.id)::numeric * 100, 2)::float8
                    ELSE 0
                END as coverage_rate
            FROM merge_requests mr
            JOIN repositories r ON mr.repo_id = r.id
            WHERE r.organization_id = $1
                AND mr.created_at >= $2
                AND mr.created_at <= $3
                AND ($4::uuid IS NULL OR mr.repo_id = $4)
            GROUP BY DATE(mr.created_at)
            ORDER BY date
            "#,
            organization_id,
            start_date,
            end_date,
            repo_id,
        )
        .fetch_all(&self.pool)
        .await?;
        Ok(trends)
    }

    pub async fn get_response_time_trend(
        &self,
        organization_id: Uuid,
        days: i32,
        repo_id: Option<Uuid>,
    ) -> AppResult<Vec<ResponseTimeTrend>> {
        let end_date = Utc::now();
        let start_date = end_date - Duration::days(days as i64);

        let trends = sqlx::query_as!(
            ResponseTimeTrend,
            r#"
            SELECT
                DATE(mr.created_at)::varchar as date,
                COALESCE(AVG(EXTRACT(EPOCH FROM (first_comment.first_comment_at - mr.created_at)) / 3600)::float8, 0) as avg_response_hours,
                COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (first_comment.first_comment_at - mr.created_at)) / 3600)::float8, 0) as median_response_hours
            FROM merge_requests mr
            JOIN repositories r ON mr.repo_id = r.id
            LEFT JOIN (
                SELECT merge_request_id, MIN(created_at) as first_comment_at
                FROM comments
                WHERE parent_id IS NULL
                GROUP BY merge_request_id
            ) first_comment ON mr.id = first_comment.merge_request_id
            WHERE r.organization_id = $1
                AND mr.created_at >= $2
                AND mr.created_at <= $3
                AND ($4::uuid IS NULL OR mr.repo_id = $4)
                AND first_comment.first_comment_at IS NOT NULL
            GROUP BY DATE(mr.created_at)
            ORDER BY date
            "#,
            organization_id,
            start_date,
            end_date,
            repo_id,
        )
        .fetch_all(&self.pool)
        .await?;
        Ok(trends)
    }

    pub async fn get_dashboard_stats(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
    ) -> AppResult<DashboardStats> {
        let stats = sqlx::query_as!(
            DashboardStats,
            r#"
            SELECT
                (
                    SELECT COUNT(*) FROM merge_requests mr
                    JOIN repositories r ON mr.repo_id = r.id
                    WHERE r.organization_id = $1
                        AND mr.status IN ('open', 'reviewing', 'changes_requested')
                ) as total_pending_reviews,
                (
                    SELECT COUNT(*) FROM merge_requests mr
                    JOIN mr_reviewers mrr ON mr.id = mrr.merge_request_id
                    WHERE mrr.user_id = $2
                        AND mr.status IN ('open', 'reviewing', 'changes_requested')
                        AND (mrr.review_status IS NULL OR mrr.review_status != 'approved')
                ) as my_pending_reviews,
                (
                    SELECT COUNT(*) FROM issues i
                    WHERE i.reporter_id = $2 AND i.status IN ('open', 'in_progress', 'pending_review')
                ) as my_open_issues,
                (
                    SELECT COUNT(*) FROM issues i
                    WHERE i.assignee_id = $2 AND i.status IN ('open', 'in_progress')
                ) as issues_assigned_to_me,
                (
                    SELECT CASE
                        WHEN COUNT(DISTINCT mr.id) > 0
                        THEN ROUND(COUNT(DISTINCT CASE WHEN mr.status IN ('approved', 'merged') THEN mr.id END)::numeric / COUNT(DISTINCT mr.id)::numeric * 100, 2)::float8
                        ELSE 0
                    END FROM merge_requests mr
                    JOIN repositories r ON mr.repo_id = r.id
                    WHERE r.organization_id = $1
                        AND mr.created_at >= NOW() - INTERVAL '30 days'
                ) as team_review_coverage,
                (
                    SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (mr.updated_at - mr.created_at)) / 3600)::float8, 0)
                    FROM merge_requests mr
                    JOIN repositories r ON mr.repo_id = r.id
                    WHERE r.organization_id = $1
                        AND mr.created_at >= NOW() - INTERVAL '30 days'
                        AND mr.status IN ('approved', 'merged')
                ) as avg_response_time_hours
            "#,
            organization_id,
            user_id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(stats)
    }

    pub async fn get_recent_activity(
        &self,
        organization_id: Uuid,
        limit: i32,
    ) -> AppResult<Vec<ActivityItem>> {
        let activities = sqlx::query_as!(
            ActivityItem,
            r#"
            SELECT * FROM (
                SELECT
                    c.id,
                    'comment' as type_,
                    '新评论' as title,
                    LEFT(c.content, 100) as description,
                    c.author_id as user_id,
                    u.username,
                    u.avatar_url,
                    CONCAT('/merge-requests/', c.merge_request_id) as related_url,
                    c.created_at
                FROM comments c
                JOIN users u ON c.author_id = u.id
                JOIN merge_requests mr ON c.merge_request_id = mr.id
                JOIN repositories r ON mr.repo_id = r.id
                WHERE r.organization_id = $1

                UNION ALL

                SELECT
                    i.id,
                    'issue' as type_,
                    CASE i.status
                        WHEN 'open' THEN '问题创建'
                        WHEN 'in_progress' THEN '问题开始处理'
                        WHEN 'pending_review' THEN '问题待验证'
                        WHEN 'resolved' THEN '问题已解决'
                        ELSE '问题状态变更'
                    END as title,
                    i.title as description,
                    i.reporter_id as user_id,
                    u.username,
                    u.avatar_url,
                    CONCAT('/issues/', i.id) as related_url,
                    i.updated_at as created_at
                FROM issues i
                JOIN users u ON i.reporter_id = u.id
                WHERE i.merge_request_id IN (
                    SELECT mr.id FROM merge_requests mr
                    JOIN repositories r ON mr.repo_id = r.id
                    WHERE r.organization_id = $1
                )

                UNION ALL

                SELECT
                    mr.id,
                    'merge_request' as type_,
                    CASE mr.status
                        WHEN 'open' THEN '新MR创建'
                        WHEN 'approved' THEN 'MR已批准'
                        WHEN 'merged' THEN 'MR已合并'
                        WHEN 'closed' THEN 'MR已关闭'
                        ELSE 'MR状态变更'
                    END as title,
                    mr.title as description,
                    mr.author_id as user_id,
                    u.username,
                    u.avatar_url,
                    CONCAT('/merge-requests/', mr.id) as related_url,
                    mr.updated_at as created_at
                FROM merge_requests mr
                JOIN users u ON mr.author_id = u.id
                JOIN repositories r ON mr.repo_id = r.id
                WHERE r.organization_id = $1
            ) combined
            ORDER BY created_at DESC
            LIMIT $2
            "#,
            organization_id,
            limit as i64,
        )
        .fetch_all(&self.pool)
        .await?;
        Ok(activities)
    }

    pub async fn get_team_ranking(
        &self,
        organization_id: Uuid,
        start_date: Option<DateTime<Utc>>,
        end_date: Option<DateTime<Utc>>,
        limit: i32,
    ) -> AppResult<Vec<TeamRankingItem>> {
        let rankings = sqlx::query!(
            r#"
            SELECT
                ROW_NUMBER() OVER (ORDER BY (
                    COUNT(DISTINCT c.id) * 2 + COUNT(DISTINCT i.id) * 5
                ) DESC) as rank,
                u.id as user_id,
                u.username,
                u.avatar_url,
                COUNT(DISTINCT c.id) as reviews_count,
                COUNT(DISTINCT i.id) as issues_found,
                ROUND((COUNT(DISTINCT c.id) * 2 + COUNT(DISTINCT i.id) * 5)::numeric, 2)::float8 as score
            FROM users u
            JOIN team_members tm ON u.id = tm.user_id
            JOIN teams t ON tm.team_id = t.id
            LEFT JOIN comments c ON u.id = c.author_id
            LEFT JOIN issues i ON u.id = i.reporter_id
            LEFT JOIN merge_requests mr_c ON c.merge_request_id = mr_c.id
            LEFT JOIN merge_requests mr_i ON i.merge_request_id = mr_i.id
            LEFT JOIN repositories r_c ON mr_c.repo_id = r_c.id
            LEFT JOIN repositories r_i ON mr_i.repo_id = r_i.id
            WHERE t.organization_id = $1
                AND ($2::timestamp IS NULL OR c.created_at >= $2 OR i.created_at >= $2)
                AND ($3::timestamp IS NULL OR c.created_at <= $3 OR i.created_at <= $3)
                AND (r_c.organization_id = $1 OR r_i.organization_id = $1 OR r_c IS NULL OR r_i IS NULL)
            GROUP BY u.id, u.username, u.avatar_url
            ORDER BY score DESC
            LIMIT $4
            "#,
            organization_id,
            start_date,
            end_date,
            limit as i64,
        )
        .fetch_all(&self.pool)
        .await?;

        let result = rankings.into_iter().map(|row| {
            TeamRankingItem {
                rank: row.rank.unwrap_or(0) as i32,
                user_id: row.user_id,
                username: row.username,
                avatar_url: row.avatar_url,
                reviews_count: row.reviews_count.unwrap_or(0),
                issues_found: row.issues_found.unwrap_or(0),
                score: row.score.unwrap_or(0.0),
            }
        }).collect();

        Ok(result)
    }

    pub async fn get_issues_by_severity(
        &self,
        organization_id: Uuid,
        start_date: Option<DateTime<Utc>>,
        end_date: Option<DateTime<Utc>>,
    ) -> AppResult<Vec<IssueBySeverity>> {
        let stats = sqlx::query!(
            r#"
            WITH total AS (
                SELECT COUNT(*) as total FROM issues i
                JOIN merge_requests mr ON i.merge_request_id = mr.id
                JOIN repositories r ON mr.repo_id = r.id
                WHERE r.organization_id = $1
                    AND ($2::timestamp IS NULL OR i.created_at >= $2)
                    AND ($3::timestamp IS NULL OR i.created_at <= $3)
            )
            SELECT
                i.severity,
                COUNT(*) as count,
                CASE
                    WHEN t.total > 0
                    THEN ROUND(COUNT(*)::numeric / t.total::numeric * 100, 2)::float8
                    ELSE 0
                END as percentage
            FROM issues i
            JOIN merge_requests mr ON i.merge_request_id = mr.id
            JOIN repositories r ON mr.repo_id = r.id
            CROSS JOIN total t
            WHERE r.organization_id = $1
                AND ($2::timestamp IS NULL OR i.created_at >= $2)
                AND ($3::timestamp IS NULL OR i.created_at <= $3)
            GROUP BY i.severity, t.total
            ORDER BY CASE i.severity
                WHEN 'critical' THEN 1
                WHEN 'major' THEN 2
                WHEN 'minor' THEN 3
                WHEN 'info' THEN 4
                ELSE 5
            END
            "#,
            organization_id,
            start_date,
            end_date,
        )
        .fetch_all(&self.pool)
        .await?;

        Ok(stats.into_iter().map(|row| IssueBySeverity {
            severity: row.severity,
            count: row.count,
            percentage: row.percentage.unwrap_or(0.0),
        }).collect())
    }

    pub async fn get_issues_by_status(
        &self,
        organization_id: Uuid,
        start_date: Option<DateTime<Utc>>,
        end_date: Option<DateTime<Utc>>,
    ) -> AppResult<Vec<IssueByStatus>> {
        let stats = sqlx::query!(
            r#"
            WITH total AS (
                SELECT COUNT(*) as total FROM issues i
                JOIN merge_requests mr ON i.merge_request_id = mr.id
                JOIN repositories r ON mr.repo_id = r.id
                WHERE r.organization_id = $1
                    AND ($2::timestamp IS NULL OR i.created_at >= $2)
                    AND ($3::timestamp IS NULL OR i.created_at <= $3)
            )
            SELECT
                i.status,
                COUNT(*) as count,
                CASE
                    WHEN t.total > 0
                    THEN ROUND(COUNT(*)::numeric / t.total::numeric * 100, 2)::float8
                    ELSE 0
                END as percentage
            FROM issues i
            JOIN merge_requests mr ON i.merge_request_id = mr.id
            JOIN repositories r ON mr.repo_id = r.id
            CROSS JOIN total t
            WHERE r.organization_id = $1
                AND ($2::timestamp IS NULL OR i.created_at >= $2)
                AND ($3::timestamp IS NULL OR i.created_at <= $3)
            GROUP BY i.status, t.total
            ORDER BY CASE i.status
                WHEN 'open' THEN 1
                WHEN 'in_progress' THEN 2
                WHEN 'pending_review' THEN 3
                WHEN 'resolved' THEN 4
                WHEN 'closed' THEN 5
                ELSE 6
            END
            "#,
            organization_id,
            start_date,
            end_date,
        )
        .fetch_all(&self.pool)
        .await?;

        Ok(stats.into_iter().map(|row| IssueByStatus {
            status: row.status,
            count: row.count,
            percentage: row.percentage.unwrap_or(0.0),
        }).collect())
    }
}
