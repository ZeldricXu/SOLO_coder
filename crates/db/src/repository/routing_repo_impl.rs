use async_trait::async_trait;
use sqlx::{query, query_as, PgPool};
use uuid::Uuid;

use crate::error::DbResult;
use crate::repository::routing_repo::{
    CreateRoutingRuleParams, RoutingRepository, RoutingRule, UpdateRoutingRuleParams,
};

pub struct PgRoutingRepository {
    pub pool: PgPool,
}

impl PgRoutingRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

#[async_trait]
impl RoutingRepository for PgRoutingRepository {
    async fn create_routing_rule(
        &self,
        params: &CreateRoutingRuleParams,
    ) -> DbResult<RoutingRule> {
        let rule = query_as::<_, RoutingRule>(
            r#"
            INSERT INTO routing_rules (model_name, strategy, config)
            VALUES ($1, $2, $3)
            RETURNING id, model_name, strategy, config, created_at, updated_at
            "#,
        )
        .bind(&params.model_name)
        .bind(&params.strategy)
        .bind(&params.config)
        .fetch_one(&self.pool)
        .await?;
        Ok(rule)
    }

    async fn get_routing_rule_by_id(&self, id: Uuid) -> DbResult<Option<RoutingRule>> {
        let rule = query_as::<_, RoutingRule>(
            r#"
            SELECT id, model_name, strategy, config, created_at, updated_at
            FROM routing_rules
            WHERE id = $1
            "#,
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(rule)
    }

    async fn get_routing_rule_by_model_name(
        &self,
        model_name: &str,
    ) -> DbResult<Option<RoutingRule>> {
        let rule = query_as::<_, RoutingRule>(
            r#"
            SELECT id, model_name, strategy, config, created_at, updated_at
            FROM routing_rules
            WHERE model_name = $1
            "#,
        )
        .bind(model_name)
        .fetch_optional(&self.pool)
        .await?;
        Ok(rule)
    }

    async fn list_routing_rules(
        &self,
        strategy: Option<&str>,
        limit: i64,
        offset: i64,
    ) -> DbResult<Vec<RoutingRule>> {
        let sql = if strategy.is_some() {
            r#"
            SELECT id, model_name, strategy, config, created_at, updated_at
            FROM routing_rules
            WHERE strategy = $1
            ORDER BY created_at DESC
            LIMIT $2 OFFSET $3
            "#
        } else {
            r#"
            SELECT id, model_name, strategy, config, created_at, updated_at
            FROM routing_rules
            ORDER BY created_at DESC
            LIMIT $1 OFFSET $2
            "#
        };

        let mut q = query_as::<_, RoutingRule>(sql);
        if let Some(s) = strategy {
            q = q.bind(s).bind(limit).bind(offset);
        } else {
            q = q.bind(limit).bind(offset);
        }

        let rules = q.fetch_all(&self.pool).await?;
        Ok(rules)
    }

    async fn update_routing_rule(
        &self,
        id: Uuid,
        params: &UpdateRoutingRuleParams,
    ) -> DbResult<RoutingRule> {
        let config = params.config.as_ref().and_then(|x| x.clone());
        let rule = query_as::<_, RoutingRule>(
            r#"
            UPDATE routing_rules
            SET
                strategy = COALESCE($1, strategy),
                config = COALESCE($2, config),
                updated_at = NOW()
            WHERE id = $3
            RETURNING id, model_name, strategy, config, created_at, updated_at
            "#,
        )
        .bind(&params.strategy)
        .bind(config)
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok(rule)
    }

    async fn upsert_routing_rule(
        &self,
        params: &CreateRoutingRuleParams,
    ) -> DbResult<RoutingRule> {
        let rule = query_as::<_, RoutingRule>(
            r#"
            INSERT INTO routing_rules (model_name, strategy, config)
            VALUES ($1, $2, $3)
            ON CONFLICT (model_name) DO UPDATE SET
                strategy = EXCLUDED.strategy,
                config = EXCLUDED.config,
                updated_at = NOW()
            RETURNING id, model_name, strategy, config, created_at, updated_at
            "#,
        )
        .bind(&params.model_name)
        .bind(&params.strategy)
        .bind(&params.config)
        .fetch_one(&self.pool)
        .await?;
        Ok(rule)
    }

    async fn delete_routing_rule(&self, id: Uuid) -> DbResult<()> {
        query(
            r#"
            DELETE FROM routing_rules
            WHERE id = $1
            "#,
        )
        .bind(id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn delete_routing_rule_by_model_name(&self, model_name: &str) -> DbResult<()> {
        query(
            r#"
            DELETE FROM routing_rules
            WHERE model_name = $1
            "#,
        )
        .bind(model_name)
        .execute(&self.pool)
        .await?;
        Ok(())
    }
}
