use async_trait::async_trait;
use sqlx::{query, query_as, PgPool};
use uuid::Uuid;

use crate::error::DbResult;
use crate::repository::model_repo::{
    CreateModelParams, CreateModelVersionParams, Model, ModelDeployment, ModelRepository,
    ModelVersion, UpdateModelParams, UpdateModelVersionParams,
};

pub struct PgModelRepository {
    pub pool: PgPool,
}

impl PgModelRepository {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }
}

#[async_trait]
impl ModelRepository for PgModelRepository {
    async fn create_model(&self, params: &CreateModelParams) -> DbResult<Model> {
        let model = query_as::<_, Model>(
            r#"
            INSERT INTO models (name, category, description)
            VALUES ($1, $2, $3)
            RETURNING id, name, category, description, latest_version, created_at, updated_at
            "#,
        )
        .bind(&params.name)
        .bind(&params.category)
        .bind(&params.description)
        .fetch_one(&self.pool)
        .await?;
        Ok(model)
    }

    async fn get_model_by_id(&self, id: Uuid) -> DbResult<Option<Model>> {
        let model = query_as::<_, Model>(
            r#"
            SELECT id, name, category, description, latest_version, created_at, updated_at
            FROM models
            WHERE id = $1
            "#,
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(model)
    }

    async fn get_model_by_name(&self, name: &str) -> DbResult<Option<Model>> {
        let model = query_as::<_, Model>(
            r#"
            SELECT id, name, category, description, latest_version, created_at, updated_at
            FROM models
            WHERE name = $1
            "#,
        )
        .bind(name)
        .fetch_optional(&self.pool)
        .await?;
        Ok(model)
    }

    async fn list_models(
        &self,
        category: Option<&str>,
        limit: i64,
        offset: i64,
    ) -> DbResult<Vec<Model>> {
        let sql = if category.is_some() {
            r#"
            SELECT id, name, category, description, latest_version, created_at, updated_at
            FROM models
            WHERE category = $1
            ORDER BY created_at DESC
            LIMIT $2 OFFSET $3
            "#
        } else {
            r#"
            SELECT id, name, category, description, latest_version, created_at, updated_at
            FROM models
            ORDER BY created_at DESC
            LIMIT $1 OFFSET $2
            "#
        };

        let mut q = query_as::<_, Model>(sql);
        if let Some(cat) = category {
            q = q.bind(cat).bind(limit).bind(offset);
        } else {
            q = q.bind(limit).bind(offset);
        }

        let models = q.fetch_all(&self.pool).await?;
        Ok(models)
    }

    async fn update_model(&self, id: Uuid, params: &UpdateModelParams) -> DbResult<Model> {
        let description = params.description.as_ref().and_then(|x| x.clone());
        let model = query_as::<_, Model>(
            r#"
            UPDATE models
            SET
                name = COALESCE($1, name),
                category = COALESCE($2, category),
                description = COALESCE($3, description),
                latest_version = COALESCE($4, latest_version),
                updated_at = NOW()
            WHERE id = $5
            RETURNING id, name, category, description, latest_version, created_at, updated_at
            "#,
        )
        .bind(&params.name)
        .bind(&params.category)
        .bind(description)
        .bind(params.latest_version)
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok(model)
    }

    async fn delete_model(&self, id: Uuid) -> DbResult<()> {
        query(
            r#"
            DELETE FROM models
            WHERE id = $1
            "#,
        )
        .bind(id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn create_model_version(
        &self,
        params: &CreateModelVersionParams,
    ) -> DbResult<ModelVersion> {
        let status = params.status.clone().unwrap_or_else(|| "pending".to_string());
        let version = query_as::<_, ModelVersion>(
            r#"
            INSERT INTO model_versions (
                model_id, version, framework, status, minio_bucket, minio_object_path,
                gpu_memory_required_mb, input_schema, output_schema, preprocess_pipeline,
                postprocess_pipeline
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
            RETURNING id, model_id, version, framework, status, minio_bucket, minio_object_path,
                      gpu_memory_required_mb, input_schema, output_schema, preprocess_pipeline,
                      postprocess_pipeline, created_at
            "#,
        )
        .bind(params.model_id)
        .bind(params.version)
        .bind(&params.framework)
        .bind(&status)
        .bind(&params.minio_bucket)
        .bind(&params.minio_object_path)
        .bind(params.gpu_memory_required_mb)
        .bind(&params.input_schema)
        .bind(&params.output_schema)
        .bind(&params.preprocess_pipeline)
        .bind(&params.postprocess_pipeline)
        .fetch_one(&self.pool)
        .await?;
        Ok(version)
    }

    async fn get_model_version_by_id(&self, id: Uuid) -> DbResult<Option<ModelVersion>> {
        let version = query_as::<_, ModelVersion>(
            r#"
            SELECT id, model_id, version, framework, status, minio_bucket, minio_object_path,
                   gpu_memory_required_mb, input_schema, output_schema, preprocess_pipeline,
                   postprocess_pipeline, created_at
            FROM model_versions
            WHERE id = $1
            "#,
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(version)
    }

    async fn get_model_versions(&self, model_id: Uuid) -> DbResult<Vec<ModelVersion>> {
        let versions = query_as::<_, ModelVersion>(
            r#"
            SELECT id, model_id, version, framework, status, minio_bucket, minio_object_path,
                   gpu_memory_required_mb, input_schema, output_schema, preprocess_pipeline,
                   postprocess_pipeline, created_at
            FROM model_versions
            WHERE model_id = $1
            ORDER BY version DESC
            "#,
        )
        .bind(model_id)
        .fetch_all(&self.pool)
        .await?;
        Ok(versions)
    }

    async fn get_model_version(
        &self,
        model_id: Uuid,
        version: i32,
    ) -> DbResult<Option<ModelVersion>> {
        let mv = query_as::<_, ModelVersion>(
            r#"
            SELECT id, model_id, version, framework, status, minio_bucket, minio_object_path,
                   gpu_memory_required_mb, input_schema, output_schema, preprocess_pipeline,
                   postprocess_pipeline, created_at
            FROM model_versions
            WHERE model_id = $1 AND version = $2
            "#,
        )
        .bind(model_id)
        .bind(version)
        .fetch_optional(&self.pool)
        .await?;
        Ok(mv)
    }

    async fn get_latest_model_version(&self, model_id: Uuid) -> DbResult<Option<ModelVersion>> {
        let version = query_as::<_, ModelVersion>(
            r#"
            SELECT id, model_id, version, framework, status, minio_bucket, minio_object_path,
                   gpu_memory_required_mb, input_schema, output_schema, preprocess_pipeline,
                   postprocess_pipeline, created_at
            FROM model_versions
            WHERE model_id = $1
            ORDER BY version DESC
            LIMIT 1
            "#,
        )
        .bind(model_id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(version)
    }

    async fn update_model_version(
        &self,
        id: Uuid,
        params: &UpdateModelVersionParams,
    ) -> DbResult<ModelVersion> {
        let minio_bucket = params.minio_bucket.as_ref().and_then(|x| x.clone());
        let minio_object_path = params.minio_object_path.as_ref().and_then(|x| x.clone());
        let gpu_memory_required_mb = params.gpu_memory_required_mb.as_ref().and_then(|x| x.clone());
        let input_schema = params.input_schema.as_ref().and_then(|x| x.clone());
        let output_schema = params.output_schema.as_ref().and_then(|x| x.clone());
        let version = query_as::<_, ModelVersion>(
            r#"
            UPDATE model_versions
            SET
                status = COALESCE($1, status),
                minio_bucket = COALESCE($2, minio_bucket),
                minio_object_path = COALESCE($3, minio_object_path),
                gpu_memory_required_mb = COALESCE($4, gpu_memory_required_mb),
                input_schema = COALESCE($5, input_schema),
                output_schema = COALESCE($6, output_schema)
            WHERE id = $7
            RETURNING id, model_id, version, framework, status, minio_bucket, minio_object_path,
                      gpu_memory_required_mb, input_schema, output_schema, preprocess_pipeline,
                      postprocess_pipeline, created_at
            "#,
        )
        .bind(&params.status)
        .bind(minio_bucket)
        .bind(minio_object_path)
        .bind(gpu_memory_required_mb)
        .bind(input_schema)
        .bind(output_schema)
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok(version)
    }

    async fn delete_model_version(&self, id: Uuid) -> DbResult<()> {
        query(
            r#"
            DELETE FROM model_versions
            WHERE id = $1
            "#,
        )
        .bind(id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn create_deployment(
        &self,
        model_version_id: Uuid,
        gpu_device_id: Uuid,
        status: &str,
    ) -> DbResult<ModelDeployment> {
        let deployment = query_as::<_, ModelDeployment>(
            r#"
            INSERT INTO model_deployments (model_version_id, gpu_device_id, status)
            VALUES ($1, $2, $3)
            RETURNING id, model_version_id, gpu_device_id, status, loaded_at, last_used_at, request_count
            "#,
        )
        .bind(model_version_id)
        .bind(gpu_device_id)
        .bind(status)
        .fetch_one(&self.pool)
        .await?;
        Ok(deployment)
    }

    async fn get_deployment_by_id(&self, id: Uuid) -> DbResult<Option<ModelDeployment>> {
        let deployment = query_as::<_, ModelDeployment>(
            r#"
            SELECT id, model_version_id, gpu_device_id, status, loaded_at, last_used_at, request_count
            FROM model_deployments
            WHERE id = $1
            "#,
        )
        .bind(id)
        .fetch_optional(&self.pool)
        .await?;
        Ok(deployment)
    }

    async fn list_deployments_by_model_version(
        &self,
        model_version_id: Uuid,
    ) -> DbResult<Vec<ModelDeployment>> {
        let deployments = query_as::<_, ModelDeployment>(
            r#"
            SELECT id, model_version_id, gpu_device_id, status, loaded_at, last_used_at, request_count
            FROM model_deployments
            WHERE model_version_id = $1
            ORDER BY COALESCE(last_used_at, loaded_at) DESC NULLS LAST
            "#,
        )
        .bind(model_version_id)
        .fetch_all(&self.pool)
        .await?;
        Ok(deployments)
    }

    async fn list_deployments_by_gpu(
        &self,
        gpu_device_id: Uuid,
    ) -> DbResult<Vec<ModelDeployment>> {
        let deployments = query_as::<_, ModelDeployment>(
            r#"
            SELECT id, model_version_id, gpu_device_id, status, loaded_at, last_used_at, request_count
            FROM model_deployments
            WHERE gpu_device_id = $1
            ORDER BY COALESCE(last_used_at, loaded_at) DESC NULLS LAST
            "#,
        )
        .bind(gpu_device_id)
        .fetch_all(&self.pool)
        .await?;
        Ok(deployments)
    }

    async fn list_deployments_by_status(
        &self,
        status: &str,
    ) -> DbResult<Vec<ModelDeployment>> {
        let deployments = query_as::<_, ModelDeployment>(
            r#"
            SELECT id, model_version_id, gpu_device_id, status, loaded_at, last_used_at, request_count
            FROM model_deployments
            WHERE status = $1
            ORDER BY COALESCE(last_used_at, loaded_at) DESC NULLS LAST
            "#,
        )
        .bind(status)
        .fetch_all(&self.pool)
        .await?;
        Ok(deployments)
    }

    async fn update_deployment_status(
        &self,
        id: Uuid,
        status: &str,
    ) -> DbResult<ModelDeployment> {
        let deployment = query_as::<_, ModelDeployment>(
            r#"
            UPDATE model_deployments
            SET
                status = $1,
                loaded_at = CASE WHEN $1 IN ('loaded', 'running') AND loaded_at IS NULL THEN NOW() ELSE loaded_at END
            WHERE id = $2
            RETURNING id, model_version_id, gpu_device_id, status, loaded_at, last_used_at, request_count
            "#,
        )
        .bind(status)
        .bind(id)
        .fetch_one(&self.pool)
        .await?;
        Ok(deployment)
    }

    async fn increment_request_count(&self, id: Uuid) -> DbResult<()> {
        query(
            r#"
            UPDATE model_deployments
            SET request_count = request_count + 1
            WHERE id = $1
            "#,
        )
        .bind(id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn update_last_used_at(&self, id: Uuid) -> DbResult<()> {
        query(
            r#"
            UPDATE model_deployments
            SET last_used_at = NOW()
            WHERE id = $1
            "#,
        )
        .bind(id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn delete_deployment(&self, id: Uuid) -> DbResult<()> {
        query(
            r#"
            DELETE FROM model_deployments
            WHERE id = $1
            "#,
        )
        .bind(id)
        .execute(&self.pool)
        .await?;
        Ok(())
    }
}
