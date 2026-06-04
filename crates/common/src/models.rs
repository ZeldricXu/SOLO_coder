use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::atomic::AtomicU64;
use std::sync::atomic::Ordering::Relaxed;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum ContentType {
    LiveStream,
    Vod,
    StaticAsset,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum NodeRole {
    Edge,
    Parent,
    Origin,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum NodeStatus {
    Online,
    Offline,
    Degraded,
    Maintenance,
    StorageFull,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub enum TimeSlot {
    Slot5s,
    Slot10s,
    Slot30s,
}

impl TimeSlot {
    pub fn duration_ms(&self) -> u64 {
        match self {
            TimeSlot::Slot5s => 5000,
            TimeSlot::Slot10s => 10000,
            TimeSlot::Slot30s => 30000,
        }
    }

    pub fn from_timeout_ms(timeout_ms: u64) -> Self {
        if timeout_ms <= 5000 {
            TimeSlot::Slot5s
        } else if timeout_ms <= 10000 {
            TimeSlot::Slot10s
        } else {
            TimeSlot::Slot30s
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct EdgeNode {
    pub id: Uuid,
    pub ip_address: IpAddr,
    pub datacenter: String,
    pub region: String,
    pub bandwidth_capacity: u64,
    pub bandwidth_usage: f64,
    pub storage_capacity: u64,
    pub current_load: f64,
    pub status: NodeStatus,
    pub weight: u32,
    pub latitude: f64,
    pub longitude: f64,
    pub registered_at: DateTime<Utc>,
    pub last_heartbeat_ts: AtomicU64,
    pub role: NodeRole,
    pub parent_node_id: Option<Uuid>,
}

impl Clone for EdgeNode {
    fn clone(&self) -> Self {
        EdgeNode {
            id: self.id,
            ip_address: self.ip_address,
            datacenter: self.datacenter.clone(),
            region: self.region.clone(),
            bandwidth_capacity: self.bandwidth_capacity,
            bandwidth_usage: self.bandwidth_usage,
            storage_capacity: self.storage_capacity,
            current_load: self.current_load,
            status: self.status.clone(),
            weight: self.weight,
            latitude: self.latitude,
            longitude: self.longitude,
            registered_at: self.registered_at,
            last_heartbeat_ts: AtomicU64::new(self.last_heartbeat_ts.load(Relaxed)),
            role: self.role.clone(),
            parent_node_id: self.parent_node_id,
        }
    }
}

impl PartialEq for EdgeNode {
    fn eq(&self, other: &Self) -> bool {
        self.id == other.id
            && self.ip_address == other.ip_address
            && self.datacenter == other.datacenter
            && self.region == other.region
            && self.bandwidth_capacity == other.bandwidth_capacity
            && self.bandwidth_usage == other.bandwidth_usage
            && self.storage_capacity == other.storage_capacity
            && self.current_load == other.current_load
            && self.status == other.status
            && self.weight == other.weight
            && self.latitude == other.latitude
            && self.longitude == other.longitude
            && self.registered_at == other.registered_at
            && self.last_heartbeat_ts.load(Relaxed) == other.last_heartbeat_ts.load(Relaxed)
            && self.role == other.role
            && self.parent_node_id == other.parent_node_id
    }
}

impl EdgeNode {
    pub fn last_heartbeat(&self) -> Option<DateTime<Utc>> {
        let ts = self.last_heartbeat_ts.load(Relaxed);
        if ts == 0 {
            None
        } else {
            DateTime::<Utc>::from_timestamp_millis(ts as i64)
        }
    }

    pub fn last_heartbeat_ts(&self) -> u64 {
        self.last_heartbeat_ts.load(Relaxed)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeRegistration {
    pub ip: IpAddr,
    pub region: String,
    pub datacenter: String,
    pub bandwidth_capacity: u64,
    pub storage_capacity: u64,
    pub latitude: Option<f64>,
    pub longitude: Option<f64>,
    pub role: NodeRole,
    pub parent_node_id: Option<Uuid>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Heartbeat {
    pub node_id: Uuid,
    pub timestamp: DateTime<Utc>,
    pub load: f64,
    pub memory_usage: f64,
    pub bandwidth_usage: f64,
    pub connection_count: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchedulingRequest {
    pub client_ip: Option<String>,
    pub domain: String,
    pub path: String,
    pub strategy: SchedulingStrategy,
    pub content_type: Option<ContentType>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum SchedulingStrategy {
    WeightedRoundRobin,
    LeastConnections,
    GeoLocationPriority,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SchedulingDecision {
    pub node_id: Uuid,
    pub node_ip: IpAddr,
    pub region: String,
    pub confidence: f64,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum CacheEvictionPolicy {
    LRU,
    LFU,
    FIFO,
    TwoQueue,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheRule {
    pub id: Uuid,
    pub domain_config_id: Uuid,
    pub domain: String,
    pub path_pattern: String,
    pub eviction_policy: CacheEvictionPolicy,
    pub ttl_seconds: u64,
    pub priority: i32,
    pub enabled: bool,
    pub ignore_query_params: Vec<String>,
    pub vary_by_ua: bool,
    pub vary_by_referer: bool,
    pub max_size_bytes: u64,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DomainConfig {
    pub id: Uuid,
    pub domain: String,
    pub origin_server: String,
    pub cache_ttl: u64,
    pub enabled: bool,
    pub content_type: Option<ContentType>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CachedContent {
    pub key: String,
    pub domain: String,
    pub path: String,
    pub content_hash: String,
    pub size_bytes: u64,
    pub content_type: String,
    pub last_accessed: DateTime<Utc>,
    pub access_count: u64,
    pub created_at: DateTime<Utc>,
    pub expires_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum DeliveryMode {
    Push,
    Pull,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PreheatTask {
    pub id: Uuid,
    pub content_url: String,
    pub target_regions: Vec<String>,
    pub target_nodes: Vec<Uuid>,
    pub status: PreheatStatus,
    pub progress: f64,
    pub created_at: DateTime<Utc>,
    pub completed_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum PreheatStatus {
    Pending,
    InProgress,
    Completed,
    Failed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeMetrics {
    pub id: Uuid,
    pub node_id: Uuid,
    pub timestamp: DateTime<Utc>,
    pub qps: f64,
    pub bandwidth_usage: f64,
    pub cache_hit_rate: f64,
    pub origin_fetch_rate: f64,
    pub error_rate_4xx: f64,
    pub error_rate_5xx: f64,
    pub active_connections: u64,
    pub memory_usage: f64,
    pub cpu_usage: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Alert {
    pub id: Uuid,
    pub alert_type: AlertType,
    pub severity: AlertSeverity,
    pub node_id: Option<Uuid>,
    pub message: String,
    pub acknowledged: bool,
    pub resolved: bool,
    pub metadata: HashMap<String, String>,
    pub created_at: DateTime<Utc>,
    pub resolved_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum AlertType {
    NodeOffline,
    TrafficSpike,
    TrafficDrop,
    HighLoad,
    HighErrorRate,
    LowCacheHitRate,
    ImbalancedTraffic,
    CertificateExpiring,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum AlertSeverity {
    Critical,
    Warning,
    Info,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigVersion {
    pub id: Uuid,
    pub config_type: String,
    pub version: u64,
    pub data: serde_json::Value,
    pub created_by: Option<String>,
    pub description: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConfigDeployment {
    pub id: Uuid,
    pub config_version_id: Uuid,
    pub status: DeploymentStatus,
    pub target_nodes: Vec<Uuid>,
    pub canary_percent: u32,
    pub percentage: u32,
    pub success_count: u32,
    pub failure_count: u32,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub error_message: Option<String>,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum DeploymentStatus {
    Pending,
    InProgress,
    Completed,
    Failed,
    RolledBack,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TlsCertificate {
    pub id: Uuid,
    pub domain: String,
    pub certificate_pem: String,
    pub private_key_encrypted: String,
    pub issuer: String,
    pub not_before: DateTime<Utc>,
    pub not_after: DateTime<Utc>,
    pub auto_renew: bool,
    pub status: CertificateStatus,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum CertificateStatus {
    Active,
    Expiring,
    Expired,
    Revoked,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GeoLocation {
    pub country: String,
    pub region: String,
    pub city: String,
    pub latitude: f64,
    pub longitude: f64,
    pub timezone: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OperationLog {
    pub id: Uuid,
    pub operation_type: String,
    pub entity_type: String,
    pub entity_id: Option<Uuid>,
    pub operator: Option<String>,
    pub description: Option<String>,
    pub before_data: Option<serde_json::Value>,
    pub after_data: Option<serde_json::Value>,
    pub created_at: DateTime<Utc>,
}

impl Default for NodeStatus {
    fn default() -> Self {
        NodeStatus::Online
    }
}

impl Default for SchedulingStrategy {
    fn default() -> Self {
        SchedulingStrategy::GeoLocationPriority
    }
}

impl Default for CacheEvictionPolicy {
    fn default() -> Self {
        CacheEvictionPolicy::LRU
    }
}

impl Default for DeploymentStatus {
    fn default() -> Self {
        DeploymentStatus::Pending
    }
}

impl Default for CertificateStatus {
    fn default() -> Self {
        CertificateStatus::Active
    }
}

impl Default for AlertSeverity {
    fn default() -> Self {
        AlertSeverity::Warning
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum PreheatPlanStatus {
    Pending,
    Executing,
    Completed,
    Failed,
}

impl Default for PreheatPlanStatus {
    fn default() -> Self {
        PreheatPlanStatus::Pending
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HeatRecord {
    pub url: String,
    pub region: String,
    pub timestamp: DateTime<Utc>,
    pub access_count: u64,
}
