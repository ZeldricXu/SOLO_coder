use axum::{Router, routing::{get, post, put, delete}, extract::DefaultBodyLimit};
use tower_http::trace::TraceLayer;
use tower_http::cors::{CorsLayer, Any};
use crate::api::handlers::*;
use crate::AppState;

pub fn create_router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health_check))
        
        .route("/api/v1/configs", post(create_config).get(list_configs))
        .route("/api/v1/configs/:config_id", get(get_config).put(update_config).delete(delete_config))
        .route("/api/v1/configs/:config_id/rollback", post(rollback_config))
        .route("/api/v1/configs/:config_id/history", get(get_config_history))
        
        .route("/api/v1/scaffold/templates", get(list_scaffold_templates))
        .route("/api/v1/scaffold/generate", post(generate_scaffold))
        
        .route("/api/v1/metrics", get(list_metrics))
        .route("/api/v1/metrics/:name/statistics", get(get_metric_statistics))
        
        .route("/api/v1/feature-flags", post(create_feature_flag).get(list_feature_flags))
        .route("/api/v1/feature-flags/:flag_id", get(get_feature_flag).put(update_feature_flag))
        .route("/api/v1/feature-flags/evaluate", post(evaluate_feature_flag))
        .route("/api/v1/feature-flags/evaluate-all", post(evaluate_all_feature_flags))
        .route("/api/v1/user-segments", get(list_user_segments))
        .route("/api/v1/user-segments/:segment_id/:name", post(create_user_segment))
        
        .route("/api/v1/vulnerabilities/cves", get(list_cves))
        .route("/api/v1/vulnerabilities/scan", post(scan_sbom))
        
        .route("/api/v1/scheduler/workflows", post(schedule_workflow).get(list_workflows))
        .route("/api/v1/scheduler/workflows/:execution_id", get(get_workflow_status))
        .route("/api/v1/scheduler/workflows/:execution_id/execute", post(execute_workflow))
        
        .route("/api/v1/migrations", post(register_migration))
        .route("/api/v1/migrations/run", post(run_migrations))
        .route("/api/v1/migrations/status", get(get_migration_status))
        
        .route("/api/v1/storage/frequency/:key", get(check_frequency))
        .route("/api/v1/storage/data/:key", get(get_data).put(put_data))
        .route("/api/v1/storage/backups", get(list_backups))
        .route("/api/v1/storage/backups/:source/:dest", post(create_backup))
        .route("/api/v1/storage/restore", post(restore_backup))
        
        .route("/api/v1/quality/analyze", post(analyze_code))
        .route("/api/v1/quality/gate/:report_id", get(check_quality_gate))
        .route("/api/v1/quality/rules", get(list_analysis_rules))
        .route("/api/v1/quality/thresholds", get(list_quality_thresholds))
        
        .layer(TraceLayer::new_for_http())
        .layer(CorsLayer::new().allow_origin(Any).allow_methods(Any).allow_headers(Any))
        .layer(DefaultBodyLimit::max(10 * 1024 * 1024))
        .with_state(state)
}
