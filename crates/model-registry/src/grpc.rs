use std::collections::HashMap;
use std::str::FromStr;

use chrono::Utc;
use common::error::AppError;
use common::types::{ModelCategory, ModelFramework, ModelStatus};
use tonic::{Request, Response, Status};
use tracing::{error, info, warn};
use uuid::Uuid;

use crate::google::protobuf::Timestamp;

use crate::pb::registry_server::RegistryService;
use crate::pb::{
    GetModelRequest, GetModelResponse, ListModelsRequest, ListModelsResponse, ModelMetadata,
    ModelStatus as ProtoModelStatus, ModelType, RegisterModelRequest, RegisterModelResponse,
    RuntimeBackend, UpdateModelStatusRequest, UpdateModelStatusResponse,
};
use crate::service::{ModelRegistryService, RegisterModelParams};

fn model_type_from_category(category: ModelCategory) -> ModelType {
    match category {
        ModelCategory::Cv => ModelType::Cv,
        ModelCategory::Nlp => ModelType::Llm,
        ModelCategory::Recommendation => ModelType::Custom,
    }
}

fn category_from_model_type(mt: ModelType) -> ModelCategory {
    match mt {
        ModelType::Cv => ModelCategory::Cv,
        ModelType::Llm | ModelType::Embedding | ModelType::Audio | ModelType::Multimodal => {
            ModelCategory::Nlp
        }
        ModelType::Custom | ModelType::Unspecified => ModelCategory::Recommendation,
    }
}

fn proto_status_from_status(status: ModelStatus) -> ProtoModelStatus {
    match status {
        ModelStatus::Online => ProtoModelStatus::Ready,
        ModelStatus::Loading => ProtoModelStatus::Loading,
        ModelStatus::Offline => ProtoModelStatus::Unloaded,
        ModelStatus::Failed => ProtoModelStatus::Error,
        ModelStatus::Pending => ProtoModelStatus::Unspecified,
    }
}

fn status_from_proto_status(ps: ProtoModelStatus) -> ModelStatus {
    match ps {
        ProtoModelStatus::Ready | ProtoModelStatus::Loaded => ModelStatus::Online,
        ProtoModelStatus::Loading | ProtoModelStatus::WarmingUp => ModelStatus::Loading,
        ProtoModelStatus::Unloading => ModelStatus::Offline,
        ProtoModelStatus::Unloaded => ModelStatus::Offline,
        ProtoModelStatus::Error => ModelStatus::Failed,
        ProtoModelStatus::Unspecified => ModelStatus::Pending,
    }
}

fn proto_backend_from_framework(fw: ModelFramework) -> RuntimeBackend {
    match fw {
        ModelFramework::Onnx => RuntimeBackend::Onnxrt,
        ModelFramework::TensorRT => RuntimeBackend::Tensorrt,
        ModelFramework::Tensorflow => RuntimeBackend::Custom,
        ModelFramework::Pytorch => RuntimeBackend::Torch,
    }
}

fn framework_from_proto_backend(rb: RuntimeBackend) -> ModelFramework {
    match rb {
        RuntimeBackend::Onnxrt => ModelFramework::Onnx,
        RuntimeBackend::Tensorrt => ModelFramework::TensorRT,
        RuntimeBackend::Torch => ModelFramework::Pytorch,
        RuntimeBackend::Vllm => ModelFramework::Pytorch,
        RuntimeBackend::Custom | RuntimeBackend::Unspecified => ModelFramework::Onnx,
    }
}

fn model_to_metadata(
    model: &common::types::Model,
    version: Option<&common::types::ModelVersion>,
) -> ModelMetadata {
    let v = version.or_else(|| model.versions.first());

    let (ver_str, preferred_backend, model_uri, model_size) = match v {
        Some(mv) => (
            mv.version.clone(),
            proto_backend_from_framework(mv.framework) as i32,
            String::new(),
            mv.gpu_memory_mb as i64,
        ),
        None => (
            model.latest_version.clone().unwrap_or_default(),
            RuntimeBackend::Unspecified as i32,
            String::new(),
            0,
        ),
    };

    ModelMetadata {
        model_id: model.id.to_string(),
        model_name: model.name.clone(),
        version: ver_str,
        model_type: model_type_from_category(model.category) as i32,
        description: model.description.clone().unwrap_or_default(),
        author: String::new(),
        tags: Vec::new(),
        labels: HashMap::new(),
        created_at: Some(Timestamp {
            seconds: model.created_at.timestamp(),
            nanos: model.created_at.timestamp_subsec_nanos() as i32,
        }),
        updated_at: Some(Timestamp {
            seconds: model.updated_at.timestamp(),
            nanos: model.updated_at.timestamp_subsec_nanos() as i32,
        }),
        model_uri,
        model_size_bytes: model_size,
        preferred_backend,
        input_schema: HashMap::new(),
        output_schema: HashMap::new(),
        max_batch_size: 0,
        max_sequence_length: 0,
    }
}

#[derive(Clone)]
pub struct RegistryServiceImpl {
    service: ModelRegistryService,
}

impl RegistryServiceImpl {
    pub fn new(service: ModelRegistryService) -> Self {
        Self { service }
    }

    pub fn into_inner(self) -> ModelRegistryService {
        self.service
    }
}

#[tonic::async_trait]
impl RegistryService for RegistryServiceImpl {
    async fn register_model(
        &self,
        request: Request<RegisterModelRequest>,
    ) -> Result<Response<RegisterModelResponse>, Status> {
        let req = request.into_inner();
        let metadata = req
            .metadata
            .ok_or_else(|| Status::invalid_argument("Model metadata is required"))?;

        let mt = ModelType::from_i32(metadata.model_type).unwrap_or(ModelType::Custom);
        let category = category_from_model_type(mt);
        let backend = RuntimeBackend::from_i32(metadata.preferred_backend)
            .unwrap_or(RuntimeBackend::Unspecified);
        let framework = framework_from_proto_backend(backend);

        let params = RegisterModelParams {
            model_name: metadata.model_name.clone(),
            version: if metadata.version.is_empty() {
                "1".to_string()
            } else {
                metadata.version.clone()
            },
            category,
            framework,
            description: if metadata.description.is_empty() {
                None
            } else {
                Some(metadata.description)
            },
            author: if metadata.author.is_empty() {
                None
            } else {
                Some(metadata.author)
            },
            tags: metadata.tags,
            labels: metadata.labels,
            input_schema: Vec::new(),
            output_schema: Vec::new(),
            gpu_memory_mb: metadata.model_size_bytes.max(0) as u64,
            max_batch_size: Some(metadata.max_batch_size),
            max_sequence_length: Some(metadata.max_sequence_length),
            preferred_backend: None,
            overwrite: req.overwrite,
        };

        let tmp_path = std::env::temp_dir().join(format!(
            "model-upload-{}-{}.bin",
            params.model_name, params.version
        ));

        let result = self.service.register_model(params, &tmp_path).await;

        match result {
            Ok(model) => {
                let registered_at = Some(Timestamp {
                    seconds: model.created_at.timestamp(),
                    nanos: model.created_at.timestamp_subsec_nanos() as i32,
                });

                info!(
                    "gRPC: Registered model {} version {}",
                    metadata.model_name, metadata.version
                );

                Ok(Response::new(RegisterModelResponse {
                    model_id: model.id.to_string(),
                    model_name: model.name,
                    version: metadata.version,
                    registered_at,
                    error: String::new(),
                }))
            }
            Err(e) => {
                error!("gRPC: Failed to register model: {}", e);
                match e {
                    AppError::Validation(msg) => Err(Status::already_exists(msg)),
                    other => Err(Status::internal(other.to_string())),
                }
            }
        }
    }

    async fn get_model(
        &self,
        request: Request<GetModelRequest>,
    ) -> Result<Response<GetModelResponse>, Status> {
        let req = request.into_inner();

        let id_or_name = match req.query {
            Some(crate::pb::get_model_request::Query::ModelId(id)) => id,
            Some(crate::pb::get_model_request::Query::ModelName(name)) => name,
            None => {
                return Err(Status::invalid_argument(
                    "Either model_id or model_name is required",
                ))
            }
        };

        let result = self.service.get_model(&id_or_name).await;

        match result {
            Ok(model) => {
                let target_version = if req.version.is_empty() {
                    model.versions.first()
                } else {
                    model.versions.iter().find(|v| v.version == req.version)
                };

                let metadata = model_to_metadata(&model, target_version);

                info!("gRPC: Get model {}", id_or_name);

                Ok(Response::new(GetModelResponse {
                    metadata: Some(metadata),
                    error: String::new(),
                }))
            }
            Err(e) => {
                warn!("gRPC: Model not found: {}", e);
                Ok(Response::new(GetModelResponse {
                    metadata: None,
                    error: e.to_string(),
                }))
            }
        }
    }

    async fn list_models(
        &self,
        request: Request<ListModelsRequest>,
    ) -> Result<Response<ListModelsResponse>, Status> {
        let req = request.into_inner();

        let page = if req.page_token.is_empty() {
            1
        } else {
            req.page_token.parse::<u32>().unwrap_or(1)
        };
        let page_size = if req.page_size <= 0 {
            50
        } else {
            req.page_size as u32
        };

        let category_filter = if req.model_type != ModelType::Unspecified as i32 {
            let mt = ModelType::from_i32(req.model_type).unwrap_or(ModelType::Unspecified);
            Some(category_from_model_type(mt))
        } else {
            None
        };

        let result = self
            .service
            .list_models(category_filter, page, page_size)
            .await;

        match result {
            Ok(models) => {
                let total_count = models.len() as i32;

                let proto_models: Vec<ModelMetadata> = models
                    .iter()
                    .map(|m| model_to_metadata(m, m.versions.first()))
                    .collect();

                let next_page_token = if proto_models.len() == page_size as usize {
                    (page + 1).to_string()
                } else {
                    String::new()
                };

                info!("gRPC: List models returned {} items", proto_models.len());

                Ok(Response::new(ListModelsResponse {
                    models: proto_models,
                    next_page_token,
                    total_count,
                    error: String::new(),
                }))
            }
            Err(e) => {
                error!("gRPC: Failed to list models: {}", e);
                Ok(Response::new(ListModelsResponse {
                    models: Vec::new(),
                    next_page_token: String::new(),
                    total_count: 0,
                    error: e.to_string(),
                }))
            }
        }
    }

    async fn update_model_status(
        &self,
        request: Request<UpdateModelStatusRequest>,
    ) -> Result<Response<UpdateModelStatusResponse>, Status> {
        let req = request.into_inner();

        let ps = ProtoModelStatus::from_i32(req.status).unwrap_or(ProtoModelStatus::Unspecified);
        let new_status = status_from_proto_status(ps);

        let result = async {
            let model_name = if !req.model_name.is_empty() {
                req.model_name.clone()
            } else if !req.model_id.is_empty() {
                let model_id = Uuid::from_str(&req.model_id)
                    .map_err(|_| AppError::Validation("Invalid model_id".to_string()))?;
                let model = self.service.get_model(&model_id.to_string()).await?;
                model.name.clone()
            } else {
                return Err(AppError::Validation(
                    "Either model_id or model_name is required".to_string(),
                ));
            };

            let version = if req.version.is_empty() {
                let model = self.service.get_model(&model_name).await?;
                model
                    .latest_version
                    .clone()
                    .ok_or_else(|| AppError::ModelVersionNotFound(
                        format!("No versions found for model {}", model_name)
                    ))?
            } else {
                req.version.clone()
            };

            let updated = self
                .service
                .update_version_status(&model_name, &version, new_status)
                .await?;

            Ok((updated.model_id, model_name, version, updated))
        }
        .await;

        match result {
            Ok((model_id, model_name, version, updated)) => {
                let updated_at = Some(Timestamp {
                    seconds: Utc::now().timestamp(),
                    nanos: 0,
                });

                info!(
                    "gRPC: Updated model {} version {} status to {:?}",
                    model_name, version, new_status
                );

                Ok(Response::new(UpdateModelStatusResponse {
                    model_id: model_id.to_string(),
                    model_name,
                    version,
                    status: proto_status_from_status(updated.status) as i32,
                    updated_at,
                    error: String::new(),
                }))
            }
            Err(e) => {
                error!("gRPC: Failed to update model status: {}", e);
                Ok(Response::new(UpdateModelStatusResponse {
                    model_id: req.model_id,
                    model_name: req.model_name,
                    version: req.version,
                    status: req.status,
                    updated_at: None,
                    error: e.to_string(),
                }))
            }
        }
    }
}
