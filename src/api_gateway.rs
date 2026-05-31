use crate::config::GatewayConfig;
use crate::error::SystemError;
use crate::scheduler::{Task, TaskPriority};
use axum::{
    extract::{Path, Query, State},
    http::{Method, StatusCode},
    response::{IntoResponse, Json, Response},
    routing::{delete, get, post, put},
    Router,
};
use chrono::Utc;
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Arc;
use tracing::{debug, error, info, warn};
use uuid::Uuid;

use crate::device_shadow::DeviceShadow;
use crate::edge_aggregator::{DataPoint, EdgeAggregator};
use crate::notifier::{NotificationChannel, NotificationPriority, Notifier};
use crate::offline_cache::OfflineCache;
use crate::scheduler::Scheduler;
use crate::storage::StorageManager;
use crate::core::{CoreProcessor, DataRecord};

#[derive(Clone)]
struct AppState {
    config: GatewayConfig,
    device_shadow: DeviceShadow,
    scheduler: Scheduler,
    aggregator: EdgeAggregator,
    offline_cache: OfflineCache,
    notifier: Notifier,
    core_processor: CoreProcessor,
    storage: StorageManager,
    rate_limiter: Arc<DashMap<String, RateLimitEntry>>,
}

#[derive(Debug, Clone)]
struct RateLimitEntry {
    requests: Vec<u64>,
}

impl RateLimitEntry {
    fn new() -> Self {
        Self {
            requests: Vec::new(),
        }
    }

    fn add_request(&mut self, now: u64, limit: u32, window_secs: u64) -> bool {
        self.requests.retain(|&t| now - t < window_secs * 1000);
        if self.requests.len() < limit as usize {
            self.requests.push(now);
            true
        } else {
            false
        }
    }
}

pub struct ApiGateway {
    router: Router,
    config: GatewayConfig,
}

impl ApiGateway {
    pub fn builder() -> ApiGatewayBuilder {
        ApiGatewayBuilder::new()
    }

    pub async fn start(self) -> Result<(), SystemError> {
        let listener = tokio::net::TcpListener::bind(self.config.address())
            .await
            .map_err(|e| SystemError::GatewayError(format!("绑定端口失败: {}", e)))?;

        info!("API网关启动在 {}", self.config.address());

        axum::serve(listener, self.router)
            .await
            .map_err(|e| SystemError::GatewayError(format!("服务启动失败: {}", e)))?;

        Ok(())
    }
}

pub struct ApiGatewayBuilder {
    config: Option<GatewayConfig>,
    device_shadow: Option<DeviceShadow>,
    scheduler: Option<Scheduler>,
    aggregator: Option<EdgeAggregator>,
    offline_cache: Option<OfflineCache>,
    notifier: Option<Notifier>,
    core_processor: Option<CoreProcessor>,
    storage: Option<StorageManager>,
}

impl ApiGatewayBuilder {
    pub fn new() -> Self {
        Self {
            config: None,
            device_shadow: None,
            scheduler: None,
            aggregator: None,
            offline_cache: None,
            notifier: None,
            core_processor: None,
            storage: None,
        }
    }

    pub fn config(mut self, config: &GatewayConfig) -> Self {
        self.config = Some(config.clone());
        self
    }

    pub fn device_shadow(mut self, device_shadow: DeviceShadow) -> Self {
        self.device_shadow = Some(device_shadow);
        self
    }

    pub fn scheduler(mut self, scheduler: Scheduler) -> Self {
        self.scheduler = Some(scheduler);
        self
    }

    pub fn aggregator(mut self, aggregator: EdgeAggregator) -> Self {
        self.aggregator = Some(aggregator);
        self
    }

    pub fn offline_cache(mut self, offline_cache: OfflineCache) -> Self {
        self.offline_cache = Some(offline_cache);
        self
    }

    pub fn notifier(mut self, notifier: Notifier) -> Self {
        self.notifier = Some(notifier);
        self
    }

    pub fn core_processor(mut self, core_processor: CoreProcessor) -> Self {
        self.core_processor = Some(core_processor);
        self
    }

    pub fn storage(mut self, storage: StorageManager) -> Self {
        self.storage = Some(storage);
        self
    }

    pub fn build(self) -> Result<ApiGateway, SystemError> {
        let config = self
            .config
            .ok_or_else(|| SystemError::GatewayError("缺少配置".to_string()))?;

        let state = AppState {
            config: config.clone(),
            device_shadow: self
                .device_shadow
                .ok_or_else(|| SystemError::GatewayError("缺少设备影子模块".to_string()))?,
            scheduler: self
                .scheduler
                .ok_or_else(|| SystemError::GatewayError("缺少调度模块".to_string()))?,
            aggregator: self
                .aggregator
                .ok_or_else(|| SystemError::GatewayError("缺少边缘聚合模块".to_string()))?,
            offline_cache: self
                .offline_cache
                .ok_or_else(|| SystemError::GatewayError("缺少离线缓存模块".to_string()))?,
            notifier: self
                .notifier
                .ok_or_else(|| SystemError::GatewayError("缺少通知模块".to_string()))?,
            core_processor: self
                .core_processor
                .ok_or_else(|| SystemError::GatewayError("缺少核心处理模块".to_string()))?,
            storage: self
                .storage
                .ok_or_else(|| SystemError::GatewayError("缺少存储模块".to_string()))?,
            rate_limiter: Arc::new(DashMap::new()),
        };

        let router = Router::new()
            .route("/health", get(health_check))
            .route("/api/v1/devices", get(list_devices).post(register_device))
            .route("/api/v1/devices/:id", get(get_device).put(update_device_state))
            .route("/api/v1/devices/:id/state", get(get_device_state).put(update_desired_state))
            .route("/api/v1/devices/:id/sync", post(sync_device))
            .route("/api/v1/tasks", get(list_tasks).post(create_task))
            .route("/api/v1/tasks/:id", get(get_task).delete(cancel_task))
            .route("/api/v1/tasks/:id/retry", post(retry_task))
            .route("/api/v1/tasks/:id/progress", put(update_task_progress))
            .route("/api/v1/tasks/stats", get(get_task_stats))
            .route("/api/v1/data/ingest", post(ingest_data))
            .route("/api/v1/data/aggregate", get(get_aggregated_data))
            .route("/api/v1/data/stats", get(get_data_stats))
            .route("/api/v1/cache", get(get_cache_stats).post(ingest_cache))
            .route("/api/v1/cache/:id", get(get_cached_data))
            .route("/api/v1/cache/sync", post(sync_cache))
            .route("/api/v1/notifications", get(list_notifications).post(send_notification))
            .route("/api/v1/notifications/:id", get(get_notification))
            .route("/api/v1/notifications/stats", get(get_notification_stats))
            .route("/api/v1/storage", get(list_files).post(upload_file))
            .route("/api/v1/storage/:id", get(download_file).delete(delete_file))
            .route("/api/v1/storage/:id/metadata", get(get_file_metadata))
            .route("/api/v1/storage/stats", get(get_storage_stats))
            .route("/api/v1/process", post(process_data))
            .route("/api/v1/process/schemas", get(list_schemas))
            .route("/api/v1/stats", get(get_system_stats))
            .with_state(state);

        Ok(ApiGateway { router, config })
    }
}

impl Default for ApiGatewayBuilder {
    fn default() -> Self {
        Self::new()
    }
}

async fn health_check() -> impl IntoResponse {
    Json(json!({
        "status": "ok",
        "timestamp": Utc::now().to_rfc3339(),
        "version": "1.0.0"
    }))
}

async fn list_devices(State(state): State<AppState>) -> Result<Response, AppError> {
    let devices = state.device_shadow.get_all_devices().await;
    Ok(Json(devices).into_response())
}

#[derive(Debug, Deserialize)]
struct RegisterDeviceRequest {
    device_id: String,
}

async fn register_device(
    State(state): State<AppState>,
    Json(payload): Json<RegisterDeviceRequest>,
) -> Result<Response, AppError> {
    state
        .device_shadow
        .register_device(payload.device_id.clone())
        .await?;
    Ok((
        StatusCode::CREATED,
        Json(json!({
            "device_id": payload.device_id,
            "message": "设备注册成功"
        })),
    )
        .into_response())
}

async fn get_device(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Response, AppError> {
    let device = state.device_shadow.get_device_state(&id).await?;
    Ok(Json(device).into_response())
}

#[derive(Debug, Deserialize)]
struct UpdateDeviceRequest {
    reported: HashMap<String, Value>,
}

async fn update_device_state(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(payload): Json<UpdateDeviceRequest>,
) -> Result<Response, AppError> {
    state
        .device_shadow
        .update_reported_state(&id, payload.reported)
        .await?;
    Ok(Json(json!({"message": "状态更新成功"})).into_response())
}

async fn get_device_state(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Response, AppError> {
    let state = state.device_shadow.get_device_state(&id).await?;
    Ok(Json(json!({
        "desired": state.desired,
        "reported": state.reported,
        "version": state.version,
        "timestamp": state.timestamp
    }))
    .into_response())
}

#[derive(Debug, Deserialize)]
struct UpdateDesiredStateRequest {
    desired: HashMap<String, Value>,
}

async fn update_desired_state(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Json(payload): Json<UpdateDesiredStateRequest>,
) -> Result<Response, AppError> {
    state
        .device_shadow
        .update_desired_state(&id, payload.desired)
        .await?;
    Ok(Json(json!({"message": "期望状态更新成功"})).into_response())
}

async fn sync_device(
    State(state): State<AppState>,
    Path(id): Path<String>,
) -> Result<Response, AppError> {
    state.device_shadow.force_sync(&id).await?;
    Ok(Json(json!({"message": "同步已触发"})).into_response())
}

#[derive(Debug, Deserialize)]
struct ListTasksQuery {
    status: Option<String>,
}

async fn list_tasks(
    State(state): State<AppState>,
    Query(query): Query<ListTasksQuery>,
) -> Result<Response, AppError> {
    let status_filter = query.status.and_then(|s| match s.as_str() {
        "pending" => Some(crate::scheduler::TaskStatus::Pending),
        "running" => Some(crate::scheduler::TaskStatus::Running),
        "completed" => Some(crate::scheduler::TaskStatus::Completed),
        "failed" => Some(crate::scheduler::TaskStatus::Failed),
        _ => None,
    });

    let tasks = state.scheduler.list_tasks(status_filter).await;
    Ok(Json(tasks).into_response())
}

#[derive(Debug, Deserialize)]
struct CreateTaskRequest {
    name: String,
    description: Option<String>,
    payload: Value,
    priority: Option<String>,
    tags: Option<Vec<String>>,
    dependencies: Option<Vec<Uuid>>,
}

async fn create_task(
    State(state): State<AppState>,
    Json(payload): Json<CreateTaskRequest>,
) -> Result<Response, AppError> {
    let priority = match payload.priority.as_deref() {
        Some("low") => TaskPriority::Low,
        Some("high") => TaskPriority::High,
        Some("critical") => TaskPriority::Critical,
        _ => TaskPriority::Normal,
    };

    let task_id = state
        .scheduler
        .create_task(
            payload.name,
            payload.description,
            payload.payload,
            priority,
            payload.tags.unwrap_or_default(),
            payload.dependencies.unwrap_or_default(),
        )
        .await?;

    Ok((
        StatusCode::CREATED,
        Json(json!({
            "task_id": task_id.to_string(),
            "message": "任务创建成功"
        })),
    )
        .into_response())
}

async fn get_task(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    let task = state.scheduler.get_task(id).await?;
    Ok(Json(task).into_response())
}

async fn cancel_task(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    state.scheduler.cancel_task(id).await?;
    Ok(Json(json!({"message": "任务已取消"})).into_response())
}

async fn retry_task(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    state.scheduler.retry_task(id).await?;
    Ok(Json(json!({"message": "任务重试已触发"})).into_response())
}

#[derive(Debug, Deserialize)]
struct UpdateProgressRequest {
    progress: f32,
    message: Option<String>,
}

async fn update_task_progress(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
    Json(payload): Json<UpdateProgressRequest>,
) -> Result<Response, AppError> {
    state
        .scheduler
        .update_progress(id, payload.progress, payload.message)
        .await?;
    Ok(Json(json!({"message": "进度已更新"})).into_response())
}

async fn get_task_stats(State(state): State<AppState>) -> Result<Response, AppError> {
    let stats = state.scheduler.get_stats().await?;
    Ok(Json(stats).into_response())
}

#[derive(Debug, Deserialize)]
struct IngestDataRequest {
    device_id: String,
    metric: String,
    value: f64,
    tags: Option<HashMap<String, String>>,
}

async fn ingest_data(
    State(state): State<AppState>,
    Json(payload): Json<IngestDataRequest>,
) -> Result<Response, AppError> {
    let point = DataPoint {
        id: Uuid::new_v4(),
        device_id: payload.device_id,
        metric: payload.metric,
        value: payload.value,
        timestamp: Utc::now(),
        tags: payload.tags.unwrap_or_default(),
    };

    state.aggregator.ingest(point).await?;
    Ok(Json(json!({"message": "数据已接收"})).into_response())
}

async fn get_aggregated_data(State(state): State<AppState>) -> Result<Response, AppError> {
    let results = state.aggregator.get_and_clear_results().await;
    Ok(Json(results).into_response())
}

async fn get_data_stats(State(state): State<AppState>) -> Result<Response, AppError> {
    let stats = state.aggregator.get_stats().await?;
    Ok(Json(stats).into_response())
}

async fn get_cache_stats(State(state): State<AppState>) -> Result<Response, AppError> {
    let stats = state.offline_cache.get_stats().await?;
    Ok(Json(stats).into_response())
}

#[derive(Debug, Deserialize)]
struct IngestCacheRequest {
    data_type: String,
    payload: Value,
    priority: Option<String>,
    tags: Option<Vec<String>>,
}

async fn ingest_cache(
    State(state): State<AppState>,
    Json(payload): Json<IngestCacheRequest>,
) -> Result<Response, AppError> {
    let priority = match payload.priority.as_deref() {
        Some("low") => crate::offline_cache::CachePriority::Low,
        Some("high") => crate::offline_cache::CachePriority::High,
        Some("critical") => crate::offline_cache::CachePriority::Critical,
        _ => crate::offline_cache::CachePriority::Normal,
    };

    let id = state
        .offline_cache
        .cache_data(
            payload.data_type,
            payload.payload,
            priority,
            payload.tags.unwrap_or_default(),
        )
        .await?;

    Ok((
        StatusCode::CREATED,
        Json(json!({
            "id": id.to_string(),
            "message": "数据已缓存"
        })),
    )
        .into_response())
}

async fn get_cached_data(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    let data = state
        .offline_cache
        .get_cached_data(id)
        .await?
        .ok_or_else(|| SystemError::NotFoundError(format!("缓存不存在: {}", id)))?;
    Ok(Json(data).into_response())
}

async fn sync_cache(State(state): State<AppState>) -> Result<Response, AppError> {
    let count = state.offline_cache.sync_to_cloud().await?;
    Ok(Json(json!({
        "synced_count": count,
        "message": "同步完成"
    }))
    .into_response())
}

#[derive(Debug, Deserialize)]
struct ListNotificationsQuery {
    status: Option<String>,
    channel: Option<String>,
}

async fn list_notifications(
    State(state): State<AppState>,
    Query(query): Query<ListNotificationsQuery>,
) -> Result<Response, AppError> {
    let status_filter = query.status.and_then(|s| match s.as_str() {
        "pending" => Some(crate::notifier::NotificationStatus::Pending),
        "sent" => Some(crate::notifier::NotificationStatus::Sent),
        "delivered" => Some(crate::notifier::NotificationStatus::Delivered),
        "failed" => Some(crate::notifier::NotificationStatus::Failed),
        _ => None,
    });

    let channel_filter = query.channel.and_then(|c| match c.as_str() {
        "webhook" => Some(NotificationChannel::Webhook),
        "email" => Some(NotificationChannel::Email),
        "sms" => Some(NotificationChannel::Sms),
        "inapp" => Some(NotificationChannel::InApp),
        _ => None,
    });

    let notifications = state
        .notifier
        .list_notifications(status_filter, channel_filter)
        .await;
    Ok(Json(notifications).into_response())
}

#[derive(Debug, Deserialize)]
struct SendNotificationRequest {
    channel: String,
    recipient: String,
    subject: String,
    content: String,
    priority: Option<String>,
    metadata: Option<HashMap<String, String>>,
}

async fn send_notification(
    State(state): State<AppState>,
    Json(payload): Json<SendNotificationRequest>,
) -> Result<Response, AppError> {
    let channel = match payload.channel.as_str() {
        "webhook" => NotificationChannel::Webhook,
        "email" => NotificationChannel::Email,
        "sms" => NotificationChannel::Sms,
        "inapp" => NotificationChannel::InApp,
        _ => {
            return Err(AppError::from(SystemError::ValidationError(format!(
                "不支持的通道: {}",
                payload.channel
            ))));
        }
    };

    let priority = match payload.priority.as_deref() {
        Some("low") => NotificationPriority::Low,
        Some("high") => NotificationPriority::High,
        Some("critical") => NotificationPriority::Critical,
        _ => NotificationPriority::Normal,
    };

    let id = state
        .notifier
        .send_notification(
            channel,
            payload.recipient,
            payload.subject,
            payload.content,
            priority,
            payload.metadata.unwrap_or_default(),
        )
        .await?;

    Ok((
        StatusCode::CREATED,
        Json(json!({
            "notification_id": id.to_string(),
            "message": "通知已发送"
        })),
    )
        .into_response())
}

async fn get_notification(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    let notification = state.notifier.get_notification(id).await?;
    Ok(Json(notification).into_response())
}

async fn get_notification_stats(State(state): State<AppState>) -> Result<Response, AppError> {
    let stats = state.notifier.get_stats().await?;
    Ok(Json(stats).into_response())
}

async fn list_files(State(state): State<AppState>) -> Result<Response, AppError> {
    let files = state.storage.list_files().await?;
    Ok(Json(files).into_response())
}

#[derive(Debug, Deserialize)]
struct UploadFileRequest {
    name: String,
    content: String,
    content_type: Option<String>,
}

async fn upload_file(
    State(state): State<AppState>,
    Json(payload): Json<UploadFileRequest>,
) -> Result<Response, AppError> {
    let content = payload.content.as_bytes();
    let content_type = payload.content_type.unwrap_or_else(|| "application/octet-stream".to_string());

    let file_id = state
        .storage
        .save_file(payload.name, content, content_type, None)
        .await?;

    Ok((
        StatusCode::CREATED,
        Json(json!({
            "file_id": file_id.to_string(),
            "message": "文件上传成功"
        })),
    )
        .into_response())
}

async fn download_file(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    let (content, meta) = state.storage.get_file(id).await?;
    Ok((
        [("Content-Type", meta.content_type.as_str())],
        content,
    )
        .into_response())
}

async fn delete_file(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    state.storage.delete_file(id).await?;
    Ok(Json(json!({"message": "文件已删除"})).into_response())
}

async fn get_file_metadata(
    State(state): State<AppState>,
    Path(id): Path<Uuid>,
) -> Result<Response, AppError> {
    let meta = state.storage.get_file_metadata(id).await?;
    Ok(Json(meta).into_response())
}

async fn get_storage_stats(State(state): State<AppState>) -> Result<Response, AppError> {
    let stats = state.storage.get_stats().await?;
    Ok(Json(stats).into_response())
}

#[derive(Debug, Deserialize)]
struct ProcessDataRequest {
    id: String,
    source: String,
    data_type: String,
    payload: Value,
    metadata: Option<HashMap<String, String>>,
}

async fn process_data(
    State(state): State<AppState>,
    Json(payload): Json<ProcessDataRequest>,
) -> Result<Response, AppError> {
    let record = DataRecord {
        id: payload.id,
        timestamp: Utc::now(),
        source: payload.source,
        data_type: payload.data_type,
        payload: payload.payload,
        metadata: payload.metadata.unwrap_or_default(),
    };

    let result = state.core_processor.process(record).await?;
    Ok(Json(result).into_response())
}

async fn list_schemas(State(state): State<AppState>) -> Result<Response, AppError> {
    let schemas = state.core_processor.list_schemas();
    Ok(Json(schemas).into_response())
}

async fn get_system_stats(State(state): State<AppState>) -> Result<Response, AppError> {
    let task_stats = state.scheduler.get_stats().await?;
    let data_stats = state.aggregator.get_stats().await?;
    let cache_stats = state.offline_cache.get_stats().await?;
    let notif_stats = state.notifier.get_stats().await?;
    let storage_stats = state.storage.get_stats().await?;
    let process_stats = state.core_processor.get_stats().await?;

    Ok(Json(json!({
        "timestamp": Utc::now().to_rfc3339(),
        "tasks": task_stats,
        "data_aggregation": data_stats,
        "offline_cache": cache_stats,
        "notifications": notif_stats,
        "storage": storage_stats,
        "processing": process_stats,
    }))
    .into_response())
}

struct AppError(SystemError);

impl From<SystemError> for AppError {
    fn from(err: SystemError) -> Self {
        AppError(err)
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let (status, message) = match &self.0 {
            SystemError::NotFoundError(msg) => (StatusCode::NOT_FOUND, msg.clone()),
            SystemError::ValidationError(msg) => (StatusCode::BAD_REQUEST, msg.clone()),
            SystemError::ConfigError(msg) => (StatusCode::BAD_REQUEST, msg.clone()),
            _ => (
                StatusCode::INTERNAL_SERVER_ERROR,
                self.0.to_string(),
            ),
        };

        (
            status,
            Json(json!({
                "error": message,
                "timestamp": Utc::now().to_rfc3339()
            })),
        )
            .into_response()
    }
}
