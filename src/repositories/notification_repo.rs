use sqlx::{Postgres, Pool};
use uuid::Uuid;

use crate::error::AppResult;
use crate::models::notification::{Notification, NotificationSettings, UpdateNotificationSettingsRequest};

#[derive(Clone)]
pub struct NotificationRepository {
    pool: Pool<Postgres>,
}

impl NotificationRepository {
    pub fn new(pool: Pool<Postgres>) -> Self {
        Self { pool }
    }

    pub async fn create(
        &self,
        user_id: Uuid,
        type_: &str,
        title: &str,
        content: &str,
        related_url: Option<&str>,
    ) -> AppResult<Notification> {
        let notification = sqlx::query_as!(
            Notification,
            r#"
            INSERT INTO notifications (user_id, type, title, content, related_url)
            VALUES ($1, $2, $3, $4, $5)
            RETURNING id, user_id, type as type_, title, content, related_url, read, created_at
            "#,
            user_id,
            type_,
            title,
            content,
            related_url,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(notification)
    }

    pub async fn get_by_id(&self, id: Uuid) -> AppResult<Option<Notification>> {
        let notification = sqlx::query_as!(
            Notification,
            r#"
            SELECT id, user_id, type as type_, title, content, related_url, read, created_at
            FROM notifications
            WHERE id = $1
            "#,
            id,
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(notification)
    }

    pub async fn list_by_user(
        &self,
        user_id: Uuid,
        type_: Option<&str>,
        read: Option<bool>,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<Notification>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let notifications = sqlx::query_as!(
            Notification,
            r#"
            SELECT id, user_id, type as type_, title, content, related_url, read, created_at
            FROM notifications
            WHERE user_id = $1
                AND ($2::varchar IS NULL OR type = $2)
                AND ($3::boolean IS NULL OR read = $3)
            ORDER BY created_at DESC
            LIMIT $4 OFFSET $5
            "#,
            user_id,
            type_,
            read,
            limit,
            offset,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            r#"
            SELECT COUNT(*) FROM notifications
            WHERE user_id = $1
                AND ($2::varchar IS NULL OR type = $2)
                AND ($3::boolean IS NULL OR read = $3)
            "#,
            user_id,
            type_,
            read,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok((notifications, total))
    }

    pub async fn mark_read(&self, id: Uuid, user_id: Uuid) -> AppResult<Notification> {
        let notification = sqlx::query_as!(
            Notification,
            r#"
            UPDATE notifications
            SET read = TRUE
            WHERE id = $1 AND user_id = $2
            RETURNING id, user_id, type as type_, title, content, related_url, read, created_at
            "#,
            id,
            user_id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(notification)
    }

    pub async fn mark_all_read(&self, user_id: Uuid) -> AppResult<u64> {
        let result = sqlx::query!(
            "UPDATE notifications SET read = TRUE WHERE user_id = $1 AND read = FALSE",
            user_id,
        )
        .execute(&self.pool)
        .await?;
        Ok(result.rows_affected())
    }

    pub async fn get_unread_count(&self, user_id: Uuid) -> AppResult<i64> {
        let count = sqlx::query_scalar!(
            "SELECT COUNT(*) FROM notifications WHERE user_id = $1 AND read = FALSE",
            user_id,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);
        Ok(count)
    }

    pub async fn get_settings(&self, user_id: Uuid) -> AppResult<Option<NotificationSettings>> {
        let settings = sqlx::query_as!(
            NotificationSettings,
            "SELECT * FROM notification_settings WHERE user_id = $1",
            user_id,
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(settings)
    }

    pub async fn update_settings(
        &self,
        user_id: Uuid,
        req: &UpdateNotificationSettingsRequest,
    ) -> AppResult<NotificationSettings> {
        let settings = sqlx::query_as!(
            NotificationSettings,
            r#"
            UPDATE notification_settings
            SET 
                email_enabled = COALESCE($1, email_enabled),
                slack_enabled = COALESCE($2, slack_enabled),
                dingtalk_enabled = COALESCE($3, dingtalk_enabled),
                slack_webhook_url = COALESCE($4, slack_webhook_url),
                dingtalk_webhook_url = COALESCE($5, dingtalk_webhook_url),
                on_new_review = COALESCE($6, on_new_review),
                on_comment = COALESCE($7, on_comment),
                on_mention = COALESCE($8, on_mention),
                on_issue_assigned = COALESCE($9, on_issue_assigned),
                daily_digest = COALESCE($10, daily_digest),
                updated_at = NOW()
            WHERE user_id = $11
            RETURNING *
            "#,
            req.email_enabled,
            req.slack_enabled,
            req.dingtalk_enabled,
            req.slack_webhook_url.as_deref(),
            req.dingtalk_webhook_url.as_deref(),
            req.on_new_review,
            req.on_comment,
            req.on_mention,
            req.on_issue_assigned,
            req.daily_digest,
            user_id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(settings)
    }

    pub async fn create_default_settings(&self, user_id: Uuid) -> AppResult<NotificationSettings> {
        let settings = sqlx::query_as!(
            NotificationSettings,
            r#"
            INSERT INTO notification_settings (user_id)
            VALUES ($1)
            ON CONFLICT (user_id) DO NOTHING
            RETURNING *
            "#,
            user_id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(settings)
    }

    pub async fn delete(&self, id: Uuid, user_id: Uuid) -> AppResult<()> {
        sqlx::query!(
            "DELETE FROM notifications WHERE id = $1 AND user_id = $2",
            id,
            user_id,
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }
}
