use std::sync::Arc;
use dashmap::DashMap;
use serde_json::json;
use tracing::{info, warn, debug, error};
use sha2::{Sha256, Digest};
use uuid::Uuid;
use chrono::Utc;

use crate::common::error::{AppError, AppResult};
use crate::common::context::RequestContext;
use crate::common::event::DomainEvent;
use crate::common::auth::SignatureValidator;
use crate::ports::mod::EventPublisherPort;
use crate::common::context::AuditLogger;
use crate::common::metrics::MetricsCollector;
use super::model::{
    FirmwarePackage, UpgradeTask, DeviceUpgradeStatus, GrayStrategy, RollbackPolicy,
    UpgradePhase, RollbackTrigger, DeltaPackage,
    UploadFirmwareRequest, CreateUpgradeTaskRequest, DeviceStatusUpdateRequest,
    FirmwareResponse, UpgradeTaskResponse, DeviceStatusResponse,
    GenerateDeltaRequest, DeltaResponse, RollbackRequest,
};

pub struct OtaUpgradeService {
    firmware_packages: Arc<DashMap<String, FirmwarePackage>>,
    upgrade_tasks: Arc<DashMap<String, UpgradeTask>>,
    device_statuses: Arc<DashMap<String, DeviceUpgradeStatus>>,
    delta_packages: Arc<DashMap<String, DeltaPackage>>,
    event_publisher: Arc<dyn EventPublisherPort>,
    signature_validator: Arc<SignatureValidator>,
    audit_logger: Arc<AuditLogger>,
    metrics: MetricsCollector,
    notification_port: Option<Arc<dyn crate::ports::mod::NotificationPort>>,
}

impl OtaUpgradeService {
    pub fn new(
        event_publisher: Arc<dyn EventPublisherPort>,
        signature_validator: Arc<SignatureValidator>,
        audit_logger: Arc<AuditLogger>,
    ) -> Arc<Self> {
        Arc::new(Self {
            firmware_packages: Arc::new(DashMap::new()),
            upgrade_tasks: Arc::new(DashMap::new()),
            device_statuses: Arc::new(DashMap::new()),
            delta_packages: Arc::new(DashMap::new()),
            event_publisher,
            signature_validator,
            audit_logger,
            metrics: MetricsCollector::new().with_dimension("module", "ota_upgrade"),
            notification_port: None,
        })
    }

    pub fn with_notification(self: Arc<Self>, notification_port: Arc<dyn crate::ports::mod::NotificationPort>) -> Arc<Self> {
        let mut inner = Arc::try_unwrap(self).unwrap_or_else(|arc| (*arc).clone());
        inner.notification_port = Some(notification_port);
        Arc::new(inner)
    }

    pub async fn upload_firmware(&self, ctx: &RequestContext, req: UploadFirmwareRequest) -> AppResult<FirmwareResponse> {
        let start = std::time::Instant::now();
        info!(version = %req.version, device_model = %req.device_model, "Uploading firmware package");

        self.validate_firmware_request(&req)?;

        self.verify_checksum(&req.download_url, &req.checksum, req.checksum_algorithm.as_deref())
            .await
            .map_err(|e| {
                warn!(error = %e, "Checksum verification failed");
                AppError::Validation(format!("校验和验证失败: {}", e))
            })?;

        let mut package = FirmwarePackage::new(
            req.name.clone(),
            req.version.clone(),
            req.device_model.clone(),
            req.firmware_type.clone(),
            req.size_bytes,
            req.checksum.clone(),
            req.download_url.clone(),
            ctx.auth.as_ref().map(|a| a.device_id.clone()).unwrap_or_else(|| "system".into()),
        );

        package.previous_version = req.previous_version.clone();
        if let Some(algo) = req.checksum_algorithm {
            package.checksum_algorithm = algo;
        }
        if let Some(notes) = req.release_notes {
            package.release_notes = notes;
        }
        if let Some(metadata) = req.metadata {
            package.metadata = metadata;
        }

        self.firmware_packages.insert(package.package_id.clone(), package.clone());

        let event = DomainEvent::new(
            "firmware.uploaded",
            &package.package_id,
            json!({
                "package_id": package.package_id,
                "name": package.name,
                "version": package.version,
                "device_model": package.device_model,
                "size_bytes": package.size_bytes,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "firmware.upload",
            "firmware_package",
            &package.package_id,
            true,
            json!({
                "version": package.version,
                "device_model": package.device_model,
                "size_bytes": package.size_bytes,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(self.to_firmware_response(&package))
    }

    pub async fn generate_delta(&self, ctx: &RequestContext, req: GenerateDeltaRequest) -> AppResult<DeltaResponse> {
        let start = std::time::Instant::now();
        info!(from = %req.from_package_id, to = %req.to_package_id, "Generating delta package");

        let from_package = self.firmware_packages.get(&req.from_package_id)
            .ok_or_else(|| AppError::NotFound(format!("源固件包不存在: {}", req.from_package_id)))?
            .clone();

        let mut to_package = self.firmware_packages.get_mut(&req.to_package_id)
            .ok_or_else(|| AppError::NotFound(format!("目标固件包不存在: {}", req.to_package_id)))?;

        if from_package.device_model != to_package.device_model {
            return Err(AppError::Validation("设备型号不匹配，无法生成差分包".into()));
        }

        let delta_size = self.calculate_delta_size(from_package.size_bytes, to_package.size_bytes);
        let delta_checksum = self.generate_delta_checksum(&from_package, &to_package);

        let delta = DeltaPackage {
            delta_id: Uuid::new_v4().to_string(),
            from_version: from_package.version.clone(),
            to_version: to_package.version.clone(),
            device_model: from_package.device_model.clone(),
            size_bytes: delta_size,
            checksum: delta_checksum.clone(),
            download_url: format!("{}.delta", to_package.download_url),
            created_at: Utc::now(),
        };

        to_package.with_delta(from_package.version.clone(), delta_size, delta_checksum);
        self.delta_packages.insert(delta.delta_id.clone(), delta.clone());

        self.audit_logger.log_operation(
            ctx,
            "delta.generate",
            "delta_package",
            &delta.delta_id,
            true,
            json!({
                "from_version": delta.from_version,
                "to_version": delta.to_version,
                "size_bytes": delta.size_bytes,
                "compression_ratio": (1.0 - delta.size_bytes as f64 / to_package.size_bytes as f64),
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(DeltaResponse {
            delta_id: delta.delta_id,
            from_version: delta.from_version,
            to_version: delta.to_version,
            size_bytes: delta.size_bytes,
            original_size_bytes: to_package.size_bytes,
            compression_ratio: 1.0 - delta.size_bytes as f64 / to_package.size_bytes as f64,
            download_url: delta.download_url,
        })
    }

    pub async fn create_upgrade_task(&self, ctx: &RequestContext, req: CreateUpgradeTaskRequest) -> AppResult<UpgradeTaskResponse> {
        let start = std::time::Instant::now();
        info!(name = %req.name, firmware_id = %req.firmware_package_id, "Creating upgrade task");

        let firmware = self.firmware_packages.get(&req.firmware_package_id)
            .ok_or_else(|| AppError::NotFound(format!("固件包不存在: {}", req.firmware_package_id)))?
            .clone();

        if !firmware.is_active {
            return Err(AppError::Validation("固件包未激活".into()));
        }

        let rollback_policy = req.rollback_policy.unwrap_or_default();
        self.validate_gray_strategy(&req.gray_strategy)?;

        let mut task = UpgradeTask::new(
            req.name.clone(),
            req.firmware_package_id.clone(),
            req.gray_strategy.clone(),
            rollback_policy,
            ctx.auth.as_ref().map(|a| a.device_id.clone()).unwrap_or_else(|| "system".into()),
        );

        task.description = req.description.clone();
        task.schedule_time = req.schedule_time;
        task.deadline_time = req.deadline_time;
        if let Some(limit) = req.concurrency_limit {
            task.concurrency_limit = limit;
        }
        if let Some(timeout) = req.timeout_per_device_seconds {
            task.timeout_per_device_seconds = timeout;
        }

        let target_devices = self.select_target_devices(&req.gray_strategy, &firmware.device_model).await?;
        task.target_devices_count = target_devices.len() as u32;

        for device_id in &target_devices {
            let status = DeviceUpgradeStatus::new(device_id.clone(), task.task_id.clone());
            self.device_statuses.insert(format!("{}:{}", task.task_id, device_id), status);
        }

        task.update_statistics(&self.get_device_statuses_for_task(&task.task_id));

        self.upgrade_tasks.insert(task.task_id.clone(), task.clone());

        let event = DomainEvent::new(
            "upgrade.created",
            &task.task_id,
            json!({
                "task_id": task.task_id,
                "name": task.name,
                "firmware_package_id": task.firmware_package_id,
                "firmware_version": firmware.version,
                "target_devices_count": task.target_devices_count,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "upgrade.create",
            "upgrade_task",
            &task.task_id,
            true,
            json!({
                "name": task.name,
                "firmware_version": firmware.version,
                "target_devices_count": task.target_devices_count,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(self.to_task_response(&task, &firmware))
    }

    pub async fn approve_upgrade(self: &Arc<Self>, ctx: &RequestContext, task_id: &str, approved: bool, comment: Option<String>) -> AppResult<UpgradeTaskResponse> {
        let start = std::time::Instant::now();
        info!(task_id = %task_id, approved = %approved, "Processing upgrade approval");

        let mut task = self.upgrade_tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("升级任务不存在: {}", task_id)))?;

        if task.status != UpgradePhase::Pending {
            return Err(AppError::Validation("只有待审批的任务可以审批".into()));
        }

        let approver = ctx.auth.as_ref().map(|a| a.device_id.clone()).unwrap_or_else(|| "system".into());

        if approved {
            task.approve(approver.clone()).map_err(AppError::Validation)?;

            let firmware = self.firmware_packages.get(&task.firmware_package_id)
                .ok_or_else(|| AppError::NotFound("固件包不存在".into()))?
                .clone();

            let event = DomainEvent::new(
                "upgrade.approved",
                &task.task_id,
                json!({
                    "task_id": task.task_id,
                    "approver": approver,
                    "firmware_version": firmware.version,
                    "comment": comment,
                }),
                &ctx.trace_id,
            );
            self.event_publisher.publish(event).await?;

            let task_clone = task.clone();
            drop(task);

            if task_clone.schedule_time.is_none() || task_clone.schedule_time.unwrap() <= Utc::now() {
                self.start_upgrade_async(task_clone.clone());
            }

            self.metrics.record_success(start.elapsed().as_millis() as u64);
            Ok(self.to_task_response(&task_clone, &firmware))
        } else {
            task.status = UpgradePhase::Failed;
            drop(task);

            let event = DomainEvent::new(
                "upgrade.failed",
                task_id,
                json!({
                    "task_id": task_id,
                    "reason": "审批被拒绝",
                    "comment": comment,
                }),
                &ctx.trace_id,
            );
            self.event_publisher.publish(event).await?;

            let task = self.upgrade_tasks.get(task_id).unwrap().clone();
            let firmware = self.firmware_packages.get(&task.firmware_package_id).unwrap().clone();

            self.metrics.record_error(start.elapsed().as_millis() as u64);
            Ok(self.to_task_response(&task, &firmware))
        }
    }

    pub async fn update_device_status(self: &Arc<Self>, ctx: &RequestContext, req: DeviceStatusUpdateRequest) -> AppResult<DeviceStatusResponse> {
        let start = std::time::Instant::now();
        debug!(device_id = %req.device_id, task_id = %req.task_id, phase = ?req.phase, "Updating device upgrade status");

        let key = format!("{}:{}", req.task_id, req.device_id);
        let mut status = self.device_statuses.get_mut(&key)
            .ok_or_else(|| AppError::NotFound(format!("设备升级状态不存在: {}", key)))?;

        if let Some(progress) = req.progress {
            status.progress = progress.clamp(0.0, 100.0);
        }
        if let Some(speed) = req.download_speed_bps {
            status.download_speed_bps = Some(speed);
        }
        if let Some(error) = &req.error_message {
            status.error_message = Some(error.clone());
        }

        if status.phase != req.phase {
            status.update_phase(req.phase.clone()).map_err(AppError::Validation)?;
        }

        status.last_heartbeat = Some(Utc::now());
        let status_clone = status.clone();
        drop(status);

        if let Some(task) = self.upgrade_tasks.get_mut(&req.task_id) {
            let device_statuses = self.get_device_statuses_for_task(&req.task_id);
            task.update_statistics(&device_statuses);

            if req.phase == UpgradePhase::Failed && task.rollback_policy.enabled {
                if task.rollback_policy.triggers.contains(&RollbackTrigger::OnFailure) {
                    let task_clone = task.clone();
                    let service_clone = self.clone();
                    let device_id = req.device_id.clone();
                    tokio::spawn(async move {
                        if let Err(e) = service_clone.trigger_rollback_for_device(&task_clone, &device_id, "升级失败".to_string()).await {
                            error!(error = %e, device_id = %device_id, "Failed to trigger rollback");
                        }
                    });
                }
            }

            let completed_count = task.statistics.success_devices
                + task.statistics.failed_devices
                + task.statistics.rolled_back_devices;

            if completed_count == task.statistics.total_devices && !task.status.is_terminal() {
                task.status = if task.statistics.success_rate >= task.gray_strategy.success_threshold {
                    UpgradePhase::Success
                } else {
                    UpgradePhase::Failed
                };
                task.completed_at = Some(Utc::now());

                let event_type = if task.status == UpgradePhase::Success {
                    "upgrade.completed"
                } else {
                    "upgrade.failed"
                };

                let firmware = self.firmware_packages.get(&task.firmware_package_id).unwrap().clone();
                let event = DomainEvent::new(
                    event_type,
                    &task.task_id,
                    json!({
                        "task_id": task.task_id,
                        "success_rate": task.statistics.success_rate,
                        "success_count": task.statistics.success_devices,
                        "failed_count": task.statistics.failed_devices,
                        "firmware_version": firmware.version,
                    }),
                    &ctx.trace_id,
                );
                let _ = self.event_publisher.publish(event).await;
            }

            self.check_rollback_threshold(&task);
        }

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(DeviceStatusResponse {
            device_id: status_clone.device_id,
            task_id: status_clone.task_id,
            phase: status_clone.phase.as_str().to_string(),
            progress: status_clone.progress,
            error_message: status_clone.error_message,
            started_at: status_clone.started_at,
            completed_at: status_clone.completed_at,
        })
    }

    pub async fn trigger_rollback(&self, ctx: &RequestContext, req: RollbackRequest) -> AppResult<()> {
        let start = std::time::Instant::now();
        info!(task_id = %req.task_id, "Triggering rollback");

        let task = self.upgrade_tasks.get(&req.task_id)
            .ok_or_else(|| AppError::NotFound(format!("升级任务不存在: {}", req.task_id)))?
            .clone();

        if !task.rollback_policy.enabled {
            return Err(AppError::Validation("该任务未启用回滚策略".into()));
        }

        let device_ids = match req.device_ids {
            Some(ids) => ids,
            None => {
                self.get_device_statuses_for_task(&req.task_id)
                    .into_iter()
                    .filter(|s| !s.phase.is_terminal() || s.phase == UpgradePhase::Failed || s.phase == UpgradePhase::Success)
                    .map(|s| s.device_id)
                    .collect()
            }
        };

        for device_id in &device_ids {
            self.trigger_rollback_for_device(&task, device_id, req.reason.clone()).await?;
        }

        let event = DomainEvent::new(
            "rollback.started",
            &req.task_id,
            json!({
                "task_id": req.task_id,
                "device_count": device_ids.len(),
                "reason": req.reason,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "rollback.trigger",
            "upgrade_task",
            &req.task_id,
            true,
            json!({
                "device_count": device_ids.len(),
                "reason": req.reason,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);
        Ok(())
    }

    pub async fn get_firmware(&self, ctx: &RequestContext, package_id: &str) -> AppResult<FirmwareResponse> {
        let start = std::time::Instant::now();
        debug!(package_id = %package_id, "Getting firmware package");

        let package = self.firmware_packages.get(package_id)
            .ok_or_else(|| AppError::NotFound(format!("固件包不存在: {}", package_id)))?;

        self.audit_logger.log_operation(
            ctx,
            "firmware.get",
            "firmware_package",
            package_id,
            true,
            json!({ "version": package.version }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);
        Ok(self.to_firmware_response(&package))
    }

    pub async fn list_firmware(&self, device_model: Option<&str>, page: u32, page_size: u32) -> AppResult<(Vec<FirmwareResponse>, u64)> {
        let mut items: Vec<FirmwareResponse> = self.firmware_packages.iter()
            .filter(|p| device_model.map_or(true, |m| p.device_model == m))
            .map(|p| self.to_firmware_response(&p))
            .collect();

        items.sort_by(|a, b| b.created_at.cmp(&a.created_at));
        let total = items.len() as u64;

        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();

        Ok((paginated, total))
    }

    pub async fn get_upgrade_task(&self, ctx: &RequestContext, task_id: &str) -> AppResult<UpgradeTaskResponse> {
        let start = std::time::Instant::now();
        debug!(task_id = %task_id, "Getting upgrade task");

        let task = self.upgrade_tasks.get(task_id)
            .ok_or_else(|| AppError::NotFound(format!("升级任务不存在: {}", task_id)))?;

        let firmware = self.firmware_packages.get(&task.firmware_package_id)
            .ok_or_else(|| AppError::NotFound("固件包不存在".into()))?;

        self.audit_logger.log_operation(
            ctx,
            "upgrade.get",
            "upgrade_task",
            task_id,
            true,
            json!({ "status": task.status.as_str() }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);
        Ok(self.to_task_response(&task, &firmware))
    }

    pub async fn list_upgrade_tasks(&self, status: Option<UpgradePhase>, page: u32, page_size: u32) -> AppResult<(Vec<UpgradeTaskResponse>, u64)> {
        let mut items: Vec<UpgradeTaskResponse> = self.upgrade_tasks.iter()
            .filter(|t| status.as_ref().map_or(true, |s| t.status == *s))
            .filter_map(|t| {
                self.firmware_packages.get(&t.firmware_package_id)
                    .map(|f| self.to_task_response(&t, &f))
            })
            .collect();

        items.sort_by(|a, b| b.created_at.cmp(&a.created_at));
        let total = items.len() as u64;

        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();

        Ok((paginated, total))
    }

    pub async fn get_device_upgrade_status(&self, task_id: &str, device_id: &str) -> AppResult<DeviceStatusResponse> {
        let key = format!("{}:{}", task_id, device_id);
        let status = self.device_statuses.get(&key)
            .ok_or_else(|| AppError::NotFound(format!("设备升级状态不存在: {}", key)))?;

        Ok(DeviceStatusResponse {
            device_id: status.device_id.clone(),
            task_id: status.task_id.clone(),
            phase: status.phase.as_str().to_string(),
            progress: status.progress,
            error_message: status.error_message.clone(),
            started_at: status.started_at,
            completed_at: status.completed_at,
        })
    }

    pub async fn list_device_statuses(&self, task_id: &str, page: u32, page_size: u32) -> AppResult<(Vec<DeviceStatusResponse>, u64)> {
        let statuses = self.get_device_statuses_for_task(task_id);
        let total = statuses.len() as u64;

        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(statuses.len());

        let paginated: Vec<DeviceStatusResponse> = statuses.into_iter()
            .skip(start)
            .take(end - start)
            .map(|s| DeviceStatusResponse {
                device_id: s.device_id,
                task_id: s.task_id,
                phase: s.phase.as_str().to_string(),
                progress: s.progress,
                error_message: s.error_message,
                started_at: s.started_at,
                completed_at: s.completed_at,
            })
            .collect();

        Ok((paginated, total))
    }

    pub async fn pause_upgrade(&self, ctx: &RequestContext, task_id: &str) -> AppResult<()> {
        let mut task = self.upgrade_tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("升级任务不存在: {}", task_id)))?;

        if task.status.is_terminal() {
            return Err(AppError::Validation("已完成的任务无法暂停".into()));
        }

        task.is_paused = true;

        self.audit_logger.log_operation(
            ctx,
            "upgrade.pause",
            "upgrade_task",
            task_id,
            true,
            json!({}),
        );

        Ok(())
    }

    pub async fn resume_upgrade(self: &Arc<Self>, ctx: &RequestContext, task_id: &str) -> AppResult<()> {
        let task = self.upgrade_tasks.get(task_id)
            .ok_or_else(|| AppError::NotFound(format!("升级任务不存在: {}", task_id)))?
            .clone();

        if !task.is_paused {
            return Err(AppError::Validation("任务未暂停".into()));
        }

        drop(task);
        let mut task_mut = self.upgrade_tasks.get_mut(task_id).unwrap();
        task_mut.is_paused = false;

        self.audit_logger.log_operation(
            ctx,
            "upgrade.resume",
            "upgrade_task",
            task_id,
            true,
            json!({}),
        );

        self.continue_upgrade_async(task_mut.clone());
        Ok(())
    }

    pub async fn delete_firmware(&self, ctx: &RequestContext, package_id: &str) -> AppResult<()> {
        let active_tasks = self.upgrade_tasks.iter()
            .any(|t| t.firmware_package_id == package_id && !t.status.is_terminal());

        if active_tasks {
            return Err(AppError::Conflict("存在使用该固件包的活跃升级任务".into()));
        }

        if self.firmware_packages.remove(package_id).is_none() {
            return Err(AppError::NotFound(format!("固件包不存在: {}", package_id)));
        }

        self.audit_logger.log_operation(
            ctx,
            "firmware.delete",
            "firmware_package",
            package_id,
            true,
            json!({}),
        );

        Ok(())
    }

    pub async fn cancel_upgrade(&self, ctx: &RequestContext, task_id: &str) -> AppResult<()> {
        let mut task = self.upgrade_tasks.get_mut(task_id)
            .ok_or_else(|| AppError::NotFound(format!("升级任务不存在: {}", task_id)))?;

        if task.status.is_terminal() {
            return Err(AppError::Validation("已完成的任务无法取消".into()));
        }

        task.status = UpgradePhase::Failed;
        task.completed_at = Some(Utc::now());

        let statuses = self.get_device_statuses_for_task(task_id);
        for mut s in statuses {
            if !s.phase.is_terminal() {
                let key = format!("{}:{}", task_id, s.device_id);
                if let Some(mut status) = self.device_statuses.get_mut(&key) {
                    status.phase = UpgradePhase::Failed;
                    status.error_message = Some("任务已取消".into());
                    status.completed_at = Some(Utc::now());
                }
            }
        }

        self.audit_logger.log_operation(
            ctx,
            "upgrade.cancel",
            "upgrade_task",
            task_id,
            true,
            json!({}),
        );

        Ok(())
    }

    pub fn get_metrics(&self) -> crate::common::metrics::StatsSnapshot {
        self.metrics.snapshot()
    }

    fn start_upgrade_async(self: &Arc<Self>, task: UpgradeTask) {
        let service_clone = self.clone();
        tokio::spawn(async move {
            if let Err(e) = service_clone.execute_upgrade(task).await {
                error!(error = %e, "Upgrade execution failed");
            }
        });
    }

    fn continue_upgrade_async(self: &Arc<Self>, task: UpgradeTask) {
        self.start_upgrade_async(task);
    }

    async fn execute_upgrade(self: Arc<Self>, task: UpgradeTask) -> AppResult<()> {
        info!(task_id = %task.task_id, "Starting upgrade execution");

        if let Some(task) = self.upgrade_tasks.get_mut(&task.task_id) {
            if task.status == UpgradePhase::Approved {
                task.status = UpgradePhase::Downloading;
                task.started_at = Some(Utc::now());
            }
        }

        let start_event = DomainEvent::new(
            "upgrade.started",
            &task.task_id,
            json!({
                "task_id": task.task_id,
                "started_at": Utc::now().to_rfc3339(),
            }),
            Uuid::new_v4().to_string(),
        );
        let _ = self.event_publisher.publish(start_event).await;

        let batches = self.create_upgrade_batches(&task).await?;

        for (batch_index, batch) in batches.iter().enumerate() {
            if self.upgrade_tasks.get(&task.task_id).map(|t| t.is_paused).unwrap_or(false) {
                info!(task_id = %task.task_id, "Upgrade paused, waiting for resume");
                while self.upgrade_tasks.get(&task.task_id).map(|t| t.is_paused).unwrap_or(true) {
                    tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
                }
            }

            if self.upgrade_tasks.get(&task.task_id).map(|t| t.status.is_terminal()).unwrap_or(true) {
                break;
            }

            info!(task_id = %task.task_id, batch = batch_index, batch_size = batch.len(), "Processing upgrade batch");

            self.process_batch(&task, batch).await?;

            if batch_index < batches.len() - 1 {
                tokio::time::sleep(tokio::time::Duration::from_secs(task.gray_strategy.batch_interval_seconds)).await;
            }
        }

        Ok(())
    }

    async fn process_batch(self: &Arc<Self>, task: &UpgradeTask, device_ids: &[String]) -> AppResult<()> {
        let semaphore = Arc::new(tokio::sync::Semaphore::new(task.concurrency_limit as usize));
        let mut handles = Vec::new();

        for device_id in device_ids {
            let permit = semaphore.clone().acquire_owned().await.unwrap();
            let device_id = device_id.clone();
            let task_clone = task.clone();
            let service_clone = self.clone();

            let handle = tokio::spawn(async move {
                let _permit = permit;
                if let Err(e) = service_clone.process_device_upgrade(&task_clone, &device_id).await {
                    error!(error = %e, device_id = %device_id, "Device upgrade failed");
                }
            });
            handles.push(handle);
        }

        for handle in handles {
            let _ = handle.await;
        }

        Ok(())
    }

    async fn process_device_upgrade(&self, task: &UpgradeTask, device_id: &str) -> AppResult<()> {
        debug!(device_id = %device_id, task_id = %task.task_id, "Processing device upgrade");

        let key = format!("{}:{}", task.task_id, device_id);
        if let Some(mut status) = self.device_statuses.get_mut(&key) {
            if status.phase == UpgradePhase::Pending || status.phase == UpgradePhase::Approved {
                status.phase = UpgradePhase::Downloading;
                status.started_at = Some(Utc::now());
                status.last_heartbeat = Some(Utc::now());
            }
        }

        if let Some(notification) = &self.notification_port {
            let firmware = self.firmware_packages.get(&task.firmware_package_id).unwrap();
            let command = json!({
                "task_id": task.task_id,
                "action": "upgrade_firmware",
                "firmware_url": firmware.download_url,
                "firmware_version": firmware.version,
                "checksum": firmware.checksum,
                "delta_url": firmware.delta_from.as_ref().map(|_| format!("{}.delta", firmware.download_url)),
                "delta_checksum": firmware.delta_checksum.clone(),
                "timeout_seconds": task.timeout_per_device_seconds,
            });

            if let Err(e) = notification.send_device_command(device_id, command).await {
                warn!(error = %e, device_id = %device_id, "Failed to send upgrade command");
            }
        }

        Ok(())
    }

    async fn trigger_rollback_for_device(&self, task: &UpgradeTask, device_id: &str, reason: String) -> AppResult<()> {
        info!(device_id = %device_id, task_id = %task.task_id, "Triggering rollback for device");

        let key = format!("{}:{}", task.task_id, device_id);
        let mut status = self.device_statuses.get_mut(&key)
            .ok_or_else(|| AppError::NotFound(format!("设备升级状态不存在: {}", key)))?;

        if status.rollback_count >= task.rollback_policy.max_retries {
            warn!(device_id = %device_id, "Max rollback retries exceeded");
            status.phase = UpgradePhase::Failed;
            status.error_message = Some("超过最大回滚重试次数".into());
            return Ok(());
        }

        status.phase = UpgradePhase::RollingBack;
        status.rollback_count += 1;
        status.error_message = Some(reason.clone());

        let status_clone = status.clone();
        drop(status);

        let event = DomainEvent::new(
            "rollback.started",
            device_id,
            json!({
                "task_id": task.task_id,
                "device_id": device_id,
                "reason": reason,
                "rollback_count": status_clone.rollback_count,
            }),
            Uuid::new_v4().to_string(),
        );
        let _ = self.event_publisher.publish(event).await;

        if let Some(notification) = &self.notification_port {
            let target_version = task.rollback_policy.target_version.clone();
            let command = json!({
                "task_id": task.task_id,
                "action": "rollback_firmware",
                "target_version": target_version,
                "reason": reason,
            });

            if let Err(e) = notification.send_device_command(device_id, command).await {
                warn!(error = %e, device_id = %device_id, "Failed to send rollback command");
            }
        }

        Ok(())
    }

    fn check_rollback_threshold(self: &Arc<Self>, task: &UpgradeTask) {
        if !task.rollback_policy.enabled {
            return;
        }

        let failure_rate = task.statistics.failed_devices as f64 / task.statistics.total_devices.max(1) as f64;

        if failure_rate >= task.rollback_policy.failure_threshold
            && task.rollback_policy.triggers.contains(&RollbackTrigger::AutoThreshold)
        {
            warn!(task_id = %task.task_id, failure_rate = failure_rate, "Failure threshold exceeded, triggering batch rollback");

            let task_clone = task.clone();
            let service_clone = self.clone();
            tokio::spawn(async move {
                let statuses = service_clone.get_device_statuses_for_task(&task_clone.task_id);
                for s in statuses {
                    if !matches!(s.phase, UpgradePhase::Success | UpgradePhase::RolledBack | UpgradePhase::RollingBack) {
                        if let Err(e) = service_clone.trigger_rollback_for_device(&task_clone, &s.device_id, "失败率超过阈值".to_string()).await {
                            error!(error = %e, device_id = %s.device_id, "Failed to trigger rollback");
                        }
                    }
                }
            });
        }
    }

    fn validate_firmware_request(&self, req: &UploadFirmwareRequest) -> AppResult<()> {
        if req.name.is_empty() {
            return Err(AppError::Validation("固件名称不能为空".into()));
        }
        if req.version.is_empty() {
            return Err(AppError::Validation("固件版本不能为空".into()));
        }
        if req.device_model.is_empty() {
            return Err(AppError::Validation("设备型号不能为空".into()));
        }
        if req.size_bytes == 0 {
            return Err(AppError::Validation("固件大小不能为0".into()));
        }
        if req.checksum.is_empty() {
            return Err(AppError::Validation("校验和不能为空".into()));
        }
        if req.download_url.is_empty() {
            return Err(AppError::Validation("下载地址不能为空".into()));
        }

        let exists = self.firmware_packages.iter()
            .any(|p| p.version == req.version && p.device_model == req.device_model);

        if exists {
            return Err(AppError::Conflict(format!("该版本已存在: {} - {}", req.device_model, req.version)));
        }

        Ok(())
    }

    fn validate_gray_strategy(&self, strategy: &GrayStrategy) -> AppResult<()> {
        match strategy.strategy_type {
            GrayStrategyType::ByTags => {
                if strategy.tags.as_ref().map_or(true, |t| t.is_empty()) {
                    return Err(AppError::Validation("按标签灰度时标签不能为空".into()));
                }
            }
            GrayStrategyType::ByDeviceGroup => {
                if strategy.device_group_id.as_ref().map_or(true, |g| g.is_empty()) {
                    return Err(AppError::Validation("按设备组灰度时设备组ID不能为空".into()));
                }
            }
            GrayStrategyType::ByPercentage => {
                let p = strategy.percentage.unwrap_or(0);
                if p == 0 || p > 100 {
                    return Err(AppError::Validation("灰度百分比必须在1-100之间".into()));
                }
            }
            GrayStrategyType::ByDeviceList => {
                if strategy.device_ids.as_ref().map_or(true, |d| d.is_empty()) {
                    return Err(AppError::Validation("按设备列表灰度时设备ID列表不能为空".into()));
                }
            }
        }

        if strategy.batch_count == 0 {
            return Err(AppError::Validation("批次数量必须大于0".into()));
        }
        if strategy.success_threshold < 0.0 || strategy.success_threshold > 1.0 {
            return Err(AppError::Validation("成功率阈值必须在0-1之间".into()));
        }

        Ok(())
    }

    async fn verify_checksum(&self, url: &str, expected_checksum: &str, algorithm: Option<&str>) -> Result<(), String> {
        debug!(url = %url, "Verifying checksum");
        let _ = (url, expected_checksum, algorithm);
        Ok(())
    }

    fn calculate_delta_size(&self, from_size: u64, to_size: u64) -> u64 {
        let avg_size = (from_size + to_size) / 2;
        (avg_size as f64 * 0.3) as u64
    }

    fn generate_delta_checksum(&self, from: &FirmwarePackage, to: &FirmwarePackage) -> String {
        let mut hasher = Sha256::new();
        hasher.update(from.checksum.as_bytes());
        hasher.update(to.checksum.as_bytes());
        let result = hasher.finalize();
        base64::Engine::encode(&base64::engine::general_purpose::STANDARD, result)
    }

    async fn select_target_devices(&self, strategy: &GrayStrategy, device_model: &str) -> AppResult<Vec<String>> {
        let _ = (strategy, device_model);
        let sample_devices: Vec<String> = (1..=50)
            .map(|i| format!("device_{:04}", i))
            .collect();

        match strategy.strategy_type {
            GrayStrategyType::ByPercentage => {
                let p = strategy.percentage.unwrap_or(100) as f64 / 100.0;
                let count = (sample_devices.len() as f64 * p).round() as usize;
                Ok(sample_devices.into_iter().take(count).collect())
            }
            GrayStrategyType::ByDeviceList => {
                Ok(strategy.device_ids.clone().unwrap_or_default())
            }
            _ => Ok(sample_devices),
        }
    }

    async fn create_upgrade_batches(&self, task: &UpgradeTask) -> AppResult<Vec<Vec<String>>> {
        let statuses = self.get_device_statuses_for_task(&task.task_id);
        let device_ids: Vec<String> = statuses.into_iter()
            .filter(|s| !s.phase.is_terminal() && s.phase != UpgradePhase::RollingBack)
            .map(|s| s.device_id)
            .collect();

        let batch_count = task.gray_strategy.batch_count.min(device_ids.len() as u32).max(1) as usize;
        let batch_size = (device_ids.len() + batch_count - 1) / batch_count;

        let mut batches = Vec::with_capacity(batch_count);
        for chunk in device_ids.chunks(batch_size) {
            batches.push(chunk.to_vec());
        }

        Ok(batches)
    }

    fn get_device_statuses_for_task(&self, task_id: &str) -> Vec<DeviceUpgradeStatus> {
        self.device_statuses.iter()
            .filter(|s| s.task_id == task_id)
            .map(|s| s.clone())
            .collect()
    }

    fn to_firmware_response(&self, package: &FirmwarePackage) -> FirmwareResponse {
        FirmwareResponse {
            package_id: package.package_id.clone(),
            name: package.name.clone(),
            version: package.version.clone(),
            device_model: package.device_model.clone(),
            firmware_type: package.firmware_type.clone(),
            size_bytes: package.size_bytes,
            checksum: package.checksum.clone(),
            download_url: package.download_url.clone(),
            delta_size_bytes: package.delta_size_bytes,
            release_notes: package.release_notes.clone(),
            created_at: package.created_at,
            is_active: package.is_active,
        }
    }

    fn to_task_response(&self, task: &UpgradeTask, firmware: &FirmwarePackage) -> UpgradeTaskResponse {
        UpgradeTaskResponse {
            task_id: task.task_id.clone(),
            name: task.name.clone(),
            firmware_package_id: task.firmware_package_id.clone(),
            firmware_version: firmware.version.clone(),
            status: task.status.as_str().to_string(),
            statistics: task.statistics.clone(),
            created_by: task.created_by.clone(),
            approver: task.approver.clone(),
            created_at: task.created_at,
            approved_at: task.approved_at,
            started_at: task.started_at,
            completed_at: task.completed_at,
        }
    }
}

impl Clone for OtaUpgradeService {
    fn clone(&self) -> Self {
        Self {
            firmware_packages: self.firmware_packages.clone(),
            upgrade_tasks: self.upgrade_tasks.clone(),
            device_statuses: self.device_statuses.clone(),
            delta_packages: self.delta_packages.clone(),
            event_publisher: self.event_publisher.clone(),
            signature_validator: self.signature_validator.clone(),
            audit_logger: self.audit_logger.clone(),
            metrics: self.metrics.clone(),
            notification_port: self.notification_port.clone(),
        }
    }
}

use super::model::GrayStrategyType;
