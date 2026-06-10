use sqlx::{Postgres, Pool};
use uuid::Uuid;

use crate::error::AppResult;
use crate::models::merge_request::{MergeRequest, MergeRequestWithDetails, ReviewerAssignment};

#[derive(Clone)]
pub struct MergeRequestRepository {
    pool: Pool<Postgres>,
}

impl MergeRequestRepository {
    pub fn new(pool: Pool<Postgres>) -> Self {
        Self { pool }
    }

    pub async fn create(
        &self,
        repo_id: Uuid,
        provider: &str,
        provider_id: &str,
        title: &str,
        description: Option<&str>,
        source_branch: &str,
        target_branch: &str,
        author_id: Uuid,
        status: &str,
    ) -> AppResult<MergeRequest> {
        let mr = sqlx::query_as!(
            MergeRequest,
            r#"
            INSERT INTO merge_requests (repo_id, provider, provider_id, title, description, source_branch, target_branch, author_id, status)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
            ON CONFLICT (provider, provider_id) DO UPDATE SET
                title = EXCLUDED.title,
                description = EXCLUDED.description,
                source_branch = EXCLUDED.source_branch,
                target_branch = EXCLUDED.target_branch,
                author_id = EXCLUDED.author_id,
                status = EXCLUDED.status,
                updated_at = NOW()
            RETURNING *
            "#,
            repo_id,
            provider,
            provider_id,
            title,
            description,
            source_branch,
            target_branch,
            author_id,
            status,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(mr)
    }

    pub async fn get_by_id(&self, id: Uuid) -> AppResult<Option<MergeRequest>> {
        let mr = sqlx::query_as!(
            MergeRequest,
            "SELECT * FROM merge_requests WHERE id = $1",
            id
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(mr)
    }

    pub async fn get_by_provider_id(&self, provider: &str, provider_id: &str) -> AppResult<Option<MergeRequest>> {
        let mr = sqlx::query_as!(
            MergeRequest,
            "SELECT * FROM merge_requests WHERE provider = $1 AND provider_id = $2",
            provider,
            provider_id
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(mr)
    }

    pub async fn list_with_details(
        &self,
        repo_id: Option<Uuid>,
        status: Option<&str>,
        author_id: Option<Uuid>,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<MergeRequestWithDetails>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let mrs = sqlx::query_as!(
            MergeRequestWithDetails,
            r#"
            SELECT
                mr.id,
                mr.repo_id,
                r.name as repo_name,
                mr.provider,
                mr.provider_id,
                mr.title,
                mr.description,
                mr.source_branch,
                mr.target_branch,
                mr.author_id,
                u.username as author_name,
                u.avatar_url as author_avatar,
                mr.status,
                COALESCE(c.comment_count, 0) as comment_count,
                COALESCE(uc.unresolved_count, 0) as unresolved_comment_count,
                COALESCE(i.issue_count, 0) as issue_count,
                mr.diff_snapshot_key,
                mr.created_at,
                mr.updated_at
            FROM merge_requests mr
            JOIN repositories r ON mr.repo_id = r.id
            JOIN users u ON mr.author_id = u.id
            LEFT JOIN (
                SELECT merge_request_id, COUNT(*) as comment_count
                FROM comments
                GROUP BY merge_request_id
            ) c ON mr.id = c.merge_request_id
            LEFT JOIN (
                SELECT merge_request_id, COUNT(*) as unresolved_count
                FROM comments
                WHERE resolved = FALSE
                GROUP BY merge_request_id
            ) uc ON mr.id = uc.merge_request_id
            LEFT JOIN (
                SELECT merge_request_id, COUNT(*) as issue_count
                FROM issues
                WHERE status != 'closed'
                GROUP BY merge_request_id
            ) i ON mr.id = i.merge_request_id
            WHERE ($1::uuid IS NULL OR mr.repo_id = $1)
                AND ($2::varchar IS NULL OR mr.status = $2)
                AND ($3::uuid IS NULL OR mr.author_id = $3)
            ORDER BY mr.updated_at DESC
            LIMIT $4 OFFSET $5
            "#,
            repo_id,
            status,
            author_id,
            limit,
            offset,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            r#"
            SELECT COUNT(*) FROM merge_requests
            WHERE ($1::uuid IS NULL OR repo_id = $1)
                AND ($2::varchar IS NULL OR status = $2)
                AND ($3::uuid IS NULL OR author_id = $3)
            "#,
            repo_id,
            status,
            author_id,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok((mrs, total))
    }

    pub async fn update_status(&self, id: Uuid, status: &str) -> AppResult<MergeRequest> {
        let mr = sqlx::query_as!(
            MergeRequest,
            r#"
            UPDATE merge_requests
            SET status = $1, updated_at = NOW()
            WHERE id = $2
            RETURNING *
            "#,
            status,
            id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(mr)
    }

    pub async fn update_diff_snapshot(&self, id: Uuid, diff_snapshot_key: &str) -> AppResult<MergeRequest> {
        let mr = sqlx::query_as!(
            MergeRequest,
            r#"
            UPDATE merge_requests
            SET diff_snapshot_key = $1, updated_at = NOW()
            WHERE id = $2
            RETURNING *
            "#,
            diff_snapshot_key,
            id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(mr)
    }

    pub async fn delete(&self, id: Uuid) -> AppResult<()> {
        sqlx::query!("DELETE FROM merge_requests WHERE id = $1", id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn assign_reviewer(&self, merge_request_id: Uuid, user_id: Uuid) -> AppResult<ReviewerAssignment> {
        let reviewer = sqlx::query_as!(
            ReviewerAssignment,
            r#"
            INSERT INTO mr_reviewers (merge_request_id, user_id, review_status)
            VALUES ($1, $2, 'pending')
            ON CONFLICT (merge_request_id, user_id) DO UPDATE SET
                review_status = 'pending',
                assigned_at = NOW()
            RETURNING
                id,
                merge_request_id,
                user_id,
                (SELECT username FROM users WHERE id = user_id) as username,
                (SELECT avatar_url FROM users WHERE id = user_id) as avatar_url,
                assigned_at,
                review_status,
                reviewed_at
            "#,
            merge_request_id,
            user_id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(reviewer)
    }

    pub async fn list_reviewers(&self, merge_request_id: Uuid) -> AppResult<Vec<ReviewerAssignment>> {
        let reviewers = sqlx::query_as!(
            ReviewerAssignment,
            r#"
            SELECT
                mrr.id,
                mrr.merge_request_id,
                mrr.user_id,
                u.username,
                u.avatar_url,
                mrr.assigned_at,
                mrr.review_status,
                mrr.reviewed_at
            FROM mr_reviewers mrr
            JOIN users u ON mrr.user_id = u.id
            WHERE mrr.merge_request_id = $1
            ORDER BY mrr.assigned_at
            "#,
            merge_request_id,
        )
        .fetch_all(&self.pool)
        .await?;
        Ok(reviewers)
    }

    pub async fn get_pending_reviews(
        &self,
        user_id: Uuid,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<MergeRequestWithDetails>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let mrs = sqlx::query_as!(
            MergeRequestWithDetails,
            r#"
            SELECT
                mr.id,
                mr.repo_id,
                r.name as repo_name,
                mr.provider,
                mr.provider_id,
                mr.title,
                mr.description,
                mr.source_branch,
                mr.target_branch,
                mr.author_id,
                u.username as author_name,
                u.avatar_url as author_avatar,
                mr.status,
                COALESCE(c.comment_count, 0) as comment_count,
                COALESCE(uc.unresolved_count, 0) as unresolved_comment_count,
                COALESCE(i.issue_count, 0) as issue_count,
                mr.diff_snapshot_key,
                mr.created_at,
                mr.updated_at
            FROM merge_requests mr
            JOIN mr_reviewers mrr ON mr.id = mrr.merge_request_id
            JOIN repositories r ON mr.repo_id = r.id
            JOIN users u ON mr.author_id = u.id
            LEFT JOIN (
                SELECT merge_request_id, COUNT(*) as comment_count
                FROM comments
                GROUP BY merge_request_id
            ) c ON mr.id = c.merge_request_id
            LEFT JOIN (
                SELECT merge_request_id, COUNT(*) as unresolved_count
                FROM comments
                WHERE resolved = FALSE
                GROUP BY merge_request_id
            ) uc ON mr.id = uc.merge_request_id
            LEFT JOIN (
                SELECT merge_request_id, COUNT(*) as issue_count
                FROM issues
                WHERE status != 'closed'
                GROUP BY merge_request_id
            ) i ON mr.id = i.merge_request_id
            WHERE mrr.user_id = $1
                AND mrr.review_status != 'completed'
                AND mr.status IN ('open', 'reviewing', 'changes_requested')
            ORDER BY mr.updated_at DESC
            LIMIT $2 OFFSET $3
            "#,
            user_id,
            limit,
            offset,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            r#"
            SELECT COUNT(*) FROM merge_requests mr
            JOIN mr_reviewers mrr ON mr.id = mrr.merge_request_id
            WHERE mrr.user_id = $1
                AND mrr.review_status != 'completed'
                AND mr.status IN ('open', 'reviewing', 'changes_requested')
            "#,
            user_id,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok((mrs, total))
    }

    pub async fn get_my_mrs(
        &self,
        user_id: Uuid,
        status: Option<&str>,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<MergeRequestWithDetails>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let mrs = sqlx::query_as!(
            MergeRequestWithDetails,
            r#"
            SELECT
                mr.id,
                mr.repo_id,
                r.name as repo_name,
                mr.provider,
                mr.provider_id,
                mr.title,
                mr.description,
                mr.source_branch,
                mr.target_branch,
                mr.author_id,
                u.username as author_name,
                u.avatar_url as author_avatar,
                mr.status,
                COALESCE(c.comment_count, 0) as comment_count,
                COALESCE(uc.unresolved_count, 0) as unresolved_comment_count,
                COALESCE(i.issue_count, 0) as issue_count,
                mr.diff_snapshot_key,
                mr.created_at,
                mr.updated_at
            FROM merge_requests mr
            JOIN repositories r ON mr.repo_id = r.id
            JOIN users u ON mr.author_id = u.id
            LEFT JOIN (
                SELECT merge_request_id, COUNT(*) as comment_count
                FROM comments
                GROUP BY merge_request_id
            ) c ON mr.id = c.merge_request_id
            LEFT JOIN (
                SELECT merge_request_id, COUNT(*) as unresolved_count
                FROM comments
                WHERE resolved = FALSE
                GROUP BY merge_request_id
            ) uc ON mr.id = uc.merge_request_id
            LEFT JOIN (
                SELECT merge_request_id, COUNT(*) as issue_count
                FROM issues
                WHERE status != 'closed'
                GROUP BY merge_request_id
            ) i ON mr.id = i.merge_request_id
            WHERE mr.author_id = $1
                AND ($2::varchar IS NULL OR mr.status = $2)
            ORDER BY mr.updated_at DESC
            LIMIT $3 OFFSET $4
            "#,
            user_id,
            status,
            limit,
            offset,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            r#"
            SELECT COUNT(*) FROM merge_requests
            WHERE author_id = $1
                AND ($2::varchar IS NULL OR status = $2)
            "#,
            user_id,
            status,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok((mrs, total))
    }
}
