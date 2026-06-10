use uuid::Uuid;

use crate::models::checklist::{
    CheckItemRequest, ChecklistItemRequest, ChecklistScope, ChecklistTemplate,
    ChecklistTemplateWithItems, CreateChecklistTemplateRequest, ReviewChecklist,
    ReviewChecklistWithDetails, UpdateChecklistTemplateRequest,
};
use crate::repositories::ChecklistRepository;
use crate::utils::{AppError, AppResult};

use super::comment_service::PermissionService;

#[derive(Clone)]
pub struct ChecklistService<PS: ChecklistPermissionService> {
    checklist_repo: ChecklistRepository,
    permission_service: PS,
}

impl<PS: ChecklistPermissionService> ChecklistService<PS> {
    pub fn new(checklist_repo: ChecklistRepository, permission_service: PS) -> Self {
        Self {
            checklist_repo,
            permission_service,
        }
    }

    pub async fn create_template(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
        req: &CreateChecklistTemplateRequest,
    ) -> AppResult<ChecklistTemplateWithItems> {
        if !self
            .permission_service
            .can_manage_checklist_templates(user_id, organization_id, req.scope.as_str(), req.scope_id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to create checklist templates".to_string(),
            ));
        }

        let template = self
            .checklist_repo
            .create_template(
                &req.name,
                req.description.as_deref(),
                &req.scope,
                req.scope_id,
                req.parent_id,
            )
            .await?;

        if let Some(items) = Some(&req.items) {
            for item in items {
                self.checklist_repo
                    .create_template_item(
                        template.id,
                        &item.group_name,
                        &item.title,
                        item.description.as_deref(),
                        item.order_index,
                    )
                    .await?;
            }
        }

        self.get_template(template.id).await
    }

    pub async fn get_template(&self, id: Uuid) -> AppResult<ChecklistTemplateWithItems> {
        self.checklist_repo
            .get_template_with_items(id)
            .await?
            .ok_or_else(|| AppError::NotFound(format!("Checklist template {} not found", id)))
    }

    pub async fn list_templates(
        &self,
        scope: Option<ChecklistScope>,
        scope_id: Option<Uuid>,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<ChecklistTemplate>, i64)> {
        let scope_str = scope.as_ref().map(|s| s.as_str());
        self.checklist_repo
            .list_templates(scope_str, scope_id, page, per_page)
            .await
    }

    pub async fn update_template(
        &self,
        id: Uuid,
        user_id: Uuid,
        organization_id: Uuid,
        req: &UpdateChecklistTemplateRequest,
    ) -> AppResult<ChecklistTemplateWithItems> {
        let template = self.get_template(id).await?;

        if !self
            .permission_service
            .can_manage_checklist_templates(user_id, organization_id, &template.scope, template.scope_id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to update this checklist template".to_string(),
            ));
        }

        self.checklist_repo
            .update_template(id, req.name.as_deref(), req.description.as_deref(), req.parent_id)
            .await?;

        if let Some(items) = &req.items {
            let existing_items = &template.items;
            let existing_titles: std::collections::HashSet<_> =
                existing_items.iter().map(|i| i.title.clone()).collect();

            for item in items {
                if !existing_titles.contains(&item.title) {
                    self.checklist_repo
                        .create_template_item(
                            id,
                            &item.group_name,
                            &item.title,
                            item.description.as_deref(),
                            item.order_index,
                        )
                        .await?;
                }
            }
        }

        self.get_template(id).await
    }

    pub async fn delete_template(&self, id: Uuid, user_id: Uuid, organization_id: Uuid) -> AppResult<()> {
        let template = self.get_template(id).await?;

        if !self
            .permission_service
            .can_manage_checklist_templates(user_id, organization_id, &template.scope, template.scope_id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to delete this checklist template".to_string(),
            ));
        }

        self.checklist_repo.delete_template(id).await
    }

    pub async fn create_review_checklist(
        &self,
        merge_request_id: Uuid,
        template_id: Uuid,
        user_id: Uuid,
    ) -> AppResult<ReviewChecklist> {
        if !self
            .permission_service
            .can_create_review_checklist(user_id, merge_request_id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to create review checklists".to_string(),
            ));
        }

        self.checklist_repo
            .create_review_checklist(merge_request_id, template_id)
            .await
    }

    pub async fn get_review_checklist(
        &self,
        merge_request_id: Uuid,
    ) -> AppResult<Option<ReviewChecklistWithDetails>> {
        self.checklist_repo
            .get_review_checklist_with_details(merge_request_id)
            .await
    }

    pub async fn check_item(
        &self,
        item_id: Uuid,
        user_id: Uuid,
        req: &CheckItemRequest,
    ) -> AppResult<()> {
        if !self
            .permission_service
            .can_check_checklist_item(user_id, item_id)
            .await?
        {
            return Err(AppError::Authorization(
                "You don't have permission to check this checklist item".to_string(),
            ));
        }

        self.checklist_repo
            .check_item(item_id, req.checked, user_id, req.comment.as_deref())
            .await
    }

    pub async fn get_inherited_templates(
        &self,
        organization_id: Uuid,
        team_id: Option<Uuid>,
        repository_id: Option<Uuid>,
    ) -> AppResult<Vec<ChecklistTemplateWithItems>> {
        self.checklist_repo
            .get_inherited_templates(organization_id, team_id, repository_id)
            .await
    }

    pub fn get_checklist_progress(
        &self,
        checklist: &ReviewChecklistWithDetails,
    ) -> ChecklistProgress {
        let total_items = checklist.items.len();
        let checked_items = checklist.items.iter().filter(|i| i.checked).count();

        let percentage = if total_items > 0 {
            (checked_items as f64 / total_items as f64) * 100.0
        } else {
            100.0
        };

        ChecklistProgress {
            total_items: total_items as i32,
            checked_items: checked_items as i32,
            percentage,
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ChecklistProgress {
    pub total_items: i32,
    pub checked_items: i32,
    pub percentage: f64,
}

#[async_trait::async_trait]
pub trait ChecklistPermissionService: PermissionService {
    async fn can_manage_checklist_templates(
        &self,
        user_id: Uuid,
        organization_id: Uuid,
        scope: &str,
        scope_id: Option<Uuid>,
    ) -> AppResult<bool>;

    async fn can_create_review_checklist(
        &self,
        user_id: Uuid,
        merge_request_id: Uuid,
    ) -> AppResult<bool>;

    async fn can_check_checklist_item(
        &self,
        user_id: Uuid,
        item_id: Uuid,
    ) -> AppResult<bool>;
}
