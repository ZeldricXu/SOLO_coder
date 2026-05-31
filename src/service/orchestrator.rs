use serde::{Deserialize, Serialize};
use std::sync::Arc;
use uuid::Uuid;

use crate::domain::common::{BatchResponse, BatchResult};
use crate::domain::entity::{BinaryResponse, SignedRequest};
use crate::domain::run_instance::RunInstance;
use crate::domain::user::{AuthContext, User};
use crate::infra::app_state::AppState;
use crate::infra::error::{AppError, AppResult};
use crate::modules::audit::{AuditLogService, LogEntryRequest};
use crate::modules::classification::{ClassificationService, ClassifyRequest};
use crate::modules::dp::{DifferentialPrivacyService, DPQueryRequest};
use crate::modules::federated::{FederatedLearningService, GradientSubmission, ParticipantRegistration};
use crate::modules::masking::{MaskingService, MaskingRequest};
use crate::modules::mpc::{InputSubmission, MPCService};
use crate::modules::sharding::{ReconstructRequest, ShardingService};
use crate::modules::tee::{TEEService, CreateEnclaveRequest};

#[derive(Clone)]
pub struct ServiceOrchestrator {
    pub state: AppState,
    pub tee_service: Arc<TEEService>,
    pub masking_service: Arc<MaskingService>,
    pub federated_service: Arc<FederatedLearningService>,
    pub mpc_service: Arc<MPCService>,
    pub classification_service: Arc<ClassificationService>,
    pub dp_service: Arc<DifferentialPrivacyService>,
    pub audit_service: Arc<AuditLogService>,
    pub sharding_service: Arc<ShardingService>,
}

impl ServiceOrchestrator {
    pub fn new(state: AppState) -> Self {
        let config = state.config();
        let cache = state.cache.clone();

        Self {
            tee_service: Arc::new(TEEService::with_cache(config.tee.clone(), cache)),
            masking_service: Arc::new(MaskingService::new(config.masking.clone())),
            federated_service: Arc::new(FederatedLearningService::new(config.federated.clone())),
            mpc_service: Arc::new(MPCService::new(config.mpc.clone())),
            classification_service: Arc::new(ClassificationService::new(config.classification.clone())),
            dp_service: Arc::new(DifferentialPrivacyService::new(config.dp.clone())),
            audit_service: Arc::new(AuditLogService::new(config.audit.clone())),
            sharding_service: Arc::new(ShardingService::new(config.sharding.clone())),
            state,
        }
    }

    pub fn new_without_cache(state: AppState) -> Self {
        let config = state.config();

        Self {
            tee_service: Arc::new(TEEService::new(config.tee.clone())),
            masking_service: Arc::new(MaskingService::new(config.masking.clone())),
            federated_service: Arc::new(FederatedLearningService::new(config.federated.clone())),
            mpc_service: Arc::new(MPCService::new(config.mpc.clone())),
            classification_service: Arc::new(ClassificationService::new(config.classification.clone())),
            dp_service: Arc::new(DifferentialPrivacyService::new(config.dp.clone())),
            audit_service: Arc::new(AuditLogService::new(config.audit.clone())),
            sharding_service: Arc::new(ShardingService::new(config.sharding.clone())),
            state,
        }
    }

    pub async fn execute_secure_operation(
        &self,
        enclave_id: &str,
        request: SignedRequest,
        _auth: &AuthContext,
    ) -> AppResult<BinaryResponse> {
        self.log_operation(
            "system".to_string(),
            "execute_secure_function".to_string(),
            "tee_enclave".to_string(),
            Some(enclave_id.to_string()),
            Some(serde_json::json!({"enclave_id": enclave_id})),
        )
        .await?;

        self.tee_service
            .execute_secure_function(enclave_id, request)
            .await
    }

    pub async fn classify_and_mask_data(
        &self,
        data: serde_json::Value,
        resource_id: String,
        resource_type: String,
        auth: &AuthContext,
    ) -> AppResult<serde_json::Value> {
        let classify_request = ClassifyRequest {
            data: data.clone(),
            resource_id: resource_id.clone(),
            resource_type: resource_type.clone(),
            apply_policy: true,
        };

        let classification = self
            .classification_service
            .classify_data(classify_request)
            .await?;

        self.log_operation(
            auth.user.user_id.clone(),
            "classify_data".to_string(),
            resource_type.clone(),
            Some(resource_id.clone()),
            Some(serde_json::json!({
                "report_id": classification.report_id,
                "sensitive_fields": classification.sensitive_fields,
                "overall_level": classification.overall_level.as_str()
            })),
        )
        .await?;

        let masking_request = MaskingRequest {
            data: classification
                .results
                .keys()
                .fold(data, |acc, field| {
                    let parts: Vec<&str> = field.split('.').collect();
                    acc
                }),
            data_class: Some(classification.overall_level.as_str().to_string()),
        };

        let masking_result = self
            .masking_service
            .mask_data(masking_request, auth)
            .await?;

        Ok(masking_result.masked)
    }

    pub async fn privacy_preserving_query(
        &self,
        data: serde_json::Value,
        epsilon: f64,
        delta: f64,
        auth: &AuthContext,
    ) -> AppResult<serde_json::Value> {
        let budget = self
            .dp_service
            .get_budget(&auth.user.user_id)
            .ok();

        if budget.is_none() {
            self.dp_service
                .create_budget(auth.user.user_id.clone(), None, None, None);
        }

        let dp_request = DPQueryRequest {
            query_id: None,
            data,
            params: crate::modules::dp::DPPrivacyParams {
                epsilon,
                delta,
                distribution: crate::modules::dp::NoiseDistribution::Laplace,
                sensitivity: 1.0,
            },
            user_id: auth.user.user_id.clone(),
        };

        let response = self.dp_service.apply_dp(dp_request).await?;

        self.log_operation(
            auth.user.user_id.clone(),
            "dp_query".to_string(),
            "privacy_query".to_string(),
            Some(response.query_id.clone()),
            Some(serde_json::json!({
                "epsilon_used": response.epsilon_used,
                "remaining_budget": response.remaining_budget
            })),
        )
        .await?;

        Ok(response.noised)
    }

    pub async fn federated_learning_workflow(
        &self,
        task_id: &str,
        submissions: Vec<GradientSubmission>,
        _auth: &AuthContext,
    ) -> AppResult<crate::modules::federated::ModelUpdate> {
        for submission in submissions {
            self.federated_service
                .submit_gradient(submission)
                .await?;
        }

        let update = self.federated_service.aggregate_gradients(task_id).await?;

        self.log_operation(
            "system".to_string(),
            "federated_aggregate".to_string(),
            "federated_task".to_string(),
            Some(task_id.to_string()),
            Some(serde_json::json!({
                "model_version": update.model_version,
                "checksum": update.checksum
            })),
        )
        .await?;

        Ok(update)
    }

    pub async fn secure_mpc_computation(
        &self,
        session_id: &str,
        inputs: Vec<InputSubmission>,
        _auth: &AuthContext,
    ) -> AppResult<crate::modules::mpc::MPCResult> {
        for input in inputs {
            self.mpc_service.submit_input(input).await?;
        }

        let result = self.mpc_service.execute_computation(session_id).await?;

        self.log_operation(
            "system".to_string(),
            "mpc_compute".to_string(),
            "mpc_session".to_string(),
            Some(session_id.to_string()),
            Some(serde_json::json!({
                "checksum": result.checksum
            })),
        )
        .await?;

        Ok(result)
    }

    pub async fn secure_key_reconstruction(
        &self,
        request: ReconstructRequest,
        auth: &AuthContext,
    ) -> AppResult<crate::modules::sharding::ReconstructResponse> {
        let result = self.sharding_service.reconstruct_key(request).await?;

        self.log_operation(
            auth.user.user_id.clone(),
            "reconstruct_key".to_string(),
            "sharded_key".to_string(),
            Some(result.key_id.clone()),
            Some(serde_json::json!({
                "verified": result.verified
            })),
        )
        .await?;

        Ok(result)
    }

    pub async fn register_participant(
        &self,
        registration: ParticipantRegistration,
        auth: &AuthContext,
    ) -> AppResult<crate::modules::federated::TrainingTask> {
        let task = self.federated_service.register_participant(registration).await?;

        self.log_operation(
            auth.user.user_id.clone(),
            "register_participant".to_string(),
            "federated_task".to_string(),
            Some(task.task_id.clone()),
            None,
        )
        .await?;

        Ok(task)
    }

    pub async fn create_secure_enclave(
        &self,
        request: CreateEnclaveRequest,
        auth: &AuthContext,
    ) -> AppResult<crate::modules::tee::Enclave> {
        let enclave = self.tee_service.create_enclave(request).await?;

        self.log_operation(
            auth.user.user_id.clone(),
            "create_enclave".to_string(),
            "tee_enclave".to_string(),
            Some(enclave.enclave_id.clone()),
            None,
        )
        .await?;

        Ok(enclave)
    }

    pub async fn verify_audit_integrity(&self) -> AppResult<crate::modules::audit::IntegrityCheckResult> {
        self.audit_service.verify_integrity().await
    }

    pub async fn log_operation(
        &self,
        actor: String,
        action: String,
        resource_type: String,
        resource_id: Option<String>,
        details: Option<serde_json::Value>,
    ) -> AppResult<crate::modules::audit::AuditLogEntry> {
        let request = LogEntryRequest {
            actor,
            action,
            resource_type,
            resource_id,
            details,
        };

        self.audit_service.log_event(request).await
    }

    pub async fn execute_batch_operation(
        &self,
        operations: Vec<crate::domain::common::BatchOperation>,
        auth: &AuthContext,
    ) -> AppResult<BatchResponse> {
        let mut results = Vec::new();

        for op in operations {
            let success = match op.action.as_str() {
                "start_enclave" => {
                    if let Some(Ok(id)) = op.params.as_ref().and_then(|p| p.get("enclave_id").and_then(|v| v.as_str())) {
                        self.tee_service.start_enclave(id).await.is_ok()
                    } else {
                        false
                    }
                }
                "stop_enclave" => {
                    if let Some(Ok(id)) = op.params.as_ref().and_then(|p| p.get("enclave_id").and_then(|v| v.as_str())) {
                        self.tee_service.stop_enclave(id).await.is_ok()
                    } else {
                        false
                    }
                }
                "terminate_enclave" => {
                    if let Some(Ok(id)) = op.params.as_ref().and_then(|p| p.get("enclave_id").and_then(|v| v.as_str())) {
                        self.tee_service.terminate_enclave(id).await.is_ok()
                    } else {
                        false
                    }
                }
                "seal_audit_block" => self.audit_service.seal_block().await.is_ok(),
                _ => false,
            };

            results.push(BatchResult {
                id: op.id,
                success,
                error: if success { None } else { Some("Operation failed".to_string()) },
            });
        }

        self.log_operation(
            auth.user.user_id.clone(),
            "batch_operation".to_string(),
            "batch".to_string(),
            None,
            Some(serde_json::json!({ "operation_count": operations.len() })),
        )
        .await?;

        Ok(BatchResponse {
            batch_id: format!("batch_{}", Uuid::new_v4().simple()),
            results,
        })
    }

    pub fn create_system_auth_context(&self) -> AuthContext {
        let system_user = User::new("system".to_string(), "system".to_string(), crate::domain::user::PermissionLevel::Restricted);
        AuthContext::new(system_user, 3600 * 24)
    }
}
