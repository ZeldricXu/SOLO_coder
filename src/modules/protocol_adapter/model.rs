use serde::{Serialize, Deserialize};
use chrono::{DateTime, Utc};
use serde_json::Value;
use std::collections::HashMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ProtocolType {
    Modbus,
    OpcUa,
    Mqtt,
    Http,
    Coap,
    SiemensS7,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum DriverStatus {
    Loaded,
    Unloaded,
    Loading,
    Unloading,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ConnectionStatus {
    Connected,
    Disconnected,
    Connecting,
    Reconnecting,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProtocolDriver {
    pub driver_id: String,
    pub protocol_type: ProtocolType,
    pub name: String,
    pub version: String,
    pub description: String,
    pub author: String,
    pub status: DriverStatus,
    pub library_path: String,
    pub config_schema: Value,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub capabilities: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceConnection {
    pub connection_id: String,
    pub device_id: String,
    pub driver_id: String,
    pub protocol_type: ProtocolType,
    pub name: String,
    pub endpoint: String,
    pub port: u16,
    pub config: HashMap<String, Value>,
    pub status: ConnectionStatus,
    pub last_connected_at: Option<DateTime<Utc>>,
    pub last_disconnected_at: Option<DateTime<Utc>>,
    pub reconnect_attempts: u32,
    pub max_reconnect_attempts: u32,
    pub reconnect_interval_seconds: u64,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataPoint {
    pub point_id: String,
    pub connection_id: String,
    pub name: String,
    pub address: String,
    pub data_type: String,
    pub sampling_interval_ms: u32,
    pub scaling_factor: Option<f64>,
    pub offset: Option<f64>,
    pub unit: Option<String>,
    pub description: String,
    pub enabled: bool,
    pub last_value: Option<Value>,
    pub last_updated_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConversionRule {
    pub rule_id: String,
    pub name: String,
    pub description: String,
    pub source_point_id: String,
    pub target_field: String,
    pub expression: String,
    pub condition: Option<String>,
    pub enabled: bool,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ForwardTargetType {
    RuleEngine,
    DeviceShadow,
    Cloud,
    MessageQueue,
    HttpEndpoint,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ForwardTarget {
    pub target_id: String,
    pub name: String,
    pub target_type: ForwardTargetType,
    pub endpoint: String,
    pub config: HashMap<String, Value>,
    pub enabled: bool,
    pub batch_size: u32,
    pub retry_attempts: u32,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConvertedData {
    pub data_id: String,
    pub device_id: String,
    pub connection_id: String,
    pub point_id: String,
    pub timestamp: DateTime<Utc>,
    pub raw_value: Value,
    pub converted_value: Value,
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DriverLoadRequest {
    pub protocol_type: ProtocolType,
    pub name: String,
    pub version: String,
    pub library_path: String,
    pub description: String,
    pub author: String,
    pub config_schema: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectionCreateRequest {
    pub device_id: String,
    pub driver_id: String,
    pub protocol_type: ProtocolType,
    pub name: String,
    pub endpoint: String,
    pub port: u16,
    pub config: HashMap<String, Value>,
    pub max_reconnect_attempts: u32,
    pub reconnect_interval_seconds: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataPointCreateRequest {
    pub connection_id: String,
    pub name: String,
    pub address: String,
    pub data_type: String,
    pub sampling_interval_ms: u32,
    pub scaling_factor: Option<f64>,
    pub offset: Option<f64>,
    pub unit: Option<String>,
    pub description: String,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConversionRuleCreateRequest {
    pub name: String,
    pub description: String,
    pub source_point_id: String,
    pub target_field: String,
    pub expression: String,
    pub condition: Option<String>,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ForwardTargetCreateRequest {
    pub name: String,
    pub target_type: ForwardTargetType,
    pub endpoint: String,
    pub config: HashMap<String, Value>,
    pub enabled: bool,
    pub batch_size: u32,
    pub retry_attempts: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DriverResponse {
    pub driver_id: String,
    pub protocol_type: ProtocolType,
    pub name: String,
    pub version: String,
    pub description: String,
    pub author: String,
    pub status: DriverStatus,
    pub capabilities: Vec<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectionResponse {
    pub connection_id: String,
    pub device_id: String,
    pub driver_id: String,
    pub protocol_type: ProtocolType,
    pub name: String,
    pub endpoint: String,
    pub port: u16,
    pub status: ConnectionStatus,
    pub last_connected_at: Option<DateTime<Utc>>,
    pub reconnect_attempts: u32,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataPointResponse {
    pub point_id: String,
    pub connection_id: String,
    pub name: String,
    pub address: String,
    pub data_type: String,
    pub sampling_interval_ms: u32,
    pub unit: Option<String>,
    pub enabled: bool,
    pub last_value: Option<Value>,
    pub last_updated_at: Option<DateTime<Utc>>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConversionRuleResponse {
    pub rule_id: String,
    pub name: String,
    pub source_point_id: String,
    pub target_field: String,
    pub enabled: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ForwardTargetResponse {
    pub target_id: String,
    pub name: String,
    pub target_type: ForwardTargetType,
    pub endpoint: String,
    pub enabled: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DataQueryRequest {
    pub connection_id: Option<String>,
    pub point_id: Option<String>,
    pub start_time: Option<DateTime<Utc>>,
    pub end_time: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectionCommandRequest {
    pub command: ConnectionCommand,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum ConnectionCommand {
    Connect,
    Disconnect,
    Reconnect,
}

impl ProtocolDriver {
    pub fn new(
        driver_id: impl Into<String>,
        protocol_type: ProtocolType,
        name: impl Into<String>,
        version: impl Into<String>,
        library_path: impl Into<String>,
    ) -> Self {
        let now = Utc::now();
        Self {
            driver_id: driver_id.into(),
            protocol_type,
            name: name.into(),
            version: version.into(),
            description: String::new(),
            author: String::new(),
            status: DriverStatus::Loading,
            library_path: library_path.into(),
            config_schema: Value::Null,
            created_at: now,
            updated_at: now,
            capabilities: Vec::new(),
        }
    }

    pub fn mark_loaded(&mut self) {
        self.status = DriverStatus::Loaded;
        self.updated_at = Utc::now();
    }

    pub fn mark_unloaded(&mut self) {
        self.status = DriverStatus::Unloaded;
        self.updated_at = Utc::now();
    }

    pub fn mark_error(&mut self) {
        self.status = DriverStatus::Error;
        self.updated_at = Utc::now();
    }
}

impl DeviceConnection {
    pub fn new(
        connection_id: impl Into<String>,
        device_id: impl Into<String>,
        driver_id: impl Into<String>,
        protocol_type: ProtocolType,
        name: impl Into<String>,
        endpoint: impl Into<String>,
        port: u16,
    ) -> Self {
        let now = Utc::now();
        Self {
            connection_id: connection_id.into(),
            device_id: device_id.into(),
            driver_id: driver_id.into(),
            protocol_type,
            name: name.into(),
            endpoint: endpoint.into(),
            port,
            config: HashMap::new(),
            status: ConnectionStatus::Disconnected,
            last_connected_at: None,
            last_disconnected_at: None,
            reconnect_attempts: 0,
            max_reconnect_attempts: 5,
            reconnect_interval_seconds: 10,
            created_at: now,
            updated_at: now,
        }
    }

    pub fn mark_connected(&mut self) {
        self.status = ConnectionStatus::Connected;
        self.last_connected_at = Some(Utc::now());
        self.reconnect_attempts = 0;
        self.updated_at = Utc::now();
    }

    pub fn mark_disconnected(&mut self) {
        self.status = ConnectionStatus::Disconnected;
        self.last_disconnected_at = Some(Utc::now());
        self.updated_at = Utc::now();
    }

    pub fn mark_connecting(&mut self) {
        self.status = ConnectionStatus::Connecting;
        self.updated_at = Utc::now();
    }

    pub fn mark_reconnecting(&mut self) {
        self.status = ConnectionStatus::Reconnecting;
        self.reconnect_attempts += 1;
        self.updated_at = Utc::now();
    }

    pub fn mark_error(&mut self) {
        self.status = ConnectionStatus::Error;
        self.updated_at = Utc::now();
    }

    pub fn can_reconnect(&self) -> bool {
        self.reconnect_attempts < self.max_reconnect_attempts
    }
}

impl DataPoint {
    pub fn new(
        point_id: impl Into<String>,
        connection_id: impl Into<String>,
        name: impl Into<String>,
        address: impl Into<String>,
        data_type: impl Into<String>,
        sampling_interval_ms: u32,
    ) -> Self {
        let now = Utc::now();
        Self {
            point_id: point_id.into(),
            connection_id: connection_id.into(),
            name: name.into(),
            address: address.into(),
            data_type: data_type.into(),
            sampling_interval_ms,
            scaling_factor: None,
            offset: None,
            unit: None,
            description: String::new(),
            enabled: true,
            last_value: None,
            last_updated_at: None,
            created_at: now,
            updated_at: now,
        }
    }

    pub fn update_value(&mut self, value: Value) {
        self.last_value = Some(value);
        self.last_updated_at = Some(Utc::now());
        self.updated_at = Utc::now();
    }

    pub fn apply_scaling(&self, value: f64) -> f64 {
        let factor = self.scaling_factor.unwrap_or(1.0);
        let offset = self.offset.unwrap_or(0.0);
        value * factor + offset
    }
}

impl ConversionRule {
    pub fn new(
        rule_id: impl Into<String>,
        name: impl Into<String>,
        source_point_id: impl Into<String>,
        target_field: impl Into<String>,
        expression: impl Into<String>,
    ) -> Self {
        let now = Utc::now();
        Self {
            rule_id: rule_id.into(),
            name: name.into(),
            description: String::new(),
            source_point_id: source_point_id.into(),
            target_field: target_field.into(),
            expression: expression.into(),
            condition: None,
            enabled: true,
            created_at: now,
            updated_at: now,
        }
    }
}

impl ForwardTarget {
    pub fn new(
        target_id: impl Into<String>,
        name: impl Into<String>,
        target_type: ForwardTargetType,
        endpoint: impl Into<String>,
    ) -> Self {
        let now = Utc::now();
        Self {
            target_id: target_id.into(),
            name: name.into(),
            target_type,
            endpoint: endpoint.into(),
            config: HashMap::new(),
            enabled: true,
            batch_size: 100,
            retry_attempts: 3,
            created_at: now,
            updated_at: now,
        }
    }
}
