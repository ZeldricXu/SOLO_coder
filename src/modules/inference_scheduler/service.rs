use std::sync::Arc;
use std::collections::BinaryHeap;
use std::cmp::Ordering;
use dashmap::DashMap;
use serde_json::json;
use tracing::{info, warn, debug, error};
use tokio::sync::mpsc;
use tokio::time::{timeout, Duration};
use lru::LruCache;
use parking_lot::Mutex;
use uuid::Uuid;

use crate::common::error::{AppError, AppResult};
use crate::common::context::RequestContext;
use crate::common::event::DomainEvent;
use crate::common::context::AuditLogger;
use crate::common::metrics::MetricsCollector;
use crate::ports::mod::{EventPublisherPort, CachePort, CloudSyncPort, RepositoryPort};
use super::model::{
    AiModel, InferenceTask, InferenceResult, InferenceStatistics,
    RegisterModelRequest, DeployModelRequest, InferenceRequest, BatchInferenceRequest,
    ModelResponse, TaskResponse, InferenceResultResponse, ModelVersionResponse,
    ModelDeploymentStatus, TaskStatus, TaskPriority, SyncStatus,
    ModelVersion, CreateVersionRequest, UpdateVersionRequest, VersionMigrationRequest,
    VersionMigration, SemanticVersion, VersionStatus,
};

#[derive(Debug, Clone)]
struct QueuedTask {
    task: InferenceTask,
    queued_at: chrono::DateTime<chrono::Utc>,
}

impl Eq for QueuedTask {}

impl PartialEq for QueuedTask {
    fn eq(&self, other: &Self) -> bool {
        self.task.task_id == other.task.task_id
    }
}

impl Ord for QueuedTask {
    fn cmp(&self, other: &Self) -> Ordering {
        other.task.priority_value().cmp(&self.task.priority_value())
            .then_with(|| self.queued_at.cmp(&other.queued_at))
    }
}

impl PartialOrd for QueuedTask {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

struct DeviceResource {
    device_id: String,
    cpu_cores_available: f64,
    memory_mb_available: u64,
    gpu_available: bool,
    gpu_memory_mb_available: u64,
    running_tasks: Vec<String>,
}

pub struct InferenceSchedulerService {
    models: Arc<DashMap<String, AiModel>>,
    tasks: Arc<DashMap<String, InferenceTask>>,
    results: Arc<DashMap<String, InferenceResult>>,
    task_queue: Arc<Mutex<BinaryHeap<QueuedTask>>>,
    device_resources: Arc<DashMap<String, DeviceResource>>,
    result_cache: Arc<Mutex<LruCache<String, InferenceResult>>>,
    event_publisher: Arc<dyn EventPublisherPort>,
    cache: Arc<dyn CachePort>,
    cloud_sync: Arc<dyn CloudSyncPort>,
    result_repository: Arc<dyn RepositoryPort<InferenceResult>>,
    audit_logger: Arc<AuditLogger>,
    metrics: MetricsCollector,
    task_sender: mpsc::Sender<InferenceTask>,
    sync_sender: mpsc::Sender<InferenceResult>,
}

impl InferenceSchedulerService {
    pub fn new(
        event_publisher: Arc<dyn EventPublisherPort>,
        cache: Arc<dyn CachePort>,
        cloud_sync: Arc<dyn CloudSyncPort>,
        result_repository: Arc<dyn RepositoryPort<InferenceResult>>,
        audit_logger: Arc<AuditLogger>,
    ) -> Arc<Self> {
        let (task_sender, task_receiver) = mpsc::channel::<InferenceTask>(1000);
        let (sync_sender, sync_receiver) = mpsc::channel::<InferenceResult>(1000);

        let service = Arc::new(Self {
            models: Arc::new(DashMap::new()),
            tasks: Arc::new(DashMap::new()),
            results: Arc::new(DashMap::new()),
            task_queue: Arc::new(Mutex::new(BinaryHeap::new())),
            device_resources: Arc::new(DashMap::new()),
            result_cache: Arc::new(Mutex::new(LruCache::new(
                std::num::NonZeroUsize::new(10000).unwrap(),
            ))),
            event_publisher,
            cache,
            cloud_sync,
            result_repository,
            audit_logger,
            metrics: MetricsCollector::new().with_dimension("module", "inference_scheduler"),
            task_sender,
            sync_sender,
        });

        service.start_worker(task_receiver);
        service.start_sync_worker(sync_receiver);
        service.start_timeout_checker();
        service.start_cloud_sync_worker();

        service
    }

    fn start_worker(self: &Arc<Self>, mut receiver: mpsc::Receiver<InferenceTask>) {
        let service = self.clone();
        tokio::spawn(async move {
            while let Some(task) = receiver.recv().await {
                let service_clone = service.clone();
                tokio::spawn(async move {
                    if let Err(e) = service_clone.execute_inference(task).await {
                        error!(error = %e, "Failed to execute inference task");
                    }
                });
            }
        });
    }

    fn start_sync_worker(self: &Arc<Self>, mut receiver: mpsc::Receiver<InferenceResult>) {
        let service = self.clone();
        tokio::spawn(async move {
            while let Some(result) = receiver.recv().await {
                let service_clone = service.clone();
                tokio::spawn(async move {
                    if let Err(e) = service_clone.sync_result_to_cloud(result).await {
                        warn!(error = %e, "Failed to sync result to cloud, will retry later");
                    }
                });
            }
        });
    }

    fn start_timeout_checker(self: &Arc<Self>) {
        let service = self.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(5));
            loop {
                interval.tick().await;
                service.check_timeouts().await;
            }
        });
    }

    fn start_cloud_sync_worker(self: &Arc<Self>) {
        let service = self.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(30));
            loop {
                interval.tick().await;
                service.retry_failed_syncs().await;
            }
        });
    }

    pub async fn register_model(
        &self,
        ctx: &RequestContext,
        req: RegisterModelRequest,
    ) -> AppResult<ModelResponse> {
        let start = std::time::Instant::now();
        debug!(model_name = %req.name, version = %req.version, "Registering new model");

        self.validate_register_request(&req)?;

        let model = AiModel::new(req);
        let model_id = model.model_id.clone();

        self.models.insert(model_id.clone(), model.clone());

        self.audit_logger.log_operation(
            ctx,
            "model.register",
            "inference_model",
            &model_id,
            true,
            json!({
                "name": model.name,
                "version": model.version,
                "framework": model.framework,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(ModelResponse {
            model_id: model.model_id,
            name: model.name,
            version: model.version,
            description: model.description,
            model_type: model.model_type,
            framework: model.framework,
            deployment_status: model.deployment_status,
            deployed_device_id: model.deployed_device_id,
            deployed_at: model.deployed_at,
            created_at: model.created_at,
            version_count: model.versions.len(),
            current_version: model.current_version_id.clone(),
        })
    }

    pub async fn deploy_model(
        &self,
        ctx: &RequestContext,
        req: DeployModelRequest,
    ) -> AppResult<ModelResponse> {
        let start = std::time::Instant::now();
        info!(model_id = %req.model_id, device_id = %req.device_id, "Deploying model to device");

        let mut model = self.models.get_mut(&req.model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", req.model_id)))?;

        if model.deployment_status == ModelDeploymentStatus::Deployed {
            return Err(AppError::Conflict(format!("模型已部署在设备: {}", model.deployed_device_id.as_ref().unwrap())));
        }

        self.allocate_resources(&req.device_id, &model.resource_requirements)?;

        model.deploy(req.device_id.clone(), req.version_id.clone());
        let model_clone = model.clone();

        drop(model);

        let event = DomainEvent::new(
            "model.deployed",
            &req.model_id,
            json!({
                "model_id": req.model_id,
                "device_id": req.device_id,
                "model_name": model_clone.name,
                "version": model_clone.version,
                "version_id": req.version_id,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        tokio::time::sleep(Duration::from_millis(500)).await;

        let mut model = self.models.get_mut(&req.model_id).unwrap();
        model.mark_deployed(req.version_id.clone());
        let model_clone = model.clone();
        drop(model);

        self.audit_logger.log_operation(
            ctx,
            "model.deploy",
            "inference_model",
            &req.model_id,
            true,
            json!({
                "device_id": req.device_id,
                "status": "deployed",
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(ModelResponse {
            model_id: model_clone.model_id,
            name: model_clone.name,
            version: model_clone.version,
            description: model_clone.description,
            model_type: model_clone.model_type,
            framework: model_clone.framework,
            deployment_status: model_clone.deployment_status,
            deployed_device_id: model_clone.deployed_device_id,
            deployed_at: model_clone.deployed_at,
            created_at: model_clone.created_at,
            version_count: model_clone.versions.len(),
            current_version: model_clone.current_version_id.clone(),
        })
    }

    pub async fn get_model(&self, ctx: &RequestContext, model_id: &str) -> AppResult<ModelResponse> {
        let start = std::time::Instant::now();
        debug!(model_id = %model_id, "Getting model");

        let model = self.models.get(model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", model_id)))?;

        self.audit_logger.log_operation(
            ctx,
            "model.get",
            "inference_model",
            model_id,
            true,
            json!({ "deployment_status": format!("{:?}", model.deployment_status) }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(ModelResponse {
            model_id: model.model_id.clone(),
            name: model.name.clone(),
            version: model.version.clone(),
            description: model.description.clone(),
            model_type: model.model_type.clone(),
            framework: model.framework.clone(),
            deployment_status: model.deployment_status.clone(),
            deployed_device_id: model.deployed_device_id.clone(),
            deployed_at: model.deployed_at,
            created_at: model.created_at,
            version_count: model.versions.len(),
            current_version: model.current_version_id.clone(),
        })
    }

    pub async fn list_models(
        &self,
        page: u32,
        page_size: u32,
    ) -> AppResult<(Vec<ModelResponse>, u64)> {
        let items: Vec<ModelResponse> = self.models.iter()
            .map(|m| ModelResponse {
                model_id: m.model_id.clone(),
                name: m.name.clone(),
                version: m.version.clone(),
                description: m.description.clone(),
                model_type: m.model_type.clone(),
                framework: m.framework.clone(),
                deployment_status: m.deployment_status.clone(),
                deployed_device_id: m.deployed_device_id.clone(),
                deployed_at: m.deployed_at,
                created_at: m.created_at,
                version_count: m.versions.len(),
                current_version: m.current_version_id.clone(),
            })
            .collect();

        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();

        Ok((paginated, total))
    }

    pub async fn submit_inference(
        &self,
        ctx: &RequestContext,
        req: InferenceRequest,
    ) -> AppResult<TaskResponse> {
        let start = std::time::Instant::now();
        debug!(model_id = %req.model_id, "Submitting inference task");

        let model = self.models.get(&req.model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", req.model_id)))?;

        if !model.is_deployed() {
            return Err(AppError::Conflict(format!("模型未部署，当前状态: {:?}", model.deployment_status)));
        }

        let model_version = if let Some(version_id) = &req.version_id {
            model.get_version(version_id)
                .map(|v| v.version.clone())
                .unwrap_or_else(|| model.version.clone())
        } else {
            req.model_version.clone().unwrap_or_else(|| model.version.clone())
        };
        let version_id = req.version_id.clone();
        drop(model);

        let task = InferenceTask::new(req, model_version, version_id);
        let task_id = task.task_id.clone();

        let start_event = DomainEvent::new(
            "inference.started",
            &task_id,
            json!({
                "task_id": task_id,
                "model_id": task.model_id,
                "model_version": task.model_version,
                "priority": format!("{:?}", task.priority),
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(start_event).await?;

        self.tasks.insert(task_id.clone(), task.clone());

        self.enqueue_task(task.clone()).await?;

        self.audit_logger.log_operation(
            ctx,
            "inference.submit",
            "inference_task",
            &task_id,
            true,
            json!({
                "model_id": task.model_id,
                "priority": format!("{:?}", task.priority),
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(TaskResponse {
            task_id: task.task_id,
            model_id: task.model_id,
            model_version: task.model_version,
            version_id: task.version_id,
            status: task.status,
            priority: task.priority,
            created_at: task.created_at,
            started_at: task.started_at,
            completed_at: task.completed_at,
        })
    }

    pub async fn batch_inference(
        &self,
        ctx: &RequestContext,
        req: BatchInferenceRequest,
    ) -> AppResult<Vec<TaskResponse>> {
        let mut responses = Vec::new();
        for inference_req in req.requests {
            let resp = self.submit_inference(ctx, inference_req).await?;
            responses.push(resp);
        }
        Ok(responses)
    }

    pub async fn get_task(&self, ctx: &RequestContext, task_id: &str) -> AppResult<TaskResponse> {
        let start = std::time::Instant::now();
        debug!(task_id = %task_id, "Getting inference task");

        let task = self.tasks.get(task_id)
            .ok_or_else(|| AppError::NotFound(format!("推理任务不存在: {}", task_id)))?;

        self.audit_logger.log_operation(
            ctx,
            "inference.get_task",
            "inference_task",
            task_id,
            true,
            json!({ "status": format!("{:?}", task.status) }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(TaskResponse {
            task_id: task.task_id.clone(),
            model_id: task.model_id.clone(),
            model_version: task.model_version.clone(),
            version_id: task.version_id.clone(),
            status: task.status.clone(),
            priority: task.priority.clone(),
            created_at: task.created_at,
            started_at: task.started_at,
            completed_at: task.completed_at,
        })
    }

    pub async fn get_result(
        &self,
        ctx: &RequestContext,
        task_id: &str,
    ) -> AppResult<InferenceResultResponse> {
        let start = std::time::Instant::now();
        debug!(task_id = %task_id, "Getting inference result");

        let result = self.results.get(task_id)
            .ok_or_else(|| AppError::NotFound(format!("推理结果不存在: {}", task_id)))?;

        self.audit_logger.log_operation(
            ctx,
            "inference.get_result",
            "inference_result",
            task_id,
            true,
            json!({
                "success": result.success,
                "latency_ms": result.latency_ms,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(InferenceResultResponse {
            result_id: result.result_id.clone(),
            task_id: result.task_id.clone(),
            model_id: result.model_id.clone(),
            model_version: result.model_version.clone(),
            version_id: result.version_id.clone(),
            output_data: result.output_data.clone(),
            latency_ms: result.latency_ms,
            success: result.success,
            error_message: result.error_message.clone(),
            created_at: result.created_at,
            sync_status: result.sync_status.clone(),
        })
    }

    pub async fn list_tasks(
        &self,
        page: u32,
        page_size: u32,
        status: Option<TaskStatus>,
    ) -> AppResult<(Vec<TaskResponse>, u64)> {
        let items: Vec<TaskResponse> = self.tasks.iter()
            .filter(|t| status.as_ref().map(|s| &t.status == s).unwrap_or(true))
            .map(|t| TaskResponse {
                task_id: t.task_id.clone(),
                model_id: t.model_id.clone(),
                model_version: t.model_version.clone(),
                version_id: t.version_id.clone(),
                status: t.status.clone(),
                priority: t.priority.clone(),
                created_at: t.created_at,
                started_at: t.started_at,
                completed_at: t.completed_at,
            })
            .collect();

        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();

        Ok((paginated, total))
    }

    pub async fn get_statistics(
        &self,
        model_id: &str,
        time_window_seconds: u64,
    ) -> AppResult<InferenceStatistics> {
        let now = chrono::Utc::now();
        let window_start = now - chrono::Duration::seconds(time_window_seconds as i64);

        let relevant_results: Vec<&InferenceResult> = self.results.iter()
            .filter(|r| r.model_id == model_id && r.created_at >= window_start)
            .map(|r| r.value())
            .collect();

        let total_requests = relevant_results.len() as u64;
        let successful_requests = relevant_results.iter().filter(|r| r.success).count() as u64;
        let failed_requests = total_requests - successful_requests;

        let success_rate = if total_requests > 0 {
            successful_requests as f64 / total_requests as f64
        } else {
            0.0
        };

        let mut latencies: Vec<u64> = relevant_results.iter()
            .map(|r| r.latency_ms)
            .collect();
        latencies.sort();

        let avg_latency_ms = if !latencies.is_empty() {
            latencies.iter().sum::<u64>() as f64 / latencies.len() as f64
        } else {
            0.0
        };

        let p50 = if !latencies.is_empty() {
            latencies[latencies.len() / 2]
        } else {
            0
        };

        let p99 = if !latencies.is_empty() {
            let idx = (latencies.len() as f64 * 0.99) as usize;
            latencies[idx.min(latencies.len() - 1)]
        } else {
            0
        };

        let throughput = if time_window_seconds > 0 {
            total_requests as f64 / time_window_seconds as f64
        } else {
            0.0
        };

        Ok(InferenceStatistics {
            model_id: model_id.to_string(),
            time_window_seconds,
            total_requests,
            successful_requests,
            failed_requests,
            throughput,
            avg_latency_ms,
            p50_latency_ms: p50,
            p99_latency_ms: p99,
            success_rate,
            timestamp: now,
        })
    }

    pub fn get_metrics(&self) -> crate::common::metrics::StatsSnapshot {
        self.metrics.snapshot()
    }

    pub async fn cancel_task(&self, ctx: &RequestContext, task_id: &str) -> AppResult<()> {
        let mut task = self.tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("推理任务不存在: {}", task_id)))?;

        if matches!(task.status, TaskStatus::Completed | TaskStatus::Failed | TaskStatus::Cancelled) {
            return Err(AppError::Conflict(format!("任务已完成或取消，状态: {:?}", task.status)));
        }

        task.status = TaskStatus::Cancelled;
        task.completed_at = Some(chrono::Utc::now());

        self.audit_logger.log_operation(
            ctx,
            "inference.cancel",
            "inference_task",
            task_id,
            true,
            json!({}),
        );

        Ok(())
    }

    pub async fn undeploy_model(&self, ctx: &RequestContext, model_id: &str) -> AppResult<()> {
        let mut model = self.models.get_mut(model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", model_id)))?;

        if !model.is_deployed() {
            return Err(AppError::Conflict(format!("模型未部署，状态: {:?}", model.deployment_status)));
        }

        let device_id = model.deployed_device_id.clone().unwrap();
        self.release_resources(&device_id, &model.resource_requirements);

        model.deployment_status = ModelDeploymentStatus::Undeployed;
        model.deployed_device_id = None;
        model.deployed_at = None;
        model.updated_at = chrono::Utc::now();

        self.audit_logger.log_operation(
            ctx,
            "model.undeploy",
            "inference_model",
            model_id,
            true,
            json!({ "device_id": device_id }),
        );

        Ok(())
    }

    pub async fn delete_model(&self, ctx: &RequestContext, model_id: &str) -> AppResult<()> {
        let model = self.models.get(model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", model_id)))?;

        if model.is_deployed() {
            return Err(AppError::Conflict("请先取消部署模型".into()));
        }

        drop(model);
        self.models.remove(model_id);

        self.audit_logger.log_operation(
            ctx,
            "model.delete",
            "inference_model",
            model_id,
            true,
            json!({}),
        );

        Ok(())
    }

    fn validate_register_request(&self, req: &RegisterModelRequest) -> AppResult<()> {
        if req.name.is_empty() {
            return Err(AppError::Validation("模型名称不能为空".into()));
        }
        if req.version.is_empty() {
            return Err(AppError::Validation("模型版本不能为空".into()));
        }
        if req.framework.is_empty() {
            return Err(AppError::Validation("框架类型不能为空".into()));
        }
        if req.file_path.is_empty() {
            return Err(AppError::Validation("模型文件路径不能为空".into()));
        }
        if req.file_size_bytes == 0 {
            return Err(AppError::Validation("模型文件大小不能为0".into()));
        }
        Ok(())
    }

    fn allocate_resources(
        &self,
        device_id: &str,
        requirements: &super::model::ModelResourceRequirements,
    ) -> AppResult<()> {
        let mut device = self.device_resources.entry(device_id.to_string())
            .or_insert_with(|| DeviceResource {
                device_id: device_id.to_string(),
                cpu_cores_available: 8.0,
                memory_mb_available: 16384,
                gpu_available: true,
                gpu_memory_mb_available: 8192,
                running_tasks: Vec::new(),
            });

        if device.cpu_cores_available < requirements.cpu_cores {
            return Err(AppError::ServiceUnavailable(format!(
                "CPU资源不足，需要: {}, 可用: {}",
                requirements.cpu_cores, device.cpu_cores_available
            )));
        }
        if device.memory_mb_available < requirements.memory_mb {
            return Err(AppError::ServiceUnavailable(format!(
                "内存资源不足，需要: {}MB, 可用: {}MB",
                requirements.memory_mb, device.memory_mb_available
            )));
        }
        if requirements.gpu_required && !device.gpu_available {
            return Err(AppError::ServiceUnavailable("GPU资源不可用".into()));
        }
        if requirements.gpu_required && requirements.gpu_memory_mb.is_some() {
            let gpu_needed = requirements.gpu_memory_mb.unwrap();
            if device.gpu_memory_mb_available < gpu_needed {
                return Err(AppError::ServiceUnavailable(format!(
                    "GPU显存不足，需要: {}MB, 可用: {}MB",
                    gpu_needed, device.gpu_memory_mb_available
                )));
            }
            device.gpu_memory_mb_available -= gpu_needed;
        }

        device.cpu_cores_available -= requirements.cpu_cores;
        device.memory_mb_available -= requirements.memory_mb;

        Ok(())
    }

    fn release_resources(
        &self,
        device_id: &str,
        requirements: &super::model::ModelResourceRequirements,
    ) {
        if let Some(mut device) = self.device_resources.get_mut(device_id) {
            device.cpu_cores_available += requirements.cpu_cores;
            device.memory_mb_available += requirements.memory_mb;
            if requirements.gpu_required && requirements.gpu_memory_mb.is_some() {
                device.gpu_memory_mb_available += requirements.gpu_memory_mb.unwrap();
            }
        }
    }

    async fn enqueue_task(&self, task: InferenceTask) -> AppResult<()> {
        let cache_key = format!("inference_cache:{}:{}", task.model_id, hash_input(&task.input_data));

        if let Some(cached) = self.cache.get(&cache_key).await? {
            if let Ok(cached_result) = serde_json::from_str::<InferenceResult>(&cached) {
                info!(task_id = %task.task_id, "Cache hit, returning cached result");
                self.results.insert(task.task_id.clone(), cached_result);

                let mut task_mut = self.tasks.get_mut(&task.task_id).unwrap();
                task_mut.status = TaskStatus::Completed;
                task_mut.completed_at = Some(chrono::Utc::now());

                return Ok(());
            }
        }

        let queued = QueuedTask {
            task: task.clone(),
            queued_at: chrono::Utc::now(),
        };

        self.task_queue.lock().push(queued);

        if let Some(next_task) = self.task_queue.lock().pop() {
            self.task_sender.send(next_task.task).await
                .map_err(|e| AppError::Internal(format!("任务队列发送失败: {}", e)))?;
        }

        Ok(())
    }

    async fn execute_inference(&self, mut task: InferenceTask) -> AppResult<InferenceResult> {
        let start = std::time::Instant::now();
        let task_id = task.task_id.clone();
        let ctx = RequestContext::new_with_random();

        debug!(task_id = %task_id, model_id = %task.model_id, "Executing inference task");

        let model = self.models.get(&task.model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", task.model_id)))?;

        let device_id = model.deployed_device_id.clone().unwrap();
        drop(model);

        if let Some(mut task_mut) = self.tasks.get_mut(&task_id) {
            task_mut.schedule(device_id.clone());
            task_mut.start();
            task = task_mut.clone();
        }

        let timeout_duration = Duration::from_secs(task.timeout_seconds);
        let result = match timeout(timeout_duration, self.run_inference(&task)).await {
            Ok(Ok(output)) => {
                let latency_ms = start.elapsed().as_millis() as u64;
                let result = InferenceResult::success(&task, output, latency_ms);

                if let Some(mut task_mut) = self.tasks.get_mut(&task_id) {
                    task_mut.complete();
                }

                let complete_event = DomainEvent::new(
                    "inference.completed",
                    &task_id,
                    json!({
                        "task_id": task_id,
                        "model_id": task.model_id,
                        "latency_ms": latency_ms,
                        "success": true,
                    }),
                    &ctx.trace_id,
                );
                self.event_publisher.publish(complete_event).await?;

                result
            }
            Ok(Err(e)) => {
                let latency_ms = start.elapsed().as_millis() as u64;

                if task.should_retry() {
                    warn!(task_id = %task_id, error = %e, "Inference failed, retrying");
                    if let Some(mut task_mut) = self.tasks.get_mut(&task_id) {
                        task_mut.increment_retry();
                        let retried_task = task_mut.clone();
                        drop(task_mut);
                        return self.execute_inference(retried_task).await;
                    }
                }

                let result = InferenceResult::failure(&task, e.to_string(), latency_ms);

                if let Some(mut task_mut) = self.tasks.get_mut(&task_id) {
                    task_mut.fail();
                }

                let failed_event = DomainEvent::new(
                    "inference.failed",
                    &task_id,
                    json!({
                        "task_id": task_id,
                        "model_id": task.model_id,
                        "error": e.to_string(),
                        "retry_count": task.retry_count,
                    }),
                    &ctx.trace_id,
                );
                self.event_publisher.publish(failed_event).await?;

                result
            }
            Err(_) => {
                let latency_ms = start.elapsed().as_millis() as u64;

                if task.should_retry() {
                    warn!(task_id = %task_id, "Inference timed out, retrying");
                    if let Some(mut task_mut) = self.tasks.get_mut(&task_id) {
                        task_mut.increment_retry();
                        let retried_task = task_mut.clone();
                        drop(task_mut);
                        return self.execute_inference(retried_task).await;
                    }
                }

                let result = InferenceResult::failure(&task, "推理超时".into(), latency_ms);

                if let Some(mut task_mut) = self.tasks.get_mut(&task_id) {
                    task_mut.timeout();
                }

                let failed_event = DomainEvent::new(
                    "inference.failed",
                    &task_id,
                    json!({
                        "task_id": task_id,
                        "model_id": task.model_id,
                        "error": "timeout",
                        "timeout_seconds": task.timeout_seconds,
                    }),
                    &ctx.trace_id,
                );
                self.event_publisher.publish(failed_event).await?;

                result
            }
        };

        if result.success {
            let cache_key = format!("inference_cache:{}:{}", task.model_id, hash_input(&task.input_data));
            let cache_value = serde_json::to_string(&result)?;
            self.cache.set(&cache_key, &cache_value, Some(300)).await.ok();

            self.result_cache.lock().put(task_id.clone(), result.clone());
        }

        self.results.insert(task_id.clone(), result.clone());
        self.result_repository.save(&result).await.ok();

        self.sync_sender.send(result.clone()).await.ok();

        self.audit_logger.log_operation(
            &ctx,
            "inference.execute",
            "inference_task",
            &task_id,
            result.success,
            json!({
                "latency_ms": result.latency_ms,
                "model_id": task.model_id,
            }),
        );

        if result.success {
            self.metrics.record_success(result.latency_ms);
        } else {
            self.metrics.record_error(result.latency_ms);
        }

        Ok(result)
    }

    async fn run_inference(&self, task: &InferenceTask) -> AppResult<serde_json::Value> {
        debug!(task_id = %task.task_id, "Running simulated inference");

        tokio::time::sleep(Duration::from_millis(100)).await;

        let output = json!({
            "predictions": [
                {
                    "label": "class_0",
                    "confidence": 0.95,
                },
                {
                    "label": "class_1",
                    "confidence": 0.03,
                },
            ],
            "model_version": task.model_version,
            "inference_id": Uuid::new_v4().to_string(),
            "metadata": {
                "processing_time_ms": 85,
                "input_size": task.input_data.to_string().len(),
            },
        });

        Ok(output)
    }

    async fn sync_result_to_cloud(&self, mut result: InferenceResult) -> AppResult<()> {
        if !self.cloud_sync.is_online().await {
            warn!(result_id = %result.result_id, "Cloud is offline, skipping sync");
            return Ok(());
        }

        result.sync_status = SyncStatus::Syncing;
        self.results.insert(result.task_id.clone(), result.clone());

        let sync_data = json!({
            "result_id": result.result_id,
            "task_id": result.task_id,
            "model_id": result.model_id,
            "model_version": result.model_version,
            "output_data": result.output_data,
            "latency_ms": result.latency_ms,
            "success": result.success,
            "error_message": result.error_message,
            "created_at": result.created_at,
            "metadata": result.metadata,
        });

        match self.cloud_sync.upload_data(sync_data).await {
            Ok(_) => {
                result.mark_synced();
                self.results.insert(result.task_id.clone(), result.clone());
                info!(result_id = %result.result_id, "Result synced to cloud successfully");
                Ok(())
            }
            Err(e) => {
                result.mark_sync_failed();
                self.results.insert(result.task_id.clone(), result.clone());
                Err(e)
            }
        }
    }

    async fn check_timeouts(&self) {
        let now = chrono::Utc::now();
        let mut timed_out_tasks = Vec::new();

        for task in self.tasks.iter() {
            if task.status == TaskStatus::Running {
                if let Some(started_at) = task.started_at {
                    let elapsed = (now - started_at).num_seconds() as u64;
                    if elapsed > task.timeout_seconds {
                        timed_out_tasks.push(task.task_id.clone());
                    }
                }
            }
        }

        for task_id in timed_out_tasks {
            warn!(task_id = %task_id, "Task timeout detected by watchdog");
            if let Some(mut task) = self.tasks.get_mut(&task_id) {
                task.timeout();
            }
        }
    }

    async fn retry_failed_syncs(&self) {
        let mut to_retry = Vec::new();

        for result in self.results.iter() {
            if result.needs_sync() {
                to_retry.push(result.clone());
            }
        }

        for result in to_retry {
            debug!(result_id = %result.result_id, "Retrying cloud sync");
            if let Err(e) = self.sync_result_to_cloud(result).await {
                warn!(error = %e, "Retry sync failed");
            }
        }
    }

    pub async fn create_version(
        &self,
        ctx: &RequestContext,
        req: CreateVersionRequest,
    ) -> AppResult<ModelVersionResponse> {
        let start = std::time::Instant::now();
        debug!(model_id = %req.model_id, version = %req.version, "Creating new model version");

        let mut model = self.models.get_mut(&req.model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", req.model_id)))?;

        let semantic_version = SemanticVersion::parse(&req.version)
            .ok_or_else(|| AppError::Validation(format!("无效的语义版本格式: {}", req.version)))?;

        for existing in &model.versions {
            if existing.version == req.version {
                return Err(AppError::Conflict(format!("版本 {} 已存在", req.version)));
            }
        }

        let created_by = ctx.auth.as_ref()
            .map(|a| a.device_id.clone())
            .unwrap_or_else(|| "system".into());

        let version = ModelVersion {
            version_id: Uuid::new_v4().to_string(),
            model_id: req.model_id.clone(),
            version: req.version.clone(),
            semantic_version,
            status: req.status.clone().unwrap_or(VersionStatus::Stable),
            description: req.description.clone(),
            file_path: req.file_path.clone(),
            file_size_bytes: req.file_size_bytes,
            checksum: req.checksum.clone(),
            checksum_algorithm: req.checksum_algorithm.clone().unwrap_or_else(|| "sha256".to_string()),
            compatible_runtimes: req.compatible_runtimes.clone().unwrap_or_default(),
            minimum_runtime_version: req.minimum_runtime_version.clone(),
            dependencies: req.dependencies.clone().unwrap_or_default(),
            release_notes: req.release_notes.clone(),
            created_by,
            created_at: Utc::now(),
            deployed_at: None,
            deployment_status: ModelDeploymentStatus::Pending,
            deployed_device_id: None,
            performance_metrics: std::collections::HashMap::new(),
            tags: req.tags.clone().unwrap_or_default(),
            is_latest: true,
            is_default: false,
        };

        let version_id = version.version_id.clone();
        model.add_version(version);
        let model_clone = model.clone();
        drop(model);

        let version = model_clone.get_version(&version_id).unwrap().clone();

        let event = DomainEvent::new(
            "model.version.created",
            &req.model_id,
            json!({
                "model_id": req.model_id,
                "version_id": version_id,
                "version": version.version,
                "status": format!("{:?}", version.status),
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "model.version.create",
            "inference_model",
            &req.model_id,
            true,
            json!({
                "version_id": version_id,
                "version": version.version,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(ModelVersionResponse {
            version_id: version.version_id,
            model_id: version.model_id,
            version: version.version,
            status: version.status,
            description: version.description,
            file_size_bytes: version.file_size_bytes,
            created_at: version.created_at,
            deployed_at: version.deployed_at,
            deployment_status: version.deployment_status,
            deployed_device_id: version.deployed_device_id,
            tags: version.tags,
            is_latest: version.is_latest,
            is_default: version.is_default,
        })
    }

    pub async fn list_versions(
        &self,
        model_id: &str,
    ) -> AppResult<Vec<ModelVersionResponse>> {
        let model = self.models.get(model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", model_id)))?;

        let versions: Vec<ModelVersionResponse> = model.versions.iter()
            .map(|v| ModelVersionResponse {
                version_id: v.version_id.clone(),
                model_id: v.model_id.clone(),
                version: v.version.clone(),
                status: v.status.clone(),
                description: v.description.clone(),
                file_size_bytes: v.file_size_bytes,
                created_at: v.created_at,
                deployed_at: v.deployed_at,
                deployment_status: v.deployment_status.clone(),
                deployed_device_id: v.deployed_device_id.clone(),
                tags: v.tags.clone(),
                is_latest: v.is_latest,
                is_default: v.is_default,
            })
            .collect();

        Ok(versions)
    }

    pub async fn get_version(
        &self,
        model_id: &str,
        version_id: &str,
    ) -> AppResult<ModelVersionResponse> {
        let model = self.models.get(model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", model_id)))?;

        let version = model.get_version(version_id)
            .ok_or_else(|| AppError::NotFound(format!("版本不存在: {}", version_id)))?;

        Ok(ModelVersionResponse {
            version_id: version.version_id.clone(),
            model_id: version.model_id.clone(),
            version: version.version.clone(),
            status: version.status.clone(),
            description: version.description.clone(),
            file_size_bytes: version.file_size_bytes,
            created_at: version.created_at,
            deployed_at: version.deployed_at,
            deployment_status: version.deployment_status.clone(),
            deployed_device_id: version.deployed_device_id.clone(),
            tags: version.tags.clone(),
            is_latest: version.is_latest,
            is_default: version.is_default,
        })
    }

    pub async fn update_version(
        &self,
        ctx: &RequestContext,
        model_id: &str,
        version_id: &str,
        req: UpdateVersionRequest,
    ) -> AppResult<ModelVersionResponse> {
        let mut model = self.models.get_mut(model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", model_id)))?;

        let version = model.versions.iter_mut()
            .find(|v| v.version_id == version_id)
            .ok_or_else(|| AppError::NotFound(format!("版本不存在: {}", version_id)))?;

        if let Some(status) = req.status {
            version.status = status;
        }
        if let Some(description) = req.description {
            version.description = Some(description);
        }
        if let Some(release_notes) = req.release_notes {
            version.release_notes = Some(release_notes);
        }
        if let Some(tags) = req.tags {
            version.tags = tags;
        }
        if let Some(is_default) = req.is_default {
            if is_default {
                model.set_default_version(version_id);
            }
        }

        let version_clone = version.clone();
        drop(model);

        let event = DomainEvent::new(
            "model.version.updated",
            model_id,
            json!({
                "version_id": version_id,
                "status": format!("{:?}", version_clone.status),
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "model.version.update",
            "inference_model",
            model_id,
            true,
            json!({
                "version_id": version_id,
            }),
        );

        Ok(ModelVersionResponse {
            version_id: version_clone.version_id,
            model_id: version_clone.model_id,
            version: version_clone.version,
            status: version_clone.status,
            description: version_clone.description,
            file_size_bytes: version_clone.file_size_bytes,
            created_at: version_clone.created_at,
            deployed_at: version_clone.deployed_at,
            deployment_status: version_clone.deployment_status,
            deployed_device_id: version_clone.deployed_device_id,
            tags: version_clone.tags,
            is_latest: version_clone.is_latest,
            is_default: version_clone.is_default,
        })
    }

    pub async fn delete_version(
        &self,
        ctx: &RequestContext,
        model_id: &str,
        version_id: &str,
    ) -> AppResult<()> {
        let mut model = self.models.get_mut(model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", model_id)))?;

        let version_index = model.versions.iter()
            .position(|v| v.version_id == version_id)
            .ok_or_else(|| AppError::NotFound(format!("版本不存在: {}", version_id)))?;

        let version = &model.versions[version_index];
        if version.deployment_status == ModelDeploymentStatus::Deployed {
            return Err(AppError::Conflict("不能删除已部署的版本".into()));
        }

        if version.is_default {
            return Err(AppError::Conflict("不能删除默认版本，请先设置其他版本为默认".into()));
        }

        model.versions.remove(version_index);
        drop(model);

        let event = DomainEvent::new(
            "model.version.deleted",
            model_id,
            json!({
                "version_id": version_id,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "model.version.delete",
            "inference_model",
            model_id,
            true,
            json!({
                "version_id": version_id,
            }),
        );

        Ok(())
    }

    pub async fn set_default_version(
        &self,
        ctx: &RequestContext,
        model_id: &str,
        version_id: &str,
    ) -> AppResult<()> {
        let mut model = self.models.get_mut(model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", model_id)))?;

        if !model.set_default_version(version_id) {
            return Err(AppError::NotFound(format!("版本不存在: {}", version_id)));
        }

        drop(model);

        let event = DomainEvent::new(
            "model.version.default_set",
            model_id,
            json!({
                "version_id": version_id,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "model.version.set_default",
            "inference_model",
            model_id,
            true,
            json!({
                "version_id": version_id,
            }),
        );

        Ok(())
    }

    pub async fn compare_versions(
        &self,
        model_id: &str,
        from_version_id: &str,
        to_version_id: &str,
    ) -> AppResult<super::model::ModelVersionDiff> {
        let model = self.models.get(model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", model_id)))?;

        let from_version = model.get_version(from_version_id)
            .ok_or_else(|| AppError::NotFound(format!("源版本不存在: {}", from_version_id)))?;

        let to_version = model.get_version(to_version_id)
            .ok_or_else(|| AppError::NotFound(format!("目标版本不存在: {}", to_version_id)))?;

        let mut changed_fields = Vec::new();
        if from_version.version != to_version.version {
            changed_fields.push("version".to_string());
        }
        if from_version.file_path != to_version.file_path {
            changed_fields.push("file_path".to_string());
        }
        if from_version.file_size_bytes != to_version.file_size_bytes {
            changed_fields.push("file_size_bytes".to_string());
        }
        if from_version.checksum != to_version.checksum {
            changed_fields.push("checksum".to_string());
        }
        if from_version.dependencies != to_version.dependencies {
            changed_fields.push("dependencies".to_string());
        }

        let compatibility = if from_version.semantic_version.major != to_version.semantic_version.major {
            super::model::VersionCompatibility::Incompatible
        } else if from_version.semantic_version.minor != to_version.semantic_version.minor {
            super::model::VersionCompatibility::RequiresUpgrade
        } else if from_version.semantic_version.patch != to_version.semantic_version.patch {
            super::model::VersionCompatibility::Compatible
        } else {
            super::model::VersionCompatibility::Compatible
        };

        Ok(super::model::ModelVersionDiff {
            model_id: model_id.to_string(),
            from_version: from_version.version.clone(),
            to_version: to_version.version.clone(),
            changed_fields,
            compatibility,
            breaking_changes: Vec::new(),
            performance_impact: None,
        })
    }

    pub async fn check_version_compatibility(
        &self,
        model_id: &str,
        version_id: &str,
        device_id: &str,
    ) -> AppResult<super::model::VersionCompatibilityCheck> {
        let model = self.models.get(model_id)
            .ok_or_else(|| AppError::NotFound(format!("模型不存在: {}", model_id)))?;

        let version = model.get_version(version_id)
            .ok_or_else(|| AppError::NotFound(format!("版本不存在: {}", version_id)))?;

        let mut check = super::model::VersionCompatibilityCheck {
            model_id: model_id.to_string(),
            version: version.version.clone(),
            device_id: device_id.to_string(),
            compatible: true,
            issues: Vec::new(),
            required_upgrades: Vec::new(),
        };

        if version.status == VersionStatus::Deprecated {
            check.compatible = false;
            check.issues.push("该版本已被弃用".to_string());
        }

        if version.status == VersionStatus::Archived {
            check.compatible = false;
            check.issues.push("该版本已归档".to_string());
        }

        Ok(check)
    }
}

fn hash_input(input: &serde_json::Value) -> String {
    use sha2::{Sha256, Digest};
    let mut hasher = Sha256::new();
    hasher.update(input.to_string());
    format!("{:x}", hasher.finalize())
}
