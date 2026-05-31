use axum::{
    Json,
    extract::{State, Path, Query},
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Deserialize;
use std::collections::HashMap;
use crate::utils::error::PlatformError;
use crate::models::common::{ApiResponse, BatchRequest, BatchResult, BatchResponse};
use crate::models::config::{
    CreateConfigRequest, UpdateConfigRequest, RollbackRequest, ConfigHistoryEntry, ConfigVersion,
};
use crate::scaffold::models::{ScaffoldConfig, ScaffoldResult, CreateScaffoldRequest, TemplateInfo};
use crate::monitoring::aggregator::MetricStatistics;
use crate::feature_flags::models::{
    CreateFlagRequest, UpdateFlagRequest, EvaluateRequest, EvaluateResponse,
    FeatureFlag, UserSegment, UserContext,
};
use crate::vulnerability::manager::{
    SBOMDocument, VulnerabilityReport, CVEEntry, Severity,
};
use crate::scheduler::models::{
    ScheduleTaskRequest, ScheduleResponse, WorkflowExecution, WorkflowDefinition,
};
use crate::data_access::schema::{MigrationDefinition, MigrationResult, SchemaVersion};
use crate::storage::models::{
    BackupRecord, RestoreRequest, RestoreResult, FrequencyCheckResult, DataRecord,
};
use crate::quality_gate::models::{
    AnalyzeRequest, AnalysisReport, QualityGateResult, StaticAnalysisRule,
    QualityGateThreshold, Language,
};
use crate::AppState;

#[derive(Debug, Deserialize)]
pub struct NamespaceQuery {
    pub namespace: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ListParams {
    pub limit: Option<usize>,
    pub offset: Option<usize>,
}

impl IntoResponse for PlatformError {
    fn into_response(self) -> Response {
        let status = match self.code() {
            400 => StatusCode::BAD_REQUEST,
            404 => StatusCode::NOT_FOUND,
            409 => StatusCode::CONFLICT,
            502 => StatusCode::BAD_GATEWAY,
            504 => StatusCode::GATEWAY_TIMEOUT,
            _ => StatusCode::INTERNAL_SERVER_ERROR,
        };
        
        let response = ApiResponse::error(self.code(), self.to_string());
        (status, Json(response)).into_response()
    }
}

pub async fn create_config(
    State(state): State<AppState>,
    Json(req): Json<CreateConfigRequest>,
) -> Result<Json<ApiResponse<ConfigVersion>>, PlatformError> {
    let version = state.config_manager.create(req).await?;
    Ok(Json(ApiResponse::created(version)))
}

pub async fn get_config(
    State(state): State<AppState>,
    Path(config_id): Path<String>,
) -> Result<Json<ApiResponse<ConfigVersion>>, PlatformError> {
    let config = state.config_manager.get(&config_id).await?;
    Ok(Json(ApiResponse::success(config)))
}

pub async fn list_configs(
    State(state): State<AppState>,
    Query(query): Query<NamespaceQuery>,
) -> Result<Json<ApiResponse<Vec<ConfigVersion>>>, PlatformError> {
    let configs = state.config_manager.list(query.namespace.as_deref()).await?;
    Ok(Json(ApiResponse::success(configs)))
}

pub async fn update_config(
    State(state): State<AppState>,
    Path(config_id): Path<String>,
    Json(req): Json<UpdateConfigRequest>,
) -> Result<Json<ApiResponse<ConfigVersion>>, PlatformError> {
    let version = state.config_manager.update(&config_id, req).await?;
    Ok(Json(ApiResponse::success(version)))
}

pub async fn rollback_config(
    State(state): State<AppState>,
    Path(config_id): Path<String>,
    Json(req): Json<RollbackRequest>,
) -> Result<Json<ApiResponse<ConfigVersion>>, PlatformError> {
    let version = state.config_manager.rollback(&config_id, req).await?;
    Ok(Json(ApiResponse::success(version)))
}

pub async fn get_config_history(
    State(state): State<AppState>,
    Path(config_id): Path<String>,
) -> Result<Json<ApiResponse<Vec<ConfigHistoryEntry>>>, PlatformError> {
    let history = state.config_manager.history(&config_id).await?;
    Ok(Json(ApiResponse::success(history)))
}

pub async fn delete_config(
    State(state): State<AppState>,
    Path(config_id): Path<String>,
) -> Result<Json<ApiResponse<()>>, PlatformError> {
    state.config_manager.delete(&config_id).await?;
    Ok(Json(ApiResponse::success(())))
}

pub async fn list_scaffold_templates(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<Vec<TemplateInfo>>>, PlatformError> {
    let templates = state.scaffold_generator.list_templates();
    Ok(Json(ApiResponse::success(templates)))
}

pub async fn generate_scaffold(
    State(state): State<AppState>,
    Json(req): Json<CreateScaffoldRequest>,
) -> Result<Json<ApiResponse<ScaffoldResult>>, PlatformError> {
    let result = state.scaffold_generator.generate(&req.config, None).await?;
    Ok(Json(ApiResponse::success(result)))
}

pub async fn list_metrics(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<Vec<String>>>, PlatformError> {
    let names = state.metrics_aggregator.get_all_metric_names().await;
    Ok(Json(ApiResponse::success(names)))
}

pub async fn get_metric_statistics(
    State(state): State<AppState>,
    Path(metric_name): Path<String>,
) -> Result<Json<ApiResponse<MetricStatistics>>, PlatformError> {
    let stats = state.metrics_aggregator.get_statistics(&metric_name).await
        .ok_or_else(|| PlatformError::NotFound(format!("metric {} not found", metric_name)))?;
    Ok(Json(ApiResponse::success(stats)))
}

pub async fn create_feature_flag(
    State(state): State<AppState>,
    Json(req): Json<CreateFlagRequest>,
) -> Result<Json<ApiResponse<FeatureFlag>>, PlatformError> {
    let flag = state.feature_flag_manager.create_flag(req).await?;
    Ok(Json(ApiResponse::created(flag)))
}

pub async fn get_feature_flag(
    State(state): State<AppState>,
    Path(flag_id): Path<String>,
) -> Result<Json<ApiResponse<FeatureFlag>>, PlatformError> {
    let flag = state.feature_flag_manager.get_flag(&flag_id).await?;
    Ok(Json(ApiResponse::success(flag)))
}

pub async fn list_feature_flags(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<Vec<FeatureFlag>>>, PlatformError> {
    let flags = state.feature_flag_manager.list_flags().await?;
    Ok(Json(ApiResponse::success(flags)))
}

pub async fn update_feature_flag(
    State(state): State<AppState>,
    Path(flag_id): Path<String>,
    Json(req): Json<UpdateFlagRequest>,
) -> Result<Json<ApiResponse<FeatureFlag>>, PlatformError> {
    let flag = state.feature_flag_manager.update_flag(&flag_id, req).await?;
    Ok(Json(ApiResponse::success(flag)))
}

pub async fn evaluate_feature_flag(
    State(state): State<AppState>,
    Json(req): Json<EvaluateRequest>,
) -> Result<Json<ApiResponse<EvaluateResponse>>, PlatformError> {
    let result = state.feature_flag_manager.evaluate(&req.flag_id, &req.user).await?;
    Ok(Json(ApiResponse::success(result)))
}

pub async fn evaluate_all_feature_flags(
    State(state): State<AppState>,
    Json(user): Json<UserContext>,
) -> Result<Json<ApiResponse<HashMap<String, bool>>>, PlatformError> {
    let results = state.feature_flag_manager.evaluate_all(&user).await?;
    Ok(Json(ApiResponse::success(results)))
}

pub async fn create_user_segment(
    State(state): State<AppState>,
    Path((segment_id, name)): Path<(String, String)>,
) -> Result<Json<ApiResponse<UserSegment>>, PlatformError> {
    let segment = state.feature_flag_manager.create_segment(segment_id, name).await?;
    Ok(Json(ApiResponse::created(segment)))
}

pub async fn list_user_segments(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<Vec<UserSegment>>>, PlatformError> {
    let segments = state.feature_flag_manager.list_segments().await?;
    Ok(Json(ApiResponse::success(segments)))
}

pub async fn scan_sbom(
    State(state): State<AppState>,
    Json(sbom): Json<SBOMDocument>,
) -> Result<Json<ApiResponse<VulnerabilityReport>>, PlatformError> {
    let report = state.vulnerability_manager.scan_sbom(&sbom).await?;
    Ok(Json(ApiResponse::success(report)))
}

pub async fn list_cves(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<Vec<CVEEntry>>>, PlatformError> {
    let cves = state.vulnerability_manager.list_cves(None).await?;
    Ok(Json(ApiResponse::success(cves)))
}

pub async fn schedule_workflow(
    State(state): State<AppState>,
    Json(req): Json<ScheduleTaskRequest>,
) -> Result<Json<ApiResponse<ScheduleResponse>>, PlatformError> {
    let response = state.task_scheduler.schedule_workflow(req).await?;
    Ok(Json(ApiResponse::success(response)))
}

pub async fn execute_workflow(
    State(state): State<AppState>,
    Path(execution_id): Path<String>,
) -> Result<Json<ApiResponse<WorkflowExecution>>, PlatformError> {
    let execution = state.task_scheduler.execute_workflow(&execution_id).await?;
    Ok(Json(ApiResponse::success(execution)))
}

pub async fn get_workflow_status(
    State(state): State<AppState>,
    Path(execution_id): Path<String>,
) -> Result<Json<ApiResponse<WorkflowExecution>>, PlatformError> {
    let execution = state.task_scheduler.get_execution(&execution_id).await?;
    Ok(Json(ApiResponse::success(execution)))
}

pub async fn list_workflows(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<Vec<WorkflowExecution>>>, PlatformError> {
    let executions = state.task_scheduler.list_executions().await?;
    Ok(Json(ApiResponse::success(executions)))
}

pub async fn register_migration(
    State(state): State<AppState>,
    Json(migration): Json<MigrationDefinition>,
) -> Result<Json<ApiResponse<()>>, PlatformError> {
    state.migration_manager.register_migration(migration).await?;
    Ok(Json(ApiResponse::success(())))
}

pub async fn run_migrations(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<Vec<MigrationResult>>>, PlatformError> {
    let results = state.migration_manager.migrate().await?;
    Ok(Json(ApiResponse::success(results)))
}

pub async fn get_migration_status(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<Vec<SchemaVersion>>>, PlatformError> {
    let applied = state.migration_manager.get_applied_migrations().await;
    Ok(Json(ApiResponse::success(applied)))
}

pub async fn check_frequency(
    State(state): State<AppState>,
    Path(key): Path<String>,
) -> Result<Json<ApiResponse<FrequencyCheckResult>>, PlatformError> {
    let result = state.storage_manager.check_frequency(&key).await;
    Ok(Json(ApiResponse::success(result)))
}

pub async fn get_data(
    State(state): State<AppState>,
    Path(key): Path<String>,
) -> Result<Json<ApiResponse<DataRecord>>, PlatformError> {
    let record = state.storage_manager.get(&key).await?;
    Ok(Json(ApiResponse::success(record)))
}

pub async fn put_data(
    State(state): State<AppState>,
    Path(key): Path<String>,
    Json(value): Json<serde_json::Value>,
) -> Result<Json<ApiResponse<DataRecord>>, PlatformError> {
    let record = state.storage_manager.put(&key, value).await?;
    Ok(Json(ApiResponse::success(record)))
}

pub async fn create_backup(
    State(state): State<AppState>,
    Path((source, dest)): Path<(String, String)>,
) -> Result<Json<ApiResponse<BackupRecord>>, PlatformError> {
    let backup = state.storage_manager.create_backup(&source, &dest).await?;
    Ok(Json(ApiResponse::success(backup)))
}

pub async fn list_backups(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<Vec<BackupRecord>>>, PlatformError> {
    let backups = state.storage_manager.list_backups().await?;
    Ok(Json(ApiResponse::success(backups)))
}

pub async fn restore_backup(
    State(state): State<AppState>,
    Json(req): Json<RestoreRequest>,
) -> Result<Json<ApiResponse<RestoreResult>>, PlatformError> {
    let result = state.storage_manager.restore(req).await?;
    Ok(Json(ApiResponse::success(result)))
}

pub async fn analyze_code(
    State(state): State<AppState>,
    Json(req): Json<AnalyzeRequest>,
) -> Result<Json<ApiResponse<AnalysisReport>>, PlatformError> {
    let report = state.quality_gate_manager.analyze(req).await?;
    Ok(Json(ApiResponse::success(report)))
}

pub async fn check_quality_gate(
    State(state): State<AppState>,
    Path(report_id): Path<String>,
) -> Result<Json<ApiResponse<QualityGateResult>>, PlatformError> {
    let report = state.quality_gate_manager.get_report(&report_id).await?;
    let result = state.quality_gate_manager.check_quality_gate(&report).await?;
    Ok(Json(ApiResponse::success(result)))
}

pub async fn list_analysis_rules(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<Vec<StaticAnalysisRule>>>, PlatformError> {
    let rules = state.quality_gate_manager.list_rules(None).await?;
    Ok(Json(ApiResponse::success(rules)))
}

pub async fn list_quality_thresholds(
    State(state): State<AppState>,
) -> Result<Json<ApiResponse<Vec<QualityGateThreshold>>>, PlatformError> {
    let thresholds = state.quality_gate_manager.list_thresholds().await?;
    Ok(Json(ApiResponse::success(thresholds)))
}

pub async fn health_check() -> Json<ApiResponse<serde_json::Value>> {
    Json(ApiResponse::success(serde_json::json!({
        "status": "healthy",
        "timestamp": chrono::Utc::now().to_rfc3339()
    })))
}
