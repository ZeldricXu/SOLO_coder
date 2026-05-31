use serde::{Serialize, Deserialize};
use chrono::{DateTime, Utc};
use serde_json::Value;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum NetworkStatus {
    Online,
    Offline,
    Unknown,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum SyncStatus {
    Pending,
    Syncing,
    Synced,
    Failed,
    Expired,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CachedData {
    pub id: String,
    pub entity_type: String,
    pub entity_id: String,
    pub operation: String,
    pub payload: Value,
    pub idempotency_key: String,
    pub created_at: DateTime<Utc>,
    pub expires_at: Option<DateTime<Utc>>,
    pub sync_status: SyncStatus,
    pub sync_attempts: u32,
    pub last_sync_attempt: Option<DateTime<Utc>>,
    pub last_sync_error: Option<String>,
    pub synced_at: Option<DateTime<Utc>>,
    pub priority: u8,
    pub size_bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncProgress {
    pub total_pending: u64,
    pub total_synced: u64,
    pub total_failed: u64,
    pub current_syncing: Option<String>,
    pub sync_started_at: Option<DateTime<Utc>>,
    pub last_completed_at: Option<DateTime<Utc>>,
    pub estimated_remaining_seconds: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncPolicy {
    pub max_retries: u32,
    pub retry_delay_seconds: u64,
    pub batch_size: u32,
    pub max_parallel_syncs: u32,
    pub sync_interval_seconds: u64,
    pub enable_idempotency: bool,
    pub auto_clean_synced_hours: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheStatus {
    pub network_status: NetworkStatus,
    pub total_items: u64,
    pub pending_items: u64,
    pub synced_items: u64,
    pub failed_items: u64,
    pub total_size_bytes: u64,
    pub max_size_bytes: u64,
    pub usage_percent: f64,
    pub sync_progress: SyncProgress,
    pub sync_policy: SyncPolicy,
    pub oldest_pending_at: Option<DateTime<Utc>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheWriteRequest {
    pub entity_type: String,
    pub entity_id: String,
    pub operation: String,
    pub payload: Value,
    pub idempotency_key: Option<String>,
    pub ttl_seconds: Option<u64>,
    pub priority: Option<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheWriteResponse {
    pub cache_id: String,
    pub status: String,
    pub network_status: NetworkStatus,
    pub will_sync: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SyncResult {
    pub cache_id: String,
    pub success: bool,
    pub synced_at: Option<DateTime<Utc>>,
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheQuery {
    pub sync_status: Option<SyncStatus>,
    pub entity_type: Option<String>,
    pub entity_id: Option<String>,
    pub operation: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NetworkStatusEvent {
    pub previous_status: NetworkStatus,
    pub current_status: NetworkStatus,
    pub changed_at: DateTime<Utc>,
}

impl Default for SyncPolicy {
    fn default() -> Self {
        Self {
            max_retries: 5,
            retry_delay_seconds: 30,
            batch_size: 100,
            max_parallel_syncs: 5,
            sync_interval_seconds: 60,
            enable_idempotency: true,
            auto_clean_synced_hours: 24,
        }
    }
}

impl CachedData {
    pub fn new(
        entity_type: impl Into<String>,
        entity_id: impl Into<String>,
        operation: impl Into<String>,
        payload: Value,
        idempotency_key: Option<String>,
        ttl_seconds: Option<u64>,
        priority: Option<u8>,
    ) -> Self {
        let size_bytes = serde_json::to_vec(&payload).map(|v| v.len() as u64).unwrap_or(0);
        let idempotency_key = idempotency_key.unwrap_or_else(|| Uuid::new_v4().to_string());

        Self {
            id: Uuid::new_v4().to_string(),
            entity_type: entity_type.into(),
            entity_id: entity_id.into(),
            operation: operation.into(),
            payload,
            idempotency_key,
            created_at: Utc::now(),
            expires_at: ttl_seconds.map(|ttl| Utc::now() + chrono::Duration::seconds(ttl as i64)),
            sync_status: SyncStatus::Pending,
            sync_attempts: 0,
            last_sync_attempt: None,
            last_sync_error: None,
            synced_at: None,
            priority: priority.unwrap_or(50),
            size_bytes,
        }
    }

    pub fn is_expired(&self) -> bool {
        self.expires_at
            .map(|exp| exp <= Utc::now())
            .unwrap_or(false)
    }

    pub fn can_retry(&self, max_retries: u32) -> bool {
        self.sync_attempts < max_retries
    }

    pub fn mark_sync_attempt(&mut self) {
        self.sync_attempts += 1;
        self.last_sync_attempt = Some(Utc::now());
        self.sync_status = SyncStatus::Syncing;
    }

    pub fn mark_synced(&mut self) {
        self.sync_status = SyncStatus::Synced;
        self.synced_at = Some(Utc::now());
        self.last_sync_error = None;
    }

    pub fn mark_failed(&mut self, error: impl Into<String>) {
        self.last_sync_error = Some(error.into());
        self.sync_status = SyncStatus::Failed;
    }
}

impl Default for SyncProgress {
    fn default() -> Self {
        Self {
            total_pending: 0,
            total_synced: 0,
            total_failed: 0,
            current_syncing: None,
            sync_started_at: None,
            last_completed_at: None,
            estimated_remaining_seconds: None,
        }
    }
}

impl NetworkStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            NetworkStatus::Online => "online",
            NetworkStatus::Offline => "offline",
            NetworkStatus::Unknown => "unknown",
        }
    }
}
