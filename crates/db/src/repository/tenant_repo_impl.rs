use async_trait::async_trait;
use sqlx::{query, query_as, PgPool};
use uuid::Uuid;

use crate::error::DbResult;
use crate::repository::tenant_repo::{
    CreateTenantParams, Tenant, TenantRepository, UpdateTenantParams,
};

pub struct PgTenantRepository {
    pub pool: PgPool,
}

impl PgTenantRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

#[async_trait]
impl TenantRepository for PgTenantRepository {
    async fn create_tenant(&self, params: &CreateTenantParams) -> DbResult<Tenant> {
        let tenant = query_as::<_, Tenant>(
            r#"
            INSERT INTO tenants (name, api_key, api_key_hash, qps_limit, rate_limit_per_minute)
            VALUES ($1, $2, $3, COALESCE($4, 100), COALESCE($5, 6000))
            RETURNING id, name, api_key, api_key_hash, qps_limit, rate_limit_per_minute, created_at, updated_at
            "#,
        )
        .bind(&params.name)
        .bind(&params.api_key)
        .bind(&params.api_key_hash)
        .bind(params.qps_limit)
        .bind(params.rate_limit_per_minute)
        .fetch_one(&self.pool)
        .await?;
        Ok(tenant)
    }

    async fn get_tenant_by_id(&self, id: Uuid) -> DbResult<Option<Tenant>> {
        let tenant = query_as::<_, Tenant>(
            r#"
            SELECT id, name, api_key, api_key_hash, qps_limit, rate_limit_per_minute, created_at, updated_at
            FROM tenants
            WHERE id = $1
            "#,
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(tenant)
    }

    async fn get_tenant_by_api_key(&self, api_key: &str) -> DbResult<Option<Tenant>> {
        let tenant = query_as::<_, Tenant>(
            r#"
            SELECT id, name, api_key, api_key_hash, qps_limit, rate_limit_per_minute, created_at, updated_at
            FROM tenants
            WHERE api_key = $1
            "#,
        )
        .bind(api_key)
        .fetch_optional(&self.pool)
        .await?;
        Ok(tenant)
    }

    async fn get_tenant_by_api_key_hash(&self, api_key_hash: &str) -> DbResult<Option<Tenant>> {
        let tenant = query_as::<_, Tenant>(
            r#"
            SELECT id, name, api_key, api_key_hash, qps_limit, rate_limit_per_minute, created_at, updated_at
            FROM tenants
            WHERE api_key_hash = $1
            "#,
        )
        .bind(api_key_hash)
        .fetch_optional(&self.pool)
        .await?;
        Ok(tenant)
    }

    async fn list_tenants(&self, limit: i64, offset: i64) -> DbResult<Vec<Tenant>> {
        let tenants = query_as::<_, Tenant>(
            r#"
            SELECT id, name, api_key, api_key_hash, qps_limit, rate_limit_per_minute, created_at, updated_at
            FROM tenants
            ORDER BY created_at DESC
            LIMIT $1 OFFSET $2
            "#,
        )
        .bind(limit)
        .bind(offset)
        .fetch_all(&self.pool)
        .await?;
        Ok(tenants)
    }

    async fn update_tenant(&self, id: Uuid, params: &UpdateTenantParams) -> DbResult<Tenant> {
        let tenant = query_as::<_, Tenant>(
            r#"
            UPDATE tenants
            SET
                name = COALESCE($1, name),
                qps_limit = COALESCE($2, qps_limit),
                rate_limit_per_minute = COALESCE($3, rate_limit_per_minute),
                api_key = COALESCE($4, api_key),
                api_key_hash = COALESCE($5, api_key_hash),
                updated_at = NOW()
            WHERE id = $6
            RETURNING id, name, api_key, api_key_hash, qps_limit, rate_limit_per_minute, created_at, updated_at
            "#,
        )
        .bind(&params.name)
        .bind(params.qps_limit)
        .bind(params.rate_limit_per_minute)
        .bind(&params.api_key)
        .bind(&params.api_key_hash)
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok(tenant)
    }

    async fn delete_tenant(&self, id: Uuid) -> DbResult<()> {
        query(
            r#"
            DELETE FROM tenants
            WHERE id = $1
            "#,
        )
        .bind(id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn rotate_api_key(
        &self,
        id: Uuid,
        new_api_key: &str,
        new_api_key_hash: &str,
    ) -> DbResult<Tenant> {
        let tenant = query_as::<_, Tenant>(
            r#"
            UPDATE tenants
            SET
                api_key = $1,
                api_key_hash = $2,
                updated_at = NOW()
            WHERE id = $3
            RETURNING id, name, api_key, api_key_hash, qps_limit, rate_limit_per_minute, created_at, updated_at
            "#,
        )
        .bind(new_api_key)
        .bind(new_api_key_hash)
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok(tenant)
    }

    async fn update_rate_limits(
        &self,
        id: Uuid,
        qps_limit: i32,
        rate_limit_per_minute: i32,
    ) -> DbResult<Tenant> {
        let tenant = query_as::<_, Tenant>(
            r#"
            UPDATE tenants
            SET
                qps_limit = $1,
                rate_limit_per_minute = $2,
                updated_at = NOW()
            WHERE id = $3
            RETURNING id, name, api_key, api_key_hash, qps_limit, rate_limit_per_minute, created_at, updated_at
            "#,
        )
        .bind(qps_limit)
        .bind(rate_limit_per_minute)
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok(tenant)
    }
}
