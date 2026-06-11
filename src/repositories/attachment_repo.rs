use sqlx::{Postgres, Pool};
use uuid::Uuid;

use crate::error::AppResult;
use crate::models::attachment::{Attachment, AttachmentWithDetails};
use crate::models::CreateAttachmentRequest;

#[derive(Clone)]
pub struct AttachmentRepository {
    pool: Pool<Postgres>,
}

impl AttachmentRepository {
    pub fn new(pool: Pool<Postgres>) -> Self {
        Self { pool }
    }

    pub async fn create(
        &self,
        uploader_id: Uuid,
        req: &CreateAttachmentRequest,
    ) -> AppResult<Attachment> {
        let attachment = sqlx::query_as!(
            Attachment,
            r#"
            INSERT INTO attachments (
                attachment_type, target_id, uploader_id, file_name, storage_key,
                content_type, file_size_bytes, width, height, thumbnail_key
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
            RETURNING *
            "#,
            req.attachment_type,
            req.target_id,
            uploader_id,
            req.file_name,
            req.storage_key,
            req.content_type,
            req.file_size_bytes,
            req.width,
            req.height,
            req.thumbnail_key.as_deref(),
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(attachment)
    }

    pub async fn get_by_id(&self, id: Uuid) -> AppResult<Option<Attachment>> {
        let attachment = sqlx::query_as!(
            Attachment,
            "SELECT * FROM attachments WHERE id = $1",
            id,
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(attachment)
    }

    pub async fn get_by_target(
        &self,
        attachment_type: &str,
        target_id: Uuid,
    ) -> AppResult<Vec<AttachmentWithDetails>> {
        let rows = sqlx::query!(
            r#"
            SELECT
                a.id,
                a.attachment_type,
                a.target_id,
                a.uploader_id,
                u.username as uploader_name,
                u.avatar_url as uploader_avatar,
                a.file_name,
                a.storage_key,
                a.storage_key as file_url,
                a.thumbnail_key,
                a.thumbnail_key as thumbnail_url,
                a.content_type,
                a.file_size_bytes,
                a.width,
                a.height,
                a.created_at
            FROM attachments a
            JOIN users u ON a.uploader_id = u.id
            WHERE a.attachment_type = $1
                AND a.target_id = $2
            ORDER BY a.created_at DESC
            "#,
            attachment_type,
            target_id,
        )
        .fetch_all(&self.pool)
        .await?;

        let result = rows
            .into_iter()
            .map(|row| AttachmentWithDetails {
                id: row.id,
                attachment_type: row.attachment_type,
                target_id: row.target_id,
                uploader_id: row.uploader_id,
                uploader_name: row.uploader_name,
                uploader_avatar: row.uploader_avatar,
                file_name: row.file_name,
                storage_key: row.storage_key,
                file_url: row.file_url,
                thumbnail_url: row.thumbnail_url,
                content_type: row.content_type,
                file_size_bytes: row.file_size_bytes,
                width: row.width,
                height: row.height,
                thumbnail_key: row.thumbnail_key,
                created_at: row.created_at,
            })
            .collect();

        Ok(result)
    }

    pub async fn get_by_uploader(
        &self,
        uploader_id: Uuid,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<Attachment>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let attachments = sqlx::query_as!(
            Attachment,
            r#"
            SELECT * FROM attachments
            WHERE uploader_id = $1
            ORDER BY created_at DESC
            LIMIT $2 OFFSET $3
            "#,
            uploader_id,
            limit,
            offset,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            "SELECT COUNT(*) FROM attachments WHERE uploader_id = $1",
            uploader_id,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok((attachments, total))
    }

    pub async fn delete(&self, id: Uuid) -> AppResult<()> {
        sqlx::query!(
            "DELETE FROM attachments WHERE id = $1",
            id,
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn get_total_size(&self, organization_id: Uuid) -> AppResult<i64> {
        let size = sqlx::query_scalar!(
            r#"
            SELECT COALESCE(SUM(a.file_size_bytes), 0) as total_size
            FROM attachments a
            JOIN users u ON a.uploader_id = u.id
            JOIN team_members tm ON u.id = tm.user_id
            JOIN teams t ON tm.team_id = t.id
            WHERE t.organization_id = $1
            "#,
            organization_id,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);
        Ok(size)
    }
}
