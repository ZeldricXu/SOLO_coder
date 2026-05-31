use actix_web::{web, HttpResponse, Responder};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use crate::api::AppState;
use crate::utils::error::AppError;
use crate::models::{Entity, Config, RunInstance, Snapshot};

#[derive(Debug, Serialize, Deserialize)]
pub struct ApiResponse<T> {
    pub code: u32,
    pub message: String,
    pub data: Option<T>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CreateResourceRequest {
    pub resource_type: String,
    pub config: serde_json::Value,
    pub labels: HashMap<String, String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct BatchOperation {
    pub action: String,
    pub id: String,
    pub params: Option<serde_json::Value>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct BatchOperationRequest {
    pub operations: Vec<BatchOperation>,
}

fn success_response<T: Serialize>(data: T) -> HttpResponse {
    HttpResponse::Ok().json(ApiResponse {
        code: 200,
        message: "Success".to_string(),
        data: Some(data),
    })
}

fn created_response<T: Serialize>(data: T) -> HttpResponse {
    HttpResponse::Created().json(ApiResponse {
        code: 201,
        message: "Created".to_string(),
        data: Some(data),
    })
}

fn error_response(code: u32, message: String) -> HttpResponse {
    HttpResponse::BadRequest().json(ApiResponse::<()> {
        code,
        message,
        data: None,
    })
}

pub async fn health_check() -> impl Responder {
    HttpResponse::Ok().json(serde_json::json!({
        "status": "healthy",
        "timestamp": chrono::Utc::now().to_rfc3339()
    }))
}

pub async fn create_resource(
    state: web::Data<AppState>,
    request: web::Json<CreateResourceRequest>,
) -> impl Responder {
    state.metrics.increment_counter("api_create_resource");
    
    match request.resource_type.as_str() {
        "gpu_task" => {
            let spec: Result<crate::gpu_scheduler::GpuTaskSpec, _> = 
                serde_json::from_value(request.config.clone());
            
            match spec {
                Ok(task_spec) => {
                    match state.gpu_scheduler.submit_task(task_spec, 3) {
                        Ok(task) => created_response(task),
                        Err(e) => error_response(400, e.to_string()),
                    }
                }
                Err(e) => error_response(400, format!("Invalid task config: {}", e)),
            }
        }
        "document_pipeline" => {
            HttpResponse::NotImplemented().json(ApiResponse::<()> {
                code: 501,
                message: "Document pipeline creation not implemented yet".to_string(),
                data: None,
            })
        }
        _ => error_response(400, format!("Unknown resource type: {}", request.resource_type)),
    }
}

pub async fn get_resource_status(
    state: web::Data<AppState>,
    path: web::Path<String>,
) -> impl Responder {
    state.metrics.increment_counter("api_get_resource_status");
    
    let id = path.into_inner();
    
    if let Ok(task) = state.gpu_scheduler.get_task(&id) {
        return success_response(serde_json::json!({
            "id": task.task_id,
            "status": format!("{:?}", task.status),
            "progress": task.progress,
            "created_at": task.created_at.to_rfc3339(),
            "started_at": task.start_time.map(|t| t.to_rfc3339()),
            "allocated_device": task.allocated_device_id,
        }));
    }
    
    error_response(404, format!("Resource {} not found", id))
}

pub async fn batch_operations(
    state: web::Data<AppState>,
    request: web::Json<BatchOperationRequest>,
) -> impl Responder {
    state.metrics.increment_counter("api_batch_operations");
    
    let batch_id = format!("batch_{}", crate::utils::id::generate_id(""));
    let mut results = Vec::new();
    
    for op in &request.operations {
        let result = match op.action.as_str() {
            "cancel_task" => {
                match state.gpu_scheduler.cancel_task(&op.id) {
                    Ok(task) => serde_json::json!({
                        "id": op.id,
                        "success": true,
                        "status": format!("{:?}", task.status),
                    }),
                    Err(e) => serde_json::json!({
                        "id": op.id,
                        "success": false,
                        "error": e.to_string(),
                    }),
                }
            }
            "pause_task" => {
                serde_json::json!({
                    "id": op.id,
                    "success": false,
                    "error": "Pause not implemented yet",
                })
            }
            "stop_task" => {
                match state.gpu_scheduler.cancel_task(&op.id) {
                    Ok(task) => serde_json::json!({
                        "id": op.id,
                        "success": true,
                        "status": format!("{:?}", task.status),
                    }),
                    Err(e) => serde_json::json!({
                        "id": op.id,
                        "success": false,
                        "error": e.to_string(),
                    }),
                }
            }
            _ => serde_json::json!({
                "id": op.id,
                "success": false,
                "error": format!("Unknown action: {}", op.action),
            }),
        };
        results.push(result);
    }
    
    success_response(serde_json::json!({
        "batch_id": batch_id,
        "results": results,
    }))
}

pub async fn get_metrics(state: web::Data<AppState>) -> impl Responder {
    let snapshot = state.metrics.snapshot();
    success_response(snapshot)
}

pub async fn get_gpu_scheduler_stats(state: web::Data<AppState>) -> impl Responder {
    let stats = state.gpu_scheduler.get_stats();
    success_response(stats)
}

pub async fn list_gpu_tasks(
    state: web::Data<AppState>,
    query: web::Query<HashMap<String, String>>,
) -> impl Responder {
    let status_filter = query.get("status").and_then(|s| {
        match s.as_str() {
            "pending" => Some(crate::gpu_scheduler::TaskStatus::Pending),
            "queued" => Some(crate::gpu_scheduler::TaskStatus::Queued),
            "running" => Some(crate::gpu_scheduler::TaskStatus::Running),
            "completed" => Some(crate::gpu_scheduler::TaskStatus::Completed),
            "failed" => Some(crate::gpu_scheduler::TaskStatus::Failed),
            _ => None,
        }
    });
    
    let tasks = state.gpu_scheduler.list_tasks(status_filter);
    success_response(tasks)
}

pub async fn register_gpu_device(
    state: web::Data<AppState>,
    device: web::Json<crate::gpu_scheduler::GpuDevice>,
) -> impl Responder {
    match state.gpu_scheduler.register_device(device.into_inner()) {
        Ok(d) => created_response(d),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn list_gpu_devices(state: web::Data<AppState>) -> impl Responder {
    let devices = state.gpu_scheduler.list_devices();
    success_response(devices)
}

pub async fn get_prompt_manager_stats(state: web::Data<AppState>) -> impl Responder {
    let stats = state.prompt_manager.get_stats();
    success_response(stats)
}

pub async fn create_prompt(
    state: web::Data<AppState>,
    request: web::Json<crate::prompt_experiments::PromptRegistrationRequest>,
) -> impl Responder {
    match state.prompt_manager.register_prompt(request.into_inner()) {
        Ok(p) => created_response(p),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn list_prompts(state: web::Data<AppState>) -> impl Responder {
    let prompts = state.prompt_manager.list_prompts(None);
    success_response(prompts)
}

pub async fn create_ab_test(
    state: web::Data<AppState>,
    request: web::Json<crate::prompt_experiments::ABTestCreationRequest>,
) -> impl Responder {
    match state.prompt_manager.create_ab_test(request.into_inner()) {
        Ok(t) => created_response(t),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn start_ab_test(
    state: web::Data<AppState>,
    path: web::Path<String>,
) -> impl Responder {
    let test_id = path.into_inner();
    match state.prompt_manager.start_test(&test_id) {
        Ok(t) => success_response(t),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn generate_ab_test_report(
    state: web::Data<AppState>,
    path: web::Path<String>,
) -> impl Responder {
    let test_id = path.into_inner();
    match state.prompt_manager.generate_report(&test_id) {
        Ok(r) => success_response(r),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn get_model_registry_stats(state: web::Data<AppState>) -> impl Responder {
    let stats = state.model_registry.get_stats();
    success_response(stats)
}

pub async fn register_model(
    state: web::Data<AppState>,
    request: web::Json<crate::model_registry::ModelRegistrationRequest>,
) -> impl Responder {
    match state.model_registry.register_model(request.into_inner()) {
        Ok(m) => created_response(m),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn list_models(state: web::Data<AppState>) -> impl Responder {
    let models = state.model_registry.list_models(None);
    success_response(models)
}

pub async fn get_model_dashboard(
    state: web::Data<AppState>,
    path: web::Path<String>,
) -> impl Responder {
    let model_id = path.into_inner();
    match state.model_dashboard.get_model_dashboard(&model_id) {
        Ok(d) => success_response(d),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn generate_adversarial_samples(
    state: web::Data<AppState>,
    request: web::Json<crate::adversarial::AdversarialGenerationRequest>,
) -> impl Responder {
    match state.adversarial_generator.generate_batch(&request.into_inner()) {
        Ok(samples) => success_response(samples),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn evaluate_adversarial(
    state: web::Data<AppState>,
    request: web::Json<crate::adversarial::EvaluationRequest>,
) -> impl Responder {
    match state.adversarial_generator.evaluate(&request.into_inner()) {
        Ok(result) => success_response(result),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn process_document(
    state: web::Data<AppState>,
    request: web::Json<crate::document_pipeline::PipelineRequest>,
) -> impl Responder {
    match state.document_pipeline.process(request.into_inner()) {
        Ok(result) => success_response(result),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn inference_request(
    state: web::Data<AppState>,
    request: web::Json<crate::inference_gateway::InferenceRequest>,
) -> impl Responder {
    match state.inference_gateway.infer(&request.into_inner()).await {
        Ok(result) => success_response(result),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn get_feature_store_stats(state: web::Data<AppState>) -> impl Responder {
    let stats = state.feature_store.stats();
    success_response(stats)
}

pub async fn register_feature(
    state: web::Data<AppState>,
    request: web::Json<crate::feature_store::FeatureRegistrationRequest>,
) -> impl Responder {
    match state.feature_store.register_feature(request.into_inner()) {
        Ok(f) => created_response(f),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn list_features(
    state: web::Data<AppState>,
    query: web::Query<HashMap<String, String>>,
) -> impl Responder {
    let entity_type = query.get("entity_type").map(|s| s.as_str());
    let features = state.feature_store.list_features(entity_type, None);
    success_response(features)
}

pub async fn insert_feature(
    state: web::Data<AppState>,
    request: web::Json<crate::feature_store::FeatureStoreRequest>,
) -> impl Responder {
    match state.feature_store.insert_feature(request.into_inner()) {
        Ok(f) => created_response(f),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn lookup_features(
    state: web::Data<AppState>,
    request: web::Json<crate::feature_store::FeatureLookupRequest>,
) -> impl Responder {
    match state.feature_store.lookup_features(request.into_inner()) {
        Ok(features) => success_response(features),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn point_in_time_lookup(
    state: web::Data<AppState>,
    request: web::Json<crate::feature_store::PointInTimeLookupRequest>,
) -> impl Responder {
    match state.feature_store.point_in_time_lookup(request.into_inner()) {
        Ok(features) => success_response(features),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn create_feature_version(
    state: web::Data<AppState>,
    request: web::Json<crate::feature_store::CreateVersionRequest>,
) -> impl Responder {
    match state.feature_store.create_feature_version(request.into_inner()) {
        Ok(v) => created_response(v),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn get_feature_version(
    state: web::Data<AppState>,
    path: web::Path<(String, u32)>,
) -> impl Responder {
    let (feature_id, version_number) = path.into_inner();
    match state.feature_store.get_feature_version(&feature_id, version_number) {
        Some(v) => success_response(v),
        None => error_response(404, format!("Version {} not found for feature {}", version_number, feature_id)),
    }
}

pub async fn list_feature_versions(
    state: web::Data<AppState>,
    path: web::Path<String>,
) -> impl Responder {
    let feature_id = path.into_inner();
    let versions = state.feature_store.list_feature_versions(&feature_id);
    success_response(versions)
}

pub async fn compare_feature_versions(
    state: web::Data<AppState>,
    path: web::Path<(String, u32, u32)>,
) -> impl Responder {
    let (feature_id, from_version, to_version) = path.into_inner();
    match state.feature_store.compare_versions(&feature_id, from_version, to_version) {
        Ok(diff) => success_response(diff),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn rollback_feature_version(
    state: web::Data<AppState>,
    path: web::Path<(String, u32)>,
    request: web::Json<HashMap<String, String>>,
) -> impl Responder {
    let (feature_id, target_version) = path.into_inner();
    let created_by = request.get("created_by").cloned().unwrap_or_else(|| "api".to_string());
    match state.feature_store.rollback_feature_version(&feature_id, target_version, &created_by) {
        Ok(result) => success_response(result),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn get_value_versions(
    state: web::Data<AppState>,
    path: web::Path<(String, String)>,
) -> impl Responder {
    let (feature_id, entity_id) = path.into_inner();
    let versions = state.feature_store.get_value_versions(&feature_id, &entity_id);
    success_response(versions)
}

pub async fn create_snapshot(
    state: web::Data<AppState>,
    request: web::Json<HashMap<String, serde_json::Value>>,
) -> impl Responder {
    let name = request.get("name").and_then(|v| v.as_str()).unwrap_or("snapshot").to_string();
    let description = request.get("description").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let feature_ids = request.get("feature_ids")
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect())
        .unwrap_or_default();
    let created_by = request.get("created_by").and_then(|v| v.as_str()).unwrap_or("api").to_string();
    
    let snapshot = state.feature_store.create_snapshot(name, description, feature_ids, created_by);
    created_response(snapshot)
}

pub async fn list_snapshots(state: web::Data<AppState>) -> impl Responder {
    let snapshots = state.feature_store.list_snapshots();
    success_response(snapshots)
}

pub async fn get_snapshot(
    state: web::Data<AppState>,
    path: web::Path<String>,
) -> impl Responder {
    let snapshot_id = path.into_inner();
    match state.feature_store.get_snapshot(&snapshot_id) {
        Some(s) => success_response(s),
        None => error_response(404, format!("Snapshot {} not found", snapshot_id)),
    }
}

pub async fn delete_snapshot(
    state: web::Data<AppState>,
    path: web::Path<String>,
) -> impl Responder {
    let snapshot_id = path.into_inner();
    match state.feature_store.delete_snapshot(&snapshot_id) {
        Ok(_) => success_response(serde_json::json!({"message": "Snapshot deleted"})),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn protect_snapshot(
    state: web::Data<AppState>,
    path: web::Path<String>,
) -> impl Responder {
    let snapshot_id = path.into_inner();
    match state.feature_store.protect_snapshot(&snapshot_id) {
        Ok(s) => success_response(s),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn get_document_cache_stats(
    state: web::Data<AppState>,
) -> impl Responder {
    let stats = state.document_pipeline.cache_stats();
    success_response(stats)
}

pub async fn invalidate_document_cache(
    state: web::Data<AppState>,
    request: web::Json<serde_json::Value>,
) -> impl Responder {
    use crate::document_pipeline::CacheInvalidationRequest;
    
    let keys = request.get("keys")
        .and_then(|v| v.as_array())
        .map(|arr| arr.iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect())
        .unwrap_or_default();
    
    let invalidate_pattern = request.get("invalidate_pattern")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    
    let tier = request.get("tier")
        .and_then(|v| v.as_str())
        .map(|s| match s {
            "l1" => crate::document_pipeline::CacheTier::L1,
            "l2" => crate::document_pipeline::CacheTier::L2,
            _ => crate::document_pipeline::CacheTier::Both,
        })
        .unwrap_or(crate::document_pipeline::CacheTier::Both);
    
    let invalidation_request = CacheInvalidationRequest {
        keys,
        invalidate_pattern,
        tier,
    };
    
    let invalidated = state.document_pipeline.invalidate_cache(invalidation_request);
    success_response(serde_json::json!({
        "invalidated_entries": invalidated,
        "message": "Cache invalidation completed"
    }))
}

pub async fn clear_document_cache(
    state: web::Data<AppState>,
    query: web::Query<HashMap<String, String>>,
) -> impl Responder {
    let tier = query.get("tier")
        .map(|s| match s.as_str() {
            "l1" => crate::document_pipeline::CacheTier::L1,
            "l2" => crate::document_pipeline::CacheTier::L2,
            _ => crate::document_pipeline::CacheTier::Both,
        })
        .unwrap_or(crate::document_pipeline::CacheTier::Both);
    
    state.document_pipeline.clear_cache(tier);
    success_response(serde_json::json!({
        "message": format!("Cache cleared for tier: {:?}", tier)
    }))
}

pub async fn warmup_document_cache(
    state: web::Data<AppState>,
) -> impl Responder {
    state.document_pipeline.warmup_cache();
    success_response(serde_json::json!({
        "message": "Cache warmup initiated"
    }))
}

pub async fn batch_inference(
    state: web::Data<AppState>,
    request: web::Json<crate::inference_gateway::BatchInferenceRequest>,
) -> impl Responder {
    match state.inference_gateway.batch_chat(request.into_inner()).await {
        Ok(response) => success_response(response),
        Err(e) => error_response(400, e.to_string()),
    }
}

pub async fn get_batch_stats(
    state: web::Data<AppState>,
) -> impl Responder {
    let stats = state.inference_gateway.batch_stats().await;
    success_response(stats)
}

pub async fn start_batch_processing(
    state: web::Data<AppState>,
) -> impl Responder {
    state.inference_gateway.start_batch_processing().await;
    success_response(serde_json::json!({
        "message": "Batch processing started"
    }))
}

pub async fn stop_batch_processing(
    state: web::Data<AppState>,
) -> impl Responder {
    state.inference_gateway.stop_batch_processing();
    success_response(serde_json::json!({
        "message": "Batch processing stopped"
    }))
}

pub async fn get_feature_store_monitor(
    state: web::Data<AppState>,
) -> impl Responder {
    let snapshot = state.feature_store.monitor_snapshot();
    success_response(snapshot)
}

pub async fn get_feature_store_prometheus(
    state: web::Data<AppState>,
) -> impl Responder {
    let metrics = state.feature_store.export_prometheus_metrics();
    HttpResponse::Ok()
        .content_type("text/plain; version=0.0.4")
        .body(metrics)
}

pub async fn reset_feature_store_monitor(
    state: web::Data<AppState>,
) -> impl Responder {
    state.feature_store.reset_monitor_stats();
    success_response(serde_json::json!({
        "message": "Feature store monitoring stats reset"
    }))
}
