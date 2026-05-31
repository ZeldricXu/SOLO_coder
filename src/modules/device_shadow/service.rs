use std::sync::Arc;
use dashmap::DashMap;
use serde_json::json;
use tracing::{info, warn, debug};
use tokio::sync::RwLock;

use crate::common::error::{AppError, AppResult};
use crate::common::context::RequestContext;
use crate::common::event::DomainEvent;
use crate::common::auth::SignatureValidator;
use crate::ports::mod::EventPublisherPort;
use crate::common::context::AuditLogger;
use crate::common::metrics::MetricsCollector;
use super::model::{
    DeviceShadow, ShadowUpdateRequest, ShadowState, ShadowResponse, SyncStatus,
    ShadowMonitoring, MonitoringConfig, MonitorPoint, MonitorPointCreateRequest,
    MonitorPointUpdateRequest, MonitoringReport, MonitoringAlert,
};

pub struct DeviceShadowService {
    shadows: Arc<DashMap<String, DeviceShadow>>,
    monitor_configs: Arc<DashMap<String, MonitoringConfig>>,
    monitor_points: Arc<DashMap<String, DashMap<String, MonitorPoint>>>,
    alerts: Arc<RwLock<Vec<MonitoringAlert>>>,
    event_publisher: Arc<dyn EventPublisherPort>,
    signature_validator: Arc<SignatureValidator>,
    audit_logger: Arc<AuditLogger>,
    metrics: MetricsCollector,
}

impl DeviceShadowService {
    pub fn new(
        event_publisher: Arc<dyn EventPublisherPort>,
        signature_validator: Arc<SignatureValidator>,
        audit_logger: Arc<AuditLogger>,
    ) -> Arc<Self> {
        Arc::new(Self {
            shadows: Arc::new(DashMap::new()),
            monitor_configs: Arc::new(DashMap::new()),
            monitor_points: Arc::new(DashMap::new()),
            alerts: Arc::new(RwLock::new(Vec::new())),
            event_publisher,
            signature_validator,
            audit_logger,
            metrics: MetricsCollector::new().with_dimension("module", "device_shadow"),
        })
    }

    pub async fn update_shadow(&self, ctx: &RequestContext, req: ShadowUpdateRequest) -> AppResult<ShadowResponse> {
        let start = std::time::Instant::now();
        debug!(device_id = %req.device_id, "Updating device shadow");

        self.validate_input(&req)?;

        let payload = json!(&req.state).to_string();
        self.signature_validator.validate(&payload, &req.signature, req.timestamp)?;

        let mut shadow = self.get_or_create_shadow(&req.device_id).await;
        shadow.sync_status = SyncStatus::Syncing;

        let event_type = match &req.state {
            ShadowState::Desired(state) => {
                shadow.update_desired(state.clone());
                self.check_monitor_points(&req.device_id, &shadow.reported).await;
                "shadow.desired.updated"
            }
            ShadowState::Reported(state) => {
                shadow.update_reported(state.clone());
                self.check_monitor_points(&req.device_id, &shadow.reported).await;
                "shadow.reported.updated"
            }
        };

        let data_mapped = self.transform_data(&shadow)?;

        self.shadows.insert(req.device_id.clone(), shadow.clone());

        let event = DomainEvent::new(
            event_type,
            &req.device_id,
            json!({
                "device_id": req.device_id,
                "version": shadow.version,
                "sync_status": format!("{:?}", shadow.sync_status),
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "shadow.update",
            "device_shadow",
            &req.device_id,
            true,
            json!({ "version": shadow.version, "sync_status": format!("{:?}", shadow.sync_status) }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        if let Some(delta) = &shadow.delta {
            let delta_event = DomainEvent::new(
                "shadow.delta.detected",
                &req.device_id,
                json!({
                    "device_id": req.device_id,
                    "delta": delta,
                    "version": shadow.version,
                }),
                &ctx.trace_id,
            );
            self.event_publisher.publish(delta_event).await?;

            if delta.len() > 0 {
                self.create_alert(
                    &req.device_id,
                    "system",
                    super::model::AlertType::OutOfSync,
                    format!("设备检测到 {} 个不同步属性", delta.len()),
                    json!(delta),
                    None,
                ).await;
            }
        }

        Ok(ShadowResponse {
            device_id: req.device_id,
            status: "success".into(),
            version: shadow.version,
            state: shadow.reported.clone(),
            delta: shadow.delta.clone(),
            monitoring: Some(shadow.monitoring.clone()),
        })
    }

    pub async fn get_shadow(&self, ctx: &RequestContext, device_id: &str, include_monitoring: bool) -> AppResult<ShadowResponse> {
        let start = std::time::Instant::now();
        debug!(device_id = %device_id, "Getting device shadow");

        let shadow = self.shadows.get(device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备影子不存在: {}", device_id)))?;

        self.audit_logger.log_operation(
            ctx,
            "shadow.get",
            "device_shadow",
            device_id,
            true,
            json!({ "version": shadow.version }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(ShadowResponse {
            device_id: device_id.to_string(),
            status: "success".into(),
            version: shadow.version,
            state: shadow.reported.clone(),
            delta: shadow.delta.clone(),
            monitoring: if include_monitoring { Some(shadow.monitoring.clone()) } else { None },
        })
    }

    pub async fn sync_device(&self, ctx: &RequestContext, device_id: &str) -> AppResult<ShadowResponse> {
        let start = std::time::Instant::now();
        info!(device_id = %device_id, "Syncing device shadow");

        let mut shadow = self.get_or_create_shadow(device_id).await;

        if shadow.delta.is_some() {
            shadow.sync_status = SyncStatus::Syncing;

            let sync_event = DomainEvent::new(
                "shadow.sync.started",
                device_id,
                json!({
                    "device_id": device_id,
                    "delta": shadow.delta,
                }),
                &ctx.trace_id,
            );
            self.event_publisher.publish(sync_event).await?;

            let reported = shadow.reported.clone();
            shadow.update_reported(reported);
            shadow.sync_status = SyncStatus::InSync;

            let sync_duration = start.elapsed().as_millis() as u64;
            shadow.record_sync_success(sync_duration);

            self.shadows.insert(device_id.to_string(), shadow.clone());

            let complete_event = DomainEvent::new(
                "shadow.sync.completed",
                device_id,
                json!({
                    "device_id": device_id,
                    "version": shadow.version,
                    "duration_ms": sync_duration,
                }),
                &ctx.trace_id,
            );
            self.event_publisher.publish(complete_event).await?;
        }

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(ShadowResponse {
            device_id: device_id.to_string(),
            status: "synced".into(),
            version: shadow.version,
            state: shadow.reported.clone(),
            delta: shadow.delta.clone(),
            monitoring: Some(shadow.monitoring.clone()),
        })
    }

    pub async fn create_monitor_point(&self, ctx: &RequestContext, device_id: &str, req: MonitorPointCreateRequest) -> AppResult<MonitorPoint> {
        debug!(device_id = %device_id, name = %req.name, "Creating monitor point");

        self.ensure_device_exists(device_id).await?;

        let point = MonitorPoint::new(req);
        let points = self.monitor_points
            .entry(device_id.to_string())
            .or_insert_with(|| DashMap::new());
        points.insert(point.point_id.clone(), point.clone());

        let event = DomainEvent::new(
            "monitor.point.created",
            &point.point_id,
            json!({
                "device_id": device_id,
                "point_id": point.point_id,
                "name": point.name,
                "path": point.path,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "monitor.point.create",
            "device_shadow",
            device_id,
            true,
            json!({ "point_id": point.point_id, "name": point.name }),
        );

        Ok(point)
    }

    pub async fn update_monitor_point(&self, ctx: &RequestContext, device_id: &str, point_id: &str, req: MonitorPointUpdateRequest) -> AppResult<MonitorPoint> {
        debug!(device_id = %device_id, point_id = %point_id, "Updating monitor point");

        let points = self.monitor_points.get(device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备监控点不存在: {}", device_id)))?;

        let mut point = points.get_mut(point_id)
            .ok_or_else(|| AppError::NotFound(format!("监控点不存在: {}", point_id)))?;

        if let Some(name) = req.name {
            point.name = name;
        }
        if let Some(high) = req.threshold_high {
            point.threshold_high = Some(high);
        }
        if let Some(low) = req.threshold_low {
            point.threshold_low = Some(low);
        }
        if let Some(enabled) = req.alert_enabled {
            point.alert_enabled = enabled;
        }

        let updated = point.clone();

        self.audit_logger.log_operation(
            ctx,
            "monitor.point.update",
            "device_shadow",
            device_id,
            true,
            json!({ "point_id": point_id }),
        );

        Ok(updated)
    }

    pub async fn delete_monitor_point(&self, ctx: &RequestContext, device_id: &str, point_id: &str) -> AppResult<()> {
        debug!(device_id = %device_id, point_id = %point_id, "Deleting monitor point");

        let points = self.monitor_points.get(device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备监控点不存在: {}", device_id)))?;

        if points.remove(point_id).is_none() {
            return Err(AppError::NotFound(format!("监控点不存在: {}", point_id)));
        }

        self.audit_logger.log_operation(
            ctx,
            "monitor.point.delete",
            "device_shadow",
            device_id,
            true,
            json!({ "point_id": point_id }),
        );

        Ok(())
    }

    pub async fn list_monitor_points(&self, device_id: &str) -> AppResult<Vec<MonitorPoint>> {
        let points = self.monitor_points.get(device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备监控点不存在: {}", device_id)))?;

        Ok(points.iter().map(|p| p.clone()).collect())
    }

    pub async fn get_monitoring_report(&self, device_id: &str, duration_seconds: u64) -> AppResult<MonitoringReport> {
        let shadow = self.shadows.get(device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备影子不存在: {}", device_id)))?;

        Ok(shadow.generate_monitoring_report(duration_seconds))
    }

    pub async fn get_monitoring(&self, device_id: &str) -> AppResult<ShadowMonitoring> {
        let shadow = self.shadows.get(device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备影子不存在: {}", device_id)))?;

        Ok(shadow.monitoring.clone())
    }

    pub async fn get_alerts(&self, device_id: Option<&str>, limit: usize) -> Vec<MonitoringAlert> {
        let alerts = self.alerts.read().await;
        let mut result: Vec<MonitoringAlert> = if let Some(dev_id) = device_id {
            alerts.iter().filter(|a| a.device_id == dev_id).cloned().collect()
        } else {
            alerts.clone()
        };
        result.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));
        result.into_iter().take(limit).collect()
    }

    pub async fn acknowledge_alert(&self, alert_id: &str) -> AppResult<()> {
        let mut alerts = self.alerts.write().await;
        for alert in alerts.iter_mut() {
            if alert.alert_id == alert_id {
                alert.acknowledged = true;
                return Ok(());
            }
        }
        Err(AppError::NotFound(format!("告警不存在: {}", alert_id)))
    }

    async fn check_monitor_points(&self, device_id: &str, reported: &std::collections::HashMap<String, serde_json::Value>) {
        if let Some(points) = self.monitor_points.get(device_id) {
            for mut point in points.iter_mut() {
                if let Some(value) = reported.get(&point.path) {
                    point.last_value = Some(value.clone());
                    point.last_updated = Some(chrono::Utc::now());

                    if let Some(alert_type) = point.check_alert(value) {
                        let threshold = match alert_type {
                            super::model::AlertType::ThresholdHigh => point.threshold_high,
                            super::model::AlertType::ThresholdLow => point.threshold_low,
                            _ => None,
                        };
                        let message = format!(
                            "监控点 '{}' 触发告警: 当前值={:?}",
                            point.name, value
                        );
                        self.create_alert(device_id, &point.point_id, alert_type, message, value.clone(), threshold).await;
                    }
                }
            }
        }
    }

    async fn create_alert(
        &self,
        device_id: &str,
        point_id: &str,
        alert_type: super::model::AlertType,
        message: String,
        current_value: serde_json::Value,
        threshold: Option<f64>,
    ) {
        let alert = MonitoringAlert {
            alert_id: uuid::Uuid::new_v4().to_string(),
            device_id: device_id.to_string(),
            point_id: point_id.to_string(),
            alert_type,
            message,
            current_value,
            threshold,
            timestamp: chrono::Utc::now(),
            acknowledged: false,
        };

        let mut alerts = self.alerts.write().await;
        alerts.push(alert.clone());

        let event = DomainEvent::new(
            "monitor.alert.triggered",
            &alert.alert_id,
            json!({
                "alert_id": alert.alert_id,
                "device_id": device_id,
                "point_id": point_id,
                "alert_type": format!("{:?}", alert_type),
                "message": alert.message,
            }),
            "system",
        );
        let _ = self.event_publisher.publish(event).await;
    }

    async fn ensure_device_exists(&self, device_id: &str) -> AppResult<()> {
        if !self.shadows.contains_key(device_id) {
            let shadow = DeviceShadow::new(device_id);
            self.shadows.insert(device_id.to_string(), shadow);
        }
        Ok(())
    }

    fn validate_input(&self, req: &ShadowUpdateRequest) -> AppResult<()> {
        if req.device_id.is_empty() {
            return Err(AppError::Validation("设备ID不能为空".into()));
        }
        if req.signature.is_empty() {
            return Err(AppError::Validation("签名不能为空".into()));
        }
        if req.timestamp == 0 {
            return Err(AppError::Validation("时间戳不能为空".into()));
        }
        Ok(())
    }

    fn transform_data(&self, shadow: &DeviceShadow) -> AppResult<serde_json::Value> {
        Ok(json!({
            "device_id": shadow.device_id,
            "version": shadow.version,
            "sync_status": format!("{:?}", shadow.sync_status),
            "desired_count": shadow.desired.len(),
            "reported_count": shadow.reported.len(),
            "delta_count": shadow.delta.as_ref().map(|d| d.len()).unwrap_or(0),
            "monitoring_enabled": shadow.monitoring.enabled,
            "total_updates": shadow.monitoring.update_count,
            "sync_count": shadow.monitoring.sync_count,
        }))
    }

    async fn get_or_create_shadow(&self, device_id: &str) -> DeviceShadow {
        self.shadows.get(device_id)
            .map(|s| s.clone())
            .unwrap_or_else(|| DeviceShadow::new(device_id))
    }

    pub fn get_metrics(&self) -> crate::common::metrics::StatsSnapshot {
        self.metrics.snapshot()
    }

    pub async fn list_shadows(&self, page: u32, page_size: u32, include_monitoring: bool) -> AppResult<(Vec<ShadowResponse>, u64)> {
        let items: Vec<ShadowResponse> = self.shadows.iter()
            .map(|s| ShadowResponse {
                device_id: s.device_id.clone(),
                status: format!("{:?}", s.sync_status).to_lowercase(),
                version: s.version,
                state: s.reported.clone(),
                delta: s.delta.clone(),
                monitoring: if include_monitoring { Some(s.monitoring.clone()) } else { None },
            })
            .collect();
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }

    pub async fn delete_shadow(&self, ctx: &RequestContext, device_id: &str) -> AppResult<()> {
        if self.shadows.remove(device_id).is_none() {
            return Err(AppError::NotFound(format!("设备影子不存在: {}", device_id)));
        }

        self.monitor_points.remove(device_id);
        self.monitor_configs.remove(device_id);

        let event = DomainEvent::new(
            "shadow.deleted",
            device_id,
            json!({ "device_id": device_id }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "shadow.delete",
            "device_shadow",
            device_id,
            true,
            json!({}),
        );

        Ok(())
    }
}
