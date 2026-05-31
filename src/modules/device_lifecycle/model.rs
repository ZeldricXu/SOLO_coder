use serde::{Serialize, Deserialize};
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum DeviceStatus {
    Pending,
    Active,
    Inactive,
    Offline,
    Disabled,
    Deleted,
}

impl DeviceStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            DeviceStatus::Pending => "pending",
            DeviceStatus::Active => "active",
            DeviceStatus::Inactive => "inactive",
            DeviceStatus::Offline => "offline",
            DeviceStatus::Disabled => "disabled",
            DeviceStatus::Deleted => "deleted",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "pending" => Some(DeviceStatus::Pending),
            "active" => Some(DeviceStatus::Active),
            "inactive" => Some(DeviceStatus::Inactive),
            "offline" => Some(DeviceStatus::Offline),
            "disabled" => Some(DeviceStatus::Disabled),
            "deleted" => Some(DeviceStatus::Deleted),
            _ => None,
        }
    }

    pub fn can_transition_to(&self, next: &DeviceStatus) -> bool {
        match (self, next) {
            (DeviceStatus::Pending, DeviceStatus::Active) => true,
            (DeviceStatus::Pending, DeviceStatus::Disabled) => true,
            (DeviceStatus::Active, DeviceStatus::Inactive) => true,
            (DeviceStatus::Active, DeviceStatus::Offline) => true,
            (DeviceStatus::Active, DeviceStatus::Disabled) => true,
            (DeviceStatus::Active, DeviceStatus::Deleted) => true,
            (DeviceStatus::Inactive, DeviceStatus::Active) => true,
            (DeviceStatus::Inactive, DeviceStatus::Offline) => true,
            (DeviceStatus::Inactive, DeviceStatus::Disabled) => true,
            (DeviceStatus::Inactive, DeviceStatus::Deleted) => true,
            (DeviceStatus::Offline, DeviceStatus::Active) => true,
            (DeviceStatus::Offline, DeviceStatus::Disabled) => true,
            (DeviceStatus::Disabled, DeviceStatus::Active) => true,
            (DeviceStatus::Disabled, DeviceStatus::Deleted) => true,
            _ => false,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Device {
    pub device_id: String,
    pub device_name: String,
    pub device_type: String,
    pub manufacturer: String,
    pub model: String,
    pub serial_number: String,
    pub firmware_version: String,
    pub hardware_version: String,
    pub tenant_id: String,
    pub status: DeviceStatus,
    pub labels: HashMap<String, String>,
    pub tags: Vec<String>,
    pub activation_code: Option<String>,
    pub activation_code_expires_at: Option<DateTime<Utc>>,
    pub last_connected_at: Option<DateTime<Utc>>,
    pub last_disconnected_at: Option<DateTime<Utc>>,
    pub last_heartbeat_at: Option<DateTime<Utc>>,
    pub heartbeat_interval: u32,
    pub ip_address: Option<String>,
    pub location: Option<DeviceLocation>,
    pub metadata: HashMap<String, String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub registered_at: Option<DateTime<Utc>>,
    pub activated_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceLocation {
    pub latitude: f64,
    pub longitude: f64,
    pub altitude: Option<f64>,
    pub address: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceCredentials {
    pub device_id: String,
    pub device_secret: String,
    pub api_key: Option<String>,
    pub certificate_pem: Option<String>,
    pub certificate_expires_at: Option<DateTime<Utc>>,
    pub public_key: Option<String>,
    pub private_key: Option<String>,
    pub created_at: DateTime<Utc>,
    pub rotated_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Heartbeat {
    pub heartbeat_id: String,
    pub device_id: String,
    pub timestamp: DateTime<Utc>,
    pub uptime_seconds: u64,
    pub cpu_usage: Option<f32>,
    pub memory_usage: Option<f32>,
    pub disk_usage: Option<f32>,
    pub network_rx_bytes: Option<u64>,
    pub network_tx_bytes: Option<u64>,
    pub temperature: Option<f32>,
    pub battery_level: Option<f32>,
    pub signal_strength: Option<i32>,
    pub health_status: String,
    pub custom_metrics: HashMap<String, serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceSession {
    pub session_id: String,
    pub device_id: String,
    pub token: String,
    pub expires_at: DateTime<Utc>,
    pub created_at: DateTime<Utc>,
    pub last_activity_at: DateTime<Utc>,
    pub ip_address: Option<String>,
    pub user_agent: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthIndicator {
    pub name: String,
    pub status: String,
    pub value: f64,
    pub threshold: f64,
    pub unit: String,
    pub last_updated: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceRegisterRequest {
    pub device_name: String,
    pub device_type: String,
    pub manufacturer: String,
    pub model: String,
    pub serial_number: String,
    pub firmware_version: String,
    pub hardware_version: String,
    pub tenant_id: String,
    pub labels: Option<HashMap<String, String>>,
    pub tags: Option<Vec<String>>,
    pub heartbeat_interval: Option<u32>,
    pub location: Option<DeviceLocation>,
    pub metadata: Option<HashMap<String, String>>,
    pub generate_certificate: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceRegisterResponse {
    pub device_id: String,
    pub device_name: String,
    pub device_secret: String,
    pub activation_code: String,
    pub activation_code_expires_at: DateTime<Utc>,
    pub api_key: Option<String>,
    pub certificate_pem: Option<String>,
    pub status: String,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceActivateRequest {
    pub device_id: String,
    pub activation_code: String,
    pub signature: String,
    pub timestamp: i64,
    pub firmware_version: Option<String>,
    pub ip_address: Option<String>,
    pub location: Option<DeviceLocation>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceActivateResponse {
    pub device_id: String,
    pub status: String,
    pub session_token: String,
    pub session_expires_at: DateTime<Utc>,
    pub activated_at: DateTime<Utc>,
    pub server_time: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceAuthRequest {
    pub device_id: String,
    pub device_secret: String,
    pub signature: String,
    pub timestamp: i64,
    pub nonce: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceAuthResponse {
    pub device_id: String,
    pub session_token: String,
    pub expires_at: DateTime<Utc>,
    pub tenant_id: String,
    pub permissions: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HeartbeatRequest {
    pub device_id: String,
    pub signature: String,
    pub timestamp: i64,
    pub uptime_seconds: u64,
    pub cpu_usage: Option<f32>,
    pub memory_usage: Option<f32>,
    pub disk_usage: Option<f32>,
    pub network_rx_bytes: Option<u64>,
    pub network_tx_bytes: Option<u64>,
    pub temperature: Option<f32>,
    pub battery_level: Option<f32>,
    pub signal_strength: Option<i32>,
    pub health_status: Option<String>,
    pub custom_metrics: Option<HashMap<String, serde_json::Value>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HeartbeatResponse {
    pub device_id: String,
    pub status: String,
    pub server_time: DateTime<Utc>,
    pub next_heartbeat_interval: u32,
    pub commands: Option<Vec<serde_json::Value>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceStatusUpdateRequest {
    pub status: String,
    pub reason: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceResponse {
    pub device_id: String,
    pub device_name: String,
    pub device_type: String,
    pub manufacturer: String,
    pub model: String,
    pub serial_number: String,
    pub firmware_version: String,
    pub hardware_version: String,
    pub tenant_id: String,
    pub status: String,
    pub labels: HashMap<String, String>,
    pub tags: Vec<String>,
    pub last_connected_at: Option<DateTime<Utc>>,
    pub last_heartbeat_at: Option<DateTime<Utc>>,
    pub heartbeat_interval: u32,
    pub ip_address: Option<String>,
    pub location: Option<DeviceLocation>,
    pub metadata: HashMap<String, String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
    pub registered_at: Option<DateTime<Utc>>,
    pub activated_at: Option<DateTime<Utc>>,
    pub health_indicators: Option<Vec<HealthIndicator>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceLabelUpdateRequest {
    pub labels: HashMap<String, String>,
    pub operation: LabelOperation,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum LabelOperation {
    Set,
    Remove,
    Replace,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceTagUpdateRequest {
    pub tags: Vec<String>,
    pub operation: TagOperation,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum TagOperation {
    Add,
    Remove,
    Replace,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DeviceQueryParams {
    pub status: Option<String>,
    pub device_type: Option<String>,
    pub tenant_id: Option<String>,
    pub label_key: Option<String>,
    pub label_value: Option<String>,
    pub tag: Option<String>,
    pub last_heartbeat_before: Option<DateTime<Utc>>,
    pub last_heartbeat_after: Option<DateTime<Utc>>,
}

impl Device {
    pub fn new(req: &DeviceRegisterRequest) -> Self {
        let now = Utc::now();
        Self {
            device_id: Uuid::new_v4().to_string(),
            device_name: req.device_name.clone(),
            device_type: req.device_type.clone(),
            manufacturer: req.manufacturer.clone(),
            model: req.model.clone(),
            serial_number: req.serial_number.clone(),
            firmware_version: req.firmware_version.clone(),
            hardware_version: req.hardware_version.clone(),
            tenant_id: req.tenant_id.clone(),
            status: DeviceStatus::Pending,
            labels: req.labels.clone().unwrap_or_default(),
            tags: req.tags.clone().unwrap_or_default(),
            activation_code: None,
            activation_code_expires_at: None,
            last_connected_at: None,
            last_disconnected_at: None,
            last_heartbeat_at: None,
            heartbeat_interval: req.heartbeat_interval.unwrap_or(60),
            ip_address: None,
            location: req.location.clone(),
            metadata: req.metadata.clone().unwrap_or_default(),
            created_at: now,
            updated_at: now,
            registered_at: None,
            activated_at: None,
        }
    }

    pub fn is_online(&self) -> bool {
        matches!(self.status, DeviceStatus::Active)
    }

    pub fn is_enabled(&self) -> bool {
        !matches!(self.status, DeviceStatus::Disabled | DeviceStatus::Deleted)
    }

    pub fn transition_status(&mut self, new_status: DeviceStatus) -> bool {
        if self.status.can_transition_to(&new_status) {
            self.status = new_status;
            self.updated_at = Utc::now();
            true
        } else {
            false
        }
    }

    pub fn to_response(&self, health_indicators: Option<Vec<HealthIndicator>>) -> DeviceResponse {
        DeviceResponse {
            device_id: self.device_id.clone(),
            device_name: self.device_name.clone(),
            device_type: self.device_type.clone(),
            manufacturer: self.manufacturer.clone(),
            model: self.model.clone(),
            serial_number: self.serial_number.clone(),
            firmware_version: self.firmware_version.clone(),
            hardware_version: self.hardware_version.clone(),
            tenant_id: self.tenant_id.clone(),
            status: self.status.as_str().to_string(),
            labels: self.labels.clone(),
            tags: self.tags.clone(),
            last_connected_at: self.last_connected_at,
            last_heartbeat_at: self.last_heartbeat_at,
            heartbeat_interval: self.heartbeat_interval,
            ip_address: self.ip_address.clone(),
            location: self.location.clone(),
            metadata: self.metadata.clone(),
            created_at: self.created_at,
            updated_at: self.updated_at,
            registered_at: self.registered_at,
            activated_at: self.activated_at,
            health_indicators,
        }
    }
}

impl Heartbeat {
    pub fn from_request(req: &HeartbeatRequest) -> Self {
        Self {
            heartbeat_id: Uuid::new_v4().to_string(),
            device_id: req.device_id.clone(),
            timestamp: Utc::now(),
            uptime_seconds: req.uptime_seconds,
            cpu_usage: req.cpu_usage,
            memory_usage: req.memory_usage,
            disk_usage: req.disk_usage,
            network_rx_bytes: req.network_rx_bytes,
            network_tx_bytes: req.network_tx_bytes,
            temperature: req.temperature,
            battery_level: req.battery_level,
            signal_strength: req.signal_strength,
            health_status: req.health_status.clone().unwrap_or_else(|| "healthy".to_string()),
            custom_metrics: req.custom_metrics.clone().unwrap_or_default(),
        }
    }

    pub fn to_health_indicators(&self) -> Vec<HealthIndicator> {
        let mut indicators = Vec::new();
        let now = Utc::now();

        if let Some(cpu) = self.cpu_usage {
            indicators.push(HealthIndicator {
                name: "cpu_usage".into(),
                status: if cpu > 90.0 { "critical" } else if cpu > 70.0 { "warning" } else { "healthy" }.into(),
                value: cpu as f64,
                threshold: 90.0,
                unit: "%".into(),
                last_updated: now,
            });
        }

        if let Some(memory) = self.memory_usage {
            indicators.push(HealthIndicator {
                name: "memory_usage".into(),
                status: if memory > 90.0 { "critical" } else if memory > 70.0 { "warning" } else { "healthy" }.into(),
                value: memory as f64,
                threshold: 90.0,
                unit: "%".into(),
                last_updated: now,
            });
        }

        if let Some(disk) = self.disk_usage {
            indicators.push(HealthIndicator {
                name: "disk_usage".into(),
                status: if disk > 95.0 { "critical" } else if disk > 80.0 { "warning" } else { "healthy" }.into(),
                value: disk as f64,
                threshold: 95.0,
                unit: "%".into(),
                last_updated: now,
            });
        }

        if let Some(temp) = self.temperature {
            indicators.push(HealthIndicator {
                name: "temperature".into(),
                status: if temp > 85.0 { "critical" } else if temp > 70.0 { "warning" } else { "healthy" }.into(),
                value: temp as f64,
                threshold: 85.0,
                unit: "°C".into(),
                last_updated: now,
            });
        }

        if let Some(battery) = self.battery_level {
            indicators.push(HealthIndicator {
                name: "battery_level".into(),
                status: if battery < 10.0 { "critical" } else if battery < 30.0 { "warning" } else { "healthy" }.into(),
                value: battery as f64,
                threshold: 10.0,
                unit: "%".into(),
                last_updated: now,
            });
        }

        indicators
    }
}

impl DeviceCredentials {
    pub fn new(device_id: &str, device_secret: &str) -> Self {
        Self {
            device_id: device_id.to_string(),
            device_secret: device_secret.to_string(),
            api_key: None,
            certificate_pem: None,
            certificate_expires_at: None,
            public_key: None,
            private_key: None,
            created_at: Utc::now(),
            rotated_at: None,
        }
    }
}

impl DeviceSession {
    pub fn new(device_id: &str, token: &str, ttl_seconds: i64) -> Self {
        let now = Utc::now();
        Self {
            session_id: Uuid::new_v4().to_string(),
            device_id: device_id.to_string(),
            token: token.to_string(),
            expires_at: now + chrono::Duration::seconds(ttl_seconds),
            created_at: now,
            last_activity_at: now,
            ip_address: None,
            user_agent: None,
        }
    }

    pub fn is_valid(&self) -> bool {
        Utc::now() < self.expires_at
    }

    pub fn touch(&mut self) {
        self.last_activity_at = Utc::now();
    }
}
