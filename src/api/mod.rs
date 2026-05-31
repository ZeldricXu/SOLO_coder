use crate::config::{AppConfig, DynamicConfigManager, DeploymentScene, TeeConfig, MpcStrategyConfig, MaskingAsyncConfig};
use crate::models::{
    ApiResponse, BatchRequest, BatchResponse, BatchResult, ResourceCreateRequest,
    ResourceCreateResponse, ResourceStatus, AppError,
};
use crate::tee::TeeManager;
use crate::mpc::MpcManager;
use crate::masking::{DynamicMaskingEngine, AsyncMaskingEngine, MaskingContext, UserRole, MaskingTaskStatus};
use crate::classification::DataClassificationEngine;
use crate::dp::DifferentialPrivacyEngine;
use crate::auditlog::AuditLogManager;
use crate::shamir::ShamirSecretSharing;
use crate::federated::FederatedLearningCoordinator;
use axum::{
    extract::{Path, State},
    http::StatusCode,
    response::{IntoResponse, Response},
    routing::{get, post, delete},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};
use uuid::Uuid;

#[derive(Clone)]
pub struct AppState {
    pub config: AppConfig,
    pub config_manager: Arc<DynamicConfigManager>,
    pub tee_manager: Arc<TeeManager>,
    pub mpc_manager: Arc<MpcManager>,
    pub masking_engine: Arc<DynamicMaskingEngine>,
    pub async_masking_engine: Arc<RwLock<AsyncMaskingEngine>>,
    pub classification_engine: Arc<DataClassificationEngine>,
    pub dp_engine: Arc<DifferentialPrivacyEngine>,
    pub audit_log: Arc<AuditLogManager>,
    pub shamir: Arc<ShamirSecretSharing>,
    pub federated: Arc<FederatedLearningCoordinator>,
    pub resources: Arc<Mutex<HashMap<String, ResourceStatus>>>,
}

impl AppState {
    pub fn new(config: AppConfig) -> Self {
        let config_manager = Arc::new(DynamicConfigManager::new());

        let tee_manager = Arc::new(TeeManager::new(config_manager.clone()).expect("Failed to create TeeManager"));

        let mpc_manager = Arc::new(MpcManager::with_config_manager(
            config.mpc.clone(),
            config_manager.clone(),
        ));

        let masking_engine = Arc::new(DynamicMaskingEngine::new(config.masking.clone()));

        let async_engine = AsyncMaskingEngine::with_config_manager(
            config.masking.clone(),
            config_manager.clone(),
        );

        Self {
            config_manager,
            tee_manager,
            mpc_manager,
            masking_engine: masking_engine.clone(),
            async_masking_engine: Arc::new(RwLock::new(async_engine)),
            classification_engine: Arc::new(DataClassificationEngine::new(config.classification.clone())),
            dp_engine: Arc::new(DifferentialPrivacyEngine::new(config.dp.clone())),
            audit_log: Arc::new(AuditLogManager::new(config.auditlog.clone())),
            shamir: Arc::new(ShamirSecretSharing::new(config.shamir.clone())),
            federated: Arc::new(FederatedLearningCoordinator::new(config.federated.clone())),
            resources: Arc::new(Mutex::new(HashMap::new())),
            config,
        }
    }

    pub async fn start_async_masking(&self) {
        let mut engine = self.async_masking_engine.write().await;
        engine.start();
    }

    pub async fn stop_async_masking(&self) {
        let mut engine = self.async_masking_engine.write().await;
        engine.stop();
    }
}

pub struct AppErrorWrapper(AppError);

impl IntoResponse for AppErrorWrapper {
    fn into_response(self) -> Response {
        let (status, message) = match self.0 {
            AppError::Validation(msg) => (StatusCode::UNPROCESSABLE_ENTITY, msg),
            AppError::Timeout => (StatusCode::GATEWAY_TIMEOUT, "上游服务响应超时".to_string()),
            AppError::Internal(msg) => (StatusCode::INTERNAL_SERVER_ERROR, msg),
            AppError::NotFound(msg) => (StatusCode::NOT_FOUND, msg),
        };

        let body = Json(serde_json::json!({
            "code": status.as_u16(),
            "error": message,
        }));

        (status, body).into_response()
    }
}

impl From<AppError> for AppErrorWrapper {
    fn from(err: AppError) -> Self {
        AppErrorWrapper(err)
    }
}

pub fn create_router(state: AppState) -> Router {
    Router::new()
        .route("/api/v1/resources", post(create_resource).get(list_resources))
        .route("/api/v1/resources/:id/status", get(get_resource_status))
        .route("/api/v1/resources/batch", post(batch_operations))
        .route("/api/v1/health", get(health_check))
        .route("/api/v1/tee/enclaves", post(tee_create_enclave).get(tee_list_enclaves))
        .route("/api/v1/tee/enclaves/:id", get(tee_get_enclave).delete(tee_destroy_enclave))
        .route("/api/v1/tee/enclaves/:id/attest", post(tee_attest))
        .route("/api/v1/tee/enclaves/:id/execute", post(tee_execute))
        .route("/api/v1/tee/config/scenes", get(tee_list_scenes).post(tee_create_scene))
        .route("/api/v1/tee/config/scenes/:scene", get(tee_get_scene_config).put(tee_update_scene_config))
        .route("/api/v1/tee/config/scenes/:scene/versions", get(tee_list_versions))
        .route("/api/v1/tee/config/scenes/:scene/versions/:version/rollback", post(tee_rollback_config))
        .route("/api/v1/tee/config/active-scene", get(tee_get_active_scene).put(tee_set_active_scene))
        .route("/api/v1/tee/config/refresh", post(tee_refresh_config))
        .route("/api/v1/mpc/sessions", post(mpc_create_session).get(mpc_list_sessions))
        .route("/api/v1/mpc/sessions/:id", get(mpc_get_session))
        .route("/api/v1/mpc/sessions/:id/join", post(mpc_join))
        .route("/api/v1/mpc/sessions/:id/submit", post(mpc_submit_input))
        .route("/api/v1/mpc/sessions/:id/compute", post(mpc_compute))
        .route("/api/v1/mpc/sessions/:id/rollback", post(mpc_rollback_session))
        .route("/api/v1/mpc/strategies", get(mpc_list_strategies))
        .route("/api/v1/mpc/strategies/active", get(mpc_get_active_strategy).put(mpc_set_active_strategy))
        .route("/api/v1/masking/mask", post(masking_mask))
        .route("/api/v1/masking/rules", get(masking_list_rules))
        .route("/api/v1/masking/async", post(masking_async_submit))
        .route("/api/v1/masking/async/tasks/:id", get(masking_async_get_task).delete(masking_async_cancel_task))
        .route("/api/v1/masking/async/engine", post(masking_async_start).delete(masking_async_stop))
        .route("/api/v1/masking/async/config", get(masking_async_get_config).put(masking_async_update_config))
        .route("/api/v1/classification/scan", post(classification_scan))
        .route("/api/v1/classification/patterns", get(classification_list_patterns))
        .route("/api/v1/dp/noisify", post(dp_add_noise))
        .route("/api/v1/dp/budget", get(dp_check_budget))
        .route("/api/v1/audit/logs", post(audit_log_entry).get(audit_list_logs))
        .route("/api/v1/audit/verify", get(audit_verify))
        .route("/api/v1/shamir/split", post(shamir_split))
        .route("/api/v1/shamir/recover", post(shamir_recover))
        .route("/api/v1/federated/tasks", post(federated_create_task).get(federated_list_tasks))
        .route("/api/v1/federated/tasks/:id", get(federated_get_task))
        .route("/api/v1/federated/clients", post(federated_register_client).get(federated_list_clients))
        .route("/api/v1/federated/tasks/:id/submit", post(federated_submit_gradient))
        .with_state(state)
}

async fn health_check() -> impl IntoResponse {
    Json(serde_json::json!({
        "status": "healthy",
        "version": env!("CARGO_PKG_VERSION"),
    }))
}

async fn create_resource(
    State(state): State<AppState>,
    Json(request): Json<ResourceCreateRequest>,
) -> Result<Json<ApiResponse<ResourceCreateResponse>>, AppErrorWrapper> {
    let id = format!("rsc_{}", Uuid::new_v4().simple());
    let response = ResourceCreateResponse {
        id: id.clone(),
        status: "provisioning".to_string(),
    };

    let mut resources = state.resources.lock().await;
    resources.insert(
        id.clone(),
        ResourceStatus {
            id: id.clone(),
            status: "provisioning".to_string(),
            progress: 0.0,
        },
    );

    Ok(Json(ApiResponse {
        code: 201,
        data: response,
    }))
}

async fn list_resources(
    State(state): State<AppState>,
) -> Json<ApiResponse<Vec<ResourceStatus>>> {
    let resources = state.resources.lock().await;
    let list: Vec<ResourceStatus> = resources.values().cloned().collect();
    Json(ApiResponse {
        code: 200,
        data: list,
    })
}

async fn get_resource_status(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<ApiResponse<ResourceStatus>>, AppErrorWrapper> {
    let resources = state.resources.lock().await;
    let resource = resources
        .get(&id)
        .ok_or_else(|| AppError::NotFound(format!("Resource not found: {}", id)))?;
    Ok(Json(ApiResponse {
        code: 200,
        data: resource.clone(),
    }))
}

async fn batch_operations(
    State(state): State<AppState>,
    Json(request): Json<BatchRequest>,
) -> Json<ApiResponse<BatchResponse>> {
    let mut results = Vec::new();
    let mut resources = state.resources.lock().await;

    for op in request.operations {
        let result = match op.action.as_str() {
            "restart" => {
                if let Some(resource) = resources.get_mut(&op.id) {
                    resource.status = "restarting".to_string();
                    BatchResult {
                        id: op.id,
                        action: op.action,
                        success: true,
                        message: Some("Resource restarted".to_string()),
                    }
                } else {
                    BatchResult {
                        id: op.id,
                        action: op.action,
                        success: false,
                        message: Some("Resource not found".to_string()),
                    }
                }
            }
            "stop" => {
                if let Some(resource) = resources.get_mut(&op.id) {
                    resource.status = "stopped".to_string();
                    BatchResult {
                        id: op.id,
                        action: op.action,
                        success: true,
                        message: Some("Resource stopped".to_string()),
                    }
                } else {
                    BatchResult {
                        id: op.id,
                        action: op.action,
                        success: false,
                        message: Some("Resource not found".to_string()),
                    }
                }
            }
            _ => BatchResult {
                id: op.id,
                action: op.action,
                success: false,
                message: Some(format!("Unknown action: {}", op.action)),
            },
        };
        results.push(result);
    }

    Json(ApiResponse {
        code: 200,
        data: BatchResponse {
            batch_id: format!("batch_{}", Uuid::new_v4().simple()),
            results,
        },
    })
}

use crate::tee::{EnclaveCreateRequest, AttestationRequest, EnclaveExecuteRequest};

async fn tee_create_enclave(
    State(state): State<AppState>,
    Json(request): Json<EnclaveCreateRequest>,
) -> Result<Json<ApiResponse<crate::tee::Enclave>>, AppErrorWrapper> {
    let enclave = state
        .tee_manager
        .create_enclave(request)?;
    Ok(Json(ApiResponse {
        code: 201,
        data: enclave,
    }))
}

async fn tee_list_enclaves(
    State(state): State<AppState>,
) -> Json<ApiResponse<Vec<crate::tee::Enclave>>> {
    let enclaves = state.tee_manager.list_enclaves();
    Json(ApiResponse {
        code: 200,
        data: enclaves,
    })
}

async fn tee_get_enclave(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<ApiResponse<crate::tee::Enclave>>, AppErrorWrapper> {
    let enclave = state
        .tee_manager
        .get_enclave(&id)
        .ok_or_else(|| AppError::NotFound(format!("Enclave not found: {}", id)))?;
    Ok(Json(ApiResponse {
        code: 200,
        data: enclave,
    }))
}

async fn tee_destroy_enclave(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<ApiResponse<serde_json::Value>>, AppErrorWrapper> {
    state.tee_manager.destroy_enclave(&id)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: serde_json::json!({"message": "Enclave destroyed"}),
    }))
}

async fn tee_attest(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(mut request): Json<AttestationRequest>,
) -> Result<Json<ApiResponse<crate::tee::AttestationResponse>>, AppErrorWrapper> {
    request.enclave_id = id;
    let response = state.tee_manager.perform_remote_attestation(request)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: response,
    }))
}

async fn tee_execute(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(mut request): Json<EnclaveExecuteRequest>,
) -> Result<Json<ApiResponse<serde_json::Value>>, AppErrorWrapper> {
    request.enclave_id = id;
    let result = state.tee_manager.execute_in_enclave(request)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: serde_json::json!({
            "checksum": hex::encode(result.checksum),
            "length": result.length_prefix,
            "valid": result.verify(),
        }),
    }))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TeeSceneResponse {
    pub scene: String,
    pub config: TeeConfig,
}

async fn tee_list_scenes(
    State(state): State<AppState>,
) -> Json<ApiResponse<Vec<String>>> {
    let scenes: Vec<String> = state.config_manager.list_scenes()
        .iter()
        .map(|s| s.to_str().to_string())
        .collect();
    Json(ApiResponse {
        code: 200,
        data: scenes,
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TeeSceneCreateRequest {
    pub scene: String,
    pub config: TeeConfig,
}

async fn tee_create_scene(
    State(_state): State<AppState>,
    Json(_request): Json<TeeSceneCreateRequest>,
) -> Result<Json<ApiResponse<serde_json::Value>>, AppErrorWrapper> {
    Ok(Json(ApiResponse {
        code: 201,
        data: serde_json::json!({"message": "Scene created (requires pre-initialization in manager)"}),
    }))
}

async fn tee_get_scene_config(
    State(state): State<AppState>,
    Path(scene_name): Path<String>,
) -> Result<Json<ApiResponse<TeeSceneResponse>>, AppErrorWrapper> {
    let scene = DeploymentScene::from_str(&scene_name)?;
    let config = state.config_manager.get_tee_config_for_scene(scene)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: TeeSceneResponse {
            scene: scene_name,
            config,
        },
    }))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TeeSceneUpdateRequest {
    pub config: TeeConfig,
    pub changelog: Option<String>,
    pub created_by: Option<String>,
}

async fn tee_update_scene_config(
    State(state): State<AppState>,
    Path(scene_name): Path<String>,
    Json(request): Json<TeeSceneUpdateRequest>,
) -> Result<Json<ApiResponse<serde_json::Value>>, AppErrorWrapper> {
    let scene = DeploymentScene::from_str(&scene_name)?;
    let new_version = state.config_manager.update_tee_config(
        scene,
        request.config,
        request.changelog,
        request.created_by,
    )?;
    Ok(Json(ApiResponse {
        code: 200,
        data: serde_json::json!({
            "version": new_version,
            "message": "Configuration updated"
        }),
    }))
}

async fn tee_list_versions(
    State(state): State<AppState>,
    Path(scene_name): Path<String>,
) -> Result<Json<ApiResponse<Vec<crate::config::ConfigVersion>>>, AppErrorWrapper> {
    let scene = DeploymentScene::from_str(&scene_name)?;
    let versions = state.config_manager.get_tee_config_versions(scene);
    Ok(Json(ApiResponse {
        code: 200,
        data: versions,
    }))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TeeRollbackRequest {
    pub version: u32,
}

async fn tee_rollback_config(
    State(state): State<AppState>,
    Path((scene_name, version_str)): Path<(String, String)>,
) -> Result<Json<ApiResponse<serde_json::Value>>, AppErrorWrapper> {
    let scene = DeploymentScene::from_str(&scene_name)?;
    let version: u32 = version_str.parse()
        .map_err(|_| AppError::Validation("Invalid version number".to_string()))?;
    state.config_manager.rollback_tee_config(scene, version)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: serde_json::json!({"message": "Configuration rolled back"}),
    }))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ActiveSceneResponse {
    pub scene: String,
    pub config: TeeConfig,
}

async fn tee_get_active_scene(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<ActiveSceneResponse>>, AppErrorWrapper> {
    let scene = state.config_manager.get_active_scene();
    let config = state.config_manager.get_tee_config()?;
    Ok(Json(ApiResponse {
        code: 200,
        data: ActiveSceneResponse {
            scene: scene.to_str().to_string(),
            config,
        },
    }))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SetActiveSceneRequest {
    pub scene: String,
}

async fn tee_set_active_scene(
    State(state): State<AppState>,
    Json(request): Json<SetActiveSceneRequest>,
) -> Result<Json<ApiResponse<serde_json::Value>>, AppErrorWrapper> {
    let scene = DeploymentScene::from_str(&request.scene)?;
    state.config_manager.set_active_scene(scene)?;
    state.tee_manager.refresh_config()?;
    Ok(Json(ApiResponse {
        code: 200,
        data: serde_json::json!({"message": "Active scene changed", "scene": request.scene}),
    }))
}

async fn tee_refresh_config(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<serde_json::Value>>, AppErrorWrapper> {
    state.tee_manager.refresh_config()?;
    Ok(Json(ApiResponse {
        code: 200,
        data: serde_json::json!({"message": "Configuration refreshed"}),
    }))
}

use crate::mpc::{MpcSessionCreateRequest, MpcJoinRequest, MpcSubmitInputRequest};

async fn mpc_create_session(
    State(state): State<AppState>,
    Json(request): Json<MpcSessionCreateRequest>,
) -> Result<Json<ApiResponse<crate::mpc::MpcSession>>, AppErrorWrapper> {
    let session = state.mpc_manager.create_session(request)?;
    Ok(Json(ApiResponse {
        code: 201,
        data: session,
    }))
}

async fn mpc_list_sessions(
    State(state): State<AppState>,
) -> Json<ApiResponse<Vec<crate::mpc::MpcSession>>> {
    let sessions = state.mpc_manager.list_sessions();
    Json(ApiResponse {
        code: 200,
        data: sessions,
    })
}

async fn mpc_get_session(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<ApiResponse<crate::mpc::MpcSession>>, AppErrorWrapper> {
    let session = state
        .mpc_manager
        .get_session(&id)
        .ok_or_else(|| AppError::NotFound(format!("Session not found: {}", id)))?;
    Ok(Json(ApiResponse {
        code: 200,
        data: session,
    }))
}

async fn mpc_join(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(mut request): Json<MpcJoinRequest>,
) -> Result<Json<ApiResponse<crate::mpc::MpcParticipant>>, AppErrorWrapper> {
    request.session_id = id;
    let participant = state.mpc_manager.join_session(request)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: participant,
    }))
}

async fn mpc_submit_input(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(mut request): Json<MpcSubmitInputRequest>,
) -> Result<Json<ApiResponse<serde_json::Value>>, AppErrorWrapper> {
    request.session_id = id;
    state.mpc_manager.submit_encrypted_input(request)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: serde_json::json!({"message": "Input submitted"}),
    }))
}

async fn mpc_compute(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<ApiResponse<crate::mpc::MpcResult>>, AppErrorWrapper> {
    state.mpc_manager.start_computation(&id)?;
    let result = state.mpc_manager.execute_computation(&id)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: result,
    }))
}

async fn mpc_rollback_session(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<ApiResponse<crate::mpc::MpcSession>>, AppErrorWrapper> {
    let session = state.mpc_manager.rollback_session(&id)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: session,
    }))
}

async fn mpc_list_strategies(
    State(state): State<AppState>,
) -> Json<ApiResponse<Vec<crate::mpc::StrategyInfo>>> {
    let strategies = state.mpc_manager.list_available_strategies();
    Json(ApiResponse {
        code: 200,
        data: strategies,
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpcStrategyResponse {
    pub active_strategy: String,
}

async fn mpc_get_active_strategy(
    State(state): State<AppState>,
) -> Json<ApiResponse<MpcStrategyResponse>> {
    let active = state.mpc_manager.get_active_strategy_name();
    Json(ApiResponse {
        code: 200,
        data: MpcStrategyResponse { active_strategy: active },
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MpcSetStrategyRequest {
    pub strategy: String,
}

async fn mpc_set_active_strategy(
    State(state): State<AppState>,
    Json(request): Json<MpcSetStrategyRequest>,
) -> Result<Json<ApiResponse<serde_json::Value>>, AppErrorWrapper> {
    state.mpc_manager.set_active_strategy(&request.strategy)?;
    state.config_manager.set_active_mpc_strategy(&request.strategy)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: serde_json::json!({
            "message": "Strategy activated",
            "strategy": request.strategy
        }),
    }))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MaskingRequest {
    pub user_role: String,
    pub fields: HashMap<String, String>,
}

async fn masking_mask(
    State(state): State<AppState>,
    Json(request): Json<MaskingRequest>,
) -> Result<Json<ApiResponse<HashMap<String, crate::masking::MaskingResult>>>, AppErrorWrapper> {
    let role = crate::masking::UserRole::from_str(&request.user_role)?;
    let context = crate::masking::MaskingContext::new("api_user", role);
    let results = state.masking_engine.batch_mask(&request.fields, &context);
    Ok(Json(ApiResponse {
        code: 200,
        data: results,
    }))
}

async fn masking_list_rules(
    State(state): State<AppState>,
) -> Json<ApiResponse<Vec<crate::masking::MaskingRule>>> {
    let rules = state.masking_engine.list_rules();
    Json(ApiResponse {
        code: 200,
        data: rules,
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AsyncMaskingSubmitRequest {
    pub user_role: String,
    pub task_type: String,
    pub fields: Option<HashMap<String, String>>,
    pub json_value: Option<serde_json::Value>,
    pub text: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AsyncMaskingSubmitResponse {
    pub task_id: String,
    pub status: String,
}

async fn masking_async_submit(
    State(state): State<AppState>,
    Json(request): Json<AsyncMaskingSubmitRequest>,
) -> Result<Json<ApiResponse<AsyncMaskingSubmitResponse>>, AppErrorWrapper> {
    let role = UserRole::from_str(&request.user_role)?;
    let context = MaskingContext::new("async_api_user", role);

    let engine = state.async_masking_engine.read().await;
    
    if !engine.is_running() {
        return Err(AppErrorWrapper::from(AppError::Internal("Async masking engine not started".to_string())));
    }

    let task_id = match request.task_type.as_str() {
        "field" => {
            let fields = request.fields.ok_or_else(|| 
                AppError::Validation("fields required for field masking".to_string()))?;
            if fields.len() == 1 {
                let (field_name, value) = fields.into_iter().next().unwrap();
                engine.submit_mask_field(field_name, value, context, Vec::new()).await
            } else {
                engine.submit_batch_mask(fields, context, Vec::new()).await
            }
        }
        "json" => {
            let value = request.json_value.ok_or_else(|| 
                AppError::Validation("json_value required for JSON masking".to_string()))?;
            engine.submit_mask_json(value, context, Vec::new()).await
        }
        "text" => {
            let text = request.text.ok_or_else(|| 
                AppError::Validation("text required for text masking".to_string()))?;
            engine.submit_mask_text(text, context, Vec::new()).await
        }
        "batch" => {
            let fields = request.fields.ok_or_else(|| 
                AppError::Validation("fields required for batch masking".to_string()))?;
            engine.submit_batch_mask(fields, context, Vec::new()).await
        }
        _ => return Err(AppErrorWrapper::from(AppError::Validation(
            format!("Unknown task type: {}", request.task_type)
        ))),
    };

    Ok(Json(ApiResponse {
        code: 202,
        data: AsyncMaskingSubmitResponse {
            task_id: task_id.clone(),
            status: "submitted".to_string(),
        },
    }))
}

async fn masking_async_get_task(
    State(state): State<AppState>,
    Path(task_id): Path<String>,
) -> Result<Json<ApiResponse<crate::masking::MaskingTask>>, AppErrorWrapper> {
    let engine = state.async_masking_engine.read().await;
    let task = engine.get_task(&task_id)
        .ok_or_else(|| AppError::NotFound(format!("Task not found: {}", task_id)))?;
    Ok(Json(ApiResponse {
        code: 200,
        data: task,
    }))
}

async fn masking_async_cancel_task(
    State(state): State<AppState>,
    Path(task_id): Path<String>,
) -> Result<Json<ApiResponse<serde_json::Value>>, AppErrorWrapper> {
    let engine = state.async_masking_engine.read().await;
    engine.cancel_task(&task_id)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: serde_json::json!({"message": "Task cancelled"}),
    }))
}

async fn masking_async_start(
    State(state): State<AppState>,
) -> Json<ApiResponse<serde_json::Value>> {
    state.start_async_masking().await;
    Json(ApiResponse {
        code: 200,
        data: serde_json::json!({"message": "Async masking engine started"}),
    })
}

async fn masking_async_stop(
    State(state): State<AppState>,
) -> Json<ApiResponse<serde_json::Value>> {
    state.stop_async_masking().await;
    Json(ApiResponse {
        code: 200,
        data: serde_json::json!({"message": "Async masking engine stopped"}),
    })
}

async fn masking_async_get_config(
    State(state): State<AppState>,
) -> Json<ApiResponse<MaskingAsyncConfig>> {
    let engine = state.async_masking_engine.read().await;
    Json(ApiResponse {
        code: 200,
        data: engine.get_async_config(),
    })
}

async fn masking_async_update_config(
    State(state): State<AppState>,
    Json(request): Json<MaskingAsyncConfig>,
) -> Json<ApiResponse<serde_json::Value>> {
    let engine = state.async_masking_engine.read().await;
    engine.update_async_config(request.clone());
    state.config_manager.update_masking_async_config(request);
    Json(ApiResponse {
        code: 200,
        data: serde_json::json!({"message": "Async masking configuration updated"}),
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClassificationRequest {
    pub document: serde_json::Value,
}

async fn classification_scan(
    State(state): State<AppState>,
    Json(request): Json<ClassificationRequest>,
) -> Json<ApiResponse<crate::classification::ScanResult>> {
    let result = state.classification_engine.scan_document(&request.document);
    Json(ApiResponse {
        code: 200,
        data: result,
    })
}

async fn classification_list_patterns(
    State(state): State<AppState>,
) -> Json<ApiResponse<Vec<crate::classification::ClassificationPattern>>> {
    let patterns = state.classification_engine.list_patterns();
    Json(ApiResponse {
        code: 200,
        data: patterns,
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DpRequest {
    pub value: f64,
    pub epsilon: f64,
    pub sensitivity: f64,
    pub mechanism: String,
}

async fn dp_add_noise(
    State(state): State<AppState>,
    Json(request): Json<DpRequest>,
) -> Result<Json<ApiResponse<crate::dp::DpResult>>, AppErrorWrapper> {
    let mechanism = match request.mechanism.as_str() {
        "gaussian" => crate::dp::NoiseMechanism::Gaussian,
        "exponential" => crate::dp::NoiseMechanism::Exponential,
        _ => crate::dp::NoiseMechanism::Laplace,
    };

    let context = crate::dp::QueryContext {
        query_id: crate::utils::generate_id("qry"),
        epsilon: request.epsilon,
        delta: state.config.dp.default_delta,
        sensitivity: request.sensitivity,
        mechanism,
        timestamp: crate::utils::current_datetime(),
        user_id: None,
        dataset_id: None,
    };

    let result = state.dp_engine.add_noise(request.value, &context, None)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: result,
    }))
}

async fn dp_check_budget(
    State(state): State<AppState>,
) -> Json<ApiResponse<crate::dp::PrivacyBudget>> {
    let budget = state.dp_engine.check_budget();
    Json(ApiResponse {
        code: 200,
        data: budget,
    })
}

use crate::auditlog::AuditLogRequest;

async fn audit_log_entry(
    State(state): State<AppState>,
    Json(request): Json<AuditLogRequest>,
) -> Result<Json<ApiResponse<crate::auditlog::AuditLogEntry>>, AppErrorWrapper> {
    let entry = state.audit_log.log(request)?;
    Ok(Json(ApiResponse {
        code: 201,
        data: entry,
    }))
}

async fn audit_list_logs(
    State(state): State<AppState>,
) -> Json<ApiResponse<Vec<crate::auditlog::AuditLogEntry>>> {
    let logs = state.audit_log.get_all_logs();
    Json(ApiResponse {
        code: 200,
        data: logs,
    })
}

async fn audit_verify(
    State(state): State<AppState>,
) -> Json<ApiResponse<crate::auditlog::IntegrityReport>> {
    let report = state.audit_log.generate_integrity_report();
    Json(ApiResponse {
        code: 200,
        data: report,
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShamirSplitRequest {
    pub secret: String,
    pub threshold: usize,
    pub total_shares: usize,
    pub description: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ShamirRecoverRequest {
    pub secret_id: String,
    pub shares: Vec<crate::shamir::SecretShare>,
}

async fn shamir_split(
    State(state): State<AppState>,
    Json(request): Json<ShamirSplitRequest>,
) -> Result<Json<ApiResponse<crate::shamir::ShareDistribution>>, AppErrorWrapper> {
    let secret_bytes = hex::decode(&request.secret)
        .map_err(|_| AppError::Validation("Invalid secret hex format".to_string()))?;
    
    let distribution = state.shamir.split_secret(
        &secret_bytes,
        request.threshold,
        request.total_shares,
        &request.description,
    )?;
    
    Ok(Json(ApiResponse {
        code: 201,
        data: distribution,
    }))
}

async fn shamir_recover(
    State(state): State<AppState>,
    Json(request): Json<ShamirRecoverRequest>,
) -> Result<Json<ApiResponse<crate::shamir::SecretRecovery>>, AppErrorWrapper> {
    let recovery = state.shamir.recover_secret(&request.secret_id, &request.shares)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: recovery,
    }))
}

use crate::federated::{TaskCreateRequest, ClientRegisterRequest, GradientSubmission};

async fn federated_create_task(
    State(state): State<AppState>,
    Json(request): Json<TaskCreateRequest>,
) -> Result<Json<ApiResponse<crate::federated::TrainingTask>>, AppErrorWrapper> {
    let task = state.federated.create_task(request)?;
    Ok(Json(ApiResponse {
        code: 201,
        data: task,
    }))
}

async fn federated_list_tasks(
    State(state): State<AppState>,
) -> Json<ApiResponse<Vec<crate::federated::TrainingTask>>> {
    let tasks = state.federated.list_tasks();
    Json(ApiResponse {
        code: 200,
        data: tasks,
    })
}

async fn federated_get_task(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Json<ApiResponse<crate::federated::TrainingTask>>, AppErrorWrapper> {
    let task = state
        .federated
        .get_task(&id)
        .ok_or_else(|| AppError::NotFound(format!("Task not found: {}", id)))?;
    Ok(Json(ApiResponse {
        code: 200,
        data: task,
    }))
}

async fn federated_register_client(
    State(state): State<AppState>,
    Json(request): Json<ClientRegisterRequest>,
) -> Result<Json<ApiResponse<crate::federated::FederatedClient>>, AppErrorWrapper> {
    let client = state.federated.register_client(request)?;
    Ok(Json(ApiResponse {
        code: 201,
        data: client,
    }))
}

async fn federated_list_clients(
    State(state): State<AppState>,
) -> Json<ApiResponse<Vec<crate::federated::FederatedClient>>> {
    let clients = state.federated.list_clients();
    Json(ApiResponse {
        code: 200,
        data: clients,
    })
}

async fn federated_submit_gradient(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(mut request): Json<GradientSubmission>,
) -> Result<Json<ApiResponse<crate::federated::ModelUpdate>>, AppErrorWrapper> {
    request.task_id = id;
    let update = state.federated.submit_gradient(request)?;
    Ok(Json(ApiResponse {
        code: 200,
        data: update,
    }))
}
