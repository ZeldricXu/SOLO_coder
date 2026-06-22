use axum::{
    extract::{Multipart, Path, Query, State},
    Json,
};
use common::error::AppError;
use common::types::{
    IOSchema, Model, ModelCategory, ModelFramework, ModelStatus, ModelVersion,
};
use model_registry::RegisterModelParams;
use security::AuthenticatedTenant;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use tracing::{error, info, instrument, warn};
use utoipa::{IntoParams, ToSchema};
use uuid::Uuid;

use crate::state::AppState;

#[derive(Debug, Clone, Deserialize, IntoParams)]
pub struct ListModelsQuery {
    pub category: Option<ModelCategory>,
    #[param(example = 1)]
    pub page: Option<u32>,
    #[param(example = 20)]
    pub page_size: Option<u32>,
}

#[derive(Debug, Clone, Deserialize, Serialize, ToSchema)]
pub struct RegisterModelRequest {
    pub name: String,
    pub category: ModelCategory,
    pub description: Option<String>,
    pub framework: ModelFramework,
    pub version: String,
    pub input_schema: Vec<IOSchema>,
    pub output_schema: Vec<IOSchema>,
    pub gpu_memory_mb: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize, ToSchema)]
pub struct RegisterVersionRequest {
    pub version: String,
    pub framework: ModelFramework,
    pub input_schema: Vec<IOSchema>,
    pub output_schema: Vec<IOSchema>,
    pub gpu_memory_mb: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize, ToSchema)]
pub struct UpdateVersionStatusRequest {
    pub status: ModelStatus,
}

#[derive(Debug, Clone, Serialize, ToSchema)]
pub struct ListResponse<T> {
    pub items: Vec<T>,
    pub total: usize,
    pub page: u32,
    pub page_size: u32,
}

#[utoipa::path(
    post,
    path = "/api/v1/models",
    request_body = RegisterModelRequest,
    responses(
        (status = 201, description = "Model registered successfully", body = Model),
        (status = 400, description = "Invalid request"),
        (status = 401, description = "Unauthorized"),
        (status = 409, description = "Model name already exists"),
    ),
    tag = "models",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(model_name = %request.name))]
pub async fn register_model(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Json(request): Json<RegisterModelRequest>,
) -> Result<Json<Model>, AppError> {
    info!("Registering model: {}", request.name);

    if request.name.is_empty() {
        return Err(AppError::Validation("Model name cannot be empty".to_string()));
    }

    let temp_dir = std::env::temp_dir().join("model_uploads");
    let dummy_path = temp_dir.join(format!("{}-dummy", request.name));
    tokio::fs::create_dir_all(&temp_dir).await.ok();
    tokio::fs::write(&dummy_path, []).await.ok();

    let params = RegisterModelParams {
        model_name: request.name.clone(),
        version: request.version.clone(),
        category: request.category,
        framework: request.framework,
        description: request.description.clone(),
        author: None,
        tags: vec![],
        labels: HashMap::new(),
        input_schema: request.input_schema.clone(),
        output_schema: request.output_schema.clone(),
        gpu_memory_mb: request.gpu_memory_mb,
        max_batch_size: None,
        max_sequence_length: None,
        preferred_backend: None,
        overwrite: false,
    };

    let model = state
        .model_registry
        .register_model(params, &dummy_path)
        .await?;

    let _ = tokio::fs::remove_file(&dummy_path).await;

    info!("Model registered successfully: {} (id={})", request.name, model.id);

    Ok(Json(model))
}

#[utoipa::path(
    get,
    path = "/api/v1/models",
    params(ListModelsQuery),
    responses(
        (status = 200, description = "List of models", body = [Model]),
        (status = 401, description = "Unauthorized"),
    ),
    tag = "models",
    security(("api_key" = [])),
)]
#[instrument(skip_all)]
pub async fn list_models(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Query(query): Query<ListModelsQuery>,
) -> Result<Json<ListResponse<Model>>, AppError> {
    let page = query.page.unwrap_or(1);
    let page_size = query.page_size.unwrap_or(20);

    info!(
        "Listing models: category={:?}, page={}, page_size={}",
        query.category, page, page_size
    );

    let models = state
        .model_registry
        .list_models(query.category, page, page_size)
        .await?;

    Ok(Json(ListResponse {
        total: models.len(),
        items: models,
        page,
        page_size,
    }))
}

#[utoipa::path(
    get,
    path = "/api/v1/models/{id_or_name}",
    params(
        ("id_or_name" = String, Path, description = "Model UUID or name"),
    ),
    responses(
        (status = 200, description = "Model details", body = Model),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Model not found"),
    ),
    tag = "models",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(id_or_name = %id_or_name))]
pub async fn get_model(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Path(id_or_name): Path<String>,
) -> Result<Json<Model>, AppError> {
    info!("Getting model: {}", id_or_name);

    let model = state.model_registry.get_model(&id_or_name).await?;

    Ok(Json(model))
}

#[utoipa::path(
    delete,
    path = "/api/v1/models/{id}",
    params(
        ("id" = Uuid, Path, description = "Model UUID"),
    ),
    responses(
        (status = 204, description = "Model deleted successfully"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Model not found"),
    ),
    tag = "models",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(model_id = %model_id))]
pub async fn delete_model(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Path(model_id): Path<Uuid>,
) -> Result<(), AppError> {
    info!("Deleting model: {}", model_id);

    let model = state.model_registry.get_model(&model_id.to_string()).await?;

    for version in &model.versions {
        state
            .model_registry
            .delete_model_version(&model.name, &version.version)
            .await?;
    }

    info!("Model deleted successfully: {}", model_id);

    Ok(())
}

#[utoipa::path(
    post,
    path = "/api/v1/models/{model_id}/versions",
    params(
        ("model_id" = Uuid, Path, description = "Model UUID"),
    ),
    request_body(content = RegisterVersionRequest, content_type = "multipart/form-data"),
    responses(
        (status = 201, description = "Version registered successfully", body = ModelVersion),
        (status = 400, description = "Invalid request"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Model not found"),
        (status = 409, description = "Version already exists"),
    ),
    tag = "models",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(model_id = %model_id))]
pub async fn upload_model_version(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Path(model_id): Path<Uuid>,
    mut multipart: Multipart,
) -> Result<Json<ModelVersion>, AppError> {
    info!("Uploading model version for model: {}", model_id);

    let mut version_info: Option<RegisterVersionRequest> = None;
    let mut model_file_path: Option<std::path::PathBuf> = None;

    while let Some(field) = multipart
        .next_field()
        .await
        .map_err(|e| AppError::Validation(format!("Failed to read multipart field: {}", e)))?
    {
        let name = field.name().unwrap_or("").to_string();

        match name.as_str() {
            "metadata" => {
                let data = field
                    .bytes()
                    .await
                    .map_err(|e| AppError::Validation(format!("Failed to read metadata: {}", e)))?;
                let metadata: RegisterVersionRequest =
                    serde_json::from_slice(&data).map_err(|e| {
                        AppError::Validation(format!("Invalid metadata JSON: {}", e))
                    })?;
                version_info = Some(metadata);
            }
            "model_file" => {
                let file_name = field.file_name().unwrap_or("model.bin").to_string();
                let data = field
                    .bytes()
                    .await
                    .map_err(|e| AppError::Validation(format!("Failed to read model file: {}", e)))?;
                let temp_dir = std::env::temp_dir().join("model_uploads");
                tokio::fs::create_dir_all(&temp_dir).await.map_err(|e| {
                    AppError::Internal(format!("Failed to create temp directory: {}", e))
                })?;

                let path = temp_dir.join(format!("{}-{}", model_id, file_name));
                tokio::fs::write(&path, &data).await.map_err(|e| {
                    AppError::Internal(format!("Failed to write temp model file: {}", e))
                })?;
                model_file_path = Some(path);
            }
            _ => {
                warn!("Ignoring unknown multipart field: {}", name);
            }
        }
    }

    let metadata = version_info.ok_or_else(|| AppError::Validation("Missing 'metadata' field".to_string()))?;
    let file_path =
        model_file_path.ok_or_else(|| AppError::Validation("Missing 'model_file' field".to_string()))?;

    let model = state.model_registry.get_model(&model_id.to_string()).await?;

    let params = RegisterModelParams {
        model_name: model.name.clone(),
        version: metadata.version.clone(),
        category: model.category,
        framework: metadata.framework,
        description: model.description.clone(),
        author: None,
        tags: vec![],
        labels: HashMap::new(),
        input_schema: metadata.input_schema,
        output_schema: metadata.output_schema,
        gpu_memory_mb: metadata.gpu_memory_mb,
        max_batch_size: None,
        max_sequence_length: None,
        preferred_backend: None,
        overwrite: false,
    };

    let version = state
        .model_registry
        .register_model(params, &file_path)
        .await?
        .versions
        .into_iter()
        .find(|v| v.version == metadata.version)
        .ok_or_else(|| AppError::Internal("Version not found after registration".to_string()))?;

    let _ = tokio::fs::remove_file(&file_path).await;

    info!(
        "Model version v{} registered successfully for model {} (id={})",
        version.version, model_id, version.id
    );

    Ok(Json(version))
}

#[utoipa::path(
    get,
    path = "/api/v1/models/{model_id}/versions",
    params(
        ("model_id" = Uuid, Path, description = "Model UUID"),
    ),
    responses(
        (status = 200, description = "List of model versions", body = [ModelVersion]),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Model not found"),
    ),
    tag = "models",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(model_id = %model_id))]
pub async fn list_versions(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Path(model_id): Path<Uuid>,
) -> Result<Json<Vec<ModelVersion>>, AppError> {
    info!("Listing versions for model: {}", model_id);

    let versions = state.model_registry.list_versions(model_id).await?;

    Ok(Json(versions))
}

#[utoipa::path(
    get,
    path = "/api/v1/models/{model_id}/versions/{version}",
    params(
        ("model_id" = Uuid, Path, description = "Model UUID"),
        ("version" = String, Path, description = "Version number string"),
    ),
    responses(
        (status = 200, description = "Version details", body = ModelVersion),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Version not found"),
    ),
    tag = "models",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(model_id = %model_id, version = %version_str))]
pub async fn get_version(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Path((model_id, version_str)): Path<(Uuid, String)>,
) -> Result<Json<ModelVersion>, AppError> {
    let model = state.model_registry.get_model(&model_id.to_string()).await?;

    let version = state
        .model_registry
        .get_model_version(&model.name, &version_str)
        .await?;

    Ok(Json(version))
}

#[utoipa::path(
    patch,
    path = "/api/v1/versions/{version_id}/status",
    params(
        ("version_id" = Uuid, Path, description = "Version UUID"),
    ),
    request_body = UpdateVersionStatusRequest,
    responses(
        (status = 200, description = "Version status updated", body = ModelVersion),
        (status = 400, description = "Invalid request"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Version not found"),
    ),
    tag = "models",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(version_id = %version_id, status = ?request.status))]
pub async fn update_version_status(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Path(version_id): Path<Uuid>,
    Json(request): Json<UpdateVersionStatusRequest>,
) -> Result<Json<ModelVersion>, AppError> {
    info!(
        "Updating version {} status to {:?}",
        version_id, request.status
    );

    let model = state.model_registry.get_model(&version_id.to_string()).await.ok();

    let version = if let Some(m) = model {
        if let Some(v) = m.versions.iter().find(|v| v.id == version_id) {
            state
                .model_registry
                .update_version_status(&m.name, &v.version, request.status)
                .await?
        } else {
            return Err(AppError::ModelVersionNotFound(format!("Version {} not found", version_id)));
        }
    } else {
        return Err(AppError::ModelVersionNotFound(format!("Version {} not found", version_id)));
    };

    info!("Version {} status updated successfully", version_id);

    Ok(Json(version))
}

#[utoipa::path(
    delete,
    path = "/api/v1/versions/{version_id}",
    params(
        ("version_id" = Uuid, Path, description = "Version UUID"),
    ),
    responses(
        (status = 204, description = "Version deleted successfully"),
        (status = 401, description = "Unauthorized"),
        (status = 404, description = "Version not found"),
    ),
    tag = "models",
    security(("api_key" = [])),
)]
#[instrument(skip_all, fields(version_id = %version_id))]
pub async fn delete_version(
    State(state): State<AppState>,
    _tenant: AuthenticatedTenant,
    Path(version_id): Path<Uuid>,
) -> Result<(), AppError> {
    info!("Deleting version: {}", version_id);

    let model = state.model_registry.get_model(&version_id.to_string()).await.ok();

    if let Some(m) = model {
        if let Some(v) = m.versions.iter().find(|v| v.id == version_id) {
            state
                .model_registry
                .delete_model_version(&m.name, &v.version)
                .await?;
        } else {
            return Err(AppError::ModelVersionNotFound(format!("Version {} not found", version_id)));
        }
    } else {
        return Err(AppError::ModelVersionNotFound(format!("Version {} not found", version_id)));
    }

    info!("Version {} deleted successfully", version_id);

    Ok(())
}
