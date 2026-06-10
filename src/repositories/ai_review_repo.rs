use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use sqlx::{Postgres, Pool};
use uuid::Uuid;

use crate::error::AppResult;
use crate::models::ai_review::{AiReview, AiSuggestion, AiReviewWithSuggestions, AiSuggestionWithDetails};

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct AiSuggestionCategoryStat {
    pub category: String,
    pub count: i64,
}

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct AiSuggestionSeverityStat {
    pub severity: String,
    pub count: i64,
}

#[derive(Debug, Serialize, Deserialize, Clone, sqlx::FromRow)]
pub struct AiSuggestionStatusStat {
    pub status: String,
    pub count: i64,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct AiSuggestionStatistics {
    pub by_category: Vec<AiSuggestionCategoryStat>,
    pub by_severity: Vec<AiSuggestionSeverityStat>,
    pub by_status: Vec<AiSuggestionStatusStat>,
    pub total: i64,
}

#[derive(Clone)]
pub struct AiReviewRepository {
    pool: Pool<Postgres>,
}

impl AiReviewRepository {
    pub fn new(pool: Pool<Postgres>) -> Self {
        Self { pool }
    }

    pub async fn create_review(&self, merge_request_id: Uuid) -> AppResult<AiReview> {
        let review = sqlx::query_as!(
            AiReview,
            r#"
            INSERT INTO ai_reviews (merge_request_id, status, started_at)
            VALUES ($1, 'pending', NOW())
            RETURNING *
            "#,
            merge_request_id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(review)
    }

    pub async fn update_review_status(&self, id: Uuid, status: &str) -> AppResult<AiReview> {
        let review = sqlx::query_as!(
            AiReview,
            r#"
            UPDATE ai_reviews
            SET status = $1
            WHERE id = $2
            RETURNING *
            "#,
            status,
            id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(review)
    }

    pub async fn complete_review(&self, id: Uuid, status: &str) -> AppResult<AiReview> {
        let review = sqlx::query_as!(
            AiReview,
            r#"
            UPDATE ai_reviews
            SET status = $1, completed_at = NOW()
            WHERE id = $2
            RETURNING *
            "#,
            status,
            id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(review)
    }

    pub async fn add_suggestion(
        &self,
        ai_review_id: Uuid,
        file_path: &str,
        line_no: i32,
        category: &str,
        severity: &str,
        title: &str,
        description: &str,
        suggestion: &str,
    ) -> AppResult<AiSuggestion> {
        let sugg = sqlx::query_as!(
            AiSuggestion,
            r#"
            INSERT INTO ai_suggestions (ai_review_id, file_path, line_no, category, severity, title, description, suggestion, status)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 'pending')
            RETURNING *
            "#,
            ai_review_id,
            file_path,
            line_no,
            category,
            severity,
            title,
            description,
            suggestion,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(sugg)
    }

    pub async fn get_review_with_suggestions(&self, id: Uuid) -> AppResult<Option<AiReviewWithSuggestions>> {
        let review = sqlx::query_as!(
            AiReview,
            "SELECT * FROM ai_reviews WHERE id = $1",
            id
        )
        .fetch_optional(&self.pool)
        .await?;

        let Some(review) = review else {
            return Ok(None);
        };

        let suggestions = sqlx::query_as!(
            AiSuggestionWithDetails,
            r#"
            SELECT 
                s.id,
                s.ai_review_id,
                s.file_path,
                s.line_no,
                s.category,
                s.severity,
                s.title,
                s.description,
                s.suggestion,
                s.status,
                s.acted_by,
                u.username as acted_by_name,
                s.acted_at,
                s.created_at
            FROM ai_suggestions s
            LEFT JOIN users u ON s.acted_by = u.id
            WHERE s.ai_review_id = $1
            ORDER BY s.file_path, s.line_no
            "#,
            id,
        )
        .fetch_all(&self.pool)
        .await?;

        Ok(Some(AiReviewWithSuggestions {
            id: review.id,
            merge_request_id: review.merge_request_id,
            status: review.status,
            started_at: review.started_at,
            completed_at: review.completed_at,
            suggestions,
            created_at: review.created_at,
        }))
    }

    pub async fn get_latest_review(&self, merge_request_id: Uuid) -> AppResult<Option<AiReview>> {
        let review = sqlx::query_as!(
            AiReview,
            r#"
            SELECT * FROM ai_reviews
            WHERE merge_request_id = $1
            ORDER BY created_at DESC
            LIMIT 1
            "#,
            merge_request_id,
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(review)
    }

    pub async fn update_suggestion_status(
        &self,
        suggestion_id: Uuid,
        status: &str,
        acted_by: Uuid,
    ) -> AppResult<AiSuggestion> {
        let suggestion = sqlx::query_as!(
            AiSuggestion,
            r#"
            UPDATE ai_suggestions
            SET status = $1, acted_by = $2, acted_at = NOW()
            WHERE id = $3
            RETURNING *
            "#,
            status,
            acted_by,
            suggestion_id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(suggestion)
    }

    pub async fn list_reviews(
        &self,
        merge_request_id: Uuid,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<AiReview>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let reviews = sqlx::query_as!(
            AiReview,
            r#"
            SELECT * FROM ai_reviews
            WHERE merge_request_id = $1
            ORDER BY created_at DESC
            LIMIT $2 OFFSET $3
            "#,
            merge_request_id,
            limit,
            offset,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            "SELECT COUNT(*) FROM ai_reviews WHERE merge_request_id = $1",
            merge_request_id
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok((reviews, total))
    }

    pub async fn get_suggestion_statistics(
        &self,
        ai_review_id: Uuid,
    ) -> AppResult<AiSuggestionStatistics> {
        let by_category = sqlx::query_as!(
            AiSuggestionCategoryStat,
            r#"
            SELECT category, COUNT(*) as count
            FROM ai_suggestions
            WHERE ai_review_id = $1
            GROUP BY category
            ORDER BY count DESC
            "#,
            ai_review_id,
        )
        .fetch_all(&self.pool)
        .await?;

        let by_severity = sqlx::query_as!(
            AiSuggestionSeverityStat,
            r#"
            SELECT severity, COUNT(*) as count
            FROM ai_suggestions
            WHERE ai_review_id = $1
            GROUP BY severity
            ORDER BY CASE severity
                WHEN 'critical' THEN 1
                WHEN 'major' THEN 2
                WHEN 'minor' THEN 3
                WHEN 'info' THEN 4
                ELSE 5
            END
            "#,
            ai_review_id,
        )
        .fetch_all(&self.pool)
        .await?;

        let by_status = sqlx::query_as!(
            AiSuggestionStatusStat,
            r#"
            SELECT status, COUNT(*) as count
            FROM ai_suggestions
            WHERE ai_review_id = $1
            GROUP BY status
            ORDER BY count DESC
            "#,
            ai_review_id,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            "SELECT COUNT(*) FROM ai_suggestions WHERE ai_review_id = $1",
            ai_review_id
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok(AiSuggestionStatistics {
            by_category,
            by_severity,
            by_status,
            total,
        })
    }
}
