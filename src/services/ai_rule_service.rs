use uuid::Uuid;

use crate::models::ai_rule::{AiRule, AiRuleQuery, CreateAiRuleRequest, UpdateAiRuleRequest};
use crate::repositories::AiRuleRepository;
use crate::utils::{AppError, AppResult};

use super::PermissionService;

#[derive(Clone)]
pub struct AiRuleService {
    ai_rule_repo: AiRuleRepository,
    permission_service: PermissionService,
}

impl AiRuleService {
    pub fn new(ai_rule_repo: AiRuleRepository, permission_service: PermissionService) -> Self {
        Self {
            ai_rule_repo,
            permission_service,
        }
    }

    pub async fn create_rule(
        &self,
        user_id: Uuid,
        req: &CreateAiRuleRequest,
    ) -> AppResult<AiRule> {
        let is_maintainer = self
            .permission_service
            .is_maintainer(user_id, req.organization_id)
            .await?;
        if !is_maintainer {
            return Err(AppError::Authorization(
                "User requires maintainer role to create AI rules".to_string(),
            ));
        }

        let rule = self.ai_rule_repo.create(req).await?;

        if req.is_default.unwrap_or(false) && req.repo_id.is_none() {
            self.ai_rule_repo
                .set_default_rule(rule.id, req.organization_id)
                .await?;
        }

        Ok(rule)
    }

    pub async fn update_rule(
        &self,
        user_id: Uuid,
        rule_id: Uuid,
        req: &UpdateAiRuleRequest,
    ) -> AppResult<AiRule> {
        let rule = self.get_rule_internal(rule_id).await?;
        let is_maintainer = self
            .permission_service
            .is_maintainer(user_id, rule.organization_id)
            .await?;
        if !is_maintainer {
            return Err(AppError::Authorization(
                "User requires maintainer role to update AI rules".to_string(),
            ));
        }

        self.ai_rule_repo.update(rule_id, req).await
    }

    pub async fn delete_rule(&self, user_id: Uuid, rule_id: Uuid) -> AppResult<()> {
        let rule = self.get_rule_internal(rule_id).await?;
        let is_maintainer = self
            .permission_service
            .is_maintainer(user_id, rule.organization_id)
            .await?;
        if !is_maintainer {
            return Err(AppError::Authorization(
                "User requires maintainer role to delete AI rules".to_string(),
            ));
        }

        self.ai_rule_repo.delete(rule_id).await
    }

    pub async fn get_rule(&self, user_id: Uuid, rule_id: Uuid) -> AppResult<AiRule> {
        let rule = self.get_rule_internal(rule_id).await?;
        let is_reviewer = self
            .permission_service
            .is_reviewer(user_id, rule.organization_id)
            .await?;
        if !is_reviewer {
            return Err(AppError::Authorization(
                "User requires reviewer role to view AI rules".to_string(),
            ));
        }

        Ok(rule)
    }

    async fn get_rule_internal(&self, rule_id: Uuid) -> AppResult<AiRule> {
        self.ai_rule_repo
            .get_by_id(rule_id)
            .await?
            .ok_or_else(|| AppError::NotFound(format!("AI rule {} not found", rule_id)))
    }

    pub async fn list_rules(
        &self,
        user_id: Uuid,
        query: &AiRuleQuery,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<AiRule>, i64)> {
        let is_reviewer = self
            .permission_service
            .is_reviewer(user_id, query.organization_id)
            .await?;
        if !is_reviewer {
            return Err(AppError::Authorization(
                "User requires reviewer role to list AI rules".to_string(),
            ));
        }

        self.ai_rule_repo.list(query, page, per_page).await
    }

    pub async fn set_default_rule(
        &self,
        user_id: Uuid,
        rule_id: Uuid,
        organization_id: Uuid,
    ) -> AppResult<()> {
        let is_maintainer = self
            .permission_service
            .is_maintainer(user_id, organization_id)
            .await?;
        if !is_maintainer {
            return Err(AppError::Authorization(
                "User requires maintainer role to set default AI rules".to_string(),
            ));
        }

        let rule = self.get_rule_internal(rule_id).await?;
        if rule.repo_id.is_some() {
            return Err(AppError::Validation(
                "Only organization-level rules can be set as default".to_string(),
            ));
        }

        self.ai_rule_repo.set_default_rule(rule_id, organization_id).await
    }

    pub async fn get_effective_rules(
        &self,
        organization_id: Uuid,
        repo_id: Uuid,
    ) -> AppResult<Vec<AiRule>> {
        self.ai_rule_repo.get_active_rules(organization_id, repo_id).await
    }

    pub async fn get_default_rule_for_repo(
        &self,
        organization_id: Uuid,
        repo_id: Uuid,
    ) -> AppResult<Option<AiRule>> {
        let repo_rules = self.ai_rule_repo.get_active_rules(organization_id, repo_id).await?;

        if !repo_rules.is_empty() {
            return Ok(repo_rules.into_iter().next());
        }

        self.ai_rule_repo.get_default_rule(organization_id).await
    }
}
