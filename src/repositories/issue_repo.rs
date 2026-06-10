use sqlx::{Postgres, Pool};
use uuid::Uuid;

use crate::error::AppResult;
use crate::models::issue::{Issue, IssueWithDetails};
use crate::models::stats::{IssueBySeverity, IssueByStatus};

#[derive(Clone)]
pub struct IssueRepository {
    pool: Pool<Postgres>,
}

impl IssueRepository {
    pub fn new(pool: Pool<Postgres>) -> Self {
        Self { pool }
    }

    pub async fn create(
        &self,
        merge_request_id: Option<Uuid>,
        file_path: Option<&str>,
        line_no: Option<i32>,
        title: &str,
        description: &str,
        severity: &str,
        reporter_id: Uuid,
        assignee_id: Option<Uuid>,
        code_snippet: Option<&str>,
    ) -> AppResult<Issue> {
        let issue = sqlx::query_as!(
            Issue,
            r#"
            INSERT INTO issues (merge_request_id, file_path, line_no, title, description, severity, reporter_id, assignee_id, code_snippet)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
            RETURNING *
            "#,
            merge_request_id,
            file_path,
            line_no,
            title,
            description,
            severity,
            reporter_id,
            assignee_id,
            code_snippet,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(issue)
    }

    pub async fn get_by_id(&self, id: Uuid) -> AppResult<Option<IssueWithDetails>> {
        let issue = sqlx::query_as!(
            IssueWithDetails,
            r#"
            SELECT 
                i.id, i.merge_request_id, mr.title as merge_request_title, r.name as repo_name,
                i.file_path, i.line_no, i.title, i.description, i.severity, i.status,
                i.reporter_id, ru.username as reporter_name, ru.avatar_url as reporter_avatar,
                i.assignee_id, au.username as assignee_name, au.avatar_url as assignee_avatar,
                i.code_snippet, i.created_at, i.updated_at
            FROM issues i
            LEFT JOIN merge_requests mr ON i.merge_request_id = mr.id
            LEFT JOIN repositories r ON mr.repo_id = r.id
            LEFT JOIN users ru ON i.reporter_id = ru.id
            LEFT JOIN users au ON i.assignee_id = au.id
            WHERE i.id = $1
            "#,
            id
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(issue)
    }

    pub async fn list_with_details(
        &self,
        merge_request_id: Option<Uuid>,
        repo_id: Option<Uuid>,
        severity: Option<&str>,
        status: Option<&str>,
        reporter_id: Option<Uuid>,
        assignee_id: Option<Uuid>,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<IssueWithDetails>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let issues = sqlx::query_as!(
            IssueWithDetails,
            r#"
            SELECT 
                i.id, i.merge_request_id, mr.title as merge_request_title, r.name as repo_name,
                i.file_path, i.line_no, i.title, i.description, i.severity, i.status,
                i.reporter_id, ru.username as reporter_name, ru.avatar_url as reporter_avatar,
                i.assignee_id, au.username as assignee_name, au.avatar_url as assignee_avatar,
                i.code_snippet, i.created_at, i.updated_at
            FROM issues i
            LEFT JOIN merge_requests mr ON i.merge_request_id = mr.id
            LEFT JOIN repositories r ON mr.repo_id = r.id
            LEFT JOIN users ru ON i.reporter_id = ru.id
            LEFT JOIN users au ON i.assignee_id = au.id
            WHERE ($1::uuid IS NULL OR i.merge_request_id = $1)
                AND ($2::uuid IS NULL OR mr.repo_id = $2)
                AND ($3::varchar IS NULL OR i.severity = $3)
                AND ($4::varchar IS NULL OR i.status = $4)
                AND ($5::uuid IS NULL OR i.reporter_id = $5)
                AND ($6::uuid IS NULL OR i.assignee_id = $6)
            ORDER BY i.created_at DESC
            LIMIT $7 OFFSET $8
            "#,
            merge_request_id,
            repo_id,
            severity,
            status,
            reporter_id,
            assignee_id,
            limit,
            offset,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            r#"
            SELECT COUNT(*) FROM issues i
            LEFT JOIN merge_requests mr ON i.merge_request_id = mr.id
            WHERE ($1::uuid IS NULL OR i.merge_request_id = $1)
                AND ($2::uuid IS NULL OR mr.repo_id = $2)
                AND ($3::varchar IS NULL OR i.severity = $3)
                AND ($4::varchar IS NULL OR i.status = $4)
                AND ($5::uuid IS NULL OR i.reporter_id = $5)
                AND ($6::uuid IS NULL OR i.assignee_id = $6)
            "#,
            merge_request_id,
            repo_id,
            severity,
            status,
            reporter_id,
            assignee_id,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok((issues, total))
    }

    pub async fn update(
        &self,
        id: Uuid,
        title: Option<&str>,
        description: Option<&str>,
        severity: Option<&str>,
        assignee_id: Option<Uuid>,
        code_snippet: Option<&str>,
    ) -> AppResult<Issue> {
        let issue = sqlx::query_as!(
            Issue,
            r#"
            UPDATE issues
            SET 
                title = COALESCE($1, title),
                description = COALESCE($2, description),
                severity = COALESCE($3, severity),
                assignee_id = COALESCE($4, assignee_id),
                code_snippet = COALESCE($5, code_snippet),
                updated_at = NOW()
            WHERE id = $6
            RETURNING *
            "#,
            title,
            description,
            severity,
            assignee_id,
            code_snippet,
            id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(issue)
    }

    pub async fn update_status(&self, id: Uuid, status: &str) -> AppResult<Issue> {
        let issue = sqlx::query_as!(
            Issue,
            r#"
            UPDATE issues
            SET status = $1, updated_at = NOW()
            WHERE id = $2
            RETURNING *
            "#,
            status,
            id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(issue)
    }

    pub async fn assign(&self, id: Uuid, assignee_id: Uuid) -> AppResult<Issue> {
        let issue = sqlx::query_as!(
            Issue,
            r#"
            UPDATE issues
            SET assignee_id = $1, updated_at = NOW()
            WHERE id = $2
            RETURNING *
            "#,
            assignee_id,
            id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(issue)
    }

    pub async fn list_by_reporter(
        &self,
        reporter_id: Uuid,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<IssueWithDetails>, i64)> {
        self.list_with_details(
            None,
            None,
            None,
            None,
            Some(reporter_id),
            None,
            page,
            per_page,
        )
        .await
    }

    pub async fn list_by_assignee(
        &self,
        assignee_id: Uuid,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<IssueWithDetails>, i64)> {
        self.list_with_details(
            None,
            None,
            None,
            None,
            None,
            Some(assignee_id),
            page,
            per_page,
        )
        .await
    }

    pub async fn get_issues_by_file(
        &self,
        file_path: &str,
        repo_id: Option<Uuid>,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<IssueWithDetails>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let issues = sqlx::query_as!(
            IssueWithDetails,
            r#"
            SELECT 
                i.id, i.merge_request_id, mr.title as merge_request_title, r.name as repo_name,
                i.file_path, i.line_no, i.title, i.description, i.severity, i.status,
                i.reporter_id, ru.username as reporter_name, ru.avatar_url as reporter_avatar,
                i.assignee_id, au.username as assignee_name, au.avatar_url as assignee_avatar,
                i.code_snippet, i.created_at, i.updated_at
            FROM issues i
            LEFT JOIN merge_requests mr ON i.merge_request_id = mr.id
            LEFT JOIN repositories r ON mr.repo_id = r.id
            LEFT JOIN users ru ON i.reporter_id = ru.id
            LEFT JOIN users au ON i.assignee_id = au.id
            WHERE i.file_path = $1
                AND ($2::uuid IS NULL OR mr.repo_id = $2)
            ORDER BY i.created_at DESC
            LIMIT $3 OFFSET $4
            "#,
            file_path,
            repo_id,
            limit,
            offset,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            r#"
            SELECT COUNT(*) FROM issues i
            LEFT JOIN merge_requests mr ON i.merge_request_id = mr.id
            WHERE i.file_path = $1
                AND ($2::uuid IS NULL OR mr.repo_id = $2)
            "#,
            file_path,
            repo_id,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok((issues, total))
    }

    pub async fn get_issue_statistics(
        &self,
        repo_id: Option<Uuid>,
        merge_request_id: Option<Uuid>,
    ) -> AppResult<(Vec<IssueBySeverity>, Vec<IssueByStatus>)> {
        let severity_rows = sqlx::query!(
            r#"
            SELECT 
                i.severity,
                COUNT(*) as count
            FROM issues i
            LEFT JOIN merge_requests mr ON i.merge_request_id = mr.id
            WHERE ($1::uuid IS NULL OR mr.repo_id = $1)
                AND ($2::uuid IS NULL OR i.merge_request_id = $2)
            GROUP BY i.severity
            ORDER BY CASE i.severity
                WHEN 'critical' THEN 1
                WHEN 'major' THEN 2
                WHEN 'minor' THEN 3
                WHEN 'info' THEN 4
                ELSE 5
            END
            "#,
            repo_id,
            merge_request_id,
        )
        .fetch_all(&self.pool)
        .await?;

        let status_rows = sqlx::query!(
            r#"
            SELECT 
                i.status,
                COUNT(*) as count
            FROM issues i
            LEFT JOIN merge_requests mr ON i.merge_request_id = mr.id
            WHERE ($1::uuid IS NULL OR mr.repo_id = $1)
                AND ($2::uuid IS NULL OR i.merge_request_id = $2)
            GROUP BY i.status
            ORDER BY CASE i.status
                WHEN 'open' THEN 1
                WHEN 'in_progress' THEN 2
                WHEN 'pending_review' THEN 3
                WHEN 'resolved' THEN 4
                WHEN 'closed' THEN 5
                ELSE 6
            END
            "#,
            repo_id,
            merge_request_id,
        )
        .fetch_all(&self.pool)
        .await?;

        let total_severity: i64 = severity_rows.iter().map(|r| r.count.unwrap_or(0)).sum();
        let total_status: i64 = status_rows.iter().map(|r| r.count.unwrap_or(0)).sum();

        let by_severity = severity_rows
            .into_iter()
            .map(|row| IssueBySeverity {
                severity: row.severity,
                count: row.count.unwrap_or(0),
                percentage: if total_severity > 0 {
                    row.count.unwrap_or(0) as f64 / total_severity as f64 * 100.0
                } else {
                    0.0
                },
            })
            .collect();

        let by_status = status_rows
            .into_iter()
            .map(|row| IssueByStatus {
                status: row.status,
                count: row.count.unwrap_or(0),
                percentage: if total_status > 0 {
                    row.count.unwrap_or(0) as f64 / total_status as f64 * 100.0
                } else {
                    0.0
                },
            })
            .collect();

        Ok((by_severity, by_status))
    }
}
