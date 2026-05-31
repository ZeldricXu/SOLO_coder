use actix_web::{web, Scope};
use crate::api::handlers::*;

pub fn configure_routes() -> Scope {
    web::scope("/api/v1")
        .route("/health", web::get().to(health_check))
        
        .route("/resources", web::post().to(create_resource))
        .route("/resources/{id}/status", web::get().to(get_resource_status))
        .route("/resources/batch", web::post().to(batch_operations))
        
        .route("/metrics", web::get().to(get_metrics))
        
        .route("/gpu/devices", web::get().to(list_gpu_devices))
        .route("/gpu/devices", web::post().to(register_gpu_device))
        .route("/gpu/tasks", web::get().to(list_gpu_tasks))
        .route("/gpu/stats", web::get().to(get_gpu_scheduler_stats))
        
        .route("/prompts", web::get().to(list_prompts))
        .route("/prompts", web::post().to(create_prompt))
        .route("/prompts/stats", web::get().to(get_prompt_manager_stats))
        
        .route("/ab-tests", web::post().to(create_ab_test))
        .route("/ab-tests/{id}/start", web::post().to(start_ab_test))
        .route("/ab-tests/{id}/report", web::get().to(generate_ab_test_report))
        
        .route("/models", web::get().to(list_models))
        .route("/models", web::post().to(register_model))
        .route("/models/stats", web::get().to(get_model_registry_stats))
        .route("/models/{id}/dashboard", web::get().to(get_model_dashboard))
        
        .route("/adversarial/generate", web::post().to(generate_adversarial_samples))
        .route("/adversarial/evaluate", web::post().to(evaluate_adversarial))
        
        .route("/documents/process", web::post().to(process_document))
        .route("/documents/cache/stats", web::get().to(get_document_cache_stats))
        .route("/documents/cache/invalidate", web::post().to(invalidate_document_cache))
        .route("/documents/cache/clear", web::post().to(clear_document_cache))
        .route("/documents/cache/warmup", web::post().to(warmup_document_cache))
        
        .route("/inference", web::post().to(inference_request))
        .route("/inference/batch", web::post().to(batch_inference))
        .route("/inference/batch/stats", web::get().to(get_batch_stats))
        .route("/inference/batch/start", web::post().to(start_batch_processing))
        .route("/inference/batch/stop", web::post().to(stop_batch_processing))
        
        .route("/features", web::get().to(list_features))
        .route("/features", web::post().to(register_feature))
        .route("/features/stats", web::get().to(get_feature_store_stats))
        .route("/features/insert", web::post().to(insert_feature))
        .route("/features/lookup", web::post().to(lookup_features))
        .route("/features/point-in-time", web::post().to(point_in_time_lookup))
        
        .route("/features/versions", web::post().to(create_feature_version))
        .route("/features/{feature_id}/versions", web::get().to(list_feature_versions))
        .route("/features/{feature_id}/versions/{version_number}", web::get().to(get_feature_version))
        .route("/features/{feature_id}/versions/{from_version}/compare/{to_version}", web::get().to(compare_feature_versions))
        .route("/features/{feature_id}/versions/{target_version}/rollback", web::post().to(rollback_feature_version))
        
        .route("/features/{feature_id}/values/{entity_id}/versions", web::get().to(get_value_versions))
        
        .route("/features/monitor", web::get().to(get_feature_store_monitor))
        .route("/features/monitor/prometheus", web::get().to(get_feature_store_prometheus))
        .route("/features/monitor/reset", web::post().to(reset_feature_store_monitor))
        
        .route("/snapshots", web::get().to(list_snapshots))
        .route("/snapshots", web::post().to(create_snapshot))
        .route("/snapshots/{snapshot_id}", web::get().to(get_snapshot))
        .route("/snapshots/{snapshot_id}", web::delete().to(delete_snapshot))
        .route("/snapshots/{snapshot_id}/protect", web::post().to(protect_snapshot))
}
