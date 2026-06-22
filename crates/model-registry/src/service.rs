use std::path::{Path, PathBuf};

use chrono::{DateTime, Utc};
use common::error::AppError;
use common::types::{IOSchema, Model, ModelCategory, ModelFramework, ModelStatus, ModelVersion};
use db::{DatabasePool, RedisClient};
use redis::AsyncCommands;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sqlx::{query, query_as, FromRow, PgPool};
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use crate::minio::MinioClient;

const ONLINE_VERSIONS_CACHE_TTL: u64 = 60;
const MODEL_CACHE_DIR: &str = "/tmp/model-registry-cache";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RegisterModelParams {
    pub model_name: String,
    pub version: String,
    pub category: ModelCategory,
    pub framework: ModelFramework,
    pub description: Option<String>,
    pub author: Option<String>,
    pub tags: Vec<String>,
    pub labels: std::collections::HashMap<String, String>,
    pub input_schema: Vec<IOSchema>,
    pub output_schema: Vec<IOSchema>,
    pub gpu_memory_mb: u64,
    pub max_batch_size: Option<i64>,
    pub max_sequence_length: Option<i64>,
    pub preferred_backend: Option<String>,
    pub overwrite: bool,
}

#[derive(FromRow)]
struct ModelRow {
    id: Uuid,
    name: String,
    category: String,
    description: Option<String>,
    latest_version: Option<i32>,
    created_at: DateTime<Utc>,
    updated_at: DateTime<Utc>,
}

#[derive(FromRow)]
struct ModelVersionRow {
    id: Uuid,
    model_id: Uuid,
    version: i32,
    framework: String,
    status: String,
    minio_bucket: Option<String>,
    minio_object_path: Option<String>,
    gpu_memory_required_mb: Option<i32>,
    input_schema: Option<Value>,
    output_schema: Option<Value>,
    created_at: DateTime<Utc>,
}

#[derive(FromRow)]
struct ExistingVersionRow {
    id: Uuid,
    minio_bucket: Option<String>,
    minio_object_path: Option<String>,
}

#[derive(FromRow)]
struct MaxVersionRow {
    max_ver: Option<i32>,
}

#[derive(FromRow)]
struct ModelVersionIdRow {
    id: Uuid,
    name: String,
}

fn category_from_str(s: &str) -> Result<ModelCategory, AppError> {
    match s.to_lowercase().as_str() {
        "recommendation" => Ok(ModelCategory::Recommendation),
        "nlp" => Ok(ModelCategory::Nlp),
        "cv" => Ok(ModelCategory::Cv),
        other => Err(AppError::Validation(format!(
            "Invalid model category: {}",
            other
        ))),
    }
}

fn category_to_str(c: ModelCategory) -> &'static str {
    match c {
        ModelCategory::Recommendation => "recommendation",
        ModelCategory::Nlp => "nlp",
        ModelCategory::Cv => "cv",
    }
}

fn framework_from_str(s: &str) -> Result<ModelFramework, AppError> {
    match s.to_lowercase().as_str() {
        "onnx" => Ok(ModelFramework::Onnx),
        "tensorrt" => Ok(ModelFramework::TensorRT),
        "tensorflow" => Ok(ModelFramework::Tensorflow),
        "pytorch" => Ok(ModelFramework::Pytorch),
        other => Err(AppError::Validation(format!(
            "Invalid model framework: {}",
            other
        ))),
    }
}

fn framework_to_str(f: ModelFramework) -> &'static str {
    match f {
        ModelFramework::Onnx => "onnx",
        ModelFramework::TensorRT => "tensorrt",
        ModelFramework::Tensorflow => "tensorflow",
        ModelFramework::Pytorch => "pytorch",
    }
}

fn status_from_str(s: &str) -> Result<ModelStatus, AppError> {
    match s.to_lowercase().as_str() {
        "pending" => Ok(ModelStatus::Pending),
        "loading" => Ok(ModelStatus::Loading),
        "online" => Ok(ModelStatus::Online),
        "offline" => Ok(ModelStatus::Offline),
        "failed" => Ok(ModelStatus::Failed),
        other => Err(AppError::Validation(format!(
            "Invalid model status: {}",
            other
        ))),
    }
}

fn status_to_str(s: ModelStatus) -> &'static str {
    match s {
        ModelStatus::Pending => "pending",
        ModelStatus::Loading => "loading",
        ModelStatus::Online => "online",
        ModelStatus::Offline => "offline",
        ModelStatus::Failed => "failed",
    }
}

fn io_schema_from_json(v: Option<&Value>) -> Result<Vec<IOSchema>, AppError> {
    match v {
        None => Ok(Vec::new()),
        Some(val) => serde_json::from_value(val.clone())
            .map_err(|e| AppError::Serialization(format!("Invalid IO schema: {}", e))),
    }
}

fn row_to_model_version(row: ModelVersionRow) -> Result<ModelVersion, AppError> {
    Ok(ModelVersion {
        id: row.id,
        model_id: row.model_id,
        version: row.version.to_string(),
        framework: framework_from_str(&row.framework)?,
        status: status_from_str(&row.status)?,
        input_schema: io_schema_from_json(row.input_schema.as_ref())?,
        output_schema: io_schema_from_json(row.output_schema.as_ref())?,
        gpu_memory_mb: row.gpu_memory_required_mb.unwrap_or(0) as u64,
        created_at: row.created_at,
    })
}

fn row_to_model(row: ModelRow, versions: Vec<ModelVersion>) -> Model {
    Model {
        id: row.id,
        name: row.name,
        category: category_from_str(&row.category).unwrap_or(ModelCategory::Recommendation),
        description: row.description,
        latest_version: row.latest_version.map(|v| v.to_string()),
        versions,
        created_at: row.created_at,
        updated_at: row.updated_at,
    }
}

async fn load_versions_for_model(
    pool: &PgPool,
    model_id: Uuid,
) -> Result<Vec<ModelVersion>, AppError> {
    let rows: Vec<ModelVersionRow> = query_as::<_, ModelVersionRow>(
        r#"
        SELECT id, model_id, version, framework, status, minio_bucket,
               minio_object_path, gpu_memory_required_mb, input_schema,
               output_schema, created_at
        FROM model_versions
        WHERE model_id = $1
        ORDER BY version DESC
        "#,
    )
    .bind(model_id)
    .fetch_all(pool)
    .await
    .map_err(|e| AppError::Database(e.to_string()))?;

    let mut versions = Vec::with_capacity(rows.len());
    for row in rows {
        versions.push(row_to_model_version(row)?);
    }
    Ok(versions)
}

fn online_versions_cache_key(model_name: &str) -> String {
    format!("model:online_versions:{}", model_name)
}

fn model_cache_path(model_name: &str, version: &str) -> PathBuf {
    Path::new(MODEL_CACHE_DIR).join(format!("{}-{}.bin", model_name, version))
}

#[derive(Clone)]
pub struct ModelRegistryService {
    db: DatabasePool,
    redis: RedisClient,
    minio: MinioClient,
    cache_dir: PathBuf,
}

impl ModelRegistryService {
    pub fn new(db: DatabasePool, redis: RedisClient, minio: MinioClient) -> Self {
        Self {
            db,
            redis,
            minio,
            cache_dir: PathBuf::from(MODEL_CACHE_DIR),
        }
    }

    pub fn with_cache_dir(mut self, cache_dir: impl Into<PathBuf>) -> Self {
        self.cache_dir = cache_dir.into();
        self
    }

    pub async fn register_model(
        &self,
        params: RegisterModelParams,
        file_path: impl AsRef<Path>,
    ) -> Result<Model, AppError> {
        let version_num: i32 = params
            .version
            .parse()
            .map_err(|_| AppError::Validation(format!("Invalid version number: {}", params.version)))?;

        let category_str = category_to_str(params.category);
        let framework_str = framework_to_str(params.framework);
        let input_schema_json = serde_json::to_value(&params.input_schema)
            .map_err(|e| AppError::Serialization(e.to_string()))?;
        let output_schema_json = serde_json::to_value(&params.output_schema)
            .map_err(|e| AppError::Serialization(e.to_string()))?;

        let mut tx = self
            .db
            .inner()
            .begin()
            .await
            .map_err(|e| AppError::Database(e.to_string()))?;

        let model_row = query_as::<_, ModelRow>(
            r#"
            SELECT id, name, category, description, latest_version, created_at, updated_at
            FROM models
            WHERE name = $1
            "#,
        )
        .bind(&params.model_name)
        .fetch_optional(&mut *tx)
        .await
        .map_err(|e| AppError::Database(e.to_string()))?;

        let model_id = match model_row {
            Some(row) => row.id,
            None => {
                let row = query_as::<_, ModelRow>(
                    r#"
                    INSERT INTO models (name, category, description)
                    VALUES ($1, $2, $3)
                    RETURNING id, name, category, description, latest_version, created_at, updated_at
                    "#,
                )
                .bind(&params.model_name)
                .bind(category_str)
                .bind(params.description.as_deref())
                .fetch_one(&mut *tx)
                .await
                .map_err(|e| match e {
                    sqlx::Error::Database(db_err) if db_err.is_unique_violation() => {
                        AppError::Validation(format!(
                            "Model name '{}' already exists",
                            params.model_name
                        ))
                    }
                    other => AppError::Database(other.to_string()),
                })?;
                row.id
            }
        };

        let existing_version = query_as::<_, ExistingVersionRow>(
            r#"
            SELECT id, minio_bucket, minio_object_path
            FROM model_versions
            WHERE model_id = $1 AND version = $2
            "#,
        )
        .bind(model_id)
        .bind(version_num)
        .fetch_optional(&mut *tx)
        .await
        .map_err(|e| AppError::Database(e.to_string()))?;

        if existing_version.is_some() && !params.overwrite {
            return Err(AppError::Validation(format!(
                "Version {} already exists for model {}",
                version_num, params.model_name
            )));
        }

        let object_path = self
            .minio
            .upload_model(&params.model_name, &params.version, file_path.as_ref())
            .await
            .map_err(|e| {
                AppError::Internal(format!("Failed to upload model to MinIO: {}", e))
            })?;

        let bucket_name = self.minio.bucket_name().to_string();

        let new_version_id = Uuid::new_v4();

        if let Some(ev) = existing_version {
            if let (Some(b), Some(p)) = (ev.minio_bucket, ev.minio_object_path) {
                let _ = self.minio.delete_model(&params.model_name, &params.version).await;
            }

            query(
                r#"
                UPDATE model_versions
                SET framework = $1, status = 'pending',
                    minio_bucket = $2, minio_object_path = $3,
                    gpu_memory_required_mb = $4,
                    input_schema = $5, output_schema = $6
                WHERE id = $7
                "#,
            )
            .bind(framework_str)
            .bind(Some(&bucket_name))
            .bind(Some(&object_path))
            .bind(Some(params.gpu_memory_mb as i32))
            .bind(Some(&input_schema_json))
            .bind(Some(&output_schema_json))
            .bind(ev.id)
            .execute(&mut *tx)
            .await
            .map_err(|e| AppError::Database(e.to_string()))?;
        } else {
            query(
                r#"
                INSERT INTO model_versions (
                    id, model_id, version, framework, status,
                    minio_bucket, minio_object_path, gpu_memory_required_mb,
                    input_schema, output_schema
                ) VALUES ($1, $2, $3, $4, 'pending', $5, $6, $7, $8, $9)
                "#,
            )
            .bind(new_version_id)
            .bind(model_id)
            .bind(version_num)
            .bind(framework_str)
            .bind(Some(&bucket_name))
            .bind(Some(&object_path))
            .bind(Some(params.gpu_memory_mb as i32))
            .bind(Some(&input_schema_json))
            .bind(Some(&output_schema_json))
            .execute(&mut *tx)
            .await
            .map_err(|e| AppError::Database(e.to_string()))?;
        }

        query(
            r#"
            UPDATE models
            SET updated_at = NOW()
            WHERE id = $1
            "#,
        )
        .bind(model_id)
        .execute(&mut *tx)
        .await
        .map_err(|e| AppError::Database(e.to_string()))?;

        let latest_row = query_as::<_, MaxVersionRow>(
            r#"
            SELECT COALESCE(MAX(version), 0) as max_ver
            FROM model_versions
            WHERE model_id = $1
            "#,
        )
        .bind(model_id)
        .fetch_one(&mut *tx)
        .await
        .map_err(|e| AppError::Database(e.to_string()))?;

        let max_ver = latest_row.max_ver.unwrap_or(0);
        if max_ver >= version_num {
            query(
                r#"
                UPDATE models
                SET latest_version = $1
                WHERE id = $2
                "#,
            )
            .bind(max_ver)
            .bind(model_id)
            .execute(&mut *tx)
            .await
            .map_err(|e| AppError::Database(e.to_string()))?;
        }

        tx.commit()
            .await
            .map_err(|e| AppError::Database(e.to_string()))?;

        self.invalidate_online_versions_cache(&params.model_name).await;

        let model = self.get_model(&params.model_name).await?;

        info!(
            "Registered model {} version {} (overwrite={})",
            params.model_name, params.version, params.overwrite
        );

        Ok(model)
    }

    pub async fn get_model(&self, model_name_or_id: &str) -> Result<Model, AppError> {
        if let Ok(uuid) = Uuid::parse_str(model_name_or_id) {
            if let Some(model) = self.get_model_by_id_internal(uuid).await? {
                let versions = load_versions_for_model(self.db.inner(), model.id).await?;
                return Ok(row_to_model(model, versions));
            }
        }

        let row = query_as::<_, ModelRow>(
            r#"
            SELECT id, name, category, description, latest_version, created_at, updated_at
            FROM models
            WHERE name = $1
            "#,
        )
        .bind(model_name_or_id)
        .fetch_optional(self.db.inner())
        .await
        .map_err(|e| AppError::Database(e.to_string()))?
        .ok_or_else(|| AppError::ModelNotFound(model_name_or_id.to_string()))?;

        let versions = load_versions_for_model(self.db.inner(), row.id).await?;

        Ok(row_to_model(row, versions))
    }

    async fn get_model_by_id_internal(&self, model_id: Uuid) -> Result<Option<ModelRow>, AppError> {
        query_as::<_, ModelRow>(
            r#"
            SELECT id, name, category, description, latest_version, created_at, updated_at
            FROM models
            WHERE id = $1
            "#,
        )
        .bind(model_id)
        .fetch_optional(self.db.inner())
        .await
        .map_err(|e| AppError::Database(e.to_string()))
    }

    pub async fn get_model_with_local_path(
        &self,
        model_name: &str,
        version: &str,
    ) -> Result<(Model, ModelVersion, PathBuf), AppError> {
        let model = self.get_model(model_name).await?;
        let version_obj = self.get_model_version(model_name, version).await?;

        let local_path = self.ensure_model_cached(model_name, version).await?;

        Ok((model, version_obj, local_path))
    }

    pub async fn get_model_version(
        &self,
        model_name: &str,
        version: &str,
    ) -> Result<ModelVersion, AppError> {
        let version_num: i32 = version
            .parse()
            .map_err(|_| AppError::Validation(format!("Invalid version number: {}", version)))?;

        let row = query_as::<_, ModelVersionRow>(
            r#"
            SELECT mv.id, mv.model_id, mv.version, mv.framework, mv.status,
                   mv.minio_bucket, mv.minio_object_path, mv.gpu_memory_required_mb,
                   mv.input_schema, mv.output_schema, mv.created_at
            FROM model_versions mv
            JOIN models m ON m.id = mv.model_id
            WHERE m.name = $1 AND mv.version = $2
            "#,
        )
        .bind(model_name)
        .bind(version_num)
        .fetch_optional(self.db.inner())
        .await
        .map_err(|e| AppError::Database(e.to_string()))?
        .ok_or_else(|| {
            AppError::ModelVersionNotFound(format!(
                "model={}, version={}",
                model_name, version
            ))
        })?;

        Ok(row_to_model_version(row)?)
    }

    pub async fn list_models(
        &self,
        category: Option<ModelCategory>,
        page: u32,
        size: u32,
    ) -> Result<Vec<Model>, AppError> {
        let page = page.max(1);
        let size = size.max(1).min(100);
        let offset = (page - 1) as i64 * size as i64;
        let limit = size as i64;

        let category_filter = category.map(category_to_str);

        let rows: Vec<ModelRow> = match category_filter {
            Some(cat) => {
                query_as::<_, ModelRow>(
                    r#"
                    SELECT id, name, category, description, latest_version, created_at, updated_at
                    FROM models
                    WHERE category = $1
                    ORDER BY created_at DESC
                    LIMIT $2 OFFSET $3
                    "#,
                )
                .bind(cat)
                .bind(limit)
                .bind(offset)
                .fetch_all(self.db.inner())
                .await
            }
            None => {
                query_as::<_, ModelRow>(
                    r#"
                    SELECT id, name, category, description, latest_version, created_at, updated_at
                    FROM models
                    ORDER BY created_at DESC
                    LIMIT $1 OFFSET $2
                    "#,
                )
                .bind(limit)
                .bind(offset)
                .fetch_all(self.db.inner())
                .await
            }
        }
        .map_err(|e| AppError::Database(e.to_string()))?;

        let mut models = Vec::with_capacity(rows.len());
        for row in rows {
            let versions = load_versions_for_model(self.db.inner(), row.id).await?;
            models.push(row_to_model(row, versions));
        }

        Ok(models)
    }

    pub async fn update_version_status(
        &self,
        model_name: &str,
        version: &str,
        status: ModelStatus,
    ) -> Result<ModelVersion, AppError> {
        let version_num: i32 = version
            .parse()
            .map_err(|_| AppError::Validation(format!("Invalid version number: {}", version)))?;

        let status_str = status_to_str(status);

        let version_row = query_as::<_, ModelVersionRow>(
            r#"
            UPDATE model_versions
            SET status = $1
            WHERE model_id = (SELECT id FROM models WHERE name = $2) AND version = $3
            RETURNING id, model_id, version, framework, status,
                      minio_bucket, minio_object_path, gpu_memory_required_mb,
                      input_schema, output_schema, created_at
            "#,
        )
        .bind(status_str)
        .bind(model_name)
        .bind(version_num)
        .fetch_optional(self.db.inner())
        .await
        .map_err(|e| AppError::Database(e.to_string()))?
        .ok_or_else(|| {
            AppError::ModelVersionNotFound(format!(
                "model={}, version={}",
                model_name, version
            ))
        })?;

        self.invalidate_online_versions_cache(model_name).await;

        info!(
            "Updated model {} version {} status to {:?}",
            model_name, version, status
        );

        Ok(row_to_model_version(version_row)?)
    }

    pub async fn delete_model_version(
        &self,
        model_name: &str,
        version: &str,
    ) -> Result<(), AppError> {
        let version_num: i32 = version
            .parse()
            .map_err(|_| AppError::Validation(format!("Invalid version number: {}", version)))?;

        let row = query_as::<_, ModelVersionIdRow>(
            r#"
            SELECT mv.id, m.name
            FROM model_versions mv
            JOIN models m ON m.id = mv.model_id
            WHERE m.name = $1 AND mv.version = $2
            "#,
        )
        .bind(model_name)
        .bind(version_num)
        .fetch_optional(self.db.inner())
        .await
        .map_err(|e| AppError::Database(e.to_string()))?
        .ok_or_else(|| {
            AppError::ModelVersionNotFound(format!(
                "model={}, version={}",
                model_name, version
            ))
        })?;

        let _ = self.minio.delete_model(model_name, version).await;

        query(
            r#"
            DELETE FROM model_versions WHERE id = $1
            "#,
        )
        .bind(row.id)
        .execute(self.db.inner())
        .await
        .map_err(|e| AppError::Database(e.to_string()))?;

        self.invalidate_online_versions_cache(&row.name).await;

        let _ = tokio::fs::remove_file(model_cache_path(model_name, version)).await;

        info!("Deleted version {} of model {}", version, model_name);

        Ok(())
    }

    pub async fn check_model_ready(&self, model_name: &str, version: &str) -> bool {
        match self.get_model_version(model_name, version).await {
            Ok(v) => {
                let status_ready = matches!(v.status, ModelStatus::Online | ModelStatus::Loading);
                let minio_exists = self.minio.model_exists(model_name, version).await;
                status_ready && minio_exists
            }
            Err(e) => {
                debug!("Model not found for ready check: {} v{}: {}", model_name, version, e);
                false
            }
        }
    }

    pub async fn ensure_model_cached(
        &self,
        model_name: &str,
        version: &str,
    ) -> Result<PathBuf, AppError> {
        let local_path = model_cache_path(model_name, version);

        if local_path.exists() {
            debug!("Model already cached: {}", local_path.display());
            return Ok(local_path);
        }

        info!("Downloading model to cache: {} v{}", model_name, version);

        let cache_dir = local_path.parent().unwrap_or(&self.cache_dir);
        tokio::fs::create_dir_all(cache_dir)
            .await
            .map_err(|e| AppError::Internal(format!("Failed to create cache dir: {}", e)))?;

        let downloaded = self
            .minio
            .download_model(model_name, version, cache_dir)
            .await
            .map_err(|e| AppError::Internal(format!("Failed to download model: {}", e)))?;

        Ok(downloaded)
    }

    pub async fn get_online_versions(
        &self,
        model_name: &str,
    ) -> Result<Vec<ModelVersion>, AppError> {
        let cache_key = online_versions_cache_key(model_name);

        let mut manager = self.redis.manager.clone();

        let cached: Option<String> = manager
            .get::<String, Option<String>>(cache_key.clone())
            .await
            .map_err(|e| AppError::Cache(e.to_string()))?;

        if let Some(json) = cached {
            let versions: Vec<ModelVersion> =
                serde_json::from_str(&json).map_err(|e| AppError::Serialization(e.to_string()))?;
            return Ok(versions);
        }

        let rows: Vec<ModelVersionRow> = query_as::<_, ModelVersionRow>(
            r#"
            SELECT mv.id, mv.model_id, mv.version, mv.framework, mv.status,
                   mv.minio_bucket, mv.minio_object_path, mv.gpu_memory_required_mb,
                   mv.input_schema, mv.output_schema, mv.created_at
            FROM model_versions mv
            JOIN models m ON m.id = mv.model_id
            WHERE m.name = $1 AND mv.status = 'online'
            ORDER BY mv.version DESC
            "#,
        )
        .bind(model_name)
        .fetch_all(self.db.inner())
        .await
        .map_err(|e| AppError::Database(e.to_string()))?;

        let mut versions = Vec::with_capacity(rows.len());
        for row in rows {
            versions.push(row_to_model_version(row)?);
        }

        if let Ok(json) = serde_json::to_string(&versions) {
            let mut manager = self.redis.manager.clone();
            let _: Result<(), _> = manager
                .set_ex::<String, String, ()>(cache_key, json, ONLINE_VERSIONS_CACHE_TTL)
                .await;
        }

        Ok(versions)
    }

    async fn invalidate_online_versions_cache(&self, model_name: &str) {
        let cache_key = online_versions_cache_key(model_name);
        let mut manager = self.redis.manager.clone();
        if let Err(e) = manager.del::<String, ()>(cache_key).await {
            warn!("Failed to invalidate cache for model {}: {}", model_name, e);
        }
    }

    pub fn minio(&self) -> &MinioClient {
        &self.minio
    }

    pub fn db(&self) -> &DatabasePool {
        &self.db
    }

    pub fn redis(&self) -> &RedisClient {
        &self.redis
    }

    pub async fn list_versions(&self, _model_id: Uuid) -> Result<Vec<ModelVersion>, AppError> {
        let models = self.list_models(None, 1, 10000).await.unwrap_or_default();
        let mut all_versions = Vec::new();
        for model in models {
            if let Ok(versions) = self.get_online_versions(&model.name).await {
                all_versions.extend(versions);
            }
        }
        Ok(all_versions)
    }
}
