use regex::Regex;
use uuid::Uuid;

use crate::models::comment::{Comment, CommentType, CommentWithDetails, CreateCommentRequest, UpdateCommentRequest};
use crate::repositories::{CommentRepository, NotificationRepository, UserRepository};
use crate::utils::{AppError, AppResult};

#[async_trait::async_trait]
pub trait NotificationService: Send + Sync + Clone {
    async fn send_notification(
        &self,
        user_id: Uuid,
        notification_type: &str,
        title: &str,
        content: &str,
        related_url: Option<&str>,
    ) -> AppResult<()>;
}

#[async_trait::async_trait]
pub trait PermissionService: Send + Sync + Clone {
    async fn can_comment(&self, user_id: Uuid, merge_request_id: Uuid) -> AppResult<bool>;
    async fn can_edit_comment(&self, user_id: Uuid, comment_id: Uuid) -> AppResult<bool>;
    async fn can_resolve_comment(&self, user_id: Uuid, comment_id: Uuid) -> AppResult<bool>;
}

#[derive(Clone)]
pub struct CommentService {
    comment_repo: CommentRepository,
    notification_service: NotificationRepository,
    permission_service: PermissionRepository,
    user_repo: UserRepository,
    mention_regex: Regex,
}

#[derive(Clone)]
pub struct PermissionRepository;

impl PermissionRepository {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait::async_trait]
impl PermissionService for PermissionRepository {
    async fn can_comment(&self, _user_id: Uuid, _merge_request_id: Uuid) -> AppResult<bool> {
        Ok(true)
    }

    async fn can_edit_comment(&self, user_id: Uuid, comment_id: Uuid) -> AppResult<bool> {
        Ok(true)
    }

    async fn can_resolve_comment(&self, _user_id: Uuid, _comment_id: Uuid) -> AppResult<bool> {
        Ok(true)
    }
}

#[async_trait::async_trait]
impl super::checklist_service::ChecklistPermissionService for PermissionRepository {
    async fn can_manage_checklist_templates(
        &self,
        _user_id: Uuid,
        _organization_id: Uuid,
        _scope: &str,
        _scope_id: Option<Uuid>,
    ) -> AppResult<bool> {
        Ok(true)
    }

    async fn can_create_review_checklist(
        &self,
        _user_id: Uuid,
        _merge_request_id: Uuid,
    ) -> AppResult<bool> {
        Ok(true)
    }

    async fn can_check_checklist_item(
        &self,
        _user_id: Uuid,
        _item_id: Uuid,
    ) -> AppResult<bool> {
        Ok(true)
    }
}

#[async_trait::async_trait]
impl NotificationService for NotificationRepository {
    async fn send_notification(
        &self,
        user_id: Uuid,
        notification_type: &str,
        title: &str,
        content: &str,
        related_url: Option<&str>,
    ) -> AppResult<()> {
        self.create(user_id, notification_type, title, content, related_url)
            .await?;
        Ok(())
    }
}

impl CommentService {
    pub fn new(
        comment_repo: CommentRepository,
        notification_service: NotificationRepository,
        permission_service: PermissionRepository,
        user_repo: UserRepository,
    ) -> Self {
        Self {
            comment_repo,
            notification_service,
            permission_service,
            user_repo,
            mention_regex: Regex::new(r"@([\w-]+)").unwrap(),
        }
    }

    pub async fn create_comment(
        &self,
        merge_request_id: Uuid,
        author_id: Uuid,
        req: &CreateCommentRequest,
    ) -> AppResult<Comment> {
        if !self
            .permission_service
            .can_comment(author_id, merge_request_id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to comment on this merge request".to_string(),
            ));
        }

        let comment = self
            .comment_repo
            .create(
                merge_request_id,
                author_id,
                req.file_path.as_deref(),
                req.line_no,
                req.line_type.as_deref(),
                &req.content,
                req.parent_id,
            )
            .await?;

        let mentions = self.extract_mentions(&req.content);
        for username in &mentions {
            if let Ok((users, _)) = self.user_repo.list(1, 100).await {
                if let Some(user) = users.iter().find(|u| u.username == *username) {
                    let title = "你被提及了";
                    let content = format!("在评论中被提及: {}", &req.content[..req.content.len().min(100)]);
                    let _ = self
                        .notification_service
                        .send_notification(
                            user.id,
                            "mention",
                            title,
                            &content,
                            Some(&format!("/merge_requests/{}", merge_request_id)),
                        )
                        .await;
                }
            }
        }

        if req.parent_id.is_none() {
            let _ = self
                .notification_service
                .send_notification(
                    Uuid::nil(),
                    "new_comment",
                    "新评论",
                    &format!("收到新评论: {}", &req.content[..req.content.len().min(100)]),
                    Some(&format!("/merge_requests/{}", merge_request_id)),
                )
                .await;
        }

        Ok(comment)
    }

    pub async fn get_comment(&self, id: Uuid) -> AppResult<Comment> {
        self.comment_repo
            .get_by_id(id)
            .await?
            .ok_or_else(|| AppError::NotFound(format!("Comment {} not found", id)))
    }

    pub async fn list_comments(
        &self,
        merge_request_id: Uuid,
    ) -> AppResult<Vec<CommentWithDetails>> {
        self.comment_repo.list_by_mr(merge_request_id).await
    }

    pub async fn list_file_comments(
        &self,
        merge_request_id: Uuid,
        file_path: &str,
    ) -> AppResult<Vec<CommentWithDetails>> {
        self.comment_repo
            .list_by_file(merge_request_id, file_path)
            .await
    }

    pub async fn update_comment(
        &self,
        id: Uuid,
        user_id: Uuid,
        req: &UpdateCommentRequest,
    ) -> AppResult<Comment> {
        let comment = self.get_comment(id).await?;

        if !self
            .permission_service
            .can_edit_comment(user_id, id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to edit this comment".to_string(),
            ));
        }

        if comment.author_id != user_id {
            return Err(AppError::Authorization(
                "You can only edit your own comments".to_string(),
            ));
        }

        self.comment_repo.update(id, &req.content).await
    }

    pub async fn delete_comment(&self, id: Uuid, user_id: Uuid) -> AppResult<()> {
        let comment = self.get_comment(id).await?;

        if !self
            .permission_service
            .can_edit_comment(user_id, id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to delete this comment".to_string(),
            ));
        }

        if comment.author_id != user_id {
            return Err(AppError::Authorization(
                "You can only delete your own comments".to_string(),
            ));
        }

        self.comment_repo.delete(id).await
    }

    pub async fn resolve_comment(
        &self,
        id: Uuid,
        user_id: Uuid,
        resolved: bool,
    ) -> AppResult<Comment> {
        let comment = self.get_comment(id).await?;

        if !self
            .permission_service
            .can_resolve_comment(user_id, id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to resolve this comment".to_string(),
            ));
        }

        let resolved_by = if resolved { Some(user_id) } else { None };

        self.comment_repo.resolve(id, resolved, resolved_by).await
    }

    pub async fn get_unresolved_comments(
        &self,
        merge_request_id: Uuid,
    ) -> AppResult<Vec<CommentWithDetails>> {
        let all_comments = self.list_comments(merge_request_id).await?;
        let unresolved = self.filter_unresolved(all_comments);
        Ok(unresolved)
    }

    pub async fn create_reply(
        &self,
        merge_request_id: Uuid,
        parent_id: Uuid,
        author_id: Uuid,
        content: &str,
    ) -> AppResult<Comment> {
        let parent_comment = self.get_comment(parent_id).await?;
        if parent_comment.merge_request_id != merge_request_id {
            return Err(AppError::Validation(
                "Parent comment does not belong to this merge request".to_string(),
            ));
        }

        let req = CreateCommentRequest {
            file_path: parent_comment.file_path.clone(),
            line_no: parent_comment.line_no,
            line_type: parent_comment.line_type.clone(),
            content: content.to_string(),
            parent_id: Some(parent_id),
        };

        self.create_comment(merge_request_id, author_id, &req).await
    }

    pub fn extract_mentions(&self, content: &str) -> Vec<String> {
        self.mention_regex
            .captures_iter(content)
            .filter_map(|cap| cap.get(1).map(|m| m.as_str().to_string()))
            .collect()
    }

    fn filter_unresolved(&self, comments: Vec<CommentWithDetails>) -> Vec<CommentWithDetails> {
        let mut result = Vec::new();
        for comment in comments {
            if !comment.resolved {
                let replies = self.filter_unresolved(comment.replies);
                result.push(CommentWithDetails {
                    replies,
                    ..comment
                });
            }
        }
        result
    }
}
