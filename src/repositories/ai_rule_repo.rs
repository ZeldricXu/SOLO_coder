use sqlx::{Postgres, Pool};
use uuid::Uuid;

use crate::error::AppResult;
use crate::models::ai_rule::{AiRule, AiRuleQuery, UpdateAiRuleRequest};
use crate::models::CreateAiRuleRequest;

#[derive(Clone)]
pub struct AiRuleRepository {
    pool: Pool<Postgres>,
}

impl AiRuleRepository {
    pub fn new(pool: Pool<Postgres>) -> Self {
        Self { pool }
    }

    pub async fn create(&self, req: &CreateAiRuleRequest) -> AppResult<AiRule> {
        let enabled_categories = sqlx::types::Json(req.enabled_categories.clone());
        let rule = sqlx::query_as!(
            AiRule,
            r#"
            INSERT INTO ai_rules (
                organization_id, repo_id, name, description, scope, severity_level,
                custom_prompt, enabled_categories, min_changed_lines, context_lines,
                is_active, is_default
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
            RETURNING *
            "#,
            req.organization_id,
            req.repo_id,
            req.name,
            req.description.as_deref(),
            req.scope,
            req.severity_level,
            req.custom_prompt,
            enabled_categories as _,
            req.min_changed_lines,
            req.context_lines,
            req.is_active.unwrap_or(true),
            req.is_default.unwrap_or(false),
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(rule)
    }

    pub async fn update(&self, id: Uuid, req: &UpdateAiRuleRequest) -> AppResult<AiRule> {
        let enabled_categories = req.enabled_categories.as_ref().map(|v| sqlx::types::Json(v.clone()));
        let rule = sqlx::query_as!(
            AiRule,
            r#"
            UPDATE ai_rules
            SET
                name = COALESCE($1, name),
                description = COALESCE($2, description),
                severity_level = COALESCE($3, severity_level),
                custom_prompt = COALESCE($4, custom_prompt),
                enabled_categories = COALESCE($5, enabled_categories),
                min_changed_lines = COALESCE($6, min_changed_lines),
                context_lines = COALESCE($7, context_lines),
                is_active = COALESCE($8, is_active),
                updated_at = NOW()
            WHERE id = $9
            RETURNING *
            "#,
            req.name.as_deref(),
            req.description.as_deref(),
            req.severity_level.as_deref(),
            req.custom_prompt.as_deref(),
            enabled_categories as _,
            req.min_changed_lines,
            req.context_lines,
            req.is_active,
            id,
        )
        .fetch_one(&self.pool)
        .await?;
        Ok(rule)
    }

    pub async fn delete(&self, id: Uuid) -> AppResult<()> {
        sqlx::query!(
            "DELETE FROM ai_rules WHERE id = $1",
            id,
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn get_by_id(&self, id: Uuid) -> AppResult<Option<AiRule>> {
        let rule = sqlx::query_as!(
            AiRule,
            "SELECT * FROM ai_rules WHERE id = $1",
            id,
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(rule)
    }

    pub async fn list(
        &self,
        query: &AiRuleQuery,
        page: i32,
        per_page: i32,
    ) -> AppResult<(Vec<AiRule>, i64)> {
        let offset = ((page - 1) * per_page) as i64;
        let limit = per_page as i64;

        let rules = sqlx::query_as!(
            AiRule,
            r#"
            SELECT * FROM ai_rules
            WHERE organization_id = $1
                AND ($2::uuid IS NULL OR repo_id = $2)
                AND ($3::varchar IS NULL OR scope = $3)
                AND ($4::boolean IS NULL OR is_active = $4)
                AND ($5::boolean IS NULL OR is_default = $5)
            ORDER BY created_at DESC
            LIMIT $6 OFFSET $7
            "#,
            query.organization_id,
            query.repo_id,
            query.scope,
            query.is_active,
            query.is_default,
            limit,
            offset,
        )
        .fetch_all(&self.pool)
        .await?;

        let total = sqlx::query_scalar!(
            r#"
            SELECT COUNT(*) FROM ai_rules
            WHERE organization_id = $1
                AND ($2::uuid IS NULL OR repo_id = $2)
                AND ($3::varchar IS NULL OR scope = $3)
                AND ($4::boolean IS NULL OR is_active = $4)
                AND ($5::boolean IS NULL OR is_default = $5)
            "#,
            query.organization_id,
            query.repo_id,
            query.scope,
            query.is_active,
            query.is_default,
        )
        .fetch_one(&self.pool)
        .await?
        .unwrap_or(0);

        Ok((rules, total))
    }

    pub async fn get_active_rules(
        &self,
        organization_id: Uuid,
        repo_id: Uuid,
    ) -> AppResult<Vec<AiRule>> {
        let repo_rules = sqlx::query_as!(
            AiRule,
            r#"
            SELECT * FROM ai_rules
            WHERE organization_id = $1
                AND repo_id = $2
                AND is_active = TRUE
            ORDER BY created_at DESC
            "#,
            organization_id,
            repo_id,
        )
        .fetch_all(&self.pool)
        .await?;

        if !repo_rules.is_empty() {
            return Ok(repo_rules);
        }

        let org_rules = sqlx::query_as!(
            AiRule,
            r#"
            SELECT * FROM ai_rules
            WHERE organization_id = $1
                AND repo_id IS NULL
                AND is_active = TRUE
            ORDER BY is_default DESC, created_at DESC
            "#,
            organization_id,
        )
        .fetch_all(&self.pool)
        .await?;

        Ok(org_rules)
    }

    pub async fn get_default_rule(&self, organization_id: Uuid) -> AppResult<Option<AiRule>> {
        let rule = sqlx::query_as!(
            AiRule,
            r#"
            SELECT * FROM ai_rules
            WHERE organization_id = $1
                AND is_default = TRUE
                AND is_active = TRUE
                AND repo_id IS NULL
            LIMIT 1
            "#,
            organization_id,
        )
        .fetch_optional(&self.pool)
        .await?;
        Ok(rule)
    }

    pub async fn set_default_rule(&self, id: Uuid, organization_id: Uuid) -> AppResult<()> {
        let mut tx = self.pool.begin().await?;

        sqlx::query!(
            r#"
            UPDATE ai_rules
            SET is_default = FALSE, updated_at = NOW()
            WHERE organization_id = $1
                AND repo_id IS NULL
                AND is_default = TRUE
            "#,
            organization_id,
        )
        .execute(&mut *tx)
        .await?;

        sqlx::query!(
            r#"
            UPDATE ai_rules
            SET is_default = TRUE, updated_at = NOW()
            WHERE id = $1 AND organization_id = $2
            "#,
            id,
            organization_id,
        )
        .execute(&mut *tx)
        .await?;

        tx.commit().await?;
        Ok(())
    }
}
