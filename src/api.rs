use crate::core::RequestHandler;
use crate::types::{
    ApiResponse, AppConfig, AppError, AppResult, BatchOperationRequest,
    BatchOperationResponse, QualityRule, ResourceCreateRequest, ResourceCreateResponse,
    ResourceStatusResponse, generate_id,
};
use crate::notification::{NotificationManager, NotificationTemplate};
use crate::lineage::LineageManager;
use crate::data_quality::QualityRuleManager;
use crate::metadata_crawler::MetadataCrawler;
use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;

#[derive(Clone)]
pub struct AppState {
    pub config: AppConfig,
    pub request_handler: Arc<RequestHandler>,
    pub notification_manager: Arc<NotificationManager>,
    pub lineage_manager: Arc<LineageManager>,
    pub quality_rule_manager: Arc<QualityRuleManager>,
    pub metadata_crawler: Arc<MetadataCrawler>,
    pub resources: Arc<DashMap<String, crate::types::CoreEntity>>,
    pub run_instances: Arc<DashMap<String, crate::types::RunInstance>>,
}

impl AppState {
    pub fn new(
        config: AppConfig,
        request_handler: RequestHandler,
        notification_manager: NotificationManager,
        lineage_manager: LineageManager,
        quality_rule_manager: QualityRuleManager,
        metadata_crawler: MetadataCrawler,
    ) -> Self {
        Self {
            config,
            request_handler: Arc::new(request_handler),
            notification_manager: Arc::new(notification_manager),
            lineage_manager: Arc::new(lineage_manager),
            quality_rule_manager: Arc::new(quality_rule_manager),
            metadata_crawler: Arc::new(metadata_crawler),
            resources: Arc::new(DashMap::new()),
            run_instances: Arc::new(DashMap::new()),
        }
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> axum::response::Response {
        let status = match &self {
            AppError::ValidationError(_) => StatusCode::BAD_REQUEST,
            AppError::TimeoutError => StatusCode::GATEWAY_TIMEOUT,
            AppError::NotFound(_) => StatusCode::NOT_FOUND,
            AppError::Unauthorized(_) => StatusCode::UNAUTHORIZED,
            AppError::Forbidden(_) => StatusCode::FORBIDDEN,
            AppError::RateLimited => StatusCode::TOO_MANY_REQUESTS,
            AppError::Conflict(_) => StatusCode::CONFLICT,
            _ => StatusCode::INTERNAL_SERVER_ERROR,
        };

        let body = ApiResponse::<()>::error(status.as_u16(), self.to_string());
        (status, Json(body)).into_response()
    }
}

pub async fn health_check() -> impl IntoResponse {
    let response = ApiResponse::success(serde_json::json!({
        "status": "healthy",
        "version": crate::version(),
        "timestamp": crate::types::now_utc().to_rfc3339()
    }));
    Json(response)
}

#[derive(Debug, Deserialize)]
pub struct PaginationQuery {
    pub limit: Option<usize>,
    pub offset: Option<usize>,
}

pub async fn create_resource(
    State(state): State<AppState>,
    Json(request): Json<ResourceCreateRequest>,
) -> AppResult<impl IntoResponse> {
    tracing::info!(target: "api", "创建资源: type={}", request.r#type);

    let entity = crate::types::CoreEntity {
        id: generate_id("rsc"),
        r#type: request.r#type.clone(),
        status: crate::types::EntityStatus::Provisioning,
        attributes: {
            let mut attrs = HashMap::new();
            attrs.insert("config".to_string(), request.config);
            attrs.insert("labels".to_string(), serde_json::json!(request.labels));
            attrs
        },
        created_at: crate::types::now_utc(),
        updated_at: crate::types::now_utc(),
    };

    let run_instance = crate::types::RunInstance {
        run_id: generate_id("run"),
        entity_id: entity.id.clone(),
        phase: crate::types::RunPhase::Initializing,
        progress: 0.0,
        started_at: crate::types::now_utc(),
        completed_at: None,
        error_detail: None,
    };

    state.resources.insert(entity.id.clone(), entity.clone());
    state.run_instances.insert(run_instance.run_id.clone(), run_instance);

    let response = ApiResponse::created(ResourceCreateResponse {
        id: entity.id,
        status: "provisioning".to_string(),
    });

    Ok((StatusCode::CREATED, Json(response)))
}

pub async fn get_resource_status(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> AppResult<impl IntoResponse> {
    tracing::info!(target: "api", "查询资源状态: id={}", id);

    let resource = state
        .resources
        .get(&id)
        .ok_or_else(|| AppError::NotFound(format!("资源不存在: {}", id)))?;

    let progress = state
        .run_instances
        .iter()
        .find(|ri| ri.entity_id == id)
        .map(|ri| ri.progress)
        .unwrap_or(0.0);

    let status_str = match resource.status {
        crate::types::EntityStatus::Provisioning => "provisioning",
        crate::types::EntityStatus::Completed => "completed",
        crate::types::EntityStatus::Failed => "failed",
        crate::types::EntityStatus::Active => "active",
        crate::types::EntityStatus::Inactive => "inactive",
        crate::types::EntityStatus::Pending => "pending",
        crate::types::EntityStatus::Deprovisioning => "deprovisioning",
        crate::types::EntityStatus::Cancelled => "cancelled",
    };

    let response = ApiResponse::success(ResourceStatusResponse {
        id: resource.id.clone(),
        status: status_str.to_string(),
        progress,
    });

    Ok(Json(response))
}

pub async fn batch_operations(
    State(state): State<AppState>,
    Json(request): Json<BatchOperationRequest>,
) -> AppResult<impl IntoResponse> {
    tracing::info!(target: "api", "执行批量操作: count={}", request.operations.len());

    let batch_id = generate_id("batch");
    let mut results = Vec::new();

    for op in request.operations {
        let success = state.resources.contains_key(&op.id);
        let message = if success {
            None
        } else {
            Some(format!("资源不存在: {}", op.id))
        };

        if success {
            if let Some(mut resource) = state.resources.get_mut(&op.id) {
                match op.action.as_str() {
                    "start" => resource.status = crate::types::EntityStatus::Active,
                    "stop" => resource.status = crate::types::EntityStatus::Inactive,
                    "delete" => resource.status = crate::types::EntityStatus::Deprovisioning,
                    _ => {}
                }
                resource.updated_at = crate::types::now_utc();
            }
        }

        results.push(crate::types::BatchResult {
            id: op.id,
            success,
            message,
        });
    }

    let response = ApiResponse::success(BatchOperationResponse {
        batch_id,
        results,
    });

    Ok(Json(response))
}

pub async fn list_resources(
    State(state): State<AppState>,
    Query(pagination): Query<PaginationQuery>,
) -> AppResult<impl IntoResponse> {
    let limit = pagination.limit.unwrap_or(100);
    let offset = pagination.offset.unwrap_or(0);

    let mut resources: Vec<_> = state.resources.iter().map(|r| r.clone()).collect();
    resources.sort_by(|a, b| b.created_at.cmp(&a.created_at));

    let total = resources.len();
    let paginated: Vec<_> = resources.into_iter().skip(offset).take(limit).collect();

    let response = ApiResponse::success(serde_json::json!({
        "items": paginated,
        "total": total,
        "limit": limit,
        "offset": offset
    }));

    Ok(Json(response))
}

pub async fn execute_request(
    State(state): State<AppState>,
    Json(request): Json<crate::types::HandlerRequest>,
) -> AppResult<impl IntoResponse> {
    tracing::info!(target: "api", "执行请求: trace_id={}", request.trace_id);

    let response = state.request_handler.execute_handler(request).await;

    let api_response = ApiResponse {
        code: response.code,
        data: response.data,
        message: response.message,
    };

    let status = StatusCode::from_u16(response.code).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR);

    Ok((status, Json(api_response)))
}

#[derive(Debug, Deserialize)]
pub struct NotificationSendRequest {
    pub channel: String,
    pub recipient: String,
    pub template_id: String,
    pub variables: HashMap<String, serde_json::Value>,
    pub priority: String,
}

pub async fn send_notification(
    State(state): State<AppState>,
    Json(request): Json<NotificationSendRequest>,
) -> AppResult<impl IntoResponse> {
    let channel = match request.channel.as_str() {
        "email" => crate::types::NotificationChannel::Email,
        "sms" => crate::types::NotificationChannel::Sms,
        "slack" => crate::types::NotificationChannel::Slack,
        "dingtalk" => crate::types::NotificationChannel::Dingtalk,
        "wechat" => crate::types::NotificationChannel::Wechat,
        "webhook" => crate::types::NotificationChannel::Webhook,
        "in_app" => crate::types::NotificationChannel::InApp,
        _ => return Err(AppError::ValidationError(format!("不支持的通知渠道: {}", request.channel))),
    };

    let priority = match request.priority.as_str() {
        "low" => crate::types::NotificationPriority::Low,
        "medium" => crate::types::NotificationPriority::Medium,
        "high" => crate::types::NotificationPriority::High,
        "urgent" => crate::types::NotificationPriority::Urgent,
        _ => return Err(AppError::ValidationError(format!("不支持的优先级: {}", request.priority))),
    };

    let result = state
        .notification_manager
        .send(channel, &request.recipient, &request.template_id, request.variables, priority)
        .await?;

    let response = ApiResponse::success(result);
    Ok(Json(response))
}

pub async fn list_notification_templates(
    State(state): State<AppState>,
) -> AppResult<impl IntoResponse> {
    let templates = state.notification_manager.template_manager().list_templates();
    let response = ApiResponse::success(templates);
    Ok(Json(response))
}

pub async fn create_notification_template(
    State(state): State<AppState>,
    Json(template): Json<NotificationTemplate>,
) -> AppResult<impl IntoResponse> {
    state.notification_manager.template_manager().register_template(template.clone())?;
    let response = ApiResponse::created(template);
    Ok((StatusCode::CREATED, Json(response)))
}

#[derive(Debug, Deserialize)]
pub struct ParseSqlRequest {
    pub sql: String,
}

#[derive(Debug, Serialize)]
pub struct ParseSqlResponse {
    pub query_id: String,
    pub source_tables: Vec<String>,
    pub target_tables: Vec<String>,
    pub source_columns: Vec<(String, String)>,
    pub target_columns: Vec<(String, String)>,
}

pub async fn parse_sql_lineage(
    State(state): State<AppState>,
    Json(request): Json<ParseSqlRequest>,
) -> AppResult<impl IntoResponse> {
    let parsed = state.lineage_manager.parse_sql_async(&request.sql).await?;

    let response = ApiResponse::success(ParseSqlResponse {
        query_id: parsed.query_id,
        source_tables: parsed.source_tables,
        target_tables: parsed.target_tables,
        source_columns: parsed.source_columns,
        target_columns: parsed.target_columns,
    });

    Ok(Json(response))
}

pub async fn build_lineage_dag(
    State(state): State<AppState>,
) -> AppResult<impl IntoResponse> {
    let graph = state.lineage_manager.build_dag(None)?;
    let response = ApiResponse::success(graph);
    Ok(Json(response))
}

pub async fn get_lineage_graph(
    State(state): State<AppState>,
    Path(graph_id): Path<String>,
) -> AppResult<impl IntoResponse> {
    let graph = state
        .lineage_manager
        .get_graph(&graph_id)
        .ok_or_else(|| AppError::NotFound(format!("图谱不存在: {}", graph_id)))?;

    let response = ApiResponse::success(graph);
    Ok(Json(response))
}

#[derive(Debug, Deserialize)]
pub struct LineageQueryRequest {
    pub graph_id: String,
    pub node_fqn: String,
}

pub async fn get_lineage_upstream(
    State(state): State<AppState>,
    Path((graph_id, node_fqn)): Path<(String, String)>,
) -> AppResult<impl IntoResponse> {
    let nodes = state.lineage_manager.get_upstream(&graph_id, &node_fqn)?;
    let response = ApiResponse::success(nodes);
    Ok(Json(response))
}

pub async fn get_lineage_downstream(
    State(state): State<AppState>,
    Path((graph_id, node_fqn)): Path<(String, String)>,
) -> AppResult<impl IntoResponse> {
    let nodes = state.lineage_manager.get_downstream(&graph_id, &node_fqn)?;
    let response = ApiResponse::success(nodes);
    Ok(Json(response))
}

pub async fn list_quality_rules(
    State(state): State<AppState>,
    Query(pagination): Query<PaginationQuery>,
) -> AppResult<impl IntoResponse> {
    let limit = pagination.limit.unwrap_or(100);
    let offset = pagination.offset.unwrap_or(0);

    let mut rules = state.quality_rule_manager.list_rules();
    let total = rules.len();

    rules.sort_by(|a, b| b.created_at.cmp(&a.created_at));
    let paginated: Vec<_> = rules.into_iter().skip(offset).take(limit).collect();

    let response = ApiResponse::success(serde_json::json!({
        "items": paginated,
        "total": total,
        "limit": limit,
        "offset": offset
    }));

    Ok(Json(response))
}

pub async fn create_quality_rule(
    State(state): State<AppState>,
    Json(rule): Json<crate::types::QualityRule>,
) -> AppResult<impl IntoResponse> {
    let created = state.quality_rule_manager.create_rule(
        &rule.name,
        &rule.description,
        rule.rule_type.clone(),
        &rule.dataset,
        &rule.expression,
        rule.severity.clone(),
        rule.threshold,
        &rule.schedule,
    );
    let response = ApiResponse::created(created);
    Ok((StatusCode::CREATED, Json(response)))
}

pub async fn execute_quality_rule(
    State(state): State<AppState>,
    Path(rule_id): Path<String>,
) -> AppResult<impl IntoResponse> {
    let result = state.quality_rule_manager.execute_rule(&rule_id).await?;
    let response = ApiResponse::success(result);
    Ok(Json(response))
}

pub async fn list_data_sources(
    State(state): State<AppState>,
) -> AppResult<impl IntoResponse> {
    let sources = state.metadata_crawler.list_sources();
    let response = ApiResponse::success(sources);
    Ok(Json(response))
}

pub async fn crawl_data_source(
    State(state): State<AppState>,
    Path(source_id): Path<String>,
) -> AppResult<impl IntoResponse> {
    let schema = state.metadata_crawler.crawl_source(&source_id).await?;
    let response = ApiResponse::success(schema);
    Ok(Json(response))
}

pub async fn get_data_source_schema(
    State(state): State<AppState>,
    Path(source_id): Path<String>,
) -> AppResult<impl IntoResponse> {
    let schema = state
        .metadata_crawler
        .get_schema(&source_id)
        .ok_or_else(|| AppError::NotFound(format!("数据源不存在: {}", source_id)))?;

    let response = ApiResponse::success(schema);
    Ok(Json(response))
}

pub fn create_router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health_check))
        .route("/api/v1/resources", post(create_resource).get(list_resources))
        .route("/api/v1/resources/:id/status", get(get_resource_status))
        .route("/api/v1/resources/batch", post(batch_operations))
        .route("/api/v1/execute", post(execute_request))
        .route("/api/v1/notifications/send", post(send_notification))
        .route(
            "/api/v1/notifications/templates",
            get(list_notification_templates).post(create_notification_template),
        )
        .route("/api/v1/lineage/parse", post(parse_sql_lineage))
        .route("/api/v1/lineage/dag", post(build_lineage_dag))
        .route("/api/v1/lineage/dag/:graph_id", get(get_lineage_graph))
        .route(
            "/api/v1/lineage/dag/:graph_id/upstream/:node_fqn",
            get(get_lineage_upstream),
        )
        .route(
            "/api/v1/lineage/dag/:graph_id/downstream/:node_fqn",
            get(get_lineage_downstream),
        )
        .route(
            "/api/v1/quality/rules",
            get(list_quality_rules).post(create_quality_rule),
        )
        .route("/api/v1/quality/rules/:rule_id/execute", post(execute_quality_rule))
        .route("/api/v1/metadata/sources", get(list_data_sources))
        .route("/api/v1/metadata/sources/:source_id/crawl", post(crawl_data_source))
        .route("/api/v1/metadata/sources/:source_id/schema", get(get_data_source_schema))
        .with_state(state)
}
