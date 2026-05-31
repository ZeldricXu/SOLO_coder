use std::sync::Arc;
use dashmap::DashMap;
use rand::Rng;
use serde_json::json;
use tracing::{info, warn, debug, error};
use chrono::{Utc, Duration};
use base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};

use crate::common::error::{AppError, AppResult};
use crate::common::context::RequestContext;
use crate::common::event::DomainEvent;
use crate::common::auth::SignatureValidator;
use crate::ports::mod::EventPublisherPort;
use crate::common::context::AuditLogger;
use crate::common::metrics::MetricsCollector;
use super::model::{
    Device, DeviceCredentials, DeviceStatus, DeviceSession, Heartbeat,
    DeviceRegisterRequest, DeviceRegisterResponse, DeviceActivateRequest, DeviceActivateResponse,
    DeviceAuthRequest, DeviceAuthResponse, HeartbeatRequest, HeartbeatResponse,
    DeviceStatusUpdateRequest, DeviceLabelUpdateRequest, DeviceTagUpdateRequest,
    DeviceResponse, DeviceQueryParams, LabelOperation, TagOperation,
};

pub struct DeviceLifecycleService {
    devices: Arc<DashMap<String, Device>>,
    credentials: Arc<DashMap<String, DeviceCredentials>>,
    sessions: Arc<DashMap<String, DeviceSession>>,
    heartbeats: Arc<DashMap<String, Heartbeat>>,
    activation_codes: Arc<DashMap<String, String>>,
    event_publisher: Arc<dyn EventPublisherPort>,
    signature_validator: Arc<SignatureValidator>,
    audit_logger: Arc<AuditLogger>,
    metrics: MetricsCollector,
}

impl DeviceLifecycleService {
    pub fn new(
        event_publisher: Arc<dyn EventPublisherPort>,
        signature_validator: Arc<SignatureValidator>,
        audit_logger: Arc<AuditLogger>,
    ) -> Arc<Self> {
        Arc::new(Self {
            devices: Arc::new(DashMap::new()),
            credentials: Arc::new(DashMap::new()),
            sessions: Arc::new(DashMap::new()),
            heartbeats: Arc::new(DashMap::new()),
            activation_codes: Arc::new(DashMap::new()),
            event_publisher,
            signature_validator,
            audit_logger,
            metrics: MetricsCollector::new().with_dimension("module", "device_lifecycle"),
        })
    }

    pub async fn register_device(
        &self,
        ctx: &RequestContext,
        req: DeviceRegisterRequest,
    ) -> AppResult<DeviceRegisterResponse> {
        let start = std::time::Instant::now();
        info!(device_name = %req.device_name, "Registering new device");

        self.validate_register_request(&req)?;

        let device = Device::new(&req);
        let device_secret = self.generate_device_secret();
        let activation_code = self.generate_activation_code();
        let activation_expires_at = Utc::now() + Duration::hours(24);

        let credentials = DeviceCredentials::new(&device.device_id, &device_secret);

        if req.generate_certificate.unwrap_or(false) {
            let (cert, priv_key, pub_key) = self.generate_certificate(&device);
            let mut creds = credentials.clone();
            creds.certificate_pem = Some(cert);
            creds.private_key = Some(priv_key);
            creds.public_key = Some(pub_key);
            creds.certificate_expires_at = Some(Utc::now() + Duration::days(365));
            self.credentials.insert(device.device_id.clone(), creds);
        } else {
            self.credentials.insert(device.device_id.clone(), credentials.clone());
        }

        let api_key = self.generate_api_key();
        let mut device_with_activation = device.clone();
        device_with_activation.activation_code = Some(activation_code.clone());
        device_with_activation.activation_code_expires_at = Some(activation_expires_at);
        device_with_activation.registered_at = Some(Utc::now());

        self.activation_codes.insert(activation_code.clone(), device.device_id.clone());
        self.devices.insert(device.device_id.clone(), device_with_activation);

        let event = DomainEvent::new(
            "device.registered",
            &device.device_id,
            json!({
                "device_id": device.device_id,
                "device_name": device.device_name,
                "device_type": device.device_type,
                "tenant_id": device.tenant_id,
                "manufacturer": device.manufacturer,
                "model": device.model,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "device.register",
            "device",
            &device.device_id,
            true,
            json!({
                "device_name": device.device_name,
                "device_type": device.device_type,
                "tenant_id": device.tenant_id,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(DeviceRegisterResponse {
            device_id: device.device_id,
            device_name: device.device_name,
            device_secret,
            activation_code,
            activation_code_expires_at: activation_expires_at,
            api_key: Some(api_key),
            certificate_pem: credentials.certificate_pem,
            status: DeviceStatus::Pending.as_str().to_string(),
            created_at: device.created_at,
        })
    }

    pub async fn activate_device(
        &self,
        ctx: &RequestContext,
        req: DeviceActivateRequest,
    ) -> AppResult<DeviceActivateResponse> {
        let start = std::time::Instant::now();
        info!(device_id = %req.device_id, "Activating device");

        self.validate_activate_request(&req)?;

        let mut device = self.devices.get_mut(&req.device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备不存在: {}", req.device_id)))?;

        if !device.is_enabled() {
            return Err(AppError::Forbidden(format!("设备已被禁用或删除: {}", req.device_id)));
        }

        if device.status != DeviceStatus::Pending {
            return Err(AppError::Conflict(format!("设备状态不允许激活: {:?}", device.status)));
        }

        let stored_activation_code = device.activation_code.as_ref()
            .ok_or_else(|| AppError::Validation("激活码已失效".into()))?;

        if stored_activation_code != &req.activation_code {
            return Err(AppError::Unauthorized("激活码错误".into()));
        }

        if let Some(expires_at) = device.activation_code_expires_at {
            if Utc::now() > expires_at {
                return Err(AppError::Unauthorized("激活码已过期".into()));
            }
        }

        let payload = json!({
            "device_id": req.device_id,
            "activation_code": req.activation_code,
        }).to_string();
        self.signature_validator.validate(&payload, &req.signature, req.timestamp)?;

        device.status = DeviceStatus::Active;
        device.activation_code = None;
        device.activation_code_expires_at = None;
        device.activated_at = Some(Utc::now());
        device.last_connected_at = Some(Utc::now());
        device.ip_address = req.ip_address.clone();
        if let Some(location) = req.location.clone() {
            device.location = Some(location);
        }
        if let Some(fw_version) = req.firmware_version.clone() {
            device.firmware_version = fw_version;
        }
        device.updated_at = Utc::now();

        let session_token = self.generate_session_token();
        let session = DeviceSession::new(&req.device_id, &session_token, 3600 * 24);
        self.sessions.insert(session_token.clone(), session.clone());

        self.activation_codes.remove(&req.activation_code);

        let event = DomainEvent::new(
            "device.activated",
            &req.device_id,
            json!({
                "device_id": req.device_id,
                "ip_address": req.ip_address,
                "activated_at": device.activated_at,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        let connect_event = DomainEvent::new(
            "device.connected",
            &req.device_id,
            json!({
                "device_id": req.device_id,
                "ip_address": req.ip_address,
                "connected_at": device.last_connected_at,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(connect_event).await?;

        self.audit_logger.log_operation(
            ctx,
            "device.activate",
            "device",
            &req.device_id,
            true,
            json!({
                "ip_address": req.ip_address,
                "previous_status": "pending",
                "new_status": "active",
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(DeviceActivateResponse {
            device_id: req.device_id,
            status: DeviceStatus::Active.as_str().to_string(),
            session_token,
            session_expires_at: session.expires_at,
            activated_at: device.activated_at.unwrap(),
            server_time: Utc::now(),
        })
    }

    pub async fn authenticate_device(
        &self,
        ctx: &RequestContext,
        req: DeviceAuthRequest,
    ) -> AppResult<DeviceAuthResponse> {
        let start = std::time::Instant::now();
        debug!(device_id = %req.device_id, "Authenticating device");

        let device = self.devices.get(&req.device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备不存在: {}", req.device_id)))?;

        if !device.is_enabled() {
            return Err(AppError::Forbidden(format!("设备已被禁用或删除: {}", req.device_id)));
        }

        if device.status == DeviceStatus::Pending {
            return Err(AppError::Unauthorized("设备未激活".into()));
        }

        let credentials = self.credentials.get(&req.device_id)
            .ok_or_else(|| AppError::Internal("设备凭证不存在".into()))?;

        if credentials.device_secret != req.device_secret {
            return Err(AppError::Unauthorized("设备密钥错误".into()));
        }

        let payload = json!({
            "device_id": req.device_id,
            "device_secret": req.device_secret,
            "nonce": req.nonce,
        }).to_string();
        self.signature_validator.validate(&payload, &req.signature, req.timestamp)?;

        let session_token = self.generate_session_token();
        let session = DeviceSession::new(&req.device_id, &session_token, 3600);
        self.sessions.insert(session_token.clone(), session.clone());

        self.audit_logger.log_operation(
            ctx,
            "device.authenticate",
            "device",
            &req.device_id,
            true,
            json!({ "nonce": req.nonce }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(DeviceAuthResponse {
            device_id: req.device_id,
            session_token,
            expires_at: session.expires_at,
            tenant_id: device.tenant_id.clone(),
            permissions: vec!["device:*".to_string()],
        })
    }

    pub async fn heartbeat(
        &self,
        ctx: &RequestContext,
        req: HeartbeatRequest,
    ) -> AppResult<HeartbeatResponse> {
        let start = std::time::Instant::now();
        debug!(device_id = %req.device_id, "Received heartbeat");

        let mut device = self.devices.get_mut(&req.device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备不存在: {}", req.device_id)))?;

        if !device.is_enabled() {
            return Err(AppError::Forbidden(format!("设备已被禁用或删除: {}", req.device_id)));
        }

        if device.status == DeviceStatus::Pending {
            return Err(AppError::Unauthorized("设备未激活".into()));
        }

        let payload = json!({
            "device_id": req.device_id,
            "uptime_seconds": req.uptime_seconds,
        }).to_string();
        self.signature_validator.validate(&payload, &req.signature, req.timestamp)?;

        let heartbeat = Heartbeat::from_request(&req);
        self.heartbeats.insert(req.device_id.clone(), heartbeat.clone());

        let previous_status = device.status.clone();
        if device.status == DeviceStatus::Offline || device.status == DeviceStatus::Inactive {
            device.status = DeviceStatus::Active;

            let connect_event = DomainEvent::new(
                "device.connected",
                &req.device_id,
                json!({
                    "device_id": req.device_id,
                    "previous_status": previous_status.as_str(),
                }),
                &ctx.trace_id,
            );
            self.event_publisher.publish(connect_event).await?;
        }

        device.last_heartbeat_at = Some(Utc::now());
        device.last_connected_at = Some(Utc::now());
        device.updated_at = Utc::now();

        self.audit_logger.log_operation(
            ctx,
            "device.heartbeat",
            "device",
            &req.device_id,
            true,
            json!({
                "uptime_seconds": req.uptime_seconds,
                "cpu_usage": req.cpu_usage,
                "memory_usage": req.memory_usage,
                "health_status": heartbeat.health_status,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(HeartbeatResponse {
            device_id: req.device_id,
            status: "success".to_string(),
            server_time: Utc::now(),
            next_heartbeat_interval: device.heartbeat_interval,
            commands: None,
        })
    }

    pub async fn get_device(
        &self,
        ctx: &RequestContext,
        device_id: &str,
    ) -> AppResult<DeviceResponse> {
        let start = std::time::Instant::now();
        debug!(device_id = %device_id, "Getting device info");

        let device = self.devices.get(device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备不存在: {}", device_id)))?;

        let health_indicators = self.heartbeats.get(device_id)
            .map(|h| h.to_health_indicators());

        self.audit_logger.log_operation(
            ctx,
            "device.get",
            "device",
            device_id,
            true,
            json!({ "status": device.status.as_str() }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(device.to_response(health_indicators))
    }

    pub async fn list_devices(
        &self,
        _ctx: &RequestContext,
        params: DeviceQueryParams,
        page: u32,
        page_size: u32,
    ) -> AppResult<(Vec<DeviceResponse>, u64)> {
        let start = std::time::Instant::now();
        debug!("Listing devices");

        let mut filtered: Vec<DeviceResponse> = self.devices.iter()
            .filter(|device| self.matches_query_params(device.value(), &params))
            .map(|device| {
                let health_indicators = self.heartbeats.get(&device.device_id)
                    .map(|h| h.to_health_indicators());
                device.to_response(health_indicators)
            })
            .collect();

        filtered.sort_by(|a, b| b.created_at.cmp(&a.created_at));

        let total = filtered.len() as u64;
        let start_idx = ((page - 1) * page_size) as usize;
        let end_idx = (start_idx + page_size as usize).min(filtered.len());
        let paginated = filtered.into_iter().skip(start_idx).take(end_idx - start_idx).collect();

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok((paginated, total))
    }

    pub async fn update_device_status(
        &self,
        ctx: &RequestContext,
        device_id: &str,
        req: DeviceStatusUpdateRequest,
    ) -> AppResult<DeviceResponse> {
        let start = std::time::Instant::now();
        info!(device_id = %device_id, new_status = %req.status, "Updating device status");

        let new_status = DeviceStatus::from_str(&req.status)
            .ok_or_else(|| AppError::Validation(format!("无效的设备状态: {}", req.status)))?;

        let mut device = self.devices.get_mut(device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备不存在: {}", device_id)))?;

        let previous_status = device.status.clone();

        if !device.transition_status(new_status.clone()) {
            return Err(AppError::Conflict(format!(
                "无法从状态 {:?} 转换到 {:?}",
                previous_status, new_status
            )));
        }

        if new_status == DeviceStatus::Active {
            device.last_connected_at = Some(Utc::now());
        } else if new_status == DeviceStatus::Offline {
            device.last_disconnected_at = Some(Utc::now());

            let disconnect_event = DomainEvent::new(
                "device.disconnected",
                device_id,
                json!({
                    "device_id": device_id,
                    "reason": req.reason.as_deref().unwrap_or("manual"),
                }),
                &ctx.trace_id,
            );
            self.event_publisher.publish(disconnect_event).await?;
        } else if new_status == DeviceStatus::Deleted {
            let delete_event = DomainEvent::new(
                "device.deleted",
                device_id,
                json!({
                    "device_id": device_id,
                    "reason": req.reason.as_deref().unwrap_or("manual"),
                }),
                &ctx.trace_id,
            );
            self.event_publisher.publish(delete_event).await?;

            self.credentials.remove(device_id);
            self.heartbeats.remove(device_id);
        }

        self.audit_logger.log_operation(
            ctx,
            "device.update_status",
            "device",
            device_id,
            true,
            json!({
                "previous_status": previous_status.as_str(),
                "new_status": new_status.as_str(),
                "reason": req.reason,
            }),
        );

        let health_indicators = self.heartbeats.get(device_id)
            .map(|h| h.to_health_indicators());

        let response = device.to_response(health_indicators);

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(response)
    }

    pub async fn update_labels(
        &self,
        ctx: &RequestContext,
        device_id: &str,
        req: DeviceLabelUpdateRequest,
    ) -> AppResult<DeviceResponse> {
        let start = std::time::Instant::now();
        info!(device_id = %device_id, "Updating device labels");

        let mut device = self.devices.get_mut(device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备不存在: {}", device_id)))?;

        match req.operation {
            LabelOperation::Set => {
                for (key, value) in req.labels {
                    device.labels.insert(key, value);
                }
            }
            LabelOperation::Remove => {
                for key in req.labels.keys() {
                    device.labels.remove(key);
                }
            }
            LabelOperation::Replace => {
                device.labels = req.labels;
            }
        }
        device.updated_at = Utc::now();

        self.audit_logger.log_operation(
            ctx,
            "device.update_labels",
            "device",
            device_id,
            true,
            json!({
                "operation": format!("{:?}", req.operation),
                "labels_count": device.labels.len(),
            }),
        );

        let health_indicators = self.heartbeats.get(device_id)
            .map(|h| h.to_health_indicators());

        let response = device.to_response(health_indicators);

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(response)
    }

    pub async fn update_tags(
        &self,
        ctx: &RequestContext,
        device_id: &str,
        req: DeviceTagUpdateRequest,
    ) -> AppResult<DeviceResponse> {
        let start = std::time::Instant::now();
        info!(device_id = %device_id, "Updating device tags");

        let mut device = self.devices.get_mut(device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备不存在: {}", device_id)))?;

        match req.operation {
            TagOperation::Add => {
                for tag in req.tags {
                    if !device.tags.contains(&tag) {
                        device.tags.push(tag);
                    }
                }
            }
            TagOperation::Remove => {
                device.tags.retain(|t| !req.tags.contains(t));
            }
            TagOperation::Replace => {
                device.tags = req.tags;
            }
        }
        device.updated_at = Utc::now();

        self.audit_logger.log_operation(
            ctx,
            "device.update_tags",
            "device",
            device_id,
            true,
            json!({
                "operation": format!("{:?}", req.operation),
                "tags_count": device.tags.len(),
            }),
        );

        let health_indicators = self.heartbeats.get(device_id)
            .map(|h| h.to_health_indicators());

        let response = device.to_response(health_indicators);

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(response)
    }

    pub async fn enable_device(
        &self,
        ctx: &RequestContext,
        device_id: &str,
    ) -> AppResult<DeviceResponse> {
        self.update_device_status(ctx, device_id, DeviceStatusUpdateRequest {
            status: DeviceStatus::Active.as_str().to_string(),
            reason: Some("enabled".to_string()),
        }).await
    }

    pub async fn disable_device(
        &self,
        ctx: &RequestContext,
        device_id: &str,
    ) -> AppResult<DeviceResponse> {
        self.update_device_status(ctx, device_id, DeviceStatusUpdateRequest {
            status: DeviceStatus::Disabled.as_str().to_string(),
            reason: Some("disabled".to_string()),
        }).await
    }

    pub async fn delete_device(
        &self,
        ctx: &RequestContext,
        device_id: &str,
    ) -> AppResult<()> {
        let start = std::time::Instant::now();
        info!(device_id = %device_id, "Deleting device");

        let mut device = self.devices.get_mut(device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备不存在: {}", device_id)))?;

        if device.status == DeviceStatus::Deleted {
            return Err(AppError::Conflict(format!("设备已删除: {}", device_id)));
        }

        device.status = DeviceStatus::Deleted;
        device.updated_at = Utc::now();

        let event = DomainEvent::new(
            "device.deleted",
            device_id,
            json!({
                "device_id": device_id,
                "device_name": device.device_name,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.credentials.remove(device_id);
        self.heartbeats.remove(device_id);
        self.sessions.retain(|_, s| s.device_id != device_id);
        self.devices.remove(device_id);

        self.audit_logger.log_operation(
            ctx,
            "device.delete",
            "device",
            device_id,
            true,
            json!({}),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(())
    }

    pub async fn rotate_credentials(
        &self,
        ctx: &RequestContext,
        device_id: &str,
    ) -> AppResult<DeviceRegisterResponse> {
        let start = std::time::Instant::now();
        info!(device_id = %device_id, "Rotating device credentials");

        let device = self.devices.get(device_id)
            .ok_or_else(|| AppError::NotFound(format!("设备不存在: {}", device_id)))?;

        let new_secret = self.generate_device_secret();
        let mut credentials = self.credentials.get_mut(device_id)
            .ok_or_else(|| AppError::Internal("设备凭证不存在".into()))?;

        credentials.device_secret = new_secret.clone();
        credentials.rotated_at = Some(Utc::now());

        self.audit_logger.log_operation(
            ctx,
            "device.rotate_credentials",
            "device",
            device_id,
            true,
            json!({ "rotated_at": credentials.rotated_at }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(DeviceRegisterResponse {
            device_id: device.device_id.clone(),
            device_name: device.device_name.clone(),
            device_secret: new_secret,
            activation_code: "".to_string(),
            activation_code_expires_at: Utc::now(),
            api_key: None,
            certificate_pem: None,
            status: device.status.as_str().to_string(),
            created_at: device.created_at,
        })
    }

    pub async fn check_session(
        &self,
        _ctx: &RequestContext,
        token: &str,
    ) -> AppResult<DeviceSession> {
        let session = self.sessions.get(token)
            .ok_or_else(|| AppError::Unauthorized("会话不存在".into()))?;

        if !session.is_valid() {
            self.sessions.remove(token);
            return Err(AppError::Unauthorized("会话已过期".into()));
        }

        let mut session = session.clone();
        session.touch();
        self.sessions.insert(token.to_string(), session.clone());

        Ok(session)
    }

    pub async fn logout_device(
        &self,
        ctx: &RequestContext,
        token: &str,
    ) -> AppResult<()> {
        let start = std::time::Instant::now();
        debug!("Logging out device");

        let session = self.sessions.remove(token)
            .ok_or_else(|| AppError::Unauthorized("会话不存在".into()))?;

        self.audit_logger.log_operation(
            ctx,
            "device.logout",
            "device",
            &session.device_id,
            true,
            json!({ "session_id": session.session_id }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(())
    }

    pub fn get_metrics(&self) -> crate::common::metrics::StatsSnapshot {
        self.metrics.snapshot()
    }

    fn validate_register_request(&self, req: &DeviceRegisterRequest) -> AppResult<()> {
        if req.device_name.is_empty() {
            return Err(AppError::Validation("设备名称不能为空".into()));
        }
        if req.device_type.is_empty() {
            return Err(AppError::Validation("设备类型不能为空".into()));
        }
        if req.manufacturer.is_empty() {
            return Err(AppError::Validation("制造商不能为空".into()));
        }
        if req.model.is_empty() {
            return Err(AppError::Validation("型号不能为空".into()));
        }
        if req.serial_number.is_empty() {
            return Err(AppError::Validation("序列号不能为空".into()));
        }
        if req.firmware_version.is_empty() {
            return Err(AppError::Validation("固件版本不能为空".into()));
        }
        if req.hardware_version.is_empty() {
            return Err(AppError::Validation("硬件版本不能为空".into()));
        }
        if req.tenant_id.is_empty() {
            return Err(AppError::Validation("租户ID不能为空".into()));
        }
        if let Some(interval) = req.heartbeat_interval {
            if interval == 0 || interval > 86400 {
                return Err(AppError::Validation("心跳间隔必须在1-86400秒之间".into()));
            }
        }
        Ok(())
    }

    fn validate_activate_request(&self, req: &DeviceActivateRequest) -> AppResult<()> {
        if req.device_id.is_empty() {
            return Err(AppError::Validation("设备ID不能为空".into()));
        }
        if req.activation_code.is_empty() {
            return Err(AppError::Validation("激活码不能为空".into()));
        }
        if req.signature.is_empty() {
            return Err(AppError::Validation("签名不能为空".into()));
        }
        if req.timestamp == 0 {
            return Err(AppError::Validation("时间戳不能为空".into()));
        }
        Ok(())
    }

    fn matches_query_params(&self, device: &Device, params: &DeviceQueryParams) -> bool {
        if let Some(status) = &params.status {
            if device.status.as_str() != status {
                return false;
            }
        }
        if let Some(device_type) = &params.device_type {
            if &device.device_type != device_type {
                return false;
            }
        }
        if let Some(tenant_id) = &params.tenant_id {
            if &device.tenant_id != tenant_id {
                return false;
            }
        }
        if let (Some(key), Some(value)) = (&params.label_key, &params.label_value) {
            match device.labels.get(key) {
                Some(v) if v == value => {}
                _ => return false,
            }
        }
        if let Some(tag) = &params.tag {
            if !device.tags.contains(tag) {
                return false;
            }
        }
        if let Some(before) = params.last_heartbeat_before {
            if let Some(hb_at) = device.last_heartbeat_at {
                if hb_at > before {
                    return false;
                }
            }
        }
        if let Some(after) = params.last_heartbeat_after {
            if let Some(hb_at) = device.last_heartbeat_at {
                if hb_at < after {
                    return false;
                }
            }
        }
        true
    }

    fn generate_device_secret(&self) -> String {
        let mut rng = rand::thread_rng();
        let bytes: Vec<u8> = (0..32).map(|_| rng.gen()).collect();
        BASE64.encode(bytes)
    }

    fn generate_activation_code(&self) -> String {
        let mut rng = rand::thread_rng();
        let code: u64 = rng.gen_range(100000..999999);
        format!("{:06}", code)
    }

    fn generate_api_key(&self) -> String {
        let mut rng = rand::thread_rng();
        let bytes: Vec<u8> = (0..24).map(|_| rng.gen()).collect();
        format!("ak-{}", BASE64.encode(bytes))
    }

    fn generate_session_token(&self) -> String {
        let mut rng = rand::thread_rng();
        let bytes: Vec<u8> = (0..48).map(|_| rng.gen()).collect();
        format!("st-{}", BASE64.encode(bytes))
    }

    fn generate_certificate(&self, device: &Device) -> (String, String, String) {
        let cert = format!(
            "-----BEGIN CERTIFICATE-----\n\
             Device: {}\n\
             Tenant: {}\n\
             Created: {}\n\
             -----END CERTIFICATE-----",
            device.device_id,
            device.tenant_id,
            Utc::now().to_rfc3339()
        );
        let priv_key = format!("-----BEGIN PRIVATE KEY-----\nprivate-key-for-{}\n-----END PRIVATE KEY-----", device.device_id);
        let pub_key = format!("-----BEGIN PUBLIC KEY-----\npublic-key-for-{}\n-----END PUBLIC KEY-----", device.device_id);
        (cert, priv_key, pub_key)
    }

    pub async fn check_offline_devices(&self) -> usize {
        let now = Utc::now();
        let mut offline_count = 0;

        for mut device in self.devices.iter_mut() {
            if device.status == DeviceStatus::Active {
                if let Some(last_hb) = device.last_heartbeat_at {
                    let timeout = Duration::seconds((device.heartbeat_interval * 3) as i64);
                    if now - last_hb > timeout {
                        device.status = DeviceStatus::Offline;
                        device.last_disconnected_at = Some(now);
                        device.updated_at = now;
                        offline_count += 1;

                        let event = DomainEvent::new(
                            "device.disconnected",
                            &device.device_id,
                            json!({
                                "device_id": device.device_id,
                                "reason": "heartbeat_timeout",
                                "last_heartbeat": last_hb,
                            }),
                            "",
                        );

                        let publisher = self.event_publisher.clone();
                        tokio::spawn(async move {
                            if let Err(e) = publisher.publish(event).await {
                                error!(error = %e, "Failed to publish device.disconnected event");
                            }
                        });
                    }
                }
            }
        }

        if offline_count > 0 {
            warn!(count = offline_count, "Devices marked as offline due to heartbeat timeout");
        }

        offline_count
    }

    pub async fn cleanup_expired_sessions(&self) -> usize {
        let now = Utc::now();
        let mut removed = 0;

        self.sessions.retain(|_, session| {
            if session.expires_at < now {
                removed += 1;
                false
            } else {
                true
            }
        });

        if removed > 0 {
            debug!(count = removed, "Expired sessions cleaned up");
        }

        removed
    }
}
