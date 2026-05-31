use std::sync::Arc;
use dashmap::DashMap;
use serde_json::{json, Value};
use tracing::{info, warn, debug, error};
use uuid::Uuid;
use tokio::time::{self, Duration};

use crate::common::error::{AppError, AppResult};
use crate::common::context::RequestContext;
use crate::common::event::DomainEvent;
use crate::common::context::AuditLogger;
use crate::common::metrics::MetricsCollector;
use crate::ports::mod::EventPublisherPort;
use crate::ports::mod::CloudSyncPort;
use crate::ports::mod::MessageQueuePort;
use super::model::*;

pub struct ProtocolAdapterService {
    drivers: Arc<DashMap<String, ProtocolDriver>>,
    connections: Arc<DashMap<String, DeviceConnection>>,
    data_points: Arc<DashMap<String, DataPoint>>,
    conversion_rules: Arc<DashMap<String, ConversionRule>>,
    forward_targets: Arc<DashMap<String, ForwardTarget>>,
    converted_data_cache: Arc<DashMap<String, ConvertedData>>,
    event_publisher: Arc<dyn EventPublisherPort>,
    cloud_sync: Arc<dyn CloudSyncPort>,
    message_queue: Arc<dyn MessageQueuePort>,
    audit_logger: Arc<AuditLogger>,
    metrics: MetricsCollector,
}

impl ProtocolAdapterService {
    pub fn new(
        event_publisher: Arc<dyn EventPublisherPort>,
        cloud_sync: Arc<dyn CloudSyncPort>,
        message_queue: Arc<dyn MessageQueuePort>,
        audit_logger: Arc<AuditLogger>,
    ) -> Arc<Self> {
        Arc::new(Self {
            drivers: Arc::new(DashMap::new()),
            connections: Arc::new(DashMap::new()),
            data_points: Arc::new(DashMap::new()),
            conversion_rules: Arc::new(DashMap::new()),
            forward_targets: Arc::new(DashMap::new()),
            converted_data_cache: Arc::new(DashMap::new()),
            event_publisher,
            cloud_sync,
            message_queue,
            audit_logger,
            metrics: MetricsCollector::new().with_dimension("module", "protocol_adapter"),
        })
    }

    pub async fn load_driver(&self, ctx: &RequestContext, req: DriverLoadRequest) -> AppResult<DriverResponse> {
        let start = std::time::Instant::now();
        debug!(protocol_type = ?req.protocol_type, name = %req.name, "Loading protocol driver");

        self.validate_driver_request(&req)?;

        let driver_id = Uuid::new_v4().to_string();
        let mut driver = ProtocolDriver::new(
            driver_id.clone(),
            req.protocol_type.clone(),
            req.name.clone(),
            req.version.clone(),
            req.library_path.clone(),
        );
        driver.description = req.description;
        driver.author = req.author;
        driver.config_schema = req.config_schema;

        self.simulate_driver_load(&driver).await?;

        driver.mark_loaded();
        self.drivers.insert(driver_id.clone(), driver.clone());

        let event = DomainEvent::new(
            "driver.loaded",
            &driver_id,
            json!({
                "driver_id": driver_id,
                "protocol_type": format!("{:?}", req.protocol_type),
                "name": req.name,
                "version": req.version,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "driver.load",
            "protocol_adapter",
            &driver_id,
            true,
            json!({
                "protocol_type": format!("{:?}", req.protocol_type),
                "name": req.name,
                "version": req.version,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(DriverResponse {
            driver_id: driver.driver_id,
            protocol_type: driver.protocol_type,
            name: driver.name,
            version: driver.version,
            description: driver.description,
            author: driver.author,
            status: driver.status,
            capabilities: driver.capabilities,
            created_at: driver.created_at,
        })
    }

    pub async fn unload_driver(&self, ctx: &RequestContext, driver_id: &str) -> AppResult<()> {
        let start = std::time::Instant::now();
        info!(driver_id = %driver_id, "Unloading protocol driver");

        let mut driver = self.drivers.get_mut(driver_id)
            .ok_or_else(|| AppError::NotFound(format!("驱动不存在: {}", driver_id)))?;

        driver.mark_unloaded();
        driver.mark_error();

        let event = DomainEvent::new(
            "driver.unloaded",
            driver_id,
            json!({
                "driver_id": driver_id,
                "protocol_type": format!("{:?}", driver.protocol_type),
                "name": driver.name,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "driver.unload",
            "protocol_adapter",
            driver_id,
            true,
            json!({
                "protocol_type": format!("{:?}", driver.protocol_type),
                "name": driver.name,
            }),
        );

        drop(driver);
        self.drivers.remove(driver_id);

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(())
    }

    pub async fn get_driver(&self, ctx: &RequestContext, driver_id: &str) -> AppResult<DriverResponse> {
        let start = std::time::Instant::now();
        debug!(driver_id = %driver_id, "Getting driver info");

        let driver = self.drivers.get(driver_id)
            .ok_or_else(|| AppError::NotFound(format!("驱动不存在: {}", driver_id)))?;

        self.audit_logger.log_operation(
            ctx,
            "driver.get",
            "protocol_adapter",
            driver_id,
            true,
            json!({ "name": driver.name }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(DriverResponse {
            driver_id: driver.driver_id.clone(),
            protocol_type: driver.protocol_type.clone(),
            name: driver.name.clone(),
            version: driver.version.clone(),
            description: driver.description.clone(),
            author: driver.author.clone(),
            status: driver.status.clone(),
            capabilities: driver.capabilities.clone(),
            created_at: driver.created_at,
        })
    }

    pub async fn list_drivers(&self, page: u32, page_size: u32) -> AppResult<(Vec<DriverResponse>, u64)> {
        let items: Vec<DriverResponse> = self.drivers.iter()
            .map(|d| DriverResponse {
                driver_id: d.driver_id.clone(),
                protocol_type: d.protocol_type.clone(),
                name: d.name.clone(),
                version: d.version.clone(),
                description: d.description.clone(),
                author: d.author.clone(),
                status: d.status.clone(),
                capabilities: d.capabilities.clone(),
                created_at: d.created_at,
            })
            .collect();
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }

    pub async fn create_connection(&self, ctx: &RequestContext, req: ConnectionCreateRequest) -> AppResult<ConnectionResponse> {
        let start = std::time::Instant::now();
        debug!(device_id = %req.device_id, driver_id = %req.driver_id, "Creating device connection");

        self.validate_connection_request(&req)?;

        if !self.drivers.contains_key(&req.driver_id) {
            return Err(AppError::NotFound(format!("驱动不存在: {}", req.driver_id)));
        }

        let connection_id = Uuid::new_v4().to_string();
        let mut connection = DeviceConnection::new(
            connection_id.clone(),
            req.device_id.clone(),
            req.driver_id.clone(),
            req.protocol_type.clone(),
            req.name.clone(),
            req.endpoint.clone(),
            req.port,
        );
        connection.config = req.config;
        connection.max_reconnect_attempts = req.max_reconnect_attempts;
        connection.reconnect_interval_seconds = req.reconnect_interval_seconds;

        self.connections.insert(connection_id.clone(), connection.clone());

        self.audit_logger.log_operation(
            ctx,
            "connection.create",
            "protocol_adapter",
            &connection_id,
            true,
            json!({
                "device_id": req.device_id,
                "driver_id": req.driver_id,
                "endpoint": req.endpoint,
                "port": req.port,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(ConnectionResponse {
            connection_id: connection.connection_id,
            device_id: connection.device_id,
            driver_id: connection.driver_id,
            protocol_type: connection.protocol_type,
            name: connection.name,
            endpoint: connection.endpoint,
            port: connection.port,
            status: connection.status,
            last_connected_at: connection.last_connected_at,
            reconnect_attempts: connection.reconnect_attempts,
            created_at: connection.created_at,
        })
    }

    pub async fn connect_device(&self, ctx: &RequestContext, connection_id: &str) -> AppResult<ConnectionResponse> {
        let start = std::time::Instant::now();
        info!(connection_id = %connection_id, "Connecting device");

        let mut connection = self.connections.get_mut(connection_id)
            .ok_or_else(|| AppError::NotFound(format!("连接不存在: {}", connection_id)))?;

        connection.mark_connecting();

        match self.simulate_connect(&connection).await {
            Ok(_) => {
                connection.mark_connected();

                let event = DomainEvent::new(
                    "connection.connected",
                    connection_id,
                    json!({
                        "connection_id": connection_id,
                        "device_id": connection.device_id,
                        "endpoint": connection.endpoint,
                        "port": connection.port,
                    }),
                    &ctx.trace_id,
                );
                self.event_publisher.publish(event).await?;

                self.audit_logger.log_operation(
                    ctx,
                    "connection.connect",
                    "protocol_adapter",
                    connection_id,
                    true,
                    json!({ "device_id": connection.device_id }),
                );
            }
            Err(e) => {
                connection.mark_error();
                self.metrics.record_error(start.elapsed().as_millis() as u64);
                return Err(e);
            }
        }

        let response = ConnectionResponse {
            connection_id: connection.connection_id.clone(),
            device_id: connection.device_id.clone(),
            driver_id: connection.driver_id.clone(),
            protocol_type: connection.protocol_type.clone(),
            name: connection.name.clone(),
            endpoint: connection.endpoint.clone(),
            port: connection.port,
            status: connection.status.clone(),
            last_connected_at: connection.last_connected_at,
            reconnect_attempts: connection.reconnect_attempts,
            created_at: connection.created_at,
        };

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(response)
    }

    pub async fn disconnect_device(&self, ctx: &RequestContext, connection_id: &str) -> AppResult<ConnectionResponse> {
        let start = std::time::Instant::now();
        info!(connection_id = %connection_id, "Disconnecting device");

        let mut connection = self.connections.get_mut(connection_id)
            .ok_or_else(|| AppError::NotFound(format!("连接不存在: {}", connection_id)))?;

        connection.mark_disconnected();

        let event = DomainEvent::new(
            "connection.disconnected",
            connection_id,
            json!({
                "connection_id": connection_id,
                "device_id": connection.device_id,
                "endpoint": connection.endpoint,
                "port": connection.port,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "connection.disconnect",
            "protocol_adapter",
            connection_id,
            true,
            json!({ "device_id": connection.device_id }),
        );

        let response = ConnectionResponse {
            connection_id: connection.connection_id.clone(),
            device_id: connection.device_id.clone(),
            driver_id: connection.driver_id.clone(),
            protocol_type: connection.protocol_type.clone(),
            name: connection.name.clone(),
            endpoint: connection.endpoint.clone(),
            port: connection.port,
            status: connection.status.clone(),
            last_connected_at: connection.last_connected_at,
            reconnect_attempts: connection.reconnect_attempts,
            created_at: connection.created_at,
        };

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(response)
    }

    pub async fn get_connection(&self, ctx: &RequestContext, connection_id: &str) -> AppResult<ConnectionResponse> {
        let start = std::time::Instant::now();
        debug!(connection_id = %connection_id, "Getting connection info");

        let connection = self.connections.get(connection_id)
            .ok_or_else(|| AppError::NotFound(format!("连接不存在: {}", connection_id)))?;

        self.audit_logger.log_operation(
            ctx,
            "connection.get",
            "protocol_adapter",
            connection_id,
            true,
            json!({ "device_id": connection.device_id }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(ConnectionResponse {
            connection_id: connection.connection_id.clone(),
            device_id: connection.device_id.clone(),
            driver_id: connection.driver_id.clone(),
            protocol_type: connection.protocol_type.clone(),
            name: connection.name.clone(),
            endpoint: connection.endpoint.clone(),
            port: connection.port,
            status: connection.status.clone(),
            last_connected_at: connection.last_connected_at,
            reconnect_attempts: connection.reconnect_attempts,
            created_at: connection.created_at,
        })
    }

    pub async fn list_connections(&self, page: u32, page_size: u32) -> AppResult<(Vec<ConnectionResponse>, u64)> {
        let items: Vec<ConnectionResponse> = self.connections.iter()
            .map(|c| ConnectionResponse {
                connection_id: c.connection_id.clone(),
                device_id: c.device_id.clone(),
                driver_id: c.driver_id.clone(),
                protocol_type: c.protocol_type.clone(),
                name: c.name.clone(),
                endpoint: c.endpoint.clone(),
                port: c.port,
                status: c.status.clone(),
                last_connected_at: c.last_connected_at,
                reconnect_attempts: c.reconnect_attempts,
                created_at: c.created_at,
            })
            .collect();
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }

    pub async fn delete_connection(&self, ctx: &RequestContext, connection_id: &str) -> AppResult<()> {
        if self.connections.remove(connection_id).is_none() {
            return Err(AppError::NotFound(format!("连接不存在: {}", connection_id)));
        }

        self.data_points.retain(|_, v| v.connection_id != connection_id);

        self.audit_logger.log_operation(
            ctx,
            "connection.delete",
            "protocol_adapter",
            connection_id,
            true,
            json!({}),
        );

        Ok(())
    }

    pub async fn create_data_point(&self, ctx: &RequestContext, req: DataPointCreateRequest) -> AppResult<DataPointResponse> {
        let start = std::time::Instant::now();
        debug!(connection_id = %req.connection_id, name = %req.name, "Creating data point");

        self.validate_data_point_request(&req)?;

        if !self.connections.contains_key(&req.connection_id) {
            return Err(AppError::NotFound(format!("连接不存在: {}", req.connection_id)));
        }

        let point_id = Uuid::new_v4().to_string();
        let mut data_point = DataPoint::new(
            point_id.clone(),
            req.connection_id.clone(),
            req.name.clone(),
            req.address.clone(),
            req.data_type.clone(),
            req.sampling_interval_ms,
        );
        data_point.scaling_factor = req.scaling_factor;
        data_point.offset = req.offset;
        data_point.unit = req.unit;
        data_point.description = req.description;
        data_point.enabled = req.enabled;

        self.data_points.insert(point_id.clone(), data_point.clone());

        self.audit_logger.log_operation(
            ctx,
            "data_point.create",
            "protocol_adapter",
            &point_id,
            true,
            json!({
                "connection_id": req.connection_id,
                "name": req.name,
                "address": req.address,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(DataPointResponse {
            point_id: data_point.point_id,
            connection_id: data_point.connection_id,
            name: data_point.name,
            address: data_point.address,
            data_type: data_point.data_type,
            sampling_interval_ms: data_point.sampling_interval_ms,
            unit: data_point.unit,
            enabled: data_point.enabled,
            last_value: data_point.last_value,
            last_updated_at: data_point.last_updated_at,
            created_at: data_point.created_at,
        })
    }

    pub async fn get_data_point(&self, ctx: &RequestContext, point_id: &str) -> AppResult<DataPointResponse> {
        let start = std::time::Instant::now();
        debug!(point_id = %point_id, "Getting data point");

        let point = self.data_points.get(point_id)
            .ok_or_else(|| AppError::NotFound(format!("数据点不存在: {}", point_id)))?;

        self.audit_logger.log_operation(
            ctx,
            "data_point.get",
            "protocol_adapter",
            point_id,
            true,
            json!({ "name": point.name }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(DataPointResponse {
            point_id: point.point_id.clone(),
            connection_id: point.connection_id.clone(),
            name: point.name.clone(),
            address: point.address.clone(),
            data_type: point.data_type.clone(),
            sampling_interval_ms: point.sampling_interval_ms,
            unit: point.unit.clone(),
            enabled: point.enabled,
            last_value: point.last_value.clone(),
            last_updated_at: point.last_updated_at,
            created_at: point.created_at,
        })
    }

    pub async fn list_data_points(&self, connection_id: Option<String>, page: u32, page_size: u32) -> AppResult<(Vec<DataPointResponse>, u64)> {
        let items: Vec<DataPointResponse> = self.data_points.iter()
            .filter(|p| connection_id.as_ref().map_or(true, |cid| p.connection_id == *cid))
            .map(|p| DataPointResponse {
                point_id: p.point_id.clone(),
                connection_id: p.connection_id.clone(),
                name: p.name.clone(),
                address: p.address.clone(),
                data_type: p.data_type.clone(),
                sampling_interval_ms: p.sampling_interval_ms,
                unit: p.unit.clone(),
                enabled: p.enabled,
                last_value: p.last_value.clone(),
                last_updated_at: p.last_updated_at,
                created_at: p.created_at,
            })
            .collect();
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }

    pub async fn delete_data_point(&self, ctx: &RequestContext, point_id: &str) -> AppResult<()> {
        if self.data_points.remove(point_id).is_none() {
            return Err(AppError::NotFound(format!("数据点不存在: {}", point_id)));
        }

        self.conversion_rules.retain(|_, v| v.source_point_id != point_id);

        self.audit_logger.log_operation(
            ctx,
            "data_point.delete",
            "protocol_adapter",
            point_id,
            true,
            json!({}),
        );

        Ok(())
    }

    pub async fn create_conversion_rule(&self, ctx: &RequestContext, req: ConversionRuleCreateRequest) -> AppResult<ConversionRuleResponse> {
        let start = std::time::Instant::now();
        debug!(name = %req.name, source_point_id = %req.source_point_id, "Creating conversion rule");

        self.validate_conversion_rule_request(&req)?;

        if !self.data_points.contains_key(&req.source_point_id) {
            return Err(AppError::NotFound(format!("数据点不存在: {}", req.source_point_id)));
        }

        let rule_id = Uuid::new_v4().to_string();
        let mut rule = ConversionRule::new(
            rule_id.clone(),
            req.name.clone(),
            req.source_point_id.clone(),
            req.target_field.clone(),
            req.expression.clone(),
        );
        rule.description = req.description;
        rule.condition = req.condition;
        rule.enabled = req.enabled;

        self.conversion_rules.insert(rule_id.clone(), rule.clone());

        self.audit_logger.log_operation(
            ctx,
            "conversion_rule.create",
            "protocol_adapter",
            &rule_id,
            true,
            json!({
                "name": req.name,
                "source_point_id": req.source_point_id,
                "target_field": req.target_field,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(ConversionRuleResponse {
            rule_id: rule.rule_id,
            name: rule.name,
            source_point_id: rule.source_point_id,
            target_field: rule.target_field,
            enabled: rule.enabled,
            created_at: rule.created_at,
        })
    }

    pub async fn list_conversion_rules(&self, page: u32, page_size: u32) -> AppResult<(Vec<ConversionRuleResponse>, u64)> {
        let items: Vec<ConversionRuleResponse> = self.conversion_rules.iter()
            .map(|r| ConversionRuleResponse {
                rule_id: r.rule_id.clone(),
                name: r.name.clone(),
                source_point_id: r.source_point_id.clone(),
                target_field: r.target_field.clone(),
                enabled: r.enabled,
                created_at: r.created_at,
            })
            .collect();
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }

    pub async fn delete_conversion_rule(&self, ctx: &RequestContext, rule_id: &str) -> AppResult<()> {
        if self.conversion_rules.remove(rule_id).is_none() {
            return Err(AppError::NotFound(format!("转换规则不存在: {}", rule_id)));
        }

        self.audit_logger.log_operation(
            ctx,
            "conversion_rule.delete",
            "protocol_adapter",
            rule_id,
            true,
            json!({}),
        );

        Ok(())
    }

    pub async fn create_forward_target(&self, ctx: &RequestContext, req: ForwardTargetCreateRequest) -> AppResult<ForwardTargetResponse> {
        let start = std::time::Instant::now();
        debug!(name = %req.name, target_type = ?req.target_type, "Creating forward target");

        self.validate_forward_target_request(&req)?;

        let target_id = Uuid::new_v4().to_string();
        let mut target = ForwardTarget::new(
            target_id.clone(),
            req.name.clone(),
            req.target_type.clone(),
            req.endpoint.clone(),
        );
        target.config = req.config;
        target.enabled = req.enabled;
        target.batch_size = req.batch_size;
        target.retry_attempts = req.retry_attempts;

        self.forward_targets.insert(target_id.clone(), target.clone());

        self.audit_logger.log_operation(
            ctx,
            "forward_target.create",
            "protocol_adapter",
            &target_id,
            true,
            json!({
                "name": req.name,
                "target_type": format!("{:?}", req.target_type),
                "endpoint": req.endpoint,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(ForwardTargetResponse {
            target_id: target.target_id,
            name: target.name,
            target_type: target.target_type,
            endpoint: target.endpoint,
            enabled: target.enabled,
            created_at: target.created_at,
        })
    }

    pub async fn list_forward_targets(&self, page: u32, page_size: u32) -> AppResult<(Vec<ForwardTargetResponse>, u64)> {
        let items: Vec<ForwardTargetResponse> = self.forward_targets.iter()
            .map(|t| ForwardTargetResponse {
                target_id: t.target_id.clone(),
                name: t.name.clone(),
                target_type: t.target_type.clone(),
                endpoint: t.endpoint.clone(),
                enabled: t.enabled,
                created_at: t.created_at,
            })
            .collect();
        let total = items.len() as u64;
        let start = ((page - 1) * page_size) as usize;
        let end = (start + page_size as usize).min(items.len());
        let paginated = items.into_iter().skip(start).take(end - start).collect();
        Ok((paginated, total))
    }

    pub async fn delete_forward_target(&self, ctx: &RequestContext, target_id: &str) -> AppResult<()> {
        if self.forward_targets.remove(target_id).is_none() {
            return Err(AppError::NotFound(format!("转发目标不存在: {}", target_id)));
        }

        self.audit_logger.log_operation(
            ctx,
            "forward_target.delete",
            "protocol_adapter",
            target_id,
            true,
            json!({}),
        );

        Ok(())
    }

    pub async fn collect_and_convert_data(&self, ctx: &RequestContext, point_id: &str, raw_value: Value) -> AppResult<ConvertedData> {
        let start = std::time::Instant::now();
        debug!(point_id = %point_id, "Collecting and converting data");

        let mut point = self.data_points.get_mut(point_id)
            .ok_or_else(|| AppError::NotFound(format!("数据点不存在: {}", point_id)))?;

        let converted_value = self.convert_value(&point, &raw_value)?;

        let connection = self.connections.get(&point.connection_id)
            .ok_or_else(|| AppError::NotFound(format!("连接不存在: {}", point.connection_id)))?;

        point.update_value(raw_value.clone());

        let converted_data = ConvertedData {
            data_id: Uuid::new_v4().to_string(),
            device_id: connection.device_id.clone(),
            connection_id: point.connection_id.clone(),
            point_id: point.point_id.clone(),
            timestamp: chrono::Utc::now(),
            raw_value: raw_value.clone(),
            converted_value: converted_value.clone(),
            metadata: self.build_metadata(&point, &connection),
        };

        self.converted_data_cache.insert(converted_data.data_id.clone(), converted_data.clone());

        let event = DomainEvent::new(
            "data.converted",
            &converted_data.data_id,
            json!({
                "data_id": converted_data.data_id,
                "device_id": converted_data.device_id,
                "point_id": point_id,
                "raw_value": raw_value,
                "converted_value": converted_value,
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "data.convert",
            "protocol_adapter",
            &converted_data.data_id,
            true,
            json!({
                "point_id": point_id,
                "raw_value": raw_value,
                "converted_value": converted_value,
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(converted_data)
    }

    pub async fn forward_data(&self, ctx: &RequestContext, data: ConvertedData) -> AppResult<()> {
        let start = std::time::Instant::now();
        debug!(data_id = %data.data_id, "Forwarding data to targets");

        let targets: Vec<ForwardTarget> = self.forward_targets.iter()
            .filter(|t| t.enabled)
            .map(|t| t.clone())
            .collect();

        for target in targets {
            if let Err(e) = self.forward_to_target(&data, &target).await {
                error!(target_id = %target.target_id, error = %e, "Failed to forward data");
                continue;
            }
        }

        let event = DomainEvent::new(
            "data.forwarded",
            &data.data_id,
            json!({
                "data_id": data.data_id,
                "device_id": data.device_id,
                "target_count": targets.len(),
            }),
            &ctx.trace_id,
        );
        self.event_publisher.publish(event).await?;

        self.audit_logger.log_operation(
            ctx,
            "data.forward",
            "protocol_adapter",
            &data.data_id,
            true,
            json!({
                "device_id": data.device_id,
                "target_count": targets.len(),
            }),
        );

        self.metrics.record_success(start.elapsed().as_millis() as u64);

        Ok(())
    }

    pub async fn start_reconnection_monitor(self: Arc<Self>) {
        info!("Starting reconnection monitor");

        tokio::spawn(async move {
            let mut interval = time::interval(Duration::from_secs(10));

            loop {
                interval.tick().await;

                let connections_to_reconnect: Vec<String> = self.connections.iter()
                    .filter(|c| {
                        matches!(c.status, ConnectionStatus::Disconnected | ConnectionStatus::Error)
                            && c.can_reconnect()
                    })
                    .map(|c| c.connection_id.clone())
                    .collect();

                for connection_id in connections_to_reconnect {
                    let self_clone = self.clone();
                    tokio::spawn(async move {
                        if let Some(mut conn) = self_clone.connections.get_mut(&connection_id) {
                            if conn.can_reconnect() {
                                info!(connection_id = %connection_id, attempt = conn.reconnect_attempts + 1, "Attempting reconnection");
                                conn.mark_reconnecting();

                                let ctx = RequestContext::new_with_random();
                                match self_clone.connect_device(&ctx, &connection_id).await {
                                    Ok(_) => {
                                        info!(connection_id = %connection_id, "Reconnection successful");
                                    }
                                    Err(e) => {
                                        warn!(connection_id = %connection_id, error = %e, "Reconnection failed");
                                        if let Some(mut c) = self_clone.connections.get_mut(&connection_id) {
                                            c.mark_error();
                                        }
                                    }
                                }
                            }
                        }
                    });
                }
            }
        });
    }

    async fn simulate_driver_load(&self, driver: &ProtocolDriver) -> AppResult<()> {
        time::sleep(Duration::from_millis(100)).await;

        if driver.library_path.is_empty() {
            return Err(AppError::Validation("驱动库路径不能为空".into()));
        }

        Ok(())
    }

    async fn simulate_connect(&self, connection: &DeviceConnection) -> AppResult<()> {
        time::sleep(Duration::from_millis(200)).await;

        if connection.endpoint.is_empty() {
            return Err(AppError::Validation("连接端点不能为空".into()));
        }

        Ok(())
    }

    fn convert_value(&self, point: &DataPoint, raw_value: &Value) -> AppResult<Value> {
        if let Some(num) = raw_value.as_f64() {
            let scaled = point.apply_scaling(num);
            Ok(json!(scaled))
        } else {
            Ok(raw_value.clone())
        }
    }

    fn build_metadata(&self, point: &DataPoint, connection: &DeviceConnection) -> std::collections::HashMap<String, String> {
        let mut metadata = std::collections::HashMap::new();
        metadata.insert("protocol_type".into(), format!("{:?}", connection.protocol_type));
        metadata.insert("driver_id".into(), connection.driver_id.clone());
        metadata.insert("data_type".into(), point.data_type.clone());
        if let Some(unit) = &point.unit {
            metadata.insert("unit".into(), unit.clone());
        }
        metadata
    }

    async fn forward_to_target(&self, data: &ConvertedData, target: &ForwardTarget) -> AppResult<()> {
        let payload = json!({
            "data_id": data.data_id,
            "device_id": data.device_id,
            "connection_id": data.connection_id,
            "point_id": data.point_id,
            "timestamp": data.timestamp,
            "value": data.converted_value,
            "metadata": data.metadata,
        });

        match target.target_type {
            ForwardTargetType::RuleEngine => {
                self.message_queue.send("rule_engine", payload.clone()).await?;
            }
            ForwardTargetType::DeviceShadow => {
                self.message_queue.send("device_shadow", payload.clone()).await?;
            }
            ForwardTargetType::Cloud => {
                self.cloud_sync.upload_data(payload.clone()).await?;
            }
            ForwardTargetType::MessageQueue => {
                let queue = target.config.get("queue")
                    .and_then(|v| v.as_str())
                    .unwrap_or("default");
                self.message_queue.send(queue, payload.clone()).await?;
            }
            ForwardTargetType::HttpEndpoint => {
                debug!(endpoint = %target.endpoint, "Forwarding to HTTP endpoint");
            }
        }

        Ok(())
    }

    fn validate_driver_request(&self, req: &DriverLoadRequest) -> AppResult<()> {
        if req.name.is_empty() {
            return Err(AppError::Validation("驱动名称不能为空".into()));
        }
        if req.version.is_empty() {
            return Err(AppError::Validation("驱动版本不能为空".into()));
        }
        if req.library_path.is_empty() {
            return Err(AppError::Validation("驱动库路径不能为空".into()));
        }
        Ok(())
    }

    fn validate_connection_request(&self, req: &ConnectionCreateRequest) -> AppResult<()> {
        if req.device_id.is_empty() {
            return Err(AppError::Validation("设备ID不能为空".into()));
        }
        if req.driver_id.is_empty() {
            return Err(AppError::Validation("驱动ID不能为空".into()));
        }
        if req.name.is_empty() {
            return Err(AppError::Validation("连接名称不能为空".into()));
        }
        if req.endpoint.is_empty() {
            return Err(AppError::Validation("连接端点不能为空".into()));
        }
        if req.port == 0 {
            return Err(AppError::Validation("端口不能为0".into()));
        }
        Ok(())
    }

    fn validate_data_point_request(&self, req: &DataPointCreateRequest) -> AppResult<()> {
        if req.connection_id.is_empty() {
            return Err(AppError::Validation("连接ID不能为空".into()));
        }
        if req.name.is_empty() {
            return Err(AppError::Validation("数据点名称不能为空".into()));
        }
        if req.address.is_empty() {
            return Err(AppError::Validation("数据点地址不能为空".into()));
        }
        if req.data_type.is_empty() {
            return Err(AppError::Validation("数据类型不能为空".into()));
        }
        if req.sampling_interval_ms == 0 {
            return Err(AppError::Validation("采样间隔不能为0".into()));
        }
        Ok(())
    }

    fn validate_conversion_rule_request(&self, req: &ConversionRuleCreateRequest) -> AppResult<()> {
        if req.name.is_empty() {
            return Err(AppError::Validation("规则名称不能为空".into()));
        }
        if req.source_point_id.is_empty() {
            return Err(AppError::Validation("源数据点ID不能为空".into()));
        }
        if req.target_field.is_empty() {
            return Err(AppError::Validation("目标字段不能为空".into()));
        }
        if req.expression.is_empty() {
            return Err(AppError::Validation("转换表达式不能为空".into()));
        }
        Ok(())
    }

    fn validate_forward_target_request(&self, req: &ForwardTargetCreateRequest) -> AppResult<()> {
        if req.name.is_empty() {
            return Err(AppError::Validation("目标名称不能为空".into()));
        }
        if req.endpoint.is_empty() {
            return Err(AppError::Validation("目标端点不能为空".into()));
        }
        if req.batch_size == 0 {
            return Err(AppError::Validation("批处理大小不能为0".into()));
        }
        Ok(())
    }

    pub fn get_metrics(&self) -> crate::common::metrics::StatsSnapshot {
        self.metrics.snapshot()
    }

    pub fn get_converted_data(&self, query: &DataQueryRequest) -> Vec<ConvertedData> {
        self.converted_data_cache.iter()
            .filter(|d| {
                query.connection_id.as_ref().map_or(true, |cid| d.connection_id == *cid)
                    && query.point_id.as_ref().map_or(true, |pid| d.point_id == *pid)
                    && query.start_time.map_or(true, |t| d.timestamp >= t)
                    && query.end_time.map_or(true, |t| d.timestamp <= t)
            })
            .map(|d| d.clone())
            .collect()
    }
}
