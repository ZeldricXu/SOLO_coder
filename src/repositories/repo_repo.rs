use chrono::{DateTime, Utc};
use sqlx::{Postgres, Pool};
use uuid::Uuid;

use crate::error::AppResult;
use crate::models::repository::{Repository, RepositoryWithDetails, WebhookLog, DiffSnapshot};

#[derive(Clone)]
pub struct RepoRepository {
    pool: Pool<Postgres>,
}

impl RepoRepository {
    pub fn new(pool: Pool<Postgres>) -> Self {
        Self { pool }
    }

    pub async fn create(
        &self,
        organization_id: Uuid,
        team_id: Option<Uuid>,
        provider: &str,
        provider_id: &str,
        name: &str,
        full_name: &str,
        webhook_secret: &str,
    ) -> AppResult<Repository> {
        let repo = sqlx::query_as!(
            Repository,
            r#"
            INSERT INTO repositories (organization_id, team_id, provider, provider_id, name, full_name, webhook_secret)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            ON CONFLICT (provider, provider_id) DO UPDATE SET
                team_id = EXCLUDED.team_id,
                name = EXCLUDED.name,
                full_name = EXCLUDED.full_name,
                webhook_secret = EXCLUDED.webhook_secret,
                is_active = TRUE
            RETURNING *
            "#,
            organization_id,
            team_id,
            provider,
            provider_id,
            name,
            full_name,
            webhook_secret,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(repo)
    }

    pub async fn get_by_id(&self, id: Uuid) -> AppResult<Option<Repository>> {
        let repo = sqlx::query_as!(
            Repository,
            "SELECT * FROM repositories WHERE id = $1",
            id
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(repo)
    }

    pub async fn get_by_provider_id(&self, provider: &str, provider_id: &str) -> AppResult<Option<Repository>> {
        let repo = sqlx::query_as!(
            Repository,
            "SELECT * FROM repositories WHERE provider = $1 AND provider_id = $2",
            provider,
            provider_id
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(repo)
    }

    pub async fn list_with_details(
        &self,
        organization_id: Uuid,
        team_id: Option<Uuid>,
        provider: Option<&str>,
        is_active: Option<bool>,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<RepositoryWithDetails>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let repos = sqlx::query_as!(
            RepositoryWithDetails,
            r#"
            SELECT 
                r.id,
                r.organization_id,
                r.team_id,
                t.name as team_name,
                r.provider,
                r.provider_id,
                r.name,
                r.full_name,
                r.is_active,
                r.last_sync_at,
                r.created_at,
                COALESCE(mr.mr_count, 0) as mr_count,
                COALESCE(pr.pending_count, 0) as pending_reviews
            FROM repositories r
            LEFT JOIN teams t ON r.team_id = t.id
            LEFT JOIN (
                SELECT repo_id, COUNT(*) as mr_count
                FROM merge_requests
                GROUP BY repo_id
            ) mr ON r.id = mr.repo_id
            LEFT JOIN (
                SELECT repo_id, COUNT(*) as pending_count
                FROM merge_requests
                WHERE status IN ('open', 'reviewing', 'changes_requested')
                GROUP BY repo_id
            ) pr ON r.id = pr.repo_id
            WHERE r.organization_id = $1
                AND ($2::uuid IS NULL OR r.team_id = $2)
                AND ($3::varchar IS NULL OR r.provider = $3)
                AND ($4::boolean IS NULL OR r.is_active = $4)
            ORDER BY r.name
            LIMIT $5 OFFSET $6
            "#,
            organization_id,
            team_id,
            provider,
            is_active,
            limit,
            offset,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            r#"
            SELECT COUNT(*) FROM repositories
            WHERE organization_id = $1
                AND ($2::uuid IS NULL OR team_id = $2)
                AND ($3::varchar IS NULL OR provider = $3)
                AND ($4::boolean IS NULL OR is_active = $4)
            "#,
            organization_id,
            team_id,
            provider,
            is_active,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok((repos, total))
    }

    pub async fn update_sync_status(&self, id: Uuid) -> AppResult<()> {
        sqlx::query!(
            "UPDATE repositories SET last_sync_at = NOW() WHERE id = $1",
            id
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn set_active(&self, id: Uuid, is_active: bool) -> AppResult<()> {
        sqlx::query!(
            "UPDATE repositories SET is_active = $1 WHERE id = $2",
            is_active,
            id
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn delete(&self, id: Uuid) -> AppResult<()> {
        sqlx::query!("DELETE FROM repositories WHERE id = $1", id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn create_webhook_log(
        &self,
        provider: &str,
        repo_id: Option<Uuid>,
        event_type: &str,
        delivery_id: Option<&str>,
        payload: &serde_json::Value,
        status: i32,
        error_message: Option<&str>,
    ) -> AppResult<WebhookLog> {
        let log = sqlx::query_as!(
            WebhookLog,
            r#"
            INSERT INTO webhook_logs (provider, repo_id, event_type, delivery_id, payload, status, error_message)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING *
            "#,
            provider,
            repo_id,
            event_type,
            delivery_id,
            payload,
            status,
            error_message,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(log)
    }

    pub async fn check_delivery_exists(&self, delivery_id: &str) -> AppResult<bool> {
        let exists = sqlx::query_scalar!(
            "SELECT EXISTS(SELECT 1 FROM webhook_logs WHERE delivery_id = $1)",
            delivery_id
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(false);
        Ok(exists)
    }

    pub async fn list_webhook_logs(
        &self,
        repo_id: Option<Uuid>,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<WebhookLog>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let logs = sqlx::query_as!(
            WebhookLog,
            r#"
            SELECT * FROM webhook_logs
            WHERE ($1::uuid IS NULL OR repo_id = $1)
            ORDER BY created_at DESC
            LIMIT $2 OFFSET $3
            "#,
            repo_id,
            limit,
            offset,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            r#"
            SELECT COUNT(*) FROM webhook_logs
            WHERE ($1::uuid IS NULL OR repo_id = $1)
            "#,
            repo_id
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok((logs, total))
    }

    pub async fn create_diff_snapshot(
        &self,
        merge_request_id: Uuid,
        storage_key: &str,
        checksum: &str,
        line_count: i32,
        changed_files: i32,
    ) -> AppResult<DiffSnapshot> {
        let snapshot = sqlx::query_as!(
            DiffSnapshot,
            r#"
            INSERT INTO diff_snapshots (merge_request_id, storage_key, checksum, line_count, changed_files)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING *
            "#,
            merge_request_id,
            storage_key,
            checksum,
            line_count,
            changed_files,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(snapshot)
    }

    pub async fn get_latest_diff_snapshot(&self, merge_request_id: Uuid) -> AppResult<Option<DiffSnapshot>> {
        let snapshot = sqlx::query_as!(
            DiffSnapshot,
            r#"
            SELECT * FROM diff_snapshots
            WHERE merge_request_id = $1
            ORDER BY created_at DESC
            LIMIT 1
            "#,
            merge_request_id
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(snapshot)
    }

    pub async fn list_diff_snapshots(&self, merge_request_id: Uuid) -> AppResult<Vec<DiffSnapshot>> {
        let snapshots = sqlx::query_as!(
            DiffSnapshot,
            r#"
            SELECT * FROM diff_snapshots
            WHERE merge_request_id = $1
            ORDER BY created_at DESC
            "#,
            merge_request_id
        )
        .fetch_all(&self.pool)
        .await?;
        Ok(snapshots)
    }
}
