use sqlx::{Postgres, Pool};
use uuid::Uuid;
use std::collections::HashMap;

use crate::error::AppResult;
use crate::models::comment::{Comment, CommentWithDetails};

#[derive(Clone)]
pub struct CommentRepository {
    pool: Pool<Postgres>,
}

impl CommentRepository {
    pub fn new(pool: Pool<Postgres>) -> Self {
        Self { pool }
    }

    pub async fn create(
        &self,
        merge_request_id: Uuid,
        author_id: Uuid,
        file_path: Option<&str>,
        line_no: Option<i32>,
        line_type: Option<&str>,
        content: &str,
        parent_id: Option<Uuid>,
    ) -> AppResult<Comment> {
        let comment = sqlx::query_as!(
            Comment,
            r#"
            INSERT INTO comments (merge_request_id, author_id, file_path, line_no, line_type, content, parent_id)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING *
            "#,
            merge_request_id,
            author_id,
            file_path,
            line_no,
            line_type,
            content,
            parent_id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(comment)
    }

    pub async fn get_by_id(&self, id: Uuid) -> AppResult<Option<Comment>> {
        let comment = sqlx::query_as!(
            Comment,
            "SELECT * FROM comments WHERE id = $1",
            id
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(comment)
    }

    pub async fn list_by_mr(&self, merge_request_id: Uuid) -> AppResult<Vec<CommentWithDetails>> {
        let comments = sqlx::query_as!(
            CommentWithDetails,
            r#"
            SELECT
                c.id,
                c.merge_request_id,
                c.author_id,
                u.username as author_name,
                u.avatar_url as author_avatar,
                c.file_path,
                c.line_no,
                c.line_type,
                c.content,
                c.parent_id,
                c.resolved,
                c.resolved_by,
                rb.username as resolved_by_name,
                c.resolved_at,
                c.created_at
            FROM comments c
            JOIN users u ON c.author_id = u.id
            LEFT JOIN users rb ON c.resolved_by = rb.id
            WHERE c.merge_request_id = $1
            ORDER BY c.created_at
            "#,
            merge_request_id,
        )
        .fetch_all(&self.pool)
        .await?;

        let comments_with_replies = Self::build_comment_tree(comments);
        Ok(comments_with_replies)
    }

    pub async fn list_by_file(
        &self,
        merge_request_id: Uuid,
        file_path: &str,
    ) -> AppResult<Vec<CommentWithDetails>> {
        let comments = sqlx::query_as!(
            CommentWithDetails,
            r#"
            SELECT
                c.id,
                c.merge_request_id,
                c.author_id,
                u.username as author_name,
                u.avatar_url as author_avatar,
                c.file_path,
                c.line_no,
                c.line_type,
                c.content,
                c.parent_id,
                c.resolved,
                c.resolved_by,
                rb.username as resolved_by_name,
                c.resolved_at,
                c.created_at
            FROM comments c
            JOIN users u ON c.author_id = u.id
            LEFT JOIN users rb ON c.resolved_by = rb.id
            WHERE c.merge_request_id = $1
                AND (c.file_path = $2 OR c.parent_id IN (
                    SELECT id FROM comments WHERE merge_request_id = $1 AND file_path = $2
                ))
            ORDER BY c.created_at
            "#,
            merge_request_id,
            file_path,
        )
        .fetch_all(&self.pool)
        .await?;

        let comments_with_replies = Self::build_comment_tree(comments);
        Ok(comments_with_replies)
    }

    pub async fn update(&self, id: Uuid, content: &str) -> AppResult<Comment> {
        let comment = sqlx::query_as!(
            Comment,
            r#"
            UPDATE comments
            SET content = $1
            WHERE id = $2
            RETURNING *
            "#,
            content,
            id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(comment)
    }

    pub async fn delete(&self, id: Uuid) -> AppResult<()> {
        sqlx::query!("DELETE FROM comments WHERE id = $1", id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn resolve(
        &self,
        id: Uuid,
        resolved: bool,
        resolved_by: Option<Uuid>,
    ) -> AppResult<Comment> {
        let comment = sqlx::query_as!(
            Comment,
            r#"
            UPDATE comments
            SET resolved = $1,
                resolved_by = CASE WHEN $1 = TRUE THEN $2 ELSE NULL END,
                resolved_at = CASE WHEN $1 = TRUE THEN NOW() ELSE NULL END
            WHERE id = $3
            RETURNING *
            "#,
            resolved,
            resolved_by,
            id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(comment)
    }

    pub fn build_comment_tree(comments: Vec<CommentWithDetails>) -> Vec<CommentWithDetails> {
        let mut comment_map: HashMap<Uuid, CommentWithDetails> = HashMap::new();
        let mut root_comments: Vec<CommentWithDetails> = Vec::new();

        for comment in comments {
            comment_map.insert(comment.id, comment);
        }

        let comment_ids: Vec<Uuid> = comment_map.keys().cloned().collect();
        for id in comment_ids {
            let mut comment = comment_map.remove(&id).unwrap();
            comment.replies = Vec::new();

            if let Some(parent_id) = comment.parent_id {
                if let Some(parent) = comment_map.get_mut(&parent_id) {
                    parent.replies.push(comment);
                } else {
                    root_comments.push(comment);
                }
            } else {
                root_comments.push(comment);
            }
        }

        root_comments.sort_by(|a, b| a.created_at.cmp(&b.created_at));
        root_comments
    }

    pub async fn get_unresolved_count(&self, merge_request_id: Uuid) -> AppResult<i64> {
        let count = sqlx::query_scalar!(
            r#"
            SELECT COUNT(*) FROM comments
            WHERE merge_request_id = $1 AND resolved = FALSE AND parent_id IS NULL
            "#,
            merge_request_id,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);
        Ok(count)
    }
}
